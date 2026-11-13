# Design — media, competiții și ziua meciului

**Stare: propunere (2026-11-13).** Cele 8 întrebări din §11 sunt decise de utilizator (2026-11-13). Restul cifrelor sunt valori implicite alese de mine, de confirmat la testare.

---

## 0. Principii

- **Fiecare sistem nou produce povești.** Arbitrii, cotele, abonamentele și stadioanele trebuie să ajungă în presă, la conferințe și în reacțiile fanilor. Un sistem doar afișat (statistici fără efect) e **decor** și e marcat ca atare.
- **Scorul rămâne al motorului** (DESIGN_START_CARIERA §0). Nimic de aici nu decide un rezultat direct. Unde un sistem ar atinge motorul, e marcat **„atinge motorul”** și cere decizia ta.
- **Lume inventată.** Arbitrii, jurnaliștii și publicațiile au nume inventate (din țara ligii), iar textele sunt originale. Nu copiem un joc sau o persoană reală.
- **Datele de lume** se creează leneș, la prima folosire, ca `AnalystService.ensureAnalysts`, și intră în salvare (manifest).

---

## 1. Bug-urile de presă (de reparat întâi)

| Problema (cod) | Reparația |
|---|---|
| `POST /pressConference/{id}/respond` (`GameController.java:405`): nu verifică cine răspunde; se poate răspunde de mai multe ori, iar moralul se adună | `ClubActionAuthorizationService` cu `MANAGER_DUTY` pe echipa conferinței; al doilea răspuns → 409 `ALREADY_ANSWERED` |
| Același endpoint repornește calendarul tuturor | calendarul se reia doar când nu mai e nicio conferință în așteptare la vreun om |
| Reputația „pariată” (`confident` +1/−3, `aggressive` +2/−5) e doar salvată, nu se aplică | la decontarea meciului (`TeamPostMatchService`): victorie → `reputationIfWin`, înfrângere → `reputationIfLose`, egal → 0; dacă efectul e ≥ 3 în valoare absolută, apare și un articol scurt („și-a ținut cuvântul” / „vorbele s-au întors împotriva lui”) |
| O singură echipă umană primește conferința de dinaintea meciului (`CalendarEventDispatcher.java:200`) | câte o conferință pentru fiecare echipă umană care joacă; fiecare manager o vede doar pe a lui |
| Credibilitatea zvonurilor se uită după `counter` / `negotiating`, care nu mai există | înlocuită de jurnaliști (§2); până atunci, `talks` / `final_offer` / `fee_agreed` |
| Dosarul Media din inbox nu are `MEDIA_FORMER_PLAYER`, `MEDIA_FORMER_MANAGER`, `MEDIA_FINANCIAL_TROUBLE` | se adaugă în `inbox2.mediaCategories` |

**Implementat (2026-11-13, necomis):**
- **Răspunsul:** `GameController.respondToPressConference` autorizează prin `ClubActionAuthorizationService`: `MANAGER_DUTY`, iar la o conferință `BOARDROOM` `CLUB_LEGACY`, ca s-o poată da și patronul care controlează clubul. Răspunde 409 `ALREADY_ANSWERED` / `EXPIRED`, 400 la un răspuns necunoscut și 404 dacă nu există.
- **Calendarul** se reia doar când nicio echipă umană nu mai are o conferință în așteptare (`anyPendingPreMatch`).
- **O conferință pe echipă:** `PressConferenceService.generatePreMatchPressConferences` creează câte una pentru fiecare echipă umană care joacă. Cele nerăspunse și înlocuite devin `MISSED`.
- **Câmpuri noi:** `PressConference` are `competitionId` și `legNumber` (implicit 0, deci salvările vechi se încarcă).
- **Rezultatul evenimentului** are lista `pressConferences` (`teamId`, `pressConferenceId`, `question`).
- **Conferința mea în așteptare:** `GET /game/pressConference/pending` o întoarce; frontend-ul o deschide după fiecare reîmprospătare a stării și după Continue în camere.
- **Pariul pe reputație:** `settleReputationBet`, apelat din `TeamPostMatchService.generateMatchReport` (simularea rapidă și meciul live), pe reputația managerului. Articolul apare de la ±2 în sus (nu ±3: altfel o victorie n-ar produce niciodată articol). Răspunsul la o conferință de după meci nu mai pariază nimic.
- **Credibilitatea zvonurilor** recunoaște `talks` / `final_offer` / `fee_agreed`. Azi zvonurile vin însă doar din ofertele AI `pending`, deci tot 55–67% rămân, până la §2.
- **Teste:** `PressConferenceAnswersTest` (6), `RumourCredibilityTest` (1).

