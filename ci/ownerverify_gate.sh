#!/bin/bash
# OWNERVERIFY GATE — phase 9 W2 (week 43) — ci/ownerverify_gate.sh
#
#   Pipeline stage 43, LAST: مُتحقِّق الجلب للمالك — أي إصدار منشور
#   يُدقَّق من أي جهاز بلا اعتماد الكتابة. docs/PHASE9-W1-REPORT.md §5
#   يشتق الأسبوع من ما قاسته W1: النشر صار مدخلاً مُحكَماً به لكن
#   سلسلة التحقق نفسها كانت تعيش خلف اعتماد الكتابة وداخل بيئة النشر —
#   والبيان (shas + الهوية) كان محلياً لا يصل إلى يد المالك على جهاز
#   آخر. W2 يكمل السطر:
#
#     1. البيان يُنشَر أصلاً ثالثاً (pubsync W2): المالك يجلب البيان
#        والأصلين معاً — sha البيان نفسه مستحيل ذاتياً (قانون الإثبات
#        الذاتي W39: لا شيء يحمل حكمه الخاص)، فكل حقل فيه يُرسَى
#        انتقالياً: sha الأرشيف ضد السجل، sha الرقعة ضد إعادة البناء.
#     2. السجل هو المرساة ضد المزوِّر المتسق (بيان معاد ختمه + أصول
#        معاد حزمها): سطر الحكم الأخضر المحفوظ في git يحمل sha الأرشيف
#        الكامل — من يكتب على الإصدار لا يكتب في تاريخ git الذي
#        استنسخه المالك. خلال أسبوع النشر نفسه يُرسَى الدفتر المحلي
#        (حالة النشر الطازجة)؛ وبعد التعهيد وعلى أي جهاز آخر الترسو
#        على git — الحقل record=git|ledger في الحكم يصرّح بالمصدر.
#     3. القراءة فقط: الأداة GET لا غير — فحص ذاتي وقت التشغيل يرفض
#        الأداة التي تحمل فعلاً كتابةً في سطحها (ownercheck.readonly)،
#        والاعتماد قراءة فقط (PUBVERIFY_TOKEN_FILE) شرط مغلقة-الفشل في
#        الوضع البعيد — الفصل بين مَن ينشر ومَن يتحقق.
#     4. السلسلة (الاشتقاق الواحد — وحدة PubSync وEvAudit نفسيهما لا
#        إعادة تنفيذ): قوانين البيان + sha يُعاد اشتقاقه من المنزَّل
#        (PubSync.verify) ← فك الأرشيف المنزَّل (PubSync.unzip — رفض
#        zip-slip والمقطوع) ← تدقيق معزول (EvAudit.check --expect-vc/vn)
#        ← **إعادة البناء**: الرقعة المنزَّلة تُطبَّق على قاعدة المنشور
#        في worktree لحظي ويجب أن تعيد بناء شجرة HEAD بايت-لطرفاً
#        (oid الشجرة الناتجة == oid شجرة HEAD) — إثبات الرقعة بلا sha
#        مسجَّل: من يزوّرها فإما لا تطبق أو تبني شجرة غير HEAD.
#     5. الاثنتا عشرة سلبة بوسمها الحرفي على نسخ متسخة مؤقتة — في
#        الاستقلال (البطارية في الوضعين المحلي والبعيد المستقلين؛
#        مرحلة الأنبوب تُدقِّق الحالة والدفتر يحمل حكم البطارية — نمط
#        W1). الإثبات السلبي المحفوظ: ci/ownerverify-negative-proof.log.
#
#   Hooks (testability — نفس نمط PUBSYNC_TOKEN_FILE): OWNERVERIFY_RECORD_FILE
#   (تثبيت مصدر السجل بلا رجوع)، OWNERVERIFY_STATE_DIR (مصدر الحالة
#   المحلية)، OWNERVERIFY_MANIFEST، OWNERVERIFY_WORK_DIR،
#   OWNERVERIFY_SKIP_BATTERY، OWNERVERIFY_HTTP_TRIES.
#
#   Modes: الافتراضي local (الأنبوب: حالة النشر المحلية بلا شبكة) و
#   --remote (جهاز المالك: جلب المنشور من الإصدار). Verdict:
#   `OWNERVERIFY_OK checks=N negatives=n/12 assets=3 rel=R base=T
#   week=W mode=M record=S vc=1 vn=0.1.0` / `OWNERVERIFY_FAILED <tag>`
#   (exit 1). المفاتيح تُربَد idempotently (تحصين W33) وتحملها
#   CLOSE_KEEP.
set -uo pipefail
cd "$(dirname "$0")/.."

