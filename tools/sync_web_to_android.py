"""Copy the web experience into the Android app (android/app/src/main/assets/web), offline-ready:
three.js is bundled locally (only the modules actually imported) instead of loaded from the CDN.

Usage: python3 tools/sync_web_to_android.py <path-to-three.js-checkout>
"""
import os, re, shutil, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
THREE = sys.argv[1]
WEB = os.path.join(ROOT, 'web')
OUT = os.path.join(ROOT, 'android', 'app', 'src', 'main', 'assets', 'web')
CDN = 'https://cdn.jsdelivr.net/npm/three@0.186.1/'

shutil.rmtree(OUT, ignore_errors=True)
os.makedirs(OUT)
html = open(os.path.join(WEB, 'index.html'), encoding='utf8').read()
html = html.replace(CDN + 'build/three.module.js', './lib/three/build/three.module.js')
html = html.replace(CDN + 'examples/jsm/', './lib/three/examples/jsm/')
open(os.path.join(OUT, 'index.html'), 'w', encoding='utf8').write(html)
shutil.copytree(os.path.join(WEB, 'assets'), os.path.join(OUT, 'assets'))

# resolve the import graph of the addons we use
IMP = re.compile(r"""(?:import|export)\s[^'"]*?from\s*['"]([^'"]+)['"]|import\s*['"]([^'"]+)['"]""")
todo = ['examples/jsm/' + m for m in re.findall(r"from 'three/addons/([^']+)'", html)]
todo += ['build/three.module.js']
seen = set()
while todo:
    rel = os.path.normpath(todo.pop())
    if rel in seen:
        continue
    seen.add(rel)
    src = os.path.join(THREE, rel)
    dst = os.path.join(OUT, 'lib', 'three', rel)
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    shutil.copyfile(src, dst)
    code = open(src, encoding='utf8').read()
    for a, b in IMP.findall(code):
        spec = a or b
        if spec == 'three':
            continue
        if spec.startswith('.'):
            todo.append(os.path.normpath(os.path.join(os.path.dirname(rel), spec)))
# the bare 'three' specifier used inside addons is mapped by the page's importmap
size = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(OUT) for f in fs)
print(f'copied {len(seen)} three.js modules, total {size / 1e6:.1f} MB -> {OUT}')