---

## 2. Jurnaliștii și zvonurile de transfer

Detaliază ce e deja decis în DESIGN_JUCATORI_SI_PIATA §3.6: zvonuri greșite, un insider de încredere, rată de succes vizibilă.

### 2.1 Cine sunt

Entitatea `Journalist` are: nume, țară, ligă, publicație (nume inventat), tip și rata de succes calculată.

| Tip | Câți | Ce publică |
|---|---|---|
| **Insider** | 1 pe ligă (doar în ligile cu reputație mare) + 1 internațional pentru cluburile cu reputația ≥ 8.000 (pragul analiștilor) | doar ce e aproape sigur: `fee_agreed` sau un transfer AI planificat în ≤ 2 zile |
| **Reporter** | 2 pe ligă | ofertele reale în discuție (`talks`), transferurile AI planificate de când apare „wanted” și, rar, un interes inventat |
| **Tabloid** | 1 pe ligă | multe zvonuri, majoritatea inventate, dar plauzibile |

**Rata de succes nu e un număr setat, ci rezultatul surselor.** Insiderul publică doar lucruri aproape sigure, deci iese în jur de 95%; greșește doar când o înțelegere cade la contractul personal. Tabloidul inventează, deci iese pe la 30–40%. Nimeni nu „aruncă zarul” pentru adevăr.

### 2.2 Zvonul ca obiect

Tabelul `rumour` are:
- jurnalistul, jucătorul, clubul care vinde, clubul care cumpără;
- treapta: `INTEREST` / `TALKS` / `DONE_DEAL`;
- ziua;
- sursa reală: id-ul ofertei, id-ul transferului planificat, sau nimic dacă e inventat;
- starea: `OPEN` / `TRUE` / `FALSE` și ziua decontării.

**Decontarea:**
- `TRUE`: jucătorul ajunge la clubul din zvon în fereastra curentă;
- `FALSE`: se închide fereastra fără mutare, sau jucătorul pleacă în altă parte.

**Rata de succes** = TRUE / (TRUE + FALSE), pe zvonurile decontate. Apare pe profilul jurnalistului și pe fiecare zvon, de exemplu „82% din 34 de zvonuri”.

### 2.3 Zvonurile inventate trebuie să fie plauzibile

Tabloidul alege:
- un jucător bun de la un club mai mic;
- un club care cumpără, cu reputație mai mare, buget suficient și **nevoie reală pe post** (titularul lui pe acea poziție e sub ratingul jucătorului).

Un zvon fals nu trebuie să se poată recunoaște din prima. Altfel rata de succes nu mai contează.

### 2.4 Scurgerile de informații din ofertele mele

O ofertă reală poate ajunge în presă:
- 20% pe zi cât e în `talks`;
- 60% când devine `fee_agreed`.

Așa presa poate afla de negocierile mele înainte să le anunț eu.

### 2.5 Volumul

În fereastra de transferuri, fiecare jurnalist publică cel mult un zvon pe zi. În afara ferestrei nu se publică zvonuri.

Managerul vede în inbox zvonurile despre:
- jucătorii lui;
- jucătorii pe care îi urmărește (shortlist / scouting);
- clubul lui, ca și cumpărător.

Restul apar doar pe pagina de știri (§3).

### 2.6 Efecte în joc

| Efect | Regula |
|---|---|
| Zvon despre jucătorul meu la un club mai mare (reputația ≥ a mea + 500) | **(decis)** jucătorii cu personalitatea FAME sau AMBITION: moral −3, o singură dată pe zvon; MONEY: nimic |
| Zvon că vreau jucătorul X | **(decis) fără efect pe preț**, nici la zvonurile reale, nici la cele inventate |
| Zvonuri în conferințe | întrebări despre ele (§9) |

### 2.7 Interfața

