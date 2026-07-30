# Ghidul greutăților — Compartment Engine V1

Referință pentru `src/main/resources/compartment-scoring-weights-v1.yml`, singura sursă
activă de greutăți canonice. Fiecare secțiune explică **ce consumă valoarea**, **ce
influențează** și **ce se întâmplă dacă o miști**.

Formulele citate sunt verificate în cod, nu aproximate.

---

## Lanțul complet, de la atribut la scor

```
1. atribut brut (1..20)
        ↓ normalizare
2. scor per compartiment (ATTACK / MIDFIELD / DEFENSE) per jucător
        ↓ × poziție × rol × sarcină × familiaritate × formă × moral × potrivire-rol
3. scor final per jucător
        ↓ însumare pe cei 11 + redistribuire după mentalitate
4. atac / protecție de echipă
        ↓ penalizare de expunere defensivă
5. atac efectiv vs protecție efectivă
        ↓ matchup-exponent
6. cota q → xG = openness × q
        ↓ Gamma-Poisson (gamma-shape)
7. scorul meciului
```

Fiecare bloc din yml intervine exact într-un pas. Regula practică: **cu cât e mai jos
în lanț, cu atât efectul e mai mare și mai neliniar.**

---

## 1. `rating` — cum se transformă atributele în scor

| Cheie | Valoare | Ce face |
|---|---|---|
| `attribute-min` / `attribute-max` | 1 / 20 | Domeniul atributelor. Normalizare: `(atribut − 1) / 19`. Un jucător cu 20 dă 1.0, unul cu 1 dă 0.0. |
| `score-scale` | 100.0 | Scara scorului brut per compartiment. E doar o unitate de măsură — dacă o dublezi, dublezi toate scorurile proporțional, fără efect asupra rezultatelor. |
| `context-coefficient-min/max` | −1.0 / 1.0 | Plafonul coeficienților din `context-rules`. Limitează cât poate distorsiona tactica un singur atribut. |
| `role-fit-base` | 0.85 | Multiplicatorul unui jucător cu potrivire **zero** pe rol. Adică pierde doar 15%. |
| `role-fit-range` | 0.30 | Câștigul maxim adăugat la potrivire perfectă → `0.85 … 1.15`. |
| `fitness-floor` | 0.70 | Podeaua formei fizice. Un jucător complet epuizat tot păstrează 70% din valoare. |
| `morale-neutral` | 70.0 | Moralul la care multiplicatorul e exact 1.0. |
| `morale-slope` | 0.0004 | Panta moralului. La moral 100: `1 + 30 × 0.0004 = 1.012`. **Efect maxim ±1.2%** — practic neglijabil. |

**Formula:**
```
finalScore = contextualScore × poziție × rol × sarcină
           × familiaritate × formă × moral × potrivireRol
```

**Ce merită mișcat:**
- `role-fit-base` de la `0.85` la ~`0.70` dacă vrei ca punerea unui jucător pe rol
  nepotrivit să doară cu adevărat. Acum diferența dintre rol perfect și rol total
  greșit e de doar 30%.
- `morale-slope` dacă vrei ca moralul să conteze deloc. La `0.0004` e decorativ; `0.003`
  ar da un interval de ±9%.
- `fitness-floor` la ~`0.50` dacă vrei ca oboseala să fie o problemă reală.

---

## 2. `compartments` — ce atribute contează pentru fiecare compartiment

Trei tabele care **trebuie să însumeze 1.0** fiecare.

| Compartiment | Atributele dominante |
|---|---|
| ATTACK | Finishing 0.18, Off The Ball 0.14, Dribbling 0.12, Passing 0.10 |
| MIDFIELD | Passing 0.16, Vision 0.14, First Touch 0.11, Technique 0.10, Decisions 0.10 |
| DEFENSE | Tackling 0.16, Marking 0.15, Positioning 0.14, Anticipation 0.10, Concentration 0.10 |

**Influențează:** ce fel de jucători sunt valoroși în joc. Dacă urci `PACE` în ATTACK,
atacanții rapizi devin brusc mai scumpi și mai eficienți; scouting-ul și transferurile
se mută după ei.

**Atenție:** suma trebuie să rămână 1.0. Dacă urci ceva, coboară altceva, altfel
compartimentul devine sistematic mai mare sau mai mic decât celelalte două și
dezechilibrezi tot lanțul.

### `position-compartment-overrides`

