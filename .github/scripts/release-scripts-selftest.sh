#!/usr/bin/env bash
# AT-16 S1/S3/S11: proves the release helper scripts (verify-tag-provenance.sh, release-preflight.sh,
# publish-swift-package.sh, dispatch-kits.sh) against throwaway git repositories and a local HTTP stub, no network needed.
set -euo pipefail
SCRIPTS="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
T="$(mktemp -d)"
STUB_PID=""
stop_stub() { if [ -n "$STUB_PID" ]; then kill "$STUB_PID" 2>/dev/null || true; wait "$STUB_PID" 2>/dev/null || true; STUB_PID=""; fi; }
trap 'stop_stub; rm -rf "$T"' EXIT
ok() { echo "ok: $1"; }
fail() { echo "SELFTEST FAIL: $1" >&2; exit 1; }
expect_fail() { # description command...
  local d="$1"; shift
  if "$@" >"$T/out" 2>&1; then cat "$T/out"; fail "$d (expected failure, got success)"; fi
  ok "$d"
}
export GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_SYSTEM=/dev/null
export GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t

# ---- S1 provenance -------------------------------------------------------------------------------------------------
git init -q --bare -b main "$T/origin.git"
git clone -q "$T/origin.git" "$T/w" 2>/dev/null
( cd "$T/w" && git checkout -q -b main && echo a > f && git add f && git commit -q -m a && git push -q origin main \
  && git checkout -q -b feature && echo b > f && git commit -qam b && git push -q origin feature )
git clone -q "$T/origin.git" "$T/onmain"
( cd "$T/onmain" && git checkout -q main && "$SCRIPTS/verify-tag-provenance.sh" >/dev/null ) || fail "a commit on main must pass the provenance check"
ok "a commit on main passes the provenance check"
git clone -q "$T/origin.git" "$T/offmain" && ( cd "$T/offmain" && git checkout -q feature )
expect_fail "a tag on a commit that is not on main fails the provenance check" bash -c "cd '$T/offmain' && '$SCRIPTS/verify-tag-provenance.sh'"
git clone -q --depth 1 "file://$T/origin.git" "$T/shallow"
expect_fail "a shallow checkout fails the provenance check (cannot prove anything)" bash -c "cd '$T/shallow' && '$SCRIPTS/verify-tag-provenance.sh'"

# ---- local HTTP stub for the registry and the dispatch API ---------------------------------------------------------
cat > "$T/stub.py" <<'PY'
import http.server, os, sys
PRESENT = set(filter(None, os.environ.get("STUB_PRESENT", "").split(",")))
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.headers.get("Authorization") is None:
            self.send_response(401); self.end_headers(); return
        art = self.path.rsplit("/", 1)[-1]
        self.send_response(200 if any(art.startswith(p + "-") for p in PRESENT) else 404); self.end_headers()
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        bad = self.path.split("/")[3] == os.environ.get("STUB_BAD_KIT", "-")
        self.send_response(404 if bad else 204); self.end_headers()
        if bad: self.wfile.write(b'{"message":"Not Found"}')
    def log_message(self, *a): pass
http.server.HTTPServer(("127.0.0.1", int(sys.argv[1])), H).serve_forever()
PY
start_stub() { # port present bad-kit
  stop_stub
  STUB_PRESENT="$2" STUB_BAD_KIT="${3:--}" python3 "$T/stub.py" "$1" & STUB_PID=$!
  for _ in 1 2 3 4 5 6 7 8 9 10; do curl -s -o /dev/null "http://127.0.0.1:$1/" && break; sleep 0.3; done
}
PORT=$((20000 + RANDOM % 20000))
export GITHUB_TOKEN=tok GITHUB_ACTOR=bot GITHUB_REPOSITORY=o/r PACKAGES_BASE_URL="http://127.0.0.1:$PORT" GITHUB_OUTPUT="$T/gho"

# ---- S11 preflight -------------------------------------------------------------------------------------------------
: > "$T/gho"; start_stub "$PORT" ""
"$SCRIPTS/release-preflight.sh" 9.9.9 >/dev/null
grep -qx 'maven_state=none' "$T/gho" || fail "preflight: nothing published must give none"
ok "preflight: nothing published -> none"
: > "$T/gho"
start_stub "$PORT" "atlas-measurement-schema-types,atlas-measurement-schema-reference,atlas-measurement-schema-kotlin,atlas-measurement-vectors,atlas-measurement-schema-codegen"
"$SCRIPTS/release-preflight.sh" 9.9.9 >/dev/null
grep -qx 'maven_state=complete' "$T/gho" || fail "preflight: everything published must give complete"
ok "preflight: everything published -> complete (re-run skips the deploy)"
start_stub "$PORT" "atlas-measurement-schema-types"
expect_fail "preflight: a half-published release fails with a message" "$SCRIPTS/release-preflight.sh" 9.9.9
grep -q 'half published' "$T/out" || fail "preflight: the message must name the problem"
ok "preflight: the message names the problem"
expect_fail "preflight: an unreachable registry fails closed" env PACKAGES_BASE_URL=http://127.0.0.1:1 "$SCRIPTS/release-preflight.sh" 9.9.9

