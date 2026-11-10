# Become Immortal — explicație triplă

- **Kata**: Codewars 1 kyu, „Become Immortal" (id 59568be9cc15b57637000054), tag-uri: Dynamic Programming, Performance
- **Repo**: footballmanager-backend, branch `master`, commit `8488435` (+ fișier de laborator necomis)
- **Cod-laborator**: `src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java` — oracol + soluție rapidă + runner; toate numerele citate provin din rularea runner-ului (2026-08-22)
- **Data**: 2026-08-22
- **Țintă**: să poți calcula de mână `elderAge(m,n,l,t)` pe un exemplu mic prin descompunerea în cadrane, să prezici de ce brute force moare la m,n uriașe și unde anume te arde mod-ul aplicat greșit.

**Problema, într-o propoziție**: worshipperul de pe coloana x, rândul y (ambele de la 0) donează `max(0, (x xor y) − l)` secunde; suma peste tot dreptunghiul m×n, raportată modulo t, e vârsta Bătrânului.

## Harta confirmată

Tehnologii: T1 XOR pe biți · T2 structura XOR pe pătrat 2^k×2^k · T3 suma aritmetică cu prag l · T4 descompunerea în cadrane · T5 aritmetică modulară cu t · T6 complexitate O(log) vs O(m·n) · T7 brute force ca oracol

Fire: F1 oracolul pe 8×5 · F2 soluția rapidă pe același input · F3 pătrat complet 2^k · F4 dimensiuni mari nealineate · F5 capcana de overflow · F6 fuzz fast vs oracol

Perechi de separat: P1 eticheta „DP" vs divide-et-impera · P2 l prag vs l pierdere · P3 mod pe sumă vs pe factori · P4 dimensiuni vs indici · P5 puterea de 2 din dimensiunea mare vs mică

**Legendă**: T = tehnologie (concept de pe fir) · F = fir de execuție (declanșator → efect) · P = pereche cu risc de amestec · E1 = traseul mecanic · E2 = modelul mental · E3 = scenariul predictiv · Q1 = întrebare orientativă (simulată, nu pusă de utilizator) · EQ = răspunsul la Q1.

---

# Capitolul T — Tehnologii

## T1. XOR pe biți

### T1-E1 — Traseul mecanic

Declanșatorul e o singură celulă a dreptunghiului: worshipperul de pe coloana x, rândul y. Actorul care îi calculează donația e operatorul `^` al Javei, în [BecomeImmortal.java:23](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java): `long donated = (x ^ y) - l;`. `^` compară cei doi operanzi bit cu bit: bitul k al rezultatului e 1 exact când biții k ai lui x și y diferă. Pentru x=6 (`110`) și y=3 (`011`): biții diferă pe pozițiile 2 și 0 → `101` = 5. Efectul: donația brută a celulei (6,3) e 5, din care linia 23 scade imediat pierderea l; linia 24 (`if (donated > 0)`) lasă în sumă doar ce rămâne pozitiv.

*Regula generală: valoarea unei celule nu se caută nicăieri — se calculează pe loc din coordonatele ei.*

### T1-E2 — Modelul mental

De ce XOR și nu adunare: XOR e „suma fără transport" — fiecare bit se decide local, fără să se propage nimic între poziții. Asta îi dă cele două proprietăți pe care se sprijină tot restul manualului: (1) e comutativ și propriul lui invers (`(x ^ y) ^ y == x`), și (2) dacă ambii operanzi sunt sub 2^k, rezultatul rămâne sub 2^k — biții de sus nu apar din nimic. Vocabular de fixat aici, pentru perechea P4: **m, n sunt dimensiuni** (numărul de coloane/rânduri, exclusive), iar **x, y sunt indici** (0-indexați, deci x ≤ m−1). În `x ^ y` intră doar indici; valorile m și n nu ating niciodată operatorul XOR.

### T1-E3 — Scenariul predictiv

Grila completă 4×4 a lui `x ^ y` (x pe coloane, y pe rânduri):

| y\x | 0 | 1 | 2 | 3 |
|-----|---|---|---|---|
| 0 | 0 | 1 | 2 | 3 |
| 1 | 1 | 0 | 3 | 2 |
| 2 | 2 | 3 | 0 | 1 |
| 3 | 3 | 2 | 1 | 0 |

Fiecare rând conține exact valorile {0,1,2,3}, rearanjate. Cazul-limită: diagonala x=y dă mereu `x ^ x = 0`, deci `max(0, 0 − l)` = 0 pentru orice l ≥ 0 — worshipperii de pe diagonală nu donează niciodată, indiferent cât de mică e pierderea.

### T1-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: simetria rolurilor (actor exact). „`elderAge(8, 5, 1, 100)` și `elderAge(5, 8, 1, 100)` dau același rezultat, sau schimbarea lui m cu n schimbă suma?"

### T1-EQ — Răspunsul

Dau același rezultat. Două dovezi independente în cod: (1) matematic, mulțimea de perechi {(x,y) : x<8, y<5} devine prin transpunere {(y,x)} iar `x ^ y == y ^ x` la linia 23 — aceleași 40 de valori, doar parcurse în altă ordine; (2) mecanic, soluția rapidă nici nu păstrează distincția: `rect` la [BecomeImmortal.java:63-67](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java) face swap ca `a` să fie mereu dimensiunea mare, deci `rect(8,5,…)` și `rect(5,8,…)` execută instrucțiune cu instrucțiune același traseu. Predicție vecină, neverificată aici: `countDonors(5,8,1)` ar raporta tot 30, ca în F1.

## T2. Structura XOR pe pătratul 2^k × 2^k

### T2-E1 — Traseul mecanic

Actorul e ramura închisă a lui `rect`, liniile [69-72](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java): când `p == a` (a e putere de 2, iar b ≤ a după swap), metoda NU mai recursează — întoarce direct `mulMod(b % t, rowSum(p, off, l, t), t)`. Datele care fac asta legal: pentru un y fixat sub p, funcția x → `x ^ y` pe x ∈ [0,p) e o bijecție pe [0,p). Concret, p=8, y=5: `x ^ 5` pentru x=0..7 dă 5, 4, 7, 6, 1, 0, 3, 2 — exact mulțimea {0..7}, reordonată. Efectul: fiecare din cele b rânduri are aceeași sumă, așa că un singur `rowSum` × b închide tot blocul.

### T2-E2 — Modelul mental

De ce e bijecție: XOR cu un y fixat e propria lui inversă — dacă `x1 ^ y == x2 ^ y`, aplici `^ y` pe ambele părți și obții x1 == x2; deci pe [0,p) nicio valoare nu se repetă, și cum rezultatele rămân sub p (T1-E2, proprietatea 2 — aici e nevoie de y < p), toate cele p valori apar exact o dată. „Permutare" aici înseamnă: același multiset, altă ordine. Invariantul de reținut: **conținutul unui rând nu depinde de y** (doar ordinea lui depinde) — condiționat strict de y < p. Asta transformă o sumă 2D într-o singură sumă 1D înmulțită cu numărul de rânduri.

