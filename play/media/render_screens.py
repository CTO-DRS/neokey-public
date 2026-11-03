# -*- coding: utf-8 -*-
"""render_screens.py — renders the 8 Play Store screenshots (1080x1920) + the
feature graphic (1024x500) in the app's real Glass-2036 identity, from HTML/CSS
via headless Chromium (python-playwright). Output: play/screens/*.png, play/feature-1024x500.png
Run: python3 play/media/render_screens.py"""
import os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from asolib import CSS, kb, scene, head_block, brand
from playwright.sync_api import sync_playwright

BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))  # play/
OUT = os.path.join(BASE, "screens")

METEOR_STYLE = """
.meteor{height:100%;border-radius:34px;position:relative;overflow:hidden;
  background:radial-gradient(2px 2px at 12% 20%,#fff8,transparent 60%),
   radial-gradient(2px 2px at 78% 12%,#fff6,transparent 60%),
   radial-gradient(1.5px 1.5px at 30% 68%,#fff5,transparent 60%),
   radial-gradient(2px 2px at 88% 55%,#fff7,transparent 60%),
   radial-gradient(1.5px 1.5px at 55% 32%,#fff4,transparent 60%),
   radial-gradient(2px 2px at 42% 85%,#fff6,transparent 60%),
   radial-gradient(1.5px 1.5px at 66% 74%,#fff5,transparent 60%),
   radial-gradient(2px 2px at 8% 48%,#fff5,transparent 60%),
   linear-gradient(160deg,#0A0730 0%,#1B0B45 55%,#05061A 100%);}
.meteor .streak{position:absolute;width:220px;height:2.5px;border-radius:3px;
  background:linear-gradient(90deg,transparent,#C084FC,#fff);transform:rotate(-24deg);
  box-shadow:0 0 14px rgba(192,132,252,.8);}
.meteor .moon{position:absolute;top:70px;left:90px;width:110px;height:110px;border-radius:50%;
  background:radial-gradient(circle at 35% 30%,#F1E9FF,#B9A8E8 60%,#6D3FC9 130%);
  box-shadow:0 0 60px rgba(192,132,252,.55);}
.meteor .cap{position:absolute;bottom:28px;right:30px;font-weight:800;font-size:30px;
  color:#fff;padding:12px 28px;border-radius:999px;background:rgba(5,6,26,.55);
  border:1.5px solid rgba(159,140,255,.4);}
"""
WAVES = [dict(n="مطر", b=[14, 30, 52, 34, 20, 44, 26, 12]),
         dict(n="ماريمبا", b=[20, 44, 26, 56, 30, 16, 40, 22]),
         dict(n="عود", b=[30, 18, 48, 26, 60, 34, 18, 28]),
         dict(n="أجراس", b=[12, 26, 18, 40, 22, 50, 24, 14]),
         dict(n="نقر", b=[24, 16, 34, 20, 46, 18, 30, 12]),
         dict(n="ساعة", b=[16, 22, 14, 26, 16, 22, 14, 26]),
         dict(n="فضاء", b=[10, 34, 22, 48, 28, 40, 16, 30]),
         dict(n="سينثويف", b=[28, 50, 36, 62, 40, 54, 30, 44])]

def sound_tile(t):
    bars = "".join(f'<i style="height:{h}px"></i>' for h in t["b"])
    return f'''<div class="tcard" style="padding:22px 26px">
  <div style="display:flex;align-items:center;justify-content:space-between">
    <div class="tt" style="font-size:32px">{t["n"]}</div>
    <div class="wavebar" style="display:flex;align-items:flex-end;gap:6px;height:64px">{bars}</div>
  </div></div>'''

def waves_css():
    return ".wavebar{display:flex;align-items:flex-end;gap:6px;height:64px}" \
           ".wavebar i{width:7px;border-radius:4px;display:block;" \
           "background:linear-gradient(180deg,#A855F7,#22D3EE);opacity:.9}"

