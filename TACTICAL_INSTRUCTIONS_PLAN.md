# Plan: instrucțiuni per-jucător + instrucțiuni de echipă + executanți lovituri (2026-05-31)

Plan de implementare pentru: (1) instrucțiuni personale per jucător (popup din „+", etichete scurte
sub jucător), (2) instrucțiuni noi de echipă, (3) sub-pagină executanți lovituri, (4) cum se
configurează ca să conteze la **valoarea tacticii** — pe toate paginile tactics1…tactics5.

## Ce există deja (reutilizăm — NU rescriem)
- **Instrucțiuni per-jucător**: `PlayerInstructionService` (24 instrucțiuni, pe categorii + poziție),
  endpoint `GET /tactic/instructions/{position}`. Multiplicatorul 0.92–1.08 se aplică **deja** în
  `LineupRatingService` (per jucător, în valoarea de meci). Config: `MatchEngineConfig.InstructionWeights`
  (`DEFAULT_BONUSES` base + excepții pe poziție, `conflicts`, `bonusScale`, `conflictPenalty`, clamp).
- **Roluri per-jucător**: `PlayerRoleService` (roluri+duty+suitabilitate pe poziție), endpoint
  `GET /tactic/roles/{position}` + `GET /tactic/allRoleSuitabilities/{playerId}`. `computeEffectiveRating`
  se aplică în `LineupRatingService` (blend `RoleWeights.overallBlend/roleBlend`).
- **Executanți lovituri**: câmpuri pe `PersonalizedTactic` (`penaltyTakerId/freeKickTakerId/
  cornerTakerLeftId/cornerTakerRightId`) + `GET /tactic/suggestSetPieceTakers/{teamId}`. **Stocate, dar
  NEfolosite în engine.**
- **Persistență per-jucător**: `saveFormation` serializează `formationDataList` (cu `role/duty/
  instructions`) în coloana `first11` (JSON). Pagina **legacy `tactic`** are UX-ul complet de referință
  (popup rol + popup instrucțiuni + selectoare lovituri).

## Lacune (de făcut)
- **tactics1…tactics5 au ARUNCAT** datele per-celulă: `PositionedPlayer` are doar `{positionIndex, player}`;
  payload-ul de save trimite doar `{positionIndex, playerId}` — fără `role/duty/instructions`; și fără
  câmpurile de executanți lovituri. → instrucțiunile/rolurile nu se salvează din paginile noi.
- **Instrucțiuni noi de echipă** (dribling, fault, fragmentare, stil cross/cut-inside/shoot, contraatac/
  obținut faulturi, duritate fault) — **nu există** (nici câmp, nici config, nici engine).
- **Executanții de lovituri nu contează în meci** (nu-i citește engine-ul).

---

## A. Instrucțiuni + roluri per-jucător pe tactics1–5 (reutilizare, efort mic)
**FE** (fiecare din tactics1–5):
1. `PositionedPlayer` → adaugă `role: string|null, duty: string|null, instructions: string[]` (ca pe
   legacy `tactic.component`).
2. Pe fiecare jucător de pe teren: un buton **„+"** (sau click pe jucător) → deschide un panou pentru
   ACEL jucător: tab Rol (din `/tactic/roles/{poz}` + suitabilitate din `/allRoleSuitabilities/{id}`) și
   tab Instrucțiuni (din `/tactic/instructions/{poz}`, toggle). Stilizat per pagină (modernist/clasic/
   tabbed/dashboard/wizard).
