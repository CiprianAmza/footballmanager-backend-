"""Preference scorer: predicted 1-100 rating plus an uncertainty estimate.

Deliberately small: a bootstrap ensemble of 5 GradientBoostingRegressors. The mean is
the score the GA exploits; the spread across the ensemble is the uncertainty it explores.
With tens-to-hundreds of votes anything deeper would just memorise.

The model is optional: below ``MIN_VOTES`` (or with scikit-learn absent) ``fit`` returns a
null model that predicts a flat 50 with maximum uncertainty, which makes the GA fall back
to exploration — the correct behaviour on a cold start rather than a crash.
"""

from __future__ import annotations

import math
from dataclasses import dataclass, field
from typing import List, Sequence, Tuple

from .features import feature_names, vectorise
from .genome import Genome

MIN_VOTES = 20
ENSEMBLE = 5
SEED = 20260730


@dataclass
class Prediction:
    score: float
    uncertainty: float


@dataclass
class Scorer:
    """Fitted ensemble, or a null model when there is not enough signal yet."""
    models: List[object] = field(default_factory=list)
    n_votes: int = 0
    trained: bool = False
    note: str = "not trained"
    cv_mae: float | None = None

    def predict(self, genomes: Sequence[Genome]) -> List[Prediction]:
        if not self.trained:
            return [Prediction(50.0, 1.0) for _ in genomes]
        import numpy as np
        X = np.asarray(vectorise(genomes), dtype=float)
        preds = np.stack([m.predict(X) for m in self.models])  # type: ignore[attr-defined]
        mean = preds.mean(axis=0)
        std = preds.std(axis=0)
        # normalise uncertainty to 0..1 against the widest spread in this batch
        top = float(std.max()) if std.size and float(std.max()) > 1e-9 else 1.0
        return [Prediction(float(m), float(s) / top) for m, s in zip(mean, std)]

    def predict_one(self, g: Genome) -> Prediction:
        return self.predict([g])[0]


def _sklearn():
    try:
        from sklearn.ensemble import GradientBoostingRegressor  # noqa: F401
        return True
    except ImportError:
        return False


def fit(rated: Sequence[dict]) -> Scorer:
    """``rated`` is a list of ``{"genome": …, "rating": …}`` rows from Store.rated_genomes()."""
    n = len(rated)
    if n < MIN_VOTES:
        return Scorer(n_votes=n, trained=False,
                      note=f"only {n} votes; need {MIN_VOTES} before the model is worth fitting")
    if not _sklearn():
        return Scorer(n_votes=n, trained=False,
                      note="scikit-learn is not installed (pip install -r face-lab/requirements.txt)")

    import numpy as np
    from sklearn.ensemble import GradientBoostingRegressor

    genomes = [r["genome"] for r in rated]
    y = np.asarray([float(r["rating"]) for r in rated])
    X = np.asarray(vectorise(genomes), dtype=float)

    rs = np.random.RandomState(SEED)
    models = []
    for k in range(ENSEMBLE):
        idx = rs.randint(0, n, n)  # bootstrap sample
        m = GradientBoostingRegressor(
            n_estimators=180, learning_rate=0.06, max_depth=3,
            subsample=0.9, random_state=SEED + k)
        m.fit(X[idx], y[idx])
        models.append(m)

    scorer = Scorer(models=models, n_votes=n, trained=True,
                    note=f"ensemble of {ENSEMBLE} GradientBoosting on {n} votes")
    scorer.cv_mae = _cv_mae(X, y)
    return scorer


def _cv_mae(X, y) -> float | None:
    """Honest out-of-fold error, so `train` reports something other than the training fit."""
    try:
        import numpy as np
        from sklearn.ensemble import GradientBoostingRegressor
        from sklearn.model_selection import KFold
    except ImportError:
        return None
    n = len(y)
    if n < 2 * MIN_VOTES:
        return None
    errs = []
    for tr, te in KFold(n_splits=5, shuffle=True, random_state=SEED).split(X):
        m = GradientBoostingRegressor(n_estimators=180, learning_rate=0.06, max_depth=3,
                                      subsample=0.9, random_state=SEED)
        m.fit(X[tr], y[tr])
        errs.append(float(np.abs(m.predict(X[te]) - y[te]).mean()))
    return sum(errs) / len(errs)


def importances(scorer: Scorer, top: int = 20) -> List[Tuple[str, float]]:
    """Mean feature importance across the ensemble — the 'what does it like' report."""
    if not scorer.trained:
        return []
    import numpy as np
    names = feature_names()
    imp = np.mean([m.feature_importances_ for m in scorer.models], axis=0)  # type: ignore[attr-defined]
    ranked = sorted(zip(names, imp), key=lambda kv: -kv[1])
    return [(k, float(v)) for k, v in ranked[:top] if v > 0]


def bradley_terry(pairs: Sequence[dict], iterations: int = 200) -> dict:
    """Optional A/B signal: strengths from pairwise wins (log-space MM updates).

    Returned as ``{genomeId: strength}`` on a 0-centred log scale. Used only as a report;
    the 1-100 ratings remain the primary training target.
    """
    wins: dict = {}
    plays: dict = {}
    for p in pairs:
        a, b, w = p.get("aId"), p.get("bId"), p.get("winnerId")
        if not a or not b or w not in (a, b):
            continue
        wins[w] = wins.get(w, 0) + 1
        for k in (a, b):
            plays.setdefault(k, []).append(b if k == a else a)
    if not plays:
        return {}
    strength = {k: 1.0 for k in plays}
    for _ in range(iterations):
        new = {}
        for k, opponents in plays.items():
            denom = sum(1.0 / (strength[k] + strength[o]) for o in opponents if o in strength)
            new[k] = (wins.get(k, 0) + 1e-9) / denom if denom > 0 else strength[k]
        norm = sum(new.values()) / len(new)
        strength = {k: v / norm for k, v in new.items()}
    return {k: math.log(v) for k, v in strength.items()}
