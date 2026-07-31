"""Distillation, headless half.

The renderer text itself is emitted by ``src/app/face-lab/face-codegen.ts`` — it calls the
parametric renderer in symbolic mode, so the frozen ``drawX()`` is *generated from the
same code that drew the face the user voted on* and can never drift from it. Re-deriving
the SVG here would mean a second renderer implementation and a second thing to keep in
step, which is exactly what the parametric approach exists to avoid.

What this module does own is everything that is pure data and useful without a browser:

  * the three rotated 12-entry palette banks (slot 0 = the winning combination),
  * the two plumbing lines (FaceGenerator.java + buildInner()),
  * a frozen copy of the genome, so the export is reproducible.

Use ``facelab.cli distill`` and then the gallery's "distilare" button for the renderer.
"""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Dict, List

from . import palettes
from .genome import Genome, describe

EXPORTS = Path(__file__).resolve().parent.parent / "exports"


def slug(name: str) -> str:
    return re.sub(r"[^a-z0-9]", "", (name or "newspecies").lower()) or "newspecies"


def pascal(name: str) -> str:
    s = slug(name)
    return s[:1].upper() + s[1:]


def prefix_for(name: str) -> str:
    return slug(name)[:4].upper()


def rotate(bank: List[dict], winning: int) -> List[dict]:
    k = winning % len(bank)
    return bank[k:] + bank[:k]


def _ramp_line(r: dict, i: int) -> str:
    tail = "  // 0 = the voted face" if i == 0 else ""
    return f"    {{ lt: '{r['lt']}', md: '{r['md']}', dk: '{r['dk']}', hl: '{r['hl']}' }},{tail}"


def _glow_line(g: dict, i: int) -> str:
    tail = "  // 0 = the voted face" if i == 0 else ""
    return f"    {{ bright: '{g['bright']}', mid: '{g['mid']}', dk: '{g['dk']}' }},{tail}"


def palette_block(g: Genome, species: str) -> str:
    """The three `private static readonly` banks, rotated onto the winning combination."""
    fam = palettes.load()[g["palette"]["family"]]
    pfx = prefix_for(species)
    body = rotate(fam["body"], g["palette"]["skinIdx"])
    accent = rotate(fam["accent"], g["palette"]["accentIdx"])
    glow = rotate(fam["glow"], g["palette"]["glowIdx"])
    return (
        f"  // ---- {pascal(species)} palettes — distilled from Face Lab genome {g['id']}\n"
        f"  //      (family '{fam['id']}', rotated so slot 0 is the voted combination).\n"
        f"  /** body material: lit plane / base / shadow plane / edge highlight (by skinTone) */\n"
        f"  private static readonly {pfx}_BODY = [\n"
        + "\n".join(_ramp_line(r, i) for i, r in enumerate(body)) + "\n  ];\n"
        f"  /** signature / crest accent (by hairColor) */\n"
        f"  private static readonly {pfx}_ACCENT = [\n"
        + "\n".join(_ramp_line(r, i) for i, r in enumerate(accent)) + "\n  ];\n"
        f"  /** emissive eye / feature glow (by eyeColor) */\n"
        f"  private static readonly {pfx}_GLOW = [\n"
        + "\n".join(_glow_line(r, i) for i, r in enumerate(glow)) + "\n  ];\n"
    )


def plumbing_block(species: str, nation_id: int | None) -> str:
    sid = slug(species)
    method = "draw" + pascal(species)
    nation_line = (f"            {nation_id}L, \"{sid}\","
                   if nation_id is not None
                   else f"            <NATION_ID>L, \"{sid}\",   // a nation that has no species yet")
    return f"""## Plumbing — 2 lines

**1. Backend** — `src/main/java/com/footballmanagergamesimulator/service/FaceGenerator.java`,
in `NATION_SPECIES` (around line 101):

```java
{nation_line}
```

`Map.of` takes at most 10 pairs — switch to `Map.ofEntries(Map.entry(…), …)` once the
mapping outgrows that.

**2. Frontend** — `src/app/player-face/player-face.component.ts`, in `buildInner()`
(around line 2183), above the human fallback:

```ts
    if (this.species === '{sid}') return this.{method}();
```

The `{method}()` body comes from the gallery's "distilare" button (`/dev/face-gallery` →
tab Evoluție → îngheață → descarcă .md). Paste it next to the other `drawX()` renderers
and the palette banks next to the other `private static readonly` palettes.
"""


def distill(g: Genome, species: str, nation_id: int | None = None,
            out_dir: Path = EXPORTS) -> Dict[str, Path]:
    """Write the palette banks, the plumbing notes and the frozen genome."""
    sid = slug(species)
    out_dir.mkdir(parents=True, exist_ok=True)
    written: Dict[str, Path] = {}

    p = out_dir / f"{sid}.palettes.ts"
    p.write_text(palette_block(g, species), encoding="utf-8")
    written["palettes"] = p

    p = out_dir / f"{sid}.plumbing.md"
    p.write_text(f"# Species: {sid}\n\n{describe(g)}\n\n" + plumbing_block(species, nation_id),
                 encoding="utf-8")
    written["plumbing"] = p

    p = out_dir / f"{sid}.genome.json"
    p.write_text(json.dumps(g, indent=2), encoding="utf-8")
    written["genome"] = p

    return written
