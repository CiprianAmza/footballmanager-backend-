# Football Manager Backend — Handoff (condensat 2026-05-30)

Document de stare: ce avem acum, cum se rulează/testează, și ce mai e de făcut.
Istoricul detaliat al refactor-ului (sesiunile 1–26) a fost comprimat — codul verde + git log sunt sursa de adevăr.

---

## 1. Starea curentă

- **Refactor mamut încheiat**: 0 fișiere `src/main` peste 1000 linii. `CompetitionController` 8176 → 243 linii. ~30 servicii dedicate.
- **Engine de scor unificat și config-driven**: tot scoringul (ligă/cupă/grupe/european, uman + AI, batch + live-commit) trece prin `MatchSimulationService.calculateScores`. Nu mai există copii hardcodate divergente.
- **Valoarea de meci a jucătorului = serviciu ponderat config-driven** (`PlayerValueService`, sesiunea 2026-05-30): valoarea fiecărui titular = atribute ponderate pe poziție × familiaritate cu poziția × moral × fitness; valoarea echipei = suma celor 11. Asta e puterea care intră în scor (vezi §5 + §9). `Human.rating` rămâne skill-ul generic (sortare lot, transferuri, UI).
- **Rating scale 1–300** activă peste tot (echipe rep 10k → jucători 250–300, scade cu reputația).
- **Engine determinist + seedabil** (RNG centralizat); framework complet de tuning/sensitivity/Sobol în `src/test/.../engine/`.
- **Two-leg knockout** complet: path AI/batch + path interactiv uman (vezi §5).
- Build: `mvn verify` (default) verde — ~145 unit + ~75 IT.

### Surse unice de adevăr (schimbi într-un loc → se schimbă peste tot, producție + teste)

| Domeniu | Sursă |
|---|---|
| Scor / parametri engine | `config/MatchEngineConfig` (+ `application.yml`) |
| Valoarea de meci a jucătorului / echipei | `service/PlayerValueService` (+ `MatchEngineConfig.PlayerValue`) |
| Ponderi atribute pe poziție + penalizări familiaritate | `MatchEngineConfig.PlayerValue` (default-uri shipped + override) |
| Ponderi/tabele rol + bonusuri+conflicte instrucțiuni | `MatchEngineConfig.RoleWeights` / `InstructionWeights` |
| Team talk (automat + uman) | `MatchEngineConfig.TeamTalk` + `service/TeamTalkService` |
| Decidere egalitate KO (ET/penalty/agregat) | `service/knockout/KnockoutTieResolver` |
| Format competiție (grupe/runde/two-leg/qualify) | `config/CompetitionFormat` + `CompetitionFormatConfig` |
| Format european configurabil (preliminarii→grupe→KO) | `config/EuropeanFormatPlan` |
| Structură turneu (grupe/KO/byes/round-robin) | `service/tournament/TournamentEngine` |
| Încontrări ligă pe mărime | `CompetitionFormat.encountersFor(teamCount)` |
| Alocare locuri europene (coeficient) | `service/EuropeanCoefficientService` |

---

## 2. Cunoștințe de bază (rapide)

**Tipuri competiție**: 1=League, 2=Cup, 3=SecondLeague, 4=League of Champions (LoC), 5=Stars Cup (SC).

**Runde europene**:
- LoC: 0=preliminary, 1=qualifying, 2–7=grupe, 8=QF, 9=SF, 10=Final (matchday = round+1).
- SC: 1–6=grupe, 7=playoff, 8=QF, 9=SF, 10=Final (matchday = round).
- LoC rulează configurabil (40 echipe / 4 grupe × 4 implicit) prin `EuropeanFormatPlan`; SC derivă boundary-urile din forma grupelor (playoff-ul rămâne bespoke — injectează echipe externe: loc 3 LoC + runner-ups SC).
- Two-leg implicit: LoC QF+SF (runde 8,9). Manșele se joacă pe zile de calendar separate (leg1 = stochează, leg2 = agregă + decide).

**Determinism**: 2 seed-uri fixate la `20260528` (bootstrap squad-gen + match RNG). `java.util.Random(seed)` e bit-exact cross-JVM. Schimbă numerele doar: modificarea seed-urilor, `MatchEngineConfig`, set-ul de echipe, sau codul de scoring.

---

## 3. Cum rulezi / testezi

```bash
# Backend
mvn test            # unit only, ~5-8s
mvn verify          # default: unit + IT, ~80-100s   (gate verde)
mvn spring-boot:run # port 8086

# Frontend
cd /Users/ciprian.amza/Downloads/footballmanager-frontend-test && npm start  # port 4200

# Fuzz (lent, gated) — toate *FuzzIT
mvn verify -Pfuzz                                          # MatchEngineRepStrength + ChampionshipPrediction + ...

# Harness multi-sezon (reset|continue) → target/multi-season-{mode}.md
mvn verify -Pfuzz -Dit.test=MultiSeasonHarnessFuzzIT -Dsim.mode=continue -Dseasons=5
mvn verify -Pfuzz -Dit.test=MultiSeasonHarnessFuzzIT -Dsim.mode=reset -Dseasons=5 -Dleague.id=1

# Tuning / analiză engine (rapoarte în target/*.md)
mvn verify -Ptune -Dit.test=EngineAutoTunerIT
mvn verify -Ptune -Dit.test=EngineSensitivityIT
mvn verify -Ptune -Dit.test=EngineInteractionIT

# Outcome simulations (cine câștigă; -Dteam.ids count par; -Dleg.format=single|two-leg)
mvn verify -Ptune -Dit.test='LeagueOutcomeIT#simulateLeagueAndReport' -Dleague.id=8
mvn verify -Ptune -Dit.test='LeagueOutcomeIT#simulateCustomTeamsAndReport' -Dteam.ids=1,5,8,12,25,50,80,100
mvn verify -Ptune -Dit.test='CupOutcomeIT#simulateCupAndReport' -Dteam.ids=1,5,8,12,25,50,80,100
mvn verify -Ptune -Dit.test='LeagueOfChampionsOutcomeIT#simulateLeagueOfChampionsAndReport' -Dteam.ids=<≥16 ids>
mvn verify -Ptune -Dit.test='StarsCupOutcomeIT#simulateStarsCupAndReport' -Dteam.ids=<ids>
```

Rapoarte generate în `target/`: `tuning-report.md`, `sensitivity-report.md`, `interaction-report.md`, `league-outcome-{id}.md`, `cup-outcome-*.md`, `loc-outcome-*.md`, `multi-season-{mode}.md`, `season-dynamics-deviation.md`, `transfer-economy.md`.

Profile Maven: `default` (unit+IT), `-Pfuzz` (`*FuzzIT`), `-Ptune` (`*TunerIT`/`*SensitivityIT`/`*InteractionIT` + `integration/{league,cup,europe}/*IT`).

---

## 4. Framework de tuning engine (infra reutilizabilă în `src/test`)

```
engine/
├── invariants/   — catalog de 10 reguli ("power 6× → win ≥80%"); EngineInvariantSuiteRunner
├── tuner/        — random search → hill climb; fitness = -Σ(distanță_de_target)²
├── sensitivity/  — sweep one-axis, pp shift per param × invariant
└── interaction/  — Sobol S1/ST/S2 (Jansen/Saltelli), perechi care interacționează
```

Knob-urile tunate sunt promovate în `MatchEngineConfig.Power`: `moraleFloor=0.8`, `moraleSpread=0.4`, `homeAdvantage=1.08`, `ratioExponent=2.0`, `expectedGoalsTotal=3.0`.
Model: `effectivePower = base × (moraleFloor + moraleSpread×morale/100) × (gazdă ? homeAdvantage : 1)` → `calculateScores`.

