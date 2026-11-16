#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""خط إنتاج قواميس اللهجات — خارطة الطريق، المرحلة 1، البند C1 (الأسبوع 5).

يبني ثلاثة قواميس لهجات من مصادر المستودع فقط:
  • dictsrc/gulf.txt          — النواة الخليجية المشتركة (منسقة داخل المستودع منذ 1.0)
  • dictsrc/dialect_markers.tsv — جدول العلامات الموزون (مميزة + ميل + عائلة)

المخرجات:
  assets/ar_hijazi.txt, assets/ar_najdi.txt, assets/ar_gulf.txt
      — كلمة لكل سطر، الترتيب = الرتبة: المميزة أولاً (الأثقل أولاً) ثم النواة المشتركة
  dictsrc/corpus/dialect-prep-report.json — تقرير كمية وقابلة للبوابة

القواعد:
  • التطبيع normAr: إزالة التشكيل والتطويل، أإآ→ا، ة→ه، ى→ي، ؤ→و، ئ→ي
    (نفس دالة normAr في Dialect.java — البوابة تفحص التطابق بين هذا الملف وجافا)
  • إزالة التكرار: بالصيغة الحرفية وبمفتاح normAr (الأولى تبقى)
  • فلترة: أحرف عربية فقط، طول 2..20، بلا تشكيل/تطويل
  • كل كلمة علامة غير صفرية في عمود d يجب أن تخرج في قاموس d (فحص الاتساق)
