# MatchPlan refactor — un singur rezultat canonic

**Status:** în implementare (fazat). Ultima actualizare: 2026-07-21.

## Principiu

Un `MatchPlan` canonic decide **scorul + minutele golurilor**. Un **resolver comun**
alege marcatorii/pasatorii dintre jucătorii aflați pe teren la minutul fiecărui gol.
`MatchEvent` devine singura sursă de adevăr; `Scorer`, ratingurile, clasamentele și
recordurile sunt **proiecții** din el.

```
Motor rezultat (calculateScores / two-axis / admin override)
        ↓
MatchPlan canonic: scor90 + a.e.t + penalty + minutele golurilor
        ↓
Executor comun (live sau instant)
        ↓
ContributionResolver: marcator+assist din cei de pe teren la acel minut
        ↓
MatchEvent canonic
   ├── rezumatul meciului
   ├── Scorer + assisturi (proiecție, fără RNG)
   ├── ratingurile jucătorilor
   └── clasamente + recorduri
```

## Decizii stabilite

- Scorul e calculat definitiv **înaintea** meciului live.
- Planul conține echipa + minutul fiecărui gol, dar **nu** marcatorul.
- Marcatorul se alege **în momentul golului**, doar dintre jucătorii de pe teren atunci.
- Schimbările pot influența marcatorii/assisturile, **nu** scorul.
- Live-ul poate genera ratări, parade, cornere, cartonașe fără să schimbe rezultatul.
- `Scorer` nu mai distribuie aleatoriu goluri — devine proiecție a evenimentelor canonice.
- Meciurile nevizionate folosesc aceeași logică, executată instant.

### „Pe teren" — definiție dublă

| | Instant / AI | Live privit |
|---|---|---|
| Set de pe teren | din `subPlan` simulat determinist | jucătorii reali (sub-urile userului) |
| Efectul sub-urilor | pre-planificate, contează la goluri | schimbă **cine** marchează, nu scorul |

### Prelungiri + penalty (knockout)

Pentru meciurile eliminatorii (o manșă, sau două manșe egale la general) care intră în
prelungiri se calculează un scor `a.e.t` suplimentar; dacă rămâne egal, un scor de penalty.
- Golurile din prelungiri (`EXTRA_TIME`) **intră** în statistici (goluri/assist/rating).
- Loviturile de departajare (`shootout`) **nu** sunt goluri în clasamente — sunt separate.

Datele există deja prin `KnockoutMatchResolution` (etA/etB, penaltyA/penaltyB, decidedBy).

## Model canonic

`MatchPlan`:
- `matchId`, `seed`
- `homeScore90 / awayScore90` (timp regulamentar)
- `homeScoreET / awayScoreET` (prelungiri; -1 dacă nu s-a jucat)
- `homeShootout / awayShootout` (penalty departajare; -1 dacă nu s-a jucat)
- `status: PLANNED | IN_PROGRESS | COMPLETED | COMMITTED`
- `goalSlots[]`

`GoalSlot`:
- `teamId`, `minute`, `phase: REGULAR_TIME | EXTRA_TIME`, `goalType`
- `scorerId` (null până se produce), `assistId` (null până se produce), `resolved`

## Cele două fluxuri RNG (critic)

Ca live și instant să producă **aceiași marcatori** cu același seed, rezolvarea unui gol
nu are voie să depindă de câte evenimente cosmetice a inventat live-ul înainte.
- **RNG canonic**: derivat determinist per slot (`seed ⊕ slotIndex`) → marcator/assist.
- **RNG cosmetic**: stream separat → ratări, parade, cornere, comentariu. Nu atinge rezultatul.

## Fazele de implementare

1. **Model canonic** — `MatchPlan`, `GoalSlot`, `GoalPhase`. *(Faza 1)*
2. **Resolver comun** — `ContributionResolver`, config-driven peste `PositionScoringWeights`. *(Faza 1)*
3. **Planificare** — `MatchPlanningService`: rezultat → plan (minute pe faze, goalType, seed). *(Faza 1)*
4. **Executor live** — `LiveMatchSession` încarcă planul, declanșează golurile programate,
   alege marcatorul din cei de pe teren, scrie slotul + `MatchEvent`; refresh reia progresul. *(Faza 2)*
5. **Executor instant/batch** — planul executat imediat cu sub-plan simulat; același resolver. *(Faza 2)*
6. **Statistici derivate** — `Scorer`/ratinguri/leaderboard agregate exclusiv din `MatchEvent`;
   `getScorersForTeam` devine proiecție fără RNG. *(Faza 3)*
7. **Aplicarea rezultatului separată** — standings/calificări/premii ca pas distinct la commit
   (pregătește override-ul live „Faza 4"). *(Faza 3)*
8. **Commit idempotent** — pe `matchId`; re-commit/refresh nu dublează și nu schimbă marcatorii. *(Faza 3)*
9. **Cleanup** — elimină redistribuirea din `getScorersForTeam`, marcatorii din `generateMatchEvents`,
   regenerarea live la commit, fallback-urile care inventează marcatori. **Doar după validare, în spatele
   flag-ului `matchPlan.enabled`.** *(Faza 4)*

## Rollout (de-risking)

- Noul flux e construit **în spatele flag-ului** `matchPlan.enabled` (default `false`).
- Se validează întâi pe instant: un sezon întreg, comparat cu vechiul sistem (distribuție golgheteri
  pe poziție, hat-trick-uri, echilibru) în „shadow".
- Abia apoi se comută live, apoi se șterg traseele vechi.
- Fără backfill: meciurile istorice păstrează rândurile vechi.

## Harta pe cod

| Nou | Înlocuiește / reutilizează |
|---|---|
| `MatchPlan` + slots | formalizează pinned mode din `LiveMatchSession` (`targetHomeGoals/targetAwayGoals`, `forcedGoal`) |
| `MatchPlanningService` | `MatchSimulationService.calculateScores` + `resolveKnockoutMatch` |
| `ContributionResolver` | unifică `pickWeightedAttacker` + `getDifferentValueForScoringBasedOnPosition` + `getAssistWeight` peste `PositionScoringWeights` |
| proiecție `Scorer` | golește de RNG `getScorersForTeam` / `getScorersForTeamSimplified` |
| — | elimină `generateMatchEvents` (distribuția independentă) |
| admin score | modelul `PredeterminedScore` alimentează același `MatchPlan` |

## Teste obligatorii

- Suma golurilor din evenimente == scorul.
- Fiecare marcator era pe teren la minutul golului.
- Jucătorul înlocuit nu poate marca ulterior.
- Golurile/assisturile din `Scorer` coincid exact cu `MatchEvent`.
- Penalty de departajare ≠ gol în clasamente.
- Prelungirile tratate separat și corect (a.e.t intră în stats).
- Commit apelat de două ori nu dublează.
- Refresh în timpul meciului nu schimbă planul.
- Scor administrativ prestabilit folosește același mecanism.
- Live și instant, cu același seed și fără intervenții, produc aceleași contribuții.
- **Izolarea RNG**: nr. de evenimente cosmetice variază, marcatorii rămân identici.
