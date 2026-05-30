# Faza 3 — Handoff pentru următoarea sesiune

Acest document descrie tot ce s-a livrat în sesiunea curentă (Faza 1 → Faza 4 din planul "match engine interactiv" + bug-fixes ulterioare) și ce propun să continuăm.

Backend: `mvn test` → BUILD SUCCESS, 25/25 teste verzi.
Frontend: `tsc --noEmit` → curat.

---

## 1. Ce a fost livrat

### 1.1. Faza 1 — Stamina / fatigue model
**Fișier:** `src/main/java/com/footballmanagergamesimulator/service/LiveMatchSimulationService.java`

- Clasă internă `PlayerMatchState` cu: `playerId, position, name, startFitness, currentStamina (0-100), minutesPlayed, isOnPitch, staminaAttr (1-20), naturalFitness (1-20)`.
- Determinare starting XI: GK cel mai bine cotat + top 10 outfield după rating; restul sunt rezerve.
- Constants: `STAMINA_BASE_COST = 0.5`, `STAMINA_SNAPSHOT_INTERVAL = 5` minute.
- Tick stamina per minut: `cost = baseCost × positionMultiplier × (1.2 - staminaAttr/20)`, partially offset de `(naturalFitness/20) × 0.15`.
  - Position multipliers: GK 0.4, DC/DL/DR 0.75, DM 1.0, MC/ML/MR 1.15, AMC/AML/AMR 1.05, ST 0.85.
- Efecte:
  - `pickWeightedAttacker` ponderează cu `staminaFactor(0.5..1.0)` — striker tocit are 50% chance redusă.
  - `Human.fitness` actualizat post-meci: `fitness -= (startFitness - finalStamina) × 0.7`, clamped la min 20.
- `LiveMatchData.staminaSnapshots: List<StaminaSnapshot>` cu snapshot la fiecare 5 minute (DTO-uri `StaminaSnapshot`, `PlayerStaminaInfo`).

### 1.2. Faza 2 — AI subs reactive
- Enum `SubReason { FATIGUE, OFFENSIVE, DEFENSIVE, MANUAL }`.
- `decideSubReason(min, ownScore, oppScore, states, teamIds)` triggers:
  - min ≥ 75 + pierdem cu ≥2 → OFFENSIVE
  - min ≥ 80 + conducem cu ≥1 → DEFENSIVE
  - oricine pe teren cu stamina < `staminaSubThreshold(min)` → FATIGUE
  - Threshold: 0 (<35min), 60 (35-55), 70 (55-70), 78 (70-80), 85 (80+).
- `performSub(states, squad, reason)` cu strategii diferite per reason:
  - OFFENSIVE → scoate cel mai slab defender, intră cel mai bun atacant pe bench
  - DEFENSIVE → scoate atacantul cel mai tocit, intră cel mai proaspăt fundaș
  - FATIGUE → cel mai tocit non-GK iese, cel mai proaspăt same-position-group intră
- Cap 3 sub-uri/echipă + minim 8 min între sub-uri.
- Commentary specific per reason ("looks spent", "push for it", "shore things up", "Manager's call").

### 1.3. Fullscreen match + post-match Press Conference
**Fișiere:**
- `src/main/java/com/footballmanagergamesimulator/service/PressConferenceService.java`
- `src/main/java/com/footballmanagergamesimulator/service/GameAdvanceService.java`
- Frontend: `app.component.{ts,html,css}`

- Modal live e acum fullscreen (`100vw × 100vh`) cu conținut centrat în coloană max 1100px.
- `generatePostMatchPressConference(teamId, competitionId, matchday, season, teamScore, opponentScore)` — topic `POST_MATCH:<WIN|DRAW|LOSS>|<questions>` cu 3 seturi diferite de întrebări.
- `closeLiveMatch()` chain-uiește direct în modalul PC dacă există `pendingPostMatchPressConferenceId` (sărind match-result modal).
- Întrebarea în PC modal e adaptivă: "How do you respond about the upcoming match?" vs "The media want your reaction to the match. How do you respond?"

