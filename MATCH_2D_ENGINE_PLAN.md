# Plan: Engine vizual 2D (și opțional 3D) pentru meciurile live

Data: 2026-07-30. Bazat pe starea curentă a backend-ului (`LiveMatchSession` + Animation V3) și a frontend-ului (`footballmanager-frontend-test`).

**Status: DESIGN SIGNED OFF (Codex, AI_HANDOFF.md rev. 6).** Runda 1 de
implementare = Faza 0 + Faza 1, exclusiv frontend, în slice-uri reviewabile
separat. Faza 2 e blocată pe contractul delta versionat + test gates (vezi
§„Test gates înainte de Faza 2"). Întrebările deschise de la final sunt
rezolvate — decizii marcate inline cu **[DECIS]**.

## 0. Ce avem deja (fundația e surprinzător de bună)

**Backend:**
- Engine live tick pe minut: `LiveMatchSession.advanceUntil()` / `tickOneMinute()` — un branch pe minut (atac, posesie, fault, offside, buildup). **Fără poziții continue** — `PlayerMatchState` nu are x/y.
- **Animation V3 = sursa pozițională existentă**: fiecare moment de șut (GOAL/SAVE/MISS/BLOCKED, ~10–25 pe meci) e compilat în `GoalAnimationData` cu `frames[]` complete: `ballX/ballY` + `positions[][]` pentru toți jucătorii de pe teren, coordonate normalizate **0–100 pe ambele axe**, 30 fps, 150–600 frame-uri per clip. Include kit-uri, direcție (`homeAttacksRight`), events (PASS/SHOT/...), ordine deterministă, persistat în `match_animation_recipe`.
- Ancore de formație în același spațiu 0–100: `FrameCompiler.basePosition()` (GK(4,50) … ST(72,50)) + `TacticService.FORMATION_GRID_INDICES` (grid 5×6, 16 formații).
- API polling complet: `GET /match/live/{key}/state`, `POST /advance?untilMinute=N`, `/substitute`, `/commit`. Fără WebSocket/SSE (nu e nevoie pentru v1 — polling-ul pe minut există deja).
- `GET /match/animation/livePreview?teamId1&teamId2` — sandbox perfect pentru prototipare fără să joci un matchday.

**Frontend (Angular 15, fără librării de grafică):**
- Există deja un **renderer canvas 2D funcțional** pentru clipurile de gol: `renderGoalFrame()` + `drawPitch()` în `app.component.ts` (~:2261–2700) — teren cu dungi, careuri, jucători cu culori de kit + numere + contrast automat, coliziune etichete, trail pentru minge, linii de pasă/șut, confetti. Rulează pe `setInterval` 33ms.
- Tot viewer-ul live e inline în `AppComponent` (~1300 linii din 2777): scoreboard + ticker text + panou stamina/facts, timer `setInterval` cu viteze 1x/2x/4x/8x, modal substituții, suspense pe șuturi, extra-time sintetic cosmetic.
- Pitch-uri CSS-grid există la lineup preview și tactics4 (drag&drop).

**Concluzia cheie:** contractul de date pentru 2D există deja (frames 0–100 + kit-uri + direcție). Ce lipsește: (a) poziții ÎNTRE momentele de șut, (b) o componentă dedicată de renderer, (c) un singur ceas de redare.

---

## Faza 0 — Refactor FE pregătitor (fără feature nou)

Scop: scoatem live match-ul din `AppComponent` ca să avem unde pune renderer-ul.

1. `LiveMatchService` (nou): deține session key, polling `/advance`, `/substitute`, `/commit`, persistența localStorage (`fm_liveMatchKey`), recovery. Expune `state$: BehaviorSubject<LiveMatchState>`.
2. Interfețe TypeScript pentru DTO-uri (`LiveMatchData`, `GoalAnimationData`, `LiveMatchMinute`, `PlayerStaminaInfo`) — azi totul e `any`.
3. `<app-live-match>` (modalul actual mutat) + `<app-match-pitch>` (renderer-ul, gol deocamdată — primește clipul de gol curent și îl redă, adică mutăm codul canvas existent: `drawPitch`, `renderGoalFrame`, `numberColorFor`, label-collision, trail, confetti).
   **[DECIS]** `<app-match-pitch>` consumă un **frame normalizat renderer-neutral**
   (`ball` + per jucător: id, team, număr/kit, x/y), nu `GoalAnimationData` direct.
   Sinteza ambient și clipurile V3 se adaptează amândouă în acest model. Detaliile
   de desen rămân în spatele componentei (o singură implementare Canvas cu discuri
   în runda asta); fără PixiJS, fără asset-uri sprite, fără enum-uri speculative.
4. **Un singur ceas**: înlocuim cele două `setInterval`-uri (ceas de meci + clip 33ms) cu un loop `requestAnimationFrame` cu time-scale legat de `liveMatchSpeed`. Toate punctele de pauză existente (sub-modal, suspense, lineup preview, stop timer) devin `paused` pe același loop.

Risc mic, pur mecanic, zero backend. Este condiția ca restul fazelor să nu devină spaghetti.

## Faza 1 — 2D „highlights+" (fără schimbări de backend)

Scop: un teren 2D permanent vizibil pe toată durata meciului, ca în screenshot-ul de referință, folosind DOAR datele existente.

1. `<app-match-pitch>` devine pane persistent în modalul live (între scoreboard și feed; comentariul devine coloană laterală / bandă jos). Canvas responsive (scale după container, aspect ~1.6:1).
2. **Stare „ambient" între momente** — sintetizată client-side:
   - Plasăm 11v11 pe ancorele de formație (`homeFormation`/`awayFormation` + tabel de ancore copiat din `FrameCompiler.basePosition`, oglindit pentru echipa care atacă spre stânga).
   - Micro-mișcare idle (patrol sinusoidal ±1–2 unități, seeded pe playerId) ca terenul să „respire".
   - **Bias de posesie**: din evenimentul minutei curente (`eventType`/`teamId` din timeline) mutăm blocul echipei în posesie cu +5–10 unități spre atac, mingea plutește în zona corespunzătoare (buildup → mijloc, corner → colț, foul → punctul faultului aproximat pe zonă). Tranziții lerp de ~1s.
   - Cartonașe roșii / substituții se reflectă imediat (scoatem/băgăm discul, folosind `homePitch/awayPitch` care sunt deja actualizate de server).
3. **Splice clipuri V3**: la minutele cu `canonicalAnimations`, tranziție (lerp 0.5s din pozițiile ambient în frame-ul 0 al clipului), redăm clipul integral pe același canvas, apoi lerp înapoi în ambient.
   **[DECIS]** Modalul separat de goal-animation se retrage ca implementare de
   playback; capabilitatea „replay zoom" rămâne, dar prezintă **aceeași instanță
   `<app-match-pitch>`** într-un container mărit, pe **același ceas RAF** — nu
   există un al doilea canvas, al doilea frame index sau al doilea timer.
   Replay/zoom/view-switch nu apelează niciodată `/advance` sau `/commit`.
4. Mini-radar + overlay-uri: scor/minut deja există; adăugăm marker de eveniment pe teren (icon corner/fault/offside la locație aproximată).
5. **Setare user**: `matchViewMode: TEXT | PITCH_2D`. **[DECIS]** În runda 1
   doar în localStorage — persistența prin manager responsibilities e out of
   scope. TEXT rămâne default-ul/fallback-ul sigur; PITCH_2D e strict o
   prezentare a stării deja returnate de server.
6. Prototipare pe ruta `/animation-preview` (folosește `livePreview` — nu blochează un save real).

Rezultat: experiență FM-style „comprehensive highlights" — teren viu permanent, cu momentele reale animate de engine. Estimare: cea mai mare fază pe FE, dar fără nicio linie de backend.

**[DECIS] Rendering: Canvas 2D nativ, discuri colorate în culorile kitului cu
numere de tricou.** Exercitează contractele reale (frame, interpolare, scalare,
direcție, replay) fără dependență de producție de asset-uri. Sprite-urile
(facing/run state) se pot deriva ulterior din poziții consecutive + events sau
din render hints opționale — dar nu au voie să modeleze sau să extindă
contractul de backend în Faza 1.

## Faza 2 — Poziții ambient generate de backend (fidelitate reală)

Scop: între momente, mișcarea să reflecte ce simulează engine-ul, nu o sinteză FE.
**BLOCATĂ până când test gates-urile de mai jos sunt executabile.**

1. Nou în backend: `AmbientSegmentCompiler` (în pachetul `animation/`) — reutilizează steering-ul bounded + validatorul din `FrameCompiler`, dar cu buget mic: pentru fiecare minut generează un segment scurt (~30–60 frames) plecând de la branch-ul ales de `tickOneMinute`:
   - `tickAttackBranch` fără șut / buildup → circulație de pase în treimea corespunzătoare (pattern-uri simple derivate din `ShortPassingSequencePattern`).
   - posesie → pase în propriul bloc; foul → converge + freeze pe punctul faultului; corner planificat (`scheduleCorners`) → așezare la corner; offside → through ball întrerupt cu steag.
   **[DECIS] Graniță strictă de puritate:** compilerul e o proiecție pură din
   input imutabil/vizibil în checkpoint, cu **RNG local nou** — nu consumă și nu
   mutează niciodată `CheckpointRandom`-ul sesiunii, starea jucătorilor,
   counterele, timeline-ul, goal slots sau starea de commit. Exact **un segment
   ambient per minut de engine** (nu per eveniment de timeline — un minut poate
   avea 0/1/mai multe evenimente).
2. **[DECIS] Contract: delta versionat pe `/advance`, opt-in — NU frames pe
   `LiveMatchMinute`, NU endpoint GET separat** (round trip în plus + races cu
   advancement/retry/recovery). DTO response-only:
   `LiveMatchAdvanceDelta { baseMinute, currentMinute, eventsAdded,
   ambientSegments, canonicalAnimationsAdded, statePatch }`, fiecare
   `AmbientSegmentData` cu `minute` + `ambientVersion`. Reguli:
   - `POST /advance` actual continuă să returneze `LiveMatchData` complet pentru
     FE-ul existent; FE-ul nou cere explicit reprezentarea delta (o singură
     convenție: endpoint versionat / media type / `response=delta`).
   - `GET /state` rămâne contractul complet de bootstrap/resync.
   - Delta se aplică doar când `baseMinute` == minutul curent al clientului;
     la gap sau răspuns stale, FE-ul îl aruncă și face resync prin `/state`.
   - Deprecarea reprezentării legacy = schimbare separată, ulterioară.
   - Conversia pe delta se face în Faza 2 (frame-urile cumulative fac forma
     actuală de răspuns nesustenabilă).
3. **[DECIS] Determinism:** derivare **domain-separated** — `AmbientSeed.derive(...)`
   (sau salt ambient explicit), NU refolosim literal namespace-ul
   `AnimationSeed.derive` (al treilea parametru e `slotIndex`; un minut acolo
   fără salt corelează accidental spațiile de seed ambient/moment). Frames și
   recipes ambient rămân **nepersistate**, explicit în afara invariantului de
   checkpoint canonic exact. `ambientVersion` se întoarce cu segmentul;
   versiunile vechi de generator se îngheață. Dacă promitem regenerare
   byte-identică peste un deployment, versiunea selectată se pinuiește pentru
   sesiunea în curs (un singur scalar în live context/checkpoint — asta nu
   transformă frame-urile ambient în recipes durabile).
   Inputurile branch-ului necesare compilerului (minim: kind, team,
   direcție/zonă, snapshot-ul on-pitch post-tick) trebuie să fie
   **reconstructibile din checkpoint-ul exact**; dacă timeline + checkpoint nu
   ajung, checkpointăm un mic `AmbientSegmentSpec` versionat — nu inferăm
   ulterior din stare mutabilă curentă. Un `/advance` pierdut + cold recovery +
   același request trebuie să regenereze același segment fără să rejoace minutul
   de engine.
4. FE: `<app-match-pitch>` preferă `ambientSegments` când există, cu fallback pe sinteza din Faza 1 (compatibilitate + meciuri vechi).

### Test gates înainte de Faza 2 (Codex, executabile)

1. **Non-interferență de prezentare**: ambient on vs off → scor, sloturi
   canonice ordonate, contributori, substituții, cartonașe, statistici,
   câmpuri de checkpoint canonic și rânduri finale comise identice (exceptând
   metadata ambient-only); starea RNG-ului live din checkpoint neatinsă de
   compilare/serializare ambient.
2. **Retry/recovery**: `/advance(untilMinute=N)` duplicat, răspuns pierdut,
   cold recovery la N → zero duplicate de event/slot/animație și același
   segment ambient pentru N; jocul continuat după recovery păstrează aceleași
   goluri canonice viitoare și aceiași marcatori eligibili.
3. **Protocol delta**: delte stale/duplicate/out-of-order inofensive;
   mismatch `baseMinute` → resync `/state`; momentele canonice din același
   minut rămân ordonate după `slotIndex`; un segment ambient per minut de
   engine.
4. **Mărime/graniță checkpoint**: `checkpointJson` și `match_animation_recipe`
   nu conțin arrays de frame-uri ambient; metadata minimă version/spec face
   round-trip.
5. **Izolare commit**: replay/zoom/view-switch/frame generation nu apelează
   niciodată `/advance` sau `/commit`; testele existente de idempotency,
   rollback și two-concurrent-commit rămân în suita obligatorie.
6. **Compatibilitate**: serializarea legacy full `/advance` și comportamentul
   cu flag-ul de match-plan off rămân neschimbate cât timp delta e opt-in.

Regression gate minim: `LiveMatchPinnedScorelineTest`,
`CanonicalInstantLiveE2ETest`, `LiveMatchCanonicalPlanBindingTest`,
`MatchPlanIdempotencyTest`, `MatchPlanRollbackTest`,
`MatchPlanConcurrencyTest`,
`MatchdayCoordinatorCanonicalCommitConcurrencyTest`,
`AnimationV3LiveWiringTest` + noile teste de puritate/delta/recovery.

## Faza 3 — Polish „full match" 2D

- Viteze: la 4x/8x sărim redarea ambient (doar reposition lerp), clipurile de momente se redau accelerat sau doar la highlights-level KEY_MOMENTS.
- Extra time real în loc de shootout-ul sintetic cosmetic din FE (backend-ul are deja `MatchPeriod` ET în V3; de adăugat momente pentru penalty shootout — pattern `PENALTY` există).
- Stamina vizuală (disc mai pal / indicator), heatmap post-meci din pozițiile acumulate (refolosim SVG heatmap din `player-analytics`).
- Sunet/atmosferă opțional; replay-uri (clipurile V3 sunt persistate — buton „Replay highlights" post-meci gratis).

## Faza 4 (opțional) — 3D

Același contract de date (x/y 0–100 per frame) face 3D-ul un renderer alternativ, nu un engine nou:
1. **Three.js** într-o a treia implementare a `<app-match-pitch>` (interfață comună `MatchRenderer { loadFrame(state), render(dt) }`): plan texturat pentru teren, jucători ca sprite-uri billboard (v1) sau modele low-poly cu 2–3 animații (idle/run/kick, v2), cameră broadcast (urmărește mingea cu damping) + camere alternative.
2. Backend minor: `FrameCompiler` are deja zborurile mingii ca Bézier — de expus `ballZ` per frame (azi se pierde la proiecția 2D) ca lobii/crosele să arate corect în 3D. Câmp opțional în `frames[]`, backward-compatible.
3. Realist ca efort: 3D „prezentabil" (screenshot 2) = de câteva ori efortul întregului 2D. Recomand abia după ce Faza 2 e stabilă; alternativ un „2.5D" (proiecție izometrică a acelorași date, sprite-uri cu umbre) dă 80% din efect la 20% din cost.

## Ordine recomandată & dependențe

```
Faza 0 (FE refactor) → Faza 1 (2D client-only) → [feedback] → Faza 2 (ambient BE) → Faza 3 (polish) → Faza 4 (3D, opțional)
```

- Faza 1 e livrabilă și valoroasă singură — decidem după ea dacă Faza 2 merită.
- Nimic nu atinge scorurile/rezultatele: planul canonic rămâne sursa de adevăr (pinned mode), tot ce facem e prezentare.
- Multiplayer: merge din prima — totul e per-session polling deja.

## Întrebări deschise — REZOLVATE (Codex, AI_HANDOFF.md rev. 6)

1. ~~Sprite vs discuri~~ → **discuri numerotate în culorile kitului**; interfață
   renderer-neutral pregătită acum, pipeline de sprite-uri nu.
2. ~~ambientFrames inline vs endpoint separat~~ → **delta versionat opt-in pe
   `/advance` cu `ambientSegments`**; `GET /state` rămâne bootstrap/resync.
3. ~~Modal goal-animation~~ → **capabilitatea replay-zoom rămâne, implementarea
   separată de playback se retrage** (aceeași instanță `<app-match-pitch>`,
   același ceas RAF).

## Runda 1 — slice-uri de implementare (Faza 0 + Faza 1, doar frontend)

Cerință Codex: slice-uri reviewabile separat; refactorul trebuie să păstreze
comportamentul actual de polling/pauză/substituții/recovery/commit ÎNAINTE de
activarea vizualizării pitch.

1. **Slice A — extracție fără schimbare de comportament**: interfețe TS pentru
   DTO-uri, `LiveMatchService`, `<app-live-match>`, `<app-match-pitch>` (redă
   doar clipurile de gol existente, cod canvas mutat). Verificare: fluxul live
   actual identic (advance, suspense, sub-modal, resume din localStorage,
   commit).
2. **Slice B — ceas unic RAF**: înlocuirea celor două `setInterval`-uri cu un
   singur loop RAF cu time-scale (`liveMatchSpeed`); toate punctele de pauză
   mapate pe același loop.
3. **Slice C — pitch persistent + ambient client-side**: ancore de formație,
   patrol idle seeded, bias de posesie din evenimentul minutei, reflectare
   imediată sub/roșu, splice V3 inline cu lerp; toggle `matchViewMode`
   (localStorage, default TEXT).
4. **Slice D — replay zoom + retragerea modalului vechi** + polish (marker
   evenimente pe teren, layout comentariu lateral).
