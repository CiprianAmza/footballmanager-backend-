# Busy Beaver — explicație triplă

- **Problema**: Codeforces 2257E „Busy Beaver" (Round 1117 Div. 2), tag-uri: greedy, sortings, data structures, dp, implementation
- **Repo**: footballmanager-backend, branch `master`, commit `8488435` (+ fișier de laborator necomis)
- **Cod-laborator**: `src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java` — oracol BFS + soluția în două faze + runner; toate numerele citate provin din rularea runner-ului (2026-08-22, salvată în scratchpad/busybeaver-lab-output.txt)
- **Data**: 2026-08-22
- **Țintă**: să poți rula de mână cele două faze (capital maxim din segmente profitabile în ordinea pragului, apoi extinderea independentă a fiecărei clădiri) pe un exemplu mic și să prezici pragul de intrare și profitul net al oricărui segment fără simulare etaj cu etaj.

**Problema, într-o propoziție**: cu un capital inițial x, poți construi etajele oricărei clădiri doar în ordine (etajul j cere a-ij morcovi înainte și dă b-ij imediat după), poți întrețese liber clădirile, iar scopul e înălțimea maximă a UNEI clădiri — raportezi înălțimea și cel mai mic index care o atinge.

## Harta confirmată

Tehnologii: T1 profitul net al unui prefix · T2 pragul de intrare al unui segment · T3 segmentarea minimă cu profit ≥ 0 · T4 PriorityQueue pe prag · T5 simularea independentă din Faza 2 · T6 limitele și tipul long · T7 oracolul brute force

Fire: F1 exemplul 2 din enunț · F2 exemplul 1 (fără segmente profitabile) · F3 trei clădiri prin bucla PriorityQueue · F4 tie-break și înălțimea 0 · F5 prefixul parțial e mereu pierdere · F6 fuzz fast vs oracol

Perechi de separat: P1 profit net vs prag de intrare · P2 înălțimea Fazei 1 (persistentă) vs simularea Fazei 2 (aruncată) · P3 ordinea etajelor în clădire (fixă) vs între clădiri (liberă) · P4 a plătit înainte vs b primit după

**Legendă**: T = tehnologie (concept de pe fir) · F = fir de execuție (declanșator → efect) · P = pereche cu risc de amestec · E1 = traseul mecanic · E2 = modelul mental · E3 = scenariul predictiv · Q1 = întrebare orientativă (simulată, nu pusă de utilizator) · EQ = răspunsul la Q1.

---

# Capitolul T — Tehnologii

## T1. Profitul net al unui prefix

### T1-E1 — Traseul mecanic

Declanșatorul e o stare a construcției: pentru fiecare clădire i, primele st[i] etaje sunt construite. Actorul care transformă starea în bani e bucla din oracol, [BusyBeaver.java:155-159](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java): pornește de la `long money = x` (linia 155) și adună `projects[i].b[j] - projects[i].a[j]` pentru fiecare etaj construit (linia 158). Nicio informație despre ORDINEA în care etajele au fost ridicate nu intră în calcul — starea nu o memorează. Concret, pe F1 (x=3), starea [2,1] (două etaje la clădirea 1, unul la clădirea 2) dă: 3 + (5−4) + (5−4) + (4−2) = 7 — exact „bani 7" din trace-ul rulării. Efectul: banii sunt o funcție de MULȚIMEA etajelor construite.

*Regula generală: capitalul curent nu se ține minte — se recalculează oricând din ce s-a construit.*

### T1-E2 — Modelul mental

De ce ordinea nu contează la bani: fiecare etaj construit contribuie exact o dată cu b−a, iar adunarea e comutativă — indiferent dacă ai plătit etajul devreme sau târziu, la final el a lăsat în cont aceeași urmă. Ordinea contează la altceva: la FEZABILITATE — în momentul plății trebuie să ai în cont cel puțin a-ij (perechea P4: plata vine înainte, încasarea imediat după). Vocabular de fixat: **soldul** (banii din cont la un moment dat) se mișcă pas cu pas; **profitul net** al unui prefix (suma b−a) e destinația finală, independentă de traseu. Această separare e temelia întregii soluții: oracolul (T7) își permite o stare fără dimensiunea „bani", iar soluția rapidă își permite să adune profituri de segmente fără să rejoace etajele.

### T1-E3 — Scenariul predictiv

Pe F1 (x=3), aceleași trei etaje construite în două ordini: (clădirea 2 etaj 1, apoi clădirea 1 etajele 1-2) → solduri 3→5→6→7; ordinea inversă (clădirea 1 întâi) e IMPOSIBILĂ: etajul 1 al clădirii 1 costă a=4, iar în cont sunt doar 3 — plata pică. Aceeași mulțime finală de etaje ar da tot 7, dar traseul nu există. Varianta de eșec: cine crede că „ordinea nu contează deloc" ar raporta la x=3 că poate începe cu clădirea 1 — soldul 3−4 = −1 e interzis; ordinea e exact ce separă stările atinse de cele neatinse.

### T1-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: actorul exact. „Oracolul recalculează banii din stare la liniile 155-159. Soluția rapidă unde folosește același invariant — rejoacă și ea etajele, sau adună altceva?"

### T1-EQ — Răspunsul

Adună altceva: profituri pre-calculate, niciodată etaje rejucate. În Faza 1, la [BusyBeaver.java:100](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java), `money += s.profit` — profitul segmentului a fost calculat o singură dată în `nextSegment` (suma b−a pe etajele lui, linia 66 acumulează în `balance`), iar la construcție se adaugă ca un singur număr. În Faza 2, la linia 123, `m2 += p.b[h] - p.a[h]` — aici chiar etaj cu etaj, pentru că Faza 2 are nevoie de sold la FIECARE pas (verificarea `m2 >= p.a[h]` de la linia 122). Deci: unde contează doar destinația (profitul segmentului), invariantul T1 permite un singur `+=`; unde contează traseul (fezabilitatea per etaj), soldul se derulează pas cu pas. Predicție vecină, neverificată aici: dacă `Segment` ar memora și lista etajelor, nimeni n-ar citi-o — nici Faza 1, nici Faza 2 nu au nevoie de ea.

## T2. Pragul de intrare al unui segment

### T2-E1 — Traseul mecanic

