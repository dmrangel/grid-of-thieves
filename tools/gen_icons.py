#!/usr/bin/env python3
"""Gera os ícones em app/src/main/res/drawable/ic_*.xml a partir do Lucide (ISC).

O Modrinth usa o Lucide como base dos ícones dele, então os mesmos traços
(grid 24x24, linha de 2 px com pontas redondas) mantêm o visual. Cada ícone
vira um VectorDrawable branco; a cor vem do tint em tempo de execução.

Uso:  python tools/gen_icons.py [--source DIR]
"""

from __future__ import annotations

import argparse
import re
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "main" / "res" / "drawable"
URL = "https://raw.githubusercontent.com/lucide-icons/lucide/main/icons/{}.svg"

ICONS = [
    "chevron-up", "chevron-down", "chevron-left", "chevron-right", "crosshair", "eye", "eye-off",
    "grid-3x3", "loader-circle", "minus", "move", "play", "rotate-ccw", "scan-search",
    "x", "zoom-in", "zoom-out", "magnet",
]

NUMBER = re.compile(r"[-+]?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")


def num(value: str | None) -> float:
    return float(value) if value else 0.0


def fmt(value: float) -> str:
    return f"{value:g}"


def normalize(d: str) -> str:
    """Separa os tokens explicitamente: o SVG permite flags de arco coladas
    ("a2 2 0 0022 17"), que alguns parsers leem errado."""
    tokens: list[str] = []
    command, index, i = "", 0, 0
    while i < len(d):
        ch = d[i]
        if ch.isalpha():
            tokens.append(ch)
            command, index, i = ch.lower(), 0, i + 1
        elif ch in " ,\t\n":
            i += 1
        elif command == "a" and index % 7 in (3, 4):
            tokens.append(ch)
            index, i = index + 1, i + 1
        else:
            match = NUMBER.match(d, i)
            tokens.append(match.group(0))
            index, i = index + 1, match.end()
    return " ".join(tokens)


def element_path(el: ET.Element) -> str:
    tag = el.tag.split("}")[-1]
    a = el.attrib
    if tag == "path":
        return normalize(a["d"])
    if tag == "circle":
        cx, cy, r = num(a.get("cx")), num(a.get("cy")), num(a.get("r"))
        return f"M{fmt(cx - r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0"
    if tag == "rect":
        x, y, w, h = num(a.get("x")), num(a.get("y")), num(a["width"]), num(a["height"])
        rx = num(a.get("rx") or a.get("ry"))
        if rx == 0:
            return f"M{fmt(x)} {fmt(y)}h{fmt(w)}v{fmt(h)}h{fmt(-w)}z"
        return (
            f"M{fmt(x + rx)} {fmt(y)}h{fmt(w - 2 * rx)}a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(rx)} {fmt(rx)}"
            f"v{fmt(h - 2 * rx)}a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(-rx)} {fmt(rx)}h{fmt(-(w - 2 * rx))}"
            f"a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(-rx)} {fmt(-rx)}v{fmt(-(h - 2 * rx))}a{fmt(rx)} {fmt(rx)} 0 0 1 {fmt(rx)} {fmt(-rx)}z"
        )
    if tag == "line":
        return f"M{a['x1']} {a['y1']}L{a['x2']} {a['y2']}"
    if tag in ("polyline", "polygon"):
        points = a["points"].replace(",", " ").split()
        pairs = [f"{points[i]} {points[i + 1]}" for i in range(0, len(points), 2)]
        return "M" + "L".join(pairs) + ("z" if tag == "polygon" else "")
    raise ValueError(f"elemento SVG não suportado: <{tag}>")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--source", type=Path, default=Path(__file__).resolve().parent / ".cache" / "lucide")
    args = parser.parse_args()
    args.source.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)

    for icon in ICONS:
        svg = args.source / f"{icon}.svg"
        if not svg.exists():
            with urllib.request.urlopen(URL.format(icon)) as response:
                svg.write_bytes(response.read())
        paths = "".join(
            f'\n    <path\n        android:pathData="{element_path(el)}"\n        android:strokeWidth="2"\n'
            f'        android:strokeColor="#FFFFFFFF"\n        android:strokeLineCap="round"\n'
            f'        android:strokeLineJoin="round" />'
            for el in ET.parse(svg).getroot()
        )
        (OUT / f"ic_{icon.replace('-', '_')}.xml").write_text(
            f"<!-- Gerado por tools/gen_icons.py a partir do Lucide ({icon}, ISC). Não edite. -->\n"
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="24dp"\n    android:height="24dp"\n'
            f'    android:viewportWidth="24"\n    android:viewportHeight="24">{paths}\n</vector>\n',
            encoding="utf-8",
            newline="\n",
        )
    print(f"{len(ICONS)} ícones em {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
