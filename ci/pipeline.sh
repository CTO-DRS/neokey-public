#!/bin/bash
# NeoKey CI pipeline — roadmap Phase 0, item B "خط الإنتاج الآلي (CI/CD)".
#
#   probe → secrets audit → tests → performance → store → correction → nnet → dialect → assistant → sync-line → rest-wire → tls-pinning → deploy-bundle → ksdrill → payment-provider → phase-3 gates → phase3 close → build → r8 → trim → signedapk → phase4 close → phase5 close → release manifest → uploadbundle → phase6 close → billing → deploysrv → preflight → phase7 close → rebuild → evaudit → opverify → phase8 → pubsync (42 stages; phase 6 closed at stage 33 on week 33, phase 7 closed at stage 37 on week 37, phase 8 W1 = week 38 the REBUILD DRILL at 38, phase 8 W2 = week 39 the WEEK EVIDENCE AUDITOR: this run's evidence archive packed deterministically + four law layers (member integrity, log shape, verdict↔ledger cross-check, identity consensus + the saved-negative-proof law) + twelve exact-tag negatives — the archive is verified, not trusted; phase 8 W3 = week 40 the OPERATOR-OUTPUT VERIFIER LAST: the outputs of the documented outside steps (pendings #1/#2) become machine-verifiable — the Provision letter against the published deployment shape, the transferred pack re-verified before any run, the StoreActivity swap line as a gated line with its published identity + twelve exact-tag negatives; phase 8 W4 = week 41 the CLOSURE at 41, LAST: the three phase-8 gates re-run fresh in one line (rebuild = a fresh drill, evaudit, opverify) + three-way evidence integrity + zero regressions on 33 keys — NEOKEY_REBUILD_INNER=1 omits the drill AND the phase8 closer inside the inner run (both marked INNER_OMIT: the closer re-runs the drill, so inside an inner run it would recurse — a note, no row); phase 9 W1 = week 42 the PUBLICATION GATE at 42, LAST: the carry-out surface becomes a gated input — EvAudit.pack (the one derivation) → deterministic zip → upload to the published release → the ROUNDTRIP verifies the PUBLISHED bytes (sha re-derived from what was downloaded, the archive audited in isolation by EvAudit.check itself, the cumulative patch proven to apply to the published base) — no credential = fail closed (publish.credential), INNER_OMIT inside the drill (the clone carries no credentials and the drill never touches the network); phase 9 W2 = week 43 the OWNER FETCH VERIFIER at 43, LAST: any published release verified from ANY machine without the write credential — the statement published as the third asset, the sha re-derived from the FETCHED bytes, the fetched archive audited in isolation, the fetched patch proven to rebuild HEAD's tree byte-exactly, the record (the committed proof line's full published sha) anchoring against the consistent forger, read-only enforced on the tool's own surface, INNER_OMIT inside the drill (no publication state there)
#
# Every gate is hard: a FAIL aborts the pipeline with exit 1 and nothing is released.
# Runs anywhere (JVM-only stages need no SDK); full log: build/pipeline.log
# Usage: ci/pipeline.sh            (all stages)
#        SKIP_BUILD=1 ci/pipeline.sh   (JVM gates only — what release.yml runs before signing)
set -uo pipefail
cd "$(dirname "$0")/.."

LOG=build/pipeline.log; mkdir -p build; : > "$LOG"
GATES=build/release/gates.env; mkdir -p build/release
# the publication state (W42) survives the startup truncation: the ledger's
# PUBSYNC_ keys are the RESTING state between runs — refreshed only by a green
# gate run (local publishes + verifies locally, --roundtrip adds the remote
# fetch) and consumed by the pipeline's --verify stage — the same law as the
# closer keys, applied where the older law never reached: the startup wipe
grep -E '^PUBSYNC_' "$GATES" > build/release/.pubsync-keys.$$ 2>/dev/null || : > build/release/.pubsync-keys.$$
: > "$GATES"
if [ -s build/release/.pubsync-keys.$$ ]; then cat build/release/.pubsync-keys.$$ >> "$GATES"; fi
rm -f build/release/.pubsync-keys.$$
FAILED=0
NAMES=(); VERDICTS=()
WEEK=43  # the live week (the publication lifecycle guards read it — W43)

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

