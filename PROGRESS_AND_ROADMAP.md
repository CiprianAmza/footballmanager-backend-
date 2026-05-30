# Progres & Roadmap — Football Manager Simulator

Document de sinteză care cuprinde tot ce s-a livrat și ce mai poate fi îmbunătățit.

---

## 0. Context proiect

### Path-uri
- **Backend:** `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-/`
  - Java + Spring Boot, H2 in-memory DB, JPA/Hibernate, Lombok.
  - Build cu Maven (`mvn` direct, fără wrapper).
- **Frontend:** `/Users/ciprian.amza/Downloads/footballmanager-frontend-test/`
  - Angular 15 + Bootstrap 5.
  - Build/serve cu `npm start`.

### Branch curent
- `master`. Există modificări netracate de la sesiunile recente — verifică `git status` înainte de commit.

### Servicii cheie
- Backend la `http://localhost:8086` (vezi `urlApp` din [app.component.ts](../footballmanager-frontend-test/src/app/app.component.ts)).
- Frontend dev server la `http://localhost:4200`.

### Documente conexe (acelaşi folder)
- `MATCH_ENGINE_HANDOFF.md` — plan inițial al refactorului motor de animație.
- `HANDOFF.md` — context general transferabil.
- `MEMORY.md` (`~/.claude/projects/.../memory/`) — auto-memory persistent cross-session.

---

## 1. Manager career & job offers

### Livrat
- **Manager profile page** ([ManagerController.java](src/main/java/com/footballmanagergamesimulator/controller/ManagerController.java)):
  - `/managers/profile/{managerId}` întoarce istoric complet + snapshot live al sezonului curent (calculat din `TeamCompetitionDetail`).
  - Snapshot live e marcat `inProgress: true` → frontend îl randează cu badge "LIVE" + stil distinct.
  - Bug fix la `recordManagerHistory` în [CompetitionController.java](src/main/java/com/footballmanagergamesimulator/controller/CompetitionController.java): filtrare după typeId league (1 sau 3) — nu mai ia `findFirst()` pe orice competiție (putea prinde o cupă).
  - Bug fix `selectEleven`: portarii backup nu mai intră în primii 11 (cauza vizualului "două portare").
  - Helper `ensureTeamHasManager` în [HumanService.java](src/main/java/com/footballmanagergamesimulator/service/HumanService.java) — spawn AI manager automat când o echipă rămâne fără manager (după resign/transfer).

- **Resign + job offers** ([CareerController.java](src/main/java/com/footballmanagergamesimulator/controller/CareerController.java), [JobOfferService.java](src/main/java/com/footballmanagergamesimulator/service/JobOfferService.java)):
  - Endpoint `/career/me` returnează identitatea (managerId, teamId, fired flag, hasPendingOffer).
  - Endpoint `/career/resign` detașează managerul + scrie inbox confirmation + spawn AI manager pentru echipa veche.
  - Endpoint `/career/offers/{id}/accept|decline` cu lifecycle PENDING/ACCEPTED/DECLINED/EXPIRED.
  - `JobOfferService.generateOpportunisticOffer` — generator weighted pe baza power-ratio echipă vs reputație.
  - Game **paused** când există ofertă pending (atât pe `/game/advance` cât și pe `/play` legacy).
  - Inbox primește un mesaj cu category `JOB_OFFER` care conține `OFFER_ID:<n>` în content → frontend parsează și afișează buton accept/decline inline.

- **Frontend** ([app.component.ts](../footballmanager-frontend-test/src/app/app.component.ts), [career.service.ts](../footballmanager-frontend-test/src/app/services/career.service.ts)):
  - Buton "My Manager" în sidebar care navighează la `/manager-profile/:managerId`.
  - Resign button pe pagina manager-profile (vizibil doar pentru manager-ul propriu activ).
  - Modal confirm înainte de resign.
  - Banner top-of-page când există oferte pending. Click → modal cu toate ofertele + accept/decline.
  - Continue button disabled cu eticheta "OFFER PENDING" cât timp există oferte nerezolvate.
  - Inbox: rânduri JOB_OFFER cu accept/decline buttons inline + sumar contract.

- **Admin** ([AdminController.java](src/main/java/com/footballmanagergamesimulator/controller/AdminController.java)):
  - Toggle "auto-offers ON/OFF" (`/admin/jobOffers/setEnabled`).
  - Buton "force on next advance" (`/admin/jobOffers/forceNext`).
  - Generator manual cu picker pe user + team-id (`/admin/jobOffers/generateNow`).
  - UI complet în pagina admin (segmented controls + form).