fail() { echo "OWNERVERIFY_FAILED $1"; exit 1; }
MODE="local"
[ "${1:-}" = "--remote" ] && MODE="remote"
STATE=build/release/gates.env
LOGF=build/pipeline.log
WORK="${OWNERVERIFY_WORK_DIR:-build/ownerverify}"
PUBDIR="${OWNERVERIFY_STATE_DIR:-build/pubsync}"
REL=434872239
REPO=CTO-DRS/neokey-hark
PUB_API="https://api.github.com/repos/$REPO/releases/$REL"
BASE_TAG=v2.7-week33
WEEK=48
EV_NAME="neokey-evidence-week${WEEK}.zip"
PATCH_NAME="neokey-patch-week33-${WEEK}.patch"
ST_NAME="neokey-pubsync-week${WEEK}-statement.json"
CHECKS=0
ck() { CHECKS=$((CHECKS + 1)); [ "$1" = "$2" ] || fail "$3 (${4:-got rc=$1})"; }

# ---- 0: المراساة — المرجع = المنشور -------------------------------------------
PUB_VC=1
PUB_VN=0.1.0
MANIFEST="${OWNERVERIFY_MANIFEST:-AndroidManifest.xml}"
VC=$(grep -o 'versionCode="[0-9]*"' "$MANIFEST" | grep -o '[0-9]*')
VN=$(grep -o 'versionName="[^"]*"' "$MANIFEST" | cut -d'"' -f2)
[ "$VC" = "$PUB_VC" ] || fail "anchor.identity.vc (manifest declares $VC — the verifier week is $PUB_VC)"
[ "$VN" = "$PUB_VN" ] || fail "anchor.identity.vn (manifest declares $VN — the verifier week is $PUB_VN)"
CHECKS=$((CHECKS + 2))

# ---- 0b: قانون القراءة-فقط — فحص ذاتي لسطح الأداة -----------------------------
#   الأداة GET لا غير: أي فعْل كتابة في نصّها (حتى قبل أن يُنفَّذ)
#   يرفضها — الفصل بين مَن ينشر ومَن يتحقق قانون مفروض على الأداة نفسها
#   لا على نيتها. النمط يُجمَّع من أجزاء وقت التشغيل — الفحص الذاتي لا
#   يحمل فعْلَه في نصّه حرفياً (القانون لا يعضّ نفسه بالشكل لا بالمضمون:
#   النسخة المزروعة بفعْل حقيقي تُصطاد، والقالب نظيف).
W1='-X '; W2='DELETE|POST|PUT'; W3='--data'; W4='-binary'; W5='-d '; W6='@'
RO_PAT="${W1}(${W2})|${W3}${W4}|${W5}${W6}"
if grep -nE -- "$RO_PAT" "$0" >/dev/null 2>&1; then
  fail "ownercheck.readonly (the verifier's own surface carries a write verb — the reader is not the publisher)"
fi
CHECKS=$((CHECKS + 1))

# ---- 0c: الأدوات ---------------------------------------------------------------
command -v java >/dev/null 2>&1 || fail "ownercheck.tooling (java absent)"
command -v curl >/dev/null 2>&1 || fail "ownercheck.tooling (curl absent)"
command -v python3 >/dev/null 2>&1 || fail "ownercheck.tooling (python3 absent)"
command -v git >/dev/null 2>&1 || fail "ownercheck.tooling (git absent)"
CHECKS=$((CHECKS + 1))

