"""Subset Noto Sans KR to the glyphs the app can show (UI strings + rest stop names).
Run again after changing UI text or rest_stops.csv:  python3 tools/subset_fonts.py <NotoSansKR dir>"""
import pathlib, subprocess, sys

root = pathlib.Path(__file__).resolve().parents[1]
src = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else pathlib.Path.home() / '.local/share/fonts')
chars = set(''.join(chr(c) for c in range(0x20, 0x7F)) + '·℃₂…%')
for p in [*root.glob('app/src/main/java/**/*.kt'), *root.glob('app/src/main/res/**/*.xml'),
          root / 'app/src/main/assets/rest_stops.csv']:
    chars |= set(p.read_text(encoding='utf-8'))
text = ''.join(sorted(c for c in chars if c.isprintable()))
for weight in ('Regular', 'Bold'):
    out = root / f'app/src/main/res/font/noto_sans_kr_{weight.lower()}.otf'
    subprocess.run(['pyftsubset', str(src / f'NotoSansKR-{weight}.otf'), f'--text={text}',
                    '--layout-features=*', f'--output-file={out}'], check=True)
    print(out.name, out.stat().st_size)
