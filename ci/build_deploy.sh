#!/bin/bash
# Build the standalone sync-server deployment bundle (phase 5, week 27).
#   in : deploy/com/hark/neokey/deploy/DeployMain.java (launcher)
#        tests/com/hark/neokey/SyncServer.java (the SAME reference-contract class
#              proven on the wire by RESTGATE/TLSGATE — no parallel implementation)
#        src/com/hark/neokey/{SyncVault,SpkiPins,PinnedTls}.java (pure, zero android.*)
#   out: build/deploy/neokey-sync-server.jar (java 8 target, no dependencies)
# A missing class or a compile error = DEPLOY_BUILD_FAILED (fail closed).
set -euo pipefail
cd "$(dirname "$0")/.."

find_jar() {
  for d in "${NEOKEY_TOOLS:-}" ./tools /workspace/work "$HOME/.neokey/tools"; do
    [ -n "$d" ] && [ -f "$d/$1" ] && { echo "$d/$1"; return 0; }
  done
  return 1
}

OUT=build/deploy; rm -rf "$OUT" build/deploy-classes; mkdir -p "$OUT" build/deploy-classes
SRC="deploy/com/hark/neokey/deploy/DeployMain.java \
 tests/com/hark/neokey/SyncServer.java \
 src/com/hark/neokey/SyncVault.java src/com/hark/neokey/SpkiPins.java src/com/hark/neokey/PinnedTls.java"
if command -v javac >/dev/null 2>&1; then
  javac -nowarn -encoding UTF-8 -source 8 -target 8 -d build/deploy-classes $SRC 2>&1 \
    || { echo "DEPLOY_BUILD_FAILED compile"; exit 1; }
else
  ECJ_JAR=$(find_jar ecj.jar) || { echo "DEPLOY_BUILD_FAILED no javac and no ecj.jar"; exit 1; }
  java -jar "$ECJ_JAR" -nowarn -source 8 -target 8 -encoding UTF-8 -d build/deploy-classes $SRC 2>&1 \
    || { echo "DEPLOY_BUILD_FAILED compile"; exit 1; }
fi
for cls in com/hark/neokey/SyncServer.class com/hark/neokey/SyncVault.class \
           com/hark/neokey/SpkiPins.class com/hark/neokey/PinnedTls.class \
           com/hark/neokey/deploy/DeployMain.class; do
  [ -f "build/deploy-classes/$cls" ] || { echo "DEPLOY_BUILD_FAILED missing $cls"; exit 1; }
done
# jar tool is absent on JRE-only hosts — the manifest is assembled with python
# zipfile (same jar format: META-INF/MANIFEST.MF first, Main-Class set).
python3 - "$OUT/neokey-sync-server.jar" build/deploy-classes <<'PY'
import sys, zipfile, os
out, root = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    z.writestr("META-INF/MANIFEST.MF",
               "Manifest-Version: 1.0\r\nMain-Class: com.hark.neokey.deploy.DeployMain\r\n\r\n")
    for dirpath, _, files in os.walk(root):
        for f in files:
            p = os.path.join(dirpath, f)
            z.write(p, os.path.relpath(p, root))
print("jar entries written")
PY
echo "DEPLOY_BUILD_OK $OUT/neokey-sync-server.jar ($(stat -c%s "$OUT/neokey-sync-server.jar")B)"
