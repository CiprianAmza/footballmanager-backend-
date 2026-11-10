# Bermuda Rectangle — explicație triplă

**Problema:** „D. Bermuda Rectangle" — Codeforces 2257D, Round 1117 (Div. 2), <https://codeforces.com/problemset/problem/2257/D> · tag-uri oficiale: binary search, implementation, math, number theory, two pointers · limite: 2 s / 256 MB
**Repo:** footballmanager-backend · branch `master` · ultimul commit la momentul generării: `8488435` · **Data:** 2026-08-22

**Cerința problemei:** dreptunghiul Bermuda are laturi întregi, arie S și colțul stânga-jos în (0,0) — dar laturile exacte sunt necunoscute: orice pereche a × (S/a) cu a divizor al lui S e o poziție posibilă. Pentru fiecare query (x, y) numeri celulele dreptunghiului x × y (tot cu colțul în (0,0)) care pot sta în *măcar o* poziție posibilă a Bermudei. Limite: 1 ≤ S ≤ 10^14, Σq ≤ 3·10^5, Σ√S ≤ 10^7, 1 ≤ x, y ≤ S.

**Cod-ancoră:** repo-ul nu conținea nicio soluție pentru 2257D, așa că ancorele acestui manual trăiesc în `src/test/java/com/footballmanagergamesimulator/algorithms/numbertheory/BermudaRectangle.java`, scris și verificat în această sesiune: compilat cu `javac --release 17`, cele 8 răspunsuri din exemplul enunțului reproduse exact, fuzz exhaustiv fast == oracol pe S=1..60 cu toate perechile (x,y) (73.810 verificări) plus 10.000 de query-uri aleatoare pe S=61..400, și o măsurătoare de scară pe S=96.376.119.840.000 (14.400 de divizori, build 7 ms, 300.000 de query-uri în 17 ms). Orice valoare introdusă mai jos prin „măsurat:" provine din aceste rulări (runner-ul `main` din fișier + harness-ul `Trace.java` din scratchpad-ul sesiunii, seed fix 2257). Ancorele au forma `BermudaRectangle.java:NN`.

**Țintă:** să poți calcula de mână răspunsul unui query (S, x, y) prin scara divizorilor, să prezici care coloane contribuie y și care S/d, și de ce brute force pe celule moare la S=10^14.

**Statut:** acest document este *complet generat*, nu materie învățată — nu bifează niciun criteriu de trecere din planul de învățare.

---

## Harta confirmată

### Tehnologii (doar cele de pe fir)
- T1. Divizorii lui S prin trial division (împărțiri de probă până la √S) — fiecare divizor a dă dreptunghiul-candidat a × S/a
- T2. Scara divizorilor (staircase, uniunea dreptunghiurilor) — coloana i are înălțimea S / (cel mai mic divizor ≥ i)
- T3. Sume parțiale pe trepte (prefix sums, arii cumulate) — aria primelor j trepte într-o singură citire
- T4. Căutare binară pe divizorii sortați (lower/upper bound) — găsește treapta lui x și ultimul divizor ≤ prag
- T5. Împărțirea întreagă S/y ca prag (floor, rotunjire în jos) — separă coloanele cu înălțime ≥ y de restul
- T6. Complexitate O(√S + q·log d(S)) vs O(x·y) per query — de ce limitele forțează scara
- T7. Oracol brute force (dublu for pe celule + test de acoperire pe divizori) — proba de corectitudine

### Fire de execuție (ordinea învățării)
- F1. Dai S=6, query (4,5) la oracol → testează 20 de celule contra divizorilor {1,2,3,6} → 11
- F2. Același query la soluția rapidă → scara + min(înălțime, y) pe coloane → același 11, fără celule
- F3. Dai query (6,6) care acoperă toată scara lui S=6 → suma ariilor treptelor → 14
- F4. Dai query (1,1) sub prima treaptă → o singură celulă, mereu acoperită de 1×S → 1
- F5. Dai S=8 (divizori {1,2,4,8}), query (5,6) → treaptă tăiată de x + prag S/y → 15
- F6. Fuzz: sute de perechi (S, x, y) mici aleatoare → fast == oracol pe fiecare

### Perechi de separat (risc de amestec)
- P1. Divizorul a (lățimea) vs S/a (înălțimea) — aceeași pereche de numere, două roluri
- P2. Celula (pătratul unitate, 1-indexată) vs punctul/colțul de coordonate — sursa off-by-one
- P3. Dreptunghiul query (x,y) vs dreptunghiul Bermuda (a, S/a) — ambele în (0,0), doar unul necunoscut
- P4. Binary search pe x (câte coloane numeri) vs binary search pe pragul S/y — două căutări, ținte diferite
- P5. floor(S/y) (pragul aritmetic) vs cel mai mare divizor ≤ S/y (treapta reală) — pragul cade între divizori

### Legenda
- **Tn** = tehnologie de pe fir; **Fn** = fir de execuție declanșator → efect; **Pn** = pereche cu risc de amestec.
- Per punct de lucru: **E1** = traseul mecanic (declanșator → actor exact → apel/date → efect, cu ancore fișier:linie); **E2** = modelul mental (motivul, invariantul, vocabularul); **E3** = scenariul predictiv (caz concret cu valori + o variație de eșec sau limită); **Q1** = întrebare orientativă (simulată, nu pusă de utilizator); **EQ** = răspunsul la Q1, cu dovezile lui separate.
- Marcaje epistemice: „măsurat:" = ieșire reală de program din această sesiune; „verificat în cod (:NN)" = citit în sursă; „inferență:" = neconfirmat din cod/rulare; „fapt matematic clasic" = demonstrat în literatură, nu în acest repo.

---

# Partea I — Tehnologiile

## T1. Trial division — divizorii lui S

**E1 — Traseul mecanic.** Declanșatorul e construcția unui `Solver` pentru un test case: constructorul (BermudaRectangle.java:38-40) apelează `divisorsOf(s)` (:103). Actorul e bucla `for (long a = 1; a * a <= s; a++)` (:107): pentru fiecare a care trece de testul `s % a != 0` (:108), scrie perechea în două tampoane — `small[count] = a` și `large[count] = s / a` (:115-116). La final, gardul `perfectSquare` (:119) verifică dacă ultimul a coincide cu perechea lui (a = √s), iar copierea (:120-124) lipește `small` crescător cu `large` inversat într-un singur vector sortat. Film cu S=36: bucla merge până la a=6, găsește micii {1,2,3,4,6} și marii {36,18,12,9,6}; 6 apare în ambele tampoane, gardul îl deduplică. Măsurat: `divisorsOf(36)` = [1, 2, 3, 4, 6, 9, 12, 18, 36] — 9 divizori, număr impar, exact pentru că 36 e pătrat perfect.

**E2 — Modelul mental.** Motivul: nu ai nevoie să încerci toate lățimile 1..S — orice divizor a > √S e deja „celălalt capăt" al unui divizor mic (a · (S/a) = S, deci unul din ei e ≤ √S). De aceea bucla se oprește la `a * a <= s` și totuși nu pierde nimic. Vocabular: *divizor* = lățime posibilă a Bermudei; *perechea* (a, S/a) = cele două dreptunghiuri-candidat pe care le naște un singur hit al buclei (deocamdată doar ca numere — rolurile lățime/înălțime le fixează T2). Invariantul de ieșire: vectorul e strict crescător și conține TOȚI divizorii, o singură dată — pe el se sprijină ambele căutări binare din T4. *Fiecare împărțire exactă sub √S plătește doi divizori; pătratul perfect plătește unul singur.*

**E3 — Scenariul predictiv.** S=96.376.119.840.000 (≈ 9,6·10^13, sub limita 10^14): bucla rulează √S ≈ 9,8 milioane de iterații și găsește, măsurat: 14.400 de divizori în 7 ms. Predicția de cost: timpul e dictat de cele ~10^7 împărțiri de probă, nu de cei 14.400 de hit-uri — raportul hit/miss e ~1 la 682. Variația de limită: dacă limitele ar fi permis 10^4 test case-uri fiecare cu S=10^14, doar enumerarea divizorilor ar fi costat 10^4 · 10^7 = 10^11 împărțiri — mort; constrângerea din enunț Σ√S ≤ 10^7 plafonează exact suma acestor bucle pe tot inputul (vezi T6).

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „Șterg gardul `perfectSquare` de la :119 și lipesc mereu ambele tampoane întregi. Pentru ce fel de S se strică rezultatul, care e primul loc din Solver care o ia razna și ce răspuns greșit ar produce?"

