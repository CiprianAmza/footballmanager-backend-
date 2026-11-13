# Design — jucători, rating și piața de transferuri

Document viu, completat pe măsură ce discutăm. Ultima actualizare: 2026-11-10.
Joc: **Argus Football World (AFW)**. Cod verificat: backend `13b0d86`, frontend `03e3e9f`.

Legat de: `DESIGN_START_CARIERA.md`.

---

## 1. Problemele reale (lista mea)

| # | Zonă | Problemă |
|---|---|---|
| 1 | UI | Nu există o pagină frumoasă unde să văd toate ligile în focus |
| 2 | UI | Scouting-ul e greu: nu văd ușor atributele și nu pot alege ce atribute să văd |
| 3 | Backend | Prețul de transfer e o formulă: fiecare club cere aproape aceeași valoare, fără negociere reală |
| 4 | Backend | Negocierea salariului lipsește: ofertă → răspunsul jucătorului/agentului → negociere → se poate supăra și pleca, sau poate lăsa de la el |
| 5 | Backend | La fel la prelungirea contractelor |
| 6 | Backend | Ofertele de transfer nu au negociere și clauze extra care să influențeze decizia |
| 7 | Backend | **Modelul de rating e greșit** (§2) |

---

## 2. Rating și atribute

### 2.1 Principiul (decis)

- **Ratingul e un rezumat, nu adevărul.** Jocul (AI, transferuri, valoare, UI) poate folosi ratingul. **Motorul de meci folosește atributele**, favorizând anumite atribute în funcție de tactică.
- **Nimic nu modifică ratingul direct.** Creșterea sau scăderea unui jucător (inclusiv după un sezon bun) se face prin **atribute**, iar ratingul se recalculează din ele.
- Un sezon bun nu trebuie limitat de un potențial decis dinainte de joc. Dacă joacă bine, crește.

### 2.1.1 Două seturi de ponderi (decis)

- Atributele importante depind de **poziție**.
- **Ponderile ratingului ≠ ponderile motorului.** Unele atribute contează mai mult în motor decât arată ratingul.
  - **Ponderile ratingului:** ce „vede” lumea (transferuri, AI, UI).
  - **Ponderile motorului:** valoarea reală pe teren, pe poziție (și favorizate de tactică).