# ---- 1: السجل — المرساة ضد المزوِّر المتسق ------------------------------------
#   الأولوية: سطر PUBSYNC_OK الأخضر المحفوظ في git لهوية هذا الأسبوع
#   (سجل الإثبات الملتزَم — sha الأرشيف الكامل)؛ فإن لم يوجد (أسبوع
#   النشر نفسه قبل التعهيد) رُسِي الدفتر المحلي لحالة النشر الطازجة —
#   والحقل record=git|ledger في الحكم يصرّح بالمصدر. الخطّاف
#   OWNERVERIFY_RECORD_FILE يثبّت المصدر بلا رجوع (سطح الاختبار، وهو
#   كذلك فشل-مغلق). قانون W45: البحث يقرأ كل سجلات الإثبات الملتزَمة
#   (ci/*-negative-proof.log) — كل أسبوع يضيف سجل إثباته الجديد ويجده
#   البحث تلقائياً (عضة الاستعادة الرابعة: سجل W44 في
#   phase9-negative-proof.log كان خارج القائمة فمات --remote مغلقاً).
RECORD_SRC="git"
RECORD_LINE=""
if [ -n "${OWNERVERIFY_RECORD_FILE:-}" ]; then
  RECORD_LINE=$(grep -hE '^PUBSYNC_OK ' "$OWNERVERIFY_RECORD_FILE" 2>/dev/null | tail -1 || true)
  RECORD_SRC="pinned"
elif RECORD_LINE=$(grep -hE '^PUBSYNC_OK ' ci/*-negative-proof.log docs/PHASE9-W*-REPORT.md docs/PHASE10-W*-REPORT.md 2>/dev/null | grep " vc=$PUB_VC " | tail -1) && [ -n "$RECORD_LINE" ]; then
  RECORD_SRC="git"
else
  . "$STATE" 2>/dev/null || true
  if [ -n "${PUBSYNC_PUBLISHED_SHA:-}" ]; then
    RECORD_SRC="ledger"
    RECORD_SHA="$PUBSYNC_PUBLISHED_SHA"
    RECORD_REL="${PUBSYNC_REL:-}"
    RECORD_BASE="${PUBSYNC_BASE:-}"
  fi
fi
if [ -n "$RECORD_LINE" ]; then
  RECORD_SHA=$(printf '%s' "$RECORD_LINE" | sed -E 's/.* published=([0-9a-f]{64}) .*/\1/')
  RECORD_REL=$(printf '%s' "$RECORD_LINE" | sed -E 's/.* rel=([0-9]+) .*/\1/')
  RECORD_BASE=$(printf '%s' "$RECORD_LINE" | sed -E 's/.* base=([^ ]+) .*/\1/')
  case "$RECORD_SHA" in ''|*[!0-9a-f]*) fail "ownercheck.record (record line malformed: $RECORD_LINE)";; esac