### T2-E3 — Scenariul predictiv

p=4: rândurile y=0..3 sunt chiar tabela din T1-E3 — fiecare rând {0,1,2,3} reordonat, suma fiecărui rând 6. Predicția pe caz-limită: pentru y=4 (= p), `x ^ 4` pe x=0..3 dă 4, 5, 6, 7 — NU mai e o permutare a lui {0..3}: bitul 4 al lui y supraviețuiește în toate rezultatele. Exact de aceea `rect` aplică forma închisă doar pe felia y < p: la linia [74](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java), `b0 = Math.min(b, p)` taie rândurile y ≥ p și le trimite altui cadran (cu offset, vezi T4).

### T2-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: cardinalitatea. „Pentru blocul închis 512×435 din F4, `rowSum` se execută o dată sau de 435 de ori — și ce anume garantează că o singură execuție ajunge?"

### T2-EQ — Răspunsul

Exact o dată. La linia [72](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java) `rowSum(p, off, l, t)` apare ca argument unic al lui `mulMod(b % t, …)` — Java îl evaluează o singură dată, apoi înmulțește rezultatul cu numărul de rânduri. Garanția e invariantul din T2-E2: toate cele 435 de rânduri ale blocului au aceeași sumă, pentru că fiecare conține același multiset de valori. Pe cifre mici: b=3, p=4, l=0 → un singur `rowSum` dă 0+1+2+3=6, iar blocul întreg e 3×6=18 — nu trei calcule de rând, ci unul înmulțit cu 3. Predicție vecină: dacă sumele rândurilor ar diferi (cum se întâmplă când y trece de p), înmulțirea ar fi pur și simplu incorectă — de asta există tăietura `b0` de la linia 74.

## T3. Suma aritmetică cu prag (rowSum)

### T3-E1 — Traseul mecanic

`rowSum(p, off, l, t)` ([BecomeImmortal.java:88-101](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java)) primește un rând-permutare cu valorile brute off+0 … off+p−1 și întoarce suma donațiilor lui, fără nicio buclă. Prima instrucțiune (linia 89) comprimă pragul: `threshold = l − off` — v donează doar dacă v > threshold. Apoi trei ramuri: (1) linia 90, `threshold >= p-1` → return 0, nici măcar v=p−1 nu trece; (2) linia 93, `threshold < 0` → toate cele p valori donează: triunghiul complet `triangularMod(p-1)` plus platoul `p × (off − l)` (liniile 95-97); (3) linia 100, cazul din mijloc → donează doar v ∈ (threshold, p−1], cu valorile nete 1, 2, …, D unde D = p−1−threshold → `triangularMod(D)`. Pentru F2 (p=8, off=0, l=1): threshold=1, D=6, rezultat 6·7/2 = 21.

### T3-E2 — Modelul mental

Ideea: după scăderea pragului, donațiile nete ale unui rând nu sunt niște numere oarecare — sunt exact secvența 1, 2, …, D, adică un **număr triunghiular** D(D+1)/2, calculabil dintr-o singură înmulțire. Invariantul: `rowSum` e singurul loc din soluția rapidă unde „se adună" donații individuale, și o face fără să itereze — liniile 88-101 nu conțin niciun `for`. Cele trei ramuri sunt cele trei poziții posibile ale pragului față de intervalul de valori [off, off+p−1]: deasupra (totul înghițit), sub el (totul donează: triunghi + platou constant), sau în interior (triunghi trunchiat).

### T3-E3 — Scenariul predictiv

p=8, off=0: cu l=4 → threshold=4, D=8−1−4=3 → 1+2+3 = 6. Cu l=7 → threshold=7 = p−1 → ramura 1, suma 0: pierderea înghite până și valoarea maximă 7. Cu off=16, l=4 → threshold=−12 → ramura 2: triunghiul 0+1+…+7 = 28 plus platoul 8×12 = 96, total 124 — fiecare din cele 8 valori brute 16..23 donează integral (16−4=12, …, 23−4=19; 12+13+…+19 = 124, verificabil de mână).

### T3-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: perechea P2 (l ca prag vs l ca pierdere). „Kata descrie două lucruri aparent diferite: donația suferă o pierdere l, iar worshipperul care «nu poate canaliza mai mult de l» e exclus complet. Worshipperul cu x^y exact egal cu l e exclus sau donează 0 — și schimbă vreo interpretare suma?"

### T3-EQ — Răspunsul

