# Adâncimea modelului tactic — design (2026-05-31)

Spec de implementare (fără cod). Scop: ca **tactica + formația să conteze diferit**, prin contre
dependente de adversar (piatră-foarfece-hârtie), nu prin mai multe butoane monotone.
Cuplaj **simplu** ales: contrele cheamă axele tactice ale adversarului (ex. directness), **fără**
agregare nouă de atribute din lot (pace/wingeri) — aceea rămâne o extensie opțională (§7).

---

## 1. Diagnostic (confirmat în cod, nu speculație)

Sunt **două** degenerări independente.

### 1.1 Formațiile sunt literalmente identice
`TacticService.FORMATIONS` are doar 8 buckete de poziție (GK/DL/DC/DR/ML/MC/MR/ST):
```
451  = GK1 DL1 DC2 DR1 ML1 MC3 MR1 ST1
4231 = GK1 DL1 DC2 DR1 ML1 MC3 MR1 ST1   ← identic cu 451
4141 = GK1 DL1 DC2 DR1 ML1 MC3 MR1 ST1   ← identic cu 451 și 4231
```
Un 4-2-3-1 n-are DM+AM, un 4-1-4-1 n-are DM solitar — totul se aplatizează în „MC3".
→ `TacticalScoreService.profile()` produce profil atac/apărare **identic** → scor identic.
**Alegerea formației e cosmetică** între formații cu același multiset de poziții.

### 1.2 „Control" e un câștig gratis (colțul degenerat)
În `TacticalScoreService.matchup()`:
```
effDef1  = def1 · (1 − biasStrength·bias1) · (1 + controlStrength·control1)
openness = baseOpenness · (1 + opennessStrength·avg(risk)) · (1 − controlOpennessStrength·avg(control))
```
`control` îți **crește apărarea** ȘI **scade openness** (mai puține goluri → mai multă varianță,
bun pt. outsider). **Nu atinge propriul atac → niciun cost.** `risk` are deja o dependență de
adversar naturală (mai multe goluri = bun pt. favorit, rău pt. outsider), dar `control` nu.
Toate axele sunt **monotone** și **niciuna n-are un contra dependent de adversar** → optimul
analitic e un **colț fix** ("Keep Ball + Always"), indiferent de cine-i în față (§16.5 din handoff).

### 1.3 Principiul fix-ului
Adâncimea vine din **contre dependente de adversar**: fiecare proprietate trebuie să fie tare
împotriva unei setări a adversarului și slabă împotriva alteia → niciun optim universal.

---

## 2. Notație

Constante de putere (knob-uri config; nume actuale între paranteze):
`bs`=biasStrength, `cs`=controlStrength, `os`=opennessStrength, `cos`=controlOpennessStrength,
`e`=ratioExponent.

Baseline actual (`matchup()`):
```
effAtt1 = att1 · (1 + bs·bias1)
effDef1 = def1 · (1 − bs·bias1) · (1 + cs·control1)
openness = baseOpenness · (1 + os·avg(risk)) · (1 − cos·avg(control))
xg1 = openness · effAtt1^e / (effAtt1^e + effDef2^e) · homeBonus     // simetric pt. xg2
```

Axe noi derivate din setări (toate **0 la neutru**, ca tactica goală să rămână no-op):
- `line ∈ [−1, +1]` — Deep −1, Standard 0, High +1
- `press ∈ [0, 1]` — Low 0, Standard 0.5, High 1
- `width ∈ [−1, +1]` — Narrow −1, Balanced 0, Wide +1
- `directness ∈ [0, 1]` — din **passing** (Short 0, Normal 0.4, Long/Direct 1); expus separat în
  vector (azi e topit în `risk` împreună cu tempo). Necesar pt. contra liniei înalte.

---

## 3. Strat 1 — cost de atac pentru control (mic, fără FE/DB)

Singura schimbare: `control` îți scade și propriul atac (stând adânc cedezi teren).
```
effAtt1 = att1 · (1 + bs·bias1) · (1 − ca·control1)        // ca = controlAttackCost ≈ 0.15
```
Efect: `control` devine **trade-off** (+apărare, −atac propriu, −openness) în loc de câștig gratis.
Outsiderul încă beneficiază de openness redus, dar plătește în amenințare → optimul nu mai e pinned
la max-control. **Acesta singur sparge colțul din §1.2** și e validabil imediat cu fuzz, fără
câmpuri noi. Recomandat ca prim pas independent.

---

## 4. Strat 2 — proprietăți noi cu contre reale

Trei setări noi, fiecare un contra dependent de adversar. **Cuplaj simplu**: termenii cheamă axele
tactice ale adversarului (`directness2`, `width2`), nu atribute din lotul advers.