SCENES = [
    dict(name="01-hero", html=scene(1, head_block(
        "نيو كيبورد · كيبورد عربي ذكي",
        'اكتب عربي <span class="grad">بثقة</span>',
        "تنبؤ وتصحيح يفهم الهمزات والتاء المربوطة، وتبديل عربي-إنجليزي بسحبة على المسافة — وكل شيء يعمل على جهازك.",
        ["تنبؤ ذكي", "كتابة بالسحب", "خصوصية كاملة"]) +
        f'<div class="stage">{kb(typed="صباح الخير يا أحمد، ")}<div style="display:flex;gap:16px;justify-content:center;margin-top:22px"><div class="gradbtn">✦ اقتراحات تتعلّم منك</div><div class="ghostbtn">سحب على الحروف للكتابة</div></div></div>' + brand())),
    dict(name="02-themes", html=scene(2, head_block(
        "محرر السمات",
        'سمات <span class="grad">زجاج نيون</span>',
        "هوية «زجاج 2036» بمؤثرات توهج، ومحرر سمات كامل، وخلفية من صورك الشخصية.",
        ["30 سمة متحركة", "محرر كامل", "خلفية من صورك"]) +
        '<div class="stage">' +
        '<div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:18px">' +
        '<div class="tcard lit" style="padding:0;overflow:hidden"><div style="height:210px;background:linear-gradient(160deg,#0A0730,#3B0E7A 60%,#0E7490);position:relative"><div style="position:absolute;inset:auto 18px 18px 18px;height:74px;border-radius:16px;background:rgba(5,6,26,.75);border:1.5px solid rgba(168,85,247,.55);display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:700;letter-spacing:2px">ض ص ث ق</div></div><div class="tt" style="padding:20px 24px">زجاج نيون 2036</div></div>' +
        '<div class="tcard" style="padding:0;overflow:hidden"><div style="height:210px;background:linear-gradient(160deg,#02121B,#083344 60%,#02121B);position:relative"><div style="position:absolute;inset:auto 18px 18px 18px;height:74px;border-radius:16px;background:rgba(2,18,27,.8);border:1.5px solid rgba(34,211,238,.5);display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:700;letter-spacing:2px">ض ص ث ق</div></div><div class="tt" style="padding:20px 24px">محيط عميق</div></div>' +
        '<div class="tcard" style="padding:0;overflow:hidden"><div style="height:210px;background:linear-gradient(160deg,#1A0A0A,#7C2D12 60%,#1A0A0A);position:relative"><div style="position:absolute;inset:auto 18px 18px 18px;height:74px;border-radius:16px;background:rgba(26,10,10,.8);border:1.5px solid rgba(251,146,60,.5);display:flex;align-items:center;justify-content:center;font-size:26px;font-weight:700;letter-spacing:2px">ض ص ث ق</div></div><div class="tt" style="padding:20px 24px">غروب الحجاز</div></div>' +
        '</div>' +
        '<div style="display:flex;gap:16px;justify-content:center;margin-top:24px"><div class="gradbtn">🎨 افتح محرر السمات</div><div class="ghostbtn">خلفيات متحركة</div></div></div>' + brand())),
    dict(name="03-clipboard", html=scene(3, head_block(
        "الحافظة الذكية",
        'حافظة <span class="grad">ذكية منظمة</span>',
        "مثبّتات ومفضلة وتصنيفات وقوالب — وبحث يتجاهل التشكيل والهمزات والألف.",
        ["حتى 500 ألف حرف", "تصدير واستيراد", "بحث بلا تشكيل"]) +
        '<div class="stage">' +
        '<div style="display:flex;gap:14px;margin-bottom:20px">' +
        '<div class="sug lit">الكل</div><div class="sug">📌 المثبتة 3</div><div class="sug">★ المفضلة</div><div class="sug">تصنيفات</div></div>' +
        '<div class="tcard lit" style="margin-bottom:16px"><div style="display:flex;justify-content:space-between;align-items:center"><div class="tt">📌 بيانات الحساب البنكي</div><div style="font-size:30px">⋮</div></div><div class="ts">SA03 8000 0000 6080 1016 7519 · IBAN جاهز للنسخ</div></div>' +
        '<div class="tcard" style="margin-bottom:16px"><div style="display:flex;justify-content:space-between;align-items:center"><div class="tt">عنوان الشقة</div><div style="font-size:30px">★</div></div><div class="ts">حي الياسمين، الرياض 12333 — شقة 12، الدور الثاني</div></div>' +
        '<div class="tcard"><div style="display:flex;justify-content:space-between;align-items:center"><div class="tt">قالب رد الاجتماعات</div><div style="font-size:30px">★</div></div><div class="ts">شكراً لكم، أؤكد حضور الاجتماع يوم الأحد الساعة العاشرة…</div></div>' +
        '<div style="display:flex;gap:16px;justify-content:center;margin-top:24px"><div class="gradbtn">📋 الصق بضغطة</div><div class="ghostbtn">تصدير JSON/TXT/CSV</div></div></div>' + brand())),
    dict(name="04-assistant", html=scene(4, head_block(
        "مساعد المراسلات",
        'ردود بثلاث <span class="grad">نبرات</span>',
        "قوالب مهنية بالعربي والإنجليزي: رسمي، ودّي، وقصير — بلا مغادرة الكيبورد.",
        ["عربي وإنجليزي", "قوالب جاهزة", "تحسين الصياغة"]) +
        '<div class="stage">' +
        '<div class="tcard" style="margin-bottom:16px"><div class="tt">📨 الموقف: اعتذار عن تأخر موعد تسليم</div><div class="ts">اكتب رد من ثلاث نبرات واختر الأنسب…</div></div>' +
        '<div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:16px;margin-bottom:20px">' +
        '<div class="tcard lit" style="text-align:center"><div class="tt">رسمي</div><div class="ts">نحيطكم علماً بأن التسليم سيتم الخميس القادم.</div></div>' +
        '<div class="tcard" style="text-align:center"><div class="tt">ودّي</div><div class="ts">التسليم بيكون الخميس إن شاء الله، اعتذر عن التأخير 🙏</div></div>' +
        '<div class="tcard" style="text-align:center"><div class="tt">قصير</div><div class="ts">التسليم: الخميس القادم.</div></div>' +
        '</div>' +
        '<div style="display:flex;gap:16px;justify-content:center"><div class="gradbtn">✦ حسّن الصياغة</div><div class="ghostbtn">أدخل في الحقل</div></div></div>' + brand())),
    dict(name="05-tools", html=scene(5, head_block(
        "لوحة الأدوات",
        'أدوات فوق <span class="grad">الكيبورد</span>',
        "ترجمة وحاسبة وزخرفة نصوص وكتابة صوتية وعبارات وأذكار — بضغطة واحدة وأنت تكتب.",
        ["ترجمة فورية", "حاسبة", "زخرفة نصوص", "كتابة صوتية"]) +
        '<div class="stage">' +
        '<div style="display:grid;grid-template-columns:1fr 1fr;gap:18px">' +
        '<div class="tcard" style="display:flex;align-items:center;gap:20px"><div style="width:74px;height:74px;border-radius:22px;display:flex;align-items:center;justify-content:center;font-size:38px;background:linear-gradient(135deg,#8E5BD0,#2FA9C0);box-shadow:0 0 24px rgba(168,85,247,.4)">🌐</div><div><div class="tt">ترجمة</div><div class="ts">عربي ↔ إنجليزي</div></div></div>' +
        '<div class="tcard" style="display:flex;align-items:center;gap:20px"><div style="width:74px;height:74px;border-radius:22px;display:flex;align-items:center;justify-content:center;font-size:38px;background:linear-gradient(135deg,#8E5BD0,#2FA9C0);box-shadow:0 0 24px rgba(168,85,247,.4)">🧮</div><div><div class="tt">حاسبة</div><div class="ts">تحسب أثناء الكتابة</div></div></div>' +
        '<div class="tcard" style="display:flex;align-items:center;gap:20px"><div style="width:74px;height:74px;border-radius:22px;display:flex;align-items:center;justify-content:center;font-size:38px;background:linear-gradient(135deg,#8E5BD0,#2FA9C0);box-shadow:0 0 24px rgba(168,85,247,.4)">✨</div><div><div class="tt">زخرفة النصوص</div><div class="ts">أكثر من 30 نمطاً</div></div></div>' +
        '<div class="tcard" style="display:flex;align-items:center;gap:20px"><div style="width:74px;height:74px;border-radius:22px;display:flex;align-items:center;justify-content:center;font-size:38px;background:linear-gradient(135deg,#8E5BD0,#2FA9C0);box-shadow:0 0 24px rgba(168,85,247,.4)">🎙️</div><div><div class="tt">كتابة صوتية</div><div class="ts">عربي وإنجليزي</div></div></div>' +
        '<div class="tcard" style="display:flex;align-items:center;gap:20px"><div style="width:74px;height:74px;border-radius:22px;display:flex;align-items:center;justify-content:center;font-size:38px;background:linear-gradient(135deg,#8E5BD0,#2FA9C0);box-shadow:0 0 24px rgba(168,85,247,.4)">📜</div><div><div class="tt">عبارات جاهزة</div><div class="ts">لأي موقف</div></div></div>' +
        '<div class="tcard" style="display:flex;align-items:center;gap:20px"><div style="width:74px;height:74px;border-radius:22px;display:flex;align-items:center;justify-content:center;font-size:38px;background:linear-gradient(135deg,#8E5BD0,#2FA9C0);box-shadow:0 0 24px rgba(168,85,247,.4)">🕌</div><div><div class="tt">أذكار</div><div class="ts">الصباح والمساء</div></div></div>' +
        '</div></div>' + brand())),
    dict(name="06-backgrounds", html=scene(6, head_block(
        "الخلفيات المتحركة",
        'خلفيات <span class="grad">تتنفس</span>',
        "30 خلفية حية و30 سمة متحركة: شهب وثلوج ومجرات وجمر — بخفوت بطارية.",
        ["30 خلفية متحركة", "بدون بطارية", "من صورك أيضاً"]) +
        f'<style>{METEOR_STYLE}</style><div class="stage"><div class="meteor" style="height:520px">'
        '<div class="moon"></div>'
        '<div class="streak" style="top:120px;right:160px"></div>'
        '<div class="streak" style="top:230px;right:420px;width:150px"></div>'
        '<div class="streak" style="top:90px;right:640px;width:180px"></div>'
        '<div class="cap">شهب — خلفية متحركة رقم 1 من 30</div>'
        '</div><div style="display:flex;gap:14px;justify-content:center;margin-top:24px;flex-wrap:wrap">'
        '<div class="sug lit">شهب</div><div class="sug">ثلوج</div><div class="sug">مجرة</div><div class="sug">جمر</div><div class="sug">شفق</div><div class="sug">موج</div></div></div>' + brand())),
    dict(name="07-sounds", html=scene(7, head_block(
        "الأصوات والمؤثرات",
        '27 حزمة <span class="grad">أصوات ضغط</span>',
        "أصوات مُخلَّقة على جهازك: مطر وماريمبا وعود وأجراس — و24 مؤثر ضغط مرسوم.",
        ["مخلَّقة محلياً", "24 مؤثر ضغط", "تحكم بالصوت"]) +
        f'<style>{waves_css()}</style><div class="stage">' +
        '<div style="display:grid;grid-template-columns:1fr 1fr;gap:16px">' +
        "".join(sound_tile(t) for t in WAVES) +
        '</div><div style="display:flex;gap:16px;justify-content:center;margin-top:24px"><div class="gradbtn">🔊 جرّب الحزم</div><div class="ghostbtn">+19 حزمة أخرى</div></div></div>' + brand())),
    dict(name="08-privacy", html=scene(8, head_block(
        "الخصوصية والدروع",
        'خصوصيتك <span class="grad">أولاً</span>',
        "لا إعلانات، لا حسابات، ولا يخرج ما تكتبه من جهازك — مع درع تحقق يحمي التطبيق من إعادة التجميع.",
        ["صفر جمع بيانات", "درع تحقق", "تصدير وحذف فوري"]) +
        '<div class="stage">' +
        '<div class="tcard lit" style="margin-bottom:16px"><div style="display:flex;align-items:center;gap:20px"><div style="width:78px;height:78px;border-radius:50%;display:flex;align-items:center;justify-content:center;font-size:40px;background:linear-gradient(135deg,#8E5BD0,#2FA9C0);box-shadow:0 0 30px rgba(168,85,247,.5)">🛡️</div><div><div class="tt">درع التحقق — الحالة سليمة ✓</div><div class="ts">5 فحوصات دورية: التوقيع، البيئة، مصدر التثبيت — بلا جمع أي معرفات</div></div></div></div>' +
        '<div style="display:grid;grid-template-columns:1fr 1fr;gap:16px;margin-bottom:20px">' +
        '<div class="tcard"><div class="tt">🚫 لا إعلانات</div><div class="ts">ولا متتبعات</div></div>' +
        '<div class="tcard"><div class="tt">👤 لا حسابات</div><div class="ts">ولا تسجيل دخول</div></div>' +
        '<div class="tcard"><div class="tt">📱 بياناتك على جهازك</div><div class="ts">قاموسك وحافظتك محلية</div></div>' +
        '<div class="tcard"><div class="tt">📤 تصدير وحذف</div><div class="ts">نسخة احتياطية بملف واحد</div></div>' +
        '</div>' +
        '<div style="display:flex;gap:16px;justify-content:center"><div class="gradbtn">🛡️ شاهد تقرير الفحص المجهول</div></div></div>' + brand())),
]

