# Motorul de meci 2D, în Java (microserviciu)

> **În acest depozit:** dosarul `match2d-service` e microserviciul de meci 2D folosit de backend când
> `match2d.mode` e pornit (vezi secțiunea „2D match engine” din README-ul principal). E de sine stătător:
> `bash build.sh serve`, apoi `java -jar out/serve/match2d.jar serve --port 8791`. Motorul Python de
> referință (`../match-engine-2d`) și uneltele de măsură (`tools/`, `python tools/...`) pomenite mai jos
> NU sunt în depozit; comenzile care le folosesc nu merg de aici.

Același simulator ca în `../match-engine-2d` (Python), portat pas cu pas în Java 17, fără nicio
bibliotecă. Joacă un meci de 90 de minute în aproximativ o secundă; motorul Python are nevoie de
12–34 de secunde. De la portare încoace **acesta e motorul de lucru**: regulile se schimbă aici,
iar proiectul Python a rămas referința portării și gazda uneltelor de măsurare.

## Pornire

```bash
bash build.sh                                   # compilează și face out/match2d.jar (~5 s)
```

```bash
java -jar out/match2d.jar serve                 # microserviciul, pe http://127.0.0.1:8791
```

```bash
bash test.sh                                    # testele vechi (regulile de gol, etapa 4)
bash test.sh RunsTest ZonesSupportTest TacticsFoulTest SetPieceTest HeaderAndSaveTest EventContractTest
```

```bash
java -jar out/match2d.jar weights               # toate ponderile, cu valoarea lor curentă
```

`build.sh` și `test.sh` folosesc JDK-ul din `JAVA_HOME` dacă e setat, altfel `java`, `javac` și `jar`
din PATH (Java 17 sau mai nou).

**Serviciul rulează dintr-o copie a arhivei**, `out/serve/match2d.jar`, ca motorul să poată fi
recompilat și măsurat cât serviciul e pornit. `bash build.sh` face doar `out/match2d.jar` (cea
folosită de unelte și de liga la televizor). Ca serviciul să primească versiunea nouă: îl
oprești, rulezi `bash build.sh serve` (copiază arhiva; refuză dacă serviciul încă răspunde pe
8791) și îl pornești din nou cu `java -jar out/serve/match2d.jar serve`. Nu copia arhiva de mână
peste un serviciu pornit: Windows lasă fișierul scris, iar procesul încarcă apoi clase amestecate.

**Arhiva se face alături, apoi se mută.** `build.sh` scrie `out/match2d.new.jar` și abia apoi o
mută peste `out/match2d.jar`. Dacă arhiva veche e ținută de un proces Java (o măsurătoare, un
lot agățat), mutarea eșuează, `build.sh` iese cu eroare, iar arhiva nouă rămâne în
`out/match2d.new.jar`. De aceea măsurătorile lungi rulează pe o copie a arhivei, aleasă cu
variabila de mediu `MATCH2D_JAR`: un build nou nu schimbă arhiva sub ele.

## Microserviciul

| Adresă | Ce face |
|---|---|
| `POST /matches` | joacă meciul din `{"home", "away", "seed", "minutes"}`; întoarce `id`, `viewUrl`, `score`, `goals`, `tactics`, `tacticIgnored`, `summary`, `simulatedInMs`, `cached`, `engine: "java"`, `engineVersion`, `players`, `highlights` |
| `POST /results` | rezultatul fără pagină: un meci (același corp, plus `ref` opțional, întors neschimbat) sau un grup `{"matches": [meci, ...]}` (cel mult 512) → `{"results": [...]}` în aceeași ordine |
| `GET /view/<id>` | pagina de afișaj, de pus într-un `iframe`; are butoanele „Rezumat" și „Reluări", iar bara de timp arată fazele importante (`highlights` din datele meciului) ca intervale evidențiate |
| `GET /matches/<id>` | cronologia brută (JSON) |
| `POST /simulate` | același corp; întoarce direct cronologia întreagă (`"summaryOnly": true` = doar rezumatul, evenimentele, `players` și `highlights`) |
| `GET /health` | `{"status": "ok", "engine": "java", "engineVersion": 1}` |

Rezultatul din `/results` are aceleași câmpuri ca `POST /matches`, fără `viewUrl` și `cached`,
plus `ref` (dacă a fost dat); `id` e același pe care l-ar da `/matches`. Meciurile nu se țin în
memorie și nu păstrează cadre; grupul se joacă în paralel, pe câte un fir pe procesor. Un meci
care eșuează devine `{"ref", "error"}` și nu strică grupul (un singur meci greșit: 400).
Fiecare gol din `goals` are, pe lângă `minute`, `team`, `text`: `playerId` și `player`
(marcatorul; la autogol cel care l-a dat), `assistId` și `assist` (sau `null`), `own` și `how`
(`penalty`, `free_kick`, `header` sau `null`). `engineVersion` e cheia `version` a cronologiei.

**Notele și fazele importante le calculează motorul** (post-procesare peste evenimente, fără
nicio tragere din generator, doar pe drumul nou), iar cronologia (comenzile `run`, `batch`,
`/simulate`, `/matches/<id>`, pagina) le are la nivelul de sus:

- `players`: câte unul pe jucător, `{team, uid, id, name, number, position, rating, motm, stats}`;
  `id` e id-ul jucătorului din cerere (sau `null`), `rating` are o zecimală, `stats` are
  contoarele cu numele din `match_stats.COLUMNS` (`goals`, `assists`, ..., `conceded`,
  `clean_sheet`) și ajutătoarele notei (`shots_off`, `open_goals`, `winning_goal`, ...).
  Clasa `MatchStats`; ponderile notei sunt câmpurile statice de la începutul ei.
- `highlights`: `[{from, to, label, kind}]` în secunde de joc (`kind`: `goal`, `penalty`,
  `free_kick`, `save`, `edge`), ordonate și unite. Clasa `Highlights`.

### Meciul eliminatoriu (`knockout`)

Câmp opțional în cerere (`run`, `batch`, `POST /matches`, `/results`, `/simulate`):
`"knockout": {"aggregate": [gazde, oaspeți]}`, golurile din prima manșă în orientarea acestui
meci (`[0, 0]` sau `aggregate` lipsă = manșă unică). Intră în `id`. Fără el meciul iese exact ca
înainte; cu el primele 90 de minute sunt aceleași (aceeași sămânță), iar restul continuă cu
același generator.