### 4.1 Defensive Line (Deep / Standard / High)
Linia înaltă comprimă spațiul (suport ofensiv mic) dar e **vulnerabilă la joc direct**; linia
adâncă e sigură dar **cedează teren** (atacul tău scade).
```
lineVuln1 = 1 + lhv · max(0, line1) · directness2           // >1 când ești sus ȘI ei joacă direct
effDef1  /= lineVuln1                                        // apărarea ta slăbește doar la acest matchup
effAtt1  *= (1 + lhs · line1)                                // sus = +mic teritoriu; adânc = −mic
```
`lhv`=lineHeightVulnerability ≈ 0.25, `lhs`=lineHeightSupport ≈ 0.08.
**Contra**: linie înaltă superbă — DOAR dacă adversarul nu joacă direct; altfel pedepsită. Linie
adâncă sigură dar reduce amenințarea proprie. Matchup prin `directness2`. ✓

### 4.2 Pressing (Low / Standard / High)
Presingul îți reduce eficiența atacului advers (disturbi construcția) dar costă stamina și
**amplifică** vulnerabilitatea liniei înalte (spațiu în spate).
```
effAtt2   *= (1 − pd · press1)                               // disturbi buildup-ul lor
effAtt1   *= (1 − psc · press1)                              // proxy fatigue pe engine instant
lineVuln1 += plc · press1 · max(0, line1) · directness2      // press + linie sus + ei direct = expus
```
`pd`=pressDisruption ≈ 0.18, `psc`=pressStaminaCost ≈ 0.06, `plc`=pressLineCompound ≈ 0.15.
**Contra**: presingul sufocă o echipă de posesie, dar press + linie înaltă contra unei echipe
directe = catastrofă; plus taxă de oboseală. (Cuplajul complet cu stamina trăiește deja în engine-ul
**live**; engine-ul instant folosește proxy-ul mic `psc`.) ✓

### 4.3 Width (Narrow / Balanced / Wide)
Width-vs-width pur, fără optim universal.
```
effAtt1 *= (1 + ws · width1 · (−width2))                     // larg bate îngust; îngust bate larg
```
`ws`=widthStrength ≈ 0.12.
**Contra**: tu larg + ei îngust (`−width2>0`) → bonus; tu îngust + ei larg → penalizare; ambii la
fel → se anulează. Piatră-foarfece-hârtie curat. ✓

> Toți termenii noi sunt **multiplicativi în jurul lui 1** și **0 la neutru** → o tactică implicită
> reproduce exact scorul de azi (determinism + ITurile structurale nemodificate).

---

## 5. Strat 3 — poziții fine (face formațiile distincte)

Cea mai mare suprafață. Rezolvă §1.1.
- **Buckete noi de poziție** + `attackShare` shipped (propunere): `DM` 0.30, `AMC` 0.75,
  `AML`/`AMR` 0.85, `WBL`/`WBR` 0.55. (DL/DR rămân 0.45; ML/MR 0.80; MC 0.50.)
- **Spargi `TacticService.FORMATIONS`**: 4231 → `DM`+`AMC`; 4141 → `DM`+ bandă MC; 451 → 3×MC etc.
  Atunci cele trei formații din §1.1 produc profiluri diferite.
- **Touch points suplimentare** (de aici efortul): matricea de **familiaritate** natural→folosit
  (`MatchEngineConfig.PlayerValue.familiarityPenalty` — pozițiile noi au nevoie de intrări),
  **profilurile de ponderi pe atribut** per poziție (`PlayerValue.weights`), **selecția XI**
  (`TacticController.selectStarterSlots` — sortarea slot-urilor prin `getValueForTacticDisplay`),
  grid-ul vizual de formație (`TacticService.getFormationGridIndices`), FE (afișare poziții noi).
- **Risc**: jucătorii existenți au `position` în cele 8 buckete vechi → un DM/AM e populat din MC
  prin familiaritate (fallback blând), nu rupe nimic, dar profilurile trebuie verificate.

Recomandare: Strat 3 **separat**, după ce 1+2 sunt validate, fiindcă schimbă numerele peste tot
(orice modificare de poziție/share mută scorurile — vezi determinism §46 handoff).

---

## 6. Config — toate în `MatchEngineConfig.TacticalModel`

Knob-uri scalare noi (default-uri propuse):
| Knob | Default | Rol |
|---|---|---|
| `controlAttackCost` | 0.15 | Strat 1 — costul de atac al lui control |
| `lineHeightSupport` | 0.08 | suport ofensiv al liniei (semn = line) |
| `lineHeightVulnerability` | 0.25 | cât slăbește apărarea linia sus vs joc direct |
| `pressDisruption` | 0.18 | cât scade press-ul atacul advers |
| `pressStaminaCost` | 0.06 | proxy fatigue pe engine instant |
| `pressLineCompound` | 0.15 | amplificarea liniei înalte de către press |
| `widthStrength` | 0.12 | tăria contra-ului width-vs-width |

