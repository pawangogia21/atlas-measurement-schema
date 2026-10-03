# Atlas Measurement Schema: Units and Frame Conventions

This document specifies the units, coordinate systems, and frame-of-reference semantics that the Kits, Ingestion, Processing and the Measurement service agree on.

## Units

All dimensions and distances in the measurement schema are in **metres**. This applies to:
- `dimensions`: `lengthM`, `widthM`, `heightM`, `distanceM`
- `obb`: `centerWorld`, `halfExtentsM`
- `sigma`: `sigmaM`, `sigmaLengthM`, `sigmaWidthM`, `sigmaHeightM`, `sigmaDistanceM`
- `ci95`: `ci95M`, `ci95LengthM`, `ci95WidthM`, `ci95HeightM`, `ci95DistanceM`
- `overlay.keypoints`: `world`, `sigma`
- `depth` map: Float32 values in metres

Values are stored and compared as `NUMERIC(9,5)` in Postgres (metres with 1e-5 precision) and as Float32 in JSON and the `LiveMeasurement` serialisation.

## Coordinate Systems

### Global Frame

The global frame is **Y-up, right-handed, metres**. This matches the AR platform conventions:
- iOS: ARKit's world frame (Y-up, right-handed)
- Android: ARCore's world frame (Y-up, right-handed)

### Depth Map Orientation

The depth map is **row-major** (scanline order), with resolution that varies by platform:
- iOS LiDAR: 256×192
- ARCore depth-from-motion: 160×120 to 320×240
- ToF depth sensors: typically 240×180 or less

All values are Float32, in metres. The depth frame coordinate system and intrinsics scaling are handled by the platform adapter:
- The adapter normalises `confidence` to 0..1 (iOS: {0, 0.5, 1} → {0, 0.5, 1}; ARCore: 0-255 mapping by per-source table)
- The adapter scales `depthIntrinsics` (focal length `fx`, `fy` and principal point `cx`, `cy`) to match the depth resolution
  - iOS example: for a 1920×1440 image with LiDAR depth at 256×192, scale is (256/1920, 192/1440)

**Important:** The Kit receives intrinsics already scaled to the depth map resolution; raw image intrinsics are not passed to the core algorithm.

### Gravity and Support Planes