fi
[ -n "${RECORD_SHA:-}" ] || fail "ownercheck.record (no committed record and no ledger state — nothing to anchor on; no pretending)"
[ ${#RECORD_SHA} = 64 ] || fail "ownercheck.record (the record sha is not a full sha256)"
[ "$RECORD_REL" = "$REL" ] || fail "ownercheck.record_fields (record rel=${RECORD_REL:-empty} != the gate's $REL)"
[ "$RECORD_BASE" = "$BASE_TAG" ] || fail "ownercheck.record_fields (record base=${RECORD_BASE:-empty} != the gate's $BASE_TAG)"
CHECKS=$((CHECKS + 2))

# ---- 2: حالة المنشور — المحلية أو الجلب ----------------------------------------
rm -rf "$WORK"; mkdir -p "$WORK/dl" "$WORK/vdir"
if [ "$MODE" = "remote" ]; then
  TOKEN_FILE="${PUBVERIFY_TOKEN_FILE:-}"
  if [ -z "$TOKEN_FILE" ] || [ ! -f "$TOKEN_FILE" ] || [ ! -s "$TOKEN_FILE" ]; then
    fail "ownercheck.credential (PUBVERIFY_TOKEN_FILE missing/empty — a private release needs a READ credential; the verifier never writes)"
  fi
  TOKEN=$(tr -d ' \r\n' < "$TOKEN_FILE")
  [ -n "$TOKEN" ] || fail "ownercheck.credential (empty credential file)"
  CHECKS=$((CHECKS + 1))
  ov_gh() { curl -sSL --retry 2 -H "Authorization: token $TOKEN" "$@"; }
  ov_retry() { # $1 tag, $2.. curl args — the spaced-backoff law (W1: the
    # asset endpoints soft-404 dense bursts and serve fresh bytes late)
    local tag="$1"; shift
    local code try tries="${OWNERVERIFY_HTTP_TRIES:-6}"
    for try in $(seq 1 "$tries"); do
      code=$(ov_gh -o "$WORK/.http-body" -w '%{http_code}' "$@")
      case "$code" in 200) return 0;; esac
      sleep $(( (try * 12) > 45 ? 45 : try * 12 ))
    done
    fail "$tag (http=$code after $tries tries: $(head -c 140 "$WORK/.http-body" 2>/dev/null))"
  }
  ASSETS_JSON=""
  for try in 1 2 3; do
    ASSETS_JSON=$(ov_gh "$PUB_API/assets?per_page=100")
    [ -n "$ASSETS_JSON" ] && break
    sleep 2
  done
  [ -n "$ASSETS_JSON" ] || fail "ownercheck.list (empty asset listing)"
  CHECKS=$((CHECKS + 1))
  i=0
  for NAME in "$EV_NAME" "$PATCH_NAME" "$ST_NAME"; do
    i=$((i + 1))
    AID=$(printf '%s' "$ASSETS_JSON" | python3 -c "
import json, sys
for a in json.load(sys.stdin):
    if a['name'] == sys.argv[1]:
        print(a['id']); break
" "$NAME")
    [ -n "$AID" ] || fail "ownercheck.stale ($NAME is not on the release — the publication must carry this week's statement and assets)"
    echo "$AID" >> "$WORK/.asset-ids"
    CHECKS=$((CHECKS + 1))
  done
  i=0
  for NAME in "$EV_NAME" "$PATCH_NAME" "$ST_NAME"; do
    i=$((i + 1))
    AID=$(sed -n "${i}p" "$WORK/.asset-ids")
    ov_retry "ownercheck.download ($NAME id=$AID)" -H "Accept: application/octet-stream" -o "$WORK/dl/$NAME" "$PUB_API/assets/$AID"
    [ -s "$WORK/dl/$NAME" ] || fail "ownercheck.download ($NAME — empty fetch)"
    CHECKS=$((CHECKS + 1))
  done
  STATEMENT="$WORK/dl/$ST_NAME"; EVIDENCE="$WORK/dl/$EV_NAME"; PATCHF="$WORK/dl/$PATCH_NAME"
else
  # local mode: the publication state the standalone gate established in
  # THIS clone — the same chain against the local bytes, no network
  [ -d "$PUBDIR" ] || fail "ownercheck.stale (no publication state in $PUBDIR — run ci/pubsync_gate.sh first; the weekly flow: the gate publishes, the verifier audits)"
  CHECKS=$((CHECKS + 1))
  for f in "$PUBDIR/statement.json" "$PUBDIR/$EV_NAME" "$PUBDIR/$PATCH_NAME"; do
    [ -s "$f" ] || fail "ownercheck.stale ($f missing/empty — the publication state must carry the statement and both assets)"
    CHECKS=$((CHECKS + 1))
  done
  STATEMENT="$PUBDIR/statement.json"; EVIDENCE="$PUBDIR/$EV_NAME"; PATCHF="$PUBDIR/$PATCH_NAME"
fi

# ---- 3: الترجمة — الوحدات الثلاث (الاشتقاق الواحد) -----------------------------
OUTD="${WORK}/tooling"; mkdir -p "$OUTD"
ECJ="java -jar tools/ecj.jar -nowarn -source 8 -target 8 -encoding UTF-8"
command -v javac >/dev/null 2>&1 && ECJ="javac -nowarn -encoding UTF-8"
$ECJ -cp . -d "$OUTD" tests/com/hark/neokey/SyncServerPack.java tests/com/hark/neokey/EvAudit.java tests/com/hark/neokey/PubSync.java 2>&1 | head -30
ck "$?" 0 "compile.tooling"
[ -f "$OUTD/com/hark/neokey/PubSync.class" ] || fail "compile.tooling (PubSync.class missing)"
CHECKS=$((CHECKS + 1))

# ---- 4: قوانين البيان + سلامة المنزَّل — الوحدة نفسها --------------------------
#   vdir يحمل الأصلين المعلَنين فقط (البيان بلا sha ذاتي — قانون الإثبات
#   الذاتي: لا شيء يحمل حكمه الخاص، فالبيان خارج قائمة أصوله وكل حقل
#   فيه يُرسَى انتقالياً).
ln "$EVIDENCE" "$WORK/vdir/$EV_NAME" 2>/dev/null || cp "$EVIDENCE" "$WORK/vdir/$EV_NAME"
ln "$PATCHF" "$WORK/vdir/$PATCH_NAME" 2>/dev/null || cp "$PATCHF" "$WORK/vdir/$PATCH_NAME"
java -cp "$OUTD" com.hark.neokey.PubSync verify "$STATEMENT" "$WORK/vdir" || fail "ownercheck.sha (the statement laws + the re-derived sha of the fetched bytes must pass — PubSync's own tags carry the refusal)"
CHECKS=$((CHECKS + 3)) # statement.laws (schema/rel/base) + statement.identity (vc/vn + the week's asset names) + statement.shas (re-derived from the fetched bytes, no extra member)

ST_EV_SHA=$(sed -n 's/.*"asset_sha1": "\([0-9a-f]*\)".*/\1/p' "$STATEMENT" | head -1)
[ "$ST_EV_SHA" = "$RECORD_SHA" ] || fail "ownercheck.record_sha (the statement's archive sha ${ST_EV_SHA:-empty} != the recorded published sha $RECORD_SHA — a re-stamped statement bites here)"
CHECKS=$((CHECKS + 1))

# ---- 5: التدقيق المعزول — الأرشيف المنزَّل يُدقَّق بـEvAudit نفسها -------------
java -cp "$OUTD" com.hark.neokey.PubSync unzip "$EVIDENCE" "$WORK/archive" || fail "ownercheck.unzip (PubSync's own tags carry the refusal)"
CHECKS=$((CHECKS + 1))
AOUT=$(java -cp "$OUTD" com.hark.neokey.EvAudit check "$WORK/archive" --expect-vc "$PUB_VC" --expect-vn "$PUB_VN" 2>&1); ARC=$?
[ "$ARC" = "0" ] || fail "ownercheck.audit (the fetched archive must verify in isolation — EvAudit's own laws; got rc=$ARC: $(printf '%s' "$AOUT" | tail -1))"
CHECKS=$((CHECKS + 1))

# ---- 6: إعادة البناء — الرقعة المنزَّلة تعيد بناء شجرة HEAD بايت-لطرفاً --------
rm -rf "$WORK/base"
git worktree prune >/dev/null 2>&1 || true
git worktree add --detach -q "$WORK/base" "$BASE_TAG" 2>/dev/null || fail "patch.base_worktree ($BASE_TAG)"
git -C "$WORK/base" apply "$(pwd)/$PATCHF" 2>&1 | head -3
ck "$?" 0 "ownercheck.reconstruct" "the fetched patch must apply to the published base"
git -C "$WORK/base" add -A
APPLIED_TREE=$(git -C "$WORK/base" write-tree)
HEAD_TREE=$(git rev-parse HEAD^{tree})
git worktree remove --force "$WORK/base" 2>/dev/null || true
[ "$APPLIED_TREE" = "$HEAD_TREE" ] || fail "ownercheck.reconstruct (the patch rebuilt tree $APPLIED_TREE != HEAD tree $HEAD_TREE — a tampered or stale cumulative patch bites here)"
CHECKS=$((CHECKS + 2))

# ---- 7: الاثنتا عشرة سلبة — بوسمها الحرفي على نسخ متسخة مؤقتة ------------------
#   البطارية في الاستقلال (المحلي والبعيد)؛ مرحلة الأنبوب تُدقِّق
#   الحالة والدفتر يحمل حكم البطارية (نمط W1: النشر يُنفَّذ مستقلاً،
#   والأنبوب يُدقِّق). كل نوبة على نسخة مؤقتة — المدخلات الحقيقية لا
#   تُمَسّ، والأخضر يعاد بعدها.
if [ "${OWNERVERIFY_SKIP_BATTERY:-0}" != "1" ]; then

neg() { # $1 tag expected, $2.. the command
  local want="$1"; shift
  local out rc
  out=$("$@" 2>&1); rc=$?
  case "$out" in
    *"OWNERVERIFY_FAILED $want"*|*"PUBSYNC_FAILED $want"*) NEG_PASS=$((NEG_PASS+1));;
    *) echo "NEG-MISS want=$want rc=$rc out: $(printf '%s' "$out" | tail -2)"; NEG_FAIL=$((NEG_FAIL+1));; esac
}
NEG_PASS=0; NEG_FAIL=0
cp "$STATEMENT" "$WORK/st.json"
TOKTMP="$WORK/.empty-token"; : > "$TOKTMP"
RECORD_TMP="$WORK/.empty-record"; : > "$RECORD_TMP"
STDIRTMP="$WORK/.empty-state"; mkdir -p "$STDIRTMP"
MNTMP="$WORK/.dirty-manifest"
{ grep -v 'versionCode=\|versionName=' "$MANIFEST"; printf '<manifest package="com.hark.neokey" versionCode="99" versionName="9.9" />\n'; } > "$MNTMP"
SUB() { env OWNERVERIFY_SKIP_BATTERY=1 OWNERVERIFY_WORK_DIR="$WORK/sub-$1" "${@:2}"; }

