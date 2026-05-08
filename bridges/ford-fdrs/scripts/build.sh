#!/usr/bin/env bash
# Build the futureai-fdrs-layer2-bridge OSGi bundle.
#
# Uses FDRS's shipped JDK 17 and Ford's own bundles as compile classpath —
# no host-wide Java toolchain dependency. Output:
#   build/jar/futureai-fdrs-layer2-bridge-<version>.jar
#
# Path handling: javac.exe is a native Windows binary. Under git-bash the
# semicolon-separated classpath confuses MSYS path translation, so we
# explicitly cygpath -w each path before handing it to javac.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FDRS="/c/Program Files (x86)/Ford Motor Company/FDRS"
JDK="$FDRS/3rdParty/java17/bin"

# Resolve Ford OTX bundles by glob (version-agnostic). Different FDRS
# installs ship different bundle versions (Justin's McGraw bench: 40.23.10
# / 72.33.28 vs the previous 40.24.26 / 72.34.72 hardcoded here). Globbing
# lets the build adapt to whichever versions are present without per-tech
# patches. Each glob must resolve to exactly ONE file — multiple matches
# means an unexpected install state we'd rather fail than guess.
resolve_one() {
  local pattern="$1"
  # shellcheck disable=SC2207  # intentional split on filenames (no spaces in Ford paths)
  local matches=( $(compgen -G "$pattern" || true) )
  if [[ ${#matches[@]} -eq 0 ]]; then
    echo "ERROR: no JAR matches pattern: $pattern" >&2
    echo "       Is FDRS installed at the expected path?" >&2
    return 1
  fi
  if [[ ${#matches[@]} -gt 1 ]]; then
    echo "ERROR: multiple JAR matches for pattern: $pattern" >&2
    printf '         %s\n' "${matches[@]}" >&2
    echo "       Expected exactly one. Resolve the duplicate before retrying." >&2
    return 1
  fi
  echo "${matches[0]}"
}

# Bundle name patterns · resolved at build-time against the local FDRS.
# common.core was added in PR#H for GetServiceBulletinsForDTCsAdapter which
# constructs com.ford.etis.vehicle.beans.evis.DTC instances; that bean
# lives in common.core (Export-Package), not services.vehicle.
PATTERNS=(
  "$FDRS/bin/org.apache.felix.main-*.jar"
  "$FDRS/bundle/com.ford.otx.command-*.jar"
  "$FDRS/bundle/com.ford.otx.command.invoker-*.jar"
  "$FDRS/bundle/com.ford.otx.services.vehicle-*.jar"
  "$FDRS/bundle/com.ford.otx.services.user-*.jar"
  "$FDRS/bundle/com.ford.otx.common.measurement.management-*.jar"
  "$FDRS/bundle/com.ford.otx.common.core-*.jar"
)

CP_JARS=()
for pat in "${PATTERNS[@]}"; do
  # `command.invoker` glob also matches `command`; require the literal-prefix
  # filename so command-* and command.invoker-* don't cross-match.
  base="$(basename "$pat" | sed 's/-\*\.jar$//')"
  resolved="$(resolve_one "$pat")"
  if [[ -z "$resolved" ]]; then exit 1; fi
  resolved_base="$(basename "$resolved" | sed -E 's/-[0-9].+\.jar$//')"
  if [[ "$resolved_base" != "$base" ]]; then
    echo "ERROR: glob $pat resolved to $resolved (base=$resolved_base, expected $base)" >&2
    echo "       This usually means a sibling bundle's name overlaps; refine the pattern." >&2
    exit 1
  fi
  CP_JARS+=("$resolved")
done
if [[ ! -x "$JDK/javac.exe" ]]; then
  echo "ERROR: FDRS JDK not at $JDK" >&2
  exit 1
fi

# Convert to Windows paths for javac.exe (native binary; MSYS semicolon
# handling trips it up otherwise).
to_win() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    echo "$1"
  fi
}

WIN_CP_JARS=()
for jar in "${CP_JARS[@]}"; do
  WIN_CP_JARS+=("$(to_win "$jar")")
done
CP=$(IFS=';'; echo "${WIN_CP_JARS[*]}")

VERSION="$(grep '^Bundle-Version:' "$ROOT/src/main/resources/META-INF/MANIFEST.MF" | awk '{print $2}' | tr -d '\r')"
JAR_NAME="futureai-fdrs-layer2-bridge-${VERSION}.jar"

echo ">>> Building $JAR_NAME (Bundle-Version=$VERSION)"

rm -rf "$ROOT/build/classes" "$ROOT/build/jar"
mkdir -p "$ROOT/build/classes" "$ROOT/build/jar"

mapfile -t SRCS < <(find "$ROOT/src/main/java" -name '*.java')
echo "  source files: ${#SRCS[@]}"
echo "  classpath entries: ${#CP_JARS[@]}"

WIN_SRCS=()
for s in "${SRCS[@]}"; do
  WIN_SRCS+=("$(to_win "$s")")
done
WIN_CLASSES_DIR="$(to_win "$ROOT/build/classes")"

"$JDK/javac.exe" \
  --release 8 \
  -Xlint:all,-classfile -Werror \
  -cp "$CP" \
  -d "$WIN_CLASSES_DIR" \
  "${WIN_SRCS[@]}"

cp -r "$ROOT/src/main/resources/META-INF" "$ROOT/build/classes/"

(cd "$ROOT/build/classes" && "$JDK/jar.exe" cfm "$(to_win "$ROOT/build/jar/$JAR_NAME")" META-INF/MANIFEST.MF com)

JAR_SIZE=$(wc -c < "$ROOT/build/jar/$JAR_NAME")
echo ">>> Built: $ROOT/build/jar/$JAR_NAME ($JAR_SIZE bytes)"
"$JDK/jar.exe" tf "$(to_win "$ROOT/build/jar/$JAR_NAME")" | sort | sed 's/^/  /'