Gravity is a unit vector in world coordinates, derived from the IMU (or the AR platform's estimate). It points downward (negative Y).

Support planes are horizontal surfaces (e.g. tables, floors) detected by the platform, represented as their normal (pointing upward) and distance from the origin, in the world frame. The algorithm uses gravity to align the bounding box and compute the support plane estimate via RANSAC.

### Gravity-Aligned Bounding Box

The `obb` (oriented bounding box) has the following structure:
- **`centerWorld[3]`**: 3D position of the box centre in world coordinates (metres)
- **`halfExtentsM[3]`**: Half-widths (X, Y, Z) in metres, all non-negative
  - X, Z are horizontal (gravity-perpendicular)
  - Y is vertical (gravity-aligned)
- **`yawRad`**: Rotation around the Y axis, in radians [−π, π]
- **`supportPlaneY`**: Y coordinate of the support plane (the plane on which the box rests), in metres

The box is axis-aligned in the gravity frame: no roll or pitch, only yaw. This simplifies measurement, matches how users naturally measure furniture and parcels, and ensures consistency with the server's gravity-aligned representation.

## Frame of Reference and Session Binding

Two clients on the same AR session may have different world origins. The `frameOfReference` object identifies the coordinate frame so the server can relate a live hint to a scan's canonical frame.

### Fields

```json
{
  "frameOfReference": {
    "convention": "Y_UP_RIGHT_HANDED",
    "worldOriginId": "<optional platform-specific identifier>",
    "worldOriginEpoch": 123,
    "sessionId": "<session-uuid>"
  }
}
```

### Semantics

- **`convention`**: Always `"Y_UP_RIGHT_HANDED"` (reserved for future alternatives; the value is fixed in the v1 schema)
- **`worldOriginId`**: Optional platform-specific world-origin identifier (not used for reconciliation; present for diagnostics only)
- **`worldOriginEpoch`**: Integer, incremented each time the AR platform's world origin is reset (e.g. on iOS ARKit `worldOrigin` change, Android ARCore relocalization). Frames of reference are comparable only when `epoch` values match. A large epoch change indicates the AR system has lost global tracking and the previous coordinates are no longer valid relative to the world.
- **`sessionId`**: Changes when the AR session is interrupted and resumed (iOS `sessionInterruptionEnded`, Android recreation). Hints with different session IDs cannot be directly compared in 3D space.

### Association and Comparison

The server uses `frameOfReference` to determine whether two measurements can be directly compared in 3D:
1. **Seeded association** (client hint seeded a server measurement): pair by construction.
2. **Independent measurements, frame mapping available** (client and server have matching `sessionId` and `worldOriginEpoch`, and `transformToCanonical` exists): map the hint into the canonical frame and compare by geometry (IoU, distance, normals; see section 21.2 of the design).
3. **Dimension-only fallback** (mapping unavailable): compare dimensions only, frame-independent. Qualifies for `AGREE` or `MINOR_DIFF` only, never `MAJOR_DIFF`.

If neither the session nor the epoch match, no 3D comparison is possible, and the outcome is `NOT_COMPARABLE`.

## FrameBundle Contract

The `FrameBundle` is the input from the AR platform to the Kit's core algorithm. It carries one AR frame's data in a value type (never retained across frames, always copied or released immediately after `ingest()` returns).

### Fields

| Field | Type | Semantics |
|---|---|---|
| `timestamp` | Monotonic seconds (Double or UInt64) | Frame timestamp, used to match frames and depth maps (max skew 20 ms). Monotonic within a session. |
| `image` | Optional: YCbCr or BGRA + size | Camera image, optional. Used for edge refinement and keypoint visualisation; the core algorithm works without it. The Kit drops a reference to the platform image before returning from `ingest()`. |
| `depth` | Float32 map (width, height) + confidence, source, alignedToImage, intrinsics | **Metres, variable resolution, row-major.** Each pixel is depth in metres (or NaN/0 for invalid). `confidence[0..1]`, normalised by the adapter. `source`: `LIDAR`, `TOF`, `DEPTH_FROM_MOTION`, `NONE`. `alignedToImage`: boolean (false on ARCore if depth is at a different resolution). `depthIntrinsics` (`fx`, `fy`, `cx`, `cy`) already scaled to this depth map's resolution. |
| `intrinsics` | fx, fy, cx, cy, image width/height | Image-space intrinsics; depth intrinsics are separate (in the `depth` struct). Per-frame, so refocusing or zoom changes are captured. |
| `pose` | 4×4 matrix: camera-to-world | Column-major or row-major per platform convention. Transforms a point in camera space to world coordinates. ARKit and ARCore both use right-handed Y-up world frames. |
| `trackingState` | Enum | `NORMAL`, `LIMITED(reason)`, `NOT_AVAILABLE`. Used to gate algorithm execution; `LIMITED` may flag a quality issue. |
| `worldOriginEpoch` | Integer | Incremented on world-origin change (ARKit `worldOrigin`, ARCore relocalization). Frames with different epochs are in different coordinate systems. |
| `sessionId` | UUID | Changes when the AR session is interrupted and resumed. Used to bind hints to the scan's capture session. |
| `planes` (optional) | Platform-detected planes | Extents and normals; speeds up support-plane estimation on tier B. Platform-specific representation (ARKit `ARPlaneAnchor`, ARCore `Plane`); the adapter normalises them. |
| `gravity` | Unit vector (X, Y, Z) | Gravity vector (pointing downward, typically ~[0, −1, 0] in world frame), derived from IMU. Provided by the platform or computed from the pose if absent. |
| `deviceInfo` | model, OS, thermal state | Device identifiers for bias tables and capability detection. |

### Adapter Responsibilities

The AR platform adapter (e.g., `ARKitFrameAdapter`, `ArCoreFrameAdapter`) is responsible for:
1. **Copying depth into a pooled buffer** (3–4 slots, sized per device) and releasing the platform's depth image immediately.
2. **Normalising `confidence`** to 0..1 by the platform-specific mapping (iOS: 0/1/2 → 0/0.5/1; ARCore: 0–255 table per source).
3. **Scaling `depthIntrinsics`** to the actual depth map resolution (e.g., 1920×1440 image with 256×192 depth → scale factors 256/1920 and 192/1440).
4. **Never retaining references** to platform frames (`ARFrame`, `Image`) after returning from the adapter method.
5. **Handling latest-wins semantics**: when frames arrive faster than the core algorithm consumes them, drop older frames and process only the latest on each update.

### Scale-Source Confidence Cap

When the depth source is `VIO_METRIC` (visual-inertial odometry scale, tier B), the Kit **caps the composite confidence at 0.6** in the output `LiveMeasurement`. This reflects the inherent uncertainty of scale-from-motion. Sensor-metric sources (LiDAR, ToF) have no cap on the scale source alone, but the composite confidence accounts for all sources of error (depth noise, fit residual, coverage, tracking quality, thermal).

## Uncertainty: Sigma and CI95

Uncertainty is first-class: every dimension carries a 1-sigma estimate and a 95% confidence interval (CI95).

- **`sigmaM`** (1-sigma): Root-sum-square of depth noise (range-dependent), fit residual, view coverage and tracking quality, in metres.
- **`ci95M`** (95% confidence interval): Approximately `2 * sigmaM` for a normal distribution, clamped by the measurement's stability.

The UI should show ranges ("1.20 m ± 0.03 m" or "1.17 m to 1.23 m") and state (`STABLE` for settling, `DEGRADED` for low confidence), not bare point numbers. Confidence (`0..1`) is a composite score combining coverage, tracking, depth quality and thermal state.

## Reference Implementation

For the precise definitions of noise models, accumulation, reduction order, RNG, PCA, RANSAC and other algorithm details, see `algorithm-spec.md`.