# ---- 3g2c: deployment-bundle gate (phase 5, week 2 — week 27) -------------------
# The deployment slice of the "live sync server" pending: the standalone jar
# (ci/build_deploy.sh) is launched as a REAL SEPARATE PROCESS — READY line pin
# == fixture pin == gate anchor, loopback default bind, restart-stable pin, a
# real sync round through the PRODUCTION pinned transport against the
# process, health check FROM THE JAR (the contract is the probe), fail-closed
# boot (wrong password / unknown arg = DEPLOY_REFUSED, never a READY line).
stage_deploy() { ci/deploy_gate.sh; }

# ---- 3g2d: keystore-recovery-drill gate (phase 5, week 3 — week 28) -------------
# The rehearsal slice of the documented "restore signing secrets from the
# encrypted backup" pending: the owner's decision procedure (KsValidator) runs
# against a restored STAND-IN keystore — the anchor (published fingerprint)
# is the FIRST barrier, then eight designed negatives prove every check still
# bites (rogue key / weak key / expired / short validity / wrong container /
# wrong password / wrong alias / missing artifact). The secret itself never
# leaves the owner's vault.
stage_ksdrill() { ci/keystore_drill.sh; }

# ---- 3g2b: provisioning + rotation drill (phase 6, week 1 — week 30) ----------
# The last locally-closable slice of the live-TLS-deployment pending: the
# pin-SETTING decision procedure automated and proven to bite. Provision (pure
# JDK, the operator's CLI) decides whether a server chain is provisionable
# (chain order/signatures to the operator's CA, SAN matches the domain,
# validity floor, RSA>=2048), derives the pin by the ONE derivation
# (SpkiPins.pinOf) and emits the literal NetGate.setSyncPins line + a
# deterministic statement. The rotation drill proves on the wire: same-key
# renewal = same pin (transparent), new key = new pin refused INSIDE the
# handshake until re-pin, then a green round. The anchor is the FIRST barrier:
# the committed arena fixture re-derives the pinned literal EXPECTED_ARENA_PIN.
stage_provision() { ci/provision_gate.sh; }

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

# ---- 3s: phase-5 CLOSING gate (roadmap phase 5, week 4 — week 29) --------------
# All phase-5 gates in one line, fresh: the TLS trust contract, the deployment
# bundle and the keystore-recovery drill re-execute with every shipped field
# pinned, three-way evidence integrity (fresh == pipeline.log == gates.env),
# zero regressions on all 21 previous gate keys, and the measured exit criteria
# (tests >= 1,354, dex <= the lowered 512,000B ceiling, vc >= 70, vn = 2.3).
stage_phase5() { ci/phase5_gate.sh; }

# ---- 4a: signed-artifact gate (phase 6, week 2 — week 31) ----------------------
# The ARTIFACT half of the "restore signing secrets" pending (the DECISION half
# was the W28 drill): the full APK is assembled offline (aapt2 link — real
# resources, the step silently broken since week 22 dropped the identity
# strings), aligned (dex injected, arsc stored + 4-byte aligned), signed v1+v2
# with the drill stand-in through Google's apksig and verified end-to-end —
# the owner re-runs the SAME two commands with the real (W28-validated)
# keystore. Anchor first (published fingerprint), then 14 checks + 6 negatives.
stage_signedapk() { ci/signedapk_gate.sh; }

# ---- 3g2e: store-upload-bundle gate (phase 6, week 3 — week 32) ---------------
# The locally-closable slice of the "store accounts + actual upload" pending:
# ONE verifiable upload bundle — the signed artifact (anchored to the W31
# statement byte-for-byte BEFORE assembly), the deobfuscation mapping, the
# ar/en listing re-measured by the SAME parser as the weekly play gate, every
# listing claim coupled to a LIVE ui_en.txt key, data-safety answers DERIVED
# from the actually-declared manifest permissions (duplicates refused, no
# answer without a declaration), the privacy policy and the staged rollout
# plan — and a gate that refuses anything missing or inconsistent.
stage_uploadbundle() { ci/uploadbundle_gate.sh; }