# n1: هوية البيان مقلوبة (مستقلة عن الترقية — الفعل على الصفة لا القيمة)
cp "$STATEMENT" "$WORK/n1.json"; sed -i 's/"vc": "[0-9]*"/"vc": "99"/' "$WORK/n1.json"
neg "pubsync.statement_identity" java -cp "$OUTD" com.hark.neokey.PubSync verify "$WORK/n1.json" "$WORK/vdir"

# n2: مخطط البيان أجنبي
cp "$STATEMENT" "$WORK/n2.json"; sed -i 's/neokey-pubsync-1/neokey-pubsync-9/' "$WORK/n2.json"
neg "pubsync.statement_schema" java -cp "$OUTD" com.hark.neokey.PubSync verify "$WORK/n2.json" "$WORK/vdir"

# n3: المزوِّر المتسق — بيان معاد ختمه على أرشيف معاد حزمه، لكن السجل
#     (git) ما زال يحمل sha المنشور الحقيقي → record_sha يعضّ
rm -rf "$WORK/forged"; mkdir -p "$WORK/forged"
cp "$EVIDENCE" "$WORK/forged/$EV_NAME"
python3 - "$WORK/forged/$EV_NAME" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
b[35] ^= 0x5A
open(p, "wb").write(bytes(b))
PY
FSHA=$(sha256sum "$WORK/forged/$EV_NAME" | cut -d' ' -f1)
sed "s/\"asset_sha1\": \"[0-9a-f]*\"/\"asset_sha1\": \"$FSHA\"/" "$STATEMENT" > "$WORK/forged/statement.json"
printf 'PUBSYNC_OK checks=21 negatives=12/12 assets=3 members=28 proofs=22 rel=%s base=%s roundtrip=local published=%s vc=%s vn=%s\n' "$REL" "$BASE_TAG" "$FSHA" "$PUB_VC" "$PUB_VN" > "$WORK/forged/record.log"
ln "$PATCHF" "$WORK/forged/$PATCH_NAME" 2>/dev/null || cp "$PATCHF" "$WORK/forged/$PATCH_NAME"
# مزوّر متسق كامل: البيان متطابق مع الأرشيف المزوّر — لكن السجل المرسّى
# (الخطّاف المثبَّت يحمل sha الحقيقي لا sha الزيف) يكشفه
printf 'PUBSYNC_OK checks=21 negatives=12/12 assets=3 members=28 proofs=22 rel=%s base=%s roundtrip=local published=%s vc=%s vn=%s\n' "$REL" "$BASE_TAG" "$RECORD_SHA" "$PUB_VC" "$PUB_VN" > "$WORK/forged/record.log"
neg "ownercheck.record_sha" SUB 3 env OWNERVERIFY_RECORD_FILE="$WORK/forged/record.log" OWNERVERIFY_STATE_DIR="$WORK/forged" bash "$0"