3. **Etichete scurte sub jucător**: afișează `duty` (Defend/Support/Attack) + abrevieri instrucțiuni
   (ex. „Support · Shoot+ · Pass+"). Helper FE care mapează numele instrucțiunii la abreviere scurtă.
4. `saveData` include `role/duty/instructions` în fiecare entry din `formationDataList` (ca legacy).
**Backend**: nimic nou — `saveFormation` + `LineupRatingService` le consumă deja. (Eventual: expune și
pe path-ul de scor instant/two-axis dacă vrem — vezi §D.)

## B. Sub-pagină executanți lovituri pe tactics1–5 (reutilizare)
**FE**: o sub-pagină/secțiune (modal sau tab) cu 4 selectoare (Penalty / Free Kick / Corner Left /
Corner Right) populate din lotul de pe teren, buton „Auto-suggest" → `GET /tactic/suggestSetPieceTakers/
{teamId}`; salvează cele 4 id-uri în payload (deja suportate de `saveFormation`).
**Backend (opțional, ca să CONTEZE)**: în `MatchSimulationService`/live, la penalty/lovituri, folosește
atributele executantului ales (PenaltyTaking/FreeKick/Corners) ca modificator de conversie. Knob nou
`MatchEngineConfig.SetPieces` (greutăți atribute + magnitudine). Altfel rămâne doar selecție cosmetică.

## C. Instrucțiuni NOI de echipă (câmp + config + engine + FE)
Câmpuri noi pe `PersonalizedTactic` (String, null→neutru) + hărți categorical→numeric în
`MatchEngineConfig.TacticalModel` (resolver override→shipped→0, ca axele existente) + consum în
`TacticalScoreService.vector()/matchup()`:

| Setare | Valori | Mapare la engine (cum contează în valoare) |
|---|---|---|
| **Dribbling** | Less / Standard / More | `+risk` (deschide jocul, flair individual). More → openness↑. |
| **Fault frequency** | Rarely / Normal / Often | Often → mic `effDef` advers↓ (disturbi) DAR `cardRisk↑` (în live → man-disadvantage). |
| **Fault hardness** | Soft / Medium / Hard | Scalează efectul de mai sus + riscul de cartonaș (knob). |
| **Tempo fragmentation** | Flowing / Normal / Fragment | Fragment → openness↓ (ca `control`), bun pt. outsider. |
| **Wide play** | Cross / Cut Inside / Shoot | Cross → leagă-se de `width`+heading; Cut Inside → `narrow`+`risk`; Shoot → `directness`/long-shots. Contre pe apărarea adversă. |
| **Transition** | Fast Counter / Balanced / Win Fouls | Fast Counter → `risk`+ (exploatează linia înaltă adversă); Win Fouls → `control`+ (fragmentare). |

Knob-uri noi de putere în `TacticalModel` (ex. `dribbleRisk`, `foulDisruption`, `foulCardRisk`,
`fragmentControl`, `widePlayWidthBias`, `counterRisk`) + hărți (`dribbleAxis`, `foulFreqAxis`,
`foulHardnessAxis`, `fragmentAxis`, `widePlayAxis`, `transitionAxis`). Toate **0 la neutru** → no-op,
determinism intact. AI le alege prin extinderea euristicii (`ManagerTacticService` — coordonată, nu grid).

## D. Cum contează în VALOAREA tacticii (config-driven)
- **Per-jucător (rol+instrucțiuni)**: deja în `LineupRatingService` (base × familiaritate × moral ×
  fitness × **instructionMultiplier**, cu `base` = `computeEffectiveRating` când rolul e setat). Tunabil
  din `InstructionWeights` + `RoleWeights`. ⚠️ De verificat: path-ul **two-axis** de producție folosește
  valoarea per-jucător din `PlayerValueService.evaluatePlayer` — care **NU** aplică rol/instrucțiuni azi
  (doar `LineupRatingService` o face). Decizie de design (§Decizii): fie mutăm aplicarea rol/instrucțiune
  în `PlayerValueService` (ca să conteze în two-axis peste tot), fie le aplicăm la construirea profilului
  în `MatchRoundSimulator.starterValues`.
- **De echipă (C)**: intră în `TacticVector` (axe noi) → `matchup()` → scor, deci automat în
  `panelExpectedPoints` (advisor/AI) și în meci. Tunabil din `TacticalModel`.
- **Executanți lovituri (B)**: knob `SetPieces` în conversie (nu în valoarea de bază, ci în rezultat).

## E. UI per pagină (stiluri păstrate)
Fiecare pagină primește: (1) panoul per-jucător (rol+instrucțiuni) din „+", (2) etichete sub jucător,
(3) noile setări de echipă ca încă 6 control-uri (în stilul paginii: modale/gauge/tabs/wizard-step),
(4) sub-pagina de executanți. tactics4 (dashboard) poate vizualiza și instrucțiunile ca meters.

## F. Verificare
- `mvn verify` verde (no-op la neutru → ITuri structurale + determinism intacte).
- Teste noi: binding axe noi în `TacticalScoreServiceTest` (fiecare setare nouă mișcă scorul în direcția
  așteptată; neutru = no-op); `TacticController` save/load roundtrip pentru role/duty/instructions +
  executanți pe payload-ul nou.
- `ng build` verde; smoke vizual: „+" deschide panoul, etichetele apar sub jucător, executanții se
  salvează.

## Faze recomandate (livrare incrementală)
1. **Faza 1** (mic, reutilizare): A + B pe tactics1–5 (re-adaugă per-celulă rol/instrucțiuni + sub-pagina
   executanți). Valoare imediată, backend deja gata.
2. **Faza 2** (mediu): C — instrucțiuni de echipă (entitate+config+engine+FE+AI) + decizia din §D (rol/
   instrucțiuni în two-axis).
3. **Faza 3** (opțional): B-backend (executanții contează la conversie) + vizualizări tactics4.

## Decizii de confirmat
- Rol/instrucțiuni per-jucător să conteze în engine-ul **two-axis** de producție (azi doar în
  `LineupRatingService`/path uman)? Recomand: DA — mut aplicarea în `PlayerValueService`/`starterValues`.
- Executanții de lovituri să **conteze** în meci (Faza 3) sau rămân selecție?
- Mapările exacte (valori numerice) pentru cele 6 instrucțiuni de echipă — pornesc cu default-uri mici și
  le reglezi după smoke (ca la Width/Def Line).