**EQ — Răspuns.** Se strică exact pentru S pătrat perfect: √S ar apărea de două ori în vector (o dată din `small`, o dată din `large`), lipite adiacent — pentru S=36 vectorul ar fi [1,2,3,4,6,6,9,...]. Primul loc care o ia razna e constructorul Solver-ului (:44-49): la al doilea 6, termenul `(edge - previousEdge) * (s / edge)` = (6−6)·6 = 0, deci `areaPrefix` capătă o intrare duplicată cu aceeași valoare cumulată — măsurat pe varianta corectă, prefixul lui 36 e [36, 54, 66, 75, 87, ...] și 87 ar apărea de două ori. Ariile rămân, din noroc, corecte (treapta duplicată are lățime 0), dar `firstDivisorIndexAtLeast(6)` (:87-99) poate întoarce oricare dintre cei doi indici — rezultatul `staircaseCells` rămâne identic pentru că ambii indici au același `areaPrefix` și același `s/edge`. Concluzia verificabilă: bug-ul e *latent*, nu activ — răspunsurile ar rămâne corecte, dar invariantul „strict crescător" pe care îl declară T1/E2 ar fi fals, iar orice cod viitor care se bazează pe unicitate (de ex. numărare de divizori) ar moșteni eroarea. Inferență marcată: analiza „rămâne corect din noroc" e dedusă din cod, nu fuzz-uită pe varianta stricată.

## T2. Scara divizorilor — uniunea dreptunghiurilor (P1)

**E1 — Traseul mecanic.** După ce T1 predă vectorul sortat, constructorul Solver (:42-49) ridică scara: pentru fiecare divizor `edge` (marginea dreaptă a treptei k), actorul-buclă adaugă `(edge - previousEdge) * (s / edge)` (:46) — lățimea treptei ori înălțimea ei — și scrie totalul în `areaPrefix[k]` (:47). Film cu S=6: divizorii [1,2,3,6] produc treptele: coloana 1 cu înălțimea 6/1=6, coloana 2 cu 6/2=3, coloana 3 cu 6/3=2, coloanele 4..6 cu 6/6=1. Măsurat: `areaPrefix` = [6, 9, 11, 14] — 6 celule sub prima treaptă, +3, +2, +3·1. Cine folosește scara: `staircaseCells(x)` (:64-68) o citește ca să afle câte celule are uniunea în coloanele 1..x.

**E2 — Modelul mental (P1).** Întâi cele două vocabulare, separat. *Lățimea* a: numărul de coloane pe care le acoperă un candidat — pe axa x. *Înălțimea* S/a: numărul de rânduri — pe axa y. Aceeași pereche de numere (2, 3) e candidat „2 lat și 3 înalt" SAU „3 lat și 2 înalt" — două dreptunghiuri diferite; vectorul din T1 le conține pe amândouă pentru că și 2 și 3 sunt divizori. Abia acum maparea: uniunea tuturor candidaților e o *scară* care coboară — coloana i primește înălțimea celui mai generos candidat care o acoperă, adică S / (cel mai mic divizor ≥ i); divizor mai mic înseamnă înălțime mai mare, deci candidatul optim pentru coloana i e cel mai îngust care încă ajunge la ea. Invariantul: înălțimea e constantă pe fiecare interval (divizor anterior, divizor] și strict scade de la o treaptă la alta. *Scara nu e un dreptunghi — e maximul pe verticală al tuturor dreptunghiurilor deodată.*

**E3 — Scenariul predictiv.** S=8, divizori [1,2,4,8]: prezici înălțimile 8, 4, 2, 1 pe intervalele de coloane {1}, {2}, {3..4}, {5..8} — lățimi 1, 1, 2, 4. Aria totală: 8 + 4 + 2·2 + 4·1 = 20. Măsurat: `areaPrefix` = [8, 12, 16, 20]. Variația de eșec: dacă ai citi înălțimea coloanei i cu *cel mai mare* divizor ≥ i în loc de cel mai mic, coloana 3 la S=8 ar primi 8/4=2 corect din întâmplare, dar coloana 2 ar primi 8/8=1 în loc de 8/2=4 — trei celule pierdute; alegerea „cel mai mic divizor ≥ i" nu e o convenție, e maximizarea înălțimii.

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „De ce e suficient să iau *un singur* candidat (cel mai mic divizor ≥ i) pentru coloana i — nu se pot combina doi candidați ca să acopere împreună celule pe care niciunul nu le acoperă singur?"

**EQ — Răspuns.** Nu — pentru o *coloană fixă* acoperirea candidaților e imbricată, nu complementară. Filmul pe S=6, coloana 2: candidații care ating coloana 2 sunt lățimile 2, 3, 6, cu înălțimile 3, 2, 1 — mulțimile de celule acoperite în coloana 2 sunt {rândurile 1..3} ⊇ {1..2} ⊇ {1}. Orice candidat acoperă un *prefix* de rânduri (de la rândul 1 în sus, pentru că toate dreptunghiurile stau cu colțul în (0,0)), iar reuniunea unor prefixe e prefixul cel mai lung — adică exact candidatul cu înălțimea maximă, cel din T2/E2. Verificat în cod: `isCovered` (:147-153) întoarce true la primul divizor cu `width >= i && s / width >= j` — nu are nevoie de combinații; iar fuzz-ul exhaustiv (măsurat: 73.810 de verificări fast == oracol) confirmă empiric că scara cu un singur candidat per coloană numără identic cu oracolul care întreabă toți candidații. Aici e și separarea P3: cele două dreptunghiuri din enunț împart colțul (0,0) — de asta acoperirile sunt prefixe pe ambele axe și scara există.

## T3. Sume parțiale pe trepte — aria fără buclă

**E1 — Traseul mecanic.** `areaPrefix[k]` se umple o singură dată, în constructor (:44-49), și după aceea nimeni nu mai adună trepte în buclă: `staircaseCells(x)` (:64-68) răspunde „câte celule are uniunea în coloanele 1..x" din trei citiri — găsește treapta lui x cu `firstDivisorIndexAtLeast(x)` (:65), ia totalul de dinaintea treptei din `areaPrefix[step-1]` (:67) și adaugă bucata tăiată `(x - previousEdge) * (s / divisors[step])` (:68). Film cu S=8, x=5: treapta lui 5 e divizorul 8 (indexul 3), înainte de ea `areaPrefix[2]` = 16, bucata = (5−4)·(8/8) = 1. Măsurat: `staircaseCells(5)` = 17.

**E2 — Modelul mental.** Motivul: un query nu are voie să coste cât numărul de trepte — la S=96.376.119.840.000 sunt 14.400 de trepte și 3·10^5 query-uri; 14.400 · 3·10^5 = 4,3·10^9 adunări ar sparge limita de 2 s. Suma parțială mută costul: plătești o dată la construcție (o trecere peste trepte) și apoi fiecare query citește diferențe. Vocabular: *prefix* = totalul cumulat până la o margine de treaptă; *bucata tăiată* = partea din treapta curentă aflată la stânga lui x. Invariantul: `areaPrefix[k] − areaPrefix[k−1]` e exact aria treptei k — diferența a două citiri reconstituie orice interval de trepte fără buclă. *Construiești o dată, citești diferențe pentru totdeauna.*