---

## 2. Match engine — 7 Etape

### Etapa 1: Culorile echipelor pe animații
- Nou: [TeamKitResolver.java](src/main/java/com/footballmanagergamesimulator/service/TeamKitResolver.java) — mapează culori CSS pe 11 familii, alege kit-uri pentru ambele echipe + GK distinct (paleta de 6 culori, evită familia outfield-ului).
- `GoalAnimationData` are acum `scoringTeamKit` și `defendingTeamKit` cu `outfieldPrimary/Secondary/Border` + `gkPrimary/Border`.
- Helper frontend `numberColorFor(fill)` — alb/negru pe baza luminanței, ca să nu fie text alb pe tricou galben.

### Etapa 2: Nume pe toți jucătorii + ball carrier evidențiat
- Frontend `renderGoalFrame`: nume de familie sub fiecare cerc, prioritate:
  - Marcator în reveal window (frame ≥130): bold 10px auriu + glow.
  - Ball carrier: bold 9px galben deschis.
  - Restul: 7px alb semi-transparent.
- Pre-pass O(n²) anti-aglomerare: dacă doi jucători au |Δx|<5 ȘI |Δy|<3, label-ul celui non-ball-carrier dispare.

### Etapa 3a: Setting Match Highlights Level
- Câmp nou `Human.matchHighlightsLevel` (NONE / GOALS_ONLY / KEY_MOMENTS), cu legacy `watchGoalHighlights` sincronizat.
- Pagina Staff — checkbox-ul vechi înlocuit cu segmented control de 3 pastile.
- Frontend cachează în `localStorage` ca să nu hit-uiească backend la fiecare animație.
- `playGoalAnimation` cheamă `shouldPlayAnimation(outcome)` care decide pe baza setting-ului.

### Etapa 3b: Big chances + shot disparity realism
- Nou: per-team `attackChance` care scalează cu power-ratio:
  - Echipe egale (50/50) → ~10 șuturi fiecare.
  - 75/25 → ~12 vs ~3.
- Big chances pre-alocate per echipă (1-4 pentru cea tare, 0-2 pentru cea slabă).
- Big chances forțează posesia + ATTACK branch + ALWAYS animation (chiar și pentru SAVE/MISS).
- `GoalAnimationService.generate()` extins cu param `outcome` (GOAL/SAVE/MISS) care ramifică:
  - `goalY` (în plasă / lângă GK / lateral peste bară).
  - `gkDiveTargetY` (greșit / corect / centru).
  - `shotTargetX` (100 pentru GOAL/MISS, 97 pentru SAVE).
- Calibrare goluri: ~3 goluri/meci media (în loc de ~6 inițial).
- Bug fix knockout tiebreaker: `appendKnockoutWinnerGoal` adaugă un MatchEvent "goal" la min 120 când scorul e bumped, ca să nu mai existe discrepanță între score și goluri-în-raport.

### Etapa 4: Zone constraints (atacanții nu coboară la apărare)
- Helper nou `zoneRangeX(group, isDefendingTeam)` cu band-uri X per rol:
  - Atacant echipă defensivă: [15, 65] — stă sus pentru contra.
  - Fundaș echipă atacantă: [10, 55] — nu trece de jumătate.
  - Etc.
- Ball-attraction damping pe out-of-zone: `force × 0.25` pentru atacanți defensivi, `× 0.4` pentru fundași atacanți.
- Hard clamp `finalX` pe band per jucător (cu excepție pentru ball carrier și GK în plonjon).

### Etapa 5: Lineup overlay la start meci
- Modal preliminar care apare ~2.8s înainte ca primul tick al meciului să ruleze.
- Layout: split-uri pe culori echipă, două jumătăți cu formații tactice reale.
- Grid 5 coloane per rând cu poziționare semantică:
  - 1 jucător → col 3 (centru)
  - 2 → cols 2, 4 (atacanți simetrici)
  - 3 → cols 2, 3, 4 (DC centrali)
  - 4 → cols 1, 2, 4, 5 (flat back four cu gap centru)
  - 5 → cols 1-5
- Bug fix: lineup apare o singură dată per meci (în `fetchLiveMatch`), nu înainte de fiecare animație.