# ---- 4b: phase-6 CLOSING gate (phase 6, week 4 — week 33) ---------------------
# All phase-6 gates in one line, fresh: the provisioning + rotation drill, the
# signed artifact and the store-upload bundle re-execute with every shipped
# field pinned (anchors literal, checks/negatives exact, the artifact sha256
# three-way), three-way evidence integrity (fresh == pipeline.log ==
# gates.env), zero regressions on all 25 previous gate keys, and the measured
# exit criteria (tests >= 1,424, dex <= the lowered 512,000B ceiling, vc >= 74,
# vn >= 2.7 — the exact close measurement is pinned once in
# release-manifest.phase6Close; the live check is a non-regression floor from
# day one, the W30 lesson applied proactively).
stage_phase6() { ci/phase6_gate.sh; }

# ---- 4: the phase-7 opener (phase 7 W1, week 34) — the billing adapter -------
# release-manifest.billing; the live check is the contract-parity suite itself
stage_billing() { ci/billing_gate.sh; }

# ---- 5: the deployable sync-server pack (phase 7 W2, week 35) -----------------
# release-manifest.deploySrv; the live check boots the pack's own copy
stage_deploysrv() { ci/deploysrv_gate.sh; }

# ---- 6: the phase-7 preflight (phase 7 W3, week 36) — the operator sheet ------
# release-manifest.preflight; the live check is THE FIVE FRESH PROOFS: the
# four week gates re-run green inside the stage + the release commands'
# existence, then the handover sheet is emitted from THIS run's evidence and
# checked against itself (the sheet is the reference).
stage_preflight() { ci/preflight_gate.sh; }

