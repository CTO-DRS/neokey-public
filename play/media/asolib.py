# -*- coding: utf-8 -*-
"""asolib.py — the Glass-2036 design language as HTML/CSS, matching the app's
actual code values: Glass.java (PURPLE/INDIGO/CYAN, BG, aurora glows, panel fill
0x0B0C24 @ ~72%), Theme.java neonOf('زجاج نيون 2036') (#A855F7 / #22D3EE / #F5F3FF),
KeyboardView.drawHyper (glass key sheen + gradient rim, gradient enter), Layouts.java
(Arabic rows). Fonts: Cairo (installed to ~/.fonts). Screenshots are 1080x1920 (9:16)
— inside Play's 1:2..2:1 aspect limits."""

CSS = """
@font-face{font-family:'Cairo';src:url('file:///home/z/.fonts/Cairo-VF.ttf') format('truetype');
  font-weight:200 1000;}
*{margin:0;padding:0;box-sizing:border-box;-webkit-font-smoothing:antialiased;}
:root{
  --bg:#05061A; --bg2:#0E0A2E; --purple:#A855F7; --cyan:#22D3EE;
  --gp:#8E5BD0; --gi:#5558C2; --gc:#2FA9C0;
  --keytext:#F5F3FF; --sub:#B8B4D9; --rim:rgba(159,140,255,.30);
}
body{background:#000;font-family:'Cairo','Noto Sans Arabic',sans-serif;color:var(--keytext);}
.scene{width:1080px;height:1920px;position:absolute;top:0;left:0;overflow:hidden;
  display:none;direction:rtl;
  background:linear-gradient(180deg,var(--bg) 0%,var(--bg2) 100%);}
.scene.active{display:block;}
/* aurora — same three glows as Glass.Bg */
.aurora,.aurora::before,.aurora::after{position:absolute;inset:0;pointer-events:none;}
.aurora::before{content:'';background:radial-gradient(810px 810px at 85% 6%,rgba(124,58,237,.23),transparent 70%);}
.aurora::after{content:'';background:radial-gradient(756px 756px at 10% 55%,rgba(6,182,212,.13),transparent 70%);}
.aurb{position:absolute;inset:0;background:radial-gradient(756px 756px at 60% 95%,rgba(192,38,211,.09),transparent 70%);}
.wrap{position:relative;z-index:3;height:100%;display:flex;flex-direction:column;
  padding:76px 64px 56px;}
/* headline block */
.kicker{display:inline-flex;align-items:center;gap:14px;align-self:flex-start;
  font-weight:700;font-size:30px;color:#CFC8F2;letter-spacing:.5px;
  padding:10px 26px;border-radius:999px;margin-bottom:30px;
  background:rgba(11,12,36,.72);border:1.5px solid var(--rim);}
.kicker .dot{width:14px;height:14px;border-radius:50%;
  background:linear-gradient(135deg,var(--purple),var(--cyan));
  box-shadow:0 0 18px rgba(168,85,247,.8);}
h1{font-weight:900;font-size:88px;line-height:1.22;letter-spacing:-.5px;}
h1 .grad{background:linear-gradient(100deg,#C084FC 0%,#A855F7 38%,#22D3EE 100%);
  -webkit-background-clip:text;background-clip:text;color:transparent;}
.sub{font-weight:500;font-size:40px;line-height:1.55;color:var(--sub);margin-top:18px;}
.chips{display:flex;gap:16px;flex-wrap:wrap;margin-top:34px;}
.chip{display:inline-flex;align-items:center;gap:12px;font-weight:700;font-size:30px;
  color:#E4DFF9;padding:14px 28px;border-radius:999px;
  background:rgba(11,12,36,.72);border:1.5px solid var(--rim);}
.chip .dot{width:12px;height:12px;border-radius:50%;background:var(--cyan);
  box-shadow:0 0 12px var(--cyan);}
/* glass stage (mock area) */
.stage{margin-top:44px;flex:1 0 auto;border-radius:52px;padding:34px;
  background:rgba(11,12,36,.72);
  box-shadow:0 0 0 3.5px var(--rim),0 30px 80px rgba(0,0,0,.55),
   0 0 90px rgba(124,58,237,.16);}
.brandrow{margin-top:auto;display:flex;align-items:center;gap:22px;padding-top:36px;}
.orb{width:64px;height:64px;border-radius:50%;flex:0 0 auto;position:relative;
  background:radial-gradient(circle at 32% 28%,#C084FC,#8E5BD0 52%,#2FA9C0 130%);
  box-shadow:0 0 34px rgba(168,85,247,.65);}
.orb::after{content:'';position:absolute;inset:-7px;border-radius:50%;
  border:2px solid rgba(168,85,247,.55);}
.brandrow .bt{font-weight:900;font-size:44px;line-height:1.1;}
.brandrow .bs{font-weight:600;font-size:26px;color:var(--sub);letter-spacing:2.5px;}
.brandrow .line{flex:1;height:3px;border-radius:3px;
  background:linear-gradient(90deg,var(--purple),var(--gi),transparent);}
/* ——— keyboard mock (Layouts.java AR rows, drawHyper style) ——— */
.kb{background:rgba(5,6,26,.88);border-radius:38px;padding:22px 20px 24px;
  border:1.5px solid rgba(159,140,255,.22);}
.toolbar{display:flex;align-items:center;gap:14px;height:92px;margin-bottom:18px;
  border-radius:999px;padding:0 20px;
  background:rgba(255,255,255,.055);
  background-image:linear-gradient(0deg,rgba(168,85,247,.05),rgba(168,85,247,.05));
  border:1.5px solid var(--rim);box-shadow:0 0 26px rgba(168,85,247,.12);}
.tool{width:58px;height:58px;border-radius:50%;flex:0 0 auto;display:flex;
  align-items:center;justify-content:center;font-size:30px;color:#D9D2F5;
  background:rgba(255,255,255,.06);border:1.5px solid rgba(159,140,255,.25);}
.avatar{width:62px;height:62px;border-radius:50%;flex:0 0 auto;
  background:radial-gradient(circle at 32% 28%,#C084FC,#8E5BD0 55%,#2FA9C0 135%);
  border:2.5px solid rgba(192,132,252,.75);box-shadow:0 0 20px rgba(168,85,247,.55);}
.sugg{flex:1;display:flex;gap:12px;justify-content:center;min-width:0;}
.sug{font-weight:700;font-size:34px;color:#D9D2F5;padding:12px 30px;border-radius:999px;
  background:rgba(255,255,255,.05);border:1.5px solid rgba(159,140,255,.20);white-space:nowrap;}
.sug.lit{color:#fff;border:1.8px solid transparent;
  background:linear-gradient(rgba(11,12,36,.9),rgba(11,12,36,.9)) padding-box,
   linear-gradient(100deg,var(--purple),var(--gc)) border-box;
  box-shadow:0 0 24px rgba(168,85,247,.35);}
.row{display:flex;gap:12px;margin-bottom:12px;}
.row:last-child{margin-bottom:0;}
.key{flex:1;height:96px;border-radius:18px;display:flex;align-items:center;
  justify-content:center;font-weight:600;font-size:46px;color:var(--keytext);
  background:linear-gradient(180deg,rgba(255,255,255,.10),rgba(255,255,255,.035));
  border:1.5px solid var(--rim);
  box-shadow:inset 0 1px 0 rgba(255,255,255,.16);}
.key.fn{font-size:32px;font-weight:700;color:#CFC8F2;}
.key.w12{flex:1.2;} .key.w14{flex:1.4;} .key.w18{flex:1.8;} .key.w38{flex:3.8;}
.key.enter{border:none;font-size:40px;color:#fff;
  background:linear-gradient(135deg,var(--gp),var(--gi),var(--gc));
  box-shadow:0 0 42px rgba(168,85,247,.45),inset 0 1px 0 rgba(255,255,255,.25);}
.key.space{font-size:30px;font-weight:700;color:#CFC8F2;}
.key.tash{font-size:52px;}
/* misc widgets */
.field{display:flex;align-items:center;gap:16px;border-radius:26px;padding:26px 30px;
  font-size:42px;font-weight:600;color:var(--keytext);margin-bottom:20px;
  background:rgba(255,255,255,.05);border:1.5px solid var(--rim);}
.field .cur{width:3px;height:52px;border-radius:3px;background:var(--cyan);
  box-shadow:0 0 12px var(--cyan);}
.tcard{border-radius:28px;padding:26px 30px;background:rgba(255,255,255,.05);
  border:1.5px solid var(--rim);}
.tcard.lit{border:2px solid transparent;
  background:linear-gradient(rgba(11,12,36,.85),rgba(11,12,36,.85)) padding-box,
   linear-gradient(100deg,var(--purple),var(--gc)) border-box;
  box-shadow:0 0 34px rgba(168,85,247,.30);}
.tcard .tt{font-weight:800;font-size:36px;color:#fff;}
.tcard .ts{font-weight:500;font-size:28px;color:var(--sub);line-height:1.5;}
.gradbtn{display:inline-flex;align-items:center;justify-content:center;gap:14px;
  border-radius:999px;padding:18px 42px;font-weight:800;font-size:34px;color:#fff;
  background:linear-gradient(100deg,var(--gp),var(--gi),var(--gc));
  box-shadow:0 0 36px rgba(168,85,247,.4);}
.ghostbtn{display:inline-flex;align-items:center;justify-content:center;gap:12px;
  border-radius:999px;padding:18px 42px;font-weight:700;font-size:34px;color:#E4DFF9;
  background:rgba(255,255,255,.05);border:1.5px solid var(--rim);}
.feature-graphic{width:1024px;height:500px;position:absolute;top:0;left:0;overflow:hidden;
  display:none;direction:rtl;background:linear-gradient(135deg,var(--bg) 0%,var(--bg2) 100%);}
.feature-graphic.active{display:block;}
"""