### Etapa 6: Scenarii multiple de faze
- Enum `OpenPlayScenario` cu 7 valori: BUILD_UP, QUICK_COUNTER, LONG_BALL, INDIVIDUAL_DRIBBLE, CROSS_AND_FINISH, ONE_TWO, LONG_RANGE_SHOT.
- `selectScenario(scorer, assister, rng)` weighted pe baza poziției:
  - ST/AMC: build-up 25%, cross 22%, one-two 18%, counter 15%, dribble 12%, long ball 8%.
  - AML/AMR: dribble 35%, build-up 30%, cross 20%, counter 15%.
  - MC/DM: build-up 35%, long-range 35%, dribble 15%, one-two 15%.
  - DC: doar BUILD_UP.
- `buildPassChain` variază chain composition per scenariu.
- `generateKeyframes` cu helper-i `scorerKeyframe` și `assisterKeyframe` care produc trasee distincte per scenariu.
- BUILD_UP cu chain mai lung (DC → 2 mids → assister → scorer = 4 pase vizibile).
- INDIVIDUAL_DRIBBLE cu Y-swerve (32→64→38→60→50 = 4 schimbări de direcție).
- LONG_RANGE_SHOT cu scorer la x≈66 (28 unități ball travel = șut clar de la distanță).

### Etapa 7: Polish vizual
- **Ball trail**: ultimele 6 poziții cu fade alpha + shrink — pe pasele rapide se vede streak.
- **Striped shirts**: dungă verticală 4px secondary color peste cerc (excepție GK + ball carrier).
- **Goal confetti**: 60 particule cu paleta echipei marcatoare, gravitație + drag, burst de la zona porții.
- **Replay button**: după ce animația se termină, buton pentru a o rejua (curăță trail + confetti).
- **Mingea în poartă**: range Y restrâns la [45, 55] (era [40, 60] — care lăsa 40% din goluri să treacă pe lângă bare).

---

## 3. Stoppage time

- `LiveMatchData`: câmpuri `firstHalfStoppage` și `secondHalfStoppage` (random 0-5 per repriză).
- `GoalAnimationData.firstHalfStoppage` pentru ca mirror-ul să clasifice golurile din prelungiri corect.
- ThreadLocal în `GoalAnimationService` pentru a propaga `firstHalfStoppage` fără a schimba 6+ signatures.
- Bucla loop extinsă: `1..(90 + first + second)`.
- Half-time la `min == 45 + firstHalfStoppage`, full-time la `totalMinutes`.
- Frontend helper `formatMatchMinute(raw, fhs)`:
  - 1-45 → "X'"
  - prelungiri prima repriză → "45+X'"
  - a doua repriză → "X'"
  - prelungiri repriza a doua → "90+X'"
- Progress bar și HT marker calculează cu totalul real.

---

## 4. Stats & data fixes

### Bug fixes pe statistici
- **M=2 după o etapă**: placeholder Scorer rows (`teamScore=-1`) erau numărate în `/stats/competition/{id}/{season}`. Acum filtrate.
- **Assists 0**: `setAssists()` nu se apela nicăieri. Helper nou `getAssistWeight` ponderat pe creative positions (ML/MR/MC > DC/ST). ~75% din goluri primesc un assist.
- **Atacanții nu marchează destul**: `scorer.setRating()` lipsea înainte de `getDifferentValueForScoringBasedOnPosition` → rating² = 0 → fallback la 0.1 pentru toți. Fix: setat rating din Human/PlayerView înainte de weighting. Acum striker rating 80 e ~8× mai probabil să marcheze decât DC rating 80.

### Media prediction
- Adăugate 10 formații lipsă pe frontend (4231, 4141, 4411, 4321, 4222, 3421, 532, 5212, 541, 3511) cu layout grid 5×6 per scenariu.

---

## 5. Refactoring & security

### Refactor `CurrentUserService` + `TeamAccessGuard`
- [CurrentUserService.java](src/main/java/com/footballmanagergamesimulator/user/CurrentUserService.java) — centralizează parsarea `X-User-Id` header. 4 metode: `getUserIdOrNull`, `getUserOrNull` (null-safe), `requireUser`, `requireTeamId` (throwing).
- [TeamAccessGuard.java](src/main/java/com/footballmanagergamesimulator/user/TeamAccessGuard.java) — autorizare pe baza apartenenței. `canAccessTeam`, `resolveInboxTeamId` (cu fallback la `lastTeamId` pentru fired users), `canAccessInboxMessage`.
- `UserContext.java` devine thin wrapper care deleagă la `CurrentUserService` (păstrează API-ul pentru callerii existenți).

