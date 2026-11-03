#!/bin/bash
# tools/fetch.sh — bootstrap the build tools on a fresh machine/clone.
# The jars are NOT in git (binary artifacts, docs/R8.md §6 documents why);
# this script re-downloads the EXACT pinned versions the pipeline was
# measured with. Run once from the repo root:  bash tools/fetch.sh
set -euo pipefail
cd "$(dirname "$0")"

fetch() { # url, out
  [ -s "$2" ] && { echo "have  $2"; return; }
  echo "fetch $2"
  curl -fsSL -o "$2" "$1"
}

fetch "https://repo1.maven.org/maven2/org/eclipse/jdt/ecj/3.36.0/ecj-3.36.0.jar" ecj.jar
fetch "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.5.35/r8-8.5.35.jar" r8.jar
fetch "https://raw.githubusercontent.com/Sable/android-platforms/master/android-30/android.jar" android.jar
fetch "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.0/sqlite-jdbc-3.46.1.0.jar" sqlite-jdbc.jar
fetch "https://repo1.maven.org/maven2/org/slf4j/slf4j-api/1.7.36/slf4j-api-1.7.36.jar" slf4j-api.jar

for j in ecj r8 android sqlite-jdbc slf4j-api; do
  [ -s "$j.jar" ] || { echo "MISSING $j.jar"; exit 1; }
done
echo "TOOLS_OK $(ls -la *.jar | wc -l) jars"