- În inbox rămâne „Transfer Wire”, cu jurnalistul și rata lui.
- Profilul jurnalistului: tip, publicație, rată, ultimele zvonuri și cum s-au încheiat.

---

## 3. Pagina de știri

**Ce există:** articolele există, dar fiecare e un mesaj în inboxul unei echipe umane:
- reacția după meci;
- zvonurile;
- declarațiile foștilor jucători și antrenori;
- articolele despre datorii;
- articolul de numire.

Nu există știri ale lumii: ce se întâmplă în alte ligi sau la alte cluburi nu se vede nicăieri.

### 3.1 Modelul

Tabelul nou `news_article` aparține lumii, nu unui inbox. Câmpuri: ziua absolută, sezonul, tipul, titlul, textul, autorul (jurnalist sau analist, opțional), etichetele (competiție, echipe, jucători) și importanța (0–100).

`NewsService.publish(articol, inboxPentru)` scrie articolul o singură dată. Inboxul managerilor implicați primește doar un link spre el. Serviciile actuale trec pe rând la acest flux, iar mesajele vechi din inbox rămân cum sunt.

### 3.2 Tipurile de articole

Toate pornesc din evenimente care există deja:

| Tip | Când | Date |
|---|---|---|
| Cronica meciului | meciuri importante: derby, primele 3, finale, scor cu diferență de ≥ 3 goluri, **surpriză** (rezultat cu probabilitate < 20%, §4) | `MatchEvent`, `Scorer`, cotele |
| Transfer încheiat | la executarea transferului (al meu sau AI planificat) | `Transfer`, `planned_transfer` |
| Zvon | §2 | `rumour` |
| Numire / demitere | oferte de muncă acceptate, demiteri | `ManagerHistory`, articolul de numire |
| Cursa pentru titlu | săptămânal, în ligile cu om | §4 |
| Promisiune ținută / încălcată | `PublicPromiseService.settle` | `PublicPromise` |
| Probleme financiare | deja există | `FinancialTroubleMediaService` |
| Serii și recorduri | ≥ 5 victorii sau înfrângeri la rând, golgheterul, primul punct al sezonului | `TeamCompetitionDetail.form`, `Scorer` |
| Arbitraj controversat | §5 | erorile arbitrului |

### 3.3 Volumul și textele

- **Volumul:** cel mult 5 articole mari pe ligă pe zi, alese după importanță. Restul intră într-o rubrică „Pe scurt”, cu un rând fiecare.
- **Textele:** fragmente scrise dinainte, cu același mecanism de rotație ca la numire (`press_fragment`, categorii noi). Se generează cu agenți, ca cele 985 de acum.

### 3.4 Pagina

- tab-uri: „Clubul meu”, „Liga mea”, „Lumea”, „Zvonuri”;
- filtru pe competiție;
- autorul are link spre profilul lui (jurnalist sau analist).

---

## 4. Șansele la titlu (și la retrogradare, cupe)

**Ce există:**
- predicția = clasamentul după ratingul echipei cu cea mai bună tactică, fără probabilități;
- motorul calculează deja probabilitatea de victorie / egal / înfrângere a unui meci (`OutcomeProbability` din `CanonicalMatchEvaluation`), iar `TacticSimulationService` evaluează deja o echipă contra tuturor adversarilor.

### 4.1 `TitleOddsService`

Pentru o ligă:
1. o evaluare pe echipă, cu lotul și tactica de acum (accidentările contează);
2. pentru fiecare meci rămas (`CompetitionTeamInfoMatch`, `team1` = gazda), probabilitatea V/E/Î din adaptor;
3. **10.000 de finaluri de sezon simulate** pornind de la clasamentul real (`TeamCompetitionDetail`): doar V/E/Î, fără goluri. La egalitate de puncte decide golaverajul de acum, apoi aleatoriu.

**Rezultatul,** pe fiecare echipă: P(titlu), P(primele 3 / locuri europene), P(retrogradare), locul mediu.

**Costul:** 20 de echipe × 38 de etape ≈ 380 de meciuri × 10.000 ≈ 3,8 milioane de trageri, deci sub o secundă. Partea scumpă e evaluarea fiecărei echipe; ea se face o dată pe ligă, după fiecare etapă.

