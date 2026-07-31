"""Genome -> feature vector.

Data is scarce (tens to a few hundred votes), so the encoding stays flat and explicit:
one-hot for the categorical axes, the raw numerics, a handful of derived colour statistics,
and the silhouette x signature interaction the two axes are most visibly coupled through
(a tall crest on a spire skull reads completely differently to the same crest on a dome).
"""

from __future__ import annotations

from typing import Dict, List, Sequence

from . import palettes
from .genome import (BACKGROUND_TYPES, EYE_TYPES, PALETTE_FAMILY_IDS, SIGNATURE_TYPES,
                     SILHOUETTE_FAMILIES, Genome)


def _one_hot(value: str, vocab: Sequence[str], prefix: str) -> Dict[str, float]:
    return {f"{prefix}={v}": (1.0 if v == value else 0.0) for v in vocab}


def feature_dict(g: Genome) -> Dict[str, float]:
    sil, eyes, sig, sh, pal = g["silhouette"], g["eyes"], g["signature"], g["shading"], g["palette"]
    banks = palettes.load()
    fam = banks.get(pal["family"], banks[PALETTE_FAMILY_IDS[0]])
    body = fam["body"][pal["skinIdx"] % 12]
    accent = fam["accent"][pal["accentIdx"] % 12]
    glow = fam["glow"][pal["glowIdx"] % 12]

    f: Dict[str, float] = {}
    f.update(_one_hot(sil["family"], SILHOUETTE_FAMILIES, "sil"))
    f.update(_one_hot(eyes["type"], EYE_TYPES, "eye"))
    f.update(_one_hot(sig["type"], SIGNATURE_TYPES, "sig"))
    f.update(_one_hot(pal["family"], PALETTE_FAMILY_IDS, "pal"))
    f.update(_one_hot(g["background"], BACKGROUND_TYPES, "bg"))

    # silhouette x signature — the interaction the plan calls out
    for s in SILHOUETTE_FAMILIES:
        for t in SIGNATURE_TYPES:
            f[f"sil*sig={s}*{t}"] = 1.0 if (sil["family"] == s and sig["type"] == t) else 0.0

    f["num.width"] = float(sil["width"])
    f["num.jawRatio"] = float(sil["jawRatio"])
    f["num.cranFlat"] = float(sil["cranFlat"])
    f["num.jitter"] = float(sil["jitterSeed"]) / 3.0
    f["num.eyeSize"] = float(eyes["size"])
    f["num.eyeSpacing"] = float(eyes["spacing"])
    f["num.eyeTilt"] = float(eyes["tilt"])
    f["num.sigIntensity"] = float(sig["intensity"])
    f["num.planes"] = (float(sh["planes"]) - 2.0) / 2.0
    f["num.contrast"] = float(sh["contrast"])

    # derived colour statistics — what the eye actually reads
    lb = palettes.luminance(body["md"])
    la = palettes.luminance(accent["md"])
    lg = palettes.luminance(glow["mid"])
    f["col.bodyLum"] = lb
    f["col.accentLum"] = la
    f["col.glowLum"] = lg
    f["col.bodyAccentGap"] = abs(lb - la)
    f["col.bodyGlowGap"] = abs(lb - lg)
    f["col.bodyRange"] = abs(palettes.luminance(body["lt"]) - palettes.luminance(body["dk"]))

    # a coarse warm/cool read of the body and the glow
    for name, hexv in (("body", body["md"]), ("glow", glow["mid"])):
        r, gg, b = palettes.hex_to_rgb(hexv)
        f[f"col.{name}Warm"] = (r - b) / 255.0
        f[f"col.{name}Sat"] = (max(r, gg, b) - min(r, gg, b)) / 255.0
    return f


def feature_names() -> List[str]:
    """Stable column order — derived from a canonical genome so it never depends on data."""
    probe: Genome = {
        "id": "probe",
        "silhouette": {"family": SILHOUETTE_FAMILIES[0], "width": 0.5, "jawRatio": 0.5,
                       "cranFlat": 0.5, "jitterSeed": 0},
        "eyes": {"type": EYE_TYPES[0], "size": 0.5, "spacing": 0.5, "tilt": 0.5},
        "signature": {"type": SIGNATURE_TYPES[0], "intensity": 0.5},
        "shading": {"planes": 3, "contrast": 0.5},
        "palette": {"family": PALETTE_FAMILY_IDS[0], "skinIdx": 0, "accentIdx": 0, "glowIdx": 0},
        "background": BACKGROUND_TYPES[0],
    }
    return list(feature_dict(probe).keys())


def vectorise(genomes: Sequence[Genome]) -> List[List[float]]:
    names = feature_names()
    rows = []
    for g in genomes:
        f = feature_dict(g)
        rows.append([f.get(k, 0.0) for k in names])
    return rows