Actorul e `nextSegment`, [BusyBeaver.java:60-72](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java). Pentru fiecare etaj j al candidatului: linia 64 plătește (`balance -= p.a[j]`), linia 65 fotografiază adâncimea gropii (`worst = Math.max(worst, -balance)`) — ATENȚIE, între plată și încasare — apoi linia 66 încasează (`balance += p.b[j]`). Filmul pe clădirea 2 din F3, a=(10,0), b=(0,100): etajul 1 → sold −10, worst=10, încasare 0 → sold −10; etajul 2 → sold −10, worst rămâne 10, încasare 100 → sold +90 ≥ 0 → linia 67-68 întoarce `Segment` cu prag=10, profit=90 (exact rândurile „etaj 1: sold dupa plata=-10" din rularea F5). Efectul: pragul spune câți morcovi trebuie să ai LA INTRARE ca soldul real să nu coboare sub zero nicăieri pe parcurs.

*Regula generală: pragul se măsoară în punctul cel mai adânc al gropii — după o plată, înainte de încasarea ei.*

### T2-E2 — Modelul mental

Separarea perechii P1, întâi fiecare vocabular. **Profitul net** = unde AJUNGI: soldul la finalul segmentului minus soldul la intrare; poate fi mare (+90) chiar dacă drumul e urât. **Pragul de intrare** = ce-ți trebuie ca să SUPRAVIEȚUIEȘTI drumul: cea mai adâncă scufundare a soldului sub nivelul de intrare, cu semn schimbat. Maparea: segmentul (10,0)/(0,100) are prag 10 și profit 90 — două numere care nu se calculează unul din altul; primul e un maxim pe parcurs, al doilea o sumă la final. Perechea P4 explică DE CE punctul de măsură e după plată: dacă linia 65 ar sta după linia 66, groapa ar părea mai mică exact cu b-ij, și un capital insuficient ar fi declarat suficient. Garanția pe care o cumperi cu pragul: intri cu sold ≥ prag → fiecare plată de pe parcurs e acoperită → segmentul, odată început, se poate TERMINA întotdeauna.

### T2-E3 — Scenariul predictiv

Segmentul [2..3) al clădirii 1 din F3 (un singur etaj, a=10, b=12): sold −10, worst=10, apoi +12 → +2. Prag 10, profit 2 — nu −2, nu 2: încasarea de 12 nu te ajută să PLĂTEȘTI cei 10, ea vine după. Predicția pe caz-limită: intri cu sold exact 10 în segmentul (10,0)/(0,100) → după plata etajului 1 soldul e exact 0, etajul 2 costă 0 → plătibil, încasezi 100 → soldul final 100, adică 10 (intrarea) + 90 (profitul). Pragul e atins la fix: un morcov mai puțin (9) și plata primului etaj ar duce soldul la −1 — interzis. Varianta de eșec: cine calculează pragul ca „cel mai scump etaj, max(a-ij)" greșește pe a=(3,4), b=(1,7): plățile sunt 3 și 4, dar soldul curge −3, +1 (încasare), −4 → groapa e 6, nu 4 — deficitul se ACUMULEAZĂ peste etaje când încasările nu îl astupă. Pragul e o proprietate a traseului, nu a celei mai scumpe plăți.

### T2-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: cardinalitatea sistemelor de referință. „`worst` din nextSegment pornește de la 0 pentru orice segment, chiar și pentru unul care începe la etajul 5 al clădirii. Cum poate un prag calculat «local» să fie comparat cu banii «globali» din Faza 1, la linia 98?"

### T2-EQ — Răspunsul

Pentru că ambele numere sunt exprimate față de ACELAȘI moment: intrarea în segment. `nextSegment` simulează soldul relativ — „pornesc de la zero, cât de jos cobor?" ([BusyBeaver.java:61-65](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)) — iar Faza 1 compară `pq.peek().threshold <= money` (linia 98) unde `money` sunt banii reali din clipa în care segmentul AR începe. Banii reali ≥ prag ⇔ soldul real (= bani reali + soldul relativ) nu coboară sub zero nicăieri. Etajele deja construite ale clădirii nu mai apar în calcul: `nextSegment(s.building, …, s.to)` (linia 105) pornește simularea de la `s.to`, cu balance resetat la 0 — costurile lor au fost deja plătite din banii reali, la momentul construcției lor. Predicție vecină: dacă pragul s-ar calcula față de începutul CLĂDIRII (nu al segmentului), segmentul al doilea al clădirii 1 din F3 ar cere 4+10−6=8 în loc de 10 — și Faza 1 ar intra în el cu bani insuficienți.

## T3. Segmentarea minimă cu profit ≥ 0

### T3-E1 — Traseul mecanic

Declanșatorul: `nextSegment(i, p, from)` caută de la etajul `from` înainte. Bucla de la [BusyBeaver.java:63-70](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java) se oprește la PRIMUL j cu sold ≥ 0 (condiția `if (balance >= 0)` de la linia 67) — nu caută mai departe un segment „mai profitabil". Pe clădirea 1 din F1, a=(4,4), b=(5,5): etajul 1 → −4+5 = +1 ≥ 0 → segment [1..2), prag 4, profit 1; reluat de la etajul 2 → alt segment [2..3), prag 4, profit 1. Rularea confirmă: „segmente cladirea 1: [1..2) prag=4 profit=1; [2..3) prag=4 profit=1". Dacă bucla epuizează etajele fără sold ≥ 0, întoarce null (linia 71) — clădirea 1 din F2 (toate prefixele negative: −2, −2, −3, −3) primește „niciunul". Efectul: fiecare clădire devine un lanț de segmente minime, consumate în ordine prin `from = s.to`.

*Regula generală: un segment se închide la prima gură de aer — primul moment în care soldul revine la zero sau deasupra.*

### T3-E2 — Modelul mental

De ce cele mai SCURTE segmente și nu unul mare: profitul unui segment se încasează în cont abia la finalul lui. Două segmente mici înseamnă două încasări intermediare — după primul, banii au crescut deja, și exact acei bani pot debloca un segment al ALTEI clădiri, mai ieftin decât continuarea acesteia. Un segment monolit ar ține profitul „blocat în șantier" până la capăt. Tăierea minimă nu costă nimic: dacă segmentele s1, s2 vin consecutiv, a construi s1 (prag t1) și apoi s2 (prag t2, dar cu profitul lui s1 deja în cont) cere la intrare max(t1, t2 − profit(s1)) — exact pragul pe care l-ar fi avut monolitul s1+s2. Deci împărțirea nu e niciodată mai scumpă, dar poate fi mai flexibilă. Consecința structurală, folosită de F5: orice prefix PROPRIU al unui segment minim are sold strict negativ — altfel bucla s-ar fi oprit acolo (comentariul de la liniile 55-58 fixează exact această garanție).

### T3-E3 — Scenariul predictiv

Clădirea a=(2,3,10), b=(3,4,9): etajul 1 → −2+3 = +1 → segment [1..2) prag 2 profit 1; de la etajul 2 → −3+4 = +1 → segment [2..3) prag 3 profit 1; de la etajul 3 → −10+9 = −1, etaje epuizate → null. Segmentarea: două segmente de câte un etaj, iar etajul 3 rămâne pe dinafară — el poate fi construit DOAR în Faza 2, de cine acceptă să piardă un morcov pentru înălțime. Predicția pe caz-limită: b-ij = a-ij pentru un etaj → sold 0 ≥ 0 → segment de profit 0, care SE construiește în Faza 1 (condiția e ≥, nu >) — clădirea 3 din F3, a=(3,50), b=(3,0), produce exact segmentul [1..2) prag=3 profit=0 din rulare.

### T3-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: tranziția de stare între segmente. „După ce Faza 1 construiește segmentul [1..2) al clădirii 1 din F3, cine și când calculează segmentul [2..3) — era în coadă de la început, sau apare abia atunci?"

### T3-EQ — Răspunsul

Apare abia atunci. La inițializare ([BusyBeaver.java:93-96](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)), coada primește EXACT UN segment per clădire — primul. Abia când un segment e construit, linia 105 (`Segment nxt = nextSegment(s.building, projects[s.building], s.to)`) calculează următorul segment al ACELEIAȘI clădiri și îl bagă în coadă (linia 106). Deci coada conține în orice moment cel mult n segmente — câte unul per clădire care mai are ceva de oferit — nu toate segmentele tuturor clădirilor. Dovada în trace-ul F3: segmentul [2..3) prag=10 al clădirii 1 e construit DUPĂ clădirea 2, deși clădirea 1 începuse prima — a reintrat în coadă abia după consumarea segmentului ei anterior și a așteptat acolo cu pragul lui. Predicție vecină: dacă toate segmentele ar intra de la început, corectitudinea nu s-ar strica (pragurile sunt aceleași), dar s-ar strica lanțul — ai putea construi [2..3) înaintea lui [1..2), ceea ce fizic nu există.

## T4. PriorityQueue pe prag

### T4-E1 — Traseul mecanic

Actorul: `PriorityQueue<Segment>` ordonată prin `Comparator.comparingLong(s -> s.threshold)` ([BusyBeaver.java:92](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)). Bucla Fazei 1 (liniile 98-107): cât timp segmentul cu pragul MINIM e accesibil (`pq.peek().threshold <= money`, linia 98), îl scoate (99), încasează profitul (100), ridică înălțimea clădirii (`built[s.building] = s.to`, linia 101) și pune în coadă următorul segment al clădirii (105-106). Filmul pe F3 (x=11): praguri în coadă {3, 4, 10} → construiește prag 3 (bani 11), prag 4 (bani 13), apoi cele două praguri 10 (bani 103, 105). Când pragul minim depășește banii, bucla se oprește: pe varianta x=5 din rulare, „stop Faza 1: pragul minim ramas 10 > bani 7" (mesajul de la liniile 109-110). Efectul: capitalul crește monoton până la maximul atingibil.

*Regula generală: un singur peek() răspunde la întrebarea „mai există CEVA construibil?" — pentru asta e heap-ul.*

### T4-E2 — Modelul mental

De ce e legal să construiești mereu cel mai ieftin segment, fără să te uiți la profit: în Faza 1 banii NU scad niciodată (fiecare segment are profit ≥ 0), deci accesibilitatea e monotonă — un segment accesibil acum rămâne accesibil pentru totdeauna. Construirea segmentului ieftin nu blochează nimic: după ea, banii sunt cel puțin egali, deci tot ce era accesibil rămâne accesibil. Iar dacă CEL MAI IEFTIN segment nu e accesibil, niciunul nu e (pragurile din coadă sunt toate ≥ minimul) — condiția de oprire e simultan condiția de optimalitate: nu există nicio construcție care să mai crească vreodată capitalul. Ordinea pe prag nu e o optimizare de viteză, e demonstrația de corectitudine comprimată într-un comparator. Nuanța de reținut: ordinea EXTRAGERII poate varia între praguri egale, dar mulțimea segmentelor construite — și deci banii finali (invariantul T1) — nu.

### T4-E3 — Scenariul predictiv

Pe F3 (x=11), în coadă stau la un moment dat DOUĂ segmente cu prag 10: [1..3) al clădirii 2 și [2..3) al clădirii 1. Rularea le-a extras în ordinea clădirea 2 → clădirea 1 (bani 13→103→105); dacă heap-ul le-ar fi extras invers, banii ar fi trecut prin 13→15→105 — alt traseu, ACEEAȘI destinație 105 și aceleași înălțimi [2,2,1]. Predicția pe caz-limită: la x=5, după pragurile 3 și 4, banii sunt 7 și pragul minim rămas e 10 — bucla nu construiește nici clădirea 2 (deși profitul ei, +90, ar „merita"): profitul nu poate cumpăra intrarea. Cine ar ordona coada după profit în loc de prag ar încerca segmentul +90 primul și s-ar bloca având în mână segmente accesibile de prag 3 și 4 neîncercate.

### T4-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: cardinalitatea inserărilor. „La fiecare extragere din coadă, câte segmente noi intră — unul, toate segmentele rămase ale clădirii, sau depinde?"

### T4-EQ — Răspunsul

Exact zero sau unul, niciodată mai multe. Singurul punct de inserare din buclă e [BusyBeaver.java:105-106](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java): după construirea segmentului s, `nextSegment(s.building, …, s.to)` calculează URMĂTORUL segment al aceleiași clădiri; dacă există, intră în coadă (unul), dacă nu (null — clădirea nu mai are nicio bucată cu profit ≥ 0), clădirea părăsește definitiv Faza 1 (zero). Alte clădiri nu sunt atinse de extragere — segmentele lor stau deja în coadă, câte unul fiecare. Consecința de complexitate: pe toată Faza 1 se fac cel mult „numărul total de segmente" ≤ Σm-i inserări, fiecare O(log n) — de aici vine O(Σm-i · log n) din editorial (inferență externă, nemăsurată în laborator). Predicție vecină: în trace-ul F3 apar exact 4 mesaje „construiesc" — 2 segmente ale clădirii 1, 1 al clădirii 2, 1 al clădirii 3 — și nicio clădire nu apare de două ori consecutiv cu inserări multiple.

## T5. Simularea independentă din Faza 2

### T5-E1 — Traseul mecanic

Declanșator: Faza 1 s-a oprit cu banii maximizați (`money`) și înălțimile persistente `built[]`. Actorul: bucla for de la [BusyBeaver.java:118-135](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java). Pentru fiecare clădire i: COPIA locală `long m2 = money` (linia 120), pornirea de la `int h = built[i]` (linia 121), apoi urcarea etaj cu etaj cât timp e plătibil: `while (h < p.floors() && m2 >= p.a[h])` (linia 122), cu soldul actualizat `m2 += p.b[h] - p.a[h]` (linia 123). Filmul pe F3: clădirea 1 pornește de la h=2 cu 105, plătește etajul 3 (a=3, b=0) → h=3, m2=102; clădirea 2 e deja completă (h=2, m2 rămâne 105); clădirea 3 pornește TOT de la 105 (nu de la 102!), plătește etajul 2 (a=50) → h=2, m2=55. Efectul: trei înălțimi finale ipotetice — 3, 2, 2 — din care liniile 130-133 aleg maximul cu indexul cel mai mic: „3 1".

*Regula generală: Faza 2 pune de fiecare dată TOT capitalul pe masă pentru o singură clădire — și după fiecare încercare îl ia înapoi.*

### T5-E2 — Modelul mental

Separarea perechii P2, fiecare vocabular întâi. **Înălțimea din Faza 1** (`built[i]`): construcție REALĂ — etajele au fost plătite o singură dată, din banii comuni, și rămân construite pentru toate clădirile simultan; ele au și produs bani (profit ≥ 0). **Simularea din Faza 2** (`h`, `m2`): construcție IPOTETICĂ — „ce-ar fi dacă de-acum înainte totul merge în clădirea asta?"; se aruncă la trecerea la clădirea următoare, pentru că cele n scenarii sunt ALTERNATIVE concurente, nu pași succesivi. De ce e corect să ignori celelalte clădiri în Faza 2: orice ajutor suplimentar de la ele ar fi ori un segment cu profit ≥ 0 (toate inaccesibile — Faza 1 s-a oprit exact pe condiția asta), ori o construcție în pierdere, care micșorează capitalul cu care ataci clădirea-țintă. Iar în clădirea-țintă pierderile sunt binevenite: scopul e înălțimea, banii sunt doar combustibil — F2 arde 3 morcovi ca să urce 4 etaje.

### T5-E3 — Scenariul predictiv

Pe setul „tie" din F4, x=10: clădirea 1, a=(5,6), b=(0,0): 10 ≥ 5 → h=1, m2=5; 5 < 6 → stop la 1 etaj. Clădirea 2, a=(10): 10 ≥ 10 → h=1, m2=0. Ambele ating 1 → linia 130 folosește strict `>`, deci clădirea 2 NU o detronează pe 1 → „1 1" (confirmat de rulare, fast=oracol). Predicția pe caz-limită: x=2 cu a=(7)/(9) → niciun etaj plătibil nicăieri, `bestHeight` rămâne 0 și `bestIndex` rămâne inițializarea 1 (linia 117) → „0 1". Varianta de eșec: cine ar lăsa `m2 = money` în AFARA buclei for ar transmite banii rămași de la o simulare la următoarea — clădirea 3 din F3 ar porni de la 102 în loc de 105 și, la un cost de etaj de 104, ar raporta o înălțime falsă.

### T5-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: perechea P2 pe direcția inversă. „Etajele construite de Faza 2 pentru clădirea câștigătoare există «pe bune»? Dacă da, de ce nu apar în `built[]`, și cum poate răspunsul final să citeze o înălțime pe care nimeni n-a construit-o persistent?"

### T5-EQ — Răspunsul

Există în planul de acțiune, nu în starea programului — și e suficient. Problema cere un NUMĂR (înălțimea maximă atingibilă + indexul), nu execuția planului: `solveFast` întoarce `new Answer(bestHeight, bestIndex)` ([BusyBeaver.java:136](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)) fără să mai atingă `built[]`. Garanția că planul e executabil vine din construcție: simularea Fazei 2 a verificat plătibilitatea fiecărui etaj (`m2 >= p.a[h]`, linia 122) pornind dintr-o stare REAL atingibilă (Faza 1) — deci secvența „rulează Faza 1, apoi toarnă tot în clădirea i" chiar se poate juca, morcov cu morcov. Oracolul confirmă independent: el chiar JOACĂ toate secvențele (T7) și raportează aceleași perechi înălțime-index pe toate cele 2000 de inputuri fuzz. Predicție vecină: dacă după Faza 2 ai vrea să afișezi și ORDINEA exactă a contractelor, ar trebui să memorezi trace-ul Fazei 1 (segmentele construite) plus etajele simulate ale câștigătoarei — informația există în `trace`, tocmai pentru manual.

## T6. Limitele și tipul long

### T6-E1 — Traseul mecanic

Datele problemei: x ≤ 10^18, Σm-i ≤ 2·10^5, a-ij și b-ij ≤ 10^9. Actorii care cară aceste valori sunt toți `long`: câmpurile `a`, `b` ale proiectului ([BusyBeaver.java:24-25](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)), `threshold` și `profit` ale segmentului (liniile 39-40), `money` (97), `balance`/`worst` (61-62), `m2` (120). Cel mai mare număr care poate apărea: capitalul maxim = x + suma tuturor profiturilor ≤ 10^18 + 2·10^5 · 10^9 = 10^18 + 2·10^14 ≈ 1,0002·10^18. Plafonul lui `long` e 2^63−1 ≈ 9,223·10^18 — încape de nouă ori. Pragul maxim = suma tuturor costurilor unui lanț ≤ 2·10^14. Efectul: nicio operație din soluție nu poate depăși long.

*Regula generală: aduni doar ce e mărginit de suma inputului — verifici plafonul o dată, pe hârtie, nu în cod.*

### T6-E2 — Modelul mental

De ce aici long ajunge fără nicio precauție, spre deosebire de laboratorul Become Immortal (unde înmulțirea a cerut trucul cu factorul par împărțit înainte): soluția Busy Beaver NU ÎNMULȚEȘTE niciodată două numere de mărimea datelor — singurele operații pe bani sunt adunări, scăderi și comparații. Adunarea crește liniar cu inputul (2·10^5 termeni de cel mult 10^9), pe când o singură înmulțire a două valori de 10^18 ar sări instant peste 2^63. Deci pericolul de overflow se judecă pe OPERAȚII, nu pe mărimea tipului: același `long` care acolo cerea aritmetică modulară atent ordonată, aici duce tot algoritmul nativ. Inversul rămâne adevărat: înălțimile stau liniștite în `int` (`built[]`, `h`) pentru că m-i ≤ 2·10^5 e departe de 2^31.

### T6-E3 — Scenariul predictiv

Scenariul extrem legal: x = 10^18 și toate cele 2·10^5 etaje cu a=0, b=10^9 → un singur lanț de segmente de profit pur → capital final 10^18 + 2·10^14 — încape. Predicția pe variații: dacă enunțul ar fi permis x până la 9·10^18, tot ar merge (9·10^18 + 2·10^14 < 9,223·10^18), dar la limită; dacă a, b ar urca la 10^18, suma profiturilor ar atinge 2·10^23 → long ar da overflow tăcut → ar trebui BigInteger sau saturare. Laboratorul își ține fuzz-ul departe de zona asta cu intenție: valori 0..6 (linia 297) — nu pentru long, ci pentru ca ORACOLUL să aibă spațiu de stări mic (T7); long-ul e exersat separat de x-ul din exemplele mari.

### T6-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: tranziția de semn. „`balance` din nextSegment coboară sub zero prin construcție (−10 la clădirea editorialului). `money` din Faza 1 poate coborî vreodată sub zero — și dacă nu, ce linie anume garantează asta?"

### T6-EQ — Răspunsul

Nu poate. `money` pornește de la x ≥ 0 ([BusyBeaver.java:97](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)) și e modificat într-un singur loc în Faza 1: `money += s.profit` (linia 100), unde `s.profit ≥ 0` prin construcția segmentului — `nextSegment` întoarce doar la `balance >= 0` (linia 67). În Faza 2, `m2` scade sub nivelul de pornire (F2: din 6 ajunge la 3), dar niciodată sub zero: garda `m2 >= p.a[h]` (linia 122) verifică ÎNAINTE de plată, iar după plată vine încasarea b ≥ 0. Cele două variabile trăiesc sub contracte diferite: `balance` e un sold RELATIV, proiectat să măsoare groapa (negativul e chiar informația căutată — worst, linia 65); `money`/`m2` sunt solduri ABSOLUTE, iar nenegativitatea lor e chiar regula jocului din enunț. Predicție vecină: un assert `money >= 0` după linia 100 nu ar pica niciodată; un assert `balance >= 0` în mijlocul lui nextSegment ar pica la primul segment cu groapă reală.

## T7. Oracolul brute force

### T7-E1 — Traseul mecanic

Actorul: `solveOracle`, [BusyBeaver.java:144-178](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java). Starea = vectorul de înălțimi construite `int[] st`. Pornire: starea [0,…,0] intră în coadă (linia 149). Pentru fiecare stare scoasă: (1) banii se RECALCULEAZĂ din stare (liniile 155-159, invariantul T1); (2) recordul se actualizează — înălțime mai mare, sau egală cu index mai mic (liniile 161-167); (3) expansiune: pentru fiecare clădire i cu următorul etaj plătibil (`money >= projects[i].a[h]`, linia 171), starea-copil cu `nxt[i] = h + 1` (linia 173) intră în coadă dacă nu a mai fost văzută (`seen.add`, linia 174). Efectul: BFS enumeră TOATE mulțimile de etaje atingibile prin vreo ordine legală și citește răspunsul direct din definiția problemei.

*Regula generală: oracolul nu știe nicio idee — execută enunțul literal, și exact de-aia are dreptate.*

### T7-E2 — Modelul mental

De ce starea nu conține banii: T1 — banii sunt funcție de stare, deci a-i memora ar fi redundant, iar a-i pune în cheia de deduplicare ar fi inofensiv dar inutil. De ce BFS și nu DFS: indiferent de ordinea explorării, mulțimea stărilor atinse e aceeași (accesibilitatea unui copil depinde doar de starea-părinte, nu de drumul până la ea) — coada e doar cea mai simplă unealtă de „vizitează tot o singură dată". Cardinalitatea care îl face utilizabil DOAR pe inputuri mici: numărul de stări ≤ Π(m-i + 1); la fuzz (n ≤ 3, m ≤ 4) cel mult 5³ = 125 de stări, la dimensiunile enunțului (m-i până la 2·10^5) — astronomic. De-aia oracolul trăiește în laborator, nu în competiție: rolul lui e de ARBITRU pentru soluția rapidă (F6), nu de concurent.

### T7-E3 — Scenariul predictiv

Pe exemplul 2 din enunț (m=(2,2)), spațiul complet ar fi 3×3 = 9 stări; din x=3 sunt atingibile mai puține — de la [0,0] singura mutare plătibilă e clădirea 2 (a=2 ≤ 3; clădirea 1 cere 4). Derulare: [0,0]→[0,1] (bani 5) → de acolo [1,1] (bani 6) și [0,2]? — etajul 2 al clădirii 2 costă 20 > 5, deci nu; [1,1]→[2,1] (bani 7). Stările atinse: {[0,0],[0,1],[1,1],[2,1]} — 4 din 9; recordul: înălțimea 2 la clădirea 1 → „2 1", identic cu soluția rapidă. Predicția pe caz-limită: dacă TOATE etajele ar fi gratuite (a=0), toate cele 9 stări ar deveni atingibile și oracolul ar termina tot — dar la m=20 pe 3 clădiri, 21³ ≈ 9.261 de stări încă merg; la m=2·10^5, niciodată.

### T7-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: actorul recordului. „Recordul oracolului se actualizează la SCOATEREA stării din coadă (liniile 161-167), nu la inserare. O stare care n-a apucat să fie scoasă — de exemplu ultima generată — poate rămâne necontorizată?"

### T7-EQ — Răspunsul

Nu: bucla `while (!queue.isEmpty())` ([BusyBeaver.java:153](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)) rulează până când coada se golește complet, iar fiecare stare inserată (149, 174) e scoasă exact o dată — nimeni nu iese din buclă mai devreme, nu există „țintă atinsă, gata". Deduplicarea prin `seen` (adăugare la INSERARE, linia 174, nu la scoatere) garantează că fiecare stare intră în coadă cel mult o dată, deci bucla termină. Ordinea contorizării nu contează pentru corectitudine: recordul e un maxim peste toate stările, iar maximul e indiferent la ordine — inclusiv tie-break-ul, pentru că regula „egal → index mai mic" (liniile 165-166) se aplică la fiecare stare în parte. Predicție vecină: dacă `seen.add` s-ar muta la scoatere, aceeași stare ar putea sta în coadă de mai multe ori (căi diferite spre ea) — rezultatul ar rămâne corect, dar timpul ar crește; fuzz-ul n-ar prinde nimic, pentru că fuzz-ul compară doar răspunsuri.

---

# Firele de execuție

## F1. Exemplul 2 din enunț — clădirea 2 finanțează clădirea 1

### F1-E1 — Traseul mecanic

Input ([BusyBeaver.java:200-211](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)): x=3; clădirea 1: a=(4,4), b=(5,5); clădirea 2: a=(2,20), b=(4,0). Filmul complet din rulare: `printSegments` raportează segmentele — clădirea 1: [1..2) prag=4 profit=1 și [2..3) prag=4 profit=1; clădirea 2: [1..2) prag=2 profit=2 (etajul 2, cu a=20/b=0, nu formează segment). Faza 1, în ordinea pragurilor: prag 2 (clădirea 2, bani 3→5), prag 4 (clădirea 1 etajul 1, bani →6), prag 4 (clădirea 1 etajul 2, bani →7). Coada se golește → „Faza 1 inchisa: bani=7, inaltimi=[2, 1]". Faza 2 nu mai mișcă nimic: etajul 2 al clădirii 2 costă 20 > 7. Răspuns: fast=„2 1", oracol=„2 1" — identic cu răspunsul oficial al enunțului.

*Regula generală: notația [1..2) numește etajele de la 1 inclusiv la 2 exclusiv — adică exact etajul 1.*

### F1-E2 — Modelul mental

Firul ăsta e chiar nota explicativă a enunțului, tradusă în vocabularul soluției: „construiește întâi etajul 1 al clădirii 2, câștigând 2 morcovi" = segmentul de prag 2 și profit 2 al clădirii 2; „apoi poți construi ambele etaje ale clădirii 1" = cele două segmente de prag 4, deblocate de banii crescuți la 5. Observația de reținut: aici răspunsul e decis COMPLET în Faza 1 — înălțimea 2 a clădirii 1 e deja în `built[]`, iar Faza 2 doar constată că nu mai poate adăuga nimic. Clădirea 2 joacă rolul de finanțator (profitul ei pornește lanțul), dar rămâne la înălțimea 1: rolul de sursă de bani și rolul de candidat la înălțime sunt independente — aceeași clădire le joacă pe amândouă, cu succes diferit.

### F1-E3 — Scenariul predictiv

Coborâm capitalul și prezicem: la x=2, segmentul de prag 2 e încă accesibil → bani 2→4→5→6, aceleași înălțimi [2,1], răspuns tot „2 1". La x=1, pragul 2 nu mai e accesibil → Faza 1 nu construiește nimic; Faza 2: clădirea 1 cere 4 > 1, clădirea 2 cere 2 > 1 → „0 1". Între x=1 și x=2 e o prăpastie: un singur morcov desparte „nimic construibil" de „patru contracte și înălțimea 2". Varianta de eșec instructivă: cine sare peste Faza 1 și toarnă x=3 direct în clădirea 1 (ținta finală) se blochează la primul etaj (4 > 3) — drumul spre înălțime trece obligatoriu prin altă clădire.

### F1-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: rolurile clădirilor (actor exact). „Clădirea 2 a pornit tot lanțul — fără ea nu se construia nimic. De ce răspunsul o ignoră și premiază clădirea 1, și există vreun input în care finanțatorul să fie chiar câștigătorul?"

### F1-EQ — Răspunsul

Răspunsul premiază exclusiv înălțimea maximă: comparația de la [BusyBeaver.java:130-133](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java) se uită doar la `h`, nu la cine a produs banii. Clădirea 2 termină la înălțimea 1 (etajul ei 2 cere 20, iar capitalul maxim e 7), clădirea 1 la 2 — deci „2 1". Și da, finanțatorul poate câștiga: în F3, clădirea 2 (finanțatorul cu +90) termină la înălțimea 2, dar clădirea 1 urcă la 3 prin Faza 2; dacă îi tai clădirii 1 etajul 3 (m=2), toate trei ating înălțimea 2 și tie-break-ul dă tot „2 1" — finanțatorul pierde din nou, de data asta la egalitate. Un exemplu unde finanțatorul chiar ia premiul e F4-„castiga indexul 2": clădirea 2, singura construibilă, e și cea mai înaltă → „2 2". Distincția care rămâne: profitul decide CINE SE CONSTRUIEȘTE ÎN FAZA 1, înălțimea decide cine apare în răspuns.

## F2. Exemplul 1 din enunț — Faza 2 duce tot greul

### F2-E1 — Traseul mecanic

Input ([BusyBeaver.java:213-223](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)): x=6; o singură clădire, a=(4,4,2,1), b=(2,4,1,1). `printSegments`: „niciunul" — soldurile prefixelor sunt −2, −2, −3, −3, nicăieri ≥ 0, deci `nextSegment` întoarce null de la primul apel (linia 71). Faza 1 se închide fără nicio construcție: „bani=6, inaltimi=[0]". Faza 2 urcă etaj cu etaj: 6−4+2=4 (etajul 1), 4−4+4=4 (etajul 2), 4−2+1=3 (etajul 3), 3−1+1=3 (etajul 4) → h=4, „bani finali simulati 3". Răspuns: „4 1", identic la fast și oracol — și identic cu enunțul.

*Regula generală: o clădire fără segmente profitabile e invizibilă pentru Faza 1, dar perfect construibilă pentru Faza 2.*

### F2-E2 — Modelul mental

Firul separă două întrebări care sună la fel și nu sunt: „merită construit?" (Faza 1 — doar dacă profitul net e ≥ 0) și „poate fi construit?" (Faza 2 — doar dacă soldul acoperă fiecare plată). Clădirea asta pierde bani la fiecare pas — nete −2, +0, −1, +0 — și totuși toate cele 4 etaje se ridică, pentru că scopul final nu e capitalul, ci înălțimea: cei 6 morcovi sunt un buget de ars, iar clădirea arde net doar 3 din ei. Perechea P1 reapare la nivel de clădire întreagă: „nu e profitabilă" (adevărat) nu implică „nu e accesibilă" (fals — pragul întregului lanț, calculat manual, ar fi 6: exact capitalul disponibil).

### F2-E3 — Scenariul predictiv

Predicții pe capitaluri mai mici, verificabile de mână pe același film: x=5 → 5−4+2=3, etajul 2 cere 4 > 3 → h=1. x=4 → 4−4+2=2 < 4 → h=1. x=3 → 3 < 4 → h=0, răspuns „0 1". Deci înălțimea în funcție de x sare 0→1 la x=4 și 1→4 la x=6: etajul 2 (a=4, după ce etajul 1 a lăsat doar +2−4=−2 net) e zidul; odată trecut de el, etajele 3-4 (ieftine) vin gratis-aproape. Cazul-limită interesant: la x=6 soldul atinge exact 4 înaintea etajului 2 — fix cât trebuie; un input cu b1=1 în loc de 2 ar tăia înălțimea înapoi la 1.

### F2-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: tranziția de stare din bucla Fazei 2. „Pe filmul x=6, soldul înaintea etajului 2 e 4 și plata e tot 4. Garda de la linia 122 e `>=` — dacă ar fi fost `>`, ce înălțime ar fi raportat programul, și pe care din cele 2000 de inputuri fuzz s-ar fi văzut prima dată diferența?"

### F2-EQ — Răspunsul

Cu `>` strict, etajul 2 ar fi refuzat la sold exact 4 → clădirea s-ar opri la h=1 → răspuns „1 1" în loc de „4 1". Enunțul cere doar să POȚI plăti a-ij — egalitatea e plată legală, deci `m2 >= p.a[h]` la [BusyBeaver.java:122](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java) e transcrierea fidelă; oracolul are aceeași comparație la linia 171 (`money >= projects[i].a[h]`), deci pe exemplul 1 cele două s-ar despărți imediat: oracolul plătește cu sold exact, mutantul nu. Pe fuzz nu pot numi iterația exactă fără să rulez mutantul (Necunoscut din cod — mutația asta nu a fost rulată în laborator; cele două mutații rulate sunt la F6-E3), dar clasa de inputuri care o prinde e clară: orice lanț în care soldul atinge exact costul următorului etaj. Predicție vecină: aceeași greșeală în Faza 1 (linia 98, `<=` devenit `<`) A FOST rulată ca mutant — 46 de nepotriviri din 2000 (F6-E3).

## F3. Trei clădiri — bucla PriorityQueue cap-coadă

### F3-E1 — Traseul mecanic

Input ([BusyBeaver.java:225-242](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)): x=11; clădirea 1: a=(4,10,3), b=(6,12,0); clădirea 2: a=(10,0), b=(0,100); clădirea 3: a=(3,50), b=(3,0). Segmentele din rulare: clădirea 1 → [1..2) prag=4 profit=2 și [2..3) prag=10 profit=2; clădirea 2 → [1..3) prag=10 profit=90 (un segment de DOUĂ etaje); clădirea 3 → [1..2) prag=3 profit=0. Faza 1, patru extrageri în ordinea pragurilor 3, 4, 10, 10: bani 11→11 (profit 0!)→13→103→105, înălțimi [2, 2, 1]. Faza 2: clădirea 1 urcă etajul 3 (a=3, b=0) → h=3, m2=102; clădirea 2 e completă; clădirea 3 urcă etajul 2 (a=50) → h=2, m2=55. Răspuns: „3 1", confirmat de oracol.

*Regula generală: trace-ul Fazei 1 se citește ca un extras de cont — fiecare rând, un segment întreg încasat.*

### F3-E2 — Modelul mental

Cele trei clădiri sunt cele trei roluri posibile în Faza 1. Clădirea 3: „portarul" — profit 0, dar intră (condiția ≥ 0 din T3) și aduce înălțime gratis; banii nu se mișcă, `built[2]` da. Clădirea 1: „trepte" — două segmente mici care cresc capitalul treptat (11→13→…→105) și lasă clădirea aproape gata. Clădirea 2: „jackpotul cu barieră" — profitul uriaș (+90) stă în spatele unui prag de 10. La x=11 bariera e trecută din prima clipă (11 ≥ 10), deci ordinea extragerilor nici nu contează pentru rezultat; bariera mușcă abia la x=5 (varianta din rulare): acolo banii urcă doar la 7 prin pragurile 3 și 4, iar mesajul „stop Faza 1: pragul minim ramas 10 > bani 7" arată exact clipa în care jackpotul rămâne închis. Perechea P3, pe viu: ÎN clădirea 2, etajele 1→2 vin obligatoriu în ordine (un singur segment le înghite pe amândouă); ÎNTRE clădiri, ordinea o dă exclusiv pragul.

### F3-E3 — Scenariul predictiv

Predicție pe capitaluri intermediare, derulabilă de mână: la x=10, pragul 3 → 10, pragul 4 → 12, pragul 10 → 102, pragul 10 → 104 — tot totul construit, înălțimi [2,2,1], răspuns tot „3 1". La x=4: prag 3 → 4, prag 4 → 6, prag 10 > 6 → stop; înălțimi [1,0,1], Faza 2: clădirea 1 nu poate plăti etajul 2 (10 > 6), clădirea 2 nici etajul 1 (10 > 6), clădirea 3 nici etajul 2 (50 > 6) → „1 1". Granițele: x ≥ 8 deblochează tot lanțul (după pragurile 3 și 4, bani = x+2 ≥ 10); x ∈ {4..7} lasă doar treptele mici (praguri 3 și 4); la x=3 intră doar portarul de prag 3; x ≤ 2 nu pornește nimic → „0 1". Varianta x=5 din rulare (bani finali 7, răspuns „1 1") cade exact în banda din mijloc.

### F3-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: perechea amestecabilă „segment de două etaje" vs „două segmente". „Clădirea 2 are etajele (10,0)/(0,100) într-un SINGUR segment [1..3), dar clădirea 1 are (4,6) și (10,12) în DOUĂ segmente separate. Ce anume din cifre decide tăietura, și ce s-ar schimba dacă clădirea 2 ar avea b=(11,89) în loc de (0,100)?"

### F3-EQ — Răspunsul

Decide primul loc unde soldul relativ urcă la ≥ 0 ([BusyBeaver.java:67](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)). La clădirea 1, deja după etajul 1 soldul e −4+6 = +2 ≥ 0 → tăietură imediată; etajul 2 începe segment nou. La clădirea 2, după etajul 1 soldul e −10+0 = −10 < 0 → bucla e OBLIGATĂ să continue; abia etajul 2 (−10−0+100) îl scoate la +90 → un singur segment, cu tot cu garanția din T3-E2 că prefixul lui propriu (etajul 1 singur) e strict pe minus. Cu b=(11,89): după etajul 1, soldul −10+11 = +1 ≥ 0 → tăietura apare → segmentele devin [1..2) prag=10 profit=1 și [2..3) prag=0 profit=89 — același total (+90), aceeași intrare (10), dar cu o încasare intermediară care, la un x fix între praguri, poate debloca alte clădiri mai devreme. Predicție vecină: cu b=(11,89), varianta x=5 tot n-ar mișca nimic la clădirea 2 — pragul primului segment rămâne 10, peste banii maximi 7.

## F4. Tie-break pe index și înălțimea 0

### F4-E1 — Traseul mecanic

Trei mini-inputuri în [BusyBeaver.java:244-266](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java), toate cu b=0 peste tot (nicio clădire nu produce bani → Faza 1 idle, „inaltimi=[0, 0]"). (1) Tie, x=10: clădirea 1, a=(5,6) → h=1 (rămân 5, etajul 2 cere 6); clădirea 2, a=(10) → h=1 (rămân 0). Ambele la 1 → comparația strictă `h > bestHeight` (linia 130) nu lasă clădirea 2 să detroneze → „1 1". (2) Zero, x=2: a=(7) și a=(9), nimic plătibil → `bestHeight`=0, `bestIndex` rămâne inițializarea 1 (linia 117) → „0 1". (3) Câștigă indexul 2, x=10: clădirea 1, a=(20) → h=0; clădirea 2, a=(5,5) → h=2 → „2 2". Toate trei confirmate de oracol.

*Regula generală: parcurgerea crescătoare a indecșilor + comparația strictă = „primul dintre egali câștigă", fără niciun cod special de tie-break.*

### F4-E2 — Modelul mental

Enunțul cere „cel mai mic index" doar la egalitate de înălțime — dar tace complet despre cazul înălțimii 0 (niciun etaj construibil nicăieri): ce index raportezi când NICIO clădire nu atinge nimic? Convenția laboratorului: indexul 1, prin inițializarea `bestIndex = 1` — care se poate citi ca „toate clădirile ating trivial înălțimea 0, deci cel mai mic index care o atinge e 1". Aceeași convenție e implementată independent în oracol (liniile 151-152), așa că fuzz-ul VERIFICĂ CONSISTENȚA celor două implementări, nu adevărul enunțului — dacă juriul ar fi vrut alt răspuns la 0, ambele ar greși la unison și fuzz-ul n-ar clipi. Comentariile de la editorial semnalează exact golul ăsta („when no building can be constructed at all, what should the index be? The intended answer seems to be 1"), deci convenția coincide cu intenția aparentă a juriului — inferență externă, nu fapt din enunț.

### F4-E3 — Scenariul predictiv

Predicții pe variații ale celor trei inputuri: la „tie" cu ordinea clădirilor inversată (a=(10) prima, a=(5,6) a doua), ambele ating tot 1, iar răspunsul devine tot „1 1" — dar acum indexul 1 e FOSTA clădire 2: tie-break-ul premiază poziția în input, nu vreo proprietate a clădirii. La „castiga indexul 2" cu x crescut la 20: clădirea 1 poate plăti (20 ≥ 20) → h=1; clădirea 2 tot 2 → „2 2" neschimbat; ca indexul 1 să conteze la tie ar trebui x suficient pentru două etaje la clădirea 1 — dar ea ARE un singur etaj (m=1), deci „2 2" e verdictul pentru orice x ≥ 10. Cazul-limită de prezis corect: x=9 la inputul (2): clădirea 2 face 9−5=4 < 5 → h=1; clădirea 1: 20 > 9 → h=0 → „1 2".

### F4-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: interacțiunea tie-break-ului cu Faza 1. „În toate cele trei inputuri de aici Faza 1 nu construiește nimic, deci tie-ul se judecă doar în Faza 2. Dacă o clădire iese din Faza 1 gata construită la înălțimea ei maximă, participă la tie-break cu aceleași reguli, sau are prioritate ca «deja reală»?"

### F4-EQ — Răspunsul

Aceleași reguli, nicio prioritate. Bucla Fazei 2 ([BusyBeaver.java:118-135](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java)) nu deosebește proveniența înălțimii: pentru clădirea completată în Faza 1, `h = built[i]` pornește de la maxim, `while`-ul nu mai are ce urca, și `h` intră în aceeași comparație de la linia 130 ca oricare alta. Dovada pe F3: clădirea 2 iese din Faza 1 la înălțimea ei maximă 2 („reală", plătită) și PIERDE în fața clădirii 1, care ajunge la 3 doar în simulare — răspunsul „3 1" preferă înălțimea ipotetică mai mare celei reale mai mici, exact ce cere problema (înălțimea ATINGIBILĂ). Iar la egalitate, tot poziția în input decide: dacă clădirea completă în Faza 1 are index mai mare, egalul ei cu index mai mic o bate. Predicție vecină: pe F3 cu etajul 3 al clădirii 1 scos (m=2), toate trei ating 2 — răspuns „2 1", deși clădirea 1 și-a câștigat înălțimea parțial prin Faza 1 și clădirea 3 prin amestec Faza 1 + Faza 2.

## F5. Prefixul parțial e mereu pierdere — capitalul Fazei 1 e maximal

### F5-E1 — Traseul mecanic

Runner-ul [BusyBeaver.java:268-280](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java) derulează clădirea-exemplu a editorialului, a=(10,0), b=(0,100), sold cu sold: „etaj 1: sold dupa plata=-10, sold dupa incasare=-10" (b1=0 nu aduce nimic înapoi), „etaj 2: sold dupa plata=-10, sold dupa incasare=90". `nextSegment` întoarce segmentul întreg [1..3), prag=10, profit=90. Linia finală a trace-ului pune cifrele față în față: cu 15 morcovi în cont, construirea DOAR a etajului 1 lasă 5; construirea segmentului întreg lasă 105. Oprirea la jumătate transformă un câștig de +90 într-o pierdere de −10.

*Regula generală: soldul unui prefix propriu de segment minim e strict negativ — oprirea acolo înseamnă, prin definiție, bani pierduți.*

### F5-E2 — Modelul mental

De ce asta demonstrează că Faza 1 maximizează capitalul: la finalul ei, orice construcție suplimentară pe clădirile-ajutor e una din două — (a) un segment întreg cu profit ≥ 0, dar toate au prag > bani (condiția de oprire de la linia 98, T4-E2), deci inaccesibile; sau (b) o bucată care NU e segment întreg — adică un prefix propriu al unui segment minim — și atunci soldul ei e strict negativ (garanția T3), deci bani în minus. Nu există a treia opțiune: orice serie de etaje consecutive pornind de la înălțimea curentă ori atinge un punct de sold ≥ 0 (și atunci primul asemenea punct e chiar granița următorului segment minim), ori nu-l atinge deloc. Important: „a începe un segment accesibil" nu creează risc de blocare la mijloc — pragul garantează completarea (T2-E2) — deci Faza 1 nici măcar nu trebuie să „planifice": ia tot ce e accesibil, întreg, și capitalul rezultat domină orice altă strategie de finanțare.

### F5-E3 — Scenariul predictiv

Predicție cu cifrele din trace: pornești cu 15, vrei să „economisești" construind doar etajul 1 al clădirii editorialului azi și etajul 2 mâine — după etajul 1 ai 5; etajul 2 costă 0, deci POȚI continua oricând, iar la final tot 105 ai. Aici oprirea la mijloc nu te blochează, doar întârzie — pentru că restul segmentului avea costuri 0. Varianta care chiar doare: fie clădirea a=(10,6), b=(0,100) — segment [1..3), prag 16, profit 84. Cu 15 în cont, pragul 16 > 15 → segmentul e inaccesibil ÎNTREG; dacă totuși construiești etajul 1 „ca să avansezi", rămâi cu 5, iar etajul 2 cere 6 > 5 → blocat pe minus 10, fără drum înapoi. Exact scenariul pe care condiția `threshold <= money` îl interzice: intri doar dacă poți termina.

### F5-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: corecția din F2 aplicată greșit aici. „F2 tocmai a arătat că etaje în pierdere SE construiesc (toate cele 4). F5 spune că bucățile în pierdere NU se construiesc. Care e criteriul exact care împacă cele două fire — cine are voie să piardă bani și cine nu?"

### F5-EQ — Răspunsul

Criteriul e ROLUL clădirii în momentul construcției, nu clădirea în sine. Cât timp o clădire e AJUTOR (Faza 1), singura ei valoare e capitalul produs — o bucată în pierdere contrazice însuși motivul construirii ei, deci interdicția e absolută: Faza 1 construiește exclusiv segmente întregi cu profit ≥ 0 ([BusyBeaver.java:67](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java), 98). Când o clădire devine ȚINTĂ (Faza 2), valoarea ei e înălțimea — banii coboară de la scop la mijloc, și pierderea pe etaj e acceptabilă până la ultimul morcov plătibil (linia 122, fără nicio condiție de profit). Aceeași clădire trece prin ambele roluri: clădirea 1 din F3 e ajutor pentru segmentele (4,6) și (10,12), apoi țintă pentru etajul 3 în pierdere (a=3, b=0). Subtilitatea de reținut: Faza 2 încearcă TOATE clădirile pe rolul de țintă, pe rând — deci „cine are voie să piardă" primește răspuns diferit în fiecare din cele n simulări. Predicție vecină: un etaj în pierdere construit pentru ținta i Nu apare în simularea țintei j — F3: cei 3 morcovi arși de clădirea 1 pe etajul 3 nu lipsesc din cei 105 cu care pornește clădirea 3.

## F6. Fuzz — fast egal cu oracolul pe 2000 de inputuri

### F6-E1 — Traseul mecanic

Actorul: `f6Fuzz`, [BusyBeaver.java:284-311](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java). Generatorul cu sămânță fixă `new Random(20260822L)` (linia 286) produce 2000 de inputuri (287): n ∈ 1..3 clădiri (290), fiecare cu m ∈ 1..4 etaje (293), costuri și încasări în 0..6 (297-298), capital x ∈ 0..12 (302). Pentru fiecare input rulează ambele soluții și compară PERECHEA completă — `fast.height != oracle.height || fast.index != oracle.index` (linia 305); orice nepotrivire se tipărește cu inputul complet (307-308) ca să fie rejucabilă. Rezultatul rulării: „2000 inputuri aleatoare, 0 nepotriviri".

*Regula generală: sămânța fixă face din fuzz un test determinist — aceleași 2000 de inputuri la fiecare rulare, orice eșec reproductibil imediat.*

### F6-E2 — Modelul mental

Ce demonstrează și ce nu. Demonstrează: pe cutia [n≤3, m≤4, valori ≤6, x≤12], soluția rapidă coincide cu DEFINIȚIA problemei (oracolul, T7) în ambele componente ale răspunsului — inclusiv tie-break-ul pe index, care fără comparația de la linia 305 ar scăpa nevalidat. Nu demonstrează: comportamentul la valori mari (acolo veghează doar analiza T6, pe hârtie), performanța la Σm = 2·10^5 (complexitatea din editorial rămâne inferență externă) și corectitudinea convenției „înălțime 0 → index 1" față de juriul real (F4-E2 — ambele implementări o împărtășesc, deci fuzz-ul e orb la ea). Cutia mică e o alegere, nu o limitare întâmplătoare: valori mici înghesuie multe coliziuni interesante (solduri exacte, praguri la fix, egalități de înălțime) în puține stări, exact unde greedy-urile mor de obicei.

### F6-E3 — Scenariul predictiv

Predicția „fuzz-ul chiar prinde" a fost verificată cu două mutații rulate în laborator (2026-08-22, copii în scratchpad, nu în repo). Mutația 1: `<=` devenit `<` la linia 98 (segmentul cu prag EXACT egal cu banii refuzat) → 46 de nepotriviri din 2000, prima la iterația 2: fast=„2 3" vs oracol=„2 2" pe x=0 — remarcabil, un input cu x=0 unde un segment de prag 0 era refuzat. Mutația 2: `>` devenit `>=` la linia 130 (tie-break inversat: ultimul dintre egali câștigă) → 507 nepotriviri din 2000, prima chiar la iterația 1: fast=„2 3" vs oracol=„2 1". Diferența de densitate (46 vs 507) e ea însăși informativă: egalitățile de înălțime sunt frecvente în cutia mică, pragurile atinse la fix sunt rare — a doua clasă de bug-uri are nevoie de mai mult fuzz sau de cutii mai strâmte ca să iasă la iveală la fel de des.

### F6-Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Riscul ales: acoperirea (ce NU testează fuzz-ul). „Dacă mâine cineva strică `nextSegment` doar pentru clădiri cu m ≥ 5 — de exemplu un buffer care se umple la 4 etaje — cele 2000 de inputuri ar rămâne verzi. Ce parametri ai cutiei ar trebui lărgiți, și care e prețul?"

### F6-EQ — Răspunsul

Verzi ar rămâne, da: generatorul nu produce niciodată m ≥ 5 ([BusyBeaver.java:293](../../src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java), `1 + rnd.nextInt(4)`), deci codul ipotetic stricat n-ar fi executat nici măcar o dată. Lărgirea cutiei se plătește la oracol, nu la fast: spațiul de stări e Π(m-i+1) (T7-E2), deci m ≤ 10 pe n ≤ 3 înseamnă până la 11³ = 1.331 stări per input — încă ieftin; n ≤ 5 cu m ≤ 10 urcă la 11⁵ ≈ 161.000 — tot fezabil, dar de 100 de ori mai lent per input, deci ori tai numărul de iterații, ori aștepți. Valorile (0..6) și x (0..12) ar trebui scalate ÎMPREUNĂ cu m: praguri mai adânci au nevoie de capitaluri comparabile ca să fie uneori accesibile și alteori nu — un x mereu prea mic ar testa doar „0 1", unul mereu prea mare doar „totul construibil". Predicție vecină: chiar și cutia curentă are un punct orb cunoscut — b > a cu diferențe mari (jackpoturi de +100 ca în F3/F5 nu apar la valori ≤ 6); un al doilea fuzz cu distribuție bimodală a lui b ar acoperi exact clasa segmentelor „groapă adâncă, răsplată mare".

---

# Glosar

- **a-ij / b-ij** — costul, respectiv încasarea etajului j al clădirii i; plata vine ÎNAINTE de construcție, încasarea imediat DUPĂ (perechea P4).
- **capital / bani (money, m2)** — soldul absolut al companiei; nu are voie să coboare sub 0. `money` e cel real (Faza 1), `m2` copia ipotetică per clădire (Faza 2).
- **sold relativ (balance)** — derularea banilor față de intrarea într-un segment, în `nextSegment`; negativul lui e tocmai informația căutată.
- **segment** — cea mai scurtă serie de etaje consecutive cu profit net ≥ 0, pornind de la înălțimea curentă; unitatea de construcție a Fazei 1.
- **profit net** — suma b−a pe o mulțime de etaje; unde AJUNGE soldul la final (perechea P1, partea 1).
- **prag de intrare (threshold)** — capitalul minim la intrarea în segment ca soldul să nu coboare sub 0; cât îți trebuie ca să SUPRAVIEȚUIEȘTI drumul (perechea P1, partea 2).
- **prefix propriu** — bucată de segment oprită înainte de final; are sold strict negativ prin construcția segmentului minim (F5).
- **Faza 1** — bucla PriorityQueue care construiește segmente accesibile în ordinea pragului până maximizează capitalul; înălțimile ei (`built[]`) sunt persistente.
- **Faza 2** — simularea independentă „tot capitalul într-o singură clădire", etaj cu etaj; rezultatele ei sunt ipotetice și se aruncă între clădiri (perechea P2).
- **PriorityQueue (heap)** — colecția Java care întoarce mereu elementul minim după comparator; aici, segmentul cu pragul cel mai mic.
- **oracol** — implementarea brute force a definiției problemei (BFS pe stări), folosită ca arbitru pentru soluția rapidă.
- **BFS** — parcurgere în lățime: stările intră într-o coadă și sunt procesate o singură dată fiecare, cu deduplicare prin `seen`.
- **stare (în oracol)** — vectorul înălțimilor construite per clădire; banii nu fac parte din stare (invariantul T1).
- **fuzz** — comparare automată fast vs oracol pe multe inputuri aleatoare mici, cu sămânță fixă pentru reproductibilitate.
- **tie-break** — regula „la înălțimi egale câștigă indexul mai mic", obținută prin parcurgere crescătoare + comparație strictă.
- **mutant / mutație** — copie a codului cu o singură greșeală introdusă intenționat, folosită ca să verifici că fuzz-ul chiar detectează clasa respectivă de bug-uri.

# Index de ancore în cod

Toate ancorele indică `src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java` (fișier de laborator nou, necomis la data generării).

| Ancoră | Rol |
|--------|-----|
| `Segment` 35-53 | structura segment: clădire, interval [from, to), prag, profit |
| `nextSegment` 60-72 | segmentul minim cu profit ≥ 0; plata (64), groapa (65), încasarea (66), tăietura (67), null (71) |
| `solveFast` 89-137 | soluția în două faze |
| linia 92 | PriorityQueue ordonată pe prag |
| liniile 93-96 | inițializare: primul segment al fiecărei clădiri |
| liniile 98-107 | bucla Fazei 1: peek/poll, încasare profit (100), înălțime persistentă (101), reîncărcare segment (105-106) |
| liniile 109-110 | mesajul de oprire „pragul minim ramas > bani" |
| liniile 116-117 | inițializarea recordului: bestHeight=0, bestIndex=1 (convenția pentru înălțimea 0) |
| liniile 118-135 | Faza 2: copia m2 (120), pornirea din built[i] (121), urcarea cu gardă `>=` (122-124), recordul strict (130-133) |
| `solveOracle` 144-178 | oracolul BFS |
| liniile 155-159 | banii recalculați din stare (invariantul T1) |
| liniile 161-167 | recordul + tie-break, implementate independent de fast |
| liniile 170-175 | expansiunea stărilor cu deduplicare `seen` |
| `f1ExempluDoi` 200-211 | firul F1 (exemplul 2 din enunț) |
| `f2ExempluUnu` 213-223 | firul F2 (exemplul 1 din enunț) |
| `f3TreiCladiri` 225-242 | firul F3 (x=11 și varianta x=5) |
| `f4TieBreakSiZero` 244-266 | firul F4 (tie, zero, câștigă indexul 2) |
| `f5PrefixPartial` 268-280 | firul F5 (clădirea editorialului, sold cu sold) |
| `f6Fuzz` 284-311 | firul F6: sămânța (286), 2000 de rulări (287), cutia de generare (290-302), comparația dublă (305) |

# Necunoscute și inferențe

- **Soluția în două faze și complexitatea O(Σm-i · log n) provin din editorialul oficial** (blog Codeforces 156058, autor pskobx). Corectitudinea a fost re-verificată local prin fuzz (F6: 2000 de inputuri, 0 nepotriviri), dar complexitatea NU a fost măsurată în laborator — rămâne inferență externă.
- **Convenția „înălțime 0 → index 1"** nu apare în enunț; e implementată identic în fast și oracol (deci fuzz-ul nu o poate contrazice) și coincide cu intenția juriului dedusă din comentariile editorialului — inferență, nu fapt verificat contra testelor oficiale.
- **Soluția nu a fost trimisă pe Codeforces** din acest laborator: echivalența cu verdictul oficial AC e neverificată direct; proba locală e oracolul, nu juriul.
- **Ordinea extragerii segmentelor cu praguri egale** din PriorityQueue nu e specificată de Java; rularea citată a extras clădirea 2 înaintea segmentului [2..3) al clădirii 1 (ambele prag 10). Banii finali și înălțimile nu depind de această ordine (T4-E3), dar trace-ul exact poate diferi între JVM-uri.
- **Mutațiile din F6-E3 au fost rulate pe copii în scratchpad**, nu pe fișierul din repo; numerele (46 și 507 nepotriviri) sunt din rularea 2026-08-22 cu sămânța 20260822.
- **Limitele t ≤ 3·10^4 seturi de date și citirea rapidă a inputului** (relevante pentru trimiterea reală pe Codeforces) nu sunt exersate de laborator — runner-ul construiește inputurile în memorie.