### 1.4. Lineup preview redesign (green pitch)
- Restructurare HTML/CSS — un singur teren verde cu ambele echipe pe el (top + bottom), centre circle, careu mare, careu mic, dungi de iarbă.
- Banner cu "STARTING LINE UP" în antet (nu mai overlapping peste jucători ca înainte).
- Bandă cu nume echipă + formația (4-4-2 etc.) per fiecare jumătate.
- `lineupRows()` returnează acum GK → DEF → MID → ATK (în loc de invers), iar away folosește `column-reverse` ca atacanții ambelor echipe să se întâlnească la linia de centru.
- Helper `formationOf(teamId)` + `shirtLabel(p)` (ascunde 0 când shirt number nu e setat).

### 1.5. Faza 3 — Engine tickable + substituții interactive
**Sesiunea 1**: refactor `LiveMatchSimulationService` într-o clasă internă `LiveMatchSession` (~480 linii) cu state per-meci. `simulateLiveMatch(...)` rămâne wrapper backward-compatible care creează sesiune și avansează la totalMinutes.

**Sesiunea 2**: endpoint-uri noi în `MatchController`:
- `GET /match/live/{key}/state` → snapshot complet
- `POST /match/live/{key}/advance?untilMinute=X` → avansează engine-ul, returnează evenimente noi
- `POST /match/live/{key}/substitute` body `{playerOutId, playerInId, atMinute?}` → aplică swap user-driven sau 400 cu mesaj
- `LiveMatchSession.applyUserSub(out, in)` cu validări (subs available, players in correct squad, GK doar cu GK, etc.) + `InvalidSubstitutionException`
- Toate operațiile sub `synchronized` pe sesiune ca două request-uri să nu intre în race.

**Sesiunea 3**: UI pentru substituții manuale:
- Buton "Make Substitution (X/3)" lângă speed controls.
- Modal cu 2 coloane: jucători on-pitch + jucători bench, sortabili cu indicator "recomandat" (matching position group).
- POST `/substitute` cu `atMinute = current playback minute`.
- Insert chronological al sub event-ului în timeline (vs. append la coadă).

**Sesiunea 4**: deferred commit — engine respectă REAL substituțiile:
- `createInteractiveSession(...)` în service (creează sesiune fără advance).
- `simulateRound` (în CompetitionController): pentru manager-ii cu `viewFullMatch=true`, creează sesiune fără advance + sare peste TOT post-match work-ul (scorers, stats, injuries, standings, KO progression, detail record).
- `finalizeInteractiveLiveMatch(key)` în CompetitionController — rulează deferred work cu scorul real din sesiune (după ce engine-ul a tickat la full-time prin polling).
- `POST /match/live/{key}/commit` în MatchController:
  - Apelează `finalizeInteractiveLiveMatch`
  - Rulează `suspensionService.processMatchCards`
  - Generează post-match PC
  - Returnează `{ homeScore, awayScore, postMatchPressConferenceId, liveMatch: snapshot }`
- `GameAdvanceService.processBatchMatches`: detectare interactivă via `findSessionForTeam(...)`. Skip post-match PC pentru interactive (PC se generează la /commit).
- Frontend: `tickInteractive()` pollează `/advance` minut cu minut, `tickPlayback()` rămâne pentru legacy. Pe `state.finished === true` → POST `/commit` automat.

### 1.6. Bug-fixes ulterioare
1. **Score & feed spoiler la goluri** — `displayedHomeScore/AwayScore` + filtru pe `liveVisibleEvents` ascund golul atât pe tabelă cât și în feed cât rulează animația.
2. **`liveMatchFinished` regression** — folosește acum `liveMatchData.finished` (engine state) ca sursă autoritară, nu `liveCurrentIndex`.
3. **Sub button "doar 1 sub apoi blocat"** — la merge-ul răspunsului `/substitute`, FE face spread complet `{...liveMatchData, ...state}` + update `liveCurrentIndex` + reset `liveAdvanceInFlight`. Înainte, doar câteva field-uri se actualizau și liveCurrentIndex rămânea pe entry-ul vechi.
4. **Minute discrepancy** — același fix de mai sus.
5. **Hasn't open user match** — `processBatchMatches` detecta live match prin `allMatchResults`, gol pentru match-uri interactive. Acum folosește `findSessionForTeam(...)` direct pe service. FE-ul a fost și el actualizat: `find()` pe `eventsProcessed` acceptă acum `e.hasLiveMatch` ca semnal (înainte cerea `e.allMatchResults || e.matchResult`).
6. **Lineup preview missing în interactive** — `buildLineupFromMatch` are acum două căi: legacy (din `goalAnimations`) și interactiv (din `homePitch`/`awayPitch` cu kit default).

