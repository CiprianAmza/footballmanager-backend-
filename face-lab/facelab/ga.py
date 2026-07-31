"""Model-guided GA: build the next generation of genomes.

Per generation of 24 (scaled proportionally for other sizes):

    8 elite    — the best-rated genomes so far, numerics nudged +/-10%
    8 crossover— whole-axis mixes of two high-rated parents (the 'random mix' of the plan)
    4 explore  — the candidates the model is least sure about
    4 random   — pure exploration, so the search can leave the current basin

Everything is seeded, so ``gen-<N>.json`` is reproducible from (seed, N, votes-so-far).
Duplicates against the current generation *and* against everything already voted on are
dropped, so the user never rates the same face twice.
"""

from __future__ import annotations

from typing import Callable, Dict, List, Sequence

from .genome import Genome, apply_constraints, crossover, key_of, make_rng, mutate, random_genome
from .learner import Scorer

#: Composition of a 24-genome generation.
MIX = {"elite": 8, "crossover": 8, "explore": 4, "random": 4}
#: How many candidates the explore slot samples before picking the most uncertain ones.
EXPLORE_POOL = 60


def _scaled_mix(size: int) -> Dict[str, int]:
    total = sum(MIX.values())
    out = {k: max(0, round(v * size / total)) for k, v in MIX.items()}
    # fix rounding drift on the random slot
    out["random"] += size - sum(out.values())
    if out["random"] < 0:
        out["crossover"] = max(0, out["crossover"] + out["random"])
        out["random"] = 0
    return out


def _tournament(rng: Callable[[], float], ranked: Sequence[dict], k: int = 3) -> Genome:
    """Pick the best of k random draws from the rated pool (higher mean wins)."""
    best = None
    for _ in range(max(1, k)):
        cand = ranked[int(rng() * len(ranked)) % len(ranked)]
        if best is None or cand["mean"] > best["mean"]:
            best = cand
    return best["genome"]  # type: ignore[index]


def next_generation(generation: int,
                    ranked: Sequence[dict],
                    scorer: Scorer,
                    size: int = 24,
                    seed: int = 20260730,
                    seen_keys: Sequence[str] = ()) -> List[Genome]:
    """``ranked`` is Store.best(...)-shaped: ``[{"genome":…, "mean":…, "votes":…}, …]``."""
    rng = make_rng(seed + generation * 7919)
    mix = _scaled_mix(size)
    out: List[Genome] = []
    keys = set(seen_keys)

    def add(g: Genome) -> bool:
        k = key_of(g)
        if k in keys:
            return False
        keys.add(k)
        out.append(g)
        return True

    counter = [0]

    def gid(tag: str) -> str:
        counter[0] += 1
        return f"g{generation}-{tag}{counter[0]}"

    # ---- elite: mutate the best-rated genomes -------------------------------
    if ranked:
        for i in range(mix["elite"]):
            parent = ranked[i % len(ranked)]["genome"]
            for _attempt in range(8):
                if add(mutate(parent, rng, gid("e"), strength=0.10)):
                    break

    # ---- crossover between high-rated parents -------------------------------
    if len(ranked) >= 2:
        for _ in range(mix["crossover"]):
            for _attempt in range(8):
                a = _tournament(rng, ranked)
                b = _tournament(rng, ranked)
                if key_of(a) == key_of(b):
                    continue
                if add(crossover(a, b, rng, gid("x"))):
                    break

    # ---- explore: the candidates the model is least sure about ---------------
    if mix["explore"] > 0:
        pool = [random_genome(rng, gid("p")) for _ in range(EXPLORE_POOL)]
        if ranked:
            pool += [mutate(ranked[i % len(ranked)]["genome"], rng, gid("p"), strength=0.35)
                     for i in range(EXPLORE_POOL // 2)]
        preds = scorer.predict(pool)
        order = sorted(range(len(pool)), key=lambda i: -preds[i].uncertainty)
        picked = 0
        for i in order:
            if picked >= mix["explore"]:
                break
            g = dict(pool[i])
            g["meta"] = {"op": "explore", "uncertainty": round(preds[i].uncertainty, 4),
                         "predicted": round(preds[i].score, 2)}
            g["id"] = gid("u")
            if add(apply_constraints(g)):
                picked += 1

    # ---- pure random --------------------------------------------------------
    guard = 0
    while len([g for g in out]) < size and guard < size * 20:
        guard += 1
        add(random_genome(rng, gid("r")))

    # annotate with the model's opinion so the gallery can show it
    if out:
        preds = scorer.predict(out)
        for g, p in zip(out, preds):
            meta = g.setdefault("meta", {})
            meta["generation"] = generation
            meta["predicted"] = round(p.score, 2)
            meta["uncertainty"] = round(p.uncertainty, 4)
    return out[:size]


def seed_generation(size: int = 24, seed: int = 20260730,
                    references: Sequence[Genome] = ()) -> List[Genome]:
    """Generation 0: the shipped species as anchors, then random genomes to fill."""
    rng = make_rng(seed)
    out: List[Genome] = []
    keys = set()
    for i, ref in enumerate(references):
        g = apply_constraints({**ref, "id": f"g0-ref{i}",
                               "meta": {"op": "reference", "of": ref.get("id")}},
                              mode="reference")
        k = key_of(g)
        if k not in keys:
            keys.add(k)
            out.append(g)
    i = 0
    guard = 0
    while len(out) < size and guard < size * 20:
        guard += 1
        g = random_genome(rng, f"g0-r{i}")
        i += 1
        k = key_of(g)
        if k not in keys:
            keys.add(k)
            out.append(g)
    return out[:size]
