#!/usr/bin/env python3
"""Converte prints de bases em fixtures do teste do BaseMatcher.

O app captura a tela já reduzida para 1000 px de largura e trabalha só com a
luminância (média de R, G e B). As fixtures guardam exatamente isso: PNG em
tons de cinza com 1000 px de largura, em app/src/test/resources/bases/.
O nome do arquivo é <número da base>-<aparelho>.png, como no chrollo.

Uso:  python tools/make_fixtures.py print1.webp [print2.png ...]   (precisa do Pillow)
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "app" / "src" / "test" / "resources" / "bases"
WORK_WIDTH = 1000


def main() -> None:
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    OUT.mkdir(parents=True, exist_ok=True)
    for arg in sys.argv[1:]:
        src = Path(arg)
        image = Image.open(src).convert("RGB")
        height = round(image.height * WORK_WIDTH / image.width)
        image = image.resize((WORK_WIDTH, height), Image.Resampling.BILINEAR)
        rgb = image.tobytes()
        lum = bytes((rgb[i] + rgb[i + 1] + rgb[i + 2] + 1) // 3 for i in range(0, len(rgb), 3))
        gray = Image.frombytes("L", image.size, lum)
        target = OUT / f"{src.stem}.png"
        gray.save(target, optimize=True)
        print(f"{target.relative_to(ROOT)}  {gray.width}x{gray.height}  (original {Image.open(src).size})")


if __name__ == "__main__":
    main()