Doar `GK` are propriul tabel DEFENSE: Handling 0.24, Reflexes 0.24, One On Ones 0.16.
Portarii nu sunt evaluați cu Tackling/Marking. Aici adaugi excepții per poziție.

---

## 3. `positions` — cât contribuie fiecare poziție la fiecare compartiment

```yaml
GK:  { attack: 0.15, midfield: 0.35, defense: 1.20 }
ST:  { attack: 1.20, midfield: 0.60, defense: 0.25 }
```

**Influențează:** structura de bază a valorii pe teren. Un ST cu atac 1.20 contribuie
de 8 ori mai mult la atacul echipei decât un GK cu 0.15.

**Efect indirect important:** aceste valori decid ce formații sunt puternice. `positions`
cu atac mare pentru AML/AMR (1.15) fac formațiile cu extreme avansate mai ofensive decât
cele cu ML/MR (0.98).

---

## 4. `roles` (24 roluri) și `duties` (3 sarcini)

Multiplicatori pe cele trei compartimente, aplicați peste poziție.

```yaml
POACHER:        { attack: 1.12, midfield: 0.90, defense: 0.88 }
SHADOW_STRIKER: { attack: 1.14, midfield: 0.98, defense: 0.86 }

ATTACK:  { attack: 1.10, midfield: 0.98, defense: 0.80 }
DEFEND:  { attack: 0.82, midfield: 0.98, defense: 1.12 }
```

**Influențează:** cât contează alegerea rolului și a sarcinii. Intervalul actual e
îngust — rolurile variază între 0.86 și 1.14, deci alegerea rolului mișcă cel mult ±14%.
Dacă vrei ca micro-managementul tactic să conteze, lărgește intervalul (ex. 0.75–1.25).

**Notă:** rolul se aplică *înmulțit* cu poziția și sarcina. Un POACHER pe ST cu sarcina
ATTACK: `1.20 × 1.12 × 1.10 = 1.478` pe atac. Efectele se compun multiplicativ.

---

## 5. `mentalities` — redistribuirea mijlocului și deschiderea meciului

Cel mai important bloc din tot fișierul.

| Cheie | Ce face |
|---|---|
| `midfield-to-attack` / `midfield-to-defense` | Cum se împarte compartimentul MIDFIELD între atac și apărare. **Trebuie să însumeze 1.0.** |
| `transfer-from` / `transfer-to` / `transfer-share` | Transfer suplimentar direct între atac și apărare, ca fracție. |
| `openness` | **Multiplicatorul total al golurilor din meci.** |

**Formula:**
```
atacFinal    = atac + mijloc × midfieldToAttack
apărareFinal = apărare + mijloc × midfieldToDefense
apoi transferul: mută transferShare din compartimentul sursă în cel destinație
```

### `openness` — butonul pentru numărul de goluri

```
xG_echipă = openness × q
```

Liniar și direct. **Dublezi openness → dublezi golurile.** Valorile curente
(3.10 / 2.89 / 2.70 / 2.43 / 2.11) dau ~2.7 goluri pe meci la echipe echilibrate.

Raporturile dintre mentalități contează la fel de mult ca valoarea absolută: acum o
echipă foarte defensivă produce cu 32% mai puține goluri decât una foarte ofensivă.

---

## 6. `work-rate` — instrucțiuni individuale de efort

```yaml
traits:
  REFUSES_DEFENSIVE_WORK:
    engagement: 0.08
    attack-multiplier: 4.5
instructions:
  DEFAULT:      { engagement: 1.00, attack-multiplier: 1.00 }
  STAY_FORWARD: { engagement: 0.30, attack-multiplier: 1.08 }
  TRACK_BACK:   { engagement: 1.15, attack-multiplier: 0.95 }
```

| Cheie | Ce face |
|---|---|
| `attack-multiplier` | Înmulțește direct atacul acelui jucător: `adjustedAttack = attack × multiplier`. |
| `engagement` | Cât participă defensiv. Contribuie la expunere cu `greutateZonă × (1 − engagement)`. La `0.08`, jucătorul lasă 92% din zona lui descoperită. |
| `ignores-defensive-instructions` | Ignoră instrucțiunile defensive ale antrenorului. |
| `forced-defensive-morale-delta` | Penalizare de moral dacă e forțat să apere. |

### Capcană verificată în cod

