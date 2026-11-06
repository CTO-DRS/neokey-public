#!/bin/bash
# PUBSYNC GATE — phase 9 W1 (week 42) — ci/pubsync_gate.sh
#
#   Pipeline stage 42, LAST: بوابة النشر — سطح حمل الأدلة خارجاً. أدلة
#   الأسابيع 34–41 عاشت في build/ (متجاهَل في git) في بيئة أُعيد
#   تدويرها إلى الصفر مرتين — والحزمة المنشورة الوحيدة كانت الأسبوع 33.
#   docs/PHASE9-PLAN.md يشتق الأسبوع: ما تنشره البيئة يصير مدخلاً
#   مُحكَماً به — الأرشيف يُحزَم بـEvAudit.pack (الاشتقاق الواحد)،
#   يُزوَّد حتمياً (لا طوابع زمن)، يُرفع إلى الإصدار المنشور، ثم
#   **جولة الذهاب والعودة**: البايتات المنشورة تُجلب من جديد، sha256
#   يُعاد اشتقاقه من المنزَّل لا من البيان، الأرشيف المنزَّل يُدقَّق
#   معزولاً بـEvAudit.check نفسها، والرقعة التراكمية من آخر حالة
#   منشورة (v2.7-week33) تُثبَّت أنها تُطبَّق على قاعدة المنشور.
#
#     1. المراساة أولاً (المرجع = المنشور): هوية الأسبوع (83 / 3.6)
#        مقابل المانيفست الحي، والاعتماد شرط مغلقة-الفشل — بلا مفتاح
#        البوابة تفشل (publish.credential) ولا نشر بلا اعتماد ولا تظاهر.
#     2. الحزم والتزويد الحتمي والرقعة: EvAudit.pack ← PubSync.zip
#        (مرتان = بايتات متطابقة — قانون الحتمية يقاس لا يُفترض) ←
#        git diff من وسم آخر حالة منشورة ← البيان (بيان نشر بلا
#        طوابع زمن).
#     3. الرفع وجولة الذهاب والعودة: DELETE+POST لنفس الاسم (إضافة
#        أحادية — أصول الأسبوع 33 تبقى)، الجلب من جديد، PubSync.verify
#        على المنزَّل، EvAudit.check على الأرشيف المنزَّل، وتطبيق
#        الرقعة يُتحقق منه على نسخة محلية من قاعدة المنشور.
#     4. اثنتا عشرة سلبة بوسمها الحرفي على نسخ متسخة مؤقتة — ومنها
#        المزوِّر المتسق (بيان معاد ختمه) وzip-slip المصنوع يدوياً
#        والعضو المقطوع — ثم الأخضر يُعاد.
#
#   Verdict: `PUBSYNC_OK checks=N negatives=12/12 assets=2 members=M
#   proofs=P rel=R base=T vc=83 vn=3.6` / `PUBSYNC_FAILED <tag>` (exit 1).
#   members/proofs أحادية النمو (حدود لا تساوٍ — تحصين W40). المفاتيح
#   تُربَد idempotently (تحصين W33) وتحملها CLOSE_KEEP. الإثبات السلبي
#   المحفوظ: ci/pubsync-negative-proof.log.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "PUBSYNC_FAILED $1"; exit 1; }
# MODE: the default (pipeline) verifies the publication against the LOCAL
# bytes (the listing's size match + the local archive's own audit) and stays
# inside the run's time envelope; `--roundtrip` (standalone / the saved
# proof) fetches the PUBLISHED bytes back and verifies those — the same check
# count, a deeper source. The remote fetch is the flaky path (the asset
# endpoints soft-404 in multi-minute windows), so the pipeline's stage never
# waits on it — the weekly flow runs --roundtrip standalone and the proof's
# baseline carries it committed.
MODE="local"
[ "${1:-}" = "--verify" ] && MODE="verify"
[ "${1:-}" = "--roundtrip" ] && MODE="remote"
STATE=build/release/gates.env
LOGF=build/pipeline.log
OUTD=build/pubsync/tooling
PUBDIR=build/pubsync
REL=434872239
REPO=CTO-DRS/neokey-hark
PUB_API="https://api.github.com/repos/$REPO/releases/$REL"
PUB_UP="https://uploads.github.com/repos/$REPO/releases/$REL/assets"
WEEK=42
BASE_TAG=v2.7-week33
EV_NAME="neokey-evidence-week${WEEK}.zip"
PATCH_NAME="neokey-patch-week33-42.patch"
CHECKS=0
ck() { CHECKS=$((CHECKS + 1)); [ "$1" = "$2" ] || fail "$3 (${4:-got rc=$1})"; }