# ---- 7: the phase-7 CLOSING gate (phase 7 W4, week 37) ------------------------
# release-manifest.phase7Close; the live check re-runs the three phase-7 gates
# FRESH in one line (billing/deploysrv/preflight — never cached verdicts) with
# three-way evidence integrity (the week-21 design, inherited a fourth time)
# and zero regressions on all 29 previous gate keys.
stage_phase7() { ci/phase7_gate.sh; }

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
  # week-33 fix (a real latent defect this closure week exists to catch): the
  # closing gates append their PHASE*_ keys to gates.env AFTER the first pass,
  # and the W32 second pass (added for UPLOADBUNDLE_*) silently WIPED them by
  # truncating the file — any reader after it (the phase-6 closer, the
  # standalone release.sh re-run) saw the closers as NOT_OK/defaults. The pass
  # now snapshots the closers' keys first and re-appends them at the end.
  local CLOSE_KEEP="build/release/.closer-keys.$$"
  grep -E '^(PHASE3_|PHASE4_|PHASE5_|PHASE6_|BILLING_|DEPLOYSRV_|PREFLIGHT_|PHASE7_|REBUILD_|EVAUDIT_|OPVERIFY_|PHASE8_|PUBSYNC_|OWNERVERIFY_)' "$GATES" > "$CLOSE_KEEP" 2>/dev/null || : > "$CLOSE_KEEP"
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
  # Deployment-bundle gate numbers (phase 5, week 27)
  echo "DEPLOY_GATE=$(grep -q '^DEPLOYGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  DP_LINE=$(grep '^DEPLOYGATE_OK' "$LOG" | tail -1)
  echo "DEPLOY_HEALTH_MS=$(echo "$DP_LINE" | sed -E 's/.*health_ms=([0-9]+).*/\1/')" >> "$GATES"
  echo "DEPLOY_NEGATIVES=$(echo "$DP_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "DEPLOY_CHECKS=$(echo "$DP_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  # keystore-recovery-drill gate numbers (phase 5, week 28)
  echo "KSDRILL_GATE=$(grep -q '^KSDRILLGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  KD_LINE=$(grep '^KSDRILLGATE_OK' "$LOG" | tail -1)
  echo "KSDRILL_CHECKS=$(echo "$KD_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  echo "KSDRILL_NEGATIVES=$(echo "$KD_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "KSDRILL_DAYS_LEFT=$(echo "$KD_LINE" | sed -E 's/.*days_left=([0-9]+).*/\1/')" >> "$GATES"
  echo "KSDRILL_FP16=$(echo "$KD_LINE" | sed -E 's/.*fp16=([0-9a-f]+).*/\1/')" >> "$GATES"
  # provisioning + rotation drill numbers (phase 6, week 30)
  echo "PROVISION_GATE=$(grep -q '^PROVISIONGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  PV_LINE=$(grep '^PROVISIONGATE_OK' "$LOG" | tail -1)
  echo "PROVISION_CHECKS=$(echo "$PV_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  echo "PROVISION_NEGATIVES=$(echo "$PV_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "PROVISION_ANCHOR_PIN16=$(echo "$PV_LINE" | sed -E 's/.*anchor_pin16=([0-9a-f]+).*/\1/')" >> "$GATES"
  echo "PROVISION_PIN16=$(echo "$PV_LINE" | sed -E 's/.* pin16=([0-9a-f]+).*/\1/')" >> "$GATES"
  echo "PROVISION_RENEW_DAYS_LEFT=$(echo "$PV_LINE" | sed -E 's/.*renew_days_left=([0-9]+).*/\1/')" >> "$GATES"
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
  # signed-artifact gate numbers (phase 6, week 2 — week 31)
  echo "SIGNEDAPK_GATE=$(grep -q '^SIGNEDAPKGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  SA_LINE=$(grep '^SIGNEDAPKGATE_OK' "$LOG" | tail -1)
  echo "SIGNEDAPK_CHECKS=$(echo "$SA_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  echo "SIGNEDAPK_NEGATIVES=$(echo "$SA_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "SIGNEDAPK_FP16=$(echo "$SA_LINE" | sed -E 's/.*fp16=([0-9a-f]+).*/\1/')" >> "$GATES"
  echo "SIGNEDAPK_V1=$(echo "$SA_LINE" | sed -E 's/.*v1=([01]).*/\1/')" >> "$GATES"
  echo "SIGNEDAPK_V2=$(echo "$SA_LINE" | sed -E 's/.*v2=([01]).*/\1/')" >> "$GATES"
  echo "SIGNEDAPK_VC=$(echo "$SA_LINE" | sed -E 's/.*vc=([0-9]+).*/\1/')" >> "$GATES"
  echo "SIGNEDAPK_VN=$(echo "$SA_LINE" | sed -E 's/.*vn=([0-9.]+) .*apk_sha256.*/\1/')" >> "$GATES"
  echo "SIGNEDAPK_APK_SHA256=$(echo "$SA_LINE" | sed -E 's/.*apk_sha256=([0-9a-f]+).*/\1/')" >> "$GATES"
  echo "SIGNEDAPK_DEX_BYTES=$(echo "$SA_LINE" | sed -E 's/.*dex=([0-9]+)\/[0-9]+.*/\1/')" >> "$GATES"
  # store-upload-bundle gate numbers (phase 6, week 32)
  echo "UPLOADBUNDLE_GATE=$(grep -q '^UPLOADBUNDLEGATE_OK' "$LOG" && echo OK || echo NOT_OK)" >> "$GATES"
  UB_LINE=$(grep '^UPLOADBUNDLEGATE_OK' "$LOG" | tail -1)
  echo "UPLOADBUNDLE_CHECKS=$(echo "$UB_LINE" | sed -E 's/.*checks=([0-9]+).*/\1/')" >> "$GATES"
  echo "UPLOADBUNDLE_NEGATIVES=$(echo "$UB_LINE" | grep -oE 'negatives=[0-9]+/[0-9]+' | cut -d= -f2)" >> "$GATES"
  echo "UPLOADBUNDLE_MEMBERS=$(echo "$UB_LINE" | sed -E 's/.*members=([0-9]+).*/\1/')" >> "$GATES"
  echo "UPLOADBUNDLE_EVIDENCE=$(echo "$UB_LINE" | sed -E 's/.*evidence=([0-9]+).*/\1/')" >> "$GATES"
  echo "UPLOADBUNDLE_APK_SHA256=$(echo "$UB_LINE" | sed -E 's/.*apk_sha256=([0-9a-f]+).*/\1/')" >> "$GATES"
  # re-append the closing gates' keys the truncation above would have wiped
  # (week-33: PHASE3_/PHASE4_/PHASE5_ keys survive every pass — the phase-6
  # closer and the standalone release.sh re-run read them AFTER this line)
  if [ -s "$CLOSE_KEEP" ]; then cat "$CLOSE_KEEP" >> "$GATES"; fi
  rm -f "$CLOSE_KEEP"
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
run_stage "deploy" stage_deploy
run_stage "ksdrill" stage_ksdrill
run_stage "provision" stage_provision   # phase 6 W1 (week 30): provisioning + rotation drill
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
if [ -f tools/apksig.jar ] && [ -f tools/aapt2 ] && [ -f tools/android.jar ]; then
  run_stage "signedapk" stage_signedapk   # phase 6 W2 (week 31): the signed artifact