**Când se calculează:**
- ligile cu om: după fiecare etapă;
- celelalte: la începutul sezonului și lunar, sau la deschiderea paginii, cu rezultatul păstrat până la etapa următoare.

### 4.2 Cupele

P(câștig) se obține simulând tabloul rămas: perechile deja trase rămân, iar tururile viitoare se trag aleatoriu în fiecare simulare.

### 4.3 Afișarea

**(decis)** Procent și cota echivalentă: „34% · 2,94” (cota = 1/p), fără marjă de casă de pariuri. Nu există pariuri în joc. Istoricul P(titlu) pe etape alimentează graficul „cursa pentru titlu”.

### 4.4 Cine le mai folosește

- presa: cursa pentru titlu și rezultatele surpriză (§3);
- analiștii: predicțiile de la început de sezon, iar la final „cine a avut dreptate”;
- patronul: așteptările lui;
- moralul fanilor: victoria ca favorit contează mai puțin decât una ca outsider (§6.1);
- conferințele: o promisiune publică în pericol (§9).

### 4.5 Implementat (2026-11-13, necomis)

- **`service/odds/LeagueOddsSimulation`** (pur): 10.000 de finaluri de sezon pornind de la clasamentul real. Dă P(titlu), P(top 3), P(League of Champions), P(retrogradare) și locul mediu.
- **`service/odds/CupOddsSimulation`** (pur): tabloul fix al cupei naționale. Câștigătorii avansează prin aceeași regulă ca tabloul real, `CupBracketService.nextSlot`, extrasă din `propagateWinner`. La egal se trag penalty-uri, 50/50.
- **`service/odds/TitleOddsService`:**
  - probabilitățile vin din `TacticSimulationService.currentTeamEvaluation` + `outcome` (adaptorul canonic, `team1` acasă);
  - o echipă care nu poate fi evaluată primește 45% / 27% / 28%;
  - cache în memorie până la următorul meci jucat în competiție;
  - locurile de League of Champions doar în prima ligă, retrogradarea din `LeagueQualificationDisplayService`.
- **Istoricul:** tabela `title_odds_snapshot`, câte un rând pe echipă și pe numărul de meciuri jucate, recalculat la aceeași etapă. E în salvarea v18, iar manifestul are acum 121 de tabele.
- **Când se calculează:** după fiecare etapă, pentru ligile cu manager uman (`GameAdvanceService`, după meciurile zilei).
- **Endpoint-uri:** `GET /odds/league/{id}`, `GET /odds/league/{id}/history`, `GET /odds/cup/{id}`.
- **Frontend:** secțiunea „Chances” în Competition hub (`/competition-view/:id/chances`) are tabelul de șanse cu procent și cotă, plus graficul „Title race” (primele 5 echipe, culori validate pe fundalul întunecat, tooltip pe etapă, tabel).
- **Limite:**
  - cupele europene (grupe + tragere la sorți) și Supercupa nu au încă șanse;
  - echipa folosită e cea din motor (formația salvată sau cea mai bună, primii 11 după rating), fără să scoată accidentații;
  - la un meci live, punctul etapei se completează abia la următoarea cerere a paginii.
- **Teste:** `LeagueOddsSimulationTest` (4), `CupOddsSimulationTest` (4), `TitleOddsServiceTest` (4).

---

## 5. Arbitrii

**Ce există:**
- arbitrul e doar o figură în animația live;
- în simularea rapidă, cartonașele galbene sunt aleatorii (0–4 pe echipă, `MatchSimulationService.java:460`) și se dau **doar la meciurile cu om**: meciurile AI nu au cartonașe deloc;
- în motorul live, galbenul vine dintr-o rată pe fault (`liveYellowCardRateMin` / `Spread`).

### 5.1 Entitatea

`Referee` are:
- nume, țară și vârstă (30–45 de ani; se retrage la 46);
- **nivelul** (1–20), care decide ce meciuri primește;
- **severitatea** (0,7–1,3), un multiplicator pe cartonașe;
- **consecvența** (1–20), care dă probabilitatea unei greșeli mari;
- numărul de meciuri arbitrate.

**Câți:** 1,5 × numărul de meciuri pe etapă în fiecare ligă (o ligă de 20 de echipe are 15 arbitri). Cei mai buni din fiecare țară formează lotul internațional pentru cupele europene.

