# Draws docs/remote-controls.png: the TCL RC833 remote with each key Family Feed uses labelled.
#
#   python3 tools/remote/make.py /tmp/remote
#   "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" --headless=new --hide-scrollbars \
#     --force-device-scale-factor=1 --window-size=1700,1420 --screenshot=docs/remote-controls.png file:///tmp/remote/remote.html
#
# Writes remote-controls.svg and remote.html (the SVG in a page, for the screenshot) into the given folder.
import sys
out_dir = sys.argv[1]

W, H = 1700, 1420
OY = 110  # remote drawn below the title
CX = 850

BODY = '#25272b'
BTN = '#3b3e44'
DIM = '#33363b'
ACCENT = '#f5a400'
LINE = '#d98a00'
INK = '#16181c'
SUB = '#4a4f57'

svg = []
a = svg.append

def y(v):
    return v + OY

def circle(cx, cy, r, fill=BTN, accent=False, dim=False):
    f = DIM if dim else fill
    a(f'<circle cx="{cx}" cy="{y(cy)}" r="{r}" fill="{f}"/>')
    if accent:
        a(f'<circle cx="{cx}" cy="{y(cy)}" r="{r + 5}" fill="none" stroke="{ACCENT}" stroke-width="4"/>')

def text(x, yy, s, size=24, fill='#fff', weight='600', anchor='middle', raw_y=False, family='Helvetica Neue, Helvetica, Arial, sans-serif'):
    yv = yy if raw_y else y(yy)
    a(f'<text x="{x}" y="{yv}" font-family="{family}" font-size="{size}" font-weight="{weight}" fill="{fill}" '
      f'text-anchor="{anchor}" dominant-baseline="central">{s}</text>')

# ---------- background and title ----------
a(f'<rect width="{W}" height="{H}" fill="#f4f5f7"/>')
text(CX, 46, 'Family Feed — remote controls', size=44, fill=INK, weight='700', raw_y=True)
text(CX, 92, 'Highlighted keys do something special inside the app. All other keys work as usual.', size=24,
     fill=SUB, weight='400', raw_y=True)

# ---------- remote body ----------
a('<defs><linearGradient id="fade" x1="0" y1="0" x2="0" y2="1">'
  f'<stop offset="0" stop-color="{BODY}"/><stop offset="0.82" stop-color="{BODY}"/>'
  '<stop offset="1" stop-color="#f4f5f7"/></linearGradient></defs>')
a(f'<rect x="{CX - 150}" y="{y(0)}" width="300" height="1250" rx="60" fill="url(#fade)"/>')

# power / mute
circle(785, 50, 24, dim=True)
a(f'<path d="M776 {y(42)} h6 l9 -8 v32 l-9 -8 h-6 z" fill="#9aa0a8"/>')
circle(915, 50, 24, dim=True)
a(f'<path d="M907 {y(42)} a11 11 0 1 0 16 0" fill="none" stroke="#e05050" stroke-width="3.5" stroke-linecap="round"/>'
  f'<line x1="915" y1="{y(36)}" x2="915" y2="{y(50)}" stroke="#e05050" stroke-width="3.5" stroke-linecap="round"/>')

