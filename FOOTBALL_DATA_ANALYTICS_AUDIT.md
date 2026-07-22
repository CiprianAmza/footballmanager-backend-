# Audit complet al datelor și statisticilor simulatorului de fotbal

**Data auditului:** 22 iulie 2026  
**Rol:** Senior Football Data Analyst / auditor de modele de simulare  
**Backend auditat:** `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-`  
**Frontend cerut:** `/Users/ciprian.amza/IdeaProjects/football-manager-frontend-test`  
**Stare:** analiză și recomandări; niciun production code, proces sau conținut al bazei de date nu a fost modificat.

## 0. Rezumat executiv

### Verdict scurt

Motorul actual poate produce rezultate și ecrane statistice plauzibile la prima vedere, dar **nu este încă un motor de date fotbalistice cauzal și coerent**. Modelul tactic cu două axe este o bază pre-meci bună, însă fluxul dominant rămâne:

```text
lot + fitness + tactică
    -> scor Poisson decis
    -> marcatori, șuturi, xG, pase, dueluri și rating fabricate/condiționate după scor
```

Nu fluxul obligatoriu:

```text
lot + tactică + context
    -> posesii/teritoriu
    -> acțiuni/dueluri/progresie
    -> șuturi și xG
    -> execuție + portar
    -> goluri și scor
    -> agregări
```

Consecința este că statisticile curente descriu o narațiune compatibilă superficial cu scorul, dar nu îl explică. Pentru un sezon complet, clasamentele și golurile sunt utilizabile ca gameplay; xG, analizele individuale, premiile bazate pe rating și majoritatea statisticilor avansate nu sunt încă suficient de credibile pentru analiză sportivă.

### Cele mai importante constatări

1. **Scorul precedă șuturile și xG-ul.** `MatchSimulationService.calculateScores(...)` sau `TacticalScoreService.score(...)` eșantionează golurile, apoi `MatchStatsService.generateMatchStats(...)` construiește șuturile și eșantionează calitatea lor condiționat de golurile deja cunoscute. Este statistic o distribuție posterioară simplificată, nu o simulare cauzală a meciului.
2. **Există mai multe adevăruri în funcție de mod.** Meciul uman instant, AI batch și live nu folosesc aceeași cale pentru evenimente, statisticile echipei, marcatori, minute și fitness. Fast-forward reutilizează orchestrarea batch, dar nu elimină diferența human-vs-AI din `MatchRoundSimulator`.
3. **AI-vs-AI nu produce un event ledger complet.** Calea optimizată distribuie marcatori în `Scorer`, generează `MatchStats` cu tacticile transmise `null`, dar nu produce aceleași `MatchEvent` de gol/cartonaș/schimbare și nu folosește `MatchPlan`.
4. **`MatchPlan` nu este activ implicit.** `MatchEngineConfig.MatchPlan.enabled=false`, fără override în configurația inspectată. Codul canonic și testele există, dar nu descriu runtime-ul implicit.
5. **Bug critic la live + schimbare manuală + plan canonic.** Orice schimbare manuală provoacă la commit o reeșantionare a întregului scor din XI-ul de la final; golurile canonice deja văzute/persistate nu sunt reconstruite pentru un meci normal. Pot rezulta scor, evenimente și marcatori incompatibili.
6. **Minutele și statisticile individuale sunt sintetice.** `PlayerMatchStatService` acordă fiecărui titular 90 de minute și generează acțiunile din atribute+tactică, independent de evenimentele echipei. Schimbările, eliminările și minutele live nu se reconciliază cu `PlayerSeasonStat`.
7. **Eroare semantică importantă:** coloana `PlayerSeasonStat.shots` acumulează valoarea formulei denumite `Expected Goals`; apoi este afișată atât ca xG, cât și în leaderboard-ul „Most shots”.
8. **Duelurile nu sunt întotdeauna reconciliabile.** În generatorul instant se eșantionează volume separate pentru cele două echipe, iar duelurile aeriene sunt doar „won”, fără attempts/lost și fără perechea adversă obligatorie.
9. **Fitness-ul diferă între moduri.** Instant/batch scade fix `8` puncte titularilor; live folosește un model per minut. Distanța, high-intensity distance și sprinturile nu există. În plus, `gameplay.player-availability-disabled: true` dezactivează în configurația curentă accidentările și disponibilitatea.
10. **Frontend-ul cerut nu există.** Calea exactă indicată nu este prezentă. Singurul proiect apropiat, `/Users/ciprian.amza/IdeaProjects/footballmanager-frontend`, este un schelet Angular: componentă root de 10 linii și template-ul implicit Angular, fără ecrane sau integrare de statistici. Auditul frontend nu poate valida ce vede utilizatorul într-o aplicație reală.

### Decizie de produs recomandată

Prima versiune credibilă nu trebuie să încerce tracking premium. Trebuie să livreze întâi un **ledger canonic unic de meci**, un flux cauzal posesie → acțiune → șut → gol, minute reale, agregări reconciliate și aceleași rezultate în live/batch/fast-forward. Distanța poate fi estimată realist fără GPS ca stare latentă pe intervale. Average position, compactness și heatmap spațial „adevărat” trebuie marcate ca estimări până când există un motor spațial.

---

## 1. Domeniu, metodă și limite

### 1.1 Ce a fost inspectat

- modelele JPA, repository-urile și configurația motorului;
- traseele de simulare human instant, AI batch, live, knockout și fast-forward;
- calculul scorului, statisticilor de echipă, statisticilor individuale, ratingurilor, premiilor, fitness-ului și agregărilor;
- endpoint-urile `MatchController` și `StatsController`;
- testele și harness-urile de invariants/calibrare existente;
- proiectul frontend disponibil și absența căii cerute;
- surse publice oficiale/profesionale și lucrări științifice deschise.

Auditul reflectă **working tree-ul existent la data analizei**, inclusiv modificări locale necomise din zona MatchPlan/live. Aceste modificări au fost tratate drept starea curentă de lucru și nu au fost atinse.

### 1.2 Ce nu trebuie confundat cu adevăr public

Opta și StatsBomb publică definiții, exemple de câmpuri și caracteristici generale ale produselor, dar nu coeficienții și întregul pipeline al modelelor comerciale xG/post-shot xG/OBV. În acest raport:

- definițiile de eveniment sunt comparate cu documentația publică;
- formulele recomandate sunt **modele proprii, calibrabile**, nu reproduceri ale formulelor proprietare;
- intervalele fizice provin din studii publice, dar pragurile de viteză diferă între furnizori; schema trebuie să persiste versiunea pragurilor;
- „distribuțiile-țintă” din §9 sunt benzi inițiale de acceptare pentru engineering, nu adevăruri universale. Ele trebuie înghețate per competiție după alegerea unui dataset de referință licențiat sau open-data.

### 1.3 Repere externe folosite