- **Regula.** La egalitate după 90 de minute (`scor + aggregate`, fără gol în deplasare):
  prelungiri, 2 × 15 minute (fix, `Sim.ET_HALF` = 900 s), cu schimbarea terenului și lovitură de
  începere (gazdele în prima, oaspeții în a doua), fără odihnă: oboseala continuă. Tot egal:
  lovituri de departajare (clasa `Shootout`): cine începe se trage la sorți; câte 5, alternativ,
  oprire când e decis, apoi câte una. Executanții: executantul de penalty (din tactică, altfel
  cel mai bun la `penaltyTaking`), ceilalți după `penaltyTaking` (apoi `composure`), portarul
  ultimul; după 11 ordinea se reia. Șansa e a penalty-ului din joc (`SetPiece.penGoalProb`,
  `penAim`).
- **Rezultatul**, la nivelul de sus al cronologiei și în răspunsurile serviciului (doar cu
  `knockout`): `{aggregate, score90, extraTime, extraTimeGoals, penalties, shootout, winner,
  decidedBy}` (`penalties`/`shootout` `null` fără departajare; `shootout` = `[{team, uid,
  playerId, player, scored}]`). `score` și sumarul au golurile din prelungiri, nu și
  departajarea. În `goals`, `minute` e 1–90, 91–105 sau 106–120 după perioadă.
- **Evenimente noi** (doar la meciul eliminatoriu): `EXTRA_TIME` (la minutul 90, în locul lui
  `FULL_TIME`), `EXTRA_HALF_TIME`, `SHOOTOUT` (`team` = cine începe, `first`), `SHOOTOUT_KICK`
  (`team`, `p` = executantul, `q` = portarul, `scored`, `kick` = numărul loviturii în
  departajare, `round` = a câta a echipei, `score` = loviturile transformate până atunci).
  `FULL_TIME` vine la final, cu „după prelungiri" sau „(pen. a - b)". Loviturile de departajare nu
  sunt `GOAL`, nu intră în note și nici în `players[].stats`.
