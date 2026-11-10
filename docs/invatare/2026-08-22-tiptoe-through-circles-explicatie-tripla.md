# Tiptoe through the circles — manual de explicație triplă

- **Kata**: Codewars 1 kyu „Tiptoe through the circles" (id 58b617f15705ae68cc0000a9) — drumul cel mai scurt A→B în plan, ocolind interiorul unor cercuri interzise; atingerea marginii e permisă; dacă nu există drum, răspunsul e −1.0.
- **Repo**: footballmanager-backend · branch `master` · commit de bază `8488435`
- **Cod-ancoră scris pentru acest manual** (nu exista în repo):
  - `src/test/java/com/footballmanagergamesimulator/algorithms/geometry/TiptoeThroughCircles.java` (solver-ul)
  - `src/test/java/com/footballmanagergamesimulator/algorithms/geometry/TiptoeLabRunner.java` (7 scenarii analitice, toate verzi la rulare)
- **Data generării**: 2026-08-22
- **Țintă**: să poți construi singur graful de tangente + arce pentru A→B printre cercuri interzise, să rulezi Dijkstra pe el și să prezici pentru o configurație dată dacă răspunsul e o lungime sau −1.0.

Acest document este generat complet, dar documentarea nu dovedește învățarea: niciun punct din planul de învățare nu este bifat aici.

## Harta confirmată

### Tehnologii (doar cele de pe fir)
- T1. Tangente punct–cerc (2 drepte din punct exterior) — cum ajungi de la A/B pe un cerc
- T2. Tangente comune cerc–cerc (exterioare + interioare, max 4) — cum treci de pe un cerc pe altul
- T3. Test segment blocat (distanță centru–segment < rază) — validarea fiecărei muchii drepte
- T4. Arc de cerc (lungime = rază × unghi) — deplasarea pe circumferință între două puncte de tangență
- T5. Test arc blocat (alt cerc acoperă o porțiune de arc) — validarea muchiilor curbe
- T6. Construcția grafului geometric (noduri generate din tangente, nu date) — asamblarea problemei
- T7. Dijkstra (drum minim cu coadă de priorități) — căutarea pe graful construit
- T8. Cazuri limită (A/B în cerc, cercuri suprapuse/conținute) — sursa lui −1.0 și a bug-urilor

### Fire de execuție
- F1. A, B, zero cercuri pe segment → răspuns = distanța euclidiană (firul reprezentativ)
- F2. Un cerc taie segmentul AB → tangente din A, arc pe cerc, tangente spre B → drum minim
- F3. Două cercuri pe traseu → tangente comune între ele + arce → drum combinat prin ambele
- F4. Cercuri care se suprapun formează zid A↔B → niciun drum valid → −1.0
- F5. Test mare (40–119 cercuri) → O(n²) perechi × validare O(n) → de ce mai încape în timp
- F6. Reconstrucție: pentru un test fix, prezici traseul (ce tangente, ce arce) înainte de rulare

### Perechi de separat
- P1. Tangentă exterioară vs interioară · P2. Coardă vs arc · P3. Atinge vs taie · P4. Graf clasic vs graf geometric · P5. atan2 vs acos · P6. −1.0 vs drum ocolitor lung

### Legendă
- **T***n* = tehnologie/concept de pe fir · **F***n* = fir de execuție · **P***n* = pereche cu risc de amestec
- **E1** = traseul mecanic (cine cheamă pe cine, cu ce valori) · **E2** = modelul mental (de ce, invariant, vocabular) · **E3** = scenariu predictiv (valori concrete + o variație de eșec/limită)
- **Q1** = întrebare orientativă **simulată** (nu pusă de utilizator) · **EQ** = răspunsul explica la Q1
- **[OK fișier:linie]** = verificat în cod · **[rulat]** = valoare obținută prin execuție · **[inferență]** = neverificat în cod

---

# Partea I — Tehnologiile (T1–T8)

## T1. Tangente punct–cerc

### T1·E1 — Traseul mecanic
Declanșator: `shortestPath` intră în bucla peste cercuri [OK TiptoeThroughCircles.java:68-77]. Pentru cercul `i`, apelează `pointTangents(a, i, circles.get(i))` [OK :69].
`pointTangents` [OK :97-105] face, în ordine:
1. calculează `d = dist(p, c.ctr())` — distanța de la A la centrul cercului;
2. dacă `d < r − EPS` (punctul e strict înăuntru), întoarce lista goală — zero tangente [OK :99-101];
3. altfel calculează `base = atan2(p.y − ctr.y, p.x − ctr.x)` — unghiul din centru spre A [OK :102];
4. calculează `offset = acos(r / d)` — deschiderea de la `base` până la punctul de tangență [OK :103];
5. întoarce exact două noduri, la unghiurile `base + offset` și `base − offset`, materializate în puncte de `onCircle` (centru + r·(cos, sin)) [OK :104, :267-270].
Efect: cele două noduri intră în lista `nodes`, iar imediat `shortestPath` încearcă muchia dreaptă A→nod prin `tryAddSegment` [OK :70-71]. Același tratament pentru B [OK :73-76].

### T1·E2 — Modelul mental
De ce tangenta și nu alt punct: un drum care ocolește un cerc trebuie să treacă „pe lângă" el; segmentul cel mai scurt din A care doar atinge cercul este exact tangenta — orice segment care ar intra mai adânc e ilegal, orice segment care trece mai departe de cerc e mai lung decât necesar. De aceea graful nu are nevoie de alte puncte pe cerc decât cele de tangență.
Invariant: dintr-un punct strict exterior există **exact două** tangente la un cerc; ele sunt simetrice față de axa punct–centru, la unghiul `acos(r/d)` de o parte și de alta.
Vocabular: **bază** = unghiul, măsurat din centru, al direcției spre punct; **offset** = jumătatea deschiderii dintre cele două puncte de tangență. Triunghiul centru–punct–tangență e dreptunghic în punctul de tangență (raza perpendiculară pe tangentă) — de aici `cos = r/d` și lungimea tangentei `√(d²−r²)`.