# n4: بايتات الأرشيف المنزَّل مسمومة → sha يُعاد اشتقاقه من المنزَّل يعضّ
rm -rf "$WORK/tampered-dl"; mkdir -p "$WORK/tampered-dl"
cp "$EVIDENCE" "$WORK/tampered-dl/$EV_NAME"
python3 - "$WORK/tampered-dl/$EV_NAME" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
for off in (35, 45, 55, 65, 75):
    if off < len(b):
        b[off] ^= 0x5A
open(p, "wb").write(bytes(b))
PY
neg "pubsync.sha_mismatch" java -cp "$OUTD" com.hark.neokey.PubSync verify "$STATEMENT" "$WORK/tampered-dl"

# n5: عضو زائر مزروع في مجلد الجولة
rm -rf "$WORK/extra-dl"; mkdir -p "$WORK/extra-dl"
ln "$EVIDENCE" "$WORK/extra-dl/$EV_NAME" 2>/dev/null || cp "$EVIDENCE" "$WORK/extra-dl/$EV_NAME"
ln "$PATCHF" "$WORK/extra-dl/$PATCH_NAME" 2>/dev/null || cp "$PATCHF" "$WORK/extra-dl/$PATCH_NAME"
echo planted > "$WORK/extra-dl/planted.bin"
neg "pubsync.extra_asset" java -cp "$OUTD" com.hark.neokey.PubSync verify "$STATEMENT" "$WORK/extra-dl"