### 1.7. Commentary polish
- Refactor `pickAttackPlayType(outcome, random)` — decis ÎNAINTE de commentary, propagat și la `buildAttackAnimation`.
- `playTypePrefix(playType, outcome)` → "PENALTY! ", "PENALTY SAVED! ", "PENALTY MISSED! ", "FREE KICK GOAL! ", "FREE KICK SAVED! ", "FREE KICK MISSED! ", sau "" pentru open play.
- `dbEvents` persistă acum și save/miss events cu prefix-ul în details (pentru istorie în `MatchEvent`).

### 1.8. Match Events panel + Squad Fitness tabs
- Panel "Match Events" doar la final de meci (lângă statistici), listează cronologic: goluri, cartonașe, sub-uri.
- Penalty/Free Kick non-goals (saves/misses) apar **doar dacă au avut animație vizuală** (`goalAnimations[event.minute]` există).
- Toggle "Squad Fitness" / "Match Facts" în modal, ambele update live de la fiecare `/advance`.
- Squad Fitness arată acum: position, nume, stamina bar, valoare, minute jucate, indicator ↑ (intrat ca rezervă) sau ↓ (înlocuit).

### 1.9. Red card visual + engine consistency (acest fix)
- La cartonaș roșu, `matchStates.get(fouler.id).isOnPitch = false`. Jucătorul iese din:
  - `filterOnPitch` (nu mai poate fi ales ca atacant/defender)
  - Animațiile de gol/save/miss (nu mai apare pe teren în secvențele 2D)
- Toate cele 4 callsite-uri de `buildAttackAnimation` / `goalAnimationService.generate` din `tickOneMinute` primesc acum squadul filtrat on-pitch (înainte primeau `team1All`/`team2All` integral).
- Beneficiu colateral: sub-urile (manual și AI) sunt și ele reflectate vizual — jucătorul ieșit nu mai apare în animații.

---

## 2. Limitări cunoscute

1. **Red card → balans engine**: jucătorul cu cartonaș roșu iese din action picking, dar atac/possession rate per echipă (`team1AttackChance`, `team1PossChance`) **nu se ajustează**. O echipă cu 10 oameni va ataca cu aceeași frecvență ca una cu 11 — irealist. Tuning de făcut.
2. **Atribut `pace` neutilizat în engine** — vezi `PROGRESS_AND_ROADMAP.md` item 14a. User-noted, în `MEMORY.md`.
3. **Shirt numbers = 0** la majoritatea jucătorilor — `Human.shirtNumber` nu e populat la generarea echipelor. Helper-ul `shirtLabel(p)` ascunde "0" dar ar fi nice să avem numere reale.
4. **Knockout extra-time în /commit** folosește `new Random()` în loc de cel din sesiune — diferență minoră de determinism.
5. **`suspensionService.processMatchCards` în /commit** — presupun că e idempotent (rulează și în non-interactive flow). Dacă nu, re-procesarea ar dubla suspensiile. Nu am verificat explicit.
6. **`LiveMatchSession.tickOneMinute` ~250 linii** — duplica logical conținutul vechiului for-loop. Refactor de spargere în sub-metode (handleAttack, handleFoul, handleSubs, handleStamina) ar fi binevenit.
7. **`CompetitionController.java`** acum > 8000 linii (după adăugarea `finalizeInteractiveLiveMatch`). Item #29 din roadmap rămâne valabil — `processMatchHumanTeam` / `simulateRound` de spart pe metode mai mici.
8. **No tests pentru flow-ul interactive** — `LiveMatchSession.applyUserSub`, `finalizeInteractiveLiveMatch`, `/commit` endpoint, polling FE — toate netestate unit. Ar fi util cel puțin `@SpringBootTest` pe `MatchController` pentru cele 3 endpoint-uri noi.