### 5.2 Delegarea

Cu 2 zile înainte de etapă, fiecare meci primește un arbitru:
- **meciurile cu miză** (derby, primele 3, miza calculată din §4) primesc arbitrii cu nivel mare;
- un arbitru nu conduce aceeași echipă de două ori în 3 etape;
- la meciurile europene, arbitrul nu e din țara vreunei echipe.

Delegarea apare în prezentarea meciului, iar presa o poate comenta („arbitru sever la derby”).

### 5.3 Efectul în meci

- **Cartonașele (atinge motorul, puțin):** numărul tras în simularea rapidă și rata pe fault din motorul live se înmulțesc cu severitatea.
- **Cartonașe și la meciurile AI (decis): evenimente reale pe jucători, cu suspendări**, la fel ca la meciurile cu om. Consecințe de care trebuie ținut cont la implementare:
  - echipele AI vor pierde jucători suspendați, deci rezultatele AI se schimbă. IT-urile de calibrare și de piață trebuie rulate din nou și, la nevoie, recalibrate;
  - tabela `match_event` crește cu toate meciurile AI;
  - cine e galben sau roșu nu mai e ales la întâmplare din tot lotul (azi `Collections.shuffle` pe tot lotul, inclusiv rezervele). Se alege dintre jucătorii care au intrat pe teren, ponderat după post și după `PlayerSkills.aggression`.
- **Greșelile:** probabilitatea pe meci scade cu consecvența, de la 12% la consecvență 1 până la 1% la consecvență 20.
  - o greșeală e un penalty nedat, un gol anulat greșit sau un roșu nemeritat;
  - **(decis) greșeala e doar poveste și nu schimbă scorul.** Apare în analiza de după meci, în presă, în reacția fanilor și la conferință, iar nota arbitrului scade.

### 5.4 Nota și cariera

- După fiecare meci, arbitrul primește o notă 1–10, din consecvență și noroc, minus greșeli.
- La final de sezon, media notelor decide promovarea sau retrogradarea între niveluri; cei mai buni ajung internaționali.

### 5.5 Statisticile

- pe arbitru: meciuri, galbene pe meci, roșii, penalty-uri, greșeli, nota medie;
- pe echipă: „cu acest arbitru: V–E–Î”.

### 5.6 În presă

- Greșeala devine articol de tip „Arbitraj controversat”.
- La conferință vine întrebarea despre arbitraj (§9): „critic” (fanii +, dar risc de amendă din banii mei) sau „diplomatic” (fără efect).

---

## 6. Moralul fanilor, abonamentele și biletele

**Ce există:**
- la fiecare meci acasă, spectatorii = capacitatea × (60–80% aleatoriu + un bonus din reputația clubului + unul din reputația adversarului), cel mult 99% (`FinanceService.java:111`);
- prețul biletului = 25 + min(reputația / 400, 25): e fix și nu-l alege nimeni;
- nu există abonamente;
- nu există moralul suporterilor; `FrontCoachService` notează deja că ar fi nevoie de el.

### 6.1 Fundația: moralul fanilor

`FanMood` e un număr 0–100 pe fiecare club. Îl folosesc spectatorii, abonamentele, tonul feed-ului fanilor și, mai târziu, încrederea patronului.

| Ce îl mișcă | Efect |
|---|---|
| Rezultat față de așteptare (§4) | victorie ca favorit +1; victorie ca outsider +3; înfrângere ca favorit −3; înfrângere ca outsider −1 |
| Derby (azi: meci între două din primele 3 cluburi ale ligii după reputație) | ±4 |
| Promisiune publică ținută / încălcată | ±10 |
| Vânzarea celui mai bun jucător | −5 |
| Trofeu | +15 |
| Prețuri peste media ligii | −1 pe lună pentru fiecare 10% peste medie |
| Revenire spre 50 | 2% din diferență pe lună |

### 6.2 Cine stabilește prețurile

- **(decis) Patronul** (sau DG-ul, unde există). Managerul doar le vede.
- Un patron uman le setează pe o pagină; la AI se setează automat, după reputație și cerere.
- Se stabilesc în iulie: prețul abonamentului și prețul biletului (cu un multiplicator opțional pentru meciurile mari).