- [Definițiile oficiale Opta](https://theanalyst.com/articles/opta-football-stats-definitions) separă explicit shot on target, off target, blocked shot, big chance, assist, key pass, sequence, possession, duel și PPDA. Un duel câștigat are corespondent pierdut; pass completion este successful/attempted; xG este probabilitatea 0–1 a unui șut.
- [StatsBomb Open Data](https://github.com/hudl/open-data) oferă JSON de meciuri, lineups, events și, pentru unele partide, 360; este o referință bună pentru schema de evenimente și un corpus reproductibil de calibrare.
- [Glosarul StatsBomb](https://stats-portal.statsbomb.com/glossary) arată concret că shots, passes, tackles, saves și cards se obțin prin filtrarea evenimentelor și outcome-urilor, nu prin numere independente inventate după meci.
- [Hudl StatsBomb](https://www.hudl.com/en_gb/products/statsbomb) declară public utilizarea pozițiilor portarului și fundașilor din freeze-frame în xG și colectarea pressure la nivel de eveniment. Nu publică însă formula comercială.
- [UEFA Technical Reports](https://www.uefa.com/development/performance-analysis/technical-reports/) combină analiza tactică/video cu passes, shots, crosses, line-breaking passes, pressing și date fizice; raportul public [Physical demands of EURO 2024](https://www.uefa.com/news-media/news/0297-1d4e35e052fe-b2bcfac5c0bb-1000--physical-demands-of-euro-2024-performance-trends-and-take/) confirmă folosirea distance covered și sprint speed în context tactic. Sunt repere de structură și prezentare, nu surse pentru coeficienți proprietari.
- [IFAB Law 10](https://www.theifab.com/laws/latest/determining-the-outcome-of-a-match/) confirmă separarea golurilor din meci de penalty shoot-out și eligibilitatea jucătorilor rămași pe teren; [Law 14](https://www.theifab.com/laws/latest/the-penalty-kick/) definește penalty-ul din timpul meciului.
- Studiul open-access [Match-related physical performance in professional soccer](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0256695) raportează, pe 1.964 observații, total distance, high-intensity distance, sprint distance și accelerări pe post. Arată și că postul explică doar 44–58% din variația schimbării, deci valorile individuale nu trebuie fixate rigid pe post.
- Studiul open-access [Effective playing time affects physical match performance in soccer](https://pmc.ncbi.nlm.nih.gov/articles/PMC10588592/) oferă un al doilea reper pe 3.731 observații pentru distance, high-intensity/sprint, maximum velocity și accelerări pe post; este folosit numai ca verificare de ordin de mărime, deoarece populația și definițiile diferă.
- [FIFA Training Centre, Qatar 2022](https://www.fifatrainingcentre.com/en/fwc2022/physical-analysis/how-do-various-positions-cover-distances.php) arată dependența alergării intense de rol, posesie și stil: CB/DM au aproximativ 70–85% din high-intensity/sprint fără minge, iar atacanții centrali/laterali aproximativ 60–70% cu mingea.
- Studiul [goalkeeperilor de la EURO 2016](https://pmc.ncbi.nlm.nih.gov/articles/PMC9924535/) raportează 4.819 m medie, interval 4.036–6.640 m și doar 0,8% din timp la intensitate mare.
- [xT în socceraction](https://socceraction.readthedocs.io/en/stable/documentation/valuing_actions/xT.html) definește xT ca model de posesie pe zone; [VAEP](https://www.ijcai.org/proceedings/2020/0648.pdf) evaluează acțiunile prin schimbarea probabilității de a marca și de a primi gol.
- [Dixon–Coles](https://rss.onlinelibrary.wiley.com/doi/10.1111/1467-9876.00065) este reperul clasic pentru scor Poisson și corecția dependențelor scorurilor mici; util ca benchmark macro, nu ca event engine.
- Lucrarea open-access [Predicting goal probabilities with improved xG models using event sequences](https://journals.plos.org/plosone/article?id=10.1371/journal.pone.0312278) găsește valoare predictivă în evenimentele precedente șutului, susținând includerea tipului de assist și a secvenței.

---

## 2. Cum funcționează sistemul actual

### 2.1 Fluxurile reale

| Etapă | Human instant | AI batch | Live urmărit | Fast-forward |
|---|---|---|---|---|
| XI/putere | `MatchRoundSimulator`, lineup și `PlayerValueService` | cache best XI + `getSimpleTeamRating`/profil tactic | XI ales de `LiveMatchSimulationService.initializeMatchStates` | același `GameAdvanceService` → `MatchdayBatchProcessor` → `MatchRoundSimulator` |
| Scor | admin override sau `TacticalScoreService.score`/`MatchSimulationService.calculateScores` | la fel, pe calea optimizată | scorul este stabilit înainte de kickoff; la schimbare manuală se poate reeșantiona integral la commit | pentru manager cu `alwaysContinue`, meciul său nu se oprește în UI; restul rămân AI batch |
| Goal plan | `MatchPlanService.buildAndPersist` doar dacă flag-ul este activ | nu este folosit | goal slots canonice dacă flag-ul este activ; altfel minute forțate legacy | depinde de ramura human/AI, deci nu este uniform |
| Goal events | canonice sau `generateMatchEvents` legacy | lipsesc pe calea optimizată | live; canonice persistate slot-cu-slot sau evenimente legacy la commit | aceleași diferențe ca batch |
| Scorer/rating | `LineupRatingService` după scor | `getScorersForTeamSimplified` după scor | proiectat din evenimente canonice sau generat legacy | neuniform |
| Team stats | `generateMatchStats`, după scor | `generateMatchStats` după scor, tacticile sunt `null` | o parte live, restul sintetizat de `persistLiveMatchStats`; dacă scorul e ajustat, regenerare completă | neuniform |
| Player stats | titularii primesc 90 min + formule sintetice | la fel, batch | tot prin lineup/scorer hook, nu din minutele și evenimentele live | neuniform |
| Fitness | scădere fixă pentru XI în update-ul post-meci | scădere fixă 8 | stamina per minut, apoi dampening la fitness | neuniform |

Referințe principale în cod:

- `MatchRoundSimulator.simulateRound(...)` — `src/main/java/com/footballmanagergamesimulator/service/MatchRoundSimulator.java:203`;
- ramura AI optimizată și stats cu tactici `null` — același fișier, aproximativ liniile 590–680;
- `MatchSimulationService.calculateScores(...)` — `src/main/java/com/footballmanagergamesimulator/service/MatchSimulationService.java:90` și `:144`;
- `TacticalScoreService.score(...)` — `src/main/java/com/footballmanagergamesimulator/service/TacticalScoreService.java`;
- `MatchStatsService.generateMatchStats(...)` — `src/main/java/com/footballmanagergamesimulator/service/MatchStatsService.java:57`;
- `MatchStatsService.persistLiveMatchStats(...)` — același fișier `:256`;
- `LiveMatchSession.tickOneMinute(...)` — `src/main/java/com/footballmanagergamesimulator/service/LiveMatchSession.java`, începând în jurul liniei 690;
- commit live — `MatchdayCoordinator.finalizeInteractiveLiveMatch(...)` la `src/main/java/com/footballmanagergamesimulator/service/MatchdayCoordinator.java:396`;
- reeșantionarea la schimbare manuală — `LiveMatchSimulationService.resolveCommitOutcome(...)` la `:896`.

### 2.2 Scorul pre-meci

#### Fallback scalar

`MatchSimulationService.calculateScores`:

```text
share_home = power_home^ratioExponent /
             (power_home^ratioExponent + power_away^ratioExponent)
lambda_home = expectedGoalsTotal * share_home
lambda_away = expectedGoalsTotal * (1 - share_home)
goals_home ~ Poisson(lambda_home), capped
goals_away ~ Poisson(lambda_away), capped
```

Configurația are `expected-goals-total=3.0`, exponent `2.0`, home advantage `1.08` și cap de 7 goluri/echipă. Puterea jucătorului include poziție, familiaritate, moral și fitness prin `PlayerValueService`.

#### Modelul tactic cu două axe

`TacticalScoreService` este partea cea mai solidă a motorului curent:

- construiește attack/defense din valorile poziționale ale titularilor;
- calculează aptitudini de pressing, discipline și stamina din grupuri de atribute;
- reduce tactica la attack bias, risk, control, directness, line, press și width;
- modelează trade-off-uri: press vs directness, linie înaltă vs spațiu, width matchup, control vs atac;
- transformă matchup-ul în două valori de expected goals și eșantionează Poisson.

Totuși, această valoare „xG” este doar intensitatea Poisson a scorului, nu suma unor șuturi persistate. Mai există două pierderi de informație:

- `MatchRoundSimulator.scaleProfile(...)` (`:1294`) reconstruiește `new TeamProfile(attack*k, defense*k)` și pierde multiplicatorii pressing/discipline/stamina;
- `LiveMatchSimulationService.currentOnPitchProfile(...)` (`:935`) folosește constructorul `StarterValue(position,value)` fără aptitudinile reale, neutralizând aceleași gates la reeșantionarea de commit.

### 2.3 MatchPlan: intenție bună, activare și acoperire incomplete

`MatchPlanningService.plan(...)` primește un scor deja decis și creează câte un `GoalSlot` pentru fiecare gol, cu minut, fază și tip. `ContributionResolver.resolve(...)` alege scorerul dintre jucătorii de pe teren. `MatchPlan`, `GoalSlot`, participanții, schimbările și aparițiile au chei și constrângeri mai bune decât modelele legacy.

Puncte pozitive:

- fixture key unic;
- seed și algorithm version;
- shootout separat de golurile meciului;
- sloturi idempotente și goal/assist cu provenance;
- același resolver poate servi instant și live;
- scorerul este ales dintre jucătorii prezenți pe teren la minutul slotului.

Limitări curente:

- `MatchEngineConfig.MatchPlan.enabled` este implicit `false`;
- AI batch nu intră în această cale;
- planul conține doar goluri/assisturi, nu întregul lanț cauzal al șutului;
- tipul golului este ales independent de foul/corner/sequence;
- nu există autogol;
- penalty-urile ratate nu pot exista, deoarece se planifică doar golurile;
- comentariul resolverului spune rating × fitness, dar `pickScorer` folosește poziție × finishing × rating², nu fitness;
- o schimbare manuală poate schimba scorul complet la commit după ce goal slots au fost deja afișate/persistate.

### 2.4 Inconsistența critică live la commit

Secvența actuală, când planul canonic este activ și utilizatorul face cel puțin o schimbare:

1. live rulează până la final și persistă goal slots canonice la minutele planificate;
2. `resolveCommitOutcome` vede `hasManualSubstitutions()` și reeșantionează întregul 0–90 din profilul XI-ului aflat la final pe teren;
3. `session.applyCommitScore(...)` suprascrie scorul;
4. pentru un meci normal, `MatchdayCoordinator` nu reconstruiește planul canonic;
5. scorerii sunt proiectați din vechile evenimente canonice, iar `MatchStats` este regenerat pentru noul scor.

Exemplu posibil:

```text
Utilizatorul vede: A 2-1 B, goluri P1, P2, Q1
Commit după o schimbare: scor reeșantionat A 0-0 B
DB: result/detail 0-0; MatchStats 0-0; MatchEvent încă 3 goluri;
    Scorer poate păstra contribuțiile celor 3 evenimente.
```

Acesta este P0: încalcă invarianta scor = suma goal events și promisiunea live=batch. O schimbare trebuie să afecteze doar hazardurile **după minutul schimbării**, nu să rescrie retrospectiv minutele 1–90.

---

## 3. Inventarul statisticilor actuale

### 3.1 `MatchStats`: statistici de echipă per meci

Persistență: modelul `MatchStats`, tabela `match_stats`, o linie unică pe `(competitionId, seasonNumber, roundNumber, team1Id, team2Id)`.

| Statistică | Calcul actual | Când | Adevăr/limitare |
|---|---|---|---|
| goluri | copiate din scorul deja decis | post-scor | canonic doar dacă toate celelalte modele se reconciliază |
| posesie | power ratio + home boost + tactic bonuses + Gaussian, clamp 25–75; live: minute cu Bernoulli | post-scor / live | parțial; live numără „minute câștigate”, nu durate de posesie |
| șuturi | Poisson din power/control/tactic, apoi `max(goals, shots)` | post-scor | parțial și condiționat de rezultat |
| șuturi pe poartă | fiecare gol este SOT; ratările sunt eșantionate după chance quality | post-scor | coerent structural pentru goluri normale, fără autogol |
| șuturi blocate | fracție din `shots-SOT` | post-scor | cosmetic; nu are blocker/event |
| xG | suma chance qualities eșantionate condiționat de goal/miss | post-scor | nu este predictor independent; nu are shot rows sau locații |
| big chances / missed | chance quality peste ramura „big”; missed = big − big goals | post-scor | incompatibil cu definiția Opta unde o big chance missed poate exista fără șut |
| cornere | bază + șuturi × 0,3 + zgomot | post-scor | cosmetic, nu derivat din deflection/out event |
| lovituri libere | egal cu faulturile adversarului | post-scor | aproximare; ignoră avantaj, offside și penalty |
| faulturi | power edge + mentalitate + zgomot | post-scor / live | live are evenimente; instant nu le leagă de jucători în AI |
| galbene/roșii | proporție/chance din faulturi | post-scor / live | valorile `MatchStats` pot diferi de `MatchEvent` și suspensions |
| offside | power ratio + zgomot; live event branch | post-scor / live | nu depinde de linie, run și pass în instant |
| pase | aproximativ 450 × possession ratio + zgomot, clamp 200–750 | post-scor | nu sunt suma paselor jucătorilor |
| pass accuracy | power edge + tactic + zgomot | post-scor | procent întreg; media sezonului este neponderată |
| tackles | bază + lipsa posesiei | post-scor | attempts/won nu sunt separate; nu există tackler |
| interceptions | bază + lipsa posesiei | post-scor | cosmetic |
| clearances | lipsa posesiei + șuturi adverse | post-scor | cosmetic |
| saves | opponent SOT − opponent goals | post-scor | nu există shot/keeper matchup; autogolul ar rupe formula |
| crosses/completed | bază + corners; accuracy din edge+noise | post-scor | fără origin/destination/recipient |
| ground/all duels won | volume și win% sintetice | post-scor | instant folosește volume separate; nu garantează pairing |
| aerial duels won | două numere independente | post-scor | nu există attempts/lost/pereche |
| MOTM home/away | câmpuri în model | niciun writer găsit în generator | practic nepopulate; endpoint-ul summary recalculează din `Scorer.rating` |

Metode: `MatchStatsService.generateMatchStats(...)`, `generateChanceLine(...)` (`:487`), `persistLiveMatchStats(...)` și `getTeamSeasonStats(...)` (`:379`). În live, doar score, possession, shots/SOT, corners, fouls și cards vin din sesiune; passes, xG, defensive stats, crosses și o parte din duels sunt inventate după fluier cu alte constante decât calea instant.

### 3.2 `MatchEvent`: evenimente

Persistență: tabela `match_event`, cu fixture provenance doar pentru evenimentele MatchPlan. Câmpuri: competition/season/round, team pair, minute, event type, player, team, details; constrângere unică `(fixture_key, slot_index, event_type)`.

Evenimente observate în cod:

- `goal`, `assist`;
- `yellow_card`, `red_card`;
- `substitution`;
- în live pot apărea și evenimente narative precum saved/wide/blocked în timeline, dar schema DB și agregatorii nu tratează uniform toate tipurile.

Probleme:

- evenimentele legacy au `fixtureKey=null`, deci unicitatea nu protejează retry-ul;
- modelul nu are secunde/period/stoppage-normalized timestamp, coordonate, start/end location, outcome standardizat, possession/sequence id, related event id, body part sau snapshot de adversari;
- AI batch poate avea `MatchStats.yellowCards>0` fără niciun `MatchEvent` de card; clasamentele disciplinare construite din events omit acele cartonașe;
- `MatchSimulationService.buildGoalAndAssistEvents(...)` legacy poate alege din lot, nu neapărat din XI-ul aflat pe teren;
- set-piece goal type nu este rezultatul unui foul/corner;
- lipsesc own goal, penalty attempt/miss/save, VAR, injury și keeper actions standardizate.

### 3.3 Goluri, assisturi și ratinguri individuale

#### `Scorer`

Tabela `scorer` persistă câte un snapshot de apariție: player/team/opponent, competition/season/round, score, goals, assists, rating, position, substitute.

Limitări:

- nu are `fixtureId`; aceeași pereche poate juca de mai multe ori în aceeași rundă/competiție, iar reconstrucțiile folosesc chei fragile;
- calea AI `getScorersForTeamSimplified(...)` distribuie golurile după scor și adaugă random „hot player”; nu are minute sau event provenance;
- calea legacy poate alege scorer/assist după rezultat, separat de MatchStats;
- `rating` este ratingul de performanță 1–10, dar există un al doilea câmp numit `rating` în `MatchPlayerRating` cu semnificație diferită.

#### `MatchPlayerRating`

Tabela `matchPlayerRating` păstrează lineup snapshot, position/index/formation/role/duty/substitute, date vizuale și:

- `rating`: valoarea intrinsecă 1–300 folosită la team strength;
- `performanceRating`: nota post-meci 1–10;
- goals și assists.

`MatchSimulationService.computeMatchRating(...)` pornește de la 6 și adaugă goluri, assisturi, clean-sheet pentru apărători/GK, rezultat, penalizare de rezervă și Gaussian. Nu folosește acțiuni reușite/eșuate, errors, shot quality, saves sau minute reale. Nota este o metrică de prezentare derivată din rezultat și contribuții, nu o evaluare profesională a prestației.

#### `PlayerSeasonStat`

Tabela `player_season_stat` conține appearances, minutes și acumulatoare sintetice:

- defensiveActions;
- pressures, counterpressures;
- tackles;
- `shots`;
- passesAttempted/completed;
- chancesCreated;
- dribblesCompleted.

`PlayerMatchStatService.recordRealMatchesForTeams(...)` acordă fiecărui jucător din `startingXI` exact 90 de minute și generează totul din `AnalyticsFormula`, skills și tactică. Nu există unique constraint pe `(playerId, competitionId, seasonNumber)`, deși comentariul clasei promite o linie unică.

Defecte concrete:

- `shots = synthesize("Expected Goals")`; numele coloanei și leaderboard-ul „Most shots” sunt false semantic;
- attempts la pase sunt `70 × passPct/100`, apoi completed aplică din nou passPct; jucătorii mai preciși primesc automat și mai multe attempts;
- suma player passes nu coincide cu `MatchStats.homePasses`;
- toate starturile sunt de 90 min, indiferent de schimbare, roșu sau prelungiri;
- `PlayerAnalyticsService.perNinety(...)` interpretează `shots` drept „Expected Goals”;
- percentilele pot compara valoarea acumulată a jucătorului cu valori **sintetizate din atributele curente ale peerilor**, nu neapărat cu datele acumulate ale peerilor;
- `Def Action Regains` și `Counterpressure Regains` sunt raporturi fixe din părinți, nu evenimente observate;
- chancesCreated și dribbles sunt folosite la premiu/leaderboard, dar nu sunt expuse consecvent în player analytics.

#### Heatmap

`PlayerAnalyticsService.buildHeatmap(...)` pornește dintr-un template pe grup de poziție și îl modulează prin Work Rate, Pace și Off The Ball. Nu provine din events sau tracking. Este o **proiecție cosmetică E**, nu heatmap de meci.

### 3.4 Statistici pe sezon și competiție

`MatchStatsService.getTeamSeasonStats(teamId, season)` agregă meciurile în care echipa apare home/away, dar nu primește competition id; rezultatul este all-competitions pentru sezon. Pass accuracy este media simplă a procentelor de meci, nu `sum(completed)/sum(attempted)`.

`StatsAggregationService` oferă:

- overview și leaderboard-uri globale din `Scorer` și `PlayerSeasonStat`;
- team data hub: W/D/L, goals, assists, rating, clean sheets, form;
- championship stats din `MatchStats` și finance;
- competition goals/assists/rating din `Scorer`;
- discipline din `MatchEvent`;
- form/compare/breakdown pe jucător și echipă;
- records și rating impact.

Team data hub reconstruiește meciuri din linii `Scorer` fără fixture id. Aceasta este o sursă de deduplicare greșită și trebuie înlocuită de `fixture_id` canonic.

### 3.5 Premii

`AwardService` folosește aproape exclusiv date deja derivate/sintetice:

| Premiu | Input actual | Observație |
|---|---|---|
| Player of Year | average `Scorer.rating` + weighted goals/appearance + assists/appearance | nota însăși depinde mult de goluri și rezultat; dublează impactul golurilor |
| Golden Boot competiție | goals, tie-break average rating | rezonabil dacă Scorer este canonic |
| Golden Boot global | weighted goals × 2,5 + weighted assists × 1 | este mai degrabă contribution award decât Golden Boot pur |
| Most Assists | assists | rezonabil dacă definiția și evenimentele sunt canonice |
| Most Entertaining | dribblesCompleted + 1,5 × chancesCreated | ambele sunt formule din atribute, nu acțiuni de meci |
| Best Goalkeeper | rating + clean sheets + saves/app − conceded/app | saves este `SOT-goals`; ratingul include rezultat/clean sheet; fixture lookup folosește doar round+team |
| Manager of Year | PPG + GD/game + underdog bonus din reputation | statistică de rezultat, acceptabilă ca design de gameplay |
| Team of Year | `playerOfYearScore` pe sloturi | propagă problemele notei și ale golurilor |
| Ballon d'Or | panel determinist simulat din rating, weighted goals/assists, big-match contributions, consistency | transparent ca gameplay, dar nu action-value; reputația competiției și scorurile sintetice domină |

### 3.6 API și frontend

Endpoint-uri backend relevante:

- `/match/summary/...`, `/match/matchEvents/...`, `/match/playerRatings/...`;
- `/match/live/{key}/state|advance|substitute|commit`;
- `/match/stats/...`, `/match/stats/season/{teamId}/{season}`;
- `/stats/overview/{season}`, `/stats/team/{teamId}/season/{season}`;
- `/stats/championshipStats/...`, `/stats/competition/...`, records, form, compare și analytics.

`MatchController.getMatchSummary(...)` ia scorul din result detail, golurile din `MatchEvent`, ratingurile din `Scorer`, iar posesia din `MatchStats`; dacă aceasta lipsește, inventează o posesie din puterea curentă a echipelor. Un meci istoric poate deci afișa o posesie schimbată după transferuri/dezvoltare — fallback exclusiv de display care trebuie eliminat sau etichetat `estimated=true`.

`/match/stats/...` etichetează răspunsul ca „Opta-style”, deși multe valori nu respectă încă proveniența și definițiile Opta. Contractul API nu include `source`, `quality`, `algorithmVersion` sau `fidelity`.

Frontend-ul exact cerut lipsește. Proiectul disponibil `/Users/ciprian.amza/IdeaProjects/footballmanager-frontend` nu conține feature components/services/stat pages; `app.component.ts` are doar titlul, iar HTML-ul este template Angular implicit. Prin urmare:

- nu se poate verifica ce endpoint este consumat în producție;
- nu se poate inventaria ce valori sunt transformate/rotunjite în UI;
- nu se poate valida afișarea live vs post-match;
- secțiunea §11.3 descrie contractul recomandat, nu o modificare a unui frontend existent.

---

## 4. Comparație cu un ecosistem profesionist

Legendă:

- **DA** = există cu definiție și provenance suficient de apropiate;
- **PARȚIAL** = există ca nume/agregat, dar este sintetic, incomplet sau nereconciliat;
- **NU** = nu există;
- tip **E** = event data; **T** = tracking/spatial; **E+T** = ambele; **D** = derivată din alte date;
- cost: **mic**, **mediu**, **mare**, **foarte mare** pentru generare+stocare la toate meciurile.

„Disponibil” e evaluat după semnificația profesională, nu după simpla existență a unui câmp cu același nume.

### 4.1 Atac

| Statistică | Definiție recomandată | Acum | Tip | Utilitate în simulator | Dificultate | Cost | Prioritate |
|---|---|---:|---|---|---|---|---|
| șuturi | toate attempts deliberate, fiecare cu shooter, context și outcome | PARȚIAL | E | volum ofensiv, bază pentru xG | medie | mediu | P0/P1 |
| șuturi pe poartă | goal + saved + last-line block, conform definiției alese | PARȚIAL | E | finishing/GK | medie | mic | P0 |
| șuturi blocate | attempt oprit înainte de ultima linie; blocker asociat | PARȚIAL | E | apărare, shot quality | medie | mic | P1 |
| din careu / din afară | shot origin față de box | NU | E | shot selection și tactică | medie | mic | P1 |
| big chances | flag contextual/versionat, nu simplu prag xG | PARȚIAL | E(+T) | calitatea ocaziilor | mare | mic | P2 |
| big chances missed | big chance neconvertită; schema trebuie să permită și air-shot/fără attempt dacă se adoptă Opta | PARȚIAL | E | finishing | mare | mic | P2 |
| xG | probabilitatea pre-shot de gol a fiecărui șut, apoi sumă | PARȚIAL | E+T aproximat | principala explicație a ocaziilor | mare | mediu | P0/P1 |
| post-shot xG / xGOT | probabilitate după placement/power pentru șuturile pe poartă | NU | E+T | shot execution și goals prevented | mare | mediu | P2 |
| xG/shot | `sum(xG)/shots` (cu reguli pentru penalties) | NU | D | shot selection | mic după xG | mic | P1 |
| non-penalty xG | suma xG excluzând penalty attempts | NU | D | comparații finishing/creation | mic după xG | mic | P1 |
| xA | pentru pasa creatoare: xG-ul șutului rezultat sau un model al probabilității de assist, definiție versionată | NU | E/D | creator quality | medie | mic | P1 |
| shot-creating actions | ultimele N acțiuni care conduc la șut, cu fereastră/definiție | NU | E | build-up credit | mare | mediu | P2 |
| goal-creating actions | acțiuni premergătoare unui gol | NU | E | attribution | medie | mic | P2 |
| atingeri în careu | touches controlate în box advers | NU | E | territory/box presence | medie | mediu | P1 |
| posesii în ultima treime | possessions care ating/start în final third | NU | E | control teritorial | medie | mic | P1 |
| field tilt | share din touches/passes în ultima treime, definiție explicită | NU | D | dominance independent de posesie | mic după events | mic | P1 |
| progressive carries | carry reușit care avansează peste pragul versionat | NU | E | ball progression | medie | mediu | P1 |
| progressive passes | pass completed care avansează mingea conform pragului | NU | E | ball progression | medie | mediu | P1 |
| key passes | ultima pasă înaintea unui șut ratat; assist separat | PARȚIAL | E | creativity | medie | mic | P1 |
| through balls | pass qualifier care sparge linia | NU | E(+T) | vision/movement | mare | mediu | P2 |
| crosses / completed | delivery din wide spre zonă centrală; success prin recipient/control | PARȚIAL | E | style și chance creation | medie | mic | P1 |
| cutbacks | pass înapoi din/byline/box către zonă centrală | NU | E | high-quality creation | medie | mic | P2 |
| driblinguri încercate/reușite | take-on contest cu defender și outcome | PARȚIAL | E | 1v1, progression | medie | mediu | P1 |
| pierderi de posesie | unsuccessful touch/pass/carry/dispossessed care schimbă controlul | NU | E | risk, transition | medie | mediu | P1 |
| offside | receiver/run penalizat offside și pass legat | PARȚIAL | E | linie și timing | medie | mic | P1 |

### 4.2 Pase și posesie

| Statistică | Definiție recomandată | Acum | Tip | Utilitate | Dificultate | Cost | Prioritate |
|---|---|---:|---|---|---|---|---|
| passes attempted/completed | evenimente individuale; completed ajunge direct la coechipier conform definiției providerului | PARȚIAL | E | possession, style, players | medie | mare dacă se persistă toate | P0/P1 |
| pass % | completed/attempted, agregare ponderată | PARȚIAL | D | precision sub context | mic | mic | P0 |
| scurte/medii/lungi | bucket după lungimea start-end și praguri versionate | NU | E | directness | mic după coordonate | mic | P1 |
| pase progresive | definiția de mai sus | NU | E/D | progression | medie | mic | P1 |
| pase în ultima treime | completed/attempted cu destination în final third | NU | E/D | territory | mic | mic | P1 |
| pase în careu | destination în box advers | NU | E/D | creation | mic | mic | P1 |
| switch of play | pass lung transversal între wide channels | NU | E | width manipulation | medie | mic | P2 |
| pase sub presiune | passer `under_pressure` sau pressure event legat | NU | E(+T) | press resistance | mare | mediu | P2 |
| passes per possession | numărul de pase din fiecare possession, apoi distribuție/medie | NU | E/D | style | mic după sequences | mic | P1 |
| directness | progres net spre poartă / timp sau lungime totală a secvenței | NU | D | tempo vertical | medie | mic | P1 |
| sequence length | număr acțiuni/durată/distanță per sequence | NU | E/D | build-up | medie | mic | P1 |
| build-up speed | progres longitudinal / duration | NU | D | transition vs control | medie | mic | P1 |
| possession won/lost | change of controlled possession, cu zone și player credit | NU | E | transitions | medie | mediu | P1 |
| xT | schimbarea valorii zonei între start/end pentru moves reușite | NU | E/D | credit progression | mare/calibrare | mic | P2 |
| packing/defenders bypassed | număr adversari depășiți de pass/carry; fără tracking doar aproximare zonală | NU | T sau E aproximat | line-breaking | foarte mare exact | mare | P3; aproximație P2 |

Definițiile Opta pentru pass, possession și sequence cer relații între evenimente și coordonate. Numărul curent `homePasses` nu poate suporta aceste derivate.

### 4.3 Apărare

| Statistică | Definiție recomandată | Acum | Tip | Utilitate | Dificultate | Cost | Prioritate |
|---|---|---:|---|---|---|---|---|
| tackles attempted/won | challenge asupra posesorului; outcome și possession regain | PARȚIAL | E | duel ability | medie | mediu | P1 |
| interceptions | citirea și întreruperea unei pase adverse | PARȚIAL | E | shape/anticipation | medie | mediu | P1 |
| clearances | îndepărtare fără recipient din zonă periculoasă | PARȚIAL | E | defensive pressure | medie | mic | P1 |
| blocks | shot/pass block, tip și shooter/passer legat | PARȚIAL | E | defending | medie | mic | P1 |
| aerial duels | pereche competitor-winner-loser, context | PARȚIAL | E | aerial matchups | medie | mediu | P0/P1 |
| ground duels | pereche și outcome unic | PARȚIAL | E | 1v1 | medie | mediu | P0/P1 |
| pressures | attempt de a închide/controla adversarul, cu timestamp/zone | PARȚIAL | E(+T) | press model | mare | mare | P1/P2 |
| successful pressures | pressure urmată de regain într-o fereastră definită | PARȚIAL | E/D | press effectiveness | medie după pressure | mic | P2 |
| PPDA | opposition passes / defensive actions în zona de pressing | NU | D | press intensity | mic după events | mic | P1/P2 |
| defensive actions outside box | tackles+interceptions+blocks etc. după zone | NU | D | proactive defense | mic după coordonate | mic | P1 |
| recoveries | câștigarea unei mingi libere/controlului | NU | E | transitions | medie | mediu | P1 |
| errors leading to shot | eroare individuală legată causal de shot | NU | E | rating/coaching | mare | mic | P2 |
| errors leading to goal | subsetul precedent convertit | NU | D | accountability | mic după above | mic | P2 |
| fouls | foul event cu offender/victim/zone/outcome | PARȚIAL | E | discipline/set pieces | medie | mic | P1 |
| galbene/roșii | card event cu reason și second-yellow/direct-red | PARȚIAL | E | man state/suspension | medie | mic | P0 |

### 4.4 Portari

| Statistică | Definiție recomandată | Acum | Tip | Utilitate | Dificultate | Cost | Prioritate |
|---|---|---:|---|---|---|---|---|
| saves | shot on target salvat, cu shot id | PARȚIAL | E | GK performance | medie | mic | P0/P1 |
| save % | saves/(saves+goals conceded from eligible SOT) | NU | D | basic GK | mic | mic | P1 |
| goals prevented | post-shot xG − goals conceded, cu reguli own goal | NU | D | shot-stopping | mare după PSxG | mic | P2 |
| PSxG − GA | aceeași familie, agregare per meci/sezon | NU | E/D | professional GK | mare | mediu | P2 |
| catches | keeper claim/control pe shot/cross | NU | E | handling | medie | mic | P2 |
| punches | punch event | NU | E | aerial decisions | medie | mic | P2 |
| claims | high-ball/cross claim attempted/successful | NU | E | command of area | medie | mic | P2 |
| sweeper actions | intervenție GK în afara/spre marginea careului | NU | E+T | high line risk | mare | mediu | P2/P3 |
| distribution | passes/throws/launches cu length/outcome | NU | E | build-up | medie | mediu | P1 |
| clean sheets | 0 goals conceded în apariție eligibilă | DA | D | awards/history | mic | mic | P1 |
| penalty saves | penalty shot outcome saved | NU | E | keeper/penalty model | medie | mic | P1 |

### 4.5 Date fizice și tracking

| Statistică | Definiție recomandată | Acum | Tip | Utilitate | Dificultate | Cost | Prioritate |
|---|---|---:|---|---|---|---|---|
| km / distance per 90 | sumă a mișcării sau model latent pe intervale, normalizată | NU | T/latent | fatigue, role fidelity | medie ca estimare | mic | P1 |
| distance in/out possession | distanță împărțită după team possession state | NU | T/latent | physical-tactical | medie | mic | P2 |
| walking/jogging/running/sprinting | distanță în zone de viteză versionate | NU | T/latent | load composition | medie | mic | P2 |
| high-intensity runs/distance | distance/efforts peste pragul ales | NU | T/latent | fatigue/press/transitions | medie | mic | P1/P2 |
| sprinturi și sprint distance | eforturi și metri peste prag; separă count/distance | NU | T/latent | pace/role | medie | mic | P1/P2 |
| viteza maximă | maxim robust al eforturilor, nu simplu Pace map | NU | T/latent | player profile | medie | mic | P2 |
| accelerări/decelerări | crossings ale pragului m/s² sau evenimente latente | NU | T/latent | mechanical load | medie | mic | P2 |
| repeated sprints | cluster de sprinturi cu recovery sub prag | NU | T/latent | fatigue/injury | mare | mic | P2 |
| heatmap | density din poziții/evenimente; template-ul curent trebuie etichetat projected | PARȚIAL | T/E | role/display | mare exact | mare | P3 |
| average position | centroid ponderat în timp/posesie | NU | T sau latent | shape | mare exact | mediu | P2 aproximat/P3 exact |
| team compactness | hull/dispersion inter-player | NU | T | defense/press | foarte mare | foarte mare | P3 |
| defensive-line height | median/mean x al ultimei linii în state relevante | NU | T/latent | tactics | mare | mediu | P2 aproximat/P3 exact |
| width | distanța laterală între extreme/shape | NU | T/latent | tactics | mare | mediu | P2 aproximat/P3 exact |
| distance defense–attack lines | diferența longitudinală între liniile echipei | NU | T | compactness | foarte mare | foarte mare | P3 |

### 4.6 Ce se poate face fără tracking real

Se pot genera credibil, cu event engine și stări latente:

- toate statisticile basic de șut/pasă/duel/keeper;
- xG pre-shot, xA, non-penalty xG, xG/shot;
- possession/sequence/directness/field tilt/PPDA;
- progressive passes/carries, key passes, through balls și zone;
- distance, HID, sprint count/distance, max speed și acc/dec ca **estimări modelate**;
- average position, line height și width ca valori latente pe intervale, dacă răspunsul API le marchează `estimated`.

Necesită motor spațial mai bogat sau tracking adevărat pentru a pretinde acuratețe profesională:

- defender locations/freeze-frame exact la șut;
- packing/defenders bypassed exact;
- pressure radius și cover shadow exacte;
- heatmap de poziție continuă;
- compactness, team surface, inter-line distances;
- post-shot placement și goalkeeper reach biomecanic fin.

---

## 5. Legătura recomandată între atribute, tactică, context și statistici

### 5.1 Principii

1. Niciun atribut nu determină singur o statistică. Fiecare acțiune are cel puțin **volum/oportunitate**, **selecție actor**, **rezolvare matchup** și **outcome**.
2. Atributele 1–20 se transformă în efecte centrate, saturate, de exemplu `z=(attr-10.5)/4.5`, apoi `sigmoid`/`softmax`; nu se înmulțesc arbitrar cu un total fix.
3. Tactica modifică **oportunitățile și contextul**, nu garantează succesul. High press creește mai multe pressure opportunities și consumă energie; nu dă direct tackles sau goluri.
4. Adversarul este obligatoriu. Dribbling fără defender, passing fără pressure/target movement sau finishing fără goalkeeper nu sunt matchups.
5. Separăm skill-ul de selecție: Off the Ball poate crește apariția într-o zonă de șut; Finishing afectează execuția, nu numărul de posesii al echipei.
6. Ratingul post-meci se calculează după acțiuni și nu mai este input pentru evenimentele aceluiași meci. Ratingul intrinsec/pre-match rămâne input, dar trebuie redenumit `lineup_value`.

### 5.2 Tabel de mapare

| Statistică | Atribute individuale relevante | Tactică/rol | Context | Model recomandat | Impact asupra rezultatului |
|---|---|---|---|---|---|
| km alergați | Stamina, Work Rate, Natural Fitness; Pace/Acceleration mai mult pentru compoziția intensității | rol, press, tempo, width, line | minute, posesie, scor, ET, oboseală, revenire | lognormal/negative-binomial pe intervale în jurul unei baze pe post | indirect: consumă rezerva și schimbă probabilitățile viitoare |
| sprinturi | Pace, Acceleration, Stamina, Off the Ball | wide/overlap, counterattack, high line | spațiu, tranziție, possession state, fatigue | count NB; distanță per sprint lognormal; cap de viteză individual | indirect prin runs, press recovery, availability |
| pase încercate | Decisions, Teamwork, Positioning/Off the Ball | playmaker/ball-playing, possession, tempo | touches, zone, teammates available, score | oportunități din sequence engine + actor softmax | mută posesia/teritoriul, nu gol direct |
| pase reușite | Passing, Technique, Composure, Decisions, Vision | passing risk/length | distanță, angle, pressure, target movement, weak foot | `logit(Pcomplete)=β0+skills+target−pressure−length−risk` | păstrează/progresează posesia și schimbă threat |
| through balls | Vision, Passing, Technique, Decisions | directness, attacking duty | run timing/Off the Ball al țintei, linie, pressure, space | `Pattempt` și `Pcomplete` separate; pass-target-defender matchup | poate produce box entry/shot de calitate |
| progressive passes | Passing, Vision, Technique, Decisions | role, risk, width | start/end zone, block advers | event rezultat, apoi derivat din coordonate | crește xT/territory, indirect |
| crosses/cutbacks | Crossing, Technique, Vision, Decisions | wide play, overlap, byline | wide zone, receiver occupation, pressure | attempt selection + delivery completion + duel/shot chain | creează shot contexts distincte |
| driblinguri | Dribbling, Technique, Agility, Acceleration, Balance, Flair, Decisions | dribble more/less, role | space, defender Tackling/Positioning/Strength, fatigue | paired duel: attempt actor, defender, outcome softmax | progresie sau turnover; nu bonus direct la scor |
| progressive carries | Dribbling, Acceleration, Technique, Balance, Decisions | role/transition | start zone, open space, pressure, fatigue | carry length/success conditional pe state și defender | schimbă territory/xT și shot probability |
| intercepții | Positioning, Anticipation, Decisions, Concentration, Agility | press shape, line, marking | pass lane, pass quality, zone, cover | hazard pe pass event; interceptor softmax | oprește atac și poate porni transition |
| tackling | Tackling, Decisions/timing, Strength, Aggression, Agility, Balance | press/foul hardness | dribbler skill, speed differential, zone, fatigue | outcome multinomial: clean win/foul/beaten/loose ball | schimbă posesia; foul poate genera set piece/card |
| aerial duels | Jumping Reach, Heading, Strength, Bravery, Positioning, Balance | target/marking, set-piece role | delivery height/quality, run-up, fatigue | un singur paired event cu winner+loser | controlează next state/shot chance |
| pressures/PPDA | Work Rate, Stamina, Aggression, Anticipation, Acceleration, Teamwork | intensity, counterpress, line, compactness | opponent possession zone, score, fatigue | pressure attempts generate; PPDA derivat din passes/actions | reduce time/accuracy, poate produce high turnover |
| șuturi | Off the Ball, Anticipation, Decisions, Positioning, Flair | attacking role, mentality, width, transitions | territory, possession, service, defender pressure | shot-selection hazard după progression, nu din goals | creează o Bernoulli/softmax de outcome |
| xG per șut | nu folosi Finishing în xG standard; eventual Heading/weak-foot doar ca shot type | set piece/open play, attack pattern | locație, unghi, body part, assist type, one-on-one, pressure, defenders/GK position estimate | logistic/GAM/GBM calibrat pe shot context | probabilitatea medie pre-shot; nu garantează gol |
| goal din xG | Finishing, Composure, Technique; Heading pentru header | shoot instruction | pre-shot xG, pressure, body part, fatigue; GK Positioning/Reflexes/1v1 | outcome model cu shooter random effect și goalkeeper layer | direct, dar xG rămâne neschimbat după outcome |
| xA | Passing, Vision, Technique, Decisions | creator role | pass type/end zone, pressure, receiver și shot xG | credit pasei legate de shot; sumă xG sau model xA versionat | explică creation, nu marchează direct |
| saves/PSxG | Reflexes, Handling, Positioning, One-on-Ones, Agility, Concentration | keeper role | placement, velocity, distance, sight/blockers, fatigue | post-shot model + keeper-vs-shot resolution | direct asupra transformării SOT în goal |
| distribution GK | Kicking, Throwing, Passing, Technique, Decisions, Composure | short build-up/long release | press, target, score | același pass model, cu action types GK | pornește sequence și afectează turnover risk |
| errors leading shot | Concentration, Decisions, Composure, Technique/First Touch | risk/build-up | pressure, zone, fatigue | event rare cu hazard; shot linked prin sequence | indirect, crește shot hazard/quality adversă |
| average position | Positioning, Off the Ball, Work Rate doar ca adaptare | formation slot, role, duty, mentality, line, width | in/out possession, score, red card | centroid latent pe intervale + jitter individual | shape schimbă transition/space, nu goal direct |
| rating post-meci | toate prin acțiunile observate, position-adjusted | rol/expected contribution | minute, game state, opponent | regularized action-value + goals added only o dată; baseline 6 | nu influențează meciul deja jucat |

### 5.3 Model generic de eveniment

Pentru o oportunitate `o`, actorul și outcome-ul se rezolvă separat:

```text
opportunity_rate(o,t) = f(team_state, tactic, zone, score_state, opponent_shape)

P(actor=i | o,t) = softmax(
    role_availability_i + position_fit_i + relevant_attributes_i
    + energy_i + spatial_opportunity_i
)

P(outcome | i,j,o,t) = softmax(
    skill_i - opposing_skill_j + tactic_context
    + spatial_context + pressure + fatigue + random_effects
)
```

Aceasta împiedică echivalentul actual „jucătorul are Pass%=90, deci primește automat mai multe passes attempted”. Volumul vine din posesii și rol; precizia vine din matchup.

---

## 6. Model realist pentru distanță și oboseală fără GPS

### 6.1 Definiții și praguri

Motorul trebuie să aleagă și să versioneze pragurile. O propunere inițială, nu standard universal:

```text
walking:       0–7 km/h
jogging:       7–15 km/h
running:      15–20 km/h
high speed:   20–25 km/h
sprinting:       >25 km/h
high accel:      >+3 m/s²
high decel:      <-3 m/s²
```

Sursele folosesc praguri diferite; de aceea fiecare rând fizic trebuie să includă `threshold_profile_version`. Altfel comparațiile între sezoane sau cu surse publice devin invalide.

### 6.2 Repere publice per 90

Tabelul de mai jos folosește media ± SD din studiul Bundesliga cu 1.964 observații full-match. Este **anchor de calibrare**, nu valoare fixă pe jucător.

| Grup pozițional | Total km | High-intensity km | Sprint km | Accelerări |
|---|---:|---:|---:|---:|
| CB/DC | 10,21 ± 0,64 | 1,04 ± 0,41 | 0,19 ± 0,08 | 484 ± 42 |
| wide defender DL/DR | 10,75 ± 0,56 | 1,37 ± 0,23 | 0,36 ± 0,14 | 500 ± 39 |
| wing-back WBL/WBR | 10,96 ± 0,55 | 1,48 ± 0,27 | 0,37 ± 0,11 | 512 ± 37 |
| CM/DM | 11,66 ± 0,92 | 1,57 ± 0,83 | 0,24 ± 0,13 | 510 ± 44 |
| wide/attacking mid | 11,07 ± 0,73 | 1,51 ± 0,28 | 0,42 ± 0,14 | 494 ± 46 |
| forward/ST | 10,86 ± 0,80 | 1,43 ± 0,30 | 0,34 ± 0,13 | 473 ± 47 |
| GK | țintă 4,0–6,6; medie publicată 4,82 | mult mai mică; ~0,8% timp high-intensity în studiul citat | model separat | acțiuni GK-specific separate |

Un al doilea corpus open-access cu 3.731 observații raportează, în funcție de post, aproximativ 10,24–11,72 km, 1,00–1,67 km high-intensity, 0,19–0,40 km sprint, max velocity 30,22–31,72 km/h și 469–518 accelerări. Aceste valori confirmă ordinea, nu identitatea distribuțiilor. FIFA arată că possession state și stilul schimbă puternic compoziția alergării.

### 6.3 Model pe intervale

Recomand intervale de 1 sau 5 minute și random streams separate (`physical`, `events`, `shots`), astfel încât fidelity să nu schimbe scorul.

Pentru jucătorul `i`, intervalul `t` de `Δm` minute:

```text
base_mpm_i = positional_mean_m_per_90(position, role) / 90

total_distance_i,t = Δm * base_mpm_i
  * role_multiplier
  * work_rate_effect
  * tempo_effect
  * possession_state_effect
  * score_state_effect
  * width_line_effect
  * transition_effect
  * availability_effect
  * fatigue_pacing_effect
  * exp(player_random + match_random + interval_noise)
```

Recomandări de parametrizare inițială:

- `player_random ~ N(0, 0.05²)` pentru total distance și mai larg pentru HID/sprint;
- `match_random ~ N(0, 0.04²)` comun echipei;
- total distance are variație relativ mică; HID și sprint au CV 20–40%;
- Work Rate/Stamina au efect saturat de ±8–12%, nu ×2;
- Pace/Acceleration influențează în primul rând shares de high-speed/sprint și max velocity, nu kilometrii totali;
- high press crește distanța fără minge, accelerările și HID, dar efectul se reduce când energia scade;
- high width crește cerința wide roles; high line schimbă recovery runs ale CB/GK;
- transitions/counterattack cresc sprinturile atacanților/wide și recovery sprinturile adversarilor;
- când echipa conduce, modelul tactic poate reduce tempo/press; când este condusă, le poate crește, fără regulă universală;
- minutele de prelungiri sunt simulate, nu multiplicate automat cu `120/90`, fiindcă fatigue pacing și schimbările alterează intensitatea;
- `injury_recovery_factor` reduce capacity și max velocity, nu adaugă kilometri ca „efort”.

### 6.4 Compoziția distanței

După total distance, compoziția se generează condiționat, dar fără a forța exact media postului:

```text
[walk, jog, run, high_speed, sprint]_shares
    ~ LogisticNormal(position_base + role + tactic + possession_state
                     + pace/acceleration + fatigue + random_effects)

high_intensity_distance = high_speed + sprint
sprint_count ~ NegBin(mean = f(role, pace, acceleration, transitions, space, minutes, fatigue))
sprint_lengths ~ LogNormal(position_role_mean, individual_sigma)
max_speed = max(sampled_efforts), capped by individual_capacity(t)
accel_count, decel_count ~ correlated NegBin(...)
```

Invariante:

- `sum(zone_distances) = total_distance` în toleranță de rotunjire;
- sprint distance ≤ high-intensity distance ≤ total distance;
- zero pentru `minutesPlayed=0`;
- valorile se proratează prin intervale reale, nu doar per90;
- max speed nu poate crește când acute fatigue scade capacity, exceptând zgomot foarte mic de măsurare;
- un GK folosește model separat: positioning/shuffles, sweeper runs, jumps/dives, nu profil de CB redus arbitrar.

### 6.5 Starea energetică și efectul invers

În locul fitness-ului curent care doar scade, folosim două stări:

- `readiness_pre_match` — condiția cu care începe meciul, influențată de recovery/injury/congestion;
- `energy_reserve(t)` — rezervă acută în meci.

```text
load_i,t = a1*total_distance + a2*HID + a3*sprint_distance
         + a4*accelerations + a5*decelerations + a6*duel_load

capacity_i = f(Stamina, NaturalFitness, readiness, injury_recovery)

energy(t+1) = clamp(energy(t)
  - load_i,t / capacity_i
  + low_intensity_recovery_i,t, 0, 1)
```

Energia afectează **următoarele** acțiuni:

```text
effective_pace       = base_pace       * (0.82 + 0.18*energy)
effective_accel      = base_accel      * (0.78 + 0.22*energy)
press_arrival_prob   *= 0.65 + 0.35*energy
duel_execution       += gamma_duel * (energy - 0.5)
pass/shot error_logit += gamma_error * (1 - energy)
concentration_lapse_hazard += gamma_lapse * (1 - energy)^2
injury_hazard        = base_hazard * exposure * readiness_penalty
                       * exp(gamma_acute*(1-energy) + gamma_history)
```

Distanța nu oferă goluri. Ea consumă resurse; mișcarea potrivită poate crea oportunități prin event/space engine, iar oboseala poate reduce press, duel, execuție și recovery runs. Injury hazard se rulează doar pentru jucătorii expuși și trebuie reactivat explicit; configurația curentă `player-availability-disabled=true` îl ocolește.

### 6.6 Criterii de acceptare pentru physical model

- medianele pe post în 100.000 de apariții sunt în ±5% față de datasetul ales;
- P5/P95 și SD, nu doar media, sunt în benzi aprobate;
- wide roles au sprint distance mai mare decât CB/CM la nivel de populație, dar distribuțiile se suprapun;
- CM are total distance mai mare în medie, fără ca fiecare CM să depășească fiecare ST;
- high press crește HID/accel și scade energia finală, cu efect monoton în sweep controlat;
- schimbarea la minutul 60 produce exact 60/30 minute și distance corespunzătoare;
- ET adaugă 30 de minute simulate și accentuează lower-tail energy;
- un jucător nefolosit are toate valorile fizice zero;
- aceleași seed/input produc aceleași physical stats în live, STANDARD și FULL.

---

## 7. Modelul cauzal recomandat pentru rezultat

### 7.1 Ce se decide înainte de meci

- fixture identity, seed root și algorithm versions;
- lineup, bench, formation, roles, tacticile inițiale și set-piece takers;
- atribute și readiness snapshot;
- coaching/home/context/weather dacă se adaugă;
- team latent strengths: build-up, progression, chance creation, press, block, finishing pool, goalkeeper;
- parametrii distribuțiilor, nu scorul.

Predetermined/admin score rămâne un caz explicit `simulation_mode=CONDITIONAL_BRIDGE`, nu calea normală.

### 7.2 Ce se rezolvă în timpul meciului

Pe intervale/evenimente:

```text
match state + tactics + energy + manpower
  -> possession start / restart
  -> zone and pressure state
  -> action selection (pass/carry/dribble/shot/clearance/...)
  -> actor and opponent matchup
  -> action outcome and next state
  -> if shot: pre-shot xG
  -> placement/on-target/block/own-goal branches
  -> goalkeeper resolution / goal
  -> update score, tactics, energy and state
```

Scorul este `count(goal events for team)`, iar statisticile sunt reducers peste același ledger.

### 7.3 Possession și progression engine

Nu este necesar tracking 25 Hz. Un state discret suficient pentru STANDARD:

```text
period, second/minute, possession_team, zone(16x12 sau macro 6x5),
phase(open_play/set_piece/transition), pressure_level,
team_shape_state, score_state, manpower, energy summaries
```

Acțiunea următoare se alege prin hazard/softmax. Ratele de posesii și acțiuni pot folosi Negative Binomial/Dirichlet-Multinomial pentru overdispersion și corelații, nu Poisson independent pentru fiecare statistică.

### 7.4 Shot quality / xG

Un baseline transparent poate fi logistic/GAM:

```text
logit(xG_shot) = β0
  + spline(distance) + βangle*angle
  + body_part + shot_type + play_pattern
  + assist_type + first_time + one_on_one
  + pressure_level + defender_density_estimate
  + goalkeeper_position_estimate
  + sequence_features
```

Reguli:

- penalty are model separat, nu aceeași ecuație cu open play;
- xG se calculează și se persistă **înainte** de outcome;
- Finishing/Composure nu intră în xG standard dacă vrem un model average-finisher; intră în goal conversion ca player effect;
- post-shot xG se calculează numai după placement/on-target și nu rescrie xG;
- `npxG=sum(xG where shot_type != PENALTY)`;
- nu există floor `xG >= goals/3` și nici `shots>=goals` pentru autogoluri; own goal este event separat.

### 7.5 Goal resolution

Pentru un șut normal:

```text
shot_context -> xG_pre

placement/outcome_class ~ softmax(
  xG_pre + shooter(Finishing, Composure, Technique, Heading/body_part)
  - pressure - fatigue + random
)

if on_target:
  psxG = post_shot_model(location_in_goal, velocity, trajectory, sight)
  goal ~ Bernoulli(logistic(logit(psxG)
         - keeper(Reflexes, Positioning, OneOnOnes, Handling)
         + rebound/deflection context))
```

Alternativ, un singur multinomial `goal/saved/woodwork/off-target/blocked` evită dublarea probabilităților. Important este ca suma marginală, pe populație average shooter/GK, să fie calibrată la xG.

Ramuri separate:

- **own goal:** deflection/clearance/cross event atribuit apărătorului; crește scorul advers fără shot/SOT obligatoriu;
- **penalty în meci:** foul → penalty awarded → taker vs GK → goal/saved/off target; penalty xG rămâne pre-shot;
- **set piece:** restart → delivery/shot chain; goal type rezultă din chain;
- **red card:** schimbă manpower și hazardurile doar din acel minut înainte;
- **extra time:** continuă state/energy 30 min, cu eligible lineup;
- **shootout:** ledger separat `ShootoutKick`, nu intră în score/goals/xG de meci; folosește doar jucătorii eligibili conform regulii competiției/IFAB.

### 7.6 De ce apar natural rezultatele extreme cerute

- `1-0, un șut, xG 0,15`: singurul șut are outcome goal la probabilitate mică; nu se mărește xG după gol.
- `0-1 la șuturi 30-1`: 30 de Bernoulli pot eșua, iar șutul advers reușește; raritatea este controlată de distribuția xG, nu interzisă.
- `3 goluri din xG 0,8`: shooter variance/placement/GK pot produce overperformance.
- `0 goluri din xG 3,2`: saves, blocks și misses; probabilitatea este mică, dar nenulă.
- autogolul nu cere shot on target;
- penalty-ul ratat contribuie xG fără gol;
- roșul schimbă viitorul, nu scorul trecut.

### 7.7 Dacă scorul trebuie decis înainte: conditional bridge corect

Pentru admin override, replay sau compatibilitate tranzitorie cu `MatchPlan`, nu se falsifică statisticile prin floor. Se folosește sampling condiționat:

```text
target_score = (h,a)
for particle in 1..K:
    simulate causal event script from pre-match state
    weight = P(script produces target_score | shot outcomes)
resample particles by weight
if no exact particle:
    bridge only unresolved future goal outcomes,
    never alter persisted shot context/xG
```

O variantă mai ieftină generează mai întâi un set cauzal de posesii/șuturi și apoi eșantionează outcome-urile condiționat pe numărul de goluri cu Poisson-binomial. Este apropiată de ideea actuală din `generateChanceLine`, dar trebuie inversată ordinea:

1. shot contexts și xG se generează necondiționat din acțiuni;
2. se condiționează **doar outcome-urile** la target score;
3. dacă target score este aproape imposibil pentru script, se respinge întregul script și se generează altul; nu se modifică xG-ul șuturilor individuale.

`MatchPlan` devine containerul pentru seed, snapshot și event ledger/status; scorurile stocate sunt verificări/cache ale goal reducer-ului, nu cauza goal slots.

### 7.8 Păstrarea modelului tactic existent

`TacticalScoreService` nu trebuie aruncat. Profilele attack/defense și tactic vector pot alimenta:

- possession/territory priors;
- action transition probabilities;
- pressing și line/width latent states;
- chance creation și shot quality priors;
- fallback BASIC aggregate simulation.

Intensitățile sale Poisson pot rămâne benchmark de regresie macro și prior pentru numărul de ocazii, dar golurile finale trebuie rezolvate din shots. Constructorii care pierd aptitude multipliers trebuie eliminați conceptual din designul nou.

---

## 8. Clasificare cauzală A–E

| Categorie | Entități/statistici recomandate | Regula |
|---|---|---|
| **A — input** | atribute snapshot, lineup/role, tactic/press/tempo/width/line, home context, weather, readiness, seed, fidelity | există înainte de kickoff; nu este rescris de rezultat |
| **B — stare latentă** | possession state, zone, pressure, team shape, player energy, effective pace, line height estimate, threat state | evoluează în timp; poate fi persistată ca snapshot, nu este stat final de sine stătător |
| **C — eveniment** | pass, carry, dribble, duel, foul/card, recovery, shot/xG, save, goal/own goal, substitution, injury, restart | are actor, time, outcome, context și legături; este sursa de adevăr |
| **D — derivată** | score, shots/SOT/blocks, passes/%, possession, xG/npxG/xA, PPDA, xT, duels, saves, distance, ratings, season totals | reducer determinist peste A/B/C și reguli versionate |
| **E — display** | text commentary, animation, rounded bars, badges, projected heatmap, fallback estimates | nu intră în awards, records sau reconstituirea rezultatului; trebuie etichetată |

Mapare importantă față de starea curentă:

| Câmp curent | Categoria reală acum | Categoria dorită |
|---|---|---|
| `TacticalScoreService` xG/intensity | B/A-derived prior | B prior pentru possessions/chances |
| `MatchStats.homeXg` | D fabricat condiționat post-scor | D sumă din `ShotEvent.xg` |
| `MatchStats.possession` instant | E/D sintetic | D din possession durations |
| `PlayerSeasonStat.pressures` | E/D sintetic din attributes | D din pressure events/estimates explicite |
| `Scorer.rating` | D din score/contributions+noise | D din action value, position-adjusted |
| heatmap curent | E projected | E cu `estimated=true`; D doar când există events/positions |
| fitness | A pre-match și B în live, dar două modele | A readiness + B energy, identic între moduri |

Dependențe interzise:

```text
performance_rating(t) -> event în același meci -> performance_rating(t)
final_score -> xG/possession/pass quality fabricate
award -> statistică sursă
display fallback -> historical record
```

---

## 9. Calibrare și validare pe 10.000–100.000 de meciuri

### 9.1 Situația testelor actuale

Proiectul are infrastructură utilă:

- `EngineInvariantSuiteRunner` rulează scenarii seed-uite și măsoară win rates/goals;
- `DefaultInvariants`, sensitivity, Sobol interactions și auto-tuner;
- fuzz/integration tests pentru strength, tactici, campionat, determinism;
- `MatchStatsServiceTest`, live/MatchPlan persistence și lifecycle tests;
- `FullCareerSimulationIT` verifică unele invariante de sezon.

Lacuna: harness-ul central evaluează în principal **scorul scalar** și win rate, nu distribuția comună event → shot → xG → goal → player/team stats. `EngineInvariantsCatalogIT` este explicit informativ și nu cere ca toate invariants să treacă. Testele de determinism verifică egalitatea între două run-uri ale aceluiași generator, nu adevărul statistic față de realitate.

### 9.2 Protocol recomandat

| Nivel | Volum | Când | Scop |
|---|---:|---|---|
| smoke | 1.000 meciuri | fiecare PR | invariants structurale, crash, determinism |
| regression | 10.000 | PR-uri de motor / nightly | percentile, rates, mode parity, strength buckets |
| calibration | 100.000 | nightly/release | tails P95/P99, rare events, upset/extremes, league strata |
| career | minimum 20×100 sezoane seed-uri | release | drift, awards, storage, promotion/relegation, player workloads |

Pentru fiecare run se persistă un artifact, nu în DB-ul jocului:

```text
engine_version, config_hash, dataset_target_version, seed_range,
fidelity, league_level, strength_delta_bucket, tactic_pair,
all requested quantiles, confidence intervals, invariant failures
```

Se compară:

- distribuții marginale prin KS/Wasserstein/PSI;
- counts prin χ² și intervale bootstrap;
- probabilități xG prin calibration curve, Brier score și log loss;
- efecte cauzale controlate prin paired seeds/A-B sweeps;
- corelații și distribuții comune, nu doar media fiecărui câmp.

### 9.3 Benzi macro inițiale de acceptare

Acestea sunt **propuneri de engineering pentru fotbal masculin profesionist modern**, intenționat largi. Înainte de gate-ul final se recalculează dintr-un corpus ales (de exemplu StatsBomb Open Data pentru competițiile compatibile) și se versionează per ligă/epocă.

#### Pe echipă și meci, 90 minute

| Metrică | P5 | P25 | Mediană | P75 | P95 | P99 |
|---|---:|---:|---:|---:|---:|---:|
| goluri | 0 | 0 | 1 | 2 | 3 | 5 |
| șuturi | 4 | 8 | 12 | 16 | 23 | 29 |
| șuturi pe poartă | 1 | 2 | 4 | 6 | 9 | 12 |
| conversie goluri/șuturi %, condiționat de shots>0 | 0 | 0 | 8 | 17 | 33 | 50 |
| xG | 0,20 | 0,65 | 1,15 | 1,80 | 3,10 | 4,40 |
| xG/shot | 0,035 | 0,065 | 0,095 | 0,130 | 0,200 | 0,280 |
| posesie % | 28 | 41 | 50 | 59 | 72 | 79 |
| passes attempted | 220 | 335 | 440 | 555 | 730 | 860 |
| pass completion % | 64 | 75 | 81 | 86 | 91 | 94 |
| faulturi | 5 | 9 | 12 | 15 | 20 | 24 |
| galbene | 0 | 1 | 2 | 3 | 5 | 6 |
| cornere | 1 | 3 | 5 | 7 | 10 | 13 |

Nu se aplică orbește aceleași percentile ligilor de nivel, epocă și stil diferite. Red cards și penalty-uri sunt mai bine validate ca rates decât percentile, deoarece majoritatea cuantilelor sunt zero:

- red card team-match: bandă inițială 1–4%;
- penalty awarded per match: bandă inițială 0,20–0,40;
- own goal per match: eveniment rar, calibrat separat din corpus;
- assist eligibility: fără assist pentru own goal/direct free-kick și reguli explicite pentru rebound/deflection conform definiției alese.

#### Pe meci

| Metrică | Bandă inițială |
|---|---|
| goals/match medie | 2,5–3,1, calibrată per ligă |
| total goals P5/P25/P50/P75/P95/P99 | 0 / 1 / 2 / 4 / 6 / 8 |
| share 0 goals | 6–11% |
| share 1 goal | 16–23% |
| share 2 goals | 22–29% |
| share 3 goals | 19–25% |
| share 4+ goals | 22–33% |
| home win / draw / away win | 42–49% / 22–29% / 27–34% |
| score cap hit | <0,1%; ideal fără cap dur în modelul nou |

Aceste benzi nu sunt toate independente; gate-ul trebuie să compare scorline matrix 0–0…8–8, inclusiv 0-0, 1-0, 0-1 și 1-1 unde independent Poisson este cunoscut ca aproximare imperfectă.

### 9.4 Physical percentiles

Pentru fiecare post se validează P5/P25/P50/P75/P95/P99 pentru total distance, HID, sprint distance/count, max speed și accel/decel. Target-ul se construiește din mean±SD public și, ideal, microdata licențiată. Exemplu inițial:

- total distance P5/P95 aproximativ `mean ± 1,65×SD`, trunchiat realist;
- HID/sprint trebuie folosite distribuții lognormal/gamma, nu normală care poate da metri negativi;
- max speed are distribuție individuală ierarhică, cu within-player variance mai mică decât between-player;
- minutele se validează separat și toate per90 includ minimum-minutes policy.

Pentru CB din reperul public, un anchor normal aproximativ ar fi 9,15–11,27 km P5–P95; pentru CM 10,14–13,18 km. Acestea nu sunt limite hard și nu trebuie aplicate jucătorilor cu minute parțiale fără model pe intervale.

### 9.5 Corelații și sensibilități care trebuie urmărite

| Relație | Așteptare |
|---|---|
| xG–goals la team-match | pozitivă moderat-puternică, nu 1; bandă inițială Pearson/Spearman 0,50–0,75 |
| shots–xG | pozitivă, dar xG/shot permite separare; 0,55–0,85 |
| possession–passes | puternic pozitivă; >0,70 în același univers de definiții |
| strength difference–xGD/win rate | monotonă în bucket-uri, cu overlap și upset tail |
| GK quality–goals prevented | monotonă în paired-seed experiment, fără a schimba shot xG |
| high press–pressures/HID | pozitiv; successful pressure nu obligatoriu monoton fără squad aptitude |
| fatigue–late errors/press effectiveness | error rate crește, press success scade în paired scenarios |
| pace–sprint/max speed | pozitiv la nivel de populație; nu determinist 1:1 |
| role change–physical profile | efect vizibil, dar player random effect păstrează identitatea |

Upset rate se raportează pe strength-delta buckets, nu ca un singur procent. Exemplu: equal, 5%, 10%, 20%, 40% difference; în fiecare se măsoară W/D/L, xGD, shot diff și tail scores.

### 9.6 Invariante obligatorii

| Invariantă | Tratament exact |
|---|---|
| score = count goals | `goal_for` + goals normale; shootout exclus |
| goals ≤ SOT | pentru goals din shot; own goals sunt excepție explicită și nu cresc automat SOT |
| SOT ≤ shots | mereu pentru shots deliberate; last-line blocks după definiția providerului |
| blocked + off-target + SOT + alte outcomes = shots | o singură partiție versionată |
| completed passes ≤ attempted | team și player; pass % din sume |
| successful dribbles ≤ attempted | paired event ledger |
| duel wins reconciliabile | fiecare duel are un winner/lost sau neutral/no-winner explicit; fără două volume independente |
| assists ≤ eligible goals | și fiecare assist referă goal event; maximum unu în definiția Opta-style |
| minutes | sumă din lineup/sub/red/injury/ET; 0≤minutes≤match duration; reguli goalkeeper |
| distance zero dacă nu a intrat | exact; bench warm-up nu este match distance |
| player↔team aggregates | egalitate pentru definițiile aditive; diferențele (possession, sequence) documentate |
| cards | `MatchStats` reducer = card events eligibile; second yellow/direct red fără double count greșit |
| saves | fiecare save referă un SOT; goals prevented din PSxG eligibility |
| xG | suma shot xG; goal outcome nu modifică valoarea |
| live=batch=fast-forward | același fixture seed/input produce score, goal events, scorers, minutes și core stats identice |
| fidelity parity | BASIC/STANDARD/FULL au același canonical ledger pentru core facts sau reducer echivalent verificabil |
| idempotency | retry/refresh nu dublează events/stats/appearances |

### 9.7 Exemple de teste de acceptare

```text
Given aceeași fixture, lineup, tactics, seed:
  simulate BASIC, STANDARD, FULL
  assert score, scorers, goal minutes/types, assists, cards, substitutions,
         minutes, shots, SOT, xG, possession_core are identical

Given manual sub at 60':
  snapshot events 0..60
  continue with/without sub using paired random streams
  assert all events 0..60 unchanged
  assert only hazards and actors after 60 differ

Given own goal as only goal:
  assert score 1-0
  assert normal scorer goals = 0
  assert SOT may be 0
  assert own_goal event names defender and credits opponent score

Given 30 shots and zero goals:
  assert no xG rewrite/floor
  assert goal reducer = 0 and xG can exceed 3.0
```

---

## 10. Arhitectura recomandată

### 10.1 Principiul central

O singură fixture are:

```text
FixtureSnapshot + SimulationManifest(seed/version/config/fidelity)
                       |
                       v
             Canonical Match Engine
                       |
        +--------------+---------------+
        | event ledger | state snapshots|
        +--------------+---------------+
                       |
               deterministic reducers
          / team stats / player stats / physical /
```

Live este un executor incremental al aceluiași motor; batch este executor până la final; fast-forward este scheduler peste batch. Nu există generatoare alternative de scoreri/stats.

### 10.2 Componente

| Componentă | Input | Output | Persistare / determinism | Cost | MatchPlan/live/FF |
|---|---|---|---|---|---|
| Fixture Snapshot Builder | fixture, XI, bench, skills, tactic, fitness, rules | snapshot immutable | persistat, deterministic | mic | înlocuiește citirea stării curente la reafișare/replay |
| Simulation Manifest | seed root, config hash, algorithm versions, fidelity | random stream ids și provenance | persistat, deterministic | minim | cheie comună tuturor modurilor |
| Possession/Sequence Engine | snapshot + match state | possession/sequence starts, states | ledger sau summaries; deterministic cu seed | mediu | live step; batch loop; FF bulk |
| Event Generator | state, tactic, actors, opponents | pass/carry/duel/foul/shot/sub etc. | events persistate conform fidelity | mediu–mare | același cod, granularitate controlată |
| Shot Quality/xG Model | shot context pre-outcome | `xg`, features/version | persistat pe shot, deterministic | mic/model mediu | niciodată post-score |
| Goal Resolution Model | shot+xG, shooter, GK, defenders, energy | outcome, PSxG, save/goal/rebound | event, seed deterministic | mic | score reducer comun |
| Set Piece/Own Goal/Shootout | foul/restart/rules/eligible players | chains și separate shootout kicks | persistat | mic | MatchPlan păstrează faza corectă |
| Player Match State/Fatigue | minute, load, readiness, events | energy/effective skills/on-pitch | snapshots interval/opțional | mediu | live vizibil; batch identic |
| Physical Load Model | role/state/tactic/energy | distance zones, speed, accel/decel | player physical aggregate + optional intervals | mic–mediu | nu depinde de rendering |
| Match Statistics Aggregator | event ledger + durations | `MatchStats`/team facts | deterministic, idempotent | mic | rulează după sau incremental |
| Player Statistics Aggregator | events + appearances | `PlayerMatchStats` | deterministic | mic | sursă pentru seasons/awards |
| Rating/Action Value | player events + context | rating, VAEP/xT-like values | derived/versioned | mediu | după meci; nu feedback same match |
| Calibration/Telemetry | batch artifacts/reference targets | quantiles, failures, drift | în afara save DB | mare offline | CI/nightly/release |
| Historical Aggregation | canonical match/player facts | season/career records | incremental + rebuildable | mic | API rapid, lineage păstrat |

### 10.3 Integrarea cu `MatchPlan`

`MatchPlan` trebuie evoluat din „scor + goal slots” în „manifest + lifecycle al ledger-ului”:

- `PLANNED`: snapshot/seed/config, fără scor decis în modul normal;
- `IN_PROGRESS`: events append-only și state checkpoint;
- `COMPLETED`: reducer final, score checksum și full-time facts;
- `COMMITTED`: immutable pentru gameplay;
- `CONDITIONAL_BRIDGE`: flag separat pentru admin/predetermined results.

Nu este necesar să persiste fiecare event în BASIC. Poate persista un `core_event_ledger` (goals, shots, cards, subs, injuries) și sufficient statistics seed-uite pentru passe/possessions, dar reducer-ul trebuie să producă aceleași core stats ca STANDARD.

### 10.4 BASIC / STANDARD / FULL

| Nivel | Ce generează | Ce persistă | Utilizare | Buget orientativ |
|---|---|---|---|---|
| BASIC | același engine la rezoluție agregată/possession transitions; core shots/goals/cards/subs/minutes/physical | manifest, core events, match/team/player aggregates | fast-forward și meciuri îndepărtate | 1× |
| STANDARD | possessions și acțiuni relevante: passes/carries/duels/fouls/shots, coordonate zonale | event ledger compact + aggregates | meciuri obișnuite | 3–8× BASIC |
| FULL | aceleași evenimente, state pe intervale, pressure/spatial estimates și animation inputs | ledger complet + selected checkpoints, nu fiecare frame | meci urmărit/live/replay | 10–30× BASIC |

Regula de paritate:

- seed-urile se separă pe namespace (`possession`, `action`, `shot`, `goal`, `discipline`, `sub`, `physical`, `cosmetic`);
- FULL nu consumă RNG din stream-ul goal pentru animații;
- fie toate modurile rulează aceleași core events și doar persistă diferit, fie BASIC folosește un reducer stochastic cu golden parity test față de un pre-generat `core plan`;
- scor, scorers, assisturi, goal minutes/type, cards, subs, minutes, shots/SOT/xG și physical core nu diferă.

Recomandarea pragmatică: generați **core plan-ul o singură dată** pentru toate fixture-urile. STANDARD/FULL îl extind cu detalii non-core fără să rescrie nimic.

---

## 11. Schema de date, API și volum după 100 de sezoane

### 11.1 Model relațional recomandat

Entitățile curente nu trebuie șterse într-o singură migrare. Ele pot deveni read models de compatibilitate, alimentate de reducer-ele noului ledger până când toate endpoint-urile sunt mutate.

| Entitate propusă | Câmpuri esențiale | Constrângeri și rol |
|---|---|---|
| `match_fixture` | `id`, competition/season/stage/round, home/away, scheduled time, ruleset | fixture identity stabil; nu `round+teams` ca identitate implicită |
| `match_simulation_manifest` | `fixture_id`, root seed, snapshot/config hash, engine/definition versions, fidelity, status, started/completed time, score și checksum | `fixture_id` PK/FK; exact un adevăr canonic per execuție activă; retry idempotent |
| `match_lineup_snapshot` | fixture/team/player, role/position, starter, bench order, skills/fitness/tactic snapshot | unique `(fixture_id, team_id, player_id)`; istoricul nu se schimbă când jucătorul evoluează |
| `match_appearance` | fixture/team/player, started, `minute_in_sec`, `minute_out_sec`, exit reason, captain/GK flags | unique `(fixture_id, player_id)`; sursa unică pentru minute și eligibility |
| `match_event_v2` | UUID/id, fixture, sequence, possession/period, clock seconds, team/player/related/opponent, type/subtype/outcome, start/end XY, body part, pressure, causal parent, qualifiers, model version | unique `(fixture_id, sequence)`; append-only până la completion; indecși `(fixture_id, clock)` și `(player_id, type)` |
| `match_shot` | `event_id`, xG, non-penalty xG, PSxG dacă on-target, outcome, situation, assist event/player, GK, body part, location, freeze-frame-quality flag | one-to-one cu shot event; xG calculat înainte de outcome; own goal separat |
| `match_team_stats` | fixture/team și toate sumele reducer-ului | unique `(fixture_id, team_id)`; două rânduri/meci elimină perechile `home*`/`away*` și simplifică agregarea |
| `player_match_stats` | fixture/player/team, minute, G/A, shots/SOT/xG/npxG/PSxG, passes attempted/completed/progressive/key/xA, carries, dribbles A/S, touches, defensive/duel/GK counts, rating | unique `(fixture_id, player_id)`; numai reducer din events/appearances |
| `player_match_physical` | fixture/player, distance total și in/out possession, walk/jog/run/HID/sprint, sprint count, Vmax, accel/decel, energy start/end, load, threshold/model version | unique `(fixture_id, player_id)`; `estimated=true` până există tracking |
| `player_season_aggregate` | player/team/competition/season + sume și minute | unique pe cheia aleasă; rebuildable din player-match, nu sursă independentă |
| `calibration_run` | engine/config/reference version, seeds, strata, quantiles, CIs, failures | bază separată/artifact store; nu umflă save-ul utilizatorului |

`qualifiers` JSON este potrivit pentru atribute rare/versionate (`deflection`, `advantage`, `set_piece_routine`), dar metricile folosite frecvent în filtre și agregări trebuie să fie coloane tipate. Coordonatele trebuie normalizate într-un singur sistem, de exemplu `0..100 × 0..100`, cu echipa în posesie atacând mereu în aceeași direcție la stocare.

### 11.2 Migrarea modelelor curente

| Model actual | Problemă de păstrat sub control | Migrare |
|---|---|---|
| `MatchStats` | un singur rând cu coloane duplicate home/away; identity prin competition/season/round/teams | adăugare `fixture_id`, apoi două `match_team_stats`; view/adapter pentru vechiul DTO |
| `MatchEvent` | `details` string, tipologie minimă, proveniență null pentru legacy, idempotency limitată la goal slots | dual-write în `match_event_v2`; backfill numai facts sigure, marcate `source=LEGACY` |
| `Scorer` | nu are fixture id; repetă scor/oponent și este folosit ca truth pentru reconstrucție | adăugare `fixture_id`, apoi derivare din `player_match_stats`; păstrat temporar ca read model |
| `PlayerSeasonStat` | fără unique constraint promis; `shots` înseamnă de fapt formula „Expected Goals”; minute fixe | nu redenumi orb coloana; migrare versionată `legacy_synthetic_xg`, apoi rebuild din `player_match_stats` |
| `MatchPlan`/goal slots | util pentru seed/idempotency, dar centrat pe scor prestabilit | transformare în manifest/lifecycle; bridge condițional separat de simularea normală |

Migrarea trebuie să fie expand → dual-write → compare → read-switch → contract. Nu se face reset de bază de date și nu se recalculează retroactiv valori care nu pot fi demonstrate. Pentru istoricul vechi, răspunsul API trebuie să spună `source=LEGACY_SYNTHETIC` și `estimated=true`.

### 11.3 Contract API recomandat

```text
GET /api/v2/matches/{fixtureId}/statistics
GET /api/v2/matches/{fixtureId}/events?detail=core|standard|full&cursor=...
GET /api/v2/matches/{fixtureId}/players
GET /api/v2/players/{playerId}/matches/{fixtureId}
GET /api/v2/players/{playerId}/analytics?competitionId=...&season=...&per90=true&minMinutes=...
GET /api/v2/competitions/{competitionId}/seasons/{season}/records
GET /api/v2/admin/calibration/runs/{runId}
```

Orice payload statistic include:

```json
{
  "fixtureId": 123,
  "engineVersion": "event-engine-v1",
  "definitionVersion": "stats-def-v1",
  "fidelity": "STANDARD",
  "source": "SIMULATED_EVENT_LEDGER",
  "estimated": false,
  "coverage": { "spatial": "ZONAL", "physical": "MODELLED" }
}
```

Frontend-ul ar trebui să aibă minimum: Summary/Team Stats, Shots & xG, Passing, Defending, Physical, Players și Timeline. Etichetele `Estimated`, `Projected` și `Legacy synthetic` trebuie afișate în UI și în tooltip-ul definiției. Nu se afișează „Opta-style” fără o definiție proprie versionată și fără a afirma afiliere/certificare.

### 11.4 Estimare de volum pentru 100 de sezoane

Dimensionarea exactă este parametrică:

```text
M = meciuri/sezon × sezoane
player rows = M × apariții medii/meci
event rows = M × evenimente persistate/meci
storage ≈ rows × bytes efectivi × (1 + index/overhead) + snapshots/backups
```

Scenariu de lucru pentru universul actual de aproximativ 106 echipe: **2.300 meciuri/sezon** incluzând ligi și cupe, deci **230.000 de meciuri în 100 de sezoane**. Este o ipoteză de capacity planning, nu un count garantat al scheduler-ului.

| Date | Multiplicator | Rânduri la 230k meciuri | Ordin de mărime cu indecși |
|---|---:|---:|---:|
| manifest + fixture/team aggregates | 1 manifest + 2 team rows | 0,69 milioane | 1–3 GB |
| player appearances/stats/physical | ~28 jucători | 6,44 milioane per tabel | 8–20 GB pentru setul player-match; physical încă 5–15 GB |
| BASIC core events | ~25 | 5,75 milioane | 5–12 GB |
| STANDARD actions | ~300 | 69 milioane | 40–100 GB |
| FULL vendor-like events | până la ~3.000 | 690 milioane | 250–700+ GB |

Cu snapshots, agregate, indecși, VACUUM/headroom și backup, bugetele practice devin aproximativ:

- BASIC pentru toate meciurile: 20–50 GB;
- STANDARD pentru toate: 70–180 GB;
- FULL pentru toate: 350 GB–1 TB, nejustificat pentru un save local.

Politica recomandată: BASIC canonic pentru toate fixture-urile; STANDARD pentru meciurile din competițiile urmărite și ultimele N sezoane; FULL numai pentru meciuri urmărite/replay, cu payload-uri rare comprimate/partitionate. Agregatele de carieră rămân în DB, iar events non-core vechi pot fi arhivate columnar. Partiționarea logică este pe `season`/competition, indecșii cei mai importanți sunt fixture-first, iar endpoints de carieră citesc agregate, nu scanează sute de milioane de events.

---

## 12. Roadmap P0–P3

### P0 — coerență și integritate înainte de statistici noi

| Livrabil | Beneficiu | Complexitate | Risc / performanță | Dependențe | Test obligatoriu |
|---|---|---:|---|---|---|
| fixture id + immutable snapshot + simulation manifest | elimină identități fragile și istoricul recalculat din lotul curent | M | migrare/dual-write; cost mic | fixture model și migrations | retry/idempotency + istoric neschimbat după transfer |
| repararea live commit după schimbare | elimină rescrierea trecutului și discrepanța score/events/Scorer | M | zonă critică live; cost runtime mic | snapshot + RNG namespaces | sub la 60': events 0–60 identice; final reducer coerent |
| un singur core plan/ledger pentru human, AI, live și fast-forward | un adevăr pentru toate modurile | L | refactor orchestration; poate crește batch 1–2× | manifest, event schema | seeded mode-parity matrix |
| reducer unic score/team/player stats | garantează invariantele și elimină generatoarele independente | L | dual-write temporar | core ledger | property tests pentru toate invariantele §9.6 |
| minute reale și semantic fix pentru `PlayerSeasonStat.shots` | repară per90, awards și analytics | M | migrare de date imposibil de backfill exact; etichetare legacy | appearances + player reducer | subs/red/ET/minutes + migration contract |
| goal/assist/card/save/duel reconciliation | repară discipline, GK și totalurile individuale | M | schimbă leaderboard-uri | event taxonomy | fiecare event are referințe și sume exacte |

**Exit P0:** niciun meci nou nu poate avea score diferit de goal ledger, minute imposibile sau rezultate diferite între moduri pentru același snapshot și seed.

### P1 — versiunea 1 credibilă statistic

| Livrabil | Beneficiu | Complexitate | Risc / performanță | Dependențe | Test obligatoriu |
|---|---|---:|---|---|---|
| possession/sequence engine la rezoluție STANDARD | face oportunitățile și posesia cauzale | L | principalul cost CPU; buget 3–8× BASIC | P0 engine loop | percentile + possession/pass correlation |
| shot rows + model xG pre-outcome + goal/GK resolver | șuturile explică scorul; permite ratări mari, 30–0 shots, low-xG wins | L | calibrare model, nu doar cod | event context + reference corpus | calibration/Brier/log loss + paired GK test |
| player-match event aggregation | passing/defense/attacking per90 reale în universul simulat | L | mai multe rows/indecși | appearances și event ledger | player sums = team sums |
| physical/fatigue pe intervale | distanță, sprint, accel/decel și efect tactic realist fără GPS | L | compute mic-mediu; risc de false precision | roles, possession states, energy | public role percentiles + zero-minutes + late fatigue |
| API v2 + provenance badges | frontend-ul știe ce este observat, estimat sau legacy | M | menținere compatibilitate v1 | schema/reducers | contract tests, pagination, no historical fallback |

**Exit P1:** 100.000 de meciuri trec benzile versionate și toate invariantele; un meci poate fi explicat de la possession la gol și înapoi la agregate.

### P2 — analiză avansată și awards mai bune

| Livrabil | Beneficiu | Complexitate | Risc / performanță | Dependențe | Test obligatoriu |
|---|---|---:|---|---|---|
| xA, key passes, progressive passes/carries, box entries, PPDA | evaluare de creare/progresie/pressing | M | definiții sensibile la provider | STANDARD events | fixture examples + corpus distribution |
| xT/VAEP-like action value propriu | ratinguri mai puțin dependente de scor | L | model leakage și explainability | zones/sequences | out-of-sample validation + ablation |
| PSxG/goals prevented și GK distribution | separă shot stopping de apărare | M-L | cere placement estimat credibil | shot/GK resolver | no-SOT case, rebound, own goal, paired GK |
| rating/awards/records v2 | premiile folosesc minute și acțiuni, fără double count al golului | M | schimbă experiența save-urilor | player facts + action value | season replay și stability bands |

### P3 — spatial/tracking-like și scară premium

| Livrabil | Beneficiu | Complexitate | Risc / performanță | Dependențe | Test obligatoriu |
|---|---|---:|---|---|---|
| positional state pe intervale + average positions/heatmaps | vizualizare tactică coerentă | XL | compute/storage mare, este tot estimare | FULL engine | formation/role plausibility + continuity |
| pressure polygons, compactness, pitch control estimat | analiză spațială avansată | XL | nu trebuie vândut ca tracking măsurat | positional state | synthetic ground-truth scenarios |
| archival/columnar events + selective FULL replay | 100 sezoane fără DB operațional gigant | L | ops/compatibilitate | retention policy | restore/replay/performance benchmarks |

Nu se începe P2/P3 pentru a cosmetiza UI-ul înainte ca P0 să fie închis. Ordinea critică este identity/snapshot → ledger → reducer → minute → mode parity → apoi metrici.

---

## 13. Verdict final și definiția unei versiuni credibile

### Răspunsuri directe

**Ce pare statistică, dar este de fapt cosmetică?** În runtime-ul actual: heatmap-ul pe template; statisticile individuale sintetizate din atribute; xG-ul și chance line-ul generate după scor; posesia fallback din puterea curentă; o mare parte din rating; descrierile de goal animation; regains ca procente fixe. `MatchStats` de echipă este mai bine numit „narațiune sintetică post-scor” decât event-derived analytics.

**Ce lipsește cel mai mult?** Nu încă un câmp avansat, ci infrastructura: fixture id canonic, snapshot, event ledger, appearances/minute și reducer unic. Primul pachet de metrici după acestea trebuie să fie shot-level xG, passes attempted/completed, duels paired, cards/subs și goalkeeper shots faced/saves.

**Ce se poate calcula fără tracking?** Aproape toate metricile event-level cerute: shots, xG, npxG, xA, key/progressive passes, carries, box entries, dribbles, tackles/interceptions/clearances/blocks, pressures/counterpressures, PPDA, set pieces, GK shot stopping și action value. Distanța, sprinturile și heatmaps pot fi **estimate** credibil prin model latent. Nu se pot pretinde măsurate: poziția exactă la fiecare frame, separarea fină on/off-ball pe traseu real, pressure radius real, compactness real, max speed sau accelerația fizică individuală observată.

**Cum trebuie construită prima versiune credibilă?** P0 plus P1: un core plan comun, event engine STANDARD, shot/xG/GK causality, minutes reale, reducer team/player/physical și API cu provenance. Nu este nevoie de tracking sau ML proprietar; este nevoie de definiții stabile, probabilități calibrate și reconciliere perfectă.

**Poate sistemul păstra 100 de sezoane?** Da, dacă păstrează BASIC pentru tot, STANDARD selectiv/partitionat și agregate rebuildable. Nu economic, într-un save local, dacă stochează permanent circa 3.000 de evenimente FULL pentru fiecare meci. Seed-ul nu înlocuiește snapshot-ul și versiunea de algoritm: fără ele, replay-ul istoric nu este reproductibil după schimbarea codului sau a lotului.

### Definition of Done pentru „statistici credibile v1”

1. Aceeași fixture/snapshot/seed produce aceleași core facts în live, instant, batch și fast-forward.
2. Score, events, shots, xG, cards, substitutions, minutes și team/player totals se reconciliază prin reducer, fără fallback de display.
3. xG este calculat înainte de outcome; goalkeeper quality afectează goal/save după shot quality, nu rescrie șutul.
4. 10.000-match regression rulează la schimbări de motor; 100.000-match calibration rulează înainte de release și salvează configurația/intervalele de încredere.
5. Rezultatele trec percentilele și corelațiile versionate pe corpus; sensibilitățile strength/tactic/fitness/GK sunt monotone în paired seeds, fără a elimina upset-urile.
6. Orice valoare din API are definiție, unitate, source, fidelity, algorithm/definition version și indicator `estimated` unde este cazul.
7. Migrarea păstrează save-urile; legacy synthetic nu este prezentat drept event-derived.
8. Un run de minimum 20×100 sezoane trece limitele de timp/stocare, rebuild-ul agregatelor și verificarea awards/records.

Verdictul final este **P0 pentru credibilitate analitică, dar utilizabil ca simulator arcade/statistical narrative**. Cea mai mare valoare nu vine din adăugarea a încă 30 de KPI-uri, ci din transformarea adevărului meciului într-un ledger unic, cauzal, reproductibil și explicabil. După acest prag, metricile avansate devin extensii controlate; înainte de el, ele amplifică neconcordanțele.

---

## 14. Anexă de trasabilitate în cod

| Responsabilitate | Locație curentă | Observație de audit |
|---|---|---|
| scalar score | `service/MatchSimulationService.calculateScores(...)` | independent Poisson, goal cap/config |
| tactical score | `service/TacticalScoreService` + apelurile din `MatchRoundSimulator` | profil tactic util; verificat și pentru pierderea multiplierilor prin `scaleProfile(...)` |
| orchestration human/AI | `service/MatchRoundSimulator` | ramuri diferite; AI optimized nu are ledger echivalent |
| team stats/xG | `service/MatchStatsService.generateMatchStats(...)` | post-score; `generateChanceLine(...)` condiționează pe goal/miss |
| live state/events/stats | `service/LiveMatchSession` și `LiveMatchSimulationService` | formule proprii live; commit rescore după manual sub |
| live persistence | `service/MatchdayCoordinator` | combină result detail, events, MatchStats și Scorer; punct critic de reconciliere |
| canonical goal plan | `matchplan/MatchPlanService`, `InstantMatchExecutor`, `MatchEventProjection` | flag off implicit; nu acoperă AI și toate evenimentele |
| team match entity | `model/MatchStats` | home/away row; xG integer×100; MOTM fields nealimentate în generator |
| event entity | `model/MatchEvent` | goal/assist/card/sub minimal; `details` liber; legacy provenance null |
| scorer/rating | `model/Scorer` și serviciile de distribuire/rating | fără fixture id; rezultat și contribuții domină ratingul |
| player analytics write | `service/PlayerMatchStatService.recordRealMatchesForTeams(...)` | 90 minute/start și formule independente de events |
| player season entity | `model/PlayerSeasonStat` | `shots` cu semantică xG; fără unique constraint declarată |
| player analytics read | `service/PlayerAnalyticsService`, `AnalyticsFormula` | fallback din atribute și heatmap template |
| season/records | `service/StatsAggregationService`, `MatchStatsService` | surse mixte; unele reconstrucții din Scorer și agregări all-competitions |
| awards | `service/AwardService` | folosește ratinguri și player stats sintetice; double-count posibil |
| API | `controller/MatchController`, `StatsController` | fără provenance/fidelity/version; fallback istoric de posesie |
| flags/config | `config/MatchEngineConfig`, `application.yml` | MatchPlan off; availability disabled; batch fitness drain 8 |
| validation | `engine/invariants/*`, integration/service tests | bază utilă pentru scor; lipsesc distributional joint gates și parity complet |

Acest tabel este indexul rapid; argumentele, formulele, definițiile și criteriile de acceptare sunt în secțiunile anterioare.
