# Match Engine Refactor — Handoff

Document de continuitate pentru refactorul motorului de animație al meciurilor + cleanup-uri rămase din review-urile anterioare. Pornește de aici într-o sesiune nouă fără context pierdut.

---

## 0. Context proiect

- **Backend:** `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-/` — Java + Spring Boot, H2, JPA, Lombok, Maven.
- **Frontend:** `/Users/ciprian.amza/Downloads/footballmanager-frontend-test/` — Angular 15.
- **Branch curent:** `master`. Există modificări netracate (vezi `git status`).
- **Build:** `mvn -q compile` (backend), `cd frontend && node_modules/.bin/tsc --noEmit -p tsconfig.app.json` (frontend).
- **Teste curente:** `mvn test -Dtest='CurrentUserServiceTest,TeamAccessGuardTest'` — 15/15 verzi.

---

## 1. Ce s-a făcut deja (Etapa 1 — culori echipă + GK distinct)

### Fișiere noi
- `src/main/java/com/footballmanagergamesimulator/service/TeamKitResolver.java` — service stateless care:
  - Mapează CSS color names + hex pe 11 familii de culoare.
  - Dacă cele două echipe au `color1` în aceeași familie, defending team trece pe `color2`.
  - Alege GK kit dintr-o paletă de 6 culori, evitând familia ambelor outfield-uri.

### Fișiere modificate
- `src/main/java/com/footballmanagergamesimulator/frontend/GoalAnimationData.java` — adăugate `scoringTeamKit`, `defendingTeamKit` + inner class `TeamKit { outfieldPrimary, outfieldSecondary, outfieldBorder, gkPrimary, gkBorder }`.
- `src/main/java/com/footballmanagergamesimulator/service/GoalAnimationService.java` — autowired `TeamRepository` + `TeamKitResolver`, adăugat `attachKits(...)` apelat la finalul celor 3 metode publice (`generate`, `generatePenalty`, `generateFreeKick`). Call site-urile din `LiveMatchSimulationService` rămân neatinse.
- Frontend `src/app/app.component.ts` — `renderGoalFrame` folosește kit-urile primite cu fallback legacy blue/red. Helper nou `numberColorFor(fill)` alege alb/negru pe baza luminanței, ca să nu mai fie număr alb pe tricou galben.

### Decizii cheie
- Kit-urile sunt setate la nivel `GoalAnimationData` (per echipă), NU pe fiecare `AnimationPlayer` — DRY.
- `attachKits` e apelat în interiorul service-ului ca să nu schimb call site-urile (5+ locații în `LiveMatchSimulationService`).
- Paleta GK e fixă (galben, cyan, mov, portocaliu, roz, teal) — selecție deterministă în funcție de culorile outfield-ului.

---

## 2. Plan pentru etapele rămase

### Etapa 2 — Nume pentru toți jucătorii + ball carrier evidențiat

**Doar frontend.** Modificare în [app.component.ts](src/app/app.component.ts) → `renderGoalFrame`.

**Ce de făcut:**
1. Sub fiecare cerc de jucător, scrie numele de familie cu font 7-8px (folosește `player.name.split(' ').pop()`).
2. Codare culoare label:
   - `playerId === ballCarrierId` → galben (`#f1c40f`) + bold + light glow.
   - Marcatorul (`playerId === scorerPlayerId`) păstrează highlight-ul lui curent, dar doar la frame ≥ 130 (sau după evenimentul GOAL/SAVE/MISS).
   - Restul: alb semi-transparent (`rgba(255,255,255,0.7)`).
3. **Anti-aglomerare:** dacă doi jucători sunt la <5 unități pe X *și* <3 unități pe Y, ascunde label-ul jucătorului ne-ball-carrier (păstrează doar pe cel cu mingea).

**Where to put it:** chiar în bucla `players.forEach(...)` din `renderGoalFrame`, după desenul shirt number-ului (aprox. linia 644 după Etapa 1).

**Risc:** low. ~50 LOC.

---

### Etapa 3 — Match Highlights Level + miss/save animations

**Setare nouă în Manager Responsibilities** + animații pentru big chances ratate/salvate. Tot user a confirmat **opțiunea (2)** — simulare nouă de big chances în `LiveMatchSimulationService` (nu derivat retrospectiv din `MatchEvent`).

#### 3a — Setting (plumbing)

