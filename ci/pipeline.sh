#!/bin/bash
# NeoKey CI pipeline — roadmap Phase 0, item B "خط الإنتاج الآلي (CI/CD)".
#
#   probe → secrets audit → tests → performance → store → correction → nnet → dialect → assistant → sync-line → rest-wire → tls-pinning → payment-provider → phase-3 gates → phase3 close → build → r8 → trim → phase4 close → release manifest (26 stages)
#
# Every gate is hard: a FAIL aborts the pipeline with exit 1 and nothing is released.
# Runs anywhere (JVM-only stages need no SDK); full log: build/pipeline.log
# Usage: ci/pipeline.sh            (all stages)
#        SKIP_BUILD=1 ci/pipeline.sh   (JVM gates only — what release.yml runs before signing)
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=build/pipeline.log; mkdir -p build; : > "$LOG"
GATES=build/release/gates.env; mkdir -p build/release; : > "$GATES"
FAILED=0
NAMES=(); VERDICTS=()

note() { echo "$@" | tee -a "$LOG"; }

run_stage() { # $1 name, $2.. command
  local name="$1"; shift
  note ""
  note "───── STAGE: $name ─────"
  local t0=$(date +%s)
  if "$@" >>"$LOG" 2>&1; then
    NAMES+=("$name"); VERDICTS+=("PASS")
    note "[$name] PASS ($(( $(date +%s) - t0 ))s)"
  else
    local rc=$?
    NAMES+=("$name"); VERDICTS+=("FAIL (rc=$rc)")
    note "[$name] FAIL (rc=$rc)"
    FAILED=1
  fi
}

skip_stage() { NAMES+=("$1"); VERDICTS+=("SKIP — $2"); note "[$1] SKIP ($2)"; }

# ---- 0: environment probe ----------------------------------------------------
stage_probe() {
  echo "java: $(java -version 2>&1 | head -1)"
  echo "rg:   $(command -v rg >/dev/null && rg --version | head -1 || echo MISSING)"
  echo "sdk:  ${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-(none set)}}}"
  echo "jars: $(ls tools/*.jar 2>/dev/null | tr '\n' ' ')"
  command -v java >/dev/null && command -v rg >/dev/null
}

# ---- 1: secrets audit (gate) -------------------------------------------------
stage_audit() { tests/audit_secrets.sh; }

# ---- 2: tests, 523 gate (gate) -----------------------------------------------
stage_tests() {
  tests/run.sh
}

# ---- 3: performance gate (gate) ----------------------------------------------
stage_perf() {
  JARCP="build/test"
  [ -f tools/sqlite-jdbc.jar ] && JARCP="$JARCP:tools/sqlite-jdbc.jar"
  [ -f tools/slf4j-api.jar ]   && JARCP="$JARCP:tools/slf4j-api.jar"
  java -cp "$JARCP" com.hark.neokey.PerfGate
}

# ---- 3b: store-listing gate (roadmap item D) ----------------------------------
stage_playstore() { ci/play_gate.sh; }

# ---- 3c: correction accuracy gate (phase 1, item B) ----------------------------
stage_correction() {
  JARCP="build/test"
  [ -f tools/sqlite-jdbc.jar ] && JARCP="$JARCP:tools/sqlite-jdbc.jar"
  [ -f tools/slf4j-api.jar ]   && JARCP="$JARCP:tools/slf4j-api.jar"
  java -cp "$JARCP" com.hark.neokey.CorrectionGate
}

# ---- 3d: neural-inference gate (phase 1, item A1) -------------------------------
stage_nnet() { ci/nnet_gate.sh; }

# ---- 3d: dialect gate (phase 1, item C1) ----------------------------------------
stage_dialect() { ci/dialect_gate.sh; }

# ---- 3e: assistant gate (phase 1, items C2+D1, week 6) ---------------------------
stage_assistant() { ci/assistant_gate.sh; }

# ---- 3f: sync gate (phase 2, item A, week 1) --------------------------------------
stage_sync() { ci/sync_gate.sh; }