**Stare cunoscută a invariantelor**: power-dominance OK; rata de câștig "10k vs 4k" ≈ 92% (target 97% — gap rămas dacă vrei tuning mai agresiv pe exponent/goluri, dar atenție să nu strici predicția campionatului).

---

## 5. Livrat recent (2026-05-30) — comis pe `master`

Toate verzi la `mvn verify` (default). **Nepush-uite.** `application.yml` rămâne untracked prin convenție — knob-urile noi merg prin default-urile din Java.

### Sesiunea „valoare ponderată a jucătorului + team talk" (2026-05-30, partea 2)

Vezi §9 pentru designul complet al motorului de valoare. Pe scurt, comise pe `master` (nepush-uite):

| Commit | Conținut |
|---|---|
| `fae4ea6` | **`PlayerValueService`** — sursa unică pentru valoarea de meci: atribute ponderate pe poziție × familiaritate × moral × fitness, sumă pe primul 11. Înlocuiește suma de `Human.rating` (AI/batch: `MatchRoundSimulator.getSimpleTeamRating`) și calculul uman (`LineupRatingService`, ambele ramuri). Moral+fitness mutate **per-jucător** (scoase din curba team-level): `effectivePower(base,morale,home)` rămâne pt. invariantele de tuning, producția cheamă noul `effectiveTeamPower(base,teamTalk,home)`. Selecția primului 11 sortează acum după **aptitudine = skill generic × fitness** și păstrează slotul de tactică al fiecărui titular (`TacticController.getBestElevenWithSlots`) pentru familiaritate. `Human.rating` rămâne skill generic. Șterse `squadMorale`/`squadFitnessFactor`/`bestElevenIds`. |
| `f860ed9` | **Default-uri reale ponderi + familiaritate** în `MatchEngineConfig.PlayerValue`: profiluri de atribute 0–5 per poziție (sparse — nelistat = irelevant/0) pt. GK/DC/DL/DR/MC/ML/MR/ST + matrice familiaritate natural→folosit (portar inutilizabil pe teren 0.1, blând în aceeași linie, mai aspru între linii, fundaș central↔atacant 0.2). `weight()`/`familiarity()` rezolvă **override utilizator → default shipped → fallback**. |
| `055c1fc` | **Externalizare tabele + team talk.** (a) Tabele atribute per-rol tunabile (`RoleWeights.attributes` merge-uit peste `RoleDef`). (b) Bonusuri instrucțiuni data-driven (`InstructionWeights.DEFAULT_BONUSES`, base + excepții per poziție) + listă perechi conflict config. (c) **Team talk pe puterea echipei**: `teamTalkFactor` citește reputația managerului (proxy man-management) → multiplicator mic determinist (`MatchEngineConfig.TeamTalk`) lângă avantajul terenului în `effectiveTeamPower` (AI + uman; `team-talk.enabled=false` îl neutralizează). (d) **Team talk uman determinist**: `TeamTalkService.giveTeamTalk`/`giveIndividualTalk` seedează RNG-ul din contextul talk-ului (jucător, tip, fază, sezon, rundă) în loc de `new Random()` → aceeași frază în același meci reproduce aceeași reacție de moral (fără reroll). |

**Notă valoare ↔ moral ↔ team talk**: moralul fiecărui jucător intră acum în valoarea de meci (`PlayerValueService.moraleFactor`). Deci feature-ul EXISTENT de team talk uman (`TeamTalkController`/`TeamTalkService.giveTeamTalk`, care modifică moralul) **influențează deja meciul** prin moral — nu a fost nevoie de un sistem nou. `teamTalkFactor` (calitate manager) e un lever **separat**, la nivel de echipă, peste asta.

### Sesiuni anterioare

| Commit | Conținut |
|---|---|
| `d41186c` | Two-leg interactiv uman: `LiveMatchSession` poartă `legNumber/tieId/matchIndex`; `MatchdayCoordinator.finalizeInteractiveLiveMatch` decide leg-aware (leg1 persistă+amână, leg2 agregă+propagă, single-leg decide+propagă — repară și un bug latent single-leg). |
| `9eb9c98` | `knockoutResultText` în răspunsul `/commit` (first leg / advance on aggregate / a.e.t. / penalties) — pentru afișare în modalul live. |
| `5a487b9` | **Bug de producție**: toate 5 strategiile de transfer vindeau OPUSUL intenției (`subList` lua coada listei sortate). Fix `subList(0,min(n,size))` + seedare `Random`. + `TransferStrategyIT` (default) + `TransferEconomyFuzzIT` (`-Pfuzz`). |
| `0df5eff` | Teste de dinamici engine: `EngineDynamicsIT` extins + `LivePaceDynamicsIT` + `SeasonDynamicsDeviationFuzzIT` (`-Pfuzz`). |
| `c3a6a46` | `RoundRobin` suportă nr. impar de echipe (pad cu BYE sentinel, lucrează pe o copie); `FixtureSchedulingService` lungime calendar `encounters*(par?N-1:N)`. Even-N bit-identic. |
| `51d7891` | **Morală + fitness dinamice pe sezon (batch)**: `squadMorale`/`squadFitnessFactor` citite proaspăt per rundă din `roundPlayers` intră în `effectivePower` (înlocuiesc morala-manager înghețată); drenaj fitness per meci batch (`stamina.batchMatchFitnessDrain`, floor `postMatchFloor`), recuperat la antrenament. |
| `8eb7ea0` | **Unfreeze cache AI**: `invalidateRatingCache(teamId)`/`invalidateAllRatingCaches()` pe `MatchRoundSimulator` (via orchestrator), apelate după antrenament / transferuri / tranziție de sezon → rating-ul de bază AI nu mai e înghețat pe sesiune. |
| `ae52b8f` | **Harness multi-sezon** `MultiSeasonHarnessFuzzIT` (`-Pfuzz`): moduri `reset` (fiecare sezon din valori inițiale) și `continue` (evoluție reală + transferuri); raport `target/multi-season-{mode}.md` (campion/favorit + drift power). `-Dsim.mode/-Dseasons/-Dleague.id`. |
| `e93f78c` | **Antrenament scalat cu facilitățile (single-source)**: `MatchEngineConfig.Training` + helper unic `FacilityTraining.developmentFactor` (tineri ≤`youthMaxAge`→`youthTrainingLevel`, 23+→`seniorTrainingLevel`, din DB). Cablat în `TrainingService` (creștere atribute + fitness; declinul neatins) și unificat în `HumanService` + `CalendarEventDispatcher` (scoasă formula hardcodată `0.5+level/20`). |

**Notă morală/fitness/antrenament**: cele 3 commit-uri (`51d7891`+`8eb7ea0`+`e93f78c`) fac împreună ca lotul AI să **evolueze** într-un sezon simulat — morala din rezultate, fitness drenat+recuperat, antrenamentul (scalat cu facilitățile) schimbă valoarea, iar cache-ul nu mai îngheață. Insight din harness: favoritul (lotul cel mai puternic) nu câștigă titlul → varianță mare a engine-ului (vezi §6, tuning 92→97).

