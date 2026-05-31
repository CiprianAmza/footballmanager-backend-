# Plan: viața extra-sportivă a antrenorului + sistem de PATRONI (2026-05-31)

O categorie nouă FE („Boardroom" / „My Life") cu sub-pagini: averea antrenorilor (filtru doar humani),
active personale (case/mașini/acțiuni — pot cumpăra acțiuni la cluburi), a deveni **patron** al unui club
(și atunci nu mai poate antrena în aceeași competiție cu acel club), iar ca patron: investește/retrage
bani la club, demite/angajează antrenori, face transferuri, decide dacă antrenorul poate face transferuri,
și **intervine în primul 11** blocând poziții (antrenorul human vede sloturile blocate, alege doar pe cele
libere, „ask assistant" lucrează STRICT pe cele libere). Presa întreabă antrenorul dacă-l deranjează și
patronul dacă i se pare normal → dinamică **aroganță patron / umilință antrenor**.

## Ce există (reutilizăm)
- **`Human.wealth`** (avere, există!), `careerEarnings`, `managerReputation`, `offensiveAbility/
  defensiveAbility`, `teamId`, `retired`, `typeId` (MANAGER_TYPE=4). `ManagerProfileComponent` afișează deja
  avere/earnings.
- **Finanțe club**: `Team.totalFinances/transferBudget/salaryBudget/debt/boardConfidence`;
  `FinanceService.recordTransaction/recordExpense`; **`processOwnerInjection`** (șablon perfect pt.
  invest/withdraw); `FinancialRecord` (audit).
- **Hire/fire**: `JobOfferService.acceptOffer/acceptJob` (demite AI→free agent, asignează, inbox);
  `ensureTeamHasManager`; `ManagerCareerService` (reputație, sacking). Găsire antrenor:
  `findAllByTeamIdAndTypeId(teamId, MANAGER_TYPE)`.
- **Transfer**: `TransferOfferController.executeTransfer` (mișcă bani + bugete).
- **XI**: `PersonalizedTactic.first11` (JSON FormationData {playerId, position, positionIndex});
  `selectStarterSlots` (selecție XI + ask-assistant).
- **Presă/board**: `PressConference`(+Service, topic pipe-delimited, moraleEffect/reputationEffect),
  `ManagerInbox` (categorii), `BoardRequest`, `SeasonObjective(Service)`.
- Securitate: TODO existent în `WebSecurityConfig` „once user-team ownership is implemented" → semnal că
  ownership era anticipat.

## Ce trebuie construit
- **Ownership** (Human ↔ Team): câmp `ownedTeamId`/`Ownership` entity (cine deține ce). Regulă: nu poți
  antrena un club din ACEEAȘI competiție cu unul deținut.
- **Active personale**: entitate `Asset` (tip: HOUSE/CAR/SHARES, valoare, club pt. acțiuni) legată de Human.
- **Acțiuni la cluburi + piață**: `ClubShareholding` (humanId, teamId, %) — pas spre patronat (>50% →
  patron); **cumpărare/vânzare acțiuni**, **vânzare/cumpărare cluburi întregi**, și **vânzare de necesitate**
  (la probleme financiare patronul scoate acțiuni la vânzare ca să strângă lichidități).
- **Piața antrenorilor (patron decide antrenorul)**: patronul **ofertează antrenori**, inclusiv pe cei
  care AU deja echipă — plătind **clauza de reziliere** (`Human.releaseClause` nou pe contractul de
  antrenor) ca să-i ia. Reutilizează fluxul `JobOfferService` (oferta → demite AI/eliberează → asignează).
- **Matricea de PERMISIUNI antrenor** (⭐ cea mai importantă — vezi secțiune dedicată): patronul human
  setează granular ce are voie antrenorul (transferuri, vânzări, alegere XI/poziții, tactică, antrenament,
  contracte, etc.). `Team.coachCanTransfer` devine un caz particular al acestui set.
- **XI locking**: flag `lockedSlots`/`lockedPlayerIds` pe `PersonalizedTactic` (sau pe FormationData
  `isLocked`) setat de patron; respectat la selecție + save + ask-assistant. (Sub-caz al permisiunilor.)
- **Metrici dinamică**: `Human.ownerArrogance` + `coachHumiliation` (double) alimentate de presă/intervenții.
- **FE**: categoria Boardroom + sub-pagini + lock-icons pe pagina de tactică + ecran de permisiuni.

## ⭐ Matricea de PERMISIUNI antrenor (mecanica centrală)
Dacă patronul e **human**, el decide câtă autonomie are antrenorul clubului. O entitate/embed
`CoachPermissions` per (team) — un set de toggle-uri pe care patronul le configurează; antrenorul (human
sau AI) e constrâns de ele peste tot în joc. Server-side enforced (nu doar UI).

Toggle-uri propuse (extensibile, config-driven):
| Permisiune | OFF înseamnă |
|---|---|
| `canBuyPlayers` | antrenorul nu poate cumpăra (doar patronul aduce) |
| `canSellPlayers` | nu poate vinde/lista jucători |
| `canNegotiateContracts` | nu poate prelungi/renegocia |
| `canPickXI` (+ `lockedSlots`) | sloturile blocate de patron sunt fixe; restul libere |
| `canChangeFormationTactics` | nu poate schimba formația/instrucțiunile (patronul le fixează) |
| `canSetTraining` | nu poate schimba antrenamentul |
| `canSetSetPieces` | nu poate alege executanții loviturilor |
| `transferBudgetCap` | plafon de buget peste care nu poate trece |

**Enforcement (puncte de hook)**: fiecare acțiune a antrenorului verifică permisiunea înainte:
- transfer (`TransferOfferController.executeTransfer` + listare) → `canBuy/SellPlayers`, `transferBudgetCap`.
- `TacticController.saveFormation` → `canChangeFormationTactics` (respinge schimbarea formației/
  instrucțiunilor), `canPickXI`/`lockedSlots` (respinge schimbarea sloturilor blocate), `canSetSetPieces`.
- antrenament (`TrainingService`/controller) → `canSetTraining`.
- contracte (`ContractController`) → `canNegotiateContracts`.
- AI: dacă antrenorul e AI, AI-ul respectă aceleași permisiuni (nu inițiază acțiuni interzise) — astfel
  patronul human controlează și un antrenor AI.
**FE**: ecran „Coach Control" (patron) cu toggle-urile; pe paginile antrenorului, acțiunile interzise apar
**dezactivate** cu tooltip „Restricționat de patron". Pe tactică: sloturi blocate + (dacă
`canChangeFormationTactics=false`) controalele de tactică read-only.
**Presă**: cu cât patronul restricționează mai mult → `ownerArrogance↑` / `coachHumiliation↑` (vezi §presă).