Hărți categorical→numeric noi (cu resolver override→shipped→0, ca cele existente):
- `lineHeightAxis`: {Deep −1, Standard 0, High 1}
- `pressAxis`: {Low 0, Standard 0.5, High 1}
- `widthAxis`: {Narrow −1, Balanced 0, Wide 1}
- `passingDirectness`: {Short 0, Normal 0.4, Long 1, Direct 1} — pt. `directness2`

(Strat 3) intrări noi în `attackShare` pentru pozițiile fine (§5).

---

## 7. Cuplaj de lot — extensie opțională (NEinclusă acum)

Aleasă varianta „simplu întâi". Dacă se dorește mai mult realism ulterior:
- **Linie înaltă vs PACE real**: `lineVuln1 *= (1 + lhPace · paceAdvers)` unde `paceAdvers` =
  pace agregat al atacanților adversi (atribut deja ponderat în `PlayerValueService`).
- **Width vs wingeri**: `widthStrength` modulat de crossing/heading propriu și wingeri.
Cere o **agregare squad-level nouă** (pace/crossing pe primul 11) expusă din `PlayerValueService`.
De adăugat ca Strat 2.5, după ce contrele pe axe sunt validate.

---

## 8. Touch points (rezumat)

| Fișier | Schimbare |
|---|---|
| `model/PersonalizedTactic` | + câmpuri `defensiveLine`, `pressing`, `width` (String, default null→neutru) |
| `service/TacticalScoreService` | `vector()` citește 3 setări noi + `directness`; `matchup()` + termenii §3–4; `TacticVector` extins cu 4 câmpuri (neutru = 0) |
| `config/MatchEngineConfig.TacticalModel` | knob-uri §6 + hărți + resolver-e + (Strat 3) `attackShare` |
| `controller/TacticController` | endpoint save/load tactică propagă noile câmpuri |
| `frontend/PersonalizedTacticView` (+ DTO) | expune noile câmpuri |
| `service/ManagerTacticService` | grid-ul de candidați crește (vezi §9) |
| `service/BestTacticService`, `TacticSimulationService` | spațiul de combinații crește (§9) |
| Frontend (repo separat) | 3 dropdown-uri noi pe pagina de tactică + `/tactics` (+ Strat 3: poziții noi pe pitch) |

---

## 9. Explozie combinatorială — de gestionat explicit

Azi: 5 mentality × 4 timeWasting × 3 possession × 3 passing × 5 tempo = **900** setări.
Cu 3 axe noi (3×3×3): **24.300** setări × 15 formații = ~364k combinații.
- **Ranking analitic** (`rankAllTactics`, `chooseTactic`): `panelExpectedPoints` e pură aritmetică
  (panel 3 × grid Poisson 8×8) → 364k evaluări sunt ms-fast, **OK** (de măsurat, dar fără simulare).
- **Unelte SIMULATE** (`Team442SeasonPointsFuzzIT`, `/tactics` tab „Simulat"): simulează N sezoane —
  **NU** pot rula 24.300 combinații. Mitigare: fixează formația (deja) + **sweep o axă** sau
  **eșantionează**, nu grid complet. De ajustat în uneltele simulate.
- Alternativ pt. AI: **coordinate ascent** (urcă o axă pe rând) în loc de exhaustiv, dacă analiticul
  devine lent.

---

## 10. Plan de validare

- **`TacticLandscapeProbeTest`** (extins): assert că tempo/passing **și** noile axe contribuie
  non-zero la metrică; **optimul diferă în funcție de adversar** (contrele produc optimuri diferite
  vs un panel direct/posesie/larg/îngust) — exact ce lipsea.
- **`TeamTacticRankingFuzzIT`** / **`Team442SeasonPointsFuzzIT`**: spread-ul de puncte se lărgește,
  topul **non-degenerat** (nu mai e „Much Higher/Keep Ball/Always" universal).
- **Determinism**: tactică implicită = scor identic cu azi (`LiveMatchPinnedScorelineTest`,
  `EngineDeterminismIT` rămân verzi); ITurile structurale (matchday/season/European/KO/two-leg) nu
  se ating de numere (toți termenii noi = no-op la neutru).
- **`ChampionshipPredictionFuzzIT`**: re-rulat după Strat 2 — favoritul tot competitiv, dar tactica
  bună a adversarilor AI ar trebui să introducă varianță realistă.

---

## 11. Secvențiere recomandată

1. **Strat 1** (cost-control) — mic, izolat, sparge colțul; validare fuzz. ~o sesiune.
2. **Strat 2** (Line/Pressing/Width + config + FE) — carnea; contre reale. + ajustare unelte simulate (§9).
3. **Strat 2.5** (cuplaj de lot §7) — opțional, dacă vrei realism pace/wingeri.
4. **Strat 3** (poziții fine) — separat, cel mai lat; face formațiile să conteze.

Decizie ortogonală deja notată în handoff §16.5: după unificarea engine-ului, **coboară
`ratioExponent` 2.0→~1.5** și re-validează pe 15–20 sezoane, ca valoarea lotului să nu strivească
complet contribuția tactică nou-adăugată.
