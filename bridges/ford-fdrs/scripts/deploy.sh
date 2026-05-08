#!/usr/bin/env bash
# Deploy the futureai-fdrs-layer2-bridge bundle into FDRS's OSGi container.
#
# Manual-only: this script does NOT auto-run from CI. It is destructive
# to FDRS's bundle set; run it only when:
#   (1) a build exists at build/jar/futureai-fdrs-layer2-bridge-<ver>.jar
#   (2) FDRS is not in the middle of a vehicle session
#   (3) you can tolerate a FDRS restart
#
# What it does:
#   - Copies the JAR into /c/Program Files (x86)/Ford Motor Company/FDRS/bundle/
#   - Prints a manual-restart instruction
#   - Prints tail-log commands to verify the bundle loaded
#
# What it does NOT do:
#   - Restart FDRS (user-action; the VCM3 session needs to be re-established after restart)
#   - Clean any Felix cache
#   - Remove prior versions of this bundle (caller's responsibility via --replace)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FDRS_BUNDLE_DIR="/c/Program Files (x86)/Ford Motor Company/FDRS/bundle"
FDRS_RUNTIME_LOG="/c/Users/$USER/.olympus/fdrs/fdrs-runtime.log"

REPLACE=0
if [[ "${1:-}" == "--replace" ]]; then REPLACE=1; fi

JAR_PATH="$(ls -t "$ROOT"/build/jar/futureai-fdrs-layer2-bridge-*.jar 2>/dev/null | head -1 || true)"
if [[ -z "$JAR_PATH" ]]; then
  echo "ERROR: no built JAR in $ROOT/build/jar/ — run scripts/build.sh first" >&2
  exit 1
fi

JAR_NAME="$(basename "$JAR_PATH")"
DEST="$FDRS_BUNDLE_DIR/$JAR_NAME"

if [[ $REPLACE -eq 1 ]]; then
  echo ">>> Removing prior futureai bundles (--replace)"
  rm -f "$FDRS_BUNDLE_DIR"/futureai-fdrs-layer2-bridge-*.jar
fi

if [[ -e "$DEST" && $REPLACE -eq 0 ]]; then
  echo "ERROR: $DEST already exists — re-run with --replace to overwrite" >&2
  exit 1
fi

echo ">>> Copying $JAR_NAME into FDRS bundle/"
cp -v "$JAR_PATH" "$DEST"

cat <<EOF

>>> DEPLOYED. Next steps (manual):

  1. Close FDRS cleanly (File → Exit or Alt-F4 — avoid taskkill).
  2. Relaunch FDRS.exe.
  3. In a separate terminal, tail the runtime log and grep for our bundle:

       tail -f "$FDRS_RUNTIME_LOG" | grep -i futureai

     You should see one line:
       futureai-fdrs-layer2-bridge v0.1.0 starting; bundleId=<N>
     ... and optionally:
       probe: OSGICommandInvoker service bound ok; class=...

  4. If no "futureai" line appears after 90 s of FDRS being ready, the
     bundle did NOT load. Grep error.log for "futureai":
       grep futureai "/c/Program Files (x86)/Ford Motor Company/FDRS/error.log"

  5. To roll back, run:
       rm "$DEST"
     then restart FDRS.

EOF