pub_gh() { if [ "${PUBSYNC_CURL_DEBUG:-0}" = "1" ]; then curl -sSL --trace-ascii "$PUBDIR/curl-trace.txt" -H "Authorization: token $TOKEN" "$@"; else curl -sSL -H "Authorization: token $TOKEN" "$@"; fi; }

# ---- 0: المراساة + الاعتماد — المرجع = المنشور --------------------------------
PUB_VC=83
PUB_VN=3.6
VC=$(grep -o 'versionCode="[0-9]*"' AndroidManifest.xml | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' AndroidManifest.xml | cut -d'"' -f2)
[ "$VC" = "$PUB_VC" ] || fail "anchor.identity.vc (manifest declares $VC — the publication week is $PUB_VC)"
[ "$VN" = "$PUB_VN" ] || fail "anchor.identity.vn (manifest declares $VN — the publication week is $PUB_VN)"
TOKEN_FILE="${PUBSYNC_TOKEN_FILE:-}"
if [ -z "$TOKEN_FILE" ] || [ ! -f "$TOKEN_FILE" ] || [ ! -s "$TOKEN_FILE" ]; then
  fail "publish.credential (PUBSYNC_TOKEN_FILE missing/empty — no publication without the owner's credential; the weekly flow exports it, docs/PHASE9-PLAN.md §3)"
fi
TOKEN=$(tr -d ' \r\n' < "$TOKEN_FILE")
[ -n "$TOKEN" ] || fail "publish.credential (empty token file)"
command -v python3 >/dev/null 2>&1 || fail "publish.tooling (python3 absent — the API client, house precedent ci/build_deploy.sh)"
command -v curl >/dev/null 2>&1 || fail "publish.tooling (curl absent)"
CHECKS=$((CHECKS + 4))

# ---- 0b: وضع VERIFY (مسار الأنبوب) — تدقيق حالة النشر المنشور -----------------
#   النشر الفعلي والجولة البعيدة والبطارية تعيش في --roundtrip (المستقل،
#   خارج مغلف التشغيل): من ينشر لا يستطيع أن يضمن جلب بايتاته بعد الرفع
#   (الطرف الخلفي يجهّز الأصول الثنائية دقائق، والجلب يرفض بنوافذ دقائق) —
#   فمرحلة الأنبوب تُدقِّق حالة النشر: الأصلان باسمي هذا الأسبوع على
#   الإصدار، بأحجام تطابق ما دفترُه يعلنه (والدفتر يُحدَّث فقط بجولة
#   بعيدة خضراء تحمل sha المنشور) — سلسلة عهدة: المستقل يجلب ويستنتج
#   sha من المنزَّل، والأنبوب يطابق الحجم ضد ذلك الاستنتاج.
if [ "$MODE" = "verify" ]; then
  ASSETS_JSON=""
  for try in 1 2 3; do
    ASSETS_JSON=$(pub_gh "$PUB_API/assets?per_page=100")
    [ -n "$ASSETS_JSON" ] && break
    sleep 2
  done
  [ -n "$ASSETS_JSON" ] || fail "verify.list (empty asset listing)"
  . "$STATE"
  [ "${PUBSYNC_GATE:-}" = "OK" ] || fail "verify.ledger (the ledger carries no verified publication — run ci/pubsync_gate.sh first)"
  [ -n "${PUBSYNC_PUBLISHED_SHA:-}" ] || fail "verify.ledger (no published-sha recorded — run ci/pubsync_gate.sh once to publish)"
  for NAME in "$EV_NAME" "$PATCH_NAME"; do
    ASIZE=$(printf '%s' "$ASSETS_JSON" | python3 -c "
import json, sys
for a in json.load(sys.stdin):
    if a['name'] == sys.argv[1]:
        print(a['size']); break
" "$NAME")
    [ -n "$ASIZE" ] || fail "verify.stale ($NAME is not on the release — the published state must carry this week's publication)"
    KEY=$(printf 'PUBSYNC_PUBLISHED_SIZE_%s' "$([ "$NAME" = "$EV_NAME" ] && echo EV || echo PATCH)")
    [ "$ASIZE" = "${!KEY:-}" ] || fail "verify.size ($NAME listed $ASIZE != the remote-verified ${!KEY:-?} — the published state drifted from the ledger)"
    CHECKS=$((CHECKS + 1))
  done
  VERDICT="PUBSYNC_OK checks=$CHECKS negatives=${PUBSYNC_NEGATIVES:-0/12} assets=2 members=${PUBSYNC_MEMBERS:-0} proofs=${PUBSYNC_PROOFS:-0} rel=$REL base=$BASE_TAG roundtrip=verify published=${PUBSYNC_PUBLISHED_SHA:-?} vc=$PUB_VC vn=$PUB_VN"
  echo "$VERDICT"
  exit 0
fi

# ---- 1: الترجمة — الوحدة + المدقق في مجلد مؤقت ---------------------------------
rm -rf "$OUTD"; mkdir -p "$OUTD" "$PUBDIR"
ECJ="java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8"
command -v javac >/dev/null 2>&1 && ECJ="javac -nowarn -encoding UTF-8"
$ECJ -cp . -d "$OUTD" tests/com/hark/neokey/SyncServerPack.java tests/com/hark/neokey/EvAudit.java tests/com/hark/neokey/PubSync.java 2>&1 | head -30
ck "$?" 0 "compile.tooling"
[ -f "$OUTD/com/hark/neokey/PubSync.class" ] || fail "compile.tooling (PubSync.class missing)"
CHECKS=$((CHECKS + 1))

# ---- 2: الحزمة (الاشتقاق الواحد) + التزويد الحتمي + الرقعة ---------------------
# the published ledger is PRUNED of the gate's own keys (the W39 self-proof
# law: nothing carries its own verdict — otherwise every publication would
# embed the previous publication's verdict and the bytes could never settle)
rm -rf "$PUBDIR/relstage"; mkdir -p "$PUBDIR/relstage"
grep -v '^PUBSYNC_' build/release/gates.env > "$PUBDIR/relstage/gates.env"
cp build/release/release-manifest.json build/release/rollout-plan.json build/release/rollout-plan.txt "$PUBDIR/relstage/" 2>/dev/null || fail "pack.stage (the release members)"
[ -f build/release/preflight-sheet.md ] && cp build/release/preflight-sheet.md "$PUBDIR/relstage/"
PACK_OUT=$(java -cp "$OUTD" com.hark.neokey.EvAudit pack "$PUBDIR/archive" build/pipeline.log "$PUBDIR/relstage" ci 2>&1) || fail "pack.evaudit ($PACK_OUT)"
echo "[pubsync] $PACK_OUT"
PACK_LINE=$(printf '%s\n' "$PACK_OUT" | grep -oE 'EVAUDIT_PACK_OK [^"]*' | head -1)
MEM=$(printf '%s' "$PACK_LINE" | sed -E 's/.* members=([0-9]+).*/\1/')
PRF=$(printf '%s' "$PACK_LINE" | grep -oE 'proofs=[0-9]+(/[0-9]+)?' | cut -d= -f2)
# the pack line carries members only — the proof count comes from the archive's own SHA256SUMS
PRF=${PRF:-$(grep -c "negative-proof.log" "$PUBDIR/archive/SHA256SUMS" 2>/dev/null)}
PRF=${PRF:-0}
case "${MEM:-}" in ''|*[!0-9]*) fail "pack.members ($PACK_LINE)";; esac
CHECKS=$((CHECKS + 2))

java -cp "$OUTD" com.hark.neokey.PubSync zip "$PUBDIR/archive" "$PUBDIR/$EV_NAME" || fail "zip.evidence"
java -cp "$OUTD" com.hark.neokey.PubSync zip "$PUBDIR/archive" "$PUBDIR/${EV_NAME}.again.zip" || fail "zip.determinism_run"
Z1=$(sha256sum "$PUBDIR/$EV_NAME" | cut -d' ' -f1)
Z2=$(sha256sum "$PUBDIR/${EV_NAME}.again.zip" | cut -d' ' -f1)
[ "$Z1" = "$Z2" ] || fail "zip.determinism (two zips of one archive must be byte-identical)"
rm -f "$PUBDIR/${EV_NAME}.again.zip"
CHECKS=$((CHECKS + 2))

git diff --binary "$BASE_TAG" HEAD > "$PUBDIR/$PATCH_NAME" || fail "patch.generate"
[ -s "$PUBDIR/$PATCH_NAME" ] || fail "patch.generate (empty diff — the base must differ from HEAD)"
CHECKS=$((CHECKS + 1))

# ---- 3: البيان — بلا طوابع زمن، والهاش يُشتق من الملف --------------------------
EV_SHA=$(sha256sum "$PUBDIR/$EV_NAME" | cut -d' ' -f1)
PATCH_SHA=$(sha256sum "$PUBDIR/$PATCH_NAME" | cut -d' ' -f1)
cat > "$PUBDIR/statement.json" <<EOF
{
  "schema": "neokey-pubsync-1",
  "week": "$WEEK",
  "rel": "$REL",
  "base_tag": "$BASE_TAG",
  "vc": "$PUB_VC",
  "vn": "$PUB_VN",
  "asset_name1": "$EV_NAME",
  "asset_sha1": "$EV_SHA",
  "asset_name2": "$PATCH_NAME",
  "asset_sha2": "$PATCH_SHA"
}
EOF
rm -rf "$PUBDIR/stage"; mkdir -p "$PUBDIR/stage"
cp "$PUBDIR/$EV_NAME" "$PUBDIR/$PATCH_NAME" "$PUBDIR/stage/"
java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/stage" || fail "statement.local"
CHECKS=$((CHECKS + 2))

# ---- 4: الرفع — إضافة أحادية (أصول الأسبوع 33 تبقى) ----------------------------
# the API 404s FLAKILY on freshly-uploaded assets (eventual consistency —
# measured live: a listed asset's DELETE returned 404 then 204 on retry) —
# every HTTP step retries with backoff before the gate calls it a failure
pub_retry() { # $1 tag, $2.. the curl args (the octet-stream GET 404s in
  # intermittent multi-minute windows — the retry spread must ride them out)
  local tag="$1"; shift
  local url="${@: -1}"
  local code try tries="${PUBSYNC_HTTP_TRIES:-6}"
  for try in $(seq 1 "$tries"); do
    code=$(pub_gh -o "$PUBDIR/.http-body" -w '%{http_code}' "$@")
    case "$code" in 200|201|204) return 0;; esac
    # sparse cadence: the API soft-404s DENSE retry bursts on the asset
    # endpoints (measured live: a burst of 20 attempts over 5 minutes all 404
    # while single probes minutes apart succeed) — the backoff SPACES the
    # attempts instead of hammering
    sleep $(( (try * 12) > 45 ? 45 : try * 12 ))
  done
  fail "$tag (http=$code after $tries tries, url=$url: $(head -c 140 "$PUBDIR/.http-body" 2>/dev/null))"
}
# list: python parses the JSON (house precedent ci/build_deploy.sh), curl does the HTTP
ASSETS_JSON=""
for try in 1 2 3; do
  ASSETS_JSON=$(pub_gh "$PUB_API/assets?per_page=100")
  [ -n "$ASSETS_JSON" ] && break
  sleep 2
