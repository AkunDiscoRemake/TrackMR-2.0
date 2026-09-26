"""Static ESSL compilation/link validation; NOT a substitute for a physical GPU test."""
from pathlib import Path
import re
import subprocess
out=Path('.cache/shaders');out.mkdir(parents=True,exist_ok=True)
sources=[Path('app/src/main/cpp/renderer.cpp'),Path('app/src/main/cpp/spatial.hpp'),Path('openxr/src/main/cpp/session.cpp')]
for source in sources:
    found={}
    for name,code in re.findall(r'const char\*\s*(\w+)\s*=\s*R"\((.*?)\)";',source.read_text(),re.S):
        if '#version' not in code: continue
        extension='vert' if 'gl_Position' in code else 'frag'
        path=out/f'{source.stem}-{name}.{extension}';path.write_text(code);found[name]=path
        subprocess.run(['glslangValidator',str(path)],check=True)
    for a,b in [('vertex','fragment'),('lineVertex','lineFragment'),('vs','fs')]:
        if a in found and b in found:
            subprocess.run(['glslangValidator','-l',str(found[a]),str(found[b])],check=True)
