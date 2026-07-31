"""The Face Lab genome — Python mirror of ``face-genome.ts``.

Both sides must agree on the axis vocabularies, the hard constraints and the RNG, so a
generation produced here renders identically in the gallery. The RNG is mulberry32, the
same one the TypeScript uses, which makes a generation reproducible from its seed on
either side.
"""

from __future__ import annotations

import copy
import json
from typing import Any, Callable, Dict, List

from . import palettes

# ---------------------------------------------------------------- vocabularies

SILHOUETTE_FAMILIES = ["faceted", "beaked", "carved", "plated", "teardrop", "dome", "smooth", "spire"]
EYE_TYPES = ["slit", "verticalPupil", "hollowGlow", "molten", "raptorRound", "sphericalLidless",
             "compound", "visor"]
SIGNATURE_TYPES = ["thirdEyeGem", "dorsalCrest", "laurel", "rockCrest", "featherCrest", "gills",
                   "hornPair", "anglerLure", "none"]
BACKGROUND_TYPES = ["none", "aura", "windLines", "lightShafts", "heatHaze"]
PALETTE_FAMILY_IDS = palettes.FAMILY_IDS

GLOWING_EYES = {"slit", "hollowGlow", "molten", "sphericalLidless", "visor"}
GLOWING_SIGNATURES = {"thirdEyeGem", "anglerLure"}

MIN_BODY_ACCENT_CONTRAST = 0.14

SIGNATURE_HEADROOM = {
    "thirdEyeGem": 1.0, "dorsalCrest": 0.75, "laurel": 0.8, "rockCrest": 0.6,
    "featherCrest": 0.7, "gills": 0.85, "hornPair": 0.65, "anglerLure": 0.55, "none": 1.0,
}
TALL_FAMILIES = {"spire", "faceted", "plated"}

Genome = Dict[str, Any]

# ---------------------------------------------------------------- rng


def make_rng(seed: int) -> Callable[[], float]:
    """mulberry32 — byte-for-byte the same sequence as the TypeScript ``makeRng``."""
    state = seed & 0xFFFFFFFF

    def _imul(a: int, b: int) -> int:
        r = (a * b) & 0xFFFFFFFF
        return r - 0x100000000 if r >= 0x80000000 else r

    def rng() -> float:
        nonlocal state
        state = (state + 0x6D2B79F5) & 0xFFFFFFFF
        t = state
        t = _imul(t ^ (t >> 15), t | 1) & 0xFFFFFFFF
        t ^= (t + _imul(t ^ (t >> 7), t | 61)) & 0xFFFFFFFF
        t &= 0xFFFFFFFF
        return ((t ^ (t >> 14)) & 0xFFFFFFFF) / 4294967296.0

    return rng


def choice(rng: Callable[[], float], items: List[Any]) -> Any:
    return items[int(rng() * len(items)) % len(items)]


def clamp01(v: float) -> float:
    return 0.0 if v < 0 else 1.0 if v > 1 else v


# ---------------------------------------------------------------- constraints


def apply_constraints(g: Genome, mode: str = "sampled") -> Genome:
    """Repair a genome so it always renders on canvas, readable, with one glow at most.

    ``mode='reference'`` applies only the safety clamps (enum membership, numeric ranges,
    palette wrap). The shipped species are ground truth, not candidates: crystalline
    legitimately pairs glowing slit eyes with a glowing gem, so the aesthetic rules must
    not rewrite the very faces they were derived from.
    """
    out = copy.deepcopy(g)
    banks = palettes.load()
    pal = out.setdefault("palette", {})
    fam_id = pal.get("family")
    if fam_id not in banks:
        fam_id = PALETTE_FAMILY_IDS[0]
    pal["family"] = fam_id
    fam = banks[fam_id]
    for key in ("skinIdx", "accentIdx", "glowIdx"):
        pal[key] = int(pal.get(key, 0)) % 12

    sil = out.setdefault("silhouette", {})
    eyes = out.setdefault("eyes", {})
    sig = out.setdefault("signature", {})
    sh = out.setdefault("shading", {})

    if sil.get("family") not in SILHOUETTE_FAMILIES:
        sil["family"] = "smooth"
    if eyes.get("type") not in EYE_TYPES:
        eyes["type"] = "raptorRound"
    if sig.get("type") not in SIGNATURE_TYPES:
        sig["type"] = "none"
    if out.get("background") not in BACKGROUND_TYPES:
        out["background"] = "none"

    if mode == "sampled":
        # 1. body / accent contrast — walk the accent bank until it separates
        body_l = palettes.luminance(fam["body"][pal["skinIdx"]]["md"])
        for k in range(12):
            idx = (pal["accentIdx"] + k) % 12
            if abs(palettes.luminance(fam["accent"][idx]["md"]) - body_l) >= MIN_BODY_ACCENT_CONTRAST:
                pal["accentIdx"] = idx
                break

        # 2. the signature must stay inside the 100x100 canvas
        cap = SIGNATURE_HEADROOM[sig["type"]]
        if sil["family"] in TALL_FAMILIES:
            cap *= 0.8
        if float(sil.get("width", 0.5)) > 0.8:
            cap *= 0.9
        sig["intensity"] = clamp01(min(float(sig.get("intensity", 0.5)), cap))

        # 3. at most ONE glowing element — emissive eyes win
        if eyes["type"] in GLOWING_EYES and sig["type"] in GLOWING_SIGNATURES:
            sig["type"] = "hornPair" if sig["type"] == "thirdEyeGem" else "dorsalCrest"
            sig["intensity"] = clamp01(min(sig["intensity"], SIGNATURE_HEADROOM[sig["type"]]))

    if sig["type"] == "none":
        sig["intensity"] = 0.0

    # 4. numeric ranges
    for key in ("width", "jawRatio", "cranFlat"):
        sil[key] = clamp01(float(sil.get(key, 0.5)))
    sil["jitterSeed"] = int(round(float(sil.get("jitterSeed", 0)))) % 4
    for key in ("size", "spacing", "tilt"):
        eyes[key] = clamp01(float(eyes.get(key, 0.5)))
    sh["planes"] = max(2, min(4, int(round(float(sh.get("planes", 3))))))
    sh["contrast"] = clamp01(float(sh.get("contrast", 0.5)))
    return out