**E3 — Scenariul predictiv.** Prezici `staircaseCells(4)` la S=6: treapta lui 4 e divizorul 6 (după marginea 3), prefix înainte = 11, bucata = (4−3)·(6/6) = 1 → 12. Măsurat: 12. Variația de limită — overflow: totalul uniunii intră în `long`? Măsurat la S=96.376.119.840.000: uniunea are 3.111.962.687.230.508 celule ≈ 3,1·10^15, față de plafonul long de 9,2·10^18. Inferență cu rol: suma ariilor treptelor e Σ (lățime·S/d) ≤ S · Σ 1/d peste divizori, iar Σ 1/d rămâne mică (aici ≈ 32·S ar fi plafonul teoretic dur; empiric 3,1·10^15 ≈ 32·S); marja până la overflow e de ~3.000 de ori — long ajunge fără BigInteger.

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „`staircaseCells` primește garanția 1 ≤ x ≤ s. Ce s-ar întâmpla mecanic dacă cineva ar apela `staircaseCells(0)` — și cine anume face ca apelul ăsta să nu se producă niciodată în `query`?"

**EQ — Răspuns.** Mecanic: `firstDivisorIndexAtLeast(0)` (:87-99) ar întoarce indexul 0 (orice divizor e ≥ 0, deci căutarea coboară pe primul), `previousEdge` = 0, `before` = 0, iar rezultatul ar fi (0−0)·(s/1) = 0 — întâmplător corect. Cine previne apelul: `query` (:53-59) cheamă `staircaseCells` doar pe ramura `x > tallColumns` (:57), cu argumentele `x` și `tallColumns`; `x ≥ 1` vine din enunț, iar `tallColumns` e un divizor real (:83), deci ≥ 1 — niciun 0 nu poate curge înăuntru. Separat, garanția `y ≤ s` face `s / y ≥ 1`, deci `largestDivisorAtMost` are mereu candidatul 1 (comentariul de la :71, verificat în cod). Măsurat indirect: fuzz-ul exhaustiv acoperă toate perechile 1 ≤ x, y ≤ s pentru S=1..60 — 73.810 verificări fără nicio abatere; cazul x=0 rămâne în afara domeniului, apărat de apelant, nu de metodă.

## T4. Căutarea binară pe divizori — două ținte diferite (P4)

**E1 — Traseul mecanic.** Doi actori gemeni, ambii pe vectorul sortat din T1. `largestDivisorAtMost(bound)` (:72-84): pornește cu lo=0, hi=ultimul index; la fiecare pas ia `mid = (lo + hi + 1) >>> 1` (:76) — rotunjit în SUS — și dacă `divisors[mid] <= bound` mută lo pe mid, altfel taie hi sub mid; întoarce o *valoare* de divizor (:83). `firstDivisorIndexAtLeast(x)` (:87-99): `mid = (lo + hi) >>> 1` (:91) — rotunjit în JOS — și dacă `divisors[mid] >= x` trage hi pe mid; întoarce un *index* (:98). Film cu S=6, `largestDivisorAtMost(2)`: lo=0, hi=3 → mid=2, divisors[2]=3 > 2 → hi=1 → mid=1, divisors[1]=2 ≤ 2 → lo=1 → gata, întoarce 2. Măsurat prin trasee: la S=6, y=3, pragul 6/3=2 dă divizorul 2.

**E2 — Modelul mental (P4).** Cele două căutări răspund la întrebări diferite și nu sunt interschimbabile. Prima: „până unde țin coloanele *înalte*?" — caută pe axa înălțimilor, cu pragul S/y, și vine din T5; ea vrea ultimul divizor care NU depășește pragul. A doua: „în ce treaptă cade coloana x?" — caută pe axa lățimilor, cu chiar x-ul query-ului; ea vrea prima margine de treaptă care ajunge la x. Mnemonicul de direcție: *atMost caută ultimul-bun mergând în sus (mid rotunjit în sus), atLeast caută primul-bun mergând în jos (mid rotunjit în jos).* Invariantul comun: ambele păstrează în [lo, hi] cel puțin un candidat valid pe toată durata — prima pentru că divizorul 1 e mereu ≤ prag (T5), a doua pentru că ultimul divizor e S ≥ x.

**E3 — Scenariul predictiv.** La S=96.376.119.840.000 vectorul are 14.400 de intrări; prezici adâncimea căutării: ⌈log₂ 14.400⌉ = 14 pași — măsurat: 14 (calculat din lungimea vectorului în Trace). Deci un query întreg costă ~2·14 comparații plus câteva citiri de vector — de aici cele 300.000 de query-uri în 17 ms, măsurat. Variația de eșec: schimbă în `largestDivisorAtMost` rotunjirea la `(lo + hi) >>> 1`. Pe cazul lo=1, hi=2 cu ambele valori ≤ bound: mid=1, `divisors[1] <= bound` → lo=1 — starea (1,2) se repetă identic la nesfârșit; rotunjirea în sus există exact ca lo=mid să avanseze mereu. Inferență verificabilă pe hârtie, nemăsurată (bucla infinită nu se rulează).

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „De ce `largestDivisorAtMost` întoarce valoarea divizorului (:83), iar `firstDivisorIndexAtLeast` întoarce indexul (:98)? Ce consumator dictează fiecare formă?"

**EQ — Răspuns.** Consumatorii sunt diferiți și fiecare dictează forma. `query` (:54) folosește rezultatul primei căutări ca *număr de coloane*: `tallColumns` intră direct în `Math.min(x, tallColumns)` (:55) și în înmulțirea `fullColumns * y` (:56) — are nevoie de valoarea divizorului (care e chiar marginea în coloane), indexul nu i-ar folosi la nimic aritmetic. `staircaseCells` (:65-68) folosește rezultatul celeilalte căutări ca *adresă de citire*: `step` indexează `divisors[step - 1]`, `areaPrefix[step - 1]` și `divisors[step]` — are nevoie de poziție, nu de valoare. Verificat în cod la liniile citate. Consecința de reținut, cu capcana ei: dacă le-ai uniformiza (ambele pe index, de exemplu), `query` ar trebui să facă el `divisors[idx]`, iar `staircaseCells(tallColumns)` de la :58 ar primi în continuare o *valoare* — cele două „lumi" (valoare-în-coloane vs index-în-vector) tot ar coexista, doar granița s-ar muta. Predicție vecină, neînchisă aici: la `staircaseCells(tallColumns)`, x-ul primit e chiar un divizor — `firstDivisorIndexAtLeast` îl găsește exact, iar bucata tăiată devine lățimea întreagă a treptei lui.

## T5. floor(S/y) — pragul care desparte coloanele (P5)

**E1 — Traseul mecanic.** Primul lucru pe care îl face `query(x, y)` (:53-54): împarte întreg `s / y` — în Java, împărțirea de long-uri aruncă restul (floor pentru operanzi pozitivi) — și dă rezultatul drept prag lui `largestDivisorAtMost`. Film cu S=6, y=5: 6/5 = 1 (nu 1,2); divizorul întors: 1 → doar coloana 1 e „înaltă" (are ≥ 5 rânduri). Cu y=3: 6/3 = 2 → divizorul 2 → coloanele 1..2 sunt înalte. Măsurat: ambele trasee exact așa, plus cazul S=36, y=5: floor(36/5) = 7, iar divizorul întors e 6 — pragul aritmetic 7 NU e divizor, treapta reală se oprește la 6.

**E2 — Modelul mental (P5).** Cele două vocabulare, separat. *Pragul aritmetic* floor(S/y): cel mai mare întreg d cu d·y ≤ S — un număr calculat, fără legătură cu divizibilitatea. *Treapta reală*: cel mai mare divizor ≤ acel prag — o margine care chiar există în scară. Maparea, și de ce e corectă fără pierderi: coloana i are înălțimea ≥ y ⇔ cel mai mic divizor a ≥ i satisface S/a ≥ y ⇔ a ≤ S/y ⇔ a ≤ floor(S/y) (a e întreg — aici floor nu taie nimic, doar formalizează). Deci mulțimea coloanelor înalte se termină exact la un divizor, chiar dacă pragul aritmetic cade între divizori: între treapta 6 și treapta 9 ale lui S=36 nu există coloane cu înălțimi intermediare — înălțimea sare direct de la 36/6=6 la 36/9=4. *Floor-ul e unealta de calcul; divizorul e granița fizică — între ele nu locuiește nimeni.*

