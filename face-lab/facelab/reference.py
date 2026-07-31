"""The 6 shipped species (+ a neutral baseline) expressed as genomes.

Mirror of ``REFERENCE_GENOMES`` in ``face-genome.ts``. They anchor generation 0 and act as
the parametric renderer's sanity check: if ``drawParametric`` cannot approximate these,
the axes are wrong. Keep the two copies in step when an axis changes.
"""

from __future__ import annotations

from typing import Dict

from .genome import Genome

REFERENCE_GENOMES: Dict[str, Genome] = {
    "crystalline": {
        "id": "ref-crystalline",
        "silhouette": {"family": "faceted", "width": 0.45, "jawRatio": 0.16, "cranFlat": 0.88, "jitterSeed": 0},
        "eyes": {"type": "slit", "size": 0.5, "spacing": 0.55, "tilt": 0.62},
        "signature": {"type": "thirdEyeGem", "intensity": 0.55},
        "shading": {"planes": 4, "contrast": 0.75},
        "palette": {"family": "crystal", "skinIdx": 0, "accentIdx": 2, "glowIdx": 0},
        "background": "aura",
    },
    "saurian": {
        "id": "ref-saurian",
        "silhouette": {"family": "beaked", "width": 0.27, "jawRatio": 0.45, "cranFlat": 0.5, "jitterSeed": 0},
        "eyes": {"type": "verticalPupil", "size": 0.58, "spacing": 0.42, "tilt": 0.55},
        "signature": {"type": "dorsalCrest", "intensity": 0.6},
        "shading": {"planes": 3, "contrast": 0.55},
        "palette": {"family": "saurian", "skinIdx": 0, "accentIdx": 3, "glowIdx": 0},
        "background": "none",
    },
    "monument": {
        "id": "ref-monument",
        "silhouette": {"family": "carved", "width": 0.55, "jawRatio": 0.49, "cranFlat": 0.25, "jitterSeed": 0},
        "eyes": {"type": "hollowGlow", "size": 0.45, "spacing": 0.5, "tilt": 0.5},
        "signature": {"type": "laurel", "intensity": 0.6},
        "shading": {"planes": 4, "contrast": 0.6},
        "palette": {"family": "marble", "skinIdx": 0, "accentIdx": 0, "glowIdx": 0},
        "background": "aura",
    },
    "rokykario": {
        "id": "ref-rokykario",
        "silhouette": {"family": "plated", "width": 0.64, "jawRatio": 0.62, "cranFlat": 0.65, "jitterSeed": 0},
        "eyes": {"type": "molten", "size": 0.44, "spacing": 0.5, "tilt": 0.5},
        "signature": {"type": "rockCrest", "intensity": 0.7},
        "shading": {"planes": 4, "contrast": 0.7},
        "palette": {"family": "basalt", "skinIdx": 1, "accentIdx": 0, "glowIdx": 0},
        "background": "heatHaze",
    },
    "eleftamide": {
        "id": "ref-eleftamide",
        "silhouette": {"family": "teardrop", "width": 0.18, "jawRatio": 0.25, "cranFlat": 0.2, "jitterSeed": 0},
        "eyes": {"type": "raptorRound", "size": 0.55, "spacing": 0.42, "tilt": 0.55},
        "signature": {"type": "featherCrest", "intensity": 0.75},
        "shading": {"planes": 3, "contrast": 0.5},
        "palette": {"family": "plumage", "skinIdx": 0, "accentIdx": 0, "glowIdx": 0},
        "background": "windLines",
    },
    "aquanimenti": {
        "id": "ref-aquanimenti",
        "silhouette": {"family": "dome", "width": 0.55, "jawRatio": 0.05, "cranFlat": 0.12, "jitterSeed": 0},
        "eyes": {"type": "sphericalLidless", "size": 0.72, "spacing": 0.6, "tilt": 0.5},
        "signature": {"type": "gills", "intensity": 0.7},
        "shading": {"planes": 3, "contrast": 0.45},
        "palette": {"family": "abyssal", "skinIdx": 0, "accentIdx": 0, "glowIdx": 0},
        "background": "lightShafts",
    },
    "human": {
        "id": "ref-human",
        "silhouette": {"family": "smooth", "width": 0.4, "jawRatio": 0.45, "cranFlat": 0.3, "jitterSeed": 0},
        "eyes": {"type": "raptorRound", "size": 0.42, "spacing": 0.48, "tilt": 0.5},
        "signature": {"type": "none", "intensity": 0.0},
        "shading": {"planes": 3, "contrast": 0.45},
        "palette": {"family": "plumage", "skinIdx": 2, "accentIdx": 11, "glowIdx": 0},
        "background": "none",
    },
}

REFERENCE_ORDER = ["crystalline", "saurian", "monument", "rokykario", "eleftamide",
                   "aquanimenti", "human"]