# ---- 3g: sync-client gate (phase 2, item A2, week 2) ------------------------------
stage_syncclient() { ci/sync_client_gate.sh; }

# ---- 3g2: REST reference-server gate (phase 4, week 2 — week 23) -----------------
# The contract docs/SYNC-REST.md proven ON THE WIRE: real HTTP server on
# 127.0.0.1 + the production SyncRestTransport — no simulator substitute.
stage_rest() { ci/rest_gate.sh; }

# ---- 3g2b: TLS-SPKI-pinning gate (phase 5, week 1 — week 26) ---------------------
# The client trust contract of the documented "live TLS sync server" pending:
# the pinned transport refuses a MITM inside the HANDSHAKE (no request byte
# leaves), the published fixture cert's SPKI sha256 re-derives byte-exactly,
# and https-without-pins closes at construction (fail-closed).
stage_tls() { ci/tls_gate.sh; }

# ---- 3g3: payment-provider contract gate (phase 4, week 3 — week 24) -------------
# The week-19 promise implemented: PaymentProvider is THE standard replacement
# point — the wallet accepts money only via a verified sealed receipt, no
# second money path; caps are wallet-side and bind regardless of the provider.
stage_paygate() { ci/pay_gate.sh; }



# ---- 3h: theme-store gate (phase 2, item B, week 3) -------------------------------
stage_themestore() { ci/theme_store_gate.sh; }

# ---- 3l: platform-neutrality gate (roadmap phase 2, item C, week 4) -----------
stage_nqfport() { ci/nqfport_gate.sh; }

# ---- 3m: community-enrichment gate (roadmap phase 2, item D, week 5) -----------
stage_community() { ci/community_gate.sh; }

# ---- 3n: signed-dictionary-merge gate (roadmap phase 3, week 2) -----------------
stage_dictsign() { ci/dictsign_gate.sh; }

# ---- 3o: in-app store gate (roadmap phase 3, week 1) ----------------------------
stage_storeui() { ci/store_ui_gate.sh; }

# ---- 3p: entitlements-across-devices gate (roadmap phase 3, week 3) -------------
stage_entitlement() { ci/entitlement_gate.sh; }

# ---- 3q: phase-3 CLOSING gate (roadmap phase 3, week 4) -----------------------
stage_phase3() { ci/phase3_gate.sh; }
stage_trim() { ci/trim_gate.sh; }   # phase 4 W1: the real dex trim, enforced (after r8)

# ---- 3r: phase-4 CLOSING gate (roadmap phase 4, week 4) -----------------------
stage_phase4() { ci/phase4_gate.sh; }

# ---- 4: build (needs SDK; no secrets at this stage) ---------------------------
stage_build() { ci/build_unsigned.sh; }

# ---- 4b: R8 Full Mode gate (roadmap item C) -------------------------------------
stage_r8() {
  # CI/build machine: real SDK resolves android.jar; offline environments use the
  # downloaded tools/android.jar (same platform jar, fetched per docs/R8.md)
  if [ -z "${R8_ANDROID_JAR:-}" ] && [ -f tools/android.jar ]; then
    R8_ANDROID_JAR=tools/android.jar ci/build_r8.sh
  else
    ci/build_r8.sh
  fi
}

# ---- 5: release manifest + rollout plan ---------------------------------------
stage_release() { ci/release.sh; }

keyof() { # stable ASCII key per gate name
  case "$1" in
    suggest*)                echo SUGGEST ;;
    predict*)                echo PREDICT ;;
    correction*)             echo CORRECTION ;;
    clip.insert1000)         echo CLIP_INSERT1000 ;;
    clip.list400x10)         echo CLIP_LIST400X10 ;;
    clip.search20in100k)     echo CLIP_SEARCH20IN100K ;;
    "clip.searchHit(deep)")  echo CLIP_SEARCHHIT ;;
    clip.export1000)         echo CLIP_EXPORT1000 ;;
    clip.parse1000)          echo CLIP_PARSE1000 ;;
    clip.dupScan1000)        echo CLIP_DUPSCAN1000 ;;
    *)                       echo "$1" | tr -c 'A-Za-z0-9' '_' | sed -E 's/_+$//;s/^_+//' ;;
  esac
}