else
  skip_stage "signedapk" "tools/apksig.jar + tools/aapt2 + tools/android.jar missing (see docs/R8.md) — the signed-artifact proof runs where they exist"
fi
collect_gates   # BEFORE the closers/release — they read these keys
# the closing gates measure THIS run's dex/trim/regression keys — they can only
# run where the r8 artifact exists (week 31 fix: a JVM-only machine skipped
# r8/trim/signedapk, so their keys are absent and the closers must SKIP with a
# documented reason, not fail red — they re-run fully in the release workflow
# and in the weekly sandbox run where the artifact lives)
if [ -f tools/r8.jar ] && [ -f tools/android.jar ]; then
  run_stage "phase3"   stage_phase3   # closing gate reads THIS run's gates.env
  run_stage "phase4"   stage_phase4   # phase 4 W4 (week 25): all phase-4 gates in one line, fresh
  run_stage "phase5"   stage_phase5   # phase 5 W4 (week 29): all phase-5 gates in one line, fresh
else
  skip_stage "phase3" "closing gates need the r8 artifact (no r8 tools here) — they re-run where the artifact lives"
  skip_stage "phase4" "closing gates need the r8 artifact (no r8 tools here) — they re-run where the artifact lives"
  skip_stage "phase5" "closing gates need the r8 artifact (no r8 tools here) — they re-run where the artifact lives"
fi
run_stage "release"  stage_release
# ---- 3g2e: the week's CLOSING gate (phase 6, week 32) — uploadbundle runs
# AFTER release on purpose: the bundle consumes release outputs (the staged
# rollout plan), then collect_gates re-runs so gates.env carries the week's
# keys and a standalone `ci/release.sh` re-run issues the manifest with the
# measured uploadBundle block.
if [ -f tools/aapt2 ] && [ -f build/NeoKey-2036.apk ] && [ -f build/release/signedapk-statement.json ] && [ -f build/release/rollout-plan.json ]; then
  run_stage "uploadbundle" stage_uploadbundle   # phase 6 W3 (week 32): the store-upload bundle
  collect_gates   # second pass: gates.env gains the UPLOADBUNDLE_* keys (closer keys preserved since week 33)
  if [ -f tools/r8.jar ] && [ -f tools/android.jar ]; then
    run_stage "phase6" stage_phase6   # phase 6 W4 (week 33): all phase-6 gates in one line, fresh
  else
    skip_stage "phase6" "closing gates need the r8 artifact (no r8 tools here) — they re-run where the artifact lives"
  fi
else
  skip_stage "uploadbundle" "needs the signedapk artifact + its statement + aapt2 + the release rollout plan — runs where the signed artifact lives"
  skip_stage "phase6" "the phase-6 closer consumes the UPLOADBUNDLE_* keys — it runs where the signed artifact lives"
fi

# ---- 4: the phase-7 opener (phase 7 W1, week 34) — billing runs LAST this
# week: the phase-6 closer (stage 33) closes the previous phase, then the new
# phase's first gate proves the billing-adapter replacement. Its keys are
# appended to gates.env by the gate itself (the phase-6 pattern; no third
# collect_gates pass needed), and the closer-key snapshot now carries BILLING_
# too (the week-33 lesson applied on day one: a later pass must never wipe
# the new phase's keys).
if command -v javac >/dev/null 2>&1 || [ -f tools/ecj.jar ]; then
  run_stage "billing" stage_billing   # phase 7 W1 (week 34): the proven billing adapter
else
  skip_stage "billing" "needs javac or tools/ecj.jar to compile the adapter contract"