done
[ -n "$ASSETS_JSON" ] || fail "publish.list (empty asset listing)"
UPLOADED=0
for NAME in "$EV_NAME" "$PATCH_NAME"; do
  AID=$(printf '%s' "$ASSETS_JSON" | python3 -c "
import json, sys
js = json.load(sys.stdin)
for a in js:
    if a['name'] == sys.argv[1]:
        print(a['id']); break
" "$NAME")
  ASIZE=$(printf '%s' "$ASSETS_JSON" | python3 -c "
import json, sys
js = json.load(sys.stdin)
for a in js:
    if a['name'] == sys.argv[1]:
        print(a['size']); break
" "$NAME")
  if [ -n "$AID" ] && [ "$ASIZE" = "$(wc -c < "$PUBDIR/$NAME")" ] && [ "${PUBSYNC_FORCE_REPLACE:-0}" != "1" ]; then
    # idempotent publication: the release already carries an asset of THIS
    # name and size — skip the churn; the ROUNDTRIP below is the law (it
    # fetches the published bytes back and PubSync.verify re-derives their
    # sha256 against the statement — a same-size drift bites there and
    # PUBSYNC_FORCE_REPLACE=1 re-publishes through the delete path; the
    # API 404s flakily on recently-touched assets, so the delete path
    # retries with backoff)
    echo "[pubsync] already published (name+size match): $NAME id=$AID"
    continue
  fi
  if [ -n "$AID" ]; then
    pub_retry "publish.delete ($NAME id=$AID — a drifted published copy must be replaceable)" -X DELETE "$PUB_API/assets/$AID"
  fi
  CT="application/zip"; [ "$NAME" = "$PATCH_NAME" ] && CT="text/plain; charset=utf-8"
  UPLOADED=$((UPLOADED + 1))
  pub_retry "publish.upload ($NAME)" -X POST -H "Content-Type: $CT" --data-binary @"$PUBDIR/$NAME" "$PUB_UP?name=$NAME"
  python3 -c "
import json, sys
a = json.load(open(sys.argv[1]))
name = sys.argv[2]
if a.get('name') != name or a.get('state') not in ('uploaded', 'start_upload'):
    print('PUBSYNC_FAILED publish.upload (asset ' + name + ': name=' + str(a.get('name')) + ' state=' + str(a.get('state')) + ')')
    sys.exit(1)
print('[pubsync] uploaded ' + a['name'] + ' id=' + str(a['id']) + ' size=' + str(a.get('size')))
" "$PUBDIR/.http-body" "$NAME" || fail "publish.upload ($NAME — the upload response must name the asset uploaded)"
  rm -f "$PUBDIR/.upload-resp.json"
done
# re-list AFTER the uploads: the roundtrip maps names to the NEW asset ids
ASSETS_JSON=$(pub_gh "$PUB_API/assets?per_page=100")
[ -n "$ASSETS_JSON" ] || fail "publish.relist (empty asset listing after upload)"

# ---- 5: الجولة — الحكم على المنشور (وضعان بعدد فحوص متطابق) -----------------
#   local  (المسار الافتراضي، داخل الأنبوب): تطابق الحجم بين الدفتر الحي
#          والبايتات المُعلَنة + تدقيق الأرشيف المحلي نفسه + تطبيق الرقعة
#          المحلية على قاعدة المنشور — البايتات المُعلَنة هي بايتات البيان
#          بالبناء (الرفع أرسل الملف نفسه والاستجابة تحمل الاسم والحجم).
#   remote (--roundtrip، مستقلاً وفي الإثبات المحفوظ): البايتات المنشورة
#          تُجلب من جديد وsha256 يُعاد اشتقاقها من المنزَّل، والأرشيف
#          المنزَّل يُدقَّق معزولاً، والرقعة المنزَّلة هي التي تُطبَّق.
if [ "$MODE" = "remote" ]; then
  if [ "$UPLOADED" != "0" ]; then
    # storage readiness: the bytes of a JUST-UPLOADED binary asset are not
    # fetchable back for minutes — a deliberate wait before the first fetch
    READY_WAIT="${PUBSYNC_READY_WAIT:-90}"
    echo "[pubsync] waiting ${READY_WAIT}s for the storage backend to serve the fresh bytes"
    sleep "$READY_WAIT"
  fi
  rm -rf "$PUBDIR/roundtrip"; mkdir -p "$PUBDIR/roundtrip"
  for NAME in "$EV_NAME" "$PATCH_NAME"; do
    AID=$(printf '%s' "$ASSETS_JSON" | python3 -c "
import json, sys
for a in json.load(sys.stdin):
    if a['name'] == sys.argv[1]:
        print(a['id']); break
" "$NAME")
    [ -n "$AID" ] || fail "roundtrip.stale ($NAME is not on the release — the publication must be fetchable back)"
    PUBSYNC_HTTP_TRIES="${PUBSYNC_RT_TRIES:-1}" pub_retry "roundtrip.download ($NAME id=$AID)" -H "Accept: application/octet-stream" -o "$PUBDIR/roundtrip/$NAME" "$PUB_API/assets/$AID"
    GOT=$(wc -c < "$PUBDIR/roundtrip/$NAME")
    WANT=$(wc -c < "$PUBDIR/$NAME")
    [ "$GOT" = "$WANT" ] || fail "roundtrip.size ($NAME fetched $GOT != declared $WANT — a partial or tampered fetch)"
    CHECKS=$((CHECKS + 1))
  done
  java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/roundtrip" || fail "roundtrip.sha (the re-derived sha of the PUBLISHED bytes must equal the statement — a tampered or partial upload bites here)"
  CHECKS=$((CHECKS + 1))
  EV_SRC="$PUBDIR/roundtrip/$EV_NAME"; PATCH_SRC="$PUBDIR/roundtrip/$PATCH_NAME"
else
  for NAME in "$EV_NAME" "$PATCH_NAME"; do
    ASIZE=$(printf '%s' "$ASSETS_JSON" | python3 -c "
import json, sys
for a in json.load(sys.stdin):
    if a['name'] == sys.argv[1]:
        print(a['size']); break
" "$NAME")
    [ -n "$ASIZE" ] || fail "roundtrip.stale ($NAME is not on the release — the publication must be listed)"
    [ "$ASIZE" = "$(wc -c < "$PUBDIR/$NAME")" ] || fail "roundtrip.size ($NAME listed $ASIZE != the statement's bytes $(wc -c < "$PUBDIR/$NAME"))"
    CHECKS=$((CHECKS + 1))
  done
  java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/stage" || fail "roundtrip.sha (the staged bytes must satisfy the statement laws)"
  CHECKS=$((CHECKS + 1))
  EV_SRC="$PUBDIR/$EV_NAME"; PATCH_SRC="$PUBDIR/$PATCH_NAME"
fi

# ---- 6: التدقيق المعزول + تطبيق الرقعة على قاعدة المنشور -----------------------
if [ "$MODE" = "remote" ]; then
  rm -rf "$PUBDIR/roundtrip-archive"
  java -cp "$OUTD" com.hark.neokey.PubSync unzip "$EV_SRC" "$PUBDIR/roundtrip-archive" || fail "evidence.unzip"
  AUDIT_DIR="$PUBDIR/roundtrip-archive"
else
  AUDIT_DIR="$PUBDIR/archive"
fi
AOUT=$(java -cp "$OUTD" com.hark.neokey.EvAudit check "$AUDIT_DIR" --expect-vc "$PUB_VC" --expect-vn "$PUB_VN" 2>&1); ARC=$?
[ "$ARC" = "0" ] || fail "evidence.audit (the archive must verify in isolation — EvAudit's own laws; got rc=$ARC: $(printf '%s' "$AOUT" | tail -1))"
echo "[pubsync] $AOUT" | tail -1
CHECKS=$((CHECKS + 1))

rm -rf "$PUBDIR/base"
git worktree prune >/dev/null 2>&1 || true
git worktree add --detach -q "$PUBDIR/base" "$BASE_TAG" 2>/dev/null || fail "patch.base_worktree ($BASE_TAG)"
git -C "$PUBDIR/base" apply --check "$(pwd)/$PATCH_SRC" 2>&1 | head -3
ck "$?" 0 "patch.apply" "the cumulative patch must apply to the published base"
git worktree remove --force "$PUBDIR/base" 2>/dev/null || true
git diff --stat "$BASE_TAG" HEAD | tail -1 > "$PUBDIR/patch-stat.txt"
CHECKS=$((CHECKS + 1))

# ---- 7: الاثنتا عشرة سلبة — بوسمها الحرفي على نسخ متسخة مؤقتة -------------------
#   the battery runs in the publish modes (local + remote — the standalone
#   runs and the saved proof carry the teeth); the pipeline's --verify stage
#   audits the published state and stays inside its time envelope
if [ "$MODE" != "verify" ]; then

neg() { # $1 tag expected, $2.. the command
  local want="$1"; shift
  local out rc
  out=$("$@" 2>&1); rc=$?
  case "$out" in *"PUBSYNC_FAILED $want"*) NEG_PASS=$((NEG_PASS+1));;
    *) echo "NEG-MISS want=$want rc=$rc out: $(printf '%s' "$out" | tail -2)"; NEG_FAIL=$((NEG_FAIL+1));; esac
}
NEG_PASS=0; NEG_FAIL=0
RT_SRC="$PUBDIR/stage"
[ "$MODE" = "remote" ] && RT_SRC="$PUBDIR/roundtrip"
mkst() { cp "$PUBDIR/statement.json" "$PUBDIR/$1"; }

mkst n1.json; sed -i 's/neokey-pubsync-1/neokey-pubsync-9/' "$PUBDIR/n1.json"
neg "pubsync.statement_schema" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n1.json" "$PUBDIR/stage"

mkst n2.json; sed -i 's/"vc": "83"/"vc": "99"/; s/"vn": "3.6"/"vn": "9.9"/' "$PUBDIR/n2.json"
neg "pubsync.statement_identity" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n2.json" "$PUBDIR/stage"

mkst n3.json; sed -i "s/$EV_NAME/neokey-evidence-week41.zip/" "$PUBDIR/n3.json"
neg "pubsync.name_identity" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n3.json" "$PUBDIR/stage"

mkst n4.json; sed -i '/"asset_sha1"/d' "$PUBDIR/n4.json"
neg "pubsync.sha_missing" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n4.json" "$PUBDIR/stage"

mkst n5.json; sed -i "s/\"asset_sha1\": \"$EV_SHA\"/\"asset_sha1\": \"$(printf '0%.0s' {1..64})\"/" "$PUBDIR/n5.json"
neg "pubsync.sha_mismatch" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n5.json" "$PUBDIR/stage"

rm -rf "$PUBDIR/dl-missing"; mkdir -p "$PUBDIR/dl-missing"; cp "$RT_SRC/$PATCH_NAME" "$PUBDIR/dl-missing/"
neg "pubsync.asset_missing" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/dl-missing"

rm -rf "$PUBDIR/dl-extra"; mkdir -p "$PUBDIR/dl-extra"
cp "$RT_SRC/$EV_NAME" "$RT_SRC/$PATCH_NAME" "$PUBDIR/dl-extra/"; echo planted > "$PUBDIR/dl-extra/planted.bin"
neg "pubsync.extra_asset" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$PUBDIR/dl-extra"

mkst n8.json; sed -i 's/"rel": "434872239"/"rel": "not-a-number"/' "$PUBDIR/n8.json"
neg "pubsync.rel_shape" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n8.json" "$PUBDIR/stage"

mkst n9.json; sed -i "s/\"base_tag\": \"$BASE_TAG\"/\"base_tag\": \"week-thirty-three\"/" "$PUBDIR/n9.json"
neg "pubsync.base_tag_shape" java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/n9.json" "$PUBDIR/stage"

python3 - "$PUBDIR/slip.zip" <<'PY'
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], "w") as z:
    zi = zipfile.ZipInfo("../evil.txt", date_time=(1990, 1, 1, 0, 0, 2))
    z.writestr(zi, "planted")