"""
import json, os, re, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DS   = os.path.join(ROOT, "dictsrc")
ASSETS = os.path.join(ROOT, "assets")
DIALECTS = ["hijazi", "najdi", "gulf"]

TASHKEEL = re.compile(r'[\u064B-\u0652\u0640]')
AR_ONLY  = re.compile(r'^[\u0621-\u063A\u0641-\u064A]{2,20}$')

def norm_ar(w):
    w = TASHKEEL.sub('', w.strip())
    table = str.maketrans({
        'أ': 'ا', 'إ': 'ا', 'آ': 'ا', 'ٱ': 'ا', 'ة': 'ه',
        'ى': 'ي', 'ؤ': 'و', 'ئ': 'ي'})
    return w.translate(table)

def parse_gulf_core(path):
    """gulf.txt: سطر واحد مفصول بـ | — النواة الخليجية المشتركة."""
    raw = []
    for line in open(path, encoding='utf-8'):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        raw.extend(t.strip() for t in line.split('|'))
    return raw

def parse_markers(path):
    """dialect_markers.tsv: كلمة<TAB>h,n,g (أسطر # تعليقات)."""
    markers = []
    for ln, line in enumerate(open(path, encoding='utf-8'), 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) != 2:
            sys.exit('BUILD_DIALECT_FAILED: bad marker line %d: %r' % (ln, line))
        w = parts[0].strip()
        try:
            h, n, g = (int(x) for x in parts[1].split(','))
        except ValueError:
            sys.exit('BUILD_DIALECT_FAILED: bad weights line %d: %r' % (ln, line))
        if not (0 <= h <= 3 and 0 <= n <= 3 and 0 <= g <= 3):
            sys.exit('BUILD_DIALECT_FAILED: weight out of range line %d: %r' % (ln, line))
        if h == 0 and n == 0 and g == 0:
            sys.exit('BUILD_DIALECT_FAILED: all-zero weights line %d: %r' % (ln, line))
        markers.append((w, (h, n, g)))
    return markers

def parse_vocab(path):
    """dialect_vocab.tsv (W88): كلمة<TAB>h,n,g بعضوية 0/1 — مفردات القاموس فقط، لا علامات كشف."""
    out = []
    if not os.path.isfile(path):
        return out
    for ln, line in enumerate(open(path, encoding='utf-8'), 1):
        line = line.strip()
        if not line or line.startswith('#'):
            continue
        parts = line.split('\t')
        if len(parts) != 2 or not AR_ONLY.match(parts[0].strip()):
            sys.exit('BUILD_DIALECT_FAILED: bad vocab line %d: %r' % (ln, line))
        try:
            f = tuple(int(x) for x in parts[1].split(','))
        except ValueError:
            sys.exit('BUILD_DIALECT_FAILED: bad vocab flags line %d: %r' % (ln, line))
        if len(f) != 3 or any(x not in (0, 1) for x in f) or sum(f) == 0:
            sys.exit('BUILD_DIALECT_FAILED: vocab flags must be 0/1, not all zero, line %d' % ln)
        out.append((parts[0].strip(), f))
    return out

def main():
    core_raw = parse_gulf_core(os.path.join(DS, 'gulf.txt'))
    markers  = parse_markers(os.path.join(DS, 'dialect_markers.tsv'))
    vocab    = parse_vocab(os.path.join(DS, 'dialect_vocab.tsv'))

    # علامة فريدة لكل مفتاح normAr — التكرار في الجدول نفسه خطأ تحرير
    seen_keys = {}
    for w, wt in markers:
        k = norm_ar(w)
        if not AR_ONLY.match(w) or not AR_ONLY.match(k):
            sys.exit('BUILD_DIALECT_FAILED: marker not Arabic-letters-only: %r' % w)
        if k in seen_keys:
            sys.exit('BUILD_DIALECT_FAILED: duplicate marker key %r (%r vs %r)'
                     % (k, seen_keys[k], w))
        seen_keys[k] = w

    core = []          # النواة بعد الفلترة والتطبيع (بصيغتها الأصلية غير المنظمة)
    core_keys = set()
    dups = 0
    for w in core_raw:
        if not AR_ONLY.match(w):
            continue
        k = norm_ar(w)
        if k in core_keys:
            dups += 1
            continue
        core_keys.add(k)
        core.append(w)

    report = {
        'tool': 'dictsrc/build_dialect.py',
        'core_source': 'dictsrc/gulf.txt',
        'markers_source': 'dictsrc/dialect_markers.tsv',
        'vocab_source': 'dictsrc/dialect_vocab.tsv',
        'markers_total': len(markers),
        'core_raw_tokens': len(core_raw),
        'core_unique': len(core),
        'core_dups_removed': dups,
        'dialects': {},
    }

    for d in DIALECTS:
        col = {'hijazi': 0, 'najdi': 1, 'gulf': 2}[d]
        # المميزة: كلمات لها وزن في هذا العمود وصفر في العمودين الآخرين
        distinct = [(w, wt[col]) for w, wt in markers
                    if wt[col] > 0 and wt[(col + 1) % 3] == 0 and wt[(col + 2) % 3] == 0]
        distinct.sort(key=lambda t: (-t[1], t[0]))
        # شرط الاتساق: كل علامة غير صفرية في هذا العمود يجب أن تكون في القاموس
        must = [w for w, wt in markers if wt[col] > 0]
        out, out_keys = [], set()
        for w, _ in distinct:
            k = norm_ar(w)
            if k not in out_keys:
                out_keys.add(k); out.append(w)
        core_added = 0
        for w in core:
            k = norm_ar(w)
            if k not in out_keys:
                out_keys.add(k); out.append(w); core_added += 1
        # W88: مفردات اللهجة بعد النواة — العلامات لا تدخل من هنا أبداً (مصدرها الجدول وحده)
        vocab_added = 0
        for w, f in vocab:
            k = norm_ar(w)
            if f[col] != 1 or k in seen_keys or k in out_keys:
                continue
            out_keys.add(k); out.append(w); vocab_added += 1
        missing = [w for w in must if norm_ar(w) not in out_keys]
        if missing:
            sys.exit('BUILD_DIALECT_FAILED: markers missing from %s dict: %r'
                     % (d, missing[:8]))
        path = os.path.join(ASSETS, 'ar_%s.txt' % d)
        with open(path, 'w', encoding='utf-8') as f:
            f.write('\n'.join(out) + '\n')
        report['dialects'][d] = {
            'asset': 'assets/ar_%s.txt' % d,
            'distinctive_words': len(distinct),
            'core_words_added': core_added,
            'vocab_words_added': vocab_added,
            'total_words': len(out),
            'markers_covered': len(must),
        }

    rp = os.path.join(DS, 'corpus', 'dialect-prep-report.json')
    with open(rp, 'w', encoding='utf-8') as f:
        json.dump(report, f, ensure_ascii=False, indent=1)

    print('BUILD_DIALECT_OK markers=%d core=%d %s'
          % (len(markers), len(core),
             ' '.join('%s=%d' % (d, report['dialects'][d]['total_words'])
                      for d in DIALECTS)))

if __name__ == '__main__':
    main()