### Security fixes
- **Inbox previously wide-open**: `markRead(messageId)` nu verifica deloc ownership-ul; `getMessages/{teamId}` permitea citirea oricărei echipe. Acum:
  - `markRead` returnează 403 dacă mesajul nu aparține echipei user-ului.
  - `markAllRead` returnează 403 dacă teamId-ul nu e al user-ului.
  - GET-urile (`getMessages`, `getMessagesBySeason`, `getUnreadCount`) folosesc `resolveInboxTeamId` care întoarce `null` pentru access denied → endpoint-ul răspunde cu `[]`/`0`.

### Teste noi
- [CurrentUserServiceTest.java](src/test/java/com/footballmanagergamesimulator/user/CurrentUserServiceTest.java) — 6 cazuri.
- [TeamAccessGuardTest.java](src/test/java/com/footballmanagergamesimulator/user/TeamAccessGuardTest.java) — 9 cazuri (rolurile principale + edge cases pe inbox access).

---

## 6. Posibile îmbunătățiri viitoare

### Securitate
1. **`TeamAccessGuard` propagat pe toate controllerele cu `{teamId}` în path** — momentan e aplicat doar pe InboxController. Endpoint-urile din `TransferOfferController`, `TrainingController`, `ContractController`, `ManagerController.responsibilities/{teamId}`, `TeamController` etc. sunt încă wide-open. Risc: orice user logat poate citi/modifica orice echipă.
2. **Inbox GET-uri → 403 explicit** când access guard refuză (în loc de `[]` / `0`). Frontend-ul nu poate distinge "inbox gol" de "n-ai voie".
3. **InboxControllerTest cu MockMvc** — coverage HTTP pentru 403/404/200 pe markRead/markAllRead.
4. **`UserContext` shim cleanup** — momentan e hibrid (deleagă request methods la CurrentUserService, păstrează metodele roster-globale local). De atacat doar după ce toate controllerele folosesc `CurrentUserService` direct.

### Match engine
5. **Audio**: efecte sonore pentru gol / fluier de pauză / fluier final. WebAudio API, sample-uri scurte.
6. **Animații 3D / SVG pe tricou**: înlocuirea cercurilor cu shirt SVG real (cu dungi orizontale, numere stilizate). Bibliotecă: `react-soccer-jersey` sau echivalent vanilla.
7. **Stamina / fatigue**: jucătorii obosesc spre final, mișcare patrol amplitude scade.
8. **Reactii regizate la cap**: pentru CROSS_AND_FINISH la finalizare cu cap, un frame anume cu scorer-ul "sărind" (Y oscilează scurt).
9. **Camera zoom/pan**: în loc de pitch static, camera urmează acțiunea (focus pe ball carrier).
10. **Replay-uri din unghi diferit**: după gol, opțiunea "watch from another angle" — pitch rotit sau zoom-it.
11. **Player movement persistence**: actualmente keyframes sunt re-generate per animație. Dacă vrei continuitate (un jucător care la min 20 era la (60, 40) trebuie să fie acolo când începe min 21), nu există.
12. **Big chance tracking**: live commentary să spună explicit "BIG CHANCE!" pentru momentele forțate.
13. **Substituiri vizibile în animații**: dacă scorer-ul a fost adus pe teren la min 70 prin schimbare, animație de "intrare pe teren" la momentul respectiv.
14. **Multi-language**: comentariile sunt hard-coded în engleză. Extragere într-un fișier de localizare.
14a. **`pace` attribute neutilizat în motor** — observat după Faza 1+2 stamina. Echipa cu pace mediu mai bun ar trebui (a) să "alerge mai mulți km" — vizibil ca decay stamina mai redus pentru jucători rapizi la tempo înalt, (b) să influențeze probabilitatea de a recupera o minge / a câștiga un duel de viteză. Tactic tempo trebuie să fie input — tempo înalt × pace bun = avantaj; tempo înalt × pace slab = drain rapid de stamină + pierdere de dueluri. La fel cum `stamina` și `naturalFitness` sunt deja cablate în `LiveMatchSession.applyStaminaTick()` și `pickWeightedAttacker()`, `pace` ar trebui inclus în formulele de cost stamina (când tempo > nominal) și în picking-ul de attacker/defender (factor pe weight). Mențiune: `pace` există ca atribut în `PlayerSkills` dar nu e citit nicăieri în `LiveMatchSimulationService`.

### UX
15. **Notificare sonoră / vibrație** când vine o ofertă de muncă.
16. **Buton "save preferred lineup"** pe modal-ul lineup preview ca să poți planifica vizual.
17. **Heatmap/touch map per jucător** după meci — afișează zonele unde a fost activ.
18. **Player stats overlay** pe animație — în reveal window, sub nume apare "Goals: X | Assists: Y | Rating: Z".
19. **Compare animations**: dacă două goluri identice ca tip (ex. ambele CROSS_AND_FINISH), opțiune de a le vedea side-by-side.
20. **Animație simplificată pentru mobil**: detect device pixel ratio, reduce particule și frame rate.