**E3 — Scenariul predictiv.** S=36, y=5: prezici câte coloane primesc y întreg — pragul 36/5=7, treapta 6, deci coloanele 1..6, fiecare cu 5 celule numărate = 30, iar coloana 7 (înălțimea 36/9=4 < 5) intră deja pe coada cu înălțimi reale. Verificare prin substituție: coloana 6 are înălțimea 6 ≥ 5 [OK], coloana 7 are 4 < 5 [OK]. Variația de eșec (leagă P5 de F2): dacă ai folosi pragul aritmetic direct ca număr de coloane înalte — `fullColumns = min(x, 7)` la S=36 — ai numi „înalte" și coloana 7, adunând 5 celule unde există doar 4; pe S=5, query (2,2), varianta stricată dă 4 în loc de 3, contra oracolului (măsurat: răspunsul corect e 3, cu pragul 5/2=2 dar treapta 1).

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „y poate fi până la S. La y=S pragul e S/S=1, dar la y=S−1 (pentru S≥3) pragul e tot 1 — floor(S/(S−1))=1. Câte valori distincte poate lua de fapt pragul floor(S/y) când y parcurge 1..S, și de ce contează asta pentru alte soluții ale aceleiași probleme?"

**EQ — Răspuns.** Aproximativ 2·√S valori distincte, nu S — fapt matematic clasic (lema împărțirilor întregi): pentru y ≤ √S fiecare y dă propriul prag (până la √S valori), iar pentru y > √S pragul floor(S/y) e < √S, deci ia și el sub √S valori. Film mic, S=8, y=1..8: pragurile sunt 8, 4, 2, 2, 1, 1, 1, 1 — cinci valori distincte {8,4,2,1} + capătul, față de 8 posibile. Pentru soluția din acest manual proprietatea e doar decor — binary search-ul nu depinde de ea. Contează pentru varianta cu *two pointers* din tag-urile oficiale (inferență din tag-uri; tutorialul nu a fost citit — vezi Necunoscute): dacă sortezi query-urile după y, pragul floor(S/y) se mișcă monoton și un pointer pe vectorul divizorilor îl poate urmări fără nicio căutare binară, amortizat O(1) per query. Ambele drumuri produc aceleași răspunsuri; manualul rămâne pe binary search, care nu cere sortarea query-urilor.

## T6. Complexitatea — de ce limitele forțează scara

**E1 — Traseul mecanic.** Costurile, pe actori, cap-coadă pentru un test case: (1) `divisorsOf` — √S împărțiri de probă (:107); (2) constructorul scării — o trecere peste d(S) trepte (:44-49); (3) fiecare query — două căutări binare a câte ⌈log₂ d(S)⌉ pași (:54, :65) plus aritmetică de câteva operații (:55-58). Măsurat, pe S=96.376.119.840.000: pasul (1)+(2) = 7 ms pentru 9,8·10^6 probe și 14.400 de trepte; pasul (3) = 17 ms pentru 300.000 de query-uri — 57 de nanosecunde per query, ~28 de comparații fiecare. Enunțul plafonează exact acești doi termeni: Σ√S ≤ 10^7 acoperă pasul (1) pe toate testele, Σq ≤ 3·10^5 acoperă pasul (3).

**E2 — Modelul mental.** Motivul pentru care limitele se citesc ca instrucțiuni de proiectare: un enunț care plafonează *suma* lui √S pe teste îți spune că autorul se așteaptă la un cost O(√S) per test — altfel constrângerea n-ar avea sens; iar Σq separat de S îți spune că un query trebuie să coste ceva independent de S (log, nu √S). Vocabular: *d(S)* = numărul de divizori ai lui S — mărimea scării; el crește mult mai încet decât S (14.400 la S≈10^14, măsurat). Invariantul de proiectare: nimic din drumul unui query nu atinge celule sau coloane una câte una — doar margini de trepte. *Limitele enunțului sunt bugetul; algoritmul e factura care trebuie să încapă în el.*

**E3 — Scenariul predictiv.** Bugetul advers: brute force pe celule pentru UN query cu x = y = 10^7 la S = 10^14 — 10^14 celule vizitate (oracolul :136-142), fiecare cu până la 14.400 de teste de divizor; la ~10^9 operații/s, peste 10^5 secunde — de 50.000 de ori peste limita de 2 s, pentru un singur query din potențial 3·10^5. Aceeași cerere prin scară: 7 ms de construcție amortizați pe tot testul + 57 ns — predicția: raportul dintre cele două drumuri depășește 10^9. Inferență etichetată: extrapolarea la 10^9 operații/s e ipoteză de hardware, neprofilată; termenii măsurați (7 ms, 17 ms/300k) sunt însă suficienți singuri ca dovadă că drumul rapid încape în 2 s cu marjă de două ordine de mărime.

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „Enunțul spune t ≤ 10^4 test case-uri. De ce a mai fost nevoie de constrângerea Σ√S ≤ 10^7 — nu era destul S ≤ 10^14 per test?"

**EQ — Răspuns.** Nu — fără plafonul pe sumă, cele două limite se compun advers: 10^4 teste, fiecare cu S = 10^14, înseamnă 10^4 · √(10^14) = 10^4 · 10^7 = 10^11 împărțiri de probă doar în `divisorsOf` — la 7 ms per 10^7 probe (măsurat), ~70 de secunde, de 35 de ori peste limită, înainte de orice query. Σ√S ≤ 10^7 spune de fapt: „toate trial division-urile tale, adunate, costă cât UN singur S maxim" — poți avea un test cu S=10^14 SAU zece mii de teste mici, nu ambele. E aceeași schemă de plafonare ca Σq ≤ 3·10^5 pentru query-uri: limita per-test dă forma datelor, limita pe sumă dă bugetul real de timp. Verificat în enunț (citat în antet); costul per probă verificat prin măsurătoarea de 7 ms. Predicție vecină: un enunț care ar fi plafonat Σ d(S) în loc de Σ√S ar fi lăsat construcția scării ieftină, dar n-ar fi acoperit costul găsirii divizorilor — semn că autorul presupune trial division, nu factorizare rapidă.

## T7. Oracolul — brute force pe celule (P2)

**E1 — Traseul mecanic.** `oracle(s, x, y)` (:133-144): își ia singur divizorii (:134), apoi dublul for parcurge fiecare celulă (i, j) din [1..x]×[1..y] (:136-137) și întreabă `isCovered` (:138). `isCovered` (:147-153) scanează divizorii ca lățimi: primul `width` cu `width >= i && s / width >= j` (:149) întoarce true; dacă niciunul nu răspunde, celula rămâne în afara Bermudei. Film cu S=6, query (4,5): 20 de celule vizitate; celula (2,3) trece — lățimea 2 are 2 ≥ 2 și 6/2=3 ≥ 3; celula (2,4) pică — lățimile 2,3,6 au înălțimile 3,2,1, toate < 4, iar lățimea 1 nu ajunge la coloana 2. Măsurat: `oracle(6,4,5)` = 11.

**E2 — Modelul mental (P2).** Cele două vocabulare, separat. *Celula* (i, j): pătratul unitate cu colțurile (i−1, j−1) și (i, j) — obiectul pe care îl numără problema; 1-indexată, celula (1,1) e prima din colț. *Punctul/colțul* (i, j): o pereche de coordonate pe grilă — obiect de graniță, fără arie. Maparea și off-by-one-ul pe care îl previne: celula (i, j) încape într-un dreptunghi a × b ancorat în (0,0) ⇔ colțul ei din dreapta-sus (i, j) satisface i ≤ a și j ≤ b — de aceea testul din cod e `width >= i` (:149), nu `width > i`; cu strict, celula de pe margine ar fi pierdută și `oracle(6,1,6)` ar rata celula (1,6) al cărei colț atinge exact vârful candidatului 1×6. Rolul oracolului în manual: e definiția executabilă a enunțului — atât de literală încât corectitudinea ei se citește direct; scara din T2-T5 e cea care trebuie *demonstrată* contra lui. *Oracolul e enunțul tradus cuvânt cu cuvânt; soluția rapidă e teorema care se verifică pe el.*