FEATURE = ('<div class="feature-graphic" id="fg"><div class="aurora"></div><div class="aurb"></div>'
           '<div style="position:relative;z-index:3;height:100%;display:flex;align-items:center;'
           'gap:40px;padding:0 60px;direction:rtl">'
           '<div style="flex:1.15">'
           '<div style="display:flex;align-items:center;gap:20px;margin-bottom:16px">'
           '<div class="orb" style="width:78px;height:78px"></div>'
           '<div><div style="font-weight:900;font-size:60px;line-height:1.05">نيو كيبورد</div>'
           '<div style="font-weight:600;font-size:20px;color:#B8B4D9;letter-spacing:4px">NEOKEY</div></div></div>'
           '<div style="font-weight:800;font-size:40px;line-height:1.3">كيبورد عربي وإنجليزي ذكي</div>'
           '<div class="grad" style="font-weight:800;font-size:40px;line-height:1.3;'
           'background:linear-gradient(100deg,#C084FC,#A855F7 40%,#22D3EE);'
           '-webkit-background-clip:text;background-clip:text;color:transparent">'
           'سمات زجاجية · تنبؤ · خصوصية كاملة</div></div>'
           '<div style="flex:1;display:flex;flex-direction:column;gap:16px">'
           '<div class="chip" style="font-size:28px"><span class="dot"></span>تنبؤ وتصحيح عربي ذكي</div>'
           '<div class="chip" style="font-size:28px"><span class="dot"></span>سمات وخلفيات متحركة</div>'
           '<div class="chip" style="font-size:28px"><span class="dot"></span>حافظة ذكية وأدوات</div>'
           '<div class="chip" style="font-size:28px"><span class="dot"></span>صفر جمع بيانات — درع تحقق</div>'
           '</div></div></div>')