### Data & analytics
21. **Big chance conversion stats per jucător**: nu doar gol/assist, ci și "big chances created", "big chances scored".
22. **Heat map per echipă pe sezon**: cu agregare animation positions.
23. **xG calculation**: din big chances + șuturi normale, computeaz expected goals per meci. Compare cu actual.
24. **Manager career achievements**: badge-uri auto-acordate (primul trofeu, 100 goluri marcate sub manager X, etc).

### Backend cleanup
25. **`Round.humanTeamId` redundant**: shouldn't exist anymore. User.teamId este source of truth. De șters cu migrare.
26. **`watchGoalHighlights` boolean legacy**: se sincronizează cu `matchHighlightsLevel`. De șters după ce save-urile vechi expiră.
27. **Cup brackets regenerare**: codul de `regenerateAllCupBrackets` e fragil când structura competițiilor se schimbă. Refactor cu state machine explicit.
28. **Match simulation defensive lookups**: am aplicat la fault tactic1/tactic2 ("442" fallback). Există probabil alte locuri cu `.get(0)` pe liste potențial goale.
29. **`processMatchHumanTeam` foarte lung** — 200+ linii. Spart pe metode (computeTactic, runLiveEngine, runInstantSim, postMatch).
30. **GoalAnimationService dimensiune**: 1300+ linii. Spart pe scenario classes (un OpenPlayScenarioGenerator per fiecare scenariu).

### Frontend cleanup
31. **`app.component.ts` monolitic** — 1100+ linii cu logica goal animation + live match + lineup preview + offers + save/load. Extras în componente separate (`<app-goal-animation>`, `<app-live-match>`, `<app-lineup-preview>`).
32. **CSS în app.component.css devine mare** — 1000+ linii. SCSS module per feature ar fi mai curat.
33. **Lipsesc tests** pe frontend. Cel puțin testare snapshot pe `lineupRows`, `formatMatchMinute`, `numberColorFor` ar fi ușor de adăugat.

---

## 7. Comenzi rapide

```bash
# Compile backend
cd /Users/ciprian.amza/IdeaProjects/footballmanager-backend-
mvn -q compile

# Tests
mvn test -Dtest='CurrentUserServiceTest,TeamAccessGuardTest'

# Frontend type check
cd /Users/ciprian.amza/Downloads/footballmanager-frontend-test
node_modules/.bin/tsc --noEmit -p tsconfig.app.json

# Frontend dev server
cd /Users/ciprian.amza/Downloads/footballmanager-frontend-test
npm start
```

---

## 8. Fișiere principale modificate / create

### Backend
- `controller/CareerController.java` — endpoint-uri career flow.
- `controller/CompetitionController.java` — score persistence, knockout fix, defensive lookups.
- `controller/ManagerController.java` — profile cu live snapshot.
- `controller/StatsController.java` — placeholder filter, assists fix.
- `controller/InboxController.java` — security 403.
- `controller/AdminController.java` — job offer admin.
- `service/GoalAnimationService.java` — engine cu 7 scenarii, kits, zones, mirror cu stoppage.
- `service/LiveMatchSimulationService.java` — big chances, shot disparity, stoppage time.
- `service/HumanService.java` — `ensureTeamHasManager`.
- `service/JobOfferService.java` — generator + accept/decline.
- `service/TeamKitResolver.java` (nou) — kit picker.
- `user/CurrentUserService.java` (nou) — header parsing.
- `user/TeamAccessGuard.java` (nou) — authorization guard.
- `frontend/GoalAnimationData.java` — kits + stoppage + outcome.
- `frontend/LiveMatchData.java` — stoppage fields.
- `model/Human.java` — `matchHighlightsLevel` field.

### Frontend (Angular)
- `app.component.ts` — flow live match + animation + lineup preview + offers.
- `app.component.html` — modal-uri + sidebar shortcuts.
- `app.component.css` — toate stilurile vizuale.
- `manager-profile/manager-profile.component.*` — istoric + resign + LIVE row.
- `inbox/inbox.component.*` — JOB_OFFER accept/decline inline.
- `admin/admin.component.*` — job offer admin panel.
- `staff/staff.component.*` — segmented control Highlights Level.
- `media-prediction/media-prediction.component.ts` — 10 formații noi.
- `services/career.service.ts` (nou) — career API client.