**Two-leg — sincronizare prod ↔ teste ↔ log-uri (LoC vs Stars Cup):**
- **LoC (type 4)**: QF (r8) + SF (r9) sunt **two-leg** (derivat din `EuropeanFormatPlan`: orice rundă KO mai puțin finala). Final (r10) single-leg. Prod chiar generează 2 manșe pe zile separate: `FixtureSchedulingService.generateSeasonCalendar` emite 2 evenimente MATCH (leg1 + leg2 la +3 zile); `getFixturesForRound` → `saveKnockoutPairing` desenează ambele manșe (același `tieId`, gazde inversate). Testat end-to-end pe servicii reale în `TwoLegKnockoutIT` (draw, leg1 amână, leg2 agregă, dispatch `simulateMatchday(...,leg)`, **commit interactiv** leg1/leg2/single-leg).
- **Stars Cup (type 5)**: **single-leg în producție** (nu setează `totalTeams`/`europeanPlan`, deci `isTwoLeg` e mereu fals). ⚠️ `StarsCupOutcomeIT` *poate* fi rulat cu `-Dleg.format=two-leg` pentru explorare, dar asta NU reflectă jocul real — în joc SC e single-leg.
- **Log-uri**: `MatchdayCoordinator.simulateMatchday` loghează `leg=1/2` per matchday; rezultatul persistat (`CompetitionTeamInfoDetail.score`) poartă „(1st leg)" / „(agg X-Y, a.e.t./pens)" (vizibil pe paginile de rezultate). Nu există un println dedicat cu agregatul în consolă.
- **Divergențe deliberate test↔prod** (documentate în `BracketUtil`): sim-urile de outcome (`-Ptune`) seedează pe **putere**, producția pe **coeficient de club**; ordinea meciurilor de grupă poate diferi (aceleași perechi).

**RĂMAS — smoke test browser** (obligatoriu, nevalidat; nu pot rula FE): echipă umană cu `viewFullMatch` în QF/SF LoC pe zile separate → leg1 nu decide, leg2 agregă corect, runda următoare desenată corect după leg2.

**Frontend (NEcomis, repo separat)**: `app.component.ts/html/css` afișează `knockoutResultText` la full-time. ⚠️ Directorul frontend NU e repo git propriu (git rezolvă la HOME) — comite-le într-un repo dedicat al frontend-ului, NU din directorul curent.

---

## 6. Ce mai e de făcut (prioritizat)

### A. Serviciu de evaluare ponderată a jucătorului — ✅ LIVRAT (2026-05-30, vezi §9)
`PlayerValueService` + `MatchEngineConfig.PlayerValue` (commits `fae4ea6`+`f860ed9`). Ponderi 0–5 per poziție, familiaritate, moral, fitness — toate config-driven cu default-uri reale shipped. Rol + instrucțiuni + team talk externalizate ulterior (`055c1fc`). Nimic rămas aici.

### B. Smoke test browser two-leg interactiv + commit FE (RĂMAS din §5)
Nevalidat: echipă umană cu `viewFullMatch` în QF/SF LoC pe zile separate (leg1 nu decide, leg2 agregă, runda următoare corectă) + afișarea `knockoutResultText`. **Frontend NEcomis** (`app.component.ts/html/css`): directorul FE **nu e repo git propriu** (git rezolvă la HOME) → comite-le într-un repo dedicat al frontend-ului.

### C. Follow-up economie de transfer (din raportul `TransferEconomyFuzzIT`)
**Cauza primară — ✅ ADRESATĂ** (commit `fb9b890`, 2026-05-30): curba de vârstă rescrisă în `TrainingService` (platou de prime 24–33 cu schimbare netă ~0, declin abia de la 34) + multiplicatorul de valoare aliniat în `TransferValueCalculator` (youth premium ≤21→1.30/≤23→1.15, platou ≤27→1.00/≤31→0.90/≤33→0.80, cliff ≤35→0.45/else 0.20 — mult mai blând decât vechiul ×0.75/0.45/0.2/0.08). Valoarea nu se mai prăbușește în prime.
**Cauza secundară — RĂMASĂ = piață ilichidă**: 22 `NO_BUY_TARGETS` (Academy nu cumpără — întoarce `null`) + 77 `NO_MARKET_MATCH` (buget insuficient SAU niciun vânzător tânăr). Levier: mai multă lichiditate (Academy/youth să listeze tineri, sau bugete mai mari). Decizie de design. *(Re-rulează `TransferEconomyFuzzIT` cu noua curbă ca să vezi cât a scăzut pierderea de ~16–20%.)*

### D. Diverse / polish
- **Balans tactic — ✅ REZOLVAT + CUTOVER FĂCUT (model pe două axe = engine implicit, vezi §12)**: `match.engine.tactical-model.enabled=true` default. Squad value decisiv (clasament ordonat după valoare în harness), tactica = lever modest. RĂMAS doar follow-up-uri de polish (live match, ET full, ștergere cod scalar — §12).
- **Tuning engine 92→97**: favoritul nu câștigă consistent titlul (varianță mare) — vezi harness-ul + `MatchEngineRepStrengthFuzzIT` (gap pre-existent ~92%, feed sintetic direct în `calculateScores`, neatins de munca de valoare). Acum cu moral/fitness per-jucător + ponderi reale + team talk, merită un sweep nou pe `power.ratioExponent`/`expectedGoalsTotal` cu `-Ptune`, fără a strica predicția campionatului.
- **Pace în batch — ✅ cablat** (sesiunea 2026-05-30): pace e unul din cele 36 de atribute ponderate, deci intră acum în valoarea de batch. Testul a devenit `EngineDynamicsIT.batchPowerReflectsPace`.
- **Fitness uman-instant**: drenajul batch e doar pe path-ul AI; meciul uman jucat instant (viewFullMatch off) nu drenează (live drenează). Simetrie ușoară dacă vrei.
- **Familiaritate/coaching age-based**: facilitățile sunt acum age-based, dar selecția staff-ului de antrenori în `HumanService` rămâne pe senior în path-ul principal (out of scope acum).
- **SC complet plan-ified** (playoff bespoke `drawStarsCupPlayoffSeeded`); **two-leg cupe naționale** (`CupBracketService` single-leg); **resume live match — replay goluri pre-refresh**.

---

## 7. Convenții utile (rafinate prin refactor)

- Service deține workflow-ul; controller-ul = REST/edge thin. Constructor injection pe controllere, field injection pe servicii. `@Lazy` doar pt. cicluri service-to-service.
- Sparge serviciile peste ~700 linii (pragul de 1000 e prea liber).
- Două pattern-uri de split: **coordinator-thin + delegates** (surface mare, mulți callers, zero churn) vs **injection swap** (surface mic, update direct callerii).
- Test foundation (IT peste comportament-cheie) ÎNAINTE de split mamut. `*IT.java` în `integration/`, order-independent (`@BeforeEach`/`@Transactional` reset).
- Delete agresiv post-extract (dead code). Validează speculațiile din handoff înainte de plan.
- **Producția trebuie să ruleze ACELAȘI engine config-driven pe care îl validează testele** — fără copii hardcodate divergente.

---

## 8. Fișiere cheie