**Toggle-ul „Stay Forward" din joc NU folosește `instructions.STAY_FORWARD`.**
`CanonicalRuntimeInputFactory:82` îl mapează pe trăsătura `REFUSES_DEFENSIVE_WORK`,
iar trăsătura **înlocuiește complet** regula instrucțiunii în `resolveWorkBehavior`.

Blocul `instructions` (DEFAULT / STAY_FORWARD / TRACK_BACK) e citit doar dacă textul
instrucțiunii de slot e literal „stay forward" / „track back" — text pe care
`PlayerInstructionService` **nu îl produce niciodată**. Sunt configurație moartă.

Deci knob-ul real pentru „Stay Forward" e `traits.REFUSES_DEFENSIVE_WORK.attack-multiplier`.

---

## 7. `exposure` — penalizarea apărării descoperite

| Cheie | Valoare | Ce face |
|---|---|---|
| `zone-weights` | CENTRAL 1.00, HALF_SPACE 0.80, WIDE 0.60 | Cât cântărește o zonă lăsată descoperită. Centrul doare cel mai mult. |
| `coverage-reduction` | 0.65 | Cât din acoperirea defensivă anulează expunerea. |
| `second-dm-weight` | 0.55 | Al doilea mijlocaș defensiv contribuie 55% din cât contribuie primul. |
| `cb-recovery-pace-cap` | 0.50 | Plafonul contribuției vitezei fundașilor centrali la acoperire. |
| `penalty-strength` | 0.55 | Cât de tare lovește penalizarea. |
| `penalty-exponent` | 1.70 | Cât de accelerat crește penalizarea cu riscul. |

**Formula:**
```
riscRezidual = max(0, expunere − 0.65 × acoperire)
multiplicator = exp(−0.55 × riscRezidual^1.70)
protecțieFinală = protecție × multiplicator
```

E **exponențială**. La risc rezidual 1.0 protecția scade cu ~42%; la 2.0 cu ~83%.

**Influențează:** cât de mult te costă să joci cu jucători care nu apără. E contra-greutatea
naturală pentru `attack-multiplier` mare — de-asta o valoare precum 4.5 nu e automat
dezechilibrantă: crește atacul, dar plătește exponențial în apărare.

Dacă vrei ca tacticile ultra-ofensive să fie mai riscante, urcă `penalty-strength`.
Dacă vrei să fie mai viabile, coboar-o.

---

## 8. `probability` — de la putere la scor

| Cheie | Valoare | Ce face |
|---|---|---|
| `matchup-exponent` | 2.5 | **Cât contează diferența de valoare.** |
| `home-advantage` | 1.08 | Multiplicator pe xG-ul gazdei. +8%. |
| `gamma-shape` | 8.0 | **Varianța scorurilor.** |
| `goal-cap` | 7 | Plafonul de goluri per echipă. |

### `matchup-exponent`

```
q = atac^k / (atac^k + protecțieAdversar^k)
```

Controlează **împărțirea**, nu totalul. Pentru un atac cu 20% peste apărarea adversă:

| k | q favorit | raport xG |
|---|---|---|
| 1.5 | 0.568 | 1.3× |
| 2.5 | 0.612 | 1.6× |
| 4.0 | 0.675 | 2.1× |
| 10 | ~0.97 | rupt |

**Peste ~4 intri în saturație.** Funcția devine o treaptă: meciurile se polarizează în
măcel sau blocaj, fără mijloc. Mai grav — dacă atacurile ambelor echipe stau sub
protecția adversarului, exponentul mare trage *ambele* xG spre zero simultan și
campionatul devine un zid de 0-0.

### `gamma-shape` — merge invers față de intuiție

Controlează supra-dispersia distribuției Gamma-Poisson.

- **Mai mic = mai multă varianță** → mai multe 4-2, 5-0, dar și mai multe 0-0.
- **Mai mare = mai puțină varianță** → totul converge spre 1-0, 1-1, 2-1.

Nu schimbă media, doar împrăștierea. Pentru scoruri spectaculoase: `6-8`. Pentru un
campionat previzibil: `15+`.

---

## 9. `aggregation`

| Cheie | Valoare | Ce face |
|---|---|---|
| `wide-redistribution-share` | 0.20 | Cât din contribuția unui jucător de bandă se contabilizează pe canalul lateral. Se aplică doar pozițiilor eligibile ca laterale. |

**Influențează:** analiza pe canale (stânga / centru / dreapta) și, prin ea, expunerea
laterală. Efect mai degrabă de raportare decât de rezultat.