# number pad
rows = [130, 200, 270, 340]
cols = [775, 850, 925]
for i, d in enumerate('123456789'):
    cx, cy = cols[i % 3], rows[i // 3]
    special = d in '59'
    circle(cx, cy, 26, accent=special, dim=not special)
    text(cx, cy, d, size=26, fill='#fff' if special else '#9aa0a8')
circle(775, 340, 26, dim=True)
a(f'<path d="M766 {y(331)} h14 v18 h-14 M760 {y(340)} h12 m-4 -4 l4 4 l-4 4" fill="none" stroke="#9aa0a8" stroke-width="2.5"/>')
circle(850, 340, 26, accent=True)
text(850, 340, '0', size=26)
circle(925, 340, 26, dim=True)  # Menu: the TV keeps it for its own settings
for dy in (-7, 0, 7):
    a(f'<line x1="914" y1="{y(340 + dy)}" x2="936" y2="{y(340 + dy)}" stroke="#9aa0a8" stroke-width="3" stroke-linecap="round"/>')

# colour keys
for cx, col in ((760, '#e53935'), (820, '#2fb35a'), (880, '#f2b600'), (940, '#2f7de1')):
    circle(cx, 405, 19, fill=BTN, accent=True)
    a(f'<circle cx="{cx}" cy="{y(405)}" r="11" fill="{col}"/>')

# D-pad
DY = 575
a(f'<circle cx="{CX}" cy="{y(DY)}" r="118" fill="{BTN}"/>')
a(f'<circle cx="{CX}" cy="{y(DY)}" r="123" fill="none" stroke="{ACCENT}" stroke-width="4"/>')
a(f'<circle cx="{CX}" cy="{y(DY)}" r="60" fill="{BODY}"/>')
a(f'<circle cx="{CX}" cy="{y(DY)}" r="54" fill="{BTN}" stroke="{ACCENT}" stroke-width="4"/>')
text(CX, DY, 'OK', size=28)
for dx, dy, rot in ((0, -88, 0), (0, 88, 180), (-88, 0, 270), (88, 0, 90)):
    a(f'<path d="M0 -9 L10 6 L-10 6 Z" fill="#fff" transform="translate({CX + dx} {y(DY + dy)}) rotate({rot})"/>')

# back / mic / home
circle(775, 740, 28, accent=True)
a(f'<path d="M786 {y(740)} h-22 m8 -8 l-8 8 l8 8" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>')
circle(850, 770, 28, dim=True)
a(f'<rect x="845" y="{y(756)}" width="10" height="18" rx="5" fill="#9aa0a8"/>'
  f'<path d="M840 {y(768)} a10 10 0 0 0 20 0 M850 {y(778)} v6" fill="none" stroke="#9aa0a8" stroke-width="2.5"/>')
circle(925, 740, 28, accent=True)
a(f'<path d="M913 {y(742)} l12 -11 l12 11 v12 h-24 z" fill="#fff"/>')

# settings / live TV
circle(775, 825, 26, dim=True)
text(775, 825, '⚙', size=26, fill='#9aa0a8', weight='400')
circle(925, 825, 26, dim=True)
a(f'<rect x="913" y="{y(819)}" width="24" height="16" rx="3" fill="none" stroke="#9aa0a8" stroke-width="2.5"/>')

# VOL / SAT / SUBTITLE / CH
text(770, 868, 'VOL', size=17, fill='#9aa0a8')
a(f'<rect x="740" y="{y(882)}" width="60" height="140" rx="30" fill="{DIM}"/>')
text(770, 912, '+', size=34, fill='#9aa0a8')
text(770, 992, '−', size=34, fill='#9aa0a8')
circle(850, 905, 28, dim=True)
text(850, 905, 'SAT', size=16, fill='#9aa0a8')
circle(850, 985, 28, dim=True)
text(850, 985, 'SUB', size=16, fill='#9aa0a8')
text(930, 868, 'CH', size=17, fill='#fff')
a(f'<rect x="900" y="{y(882)}" width="60" height="140" rx="30" fill="{BTN}" stroke="{ACCENT}" stroke-width="4"/>')
a(f'<path d="M920 {y(918)} l10 -10 l10 10" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>')
a(f'<path d="M920 {y(986)} l10 10 l10 -10" fill="none" stroke="#fff" stroke-width="3.5" stroke-linecap="round" stroke-linejoin="round"/>')

# app buttons (faded)
for cx, cy, label in ((775, 1075, 'NETFLIX'), (850, 1075, 'prime'), (925, 1075, '▶'),
                      (775, 1150, 'SONY'), (850, 1150, 'TCL'), (925, 1150, 'Jio')):
    a(f'<circle cx="{cx}" cy="{y(cy)}" r="30" fill="#c9ccd1" opacity="0.55"/>')
    text(cx, cy, label, size=13, fill='#555', weight='700')

# ---------- labels ----------
# (side, label anchor y, title, [lines], path points from the label edge to the key)
L, R = 600, 1100
labels = [
    ('L', 200, '5  ·  Pointer on / off', ['For CAPTCHAs and login checks:', 'arrows move a pointer, OK clicks'],
     [(L, 200), (690, 165), (850, 165), (850, 174)]),
    ('L', 330, '0  ·  Reload', ['If Facebook gets stuck'],
     [(L, 330), (690, 305), (850, 305), (850, 314)]),
    ('L', 430, 'Red  ·  Home feed', ['Back to the first post'],
     [(L, 430), (690, 405), (736, 405)]),
    ('L', 530, 'Green  ·  Reels', [],
     [(L, 530), (690, 438), (820, 438), (820, 429)]),
    ('L', 650, 'Arrows  ·  Move the blue ring', ['Reels: ◀ ▶ jump 10 s,', '▲ ▼ previous / next Reel',
                                             'Pointer mode: move the pointer'],
     [(L, 650), (690, 600), (727, 575)]),
    ('L', 800, 'Back  ·  Close / go back', ['Comments, menus, Reels, pointer;', 'from the top bar: exit the app'],
     [(L, 800), (690, 760), (742, 745)]),
    ('L', 960, 'VOL  ·  TV volume', ['Unchanged'],
     [(L, 960), (735, 952)], True),
    ('R', 175, '9  ·  Update the app', ['Only when you ask her to:', 'checks GitHub, then installs'],
     [(R, 175), (1010, 270), (956, 270)]),
    ('R', 420, 'Blue  ·  Comments', ['Of the current post or Reel'],
     [(R, 420), (1010, 405), (964, 405)]),
    ('R', 530, 'Yellow  ·  Like / unlike', ['The current post or Reel'],
     [(R, 530), (1010, 438), (880, 438), (880, 429)]),
    ('R', 660, 'OK  ·  Open / click', ['Reels and videos: play / pause', 'Hold (0.8 s) in Reels: Like,',
                                    'Comment and Share buttons'],
     [(R, 660), (1000, 640), (898, 594)]),
    ('R', 800, 'Home  ·  Leave the app', ['Closes it fully; stays logged in'],
     [(R, 800), (1010, 760), (958, 745)]),
    ('R', 950, 'CH ∧ ∨  ·  Scroll a screen', ['Hold to keep scrolling', 'Reels: previous / next Reel'],
     [(R, 950), (965, 952)]),
]
for item in labels:
    side, ly, title, lines, path = item[:5]
    grey = len(item) > 5 and item[5]
    col = '#9aa0a8' if grey else LINE
    pts = ' '.join(f'{px},{y(py)}' for px, py in path)
    a(f'<polyline points="{pts}" fill="none" stroke="{col}" stroke-width="3" stroke-linejoin="round" stroke-linecap="round"/>')
    ex, ey = path[-1]
    a(f'<circle cx="{ex}" cy="{y(ey)}" r="6" fill="{col}"/>')
    x = L - 12 if side == 'L' else R + 12
    anchor = 'end' if side == 'L' else 'start'
    n = 1 + len(lines)
    top = ly - (n - 1) * 17
    text(x, top, title, size=29, fill='#8a9099' if grey else INK, weight='700', anchor=anchor)
    for i, s in enumerate(lines):
        text(x, top + 38 + i * 30, s, size=22, fill=SUB, weight='400', anchor=anchor)

text(CX, H - 40, 'These keys only do this inside Family Feed. Leaving the app with Back or Home closes it fully; the login is kept.',
     size=22, fill=SUB, weight='400', raw_y=True)

doc = f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">' + ''.join(svg) + '</svg>'
open(f'{out_dir}/remote-controls.svg', 'w').write(doc)
open(f'{out_dir}/remote.html', 'w').write(
    '<!doctype html><meta charset="utf-8"><style>html,body{margin:0;padding:0;background:#f4f5f7}</style>' + doc)
print('ok')