**E3 — Scenariul predictiv.** Costul oracolului pe query-ul (4,5) la S=6: 20 de celule × până la 4 lățimi = maximum 80 de teste — instant. Prezici scalarea: la S=400 (fuzz-ul aleator), un query mediu x,y ≈ 200 face ~40.000 de celule × ~15 divizori ≈ 600.000 de teste — încă ieftin; la S=10^14 cu x=y=10^7, 10^14 celule — mort (calculul din T6/E3). Limita practică măsurată: fuzz-ul rulează 73.810 + 10.000 de comparații fast-vs-oracol în câteva secunde; deasupra lui S≈400, oracolul devine frâna întregii verificări — de aceea fuzz-ul se oprește acolo, iar corectitudinea la scară mare rămâne acoperită doar de exemplele enunțului și de structura demonstrației (vezi F6/EQ).

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „`isCovered` scanează lățimile în ordine crescătoare și iese la primul hit (:150). Pentru celula (2,3) la S=6, care e exact lățimea care dă hit — și s-ar schimba rezultatul dacă scanarea ar merge descrescător?"

**EQ — Răspuns.** Hit-ul vine de la lățimea 2: scanarea ia width=1 (1 ≥ 2 fals — nu ajunge la coloana 2), apoi width=2 (2 ≥ 2 și 6/2=3 ≥ 3 — true, ieșire). Descrescător, hit-ul ar veni de la altă lățime? width=6: 6 ≥ 2 dar 6/6=1 < 3 — fals; width=3: 3 ≥ 2 dar 6/3=2 < 3 — fals; width=2 — true, același verdict. Rezultatul boolean nu depinde de ordine — `isCovered` caută existența, nu identitatea martorului; doar *costul* diferă (crescător, primul martor pentru celule joase-late e găsit târziu; descrescător, invers). Verificat în cod (:148-152): bucla nu memorează care width a răspuns, întoarce doar true/false. Nuanța P1 de păstrat: martorul celulei (2,3) e *lățimea* 2 cu înălțimea 3 — candidatul „2 lat, 3 înalt"; candidatul-oglindă „3 lat, 2 înalt" NU o acoperă (înălțimea 2 < rândul 3) — aceeași pereche de numere, verdicte diferite după rol.

---

# Partea II — Firele de execuție

## F1. Oracolul pe exemplul enunțului — S=6, query (4,5) → 11 (P3)

**E1 — Traseul mecanic.** Declanșatorul: primul test case din enunț, S=6, al doilea query (4,5) — cel ilustrat în figura oficială. Actorul: `oracle(6, 4, 5)` (:133). Filmul: `divisorsOf(6)` predă lățimile {1,2,3,6} (:134); dublul for (:136-137) vizitează cele 4·5 = 20 de celule în ordinea (1,1), (1,2), ..., (4,5); pentru fiecare, `isCovered` (:147) scanează lățimile până la primul adăpost. Efectul: contorul `covered` (:135, :139) ajunge la 11. Măsurat: `oracle(6,4,5)` = 11, identic cu răspunsul oficial din exemplu.

**E2 — Modelul mental (P3).** Cele două dreptunghiuri din enunț, separat. *Dreptunghiul query* (x, y) = (4, 5): cunoscut, fix, e „rama roșie" din figura oficială — el delimitează ce celule ai voie să numeri. *Dreptunghiul Bermuda* a × (S/a): necunoscut, cu 4 identități posibile la S=6 — 1×6, 2×3, 3×2, 6×1, „dreptunghiurile albastre" din figură. Maparea: o celulă se numără când stă în ramă ȘI în măcar o identitate albastră — intersecția dintre ramă și *uniunea* albaștrilor. Invariantul care face exemplul reprezentativ: rama taie uniunea pe ambele axe (x=4 < 6 și y=5 < 6), deci apar toate regimurile deodată — coloane pline, coloane tăiate de y, celule respinse. *Roșu întreabă „unde am voie să număr", albastru răspunde „unde există adăpost" — răspunsul e suprapunerea lor.*

**E3 — Scenariul predictiv.** Numărătoarea pe coloane, de prezis înainte de rulare: coloana 1 — adăpost 1×6, înălțime 6, dar rama taie la y=5 → 5 celule; coloana 2 — cel mai mic divizor ≥ 2 e 2, înălțime 3 → 3; coloana 3 — divizorul 3, înălțime 2 → 2; coloana 4 — divizorul 6, înălțime 1 → 1. Total 5+3+2+1 = 11 [OK, măsurat]. Variația de simetrie: query-ul transpus (5,4) — coloanele 1..5 au înălțimile 6,3,2,1,1, tăiate la y=4 → 4+3+2+1+1 = 11, același răspuns; inferență verificată de mână aici (pentru mecanismul general al simetriei, vezi F5/E3).

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „Din cele 20 de celule ale ramei, 9 pică. Care sunt exact cele 9 și care e, pentru fiecare, verdictul-tip al lui `isCovered` — ce condiție din `width >= i && s / width >= j` eșuează?"

**EQ — Răspuns.** Cele 9 respinse, pe coloane (numerotare celulă = (coloană, rând)): coloana 2 — (2,4), (2,5); coloana 3 — (3,3), (3,4), (3,5); coloana 4 — (4,2), (4,3), (4,4), (4,5). Verdictul-tip e mereu al doilea test: pentru orice celulă cu i ≥ 2, lățimile care trec de `width >= i` sunt cele mari (la (3,4): lățimile 3 și 6), dar tocmai ele au înălțimile mici — `s / width` dă 2, respectiv 1, ambele < 4. Primul test (`width >= i`) elimină doar lățimile mici, nu celule: lățimea 1 pică la orice i ≥ 2, dar celula mai are candidați. Structura respingerii: 0+2+3+4 în trepte crescânde — oglinda exactă a scării 5+3+2+1 care acceptă; împreună dau 4·5 = 20. Verificat prin rulare (măsurat: 11 acceptate) + citirea condiției (:149); enumerarea celor 9 e derulare de mână pe înălțimile măsurate [6,3,2,1], nu ieșire directă de program.

## F2. Soluția rapidă pe același query — 11 fără nicio celulă (P4, P5)

**E1 — Traseul mecanic.** Același declanșator, alt drum: `new Solver(6)` construiește divizorii [1,2,3,6] și prefixele [6,9,11,14] (măsurat), apoi `query(4, 5)` (:53). Filmul intern, cu valorile măsurate din traseu: `s / y` = 6/5 = 1 (:54, pragul din T5) → `largestDivisorAtMost(1)` = 1 → `tallColumns` = 1; `fullColumns` = min(4, 1) = 1 (:55); `answer` = 1·5 = 5 (:56); gardul `x > tallColumns` (4 > 1, :57) deschide coada: `staircaseCells(4)` = 12 minus `staircaseCells(1)` = 6 → +6 (:58). Efect: 5 + 6 = 11 — același răspuns ca oracolul, cu zero celule vizitate.

**E2 — Modelul mental.** Formula e o tăietură în două regimuri, pe verticala `tallColumns`: la stânga ei, coloanele au înălțimea ≥ y, deci rama query-ului le retează — fiecare plătește exact y (regimul „plafonul e al ramei"); la dreapta, înălțimile au coborât sub y, rama nu mai atinge nimic — fiecare coloană plătește înălțimea ei reală, iar suma lor e o diferență de prefixe (regimul „plafonul e al scării"). Vocabular: `tallColumns` = ultima coloană din primul regim (un divizor, T5); `fullColumns` = câte din ele încap și în ramă. Invariantul: cele două regimuri nu se suprapun și acoperă împreună coloanele 1..x — de aceea o adunare de două produse-diferențe înlocuiește 20 de vizite. *min(înălțime, y) pe fiecare coloană, dar calculat pe intervale întregi, nu coloană cu coloană.*