---

## 3. Fișiere atinse în această sesiune

### Backend
- `src/main/java/com/footballmanagergamesimulator/service/LiveMatchSimulationService.java` — refactor masiv, inner class `LiveMatchSession`, stamina model, AI subs, interactive session, helpers (`pickAttackPlayType`, `playTypePrefix`, `filterOnPitch`, `findSessionForTeam`).
- `src/main/java/com/footballmanagergamesimulator/controller/MatchController.java` — endpoint-uri `/state`, `/advance`, `/substitute`, `/commit`.
- `src/main/java/com/footballmanagergamesimulator/controller/CompetitionController.java` — `simulateRound` modificat pentru interactive mode, `finalizeInteractiveLiveMatch` method nou.
- `src/main/java/com/footballmanagergamesimulator/service/GameAdvanceService.java` — `processBatchMatches` detectează interactive via session, defer PC la /commit.
- `src/main/java/com/footballmanagergamesimulator/service/PressConferenceService.java` — `generatePostMatchPressConference`.
- `src/main/java/com/footballmanagergamesimulator/frontend/LiveMatchData.java` — câmpuri noi pentru interactive state (currentMinute, finished, subsRemaining, pitch/bench lists, staminaSnapshots).

### Frontend (`/Users/ciprian.amza/Downloads/footballmanager-frontend-test/src/app/`)
- `app.component.ts` — polling `tickInteractive`, applySubstitution, commit flow, lineup builder cu fallback, displayed score/feed cu anti-spoiler, tab state, key events filter.
- `app.component.html` — fullscreen modal, sub button + modal, tabs, Match Events panel, lineup preview restructurat.
- `app.component.css` — fullscreen modal, sub modal, tabs, stamina panel cu minute/sub indicators, Match Events styles, lineup pitch (gradient verde + linii + careu).

### Docs / config
- `PROGRESS_AND_ROADMAP.md` — item 14a (pace neutilizat).
- `~/.claude/projects/.../MEMORY.md` — TODO match engine + limitări Faza 3 v1.

---

## 4. Ce propun pentru următoarea sesiune

Listă prioritizată (de la livrabil mic la refactor mai mare):

### Tier A — quick wins (1-2 ore fiecare)
1. **Balance 10-man team după red card**: în `tickOneMinute`, dacă o echipă are < 11 jucători on-pitch, scade `team1AttackChance` / `team2AttackChance` proporțional (ex. × 0.7 la 10 jucători, × 0.5 la 9). Crește `team1PossChance` pentru oponent.
2. **Implementare pace în engine** — vezi `PROGRESS_AND_ROADMAP.md` 14a:
   - În `applyStaminaTick`, când tempo > nominal (citit din `PersonalizedTactic.tempo`), modulează `cost` cu `(1.2 - pace/20)`. Pace bun + tempo înalt = drain redus.
   - În `pickWeightedAttacker` pentru shooter, în `filterOnPitch` defenders pentru fouls — bonus pentru pace.
3. **Shirt numbers** — populate-le la generarea jucătorilor în `CompetitionService` / `YouthAcademyService`. Asignează 1-25 ordonat după rating + poziție, GK = 1.
4. **Stop polling-ul pe weekend/idle** — actualmente FE pollează la 600ms × 90 = 90 cereri/meci. Acceptabil dar overhead. Buton "pause" în modal ar fi util.

### Tier B — feature work (2-4 ore)
5. **Audio cues** — vezi roadmap item 5: WebAudio API + sample-uri scurte pentru gol/half-time/full-time/yellow/red. Toggle on/off în Staff page.
6. **Mini-pitch live overlay** în loc de doar stamina bars — vezi un pitch cu 22 cerculețe care se mișcă subtle, cu cel ce are mingea evidențiat. Cărămizi peste ce avem deja.
7. **Pre-game "instruction" panel** — în loc să faci sub doar reactiv, user-ul setează intenții pre-meci ("când scor < 0 după min 60, sub Forward"). Engine respect.
8. **Pre-match PC integration** cu engine state — actualmente PC pre-meci nu influențează nimic în engine. Răspunsul "aggressive" ar putea da +5% atac în prima repriză, etc.