# n6: عضو محذوف من الأرشيف قبل التدقيق → EvAudit.check يرفض
rm -rf "$WORK/dirty-archive"; cp -r "$WORK/archive" "$WORK/dirty-archive"
DIRTY_MEMBER=$(ls "$WORK/dirty-archive" | grep -v SHA256SUMS | head -1)
rm "$WORK/dirty-archive/$DIRTY_MEMBER"
aout=$(java -cp "$OUTD" com.hark.neokey.EvAudit check "$WORK/dirty-archive" --expect-vc "$PUB_VC" --expect-vn "$PUB_VN" 2>&1); arc=$?
[ "$arc" != "0" ] && NEG_PASS=$((NEG_PASS+1)) || { echo "NEG-MISS want=ownercheck.audit (the audit went green on a truncated member)"; NEG_FAIL=$((NEG_FAIL+1)); }

# n7: رقعة مسمومة (الذيل مقطوع) → لا تطبق على قاعدة المنشور
cp "$PATCHF" "$WORK/tampered.patch"
python3 - "$WORK/tampered.patch" <<'PY'
import sys
p = sys.argv[1]
b = bytearray(open(p, "rb").read())
open(p, "wb").write(bytes(b[:-4096]))
PY
rm -rf "$WORK/base-n7"
git worktree prune >/dev/null 2>&1 || true
git worktree add --detach -q "$WORK/base-n7" "$BASE_TAG" 2>/dev/null || fail "negatives (cannot stage the base worktree)"
git -C "$WORK/base-n7" apply "$(pwd)/$WORK/tampered.patch" >/dev/null 2>&1
[ "$?" != "0" ] && NEG_PASS=$((NEG_PASS+1)) || { echo "NEG-MISS want=ownercheck.reconstruct (the truncated patch applied cleanly)"; NEG_FAIL=$((NEG_FAIL+1)); }
git worktree remove --force "$WORK/base-n7" 2>/dev/null || true

# n8: السجل مفقود في كل مكان → فشل مغلقةً (لا ترسيو بلا مرساة)
neg "ownercheck.record" SUB 8 env OWNERVERIFY_RECORD_FILE="$RECORD_TMP" bash "$0"

# n9: اعتماد القراءة مفقود في الوضع البعيد → فشل مغلقةً قبل أي جلب
neg "ownercheck.credential" SUB 9 env PUBVERIFY_TOKEN_FILE="$TOKTMP" bash "$0" --remote

# n10: حالة النشر غائبة → فشل مغلقةً
neg "ownercheck.stale" SUB 10 env OWNERVERIFY_STATE_DIR="$STDIRTMP" bash "$0"

