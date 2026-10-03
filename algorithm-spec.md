# Measurement algorithm specification (tier A and shared math)

Status: version 1.0, normative. Source: design section 31.2 (rules) and section 24 item 5 (conformance vectors). The Java reference implementation is `reference/src/main/java/com/atlas/measurement/algo/`; the vectors that pin this text are `vectors/conformance/1.0.0/`. Where this text and the reference disagree, that is a bug in one of them; fix it and publish new vectors.

Two Kit cores (Swift, Kotlin) must reproduce the vectors. The cores are independent implementations, so everything that influences a result is pinned here: precision, order of operations, RNG, tie-breaking, solver sweeps, limits and signs.

## 1. Scope

In scope: the shared primitives (section 3) and the conformance pipeline (section 5), which turns one depth frame into a support plane and a gravity-aligned box. Out of scope, and not covered by any vector:

- adapters (ARKit, ARCore): copying depth, platform depth resolution, confidence mapping to 0..1, intrinsics scaling to the depth grid (design 17.2, 31.1);
- tier B and tier C algorithms (plane raycast, depth-from-motion);
- multi-frame fusion, filtering, sigma and confidence formulas, state machine, thermal handling;
- imagery.

The pipeline in section 5 is a **conformance pipeline**: it pins the shared math so the two cores can be compared bit-for-bit-ish. It is not an accuracy claim. Accuracy is gated on real devices (design 24 item 3, 31.3).

A change to any rule or constant in this file changes results, so it is a new spec version and a new vector release (design 31.4); it also bumps the algorithm major when it changes field behaviour (design 21.4).

## 2. Conventions

| Item | Rule |
|---|---|
| Precision | Compute and accumulate in Float64 (IEEE-754 binary64). Inputs are Float32 and are widened to Float64 on read. Round to Float32 only when writing an output value. |
| Fused multiply-add | Forbidden. Every `a * b + c` is a rounded multiply followed by a rounded add. Disable contraction (`-ffp-contract=off` in native code; the JVM and Swift/Kotlin do not contract by default). |
| Summation | Kahan summation (3.5), in the order stated for each sum. Plain accumulation is used only for integer counts. |
| Reduction order | Fixed, sequential, ascending index. No parallel, SIMD-reassociated or auto-vectorised reduction in the reference path. A faster path (Accelerate) is allowed only if it matches the scalar path within the vector tolerance. |
| Evaluation order | Every expression is evaluated strictly left to right as written, one rounded operation at a time (`a * b / c` is `(a * b) / c`). |
| Pixel order | Row-major: `index = v * width + u`, `v` = row from the top, `u` = column from the left. "Point order" means ascending pixel index of the valid pixels. |
| NaN | A NaN or infinite depth or confidence is invalid. All range tests are written so that NaN fails them (`!(x >= lo && x <= hi)`). |
| Math functions | `sqrt` is exact IEEE. `atan2`, `sin`, `cos` may differ by one ulp between platforms; this is covered by the vector tolerances. |
| Angles | Radians. `yawRad` is the rotation about +Y (counter-clockwise seen from above, +Y toward the viewer). |

## 3. Primitives

### 3.1 SplitMix64

64-bit state, arithmetic modulo 2^64:

```
next():
    state = state + 0x9E3779B97F4A7C15
    z = state
    z = (z xor (z >> 30)) * 0xBF58476D1CE4E5B9
    z = (z xor (z >> 27)) * 0x94D049BB133111EB
    return z xor (z >> 31)
```

`>>` is a logical (unsigned) shift. The generator is created with `state = seed`. Reference outputs for `seed = 0`: `0xE220A8397B1DCDAF`, `0x6E789E6AA1B965F4`, `0x06C45D188009454F`. More seeds are in `primitives.json` (`splitmix64`).

xorshift128+ was rejected (weak low bits).

### 3.2 Per-window seed