def main():
    os.makedirs(OUT, exist_ok=True)
    body = "".join(s["html"] for s in SCENES) + FEATURE
    html = (f'<!DOCTYPE html><html><head><meta charset="utf-8"><style>{CSS}</style></head>'
            f'<body>{body}</body></html>')
    path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "scenes.html")
    with open(path, "w", encoding="utf-8") as f:
        f.write(html)
    with sync_playwright() as p:
        b = p.chromium.launch(args=["--force-color-profile=srgb", "--disable-lcd-text"])
        pg = b.new_page(viewport=dict(width=1080, height=1920), device_scale_factor=1)
        pg.goto("file://" + path)
        pg.wait_for_timeout(900)  # fonts
        for i, s in enumerate(SCENES, 1):
            pg.evaluate(f"document.querySelectorAll('.scene').forEach(e=>e.classList.remove('active'))")
            pg.evaluate(f"document.getElementById('s{i}').classList.add('active')")
            pg.wait_for_timeout(120)
            out = os.path.join(OUT, s["name"] + ".png")
            pg.screenshot(path=out, clip=dict(x=0, y=0, width=1080, height=1920))
            print("wrote", out)
        # feature graphic (own page — different size)
        pg2 = b.new_page(viewport=dict(width=1024, height=500), device_scale_factor=1)
        pg2.goto("file://" + path)
        pg2.evaluate("document.getElementById('fg').classList.add('active')")
        pg2.wait_for_timeout(700)
        out = os.path.join(BASE, "feature-1024x500.png")
        pg2.screenshot(path=out, clip=dict(x=0, y=0, width=1024, height=500))
        print("wrote", out)
        b.close()

if __name__ == "__main__":
    main()
