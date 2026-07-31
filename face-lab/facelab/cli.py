"""Face Lab command line.

    python -m facelab.cli status
    python -m facelab.cli seed            # write generation 0 (references + random)
    python -m facelab.cli train           # fit the scorer, report CV error + top features
    python -m facelab.cli top --k 12      # best-rated genomes so far
    python -m facelab.cli evolve          # write the next generation from the votes
    python -m facelab.cli distill --id g3-x7 --name noctilume --nation 7
    python -m facelab.cli palettes --refresh

Run it from ``face-lab/`` (or anywhere, with ``--data`` pointing at the data dir).
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from . import palettes
from .codegen import distill as do_distill
from .ga import next_generation, seed_generation
from .genome import describe, key_of
from .learner import bradley_terry, fit, importances
from .reference import REFERENCE_GENOMES, REFERENCE_ORDER
from .store import DEFAULT_DATA_DIR, Store


def _store(args) -> Store:
    return Store(args.data)


def cmd_status(args) -> int:
    st = _store(args)
    votes = st.votes()
    print(f"data dir     : {st.dir}")
    print(f"generations  : {st.generation_numbers() or '—'}")
    print(f"votes        : {len(votes)}")
    print(f"pairs        : {len(list(st.iter_pairs()))}")
    if votes:
        ratings = [v.get("rating", 0) for v in votes]
        print(f"rating range : {min(ratings)}–{max(ratings)} (mean {sum(ratings)/len(ratings):.1f})")
    try:
        banks = palettes.load()
        print(f"palettes     : {len(banks)} families ({', '.join(banks)})")
    except Exception as e:  # noqa: BLE001 — reporting, not handling
        print(f"palettes     : UNAVAILABLE — {e}")
    return 0


def cmd_seed(args) -> int:
    st = _store(args)
    refs = [REFERENCE_GENOMES[k] for k in REFERENCE_ORDER] if args.references else []
    genomes = seed_generation(size=args.size, seed=args.seed, references=refs)
    path = st.write_generation(0, genomes)
    print(f"wrote {len(genomes)} genomes -> {path}")
    for g in genomes[:8]:
        print(f"  {g['id']:>12}  {describe(g)}")
    if len(genomes) > 8:
        print(f"  … {len(genomes) - 8} more")
    return 0


def cmd_train(args) -> int:
    st = _store(args)
    rated = st.rated_genomes()
    scorer = fit(rated)
    print(f"votes  : {scorer.n_votes}")
    print(f"model  : {scorer.note}")
    if scorer.cv_mae is not None:
        print(f"CV MAE : {scorer.cv_mae:.2f} rating points (5-fold, out of fold)")
    elif scorer.trained:
        print("CV MAE : not enough data for a 5-fold estimate yet")
    top = importances(scorer, top=args.k)
    if top:
        print("\nwhat the model keys on:")
        width = max(len(k) for k, _ in top)
        for name, imp in top:
            bar = "#" * max(1, int(imp * 60 / max(i for _, i in top)))
            print(f"  {name:<{width}}  {imp:6.3f}  {bar}")
    pairs = list(st.iter_pairs())
    if pairs:
        bt = bradley_terry(pairs)
        ranked = sorted(bt.items(), key=lambda kv: -kv[1])[:8]
        print("\nA/B (Bradley-Terry) leaders:")
        for gid, s in ranked:
            print(f"  {gid:>14}  {s:+.3f}")
    return 0


def cmd_top(args) -> int:
    st = _store(args)
    best = st.best(args.k)
    if not best:
        print("no votes yet")
        return 0
    for i, row in enumerate(best):
        g = row["genome"]
        print(f"{i:>2}. {row['mean']:5.1f}  ({row['votes']} vote(s))  {g.get('id',''):>14}  {describe(g)}")
    return 0


def cmd_evolve(args) -> int:
    st = _store(args)
    rated = st.rated_genomes()
    scorer = fit(rated)
    ranked = st.best(args.parents)

    generation = args.generation
    if generation is None:
        generation = st.latest_generation() + 1
    if generation <= 0:
        print("generation 0 is the seed — run `seed` instead", file=sys.stderr)
        return 2

    seen = [key_of(r["genome"]) for r in rated]
    for n in st.generation_numbers():
        seen += [key_of(g) for g in st.read_generation(n)]

    genomes = next_generation(generation, ranked, scorer,
                              size=args.size, seed=args.seed, seen_keys=seen)
    path = st.write_generation(generation, genomes)
    print(f"model : {scorer.note}")
    print(f"wrote {len(genomes)} genomes -> {path}")
    for g in genomes:
        meta = g.get("meta", {})
        print(f"  {g['id']:>12}  {meta.get('op','?'):<9} pred {meta.get('predicted','?'):>6}"
              f"  unc {meta.get('uncertainty','?'):>6}  {describe(g)}")
    print("\nReload /dev/face-gallery (tab Evoluție) to rate this generation.")
    return 0


def cmd_distill(args) -> int:
    st = _store(args)
    genome = None
    if args.file:
        genome = json.loads(Path(args.file).read_text(encoding="utf-8"))
    else:
        target = args.id
        for n in reversed(st.generation_numbers()):
            for g in st.read_generation(n):
                if g.get("id") == target:
                    genome = g
                    break
            if genome:
                break
        if genome is None:
            for row in st.rated_genomes():
                if row["genome"].get("id") == target:
                    genome = row["genome"]
                    break
    if genome is None:
        print(f"genome {args.id!r} not found in any generation or vote", file=sys.stderr)
        return 1

    written = do_distill(genome, args.name, args.nation)
    print(f"genome : {genome['id']}  {describe(genome)}")
    for kind, path in written.items():
        print(f"{kind:>9} : {path}")
    print("\nThe drawX() renderer itself comes from the gallery: /dev/face-gallery ->"
          " tab Evoluție -> îngheață -> descarcă .md")
    return 0


def cmd_palettes(args) -> int:
    banks = palettes.load(refresh=args.refresh)
    for fid, fam in banks.items():
        print(f"{fid:<9} ink {fam['ink']}  body[0] {fam['body'][0]['md']}"
              f"  accent[0] {fam['accent'][0]['md']}  glow[0] {fam['glow'][0]['mid']}")
    print(f"\ncache: {palettes.CACHE}")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="facelab", description="Face Lab — evolve new species faces")
    p.add_argument("--data", default=str(DEFAULT_DATA_DIR), help="data directory (shared with the backend)")
    sub = p.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("status", help="votes / generations / palettes on disk")
    s.set_defaults(func=cmd_status)

    s = sub.add_parser("seed", help="write generation 0")
    s.add_argument("--size", type=int, default=24)
    s.add_argument("--seed", type=int, default=20260730)
    s.add_argument("--no-references", dest="references", action="store_false",
                   help="do not anchor generation 0 with the shipped species")
    s.set_defaults(func=cmd_seed, references=True)

    s = sub.add_parser("train", help="fit the scorer and report what it learned")
    s.add_argument("--k", type=int, default=20, help="how many features to report")
    s.set_defaults(func=cmd_train)

    s = sub.add_parser("top", help="best-rated genomes so far")
    s.add_argument("--k", type=int, default=12)
    s.set_defaults(func=cmd_top)

    s = sub.add_parser("evolve", help="write the next generation")
    s.add_argument("--generation", type=int, default=None)
    s.add_argument("--size", type=int, default=24)
    s.add_argument("--seed", type=int, default=20260730)
    s.add_argument("--parents", type=int, default=12, help="size of the elite/parent pool")
    s.set_defaults(func=cmd_evolve)

    s = sub.add_parser("distill", help="export palettes + plumbing for a winning genome")
    s.add_argument("--id", help="genome id, e.g. g3-x7")
    s.add_argument("--file", help="path to a genome JSON instead of --id")
    s.add_argument("--name", required=True, help="species name, e.g. noctilume")
    s.add_argument("--nation", type=int, default=None, help="nation id for NATION_SPECIES")
    s.set_defaults(func=cmd_distill)

    s = sub.add_parser("palettes", help="show / refresh the palette banks")
    s.add_argument("--refresh", action="store_true", help="re-parse face-genome.ts")
    s.set_defaults(func=cmd_palettes)
    return p


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    raise SystemExit(main())
