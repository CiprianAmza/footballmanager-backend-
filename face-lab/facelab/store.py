"""Disk layout shared with ``DevFaceLabController`` (Java).

    <data-dir>/generations/gen-<N>.json   { "generation": N, "genomes": [ … ] }
    <data-dir>/votes.jsonl                one {ts, generation, genomeId, rating, genome} per line
    <data-dir>/pairs.jsonl                one A/B comparison per line

Nothing here writes anything the controller cannot read back, and vice versa.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterator, List

DEFAULT_DATA_DIR = Path(__file__).resolve().parent.parent / "data"
_GEN_FILE = re.compile(r"^gen-(\d+)\.json$")


class Store:
    def __init__(self, data_dir: Path | str = DEFAULT_DATA_DIR):
        self.dir = Path(data_dir).resolve()
        self.generations_dir = self.dir / "generations"
        self.votes_file = self.dir / "votes.jsonl"
        self.pairs_file = self.dir / "pairs.jsonl"

    # ---------------------------------------------------------------- generations

    def generation_numbers(self) -> List[int]:
        if not self.generations_dir.is_dir():
            return []
        out = []
        for p in self.generations_dir.iterdir():
            m = _GEN_FILE.match(p.name)
            if m:
                out.append(int(m.group(1)))
        return sorted(out)

    def latest_generation(self) -> int:
        nums = self.generation_numbers()
        return nums[-1] if nums else -1

    def read_generation(self, n: int) -> List[dict]:
        path = self.generations_dir / f"gen-{n}.json"
        if not path.is_file():
            return []
        return json.loads(path.read_text(encoding="utf-8")).get("genomes", [])

    def write_generation(self, n: int, genomes: List[dict]) -> Path:
        self.generations_dir.mkdir(parents=True, exist_ok=True)
        path = self.generations_dir / f"gen-{n}.json"
        path.write_text(json.dumps({
            "generation": n,
            "savedAt": datetime.now(timezone.utc).isoformat(),
            "genomes": genomes,
        }, indent=1), encoding="utf-8")
        return path

    # ---------------------------------------------------------------- votes

    def iter_votes(self) -> Iterator[dict]:
        if not self.votes_file.is_file():
            return
        with self.votes_file.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    yield json.loads(line)

    def votes(self) -> List[dict]:
        return list(self.iter_votes())

    def iter_pairs(self) -> Iterator[dict]:
        if not self.pairs_file.is_file():
            return
        with self.pairs_file.open(encoding="utf-8") as fh:
            for line in fh:
                line = line.strip()
                if line:
                    yield json.loads(line)

    def append_vote(self, generation: int, genome: dict, rating: int) -> None:
        self.dir.mkdir(parents=True, exist_ok=True)
        row = {
            "ts": datetime.now(timezone.utc).isoformat(),
            "generation": generation,
            "genomeId": genome.get("id", ""),
            "rating": int(rating),
            "genome": genome,
        }
        with self.votes_file.open("a", encoding="utf-8") as fh:
            fh.write(json.dumps(row) + "\n")

    # ---------------------------------------------------------------- derived

    def rated_genomes(self) -> List[dict]:
        """One row per vote: ``{"genome": …, "rating": …}``, newest last.

        A genome voted more than once keeps every vote — the model should see the
        disagreement rather than an average that hides it.
        """
        out = []
        for v in self.iter_votes():
            g = v.get("genome")
            if isinstance(g, dict) and g:
                out.append({"genome": g, "rating": int(v.get("rating", 50)),
                            "generation": int(v.get("generation", 0))})
        return out

    def best(self, k: int = 10) -> List[dict]:
        """Top-k distinct genomes by mean rating."""
        by_id: Dict[str, dict] = {}
        for row in self.rated_genomes():
            gid = row["genome"].get("id", "")
            slot = by_id.setdefault(gid, {"genome": row["genome"], "ratings": []})
            slot["ratings"].append(row["rating"])
        scored = [{
            "genome": s["genome"],
            "mean": sum(s["ratings"]) / len(s["ratings"]),
            "votes": len(s["ratings"]),
        } for s in by_id.values()]
        scored.sort(key=lambda r: (-r["mean"], -r["votes"]))
        return scored[:k]