## Piața antrenorilor (patronul alege/aduce antrenorul)
- Patronul vede candidați (reutilizează `JobOfferService.getAvailableJobs`/listă antrenori liberi + cei cu
  echipă) și **ofertează**: dacă antrenorul are contract, plătește **clauza de reziliere**
  (`Human.releaseClause` nou; din `totalFinances` club) → fluxul `acceptOffer/acceptJob` mută antrenorul.
- Demitere: patronul concediază antrenorul curent (reutilizează pattern `ManagerCareerService`/sacking →
  free agent + inbox + eventual compensație din finanțe).
- `ensureTeamHasManager` umple cu AI dacă rămâne vacant.

## Mecanica XI-locking (cea mai novică — detaliu)
1. Patronul (pe sub-pagina lui) alege jucători pe sloturi → se salvează `lockedSlots: [{positionIndex,
   playerId}]` pe `PersonalizedTactic` al echipei.
2. `TacticController.saveFormation` (path antrenor): **respinge/ignoră** modificările pe sloturile blocate
   (jucătorul blocat rămâne). Validare server-side (nu doar UI).
3. `selectStarterSlots`/`getBestEleven`: pre-plasează jucătorii blocați în sloturile lor; umple DOAR
   sloturile libere cu logica de aptitudine. **Ask-assistant** primește doar sloturile libere → sugerează
   strict acolo.
4. **FE tactică (toate paginile)**: sloturile blocate apar cu **lock-icon** + nemutabile (drag/drop dezactivat
   pe ele); restul normal. Reutilizează `allowedIndexes` + un nou `lockedIndexes`.
5. Engine: jucătorii blocați intră normal în valoarea de meci (sunt titulari forțați) — niciun cod de scor
   nou; doar selecția se schimbă.

## Dinamica presă: aroganță / umilință
- După meci/intervenție: `PressConferenceService` generează întrebări noi:
  - către **antrenor**: „Te deranjează că patronul a impus primul 11?" → răspuns mută `coachHumiliation`
    + moral antrenor.
  - către **patron**: „E normal să treci peste antrenor?" → răspuns mută `ownerArrogance` + reputație.