Nu schimbă nimic: ambele interpretări produc aceeași sumă, și codul le implementează simultan. Stabilesc întâi fiecare vocabular separat. **Pierderea**: donația netă = (x^y) − l, tăiată la 0 — formula `max(0, v − l)`. **Pragul**: contribuie doar worshipperii cu x^y strict peste l — condiția v > l. Maparea: pentru v = l, pierderea dă max(0, 0) = 0, iar pragul îl exclude — 0 adăugat și 0 exclus sunt aceeași sumă. Dovezi: oracolul folosește pierderea cu tăiere (`donated > 0`, liniile [23-24](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java)), `countDonors` folosește pragul strict (`(x ^ y) > l`, linia 37), iar comentariul de la linia 89 („v must exceed") fixează aceeași alegere în `rowSum`. Predicție vecină: în F1, celulele cu v=1=l nu apar printre cei 30 de donatori, dar nici n-ar fi adăugat ceva — cele două numărători coincid.

## T4. Descompunerea în cadrane

### T4-E1 — Traseul mecanic

Declanșator: `rect(a, b, off, l, t)` cu a care NU e putere de 2 (altfel T2 închide direct). Filmul pe primul apel din F4, `rect(545, 435, off=0)`: linia 68 ia `p = highestOneBit(545) = 512`; 545 ≠ 512, deci se taie. Linia 74: `b0 = min(435, 512) = 435`. Se formează cadranele: **q1** (linia 75) = blocul x<512, y<435 — închis pe loc cu `435 × rowSum(512, off=0)`; **q2** (linia 76) = fâșia x ∈ [512, 545), y<435 — recursie `rect(33, 435, off=512)`: aici x = 512 + x′ cu x′ < 33, iar y < 512, deci bitul 512 supraviețuiește XOR-ului: `x ^ y = 512 + (x′ ^ y)` — valoarea 512 pleacă în parametrul `off`. Cum b=435 < p=512, cadranele q3/q4 (liniile 77-80) nu există la acest nivel. Efect: un dreptunghi 545×435 devine un bloc închis + un dreptunghi 33×435 cu offset 512.

### T4-E2 — Modelul mental

De ce tăietura e la cea mai mare putere de 2: sub p, toți indicii au bitul p stins; peste p (dar sub 2p), toți îl au aprins. XOR-ul între o parte „peste" și una „sub" păstrează bitul intact — el devine o constantă a cadranului, adunată ca `off`, iar în interior problema arată identic cu una mică (coordonate locale x′, y′). În q4 (ambele peste, linia 79) biții de sus se anulează reciproc — off rămâne neschimbat. Aici se separă perechea **P1**: **programare dinamică** înseamnă subprobleme care se SUPRAPUN și se recalculează, deci merită cache; aici cele 2-3 subprobleme ale unui apel acoperă zone disjuncte ale dreptunghiului și nu se reîntâlnesc niciodată — e **divide-et-impera** curat (perechea P4 din planul „programare dinamică": mergesort, nu Fibonacci). Eticheta „Dynamic Programming" a kata-ului descrie familia de probleme din care vine, nu mecanismul soluției — inferență despre intenția autorului, nu fapt din cod.

### T4-E3 — Scenariul predictiv

Derulare completă de mână pe m=3, n=2, l=0, t=100: `rect(3,2,0)`: p=2, b0=2; q1 = 2 × rowSum(2, off=0) = 2 × 1 = 2 (rândurile {0,1}); q2 = `rect(1, 2, off=2)` → swap la (2,1) → p=2=a → închis: 1 × rowSum(2, off=2), threshold=−2 → triunghi 1 + platou 2×2 → 5. Total 2+5 = 7. Verificare prin grila brută: 0,1 / 1,0 / 2,3 → suma exactă 7. Variația: m=2, n=2 → p=2=a de la primul apel, un singur bloc închis, suma 2 — recursia nici nu pornește.

### T4-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: perechea P5. „`p` se ia din `a` — dimensiunea MARE, după swap (liniile 63-68). Dacă l-ai lua din dimensiunea mică `b`, ce premisă a descompunerii s-ar rupe prima?"

### T4-EQ — Răspunsul

S-ar rupe premisa „fâșia rămasă încape sub p", pe care stă mutarea offset-ului. `highestOneBit(a)` garantează p ≤ a < 2p, deci restul a−p e strict sub p și coordonatele locale x′ din q2 (linia 76) au garantat bitul p stins — condiția ca `x ^ y = p + (x′ ^ y)` să fie o egalitate, nu o aproximare. Cu p luat din b (mic), a poate depăși 2p — exemplu chiar apelul de vârf din F4: a=545, b=435 ar da p=256, iar fâșia x ∈ [256, 545) ar avea lățimea 289 > 256, în care x′ = x−256 conține el însuși bitul 256 — descompunerea „bit constant + problemă locală" nu mai descrie celulele. Codul real ia p = highestOneBit(545) = 512, rest 33 < 512 ✓. Predicție vecină: pentru orice a, restul a − highestOneBit(a) e strict mai mic decât puterea tăiată — de asta recursia pierde câte un bit de vârf la fiecare nivel (firul T6).

## T5. Aritmetică modulară cu t

### T5-E1 — Traseul mecanic

Doi actori mărunți fac toată igiena modulară. `mulMod(a, b, t)` ([BecomeImmortal.java:111-113](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java)): primește factori DEJA reduși sub t (deci sub 2^32, pentru că kata garantează t ≤ 2^32−1), îi înmulțește cu `*` obișnuit și dă restul cu `Long.remainderUnsigned`. `triangularMod(d, t)` (liniile 104-108): desparte produsul d·(d+1) în factorul par și cel impar (liniile 105-106), ÎNJUMĂTĂȚEȘTE factorul par cât e încă întregul adevărat (`even / 2`), abia apoi îl reduce `% t` și îl dă lui `mulMod` (linia 107). Pentru d=6: even=6 → 3, odd=7 → 3×7 = 21 = 6·7/2 ✓. Sumele parțiale se reduc la fiecare pas: `(res + …) % t` la liniile 76-79 și `(sum + donated) % t` la linia 25.

### T5-E2 — Modelul mental

Mod-ul comută cu adunarea și înmulțirea — poți reduce oricând, oricâte ori, rezultatul final nu se schimbă. NU comută cu împărțirea: a/2 mod t nu se poate calcula din a mod t (fără invers modular). De aici invariantul întregii soluții: **orice împărțire la 2 se execută înainte de orice reducere**, pe un factor despre care știi că e cu adevărat par — iar în d·(d+1) unul dintre factori e par întotdeauna. Vocabularul perechii **P3**: „mod pe sumă" = reducerea acumulatorului după fiecare adunare (oriunde, oricând, inofensivă); „mod pe factori" = reducerea operanzilor unei înmulțiri înainte de produs (permisă, dar NUMAI după ce împărțirile au fost consumate). Al doilea invariant: niciun `mulMod` nu primește vreodată un factor ≥ 2^32.

### T5-E3 — Scenariul predictiv

t=10, D=13: corect — even=14, 14/2=7, 7%10=7; odd=13%10=3; 7×3=21 → 21%10 = **1** (referința: 13·14/2 = 91, 91%10 = 1 ✓). Variația greșită — reduci ÎNTÂI: 14%10=4, 4/2=2, 2×3=6 → **6** ≠ 1. Observația fină: 4 e par, împărțirea „a mers" fără rest — și rezultatul tot e greșit, pentru că 14 ≡ 4 (mod 10) nu implică 7 ≡ 2 (mod 10). Paritatea păstrată nu salvează nimic: congruența mod t pur și simplu nu supraviețuiește împărțirii.

### T5-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: actorul exact al corectitudinii. „În `mulMod`, produsul `a * b` poate depăși `Long.MAX_VALUE` — de ce rezultatul lui `Long.remainderUnsigned(a * b, t)` e totuși exact, și nu un rest calculat dintr-un număr deja corupt?"

### T5-EQ — Răspunsul

Pentru că biții produsului sunt toți acolo — doar eticheta lor de semn e falsă. Cu a, b < 2^32 (precondiția din javadoc, linia 110), produsul real e cel mult (2^32−1)² = 2^64 − 2^33 + 1 < 2^64, deci încape EXACT în cei 64 de biți ai unui `long`; înmulțirea Javei calculează acești 64 de biți corect indiferent de semn. Singura problemă ar fi interpretarea: citit ca `long` cu semn, un produs peste 2^63−1 pare negativ. `Long.remainderUnsigned` (linia 112) citește aceiași biți ca număr FĂRĂ semn, deci împarte valoarea adevărată la t. Contrast: dacă un singur factor ar atinge 2^33, produsul ar depăși 2^64, biții de vârf s-ar pierde fizic (wrap) și nicio interpretare nu i-ar mai recupera — de asta reducerea factorilor sub t înainte de `mulMod` nu e cosmetică. Predicție vecină: varianta „naivă" din F5 pică exact pe interpretare — acolo `/2` și `%` cu semn citesc biții corecți ca număr negativ.

## T6. Complexitate: O(log) vs O(m·n)

### T6-E1 — Traseul mecanic

Contorul `rectCalls` ([BecomeImmortal.java:46](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java), incrementat la linia 59, resetat în `elderAge` la linia 50) numără apelurile reale. Rularea din 2026-08-22: `elderAge(545, 435, 342, 1000007)` → **9 apeluri** `rect`, față de 545×435 = **237.075 celule** la oracol; `elderAge(28827050410, 35165045587, …)` → **30 de apeluri**, față de ~1,01×10²¹ celule — un dreptunghi pe care bucla dublă nu l-ar termina niciodată practic. Fiecare apel face muncă O(1): un `rowSum` (fără bucle, T3) plus 2-3 apeluri copil.

### T6-E2 — Modelul mental

Grila de estimare (aceeași ca la orice DP/recursie): **cost = numărul de subprobleme × costul uneia**. Oracolul are m·n subprobleme (celulele) a câte O(1). Recursia are ca subprobleme cadranele, iar numărul lor e legat de biții dimensiunilor, nu de mărimea lor: q2 (linia 76) taie bitul de vârf al dimensiunii mari, q4 (linia 79) taie bitul de vârf al ambelor, iar q3 (linia 78) primește a = p — putere de 2 — și se închide din primul apel prin ramura de la linia 69. Lanțul de apeluri rămâne subțire, de lungimea numărului de biți: 35165045587 are 36 de biți → 30 de apeluri măsurate; 545 are 10 biți → 9 apeluri. (Forma exactă a marginii superioare — câte apeluri per bit — e inferență susținută de măsurători, nu demonstrată aici.)

### T6-E3 — Scenariul predictiv

Dublezi dimensiunea mare: 545 → 1090 adaugă UN bit → te aștepți la ~1-2 apeluri în plus, nu la dublarea muncii; oracolul, în schimb, își dublează exact numărul de celule (237.075 → 474.150). Cazul-limită inversat: m = n = 2^40 — dreptunghiul cel mai MARE posibil la acel ordin — costă exact 1 apel, pentru că 2^40 e putere de 2 și ramura închisă de la linia 69 prinde totul din prima; „mai mare" nu înseamnă „mai scump" aici, ci „mai aliniat".

### T6-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: cardinalitatea recursiei. „`rect` se autoapelează în până la 3 locuri (liniile 76-79); o recursie ternară de adâncime 36 ar da 3^36 ≈ 1,5×10¹⁷ apeluri. De ce contorul arată 30 și nu explodează exponențial?"

### T6-EQ — Răspunsul

Pentru că din cele 3 ramuri doar una rămâne „grasă" pe fiecare direcție, iar una moare instantaneu. Concret: q3 = `rect(p, b−p, …)` are prima dimensiune putere de 2 → după swap intră direct în ramura de la linia 69 → costă exact 1 apel, fără copii. q2 și q4 primesc a−p — numărul 545 fără bitul lui de vârf — deci fiecare NIVEL de bit consumă bitul de vârf al cel puțin unei dimensiuni; după cel mult 36 de niveluri nu mai există biți. Arborele real e un lanț îngust cu frunze-q3 de cost 1 atârnate de el, nu un arbore ternar plin. Verificarea pe traseul F4 (545×435): din cele 9 apeluri, doar 4 mai recursează; q3/q4 apar o singură dată în tot traseul (la apelul `rect(51,33,896)`, unde b=33 > p=32). Predicție vecină: pentru un dreptunghi cu ambele dimensiuni sub 2^36, contorul nu poate atinge nici măcar 3×36 = 108 — ordinea de mărime rămâne „zeci".

## T7. Brute force ca oracol

### T7-E1 — Traseul mecanic

`bruteForce(m, n, l, t)` ([BecomeImmortal.java:19-31](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java)): bucla dublă de la liniile 21-22 vizitează celulele în ordine rând-cu-rând; linia 23 calculează donația netă, linia 24 o admite doar dacă e strict pozitivă, linia 25 o adaugă și reduce imediat `% t`. `countDonors` (liniile 33-43) e aceeași plimbare, dar numără celulele cu `(x ^ y) > l`. Rularea F1: 40 de celule vizitate, 30 de donatori, rezultat 5.

### T7-E2 — Modelul mental

Un **oracol** e o a doua implementare, lentă dar atât de simplă încât corectitudinea ei se vede cu ochiul liber — iar valoarea lui vine din INDEPENDENȚĂ: `bruteForce` nu știe nimic despre puteri de 2, permutări, `rowSum` sau offset-uri; singurul lucru împărtășit cu `rect` e definiția problemei de la linia 23. Când două mecanisme fără nicio piesă comună dau același număr pe sute de inputuri (F6), greșeala identică simultană devine neplauzibilă. Invariantul de proiectare: oracolul NU se optimizează — orice deșteptăciune adăugată i-ar reduce exact calitatea de martor independent.

### T7-E3 — Scenariul predictiv

Costul oracolului e m·n: pe 8×5 = 40 de pași, instant. Pe inputul mare din F4 ar fi 28827050410 × 35165045587 ≈ 1,01×10²¹ pași — la 10⁹ celule/secundă, în jur de 32.000 de ani. Predicția practică: pentru orice verificare, alegi între oracol (posibil doar sub ~10⁸ celule) și răspunsuri publicate extern (cele două valori-țintă din F4, preluate din testele kata-ului). Variația: dacă mărești fereastra fuzz-ului de la 64 la 10.000, oracolul încă ține (10⁸ celule/rulare), dar cele 500 de rulări devin minute bune — limita oracolului e bugetul de timp, nu corectitudinea.

### T7-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: plasarea mod-ului (P3, latura inofensivă). „De ce oracolul reduce `% t` la FIECARE adunare (linia 25), în loc s-o facă o singură dată, pe suma totală, la final — și s-ar schimba rezultatul?"

### T7-EQ — Răspunsul

Rezultatul nu s-ar schimba niciodată matematic — (a+b) mod t = ((a mod t) + b) mod t, reducerea acumulatorului e exact cazul „mod pe sumă" din T5-E2 — dar varianta „o dată la final" poate să nu mai aibă CE reduce: fără reducere pe parcurs, acumulatorul crește nelimitat, iar la un dreptunghi mare-dar-parcurgibil (de pildă 2^20 × 2^20 cu valori până la ~2^21) suma reală depășește 2^63−1 și `long`-ul se corupe înainte de ultimul `% t`. Reducerea per pas ține acumulatorul mereu sub t + valoarea curentă < 2^33 — departe de orice margine. Aceeași disciplină apare identic în soluția rapidă la liniile 76-79. Predicție vecină: pe F1 (sumă brută 105, t=100) ambele variante afișează 5 — inputul e prea mic ca să demaște diferența; bug-urile de mod se văd doar la scară, tema firului F5.

---

# Capitolul F — Fire de execuție

## F1. Oracolul pe m=8, n=5, l=1, t=100

### F1-E1 — Traseul mecanic

Declanșator: `main` ([BecomeImmortal.java:129-132](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java)) apelează `countDonors(8, 5, 1)` și `bruteForce(8, 5, 1, 100)`. Bucla vizitează cele 8×5 = 40 de celule rând cu rând; pe fiecare rând y, valorile `x ^ y` sunt 0..7 permutate (T2), din care donează cele 6 cu v ≥ 2, cu netul (2−1)+(3−1)+…+(7−1) = 21 pe rând. După 5 rânduri: 30 de donatori și suma brută 5×21 = 105; reducerea per pas de la linia 25 o împachetează în 105 − 100 = 5. Ieșirea tipărită (rularea 2026-08-22): `celule vizitate=40, donatori (x^y > l)=30, rezultat=5` — exact exemplul publicat al kata-ului, `elder_age(8,5,1,100) == 5`.

### F1-E2 — Modelul mental

Aici se vede pentru prima dată diferența dintre **vârsta primită** și **vârsta stocată**: Bătrânul primește 105 secunde, dar sistemul „programatorilor leneși" numără doar 0..t−1 = 0..99, deci 105 se înfășoară la 5. t nu e o a treia regulă de joc, ci doar formatul de raportare — toată fizica problemei (XOR, prag) s-a terminat înainte ca mod-ul să atingă ceva. Și o cardinalitate de fixat: 30 de donatori ≠ 30 de secunde; numărătoarea și suma sunt măsurători diferite ale aceleiași grile (6 donatori/rând, dar 21 de secunde/rând).

### F1-E3 — Scenariul predictiv

Cu t=1000 în loc de 100, nimic nu se mai înfășoară: rezultatul ar fi chiar 105. Cu l=0: pe fiecare rând donează cele 7 valori v ≥ 1 cu net 1+2+…+7 = 28 → 35 de donatori, suma brută 140, afișat 40. Varianta de eșec total: l=7 — nicio valoare dintr-un rând {0..7} nu depășește 7, deci 0 donatori, 0 secunde, iar rezultatul 0 nu mai depinde nici de t.

### F1-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: cardinalitatea pragului. „Doar două valori (0 și 1) nu trec pragul l=1, și totuși lipsesc 10 donatori din 40, nu 2. Unde se pierd ceilalți 8?"

### F1-EQ — Răspunsul

Nu se pierd nicăieri — „0 și 1" sunt valori PER RÂND, nu per grilă. Fiecare din cele 5 rânduri e o permutare completă a lui {0..7} (T2-E2), deci valorile 0 și 1 apar câte o dată în FIECARE rând: 2 celule mute × 5 rânduri = 10 celule sub prag, 40 − 10 = 30 de donatori — numărul măsurat de `countDonors` (linia 37: condiția strictă `> l` le exclude și pe cele cu v=1, iar v=1 net ar fi fost oricum 0, T3-EQ). Confuzia e între mulțimea de valori distincte {0,1} (2 elemente) și multisetul aparițiilor lor în grilă (10 apariții). Predicție vecină: cu l=2, mulțimea sub prag devine {0,1,2} → 15 celule mute → 25 de donatori.

## F2. Soluția rapidă pe același input

### F2-E1 — Traseul mecanic

`elderAge(8, 5, 1, 100)` → `rect(8, 5, off=0)`: linia 68 dă `p = highestOneBit(8) = 8`, iar testul de la linia 69 `p == a` e ADEVĂRAT din primul apel — 8 e putere de 2. Nicio tăiere în cadrane: metoda sare direct la forma închisă (linia 72): `rowSum(8, off=0, l=1)` → threshold = 1, D = 8−1−1 = 6 → 21; apoi `mulMod(5, 21)` = 105, redus mod 100 → **5**. Contorul măsurat: `apeluri rect=1`. Corecție față de plan: linia F2 din hartă anticipa „tăiere în cadrane la 2^2=4" — rularea a arătat că nu se taie nimic; anticiparea era o instanță a confuziei P5 (vezi F2-EQ).

### F2-E2 — Modelul mental

Ce s-a schimbat față de F1 nu e rezultatul, ci UNITATEA de lucru: oracolul adună celulă cu celulă (40 de operații), `rect` socotește rând-tip cu înmulțire (2 operații mari: un `rowSum`, un `mulMod`). Recursia din T4 nu e „motorul" soluției — e doar reductorul care aduce orice dreptunghi strâmb la astfel de blocuri închise; când inputul vine deja aliniat (a = putere de 2), reductorul nu are nimic de făcut. Modelul corect: forma închisă e regula, recursia e excepția pentru marginile nealineate.

### F2-E3 — Scenariul predictiv

Împinge inputul cu 1: `elderAge(9, 5, 1, 100)` → p = highestOneBit(9) = 8 ≠ 9, deci abia ACUM se taie: q1 = blocul închis 8×5 (adică fix problema F2, 105) + q2 = `rect(1, 5, off=8)` — fâșia coloanei x=8, unde fiecare celulă donează 8+(0..4 xor …) − 1… net (8^y)−1 pentru y=0..4 = 7+8+9+10+11 = 45; total brut 150, afișat 50. Variația-oglindă: `elderAge(8, 9, …)` — swap-ul de la liniile 63-67 îl face identic cu 9×8, tăierea se întâmplă tot la 8.

### F2-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: corecția din sesiunea de plan (P5). „Planul prezicea tăiere la 2^2 = 4 pentru 8×5. De unde vine 4-ul acela greșit și după ce regulă se alege în realitate punctul de tăiere?"

### F2-EQ — Răspunsul

4 = highestOneBit(5) — puterea de 2 a dimensiunii MICI. Regula reală (linia 68, DUPĂ swap-ul de la 63-67): p = highestOneBit(a) unde a e dimensiunea MARE — deci 8, care coincide cu a și închide totul fără recursie (F2-E1). Cele două candidate diferă exact în perechea P5, iar T4-EQ arată de ce varianta „din mică" e și incorectă structural, nu doar alt punct de tăiere: fâșia rămasă n-ar mai încăpea sub p și egalitatea `x ^ y = p + (x′ ^ y)` s-ar rupe. Predicție vecină: pentru 5×8 planul ar fi ghicit la fel de greșit — swap-ul face dimensiunea mare 8 înainte de orice tăiere.

## F3. Pătratul complet m=n=16

### F3-E1 — Traseul mecanic

`main` (liniile 136-138) rulează perechea `bruteForce(16, 16, 3, 1000)` și `elderAge(16, 16, 3, 1000)`. Traseul rapid: `rect(16, 16, off=0)` → p = 16 = a → forma închisă din primul apel: `rowSum(16, 0, 3)` → threshold = 3, D = 16−1−3 = 12 → 12·13/2 = 78; apoi 16 × 78 = 1248, redus mod 1000 → **248**. Oracolul, pe cele 256 de celule, tipărește același 248; contorul: `apeluri rect=1`. Aici lucrează și `triangularMod(12)`: even=12 → 6, odd=13 → `mulMod(6, 13)` = 78 — înjumătățirea înainte de mod din T5, pe cifre mici.

### F3-E2 — Modelul mental

Pătratul complet 2^k × 2^k e ATOMUL întregii metode: singura figură pe care suma se scrie dintr-o bucată (b = p rânduri × același triunghi). Tot ce face T4 e să spargă orice dreptunghi în astfel de atomi plus offset-uri; F3 e atomul văzut singur, fără context. Cardinalitatea de reținut: 16 rânduri, UN `rowSum`, 78 de secunde per rând — trei numere cu roluri diferite (câte, o dată, cât).

### F3-E3 — Scenariul predictiv

Cu l=0: D = 15 → 120 pe rând → 16×120 = 1920 → afișat 920. Cu l=14: D = 1 → fiecare rând donează exact 1 (doar v=15 trece, net 15−14=1) → total 16. Varianta de stingere: l=15 → threshold = 15 = p−1 → ramura de la linia 90 întoarce 0 înainte de orice formulă — tot pătratul e înghițit de pierdere.

### F3-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: tranziția la limită. „La m=n=16, l=15, fiecare rând CONȚINE valoarea maximă 15 — 16 celule cu v=15 în tot pătratul. De ce rezultatul e totuși 0 și pe ce ramură exactă se decide?"

### F3-EQ — Răspunsul

Pentru că a conține valoarea maximă nu înseamnă a dona: celula cu v = 15 canalizează exact cât pierderea l = 15, deci netul ei e max(0, 15−15) = 0 — pragul strict din T3-EQ o lasă pe dinafară, ca pe toate celelalte. Mecanic, decizia cade la [BecomeImmortal.java:90-92](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java): threshold = l − off = 15 ≥ p − 1 = 15 → `return 0`, fără să se construiască vreun triunghi; comentariul liniei 91 spune chiar asta: „even v = p-1 is swallowed by the loss". Cele 16 celule „maxime" există, dar existența nu se numără — donația se numără. Predicție vecină: l=14 mută threshold-ul la 14 < 15 → D=1 → exact acele 16 celule devin singurii donatori, cu 1 secundă fiecare (rezultatul 16 din F3-E3).

## F4. Dimensiuni mari nealineate: 545×435 și ~10²¹ celule

### F4-E1 — Traseul mecanic

`elderAge(545, 435, 342, 1000007)` execută exact 9 apeluri `rect` (contor măsurat), într-un lanț pe care merită să-l vezi întreg — fiecare apel taie la propriul p și lasă un bloc închis în urmă:

| # | apel (a×b, off) | p | bloc închis pe loc | recursii născute |
|---|-----------------|-----|--------------------|------------------|
| 1 | 545×435, off 0 | 512 | 512×435 | #2 |
| 2 | 33×435 → swap 435×33, off 512 | 256 | 256×33 | #3 |
| 3 | 179×33, off 768 | 128 | 128×33 | #4 |
| 4 | 51×33, off 896 | 32 | 32×32 | #5, #6, #7 (b=33 > p=32) |
| 5 | 19×32 → swap 32×19, off 928 | 32 | tot (p=a) | — |
| 6 | 32×1, off 928 | 32 | tot (p=a) | — |
| 7 | 19×1, off 896 | 16 | 16×1 | #8 |
| 8 | 3×1, off 912 | 2 | 2×1 | #9 |
| 9 | 1×1, off 914 | 1 | tot (p=a) | — |

Suma tuturor blocurilor, redusă mod 1000007, dă **808451** — valoarea publicată în testele kata-ului. Al doilea input, `elderAge(28827050410, 35165045587, 7109602, 13719506)`, închide în **30 de apeluri** un dreptunghi de ~1,01×10²¹ celule → **5456283**, tot valoarea publicată.

### F4-E2 — Modelul mental

`off` e ADRESA cadranului, citită în donație: la fiecare coborâre prin q2/q3 (liniile 76 și 78), puterea tăiată se adaugă la off pentru că exact una dintre coordonate a trecut de tăietură — bitul supraviețuiește XOR-ului; la q4 (linia 79) ambele au trecut — biții se anulează și off rămâne neschimbat. De asta apelul #9 are off = 914 = 512+256+128+16+2: istoria tăieturilor la care fâșia lui x a fost „peste" iar y „sub". Invariantul: donația brută a oricărei celule = off-ul cadranului ei + XOR-ul coordonatelor locale — descompunerea nu pierde și nu inventează secunde, doar le contabilizează pe blocuri.

### F4-E3 — Scenariul predictiv

Predicții fără rulare, din structură: (1) `elderAge(435, 545, 342, 1000007)` — swap-ul din primul apel face traseul identic, tot 9 apeluri, tot 808451; (2) cu l ≥ 1023 rezultatul devine 0: toate valorile `x ^ y` pentru x, y < 1024 stau sub 2^10 = 1024, deci niciun net pozitiv — și fiecare `rowSum` ar cădea pe ramura `threshold >= p-1`; (3) varianta de eșec: dacă cineva ar șterge swap-ul (liniile 63-67), apelul #2 ar porni cu a=33 < b=435 și `highestOneBit(33)=32` ar tăia din dimensiunea mică — exact ruptura demonstrată în T4-EQ.

### F4-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: actorul care „vede" offset-ul. „În apelul #2, `rect(33, 435, off=512)`, coordonatele reale x ∈ [512, 545) nu mai există nicăieri — ce actor adaugă înapoi cele 512 secunde de căciulă la donațiile acestui cadran, și pe ce linie?"

### F4-EQ — Răspunsul

`rowSum`, prin singurul loc unde off intră în aritmetică: linia [89](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java), `threshold = l − off`. Căciula nu se adaugă la sfârșit — se SCADE din prag la început: pentru blocul închis al apelului #2 (256×33, off 512+256=768 la copii, dar ia blocul propriu: `rowSum(256, off=512, l=342)`), threshold = 342 − 512 = −170 < 0 → ramura de la liniile 93-97: fiecare din cele 256 de valori locale donează integral — triunghiul `triangularMod(255)` = 32640 plus platoul `256 × 170` = 43520 (adică minus pierderea, valorile brute pornesc de la 512 iar l e doar 342). Deci actorul e formula pragului, nu vreo adunare explicită de 512 per celulă. Predicție vecină: orice cadran cu off > l nu mai poate cădea pe ramura „totul înghițit" — pragul e deja negativ înainte de a se uita la valori.

## F5. Capcana de overflow pe D·(D+1)/2

### F5-E1 — Traseul mecanic

`overflowDemo(4000000000, 1000000007)` ([BecomeImmortal.java:116-126](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java)) calculează același număr triunghiular pe patru căi; rularea tipărește: `referinta(BigInteger)=378 | naiv(d*(d+1)/2)=-291171626 | injumatatireDupaMod=500000395 | corect(triangularMod)=378`. Filmul căii naive (linia 119): d·(d+1) = 16.000.000.004.000.000.000 — sub 2^64, dar peste `Long.MAX_VALUE` = 9.223.372.036.854.775.807, deci ca `long` CU semn devine −2.446.744.069.709.551.616; împărțirea și restul cu semn ale Javei lucrează pe acest număr negativ și livrează −291171626. Filmul căii „înjumătățire după mod" (linia 122): even = 4×10⁹, dar even % t = 999999979 — IMPAR; `/ 2` trunchiază la 499999989 și produsul final iese 500000395, fără nicio legătură cu 378.

### F5-E2 — Modelul mental

Două mecanisme de eșec distincte, cu vocabular propriu. **Eșecul de interpretare** (calea naivă): biții produsului sunt încă toți prezenți (T5-EQ), dar `/` și `%` cu semn îi citesc ca număr negativ — datele sunt întregi, ochelarii sunt greșiți. **Eșecul de informație** (înjumătățirea după mod): reducerea `% t` a distrus proprietatea „e parul adevărat" — 999999979 nici măcar nu mai e par, iar jumătatea lui trunchiată nu mai corespunde niciunei mărimi din problemă. Ordinea corectă, o singură propoziție: împarte parul la 2 CÂT E ÎNTREG, redu abia apoi (linia 107). Ambele eșecuri sunt silențioase — niciun `ArithmeticException`, doar numere greșite cu aplomb.

### F5-E3 — Scenariul predictiv

Pe cifre de mână, t=10, D=13 (scenariul din T5-E3): corect 1; înjumătățirea după mod dă 6 — greșită chiar dacă 14 % 10 = 4 e PAR și împărțirea nu trunchiază nimic: congruența mod 10 nu supraviețuiește împărțirii la 2 (7 ≢ 2 mod 10). Iar calea naivă pe D=13 e perfect corectă (91 încape lejer în long) — predicția de reținut: bug-ul naiv DOARME sub D ≈ 3×10⁹ (unde D·(D+1) < 2^63) și trece orice test mic; se trezește doar la scară, exact unde oracolul nu mai poate verifica (T7-E3).

### F5-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: mărimea erorii. „500000395 față de 378 — eroarea nu arată ca o «scăpare de rotunjire cu 1». De ce trunchierea unei singure jumătăți produce un rezultat fără nicio legătură cu cel corect?"

### F5-EQ — Răspunsul

Pentru că nu s-a pierdut „o jumătate de unitate" — s-a înlocuit un factor cu altul. Intenția era (999999979/2) × 999999980 cu 999999979/2 = 499.999.989,5 — care nici nu e număr întreg: după reducere, produsul pur și simplu NU mai e divizibil cu 2, deci operația „/2" nu mai are obiect. Codul execută totuși ceva: factorul trunchiat 499999989 diferă de intenție cu 0,5, iar acel 0,5 se înmulțește cu 999999980 → o deplasare de ~5×10⁸, totul re-împachetat mod 1000000007 într-un număr cu aspect perfect plauzibil. Asta e semnătura bug-urilor modulare: rezultatul rămâne în intervalul valid [0, t), deci nimic nu „arată stricat" — doar comparația cu o referință (BigInteger la linia 117, sau oracolul) îl demască. Predicție vecină: schimbând t, eroarea sare haotic (alt rest, altă paritate a lui even % t) — un bug care „se mișcă" între două valori ale lui t e aproape sigur de ordinea operațiilor modulare.

## F6. Fuzz: fast vs oracol, 500 de rulări

### F6-E1 — Traseul mecanic

Blocul din `main` ([BecomeImmortal.java:150-167](../../src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java)): `new Random(42)` (linia 151) — sămânță FIXĂ; 500 de iterații trag m, n ∈ [1, 64], l ∈ [0, 79], t ∈ [2, 1001] (liniile 155-158), rulează ambele implementări și tipăresc orice nepotrivire cu inputul complet (linia 163). Rularea din 2026-08-22: `500 rulari, 0 nepotriviri`. Rangele nu sunt întâmplătoare: 64×64 ține oracolul instant; l până la 79 acoperă și „totul înghițit" (cu m, n ≤ 64 indicii stau sub 64, deci x^y ≤ 63 — orice l ≥ 63 stinge tot dreptunghiul); t mic forțează înfășurări dese, ca mod-ul să fie mereu exersat, nu decorativ.

### F6-E2 — Modelul mental

Fuzz-ul e a treia sursă de încredere, complementară celor două de dinainte: exemplele alese de om (F1-F3) verifică ce ți-ai imaginat; răspunsurile publicate (F4) verifică două puncte adânci; fuzz-ul verifică sute de combinații pe care NU ți le-ai imaginat — margini m=1, l peste orice valoare, t=2. Sămânța fixă 42 îl face determinist: o nepotrivire găsită azi se reproduce identic mâine, cu inputul ei tipărit — un caz de regresie gratuit. Invariantul de proiectare (din T7-E2): puterea comparației vine din independența totală a celor doi actori comparați.

### F6-E3 — Scenariul predictiv

Mutații și cine le prinde: (1) strici q4 — schimbi `off` în `off + p` la linia 79 — fuzz-ul ar tipări MISMATCH în primele zeci de rulări (orice input cu ambele dimensiuni peste o putere de 2 lovește q4); (2) strici `triangularMod` în varianta „înjumătățire după mod" — fuzz-ul o prinde și pe asta (T5-E3 arată eșecul deja la D=13, t=10, adică în plin range); (3) strici `mulMod` cu overflow REAL (factori nereduși) — fuzz-ul NU o prinde: la m, n ≤ 64 și t ≤ 1001 toate produsele stau sub 2^63; asta rămâne pe seama analizei din T5 și a răspunsurilor publicate din F4.

### F6-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: întinderea dovezii. „După «500 rulari, 0 nepotriviri» — ce anume S-A demonstrat și ce NU s-a demonstrat despre `elderAge`?"

### F6-EQ — Răspunsul

S-a demonstrat: pe distribuția eșantionată — dreptunghiuri până în 64×64, l până în 79, t până în 1001 — cele două implementări coincid în toate cele 500 de puncte trase; cum oracolul e evident-corect prin construcție (T7-E2), `rect` e corect pe acea regiune cu încredere practică foarte mare. NU s-a demonstrat: nimic despre regiunea în care trăiește de fapt kata-ul — dimensiuni de 36 de biți (adâncimi de recursie pe care fuzz-ul nu le atinge: la a ≤ 64 lanțul are cel mult ~6 niveluri), produse aproape de 2^64, t aproape de 2^32. Pentru regiunea aceea, dovezile sunt cele două răspunsuri publicate reproduse exact (F4) plus argumentele de margine din T5 — iar asta e o limită de eșantionare inerentă, nu un defect reparabil al fuzz-ului. Predicție vecină: lărgirea rangelui la m, n ≤ 10⁴ ar cumpăra adâncime ~14 biți pe seama minutelor de oracol (T7-E3) — compromisul timp/acoperire se mută, nu dispare.

---

# Glosar

- **XOR (sau-exclusiv, `^`)** — operație bit cu bit: bitul rezultat e 1 exact când biții operanzilor diferă; comutativă și propria ei inversă.
- **kyu** — treapta de dificultate Codewars; 1 kyu e maximul.
- **worshipper** — personajul kata-ului aflat pe celula (x, y); donează `max(0, (x^y) − l)` secunde.
- **l (pierdere/prag)** — secundele pierdute la transmisie; echivalent cu pragul „donează doar cine canalizează strict peste l" (T3-EQ).
- **t** — capacitatea sistemului de dată: rezultatul se raportează modulo t (0..t−1).
- **dimensiune vs indice (P4)** — m, n numără coloane/rânduri; x, y sunt poziții 0-indexate, deci ≤ m−1, n−1.
- **permutare (pe rând)** — același multiset de valori, în altă ordine; pe un bloc 2^k, fiecare rând conține 0..2^k−1 exact o dată.
- **număr triunghiular** — 1+2+…+D = D·(D+1)/2; forma închisă a donațiilor nete ale unui rând.
- **cadran** — sub-dreptunghi obținut prin tăierea ambelor axe la puterea de 2; q1 închis, q2/q3 cu offset, q4 fără.
- **offset (off)** — suma biților de vârf „moșteniți" de un cadran; intră în calcul doar prin `threshold = l − off`.
- **highestOneBit(a)** — cea mai mare putere de 2 ≤ a; garantează p ≤ a < 2p.
- **divide-et-impera** — spargere în subprobleme DISJUNCTE rezolvate independent; aici, spre deosebire de DP, nimic nu se recalculează, deci nu e nevoie de cache (P1).
- **programare dinamică (DP)** — recurență peste subprobleme care se SUPRAPUN, cu memorare; eticheta kata-ului, nu mecanismul soluției.
- **mod pe sumă / mod pe factori (P3)** — reducerea acumulatorului (oricând sigură) vs reducerea operanzilor unei înmulțiri (sigură doar după consumarea împărțirilor).
- **overflow (semnat)** — depășirea lui 2^63−1 la `long`; biții pot rămâne exacți dar interpretarea cu semn îi citește negativ.
- **`Long.remainderUnsigned`** — restul împărțirii citind cei 64 de biți ca număr fără semn.
- **oracol** — implementare lentă, evident corectă și independentă structural, folosită ca referință.
- **fuzz (testare aleatoare)** — compararea celor două implementări pe inputuri trase la sorți; sămânța (seed) fixă o face reproductibilă.
- **seed** — starea inițială a generatorului aleator; aceeași sămânță → aceeași secvență de inputuri.

# Index de ancore în cod

Toate în `src/test/java/com/footballmanagergamesimulator/algorithms/dp/BecomeImmortal.java` (lab necomis, scris pe 2026-08-22 pentru acest manual):

| Linie | Ce ancorează | Folosit în |
|-------|--------------|------------|
| 19-31 | `bruteForce` — oracolul, bucla dublă | T7, F1 |
| 23-24 | definiția donației: `(x ^ y) - l`, admisă doar strict pozitivă | T1, T3, T7 |
| 25 | mod pe sumă la fiecare adunare | T5, T7, F1 |
| 33-43 | `countDonors` — numărătoarea cu prag strict `> l` | T3, F1 |
| 46, 50, 59 | `rectCalls` — contorul de apeluri măsurat | T6, F2-F4 |
| 58-82 | `rect` — descompunerea în cadrane | T4 |
| 63-67 | swap: `a` devine dimensiunea mare | T1, T4, F2, F4 |
| 68 | `p = Long.highestOneBit(a)` — punctul de tăiere | T4, F2 |
| 69-72 | ramura închisă `p == a`: b × rowSum | T2, F2, F3 |
| 74 | `b0 = min(b, p)` — limita de valabilitate a permutării | T2 |
| 75-79 | cadranele q1/q2/q3/q4 și transportul offset-ului | T4, T6, F4 |
| 88-101 | `rowSum` — cele trei ramuri ale pragului | T3, F3, F4 |
| 89 | `threshold = l − off` — singurul loc unde off intră în valoare | T3, F4 |
| 90-92 | ramura „totul înghițit" (threshold ≥ p−1) | T3, F3 |
| 93-97 | ramura „totul donează": triunghi + platou | T3, F4 |
| 104-108 | `triangularMod` — înjumătățirea parului ÎNAINTE de mod | T5, F3, F5 |
| 110-113 | `mulMod` — produs exact sub 2^64, citit unsigned | T5 |
| 116-126 | `overflowDemo` — cele două căi greșite și cea corectă | F5 |
| 128-167 | `main` — runner-ul cu toate numerele citate | F1-F6 |
| 151, 155-158 | fuzz: seed 42, rangele m, n, l, t | F6 |

# Necunoscute și inferențe

Fapte verificate în acest repo: toate numerele citate (5, 30/40, 248, 808451, 5456283, 9 și 30 de apeluri, 378 / −291171626 / 500000395, 500/0) provin din rularea runner-ului din 2026-08-22, salvată în scratchpad (`lab-output.txt`). Rămân în afara dovezii din cod:

1. **Limitele exacte ale lui m, n în kata** — enunțul fixează doar t ≤ 2^32−1 (și 2^26−1 în JS); „dimensiuni până spre 2^64" e inferență din folclorul testelor kata-ului. **Necunoscut din cod.**
2. **Valorile-țintă 808451 și 5456283** — preluate din testele de exemplu publicate ale kata-ului (sursă externă); repo-ul doar le REPRODUCE, nu le deduce independent.
3. **Marginea superioară exactă a numărului de apeluri `rect`** — afirmația „lanț subțire, ordinea numărului de biți" (T6) e inferență susținută de măsurători (9 la 10 biți, 30 la 36 de biți), nu o demonstrație formală.
4. **Intenția etichetei „Dynamic Programming"** a autorului kata-ului (T4-E2) — inferență; mecanismul soluției de aici e divide-et-impera, dar de ce autorul a ales tag-ul nu se poate stabili din nicio sursă din repo.
5. **Comportamentul soluției pe m, n ≥ 2^63** — laboratorul folosește `long` cu semn și a fost exersat până la ~2^36; extinderea la întreg domeniul unsigned ar cere `divideUnsigned`/`compareUnsigned` suplimentare, nescrise aici.

*Acest document este un manual GENERAT COMPLET — documentarea celor 13 puncte nu echivalează cu parcurgerea sau învățarea lor; criteriile de trecere din planul de învățare (`2026-08-22-become-immortal.md`) rămân nebifate până la sesiuni explica interactive.*