### Tier C — refactor / cleanup
9. **Spargerea `LiveMatchSession.tickOneMinute`** în sub-metode:
   - `tickAttackBranch(min, ...)` — corp goal/save/miss/block/corner
   - `tickPossession(min, ...)`, `tickFoul(min, ...)`, `tickOffside(min, ...)`
   - `tickSubs(min, ...)` — AI subs
   - Beneficiu: testabilitate + lizibilitate.
10. **Extract `LiveMatchSession` într-un fișier propriu** — `LiveMatchSession.java` în service package. Helper-ele din outer service devin pachet-private și sunt apelate direct.
11. **`processMatchHumanTeam`** rămâne 200+ linii — vezi item #29 din roadmap.
12. **Test suite pentru flow interactive**:
    - Unit: `applyUserSub` cu input invalid (GK swap, expired session, etc.).
    - Integration: `@SpringBootTest` cu `MockMvc` pentru `/state`, `/advance`, `/substitute`, `/commit`.
    - E2E: testează că un user manual sub schimbă efectiv compoziția echipei în următorul tick.

### Tier D — open questions de discutat cu user
13. **Save vs LoadGame compatibility**: sesiunile live trăiesc DOAR în memorie (`liveMatchSessions` map). Dacă user-ul închide app-ul în mijlocul unui meci interactiv, sesiunea se pierde. Restart găsește `liveMatchCache` populat (din snapshot la creare), dar fără session interactivă FE-ul nu va putea pollu /advance. Necesită fie persistare sesiune în DB, fie blocare close-app în timpul live match.
14. **Multi-team manager (multiple human teams)**: dacă user-ul are mai mult de o echipă umană și ambele au meciuri în aceeași zi, doar primul găsit deschide modal-ul. Celălalt rămâne neviewat. Trebuie planificat — modal-uri secvențiale? Doar primul are voie?
15. **Determinism**: `LiveMatchSession.random = new Random()` (fără seed). Două apeluri /advance succesive sunt deterministice (același state, același Random), dar dacă re-creezi sesiunea, RNG-ul e altul. Pentru replay sau debugging, ar fi util un seed configurabil.

---

## 5. Cum testezi rapid în următoarea sesiune

```bash
# Backend
cd /Users/ciprian.amza/IdeaProjects/footballmanager-backend-
mvn spring-boot:run

# Frontend
cd /Users/ciprian.amza/Downloads/footballmanager-frontend-test
npm start

# Test scenariu:
# 1. Login, alege o echipă.
# 2. Staff → Responsibilities → "View Full Match" ON + "Match Highlights" = Key Moments.
# 3. Simulează până la prima zi de meci.
# 4. Vezi:
#    - Lineup preview la kickoff (cu ambele echipe pe teren verde).
#    - Modal fullscreen, polling minut-cu-minut.
#    - Stamina scade, AI face subs reactive (cu commentary "looks spent").
#    - Apasă "Make Substitution" → modal cu pitch + bench → confirmă → engine continuă cu noua componență.
#    - Animații cu prefix "PENALTY!" / "FREE KICK!".
#    - Scorul rămâne pre-gol pe tabelă cât rulează animația.
#    - La final: Match Events panel + statistici complete.
#    - Post-match Press Conference se deschide automat.
#    - Pe avansare zi, standings reflectă scorul real din sesiunea ta.
```

Logs utile pentru debug:
- Backend stdout: `=== simulateMatchday: comp=X ===` și `<<< simulateMatchday comp=X DONE` arată timing-ul.
- Network tab: `/match/live/{key}/advance` la fiecare 600ms.

---

**Pentru următorul agent Claude**: pleci dintr-un cod verde. Dacă vrei să continui pe Tier A item 1 (red card balance) sau item 2 (pace), sunt cele mai naturale next steps care construiesc pe ce e deja livrat. Pentru lucruri mai mari (Tier C), începe cu split-ul `tickOneMinute` — el e bottleneck-ul pentru orice feature work viitor pe engine.
