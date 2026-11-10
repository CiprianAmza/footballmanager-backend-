# Quantum Loop Regulator — manual de explicație triplă

- **Kata**: „The Quantum Loop Regulator — Find the Loop Length" (Codewars, id `68eee8b2081f2b54db664608`, autor xkatianx, publicat 2025-10-15; 3 rezolvitori la data scrierii)
- **Repo**: `footballmanager-backend`, branch `master`, commit `8488435` (fișierele `docs/invatare/qlr/*` sunt noi, necomise încă)
- **Data**: 2026-08-22
- **Țintă**: să poți prezice, pas cu pas, cum o soluție găsește lungimea L ≤ 1000 a buclei în ≤ 100 de operații move/read/write și să justifici bilanțul de energie pe cazul cel mai rău, verificat pe o implementare de referință rulată local.

Documentul este un **manual generat**, nu o dovadă de învățare: niciun punct din planul de învățare nu este bifat prin existența acestui text.

## Enunțul, pe scurt

Un dispozitiv (QLR) are o buclă circulară de `L` noduri binare, `L` necunoscut, cel mult 1000. Pointerul pornește într-un nod arbitrar, neobservat. Ai trei comenzi — `move(x)` (x pozitiv sau negativ, oricât de mare), `read()`, `write(v)` — și **fiecare apel costă exact 1 energie**, dintr-un buget total `E = 100`. Trebuie să anunți exact `L`. Biții nodurilor au valori preexistente aleatoare. Enunțul precizează: „There are no hidden tests".

## Harta confirmată

Plan: `docs/invatare/2026-08-22-quantum-loop-regulator.md` (confirmat de utilizator în sesiunea din 2026-08-22).

**Tehnologii**: T1 interfața QLR · T2 aritmetică modulară · T3 divizori și lcm · T4 biți aleatori și fals pozitiv · T5 simulator local.
**Fire**: F1 metoda „trenului" · F2 proba de multiplu · F3 strategia de căutare · F4 bilanțul energetic · F5 validarea empirică.
**Perechi de separat**: P1 distanța săriturii vs costul ei · P2 potrivire de citire vs multiplu dovedit · P3 poziția pointerului vs valoarea nodului · P4 L real vs N candidat.

### Legendă

| Marcaj | Sens |
| --- | --- |
| T*n* / F*n* | punct de lucru: tehnologie / fir de execuție din hartă |
| P*n* | pereche de termeni cu risc de amestec (vocabularele se stabilesc separat) |
| E1 | traseul mecanic: declanșator → actor → apel → efect, cu ancore fișier:linie |
| E2 | modelul mental: motivul, invariantul, vocabularul |
| E3 | scenariul predictiv: un exemplu cu valori reale + o variație de eșec/limită |
| Q1 | întrebare orientativă **simulată** (nu a fost pusă de utilizator) |
| EQ | răspunsul explica la Q1 |

Codul de referință (scris și rulat pentru acest manual): `docs/invatare/qlr/qlr_sim.py` (simulatorul), `solve_naive.py` (metoda trenului), `solve.py` (soluția în buget), `validate.py` (validarea 1..1000), `trace_demo.py` (urme de execuție). Toate valorile numerice din manual provin din rulări reale ale acestor fișiere, nu din estimări.

---

# Capitolul T — Tehnologiile

## T1. Interfața QLR — trei comenzi, fiecare costă 1

### T1 · E1 — Traseul mecanic

Declanșatorul e întotdeauna solver-ul: el apelează una din cele trei metode ale dispozitivului. Filmul unui apel, pe simulatorul fidel enunțului:

1. `solve()` apelează `q.read()` la prima lui linie utilă ([solve.py:58](docs/invatare/qlr/solve.py)).
2. `read()` apelează întâi `self._spend()` ([qlr_sim.py:38-41](docs/invatare/qlr/qlr_sim.py)).
3. `_spend()` incrementează `self.energy` cu 1 și compară cu bugetul `E`; dacă `energy > E`, aruncă `EnergyExceeded` — echivalentul eșecului din kata ([qlr_sim.py:28-31](docs/invatare/qlr/qlr_sim.py)).
4. Abia apoi se produce efectul: `read` întoarce `tape[pos]` (qlr_sim.py:41); `write(v)` suprascrie `tape[pos] = v` (qlr_sim.py:46); `move(x)` mută `pos = (pos + x) % L` (qlr_sim.py:36).

