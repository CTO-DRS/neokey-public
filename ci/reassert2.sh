#!/bin/bash
# Reassert v2 gate (roadmap v2, W77) — ci/reassert2.sh
#
#   W77's deliverable is the ONE-HEAD reassertion: every major gate — the
#   core phases, the identity/publication laws, and every shipped signed
#   family — measured GREEN on the same commit, the tree clean before and
#   after, and the HEAD unchanged across the whole run. The verdict carries
#   the HEAD hash: a green verdict on a moving head is worthless, so the
#   final hash check is part of the law.
#
#   Verdict: REASSERT2GATE_OK gates=N/N head=<sha12> (exit 0)
#            / REASSERT2GATE_FAILED <gate> (exit 1).
#   Standalone (W50-prep pattern — no pipeline stage).
set -uo pipefail
cd "$(dirname "$0")/.."

# ---- the law: clean tree, one recorded HEAD ---------------------------------
git diff --quiet || { echo "REASSERT2GATE_FAILED dirty tree (the reassertion measures a committed head only)"; exit 1; }
HEAD0=$(git rev-parse HEAD)
echo "reassert2: head=$HEAD0 — every major gate runs on this commit, none other"

run() { # $1 label, $2 command
  local t0=$(date +%s)
  if bash -c "$2" > "/tmp/reassert2-$1.log" 2>&1; then
    echo "  ok  $1 ($(( $(date +%s) - t0 ))s)"
    return 0
  fi
  echo "  FAIL $1 (rc=$? — log /tmp/reassert2-$1.log)"
  echo "REASSERT2GATE_FAILED $1"
  exit 1
}

N=0; TOTAL=0
step() { TOTAL=$((TOTAL+1)); run "$1" "$2" && N=$((N+1)); }

# ---- 1) the battery ----------------------------------------------------------
step battery    "bash tests/run.sh"
# ---- 2) the core phases ------------------------------------------------------
step phase4     "bash ci/phase4_gate.sh"
step phase10    "bash ci/phase10_gate.sh"
# ---- 3) the identity / publication laws -------------------------------------
step billing    "bash ci/billing_gate.sh"
step preflight  "bash ci/preflight_gate.sh"
step uploadbdl  "bash ci/uploadbundle_gate.sh"
step deploysrv  "bash ci/deploysrv_gate.sh"
step pubsync    "bash ci/pubsync_gate.sh"   # النشر أولاً — ownerverify يتحقق من حالته
step ownerverif "bash ci/ownerverify_gate.sh"
# ---- 4) the signed families, week by week -----------------------------------
step dictsign   "bash ci/dictsign_gate.sh"
step community  "bash ci/community_gate.sh"
step genthemes  "bash ci/genthemes_gate.sh"
step aac        "bash ci/aac_gate.sh"
step numsmart   "bash ci/numsmart_gate.sh"
step packgate   "bash ci/packgate_gate.sh"
step themechan  "bash ci/themechannel_gate.sh"
step syncv2     "bash ci/syncv2_gate.sh"
step storev2    "bash ci/storev2_gate.sh"
# ---- 5) the W62 reassert (engines' negatives + accuracy + fresh dex margin) --
step reassert62 "bash ci/reassert_gate.sh"

# ---- 6) the head never moved -------------------------------------------------
HEAD1=$(git rev-parse HEAD)
git diff --quiet || { echo "REASSERT2GATE_FAILED the tree is dirty after the run"; exit 1; }
if [ "$HEAD0" = "$HEAD1" ]; then
  echo "REASSERT2GATE_OK gates=$N/$TOTAL head=${HEAD1:0:12}"
  exit 0
fi
echo "REASSERT2GATE_FAILED the head moved during the run ($HEAD0 -> $HEAD1)"
exit 1