def kb(sugg=("صباح", "صباح الخير", "صباحات"), lit=1, space="العربية", typed=None):
    """Arabic keyboard mock — exact rows from Layouts.java + bottom(true,3.8,true)."""
    def keys(spec):
        return "".join(f'<div class="key {k[1]}">{k[0]}</div>' for k in spec)
    sugs = "".join(
        f'<div class="sug{" lit" if i == lit else ""}">{s}</div>' for i, s in enumerate(sugg))
    typed_html = (f'<div class="field">{typed}<span class="cur"></span></div>'
                  if typed else "")
    rows = [
        keys([(c, "") for c in "ض ص ث ق ف غ ع ه خ ح ج".split()]),
        keys([(c, "") for c in "ش س ي ب ل ا ت ن م ك ط".split()]),
        keys([(c, "") for c in "ذ ء ؤ ر ى ة و ز ظ د".split()] + [("⌫", "fn w12")]),
        keys([("?123", "fn w14"), ("،", ""), ("☺", ""), (space, "space w38"),
              (".", ""), ("َ", "tash"), ("⏎", "enter w18")]),
    ]
    rows_html = "".join(f'<div class="row">{r}</div>' for r in rows)
    return f'''{typed_html}<div class="kb">
  <div class="toolbar">
    <div class="tool">‹</div>
    <div class="sugg">{sugs}</div>
    <div class="tool">✦</div><div class="avatar"></div>
  </div>
  {rows_html}
</div>'''


def scene(idx, inner):
    return (f'<div class="scene" id="s{idx}"><div class="aurora"></div>'
            f'<div class="aurb"></div><div class="wrap">{inner}</div></div>')


def head_block(kicker, h1, sub, chips):
    chip_html = "".join(f'<div class="chip"><span class="dot"></span>{c}</div>' for c in chips)
    return f'''<div class="kicker"><span class="dot"></span>{kicker}</div>
<h1>{h1}</h1>
<div class="sub">{sub}</div>
<div class="chips">{chip_html}</div>'''


def brand():
    return '''<div class="brandrow"><div class="orb"></div>
<div><div class="bt">نيو كيبورد</div><div class="bs">NEOKEY</div></div>
<div class="line"></div></div>'''