- **Cronologia:** `halves` primește intrări `{frame, dirs, period}` cu `period` = `extra1`,
  `extra2`, `penalties`; `highlights` are segmentul „Lovituri de departajare" (`kind:
  "shootout"`). `minutes` rămâne cel cerut. Pagina arată „prelungiri 1/2", „penalty-uri" și
  „1 - 1 (pen. 4 - 3)". Liga la televizor nu folosește câmpul.

Opțiuni: `--port` (implicit 8791), `--host`, `--public-url` (adresa pusă în `viewUrl` când
serviciul stă în spatele altui nume). Ține ultimele 24 de meciuri; același meci cerut a doua
oară vine din memorie.

Backend-ul jocului (`match2d/Match2dEngineClient`) îl caută la `match2d.service.url`, implicit
`http://localhost:8791`. O echipă are aceeași formă ca în motorul Python: primul 11 cu toate
cele 43 de atribute, plus tactica. Executanții fazelor fixe vin din tactică după `id`-ul
jucătorului (vezi „Faze fixe").

## Alte comenzi

```
java -jar out/match2d.jar run cerere.json [iesire.json]      un meci, cu cronologia întreagă
java -jar out/match2d.jar batch [--threads N] < lot.json     multe meciuri în paralel
java -jar out/match2d.jar trace cerere.json DE_LA PANA_LA    starea pas cu pas (comparare cu Python)
```

Un lot e `{"jobs": [cerere, ...], "weights": {"speed.pace": 1.0}, "events": ["GOAL", "SHOT"]}`.
Uneltele din `../match-engine-2d/tools` și liga la televizor (`league_tv.py`) joacă prin aceste
comenzi dacă arhiva există; scriu în antet „motor Java". Puntea e `tools/javaengine.py`:
`MATCH2D_JAR` alege arhiva (implicit `out/match2d.jar`), iar `run_batch(..., timeout=1800)`
oprește procesul Java și ridică o eroare dacă lotul nu se termină în 30 de minute.

**`batch` nu mai rămâne agățat.** Firele de lucru sunt daemon, sunt oprite cu `shutdownNow` în
`finally`, iar orice eroare (și lipsa de memorie) scrie mesajul pe stderr și iese cu codul 1.
Lecția: un lot de 4096 de meciuri a rămas fără memorie, firul principal a murit, iar firele
rămase au ținut procesul în viață trei ore, cu `out/match2d.jar` blocată. Loturile mari se
împart: `tools/setpiece_report.py` trimite cel mult 512 meciuri pe proces Java, fiindcă
evenimentele păstrate umplu memoria.

## Liga la televizor (în Java)

Portarea lui `../match-engine-2d/league_tv.py`: campionatul Diamond League transmis „la
televizor", un meci o dată, cu ceasul serverului. Meciurile se joacă în proces, pe un fir de
lucru (cel următor se pregătește cât se vede cel curent).

```bash
java -jar out/match2d.jar league                          # http://127.0.0.1:8796
java -jar out/match2d.jar league --speed 8 --pause 45
java -jar out/match2d.jar league --state ../match-engine-2d/out/league_tv_state.json   # continuă sezonul Python
```

Opțiuni, aceleași ca în Python: `--teams` (implicit `diamond_league.json` din arhivă), `--host`
(127.0.0.1), `--port` (8796), `--seed` (2026), `--meetings` (4), `--minutes` (90), `--speed` (4),
`--state`, `--new` (aruncă sezonul salvat), `--pause` (30 de secunde cu fișa meciului după
fluierul final). `--inline`, `--engine` și `--jar` au sens doar în Python: se acceptă și se ignoră,
ca aceleași linii de comandă să meargă.

Are aceleași adrese, cu aceleași coduri și același JSON: `/` (și `/tv`), `/clasament`,
`/api/state`, `/api/sheet/N`, `/match/N`, `/replay/N`, `/replay/N/save`, `/match_sheet.js`,
`/health`, iar prin POST `/api/speed`, `/api/mode`, `/api/replays`, `/api/skip`, `/api/new-season`.
Paginile (`tv.html`, `standings.html`, `match_sheet.js`) sunt copiate neschimbate în
`src/main/resources/match2d/league/`.

**Fișierul de stare are exact formatul celui din Python**, deci un sezon început de o variantă
continuă în cealaltă. Implicit e `league_tv_state.json` lângă arhivă (`out/league_tv_state.json`);
în Python e `../match-engine-2d/out/league_tv_state.json`. Ca să continui un sezon existent,
pornești liga cu `--state` pe fișierul lui și cu aceleași `--meetings`, `--minutes` și echipe:
altfel sezonul salvat nu se recunoaște și începe unul nou (sămânța se ia din fișier).

## Unde se schimbă valorile

| Ce | Unde | Cum încerci fără să editezi |
|---|---|---|
| cât valorează fiecare atribut (ponderile) | `src/main/java/match2d/engine/Wt.java` | `python tools/calibrate.py --set speed.pace=1.0` |
| ce face fiecare valoare tactică | `src/main/java/match2d/engine/Tactics.java` | `python tools/calibrate.py --tactic tempo="Much Higher"` |
| loturile, atributele și tacticile echipelor | `../match-engine-2d/teams/diamond_league.json` | — |

După o editare în `Wt.java` sau `Tactics.java` rulezi `bash build.sh`. Fișierele
`engine/weights.py` și `engine/tactics.py` din proiectul Python nu mai au efect asupra
măsurătorilor: ele țin doar de motorul Python.

## Regulile de gol (diferă de motorul Python)

Reparate aici după portare, la 248 de meciuri de 90 de minute (aceleași meciuri, înainte și după):

| | Înainte | Acum |
|---|---|---|
| goluri pe meci | 3,30 | 2,89 |
| goluri cu marcator | 87% | 99% |
| goluri cu pasă decisivă | 62% | 89% |
| autogoluri | unul la 2 meciuri (fără nume) | unul la 31 de meciuri (cu nume) |
| meciuri 0-0 | 12 | 17 |

1. **Mingea respinsă nu mai intră în poartă.** Parada, blocajul și mingea scăpată de portar
   plecau într-o direcție la întâmplare; când direcția dădea în poartă, ieșea un „autogol".
   `BallPlay.awayFromGoal` întoarce acum mingea spre teren. De aici vin cele 0,4 goluri în minus.
2. **Golul fără șut are marcator.** Mingea ține minte ultimul jucător care a atins-o
   (`Ball.toucher`). O pasă sau o lovitură de cap care intră în poartă e golul celui care a
   trimis-o. Autogol rămâne doar mingea trimisă în propria poartă de un apărător (deviere,
   deposedare) și apare cu numele lui: câmpul `own` al evenimentului `GOAL`.
3. **Pasa decisivă vine din șirul celor care au jucat mingea** (`Sim.played`, `Sim.assistFor`):
   ultimul coechipier dinaintea marcatorului, dacă între ei nu a avut mingea un adversar. O
   paradă respinsă sau o deviere nu rup șirul, deci golul din respingere are pasă de la primul
   șutor. Fără pasă decisivă rămân golurile la care marcatorul a luat singur mingea de la
   adversar și cele direct din repunere.

`java -Dmatch2d.legacy=true ...` joacă după regulile vechi, adică exact ca motorul Python.

## Recalibrarea nivelului de goluri (făcută, 2026-11-15)

Ținta era liga celor 8 la ~2,6 goluri pe meci și 0-0 sub 10%, cu formațiile uzuale între ~2,0 și
~3,2 la echipe egale. Înainte liga avea 2,18 goluri pe meci și 0-0 în 18% din meciuri, cu 8,6
șuturi pe echipă. Toate mecanismele de mai jos sunt doar pe drumul nou (`if (!Sim.LEGACY)`):
`-Dmatch2d.legacy=true` dă în continuare exact meciurile motorului Python (`python
tools/compare.py --quick`: toate identice).

**Cauza principală era formația.** Înscriau aproape doar jucătorii ale căror zone ajung sus
(`high` ≥ 85 m în `Zones.java`: atacanții și mijlocașii ofensivi). Mijlocașii centrali (`high`
72) și cei de bandă (80) nu ajungeau la finalizare, deci o formație cu un singur atacant aproape
că nu trăgea la poartă, iar una cu patru oameni sus dădea de două ori mai multe goluri.

### Echilibrul formațiilor (`Runs.java`)

- **Tăria liniei de atac.** Om de atac = jucătorul de câmp cu `high` ≥ 85 m. Atacantul valorează
  1, omul din linia a doua (zona lui nu ajunge pe ultimul fundaș: `high` sub 87 m) `run_second`
  = 0,45, atacantul lateral (mai departe de 14 m de ax) `run_wide` = 0,23.
  `runMissing = run_target − tărie`, cu `run_target` = 2,5; numărul de alergări permise deodată
  e `runMissing` rotunjit în sus.
- **Alergarea în careu.** Când echipa are mingea și aceasta trece de `run_from − run_slope ×
  runMissing` (105 − 35 × lipsa, în metri canonici), pleacă spre careu întâi mijlocașul ofensiv
  din linia a doua, apoi cei mai avansați jucători care nu sunt nici fundași, nici oameni de
  atac de pe linie; niciunul cu mingea, cu un rol sau la peste `run_reach` = 30 m în urma
  mingii. Ținta e la `run_depth` = 9 m de linia porții, pe locul liber cel mai apropiat pe
  lățime (barele, centrul, marginile). Alergarea ține până se pierde mingea, până se trage la
  poartă, până la o repunere sau până jocul se întoarce cu `run_keep` = 8 m sub prag.
  Exemple, din valorile din cod și sarcinile implicite: 4-4-2 are lipsa 0,5 (o alergare, de la
  87,5 m); 4-1-4-1 are lipsa 1,5 (două alergări, de la 52,5 m); 4-2-3-1 are 1 + 0,45 + 2 × 0,23
  = 1,91, lipsa 0,59 (o alergare, de la ~84 m).
- **Omul ținut în spate.** Pe linia de atac (atacanți și extreme) stau cel mult `hold_line` = 3
  oameni; cei în plus sunt ținuți cu `hold_depth` = 13 m mai jos când echipa atacă (la egalitate
  își împart locul rămas).
- **Coborârea atacanților.** Când echipa se apără, sus rămân doar cei mai avansați
  `drop_stay_up` = 2 jucători; ceilalți cu `deep` peste `drop_deep` = 21 m coboară spre linia
  mijlocașilor pe măsură ce mingea vine de la `drop_from` = 55 m de poarta lor spre 35 m
  (`drop_blend` = 20), iar zona lor de apărare coboară și ea.
- `Zones.supportAttack` a rămas doar cu partea de lateral: pe o bandă fără om sus urcă cel mai
  avansat jucător de pe ea (fundașul lateral, în 3-5-2 și 3-5-1-1). Partea centrală (urcarea
  unui mijlocaș central) a fost scoasă, iar ponderile `zone_mid_push` și `zone_att_drop` au fost
  șterse din `Wt.java`.

**Ce n-a mers pe drum.** Primele variante de alergări numărau oamenii „sus" după poziția lor din
teren și erau mult prea tari: 4-4-2 urca de la 2,10 la 5,04 goluri pe meci. A mers abia modelul
continuu, cu tăria liniei și „lipsa". Cu ponderile inițiale (`run_second` și `run_wide` 0,5),
4-2-3-1 avea lipsa exact 0 și nu alerga nimeni; de aceea `run_wide` e 0,23 și `run_second` 0,45.
O deplasare fixă a zonelor (`zone_mid_push`, `zone_att_drop`) era hipersensibilă: cu
`zone_mid_push` = 6, 4-4-2 sărea la 4,78; cu 6 și 6, 4-2-3-1 cădea la 1,68.

### Tactica temperată

Pe drumul nou efectul mentalității și al tempo-ului e cam la jumătate din cel al motorului
Python (`Tactics.was(acum, vechi)`): `block` al mentalității (Defensive −0,02 în loc de −0,04),
`tempo_time` (Much Lower 1,25 în loc de 1,5) și `tempo_move` (Much Lower 0,95 în loc de 0,90).
„Defensive" + tempo „Much Lower" + pase lungi taie acum ~13% din goluri (2,39 față de 2,74), nu
44% ca înainte.

### Portarul și nivelul general

- Portarii ligii au `reflexes` și `handling` de 15–19, mult peste restul echipei. Pantele lor
  sunt reduse la 60% în jurul portarului mediu: `save_reflexes` 0,21 → 0,126 (cu
  `save_react_base` 0,32 → 0,278) și `save_handling` 0,225 → 0,135. `agility` nu s-a schimbat.
- Nivelul general: `save_sure_base` 0,80 → 0,93, `shot_error_slope` 0,34 → 0,58, `block_base`
  0,70 → 0,90, `block_reach` 1,6 → 2,8, `block_reach_near` 2,6 → 3,6.
- Valorile vechi sunt păstrate în blocul `legacy` din `Wt.java`.

### Înainte și după

Echipe generate egale, nivel 10 cu fizic 6 (ca în ligă), ambele în aceeași formație
(`python tools/formation_report.py`). Înainte: 192 de meciuri pe rând; acum: 384
(`--matches 384`).

| Formație | Înainte: goluri pe meci | Înainte: șuturi pe echipă | Acum: goluri pe meci | Acum: 0-0 |
|---|---|---|---|---|
| 4-1-4-1 | 0,86 (fără `supportAttack`: 0,70) | 5,0 | 2,24 | 9,9% |
| 3-5-1-1 | 1,33 (fără `supportAttack`: 0,78) | 6,1 | 2,30 | 11,5% |
| 4-4-1-1 | 1,67 | 9,4 | 2,46 | 8,6% |
| 3-5-2 | 1,87 | 6,9 | 2,69 | 6,5% |
| 3-1-4-1-1 | 1,89 | 8,3 | 2,87 | 5,7% |
| 4-4-2 | 2,10 | 11,3 | 2,74 | 7,6% |
| 4-3-3 | 2,55 | 9,9 | 2,58 | 6,2% |
| 4-2-3-1 | 2,93 | 12,0 | 2,33 | 7,6% |
| 4-2-2-2 | 4,54 | 13,7 | 2,63 | 6,5% |

Celelalte rânduri de acum (384 de meciuri pe rând):

| Rând | Goluri pe meci | 0-0 |
|---|---|---|
| nivel 12, 4-4-2 | 3,13 | 3,9% |
| nivel 12, 4-2-3-1 | 2,96 | 4,4% |
| nivel 12, 4-1-4-1 | 2,85 | 7,3% |
| nivel 9, 4-4-2 | 3,09 | 4,4% |
| nivel 10 fizic 6, 4-4-2 | 2,74 | 7,6% |
| + Defensive, tempo Much Lower, pase lungi | 2,39 | 8,1% |
| + doar tempo Much Lower | 2,45 | 7,6% |
| + doar mentality Defensive | 2,24 | 12,2% |
| + doar pase lungi | 2,89 | 6,5% |

**Liga celor 8** (`python tools/league_stats.py --rounds 12`, 672 de meciuri): 2,62 goluri pe
meci, 0-0 în 9,2%; pe echipă și meci 12,3 șuturi, 4,4 pe poartă, 3,1 blocate. Pe alt set de
semințe (672 de meciuri): 2,53 ±0,07 și 9,1%. Înainte: 2,18 și 18%. xG-ul ligii: 2,50 pe meci
la 2,62 goluri (semințele din `league_stats.py`), 2,44 la 2,53 pe al doilea set.
`Shooting.saveProb` e limitată la 1: înainte putea trece de 1 la portarii buni și micșora xG-ul.

**Grupuri de atribute** (`python tools/attribute_report.py`, 4-4-2 nivel 12 contra copiei ei,
512 meciuri pe rând): grupul defensiv +3 aduce +0,22 ±0,05 xG, grupul de atac +3 +0,45 ±0,05.
Ambele în criteriul pașilor (defensiv cel puțin +0,2, atac sub +1,0).

**Portarul după comprimarea la 60%** (`attribute_report.py`, 512 meciuri pe rând, +3 puncte):

| +3 la | Diferența de goluri | Diferența de xG |
|---|---|---|
| `reflexes` | +0,09 ±0,07 | +0,10 ±0,05 |
| `handling` | +0,05 ±0,07 | +0,05 ±0,04 |
| `oneOnOnes` | +0,05 ±0,08 | +0,10 ±0,05 |
| `agility` | +0,23 ±0,08 | +0,12 ±0,05 |

**Abateri rămase.** Rândurile 4-4-2 la echipe generate de nivel 12 și 9 sunt la ~3,1 (ținta
inițială era cel mult 3,0). 4-1-4-1 și 3-5-1-1 au rămas la 2,24–2,30, în banda formațiilor
uzuale, dar în partea ei de jos. `handling` +3 singur nu mai trece de două erori standard, iar
`reflexes` e la limită: e prețul comprimării făcute ca portarii ligii (15–19) să nu domine.

**Zgomotul măsurătorilor**, de ținut minte: liga pe 672 de meciuri ≈ ±0,07 goluri pe meci; un
rând de formație pe 384 de meciuri ≈ ±0,09; pe 192 ≈ ±0,12.

Mecanismele etapei 4 nu erau cauza: oprite pe rând, schimbau media ligii cu cel mult 0,1.

## Faze fixe (2026-11-15)

Faulturi, cartonașe galbene, lovituri libere directe sau centrate, penalty-uri, cornere,
centrări din joc și lovitura de cap la poartă. Totul e doar pe drumul nou: cu
`-Dmatch2d.legacy=true` motorul nu intră în `SetPiece.java`, iar câmpurile tactice de faulturi
nu există. Pantele sunt centrate pe jucătorul mediu (0,5). Ponderile sunt în `Wt.java`, în
grupele „faze fixe, etapa A" și „faze fixe, etapa B".

### Faulturi și cartonașe

- **Faultul din deposedare.** La fiecare deposedare încercată, înainte de rezolvarea duelului,
  se trage faultul: `foul_base` 0,22 × (1 + 1,2 × (`aggression` − 0,5) − 0,9 × (`tackling` −
  0,5)) × `foulFrequency`, de 1,8 ori mai des din spate (`foul_behind`), de 0,155 ori în careul
  propriu (`foul_box`: fundașul e mai atent) și de 0,6 ori dacă are galben (`foul_booked`); cel
  mult 0,6 (`foul_max`).
- **Faultul la săritură.** La duelul aerian (minge înaltă cu un adversar la sub 2,2 m), fiecare
  dintre cei doi poate împinge: `aerial_foul_base` 0,04 × (1 + 1,2 × (`aggression` − 0,5)) ×
  `foulFrequency`, cu aceiași factori pentru careu și galben.
- **Repunerea.** Faultul în careul propriu e penalty; în rest, lovitură liberă pentru echipa
  faultată, din locul faultului.
- **Cartonașul galben.** `card_base` 0,125 × `foulHardness` × (1 + 0,8 × (`aggression` − 0,5)),
  de 1,8 ori din spate, de 1,6 ori lângă poartă (sub 25 m de centrul porții și sub 14 m de ax),
  de 0,4 ori la săritură. Un singur galben pe jucător: al doilea nu se dă, nu există roșu sau
  eliminare, deci se joacă mereu 11 la 11. Jucătorul cu galben faultează mai rar (0,6) și iese
  mai rar la deposedare (`booked_eager` 0,6).
- **Tactica.** `foulFrequency`: Rarely 0,7, Normal 1,0, Often 1,4 (înmulțește șansa de fault).
  `foulHardness`: Soft 0,6, Medium 1,0, Hard 1,6 (înmulțește șansa de cartonaș); Hard câștigă și
  ceva mai multe dueluri (+0,10 în logitul duelului, Soft −0,10).

### Lovitura liberă

Felul ei vine din loc, în canonicul echipei care bate (`SetPiece.fkKind`):
- **directă** (`shot`) până la 26,5 m de centrul porții, cu unghiul față de axul porții sub 55°;
- **centrată** (`cross`) de la 72 m (canonic) în sus, dacă nu e directă;
- **jucată scurt** (`play`) în rest, ca o repunere obișnuită.

La lovitura liberă directă apărarea face zidul: 3 oameni (4 sub 22 m), la 9,15 m de minge,
mutat ca să acopere colțul scurt. Șutul rămâne în zid cu șansa 0,22 − 0,30 × (`freeKick` −
0,5). Precizia vine din `freeKick` (eroarea de unghi 0,08 − 0,15 × (`freeKick` − 0,5), cel puțin
0,015; înălțimea la poartă tot din `freeKick`), puterea din `technique` și `strength` ca la șut,
înmulțită cu 0,92. Țintește colțul mai depărtat de portar (în 25% din cazuri pe celălalt).
Portarul pleacă cu 0,43 s mai târziu (`fk_gk_late`): zidul îi ascunde mingea.

La lovitura liberă centrată, cei mai buni 5 la jocul aerian se așază pe o linie la marginea
careului, cu câte un apărător între fiecare și poartă; mingea merge la 11,5 m de linia porții, în
dreptul atacantului ales. Eroarea vine din `freeKick` (0,7) și `crossing` (0,3).

### Penalty-ul

Șansa de gol: 0,79 + 0,40 × (`penaltyTaking` − 0,5) + 0,12 × (`composure` − 0,5) − 0,12 ×
((`reflexes` + `oneOnOnes`) / 2 − 0,5) ale portarului, între 0,3 și 0,97. Din rest, 0,07 e
ratare pe lângă sau peste poartă, restul paradă (prinsă sau respinsă; respingerea rămâne în joc).
La executare ceilalți stau pe o linie la 20,5 m de linia porții.

### Cornere, centrări și jocul de cap

- **Sursele cornerului**, pe lângă mingea ieșită de pe un apărător: parada respinsă (0,7),
  șutul blocat la cel mult 25 m de linia porții (0,55), centrarea respinsă cu capul în careu
  (0,5; la un corner 0,15, ca șirurile corner – corner să se stingă; la o minge lungă obișnuită
  0,3), pasa adversă deviată în careu (0,6), deposedarea la cel mult 18 m de linia porții (0,4).
- **Cornerul.** Cei mai buni 5 la jocul aerian în careu (marginea careului mic, punctul de la
  11 m, bara îndepărtată, bara apropiată, spatele careului), unul la marginea careului mare
  (cel mai bun la `longShots`), ceilalți în urmă. Apărarea: portarul pe linie, câte un om pe
  fiecare atacant din careu (cel mai bun la jocul aerian pe cel mai bun), unul la bara apropiată,
  unul la marginea careului, cel mai avansat rămâne sus. Ținta se alege dintre atacanții din
  careu cu șansa ∝ exp(10 × (`air` − 0,5)), unde `air` e jocul aerian (`heading` 0,40,
  `jumpingReach` 0,35, `strength` 0,15, `bravery` 0,10). Eroarea vine din `corners` (0,7) și
  `crossing` (0,3), cu panta mărită de 3 ori (`corner_skill_slope`).
- **Centrarea din joc.** Mingea înaltă de pe bandă (cel puțin 14 m de ax, de la 70 m canonic)
  cu ținta în careu. E permisă și de aproape (de la 10 m), eroarea vine din `crossing` (0,6),
  `passing` (0,2) și `technique` (0,2), iar valoarea variantei e înmulțită cu `cross_bias` 1,6 și
  crește cu jocul aerian al primitorului. Șansa estimată de reușită are un minim de 0,4: în careu
  mingea se joacă și disputată.
- **Duelul aerian.** Câștigă după diferența de `air`; precizia executantului nu intră în duel.
  O pondere `delivery_duel` (centrarea bine trimisă îl ajută pe atacant la duel) a fost încercată
  și scoasă: pe 4, `corners` +3 devenea măsurabil (+2,6 ±0,9 puncte procentuale la cornere
  ajunse), dar 0-0 în ligă ieșea 10,3% pe un set de semințe; pe 3 efectul nu mai trecea de două
  erori standard.
- **Lovitura de cap la poartă.** Mingea înaltă a unui coechipier ajunsă în careu, la cel mult 12
  m de centrul porții, după duel câștigat sau fără adversar. Viteza vine din `jumpingReach` și
  `strength` (13 + 4 × (`jumpingReach` − 0,5) + 2 × (`strength` − 0,5) m/s), direcția și
  înălțimea din `heading` (eroarea 0,11 − 0,22 × (`heading` − 0,5), cel puțin 0,03). Presiunea
  mărește eroarea: 0,5 după un duel, 0,35 fără adversar. Apoi mingea trece prin blocaje și
  paradă ca un șut.

### Executanții

Din tactică, după `id`-ul jucătorului (orice scriere a cheii, id ca număr sau ca text):
`penaltyTakerId`, `freeKickTakerId` (bate lovitura liberă directă și pe cea centrată),
`cornerTakerLeftId`, `cornerTakerRightId` (după partea cornerului). Dacă lipsesc sau nu sunt în
primul 11: penalty-ul îl bate cel mai bun la `penaltyTaking` (la egalitate `composure`), lovitura
liberă cel mai bun la `freeKick`, cornerul cel mai bun la `corners` + `crossing` dintre cei care
nu sunt portari sau fundași. Fără executant în tactică, portarul nu e ales niciodată.

### Așezarea

La penalty, lovitura liberă directă, lovitura liberă centrată și corner, fiecare jucător cu loc
merge acolo cât mingea e moartă; execuția așteaptă până ajung toți la cel mult 2,5 m de locul lor,
dar cel mult 10 s.

### Contractul evenimentelor

| Eveniment | Câmpuri |
|---|---|
| `FOUL` | `team` = echipa care faultează, `p` = cel care faultează, `q` = cel faultat, `behind` (boolean), `aerial: true` doar la săritură |
| `CARD` | `card: "yellow"`, `p` = cel care a faultat; vine imediat după `FOUL` |
| `FREE_KICK` | `team` = echipa care bate, `p` = executantul, `kind`: `shot`, `cross` sau `play` |
| `PENALTY` | `team` = echipa care bate, `p` = executantul; urmează direct `SHOT` cu `penalty: true` |
| `CORNER` | `team` = echipa care bate |
| `SHOT` | în plus `penalty: true`, `freeKick: true` sau `header: true` |
| `PASS` | `sub`: `cross`, `corner`, `free_kick` (lovitura liberă centrată), în rest `through` sau `feet` |
| `GOAL` | `how`: `penalty`, `free_kick`, `header`; `after`: `corner`, `free_kick` sau `penalty`, dacă golul vine la cel mult 10 s după o fază fixă (golul direct din penalty sau lovitură liberă are doar `how`) |
| `HEADER` | la fiecare duel aerian câștigat: `team`, `p` = câștigătorul, `q` = adversarul |

O lovitură de cap la poartă fără duel are doar `SHOT` cu `header: true`, fără `HEADER`.

Cheile noi din sumar, pe echipă: `fouls` (comise), `yellow_cards`, `free_kicks` și `penalties`
(primite), `penalty_goals`, `fk_shots`, `fk_goals`, `crosses` (din joc, corner și lovitură
liberă), `crosses_ok` (ajunse la un coechipier), `headers` (lovituri de cap la poartă),
`header_goals`, `corner_goals` (goluri la cel mult 10 s după un corner). `corners` exista dinainte.

### Frecvențe măsurate

Liga celor 8, `python tools/setpiece_report.py --rounds 12` (672 de meciuri), pe meci (ambele
echipe). Pe al doilea set de semințe: 10,8 cornere, 0,24 goluri după corner, 0,50 goluri cu
capul, 0,28 penalty-uri pe meci.

| Ce | Pe meci |
|---|---|
| faulturi | 21,6 (din care 4,1 la săritură) |
| cartonașe galbene | 3,2 |
| lovituri libere | 21,9: 1,8 directe, 4,2 centrate, 15,9 jucate scurt |
| lovitura liberă directă | 7,7% goluri, 37% pe poartă, 26% în zid |
| penalty-uri | 0,30, transformate 71% |
| centrări din joc | 23,4 (25% ajung la un coechipier) |
| cornere | 11,2 (24% ajung la un coechipier) |
| lovituri de cap la poartă | 5,0, 10% goluri: 0,50 goluri cu capul |
| gol în 10 s după corner | 2,0% din cornere |

Fazele fixe propriu-zise (penalty, lovitură liberă directă, corner, lovitură liberă centrată) dau
~24% din goluri.

**Atributele fazelor fixe** (`python tools/setpiece_report.py --attr NUME`, 4-4-2 nivel 12 contra
copiei ei, +3 puncte):

| +3 la | Efect |
|---|---|
| `crossing` | +0,9 ±0,4 puncte procentuale la centrări ajunse, +0,17 ±0,08 lovituri de cap, +0,12 ±0,05 goluri pe meci |
| `freeKick` | lovitura liberă directă: +4,1 ±1,3 puncte la gol, +6,5 ±1,8 pe poartă; cea centrată: +2,2 ±0,8 puncte ajunse |
| `corners` | +0,4 ±0,8 puncte la cornere ajunse, +0,04 ±0,06 goluri (1024 de meciuri): nu se distinge de zgomot |

`corners` are mecanism (precizia cornerului și alegerea executantului), dar efectul lui nu e
măsurabil acum. Rămâne deschis.

### Ce nu există

- Motorul citește acum 40 din cele 43 de atribute. Rămân `kicking`, `throwing` (degajarea
  portarului) și `longThrows` (aruncarea de la margine).
- Nu există roșu, al doilea galben, eliminări sau accidentări.
- Ofsaidul la lovitura liberă e același ca în joc (se verifică la pasa de după ea); la corner nu
  există ofsaid.

## Etapa 4: apărare și atenție (făcută doar aici, 2026-11-15)

Șase atribute au primit mecanisme: `positioning`, `marking`, `ballRecovery`, `teamwork`,
`concentration`, `leadership`. După etapă motorul citea 38 din cele 43 (acum 40, vezi „Faze
fixe").

| Atribut | Ce face în joc | Ponderi (`Wt.java`) |
|---|---|---|
| `positioning` | cu mingea la sub 40 m de poarta lui, fundașul rămas fără om se trage spre linia minge–poartă | `cover_*` |
| `marking` | cel care îl ține pe primitor pleacă mai repede spre pasă, o interceptează mai des și, dacă pasa ajunge, îi strică preluarea | `mark_react_*`, `mark_intercept_*`, `mark_touch_*` |
| `ballRecovery` | pornește mai repede spre mingea liberă și o stăpânește mai des | `loose_react_*`, `loose_control_*` |
| `teamwork` | vine spre coechipierul presat ca să-i ofere o pasă scurtă; își caută loc cu culoar de pasă liber | `help_*`, `support_lane_*` |
| `concentration` | scăpări de atenție: 2 secunde în care nu-și mai corectează poziția și reacționează cu 0,5 s mai târziu; mai dese spre finalul reprizei | `lapse_*` |
| `leadership` | căpitanul e jucătorul cu cel mai mare `leadership`; la mai puțin de 25 m de el, presiunea strică mai puțin decizia și execuția coechipierilor (un căpitan slab o strică mai mult) | `captain_*` |

Pantele sunt centrate: jucătorul mediu (rating ~10) joacă la fel ca înainte, cel de peste medie
câștigă, cel de sub medie pierde.

**Măsurat la etapa 4** (înainte de recalibrare) cu `python tools/attribute_report.py` din
`../match-engine-2d`: o echipă contra copiei ei identice, cu +3 puncte la atribut, 512 meciuri pe
rând. „xG" e suma șanselor de gol ale șuturilor (spune același lucru ca golurile, cu de ~4 ori
mai puțin zgomot); marja e ±0,04. Cifrele de după recalibrare sunt mai sus.

| +3 la | Diferența de xG | Ce se mai vede (doar ce trece de zgomot) |
|---|---|---|
| grupul defensiv (`tackling`, `marking`, `positioning`, `anticipation`, `ballRecovery`, `bravery`) | **+0,29** (înainte de etapă: 0,00) | adversarul trage cu 2,0 șuturi mai puțin; +3,9 intercepții |
| `ballRecovery` | +0,12 | — |
| `positioning` | +0,04 | șansele adversarului −0,09 |
| `marking` | +0,04 (înainte: −0,27) | pasele adversarului −0,3%; +2,3 mingi libere câștigate |
| `teamwork` | +0,04 | nimic peste zgomot |
| `leadership` | −0,01 | pase reușite +0,4% |
| `concentration` | −0,04 | scăpări de atenție −21% (304 → 240 pe meci) |
| pentru comparație, grupul de atac (6 atribute) | +0,89 | — |

Ce spune tabelul, cinstit: apărarea contează (grupul trece clar de zgomot), dar un singur atribut
la +3 rămâne sub prag, cu excepția lui `ballRecovery`. `teamwork`, `leadership` și
`concentration` schimbă comportamentul, nu și golurile, la această mărime a eșantionului.

**Ce s-a schimbat la ce exista deja:** distanța față de omul marcat și cât se lasă fundașul tras
de el nu mai depind de `marking` (sunt fixe, la valoarea medie de până atunci). Marcajul strâns
și lipit pierdea dueluri lângă careu: `marking` +3 dădea −0,27 xG.

**Încercate și scoase, fiindcă stricau:** ieșirea din zonă la dublaj (`teamwork`: adversarul
trăgea mai mult), acoperirea liniei minge–poartă de către toți fundașii (atacanții rămâneau
liberi), zgomot de decizie mai mic doar la pase (`teamwork`: −0,11 xG).

**Mediile după etapă** (echipe generate egale, nivel 12): 2,50 goluri pe meci (înainte 3,1–3,2),
0-0 în 8% din meciuri, 11,5 șuturi pe echipă, 77,5% pase reușite. Liga celor 8 era atunci la
~1,9–2,0 goluri pe meci, cu 0-0 în ~20% din meciuri; a fost recalibrată după aceea (vezi
„Recalibrarea nivelului de goluri").

## Teste

Fără biblioteci de test, ca restul proiectului. `bash test.sh` fără argumente rulează
`GoalRulesTest` și `Stage4Test`; celelalte se dau pe nume:

```bash
bash test.sh RunsTest ZonesSupportTest TacticsFoulTest SetPieceTest HeaderAndSaveTest EventContractTest
```

| Test | Ce acoperă |
|---|---|
| `GoalRulesTest` | regulile de gol (marcator, pasă decisivă, mingea respinsă) |
| `Stage4Test` | mecanismele etapei 4 |
| `RunsTest` | cine e om de atac și cine e din linia a doua; cine pornește în alergare și în ce ordine (nu cel cu rol, nu cel prea departe în urma mingii); `runNeed` 0 = nimeni nu aleargă; oprirea la pierderea mingii, la șut, la repunere și după `run_keep`; coborârea atacanților (`drop_stay_up`, zona de apărare mai jos, nimic peste `drop_from` sau când echipa atacă). Așteptările vin din câmpurile `Wt` |
| `ZonesSupportTest` | `Zones.supportAttack`: pe banda goală urcă cel mai avansat om de pe ea, pe banda ocupată nu se mută nimic, un singur om pe bandă, partea centrală veche nu mai există, zona de apărare rămâne neschimbată; verificat și într-un meci 3-5-2 |
| `TacticsFoulTest` | mentalitatea și tempo-ul temperate (între 40% și 60% din efectul vechi, același semn, în ordine); `foulFrequency` și `foulHardness` cu valorile lor, valori lipsă, goale sau necunoscute căzute pe Normal / Medium și raportate, orice scriere a cheilor |
| `SetPieceTest` | șansa de fault măsurată exact cu un generator geamăn (`aggression`, `tackling`, `foulFrequency`, din spate, în careu, cu galben, plafonul `foul_max`); faultul la săritură; evenimentele `FOUL`, `CARD`, `FREE_KICK`, `PENALTY` și repunerea de după; un singur galben pe jucător; felul loviturii libere și zidul; executanții din tactică și cei impliciți; șansa penalty-ului și a zidului |
| `HeaderAndSaveTest` | `Shooting.saveProb` între 0 și 1 pentru orice portar și crescătoare cu `reflexes` și `handling`; condițiile din `canHeadAtGoal` (careu, `header_range`, ofsaid, portar, ambele direcții de atac); `headerXg` între 0 și 1; `heading` mare dă mai multe lovituri de cap pe poartă; lovitura de cap e `SHOT` cu `header: true` |
| `KnockoutTest` | `bash test.sh KnockoutTest`: fără `knockout` nimic nou; cu el primele 90' identice (evenimente, cadre); fără egalitate nici prelungiri, nici departajare (același meci); prelungiri de 2 × 15' la egalitate cu tot cu `aggregate`; departajarea: alternanță, oprire când e decisă, executanți diferiți până trag toți 11, ordinea executanților; `score` fără departajare; `winner` și `decidedBy` coerente; aceeași cerere, același rezultat |
| `EventContractTest` | meciuri întregi pe semințe fixe: aceeași sămânță = aceleași evenimente; doar tipuri cunoscute; fiecare `FOUL` urmat de `FREE_KICK` sau `PENALTY` pentru cealaltă echipă; doar galben, cel mult unul pe jucător; valorile permise pentru `kind`, `sub`, `how`, `after`; ordinea `PENALTY` → `SHOT`, `FREE_KICK` → `SHOT` / `PASS`, `CORNER` → `PASS`; `HEADER`; cheile de sumar potrivite cu evenimentele |

Rulate o singură dată, la finalul sesiunii (2026-11-15), cu comanda de mai sus: toate cele 484 de
verificări au trecut (`RunsTest` 216, `ZonesSupportTest` 73, `TacticsFoulTest` 50,
`SetPieceTest` 92, `HeaderAndSaveTest` 28, `EventContractTest` 25). `GoalRulesTest` și
`Stage4Test` nu au fost rulate după schimbările din sesiune; în `Stage4Test` lista atributelor
fără pondere a fost adusă la `kicking`, `throwing`, `longThrows`.

## Aceleași meciuri ca în Python

Cu `-Dmatch2d.legacy=true`, același seed dă același meci în ambele motoare, până la ultimul
eveniment. Ca să fie posibil, ambele folosesc același generator de numere (MT19937, `Mt.java`)
și aceleași funcții matematice scrise de mână (`PMath.java` și `engine/pmath.py`): funcțiile
din bibliotecile celor două limbaje diferă la ultima zecimală, iar diferența se amplifică după
~87 de secunde de joc.

```bash
python tools/compare.py                # 5 meciuri de referință, comparate cadru cu cadru
```

```bash
python tools/compare_many.py           # 48 de meciuri variate (6 procese; --workers pentru altă valoare)
```

`compare_many.py` ține două meciuri întregi în memorie pe fiecare proces; cu 16 procese a rămas
fără memorie pe acest calculator, de aceea implicit sunt 6.

## Structură

```
src/main/java/match2d/engine/    motorul: Sim (bucla), Decision, BallPlay, Shooting, Movement,
                                 Positioning, Roles, Grid (A*), Zones, Runs (echilibrul
                                 formațiilor), SetPiece (faulturi, cartonașe, faze fixe),
                                 Tactics, Wt (ponderi), Mt + PMath (generator și matematică
                                 identice cu Python), MatchStats (notele și statisticile pe
                                 jucător), Highlights (fazele importante), Shootout
                                 (loviturile de departajare)
src/main/java/match2d/service/   Main (comenzile), Service (HTTP, și /results), TeamIo (validare), Json
src/main/java/match2d/league/    liga la televizor: LeagueServer (HTTP, ceasul, linia de comandă),
                                 League (programul, starea, fazele), Playlist (ceasul pe bucăți,
                                 rezumatul, reluările), SeasonStats (pack/unpack, tabelele de
                                 sezon), PyRandom + Py (tragerea la sorți, JSON și regulile lui Python)
src/main/resources/match2d/      viewer_template.html, pagina de afișaj
src/main/resources/match2d/league/  tv.html, standings.html, match_sheet.js, diamond_league.json
src/test/java/match2d/engine/    GoalRulesTest, Stage4Test, RunsTest, ZonesSupportTest,
                                 TacticsFoulTest, SetPieceTest, HeaderAndSaveTest,
                                 EventContractTest, KnockoutTest, MtCheck, PMathCheck
tools/                           compararea cu motorul Python
```