**Backend:**
1. În [Human.java](src/main/java/com/footballmanagergamesimulator/model/Human.java), înlocuiește booleanul `watchGoalHighlights` cu:
   ```java
   @Column(columnDefinition = "varchar(20) default 'GOALS_ONLY'")
   private String matchHighlightsLevel = "GOALS_ONLY"; // NONE | GOALS_ONLY | KEY_MOMENTS
   ```
2. **Migrare:** într-o metodă `@PostConstruct` sau similar — populează câmpul pe baza vechiului boolean (`true → GOALS_ONLY`, `false → NONE`).
3. În [ManagerController.java](src/main/java/com/footballmanagergamesimulator/controller/ManagerController.java), `getResponsibilities` + `updateResponsibilities` (linia 170+) — citește/scrie noul câmp.

**Frontend:**
1. Pagina Manager Responsibilities — înlocuiește checkbox-ul "Watch goal highlights" cu un radio/dropdown:
   - **None** — nu deschide modal-ul
   - **Goals only** — doar `outcome === "GOAL"`
   - **Key moments** — toate animațiile
2. Salvează preferința în `localStorage` ca shortcut (poll-ul de `/managers/responsibilities/{teamId}` rămâne ca source of truth).
3. În [app.component.ts](src/app/app.component.ts), în `advanceGame()` (linia 115+), când vine `liveMatchKey`, fetch-uiește setting-ul; filtrarea `goalAnimations` se face DUPĂ ce datele sunt primite — dacă `level === NONE`, închide modal-ul; dacă `GOALS_ONLY`, joacă doar animațiile cu `outcome === "GOAL"`; altfel toate.

~100 LOC. Risc low.

#### 3b — Simulare big chances în `LiveMatchSimulationService`

**User a cerut și realism pe shot counts** — nu vrem 12-10 între echipe inegale; vrem 12-3.

**Modifică [LiveMatchSimulationService.java](src/main/java/com/footballmanagergamesimulator/service/LiveMatchSimulationService.java):**

1. **Disparitate șuturi** — În generarea timeline-ului, ponderarea evenimentelor de tip "attack" trebuie să fie influențată de power-ul echipelor. Caută unde se decide `team1HasBall` și unde se rolează `attackRoll` (~linia 200). Calculează `powerRatio = team1Power / (team1Power + team2Power)` și folosește-l ca probabilitate de posesie în fiecare minut, nu 50/50.
2. **Big chances (key moments):** introdu un nou concept — "big chance" = oportunitate care **trebuie** să producă o animație (vs. "shot wide" obișnuit care doar adăugă un text). 
   - Per meci, simulează `bigChancesHome` și `bigChancesAway` în funcție de `attackRating` per echipă (~2-5 per echipă pentru meciuri normale, mai puține pentru defensive duels).
   - Distribuie minutele big chance-urilor random peste 90 minute.
   - Pentru fiecare big chance: rolează outcome (GOAL / SAVE / MISS) ponderat de `finishingRating` al atacantului vs `gkRating` al GK-ului advers.
   - Pentru fiecare big chance, generează animație apelând `goalAnimationService.generate(...)` cu `outcome` corect.