# n11: فعْل كتابة مزروع في سطح الأداة → الفحص الذاتي يرفض النسخة
# (النسخة تعيش تحت ci/ — قانون cd الداخلي: dirname/.. = جذر المستودع)
python3 - "$0" "ci/.ownerverify-verbcopy.sh" <<'PY'
import sys
s = open(sys.argv[1]).read()
s = s.replace('ov_gh() { curl -sSL', 'ov_gh() { curl -sSL ' + chr(45) + chr(88) + chr(32) + chr(80) + chr(79) + chr(83) + chr(84))
open(sys.argv[2], "w").write(s)
PY
neg "ownercheck.readonly" SUB 11 env OWNERVERIFY_WORK_DIR="$WORK/verbcopy/work" bash "ci/.ownerverify-verbcopy.sh"
rm -f "ci/.ownerverify-verbcopy.sh"

# n12: مرساة الهوية منحرفة (مانيفست مؤقت دائم-الخطأ — درس W36)
neg "anchor.identity.vc" SUB 12 env OWNERVERIFY_MANIFEST="$MNTMP" bash "$0"

[ "$NEG_FAIL" = "0" ] || fail "negatives ($NEG_PASS/12 — every negative must bite with its own tag)"
CHECKS=$((CHECKS + 2))

# the REAL inputs re-verify green after the battery (restores healed)
java -cp "$OUTD" com.hark.neokey.PubSync verify "$STATEMENT" "$WORK/vdir" || fail "negatives.restored"
CHECKS=$((CHECKS + 1))
fi

# ---- 8: الدفتر — upsert idempotent + قانون السجل الخارجي ----------------------
[ -f "$STATE" ] || : > "$STATE"
NEG_FIELD="${NEG_PASS:-}"
[ -n "$NEG_FIELD" ] && NEG_FIELD="$NEG_FIELD/12"
[ -n "$NEG_FIELD" ] || NEG_FIELD=$(grep '^OWNERVERIFY_NEGATIVES=' "$STATE" 2>/dev/null | cut -d= -f2 | tail -1)
NEG_FIELD="${NEG_FIELD:-0/12}"
sed -i '/^OWNERVERIFY_/d' "$STATE"
VERDICT="OWNERVERIFY_OK checks=$CHECKS negatives=$NEG_FIELD assets=3 rel=$REL base=$BASE_TAG week=$WEEK mode=$MODE record=$RECORD_SRC vc=$PUB_VC vn=$PUB_VN"
{
  echo "OWNERVERIFY_GATE=OK"
  echo "OWNERVERIFY_CHECKS=$CHECKS"
  echo "OWNERVERIFY_NEGATIVES=$NEG_FIELD"
  echo "OWNERVERIFY_ASSETS=3"
  echo "OWNERVERIFY_REL=$REL"
  echo "OWNERVERIFY_BASE=$BASE_TAG"
  echo "OWNERVERIFY_WEEK=$WEEK"
  echo "OWNERVERIFY_MODE=$MODE"
  echo "OWNERVERIFY_RECORD=$RECORD_SRC"
  echo "OWNERVERIFY_RECORD_SHA=$RECORD_SHA"
  echo "OWNERVERIFY_VC=$PUB_VC"
  echo "OWNERVERIFY_VN=$PUB_VN"
} >> "$STATE"
. "$STATE"
[ "${OWNERVERIFY_CHECKS:-}" = "$CHECKS" ] || fail "env.ownerverify_checks"
[ "${OWNERVERIFY_VC:-}" = "$PUB_VC" ] || fail "env.ownerverify_vc"
CHECKS=$((CHECKS + 2))
# the mode-dependent fields are masked (local/remote run the same laws from
# different sources; week is weekly-monotonic like members/proofs — the W40
# law); the identity core (assets/rel/base/vc/vn) must byte-equal
mask() { printf '%s' "$1" | sed -E 's/checks=[0-9]+/checks=C/; s/negatives=[^ ]+/negatives=N/; s/week=[0-9]+/week=W/; s/mode=[a-z]+/mode=M/; s/record=[a-z]+/record=R/'; }
LOG_LINE=$(grep -oE 'OWNERVERIFY_OK [^"]*' "$LOGF" 2>/dev/null | head -1 || true)
if [ -n "$LOG_LINE" ]; then
  [ "$(mask "$LOG_LINE")" = "$(mask "$VERDICT")" ] || fail "outer_log.ownerverify (a prior OWNERVERIFY_OK line drifted from fresh — verdict fields are stable)"
fi
CHECKS=$((CHECKS + 1))

echo "$VERDICT"