```
config/MatchEngineConfig, CompetitionFormat(Config), EuropeanFormatPlan, EuropeanPhase/Stage
  └ MatchEngineConfig.PlayerValue/RoleWeights/InstructionWeights/TeamTalk — knob-uri valoare de meci
service/PlayerValueService                — valoarea de meci (atribute×familiaritate×moral×fitness), sumă pe 11
service/PlayerRoleService + PlayerInstructionService — suitabilitate rol / multiplicator instrucțiuni (config-driven)
service/TeamTalkService                   — team talk uman (moral, seedat) + catalog opțiuni
service/LineupRatingService               — rating uman (ambele ramuri prin PlayerValueService)
controller/TacticController               — getBestElevenWithSlots (selecție apt = skill×fitness + slot tactică)
service/MatchSimulationService            — calculateScores, effectivePower (curbă invariante) + effectiveTeamPower (prod: team talk×teren)
service/MatchRoundSimulator               — simulateRound (per-leg aware), KO progression AI/batch, getSimpleTeamRating
service/MatchdayCoordinator               — dispatch matchday + finalizeInteractiveLiveMatch (commit)
service/LiveMatchSimulationService + LiveMatchSession — engine live interactiv
service/knockout/KnockoutTieResolver      — decide(powerA,powerB,aggA,aggB,rng)
service/tournament/TournamentEngine       — primitive structură turneu
service/EuropeanCompetitionService + EuropeanCoefficientService — lifecycle + alocare europeană
service/CupBracketService                 — bracket cupă națională (persistent, propagateWinner)
transfermarket/                           — strategii transfer (TransferStrategyIT + TransferEconomyFuzzIT)
service/LineupRatingService.adjustTeamPowerByTacticalProperties — leverul tactic (mentalitate/tempo/...), vezi §10 (balans)
test integration/fuzz/HumanTacticOutcomeFuzzIT — explorare tactică umană: sweep pe axe + căutare exhaustivă (§10)
```

---

## 9. Motorul de valoare a jucătorului (config-driven, 2026-05-30)

### Modelul (de la cap la coadă, în ziua meciului)
```
1. Selecție XI: sloturile tacticii antrenorului → per slot, cel mai APT jucător
                APT = skill generic (Human.rating) × fitness ; accidentații excluși.
                Se păstrează slotul de tactică al fiecărui titular (pt. familiaritate).
2. Valoarea de meci a titularului (PlayerValueService.evaluatePlayer):
     base   = Σ(atribut × weight[usedPos][attr]) / Σ(weight[usedPos][attr])   // medie ponderată 1..20
     value  = clamp(base × scaleMultiplier, floor, ceil)                       // ~1..300 (×15 = ca Human.rating)
              × familiaritate(naturalPos, usedPos)                             // 1.0 pe poziția naturală
              × moralFactor(player.morale)                                     // per-jucător
              × fitnessFactor(player.fitness)                                  // per-jucător
              [doar path uman:] × suitabilitate rol × multiplicator instrucțiuni  // config-weighted
3. Valoarea echipei = Σ value pe cei 11.
4. Scor: calculateScores( effectiveTeamPower(valEchipăGazdă, teamTalkGazdă, home=true),
                          effectiveTeamPower(valEchipăOaspete, teamTalkOaspete, home=false) )
         effectiveTeamPower = base × teamTalk × (home ? homeAdvantage : 1)     // FĂRĂ termen de moral team-level
```
`calculateScores` folosește `power1/(power1+power2)^exp` → **scala absolută se anulează**, contează doar raportul + multiplicatorii team-talk/teren. Valoarea per-jucător stă pe scala ~1..300 (×15) ca `homeAdvantage=1.08` și invariantele tunate să rămână valide.

### Config (tot în `MatchEngineConfig`, default-uri shipped în Java; override din `application.yml`/Java)
- **`PlayerValue`**: `scaleMultiplier`, `ratingFloor/Ceil`, `moraleNeutral/moraleSlope`, `fitnessFloor`, `defaultFamiliarityPenalty`; `weights` (poziție→atribut→0..5, sparse, cheile = cele 36 nume din `PlayerSkillsService.GETTER_MAP`) și `familiarityPenalty` (natural→folosit→factor). `weight()`/`familiarity()` rezolvă **override → default profil shipped → fallback** (atribut nelistat într-un profil = 0; pereche poziții necunoscută = `defaultFamiliarityPenalty`).
- **`RoleWeights`**: `overallBlend/roleBlend/suitabilityScale` + `attributes` (rol→atribut→pondere, merge-uit peste tabelele `RoleDef`).
- **`InstructionWeights`**: `bonusScale/conflictPenalty/clampMin/clampMax` + `DEFAULT_BONUSES` (instrucțiune→{base, byPosition}) override-abile + `conflicts` (listă perechi).
- **`TeamTalk`**: `enabled`, `maxSwing`, `neutralReputation`, `reputationSpan`; `multiplier(reputation)` = automat din reputația managerului.

### Reguli importante / capcane
- **Moral/fitness sunt per-jucător**, mutate din curba team-level. `effectivePower(base,morale,home)` e PĂSTRAT doar pentru suita de invariante/tuner; producția cheamă `effectiveTeamPower(base,teamTalk,home)`.
- **`Human.rating` rămâne skill generic** (sortare lot, valori transfer, UI) — NU e atins de motorul de valoare (`PlayerSkillsService.computeOverallRating` neschimbat).
- **Team talk = două levere distincte, fără dublă-numărare**: (1) team talk-ul UMAN existent (`TeamTalkService.giveTeamTalk`) schimbă moralul → intră în valoare prin `moraleFactor`; (2) `teamTalkFactor` (calitate manager) e un multiplicator separat la nivel de echipă în `effectiveTeamPower`. `teamTalkFactor` e neutru 1.0 dacă `team-talk.enabled=false`.
- **Player fără `PlayerSkills`** (placeholder youth / gap de date) → fallback pe `Human.rating` ca bază (overload `evaluatePlayer(double baseValue, ...)`).
- **Determinism**: schimbarea ponderilor/scorul se mută, dar nu există teste de scor „golden"; `EngineDeterminismIT` verifică reproductibilitate (ține), invariantele de curbă folosesc `effectivePower` păstrat. `ChampionshipPredictionFuzzIT` (favoritul corelat) confirmat verde cu ponderile reale.
- **Limitare cunoscută**: team talk (ambele levere) se aplică pe **path-ul de scor instant** (AI + uman fără `viewFullMatch`). Meciul interactiv live (`viewFullMatch`) folosește puterile pasate la `createInteractiveSession` — team-talk-ul automat NU e încă cablat acolo (moralul uman DA, fiindcă e persistat înainte). De cablat dacă vrei simetrie.

### Teste
`PlayerValueServiceTest`, `PlayerValueConfigBindingTest` (binding chei cu spații, ex. `[First Touch]`), `PlayerRoleServiceTest`, `PlayerInstructionServiceTest`, `TeamTalkTest`, `TeamTalkServiceDeterminismTest`; `EngineDynamicsIT.batchPowerReflectsPace`.

---

## 10. Harness de explorare a tacticii umane + analiză de balans (2026-05-30, partea 3)

### `HumanTacticOutcomeFuzzIT` (comis `188165f`, gated `-Pfuzz`, skip fără `-Dteam.id`)
Test de explorare a căii de tactică umană: alegi echipa + tactica completă (formație + mentalitate, time-wasting, in-possession, passing, tempo), se simulează N sezoane (default 100) în liga echipei și se raportează poziția medie. Două moduri:

- **`simulateHumanTacticAndReport`** — rulează tactica configurată + face **sweep pe fiecare axă** (o axă variată, restul la baseline) → arată cum mută fiecare setare poziția ta. Raport `target/human-tactic-outcome-{teamId}.md`.
- **`searchBestTacticAndReport`** — **caută exhaustiv** toate cele 900 de combinații de setări (5 mentalități × 4 time-wasting × 3 possession × 3 passing × 5 tempo) și recomandă **cea mai bună tactică**. Formația e aleasă automat ca cea cu **valoarea de bază maximă** (cel mai bun fit pentru jucători), override cu `-Dformation`. Raport `target/best-tactic-{teamId}.md` (recomandare + top-15 + comandă de reproducere). ~11s pe 20 de echipe.