### T1·E3 — Scenariul predictiv
Valori: A=(−3, 0), cerc centrat în (0, 0) cu r=1 (fixture-ul „one blocking circle" [OK TiptoeLabRunner.java:29]).
- d = 3; base = atan2(0, −3) = π; offset = acos(1/3) ≈ 1.2310 rad.
- Tangentele ies la unghiurile π ± 1.2310, adică punctele (−1/3, +0.9428) și (−1/3, −0.9428).
- Lungimea fiecărui segment tangent: √(3² − 1²) = √8 ≈ 2.8284.
Variație-limită: mut A pe cerc, la (−1, 0): d = 1, offset = acos(1) = 0 → ambele „tangente" degenerează în chiar punctul A; nodurile coincid cu A, muchiile au lungime 0 — formula rămâne validă fără caz special.
Variație-eșec: mut A în (−0.5, 0), strict înăuntru: `d < r − EPS` e adevărat, linia :100 întoarce lista goală și A nu primește nicio poartă spre acest cerc.

### T1·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: cardinalitate + actorul exact. „Rulez `shortestPath` cu A exterior tuturor celor 5 cercuri. Câte noduri provin din A și cine decide, pentru fiecare, dacă există și o muchie A→nod?"

### T1·EQ — Răspunsul explica
Zece noduri, dar nu neapărat zece muchii — iar deciziile le iau doi actori diferiți.
`pointTangents` produce necondiționat 2 noduri per cerc exterior lui A → 5 × 2 = 10 noduri adăugate în `nodes` [OK :69-72]. Muchia însă o decide `tryAddSegment` [OK :138-148], alt actor: el verifică segmentul A→nod contra **tuturor** celor 5 cercuri și îl respinge la primul care îl taie [OK :141-144]. Deci nodul intră mereu în graf, muchia doar dacă supraviețuiește validării — un nod fără nicio muchie rămâne în listă, inert, și Dijkstra nu-l atinge niciodată.
Predicție vecină: B exterior tuturor → încă 10 noduri prin exact același mecanism [OK :73-76].

*Regula generală: generarea nodurilor e necondiționată, existența muchiilor e condiționată — cele două decizii aparțin unor metode diferite.*

## T2. Tangente comune cerc–cerc

### T2·E1 — Traseul mecanic
Declanșator: bucla dublă `i < j` din `shortestPath` [OK TiptoeThroughCircles.java:80-88] apelează `pairTangents(i, c1, j, c2)` pentru fiecare pereche de cercuri.
`pairTangents` [OK :114-135]:
1. `d = dist(c1.ctr, c2.ctr)`; dacă `d < EPS` (concentrice) → listă goală, nu există nicio dreaptă tangentă comună [OK :116-118];
2. `base = atan2` pe direcția centru1 → centru2 [OK :119];
3. **exterioarele**, dacă `d ≥ |r1 − r2| − EPS`: `phi = acos((r1 − r2)/d)`; pentru semnele ±, punctul pe c1 și punctul pe c2 stau la **același** unghi `base ± phi` [OK :122-126];
4. **interioarele**, dacă `d ≥ r1 + r2 − EPS`: `phi = acos((r1 + r2)/d)`; punctul pe c1 la `base ± phi`, punctul pe c2 la `base ± phi + π` — unghi diametral opus [OK :128-132].
Efect: fiecare tangentă validă întoarce o pereche de noduri; apelantul le adaugă pe amândouă în `nodes` și încearcă imediat segmentul dintre ele [OK :83-85].

### T2·E2 — Modelul mental
Definesc separat cele două vocabulare (P1), apoi maparea:
- **Tangenta exterioară** lasă ambele centre de aceeași parte a dreptei — drumul „sare" de pe un cerc pe celălalt rămânând pe aceeași parte (umăr–umăr).
- **Tangenta interioară** trece printre cercuri, cu centrele de o parte și de alta — drumul schimbă partea (se strecoară prin culoarul dintre ele).
Maparea pe cod: diferența e doar semnul din `acos`: `(r1 − r2)/d` la exterioare, `(r1 + r2)/d` la interioare, plus rotirea cu π a punctului de pe al doilea cerc la interioare [OK :123 vs :129-131].
Invariant: numărul de tangente comune scade pe măsură ce cercurile se apropie: 4 (disjuncte) → 3 (atingere exterioară) → 2 (secante) → 1 (atingere interioară) → 0 (cerc strict în cerc). Codul nu numără explicit — cele două condiții de existență produc exact acest comportament.

### T2·E3 — Scenariul predictiv
Valori: cercurile (−2, 0, r=1) și (2, 0, r=1) din fixture-ul „two blocking circles" [OK TiptoeLabRunner.java:34].
- d = 4, base = 0 (centru2 e la est de centru1).
- Exterioare: phi = acos((1−1)/4) = acos(0) = π/2 → puncte la ±π/2 pe ambele cercuri: (−2, ±1) → (2, ±1); dreptele orizontale y = +1 și y = −1.
- Interioare: phi = acos((1+1)/4) = acos(1/2) = π/3 → pe c1 la ±60°, pe c2 la ±60° + 180°; dreptele se încrucișează în origine.
Variație-eșec: apropii cercurile la centre (−0.95, 0) și (0.95, 0): d = 1.9 < r1 + r2 = 2, condiția de la :128 pică → interioarele dispar, exterioarele rămân; solver-ul mai are doar podul pe deasupra/pe dedesubt și răspunde 12.198674610 pentru A=(−6,0), B=(6,0) [rulat].

### T2·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: perechea P1 (exterioară vs interioară) pe o tranziție de existență. „Țin r1 = 2, r2 = 1 și aduc centrele la d = 0.5. Ce tangente mai generează `pairTangents` și care linie de cod le taie pe celelalte?"

### T2·EQ — Răspunsul explica
Niciuna — la d = 0.5 perechea nu mai produce nicio tangentă.
Filmul pe condiții: interioarele cer `d ≥ r1 + r2 − EPS`, adică 0.5 ≥ 3 — fals, ramura :128 nu intră. Exterioarele cer `d ≥ |r1 − r2| − EPS`, adică 0.5 ≥ 1 — tot fals, ramura :122 nu intră. Lista întoarsă e goală: cercul mic e strict în interiorul celui mare (d + r2 = 1.5 < r1 = 2), iar dintr-un cerc înghițit nu pleacă nicio dreaptă care să atingă ambele fără să taie unul.
Pragul exact al fiecărei dispariții: interioarele mor primele, când d scade sub r1 + r2 = 3; exterioarele mor când d scade sub |r1 − r2| = 1.
Predicție vecină: la d = 1 exact (atingere interioară), `acos(1)` = 0 → cele „două" exterioare coincid într-o singură dreaptă, prin punctul de atingere.

*Regula generală: existența fiecărui tip de tangentă e o comparație a lui d cu suma, respectiv diferența razelor — codul nu tratează cazurile, le lasă să cadă din condiții.*

## T3. Testul de segment blocat

### T3·E1 — Traseul mecanic
Declanșator: orice candidat de muchie dreaptă — A–B direct [OK TiptoeThroughCircles.java:65], A/B→tangentă [OK :71, :75], tangentă↔tangentă între cercuri [OK :85] — trece prin `tryAddSegment(nodes, u, v, circles)` [OK :138-148].
Filmul:
1. ia punctele `p = nodes.get(u).p`, `q = nodes.get(v).p` [OK :139-140];
2. pentru **fiecare** cerc din listă, calculează `distPointSegment(c.ctr(), p, q)` [OK :141-142] — implementarea [OK :277-284]: proiectează centrul pe dreapta pq, taie parametrul `t` în [0,1] (clamp), măsoară cu `Math.hypot`;
3. la primul cerc cu `dist < r − EPS`, iese fără să adauge nimic [OK :142-144];
4. dacă toate cercurile lasă segmentul, adaugă muchia în **ambele** liste de adiacență, cu lungimea euclidiană [OK :145-147].

### T3·E2 — Modelul mental
De ce strict `< r − EPS` și nu `≤ r`: segmentele candidate sunt construite chiar ca tangente, deci ating cercul-proprietar la distanță **exact** r. Un test `≤ r` și-ar ucide propriile muchii; toleranța EPS (10⁻⁹, [OK :31]) absoarbe și zgomotul de virgulă mobilă din trigonometrie. Aici trăiește perechea P3: **taie** = pătrunde strict sub rază (ilegal); **atinge** = distanță egală cu raza (legal, e chiar definiția tangentei).
Invariant: o muchie dreaptă există doar dacă **toate** cercurile o acceptă — un singur veto o omoară. Cuantificatorul e universal, iar respingerea e leneșă (primul veto încheie verificarea).
Vocabular: **clamp la [0,1]** = dacă proiecția centrului cade în afara segmentului, distanța se măsoară până la capătul cel mai apropiat, nu până la dreapta infinită — fără clamp, un cerc aflat în prelungirea segmentului l-ar bloca din greșeală.

### T3·E3 — Scenariul predictiv
Valori, pe fixture-ul „one blocking circle" (A=(−3,0), B=(3,0), cerc (0,0,1)):
- Candidatul A–B: proiecția centrului (0,0) pe segment cade la t = 0.5, punctul (0,0), distanță 0 < 1 − EPS → veto, muchia directă moare.
- Candidatul A→tangenta (−1/3, 0.9428): distanța de la (0,0) la acest segment = 1.0 exact (e tangentă); testul cere sub 1 − 10⁻⁹ → trece.
Variație cu clamp: cercul (5,5,1) față de segmentul (0,0)→(10,0) [OK TiptoeLabRunner.java:25]: proiecția lui (5,5) cade la t = 0.5, distanța = 5 → liber; dar pentru un cerc în (15,0,1), proiecția ar cădea la t = 1.5 → clamp la t = 1, distanța se măsoară până la capătul (10,0) = 5 → liber, deși dreapta infinită ar trece prin cerc.
Variație-eșec: dacă testul ar deveni `≤ r`, toate tangentele ar fi respinse și orice configurație cu un cerc pe traseu ar întoarce −1.0.

### T3·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: actorul exact + auto-referință. „Segmentul tangent construit pentru cercul 3 e verificat în `tryAddSegment` și contra cercului 3 însuși. De ce nu se blochează singur?"

### T3·EQ — Răspunsul explica
Se verifică și contra propriului cerc, și trece — datorită diferenței dintre „exact r" și „strict sub r − EPS".
Bucla de la :141 nu exclude pe nimeni: iterează toată lista, inclusiv cercul ale cărui tangente sunt chiar capetele segmentului. Pentru acel cerc, distanța centru→segment iese r (proprietatea tangentei; punctul de atingere e capătul segmentului, iar restul segmentului se depărtează). Condiția de veto `dist < r − EPS` [OK :142] e falsă la egalitate, deci nu se declanșează.
Dovadă prin rulare: „one blocking circle" întoarce 6.336528068 [rulat] — dacă tangentele s-ar auto-bloca, singurul rezultat posibil ar fi −1.0.
Predicție vecină: un segment între tangentele a două cercuri diferite atinge ambele cercuri la exact r și primește același tratament de la amândouă.

*Regula generală: validarea nu știe cine a construit segmentul — aceeași inegalitate strictă protejează simultan tangentele legitime și blochează intrușii.*

## T4. Arcul de cerc ca muchie

### T4·E1 — Traseul mecanic
Declanșator: după ce toate segmentele au fost încercate, `shortestPath` cheamă o singură dată `addArcEdges(nodes, circles)` [OK TiptoeThroughCircles.java:91, :156-196].
Filmul:
1. grupează indicii nodurilor pe cercul-proprietar (`circleIdx ≥ 0`) în `byCircle` [OK :158-167];
2. sare cercurile cu sub 2 noduri — un singur punct pe cerc nu are cu cine forma arc [OK :169-171];
3. cere intervalele interzise ale cercului de la `forbiddenIntervals` (mecanismul lor e subiectul T5) [OK :172];
4. sortează nodurile cercului după unghiul normalizat în [0, 2π) [OK :176-177];
5. pentru fiecare pereche de **vecini** unghiulari k → k+1, calculează golul `span`; ultimul nod se leagă de primul cu `span + 2π` (închiderea inelului) [OK :178-185];
6. dacă `arcFree` acceptă golul, adaugă muchia cu lungimea `r · span` în ambele sensuri [OK :186-191].

### T4·E2 — Modelul mental
Odată ajuns pe cerc printr-o tangentă, singura deplasare legală „pe loc" e alunecarea pe circumferință — iar costul ei e lungimea de arc: **rază × unghi în radiani**. Nu există teleportare între punctele de pe același cerc.
De ce doar vecinii, nu toate perechile: lanțul de goluri consecutive compune orice arc mai lung — ca să aluneci de la unghiul 1.23 la 4.37 treci inevitabil prin nodul de la 1.91, iar Dijkstra adună singur costurile bucăților [OK comentariul :152-154]. Muchiile pereche-cu-pereche ar fi redundante.
Vocabular: **span** = golul unghiular dintre doi vecini pe cerc, întotdeauna pozitiv după sortare; **închiderea inelului** = golul de la ultimul nod înapoi la primul, care altfel ar lipsi și ar rupe circumferința în lanț deschis.
Invariant: suma tuturor span-urilor unui cerc = 2π, exact o dată circumferința.

### T4·E3 — Scenariul predictiv
Valori: cercul (0,0,1) din „one blocking circle" adună 4 noduri (două de la A, două de la B), la unghiurile normalizate 1.2310, 1.9106, 4.3726, 5.0522.
- Golurile: 1.9106−1.2310 = **0.6797** (arcul de sus), apoi 2.4619 (stânga), 0.6797 (jos), 2.4619 (dreapta, cu wrap) — suma 2π.
- Fără alte cercuri, toate 4 arcele sunt libere; muchiile costă r·span: 0.6797, 2.4619, 0.6797, 2.4619.
- Drumul optim folosește arcul de sus: 0.6797 · 1 = 0.6797, exact termenul din valoarea verificată 2√8 + 0.6797 = 6.3365 [rulat].
Variație-limită: dublez raza păstrând raportul r/d (r=2, A=(−6,0), B=(6,0)): unghiurile rămân identice (depind doar de r/d), dar arcul costă 2 · 0.6797 = 1.3594 — lungimea de arc crește liniar cu raza chiar la unghi neschimbat.

### T4·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: perechea P2 (coardă vs arc). „Între cele două puncte de tangență de sus, coarda (segmentul drept dintre ele) e mai scurtă decât arcul. De ce nu merge drumul pe coardă?"

### T4·EQ — Răspunsul explica
Pentru că coarda intră în cerc — și, separat, pentru că ea nici măcar nu ajunge candidat.
Geometric: punctele de sus stau la unghiurile 1.2310 și 1.9106 pe cercul unitate; mijlocul coardei dintre ele e la distanța cos(0.6797/2) = 0.9427 < 1 de centru, deci strict în interior — coarda taie zona interzisă.
Mecanic: `tryAddSegment` primește doar candidații construiți la :65, :71, :75, :85 — adică A–B și tangentele; nimeni nu propune vreodată segmentul între două noduri de pe **același** cerc, deci coarda nu e nici măcar testată. Singura legătură pe care codul o oferă între ele e arcul de la `addArcEdges` [OK :186-191]. Chiar dacă cineva ar propune coarda, veto-ul de la :142 ar respinge-o (0.9427 < 1 − EPS).
Predicție vecină: cu cât golul unghiular scade spre 0, diferența arc − coardă scade spre 0 — dar semnul nu se schimbă niciodată: arcul e mereu cel puțin cât coarda.

*Regula generală: pe cerc se merge pe circumferință; linia dreaptă dintre două puncte ale aceluiași cerc e prin definiție prin interior, deci ilegală.*

## T5. Testul de arc blocat

### T5·E1 — Traseul mecanic
Declanșator: `addArcEdges` cere, per cerc, lista intervalelor interzise: `forbiddenIntervals(i, circles)` [OK TiptoeThroughCircles.java:172, :201-225].
Pentru cercul `i` și fiecare alt cerc `j`, metoda decide pe distanța dintre centre `d`:
1. `d ≥ ri + rj − EPS` → `continue`: j e prea departe ca să atingă circumferința lui i [OK :210-212];
2. `d + ri ≤ rj + EPS` → `return null`: toată circumferința lui i zace în interiorul lui j — semnal special „nimic nu supraviețuiește" [OK :213-215];
3. `d + rj ≤ ri` → `continue`: j plutește strict în interiorul lui i și nu ajunge la circumferință [OK :216-218];
4. altfel j „mușcă": centrul intervalului e `base` (direcția spre centrul lui j), semi-lățimea `half = acos((d² + ri² − rj²)/(2·d·ri))` — teorema cosinusului [OK :219-221].
Consumatorul `arcFree(from, to, forbidden)` [OK :227-238] declară arcul blocat dacă suprapunerea cu vreun interval depășește EPS, verificând intervalul și în copiile deplasate cu ±2π (pentru arce care trec peste 0) [OK :231-235].

### T5·E2 — Modelul mental
Imaginea: fiecare cerc vecin care intersectează circumferința lui i **mușcă din ea un interval unghiular** — punctele circumferinței aflate strict în interiorul vecinului. Un arc e valid doar dacă golul lui nu calcă pe nicio mușcătură. Asta e sora curbă a testului T3: acolo un cerc taie un segment, aici un cerc taie o porțiune de circumferință.
Invariant: mușcătura e **simetrică** în jurul direcției spre centrul mușcătorului — de aceea e suficient un centru (`base`) și o semi-lățime (`half`).
De unde vine formula: pentru punctul de pe circumferință la unghiul t, distanța până la centrul lui j iese din teorema cosinusului în triunghiul centru-i → punct → centru-j; punctul e înăuntrul lui j exact când `cos(t − base) > (d² + ri² − rj²)/(2·d·ri)`, iar egalitatea dă marginea `half` [OK comentariul :197-200].
Vocabular: **interval interzis** = perechea [base − half, base + half]; **null** ≠ listă goală: null = „tot cercul e înghițit", lista goală = „nimeni nu mușcă".

### T5·E3 — Scenariul predictiv
Valori: cercurile (0,0,1) și (1.5,0,1): d = 1.5 < 2 → mușcă; nu e nici înghițire (1.5 + 1 > 1).
- half = acos((2.25 + 1 − 1)/(2 · 1.5 · 1)) = acos(0.75) = 0.7227 rad; base = 0.
- Intervalul interzis pe primul cerc: [−0.7227, +0.7227] — felia dinspre est, exact zona unde cercurile se întrepătrund.
- Arc de la 0.5 la 1.0: suprapunere = 0.7227 − 0.5 = 0.2227 > EPS → blocat.
- Arc de la 0.8 la 1.2: suprapunere = 0.7227 − 0.8 < 0 → liber.
Variație-limită: depărtez la d = 2.0 exact (atingere exterioară): condiția :210 `d ≥ ri + rj − EPS` e adevărată → `continue`, zero mușcătură. Atingerea într-un punct nu blochează niciun arc — coerent cu „atinge ≠ taie" de la T3.

### T5·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: tranziția de stare `return null` + soarta nodurilor. „Cercul i a primit 6 noduri de tangentă, dar un cerc uriaș j îl acoperă complet. Ce se întâmplă cu cele 6 noduri și cu muchiile lor?"

### T5·EQ — Răspunsul explica
Nodurile rămân în graf, dar ajung insule: pierd arcele printr-un mecanism și segmentele prin altul.
Filmul: `forbiddenIntervals` lovește cazul :213-215 și întoarce null; în `addArcEdges`, testul de la :172-174 face `continue` → cercul i nu primește **niciun** arc, indiferent câte noduri are. Separat, orice segment tangent spre acele noduri a murit deja în `tryAddSegment`: capătul lui stă pe circumferința lui i, adică strict în interiorul lui j, deci distanța de la centrul lui j la segment e sub rj − EPS → veto [OK :142]. Rezultat: 6 noduri cu listele de muchii goale — Dijkstra nu ajunge la ele niciodată, dar nici nu deranjează.
Predicție vecină: dacă j doar intersectează (fără să înghită), mor exact arcele din mușcătură; nodurile din afara ei își păstrează arcele și segmentele.

*Regula generală: acoperirea completă nu șterge noduri — le lasă fără muchii; graful geometric preferă noduri moarte în locul cazurilor speciale.*

## T6. Construcția grafului geometric

### T6·E1 — Traseul mecanic
Declanșator: apelul public `shortestPath(a, b, circles)` [OK TiptoeThroughCircles.java:50-94]. Ordinea exactă a asamblării:
1. **garda**: dacă A sau B stă strict într-un cerc → return −1.0 imediat, fără graf [OK :51-56];
2. nodurile 0 și 1: `start` (A) și `goal` (B), cu `circleIdx = −1` [OK :57-61];
3. candidatul A–B direct [OK :64-65];
4. tangentele din A și din B spre fiecare cerc (T1), fiecare nod urmat imediat de încercarea segmentului lui [OK :68-77];
5. tangentele comune pentru fiecare pereche de cercuri (T2), cu segmentele lor [OK :80-88];
6. arcele între vecinii unghiulari (T4 + T5) [OK :91];
7. `dijkstra(nodes)` pe structura finală [OK :93].
Un nod e clasa `Node` [OK :37-47]: punctul, cercul-proprietar, unghiul pe cerc și lista proprie de muchii `{indexVecin, lungime}` — adiacența trăiește în noduri, nu într-o matrice.

### T6·E2 — Modelul mental
Aici e perechea P4, cele două vocabulare pe rând:
- **Graf clasic**: nodurile și muchiile sunt **datele de intrare** — cineva ți le dă, tu doar cauți în ele.
- **Graf geometric**: intrarea e doar A, B și cercurile; nodurile sunt **generate** de trigonometrie (T1, T2), muchiile sunt **validate** de geometrie (T3, T5). Schimbi un cerc → se schimbă însăși mulțimea nodurilor, nu doar niște ponderi.
De ce sunt suficiente tangentele: pe drumul optim, fiecare bucată dreaptă care ocolește un cerc îl atinge tangențial, iar trecerile de pe un cerc pe altul sunt tangente comune — un drum optim nu are colțuri pe margini de cerc [inferență, proprietate standard a drumului optim printre discuri; codul o folosește implicit prin alegerea candidaților].
Invariant: orice drum valid din graf e o alternanță segment-tangent → arc → segment-tangent → … cu A la un capăt și B la celălalt.

### T6·E3 — Scenariul predictiv
Numărare completă pe „two blocking circles" (2 cercuri, A și B exterioare):
- noduri: 2 (A, B) + 4 (tangente A) + 4 (tangente B) + 8 (4 tangente comune × 2 capete) = **18**;
- candidați de segment: 1 (A–B) + 4 + 4 (A/B) + 4 (perechea) = **13**, fiecare judecat de T3;
- arce: cercul 1 are 6 noduri (2 de la A, 2 de la B... din care cele dinspre B pot fi respinse ca muchii dar tot pe cerc stau, plus 4 capete de tangente comune) → 18 goluri în total pe ambele cercuri, judecate de T5.
Variație de scară: la n = 119 cercuri (testul grid), perechile devin 119·118/2 = 7021, fiecare cu până la 8 capete de noduri → zeci de mii de noduri; exact de aceea arcele se leagă doar între vecini, nu între toate perechile de pe cerc.

### T6·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: perechea P4 pe actorul concret. „Într-un graf clasic aș căuta lista de adiacență și matricea de costuri în input. Aici unde anume trăiește adiacența și cine i-a scris valorile?"

### T6·EQ — Răspunsul explica
Adiacența trăiește în câmpul `edges` al fiecărui `Node` [OK :41], și o scriu exact doi actori — nimeni altcineva.
1. `tryAddSegment` scrie perechea de intrări simetrice cu lungimea euclidiană `dist(p, q)` [OK :145-147];
2. `addArcEdges` scrie perechea de intrări simetrice cu lungimea `r · span` [OK :188-191].
Nu există matrice de cost: costul fiecărei muchii e **calculat la generare** din geometrie și înghețat în `double[]{vecin, lungime}`. Inputul metodei nu conține nicio muchie — dovadă: semnătura `shortestPath(Point, Point, List<Circle>)` [OK :50] nu primește decât geometrie.
Predicție vecină: muți un singur cerc cu 0.1 → `pointTangents`/`pairTangents` produc alte unghiuri, deci alte noduri și alte lungimi — se reconstruiește tot graful, nu se „actualizează" nimic.

*Regula generală: în graful geometric, inputul nu conține graful — nodurile le naște trigonometria, muchiile le aprobă validarea, iar adiacența stă distribuită în noduri.*

## T7. Dijkstra pe graful generat

### T7·E1 — Traseul mecanic
Declanșator: ultimul rând util din `shortestPath`: `return dijkstra(nodes)` [OK TiptoeThroughCircles.java:93, :241-265].
Filmul:
1. `best[]` pornește pe +∞ pentru toți, `best[0] = 0` — nodul 0 e A [OK :242-245];
2. coada de priorități ordonează perechile `{nod, distanță}` crescător după distanță [OK :246-247];
3. la fiecare `poll`: dacă distanța din pereche e mai mare decât `best[u]`, intrarea e veche (stale) și se sare [OK :248-252];
4. dacă nodul scos e 1 (B), întoarce `best[1]` pe loc — early exit [OK :253-255];
5. altfel relaxează fiecare muchie din `nodes.get(u).edges`: dacă `best[u] + lungime < best[v]`, scrie noul `best[v]` și împinge perechea în coadă [OK :256-261];
6. coada se golește fără ca B să fie scos → return −1.0 [OK :264].

### T7·E2 — Modelul mental
Nimic exotic: e Dijkstra de manual; toată inteligența problemei s-a consumat în construcția grafului (T1–T6). Observația-cheie: **−1.0 nu e un caz geometric detectat** („există zid?") — e pur și simplu epuizarea cozii cu B neatins, adică graf deconectat între componenta lui A și cea a lui B.
Invariant care face totul corect: toate ponderile sunt lungimi geometrice, deci ≥ 0; cu ponderi nenegative, în momentul în care un nod iese din coadă cu cea mai mică distanță, nicio cale descoperită ulterior nu-l mai poate scurta.
Vocabular: **intrare stale** = pereche rămasă în coadă de la o relaxare veche, după ce nodul a primit între timp o distanță mai bună; se aruncă la scoatere în loc să se șteargă din coadă (ștergerea din mijlocul unui heap ar fi scumpă); **relaxare** = testul „drumul prin u către v e mai bun decât ce știa v?".

### T7·E3 — Scenariul predictiv
Derulare pe „one blocking circle" (numerele de la T1/T4):
- pas 1: A iese din coadă cu 0; relaxează cele 2 segmente tangente valide → best[tangenta-sus] = best[tangenta-jos] = 2.8284;
- pas 2-3: cele două tangente ies cu 2.8284; fiecare relaxează arcele vecine: nodul de ieșire de sus primește 2.8284 + 0.6797 = 3.5081 (și, pe ruta lungă, 2.8284 + 2.4619 = 5.2903 — pierde);
- pas 4-5: nodurile de ieșire ies cu 3.5081; relaxează tangentele spre B: best[B] = 3.5081 + 2.8284 = 6.3365;
- pas 6: B iese primul dintre rămașii din coadă → return 6.336528068 [rulat].
Variație-eșec: pe „sealed ring", A iese cu 0 dar lista lui de muchii e goală (toate segmentele au murit în T3) → coada seacă imediat → −1.0 [rulat, TiptoeLabRunner.java:38].

### T7·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: actorul exact al garanției de corectitudine. „La :253-255 metoda întoarce `best[1]` de îndată ce B iese din coadă, deși coada mai conține noduri neexplorate. Cine garantează că niciun drum mai scurt nu apare mai târziu?"

### T7·EQ — Răspunsul explica
Garanția o dă proprietatea de bază a lui Dijkstra cu ponderi nenegative, nu vreo verificare suplimentară din cod.
Filmul argumentului: comparatorul cozii [OK :246] scoate mereu perechea cu distanța minimă din coadă. Când B iese cu `best[1] = 6.3365`, orice alt nod încă în coadă are distanța ≥ 6.3365. Un ipotetic drum mai scurt spre B ar trebui să treacă printr-unul din acele noduri și apoi să mai adune muchii — dar muchiile sunt lungimi geometrice, `dist(p,q)` [OK :145] și `r·span` [OK :188], ambele ≥ 0, deci totalul nu poate coborî sub 6.3365. Early exit-ul e deci sigur; el doar scutește restul explorării.
Marcaj epistemic: nenegativitatea e garantată de construcție [OK], demonstrația completă a lui Dijkstra e teorema standard [inferență, nu e în cod].
Predicție vecină: dacă cineva ar introduce o muchie cu lungime negativă, exact acest return timpuriu ar fi primul loc care ar putea minți.

*Regula generală: Dijkstra își permite să se oprească la prima scoatere a țintei pentru că, fără ponderi negative, „primul scos" = „definitiv minim".*

## T8. Cazurile limită

### T8·E1 — Traseul mecanic
Fiecare limită are un proprietar precis în cod:
- **A sau B strict în cerc**: garda de la intrare, `dist < r − EPS` → −1.0 înainte de orice construcție [OK TiptoeThroughCircles.java:51-56; verificat prin fixture „start inside a circle" → −1.0, TiptoeLabRunner.java:42];
- **A == B**: nodurile 0 și 1 au același punct; `distPointSegment` pe segment degenerat ia ramura `len2 < EPS → t = 0` și măsoară distanța la punct [OK :280]; dacă punctul e legal, muchia directă are lungime 0 → răspuns 0.0 [rulat, TiptoeLabRunner.java:45];
- **cercuri concentrice**: `pairTangents` iese pe `d < EPS` cu lista goală [OK :116-118];
- **cerc strict în alt cerc**: nicio tangentă comună (ambele condiții din T2 pică) și, la arce, `return null` din T5 dacă înghițirea atinge circumferința [OK :122/:128 vs :213-215];
- **atingere exactă** (punct pe cerc, cercuri tangente): toate pragurile poartă ±EPS ca atingerea să treacă drept legală [OK :99, :122, :128, :142, :210].

### T8·E2 — Modelul mental
Modelul de proiectare: limitele nu se tratează cu if-uri în Dijkstra — se sting **la sursă**, fie în generare (nu se produc tangente: T2 la concentrice), fie în validare (nu se produc muchii: T3/T5), fie în gardă (singurul −1.0 anticipat: A/B în cerc). Dijkstra rămâne complet generic.
Invariant, perechea P3 dusă la capăt: EPS definește sensul cuvântului „strict" în tot fișierul — interiorul strict blochează, atingerea nu blochează niciodată; și generarea înclină în același sens (acceptă tangenta la egalitate).
Vocabular: **degenerat** = un caz în care formula generală colapsează (segment de lungime 0, `acos(1) = 0`, două tangente care coincid) dar rămâne corectă fără ramură specială.

### T8·E3 — Scenariul predictiv
Coridorul de lățime zero: cercurile (0,0,1) și (2,0,1) se ating exact în (1,0); A=(1,−3), B=(1,3).
- Segmentul vertical x=1 trece exact prin punctul de atingere; distanța de la fiecare centru la segment = 1.0 = r; veto-ul cere `< r − EPS` → fals → segmentul e **liber**;
- răspuns: 6.000000000 [rulat] — drumul trece prin punctul de contact al celor două cercuri.
Variație-eșec: umflu razele la 1.001: distanța 1.0 < 1.001 − EPS → ambele cercuri taie segmentul; coridorul s-a închis, solver-ul ocolește → 7.288289553 [rulat]. O modificare de o miime a razei mută răspunsul cu peste 1.28 — limitele geometrice sunt discontinue.

### T8·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: corecția fină de semn a lui EPS. „EPS apare o dată ca `< r − EPS` (veto la blocare, :142) și altă dată ca `≥ r1 + r2 − EPS` (existența tangentei interioare, :128). De ce scade în ambele locuri, deși testele par opuse?"

### T8·EQ — Răspunsul explica
Pentru că ambele înclină balanța în aceeași direcție: **în favoarea existenței drumului**.
La :142, scăderea îngustează zona de veto: un segment trebuie să fie clar înăuntru (mai adânc de 10⁻⁹) ca să fie tăiat — atingerea și zgomotul numeric nu ucid muchii legitime. La :128, scăderea lărgește zona de existență: la `d = r1 + r2` exact (cercuri tangente), fără −EPS o eroare de rotunjire de 10⁻¹⁶ ar putea face condiția falsă și ar pierde tangenta interioară legitimă care trece prin punctul de contact.
Regula comună, formulată o dată: eroarea de virgulă mobilă nu are voie să transforme „atinge" în „taie", nici „există la limită" în „nu există". Ambele semne decurg din ea.
Predicție vecină: inversarea semnului doar la :142 (`< r + EPS`) ar închide coridorul de lățime zero din E3 — răspunsul ar sări de la 6.0 la 7.288 fără nicio schimbare a geometriei de intrare.

*Regula generală: într-un solver geometric, toleranțele nu sunt cosmetică — fiecare EPS codifică o decizie de politică: la egalitate, drumul câștigă.*

# Partea a II-a — Firele de execuție (F1–F6)

## F1. Plan liber: zero cercuri pe segment

### F1·E1 — Traseul mecanic
Declanșator: `shortestPath(A=(0,0), B=(10,0), [cerc (5,5,1)])` — fixture-ul „circle off route" [OK TiptoeLabRunner.java:25].
1. Garda: A e la 7.07 de centrul (5,5), B la 7.07 — ambii afară → se continuă [OK TiptoeThroughCircles.java:51-56];
2. `tryAddSegment(0, 1)`: `distPointSegment((5,5), A, B)` = 5.0 ≥ 1 − EPS → muchia directă de lungime 10.0 intră în graf [OK :65];
3. **fără nicio scurtătură de cod**, solver-ul generează totuși tangentele din A și B spre cerc (4 noduri), tangentele-pereche nu există (un singur cerc), apoi arcele pe cerc [OK :68-91];
4. `dijkstra`: A iese cu 0, relaxează muchia directă → best[B] = 10.0; B iese imediat după (celelalte muchii pornesc la 7.0+) → return 10.000000000 [rulat].

### F1·E2 — Modelul mental
Punctul de reținut e un **non-mecanism**: solver-ul nu detectează „cazul simplu". Nu există `if (segmentLiber) return dist(A,B);`. Cazul simplu e doar configurația în care muchia A–B supraviețuiește validării T3 și câștigă natural în Dijkstra, fiind borna inferioară absolută.
Invariant: distanța euclidiană `dist(A,B)` e minorantul oricărui răspuns — nicio cale din graf nu poate coborî sub ea; când muchia directă există, ea **este** răspunsul, iar Dijkstra doar o confirmă.
Vocabular: **fir reprezentativ** = firul pe care îl poți rula în minte cap-coadă fără geometrie grea; aici tot pipeline-ul se vede, dar fiecare etapă produce muncă „degeaba" în afara muchiei directe.

### F1·E3 — Scenariul predictiv
Valori: cercul stă la (5,5), r=1; distanța centru→segment = 5.0 → muchie directă, răspuns 10.0 [rulat].
Predicția pe variație: cobor cercul la (5, 0.5): distanța centru→segment devine 0.5 < 1 − EPS → veto pe muchia directă; răspunsul sare peste 10 — drumul intră pe tangenta din A spre cerc, alunecă pe arcul de jos sau de sus și iese spre B.
Variație-limită: cerc la (5, 1.0) exact: distanța = 1.0 = r → veto-ul cere strict sub r − EPS → muchia directă **rămâne**, răspunsul rămâne 10.0 — segmentul care doar atinge cercul e legal (T3/P3).

### F1·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: cardinalitatea muncii + bănuiala de early-exit. „Dacă muchia directă A–B intră în graf de la pasul 2, mai construiește solver-ul cele 4 noduri de tangentă și arcele — sau se oprește?"

### F1·EQ — Răspunsul explica
Le construiește pe toate — nu există niciun early-exit între pașii de construcție.
Dovada e chiar forma metodei: după `tryAddSegment(nodes, 0, 1, circles)` de la :65, execuția cade necondiționat în bucla de tangente :68-77, apoi în bucla de perechi :80-88, apoi în `addArcEdges` :91; nicio ramură nu inspectează dacă muchia directă a reușit. Rezultatul lui `tryAddSegment` e `void` [OK :138] — apelantul nici nu are ce testa.
Consecința e doar de timp, nu de corectitudine: nodurile suplimentare stau în graf cu distanțe mai mari și pierd în coadă.
Predicție vecină: pe testele mari (F5), exact această construcție integrală — nu Dijkstra — domină timpul de rulare; 60 ms pentru 119 cercuri [rulat] se duc aproape integral pe generare + validare.

*Regula generală: solver-ul plătește mereu prețul întregii construcții; cazul simplu e ieftin doar la căutare, nu la asamblare.*

## F2. Un cerc taie segmentul

### F2·E1 — Traseul mecanic
Declanșator: `shortestPath(A=(−3,0), B=(3,0), [cerc (0,0,1)])` — fixture-ul „one blocking circle" [OK TiptoeLabRunner.java:29-30].
1. Garda trece (d = 3 > 1 pentru ambii);
2. muchia directă moare: centru pe segment, distanță 0 [OK TiptoeThroughCircles.java:142];
3. `pointTangents` din A: nodurile la unghiurile π ± 1.2310; din B: ± 1.2310 — 4 noduri pe cerc [OK :69-76];
4. cele 4 segmente tangente trec T3 (ating la exact r) — 4 muchii a câte √8 = 2.8284 [OK :145-147];
5. `addArcEdges`: 4 noduri sortate → 4 goluri (0.6797 sus, 2.4619 stânga, 0.6797 jos, 2.4619 dreapta cu wrap), toate libere (niciun alt cerc care să muște) → 4 muchii de arc [OK :178-191];
6. Dijkstra compune: 2.8284 + 0.6797 + 2.8284 = **6.336528068** [rulat]; formula închisă: 2·√(d²−r²) + r·(π − 2·acos(r/d)).

### F2·E2 — Modelul mental
Filmul geometric al „ocolirii": drumul **intră** pe cerc exact pe tangenta din A, **alunecă** pe arc doar atât cât să prindă direcția tangentei spre B, apoi **iese**. Nu există „colț": la fiecare punct de tangență, segmentul și arcul au aceeași direcție — drumul optim e neted.
Invariant (cazul simetric, cercul pe mijlocul segmentului): unghiul total de arc = π − 2·acos(r/d); depinde **doar de raportul r/d**, nu de mărimile absolute — scalezi toată scena de k ori, unghiul rămâne, lungimile cresc de k ori.
Vocabular: **simetria sus/jos** = configurația are două drumuri optime în oglindă, de lungimi identice; graful le conține pe amândouă.

### F2·E3 — Scenariul predictiv
Predicție prin scalare: păstrez raportul r/d = 1/3 dar dublez scena: A=(−6,0), B=(6,0), r=2.
- unghiurile nu se mișcă: offset = acos(1/3) = 1.2310, arc de sus = 0.6797;
- tangenta devine √(36−4) = √32 = 5.6569 (dublul lui 2.8284); arcul devine 2 · 0.6797 = 1.3593 (dublul lui 0.6797);
- total prezis: 2 · 5.6569 + 1.3593 = 12.6731 = exact dublul lui 6.3365 — răspunsul scalează liniar.
Variație-eșec: apropii A de cerc până la d = 1: tangentele degenerează (offset → 0, T1·E3), arcul de sus crește spre π − 0 →... formula rămâne definită, dar la d < 1 garda de la :51-56 preia și întoarce −1.0 — tranziția „drum scump" → „imposibil" e bruscă, nu graduală.

### F2·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: perechea P6 + cardinalitatea drumurilor optime. „Configurația are două drumuri optime egale (pe sus și pe jos). Ce întoarce solver-ul — și dacă aș vrea traseul însuși, nu doar lungimea, ce lipsește din cod?"

### F2·EQ — Răspunsul explica
Solver-ul întoarce **doar lungimea** unuia dintre ele — 6.3365 — și nu-ți spune niciodată care.
Mecanic: `dijkstra` întoarce scalarul `best[1]` [OK :255]; care dintre cele două rute în oglindă „câștigă" depinde de ordinea de inserție în coadă la distanțe egale — irelevant, ambele au aceeași valoare. Pentru traseu ar lipsi un tablou `parent[]`: la fiecare relaxare reușită [OK :258-260] s-ar reține `parent[v] = u`, apoi s-ar derula înapoi de la B la A. E aceeași separare „valoare optimă vs soluție optimă" ca perechea P6 din planul de programare dinamică — tabloul dă numărul, drumul cere reconstrucție separată. Kata cere doar numărul, deci reconstrucția e omisă deliberat.
Predicție vecină: ridic centrul cercului la (0, 0.1) — simetria se rupe, drumul pe jos devine strict mai scurt, dar output-ul rămâne tot un singur număr.

*Regula generală: Dijkstra fără parent[] răspunde la „cât?", niciodată la „pe unde?".*

## F3. Două cercuri pe traseu

### F3·E1 — Traseul mecanic
Declanșator: `shortestPath(A=(−6,0), B=(6,0), [(−2,0,1), (2,0,1)])` — fixture-ul „two blocking circles" [OK TiptoeLabRunner.java:34-35].
1. Muchia directă moare pe ambele cercuri; tangentele A→c1 au lungimea √(16−1) = √15 = 3.8730;
2. noutatea față de F2: `pairTangents(c1, c2)` [OK TiptoeThroughCircles.java:82] generează podurile dintre cercuri — exterioarele y = ±1 (lungime 4) și interioarele prin origine (T2·E3);
3. arcele: d = 4 ≥ r1 + r2 = 2 → niciun cerc nu mușcă din celălalt [OK :210-212], toate golurile libere;
4. Dijkstra alege lanțul: tangentă A→c1 (3.8730) + arc pe c1 de la unghiul π − acos(1/4) = 1.8235 la π/2 (span 0.2527) + podul y=1 de la (−2,1) la (2,1) (4.0) + arc simetric pe c2 (0.2527) + tangentă c2→B (3.8730) = **12.251327203** [rulat].

### F3·E2 — Modelul mental
Saltul conceptual față de F2: de pe arcul primului cerc **nu poți pleca oriunde** — poți pleca doar în punctele unde o tangentă comună atinge cercul, pentru că doar acolo direcția de ieșire e tangentă la arc (fără colț). Podul dintre cercuri e întotdeauna una dintre cele max 4 tangente comune (T2), iar punctele lui de capăt sunt exact nodurile generate de `pairTangents`.
Invariant de netezime: pe drumul optim, în fiecare punct de tangență, segmentul care intră/iese are aceeași direcție cu arcul — de aceea mulțimea finită de noduri T1+T2 e suficientă și nu pierdem niciun drum optim [inferență geometrică; codul o exploatează prin construcția candidaților].
Vocabular: **pod** = segmentul de tangentă comună folosit efectiv de drum pentru trecerea între cercuri.

### F3·E3 — Scenariul predictiv
Predicție structurală pe variantă rulată: apropii cercurile la (−0.95, 0) și (0.95, 0) — d = 1.9 < 2, interioarele dispar (T2·E3), cercurile se intersectează și își mușcă reciproc arcele dinspre interior (T5).
- Prezic: drumul rămâne „pe deasupra" — tangentă A→c1, arc, pod exterior scurt, arc, tangentă → B — dar podul exterior nu mai e y = 1 pe lungime 4, ci mult mai scurt, iar tangentele pornesc de mai departe (d(A, c1) = 5.05);
- Rulat: 12.198674610 — mai mic decât 12.2513 al configurației depărtate, pentru că obstacolul compact se ocolește mai strâns decât două obstacole întinse pe 8 unități.
Variație-eșec: dacă cercurile s-ar apropia până se suprapun puternic și și-ar muta centrele pe verticală de-o parte și de alta a axei, podul exterior de sus ar putea muri în T5 și drumul ar fi împins pe ocolirea completă a ambelor — structura lanțului se schimbă calitativ, nu doar numeric.

### F3·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: actorul exact al podului + contrafactual. „Drumul optim folosește podul y = 1 între (−2,1) și (2,1). Dacă șterg din cod doar generarea tangentelor comune (bucla :80-88), ce răspuns iese — −1.0 sau altceva?"

### F3·EQ — Răspunsul explica
Altceva — un răspuns finit, dar greșit de mare: drumul există și fără poduri, doar că e forțat să ocolească.
Filmul fără :80-88: graful păstrează tangentele A→c1, A→c2, B→c1, B→c2 și arcele complete pe fiecare cerc. Există atunci lanțul A → tangentă spre c1 → arc lung pe c1 → tangentă c1→B? Da: `pointTangents(b, …)` a generat noduri și pe c1 [OK :73-76], iar segmentul tangent din B spre c1 nu trece prin c2? Trece — la această geometrie taie c2 (distanța de la (2,0) la acel segment ≈ 0.5 < 1, calcul în T2·E3 al dreptei din B spre c1) → moare în T3. Rămâne ocolirea prin exteriorul lui c2: A → c2 direct nu se poate din același motiv în oglindă… lanțul viabil devine A → c1 (arc pe deasupra până aproape de est) — fără pod nu există nicio muchie c1→c2, deci singura ieșire de pe c1 e o tangentă spre B care nu taie c2; asemenea tangente există (cele care ies pe deasupra, departe de c2), dar pleacă din puncte de arc mai avansate și dau un total strict mai mare decât 12.2513.
Concluzie verificabilă: nu −1.0, ci o valoare mai mare — podurile nu creează conectivitate aici, o scurtează [inferență structurală; netestat prin rulare, ar cere modificarea codului].
Predicție vecină: într-o geometrie unde singurul culoar e printre două cercuri (tangenta interioară obligatorie), ștergerea acelorași linii chiar ar produce −1.0.

*Regula generală: tangentele comune nu sunt singura conectivitate, dar sunt scurtătura — fără ele drumul se scumpește sau, în culoare înguste, dispare.*

## F4. Zidul de cercuri → −1.0

### F4·E1 — Traseul mecanic
Declanșator: `shortestPath(A=(0,0), B=(10,10), inel de 4 cercuri r=1 la (±1.2, 0) și (0, ±1.2))` — fixture-ul „sealed ring" [OK TiptoeLabRunner.java:38-39].
1. Garda trece: A e la 1.2 de fiecare centru, deci în afara fiecărui cerc individual [OK TiptoeThroughCircles.java:51-56];
2. `pointTangents` din A **generează** noduri pe fiecare cerc al inelului (A e exterior fiecăruia) — de exemplu, spre cercul (1.2, 0): offset = acos(1/1.2) = 0.5857, tangenta T = (0.3667, −0.5528);
3. dar fiecare segment A→T moare în T3 pe un cerc **vecin**: distanța de la T la centrul (0, −1.2) = 0.7438 < 1 − EPS → veto [OK :142]; prin simetrie, toate cele 8 segmente din A sunt tăiate de vecinul de inel;
4. muchia A–B directă moare la fel; nodul 0 rămâne cu `edges` goală;
5. `dijkstra`: A iese din coadă cu 0, zero relaxări, coada seacă → **−1.0** [rulat; OK :264].

### F4·E2 — Modelul mental
−1.0 nu vine dintr-un detector de zid — vine din **deconectare**: componenta lui A din graf nu atinge componenta lui B. Zidul e etanș pentru că cercurile se suprapun: chiar în zona de suprapunere mor și segmentele (veto-ul vecinului, T3) și arcele (mușcătura vecinului, T5) — nu rămâne nicio „balama" pe care drumul să se cațere dintre cercuri.
Invariant: −1.0 ↔ nicio secvență de tangente și arce valide nu leagă A de B ↔ A și B stau în componente conexe diferite ale planului liber [inferență: echivalența cu topologia planului se sprijină pe completitudinea grafului de tangente, nedemonstrată în cod].
Vocabular: **etanș** = cercurile adiacente se suprapun (d < r+r), nu doar se ating — atingerea ar lăsa un punct de trecere legal (T8·E3).

### F4·E3 — Scenariul predictiv
Numerele etanșeității pe inelul dat: centrele adiacente, de exemplu (1.2, 0) și (0, 1.2), stau la d = 1.2·√2 = 1.6971 < 2 = r + r → fiecare pereche adiacentă se suprapune; cele 4 suprapuneri închid inelul complet în jurul lui A.
Predicție pe variație, apoi rulare: lărgesc inelul la 2.2 — centre (±2.2, 0), (0, ±2.2): adiacentele ajung la d = 3.1113 > 2 → între cercuri se cască goluri pe diagonale. Segmentul direct A=(0,0) → B=(10,10) trece prin golul de pe diagonala NE: distanța de la (0, 2.2) la dreapta y = x e 2.2/√2 = 1.5556 > 1, la fel pentru (2.2, 0) → muchia directă supraviețuiește → răspunsul devine chiar distanța euclidiană √200 = 14.142135624 [rulat].
Tranziția e binară: inelul strâns dă −1.0, inelul larg dă drum — nu există „aproape imposibil".

### F4·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: perechea P6 (−1.0 vs drum doar foarte lung) pe diagnostic. „Solver-ul mi-a întors −1.0. Cum deosebesc «zid geometric real» de «EPS mi-a tăiat un coridor la limită» — unde mă uit?"

### F4·EQ — Răspunsul explica
Te uiți la **marjele validărilor**, nu la Dijkstra — Dijkstra doar raportează deconectarea, n-o cauzează.
Procedura: pentru configurația suspectă, calculezi pentru fiecare candidat respins marja `r − dist` (segmente, :142) sau suprapunerea de interval (arce, :233). Dacă cel mai strâns veto are marja de ordinul 10⁻⁹ (comparabilă cu EPS, [OK :31]), decizia a luat-o toleranța, nu geometria francă — atunci răspunsul e fragil la reprezentarea în virgulă mobilă a inputului. Dacă marja minimă e de ordinul 10⁻¹ (ca la inel: 1 − 0.7438 = 0.2562), zidul e franc.
Dovadă de sens opus, deja rulată: coridorul de lățime exact zero (T8·E3) NU dă −1.0 — dă 6.0 — pentru că egalitatea nu declanșează veto. Deci −1.0 cu marje mari = zid real; −1.0 cu marje ~EPS = caz de precizie, verifică inputul.
Predicție vecină: pe inelul strâns, mărirea lui EPS la 10⁻³ nu schimbă nimic (marjele sunt 0.25); pe un coridor de lățime 10⁻⁴, ar schimba răspunsul.

*Regula generală: −1.0 se diagnostichează în validări (marjele veto-urilor), pentru că acolo s-a decis; căutarea doar constată golul.*

## F5. Scalarea: 40–119 cercuri

### F5·E1 — Traseul mecanic
Declanșator: testul grid din kata — până la 119 cercuri; reprodus local cu un grid 11×11 tăiat la 119 cercuri (r = 4, pas 10), A=(−7,−7), B=(107,107).
Cine consumă timpul, pe etape, la n = 119:
1. bucla de perechi [OK TiptoeThroughCircles.java:80-88]: 119·118/2 = **7021** apeluri `pairTangents`, fiecare cu până la 4 tangente → până la ~28 000 de candidați de segment;
2. fiecare candidat trece prin `tryAddSegment`, care iterează **toate** cele 119 cercuri [OK :141] → ~3.3 milioane de calcule `distPointSegment` — aici e etajul O(n³);
3. `addArcEdges`: sortarea nodurilor per cerc + `forbiddenIntervals` O(n) per cerc → etaj O(n²·log);
4. `dijkstra` pe zeci de mii de noduri și muchii cu coadă de priorități — O(m·log m).
Măsurat: **163.527530100** (față de 161.220346111 direct — ocolul prin grid costă doar +2.3) în **60 ms** [rulat].

### F5·E2 — Modelul mental
Intuiția care trebuie răsturnată: la grafuri „mari", nu căutarea e scumpă, ci **fabricarea grafului**. Etajul dominant e validarea segmentelor — produs de trei cardinalități înmulțite: perechi (n²) × tangente pe pereche (≤4) × cercuri de verificat (n). Dijkstra, vedeta teoretică, e zgomot de fond aici.
Invariant de estimare: timp ≈ (număr de muchii candidate) × (costul validării uneia) + căutarea; cu float-uri simple, milioanele de operații costă zeci de milisecunde în Java — 3.3M × ~10ns ≈ 33 ms, în acord cu cei 60 ms măsurați (restul: alocări, sortări, coadă).
Vocabular: **candidat** = muchie propusă geometric, înainte de veto; raportul candidați/supraviețuitori depinde de densitatea obstacolelor — în grid-ul des, majoritatea candidaților lungi mor.

### F5·E3 — Scenariul predictiv
Predicție prin formulă, de la 119 la 40 de cercuri: etajul dominant scade cu (119/40)³ ≈ 26× → din ~33 ms de validare rămân ~1.3 ms; totalul ar cădea sub ~5 ms.
Predicție inversă, spre limită: la n = 500, validarea ar crește cu (500/119)³ ≈ 74× → ~2.5 secunde — încă tolerabil; la n = 2000 ar fi ~2.5 minute — de-abia acolo ar deveni necesară o accelerare reală (bounding box pe segment înainte de distanța exactă, sau tăierea candidaților evident dominați).
Variație-limită: dacă grid-ul ar fi atât de des încât cercurile să se suprapună (pas < 8 la r = 4), numărul de candidați ar scădea (interioarele dispar, T2), dar ar crește mușcăturile de arce (T5) — iar la suprapunere totală în jurul lui A: −1.0 pe drumul cel mai scurt spre gardă.

### F5·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: cardinalitatea muchiilor de arc — de ce nu explodează. „Pe un cerc din mijlocul grid-ului se strâng sute de noduri de tangentă. De ce `addArcEdges` nu creează sute-de-mii de muchii pe acel cerc?"

### F5·EQ — Răspunsul explica
Pentru că leagă **doar vecinii unghiulari**: m noduri pe cerc → exact m muchii de arc candidate (inclusiv închiderea inelului), nu m·(m−1)/2.
Mecanic: bucla de la :178-193 merge o singură dată în jurul cercului sortat — perechea (k, k+1) și wrap-ul final; pentru m = 300 asta înseamnă 300 de goluri, nu 44 850 de perechi. Arcul „lung" dintre două noduri nevecine nu se pierde: e suma golurilor intermediare, iar Dijkstra o compune singur (T4·E2). În plus, compunerea e și **corectă** la blocaje: dacă un gol intermediar e mușcat, arcul compus prin el e ilegal — exact ce trebuie, pentru că blocajul chiar stă pe drum.
Numeric pe grid: ~56 000 de noduri distribuite pe 119 cercuri → ~56 000 de muchii de arc în total; varianta toate-perechile ar fi produs ~26 de milioane.
Predicție vecină: același truc nu merge la segmente — două tangente nevecine NU se compun din tangente vecine, deci acolo chiar trebuie generați toți candidații pereche-cu-pereche.

*Regula generală: pe structuri unde costul se compune aditiv pe lanț (arcele pe cerc), muchiile între vecini sunt suficiente; unde nu se compune (segmentele prin plan), plătești toate perechile.*

## F6. Predicția traseului pe un test fix

### F6·E1 — Traseul mecanic (protocolul de predicție)
Declanșator: un scenariu nou, necunoscut solver-ului până acum: A=(−6,0), B=(6,0), c1=(−2,0,1), c2=(2,0, **1.5**) — al doilea cerc mai mare.
Protocolul aplicat înainte de rulare:
1. enumeri porțile: tangente A→c1 (d=4, √15=3.8730), A→c2 (d=8.14), B→c2 (d=4.27), B→c1 (d=8.06) și cele ≤4 tangente comune c1↔c2 (d=4, r1+r2=2.5 ≤ 4 → există și interioarele);
2. aplici mental T3: segmentul direct moare pe ambele; tangenta din B peste c2 se ridică mai sus (r=1.5);
3. aplici T2 pentru pod: exterioarele nu mai sunt orizontale — cu r1=1, r2=1.5, phi = acos((1−1.5)/4) = acos(−0.125) = 1.696 rad — podul urcă dinspre c1 spre c2;
4. predicția structurală: lanț de tip F3 — tangentă A→c1, arc scurt pe c1, **pod exterior înclinat**, arc pe c2, tangentă c2→B — cu totalul strict peste 12.2513 (obstacolul e mai gras);
5. rulezi: **12.441648889** [rulat] — peste 12.2513 cu 0.19, structura confirmată.

### F6·E2 — Modelul mental
De ce merită pasul „prezici, apoi rulezi": el testează exact stratul pe care T1–T7 l-au construit — dacă poți spune **înaintea** rulării ce tangente și ce arce compun drumul, atunci graful geometric e al tău, nu al solver-ului. Rularea devine verificator, nu oracol.
Ordinea interioară a predicției reface pipeline-ul: întâi porțile (generarea, T1–T2), apoi veto-urile (validarea, T3/T5), la final compunerea (căutarea, T7) — aceleași trei straturi, doar că rulate în minte.
Invariant de sanity-check: orice predicție trebuie să respecte bornele — sub nicio formă sub `dist(A,B)` = 12 (borna euclidiană, F1·E2) și, aici, peste 12.2513 (cazul cu obstacol strict mai mic, deja cunoscut) [rulat pentru ambele borne].

### F6·E3 — Scenariul predictiv
Derularea numerică a sanity-check-ului pe predicția de la E1:
- borna de jos: 12.0000 (euclidian); borna de comparație: 12.2513 (r2 = 1 în loc de 1.5);
- valoarea rulată 12.4416 stă între 12.2513 și, de pildă, 12.6731 (varianta ambele-cercuri-dublate din F2·E3) — creșterea de 0.19 e plauzibilă pentru +0.5 la o singură rază;
- verificare de structură din numere: dacă drumul ar fi ocolit pe sub (evitând c2 pe jos), tangenta din B spre c1 ar fi intrat în c2 (F3·EQ) — solver-ul n-avea variantă mai ieftină decât podul înclinat.
Variație de eșec a predicției: dacă aș fi prezis „drumul ocolește doar c2, ignoră c1", borna ar fi demascat-o: un asemenea drum ar coborî sub 12.2513... nu — ar cere tangentă A→c2 care taie c1 → veto; predicțiile greșite mor fie pe borne, fie pe veto-uri, înainte de rulare.

### F6·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)
Risc ales: perechea P5 (atan2 vs acos), singura încă nefolosită activ. „Punctele de tangență ies din `base ± offset`, unde base vine din atan2 și offset din acos. La T1·E3, `base + offset` = π + 1.2310 = 4.3726 — depășește π. De ce nu strică nimic un unghi «în afara intervalului»?"

### F6·EQ — Răspunsul explica
Nu strică nimic pentru că cei doi consumatori ai unghiului sunt amândoi imuni la reprezentant: unul e periodic, celălalt normalizează.
Întâi vocabularele (P5): **atan2(y, x)** întoarce unghiul orientat al direcției (x, y), cu semn, în (−π, π] — el fixează „încotro"; **acos(v)** întoarce o deschidere fără semn, în [0, π] — el fixează „cât de larg". Suma lor poate ieși liniștită din (−π, π].
Consumatorul 1: `onCircle` [OK TiptoeThroughCircles.java:267-270] face `centru + r·(cos θ, sin θ)` — cos și sin sunt periodice cu 2π, deci 4.3726 și −1.9106 dau **același punct**; unghiul brut se și păstrează în `Node.angle` [OK :44] fără normalizare.
Consumatorul 2: sortarea din `addArcEdges` nu compară unghiurile brute, ci `norm(angle)` [OK :177], unde `norm` aduce orice reprezentant în [0, 2π) prin modulo [OK :286-289] — astfel vecinătatea unghiulară iese corectă indiferent cum s-au născut unghiurile.
Predicție vecină: dacă sortarea ar folosi unghiurile brute (fără `norm`), un nod cu −1.9106 s-ar așeza înaintea unuia cu 1.2310, golurile ar ieși negative și arcele s-ar lega între vecini falși — bug clasic de „arc pe partea greșită".

*Regula generală: un unghi nu are o valoare, are o clasă de reprezentanți — codul rămâne corect cât timp fiecare consumator e ori periodic, ori normalizează la intrare.*

# Partea a III-a — Închidere

## Glosar

- **arc** — porțiunea de circumferință dintre două puncte de pe același cerc; lungime = rază × unghi (radiani).
- **atan2(y, x)** — unghiul orientat, cu semn, al direcției (x, y), în (−π, π]; fixează „încotro".
- **acos(v)** — deschiderea fără semn, în [0, π]; fixează „cât de larg" (offset-ul tangentei).
- **base** — unghiul, văzut din centrul unui cerc, al direcției spre punctul/centrul partener.
- **candidat** — muchie propusă de generarea geometrică, înaintea veto-ului de validare.
- **clamp** — tăierea unei valori la un interval ([0,1] la proiecția pe segment; [−1,1] la argumentul acos).
- **coardă** — segmentul drept între două puncte ale aceluiași cerc; trece prin interior, deci e ilegală ca drum.
- **EPS** — toleranța geometrică 10⁻⁹; definește sensul lui „strict" în toate comparațiile.
- **etanș** — cercuri adiacente care se suprapun (d < r₁+r₂), fără punct de trecere legal între ele.
- **graf geometric** — graf ale cărui noduri și muchii sunt generate din geometrie, nu date ca input.
- **interval interzis** — felia unghiulară a unei circumferințe aflată strict în interiorul altui cerc.
- **intrare stale** — pereche (nod, distanță) învechită rămasă în coada Dijkstra; se aruncă la scoatere.
- **închiderea inelului** — golul unghiular de la ultimul nod sortat înapoi la primul (+2π).
- **muchie** — legătură validată din graf: segment tangent (lungime euclidiană) sau arc (r·span).
- **offset** — semi-deschiderea acos care desparte punctul de tangență de direcția base.
- **pod** — tangenta comună folosită efectiv de drum pentru trecerea de pe un cerc pe altul.
- **relaxare** — testul Dijkstra „drumul prin u către v e mai bun decât ce știa v?".
- **span** — golul unghiular dintre doi vecini pe același cerc; suma span-urilor unui cerc = 2π.
- **tangentă exterioară** — dreaptă tangentă la două cercuri care lasă ambele centre de aceeași parte; există când d ≥ |r₁−r₂|.
- **tangentă interioară** — dreaptă tangentă care desparte centrele; există când d ≥ r₁+r₂.
- **veto** — respingerea unui candidat de către un singur cerc care îl taie (cuantificator universal inversat).

## Index de ancore în cod

Toate în `src/test/java/com/footballmanagergamesimulator/algorithms/geometry/`.

**TiptoeThroughCircles.java** (solver):
- :23 record `Point` · :27 record `Circle` · :31 `EPS = 1e-9` · :37-47 clasa `Node` (punct, cerc-proprietar, unghi, `edges`)
- :50-94 `shortestPath` — pipeline-ul complet · :51-56 garda A/B în cerc → −1.0 · :65 candidatul A–B · :68-77 tangente A/B → cercuri · :80-88 tangente pereche · :91 arce · :93 apel dijkstra
- :97-105 `pointTangents` — 2 noduri la base ± acos(r/d); :99-101 punct interior → gol
- :114-135 `pairTangents` — :116-118 concentrice → gol · :122-126 exterioare (acos((r1−r2)/d)) · :128-132 interioare (acos((r1+r2)/d), +π pe al doilea cerc)
- :138-148 `tryAddSegment` — :142 veto `dist < r − EPS` · :145-147 muchie simetrică
- :156-196 `addArcEdges` — :172-174 tratarea lui null · :176-177 sortare pe `norm(angle)` · :183-184 wrap +2π · :186-191 muchie de arc r·span
- :201-225 `forbiddenIntervals` — :210 prea departe · :213-215 înghițit → null · :216-218 plutitor interior · :219-221 mușcătura [base ± half]
- :227-238 `arcFree` — suprapunere > EPS, copii deplasate ±2π
- :241-265 `dijkstra` — :246 comparator · :250-252 stale · :253-255 early exit pe B · :264 coadă goală → −1.0
- :267-270 `onCircle` · :277-284 `distPointSegment` (proiecție + clamp) · :286-289 `norm` → [0, 2π)

**TiptoeLabRunner.java** (fixture-uri analitice, toate verzi la rulare):
- :22 „open plane" = 10.0 · :25 „circle off route" = 10.0 · :29 „one blocking circle" = 2√8 + π − 2·acos(1/3) · :34 „two blocking circles" = 2√15 + 2(π/2 − acos(1/4)) + 4 · :38 „sealed ring" = −1.0 · :42 „start inside a circle" = −1.0 · :45 „A egal B" = 0.0

**Rulări suplimentare** (scratchpad, în afara repo-ului): coridor tangent = 6.0; coridor închis r=1.001 = 7.288289553; pereche apropiată d=1.9 = 12.198674610; inel lărgit = 14.142135624; pereche asimetrică r₂=1.5 = 12.441648889; grid 119 cercuri = 163.527530100 în 60 ms.

## Necunoscute și inferențe

1. **Enunțul kata (necunoscut din sursă)**: pagina Codewars nu a livrat enunțul integral prin API; regulile „atingerea marginii e permisă" și „A/B strict în cerc → −1.0" sunt implementate ca alegeri consistente, dar nu confirmate literă cu literă contra testelor oficiale ale kata-ului. Solver-ul nu a fost submis pe Codewars în această sesiune.
2. **Completitudinea grafului de tangente (inferență)**: afirmația „orice drum optim e o alternanță de tangente și arce, deci nodurile generate sunt suficiente" e o teoremă standard de geometrie computațională, folosită implicit de construcție; nu există demonstrație în cod (T6·E2, F3·E2).
3. **F3·EQ (inferență, netestat)**: comportamentul solver-ului cu bucla :80-88 ștearsă (răspuns finit dar mai mare, nu −1.0) e dedus structural, nu rulat.
4. **Echivalența −1.0 ↔ componente conexe ale planului liber (inferență)**: se sprijină pe punctul 2 (F4·E2).
5. **Estimările de timp pentru n = 500 / 2000 (inferență prin extrapolare cubică)**: nemăsurate (F5·E3).
6. Restul afirmațiilor numerice din document sunt fie [OK fișier:linie] (citite din cod), fie [rulat] (obținute prin execuția runner-ului/scratch-ului la 2026-08-22, pe commit `8488435`).