**E3 — Scenariul predictiv.** Query-ul (2,3) din același test case: prezici 6/3 = 2 → tall = 2, full = min(2,2) = 2, base = 2·3 = 6, iar gardul 2 > 2 e fals — fără coadă, răspuns 6. Măsurat: exact așa („fara coada" în traseu), și e chiar primul răspuns oficial din exemplu — rama 2×3 încape întreagă în candidatul 2×3. Variația de eșec: șterge `Math.min` de la :55 și lasă `fullColumns = tallColumns`. Query-ul (1,1): tall = 6 (floor(6/1)=6, divizor), full ar deveni 6 → answer = 6·1 = 6 în loc de 1 — numeri coloane din afara ramei. Min-ul nu e cosmetic: e singurul loc unde x-ul ramei taie primul regim.

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „Coada de la :58 adună înălțimile REALE ale coloanelor tallColumns+1..x, fără nicio tăiere la y. Cine garantează că niciuna din aceste înălțimi nu depășește y — și ce s-ar strica dacă ar depăși?"

**EQ — Răspuns.** Garantează chiar definiția lui `tallColumns`, prin lanțul de echivalențe din T5: o coloană i > tallColumns are cel mai mic divizor ≥ i strict peste floor(S/y) — altfel `largestDivisorAtMost` l-ar fi întors pe el —, deci înălțimea ei S/d < y, strict. Filmul pe cazul curent: coloanele 2, 3, 4 au înălțimile 3, 2, 1, toate < 5 — coada adună 3+2+1 = 6 exact cum ar fi făcut min(înălțime, 5) coloană cu coloană. Dacă garanția ar lipsi (de exemplu, cu bug-ul P5 — prag folosit direct fără coborâre la divizor, în partea *cealaltă*: tall prea mare), o coloană cu înălțime ≥ y ar intra pe coadă și ar contribui înălțimea întreagă în loc de y — supranumărare; oglinda exactă a subnumărării din T5/E3. Verificat în cod (:54, :77, :83 — lanțul prag → divizor → valoare) și prin traseul măsurat; echivalența „coada == min pe fiecare coloană" e susținută empiric de fuzz-ul exhaustiv (73.810 de acorduri fast-oracol, măsurat).

## F3. Query-ul care înghite toată scara — S=6, (6,6) → 14

**E1 — Traseul mecanic.** Query-ul (6,6) întinde rama exact peste tot pătratul S×S posibil. Filmul măsurat: floor(6/6) = 1 → tall = 1, full = min(6,1) = 1, base = 1·6 = 6; gardul 6 > 1 deschide coada: `staircaseCells(6)` = 14 − `staircaseCells(1)` = 6 → +8; răspuns 14. Iar 14 e chiar `areaPrefix[3]` — ultima intrare a prefixului (măsurat: [6, 9, 11, 14]): rama nu mai taie nimic, răspunsul e aria întregii uniuni.

**E2 — Modelul mental.** Cazul-limită care dezvăluie ce numără de fapt algoritmul: pentru x = y = S, „intersecția ramei cu uniunea" degenerează în uniunea însăși — cele 14 celule sunt tot ce poate atinge vreodată Bermuda lui S=6, în orice identitate. De ce rama nu taie: pe axa x, ultimul divizor e chiar S = 6 ≥ x; pe axa y, prima coloană are înălțimea S/1 = 6 ≤ y. Invariantul de capăt: răspunsul oricărui query e ≤ areaPrefix[ultimul], iar egalitatea se atinge exact când rama acoperă scara pe ambele axe. Contrastul cu intuiția greșită „aria e S²": uniunea are 14 celule, nu 36 — candidații umplu doar scara, nu pătratul. *Query-ul maxim e auditul uniunii: dacă (S,S) nu dă suma tuturor treptelor, scara e construită greșit.*

**E3 — Scenariul predictiv.** S=8, query (8,8): prezici direct areaPrefix[ultimul] = 20 (măsurat: prefixele [8,12,16,20]). Derulare de control: base = 1·8 = 8, coada = staircaseCells(8) − staircaseCells(1) = 20 − 8 = 12 → 20 [OK]. Variația de margine: (7,8) — rama pierde doar coloana 8, care are înălțimea 1 → prezici 19; pe mecanism: base = 8, coada = staircaseCells(7) − 8, unde staircaseCells(7) = 16 + (7−4)·1 = 19 → 8 + 11 = 19. Inferență derulată de mână pe prefixele măsurate, query-ul (7,8) însuși nerulat — dar orice pereche de felul ăsta e acoperită de clasa verificată exhaustiv la S ≤ 60.

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „La (6,6), base-ul numără coloana 1 cu y = 6 celule. Dar coloana 1 are înălțimea reală tot 6. E o coincidență a lui S=6 sau prima coloană plătește MEREU exact înălțimea ei la query-ul (S,S)?"

**EQ — Răspuns.** Mereu — și nu doar prima coloană, ci tot primul regim, prin egalitate, nu prin tăiere. La y = S, pragul floor(S/S) = 1 face `tallColumns` = 1 (divizorul 1 e singurul ≤ 1), deci primul regim conține DOAR coloana 1, iar plata ei y = S coincide cu înălțimea reală S/1 = S — min(S, S), ambele brațe egale. Filmul general: pentru orice S, `query(S, S)` = 1·S + (areaPrefix[ultimul] − S) = areaPrefix[ultimul] — termenul S se adună și se scade, granița dintre regimuri devine invizibilă. De asta auditul din E2 funcționează: la (S,S) formula se prăbușește algebric în „citește ultima intrare a prefixului", indiferent câte trepte are scara. Verificat pe cele două cazuri măsurate (S=6 → 14, S=8 → 20 prin derulare); egalitatea algebrică e citită direct din :54-58 cu y = x = S substituit — inferență de un rând, fără cod nou.

## F4. Query-ul minim — (1,1) → 1 pentru orice S

**E1 — Traseul mecanic.** Ultimul query al primului test case: S=6, (1,1). Filmul măsurat: floor(6/1) = 6 → `largestDivisorAtMost(6)` = 6 → tall = 6; full = min(1, 6) = 1; base = 1·1 = 1; gardul `x > tallColumns` — 1 > 6 fals (:57) — coada nu se deschide. Răspuns: 1. E drumul cel mai scurt prin `query`: o căutare binară, un min, o înmulțire, un test picat.

**E2 — Modelul mental.** De ce 1 e garantat pentru ORICE S: divizorul 1 există întotdeauna, deci candidatul 1 × S există întotdeauna, iar el conține celula (1,1) — colțul comun al tuturor candidaților. Cardinalitatea pe care o fixează firul: răspunsul unui query nu e niciodată 0 — orice ramă are măcar celula (1,1) acoperită; și e niciodată peste x·y — rama însăși e plafonul (măsurat pe exemple: 1 ≤ răspuns ≤ x·y peste tot). Regimul „fără coadă" în care cade query-ul: y mic face pragul S/y uriaș, `tallColumns` sare dincolo de x și TOT query-ul e primul regim — rama e scundă și lată-nimic, scara o depășește peste tot. *Colțul (1,1) e teritoriul comun al tuturor identităților Bermudei — singura celulă pe care n-o poți pierde.*

**E3 — Scenariul predictiv.** Toată clasa query-urilor cu x = 1: prezici răspunsul y pentru orice y ≤ S — coloana 1 are înălțimea S/1 = S ≥ y, deci rama 1×y e integral acoperită. Simetric, y = 1: răspunsul x — fiecare coloană i ≤ x ≤ S are înălțimea ≥ 1 (divizorul S ≥ i îi dă măcar un rând). Măsurat pe cazul (3,1) la S=8: răspuns 3 (e primul query din F5). Variația de eșec — miniatura P2: dacă testul din `isCovered` ar fi strict (`width > i`), chiar celula (1,1) ar pica la S=1 — singurul divizor, 1, n-ar mai adăposti nimic și răspunsul ar deveni 0, contra garanției; fuzz-ul exhaustiv începe de la S=1 exact ca marginea asta să fie păzită (verificat: S=1 e în bucla de la :177).

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „`largestDivisorAtMost(6)` la S=6 întoarce chiar 6 — ultimul element al vectorului. Căutarea binară atinge marginea de sus a domeniului; există vreun input pentru care ea ar trebui să răspundă «nu există divizor ≤ prag» — și ce ar face codul atunci?"

**EQ — Răspuns.** În domeniul problemei — nu există: pragul e s/y cu y ≤ s, deci ≥ 1, iar divizorul 1 e mereu un candidat valid; „nu există" ar cere prag < 1, adică y > s, interzis de enunț (garanția citată la :71 în comentariu). Ce ar face codul dacă totuși l-ai chema cu bound = 0: filmul pe [1,2,3,6] — lo=0, hi=3, mid=2: 3 ≤ 0 fals → hi=1; mid=1: 2 ≤ 0 fals → hi=0; lo=hi=0 → întoarce `divisors[0]` = 1, TACIT greșit („cel mai mare divizor ≤ 0" nu există, dar primești 1). Consecința în `query`: tall = 1 în loc de „nimic", primul regim ar număra coloana 1 cu y celule inexistente — supranumărare nedetectată. Morala de contract: metoda nu își validează domeniul, apelantul îl garantează — aceeași împărțire a responsabilității ca la `staircaseCells(0)` din T3/EQ, doar că acolo întâmplarea dădea 0 corect, aici ar da greșit. Derulare de mână pe cod (:72-84); cazul y > s nerulabil prin `query`, deci nemăsurat — inferență completă, etichetată.