Cum rulezi:
```bash
mvn verify -Pfuzz -Dit.test=HumanTacticOutcomeFuzzIT -Dteam.id=104    # sweep pe axe
mvn verify -Pfuzz -Dit.test='HumanTacticOutcomeFuzzIT#searchBestTacticAndReport' -Dteam.id=104  # caută cea mai bună
```
Valorile cu spații trebuie încadrate în ghilimele: `-Dmentality="Very Attacking" -Dtempo="Much Higher"` (altfel shell-ul sparge argumentul → Maven crede că `Attacking`/`Higher` e un goal).

**Model** (mirror pe producția path-ului uman): putere de bază a fiecărei echipe = valoarea primului 11 (`PlayerValueService`); pentru echipa ta, ajustată per adversar prin `adjustTeamPowerByTacticalProperties` (leverul tactic, **dependent de adversar**); scor prin `calculateScores`. Adversarii = 4-4-2 neutru, fără ajustare (stau pentru AI). RNG seedat → determinist și **A/B-comparabil** (fiecare variație rejoacă același flux). Izolează leverul tactic: NU rulează evoluție de moral/fitness, team talk, avantaj teren.

### Analiză cheie: interacțiuni + problemă de balans în `adjustTeamPowerByTacticalProperties`
- **Sweep-ul pe o axă ascunde interacțiunile.** „Best per axă" se măsoară cu restul la baseline; setările **interacționează** (best-ul pasării depinde de tempo; `Balanced` mentality **gate-uiește** posesia/time-wasting la efect 0). Deci „best-of-fiecare-axă" ≠ cea mai bună combinație → de aici modul `searchBestTacticAndReport`.
- **Stacking necapat = dezechilibru.** Procentele din toate axele se **adună** fără plafon. Exemplu real (Desert Lion id=104, a 3-a cea mai mică valoare 1316 din 20): combinația `Very Defensive + Much Higher tempo + Long + Free Ball Early + Frequently` cumulează ~**+80%** (Very Defensive ca outsider +25, posesie +15, time-wasting +10, passing/tempo +30) → 1316×1.8 ≈ 2240, peste lider (2203). Rezultat: **poziția medie 2.30/20, 35% titluri** pentru o echipă slabă, **pur din tactică**.
- **Implicație de design**: tactica **anulează valoarea lotului** (ar trebui să fie un lever modest deasupra valorii, nu un override). De temperat: plafon total al swing-ului tactic (ex. ±10–15%) și/sau termeni mai mici / non-aditivi în `adjustTeamPowerByTacticalProperties` (`service/LineupRatingService`). **Nedecis încă** — user a cerut întâi documentarea.

### Valori valide pentru setări (enumerate și în raport)
- formație: `442 433 343 451 352 4231 4141 4411 4321 4222 3421 532 5212 541 3511`
- mentality: `Very Attacking, Attacking, Balanced, Defensive, Very Defensive`
- tempo: `Much Lower, Lower, Standard, Higher, Much Higher`
- passingType: `Short, Normal, Long`  •  inPossession: `Standard, Keep Ball, Free Ball Early`  •  timeWasting: `Never, Sometimes, Frequently, Always`

---

## 11. Comituri (2026-05-30, toate pe `master`, nepush-uite)

`fae4ea6` motor valoare · `f860ed9` default-uri ponderi/familiaritate · `055c1fc` tabele rol/instrucțiuni + team talk · `188165f` harness tactică · `fb9b890` curbă vârstă (prime 24–33 + cliff 34, vezi §6.C) · `e9f42f1` docs + `application*.yml` + `.gitignore` (ignoră `.claude/` + `*.pkg`) · `82a0c84`+`66ccdb1` model tactic pe două axe + coaching · `f018acb` cablare model în producție (flag OFF default) · `b915282` Stage B cutover (model = engine implicit, flag ON, +amplificare, re-baseline ChampionshipPrediction la top-3) (vezi §12).

Convenție: `application.yml` a fost totuși comis în `e9f42f1` (fără secrete); dacă vrei să respecți regula „knob-uri prin default-uri Java", scoate-l cu `git rm --cached src/main/resources/application.yml`. `mvn verify` default verde (145 unit + 75 IT).

---

## 12. Model tactic pe două axe (atac/apărare) + coaching — ENGINE DE PRODUCȚIE IMPLICIT (cutover făcut)

Comis `82a0c84`+`66ccdb1` (fundație) · `f018acb` (cablare producție în spatele flag-ului) · `b915282` (Stage B: cutover — flag default ON). **`match.engine.tactical-model.enabled` e acum `true` implicit** → toată producția (AI-vs-AI + uman instant, prin `simulateRound`) folosește modelul pe două axe. Setezi `false` ca să revii la engine-ul scalar.

**Cum funcționează** (`service/TacticalScoreService`): valoarea lotului → split atac/apărare după poziție (`attackShare`); setările tactice se reduc la (attackBias, risk, control) cu **trade-off** (atac↑ ⇒ apărare↓); **coaching** off/def amplifică fiecare latură (`coachedProfile`); scor **bilateral** `xG = openness × att^exp/(att^exp+def^exp)` cu **exponent de amplificare** (`ratioExponent=2.0`, squad value decisiv). Tactica adversarilor AI = aleasă de manager după skill (`ManagerTacticService`).

**Cablare (`MatchRoundSimulator`)**: `twoAxisScores` folosește RNG-ul seedat al simulatorului (determinism) + aplică team talk scalând profilul + expune totalul scalar (atac+apărare). Ambele site-uri de scor setează `teamPower1/2 = total pe două axe` → stats/moral/knockout ET-penalty/live-deferred rămân consistente. Vechiul `adjustTeamPowerByTacticalProperties` aditiv e sărit când flag ON. Profile/tactic cache per rundă, invalidate cu `simpleRatingCache`.

**Validare**: `mvn verify` VERDE (152 unit + 76 IT) cu engine-ul nou implicit — toate ITurile structurale (matchday, season transition, European, knockout, two-leg) trec. `HumanTacticOutcomeFuzzIT` arată clasament ordonat curat după valoare (Inazuma cel mai puternic → loc 1.83; Desert Lion al 3-lea cel mai slab → loc 15.82) → **squad value decisiv**. `ChampionshipPredictionFuzzIT` re-baseline la „favoritul termină **top-3** în ≥2/3 sezoane" (în loc de „câștigă mereu") → verde; modelul nou face cea mai bună echipă să **concureze realist**, nu deterministic. `TwoAxisProductionScoringIT` + test de determinism pe model.

**Eșec pre-existent (NU regresie)**: `MatchEngineRepStrengthFuzzIT` (~92% vs target 97%) testează engine-ul **scalar direct** (feed sintetic în `calculateScores`), neafectat de cutover — gap-ul documentat la §6.D. Suita scalară de tuning (`EngineAutoTuner/Sensitivity/Interaction`, `*OutcomeIT` via `TournamentEngine.playLeague`, `MatchSimulationServiceTest`, `EngineDynamicsIT.moraleAndHomeScaleEffectivePower`) rămâne verde fiindcă apelează metodele scalare **direct** — sunt acum teste „legacy scalar", nu reflectă producția.

