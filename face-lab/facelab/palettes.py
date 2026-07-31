"""Palette banks for the Face Lab genome.

Single source of truth is the TypeScript module ``face-genome.ts`` in the frontend: this
module *parses the colour literals out of it* rather than keeping a second hand-typed copy,
then caches the result to ``face-lab/palettes.json`` so later runs work without the
frontend checkout being present.

Only the raw colour literals are parsed. The 8-family mapping (which const feeds which
axis, and the per-family ink) is declared here, because it is short, stable, and a
mismatch fails loudly instead of drifting silently.
"""

from __future__ import annotations

import json
import os
import re
from pathlib import Path
from typing import Dict, List, Sequence

# Default location of the frontend checkout; override with FACELAB_TS.
DEFAULT_TS = Path.home() / "Downloads" / "footballmanager-frontend-test" / \
    "src" / "app" / "face-lab" / "face-genome.ts"

CACHE = Path(__file__).resolve().parent.parent / "palettes.json"

# ---------------------------------------------------------------- colour helpers
# Exact ports of the TS helpers, so a Python-side constraint check agrees with the
# gallery's.


def hex_to_rgb(h: str) -> tuple:
    h = h.lstrip("#")
    if len(h) == 3:
        h = "".join(c * 2 for c in h)
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


def rgb_to_hex(r: float, g: float, b: float) -> str:
    def c(v: float) -> int:
        return max(0, min(255, round(v)))
    return "#%02x%02x%02x" % (c(r), c(g), c(b))


def shade(hex_colour: str, t: float) -> str:
    """t > 0 lightens toward white, t < 0 darkens toward black."""
    r, g, b = hex_to_rgb(hex_colour)
    target = 255 if t > 0 else 0
    k = abs(t)
    return rgb_to_hex(r + (target - r) * k, g + (target - g) * k, b + (target - b) * k)


def luminance(hex_colour: str) -> float:
    r, g, b = hex_to_rgb(hex_colour)
    return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0


def glows_from(flat: Sequence[str]) -> List[dict]:
    return [{"bright": shade(h, 0.4), "mid": h, "dk": shade(h, -0.45)} for h in flat]


def ramps_from(items: Sequence[dict]) -> List[dict]:
    return [{
        "lt": c["bright"],
        "md": c["mid"],
        "dk": c["dk"],
        "hl": c.get("tip") or c.get("edge") or shade(c["bright"], 0.35),
    } for c in items]


# ---------------------------------------------------------------- family mapping
# (body const, accent const, glow const, glow transform, ink)
FAMILY_SPEC: Dict[str, dict] = {
    "crystal": dict(body="XTAL_BODY", accent="XTAL_BODY", glow="XTAL_GLOW",
                    glow_kind="flat", accent_kind="ramp", ink="#15121a", label="Crystal"),
    "saurian": dict(body="SAUR_HIDE", accent="SAUR_HIDE", glow="SAUR_EYE",
                    glow_kind="flat", accent_kind="ramp", ink="#160f0a", label="Saurian hide"),
    "marble": dict(body="MON_MAT", accent="MON_GOLD", glow="MON_GLOW",
                   glow_kind="flat", accent_kind="ramp", ink="#6a6154", label="Marble & bronze"),
    "basalt": dict(body="ROK_ROCK", accent="ROK_CREST", glow="ROK_LAVA",
                   glow_kind="glow", accent_kind="accent", ink="#0b0806", label="Basalt & lava"),
    "plumage": dict(body="ELF_SKIN", accent="ELF_PLUME", glow="ELF_EYE",
                    glow_kind="glow", accent_kind="accent", ink="#2a2418", label="Plumage"),
    "abyssal": dict(body="AQUA_SKIN", accent="AQUA_FIN", glow="AQUA_EYE",
                    glow_kind="glow", accent_kind="accent", ink="#06222c", label="Abyssal"),
    "fungal": dict(body="FUNGAL_BODY", accent="FUNGAL_ACCENT", glow="FUNGAL_GLOW",
                   glow_kind="flat", accent_kind="accent", ink="#231d18", label="Fungal spore"),
    "chrome": dict(body="CHROME_BODY", accent="CHROME_ACCENT", glow="CHROME_GLOW",
                   glow_kind="flat", accent_kind="accent", ink="#121820", label="Chrome alloy"),
}