collect_gates() {
  # tests totals: sum the RESULT lines of both suites
  local T=$(grep -o 'RESULT [0-9]* passed, [0-9]* failed' "$LOG" | awk '{p+=$2; f+=$4} END {print p+0, f+0}')
  {
    echo "AUDIT=$(grep -q SECRETS_AUDIT_OK "$LOG" && echo OK || echo NOT_OK)"
    echo "TESTS_TOTAL=$(echo "$T" | awk '{print $1}')"
    echo "TESTS_FAILED=$(echo "$T" | awk '{print $2}')"
  } > "$GATES"
  # per-gate perf numbers: PERF_<NAME>_MS = <p50-or-total> (Arabic gate names -> stable keys)
  grep -E '^PERFGATE name=' "$LOG" | while IFS= read -r line; do
    local n=$(echo "$line" | sed -E 's/^PERFGATE name=([^ ]+).*/\1/')
    local v=$(echo "$line" | sed -E 's/.*(total|p50)=([0-9.]+)ms.*/\2/')
    echo "PERF_$(keyof "$n")_MS=$v"
  done >> "$GATES"
  # R8 gate numbers (last R8_OK line wins)
  if grep -q '^R8_OK ' "$LOG"; then
    echo "R8_DEX_BYTES=$(grep '^R8_OK ' "$LOG" | tail -1 | sed -E 's/^R8_OK dex=([0-9]+).*/\1/')" >> "$GATES"
    echo "R8_COMPONENTS_KEPT=$(grep '^R8_OK ' "$LOG" | tail -1 | sed -E 's/.*kept=([0-9]+\/[0-9]+).*/\1/')" >> "$GATES"
  fi
  # store-listing gate numbers (roadmap item D)
  echo "PLAY_GATE=$(grep -q '^PLAY_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  grep '^PLAYGATE ' "$LOG" | sed -E 's/^PLAYGATE /PLAY_/; s/=([0-9]+)\/[0-9]+$/=\1/' >> "$GATES"
  # correction accuracy gate numbers (phase 1, item B)
  echo "CORR_GATE=$(grep -q '^CORR_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  CORR_LINE=$(grep '^CORRGATE n=' "$LOG" | tail -1)
  echo "CORR_V2_ACC=$(echo "$CORR_LINE" | sed -E 's/.*v2_acc=([0-9.]+)%.*/\1/')" >> "$GATES"
  echo "CORR_V1_ACC=$(echo "$CORR_LINE" | sed -E 's/.*v1_acc=([0-9.]+)%.*/\1/')" >> "$GATES"
  echo "CORR_FP_WRONG=$(echo "$CORR_LINE" | sed -E 's/.*fp_wrong=([0-9]+).*/\1/')" >> "$GATES"
  echo "CORR_P50_MS=$(echo "$CORR_LINE" | sed -E 's/.*p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "CORR_PUNCT=$(echo "$CORR_LINE" | sed -E 's/.*punct=([0-9]+)\/([0-9]+).*/\1\/\2/')" >> "$GATES"
  echo "CORR_POLICY_FAILS=$(echo "$CORR_LINE" | sed -E 's/.*policy_fails=([0-9]+).*/\1/')" >> "$GATES"
  # neural-inference gate numbers (phase 1, item A1)
  echo "NNET_GATE=$(grep -q '^NNET_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  NNET_LINE=$(grep '^NNETGATE model_params=' "$LOG" | tail -1)
  echo "NNET_PARAMS=$(echo "$NNET_LINE" | sed -E 's/.*model_params=([0-9]+).*/\1/')" >> "$GATES"
  echo "NNET_MODEL_BYTES=$(echo "$NNET_LINE" | sed -E 's/.*model_bytes=([0-9]+).*/\1/')" >> "$GATES"
  echo "NNET_P50_MS=$(echo "$NNET_LINE" | sed -E 's/.*p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "NNET_P95_MS=$(echo "$NNET_LINE" | sed -E 's/.*p95=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "NNET_BPP=$(echo "$NNET_LINE" | sed -E 's/.*bpp=([0-9.]+).*/\1/')" >> "$GATES"
  # A2 trained-model numbers (phase 1, week 4)
  NNET2_LINE=$(grep '^NNETGATE2 real_params=' "$LOG" | tail -1)
  echo "NNET_TOP3_HITS=$(echo "$NNET2_LINE" | sed -E 's/.*top3=([0-9]+)\/[0-9]+.*/\1/')" >> "$GATES"
  echo "NNET_TOP3_PCT=$(echo "$NNET2_LINE" | sed -E 's/.*top3=[0-9]+\/[0-9]+=([0-9.]+)%.*/\1/')" >> "$GATES"
  echo "NNET_REAL_PARAMS=$(echo "$NNET2_LINE" | sed -E 's/.*real_params=([0-9]+).*/\1/')" >> "$GATES"
  echo "NNET_REAL_BYTES=$(echo "$NNET2_LINE" | sed -E 's/.*real_bytes=([0-9]+).*/\1/')" >> "$GATES"
  echo "NNET_REAL_P50_MS=$(echo "$NNET2_LINE" | sed -E 's/.*p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  # dialect gate numbers (phase 1, item C1)
  echo "DIALECT_GATE=$(grep -q '^DIALECT_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  D_LINE=$(grep '^DIALECTGATE acc=' "$LOG" | tail -1)
  echo "DIALECT_ACC=$(echo "$D_LINE" | sed -E 's/.*acc=([0-9.]+)%.*/\1/')" >> "$GATES"
  echo "DIALECT_MARKERS=$(echo "$D_LINE" | sed -E 's/.*markers=([0-9]+).*/\1/')" >> "$GATES"
  echo "DIALECT_P50_MS=$(echo "$D_LINE" | sed -E 's/.*p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "DIALECT_DICT_WORDS=$(echo "$D_LINE" | sed -E 's/.*dict_words=([^ ]+).*/\1/')" >> "$GATES"
  # assistant gate numbers (phase 1, items C2+D1, week 6)
  echo "ASSIST_GATE=$(grep -q '^ASSIST_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  A_LINE=$(grep '^ASSISTGATE switch_acc=' "$LOG" | tail -1)
  echo "SWITCH_ACC=$(echo "$A_LINE" | sed -E 's/.*switch_acc=([0-9.]+)%.*/\1/')" >> "$GATES"
  echo "SWITCH_ROWS=$(echo "$A_LINE" | sed -E 's/.*rows=([0-9]+).*/\1/')" >> "$GATES"
  echo "SWITCH_P50_MS=$(grep '^ASSISTGATE switch_p50=' "$LOG" | tail -1 | sed -E 's/.*switch_p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "SUMMARIZE_P50_MS=$(grep '^ASSISTGATE summarize_texts=' "$LOG" | tail -1 | sed -E 's/.*p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "REPHRASE_RULES=$(grep '^ASSISTGATE rephrase_probes=' "$LOG" | tail -1 | sed -E 's/.*rules_simple=([0-9]+).*/\1/')" >> "$GATES"
  # sync gate numbers (phase 2, item A, week 1)
  echo "SYNC_GATE=$(grep -q '^SYNC_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  S_LINE=$(grep '^SYNCGLINE ' "$LOG" | tail -1)
  echo "SYNC_STATE_BYTES=$(echo "$S_LINE" | sed -E 's/.*state_bytes=([0-9]+).*/\1/')" >> "$GATES"
  echo "SYNC_OVERHEAD_PCT=$(echo "$S_LINE" | sed -E 's/.*overhead_pct=([0-9.]+).*/\1/')" >> "$GATES"
  echo "SYNC_SEAL_P50_MS=$(echo "$S_LINE" | sed -E 's/.*seal_p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "SYNC_PER100_MS=$(echo "$S_LINE" | sed -E 's/.*per100=([0-9.]+)ms.*/\1/')" >> "$GATES"
  echo "SYNC_RESTORE_MS=$(echo "$S_LINE" | sed -E 's/.*restore_ms=([0-9.]+).*/\1/')" >> "$GATES"
  echo "SYNC_KDF_MS=$(echo "$S_LINE" | sed -E 's/.*kdf_ms=([0-9]+).*/\1/')" >> "$GATES"
  echo "SYNC_ENTROPY=$(echo "$S_LINE" | sed -E 's/.*entropy=([0-9.]+).*/\1/')" >> "$GATES"
  # sync-client gate numbers (phase 2, item A2, week 2)
  echo "SYNCCLIENT_GATE=$(grep -q '^SYNCCLIENT_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  SC_LINE=$(grep '^SYNCCLGATE ' "$LOG" | tail -1)
  echo "SYNCCLIENT_STATE_RECS=$(echo "$SC_LINE" | sed -E 's/.*state_recs=([0-9]+).*/\1/')" >> "$GATES"
  echo "SYNCCLIENT_BLOB_BYTES=$(echo "$SC_LINE" | sed -E 's/.*blob_bytes=([0-9]+).*/\1/')" >> "$GATES"
  echo "SYNCCLIENT_PULL_MS=$(echo "$SC_LINE" | sed -E 's/.*pull_ms=([0-9.]+).*/\1/')" >> "$GATES"
  echo "SYNCCLIENT_ENTROPY=$(echo "$SC_LINE" | sed -E 's/.*entropy=([0-9.]+).*/\1/')" >> "$GATES"
  echo "SYNCCLIENT_BIG_MS=$(echo "$SC_LINE" | sed -E 's/.*big_ms=([0-9.]+).*/\1/')" >> "$GATES"
  echo "SYNCCLIENT_KDF_MS=$(echo "$SC_LINE" | sed -E 's/.*kdf_ms=([0-9]+).*/\1/')" >> "$GATES"
  # REST reference-server gate numbers (phase 4, week 23)
  echo "REST_GATE=$(grep -q '^RESTGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  R_LINE=$(grep '^RESTGATE_OK' "$LOG" | tail -1)
  echo "REST_ROUNDS=$(echo "$R_LINE" | sed -E 's/.*rounds=([0-9]+).*/\1/')" >> "$GATES"
  echo "REST_BLOBS=$(echo "$R_LINE" | sed -E 's/.*blobs=([0-9]+).*/\1/')" >> "$GATES"
  echo "REST_WIRE_BYTES=$(echo "$R_LINE" | sed -E 's/.*wire_bytes=([0-9]+).*/\1/')" >> "$GATES"
  echo "REST_GRANTED=$(echo "$R_LINE" | sed -E 's/.*granted=([0-9]+).*/\1/')" >> "$GATES"
  echo "REST_NEGATIVES=$(echo "$R_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "REST_ENTROPY_X100=$(echo "$R_LINE" | sed -E 's/.*entropy_x100=([0-9]+).*/\1/')" >> "$GATES"
  echo "REST_P50_PUSH_MS=$(echo "$R_LINE" | sed -E 's/.*p50_push_ms=([0-9.]+).*/\1/')" >> "$GATES"
  echo "REST_P50_PULL_MS=$(echo "$R_LINE" | sed -E 's/.*p50_pull_ms=([0-9.]+).*/\1/')" >> "$GATES"
  echo "REST_CHECKS=$(echo "$R_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  # TLS-SPKI-pinning gate numbers (phase 5, week 26)
  echo "TLS_GATE=$(grep -q '^TLSGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  TL_LINE=$(grep '^TLSGATE_OK' "$LOG" | tail -1)
  echo "TLS_PINS=$(echo "$TL_LINE" | sed -E 's/.*pins=([0-9]+).*/\1/')" >> "$GATES"
  echo "TLS_PROTOCOL=$(echo "$TL_LINE" | sed -E 's/.*protocol=([^ ]+).*/\1/')" >> "$GATES"
  echo "TLS_ROUNDS=$(echo "$TL_LINE" | sed -E 's/.*rounds=([0-9]+).*/\1/')" >> "$GATES"
  echo "TLS_NEGATIVES=$(echo "$TL_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "TLS_ENTROPY_X100=$(echo "$TL_LINE" | sed -E 's/.*entropy_x100=([0-9]+).*/\1/')" >> "$GATES"
  echo "TLS_P50_HANDSHAKE_MS=$(echo "$TL_LINE" | sed -E 's/.*p50_handshake_ms=([0-9]+).*/\1/')" >> "$GATES"
  echo "TLS_CHECKS=$(echo "$TL_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  # payment-provider contract gate numbers (phase 4, week 24)
  echo "PAY_GATE=$(grep -q '^PAYGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  PG_LINE=$(grep '^PAYGATE_OK' "$LOG" | tail -1)
  echo "PAY_PROVIDERS=$(echo "$PG_LINE" | sed -E 's/.*providers=([0-9]+).*/\1/')" >> "$GATES"
  echo "PAY_RECEIPTS=$(echo "$PG_LINE" | sed -E 's/.*receipts=([0-9]+).*/\1/')" >> "$GATES"
  echo "PAY_DET_EQUAL=$(echo "$PG_LINE" | sed -E 's/.*det_equal=([0-9]+).*/\1/')" >> "$GATES"
  echo "PAY_LEDGER_CLOSES=$(echo "$PG_LINE" | sed -E 's/.*ledger_closes=([0-9]+).*/\1/')" >> "$GATES"
  echo "PAY_NEGATIVES=$(echo "$PG_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "PAY_P50_TOPUP_US=$(echo "$PG_LINE" | sed -E 's/.*p50_topup_us=([0-9]+).*/\1/')" >> "$GATES"
  echo "PAY_CHECKS=$(echo "$PG_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  # theme-store gate numbers (phase 2, item B, week 3)
  echo "THEMESTORE_GATE=$(grep -q '^THEME_STORE_GATE_OK$' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  echo "THEMESTORE_PACKS=$(grep -o 'packs=[0-9]*' "$LOG" | tail -1 | cut -d= -f2)" >> "$GATES"
  echo "THEMESTORE_P50_MS=$(grep -o 'latency p50 [0-9.]*' "$LOG" | tail -1 | awk '{print $3}')" >> "$GATES"
  echo "THEMESTORE_NEGATIVES=$(grep -o 'negatives=[0-9]*' "$LOG" | tail -1 | cut -d= -f2)" >> "$GATES"
  echo "THEMESTORE_MONTH_GROSS_C=$(grep -o 'month gross=[0-9]*' "$LOG" | tail -1 | cut -d= -f2)" >> "$GATES"
  # nqf-port gate numbers (phase 2, item C, week 4 — platform neutrality)
  echo "NQFPORT_GATE=$(grep -q '^NQFPORT_GATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  NP_LINE=$(grep '^NQFPORT_GATE_OK' "$LOG" | tail -1)
  echo "NQFPORT_ASSETS=$(echo "$NP_LINE" | sed -E 's/.*assets=([0-9]+).*/\1/')" >> "$GATES"
  echo "NQFPORT_TENSORS=$(echo "$NP_LINE" | sed -E 's/.*tensors=([0-9]+).*/\1/')" >> "$GATES"
  echo "NQFPORT_PARAMS=$(echo "$NP_LINE" | sed -E 's/.*params=([0-9]+).*/\1/')" >> "$GATES"
  echo "NQFPORT_AGREE=$(echo "$NP_LINE" | sed -E 's/.*agree=([0-9]+).*/\1/')" >> "$GATES"
  echo "NQFPORT_NEGATIVES=$(echo "$NP_LINE" | sed -E 's/.*negatives=([0-9]+).*/\1/')" >> "$GATES"
  echo "NQFPORT_P50_MS=$(echo "$NP_LINE" | sed -E 's/.*p50=([0-9.]+)ms.*/\1/')" >> "$GATES"
  # community-dict gate numbers (phase 2, item D, week 5 — enrichment pipeline)
  echo "COMMUNITY_GATE=$(grep -q '^COMMUNITY_GATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  CM_LINE=$(grep '^COMMUNITY_GATE_OK' "$LOG" | tail -1)
  echo "COMMUNITY_ACCEPTED=$(echo "$CM_LINE" | sed -E 's/.*accepted=([0-9]+).*/\1/')" >> "$GATES"
  echo "COMMUNITY_REJECTED=$(echo "$CM_LINE" | sed -E 's/.*rejected=([0-9]+).*/\1/')" >> "$GATES"
  echo "COMMUNITY_NEGATIVES=$(echo "$CM_LINE" | sed -E 's/.*negatives=([0-9]+).*/\1/')" >> "$GATES"
  echo "COMMUNITY_P50_US=$(echo "$CM_LINE" | sed -E 's/.*p50_us=([0-9]+).*/\1/')" >> "$GATES"
  echo "COMMUNITY_BATCHES=$(echo "$CM_LINE" | sed -E 's/.*batches=([0-9]+).*/\1/')" >> "$GATES"
  # signed-dictionary-merge gate numbers (phase 3, week 2)
  echo "DICTSIGN_GATE=$(grep -q '^DICTSIGN_GATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  DS_LINE=$(grep '^DICTSIGN_GATE_OK' "$LOG" | tail -1)
  echo "DICTSIGN_WORDS=$(echo "$DS_LINE" | sed -E 's/.*words=([0-9]+).*/\1/')" >> "$GATES"
  echo "DICTSIGN_NEGATIVES=$(echo "$DS_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "DICTSIGN_P50_PUBLISH_US=$(echo "$DS_LINE" | sed -E 's/.*p50_publish_us=([0-9]+).*/\1/')" >> "$GATES"
  echo "DICTSIGN_P50_LOAD_US=$(echo "$DS_LINE" | sed -E 's/.*p50_load_us=([0-9]+).*/\1/')" >> "$GATES"
  echo "DICTSIGN_CHECKS=$(echo "$DS_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  # in-app store gate numbers (phase 3, week 1)
  echo "STOREUI_GATE=$(grep -q '^STOREUI_GATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  SU_LINE=$(grep '^STOREUI_GATE_OK' "$LOG" | tail -1)
  echo "STOREUI_NEGATIVES=$(echo "$SU_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "STOREUI_P50_US=$(echo "$SU_LINE" | sed -E 's/.*p50_install_us=([0-9]+).*/\1/')" >> "$GATES"
  echo "STOREUI_CATALOG_CENTS=$(echo "$SU_LINE" | sed -E 's/.*catalog_cents=([0-9]+).*/\1/')" >> "$GATES"
  echo "STOREUI_LEDGER_CENTS=$(echo "$SU_LINE" | sed -E 's/.*ledger_closes=([0-9]+).*/\1/')" >> "$GATES"
  # entitlements-across-devices gate numbers (phase 3, week 3)
  echo "ENTGATE=$(grep -q '^ENTGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  EN_LINE=$(grep '^ENTGATE_OK' "$LOG" | tail -1)
  echo "ENTGATE_GRANTED=$(echo "$EN_LINE" | sed -E 's/.*granted=([0-9]+).*/\1/')" >> "$GATES"
  echo "ENTGATE_NEGATIVES=$(echo "$EN_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "ENTGATE_P50_COLLECT_US=$(echo "$EN_LINE" | sed -E 's/.*p50_collect_us=([0-9]+).*/\1/')" >> "$GATES"
  echo "ENTGATE_P50_APPLY_US=$(echo "$EN_LINE" | sed -E 's/.*p50_apply_us=([0-9]+).*/\1/')" >> "$GATES"
  echo "ENTGATE_ENTROPY=$(echo "$EN_LINE" | sed -E 's/.*entropy_x100=([0-9]+).*/\1/')" >> "$GATES"
  echo "ENTGATE_CHECKS=$(echo "$EN_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  # real-dex-trim gate numbers (phase 4, week 22)
  echo "TRIM_GATE=$(grep -q '^TRIM_GATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  TR_LINE=$(grep '^TRIM_GATE_OK' "$LOG" | tail -1)
  echo "TRIM_ENTRIES=$(echo "$TR_LINE" | sed -E 's/.*entries=([0-9]+).*/\1/')" >> "$GATES"
  echo "TRIM_SAVED=$(echo "$TR_LINE" | sed -E 's/.*saved=([0-9]+).*/\1/')" >> "$GATES"
  echo "TRIM_DEX_BYTES=$(echo "$TR_LINE" | sed -E 's/.*dex=([0-9]+)\/[0-9]+.*/\1/')" >> "$GATES"
  echo "TRIM_CEILING=$(echo "$TR_LINE" | sed -E 's/.*dex=[0-9]+\/([0-9]+).*/\1/')" >> "$GATES"
  echo "TRIM_UI_EN_COUPLED=$(echo "$TR_LINE" | sed -E 's/.*ui_en_coupled=([0-9]+).*/\1/')" >> "$GATES"
}

# ---- run the line -------------------------------------------------------------
note "NeoKey CI pipeline — $(date -u +"%Y-%m-%dT%H:%M:%SZ")"
run_stage "probe"    stage_probe
run_stage "audit"    stage_audit
run_stage "tests"    stage_tests
run_stage "perf"     stage_perf
run_stage "playstore" stage_playstore
run_stage "correction" stage_correction
run_stage "nnet" stage_nnet
run_stage "dialect" stage_dialect
run_stage "assistant" stage_assistant
run_stage "sync" stage_sync
run_stage "syncclient" stage_syncclient
run_stage "rest" stage_rest
run_stage "tls" stage_tls
run_stage "paygate" stage_paygate
run_stage "themestore" stage_themestore
run_stage "nqfport" stage_nqfport
run_stage "community" stage_community
run_stage "dictsign" stage_dictsign
run_stage "storeui" stage_storeui
run_stage "entitlement" stage_entitlement
if [ "${SKIP_BUILD:-0}" = "1" ]; then
  skip_stage "build" "SKIP_BUILD=1 (JVM gates only)"
else
  if [ -n "${ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}}" ] || [ -d /workspace/work/sdk ]; then
    run_stage "build"  stage_build
  else
    skip_stage "build" "no Android SDK in this environment (compile gate runs on CI / build machine)"
  fi
fi
if [ -f tools/r8.jar ] && [ -f tools/android.jar ]; then
  run_stage "r8" stage_r8
else
  skip_stage "r8" "tools/r8.jar or tools/android.jar missing (see docs/R8.md) — shrink proof runs where they exist"
fi
if [ -f tools/r8.jar ] && [ -f tools/android.jar ]; then
  run_stage "trim" stage_trim   # phase 4 W1 (week 22): rides r8's dex artifact
else
  skip_stage "trim" "no r8 tools — the trim gate measures the r8 dex artifact"
fi
collect_gates   # BEFORE the release stage — the manifest must carry the real gate numbers
run_stage "phase3"   stage_phase3   # closing gate reads THIS run's gates.env
run_stage "phase4"   stage_phase4   # phase 4 W4 (week 25): all phase-4 gates in one line, fresh
run_stage "release"  stage_release

# ---- summary ------------------------------------------------------------------
note ""
note "════════════════ PIPELINE SUMMARY ════════════════"
for i in "${!NAMES[@]}"; do note "  $(printf '%-8s' "${NAMES[i]}") ${VERDICTS[i]}"; done
note "  tests: $(grep -o 'RESULT [0-9]* passed, [0-9]* failed' "$LOG" | awk '{p+=$2; f+=$4} END {print p+0 " passed, " f+0 " failed"}')"
note "═══════════════════════════════════════════════════"
if [ "$FAILED" = "0" ]; then note "PIPELINE_OK"; else note "PIPELINE_FAILED"; exit 1; fi