**RĂMAS — follow-up-uri**: (1) cablarea meciului **live interactiv** (`viewFullMatch` → `LiveMatchSession`) la modelul nou (acum primește un scalar consistent atac+apărare, dar engine-ul minut-cu-minut e tot scalar); (2) **ET full pe două axe** (mini-meciul de prelungiri din `KnockoutTieResolver` e încă scalar); (3) **ștergerea** path-ului scalar aditiv `adjustTeamPowerByTacticalProperties` + retragerea/rescrierea framework-ului de tuning scalar; (4) **migrarea off/def** pentru save-uri vechi (managerii creați înainte de câmpuri = 50/50); (5) externalizarea mapărilor categorice→numerice ale tacticii în config.

### De ce (problema rezolvată)
Modelul vechi (`adjustTeamPowerByTacticalProperties`) dădea bonusuri procentuale **aditive, necapate** (~+80%), deci o echipă slabă putea ajunge vicecampioană pur din tactică (vezi §10). Modelul nou face tactica un **trade-off** mărginit, cu **matchup**, astfel încât valoarea lotului rămâne decisivă.

### Cum funcționează (`service/TacticalScoreService`)
- Valoarea lotului se împarte în **atac** și **apărare** după poziția fiecărui titular (`MatchEngineConfig.TacticalModel.attackShare`: ST 0.95, MC 0.5, DC 0.12, GK 0.0…).
- Setările de tactică se reduc la 3 axe numerice: `attackBias` (mentalitate), `risk` (tempo → deschide jocul), `control` (Keep Ball / time-wasting → apărare↑, joc mai lent). **Trade-off**: bias ofensiv → atac↑ **și apărare↓** (nu bonus gratis).
- **Coaching**: fiecare manager are `offensiveAbility` + `defensiveAbility` (0–100, `Human`, semănate la generare în `HumanService` cu zgomot independent → antrenori dezechilibrați). `coachedProfile` amplifică atacul cu abilitatea ofensivă, apărarea cu cea defensivă (±`coachStrength`=0.12). → antrenor ofensiv = echipă mai bună în atac **și** tactici ofensive îi devin optime (**stil emergent**).
- Scor **bilateral**: `xG_tău = openness × atac_tău/(atac_tău+apărare_adversar)`, Poisson. Atacul tău contează față de apărarea lor → **matchup**, nu există tactică universal-optimă.

### Selecția AI după skill (`service/ManagerTacticService`)
Rankuiește cele 900 de tactici prin proxy ieftin determinist (`expectedGoalDifference` vs un adversar reprezentativ, fără simulare); managerul ia tactica de la rangul `round((100−skill)/100 × N)` (skill = media off/def). Top coach → tactică ~optimă pentru profilul lui; coach slab → tactică mediocră.

### Harness (`HumanTacticOutcomeFuzzIT`, rescris)
Acum **fiecare adversar își alege tactica după abilitățile managerului lui** (nu mai e 442 pasiv), profilurile sunt coached, scor prin modelul nou. Rezultat (Desert Lion id=104, val 1316, a 3-a cea mai mică): cea mai bună tactică → **loc ~11/20** (vs **loc 2** în modelul vechi). Tactica = lever modest (~4 poziții), nu override de valoare. ✅ balans rezolvat.