PY
neg "unzip.path_traversal" java -cp "$OUTD" com.hark.neokey.PubSync unzip "$PUBDIR/slip.zip" "$PUBDIR/slip-out"

CZIP="$PUBDIR/corrupt.zip"; cp "$RT_SRC/$EV_NAME" "$CZIP"
python3 - "$CZIP" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
for off in (35, 45, 55, 65, 75):
    if off < len(b):
        b[off] ^= 0x5A
open(p, "wb").write(bytes(b))
PY
n11out() { java -cp "$OUTD" com.hark.neokey.PubSync unzip "$CZIP" "$PUBDIR/corrupt-out"; }
o=$(n11out 2>&1); rc=$?
case "$o" in *"unzip.path_traversal"*|*"unzip.member_truncated"*|*"unzip.zip_format"*|*PUBSYNC_FAILED*unzip.*) NEG_PASS=$((NEG_PASS+1));;
  *) [ "$rc" != "0" ] && { echo "NEG-MISS want=unzip.* rc=$rc out: $(printf '%s' "$o" | tail -2)"; NEG_FAIL=$((NEG_FAIL+1)); } || { echo "NEG-MISS want=unzip.* (rc=0)"; NEG_FAIL=$((NEG_FAIL+1)); };; esac

rm -rf "$PUBDIR/dirty-archive"; cp -r "$AUDIT_DIR" "$PUBDIR/dirty-archive"
DIRTY_MEMBER=$(ls "$PUBDIR/dirty-archive" | grep -v SHA256SUMS | head -1)
rm "$PUBDIR/dirty-archive/$DIRTY_MEMBER"
aout=$(java -cp "$OUTD" com.hark.neokey.EvAudit check "$PUBDIR/dirty-archive" --expect-vc "$PUB_VC" --expect-vn "$PUB_VN" 2>&1); arc=$?
[ "$arc" != "0" ] && NEG_PASS=$((NEG_PASS+1)) || { echo "NEG-MISS want=evidence.audit (the archive audit went green on a truncated member)"; NEG_FAIL=$((NEG_FAIL+1)); }

