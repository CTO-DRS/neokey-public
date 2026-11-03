# Generates styled QR drawables for the developer card (run from keyboard-app/)
import segno
from PIL import Image, ImageDraw
L = [("qr_whatsapp","https://wa.me/message/MVTVWPOPFOVFF1","#0E7A5F"),
     ("qr_telegram","https://t.me/CTO_DRS","#1A6FA8"),
     ("qr_tiktok","https://www.tiktok.com/@cto.drs","#111111"),
     ("qr_instagram","https://www.instagram.com/cto.drs","#B3245F"),
     ("qr_x","https://x.com/cto_drs","#000000"),
     ("qr_youtube","https://youtube.com/@cto-drs","#C00000"),
     ("qr_github","https://github.com/CTO-DRS","#24292F"),
     ("qr_site","https://cto-drs.com","#3F37C9")]
S = 4
for name, url, col in L:
    q = segno.make(url, error='m', boost_error=False)
    m = [list(r) for r in q.matrix]; n = len(m)
    cell = 24; size = (n + 2*S) * cell
    im = Image.new("RGB", (size, size), "white"); d = ImageDraw.Draw(im)
    def finder(r, c): return (r < 7 and c < 7) or (r < 7 and c >= n-7) or (r >= n-7 and c < 7)
    for r in range(n):
        for c in range(n):
            if m[r][c] and not finder(r, c):
                x = (c+S)*cell; y = (r+S)*cell; p = 2
                d.rounded_rectangle([x+p, y+p, x+cell-p, y+cell-p], radius=cell*0.4, fill=col)
    for (r, c) in [(0,0),(0,n-7),(n-7,0)]:
        x = (c+S)*cell; y = (r+S)*cell
        d.rounded_rectangle([x, y, x+7*cell, y+7*cell], radius=cell*2, fill=col)
        d.rounded_rectangle([x+cell, y+cell, x+6*cell, y+6*cell], radius=cell*1.4, fill="white")
        d.rounded_rectangle([x+2*cell, y+2*cell, x+5*cell, y+5*cell], radius=cell*0.9, fill=col)
    im = im.resize((600, 600), Image.LANCZOS)
    im.save(f"res/drawable-nodpi/{name}.png", optimize=True)
    print(name, n)