---

## 10. `context-rules` — cum modifică tactica valoarea atributelor

38 de reguli care ridică coeficientul contextual al unor atribute în funcție de tactică.

```yaml
tempo:much higher: { DECISIONS: 0.30, FIRST_TOUCH: 0.20, PACE: 0.40 }
line:high:         { PACE: 0.20, ANTICIPATION: 0.15, POSITIONING: 0.10 }
pressing:high:     { WORK_RATE: 0.20, STAMINA: 0.20, ANTICIPATION: 0.10 }
```

**Formula:**
```
factor = clamp(1 + k × (2 × atributNormalizat − 1), min, max)
```

Mecanismul e elegant: un coeficient pozitiv **răsplătește** jucătorii peste medie la acel
atribut și îi **penalizează** pe cei sub medie. La atribut normalizat 0.5 factorul e
exact 1.0, indiferent de `k`.

Deci `tempo:much higher` cu `PACE: 0.40` nu dă un bonus tuturor — face viteza să
conteze: rapizii câștigă până la +40%, lenții pierd până la −40%.

**Influențează:** dacă alegerea tacticii în funcție de lot are sens. Coeficienți mari
înseamnă că trebuie să potrivești tactica la jucători; coeficienți mici înseamnă că
tactica e decorativă.

---

## 11. `player-value.familiarity-penalty`

Matrice poziție-naturală → poziție-folosită, cu factor `0.0 … 1.0` care **înmulțește
direct** scorul final.

```yaml
DL: { DR: 0.85, DC: 0.7, ML: 0.75, WBL: 0.92, GK: 0.1 }
```

Un DL folosit pe WBL păstrează 92% din valoare; pe poarta 10%.

**Influențează:** cât de liber poți muta jucătorii între poziții. Valorile mici fac
loturile specializate obligatorii; valorile mari fac orice jucător universal.

---

## 12. `role-weights`

| Cheie | Valoare | Ce face |
|---|---|---|
| `suitability-scale` | 5.0 | `potrivire = clamp(mediePonderată × 5.0, 1, 100)`. |
| `attributes` | 24 tabele | Ce atribute definesc fiecare rol, pentru calculul potrivirii. |

Rezultatul intră în `roleFit = 0.85 + 0.30 × potrivire/100`.

**Notă:** `suitability-scale: 5.0` înseamnă că media ponderată se saturează la 100
când atributele ajung la ~20/20. Un lot mediocru va avea potriviri mici peste tot,
deci `roleFit` aproape de 0.85 pentru toată lumea — rolurile nu diferențiază nimic.
Dacă vrei ca rolurile să conteze la loturi normale, urcă scala.

---

## Ordinea de reglaj recomandată

Cel mai mare efect per unitate de modificare, de sus în jos:

1. **`openness`** — numărul de goluri. Liniar, previzibil, sigur.
2. **`matchup-exponent`** — cât contează valoarea. Neliniar; nu depăși 4.
3. **`gamma-shape`** — varianța. Reține că merge invers.
4. **`exposure.penalty-strength`** — cât de riscante sunt tacticile ofensive.
5. **`positions` / `roles` / `duties`** — cât contează deciziile tactice.
6. **`compartments`** — ce fel de jucători sunt valoroși. Schimbă economia jocului.
7. **`context-rules`** — cât contează potrivirea tacticii la lot.
8. **`rating`** (morale-slope, fitness-floor, role-fit-base) — factori actualmente
   aproape inerți; de mișcat doar dacă vrei să îi faci relevanți.

**Regula de aur:** `openness` și `matchup-exponent` sunt cuplate. Exponentul mai mare
poate trage ambele xG în jos; dacă îl urci, verifică totalul de goluri și compensează
cu `openness`.

---

## Probleme cunoscute în configurația actuală

1. **`work-rate.instructions` e cod mort** — vezi secțiunea 6. Cele trei intrări nu au
   niciun efect în joc.
2. **`morale-slope: 0.0004`** dă un efect maxim de ±1.2%. Moralul e practic decorativ.
3. **`suitability-scale: 5.0`** face ca `roleFit` să fie aproape constant pentru loturi
   sub elită, deci rolurile nu diferențiază.
4. **Denumire înșelătoare:** butonul „Stay Forward" din UI ajunge în engine ca trăsătura
   `REFUSES_DEFENSIVE_WORK` — sună a defect de caracter, nu a instrucțiune tactică.