fi

# ---- 5: phase 7 W2 (week 35) — the deployable sync-server pack runs LAST:
# it rebuilds the jar fresh, packs it with generated scripts + INSTALL (the
# two literal commands), boots THE PACK'S OWN COPY and runs a full contract
# round over the SPKI-pinned channel.
if [ -f tests/tls/arena-server.p12 ] && { command -v javac >/dev/null 2>&1 || [ -f tools/ecj.jar ]; }; then
  run_stage "deploysrv" stage_deploysrv   # phase 7 W2 (week 35): the deployable server pack
else
  skip_stage "deploysrv" "needs the arena keystore fixture + javac/ecj to compile the pack"
fi

# ---- 6: phase 7 W3 (week 36) — the OPERATOR PREFLIGHT SHEET runs LAST:
# ONE tool walks ALL release conditions on FRESH re-runs (the four week
# gates re-run green inside this stage — never a cached verdict) and emits
# the handover sheet: every operator step either CHECKED here or OUTSIDE
# with its literal command + documented externality. Its keys ride the
# closer-key snapshot (PREFLIGHT_ in CLOSE_KEEP) since day one.
if command -v javac >/dev/null 2>&1 || [ -f tools/ecj.jar ]; then
  run_stage "preflight" stage_preflight   # phase 7 W3 (week 36): the operator preflight sheet
else
  skip_stage "preflight" "needs javac/ecj to compile the preflight tool"
fi

# ---- 7: phase 7 W4 (week 37) — the CLOSURE runs LAST: the three phase-7
# gates re-run fresh in one line (their re-runs need the r8 artifact the
# signedapk/uploadbundle gates proved), three-way evidence integrity, and
# zero regressions on all 29 previous gate keys. The exact close measurement
# (vc 78 / vn 3.1) is pinned once in release-manifest.phase7Close.
if [ -f tools/r8.jar ] && [ -f tools/android.jar ]; then
  run_stage "phase7" stage_phase7   # phase 7 W4 (week 37): the phase-7 closure
else
  skip_stage "phase7" "the phase-7 closer re-runs gates that need the r8 artifact — it runs where the artifact lives"
fi

# ---- 8: phase 8 W1 (week 38) — the REBUILD DRILL runs LAST: a clean local
# clone of HEAD + the seven sha256-pinned tools + the FULL INNER PIPELINE
# (38 stages since W39 grew the pipeline to 39, zero SKIP). The recursion
# guard NEOKEY_REBUILD_INNER=1 omits this stage ENTIRELY inside the inner
# run — no SKIP row, exactly 38 rows; the stages after the drill (evaudit)
# strip out of the inner/outer shape comparison (the W39 lesson).
stage_rebuild() { ci/rebuild_gate.sh; }
if [ "${NEOKEY_REBUILD_INNER:-0}" = "1" ]; then
  note "REBUILD_INNER: the drill stage omits itself inside the inner run (recursion guard NEOKEY_REBUILD_INNER=1)"
elif [ -f tools/r8.jar ] && [ -f tools/android.jar ]; then
  run_stage "rebuild" stage_rebuild
else
  skip_stage "rebuild" "the drill needs the provisioned toolchain (ci/rebuild.sh provision; docs/R8.md) — it runs where the tools live"
fi

# ---- 9: phase 8 W2 (week 39) — the WEEK EVIDENCE AUDITOR runs LAST: this
# run's evidence archive (the log + the ledger + the manifest + the rollout
# plans + the preflight sheet + EVERY saved negative-proof log) is packed
# deterministically with a SHA256SUMS manifest, then audited in isolation —
# member integrity, log shape, verdict↔ledger cross-check, identity consensus
# + the saved-negative-proof law — with twelve exact-tag negatives on dirty
# copies. The vault owner verifies any week's proof BEFORE the outside step
# (pendings #3/#4): the archive is verified, not trusted. Its keys ride the
# closer-key snapshot (EVAUDIT_ in CLOSE_KEEP) since day one.
stage_evaudit() { ci/evaudit_gate.sh; }
if command -v javac >/dev/null 2>&1 || [ -f tools/ecj.jar ]; then
  run_stage "evaudit" stage_evaudit