3. **Animation engine support pentru SAVE/MISS pe OPEN_PLAY:** [GoalAnimationService.generate()](src/main/java/com/footballmanagergamesimulator/service/GoalAnimationService.java#L101) presupune mereu GOAL. Trebuie parametrizată:
   - Adaugă param `String outcome` (sau `enum Outcome`).
   - Calculul `goalY` și `gkDiveTargetY` la linia 121 + 140 trebuie ramificat:
     - `GOAL`: `goalY` în 40-60 (în poartă), `gkDiveTargetY = goalY ± 14` (greșit).
     - `SAVE`: `goalY` lângă GK, `gkDiveTargetY = goalY ± 2` (aproape ball).
     - `MISS`: `goalY` în afară (15-25 sau 75-85), `gkDiveTargetY = 50` (statică).
   - Frame-urile 135-150 (zbor minge) → pentru `MISS`, mingea trece DE poarta, nu intră. Pentru `SAVE`, mingea ricoșează (poate adăuga frame-uri 150-180 de rebound).

4. **Frontend filtering** după setting (deja descris în 3a).

~250 LOC. Risc medium-high (touch atât engine, cât și simulare match).

---

### Etapa 4 — Constrângeri pe zone (atacanții nu coboară să apere)

**Backend doar.** Modifică [GoalAnimationService.java](src/main/java/com/footballmanagergamesimulator/service/GoalAnimationService.java).

**Problema actuală:** linia 243-249 — `ball attraction` atrage orice non-GK player la mai puțin de 35 unități de minge. Atacanții echipei defensive sfârșesc în jumătatea proprie.

**Ce de făcut:**
1. Introduce per-jucător `homeZoneMinX` și `homeZoneMaxX` în funcție de `group` (0=GK, 1=DEF, 2=MID, 3=ATK) + apartenență (atacant vs defensiv):
   - Fundaș echipă defensivă: `x ∈ [team.gkSide ± 5, 60]`.
   - Atacant echipă defensivă: `x ∈ [40, 80]` — stă sus pentru counter.
   - Atacant echipă atacantă: `x ∈ [50, 95]`.
   - Fundaș echipă atacantă: `x ∈ [team.gkSide ± 5, 60]`.
2. La calculul forței de atracție (linia 244), aplică multiplier pe outliers:
   - `group === 3 && isDefendingTeam` → force × 0.25
   - `group === 1 && !isDefendingTeam` → force × 0.4
3. La `runX/runY` (linia 258-263), exclude `group <= 1` complet — fundașii nu fac runs înainte.
4. Clampează la final `finalX` în interval-ul `[homeZoneMinX, homeZoneMaxX]` per jucător.

**Validare:** rulează manual mai multe meciuri cu echipe inegale. Atacanții echipei defensive trebuie să rămână în jumătatea opusă; fundașii echipei atacante nu trebuie să treacă jumătatea.

~80 LOC. Risc medium (poate degrada vizual scenarii deja "feel good").

---

### Etapa 5 — Lineup overlay la început

**Frontend doar (backend trimite deja datele).**

**Variante:**

**A. Inline (primele ~45 frame-uri):** jucătorii apar gradual de pe margine în formație, text "STARTING LINE UP" suprapus. Simplu, dar fuzionează cu faza.

**B. Modal preliminar separat (recomandat):**
- Când frontend-ul primește datele goal animation, înainte de `startGoalAnimationPlayback`, randează un layout static cu cele 2 formații (ca în poza pe care a trimis-o user-ul).
- Layout-ul:
  - Fundal split cu cele 2 culori primary ale echipelor.
  - Două coloane (sau una sub alta pe mobile): teren stilizat per echipă cu cele 11 poziții.
  - Sub fiecare cerc: număr + nume de familie.
  - Header: nume echipă + logo (dacă există).
  - "STARTING LINE UP" mare la mijloc + buton "Continue" sau countdown 2s.
- User apasă SPACE sau așteaptă timeout → cross-fade către animația propriu-zisă.

**Implementare:**
1. Adăugare nouă state în [AppComponent](src/app/app.component.ts): `showLineupPreview = false`.
2. În `playGoalAnimation(minute)`, înainte de `this.showGoalAnimation = true`, dacă user setting permite: `this.showLineupPreview = true; setTimeout(() => { this.showLineupPreview = false; this.showGoalAnimation = true; }, 2000);`
3. În HTML, nou block `*ngIf="showLineupPreview"` cu layout-ul descris.
4. Folosește `goalAnimationData.players` pentru a popula cele 22 de poziții, grupate pe `teamId`.

**Optional:** setting "skip lineup preview" în same Manager Responsibilities ca să sară direct la fază.

~150 LOC. Risc low-medium (state machine în frontend, dar self-contained).

---

### Etapa 6 — Mai multe tipuri de faze random

**Backend doar.** Refactor major în [GoalAnimationService.java](src/main/java/com/footballmanagergamesimulator/service/GoalAnimationService.java).

**Scenarii noi propuse pe OPEN_PLAY:**
1. **BUILD_UP** — 4-5 pase, atacul construit din spate (curent — comportamentul default).
2. **QUICK_COUNTER** — 2-3 pase rapide, ball recovery în jumătate proprie, scorer alergat în adâncime cu pasă lungă diagonală.
3. **LONG_BALL** — 1 pasă lungă de la GK/DC direct la atacant; scorer recepționează în adâncime.
4. **INDIVIDUAL_DRIBBLE** — carrier ia mingea la 30-40m, fără pase (sau o singură pasă scurtă), șut individual. Frame-uri suplimentare pentru efect "dribble through" — carrier face mișcări sinusoidale între keyframes.
5. **CROSS_AND_FINISH** — pasă din bandă (ML/MR/AML/AMR) în careu; scorer (ST/AMC) finalizează cu cap sau șut din volej.
6. **ONE_TWO** — scorer ↔ assister da-și-ia rapid lângă careu; finalizare din interior.
7. **LONG_RANGE_SHOT** — assister lateral, scorer mijlocaș, șut de la 20-25 metri.

**Selecția scenariu** în funcție de:
- Poziția scorer-ului:
  - `ST` / `AMC` → ponderare mare pentru CROSS_AND_FINISH, ONE_TWO, QUICK_COUNTER.
  - `MC` / `DM` → LONG_RANGE_SHOT, INDIVIDUAL_DRIBBLE.
  - `DC` → strict CORNER_HEADER (nou tip pentru goluri din corner — vezi linia 300 din LiveMatchSimulationService unde există deja `08% chance of goal from corner`).
- Random weighted: BUILD_UP cel mai frecvent (40%), restul împărțit ~10% fiecare.
- Diferența de power: echipă slabă vs tare → boost pe QUICK_COUNTER + LONG_BALL.

**Implementare:**
1. Adaugă enum sau String constants `OpenPlayScenario { BUILD_UP, QUICK_COUNTER, ... }`.
2. Refactor `buildPassChain(...)` (chemată la linia 102) într-un selector + 7 sub-metode private (`buildBuildUpChain`, `buildQuickCounterChain`, etc.) care întorc atât chain-ul cât și keyframe-urile (pentru INDIVIDUAL_DRIBBLE keyframe-urile sunt diferite — scorer-ul se mișcă singur, ceilalți stau cuminți).
3. `generateKeyframes` (linia 110) trebuie parametrizat de scenariu — nu mai e un singur set fix.

**Frontend rămâne identic** — consumă același `events` + `frames` format.

~400 LOC. Risc medium-high.

---

### Etapa 7 — Polish (opțional, ultimă)

Idei vizuale care nu sunt blocante:
- **Ball trail:** desenează cercuri semi-transparente pe ultimele 5 poziții ale mingii.
- **Tricou cu dungi:** desenează un dreptunghi vertical (secondary color) peste cercul jucătorului pentru a sugera dungi.
- **Direcția jucătorului:** săgeată mică (sau orientare) bazată pe diferența între pozițiile frame-ului curent și precedent.
- **Confetti pe gol:** la `outcome === "GOAL"` în ultimele frame-uri, emite particule colorate cu kit-urile echipei marcatoare.
- **Replay button:** după ce animația se termină, opțiunea de a o relua.

~200 LOC total. Risc low (totul în frontend).

---

## 3. Follow-up-uri rămase din review-urile anterioare

Aceste puncte au fost identificate în review-urile anterioare ale refactorului `CurrentUserService` / `TeamAccessGuard` și **nu au fost încă rezolvate**:

### 3.1 Endpoint-uri inbox cu access denied → 403 explicit
**Fișier:** [InboxController.java](src/main/java/com/footballmanagergamesimulator/controller/InboxController.java)

`getMessages`, `getMessagesBySeason`, `getUnreadCount` întorc azi `[]` / `0` când guard-ul refuză accesul. Frontend-ul nu poate distinge între "inbox gol" și "n-ai voie". Ar trebui 403 cu `{success: false, message: "Not allowed"}`.

`markRead` și `markAllRead` deja întorc 403 (rezolvat anterior).

### 3.2 `TeamAccessGuard` în restul controllerilor
**Fișiere:** orice controller care primește `{teamId}` ca `@PathVariable`.

Endpoint-urile cu teamId din alte controllere (`TacticController`, `TrainingController`, `ContractController`, `ManagerController.responsibilities/{teamId}`, `TeamController`, `TransferOfferController`) sunt **wide-open** — orice user logat poate citi/modifica orice echipă.

Pasul 3 din planul original: extinde `TeamAccessGuard` în `TransferOfferController` și `TrainingController` ca pilot, apoi propagă mai larg.

### 3.3 `UserContext` ca shim
**Fișier:** [UserContext.java](src/main/java/com/footballmanagergamesimulator/user/UserContext.java)

E hibrid acum — metodele bazate pe `HttpServletRequest` doar deleagă la `CurrentUserService`; restul (`getAllHumanTeamIds`, `isHumanTeam`, `isAnyUserFired`) sunt despre roster-ul global.

Atac doar după ce mai multe controllere folosesc deja `CurrentUserService` direct (i.e. după 3.2). Două opțiuni: șterge-l complet și mută cele 3 metode în altă parte, sau redenumește-l `UserDirectory` și șterge metodele cu `HttpServletRequest`.

### 3.4 Test controller pentru InboxController
**Fișier nou:** `src/test/java/com/footballmanagergamesimulator/controller/InboxControllerTest.java`

`MockMvc` care să asserteze:
- `markRead` cu mesaj propriu → 200 + `success: true`
- `markRead` cu mesaj altei echipe → 403
- `markRead` cu id inexistent → 404
- `markAllRead` cu teamId al user-ului → 200 + `marked: <count>`
- `markAllRead` cu teamId al altcuiva → 403

Acoperă golul de coverage la nivel HTTP, nu doar service.

---

## 4. Fișiere & locații importante

### Backend (animation engine)
- [GoalAnimationService.java](src/main/java/com/footballmanagergamesimulator/service/GoalAnimationService.java) (1170 LOC) — engine-ul principal.
- [GoalAnimationData.java](src/main/java/com/footballmanagergamesimulator/frontend/GoalAnimationData.java) — DTO trimis la frontend.
- [TeamKitResolver.java](src/main/java/com/footballmanagergamesimulator/service/TeamKitResolver.java) (~190 LOC) — selector culori.
- [LiveMatchSimulationService.java](src/main/java/com/footballmanagergamesimulator/service/LiveMatchSimulationService.java) (~580 LOC) — generează timeline-ul de meci + invocă engine-ul.

### Frontend (animation engine)
- [app.component.ts](../footballmanager-frontend-test/src/app/app.component.ts) → `renderGoalFrame` (linia ~584+), `playGoalAnimation` (~534), `startGoalAnimationPlayback` (~558), `drawPitch` (~746+), `numberColorFor` (helper nou ~736).

### Manager Responsibilities (pentru Etapa 3a)
- Backend: [ManagerController.java:170+](src/main/java/com/footballmanagergamesimulator/controller/ManagerController.java) — `/managers/responsibilities/{teamId}`.
- Frontend: caută componenta care apelează `responsibilities` (probabil în squad sau profile).

---

## 5. Comenzi utile

```bash
# Compile backend
cd /Users/ciprian.amza/IdeaProjects/footballmanager-backend-
mvn -q compile

# Run targeted tests
mvn test -Dtest='CurrentUserServiceTest,TeamAccessGuardTest'

# Frontend type check
cd /Users/ciprian.amza/Downloads/footballmanager-frontend-test
node_modules/.bin/tsc --noEmit -p tsconfig.app.json

# Frontend dev server (visual verification)
cd /Users/ciprian.amza/Downloads/footballmanager-frontend-test
npm start
```

---

## 6. Recomandare pentru sesiunea următoare

**Cea mai bună ordine de continuare:**

1. **Etapa 2** (nume jucători + ball carrier) — mic, vizibil imediat, low risk. ~30 min.
2. **Etapa 3a** (setting plumbing) — independent de restul, livrează valoare singur. ~45 min.
3. **Etapa 4** (zone constraints) — fix pe bug real. ~1 oră.
4. **Etapa 3b** (big chances + shot disparity) — cea mai mare bucată de cod. ~3-4 ore. Atac doar după ce e clar că 3a merge.
5. **Etapa 5** (lineup overlay) — feature nou. ~2 ore.
6. **Etapa 6** (scenarii faze) — mai mult cod, risc mare. ~4-6 ore.
7. **Etapa 7** (polish) — la final.

**Paralel** cu Etapele 2-4, follow-up-urile din secțiunea 3:
- 3.1 (403 explicit pe inbox GET-uri) — ~15 min.
- 3.2 (TeamAccessGuard în TransferOffer + Training) — ~1 oră.
- 3.4 (InboxControllerTest cu MockMvc) — ~45 min.

`UserContext` shim cleanup (3.3) — doar după ce 3.2 e propagat.

---

## 7. Decizii confirmate de user

- Realismul pe șuturi: dacă o echipă e mult mai slabă → 12-3, nu 12-10.
- Big chances generate proactiv în `LiveMatchSimulationService` (opțiunea 2), nu derivate din `MatchEvent` (opțiunea 1).
- Ordinea etapelor: 1 → 2 → 3 → 4 → 5 → 6 → 7 (planul propus).
- Setting Match Highlights Level: 3 nivele (NONE / GOALS_ONLY / KEY_MOMENTS), key moments include și ratări/salvări cu animație.
- Portar: în culori diferite atât față de echipa proprie, cât și față de cea adversă.