# ---- S3/S11 Swift publish ------------------------------------------------------------------------------------------
mkdir -p "$T/pkg/Sources" && echo 'let x = 1' > "$T/pkg/Sources/a.swift" && echo '// swift-tools-version: 5.9' > "$T/pkg/Package.swift"
mkdir -p "$T/gh/o" && git init -q --bare -b main "$T/gh/o/atlas-measurement-schema-swift.git"
export GITHUB_REPOSITORY_OWNER=o SWIFT_REPO_URL_BASE="file://$T/gh" SWIFT_REPO_TOKEN=swifttok
SWIFT_REMOTE="$T/gh/o/atlas-measurement-schema-swift.git"
"$SCRIPTS/publish-swift-package.sh" "$T/pkg" 1.0.0 >/dev/null 2>&1
git -C "$SWIFT_REMOTE" rev-parse -q --verify refs/tags/v1.0.0 >/dev/null || fail "swift: first publish must push the tag"
ok "swift: first publish pushes the content and the tag"
[ "$(git -C "$SWIFT_REMOTE" show main:Sources/a.swift)" = 'let x = 1' ] || fail "swift: the content must be on the default branch"
ok "swift: the content is on the default branch"
out="$("$SCRIPTS/publish-swift-package.sh" "$T/pkg" 1.0.0 2>&1)"
case "$out" in *'nothing to do'*) ok "swift: a re-run with identical content is a no-op" ;; *) fail "swift: a re-run must be a no-op" ;; esac
echo 'let x = 2' > "$T/pkg/Sources/a.swift"
expect_fail "swift: the same tag with different content fails (a release is never moved)" "$SCRIPTS/publish-swift-package.sh" "$T/pkg" 1.0.0
# the tag is missing but the release commit is there (a run that stopped between the push and the tag)
"$SCRIPTS/publish-swift-package.sh" "$T/pkg" 1.0.1 >/dev/null 2>&1
git -C "$SWIFT_REMOTE" tag -d v1.0.1 >/dev/null
out="$("$SCRIPTS/publish-swift-package.sh" "$T/pkg" 1.0.1 2>&1)"
case "$out" in *'only the tag is missing'*) ;; *) fail "swift: a run that stopped before the tag must only add the tag" ;; esac
git -C "$SWIFT_REMOTE" rev-parse -q --verify refs/tags/v1.0.1 >/dev/null || fail "swift: the tag must be re-created"
ok "swift: a run that stopped before the tag only adds the tag"
[ "$(git -C "$SWIFT_REMOTE" rev-list --count main)" = 2 ] || fail "swift: no duplicate release commit expected"
ok "swift: no duplicate release commit"
expect_fail "swift: a missing repository fails closed" env SWIFT_REPO=o/does-not-exist "$SCRIPTS/publish-swift-package.sh" "$T/pkg" 1.0.2
expect_fail "swift: no token fails closed" env -u SWIFT_REPO_TOKEN "$SCRIPTS/publish-swift-package.sh" "$T/pkg" 1.0.2
if grep -rq 'swifttok' "$T/gh" 2>/dev/null; then fail "swift: the token reached the remote"; fi

# ---- S3 dispatch ---------------------------------------------------------------------------------------------------
export GITHUB_API_URL="http://127.0.0.1:$PORT" KIT_DISPATCH_TOKEN=kittok
start_stub "$PORT" ""
"$SCRIPTS/dispatch-kits.sh" 1.0.0 kit-a kit-b >/dev/null || fail "dispatch: both Kits must be notified"
ok "dispatch: both Kits notified"
start_stub "$PORT" "" kit-a
expect_fail "dispatch: one failing Kit fails the run" "$SCRIPTS/dispatch-kits.sh" 1.0.0 kit-a kit-b
grep -q 'dispatched .* to o/kit-b' "$T/out" || fail "dispatch: the other Kit must still be notified"
ok "dispatch: the other Kit was still notified"
expect_fail "dispatch: no token fails closed" env -u KIT_DISPATCH_TOKEN "$SCRIPTS/dispatch-kits.sh" 1.0.0 kit-a
echo "release scripts selftest: PASS"
