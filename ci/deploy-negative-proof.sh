#!/bin/bash
# Negative proof for the deployment-bundle gate (phase 5, week 27) — SAVED.
# Three designed attacks; each must fail with ITS OWN tag, then be restored
# (cp-backup, never git) and re-verified GREEN. The proof fails loudly if any
# attack fails to bite or any restore fails to heal.
set -uo pipefail
cd "$(dirname "$0")/.."

GATE="bash ci/deploy_gate.sh"
FAILED=0

echo "== deploy negative proof — $(date -u +"%Y-%m-%dT%H:%M:%SZ") =="

# snapshot every file the attacks touch
mkdir -p build/deploy-proof-backup
cp deploy/com/hark/neokey/deploy/DeployMain.java build/deploy-proof-backup/DeployMain.java
cp tests/com/hark/neokey/SyncServer.java build/deploy-proof-backup/SyncServer.java

gate() { $GATE > "$1" 2>&1; echo $?; }

run_attack() { # $1 name, $2 attacker (bash), $3 expected tag line in output
  local name="$1" attacker="$2" expect="$3"
  echo ""
  echo "--- attack: $name (expect: $expect)"
  eval "$attacker"
  local rc=$(gate /tmp/deploy_neg.txt)
  local verdict=$(grep -m1 'DEPLOYGATE_FAILED' /tmp/deploy_neg.txt || echo "(no verdict line)")
  local tagline=$(grep -m1 "$expect" /tmp/deploy_neg.txt || echo "(tag line missing)")
  echo "rc=$rc"
  echo "verdict: $verdict"
  echo "tag: $tagline"
  case "$tagline" in *"$expect"*) echo "tag: OK";; *) echo "tag: WRONG (expected '$expect')"; FAILED=1;; esac
  [ "$rc" != "0" ] || { echo "ATTACK DID NOT BITE"; FAILED=1; }
  # restore everything from the snapshot, then the gate MUST be green
  cp build/deploy-proof-backup/DeployMain.java deploy/com/hark/neokey/deploy/DeployMain.java
  cp build/deploy-proof-backup/SyncServer.java tests/com/hark/neokey/SyncServer.java
  local rc2=$(gate /tmp/deploy_ok.txt)
  local okline=$(grep -m1 'DEPLOYGATE_OK' /tmp/deploy_ok.txt | head -c 60 || echo MISSING)
  echo "restored: rc=$rc2 $okline"
  [ "$rc2" = "0" ] && [ "$okline" != "MISSING" ] || { echo "RESTORE DID NOT HEAL"; FAILED=1; }
}

# 1) sabotaged launcher: the READY line prints a forged pin — the anchor must
#    catch it (the jar's own printed pin is checked against the pinned fixture
#    fingerprint; a lying launcher is a compromised bundle).
run_attack "forged READY pin (lying launcher)" \
'python3 - <<PYEOF
s = open("deploy/com/hark/neokey/deploy/DeployMain.java", encoding="utf-8").read()
old = "spki=\" + spki);"
assert old in s, "ready line not found"
open("deploy/com/hark/neokey/deploy/DeployMain.java", "w", encoding="utf-8").write(
    s.replace(old, "spki=\" + \"f\".repeat(64));", 1))
PYEOF' \
"GATEFAIL: boot.pin.anchor"

# 2) missing contract class: SyncServer.java removed from the bundle build —
#    the build must fail closed with its own tag (a bundle without the proven
#    contract logic is never a silent pass).
run_attack "hide SyncServer.java from the bundle build" \
'mv tests/com/hark/neokey/SyncServer.java /tmp/deploy-proof-hidden-SyncServer.java' \
"DEPLOY_BUILD_FAILED compile"

# 3) poisoned contract logic: the bundle TRUNCATES stored blobs (4 bytes off —
#    eats the GCM tag) — the sync round through the bundle must break with the
#    client's exact refusal (the envelope the contract never sealed is refused).
run_attack "truncate stored blobs in SyncServer (poisoned contract logic)" \
'python3 - <<PYEOF
s = open("tests/com/hark/neokey/SyncServer.java", encoding="utf-8").read()
old = "a.blobs.put(blobId, b.clone());"
assert old in s, "blob store line not found"
open("tests/com/hark/neokey/SyncServer.java", "w", encoding="utf-8").write(
    s.replace(old, "a.blobs.put(blobId, java.util.Arrays.copyOf(b, b.length - 4));", 1))
PYEOF' \
"DEPLOYGATE_FAILED exception"

echo ""
if [ "$FAILED" = "0" ]; then echo "DEPLOY_NEGATIVE_PROOF_OK 3 attacks bitten, all restores healed"; else echo "DEPLOY_NEGATIVE_PROOF_FAILED"; exit 1; fi