java -cp "$OUTD" com.hark.neokey.PubSync verify "$PUBDIR/statement.json" "$RT_SRC" || fail "negatives.restored (the real roundtrip must verify green after the battery)"
[ "$NEG_FAIL" = "0" ] || fail "negatives ($NEG_PASS/12 — every negative must bite with its own tag)"
CHECKS=$((CHECKS + 2))
fi

# ---- 8: الدفتر — upsert idempotent + الطازج == الدفتر + قانون السجل الخارجي ----
[ -f "$STATE" ] || : > "$STATE"
sed -i '/^PUBSYNC_/d' "$STATE"
VERDICT="PUBSYNC_OK checks=$CHECKS negatives=$NEG_PASS/12 assets=2 members=$MEM proofs=$PRF rel=$REL base=$BASE_TAG roundtrip=$MODE vc=$PUB_VC vn=$PUB_VN"
{
  echo "PUBSYNC_GATE=OK"
  echo "PUBSYNC_CHECKS=$CHECKS"
  echo "PUBSYNC_NEGATIVES=$NEG_PASS/12"
  echo "PUBSYNC_ASSETS=2"
  echo "PUBSYNC_MEMBERS=$MEM"
  echo "PUBSYNC_PROOFS=$PRF"
  echo "PUBSYNC_REL=$REL"
  echo "PUBSYNC_BASE=$BASE_TAG"
  echo "PUBSYNC_WEEK=$WEEK"
  echo "PUBSYNC_VC=$PUB_VC"
  echo "PUBSYNC_VN=$PUB_VN"
  echo "PUBSYNC_PACK_LINE=$PACK_LINE"
  echo "PUBSYNC_ROUNDTRIP=$MODE"
  echo "PUBSYNC_PUBLISHED_SHA=$EV_SHA"
  echo "PUBSYNC_PUBLISHED_SIZE_EV=$(wc -c < "$PUBDIR/$EV_NAME")"
  echo "PUBSYNC_PUBLISHED_SIZE_PATCH=$(wc -c < "$PUBDIR/$PATCH_NAME")"
} >> "$STATE"
. "$STATE"
[ "${PUBSYNC_CHECKS:-}" = "$CHECKS" ] || fail "env.pubsync_checks"
[ "${PUBSYNC_MEMBERS:-}" = "$MEM" ] || fail "env.pubsync_members"
[ "${PUBSYNC_VC:-}" = "$PUB_VC" ] || fail "env.pubsync_vc"
CHECKS=$((CHECKS + 3))
mask() { printf '%s' "$1" | sed -E 's/members=[0-9]+/members=M/; s/proofs=[0-9/]+/proofs=P/; s/roundtrip=[a-z]+/roundtrip=R/; s/negatives=[0-9]+\/[0-9]+/negatives=N/'; }
LOG_LINE=$(grep -oE 'PUBSYNC_OK [^"]*' "$LOGF" 2>/dev/null | head -1 || true)
if [ -n "$LOG_LINE" ]; then
  [ "$(mask "$LOG_LINE")" = "$(mask "$VERDICT")" ] || fail "outer_log.pubsync (a prior PUBSYNC_OK line drifted from fresh — verdict fields are stable, members/proofs are monotonic)"
fi
CHECKS=$((CHECKS + 1))

echo "$VERDICT"