For the frame window with index `windowIndex` (unsigned 64-bit, the Kit's window counter): `rng = SplitMix64(seed = windowIndex xor 0x9E3779B97F4A7C15)`. A window is one depth frame in the conformance vectors; the generator is created once per window and consumed in the order stated in section 5.2. Check: window 0 yields `0x6E789E6AA1B965F4` first (the second output of seed 0).

### 3.3 Bounded integers by rejection sampling

`nextInt(n)`, `1 <= n <= 2^31 - 1`, uniform in `[0, n)`; never plain modulo.

```
threshold = (2^64) mod n          // computed as ((2^64 - n) mod n) with unsigned 64-bit arithmetic
loop:
    r = next()                    // unsigned
    if r >= threshold: return r mod n   // unsigned remainder
```

A rejected `r` (below `threshold`) is discarded and the loop draws again, so the number of draws consumed is data-independent only in expectation; the conformance vectors fix the exact sequence. `primitives.json` (`boundedInt`) lists the thresholds and the first 16 draws for several `n`.

### 3.4 Lower median

For `n` values with positions `0..n-1`: stable-sort the positions by value ascending, ties by ascending position (pixel index, row-major). The lower median is the element at sorted index `(n - 1) / 2` (integer division, 0-based); both its value and its position are defined. Comparisons use `<` and `>` (so `-0.0` and `0.0` tie). Examples: `[3, 1, 2, 1]` gives position 3, value 1; `[9, 8, 7, 6, 5, 4]` gives position 3, value 6.

### 3.5 Kahan summation

```
sum = 0; c = 0
add(x):  y = x - c;  t = sum + y;  c = (t - sum) - y;  sum = t
```

Example: `[1e16, 1 x10, -1e16]` sums to exactly `10.0`.

### 3.6 Jacobi eigen-solver, symmetric 3x3, Float64

Input: symmetric `A` (only the upper triangle is read for the off-diagonal test but the full matrix is kept symmetric). `V = I`.

```
for sweep = 1 .. 50:
    off  = |A01| + |A02| + |A12|
    diag = |A00| + |A11| + |A22|
    if off <= 1e-12 * diag: stop            // tested before every sweep, including the first
    for (p, q) in [(0,1), (0,2), (1,2)]:    // fixed pair order
        if A[p][q] == 0: continue
        theta = (A[q][q] - A[p][p]) / (2 * A[p][q])
        t = sign(theta) / (|theta| + sqrt(theta*theta + 1))      // sign(0) = +1
        c = 1 / sqrt(t*t + 1);  s = t * c
        A[p][p] = A[p][p] - t * A[p][q]
        A[q][q] = A[q][q] + t * A[p][q]
        A[p][q] = A[q][p] = 0
        r = the index that is neither p nor q
        arp = A[r][p]; arq = A[r][q]
        A[r][p] = A[p][r] = c * arp - s * arq
        A[r][q] = A[q][r] = c * arq + s * arp
        for k in 0..2:
            vkp = V[k][p]; vkq = V[k][q]
            V[k][p] = c * vkp - s * vkq
            V[k][q] = c * vkq + s * vkp
```

The sweep count is part of the result (`jacobi3.sweeps`, exact). The eigenvalues are `A[i][i]`; the eigenvector of `A[i][i]` is column `i` of `V`.

Ordering and sign: eigenvalues are sorted descending, ties keep ascending original index. Each eigenvector is sign-fixed so that its largest-magnitude component is positive; equal magnitudes: the lowest index wins (compare with strict `>`).

### 3.6.1 Worked example

`A = [[4, 1, 0.5], [1, 3, 0.2], [0.5, 0.2, 2]]` converges in 3 sweeps to eigenvalues `4.721570077747949`, `2.3983430193369975`, `1.8800869029150529`; first eigenvector `(0.8388647614682249, 0.5095210652088142, 0.19155729188765627)`. Diagonal matrices (`diag(2, 2, 1)`) and the zero matrix converge in 0 sweeps with identity eigenvectors.

## 4. Constants and noise model

### 4.1 Constants

Changing a value is a new spec version.

| Name | Value | Use |
|---|---|---|
| `zMin`, `zMax` | 0.1 m, 5.0 m | valid depth range (inclusive) |
| `minConfidence` | 0.5 | valid confidence is `>= 0.5` |
| `minPoints` | 50 | fewer valid points: `INSUFFICIENT_POINTS` |
| `ransacIterations` | 64 | fixed, never data-dependent |
| `inlierSigmas` | 5 | inlier and object threshold in noise sigmas |
| `planeInlierFraction` | 0.30 | the plane must hold at least 30 percent of the valid points |
| `minObjectPoints` | 20 | fewer object points: `NO_OBJECT` |
| `topBandM` | 0.02 m | top-face band (5.4) |

### 4.2 Depth noise model

`sigmaZ(z) = 0.001 + 0.0015 * z * z` metres (z in metres). The inlier threshold of a point is `thr(z) = inlierSigmas * sigmaZ(z)` with the point's own depth `z`. This is a deliberately simple model for the shared pipeline; per-device bias and the production sigma formula are Kit-specific and out of scope.

## 5. Conformance pipeline

### 5.1 Inputs, valid pixels and unprojection

Input: `width`, `height`; Float32 `depth[width*height]` in metres and Float32 `confidence[width*height]` in 0..1 (the adapter's job, already done); depth-grid intrinsics `fx, fy, cx, cy`; camera-to-world `pose` as 16 numbers, **row-major** 4x4; `windowIndex`. World is Y-up, right-handed, metres, gravity `(0, -1, 0)` (the conformance pipeline assumes gravity-aligned input; `docs/conventions.md`). The camera looks along its own `-Z` with `+X` right and `+Y` up (ARKit/ARCore convention); `depth` is the distance along the optical axis (z-depth), not along the ray.

For each pixel in row-major order, widen `z = depth[i]`, `conf = confidence[i]`. The pixel is valid iff `z >= zMin && z <= zMax && conf >= minConfidence` (NaN fails). For a valid pixel (indices `u`, `v` are integers, no half-pixel offset):

```
xc =  (u - cx) * z / fx
yc = -(v - cy) * z / fy
zc = -z
xw = pose[0]*xc + pose[1]*yc + pose[2]*zc  + pose[3]
yw = pose[4]*xc + pose[5]*yc + pose[6]*zc  + pose[7]
zw = pose[8]*xc + pose[9]*yc + pose[10]*zc + pose[11]
```

evaluated left to right as written (`(p0*xc + p1*yc) + p2*zc` then `+ p3`). The valid points, in point order, form `P[0..n-1]` with `(xw, yw, zw)` and their depth `z`. `validPixels = n`. If `n < minPoints`: status `INSUFFICIENT_POINTS`, stop.

### 5.2 Support plane (horizontal plane `y = c`), RANSAC

1. Sort the `yw` values ascending; `qY` is the value at sorted index `(n - 1) / 5` (integer division): candidates must lie in the lowest 20 percent of heights.
2. `rng = SplitMix64(windowIndex xor 0x9E3779B97F4A7C15)`. Repeat `ransacIterations` times: draw `idx = rng.nextInt(n)` (**always**, even when the candidate is skipped); candidate `c = P[idx].yw`; if `c > qY`, skip; otherwise `inliers = count of j in point order with |yw_j - c| <= thr(z_j)`. Keep the candidate with the strictly greatest `inliers`; ties keep the earliest iteration.
3. If no candidate was kept, or `10 * inliers < 3 * n` (less than 30 percent): status `NO_SUPPORT_PLANE`, stop; `supportPlaneInliers` then reports the best candidate's inlier count (0 if none) and `objectPoints` is 0.
4. Refit: collect `yw_j` of the best candidate's inliers in point order; `supportPlaneY` is their **lower median** (3.4). `supportPlaneInliers` is the number of points with `|yw_j - supportPlaneY| <= thr(z_j)` (recomputed with the refit value).

### 5.3 Object points

Object points are the points with `yw_j - supportPlaneY > thr(z_j)`, in point order; `objectPoints` is their count. If `objectPoints < minObjectPoints`: status `NO_OBJECT`, stop (the support plane is still reported).

### 5.4 Height and top face

`h_j = yw_j - supportPlaneY`. `hMax` is the maximum `h_j` over the object points; `heightM = hMax`. The top face is the object points with `h_j >= hMax - topBandM`, in point order. Only the top face is used for the footprint, so visible side faces do not bias the yaw.

### 5.5 Footprint: PCA, yaw, extents

Over the top-face points (count `m`), in point order:

1. `mx = Kahan-sum(xw) / m`, `mz = Kahan-sum(zw) / m`.
2. `dx = xw - mx`, `dz = zw - mz`; `cxx = Kahan-sum(dx*dx) / m`, `cxz = Kahan-sum(dx*dz) / m`, `czz = Kahan-sum(dz*dz) / m` (population covariance).
3. Solve (3.6) the 3x3 matrix `[[cxx, 0, cxz], [0, 0, 0], [cxz, 0, czz]]`. The first (largest) eigenvector is `e = (ex, ey, ez)` after the sign rule.
4. `yaw = atan2(-ez, ex)`; if `yaw > pi/2` subtract `pi`; if `yaw <= -pi/2` add `pi`. So `yawRad` is in `(-pi/2, pi/2]`, defined modulo `pi` (a box is symmetric under a half turn).
5. Local axes in the `(x, z)` plane: `u = (cos(yaw), -sin(yaw))`, `w = (-u.z, u.x)`.
6. Over the top-face points: `s = xw*u.x + zw*u.z`, `t = xw*w.x + zw*w.z`; take `sMin, sMax, tMin, tMax` (running min and max in point order).

### 5.6 Outputs

```
lu = sMax - sMin;  lw = tMax - tMin;  sc = (sMin + sMax) / 2;  tc = (tMin + tMax) / 2
halfExtentsM = (lu / 2, hMax / 2, lw / 2)                 // local X (u), Y, local Z (w)
centerWorld  = (sc*u.x + tc*w.x,  supportPlaneY + hMax/2,  sc*u.z + tc*w.z)
lengthM = max(lu, lw)    widthM = min(lu, lw)    heightM = hMax
```

Output fields, each rounded to Float32 at the end: `status` (enum), `counts` (`validPixels`, `supportPlaneInliers`, `objectPoints`; integers), `supportPlaneY`, `obb.centerWorld[3]`, `obb.halfExtentsM[3]`, `obb.yawRad`, `dimensions.lengthM|widthM|heightM`. For a non-`OK` status only `status` and `counts` are compared. Statuses: `OK`, `INSUFFICIENT_POINTS`, `NO_SUPPORT_PLANE`, `NO_OBJECT`.

The `OBJECT_BOX` `LiveMeasurement` fields (sigma, ci95, confidence, flags, overlay) are not derived here (out of scope, section 1).

### 5.7 Worked example

`S01-box-60x40x30-64x48` (64x48 grid, `fx = fy = 57.6`, `cx = 31.5`, `cy = 23.5`, `windowIndex = 1`). Pixel `(u, v) = (40, 10)` has `z = 3.3125786781311035`; then `xc = 0.4888353952103191`, `yc = 0.7763856276869774`, `zc = -3.3125786781311035` and, with the scene's pose, `(xw, yw, zw) = (0.6840128212832304, -4.044025936345008e-08, -2.7075776000727028)` (a floor point). Expected output of the whole scene: `status OK`, `validPixels 3008`, `supportPlaneInliers 2755`, `objectPoints 253`, `dimensions 0.5881897 x 0.40042025 x 0.30000004`, `yawRad 0.3183004` (analytic truth 0.60 x 0.40 x 0.30, yaw 0.35; the gap is the 2 cm pixel footprint of the 64x48 grid, not an accuracy claim).

## 6. Vector tolerances

Per field, in the vector manifest (`manifest.json`, `vectors[].tolerances`), design 24 item 5:

| Field kind | Noise-free scenes | Seeded noisy scenes |
|---|---|---|
| Lengths in metres (`supportPlaneY`, `centerWorld`, `halfExtentsM`, `dimensions.*`) | 1 mm | 3 mm |
| `yawRad` (compared modulo pi) | 0.002 rad | 0.006 rad |
| Counts and enums (`status`, `counts.*`, `sweeps`, integers, hex strings) | exact | exact |
| `kahanSum.sum` | bit-exact | n/a |
| Jacobi eigenvalues and eigenvectors (primitives) | 1e-9 | n/a |

The angle tolerances are an addition: the design gives metre tolerances only; 0.002 and 0.006 rad are about 1 mm and 3 mm at the 0.5 m half-extent scale. A vector or tolerance change needs `qaAgent` approval and a new release (design 31.4).

## 7. Vector files (summary)

`depth.f32` and `confidence.f32`: Float32 little-endian, row-major, `width*height` values (a NaN depth is invalid; confidence 1.0 for the whole frame is written as `{"constant": 1.0}` in `input.json` instead of a file). The pipeline does not read the generator's analytic `truth` block. Scenes: noise-free (`S01` to `S04`, two resolutions), seeded noisy (`N01` to `N05`, including dropouts) and degenerate (`D01` to `D05`, each hitting one non-`OK` status). The generator (`vectorgen`) is reproducible; its noise (Box-Muller over SplitMix64, `StrictMath`) is not part of this spec because the noisy depth values are stored in the vectors, not regenerated by the Kits.