# ---------------------------------------------------------------- sampling / mixing


def random_genome(rng: Callable[[], float], gid: str) -> Genome:
    return apply_constraints({
        "id": gid,
        "silhouette": {
            "family": choice(rng, SILHOUETTE_FAMILIES),
            "width": rng(), "jawRatio": rng(), "cranFlat": rng(),
            "jitterSeed": int(rng() * 4),
        },
        "eyes": {"type": choice(rng, EYE_TYPES), "size": rng(), "spacing": rng(), "tilt": rng()},
        "signature": {"type": choice(rng, SIGNATURE_TYPES), "intensity": 0.35 + rng() * 0.65},
        "shading": {"planes": 2 + int(rng() * 3), "contrast": 0.3 + rng() * 0.7},
        "palette": {
            "family": choice(rng, PALETTE_FAMILY_IDS),
            "skinIdx": int(rng() * 12), "accentIdx": int(rng() * 12), "glowIdx": int(rng() * 12),
        },
        "background": choice(rng, BACKGROUND_TYPES),
        "meta": {"op": "random"},
    })


def crossover(a: Genome, b: Genome, rng: Callable[[], float], gid: str) -> Genome:
    """Whole-axis inheritance — the 'random mix' the lab is built around."""
    pick = lambda key: copy.deepcopy(a[key] if rng() < 0.5 else b[key])  # noqa: E731
    return apply_constraints({
        "id": gid,
        "silhouette": pick("silhouette"),
        "eyes": pick("eyes"),
        "signature": pick("signature"),
        "shading": pick("shading"),
        "palette": pick("palette"),
        "background": a["background"] if rng() < 0.5 else b["background"],
        "meta": {"op": "crossover", "parents": [a.get("id"), b.get("id")]},
    })


def mutate(a: Genome, rng: Callable[[], float], gid: str, strength: float = 0.1) -> Genome:
    """Elite mutation: numerics drift +/-strength, categoricals flip only rarely."""
    out = copy.deepcopy(a)
    out["id"] = gid
    out["meta"] = {"op": "mutate", "parents": [a.get("id")], "strength": strength}

    def jog(v: float) -> float:
        return clamp01(float(v) + (rng() * 2 - 1) * strength)

    for key in ("width", "jawRatio", "cranFlat"):
        out["silhouette"][key] = jog(out["silhouette"][key])
    for key in ("size", "spacing", "tilt"):
        out["eyes"][key] = jog(out["eyes"][key])
    out["signature"]["intensity"] = jog(out["signature"]["intensity"])
    out["shading"]["contrast"] = jog(out["shading"]["contrast"])

    if rng() < strength:
        out["silhouette"]["jitterSeed"] = int(rng() * 4)
    for key in ("skinIdx", "accentIdx", "glowIdx"):
        if rng() < strength:
            out["palette"][key] = int(rng() * 12)
    if rng() < strength * 0.5:
        out["eyes"]["type"] = choice(rng, EYE_TYPES)
    if rng() < strength * 0.5:
        out["signature"]["type"] = choice(rng, SIGNATURE_TYPES)
    if rng() < strength * 0.3:
        out["silhouette"]["family"] = choice(rng, SILHOUETTE_FAMILIES)
    if rng() < strength * 0.3:
        out["background"] = choice(rng, BACKGROUND_TYPES)
    return apply_constraints(out)


def describe(g: Genome) -> str:
    return (f"{g['silhouette']['family']}/{g['eyes']['type']}/{g['signature']['type']}"
            f" · {g['palette']['family']}")


def key_of(g: Genome) -> str:
    """Stable identity of the *look* (ignoring the id) — used to dedupe a generation."""
    return json.dumps({k: v for k, v in g.items() if k not in ("id", "meta")}, sort_keys=True)