- `ManagerInbox` categorii noi: `OWNER_DECISION`, `COACH_COMPLAINT`. La aroganță/umilință mari: efecte
  (antrenorul cere plecarea / moral lot scade / board request „reduce interference").
- Config `MatchEngineConfig.Boardroom` (sau `boardroom.yml`): magnitudinea delta-urilor aroganță/umilință,
  pragurile de consecințe, % acțiuni pt. patronat, randamentul investiției — tunabil.

## Cum „contează" (efecte în joc)
- **Invest/withdraw**: prin `FinanceService.recordTransaction/Expense` → schimbă `totalFinances`/
  `transferBudget` (deja cablat în restul jocului).
- **Lock XI**: schimbă cine joacă → afectează valoarea echipei (prin `PlayerValueService`) → scorul, automat.
- **Aroganță/umilință**: alimentează moralul (→ valoarea de meci prin `moraleFactor`) + reputația +
  board requests + plecări antrenor. Config-driven.
- **Acțiuni la club**: dividende (venit pasiv via FinanceService) + drum spre patronat.

## Faze (livrare incrementală — feature MARE)
1. **Faza 1 — Avere & profil** (mic, mult există): pagina Boardroom hub + sub-pagina „Wealth" (listă
   humani cu filtru, avere/earnings/reputație). Reutilizează `Human.wealth` + `ManagerProfileComponent`.
2. **Faza 2 — Active personale & acțiuni & piață**: entitate `Asset` + `ClubShareholding` + UI
   cumpărare/vânzare acțiuni + dividende + **vânzare/cumpărare cluburi** + **vânzare de necesitate**
   (lichidare la probleme financiare). Patronat = prag de acțiuni (>50%).
3. **Faza 3 — Patronat & control club**: `Ownership` + reguli (nu antrena în aceeași competiție) +
   invest/withdraw (`FinanceService`) + **piața antrenorilor** (ofertă + clauză de reziliere + hire/fire
   prin `JobOfferService`).
4. **Faza 4 — Matricea de permisiuni** (⭐ centrală): `CoachPermissions` + enforcement server-side în toate
   punctele (transfer/tactică/antrenament/contracte/set-pieces) + AI respectă permisiunile + ecran FE
   „Coach Control" + acțiuni dezactivate pe paginile antrenorului.
5. **Faza 5 — XI locking** (sub-caz al permisiunilor): `lockedSlots` pe tactică + `selectStarterSlots`/
   ask-assistant doar pe libere + lock-icons FE pe toate paginile de tactică + validare save.
6. **Faza 6 — Dinamica presă aroganță/umilință**: întrebări noi + metrici + consecințe (board/morale/
   plecări antrenor), scalate cu cât de mult restricționează patronul.

## Fișiere cheie (build)
- Backend nou: `model/Ownership`, `model/Asset`, `model/ClubShareholding`, `model/CoachPermissions`;
  `service/OwnershipService`, `service/AssetService`, `service/ShareMarketService`,
  `service/CoachPermissionService` (gardianul verificat în toate hook-urile);
  extensii `FinanceService` (invest/withdraw + dividende + lichidare), `JobOfferService` (clauză de
  reziliere antrenor), `TacticController` (lockedSlots + permisiuni + validare), `selectStarterSlots`,
  `TransferOfferController`/`ContractController`/`TrainingService` (verificări permisiuni),
  `PressConferenceService` (+întrebări), `Human` (+ownerArrogance/coachHumiliation/releaseClause),
  `Team` (legare owner), `PersonalizedTactic` (+lockedSlots).
- Config: `MatchEngineConfig.Boardroom` (delta aroganță/umilință, praguri patronat %, randament acțiuni,
  set default de permisiuni).
- FE: rute `/boardroom`, `/boardroom/wealth`, `/boardroom/assets`, `/boardroom/ownership`,
  `/boardroom/coach-control`; lock-icons + drag-disable pe paginile de tactică.

## Verificare
- Unit/IT: ownership rules (nu antrenezi clubul deținut din aceeași competiție); invest/withdraw mută
  finanțele; saveFormation respinge schimbarea sloturilor blocate; selectStarterSlots respectă lock +
  ask-assistant doar pe libere; presă mută aroganță/umilință. `mvn verify` verde (aditiv).
- FE `ng build` verde; smoke: lock-icons apar, drag blocat pe sloturi blocate, invest/withdraw reflectat.

## Decizii de confirmat
- Patronat: prin **% acțiuni** (>50%) sau flag direct „become owner"? (Recomand acțiuni → patronat ca prag.)
- Câte cluburi poate deține un human? (1 vs mai multe.)
- XI locking: patronul blochează **jucător→slot** (poziție fixă) sau doar „acest jucător trebuie să joace"
  (oriunde)? (Recomand jucător→slot, mai clar pe UI.)
- Ordinea de implementare a fazelor (recomand 1→6; 4+5 sunt cele care leagă de munca de tactică).
- Clauza de reziliere antrenor: câmp nou pe Human, plătită din finanțele clubului ofertant — OK?
- Permisiunile se aplică ȘI antrenorului AI (patronul human controlează un AI), nu doar uman — confirmat?
- Granularitatea permisiunilor: setul propus (8 toggle-uri) e suficient, sau adaugi/scoți?