### 6.3 Vânzarea abonamentelor

Are loc din iulie până la prima etapă.

**Cererea** = fanii potențiali (din reputație) × moralul fanilor × efectul prețului × efectul verii. Efectul prețului depinde de raportul față de media ligii. Efectul verii crește cu o vedetă adusă și scade cu o vedetă vândută.

**Limite și ritm:** cel mult 60% din capacitate (restul e pentru bilete și oaspeți). Vânzările sunt zilnice, mai multe la început, și apar în inbox și în presă („record de abonamente”).

**Banii:**
- se încasează **la vânzare**, vara, deci ajută realist la fereastra de transferuri;
- prețul abonamentului e cam numărul de meciuri acasă × prețul biletului × (1 − 25%);
- abonamentul acoperă doar campionatul; la cupe se vând bilete separat.

### 6.4 La fiecare meci

- **Spectatorii** = abonații prezenți (85–95% dintre ei) + biletele vândute, până la capacitate.
- **Cererea de bilete** = moralul × adversarul × miza × efectul prețului.
- **Încasarea** vine doar din biletele vândute la meci; abonații au plătit deja.

### 6.5 Stadionul plin (decis, atinge motorul)

Avantajul de teren din motor (`homeAdvantage`) se înmulțește cu (0,9 + 0,2 × ocupare):
- stadionul gol dă ×0,9;
- stadionul la jumătate dă ×1,0, adică avantajul de azi;
- stadionul plin dă ×1,1.

Ocuparea folosită e cea a meciului respectiv, calculată înainte de start. La finalele pe teren neutru (§8) nu există avantaj, deci formula nu se aplică. IT-urile de calibrare se rulează din nou.

### 6.6 Statisticile

Abonați, prezența medie, încasări pe meci, comparația cu liga.

---

## 7. Stadioanele

**Ce există:** `Stadium` are numele, capacitatea (+ 5.000 pe nivel de extindere), VIP, catering, magazin, fast-food, parcare, sediul și terenurile de antrenament. **Dublură:** `Team.stadiumName`, `Team.stadiumCapacity`, `Team.stadiumId`. `FinanceService` folosește `Stadium` și cade pe `Team` doar dacă nu-l găsește.

### 7.1 O singură sursă: `Stadium`

Câmpurile de pe `Team` se citesc o dată, la import sau migrare, apoi se șterg (sau rămân doar pentru citire). Așa nu mai pot avea valori diferite.

### 7.2 Câmpurile noi

| Câmp | Valori |
|---|---|
| Orașul | text |
| Anul construcției | an |
| Suprafața | iarbă naturală / hibrid / sintetic |
| Dimensiunile | lungime 100–110 m, lățime 64–75 m |
| Acoperișul | deschis / parțial / închis |
| Proprietarul | clubul / primăria (atunci clubul plătește chirie pe meci) |
| **Categoria (1–4)** | decide ce meciuri poate găzdui stadionul (finale, meciuri europene) |

**Generarea:** din capacitate și reputație. Stadioanele mari primesc hibrid și categoria 4; cluburile mici pot avea sintetic și categoria 1–2.

### 7.3 Efectul

**(decis) Doar decor.** Suprafața și dimensiunile apar pe pagina stadionului și în presă; motorul nu se schimbă.

### 7.4 Schimbarea suprafeței

Poate fi un proiect nou în `FacilityUpgradeService`, care există deja.

---

## 8. Finalele pe teren neutru

**Ce există:**
- finala e un meci obișnuit, cu `team1` ca gazdă și avantaj de teren;
- motorul are `MatchVenue.NEUTRAL` (adaptorul suportă deja `applyHomeAdvantage = false`), dar `CanonicalRuntimeScoringService.java:175` refuză orice altceva decât `HOME`.

### 8.1 Alegerea stadionului

La începutul sezonului, fiecare cupă cu finală într-un singur meci primește **stadionul finalei**:
- **cupele naționale:** cel mai mare stadion de categoria ≥ 3 din țară, prin rotație (nu același doi ani la rând, dacă există alternative);
- **cupele internaționale:** un stadion de categoria 4 dintr-o țară participantă, tot prin rotație.