- Consecință dorită: doi jucători cu același rating pot valora diferit pe teren. Cine se uită la atribute, nu doar la rating, găsește jucători subevaluați. Scouting-ul (problema #2) devine important.

**Două ratinguri (decis):**

| Rating | Calculat cu | Folosit la |
|---|---|---|
| **Rating** (cel obișnuit) | ponderile ratingului | transferuri, AI, valoare, UI |
| **Rating special** | ponderile motorului, pe poziție | calculul tactic, meciul |

Exemplu: doi jucători cu ratingul 250 pot avea ratinguri speciale complet diferite.

**Vizibilitatea ratingului special (decis):** e **vizibil mereu**, lângă ratingul obișnuit.

### 2.1.2 Fără plafoane (decis)

- **Ratingul nu are maxim.**
- **Atributele nu se opresc la 20.** La generare, maximul e 20. Prin antrenament un atribut poate trece de 20, dar rar. Șansa se aplică la un antrenament reușit (unul în care atributul ar fi crescut):

| Atributul ajunge la | Șansa, per antrenament reușit |
|---|---|
| 20 | 1 la 100 |
| 21 | 1 la 500 |
| 22 | 1 la 5.000 |
| 23 | 1 la 10.000 |
| 24 | 1 la 15.000 |
| 25 | 1 la 20.000 |
| fiecare nivel următor | încă +5.000 (26: 1 la 25.000 etc.) |

Ce contrazice codul acum:
- generarea se oprește la 19, iar 20 e rezervat atributelor puse manual (`CompetitionService.generateSkills`);
- antrenamentul limitează atributele la 1–19 și nu atinge un atribut de 20 (`TrainingService`);
- plafonul de rating la potențial (`potentialAbility`) trebuie scos.

**Fiecare punct contează mai mult (decis, 2026-11-11):** 20 nu e o categorie separată, ci aceeași mecanică cu valori mai bune (de exemplu Long Shots: șansa de gol 0,68 → 1; Positioning: șansa de 0 șuturi 25% → 5%; Passing: cota fixă de 70% la pase de gol). Pentru că 20 e atât de bun, se ajunge greu la el. Fiecare punct peste 20 trebuie să crească și mai mult șansele și ponderea. Codul de acum tratează 20 ca 19 în rating și aruncă eroare peste 20; asta se schimbă.
- **Long Shots (decis):** șansa de gol înainte de pressing e 80% la 20 (nu 100%) și crește cu 4 puncte procentuale pe fiecare nivel: 21 → 84%, 22 → 88% … 25 → 100% (practic de neatins). Sub 20 rămâne curba actuală (19 → 68%).
- **Positioning (decis):** la 20, distribuția de șuturi pe meci rămâne cea actuală (0 șuturi: 5%). Fiecare nivel peste 20 mută 1 punct procentual de la „0 șuturi” la „2 șuturi”: 21 → 4%, 22 → 3% … 25 → 0%.
- **Finishing la stilul de pase (decis):** din șansa de control, atacantul transformă `60% × Finishing / 19` sub 20 (19 → 60%, 18 → ~57%, 10 → ~32%); la 20 → 80%, apoi +4 puncte procentuale pe nivel (25 → 100%).
- **Pace la stilul de pase (decis):** la atacant, de la 20 în sus nu mai e plafonul de 25%. La mijlocași, bonusul de suprimare e 5% la 20 și +1 punct procentual pe nivel (21 → 6%); la fel penalizarea dată atacantului advers: 10% la 20, 21 → 11% etc.
- **Passing (decis):** dispare regula fixă „un 20 ia 70%”. Pasatorul golului se trage după o **pondere** din Passing, pentru toți: până la 19 ponderea = Passing; la 20 ponderea = 250, apoi +50 pe nivel (21 → 300 … 25 → 500). Într-o echipă obișnuită (restul cu Passing ~12), un 20 dă ~70% din pase, un 21 ~73%, un 25 ~82%.

**Peste 20 (decis, revizuit 2026-11-11):** șansele mici din tabel țin **doar de antrenament**. Creșterea în plus de după un sezon bun („crește ratingul cu x”) **nu are plafon**: în rating fiecare punct valorează la fel (20 → 21 cât 3 → 4), deci poate duce un atribut la 20, 21, 22 fără nicio aruncare. În practică se întâmplă rar.

**Cum se împart punctele (decis, 2026-11-11):**
- la „crește ratingul cu x” (Δ pe categorie): fiecare punct merge la un atribut ales **uniform la întâmplare** dintre atributele categoriei care încă apropie ratingul de țintă;
- la antrenament: alegere **aleatorie ponderată** (pasul 2).

**Ponderi complet separate (decis, 2026-11-11):**
- Ratingul (UI, scouting, transferuri, AI) are **propriul tabel de ponderi** pe poziție, fără să le împrumute de la motor. Punctul de pornire e vechiul tabel scris de mână (`MatchEngineConfig.DEFAULT_WEIGHTS`), mutat în configurația `game.rating-weights`.
- Ponderile motorului (atribute pe compartimente + cât contribuie fiecare poziție la atac/mijloc/apărare) rămân separate și configurabile în `compartment-scoring-weights-v1.yml`.
- AI-ul (alegerea formației) folosește **strict ratingul de bază**.

**Motorul pe două niveluri (decis, de implementat ca pas separat):**
1. **Ratingul special** se calculează din atribute, **independent de tactică**, pe poziția naturală a jucătorului.
2. **Tactica** pornește de la ratingul special și calculează valoarea jucătorului și a echipei în acea tactică, inclusiv pierderea când joacă pe alt post. Efectele speciale (de exemplu „atributul 20 dă o putere deosebită”) țin de tactică, nu de rating.

**Implementat (2026-11-11, necomis, varianta A decisă):** nivelul 1 = `PlayerSpecialProfile`: atac / mijloc / apărare din atribute, pe poziția naturală, fără tactică; ratingul special = combinația lor după ponderile poziției naturale (verificat exact în test). Nivelul 2 = `ContextualPlayerRatingCalculator.rate(profil, input)`: atributele favorizate de tactică, postul jucat (multiplicatori), familiaritatea (pierderea pe alt post), rol, sarcină, formă, moral. Singura diferență de scor față de înainte: tabelul de atribute vine de pe poziția naturală, deci contează doar la portar ↔ jucător de câmp (un fundaș pus în poartă e citit cu atributele de fundaș, nu de portar, și pierde prin familiaritate).

Atenție, în cod e acum invers: `computeOverallRating` folosește **intenționat** aceleași ponderi ca motorul. Comentariul din cod spune că tabelele separate au fost unificate ca să nu mai difere ratingul de valoarea din meci. Vreau înapoi separarea, de data asta voită, ca funcție de joc.

Stare: motorul pe atribute e o lucrare începută, **neterminată**.

### 2.2 Funcția inversabilă (decis)

Atributele au ponderi, iar ratingul se calculează din ele. Avem nevoie de trei operații:

| Operație | Intrare → ieșire | Folosire |
|---|---|---|
| **f: atribute → rating** | atributele jucătorului (+ poziția) → rating | ratingul afișat, transferuri, AI |
| **g: rating → atribute** | rating dorit (+ poziția) → set de atribute | generarea jucătorilor noi |
| **Δ: creștere de rating → modificări de atribute** | jucătorul X cu atributele lui + „să crească cu v” → exact ce atribute se schimbă și cu cât | creșterea la final de sezon, antrenament, declin |

Proprietăți cerute:
- `f(g(r)) = r`: un jucător generat cu ratingul r are exact ratingul r.
- `f(atribute + Δ(X, v)) = f(atribute) + v`: după aplicarea modificărilor, ratingul crește exact cu v.

### 2.3 Ce există deja în cod (push 2026-11-10)

| Bucată | Stare |
|---|---|
| **f** (atribute → rating) | există: `PlayerSkillsService.computeOverallRating`, cu ponderile motorului pe poziție (`PositionAttributeImportance`) |
| **g** (rating → atribute) | există, dar aproximativ: `CompetitionService.generateSkills` pune fiecare atribut la `rating/15 × importanță` + zgomot aleator. Nu garantează `f(g(r)) = r` |
| **Δ** (creștere → atribute) | **nu există** |
| Motorul de meci | folosește deja atributele (`PlayerValueService.computePositionalValue`). Ratingul e doar rezervă pentru jucătorii fără atribute |
| Rating modificat direct | **da, în mai multe locuri:** ratingul e plafonat la potențial (`min(rating calculat, potentialAbility)`) în `TrainingService` și `HumanService`, deci se poate desprinde de atribute; există și o ramură care schimbă doar ratingul, pentru jucătorii fără atribute |
| Preț de transfer | `TransferValueCalculator`: `rating³ × 20 × multiplicator de vârstă`, la fel pentru orice club. AI-ul acceptă de la prețul cerut în sus, contraoferă de la 80% |

Concluzie: jumătate din ce vreau există deja (motorul e pe atribute, f există). Lipsesc **Δ**, un **g exact** și **eliminarea modificărilor directe de rating** (inclusiv plafonul de potențial).

**Cum alege Δ atributele (decis, revizuit):** creșterea se bazează pe **statistici**. Cei mai buni jucători din fiecare ligă, la o categorie de statistici, cresc la atributele legate de acea categorie. (Prima variantă, „complet aleatoriu”, e înlocuită.)

| Categorie de statistici | Atribute care cresc (propunere, de completat) |
|---|---|
| Goluri | finalizare, șut, joc cu capul |
| Assisturi | pase, viziune |
| Driblinguri | dribling, tehnică |
| Deposedări, intercepții | marcaj, deposedare, anticipare |
| Dueluri aeriene câștigate | joc cu capul, săritură |
| Parade (portari) | reflexe, unu la unu |
| (de completat) | |

**Riscuri de știut:**
- **Calitatea statisticilor contează.** Auditul din proiect (`FOOTBALL_DATA_ANALYTICS_AUDIT.md`) spune că multe statistici individuale sunt acum sintetice, generate după scor și din atribute. Creșterea va fi doar atât de bună cât sunt ele.
- **Bucla „bogatul se îmbogățește”:** finalizare mare → mai multe goluri în statistici → finalizare și mai mare. Probabil e nevoie de o limită, de exemplu creșteri mai mici pe măsură ce atributul e deja mare.

**Creșterea e raportată la nivelul ligii (decis):**
- Dacă am fost cel mai bun din ligă la o categorie, iar jucătorii de top din ligă au, de exemplu, ratingul 90 (media ligii e 70), e normal să mă apropii de 90, pentru că am fost peste ei.
- Nivelul ligii e limita naturală: un jucător care domină o ligă slabă se apropie de nivelul acelei ligi, nu îl depășește mult. Ca să crească mai mult, trebuie să joace într-o ligă mai bună.

Model rezultat, în doi pași:
1. **Cât crește (v):** din performanța mea comparată cu nivelul ligii. Mă apropii de ratingul jucătorilor de top din ligă pe care i-am depășit.
2. **Ce atribute cresc (Δ):** din categoriile în care am excelat (goluri → finalizare, șut etc.), până la exact +v.

(Formula inițială cu diferența, `k × (referință − ratingul meu)`, a fost respinsă; vezi mai jos.)

**Cât crește (decis, înlocuiește formula cu diferența):** `v = 10–15% × ratingul de referință`, **indiferent de ratingul meu**. Creșterea mea e influențată de nivelul lor, nu de al meu.

| Referință | Creștere (oricare ar fi ratingul meu) |
|---|---|
| 90 | +9 – 13,5 |
| 250 (scara din cod, 1–300) | +25 – 37,5 |

De urmărit la testare: pe scara 1–300 creșterile sunt mari, iar un jucător deja peste referință crește și el. Dacă reiese prea rapid, se reglează procentul.

**Referința (decis, interpretare de confirmat):** „top din ligă după rating și statistica respectivă”. Interpretarea mea:
- se ia **topul ligii la statistica respectivă** (de exemplu topul marcatorilor);
- referința e **ratingul mediu** al jucătorilor din acel top;
- dacă sunt peste ei la statistică, mă apropii cu 10–15% de ratingul lor mediu.

Exemplu: top 5 marcatori au ratingul mediu 90; eu am dat mai multe goluri decât ei → +9–13,5 rating, distribuit pe finalizare, șut, joc cu capul. Interpretare **confirmată**.

**Regula finală (decis 2026-11-11, înlocuiește interpretarea de mai sus):** pe fiecare ligă și categorie se ia **topul 10 la statistică**. Cresc **doar primii 3**, față de ratingul mediu al celor **de sub ei** din top 10: locul 1 cu **15%** (media locurilor 2–10), locul 2 cu **10%** (media 3–10), locul 3 cu **5%** (media 4–10). Implementat (necomis). Driblingurile, deposedările și duelurile aeriene câștigate sunt statistici sintetice (din atribute; motorul nu simulează duelurile aeriene). **Paradele (decis 2026-11-11)** vin din meci: pentru portarul titular, șuturile pe poartă ale adversarului minus golurile primite, minim 0.

**Din ce atribute (decis):** din **toate** atributele jucătorului. Portarii și jucătorii de câmp au **seturi de atribute diferite, fără atribute comune**. Deci la un jucător de câmp pot crește orice atribute de câmp, iar la un portar orice atribute de portar.

Notă despre cod: acum `PlayerSkills` are toate atributele pentru toți jucătorii; jucătorii de câmp primesc atribute de portar mici (1–4), iar antrenamentul le filtrează cu `isRelevantAttribute`. Designul cere seturi separate.

**Întrebare deschisă (minoră):** chiar niciun atribut comun? Atributele mentale și fizice (de exemplu viteză, decizii, calm) sunt separate și ele pentru portari?

---

## 2.4 Antrenamentul (de refăcut complet)

**Implementat (2026-11-11, necomis):** actualizare lunară pe ligă (`simulation.training.update-day-of-month`, implicit 1; rulează în prima zi de antrenament de la ziua respectivă). Echipele umane: contoare pe atribut din focusul sesiunii. Echipele AI: contoare egale pe atributele compartimentului poziției naturale (din ponderile motorului). Intervalul vechi de 21 de zile a fost șters. Deocamdată doar vârsta influențează; facilitățile, staff-ul și personalitatea nu mai contează (vezi întrebarea deschisă).

**Ce vreau (decis):**
- Schimbările de atribute și rating din antrenament se aplică doar în **anumite zile din lună**.
- Zilele se aleg **pentru fiecare ligă**. La început pot fi aceleași pentru toate ligile.
- În aceste zile jucătorii își schimbă atributele (iar ratingul se recalculează) pe baza antrenamentelor.

**Ce atribute se antrenează (decis):**
- Ca antrenor, **aleg ce atribute vreau să antrenez.** Un antrenament specific conține **6–10 atribute** (numărul exact nu e încă stabilit).
- **Doar atributele antrenate pot crește.**
- **Vârsta influențează șansele** de creștere la aceste atribute.
- **Celelalte atribute** (neantrenate) au și ele o șansă să **scadă**.

**Jurnalul de antrenament (decis):**
- O **tabelă separată** salvează **strict** antrenamentele făcute de fiecare jucător: ce antrenament, ce atribute, când.
- În ziua de actualizare (ziua din lună a ligii), **toate atributele antrenate** de la ultima actualizare intră în șansa de a crește.
- **Ponderea inițială** a fiecărui atribut = **de câte ori a fost antrenat** de la ultima actualizare.
- Apoi vârsta (și eventual alți factori) modifică șansele.

Notă de implementare: tabela poate ajunge mare (mii de jucători × antrenamente zilnice). Pentru calcul sunt necesare doar intrările de la ultima actualizare, deci intrările vechi se pot șterge sau arhiva după fiecare actualizare, dacă nu vreau istoricul complet.

**Contoarele (decis):**
- Fiecare jucător are un contor pe fiecare atribut, pornit de la **0**.
- La fiecare antrenament care include un atribut, contorul lui crește cu **1**.
- (Practic, contoarele sunt suficiente pentru calcul; jurnalul detaliat e opțional, pentru istoric sau afișare.)

**Doar la echipele cu manager uman (decis).** La echipele AI, antrenamentul nu se simulează zi cu zi:
- se stabilește pe baza **tacticii antrenorului AI**;
- se presupune că s-au antrenat **doar atributele importante pentru acea tactică**, pe fiecare compartiment: **atac, mijloc, apărare, portar**.

**Optimizare (decis):** la echipele AI **nu se salvează nimic per jucător**. Contoarele se calculează pe loc în ziua de actualizare: tactica antrenorului → atributele importante pe compartiment → aceeași valoare de contor pentru fiecare jucător pe acele atribute. Doar echipele umane au contoare salvate.

Cazuri de margine (rezolvate):
- un jucător trece de la o echipă AI la una umană: contoarele pornesc de la 0 la noul club;
- un user preia o echipă AI: contoarele pornesc de la 0;
- antrenorul AI nu își schimbă tactica, deci cazul nu apare. Dacă se va schimba vreodată, regula rămâne: se folosește tactica din ziua de actualizare.

**Șansa de creștere a unui atribut (decis):**
- Se calculează o **șansă unică pentru fiecare atribut** în ziua de actualizare.
- Sub 20: `șansa = (20 − valoarea) × 5%`.

| Valoare | Șansă |
|---|---|
| 5 | 75% |
| 10 | 50% |
| 15 | 25% |
| 19 | 5% |

- La 20 și peste: șansele rare din §2.1.2 (1 la 500 pentru 21, 1 la 5.000 pentru 22 etc.).

Formula `(20 − v) × 5%` e **o idee de pornire**, nu e bătută în cuie.

**Ținta (decis):**
- Un jucător **junior** nu crește la mai mult de **2–3 atribute pe lună**.
- Numărul de creșteri pe lună e **raportat la vârstă**: cu cât e mai tânăr, cu atât crește mai des.

**Model (decis), într-o zi de actualizare:**
1. **Câte atribute pot crește luna asta** (după vârstă):

| Vârstă | Creșteri pe lună |
|---|---|
| ≤ 18 | media 3 (decis 2026-11-11) |
| 19–21 | media 2,5 (decis 2026-11-11) |
| 22–24 | 1–2 |
| 25–29 | 0–1 |
| 30+ | rar; predomină scăderea atributelor neantrenate |

   Valorile sunt **medii**, nu numere fixe (decis 2026-11-11): luna poate avea noroc sau ghinion. Numărul se trage strâns în jurul mediei (de câte ori reușesc `2 × media` încercări, fiecare cu șansa 50%). Pentru media 3: 3 în 31% din luni, 2 sau 4 în câte 23%, 1 sau 5 în câte 9%, 0 sau 6 în câte 2%. Celelalte vârste folosesc mediile 1,5 (22–24), 0,5 (25–29), 0,2 (30+).
2. **Care atribute:** se aleg dintre cele antrenate, cu pondere = `contor × (20 − valoare)` (antrenat des și încă mic → mai probabil). La 20+ se aplică șansele rare din §2.1.2.
3. Fiecare creștere = +1.

Pentru un jucător de 25–29 de ani creșterea vine în principal din sezonul bun (statistici, §2.3), nu din antrenament.

**Luna incredibilă (decis):** rar, un jucător poate avea o lună în care cresc **5–6 atribute**. În luna asta **același atribut poate crește de 2 ori**.

**Rarirate (decis):** **aceeași șansă la orice vârstă**; și un veteran poate avea o lună excepțională. Valoare propusă: **2% pe lună**, de reglat la testare.

**Ce limitează creșterea (decis 2026-11-11):** mediile pe vârstă de mai sus sunt **maximul**. Se ating doar când toate trei sunt la maxim, pentru atributul antrenat:
- **profesionalismul** jucătorului: 10 = ×1, ±0,05 pe punct (20 → ×1,5; 1 → ×0,55);
- **facilitățile de antrenament**: nivelul maxim urcă de la 10 la **20**; 10 = ×1, ±0,1 pe nivel (20 → ×2; 1 → ×0,1);
- **antrenorul delegat** pe tipul atributului (tehnică, fizic, tactic …): fiecare membru al staff-ului are, pe fiecare tip, de regulă **două atribute de antrenor**; media lor = ratingul lui pe acel tip. Maximul cere 20 + 20 **și** ca el să fie delegat pe acel antrenament. La echipele AI staff-ul e delegat optim automat. **La echipele umane (decis 2026-11-11):** un tip fără delegare validă (echipă abia preluată, salvare veche, antrenor plecat) folosește automat cel mai bun antrenor al clubului pe acel tip; delegarea explicită are prioritate.
- Cu cât factorii sunt mai slabi, cu atât creșterea scade, iar la valori mici apare chiar **scădere** a atributelor.
- **Tipurile de antrenament (decis 2026-11-11, se pot ajusta):** fiecare atribut al jucătorului aparține unui singur tip.

| Tip | Atributele jucătorului | Atributele antrenorului |
|---|---|---|
| Ofensiv | Finishing, Long Shots, Off The Ball, Crossing, Corners, Free Kick, Penalty Taking | Attacking + Set Pieces |
| Defensiv | Marking, Tackling, Ball Recovery, Heading, Bravery, Aggression | Defending + Duels |
| Tehnic | Dribbling, First Touch, Passing, Technique, Long Throws, Flair | Technical + Ball Work |
| Mental | Composure, Concentration, Determination, Leadership, Work Rate | Mental + Motivating |
| Tactic | Anticipation, Decisions, Positioning, Teamwork, Vision | Tactical + Game Reading |
| Fizic | Acceleration, Agility, Balance, Jumping Reach, Natural Fitness, Pace, Stamina, Strength | Fitness + Conditioning |
| Portar | Handling, Reflexes, One On Ones, Command Of Area, Kicking, Throwing | GK + Shot-stopping |

  Noi la staff: Set Pieces, Duels, Ball Work, Game Reading, Conditioning, Shot-stopping.
- **Formula (decis 2026-11-11, „produs”):** pentru tipul atributului, `q = (facilități/2) × (profesionalism/1,5) × (antrenor/2)`, unde antrenorul = ratingul antrenorului delegat pe tip (media celor două atribute, aceeași scară ca facilitățile: 10 → ×1, ±0,1). Fiecare creștere din bugetul lunar reușește cu șansa `q`; totul la 20 → `q = 1`, toate la 10 → `q ≈ 0,17` (junior ~0,5 creșteri/lună).
- **Scăderea:** sub `q ≈ 0,17` (club obișnuit) șansele de scădere cresc liniar, până la ×3 la `q ≈ 0`.

**Echipele AI în acest model (propunere):** jucătorii AI primesc **același buget lunar după vârstă**. Diferă doar alegerea atributelor: ponderi egale între atributele importante pentru tactica antrenorului. Bugetul garantează un ritm comparabil cu echipele umane, iar avantajul omului e că își alege țintit atributele.

**Întrebare deschisă:** la echipele AI, cu ce valori pornesc contoarele (de câte ori se consideră antrenat fiecare atribut), ca să crească în ritm comparabil cu echipele umane?

**Întrebare deschisă:** pe lângă vârstă, ce mai modifică ponderea inițială: calitatea staff-ului, facilitățile, personalitatea jucătorului, minutele jucate?

**Scăderea atributelor (decis, varianta combinată):**

| Vârstă | Ce scade | Valori propuse, de reglat |
|---|---|---|
| < 28 | rar, doar atributele **neantrenate de mult timp** | atribut neantrenat de ≥ 3 luni: 5% pe lună să scadă cu 1 |
| 28–30 | tranziție | ca mai sus, plus atributele fizice: 5% pe lună |
| > 30 | **mai ales cele fizice, chiar dacă sunt antrenate**; restul ca mai sus | 1–2 atribute fizice pe lună (antrenamentul poate încetini scăderea, nu o oprește) |

Notă de implementare: pentru „neantrenat de mult timp” e nevoie de data ultimului antrenament pe fiecare atribut, la echipele umane (contoarele se resetează lunar). La echipele AI, „neantrenate” = atributele care nu sunt importante pentru tactica antrenorului.

---

## 3. Negocieri

Trei negocieri:
1. **Transferul între cluburi:** suma, plus clauze extra care influențează decizia.
2. **Contractul cu jucătorul (salariu):** ofertă → răspunsul jucătorului/agentului → contraofertă → se poate supăra și pleca, sau poate lăsa de la el.
3. **Prelungirea de contract:** același mecanism ca la 2.

### 3.1 Ce există acum în cod

| Bucată | Stare |
|---|---|
| Transfer | o singură rundă: accept de la prețul cerut în sus, contraofertă de la 80%, altfel respins (`TransferOfferController.makeOffer`). La contraofertă, AI-ul acceptă dacă suma ≤ 1,3 × valoarea |
| Prelungire | o singură rundă: jocul îți arată direct cererea salarială (minim / cerere / maxim, `GET /contract/demand`); accept dacă salariul ≥ cerere și durata e ok, altfel respins (`ContractController.renew`) |
| Clauze | există câmpuri pe jucător: clauză de reziliere, procent la vânzarea următoare, prelungire opțională, bonus de apariție, bonus de gol, scădere de salariu la retrogradare (`/contract/setClauses`) |
| Promisiuni | există promisiunea de timp de joc (`/contract/promisePlayingTime`) |
| Agent | doar un câmp `agentId` pe jucător; nu există o entitate Agent |

### 3.2 Cu cine negociez (decis)

- **Cu agentul jucătorului.**
- **Dacă jucătorul nu are agent, negociez direct cu jucătorul.**

**Agentul (decis):** e o **entitate** a lumii, ca jurnaliștii, jucătorii și patronii. Are o **personalitate**, care decide ce urmărește în negociere:

| Personalitate | Ce urmărește |
|---|---|
| Stabilitate | contract **lung** pentru jucător |
| Câștig personal | bani mulți pentru el: **bonus la semnătură** / comision mare |
| Rulaj | să-și vândă jucătorii **cât mai des**; impune **clauze de reziliere cât mai mici** |

**Cine are agent (decis 2026-11-11):**
- La începutul jocului, **toți** jucătorii au agent.
- Un jucător **nou creat** (junior) pornește **fără agent**. Își ia unul în jurul vârstei de **18–20 de ani**, în funcție de **clubul** la care e și de **agenții colegilor** de la acel club.
- **Agenții la început (decis 2026-11-11):** pe fiecare națiune, agenții se creează la întâmplare, fiecare cu un portofoliu de **3–50 de jucători**; jucătorii națiunii se împart apoi la întâmplare între ei. Personalitatea se trage uniform (o treime fiecare), iar răbdarea uniform între 3 și 10 runde (implicite alese de agent, de confirmat la testare).
- Alegerea (decis): la întâmplare dintre agenții colegilor, fiecare cu șansa proporțională cu câți colegi de la club reprezintă deja. Așa, agenții se adună pe cluburi.

**Memoria agentului (decis):** agentul ține minte relația cu mine și o aplică la **toți jucătorii lui**. O negociere ruptă urât sau o promisiune încălcată îi face pe toți jucătorii lui mai greu de adus; afacerile bune îi fac mai ușor de adus.

**Pagina agentului (decis):** când apăs pe agent, văd:
- personalitatea lui;
- relația lui cu mine;
- ce jucători are sub contract.

**Concedierea agentului (decis):**
- Dacă agentul unui jucător de-al meu vine și cere o mărire de contract, pot **cere jucătorului să-și concedieze agentul**.
- Cererea **scade relația agentului cu mine**.
- Jucătorul decide **în funcție de cât de multă încredere are în mine**. Dacă acceptă, își concediază agentul și **își ia alt agent**.

**Noul agent (decis):** **la întâmplare**, pentru început. (Idei pentru mai târziu: jucătorul își alege agentul după personalitatea lui, sau eu recomand un agent cu care am o relație bună.)

**Costul (decis):** scade **relația agentului cu mine**, deci agentul devine **mai dur** în negocieri cu mine (pentru toți jucătorii lui). Încrederea jucătorului în mine nu scade în plus.

**Scara relației (decis 2026-11-11):** 0–100, pornește de la 50; **sub 10** agentul refuză orice negociere cu mine. Relația ține de manager (persoană), nu de club.

| Eveniment (decis 2026-11-11) | Relația |
|---|---|
| Contract încheiat | +5 |
| Agentul pleacă de la masă (oferta prea departe) | −10 |
| Refuz oferta „take it or leave it” | −5 |
| Promisiune încălcată | −15 |
| Cer jucătorului să-și concedieze agentul | −20 |

**Pragul (decis):** dacă relația cu un agent scade prea mult, **agentul refuză complet să mai negocieze cu mine**. Practic, toți jucătorii lui devin inaccesibili pentru mine.

### 3.3 Cum decurge o negociere

**Răbdarea (decis):** fiecare agent primește la întâmplare un **număr de runde de negociere, între 3 și 10**. E o trăsătură a agentului: unii sunt mai răbdători, alții mai puțin.

**Ce înseamnă răbdarea (decis):** mai multe runde **nu** înseamnă că agentul acceptă mai puțin. Înseamnă doar că **nu pleacă de la masă** dacă negocierea se lungește. Cât cere depinde de personalitatea lui și de relația cu mine, nu de răbdare.

**Finalul negocierii (decis):**
- **Înainte de ultima lui rundă**, agentul pune pe masă o ofertă **„take it or leave it”**.
- **Oricând** (chiar din prima rundă), dacă oferta mea e **prea departe** de ce vrea el, agentul **se oprește**.

**Cât cere, după relație (decis 2026-11-11):** la relația 50 cere exact valoarea jucătorului; la 10 (pragul de refuz) cu 40% mai mult; la 100 cu 25% mai puțin; liniar între. Ofertele se compară cu această cerere; pragul „prea departe” rămâne raportat la valoarea jucătorului.

**Cererea concretă (implicite, de reglat la testare):** Stabilitate → 4 ani, doar salariu; Câștig personal → 2 ani + bonus la semnătură cât 20% din cerere; Rulaj → 2 ani + clauză de reziliere = valoarea de transfer. Salariul completează restul punctelor. Oferta „take it or leave it” e chiar cererea lui. Fiecare rundă: ajung la cerere → acord (+5 relație); sub prag → pleacă (−10); altfel runda următoare. După runda răbdare−1 vine oferta finală; refuzată → −5. Jucătorul fără agent: răbdare 3, prag 50%. (`AgentNegotiation`)

### 3.4 Punctele de contract (decis)

Ca ofertele să se poată compara, totul se transformă într-o monedă comună: **puncte de contract**. E nevoie de trei funcții:

| Funcție | Intrare → ieșire | Rol |
|---|---|---|
| **Valoarea jucătorului** | reputația (și ratingul) jucătorului → puncte de contract | cât „valorează” el, deci cât vrea |
| **Valoarea ofertei mele** | oferta mea (salariu, durată, bonusuri, clauze) → puncte de contract | cât valorează ce ofer |
| **Cererea agentului** | punctele de contract ale jucătorului → o cerere concretă de contract, cu clauze | ce pune agentul pe masă (inclusiv oferta „take it or leave it”) |

Comparația: `puncte(oferta mea)` vs `puncte(jucător)`. Dacă diferența e prea mare, agentul se oprește.

**Salariul e relativ la echipa mea (decis):**
- Salariul cerut e mereu **raportat la salariile din echipa mea**.
- Jucătorul **se poziționează în ierarhia echipei** după reputația și ratingul lui. Exemplu: dacă ar fi al 3-lea cel mai valoros din lot, cere un salariu în jurul celui de-al 3-lea salariu din echipă.

**Valoarea jucătorului (decis 2026-11-11, pentru moment):** doar din **rating** (jucătorul nu are încă reputație în cod): salariul de pe rangul lui, după rating, în lotul meu (al r+1-lea cel mai mare salariu, dacă r colegi au rating mai mare; sub toți → cel mai mic; lot fără salarii → salariul de bază după rating).

**Valoarea ofertei (decis 2026-11-11):** în „salariu lunar echivalent”: salariul + bonusul la semnătură împărțit la lunile contractului + 5% din cerere pe fiecare an peste primul + 10% din cerere pentru o clauză de reziliere ≤ valoarea de transfer (fără clauză sau mai mare → 0). Personalitatea **dublează** componenta preferată. Exemplu: cerere 100, ofer 90 pe 3 ani → 100 pentru un agent obișnuit, 110 pentru Stabilitate.

**Personalitatea agentului (decis):** personalitatea = **ponderile** din funcția „valoarea ofertei mele”:

| Personalitate | Ce valorează mai mult în oferta mea |
|---|---|
| Stabilitate | durata contractului |
| Câștig personal | bonusul la semnătură / comisionul |
| Rulaj | o clauză de reziliere mică |

Aceeași ofertă valorează deci puncte diferite pentru agenți diferiți.

**„Prea departe” (decis):** pragul la care agentul se ridică de la masă depinde de **personalitatea** lui, cu un **minim absolut**: o ofertă sub **50%** din punctele jucătorului e mereu prea departe, pentru orice agent.

| Personalitate | Pleacă dacă oferta e sub (propunere, de reglat) |
|---|---|
| Câștig personal (cel mai sensibil) | 80% |
| Rulaj | 70% |
| Stabilitate (cel mai tolerant) | 60% |
| Oricine | 50% (minim absolut) |

### 3.5 Transferul între cluburi

Problema de acum: prețul cerut e aceeași formulă (`rating³ × 20 × vârstă`) la orice club.

**Ce decide prețul cerut (decis):**

| Factor | Efect |
|---|---|
| **Contractul rămas** (esențial) | cu cât mai puțin contract, cu atât mai ieftin. Dacă mai are 1 an și **nu vrea să prelungească**, clubul caută să-l vândă, chiar și pe sume mici |
| **Importanța în echipă** | titularul de bază costă mai mult decât rezerva cu același rating |
| **Situația clubului** (esențial) | un club cu probleme vinde mai ieftin; exemplu: **după retrogradare**, clubul își vinde jucătorii mai ieftin |
| **Clauza de reziliere** | dacă există, e **prețul maxim** |
| **Interesul altor cluburi** | are sens: mai mulți cumpărători → preț mai mare |
| Cine cumpără (rival, club bogat) | **nu** contează neapărat |

**Clauze extra într-o ofertă de transfer (decis):**
- plata în rate;
- bonusuri la performanță (de exemplu +X la 20 de goluri sau la titlu);
- procent la vânzarea următoare;
- clauză de răscumpărare (clubul vânzător îl poate lua înapoi la un preț fix);
- împrumut cu opțiune sau obligație de cumpărare.

**Implementat (2026-11-12):** rate (0–3, una pe sezon), procent la vânzarea următoare (0–50%), clauză de răscumpărare (2 sezoane).

**Bonusuri la performanță (decis 2026-11-12):** o sumă când jucătorul dă N goluri într-un sezon pentru mine (N între 1 și 60) și o sumă dacă iau titlul ligii. Valabile 2 sezoane, plătite o singură dată, la finalul sezonului în care se îndeplinesc. Pentru clubul vânzător contează ca ratele, înmulțite cu șansa estimată: la goluri = golurile lui de anul trecut / N pe sezon; la titlu, după locul meu de anul trecut în ligă: 1 → 50%, 2 → 25%, 3 → 15%, altfel 5% pe sezon; pe 2 sezoane = 1 − (1 − p)². (De reglat la testare.)

**Împrumut cu opțiune / obligație (decis 2026-11-12):** fără opțiune → regula de acum (taxa 5–10% din valoare, jucătorul nu e în primii 15). Cu opțiune → în plus, prețul opțiunii ≥ prețul real. Cu obligație → e o vânzare amânată: taxa + prețul obligatoriu × ponderea ratelor ≥ prețul real; regula primilor 15 nu se aplică.

**Prețul și negocierea cu clubul (decis 2026-11-12):** preț real = valoarea de piață × contract rămas (3+ ani 1,0 / 2 ani 0,9 / 1 an 0,75 / expiră 0,5) × vrea să plece 0,7 × importanță (titular 1,2 / rotație 1,0 / rezervă 0,8) × club cu probleme 0,8 × (1 + 10% pe alt club interesat, max +30%), plafonat la clauză. Cererea clubului = prețul real × aleator [−35%; +40%] (vizibilă); minimul = cererea − aleator 0–30% (ascuns). La fiecare rundă clubul coboară cu 25–75% (aleator) din distanța până la oferta mea, niciodată sub minim; sub 80% din minim se ridică de la masă. Răbdare 3–10 runde pe club; în ultima, ultimatum. Eșec → 3–10 zile fără negociere pentru acel jucător. Discuțiile cu clubul nu blochează ziua.

**Fără schimb de jucători (decis).**

**Puncte de transfer (decis):** ca la agent, oferta de transfer se transformă în **puncte de transfer** pentru clubul vânzător, cu ponderi după situația clubului:

| Situația clubului vânzător | Ce prețuiește |
|---|---|
| Probleme financiare / retrogradat | bani acum; ratele valorează puțin |
| Club stabil | acceptă rate; prețuiește procentul la vânzarea următoare |
| Club care pierde un titular important | prețuiește clauza de răscumpărare |

### 3.7 Regula de siguranță: piața nu trebuie să înghețe (decis)

Toate regulile noi (jucători care nu mai vor să plece după prelungire, agenți care refuză să negocieze, praguri, clauze) nu trebuie să ducă la o piață în care nu se mai fac transferuri.

Cum ne asigurăm:
- **Volumul AI rămâne cel de acum.** Transferurile AI se calculează cu algoritmul existent, considerat corect; regulile noi le doar împart pe zile și decid clubul ales. Numărul total de transferuri AI nu trebuie să scadă.
- **Vânzări forțate:** un club cu lotul peste limită sau cu un jucător în ultimul an de contract, care nu vrea să prelungească, **trebuie** să vândă, chiar ieftin (§3.5).
- **Măsurare la fiecare schimbare:** numărul de transferuri pe fereastră se compară cu cel de acum. Există deja teste care pot face asta: `TransferWindowReportIT`, `TransferEconomyFuzzIT`, `AiTransferMarketBenchmarkIT`, `FullCareerSimulationIT` (`mvn verify -Pcareer`). Dacă volumul scade cu mult (de exemplu peste 20%), pragurile se relaxează.

### 3.6 Transferurile AI pe durata ferestrei (decis)

**Acum, în cod:** cluburile AI își fac transferurile în bloc, la procesarea de final de sezon (`EndOfSeasonProcessor`, în mai multe treceri). Algoritmul e corect („fair”), dar totul se întâmplă deodată.

**Ce vreau:**
- Transferurile AI se **împart pe zilele ferestrei de transferuri**. Exemplu: fereastra are 30 de zile, AI-ul are n transferuri planificate, deci se fac cam **n/30 transferuri pe zi**.
- Consecința: dacă vreau un jucător care tocmai s-a transferat, **nu-l mai pot lua**. Piața se mișcă și fără mine.
- Un jucător **transferat** sau care și-a **prelungit contractul** în această fereastră **nu mai vrea să plece** (în aceeași fereastră).
- Un jucător **planificat să fie transferat în zilele următoare** are pe el informația **„wanted”** (dorit).
- Dacă îi fac o ofertă unui jucător „wanted”, **transferul lui planificat se îngheață** până se rezolvă oferta mea.

**Amânarea și decizia finală (decis):** nu există „ofertă eșuată”. Mecanismul:
- Transferul planificat de AI se **amână cu 3 zile** de fiecare dată când:
  - clubul vânzător îmi răspunde la ofertă, sau
  - negociez cu jucătorul (agentul) o ofertă.
- Amânările nu pot trece de **ultima zi a ferestrei**.
- În ultima zi transferul se face: **jucătorul alege oferta cea mai bună pentru el** dintre ofertele cluburilor AI și oferta mea.

**„Cea mai bună pentru el” (decis, revizuit):** contează trei lucruri, iar **personalitatea jucătorului** decide ce cântărește cel mai mult:

| Factor | Ce măsoară |
|---|---|
| **Banii** | punctele de contract ale ofertei (§3.4) |
| **Clubul** | reputația clubului și competițiile în care joacă |
| **Titularizarea** | unde s-ar situa el ca rating pe poziția lui în acel club (titular sau nu) |

| Personalitatea jucătorului | Alege |
|---|---|
| Atras de bani | oferta cu cele mai multe puncte de contract |
| Atras de faimă | clubul cu reputația cea mai mare |
| Ambițios | clubul unde poate fi titular |

Două personalități lucrează împreună: **agentul** decide cum se negociază contractul (§3.4), **jucătorul** decide ce club alege.

**Oferta unui club AI (decis):** nu e o ofertă negociată, ci se **calculează** cu aceeași funcție, raportată la echipa AI respectivă:
- jucătorul are ratingul x și reputația y → în echipa AI s-ar situa cam pe locul n;
- deci salariul ar fi cam al n-lea salariu din echipa aceea, iar valoarea contractului cam atât;
- rezultă un punctaj de contract pentru fiecare club AI interesat.

Consecință naturală: un club cu salarii mari oferă mai multe puncte decât unul sărac pentru același jucător.

**Fluxul complet (decis, clarificat):**
1. **La începutul ferestrei** se calculează **toate** transferurile AI, inclusiv alegerea jucătorului (personalitatea jucătorului se aplică **chiar aici**, la planificare).
2. Transferurile planificate se **împart pe zile**, ca să pară că se fac regulat, zi de zi, și să apară în presă.
3. Dacă un manager uman face o ofertă pentru un jucător planificat, transferul lui **se oprește până când jucătorul alege între mine și clubul AI** ales la planificare (cu amânările de 3 zile, cel mult până în ultima zi).
4. **„Wanted” ascunde alegerea deja făcută:** pe jucător apar **toate cluburile care au avut șanse**, nu doar cel ales. Ca manager, nu știu că jucătorul a ales deja.

(Întrebarea despre acceptarea sumei de transfer de către clubul vânzător nu se aplică la AI: totul e calculat la planificare.)

**Când apare „wanted” (decis, revizuit):** transferurile se împart pe zile, iar „wanted” apare pe un jucător **cu 5 zile înainte** de ziua în care e planificat transferul lui. (Prima variantă, „de la începutul ferestrei”, e înlocuită.)

**Nu știu când a apărut „wanted” (decis):** ca manager, nu văd de când are jucătorul eticheta. Pot avea 5 zile sau doar una. Incertitudinea face piața mai captivantă.

**Presa și zvonurile de transfer (decis):**
- Presa poate publica **zvonuri greșite** („X va ajunge la clubul Z”) care nu se întâmplă.
- Există și **jurnaliști de încredere**, care dau mereu știri corecte, în stilul unui insider celebru de transferuri. În joc ar fi un personaj inventat, nu persoana reală.

**Fiabilitatea jurnaliștilor (decis):** e vizibilă: fiecare jurnalist are o **rată de succes a predicțiilor**.

---

### 3.8 Discuții despre transferuri cu patronul, directorul general și agenții (idee, 2026-11-13)

**Ideea (de la utilizator):** pe lângă negocierea propriu-zisă cu un club sau cu un agent (§3.2–3.5), există **discuții de planificare** despre transferuri. Au loc față în față, de exemplu **la un hotel**, cu:
- **patronul**;
- **directorul general** (la cluburile care au unul, DESIGN_START_CARIERA §5);
- **agenții** jucătorilor.

**Cum decurge o discuție de planificare (idee):**
1. **Analiza mea:** stabilesc ce poziții trebuie întărite (de exemplu fundaș central, extremă stângă).
2. **Propunerile:** patronul propune ceva, eu propun ceva, iar directorul general propune același lucru sau altceva (poate agenda lui, DESIGN_START_CARIERA §5).
3. **Negocierea:** ne înțelegem asupra pozițiilor și asupra jucătorilor vizați.
4. **Banii:** se discută **salariile estimative** și **suma de transfer posibilă** pentru fiecare jucător, evident doar în ipoteza că jucătorul ar accepta să vină.

**Discuția cu un agent la hotel (idee):** agentul spune, fără angajament, cât ar cere jucătorul lui ca salariu și dacă ar fi dispus să vină. Nu e încă o negociere de contract (§3.3); e o sondare.

**Ce produce discuția (decis 2026-11-13):** o **listă de ținte aprobate**. Nu pot face o ofertă unui jucător pe care patronul nu l-a aprobat.

**La dezacord (decis 2026-11-13):** decide **mereu patronul**. Excepție: **îmi asum eu jucătorul**. Garantez pentru el ca antrenor, iar patronul îl aprobă pe răspunderea mea.

**Garanția antrenorului (idee, de stabilit):** dacă jucătorul pentru care am garantat nu confirmă, încrederea patronului în mine scade; dacă reușește, crește. De stabilit: cum se măsoară „confirmă” (rating mediu, meciuri ca titular, după un sezon?), cât de mare e efectul și câte garanții pot avea deodată.

**Spionajul făcut de mine (idee, 2026-11-13):** ca manager, pot merge eu la meciurile altor echipe ca să urmăresc jucători. Ce văd acolo îmi dă argumente în discuția cu patronul, pentru a-mi aproba ținta sau ca bază pentru garanția mea.

De stabilit:
- ce costă: o zi lipsă de la antrenament sau de la club, deplasarea;
- cât de precisă e impresia mea față de cea a unui scouter (după experiența și atributele mele de antrenor?);
- dacă presa sau clubul jucătorului află că am fost în tribună („l-a urmărit pe X”), cu efect asupra prețului și asupra zvonurilor.

**Locul discuției, pe ecran (idee):** fiecare discuție are o **imagine frumoasă cu locul** unde are loc (hotel, restaurant etc.), ca fundal al ecranului de discuție. Locul se alege după situație. Propunerea mea:

| Situație | Locul |
|---|---|
| Planificarea ferestrei cu patronul | restaurantul unui hotel elegant, la cină |
| Patronul e nemulțumit (încredere scăzută) | biroul patronului, la club: mai rece, mai formal |
| Discuție doar cu directorul general | o cafenea discretă sau barul hotelului |
| Directorul ambițios cu agenda lui | un separeu într-un restaurant, „între noi” |
| Sondarea unui agent | holul unui hotel din orașul jucătorului |
| Agent pentru un jucător mare / transfer scump | terasa unui hotel de lux sau un club privat |
| Club mic, buget mic | o masă simplă într-un restaurant local sau sala de ședințe de la stadion |
| Discuție de urgență în ultima zi a ferestrei | un salon de aeroport sau o cameră de hotel, noaptea |

Imaginea se poate schimba și cu ora (cină, noapte) și cu sezonul.

**Legături cu ce există:**
- încrederea patronului și a directorului în mine (DESIGN_START_CARIERA §5.0.1): o propunere a lor pe care o refuz sau o accept mută încrederea, ca la dorințele lunare ale directorului;
- directorul ambițios poate împinge un jucător pentru că are o înțelegere cu agentul lui (DESIGN_START_CARIERA §5, „mesaje cu sursă incertă”);
- bugetul de transferuri și cel de salarii ies din discuția cu patronul, nu doar dintr-o cifră fixă.

**Întrebări deschise:**
- Când au loc discuțiile: înainte de fiecare fereastră de transferuri, sau oricând le cer eu?
- Se pot ține promisiunile făcute în discuție (de exemplu „îți aduc un fundaș central”)? Dacă nu, ce pierd?
- Cât de exacte sunt estimările de salariu și de preț? (De exemplu, cu o marjă care scade dacă am un scouter bun sau o relație bună cu agentul.)
- De unde vin imaginile locurilor: un set fix de ilustrații (câte una sau mai multe pe tip de loc), generate o dată și păstrate în proiect?

## 4. Întrebări deschise (index)

1. ~~Δ: după ce criteriu se aleg atributele?~~ Decis (revizuit): după categorii de statistici, cei mai buni din ligă cresc la atributele legate (§2.3)
5. ~~Cine crește?~~ Decis: creșterea e raportată la nivelul ligii; cei care au fost peste jucători mai bine cotați se apropie de nivelul lor (§2.3)
6. ~~Cât crește?~~ Decis: 10–15% din ratingul de referință, indiferent de ratingul meu (§2.3)
7. ~~Nivelul de referință?~~ Decis: topul ligii la statistica respectivă, ratingul lor mediu (§2.3)
2. ~~Δ aleatoriu: din ce atribute?~~ Decis: din toate; portarii și jucătorii de câmp au seturi separate (§2.3)
3. ~~Ratingul special: vizibil?~~ Decis: vizibil mereu (§2.1.1)
8. ~~Creșterea din statistici poate trece de 20?~~ Decis: orice creștere poate, cu aceleași șanse mici (§2.1.2)
9. ~~Antrenament: ce decide schimbarea?~~ Decis: aleg 6–10 atribute; doar ele cresc, cu șanse după vârstă; celelalte pot scădea (§2.4)
10. Ce mai influențează șansele de creștere, pe lângă vârstă? (§2.4)
12. Echipele AI: ce valori primesc contoarele, ca ritmul să fie comparabil cu echipele umane? (§2.4)
13. ~~Cum se combină șansa cu contoarele și vârsta?~~ Decis: buget lunar după vârstă + alegere ponderată `contor × (20 − valoare)` (§2.4)
14. ~~Luna incredibilă: cât de rară?~~ Decis: aceeași șansă la orice vârstă, propus 2% pe lună (§2.4)
15. ~~Noul agent, după concediere?~~ Decis: la întâmplare, pentru început (§3.2)
16. ~~Dacă jucătorul refuză să-și concedieze agentul, ce pierd?~~ Decis: relația cu agentul scade, deci devine mai dur; sub un prag, refuză orice negociere cu mine (§3.2)
17. ~~Cum decurge o negociere?~~ Decis: 3–10 runde; ofertă finală înainte de ultima rundă; se oprește oricând dacă oferta e prea departe (§3.3)
18. ~~Personalitatea agentului = ponderile ofertei?~~ Decis: da (§3.4)
19. ~~Cât de departe e „prea departe”?~~ Decis: după personalitate, cu minim absolut 50% (§3.4)
20. ~~Ce decide prețul cerut?~~ Decis: contract rămas, importanță, situația clubului, clauza de reziliere ca maxim, interesul altora (§3.5)
21. ~~Ce clauze extra pot pune într-o ofertă de transfer?~~ Decis: toate, mai puțin schimbul de jucători (§3.5)
27. ~~Puncte de transfer?~~ Decis: da, cu ponderi după situația clubului vânzător; plus regula ca piața să nu înghețe (§3.5, §3.7)
22. ~~Oferta mea pentru un „wanted” eșuează?~~ Decis: nu există eșec; amânare de 3 zile la fiecare răspuns/negociere, până la ultima zi; atunci jucătorul alege cea mai bună ofertă (§3.6)
24. ~~„Cea mai bună ofertă pentru el”?~~ Decis: bani (puncte de contract), club (reputație, competiții), titularizare, cântărite după personalitatea jucătorului (bani / faimă / ambiție); ofertele AI se calculează din ierarhia salarială a fiecărui club (§3.6)
25. ~~Ultima zi: doar ofertele cu suma acceptată?~~ Nu se aplică: transferurile AI sunt calculate complet la începutul ferestrei; „wanted” arată toate cluburile care au avut șanse (§3.6)
23. ~~Cu câte zile înainte apare „wanted”?~~ Decis (revizuit): cu 5 zile înainte de ziua planificată a transferului (§3.6)
26. ~~Fiabilitatea jurnaliștilor?~~ Decis: vizibilă, ca rată de succes a predicțiilor (§3.6)
11. ~~Scăderea atributelor neantrenate?~~ Decis: combinat; sub 28 rar și doar neantrenate de mult, peste 30 mai ales fizice, chiar antrenate (§2.4)
4. Minor: chiar niciun atribut comun între portari și jucătorii de câmp (mentale, fizice)? (§2.3)
28. Moralul în motorul de meci (acum inactiv). Decis: discursul de la pauză modifică moralul pentru repriza a doua (vechiul multiplicator de putere după reputație a fost șters). Cum influențează moralul motorul — de stabilit, pas separat.
29. Discuțiile de planificare a transferurilor (patron, director general, agenți, la hotel): când au loc, cât de exacte sunt estimările, cum funcționează garanția antrenorului, ce costă și cât de precis e spionajul făcut de mine la alte meciuri (§3.8). Decis: produc ținte aprobate; fără aprobare nu pot face oferte; la dezacord decide patronul, cu excepția jucătorului pentru care garantez eu