Contractul de cost vine direct din enunț (secțiunea „The Energy Constraint"): *„Every single action (Move, Read, or Write) costs 1 unit of energy"* — costul lui `move(x)` nu depinde de `x`. În simulator asta se vede în faptul că `move` cheamă `_spend()` o singură dată, indiferent de argument (qlr_sim.py:33-36).

### T1 · E2 — Modelul mental

Dispozitivul e o cutie neagră cu o singură monedă de schimb: **apelul**. Nu vezi banda, nu vezi L, nu vezi nici măcar unde ești — orice aflii, aflii printr-un apel plătit. De aceea problema nu e „cum calculez L", ci „cum cumpăr cei ~10 biți de informație (1000 de candidați) cu 100 de monede".

Invariantul: `energy` = numărul de apeluri făcute, nimic altceva — nu distanța mutată, nu valoarea scrisă. Consecința strategică: o săritură de 55 de cifre e la fel de ieftină ca un pas de 1 (perechea P1: **distanța** săriturii ≠ **costul** ei).

Vocabular: **operație/acțiune** = un apel `move`/`read`/`write`; **buget E** = 100, plafonul total; **nod** = o celulă binară a buclei.

### T1 · E3 — Scenariul predictiv

Dispozitiv-jucărie: `QLR(L=6, bits=[1,0,0,1,1,0])`. Solver-ul apelează `move(70)`: costă 1 energie, pointerul ajunge pe nodul `70 % 6 = 4`. Apoi `move(-70)`: costă tot 1, pointerul revine pe nodul de start. Două operații, oricât de „lungi" au fost săriturile.

Variația de eșec: al 101-lea apel, oricare ar fi el, aruncă `EnergyExceeded` — verificat concret: metoda naivă pe `QLR(1000, E=100)` moare exact la operația 101, cu pointerul abia pe nodul 32 din 1000 (rulare reală, vezi F1).

### T1 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„Pointerul e pe nodul 3 într-o buclă cu L=6 și apelez `move(-9)`. Pe ce nod ajunge și cât costă?"

### T1 · EQ — Răspunsul

Ajunge pe nodul 0 și costă exact 1. Filmul: `move(-9)` → `_spend()` consumă 1 energie (qlr_sim.py:28-31) → `pos = (3 + (-9)) % 6 = (-6) % 6 = 0` (qlr_sim.py:36). Două lucruri separate s-au întâmplat într-un singur apel: contorul de energie a crescut cu 1 (indiferent de „distanța" 9), iar poziția s-a redus modulo L. În Python, `%` întoarce mereu un rest în `0..L-1`, deci și mutările negative aterizează pe un index valid de nod. [Inferență, neverificată: dispozitivul real de pe Codewars normalizează la fel mutările negative — enunțul permite explicit `x` negativ, dar nu arată implementarea.]

Predicție pe scenariul vecin: `move(-12)` din același nod 3 ar costa tot 1 și ar lăsa pointerul tot pe nodul 3 (−12 e multiplu de 6).

## T2. Aritmetică modulară — poziția pe cerc

### T2 · E1 — Traseul mecanic

Doi actori țin două numere diferite pentru „unde suntem":

1. **Solver-ul** ține `pos`, poziția *absolută* — suma tuturor argumentelor date lui `move` de la start ([solve.py:57](docs/invatare/qlr/solve.py): `pos = 0`, apoi actualizat la fiecare mutare, ex. solve.py:65: `q.move(N - pos); pos = N`).
2. **Dispozitivul** ține doar restul: la fiecare `move(x)` execută `self.pos = (self.pos + x) % self.L` (qlr_sim.py:36) — nu memorează niciodată mai mult de un număr din `0..L-1`.

Efectul compunerii: după `q.move(N - pos)`, nodul real e `(pos + (N - pos)) mod L = N mod L`. De aceea solver-ul poate „sări direct la poziția absolută N" dintr-un singur apel, oriunde s-ar afla.

### T2 · E2 — Modelul mental

Bucla e un ceas cu L ore. Poziția absolută e „câte ore am numărat de la start" (poate fi un număr de 55 de cifre); nodul real e „ce arată cadranul". Perechea P3, ținută separat: **poziția** (unde e pointerul pe cerc) e altceva decât **valoarea** (bitul scris în nodul respectiv) — un nod are amândouă.

Invariantul care poartă toată soluția: *două poziții absolute a și b cad pe același nod dacă și numai dacă L divide (a − b)*. Cazul particular folosit permanent: poziția absolută N cade pe nodul de start (nodul 0) exact atunci când **L divide N**. Asta transformă o întrebare despre geografie („unde am aterizat?") într-o întrebare despre aritmetică („divide L pe N?").

Vocabular: **poziție absolută** = contorul solver-ului; **nod** = celula fizică, indexată `0..L-1`; **mod L** = restul împărțirii la L.

### T2 · E3 — Scenariul predictiv

L=6: poziția absolută 70 cade pe nodul 4, pentru că 70 = 11·6 + 4. Poziția absolută 66 cade pe nodul 0 (66 = 11·6 + 0). Diferența 70 − 4 = 66 e multiplu de 6 — cele două poziții 70 și 4 sunt „același loc" pe cerc.

Variația-limită: același N = 70, dar pe un dispozitiv cu L=7: 70 = 10·7 + 0, deci nodul 0. Concluzia care face testul posibil: *aceeași săritură aterizează pe noduri diferite în funcție de L-ul ascuns* — răspunsul la „am aterizat pe 0?" e o informație despre L.

### T2 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„La primul test, solver-ul apelează `q.move(N - pos)` cu un N de 55 de cifre. Dispozitivul trebuie să țină minte o poziție de 55 de cifre?"

### T2 · EQ — Răspunsul

Nu — dispozitivul reduce imediat și uită numărul mare. La `move(x)`, singura lui stare de poziție devine `(pos + x) % L`, un întreg din `0..L-1` (qlr_sim.py:36); numărul de 55 de cifre nu supraviețuiește apelului. Cel care ține numere mari e solver-ul: variabila lui `pos` (solve.py:57, 65) memorează poziția absolută ca întreg Python nelimitat, ca să poată calcula sărituri exacte `N - pos` către următoarea țintă. Deci după testul 1: solver-ul știe `pos` = N (55 de cifre), dispozitivul știe doar `N mod L` (cel mult 3 cifre). [Fapt verificat în simulator; pentru dispozitivul real Codewars, suportul de întregi mari e presupus — vezi „Necunoscute".]

Predicție vecină: dacă solver-ul ar uita `pos`, n-ar mai putea reveni „la nodul 0" decât ghicind — toată contabilitatea absolută există ca săriturile să rămână exacte.

## T3. Divizori și lcm — 500 de candidați testați dintr-un foc

### T3 · E1 — Traseul mecanic

Actorul e `choose_test(cand)` ([solve.py:25-42](docs/invatare/qlr/solve.py)), apelat înaintea fiecărei probe. Filmul, cu numele reale:

1. Calculează ținta: `target = max(1, len(cand) // 2)` (solve.py:31) — „vreau ca aproximativ jumătate din candidați să răspundă DA".
2. Parcurge candidații descrescător (solve.py:33) și, pentru fiecare `d` care nu divide deja `N` (solve.py:34-35), încearcă `N2 = lcm(N, d)` (solve.py:36; `_lcm` la solve.py:21-22).
3. Numără câți candidați ar pica în tabăra DA: `yes2 = sum(1 for e in cand if N2 % e == 0)` (solve.py:37).
4. Acceptă extinderea doar dacă `yes2 <= target` (solve.py:38-39); altfel o aruncă și încearcă următorul `d`.
5. Fallback: dacă nimic nu a fost acceptat, `N = min(cand)` (solve.py:40-41), care separă garantat minimul de rest.

Rezultat măsurat pentru C = {1..1000}: primul N ales are **55 de cifre** și e divizat de **exact 500 din 1000** de candidați (rulare `trace_demo.py`).

### T3 · E2 — Modelul mental

Proba (F2) răspunde la o singură întrebare: „L divide N?". Deci un N împarte candidații în două tabere fixate dinainte: **divizorii lui N** (ar răspunde DA) și restul (ar răspunde NU). Testul nu „măsoară" nimic gradual — doar află în care tabără e L-ul adevărat.

lcm (cel mai mic multiplu comun) e unealta de a umple tabăra DA controlat: orice `d` băgat în lcm devine divizor al lui N. Iar tabăra DA primește și „pasageri clandestini": numere pe care nu le-ai adăugat explicit, dar care divid oricum lcm-ul rezultat.

Invariantul: taberele sunt complet determinate de N *înainte* de a cheltui vreo energie; energia cumpără doar un bit — care tabără e cea adevărată.

Vocabular: **divizor** (d divide N: N % d == 0); **multiplu** (N e multiplu de d — aceeași relație privită invers, perechea P4 cere să nu confunzi „N multiplu de L" cu „N = L"); **lcm(a,b)** = cel mai mic număr divizibil și cu a și cu b.

### T3 · E3 — Scenariul predictiv

Jucăria C = {1..10}, target = 5, derulată exact cum a rulat `choose_test`:

- ia d=10 → N=10, tabăra DA = {1,2,5,10} (4 ≤ 5, acceptat);
- încearcă d=9 → lcm=90, tabăra ar fi {1,2,3,5,6,9,10} (7 > 5, respins);
- d=8 → lcm=40, tabăra {1,2,4,5,8,10} (6 > 5, respins);
- d=7 → lcm=70, tabăra {1,2,5,7,10} (exact 5, acceptat);
- d=6 → lcm=210 (7 candidați, respins); d=4 → lcm=140 (6, respins); d=3 → respins; 5, 2, 1 divid deja.

Final: **N = 70** — exact primul test din urma reală a jucăriei (`trace_demo.py`, L=6, max_L=10). Variația-limită: pentru C = {3,9}, target = 1: d=9 e respins (și 3 și 9 divid 9 → tabăra ar avea 2), d=3 e acceptat → N=3 separă {3} de {9}. Verificat în rularea-jucărie cu L=9: testul 3 a fost chiar `N=3 → NU → rămâne {9}`.

### T3 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„La N=70, greedy-ul a acceptat doar d=10 și d=7. De ce tabăra DA are totuși 5 candidați, iar după un răspuns DA ar supraviețui și candidați pe care nu i-a ales nimeni?"

### T3 · EQ — Răspunsul

Pentru că filtrarea nu se uită la ce a *ales* greedy-ul, ci la ce *divide* N. Linia care taie candidații e `cand = [d for d in cand if (N % d == 0) == ans]` ([solve.py:75](docs/invatare/qlr/solve.py)) — criteriul e divizibilitatea, nu apartenența la S. 1, 2 și 5 divid 70 = 2·5·7 automat, deci stau în tabăra DA fără să fi fost adăugați: sunt pasagerii clandestini ai lcm-ului. Greedy-ul îi și contorizează corect: `yes2` numără toți divizorii din cand (solve.py:37), nu doar elementele acceptate — de aceea a respins d=9, care ar fi tras după el și 3 și 6.

Predicție vecină: după orice răspuns DA, mulțimea rămasă e închisă la divizibilitate în sensul că orice candidat rămas divide N-ul testat — un DA nu poate lăsa în viață un ne-divizor.

## T4. Biți aleatori — potrivirea nu dovedește, nepotrivirea infirmă

### T4 · E1 — Traseul mecanic

Ramificarea decisivă e la [solve.py:66-68](docs/invatare/qlr/solve.py). După săritura la poziția absolută N, solver-ul citește `v = q.read()` (solve.py:66) și compară cu `c`, bitul cunoscut al nodului 0:

1. `v != c` → `ans = False` pe loc (solve.py:67-68). Cert: nodul 0 are bitul `c`; am citit altceva, deci nu sunt pe nodul 0, deci L nu divide N. Cost: 2 operații.
2. `v == c` → nimic decis încă; intră pe ramura de confirmare (solve.py:69-74): înapoi la 0, inversează bitul nodului 0 (`c ^= 1; q.write(c)`), sare iar la N, recitește.

Cazul-capcană s-a produs chiar în jucăria reală (`trace_demo.py`, L=6, banda `[1,0,0,1,1,0]`): la testul 1 (N=70), pointerul a aterizat pe nodul 4, care avea *din fabrică* bitul 1 — identic cu `c`. Potrivire mincinoasă; confirmarea a dat NU, cost total 6.

### T4 · E2 — Modelul mental

Perechea P2, cu vocabularele stabilite separat. **Potrivirea**: „bitul citit == bitul nodului 0" — eveniment care se întâmplă și din pur noroc, fiindcă jumătate din noduri au, statistic, același bit ca nodul 0. **Dovada de multiplu**: „am demonstrat că nodul citit ESTE nodul 0". Maparea între ele e asimetrică: nepotrivirea e dovadă de ne-multiplu (certă), potrivirea nu e dovadă de nimic.

Confirmarea transformă norocul în certitudine printr-un fapt fizic: *numai nodul 0 se schimbă vreodată* — singurul `q.write` din tot solver-ul se execută cu pointerul pe nodul 0 (solve.py:70-72). Deci dacă a doua citire s-a schimbat odată cu nodul 0, sunt pe nodul 0; dacă nu s-a schimbat, nu sunt. Ambele sensuri certe.

Invariantul de contabilitate: după orice probă, `c` = bitul real curent al nodului 0 (solver-ul îl actualizează exact când îl scrie).

Vocabular: **fals pozitiv** = potrivire fără aterizare pe nodul 0; **toggle** = inversarea bitului (`c ^= 1`).

### T4 · E3 — Scenariul predictiv

Filmul confirmării pe jucărie, cu valori. Înainte: banda `[1,0,0,1,1,0]`, `c = 1` (citit la start), pointer pe poziția absolută 70 = nodul 4, `v = 1 = c` → suspect.

1. `move(-70)` → nodul 0.
2. `c` devine 0; `write(0)` → banda devine `[0,0,0,1,1,0]`.
3. `move(70)` → iar nodul 4.
4. `read()` → 1. Comparație: 1 ≠ 0 = c → nodul 4 NU s-a schimbat odată cu nodul 0 → NU e nodul 0 → L nu divide 70. Cert.

Variația: dacă bucla ar fi avut L=5, atunci 70 = 14·5, iar pasul 3 ar fi aterizat chiar pe nodul 0 proaspăt rescris; pasul 4 ar fi citit 0 = c → DA cert. Aceleași 6 operații decid în ambele lumi.

### T4 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„A doua citire nu poate fi și ea o coincidență? De ce nu e nevoie de a treia?"

### T4 · EQ — Răspunsul

Nu poate, pentru că a doua comparație nu mai întreabă „ce bit are nodul ăsta?", ci „s-a schimbat nodul ăsta când am schimbat nodul 0?" — iar la asta coincidența n-are acces. Biții nu se modifică singuri: singura cale de schimbare e `write` (qlr_sim.py:43-46), iar între cele două citiri solver-ul a scris exact o dată, pe nodul 0 (solve.py:70-72). Deci nodul citit fie e nodul 0 și arată obligatoriu noul bit, fie e alt nod și arată obligatoriu vechiul lui bit — nu există a treia posibilitate care să ceară a treia citire. Prima citire era vulnerabilă fiindcă compara cu o valoare pe care și alte noduri o puteau avea din fabrică; a doua compară cu un *eveniment* pe care doar nodul 0 l-a trăit.

Predicție vecină: dacă solver-ul ar mai scrie, din greșeală, și pe alt nod între cele două citiri, certitudinea s-ar pierde exact pentru testele care aterizează pe acel nod.

## T5. Simulatorul local — enunț complet, dispozitiv reconstruibil

### T5 · E1 — Traseul mecanic

Simulatorul ([qlr_sim.py:15-46](docs/invatare/qlr/qlr_sim.py)) implementează fidel contractul: constructor cu banda dată sau generată aleator din seed (qlr_sim.py:21), pointer pornit pe 0 (qlr_sim.py:24), contor `energy` cu plafon `E` (qlr_sim.py:28-31) și cele trei comenzi, fiecare trecând prin `_spend()`.

Consumatorul lui e `validate.py`: bucla de la [validate.py:14-28](docs/invatare/qlr/validate.py) construiește, pentru fiecare L din 1..1000, trei dispozitive — banda aleatoare cu `seed=L`, banda numai 0, banda numai 1 (validate.py:15-19) — cu bugetul real `E=100` (validate.py:21), rulează `solve` și verifică `assert got == L` (validate.py:24). Output-ul rulării reale din 2026-08-22:

```text
toate cele 1000 lungimi x 3 benzi: corect
energie maxima: 63 (L=48, banda=random)
numar maxim de teste: 11
limita garantata: 1 + 6*11 = 67 operatii
metoda naiva la L=1000: corecta, dar 3956 operatii (buget: 100)
```

### T5 · E2 — Modelul mental

De ce avem voie să ne construim dispozitivul acasă: enunțul declară „There are no hidden tests" și descrie complet comportamentul (trei comenzi, cost 1 fiecare, L ≤ 1000, E = 100). Un contract complet e o specificație executabilă — simulatorul e acea specificație, transformată în oracol: pe el măsori și corectitudinea (răspunsul e chiar L-ul construit) și bugetul (contorul de energie), înainte de orice submit.

Invariantul de onestitate: solver-ul nu are voie să atingă decât `q.move/q.read/q.write` — niciodată `q.L` sau `q.tape`, altfel validarea devine circulară. `solve()` respectă asta: singurele accesări ale lui `q` sunt cele trei metode (solve.py:58, 65-74).

Vocabular: **banda (tape)** = vectorul celor L biți; **seed** = sămânța generatorului aleator, fixată ca rularea să fie reproductibilă; **oracol** = mediul în care cunoști adevărul (L) și poți nota răspunsul solver-ului.

### T5 · E3 — Scenariul predictiv

Rulezi `python3 validate.py` în `docs/invatare/qlr/`: 3000 de rulări (1000 de lungimi × 3 benzi) trec în ~0,5 secunde, cu maximul de energie 63 atins la L=48 pe banda aleatoare.

Variația de eșec, verificată: același L=48 cu bugetul tăiat la `E=60` — `solve` aruncă `EnergyExceeded` la operația 61, înainte să termine testele. Plafonul din constructor e singurul parametru care desparte succesul de eșec; algoritmul nu știe de el și nu-și adaptează comportamentul.

### T5 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„Simulatorul pornește pointerul mereu pe indexul 0 al benzii, dar kata spune «pointer starts at an arbitrary, unobserved node». Nu testăm așa doar cazul ușor?"

### T5 · EQ — Răspunsul

Nu — „nodul 0" nu e o poziție privilegiată, e doar numele pe care sistemul de coordonate îl dă nodului de start, oriunde ar fi el fizic. Solver-ul nu vede niciodată indexul benzii: tot ce primește sunt biți întorși de `read()`, iar toate săriturile lui sunt relative la punctul de pornire (contorul `pos` pornește de la 0 prin definiție — solve.py:57). Un dispozitiv „cu start arbitrar k" e indistingibil de un dispozitiv cu start 0 și banda rotită cu k poziții — iar benzile aleatoare cu 1000 de seed-uri diferite acoperă exact astfel de rotații: distribuția biților văzuți de solver e aceeași. [Fapt pentru simulator, verificat prin construcție; pentru dispozitivul Codewars, echivalența e aceeași prin simetria enunțului.]

Predicție vecină: dacă ai roti manual banda `[1,0,0,1,1,0]` în `[0,1,0,0,1,1]`, urma de execuție ar diferi (alți biți întâlniți), dar verdictul și plafonul de cost per test ar rămâne identice.

---

# Capitolul F — Firele de execuție

## F1. Metoda „trenului" — corectă, dar de 40 de ori peste buget

### F1 · E1 — Traseul mecanic

Filmul complet al lui `solve_naive` ([solve_naive.py:9-21](docs/invatare/qlr/solve_naive.py)):

1. Scrie marcajul: `q.write(1)` pe nodul de start (solve_naive.py:10).
2. Merge pas cu pas: `q.move(1)` + `q.read()`, incrementând `steps` (solve_naive.py:13-15).
3. La fiecare 1 citit (solve_naive.py:15): îl șterge cu `q.write(0)` (17), sare înapoi la start cu `q.move(-steps)` (18) — o singură operație, oricât de departe — și citește startul (19).
4. Dacă startul e 0, 1-ul șters era chiar marcajul: a închis cercul, `return steps` (20). Dacă startul e tot 1, 1-ul șters era preexistent: `q.move(steps)` (21) și continuă plimbarea.

Măsurat: `QLR(1000, E=10**9, seed=7)` → răspuns corect 1000, dar **3956 de operații** (validate.py:34-38) — de aproape 40 de ori bugetul kata-ului.

### F1 · E2 — Modelul mental

Problema pe care o rezolvă metoda e cea de *identitate*: un 1 citit nu spune al cui e — al tău sau al fabricii (exact falsul pozitiv din T4, în altă haină). Rezolvarea ei aici e distructivă: ștergi 1-ul și te uiți dacă marcajul tău a dispărut.

De ce moare bugetul, deși săritura înapoi costă 1: plimbarea *înainte* nu poate sări nimic. Metoda numără nodurile călcând pe fiecare — minimum L mutări și L citiri, adică ≥ 2L operații doar drumul, plus 4 operații pentru fiecare 1 preexistent întâlnit. La L=1000 asta înseamnă peste 2000 de operații pentru un buget de 100.

Invariantul care o face totuși corectă: în momentul lui `return`, `steps` = exact numărul de noduri al cercului, pentru că marcajul a fost regăsit doar după un tur complet.

Vocabular: **marcaj** = bitul 1 scris de noi la start ca semn de recunoaștere.

### F1 · E3 — Scenariul predictiv

Jucăria `QLR(6, bits=[1,0,0,1,1,0])`: metoda găsește 1 preexistent la pașii 3 și 4 (fiecare declanșează verificarea de 4 operații: șters, sărit înapoi, citit, revenit), apoi marcajul la pasul 6. Total măsurat: **24 de operații** pentru L=6 — deja 4 pe nod.

Variația-limită, verificată pe banda fără 1-uri preexistente (toți biții 0, cazul cel mai ieftin posibil): la L=48 metoda termină cu exact 100 de operații — fix pe buget; la L=49 aruncă `EnergyExceeded` la operația 101. Deci chiar în cel mai bun caz al ei, metoda trenului acoperă doar ~5% din intervalul cerut de kata (L până la 1000).

### F1 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„`move(-steps)` costă 1 indiferent de distanță — atunci de ce nu sare metoda și *înainte* din 10 în 10, ca să nu calce pe fiecare nod?"

### F1 · EQ — Răspunsul

Pentru că metoda își recunoaște marcajul doar *citindu-l*, iar o săritură peste noduri poate sări chiar peste el. Dacă din nodul 40 sari direct pe nodul 50 într-o buclă cu L=45, marcajul de pe nodul de start (= nodul 45 absolut) rămâne în porțiunea nevizitată: citirea de pe 50 vede un bit oarecare, iar contorul `steps` nu mai corespunde niciunui tur complet — metoda n-are cum să afle că cercul s-a închis între două opriri. Numărarea prin vizitare cere vizitarea fiecărui nod; asta e limita de principiu a întregii familii „plimbă-te și caută semnul", nu un defect de implementare (solve_naive.py:13-15 exprimă exact această plimbare obligatorie).

Soluția din buget (F2) schimbă întrebarea, nu pasul: în loc să caute un semn mergând, întreabă aritmetic „L divide N?" cu sărituri oricât de mari — de aceea scapă de Ω(L).

Predicție vecină: pe banda toți-0, costul naivei e exact 2L + 4; formula prezice 100 la L=48 și 102 (peste buget) la L=49 — ambele confirmate în simulator.

## F2. Proba de multiplu — 6 operații pentru un răspuns cert

### F2 · E1 — Traseul mecanic

O probă completă, așa cum rulează în bucla lui `solve` ([solve.py:64-74](docs/invatare/qlr/solve.py)), pe date reale din rularea L=48:

**Ramura ieftină** (testul 6, N=1.648.380): `q.move(N - pos)` (solve.py:65) → `v = q.read()` (66) → v ≠ c → `ans = False` (67-68). **2 operații**, verdict cert NU.

**Ramura cu confirmare** (testul 11, N=48): `move` + `read` → v == c → nu decide → `q.move(-pos)` înapoi pe nodul 0 (70) → `c ^= 1; q.write(c)` — toggle (71-72) → `q.move(N)` (73) → `ans = (q.read() == c)` (74) → aici a citit noul bit → **DA cert**, 6 operații.

După ambele ramuri, linia 75 filtrează candidații cu verdictul obținut.

### F2 · E2 — Modelul mental

Proba e un dialog în două replici cu dispozitivul: prima citire e *filtrul* (norocul poate doar să te coste, nu să te mintă — o nepotrivire încheie discuția cu NU cert), a doua citire, după toggle, e *judecata* (T4 a stabilit de ce e certă în ambele sensuri). Prețul: 2 sau 6, niciodată altceva.

Invariantul de stare, care face probele înlănțuibile: la ieșirea din orice probă, solver-ul cunoaște exact atât bitul curent al nodului 0 (`c`, actualizat sincron cu singurul `write` — solve.py:71-72), cât și poziția absolută (`pos`, actualizat la fiecare `move` — solve.py:65, 70, 73). Nicio probă nu lasă mizerie în urmă pentru următoarea.

Vocabular: **proba rapidă** = prima pereche move+read; **confirmarea** = cvartetul move+write+move+read declanșat doar la potrivire.

### F2 · E3 — Scenariul predictiv

Rularea reală L=12: toate cele 10 teste au căzut pe ramura cu confirmare (cost 6 fiecare) — energie totală 1 + 10·6 = 61. Testul 1: N-ul de 55 de cifre e divizibil cu 12, deci prima citire a aterizat *chiar pe nodul 0*: potrivirea era autentică, iar confirmarea a validat-o (DA). Testul 5 (N ≈ 10²²): prima citire s-a potrivit *din noroc* — confirmarea a demascat falsul pozitiv (NU), tot 6 operații.

Variația de graniță: la L=48, testul 6 e singura probă din toate cele 11 unde prima citire a nimerit diferit de `c` — singurul verdict la preț redus (2). De aici energia 63 = 1 + 10·6 + 2, maximul observat pe toate cele 3000 de rulări.

### F2 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„După confirmarea de la testul 1, nodul 0 are alt bit decât la început. Testul 2 cu ce valoare compară — cu bitul citit la operația 1 sau cu altceva?"

### F2 · EQ — Răspunsul

Cu `c` actualizat — adică mereu cu bitul *curent* al nodului 0, nu cu cel istoric. Filmul tranziției: în confirmare, solver-ul întâi inversează variabila (`c ^= 1`, solve.py:71) și abia apoi scrie noua valoare pe nod (`q.write(c)`, solve.py:72) — variabila și nodul se schimbă în același gest, deci nu există fereastră în care să difere. Testul 2 începe cu `v = q.read()` comparat cu acest `c` nou (solve.py:66-67), și corect așa: nodul 0 chiar are acum bitul inversat. Bitul citit la operația 1 (`c` inițial) a murit ca referință în clipa primului toggle.

Perechea de ținut separat aici e chiar P3: *valoarea* nodului 0 se schimbă din test în test, dar *identitatea* lui (poziția absolută 0, ținta lui `q.move(-pos)`) rămâne fixă — solver-ul se întoarce mereu la același nod, oricâte biți i-ar fi rescris.

Predicție vecină: după k confirmări, bitul nodului 0 = bitul inițial XOR (k mod 2) — la L=12, după cele 10 confirmări, nodul 0 a revenit la bitul lui de la început.

## F3. Strategia de căutare — înjumătățiri certe până rămâne L

### F3 · E1 — Traseul mecanic

Bucla principală a lui `solve` ([solve.py:55-78](docs/invatare/qlr/solve.py)):

1. Pornire: `c = q.read()` — bitul nodului 0, singura operație din afara probelor (solve.py:58); `cand = list(range(1, 1001))` (59).
2. Cât timp `len(cand) > 1` (61): `choose_test_cached` alege N-ul care taie cand în două (62; T3), proba de multiplu obține verdictul cert (64-74; F2), filtrul păstrează tabăra adevărată: `cand = [d for d in cand if (N % d == 0) == ans]` (75).
3. La un singur candidat rămas, `return cand[0]` (78).

Rularea reală L=12 (max_L=1000, `trace_demo.py`): candidații scad 1000 → 500 → 250 → 125 → 59 → 30 → 15 → 8 → 3 → 2 → 1 în **10 teste**, energie totală 61. Primele patru verdicte au fost DA (L=12 divide toate lcm-urile uriașe alese), apoi alternanță până la testul final N=12 → DA → `[12]`.

### F3 · E2 — Modelul mental

E o căutare binară, dar nu pe un interval ordonat, ci pe o mulțime structurată de divizibilitate: „jumătățile" unui test sunt tabăra divizorilor lui N și restul (T3), iar comparația e proba certă (F2). De aceea nu există „aproape" sau backtracking — fiecare verdict e un fapt despre L, iar mulțimea se strânge monoton.

Invariantul de corectitudine (soundness): **L nu părăsește niciodată cand**. Motivul, într-o propoziție: verdictul `ans` este, prin construcția probei, chiar valoarea de adevăr a lui „N % L == 0", iar filtrul păstrează exact candidații `d` pentru care „N % d == 0" are aceeași valoare — deci L trece testul filtrului de fiecare dată.

Invariantul de progres: fiecare test elimină cel puțin un candidat — greedy-ul nu alege niciodată un N cu tabăra DA vidă sau totală (la limită, fallback-ul `N = min(cand)` separă garantat minimul de rest, solve.py:40-41) — deci bucla se termină.

Vocabular: **eliminare** = scoaterea candidaților inconsistenți cu verdictul; **consistență** = candidatul ar fi produs același verdict ca L.

### F3 · E3 — Scenariul predictiv

Jucăria completă, cu before/after la fiecare pas (rulări reale). Dispozitiv `L=6, bits=[1,0,0,1,1,0]`, candidați {1..10}:

| Test | N | Verdict | cand după |
| --- | --- | --- | --- |
| 1 | 70 | NU | {3, 4, 6, 8, 9} |
| 2 | 9 | NU | {4, 6, 8} |
| 3 | 6 | DA | {6} |

Total: 3 teste, 15 operații. Variația predictivă: același start, dar dispozitivul are L=9 (banda `[1,0,0,1,1,0,0,1,0]`): testul 1 e identic ca alegere (N=70) și dă tot NU, dar apoi drumul se desparte — N=9 → DA → {3, 9}, N=3 → NU → {9}. Verificat: 19 operații. Aceleași reguli, alt L, alt traseu prin arbore.

### F3 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„Există vreun scenariu în care cand rămâne gol — de exemplu un verdict DA la un N pe care niciun candidat rămas nu-l divide?"

### F3 · EQ — Răspunsul

Nu — mulțimea nu poate rămâne goală, pentru că cel puțin un locuitor are pașaport garantat: L însuși. Argumentul, pe pași: (1) la start, L e în {1..1000} = cand, fiindcă enunțul plafonează L la 1000; (2) la fiecare filtrare, condiția de supraviețuire pentru d este `(N % d == 0) == ans` (solve.py:75), iar `ans` e chiar valoarea lui `N % L == 0` — probă certă, demonstrată la T4/F2; substituind d = L, condiția devine o identitate, deci L supraviețuiește; (3) prin inducție, L e în cand la fiecare pas, deci și în singleton-ul final. Scenariul din întrebare — „DA, dar nimeni nu divide N" — ar cere ca nici L să nu dividă N, în contradicție cu verdictul DA cert.

Garanția e condiționată de un singur lucru: certitudinea probei. Dacă proba ar fi doar probabilistă (fără confirmarea cu toggle), un verdict greșit l-ar elimina pe L definitiv, iar bucla ar converge senin către un răspuns fals — de aceea kata nu se poate rezolva robust doar cu potriviri norocoase.

Predicție vecină: `assert got == L` din validate.py:24 nu a picat în niciuna din cele 3000 de rulări — exact ce prezice invariantul.

## F4. Bilanțul energetic — 67 garantat, 63 măsurat

### F4 · E1 — Traseul mecanic

Contabilitatea, citită direct de pe cod:

1. **1 operație** o singură dată: citirea inițială a nodului 0 (solve.py:58).
2. **Per test, ramura ieftină**: `move` + `read` = 2 operații (solve.py:65-66).
3. **Per test, ramura cu confirmare**: `move` + `read` + `move` + `write` + `move` + `read` = 6 operații (solve.py:65-74).
4. **Numărul de teste**: măsurat pe toate cele 1000 de lungimi — maximum **11** (output `validate.py`).

Plafonul rezultat: 1 + 6·11 = **67 operații**, sub bugetul de 100 cu marjă de o treime. Maximul efectiv observat: **63**, la L=48 pe banda aleatoare.

### F4 · E2 — Modelul mental

De ce e plafonul o garanție și nu o statistică — argumentul are două piese, fiecare legată de altă variabilă:

1. **Numărul de teste depinde doar de L, nu de bandă.** Verdictele sunt fapte de divizibilitate („L divide N?"), deci șirul verdictelor — și prin el șirul mulțimilor cand și al N-urilor alese — e complet determinat de L. Arborele de decizie e fix; banda nu poate lungi drumul prin el.
2. **Costul unui test e mărginit structural de 6.** Banda decide doar pe ce ramură cazi (2 sau 6); nu există a treia ramură, iar confirmarea are lungime fixă de 4 operații suplimentare.

Împreună: energie ≤ 1 + 6·tests(L) ≤ 1 + 6·11 = 67, **pentru orice bandă**, inclusiv una aleasă cu rea-voință. Diferența față de F5: acolo 63 e un fapt *măsurat* pe benzile încercate; aici 67 e o *teoremă* despre toate benzile.

Vocabular: **plafon (bound)** = limita superioară demonstrată; **caz defavorabil (worst case)** = combinația L + bandă care maximizează costul.

### F4 · E3 — Scenariul predictiv

Cazul maximului real, L=48: 11 teste, dintre care exact unul (testul 6) a picat pe ramura ieftină — energie 1 + 10·6 + 1·2 = 63. Cazul L=12: 10 teste, toate cu confirmare — 1 + 10·6 = 61.

Variația-limită: distanța dintre măsurat și garantat. Pentru L=48, plafonul spune 1 + 11·6 = 67; realitatea a dat 63, pentru că o potrivire a lipsit din noroc. O bandă în care *fiecare* primă citire s-ar potrivi cu `c` ar împinge L=48 exact la 67 — tot sub 100. Nicio bandă nu poate trece de 67 fără să schimbe numărul de teste, iar numărul de teste nu-l poate schimba decât L.

### F4 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„Verdictul NU se poate obține și pe ramura ieftină (2 operații) și pe cea scumpă (6). De ce același răspuns are două prețuri, și cine alege prețul?"

### F4 · EQ — Răspunsul

Prețul îl alege banda, nu verdictul — cele două ramuri se despart la o comparație care nu știe încă răspunsul. Filmul: prima citire `v` (solve.py:66) compară bitul nodului de aterizare cu `c`. Dacă diferă, verdictul NU e deja demonstrat — 2 operații, gata (solve.py:67-68). Dacă acele două noduri *diferite* au din fabrică același bit — eveniment de bandă, cu șansă ~1/2 — solver-ul nu poate deosebi situația de o aterizare reală pe nodul 0, așa că plătește confirmarea întreagă (4 operații în plus) ca să afle același NU (solve.py:69-74). Exemplu real: la L=48, verdictul NU a costat 2 la testul 6 și 6 la testele 5, 7, 8, 9, 10 — același răspuns, prețuri diferite, fiindcă biții de aterizare au nimerit altfel.

De aici forma corectă a garanției: nu „NU costă 2 și DA costă 6", ci „orice verdict costă cel mult 6" — DA costă întotdeauna 6 (potrivirea autentică trece obligatoriu prin confirmare), NU costă 2 sau 6 după noroc.

Predicție vecină: pe banda toți-0 și toți-1, primele citiri se potrivesc des cu `c`, deci NU-urile ieftine devin rare — energia urcă spre plafon, dar măsurătorile arată că nu l-a atins nimeni: maximul global rămâne 63.

## F5. Validarea empirică — de la măsurat la garantat

### F5 · E1 — Traseul mecanic

`validate.py`, rulat cu `python3 validate.py` din `docs/invatare/qlr/`:

1. Bucla exterioară ia fiecare L din 1..1000 (validate.py:14).
2. Pentru fiecare L construiește trei dispozitive cu bugetul real E=100 (validate.py:15-21): banda aleatoare (seed=L, reproductibil), banda toți-0, banda toți-1.
3. Rulează `solve` și oprește totul la prima nepotrivire: `assert got == L` (validate.py:23-24).
4. Agregă maximele: energia (25-27) și numărul de teste (28), apoi le tipărește împreună cu plafonul derivat (29-32).
5. Contrastul final: metoda naivă pe L=1000 — corectă, dar 3956 de operații (validate.py:34-38).

Rularea din 2026-08-22 a trecut integral: 3000/3000 corecte, max energie 63, max teste 11, în ~0,5 secunde.

### F5 · E2 — Modelul mental

Sweep-ul e o demonstrație prin epuizare, dar doar pe o axă: axa lui L e acoperită *complet* (toate cele 1000 de valori posibile — nu există alt L de testat), axa benzilor e doar *eșantionată* (3 benzi din cele 2^L posibile per lungime). Golul dintre eșantion și totalitate nu-l închide mai multă rulare, ci teorema din F4: numărul de teste nu depinde de bandă, costul per test e ≤ 6 — deci ce am măsurat pe 3 benzi mărginește, prin plafon, toate benzile.

Etichetarea epistemică a celor trei afirmații finale, ținute separat: „max 63 / 11 teste" = **fapt măsurat**; „≤ 67 pentru orice bandă" = **teoremă derivată** din fapte + structura codului; „soluția trece kata-ul real" = **inferență** — presupune că dispozitivul Codewars respectă întocmai enunțul (cost 1 per acțiune, L ≤ 1000, întregi mari acceptate de move).

Vocabular: **sweep** = parcurgerea exhaustivă a unui parametru; **eșantionare** = testarea câtorva cazuri dintr-un spațiu prea mare.

### F5 · E3 — Scenariul predictiv

Rulare la zi: output-ul citat integral la T5·E1 — 3000 de rulări corecte, plafonul tipărit chiar de script (`limita garantata: 1 + 6*11 = 67 operatii`).

Variația de eșec, verificată: strângi bugetul simulat la `QLR(48, E=60, seed=48)` — `solve` moare cu `EnergyExceeded` la operația 61. Aceeași soluție, același L, aceeași bandă; singurul lucru schimbat e plafonul dispozitivului. Morala operațională: verificarea de buget aparține dispozitivului (`_spend`, qlr_sim.py:28-31), nu solver-ului — solver-ul nu numără și nu se grăbește, el doar are, demonstrabil, nevoie de puțin.

### F5 · Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

„Dacă tot e eșantionare pe axa benzilor, de ce doar 3 benzi per L și nu 100 de seed-uri, ca să fim mai siguri?"

### F5 · EQ — Răspunsul

Pentru că siguranța nu vine de la numărul de seed-uri, ci de la ce anume poate influența banda — iar banda nu poate influența corectitudinea, doar costul. Verdictele probelor sunt certe indiferent de biți (T4), deci un răspuns greșit nu poate apărea pe nicio bandă decât printr-un bug de implementare; pentru bug-uri, cele trei benzi alese lovesc regimurile calitativ diferite (biți amestecați, potriviri maxime pe 0, potriviri maxime pe 1). Iar pentru cost, plafonul 67 din F4 acoperă *toate* benzile dintr-o dată — al 100-lea seed n-ar putea decât să mute maximul observat cu câteva unități între 61 și 67, fără să atingă vreodată bugetul de 100. Rularea rămâne astfel ieftină (0,5 s), reproductibilă (seed=L fix) și completă acolo unde completitudinea e posibilă: pe axa lui L.

Predicție vecină: dublarea numărului de seed-uri ar lăsa neschimbate toate cele trei rânduri de „corect" din output și ar putea urca „energie maximă" cel mult până la 67.

---

# Glosar

| Termen | Definiție |
| --- | --- |
| QLR | Quantum Loop Regulator — dispozitivul fictiv al kata-ului: buclă circulară de L noduri binare cu pointer |
| L | lungimea buclei (numărul de noduri), necunoscută, 1 ≤ L ≤ 1000 — răspunsul cerut |
| E | bugetul total de energie: 100; fiecare move/read/write costă exact 1 |
| operație / acțiune | un apel move(x), read() sau write(v) |
| nod | o celulă binară a buclei; are o identitate (poziția pe cerc) și o valoare (bitul) |
| banda (tape) | vectorul celor L biți, cu valori preexistente aleatoare |
| poziție absolută | contorul solver-ului: suma tuturor argumentelor date lui move de la start |
| nodul 0 | nodul de start — numele dat de sistemul de coordonate al solver-ului punctului de pornire |
| c | variabila solver-ului care oglindește bitul curent al nodului 0 |
| marcaj | bitul 1 scris de metoda naivă la start ca semn de recunoaștere |
| proba de multiplu | testul cert „L divide N?": move+read (2 op) sau move+read+move+write+move+read (6 op) |
| proba rapidă / confirmarea | cele două ramuri ale probei: prima pereche move+read, respectiv cvartetul cu toggle |
| toggle | inversarea bitului nodului 0 (c ^= 1 urmat de write) |
| fals pozitiv | potrivire de citire fără aterizare reală pe nodul 0 |
| divizor / multiplu | d divide N (N % d == 0); N e multiplu de d — aceeași relație, privită invers |
| lcm | cel mai mic multiplu comun; unealta cu care choose_test umple tabăra DA |
| tabăra DA / NU | divizorii lui N din cand, respectiv restul — cele două jumătăți ale unui test |
| cand | mulțimea candidaților încă posibili pentru L; pornește {1..1000}, se termină {L} |
| pasager clandestin | candidat care divide lcm-ul fără să fi fost adăugat explicit de greedy |
| eliminare / consistență | scoaterea candidaților al căror verdict ipotetic diferă de cel observat |
| plafon (bound) | limita superioară demonstrată a energiei: 1 + 6·11 = 67 |
| sweep / eșantionare | parcurgere exhaustivă a unui parametru / testarea câtorva cazuri dintr-un spațiu prea mare |
| seed | sămânța generatorului aleator; seed=L face fiecare rulare reproductibilă |
| oracol | mediul de test în care adevărul (L) e cunoscut și răspunsul poate fi judecat |
| EnergyExceeded | excepția simulatorului la depășirea bugetului — echivalentul eșecului din kata |

# Index de ancore în cod

| Ancoră | Ce demonstrează | Folosită la |
| --- | --- | --- |
| qlr_sim.py:28-31 | `_spend`: +1 energie per apel, excepție peste E | T1, T5, F5 |
| qlr_sim.py:33-36 | `move`: cost 1 indiferent de x; reducerea `(pos+x) % L` | T1, T2 |
| qlr_sim.py:38-41 | `read`: cost 1, întoarce `tape[pos]` | T1 |
| qlr_sim.py:43-46 | `write`: cost 1, suprascrie `tape[pos]` | T1, T4 |
| qlr_sim.py:21-24 | constructor: banda din seed sau dată; pointer pe 0 | T5 |
| solve.py:21-22 | `_lcm(a, b)` | T3 |
| solve.py:25-42 | `choose_test`: greedy descrescător cu target = jumătate | T3, F3 |
| solve.py:31 | `target = max(1, len(cand) // 2)` | T3 |
| solve.py:37 | numărarea taberei DA (`yes2`) | T3 |
| solve.py:40-41 | fallback `N = min(cand)` — progres garantat | T3, F3 |
| solve.py:57 | `pos = 0` — contorul absolut al solver-ului | T2, T5 |
| solve.py:58 | citirea inițială a nodului 0 (singura operație din afara probelor) | F3, F4 |
| solve.py:59 | `cand = {1..1000}` | F3 |
| solve.py:65-66 | proba rapidă: move + read = 2 operații | F2, F4 |
| solve.py:66-68 | ramificarea `v != c` → NU cert | T4, F2 |
| solve.py:69-74 | confirmarea: move + write + move + read (încă 4 operații) | T4, F2, F4 |
| solve.py:71-72 | toggle sincron: `c ^= 1` apoi `write(c)` | T4, F2 |
| solve.py:75 | filtrul candidaților: `(N % d == 0) == ans` | T3, F3 |
| solve.py:78 | `return cand[0]` | F3 |
| solve_naive.py:10 | scrierea marcajului | F1 |
| solve_naive.py:13-15 | plimbarea obligatorie nod-cu-nod | F1 |
| solve_naive.py:17-21 | verificarea distructivă a fiecărui 1 găsit | F1 |
| validate.py:14-28 | sweep 1..1000 × 3 benzi, assert corectitudine, agregare maxime | T5, F5 |
| validate.py:34-38 | contrastul naiv: 3956 operații la L=1000 | F1, F5 |

# Necunoscute și inferențe

Fapte stabilite prin rulare (2026-08-22, macOS, Python 3): corectitudine 3000/3000; energie maximă observată 63 (L=48, banda aleatoare); maximum 11 teste; plafonul derivat 67; naiva 3956 operații la L=1000, moare la operația 101 cu E=100; granițele naivei pe banda toți-0: 100 operații la L=48, depășire la L=49.

Rămân în afara dovezii:

1. **Dispozitivul real Codewars** — implementarea lui nu e vizibilă în API. Inferențe nedovedite: că `move` acceptă întregi Python nelimitați (N-urile de până la 55 de cifre); că mutările negative se normalizează ca în simulator; că bugetul se contorizează identic. Enunțul le susține („x pozitiv sau negativ", „every action costs 1"), dar nu le demonstrează.
2. **Generarea testelor pe Codewars** (ce L-uri, ce benzi, câte rulări) — Necunoscut din enunț; garanția noastră acoperă orice L ≤ 1000 și orice bandă, deci nu depinde de răspuns.
3. **Limita inferioară a problemei** — nu am demonstrat că 67 e aproape de optim; există probabil soluții cu mai puține operații (de exemplu alegeri de N optimizate pe costul asimetric 2/6). Manualul documentează o soluție suficientă, nu pe cea minimă.
4. **Intervalul exact al lui L** — enunțul dă doar plafonul („at most 1000"); am presupus L ≥ 1. Dacă dispozitivul real garantează L ≥ 2, soluția rămâne corectă (candidatul 1 e doar eliminat pe parcurs).

Necunoscuta din harta inițială — „strategia care încape în E=100 nu e demonstrată" — a fost închisă în timpul lucrului: plafonul 67 < 100 e demonstrat pentru orice bandă, condiționat de fidelitatea simulatorului (punctul 1 de mai sus).

---

*Document generat complet la 2026-08-22 prin skill-ul explicatie-tripla-pdf. Bilanț: 10 puncte de lucru (T1-T5, F1-F5) × (E1+E2+E3+Q1+EQ) = 30 explicații principale, 10 întrebări orientative, 10 răspunsuri — 40 de aplicări explica. Documentul atestă generarea materialului, nu învățarea lui.*