else
  skip_stage "evaudit" "needs javac/ecj to compile the evidence auditor"
fi

# ---- 10: phase 8 W3 (week 40) — the OPERATOR-OUTPUT VERIFIER runs LAST: the
# outputs of the documented outside steps become machine-verifiable (pendings
# #1/#2, external by nature — no pretending): the Provision letter (the
# emitted NetGate.setSyncPins literal + the neokey-provision-1 statement)
# verified against the published deployment shape (a fresh arena run is the
# reference), the transferred pack re-verified BEFORE any run through the
# same SyncServerPack.check that proved it (the one-derivation law), and the
# StoreActivity swap line documented as a GATED LINE with its published
# identity (literal text + sha256) — an edited copy must touch exactly that
# line. TWELVE exact-tag negatives on temp dirty copies. Its keys ride the
# closer-key snapshot (OPVERIFY_ in CLOSE_KEEP) since day one.
stage_opverify() { ci/opverify_gate.sh; }
if command -v javac >/dev/null 2>&1 || [ -f tools/ecj.jar ]; then
  run_stage "opverify" stage_opverify   # phase 8 W3 (week 40): the operator-output verifier
else
  skip_stage "opverify" "needs javac/ecj to compile the verifier"
fi

# ---- 11: phase 8 W4 (week 41) — the CLOSURE runs LAST: the three phase-8
# gates re-run fresh in one line (rebuild = A FRESH DRILL, evaudit, opverify),
# three-way evidence integrity, and zero regressions on all 33 previous gate
# keys. The exact close measurement (vc 82 / vn 3.5) is pinned once in
# release-manifest.phase8Close. RECURSION GUARD (the W38 pattern, now two
# stages deep): inside an inner run (NEOKEY_REBUILD_INNER=1) this stage omits
# itself with a note — its job IS re-running the drill, which would recurse
# forever if it ran inside the drill's own inner run. The omission is marked
# INNER_OMIT on the run_stage line: the drill's INNER_LIST derivation (the
# stage COUNT) excludes it, while AFTER_LIST (the profile STRIP) keeps it —
# a note, no row, in both logs.
stage_phase8() { ci/phase8_gate.sh; }
if [ "${NEOKEY_REBUILD_INNER:-0}" = "1" ]; then
  note "REBUILD_INNER: the phase8 closer omits itself inside the inner run (recursion guard — it re-runs the drill; the omission is a note, not a row)"
elif [ -f tools/r8.jar ] && [ -f tools/android.jar ]; then
  run_stage "phase8" stage_phase8   # INNER_OMIT (guarded above: the closer re-runs the drill — W41)
else
  skip_stage "phase8" "the closer re-runs the drill — it runs where the provisioned toolchain lives"   # INNER_OMIT (every call-site line of an inner-omitted stage carries the marker)
fi

# ---- 12: phase 9 W1 (week 42) — the PUBLICATION GATE runs LAST: the carry-out
# surface becomes a gated input. The evidence archive is packed (EvAudit.pack —
# the one derivation), zipped deterministically (two zips = identical bytes),
# uploaded to the PUBLISHED release, and then the ROUNDTRIP is what gets
# verified: the published bytes are fetched back, their sha256 re-derived from
# what was DOWNLOADED (a tampered or partial upload bites), the fetched
# archive is audited in isolation by EvAudit.check itself, and the cumulative
# patch from the last published state (v2.7-week33) is proven to apply to that
# base in a local clone. The owner's credential is a fail-closed condition
# (publish.credential — no publication without it, no pretending), and inside
# the drill the stage omits itself (INNER_OMIT): the clean clone carries no
# credentials and the drill never touches the network (the W38 law).
stage_pubsync() { PUBVERIFY_TOKEN_FILE="${PUBVERIFY_TOKEN_FILE:-${NEOKEY_PUBVERIFY_TOKEN_FILE:-.secrets/pubsync.token}}" ci/pubsync_gate.sh --verify; }
if [ "${NEOKEY_REBUILD_INNER:-0}" = "1" ]; then
  note "REBUILD_INNER: the pubsync stage omits itself inside the inner run (the clone carries no credentials and the drill never touches the network — the omission is a note, not a row)"   # INNER_OMIT
