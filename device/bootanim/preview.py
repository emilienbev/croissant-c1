"""Export a static SVG and a five-panel contact sheet after render.py."""
from pathlib import Path
from base64 import b64encode
from playwright.sync_api import sync_playwright
here=Path(__file__).resolve().parent
with sync_playwright() as p:
    b=p.chromium.launch()
    page=b.new_page(viewport={'width':480,'height':640},device_scale_factor=1)
    page.goto((here/'anim.html').as_uri()+'?static')
    page.screenshot(path=str(here/'build/static-preview.png'))
    mark=page.locator('#mascot').evaluate('(el) => el.outerHTML')
    mark=mark.replace('#0B0B0C','black').replace('#FF4F00','white')
    svg=f'''<svg xmlns="http://www.w3.org/2000/svg" viewBox="118 192 244 306" fill="none">
<title>Croissant kamado</title>
<defs><mask id="silhouette" maskUnits="userSpaceOnUse" x="118" y="192" width="244" height="306">
<g fill="white" stroke="black" stroke-width="6" stroke-linejoin="round" stroke-linecap="round">{mark}</g>
</mask></defs>
<path fill="#FF4F00" d="M118 192H362V498H118Z" mask="url(#silhouette)"/>
</svg>'''
    (here/'kamado-static.svg').write_text(svg)
    cards=[]
    for name,filename in [('Static','static-preview.png'),('0.00 s','part1/00000.png'),('0.50 s','part1/00015.png'),('1.00 s','part1/00030.png'),('1.50 s','part1/00045.png')]:
        data=b64encode((here/'build'/filename).read_bytes()).decode()
        cards.append(f'<div><p>{name}</p><img src="data:image/png;base64,{data}"></div>')
    page.set_viewport_size({'width':1248,'height':380})
    page.set_content('<style>body{margin:0;padding:16px;background:#19191b;color:#aaa;font:13px system-ui;display:flex;gap:8px}p{margin:0 0 12px 8px}img{width:240px;height:320px;display:block}</style>'+''.join(cards))
    page.screenshot(path=str(here/'build/contact-sheet.png'))
    b.close()