### Proprietăți importante / capcane
- **Între două echipe egale + echilibrate, tactica e neutră pe diferența medie de goluri** (trade-off pur). Tactica contează prin: lot **dezechilibrat** (leagă-te de punctul forte), **coaching** (asimetrie per antrenor), adversar inegal, și **varianță** (jocul închis ajută outsiderul — captat de simularea Poisson, NU de proxy-ul mediu de ranking).
- **Caveat date**: managerii din DB-ul bootstrap-at predate câmpurile off/def → au default 50/50. Un joc nou îi seamănă real. Pentru a vedea stilurile în harness, trebuie bootstrap proaspăt.
- Config: `MatchEngineConfig.TacticalModel` (strengths trade-off/openness/coach, `attackShare`); mapările categorice→numerice (mentalitate→bias etc.) sunt în `TacticalScoreService` (externalizabile ulterior).
- Teste: `TacticalScoreServiceTest` (split, trade-off „leagă-te de forță", matchup pe lot, coaching).

---

## 13. Unificarea engine-ului pe două axe — live + ET + formație (2026-05-31)

Cele trei excepții care încă rulau scalar (cu flag ON) au fost unificate pe modelul pe două axe. Engine-ul scalar rămâne fallback pentru flag OFF + suita de tuning/invariante (neatins).

- **Meciul live interactiv** (`viewFullMatch` → `LiveMatchSession`): primește un `TacticalScoreService.Matchup` (effAtt/effDef redistribuite de tactică+coaching+team talk + openness); șansele de atac/posesie/big-chance derivă din matchup (atac vs apărarea adversarului, amplificat cu `ratioExponent`), nu din raportul scalar simetric. Mecanica de minut (cartonașe, man-advantage, stamina, pace) **neatinsă**. `Matchup=null` → formulele scalare vechi.
- **Prelungiri (ET) + penalty**: `KnockoutTieResolver.decide(profileA, vectorA, profileB, vectorB, aggA, aggB, rng)` rulează ET prin `TacticalScoreService.scoreExtraTime` (openness scalat cu `TacticalModel.extraTimeOpennessScale=0.33`); penalty „weaker" comparat pe `attack+defense`. Cablat în `MatchRoundSimulator.decideTie` + `MatchdayCoordinator.decideTie` (commit-ul live, prin profilele din deferred context). Scalarul `decide(double,double,...)` rămâne fallback.
- **Formația AI**: `MatchRoundSimulator.chooseFormation` rankuiește cele 15 formații după valoarea de bază a lotului și alege la rangul dat de skill (`round((100-skill)/100×(N-1))`, ca `ManagerTacticService`). Uman/flag OFF → `tacticStyle`. Helper `starterValues(teamId, formation)`.
- **Surse noi**: `TacticalScoreService.Matchup`/`matchup()`/`scoreExtraTime()`; `MatchEngineConfig.TacticalModel.extraTimeOpennessScale`. Teste: `TacticalScoreServiceTest` (scoreExtraTime, matchup), `KnockoutTieResolverIT` (decide two-axis), `AiFormationSelectionIT`.

---

## 14. Batch de fix-uri din feedback de frontend (2026-05-31) — pe `master`, `mvn verify` verde (155 unit + 79 IT)

Patru streamuri rezolvate în paralel (agenți pe worktree, merge-uite) + fix de regresie. Comit trail: `e42aaed` (D) · `002955f` (E1+F) · `524eeea` (A+G) · `d587c1a` (B) · `3de684d` (fix regresii merge).

| Stream | Livrat | Fișiere cheie |
|---|---|---|
| **A** perf/lock | `GameLock` (ReentrantLock partajat): `advance()` + `promoteToFirstTeam` se serializează → gata cu `JdbcSQLTimeoutException`/concurrent-update pe `team` la tranziție. Colaps `findAll()` duplicat. | `GameLock`, `GameAdvanceService`, `YouthAcademyService`, `NewSeasonSetupProcessor` |
| **G** tactică | `NewSeasonSetupProcessor` șterge doar `PersonalizedTactic` AI, păstrează rândul echipei umane peste sezoane. | `NewSeasonSetupProcessor` |
| **D1** carduri | `CompetitionDisplayService.getTeamCompetitions` dedup pe `competitionId` (runda max) → un card per competiție. | `CompetitionDisplayService` |
| **D2** obiective | Evaluare pe `max(round)` (nu `findFirst`); target european = runda reală de grupă → prelim-loser **eșuează corect**, nu „achieved". | `SeasonObjectiveService` |
| **D3** Stars Cup | Loserii LoC din rundele 0+1 picat în SC, **cap la capacitate** (`groupCount×groupSize`), umplut cu cei mai puternici pe reputație (nu Set-order arbitrar). | `EuropeanCompetitionService` |
| **B1** salariu | Salariu lunar manager + `Human.careerEarnings` (în `processMonthlyWages`), expus în My Manager. | `Human`, `CalendarEventDispatcher`, `ManagerController` |
| **B2** reputație | Rebalansat în `MatchEngineConfig.Reputation` (win 2.0→0.3 etc.) → <1%/victorie; trofeele rămân salturile mari. | `MatchEngineConfig`, `ManagerCareerService` |
| **B3** oferte | `evaluateMidSeasonSackings`: demitere reală (≥10 meciuri + poziție mult sub prediction) → ofertă doar la uman cu reputație în bandă; cooldown 4 luni per club (`declineOffer` stampilează ziua). Înlocuiește random-ul plat. | `ManagerCareerService`, `JobOfferService`, `GameAdvanceService` |
| **E1** goluri AI | `scorer.setRating(pv.getRating())` în `getScorersForTeamSimplified` → distribuție ponderată pe rating ca la human (golgheteri reali), nu uniformă. | `MatchRoundSimulator` |
| **F** salarii jucători | `WageService.baseWage` (static util) sursă unică pentru generare ȘI negociere → gata cu 8k-vs-1M. Toate site-urile de generare aliniate. | `WageService`, `SquadGenerationService`, `ContractController`, `HumanService`, `EndOfSeasonProcessor`, `AdminController` |

**RĂMAS din backlog-ul de feedback** (neabordat aici):
- **E3** — `*OutcomeIT` (`LeagueOutcomeIT`/`CupOutcomeIT`/`LoC`/`StarsCup`) rulează engine-ul **scalar** prin `TournamentEngine.playLeague`, NU producția two-axis → rapoartele de outcome nu validează jocul real. De migrat pe two-axis dacă vrei să judeci „câștigă favoritul / AI folosește tactici bune".
- **C** — statistici per sezon ȘI per competiție (acum amestecate); golgheteri pe toate competițiile.
- **Frontend (repo separat)**: auto-refresh home, dedup vizual Competitions (poate rezolvat de D1 în backend), Animation Preview adus la engine-ul live nou, UI My Manager pentru noile stats (B1/C).
- **Perf**: lock-ul (A) previne cursa, dar tranziția rămâne lentă (N+1/saves în bucle în `EndOfSeasonProcessor` neatins — batching nesigur fără analiză).

---

## 15. Stats per competiție, raport outcome, perf tranziție, best-tactic + frontend (2026-05-31)

Tot pe `master` (comituri „New Match Engine"). `mvn verify` verde (161 unit + 82 IT). Backlog-ul §14 a fost golit.

### Backend
| Stream | Livrat | Fișiere cheie |
|---|---|---|
| **C** stats/competiție | `StatsAggregationService` (team + player breakdown din `Scorer`: per sezon ȘI per competiție, LoC+SC incluse). DTO `CompetitionStatLine`. Endpoint-uri `GET /stats/team/{id}/competitionBreakdown`, `GET /stats/player/{id}/competitionBreakdown` (`{total,byCompetition,byTypeAndSeason}`). Câmp `competitionBreakdown` în snapshot manager. Aditiv. | `StatsAggregationService`, `StatsController`, `ManagerController`, `CompetitionStatLine` |
| **E3** raport outcome | `ChampionshipPredictionFuzzIT` emite `target/championship-prediction-outcome.md` pe engine-ul **real two-axis** (favorit prezis vs poziție finală, hit-rate, titluri). `*OutcomeIT` scalare rămân legacy. | `ChampionshipPredictionFuzzIT` |
| **BE-1** off/def + youth wage | Backfill off/def pentru manageri la 50/50 (în `GameInitializationService.initializeRound`, idempotent, refolosește seeding-ul din `HumanService`) — repară save-uri vechi ȘI jocuri noi (path-ul bulk nu semăna off/def). Wage youth aliniat la `WageService.baseWage`. | `GameInitializationService`, `YouthAcademyService` |
| **BE-2** teste transfer | `TransferStrategyIT` (drift sell-direction + diagnostic no-trade) + `TransferEconomyFuzzIT` (economie campanie). Insight: 0 echipe blocate de gate-uri — „no transfer" = RNG buyer contestat/loan. | `TransferStrategyIT`, `TransferEconomyFuzzIT` |
| **BE-3** mapări tactică | Cele 6 mapări categorice→numerice (mentalitate→bias etc.) externalizate în `MatchEngineConfig.TacticalModel` (default-uri shipped, comportament identic). | `MatchEngineConfig`, `TacticalScoreService` |
| **best-tactic** | `BestTacticService.findBestTactic(teamId)` + `GET /admin/bestTactic/{teamId}` (header `X-Admin-Token`): caută 15 formații × 900 setări pe **valorile curente din DB** (live morale/fitness/rating prin `PlayerValueService`), întoarce recomandare + top-15 + xGD. Read-only. | `BestTacticService`, `AdminController` |
| **livePreview** | `GET /match/animation/livePreview?teamId1=&teamId2=` → `LiveMatchData` real (timeline cu evenimente) pentru Animation Preview. | `MatchController` |
| **PERF** tranziție | (1) Batch N+1 writes (regen/relații/overachievers/standings → `saveAll`; finanțele neatinse). (2) **IDENTITY→SEQUENCE** pe `Scorer`/`PlayerSkills`/`TeamPlayerHistoricalRelation`/`CompetitionTeamInfo` (`allocationSize=1`) → activează insert-batching pe 6+26 iulie. `Human` rămas IDENTITY (rupea un test). `@DirtiesContext` pe `TransferStrategyIT` (flakiness pre-existentă din context partajat). | `EndOfSeasonProcessor`, `NewSeasonSetupProcessor`, `HumanService`, 4× `model/*` |

**Note perf**: cele două date lente sunt 6 iulie (ziua 340 = `SEASON_END` → `EndOfSeasonProcessor`) și 26 iulie (ziua 360 = `SEASON_TRANSITION` → `NewSeasonSetupProcessor`); ambele rulează într-o singură tranzacție gigantică. Cel mai mare cost pe 26 iulie = inițializarea Scorer (un rând/jucător/competiție → zeci de mii), acum batch-uită prin SEQUENCE. `allocationSize=1` (50 dădea coliziuni PK în contextul de test partajat) — se poate urca pentru producție. Save-urile cuplate cu finanțe pe 6 iulie au rămas per-entitate (restructurare riscantă).

### Frontend (repo separat `~/Downloads/footballmanager-frontend-test/`, fără git din directorul curent)
Cablat + reparat (comis de user): My Manager (salariu/earnings + breakdown per competiție), player all-competitions + drill-down per sezon, stats echipă pe pagina de club, Competitions dedup (+ șters componenta duplicat moartă), home auto-refresh (`TeamService.refresh$/teamId$`, întărit cu `filter/distinctUntilChanged`), Animation Preview pe noul engine (playback `timeline[]`), panou admin Best Tactic, league news + board requests pe home, loans history/recall, contract unhappy + promise-playing-time, fix buget CSS `ng build`, fix team-talk (opțiunile reapar / hint), fix Animation Preview (coerciție teamId la număr + empty-state).

### Validări rulate (2026-05-31)
- **`ChampionshipPredictionFuzzIT`** (3 sezoane): favoritul (Shadows) termină **1 și campion 3/3** (mean pos 1.00). ⚠️ **Posibil prea determinist** — `ratioExponent=2.0` face valoarea lotului decisivă fără surprize; n=3 mic. De re-rulat pe 15-20 sezoane + eventual coborât exponentul dacă vrei varianță.
- **Transfer**: strategiile respectă direcția de vânzare; toate pierd −3.6%…−6.4% valoare primul 11 / 2 sezoane (îmbătrânire); 0 echipe blocate de gate-uri.

### RĂMAS (mic)
- **Decizie determinism**: coboară `ratioExponent` + re-validează pe 15-20 sezoane, sau lasă favoritul dominant.
- **Perf polish opțional**: `Human`→SEQUENCE (revertit), `allocationSize` mai mare pt. producție, batch finanțe pe 6 iulie (riscant), tuning 92→97.
- **Backend behavior**: `/loans/recall` întoarce mereu 400 „Cannot recall during season" — de decis dacă permiți recall.
- **FE**: testare live continuă; restul golurilor din audit sunt acoperite.
- **Smoke browser** two-leg interactiv LoC (QF/SF zile separate) — nevalidat.

---

## 16. Metrica de tactică (expected points), unificare engine live, unelte de simulare + restructurare admin (2026-05-31)

Tot pe `master` (comituri „New Match Engine"); ultimul batch (TacticSim) **staged, necomis** (userul comite). `mvn verify` verde (≈156 unit + 85 IT).

### 16.1 Metrica de tactică: expected points vs panel (NU mean xGD vs oglindă)
**Problemă dovedită numeric** (`src/test/.../service/TacticLandscapeProbeTest`): ranking-ul vechi (`expectedGoalDifference` vs o **oglindă** a echipei) colapsează — tempo+passing contribuie **exact 0** la xGD (openness folosește `(risk1+risk2)/2`, se anulează simetric), rămâne doar „control" → top degenerat „Much Higher/Keep Ball/Always", skill irelevant.
**Fix** (`service/TacticalScoreService` + `ManagerTacticService` + `BestTacticService`): ranking nou pe **`panelExpectedPoints`** = puncte așteptate (3·P(win)+P(draw)) prin **grid Poisson exact** (`outcomeProbabilities(xgH,xgA)`), mediat peste un **panel de adversari** (lotul scalat ×0.7/1.0/1.3, knob `TacticalModel.opponentPanel`). Folosit de AI (`chooseTactic`) ȘI de advisor. Acum tempo contează (0→0.0385); DTO îmbogățit cu `expectedPoints`/`winProbability`/`drawProbability`/`lossProbability`. ⚠️ **Limitare rămasă**: optimul analitic e tot un colț (modelul lasă să maximizezi simultan openness ȘI control — fără trade-off intern) — vezi §16.5 „rămas".

### 16.2 Unelte de inspecție tactică (teste + endpoint-uri)
- `BestTacticService.rankAllTactics(teamId)` — lista completă 13.500 (15 formații × 900) sortată analitic; `GET /admin/bestTactic/{teamId}` (admin) întoarce recomandarea + top-15.
- `integration/fuzz/TeamTacticRankingFuzzIT` — scrie `target/all-tactics-{id}.md` (toate analitic, lot bootstrap sezon 1).
- `integration/fuzz/Team442SeasonPointsFuzzIT` — **SIMULEAZĂ** meciurile reale ale echipei (442 fix, 900 setări, N sezoane vs adversarii reali ai ligii) → `target/season-points-442-{id}.md`. **Asta reflectă jocul**, nu formula. Spread real (50→29 pct), top NEdegenerat.
- ⚠️ Analitic (admin) ≠ simulare (real) — de-aia s-a adăugat varianta simulată în endpoint-uri/FE.

### 16.3 Unificarea engine-ului LIVE cu cel instant (Option A — narațiune)
**Problemă**: meciul **live urmărit** (`LiveMatchSession`) genera goluri minut-cu-minut cu mecanici proprii (bonus mentalitate, caps pe șanse) → **scor diferit** de engine-ul instant (`TacticalScoreService.score`) → tacticile defensive ieșeau mai bine live decât prezicea modelul.
**Fix** (`MatchRoundSimulator` + `LiveMatchSimulationService` + `LiveMatchSession`): la creare, scorul e **predeterminat O DATĂ** cu engine-ul instant pe aceleași profile+vectori (seed pe identitatea meciului). Sesiunea live **narează** exact scorul: programează minute de gol disjuncte (home/away), forțează gol la ele (`goalCutoff=1.0`) și **capează la 0** în rest (`pinned && !forced → goalCutoff=0.0`); restul (posesie/șuturi/cartonașe/schimbări/comentariu) rămâne live. → **un singur outcome** fie că urmărești fie că nu. Test: `service/LiveMatchPinnedScorelineTest` (live==instant pe seed). Modul stocastic legacy (`-1,-1`) păstrat pt. `simulateLiveMatch`/animation preview.

### 16.4 Unelte de simulare user-facing + restructurare admin
**Backend** (`service/TacticSimulationService` + `controller/TacticSimController`, **non-admin**, staged):
- `GET /tactics/formations`, `GET /tactics/analytical/{teamId}` (rankAllTactics), `GET /tactics/simulate?teamId=&formation=&seasons=&opponentIds=` (puncte simulate reale), `POST /competition/simulate` `{teamIds,seasons}` (clasament round-robin custom), `GET /competition/leaguesAndTeams` (pickere). Reutilizează logica din `Team442SeasonPointsFuzzIT`. IT: `integration/TacticSimulationServiceIT`.
**Frontend** (repo separat, necomis):
- **Admin** spart în **hub** + pagini dedicate `/admin/scores`, `/admin/offers`, `/admin/players` (best-tactic mutat afară).
- Pagină **`/tactics`** user-facing: dropdown formație + nr. sezoane + picker adversari (default liga ta, adaugi din orice ligă), 2 tab-uri **Analitic** (instant) + **Simulat** (meciuri reale).
- Pagină **`/simulate`**: picker echipe (default liga ta) + sezoane → clasament.

### 16.5 RĂMAS / planificat pentru viitor
- **Determinism** (prioritar, mic): favoritul câștigă 100% (`ChampionshipPredictionFuzzIT`) — dominat de `ratioExponent=2.0`, nu de tactică. Acum că engine-ul e unificat, coboară `ratioExponent` (~1.5) + re-validează pe 15-20 sezoane pentru surprize realiste.
- **Adâncimea modelului tactic** (cauza colțului degenerat): modelul lasă să maximizezi simultan openness ȘI control fără trade-off intern; formațiile cu aceleași sloturi ies identice (4231=4141=451, etc.). Fix real: **poziții/roluri mai fine** (DM/AM/wing-back, benzi verticale) + **proprietăți noi cu contra real** (linie defensivă sus↔vulnerabil la pace, presing↔spațiu în spate, lățime). Abia atunci formațiile + tacticile contează cu adevărat.
- **Perf polish**: `Human`→SEQUENCE (revertit, rupea un test), `allocationSize` mai mare în producție, batch finanțe pe 6 iulie (riscant), tuning 92→97 (`MatchEngineRepStrengthFuzzIT`).
- **Backend behavior**: `/loans/recall` întoarce mereu 400 „Cannot recall during season" — de decis.
- **FE**: testare live a paginilor noi (admin hub, `/tactics`, `/simulate`) + a meciului live unificat; smoke two-leg interactiv LoC nevalidat.
- **Convenție**: userul face commit-urile (handoff-ul + staged-ul rămân de comis de el).