elif [ "${SKIP_BUILD:-0}" = "1" ]; then
  skip_stage "pubsync" "SKIP_BUILD=1 (JVM gates only — the publication needs the run's own evidence)"   # INNER_OMIT
elif ! grep -q '^PUBSYNC_GATE=OK' build/release/gates.env 2>/dev/null; then
  skip_stage "pubsync" "no publication state yet — the standalone gate establishes it (the weekly flow: ci/pubsync_gate.sh, then the pipeline's --verify stage audits it); a red log publishes nothing"   # INNER_OMIT
elif ! grep -q "^PUBSYNC_WEEK=$WEEK$" build/release/gates.env 2>/dev/null; then
  skip_stage "pubsync" "the publication state is from an earlier week — the standalone gate establishes THIS week's (the weekly flow: the gate publishes, the pipeline audits; last week's state must not red this week's run)"   # INNER_OMIT
else
  run_stage "pubsync" stage_pubsync   # INNER_OMIT (guarded above: no credential inside the drill — W42)
fi

# ---- 13: phase 9 W2 (week 43) — the OWNER FETCH VERIFIER runs LAST: any
# published release verified from ANY machine without the write credential
# (docs/PHASE9-W1-REPORT.md §5 derives the week from what W1 measured). The
# chain (the ONE-derivation law — PubSync/EvAudit themselves, no
# reimplementation): the statement's laws + the sha re-derived from the
# FETCHED bytes (PubSync.verify) → the fetched archive unzipped (PubSync.unzip)
# and audited in isolation (EvAudit.check --expect-vc/vn) → the RECONSTRUCTION:
# the fetched cumulative patch applied to the published base (v2.7-week33)
# must rebuild HEAD's tree BYTE-EXACTLY (tree oid == tree oid — the patch's
# proof needs no recorded sha). The RECORD anchors the statement against the
# consistent forger: the committed proof line carries the full published sha —
# whoever can write the release cannot rewrite the git the owner cloned;
# inside the publishing week itself the fresh ledger anchors (record=git|ledger
# disclosed). READ-ONLY is a law on the tool itself: a runtime self-audit
# refuses a verifier whose own surface carries a write verb, and the read
# credential (PUBVERIFY_TOKEN_FILE) is fail-closed in the remote mode. The
# pipeline's stage runs the LOCAL mode (no network, no credential — the same
# laws against this clone's publication state); the battery lives in the
# standalone runs and the saved proof carries it. Inside the drill the stage
# omits itself (INNER_OMIT: the clean clone carries no publication state by
# law — a note, no row).
stage_ownerverify() { OWNERVERIFY_SKIP_BATTERY=1 ci/ownerverify_gate.sh; }
if [ "${NEOKEY_REBUILD_INNER:-0}" = "1" ]; then
  note "REBUILD_INNER: the owner-verify stage omits itself inside the inner run (the clean clone carries no publication state by law — the omission is a note, not a row)"   # INNER_OMIT
elif ! grep -q "^PUBSYNC_WEEK=$WEEK$" build/release/gates.env 2>/dev/null; then
  skip_stage "ownerverify" "no publication state for THIS week — the standalone gate establishes it (the weekly flow: ci/pubsync_gate.sh publishes, ci/ownerverify_gate.sh verifies, then the pipeline's stage audits the state); last week's state must not red this week's run"   # INNER_OMIT
else
  run_stage "ownerverify" stage_ownerverify   # INNER_OMIT (guarded above: no publication state inside the drill — W43)
fi

# ---- summary ------------------------------------------------------------------
note ""
note "════════════════ PIPELINE SUMMARY ════════════════"
for i in "${!NAMES[@]}"; do note "  $(printf '%-8s' "${NAMES[i]}") ${VERDICTS[i]}"; done
note "  tests: $(grep -o 'RESULT [0-9]* passed, [0-9]* failed' "$LOG" | awk '{p+=$2; f+=$4} END {print p+0 " passed, " f+0 " failed"}')"
note "═══════════════════════════════════════════════════"
if [ "$FAILED" = "0" ]; then note "PIPELINE_OK"; else note "PIPELINE_FAILED"; exit 1; fi
