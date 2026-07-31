# Face Lab

Evolve new species faces by random mixing + voting, then freeze the winner into a
production `drawX()` renderer. Implements `FACE_LAB_ML_PLAN.md`.

```
frontend (dev-only)                     backend                      face-lab/ (this dir)
─────────────────────                   ────────────────────────     ────────────────────
/dev/face-gallery                       DevFaceLabController         learner.py  scorer + uncertainty
  tab Specii     6+1 × 12 palete          POST /api/dev/facelab/votes    ga.py     next generation
  tab Parametric frozen vs genome         GET  /api/dev/facelab/batch    features.py
  tab Evoluție   grid 1–100 + A/B         POST .../generation            codegen.py palettes + plumbing
  face-codegen.ts → drawX()               files under face-lab/data/
```

Everything is seeded: a generation is reproducible from `(seed, N, votes so far)`.

## Layout

```
face-lab/
  facelab/
    palettes.py   8 colour families, PARSED from the frontend's face-genome.ts (cached)
    genome.py     schema, hard constraints, seeded sampling / crossover / mutation
    reference.py  the 6 shipped species as genomes (generation-0 anchors)
    features.py   genome -> feature vector (one-hot + numerics + silhouette×signature)
    learner.py    5× GradientBoosting bootstrap ensemble: score + uncertainty
    ga.py         8 elite / 8 crossover / 4 explore / 4 random per generation of 24
    store.py      the on-disk layout shared with DevFaceLabController
    codegen.py    palette banks + plumbing for a winning genome
    cli.py        entry point
  data/           generations/gen-N.json, votes.jsonl, pairs.jsonl   (gitignored)
  exports/        distilled artifacts                                 (gitignored)
  palettes.json   cache of the parsed colour banks                    (gitignored)
```

## Setup

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements.txt
```

`train` and the model-guided part of `evolve` need scikit-learn; everything else (seed,
status, top, distill) works without it, and `evolve` degrades to pure exploration.

The colour banks are parsed out of the frontend's `face-genome.ts` so there is only one
copy of them. Point `FACELAB_TS` at it if the checkout is not at the default location:

```bash
export FACELAB_TS=~/Downloads/footballmanager-frontend-test/src/app/face-lab/face-genome.ts
python3 -m facelab.cli palettes --refresh
```

## The loop

```bash
python3 -m facelab.cli seed          # generation 0: the 6 species + random genomes
```

Start the backend (`facelab.enabled=true` is already in `application.properties`) and the
Angular dev server, then open **`/dev/face-gallery` → tab Evoluție**, rate faces 1–100 and
press *Trimite*. Then:

```bash
python3 -m facelab.cli train         # what the model learned + out-of-fold error
python3 -m facelab.cli top --k 12    # best-rated genomes so far
python3 -m facelab.cli evolve        # writes the next generation
```

Reload the gallery — it picks up the newest generation automatically. Repeat.

If the backend is down the gallery still generates a seeded local batch, and
*publică batch-ul pe disc* pushes it to `data/generations/` so the CLI can see it.

## Distillation (F4)

```bash
python3 -m facelab.cli distill --id g3-x7 --name noctilume --nation 7
```

writes `exports/noctilume.palettes.ts`, `exports/noctilume.plumbing.md` and
`exports/noctilume.genome.json`.

**The `drawX()` renderer itself comes from the gallery**, not from Python: tab Evoluție →
*îngheață* on the winning face → *descarcă .md*. `src/app/face-lab/face-codegen.ts` runs
the parametric renderer in *symbolic mode* — same code path that drew the face you voted
on, with `${body.md}` / `${IW}` / `${uid}` placeholders instead of resolved values — so the
frozen method cannot drift from what you approved. Re-deriving the SVG in Python would
mean a second renderer implementation, which is the exact failure mode the parametric
approach exists to prevent.

The generated method has the same shape as every shipped species: the 7-step skeleton,
constants where the genome had parameters, a correct `uid`, and three 12-entry palettes
still indexed by `skinTone` / `hairColor` / `eyeColor`. The banks are **rotated** so slot 0
is the exact combination you voted on.

Then apply the two plumbing lines and delete nothing: the parametric renderer stays in
`src/app/face-lab/` and never enters the production path.

## Genome

```jsonc
{
  "silhouette": { "family": "faceted|beaked|carved|plated|teardrop|dome|smooth|spire",
                  "width": 0..1, "jawRatio": 0..1, "cranFlat": 0..1, "jitterSeed": 0..3 },
  "eyes":       { "type": "slit|verticalPupil|hollowGlow|molten|raptorRound|sphericalLidless|compound|visor",
                  "size": 0..1, "spacing": 0..1, "tilt": 0..1 },
  "signature":  { "type": "thirdEyeGem|dorsalCrest|laurel|rockCrest|featherCrest|gills|hornPair|anglerLure|none",
                  "intensity": 0..1 },
  "shading":    { "planes": 2..4, "contrast": 0..1 },
  "palette":    { "family": "crystal|saurian|marble|basalt|plumage|abyssal|fungal|chrome",
                  "skinIdx": 0..11, "accentIdx": 0..11, "glowIdx": 0..11 },
  "background": "none|aura|windLines|lightShafts|heatHaze"
}
```

The first six values on each axis are the ones extracted from the shipped renderers; the
rest are new. `reference.py` / `REFERENCE_GENOMES` express the six species as genomes —
tab *Parametric* renders them next to the frozen originals, which is the acceptance check
for the parametric renderer.

### Hard constraints

Applied at **sampling** time (`apply_constraints(g)`), never left to the model:

1. body and signature accent must differ by ≥ 0.14 relative luminance,
2. the signature is capped so it stays inside the 100×100 canvas (per type, tighter on
   tall silhouettes and wide heads),
3. at most one *glowing* element — emissive eyes win, the signature falls back.

`apply_constraints(g, mode="reference")` applies only the safety clamps. The shipped
species are ground truth, not candidates: crystalline legitimately pairs glowing slit eyes
with a glowing gem, and rule 3 must not rewrite the faces the axes were derived from.

## Keeping the two sides in step

`genome.py` mirrors `face-genome.ts` (vocabularies, constraints, mulberry32 RNG) and
`reference.py` mirrors `REFERENCE_GENOMES`. When an axis gains a value, change both.
`palettes.py` needs no update — it parses the colours from the TypeScript.