Alegerea se anunță în presă la început de sezon.

### 8.2 Avantajul de teren

- Dacă un finalist joacă pe stadionul lui, e acasă, cu avantaj normal; se întâmplă și în realitate și iese o poveste bună.
- Altfel meciul e `NEUTRAL`, fără avantaj de teren. Pentru asta se scoate restricția `HOME` din runtime.

### 8.3 Banii și spectatorii

- **Spectatorii:** fanii ambelor echipe, după moralul fiecăreia (§6.1), până la capacitatea stadionului.
- **Încasările:** după o cotă de 20% pentru organizator, restul se împarte 50/50 între finaliști.

### 8.4 Afișarea

Stadionul finalei apare în prezentarea meciului și în meciul live.

---

## 9. Conferințele de presă pe subiecte din joc (după §1–§5)

**Azi:** întrebarea vine dintr-o listă fixă, iar răspunsurile sunt aceleași 4 (`confident` / `cautious` / `aggressive` / `deflect`), oricare ar fi întrebarea.

**Propunerea:** un generator de subiecte ia situația din joc, în ordinea priorității. Fiecare subiect are răspunsurile și efectele lui:

| Subiect | Când apare | Răspunsuri → efecte |
|---|---|---|
| Zvon despre jucătorul meu | zvon deschis despre un titular (§2) | „Nu e de vânzare” → relația jucătorului cu mine +, dar dacă îl vând în aceeași fereastră, fanii −10 / „Orice e posibil” → jucătorul −moral / fără comentarii |
| Promisiune publică în pericol | P(s-o țin) < 30% (§4) | o reafirm → miza crește / recunosc → miza scade la jumătate, reputația − |
| Criticul de la numire | după 3 victorii la rând | „Ce mai spune X?” → relația cu analistul X − / „Fiecare are dreptul la o părere” → + |
| Arbitrajul | o greșeală împotriva mea (§5) | critic / diplomatic |
| Derby | meci cu un rival | promit victoria → pariu pe reputație și pe moralul fanilor / respect pentru adversar |
| Serie proastă | ≥ 3 înfrângeri | îmi susțin lotul → moral + / îi critic pe jucători → moral −, reputație + |
| Patronul / DG-ul | există deja (`BOARDROOM`) | rămâne |
| Altfel | — | întrebările generice de acum |

Pariurile pe reputație se decontează după meci, ca la §1.

---

## 10. Ordinea propusă

1. **§1 bug-urile:** mic, repară ce există.
2. **§4 șansele la titlu:** baza pentru presă, moralul fanilor și conferințe.
3. **§2 jurnaliștii + §3 pagina de știri.**
4. **§6 moralul fanilor + abonamentele.**
5. **§7 stadionul** (o singură sursă și câmpurile noi) **+ §8 finalele neutre.**
6. **§5 arbitrii.**
7. **§9 conferințele pe subiecte.**

---

## 11. Decizii (2026-11-13)

| # | Întrebarea | Decizia |
|---|---|---|
| 1 | Greșelile arbitrilor schimbă scorul? | **Nu**, doar poveste (§5.3) |
| 2 | Cartonașele la meciurile AI | **Evenimente reale pe jucători, cu suspendări** (§5.3); rezultatele AI se schimbă, deci se face recalibrare |
| 3 | Cine stabilește prețurile | **Patronul** (sau DG-ul); managerul doar le vede (§6.2) |
| 4 | Stadionul plin mărește avantajul de teren? | **Da, puțin**: × (0,9 + 0,2 × ocupare) (§6.5) |
| 5 | Suprafața și dimensiunile | **Doar decor** (§7.3) |
| 6 | Zvonurile îi neliniștesc pe jucătorii mei? | **Da, după personalitate**: −3 la FAME și AMBITION (§2.6) |
| 7 | Zvonurile despre ofertele mele cresc prețul? | **Nu** (§2.6) |
| 8 | Cum se afișează șansele | **Procent + cotă** (§4.3) |

**Consecință comună pentru 2 și 4:** ambele schimbă rezultatele meciurilor. Înainte de a le activa trebuie rulate IT-urile de calibrare (golurile pe meci, distribuția rezultatelor acasă / în deplasare), iar valorile se reglează dacă ies din țintă.
