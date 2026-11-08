import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "res")
SRC = os.path.join(ROOT, "src")

ids = set()
for dirpath, _, files in os.walk(os.path.join(RES, "layout")):
    for f in files:
        if f.endswith(".xml"):
            with open(os.path.join(dirpath, f), encoding="utf-8") as fh:
                text = fh.read()
            ids.update(re.findall(r'@\+id/([A-Za-z0-9_]+)', text))

# values resources
values = {"string": set(), "color": set(), "style": set(), "dimen": set(), "bool": set(), "integer": set()}
for dirpath, _, files in os.walk(os.path.join(RES, "values")):
    for f in files:
        if not f.endswith(".xml"):
            continue
        tree = ET.parse(os.path.join(dirpath, f))
        for child in tree.getroot():
            tag = child.tag
            name = child.attrib.get("name")
            if not name:
                continue
            if tag == "item":
                tag = child.attrib.get("type", "")
            if tag in values:
                values[tag].add(name)

def files_in(folder, exts):
    out = set()
    base = os.path.join(RES, folder)
    for dirpath, _, files in os.walk(base):
        for f in files:
            name, ext = os.path.splitext(f)
            if ext.lower() in exts:
                out.add(name)
    return out

drawables = files_in("drawable", {".xml", ".png", ".webp", ".jpg"})
mipmaps = files_in("mipmap-anydpi-v26", {".xml"}) | files_in("mipmap-hdpi", {".png"})
layouts = files_in("layout", {".xml"})

refs = []
for dirpath, _, files in os.walk(SRC):
    for f in files:
        if f.endswith(".java"):
            path = os.path.join(dirpath, f)
            with open(path, encoding="utf-8") as fh:
                text = fh.read()
            for m in re.finditer(r'R\.(id|string|color|layout|drawable|mipmap|style|dimen|bool|integer)\.([A-Za-z0-9_]+)', text):
                refs.append((f, m.group(1), m.group(2)))

missing = []
for f, kind, name in refs:
    if kind == "id":
        ok = name in ids
    elif kind == "layout":
        ok = name in layouts
    elif kind == "drawable":
        ok = name in drawables
    elif kind == "mipmap":
        ok = name in mipmaps
    else:
        ok = name in values.get(kind, set())
    if not ok:
        missing.append((f, kind, name))

print("ids in layouts:", len(ids), "| java refs:", len(refs))
if missing:
    for f, kind, name in sorted(set(missing)):
        print("MISSING", f, "R.%s.%s" % (kind, name))
    sys.exit(1)
print("all java resource references resolve")