FAMILY_IDS = list(FAMILY_SPEC.keys())

_HEX = r"#[0-9a-fA-F]{3,8}"
_OBJ = re.compile(r"\{([^{}]*)\}")
_FIELD = re.compile(r"(\w+)\s*:\s*'(" + _HEX + r")'")


def _const_block(src: str, name: str) -> str:
    """Return the bracketed literal that follows ``const <name>``."""
    m = re.search(r"const\s+" + re.escape(name) + r"\b[^=]*=\s*", src)
    if not m:
        raise KeyError(f"palette const {name!r} not found in the TypeScript source")
    i = src.index("[", m.end())
    depth, j = 0, i
    while j < len(src):
        if src[j] == "[":
            depth += 1
        elif src[j] == "]":
            depth -= 1
            if depth == 0:
                return src[i:j + 1]
        j += 1
    raise ValueError(f"unterminated literal for {name!r}")


def _parse_objects(block: str) -> List[dict]:
    return [{k: v for k, v in _FIELD.findall(body)} for body in _OBJ.findall(block)]


def _parse_flat(block: str) -> List[str]:
    return re.findall(r"'(" + _HEX + r")'", block)


def parse_typescript(ts_path: Path) -> Dict[str, dict]:
    """Build the 8 palette families from the TS colour literals."""
    src = ts_path.read_text(encoding="utf-8")
    out: Dict[str, dict] = {}
    for fam_id, spec in FAMILY_SPEC.items():
        body = _parse_objects(_const_block(src, spec["body"]))

        accent_raw = _parse_objects(_const_block(src, spec["accent"]))
        accent = accent_raw if spec["accent_kind"] == "ramp" else ramps_from(accent_raw)

        glow_block = _const_block(src, spec["glow"])
        if spec["glow_kind"] == "flat":
            glow = glows_from(_parse_flat(glow_block))
        else:
            glow = _parse_objects(glow_block)

        for name, bank, need in (("body", body, 12), ("accent", accent, 12), ("glow", glow, 12)):
            if len(bank) != need:
                raise ValueError(
                    f"family {fam_id}: expected {need} {name} entries, parsed {len(bank)} "
                    f"(is {spec[name]} still a 12-entry literal?)")

        out[fam_id] = {"id": fam_id, "label": spec["label"], "ink": spec["ink"],
                       "body": body, "accent": accent, "glow": glow}
    return out


_cached: Dict[str, dict] | None = None


def load(refresh: bool = False, ts_path: Path | None = None) -> Dict[str, dict]:
    """Palette families, from the cache when possible and from the TS otherwise."""
    global _cached
    if _cached is not None and not refresh:
        return _cached

    ts = Path(os.environ.get("FACELAB_TS", "")) if os.environ.get("FACELAB_TS") else (ts_path or DEFAULT_TS)

    if not refresh and CACHE.is_file():
        _cached = json.loads(CACHE.read_text(encoding="utf-8"))
        return _cached

    if not ts.is_file():
        if CACHE.is_file():
            _cached = json.loads(CACHE.read_text(encoding="utf-8"))
            return _cached
        raise FileNotFoundError(
            f"cannot find the TypeScript palette source at {ts} and no cache at {CACHE}. "
            f"Point FACELAB_TS at src/app/face-lab/face-genome.ts.")

    _cached = parse_typescript(ts)
    CACHE.write_text(json.dumps(_cached, indent=1), encoding="utf-8")
    return _cached