## F5. Divizori nesimetrici și treaptă tăiată — S=8, (3,1) → 3 și (5,6) → 15 (P5)

**E1 — Traseul mecanic.** Al treilea test case din enunț, cap-coadă: `new Solver(8)` → divizori [1,2,4,8], prefixe [8,12,16,20] (măsurat). Query 1, (3,1): floor(8/1) = 8 → tall = 8, full = min(3,8) = 3, base = 3·1 = 3, gardul 3 > 8 fals — răspuns 3, integral în primul regim. Query 2, (5,6): floor(8/6) = 1 → tall = 1, full = 1, base = 6; coada: `staircaseCells(5)` = 17 − `staircaseCells(1)` = 8 → +9; răspuns 15. În coadă, treapta a patra (coloanele 5..8, înălțimea 1) intră doar cu bucata (5−4)·1 = 1 — x = 5 o taie.

**E2 — Modelul mental.** Ce adaugă S=8 față de S=6: divizorii nu mai vin în perechi distincte simetrice vizual (1·8, 2·4 — fără divizor „din mijloc"), treptele au lățimi inegale (1, 1, 2, 4), iar cele două query-uri cad în regimuri OPUSE — (3,1) trăiește complet în primul regim (y=1 face pragul maxim), (5,6) aproape complet în al doilea (y=6 face pragul minim). Vocabularul de regim din F2, acum cu ambele capete la vedere: y mic → prag mare → totul e base; y mare → prag mic → totul e coadă. Invariantul de tăiere: `staircaseCells(x)` nu cere ca x să fie margine de treaptă — formula bucății (:68) plătește doar porțiunea de treaptă de la stânga lui x. *Același Solver, două query-uri, două lumi — pragul S/y alege lumea, x doar taie în ea.*

**E3 — Scenariul predictiv.** Query nou (6,3) pe același Solver: prezici floor(8/3) = 2 → tall = 2, full = 2, base = 2·3 = 6; coada: staircaseCells(6) = 16 + (6−4)·1 = 18, minus staircaseCells(2) = 12 → +6; total 12. Control pe coloane: înălțimile [8,4,2,2,1,1] tăiate la 3 → 3+3+2+2+1+1 = 12 [OK, derulare de mână pe valori măsurate]. Variația de simetrie, acum ca mecanism: transpusa (6,5) a query-ului (5,6) — floor(8/5) = 1, tall = 1, base = 5, coada = staircaseCells(6) − 8 = 10 → 15, egal cu originalul. Nu e accident: scara e propria transpusă (perechea (a, S/a) există în ambele orientări — T2/E2), deci `query(x,y)` = `query(y,x)` pentru orice ramă. Inferență din structură, spot-verificată aici și în F1/E3; nespusă în enunț, netestată explicit de fuzz (care generează x și y independent, deci o acoperă doar statistic).

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „Între query-urile (3,1) și (5,6) ale aceluiași test case, se schimbă ceva în interiorul Solver-ului? Dacă aș inversa ordinea query-urilor sau aș băga 3·10^5 între ele, s-ar putea schimba vreun răspuns?"

**EQ — Răspuns.** Nu se schimbă nimic și nu se poate schimba: `Solver` are exact trei câmpuri — `s`, `divisors`, `areaPrefix` (:30-36) — toate scrise o singură dată în constructor și doar citite de `query`, `staircaseCells` și cele două căutări; în tot fișierul nu există nicio atribuire după construcție (verificat în cod: metodele :53-99 nu conțin decât variabile locale). Consecințele, în ordinea întrebării: (1) ordinea query-urilor e irelevantă — fiecare e o funcție pură de (x, y); (2) inserarea altor query-uri nu lasă urme; (3) exact asta permite runner-ului să tragă 300.000 de query-uri aleatoare pe același Solver (măsurat: 17 ms) fără resetare. Contrastul care dă sens întrebării: varianta cu two pointers din tag-uri AR introduce stare între query-uri (pointerul avansează pe măsură ce y-urile sortate cresc) — acolo ordinea devine parte din algoritm; soluția cu binary search plătește un logaritm ca să rămână fără memorie între query-uri. Inferența despre two pointers rămâne inferență (tutorialul necitit); restul e verificat în cod la liniile citate.

## F6. Fuzz — proba de corectitudine fast == oracol

**E1 — Traseul mecanic.** Declanșatorul: `main` (:157), rulat manual în sesiune. Filmul în trei acte: (1) cele 3 test case-uri ale enunțului, hardcodate cu răspunsurile lor (:158-162), fiecare verificat cu `throw` la nepotrivire (:167-171) — măsurat: „samples: all 8 expected answers reproduced"; (2) exhaustivul — pentru FIECARE S din 1..60 și FIECARE pereche (x,y) din [1..S]², compară `solver.query` cu `oracle` (:177-190) — măsurat: 73.810 verificări, zero abateri; (3) aleatoriul — 400 de runde cu S în 61..400 și câte 25 de query-uri random (:194-208), seed fix 2257 (:21) — măsurat: 10.000 de verificări, zero abateri. Orice abatere ar opri procesul cu S, x, y și ambele valori în mesaj (:184-187, :203-206).

**E2 — Modelul mental.** De ce fuzz și nu doar exemplele: enunțul dă 8 răspunsuri — 8 puncte; exhaustivul dă TOATE punctele unui univers mic, unde trăiesc deja toate soiurile structurale: S=1 (un divizor), S prim (două), S pătrat perfect (număr impar de divizori — 36 e sub 60), S highly-composite mic (48, 60). Rolurile: oracolul e definiția (T7), fast e teorema, `throw`-ul cu reproducer e martorul — un eșec vine cu inputul care îl rejoacă, nu cu o statistică. Seed-ul fix face actul 3 repetabil: aceleași 10.000 de query-uri la fiecare rulare. Invariantul de încredere: orice optimizare viitoare în Solver se validează cu aceeași comandă, fără gândire nouă. *Exemplele dovedesc că n-ai stricat enunțul; exhaustivul, că n-ai stricat niciun caz mic; seed-ul, că eșecul de mâine e rejucabil azi.*

**E3 — Scenariul predictiv.** Plantezi bug-ul P2 în oracol — `width > i` în loc de `width >= i` la :149 — și prezici prima crăpătură: chiar S=1, x=1, y=1, primul pas al exhaustivului — oracolul stricat dă 0 (lățimea 1 nu mai adăpostește coloana 1), fast dă 1, `throw` cu „mismatch S=1 x=1 y=1: fast=1 oracle=0". Plantezi în schimb bug-ul P5 în fast — pragul aritmetic folosit direct ca `tallColumns` — și prezici că exemplele îl prind înaintea exhaustivului: S=5, query (2,2) din enunț dă 4 în loc de 3 (derularea din T5/E3). Ambele predicții sunt inferențe derulate pe mecanismele deja măsurate — variantele stricate nu au fost rulate; valoarea lor e că știi DINAINTE ce plasă prinde ce pește.

**Q1 — Întrebare orientativă (simulată, nu pusă de utilizator).** „Fuzz-ul se oprește la S=400 și fișierul stă în `src/test/java`, dar `mvn test` nu-l rulează. Ce anume din corectitudinea soluției rămâne NEdovedit de rulările acestei sesiuni — și de ce e acceptat golul?"

**EQ — Răspuns.** Trei goluri, fiecare cu motivul lui. (1) Corectitudinea la S mare: oracolul e inutilizabil peste S ≈ câteva mii (T7/E3), deci la S=96.376.119.840.000 avem doar consistență internă (build + 300k query-uri fără crash, suma uniunii plauzibilă) — nu comparație cu definiția; acoperirea reală vine din structura demonstrației: mecanismele (scară, prag, prefixe) nu au ramuri noi la scară mare, doar numere mai mari, iar singurul risc nou — overflow-ul — e mărginit aritmetic în T3/E3. (2) Perechile (x, y) adversariale la S mare (exact pe margini de treaptă): statistic atinse de query-urile aleatoare, nu construite țintit — gol asumat, inferență. (3) Integrarea în build: `main` fără adnotări JUnit nu intră în `mvn test` — verificat în cod (nicio adnotare în fișier); e o decizie de design a lab-ului (suita reală e lentă — regula sesiunilor anterioare), cu prețul că o regresie viitoare în fișier nu ar fi prinsă automat de CI. Pe scurt: dovada acoperă complet universul mic și mecanismele; scara mare stă pe demonstrație + măsurători de consistență, nu pe oracol.

---

# Glosar

- **areaPrefix** — vectorul sumelor parțiale: intrarea k = câte celule are scara în coloanele 1..divisors[k].
- **Bermuda Rectangle (candidat)** — o identitate posibilă a dreptunghiului ascuns: a × (S/a), cu a divizor al lui S, colț în (0,0).
- **Binary search (căutare binară)** — înjumătățirea intervalului de căutare pe un vector sortat; aici în două arome: ultimul-≤ (largestDivisorAtMost) și primul-≥ (firstDivisorIndexAtLeast).
- **Celulă** — pătratul unitate 1-indexat (i, j), cu colțurile (i−1, j−1)–(i, j); obiectul numărat de problemă.
- **Coada (al doilea regim)** — coloanele de după tallColumns, plătite cu înălțimile lor reale printr-o diferență de prefixe.
- **d(S)** — numărul divizorilor lui S; mărimea scării (măsurat: 14.400 la S ≈ 9,6·10^13).
- **Divizor** — a cu S % a = 0; ca lățime de candidat, marginea dreaptă a unei trepte.
- **floor (împărțire întreagă)** — împărțirea care aruncă restul; `s / y` pe long-uri pozitive în Java.
- **Fuzz** — compararea automată a soluției rapide cu oracolul pe multe inputuri (exhaustiv pe mic, aleator cu seed pe mediu).
- **Oracol** — implementarea-definiție, celulă cu celulă; etalonul de corectitudine, nu de viteză.
- **Prag (tallColumns)** — ultimul divizor ≤ floor(S/y); granița dintre coloanele plătite cu y și cele plătite cu înălțimea reală.
- **Prefix (sumă parțială)** — total cumulat până la o margine; diferența a două prefixe = suma unui interval, fără buclă.
- **Primul regim (base)** — coloanele 1..min(x, tallColumns), fiecare plătită cu exact y celule.
- **Scara divizorilor (staircase)** — uniunea tuturor candidaților: coloana i are înălțimea S / (cel mai mic divizor ≥ i); descrescătoare în trepte.
- **Seed determinist** — sămânța fixă (2257) a generatorului aleator; face rulările fuzz identice între ele.
- **Treaptă** — intervalul de coloane dintre doi divizori consecutivi, cu înălțime constantă.
- **Trial division (împărțiri de probă)** — enumerarea divizorilor testând fiecare a ≤ √S; fiecare hit dă perechea (a, S/a).
- **Two pointers** — tehnica alternativă din tag-urile oficiale: query-uri sortate + un pointer care alunecă monoton pe divizori; neacoperită de manual.

# Index de ancore în cod

Toate în `src/test/java/com/footballmanagergamesimulator/algorithms/numbertheory/BermudaRectangle.java`, fișier nou scris în această sesiune (necomis la momentul generării; ultimul commit al repo-ului: `8488435`).

| Ancoră | Ce demonstrează |
|---|---|
| :21 | seed fix 2257 — fuzz-ul aleator e reproductibil |
| :30-36 | cele trei câmpuri ale Solver-ului — toată starea, scrisă o singură dată (F5/EQ) |
| :38-50 | constructorul: divizori + ridicarea scării în areaPrefix |
| :46 | formula treptei: (edge − previousEdge) · (s / edge) |
| :53-61 | query: prag → min → base → gardul cozii → diferența de prefixe |
| :54 | floor(S/y) — pragul P5, prima linie a fiecărui query |
| :55 | Math.min — singura tăiere a primului regim la rama x (F2/E3) |
| :57 | gardul `x > tallColumns` — comutatorul între „fără coadă" și coadă |
| :64-69 | staircaseCells: treapta lui x + prefix + bucata tăiată |
| :71-84 | largestDivisorAtMost: mid rotunjit în sus, întoarce valoare |
| :86-99 | firstDivisorIndexAtLeast: mid rotunjit în jos, întoarce index |
| :103-126 | divisorsOf: trial division, perechile small/large, gardul perfectSquare |
| :107 | bucla `a * a <= s` — de ce √S e plafonul |
| :119-124 | deduplicarea √S la pătrate perfecte + lipirea sortată |
| :133-144 | oracle: dublul for pe celule — definiția executabilă |
| :147-153 | isCovered: `width >= i && s / width >= j` — testul P2, cu ≥ nu > |
| :157-174 | main actul 1: cele 8 răspunsuri ale enunțului, hardcodate + throw |
| :177-191 | main actul 2: exhaustiv S=1..60, toate perechile (x,y) |
| :194-209 | main actul 3: 400 runde × 25 query-uri aleatoare, S=61..400 |
| :211-227 | măsurătoarea de scară: S≈9,6·10^13, build, uniune, 300k query-uri |

# Necunoscute și inferențe

Etichetate pe parcurs; centralizate aici.

1. **Tutorialul oficial nu a fost citit** — soluția a fost derivată și verificată independent contra oracolului; varianta cu *two pointers* din tag-uri e reconstruită doar ca schiță (T5/EQ, F5/EQ), nu confirmată cu editorialul.
2. **Simetria query(x,y) = query(y,x)** — dedusă din structura scării (perechile de divizori dau o uniune auto-transpusă), spot-verificată pe (4,5)/(5,4) la S=6 și (5,6)/(6,5) la S=8; netestată sistematic.
3. **Numărul de valori distincte ale lui floor(S/y) ≈ 2√S** (T5/EQ) — fapt matematic clasic, ilustrat pe S=8, nedemonstrat în repo.
4. **Extrapolarea de hardware ~10^9 operații/s** (T6/E3) — ipoteză pentru comparația brute-vs-scară; termenii măsurați (7 ms build, 17 ms / 300k query-uri) sunt singurele viteze dovedite.
5. **Corectitudinea la S mare** — neacoperită de oracol (infezabil); stă pe absența ramurilor noi la scară + marginea de overflow din T3/E3 (uniune 3,1·10^15 ≪ 9,2·10^18) + cele 8 răspunsuri oficiale (F6/EQ).
6. **Analiza „bug latent" a gardului perfectSquare** (T1/EQ) și **predicțiile de bug plantat** (F6/E3) — derulate pe mecanisme, variantele stricate nu au fost compilate/rulate.
7. **Soluția de concurs completă** — lab-ul validează algoritmul, nu harness-ul de submisie: citirea rapidă a inputului (3·10^5 linii) și scrierea bufferată, obligatorii pe Codeforces la aceste volume, nu există în lab (inferență de platformă).
8. **Runner-ul nu e test JUnit** — `main` fără adnotări nu intră în `mvn test`; regresiile viitoare în fișier nu sunt prinse de CI (decizie de design, F6/EQ).
