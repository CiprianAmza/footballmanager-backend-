# Faberge easter eggs crush test [linear] — manual de explicație triplă

- **Kata**: [Codewars 1 kyu, id 5976c5a5cd933a7bbd000029](https://www.codewars.com/kata/5976c5a5cd933a7bbd000029), autor ice1000
- **Repo**: footballmanager-backend, branch `master`, commit `8488435` + fișier nou necomis `src/test/java/com/footballmanagergamesimulator/algorithms/dp/FabergeEggs.java` (soluția-ancoră, verificată prin rulare pe 2026-08-22)
- **Data**: 2026-08-22
- **Țintă**: pentru orice pereche (n ouă, m încercări) să poți prezice formula înălțimii maxime, să scrii soluția care trece ambele regimuri de date sub mod 998244353 și să estimezi costul calculului.

**Notă de statut**: acest document este generat complet, nu parcurs. Niciun criteriu de trecere din harta de învățare nu este bifat; bifarea cere predicțiile tale, în sesiuni de lucru.

## Harta confirmată

Sursa: `docs/invatare/2026-08-22-faberge-crush-test.md` (confirmată de utilizator pe 2026-08-22, fără tăieri).

- T1. Recurența egg-drop — T2. Coeficienți binomiali — T3. Aritmetică modulară — T4. Invers modular — T5. Tabele factorial/invers — T6. Termen incremental — T7. BigInteger în Java
- F1. height(2,14)=105 din recurență — F2. Regimul n≤80000, m≤100000 — F3. Regimul m≤2^200 — F4. De ce naivul pică
- P1. n vs m în binom — P2. exact vs mod p — P3. împărțire vs ×invers — P4. liniar în m vs liniar în n

## Legendă

- **Tn** = tehnologie/concept de pe fir; **Fn** = fir de execuție (declanșator → efect); **Pn** = pereche cu risc de amestec, vocabularele se definesc separat înainte de mapare.
- Fiecare punct de lucru are exact cinci unități: **E1** traseul mecanic (declanșator → actor → apel → efect, cu ancore fișier:linie), **E2** modelul mental (motiv, invariant, vocabular), **E3** scenariul predictiv (valori concrete + o variație de eșec/limită), **Q1** întrebarea orientativă (simulată, nu pusă de utilizator), **EQ** răspunsul explica la Q1.
- Ancorele `FabergeEggs.java:NN` trimit la `src/test/java/com/footballmanagergamesimulator/algorithms/dp/FabergeEggs.java`. Afirmațiile fără ancoră și fără etichetă provin din enunțul kata-ului; inferențele sunt etichetate explicit.

# Partea I — Tehnologii

## T1. Recurența egg-drop (DP)

### T1·E1 — Traseul mecanic

Apelul `FabergeEggs.heightByRecurrence(2, 14)` întoarce 105 printr-o umplere de tablou, fără nicio formulă.

1. Metoda (`FabergeEggs.java:31`) alocă tabloul `f` de 3×15 (`eggs+1` × `tries+1`); Java îl umple cu 0, deci rândul `e=0` (zero ouă) și coloana `t=0` (zero încercări) rămân 0 — cu zero resurse distingi zero etaje.
2. Bucla exterioară parcurge `e = 1..2`, cea interioară `t = 1..14`; pentru fiecare celulă rulează exact o adunare: `f[e][t] = f[e-1][t-1] + f[e][t-1] + 1` (`FabergeEggs.java:35`).
3. La final metoda întoarce colțul `f[2][14]` = 105.

[OK] verificat prin rulare (2026-08-22): `heightByRecurrence(2,14)=105`, `heightByRecurrence(7,20)=137979` — exact exemplele din enunțul kata-ului.

### T1·E2 — Modelul mental

De ce are celula forma asta: o aruncare de la un etaj ales împarte lumea în două continuări, iar tabloul adună ce distingi în fiecare.

- Oul **crapă** → etajul critic e sub etajul aruncării → continui cu un ou mai puțin și o încercare mai puțin: partea de jos poate avea cel mult `f[e-1][t-1]` etaje.
- Oul **rezistă** → etajul critic e deasupra → continui cu aceleași ouă (enunț: un ou care nu crapă se re-aruncă) și o încercare mai puțin: partea de sus poate avea `f[e][t-1]` etaje.
- `+1` este chiar etajul aruncării — el se clasifică singur prin rezultat.

Invariantul tabloului: `f[e][t]` = numărul maxim de etaje pentru care găsești **garantat** etajul critic. „Garantat" înseamnă cazul cel mai rău: nu tu alegi rezultatul aruncării, ci un adversar imaginar care alege mereu continuarea care te încurcă mai tare.

*Regula generală: o celulă DP nu memorează „ce faci", ci „cât poți acoperi din starea asta" — decizia optimă e implicită în maximul acoperit.*

### T1·E3 — Scenariul predictiv

Derulare pe rânduri, cu valori:

- Rândul `e=1`: `f[1][t] = f[0][t-1] + f[1][t-1] + 1 = 0 + (t-1) + 1 = t`. Cu un singur ou și 14 încercări acoperi exact 14 etaje.
- Rândul `e=2`: `f[2][t] = f[1][t-1] + f[2][t-1] + 1 = f[2][t-1] + t`. Deci `f[2][1]=1, f[2][2]=3, f[2][3]=6, …` — sumele parțiale — iar `f[2][14] = 14·15/2 = 105`.

Variație de limită: `heightByRecurrence(0, 14)` → bucla pe `e` nu pornește deloc, se întoarce `f[0][14] = 0` [OK] verificat prin rulare. Iar pentru `n ≥ m` valorile cresc ca `2^m − 1`, deci pe `long` metoda devine inutilizabilă pe la `m ≈ 60` — de aceea există T3 (mod p).

### T1·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

În `f[e][t] = f[e-1][t-1] + f[e][t-1] + 1`, care termen e ramura „crapă"? Și de ce ambele ramuri pierd o încercare, dar doar una pierde un ou?

### T1·EQ — Răspunsul explica

Ramura „crapă" e `f[e-1][t-1]` — singurul termen în care scade indicele ouălor.

Contabilitatea celor două resurse e diferită pentru că ele se consumă în momente diferite: **încercarea** se consumă în clipa în care oul pleacă din mână — indiferent de rezultat, ai văzut o aruncare din cele `m` promise lui Eulampy — de aceea ambii termeni au `t-1`. **Oul** se consumă doar dacă a crăpat; enunțul spune explicit că un ou care nu crapă poate fi aruncat din nou, de aceea ramura „rezistă" păstrează `e`. Dovada în cod e chiar perechea de indici de la `FabergeEggs.java:35`: `[e-1][t-1]` vs `[e][t-1]`.

Predicție vecină (fără răspuns cerut): dacă ouăle nu s-ar termina niciodată înaintea încercărilor (`e ≥ t`), ramura „crapă" n-ar atinge niciodată rândul 0, iar `f[t][t]` ar crește ca `2^t − 1`.

## T2. Coeficienți binomiali — forma închisă

### T2·E1 — Traseul mecanic

Aceeași valoare 105 iese și fără tablou DP: ca sumă de combinări, `height(n,m) = C(m,1) + C(m,2) + … + C(m,n)`, unde `C(m,i)` („combinări de m luate câte i") = numărul de moduri de a alege i obiecte din m.

1. În `heightViaFactorials` (`FabergeEggs.java:46`), bucla de sumă parcurge `i = 1..min(n,m)` (`FabergeEggs.java:58-62`).
2. Pentru fiecare `i`, linia 59 citește termenul gata calculat: `binom = fact[m] · invFact[i] · invFact[m-i] (mod p)` — adică formula `C(m,i) = m!/(i!·(m-i)!)` tradusă în înmulțiri (tabelele vin la T5).
3. Linia 61 acumulează: `sum = (sum + binom) % MOD`. Efect: `heightViaFactorials(2,14) = 105`, identic cu tabloul din T1 [OK] verificat prin rulare, plus cross-check automat pe 200 de perechi random (n,m ≤ 300) — toate trei implementările concordă.

### T2·E2 — Modelul mental

De ce suma de combinări e același număr cu tabloul DP: cele două recurențe sunt aceeași recurență.

Identitatea lui Pascal spune `C(m,i) = C(m-1,i) + C(m-1,i-1)`: alegerile care nu conțin ultimul element + cele care îl conțin. Dacă notezi `S(n,m) = Σᵢ₌₁..ₙ C(m,i)` și aplici Pascal termen cu termen, obții exact `S(n,m) = S(n-1,m-1) + S(n,m-1) + 1` — aceeași formă cu `f[e][t]` de la T1, cu aceleași valori de pornire (0 pe margini). Două funcții cu aceeași recurență și aceleași margini sunt aceeași funcție.

Intuiția de numărare (etichetă: intuiție, nu demonstrația de mai sus): o strategie cu m aruncări și cel mult n crăpături produce un „jurnal" al aruncărilor; `C(m,i)` numără jurnalele cu exact i crăpături — care aruncări au crăpat — iar fiecare jurnal distinct poate indica un etaj critic distinct.

*Regula generală: când un DP are formulă închisă, formula nu „înlocuiește" recurența — este recurența, rezolvată o dată pentru totdeauna.*

### T2·E3 — Scenariul predictiv

Cu valori: `height(2,14) = C(14,1) + C(14,2) = 14 + 91 = 105` — exact `f[2][14]` din T1.

Variație de margine: dacă `n ≥ m`, suma acoperă toate submulțimile nevide: `Σᵢ₌₁..ₘ C(m,i) = 2^m − 1`. [OK] verificat prin rulare: `height(20, 10) = 1023 = 2¹⁰ − 1` — de la a 11-a combinare încolo `C(10,i) = 0`, de aceea bucla se oprește la `min(n,m)` (`FabergeEggs.java:58`).

Limita care motivează T3: exact ca valoare, `C(100000, 40000)` are zeci de mii de cifre — nu încape în niciun tip numeric — deci kata cere rezultatul modulo 998244353.

### T2·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Pentru (n=2 ouă, m=14 încercări) suma e `Σᵢ₌₁..₂ C(14,i) = 105`. Dacă ai încurca rolurile — `Σᵢ₌₁..₁₄ C(2,i)` — ce număr ai obține și de ce e complet altă întrebare?

### T2·EQ — Răspunsul explica

Ai obține 3, nu 105: `C(2,1) + C(2,2) = 2 + 1 = 3`, iar `C(2,i) = 0` pentru orice `i > 2`.

Rolurile nu sunt simetrice (perechea P1): **m stă sus în binom** pentru că m e mulțimea din care alegi — jurnalul are m aruncări, iar combinarea alege care din cele m aruncări crapă. **n doar taie suma** pentru că ouăle nu apar în jurnal ca poziții, ci ca plafon: cel mult n crăpături în total. În cod, rolul lui m e „indexul de sus" (`fact[m]`, `invFact[m-i]`, `FabergeEggs.java:59`), iar rolul lui n e capătul buclei (`Math.min(n, m)`, `FabergeEggs.java:58`).

Predicție vecină (fără răspuns cerut): `Σᵢ₌₁..₁₄ C(2,i) = 3 = 2² − 1` e chiar cazul de margine `n ≥ m` de la E3, cu rolurile inversate — semn rapid că ai încurcat argumentele dacă rezultatul iese suspect de mic.

## T3. Aritmetică modulară — calcul cu resturi

### T3·E1 — Traseul mecanic

Tot codul soluției lucrează cu **resturi** la împărțirea cu `p = 998244353` (constanta `MOD`, `FabergeEggs.java:20`), niciodată cu valorile exacte.

1. Enunțul kata-ului cere explicit `result % 998244353`, „altfel rezultatul ar fi prea mare".
2. Fiecare înmulțire e urmată imediat de o reducere: `fact[i] = fact[i-1] * i % MOD` (`FabergeEggs.java:51`), `term = term * factor % MOD * inv[i] % MOD` (`FabergeEggs.java:87`).
3. Fiecare adunare la fel: `sum = (sum + term) % MOD` (`FabergeEggs.java:88`).

Efectul: orice valoare stocată rămâne în intervalul `[0, p)`, adică maxim 9 cifre, oricât de mare e rezultatul exact.

### T3·E2 — Modelul mental

De ce poți calcula „pe resturi": reducerea comută cu adunarea și înmulțirea. `(a + b) mod p` și `(a·b) mod p` dau același rezultat fie că reduci operanzii înainte, fie doar produsul/suma la final. De aceea codul poate reduce agresiv, după fiecare operație, fără să schimbe răspunsul final.

Vocabular: `a mod p` = restul împărțirii lui `a` la `p`; „congruent" (`a ≡ b mod p`) = același rest. `p = 998244353` e **prim** — proprietatea de care depinde tot T4 (împărțirea). Faptul că `p = 119·2²³ + 1`, un modul popular în programare competitivă (prietenos cu NTT — transformata numerică), e o inferență despre motivul alegerii; kata-ul dă doar valoarea.

*Regula generală: sub mod p, plusul și oriul înmulțirii sunt gratuite; tot ce seamănă a comparație sau împărțire e capcană.*

### T3·E3 — Scenariul predictiv

Exemplu-jucărie cu `p = 7`: `(10 + 11) = 21 ≡ 0`; redus întâi: `(3 + 4) = 7 ≡ 0` — la fel.

Variația de eșec (perechea P2 — exact vs mod p): **ordinea nu supraviețuiește reducerii**. `15 > 9`, dar `15 mod 7 = 1 < 2 = 9 mod 7`. Deci un `min`/`max`/comparație pe resturi e o greșeală — exact de aceea, la F3, `n.min(m)` se face pe valorile exacte (`FabergeEggs.java:73`) și abia apoi `m.mod(p)` pentru aritmetică (`FabergeEggs.java:77`). Nici împărțirea nu supraviețuiește: `10/2 = 5 ≡ 5 (mod 7)`, dar `(10 mod 7)/2 = 3/2` nici măcar nu e întreg — rezolvarea e T4.

### T3·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

De ce linia 87 reduce de două ori în aceeași expresie — `term * factor % MOD * inv[i] % MOD` — în loc de o singură reducere la final?

### T3·EQ — Răspunsul explica

Pentru că al doilea produs consecutiv ar depăși `long`. Java evaluează stânga→dreapta: `((term * factor) % MOD * inv[i]) % MOD`.

Cu numere: fiecare factor e sub `p − 1 = 998244352`, deci un produs de doi e sub `(p−1)² ≈ 9,96·10¹⁷` — încape în `long` (maxim `9,22·10¹⁸`). Dar un produs de trei ar fi sub `(p−1)³ ≈ 9,95·10²⁶` — de vreo sută de milioane de ori peste `long`; fără reducerea din mijloc, produsul s-ar trunchia silențios (overflow, fără excepție în Java) și rezultatul ar fi gunoi. De aceea reducerea apare **între** cele două înmulțiri, nu doar după. Același tipar la `FabergeEggs.java:59`: `fact[m] * invFact[i] % MOD * invFact[m-i] % MOD`.

Predicție vecină (fără răspuns cerut): dacă `p` ar fi avut peste 32 de biți, nici produsul de doi n-ar mai fi încăput în `long`, și tot codul ar fi trebuit mutat pe `Math.multiplyHigh` sau `BigInteger`.

## T4. Inversul modular — împărțirea sub mod p

### T4·E1 — Traseul mecanic

Codul „împarte" în două locuri, și în ambele împărțirea e de fapt o înmulțire cu un invers.

1. `heightViaFactorials` are nevoie de `1/m!`: linia `invFact[m] = modPow(fact[m], MOD - 2)` (`FabergeEggs.java:53`) îl obține ridicând `fact[m]` la puterea `p − 2 = 998244351`. `modPow` (`FabergeEggs.java:94-105`) face exponențiere rapidă: parcurge cei 30 de biți ai exponentului, ridică la pătrat la fiecare bit (`b = b*b % MOD`) și înmulțește rezultatul la biții de 1 — circa 30 de pătrate + ~15 înmulțiri, nu 998244351 de pași.
2. `height` are nevoie de `1/i` pentru fiecare `i ≤ cap`: linia `inv[i] = (MOD - (MOD / i) * inv[(int)(MOD % i)] % MOD) % MOD` (`FabergeEggs.java:81`) umple tot tabloul de inverse în O(cap), pornind de la `inv[1] = 1` (`FabergeEggs.java:80`).

### T4·E2 — Modelul mental

Sub mod p nu există operația „împarte la a"; există numărul `inv(a)` — **inversul modular** — definit prin `a · inv(a) ≡ 1 (mod p)`. „A împărți la a" înseamnă „a înmulți cu inv(a)" (perechea P3).

De ce inversul există și cum îl găsești: **mica teoremă a lui Fermat** spune că pentru p prim și `a` nedivizibil cu p, `a^(p−1) ≡ 1`. Împarte o dată la `a` în ambele părți: `a^(p−2) ≡ inv(a)` — exact exponentul `MOD − 2` din cod.

Formula tabloului de inverse iese din împărțirea cu rest a lui p însuși: `p = (p/i)·i + (p mod i)`. Sub mod p partea stângă e 0, deci `(p/i)·i ≡ −(p mod i)`, de unde `inv(i) = −(p/i) · inv(p mod i)`. Restul `p mod i` e mai mic decât `i`, deci inversul lui e deja în tablou când ajungi la `i` — de aceea o singură trecere ajunge.

*Regula generală: „împărțirea" mod p se cumpără o dată (Fermat sau tabloul de inverse) și apoi se consumă ca înmulțire.*

### T4·E3 — Scenariul predictiv

Jucărie cu `p = 7`: `inv(3) = 3⁵ mod 7 = 243 mod 7 = 5`; verificare: `3·5 = 15 ≡ 1` ✓. Deci „12/3 mod 7" = `12 · 5 mod 7 = 60 mod 7 = 4` — același rezultat cu `(12/3) mod 7 = 4`.

Variația de eșec: `inv(0)` nu există — nimic înmulțit cu 0 nu dă 1 — și, mai subtil, nu există invers nici pentru multiplii lui p. Iar dacă p nu e prim, teorema pică: mod 6, `2·x` ia doar valorile {0, 2, 4}, niciodată 1, deci 2 nu are invers mod 6. Primalitatea lui 998244353 e ce face toată schema legală.

### T4·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

La T6 vei vedea că `C(m,i) mod p` poate ieși 0. Dacă numărătorul poate fi divizibil cu p, de ce numitorul `i!` nu poate fi niciodată — adică de ce împărțirea la `i!` e mereu legală în acest kata?

### T4·EQ — Răspunsul explica

Pentru că fiecare factor al lui `i!` e strict mai mic decât p, iar p e prim.

Concret: bucla merge doar până la `cap ≤ 200000` (`FabergeEggs.java:73`), deci `i! = 1·2·…·i` e un produs de numere din `[1, 200000]`, toate sub `p = 998244353`. Niciun factor nu e divizibil cu p; p fiind prim, nici produsul nu poate fi (un prim divide un produs doar dacă divide un factor). Deci `i! mod p ≠ 0` și inversul lui există garantat. Numărătorul, în schimb, conține factori de forma `m − k` cu `m` uriaș — aceia pot nimeri multipli de p, și atunci `C(m,i) ≡ 0` legitim.

Predicție vecină (fără răspuns cerut): dacă kata-ul ar fi folosit un modul mic, de pildă `p = 7`, deja `C(m, 10)` ar fi cerut inversul lui `10!`, care conține factorul 7 — schema cu factoriali s-ar prăbuși, și ar trebui teorema lui Lucas în loc.

## T5. Tabele de factoriali și inverse — regimul n ≤ 80000, m ≤ 100000

### T5·E1 — Traseul mecanic

`heightViaFactorials(80000, 100000)` plătește o construcție o singură dată, apoi citește fiecare combinare în O(1).

1. `fact[0] = 1` (`FabergeEggs.java:50`); bucla urcătoare `fact[i] = fact[i-1] * i % MOD` (`FabergeEggs.java:51`) — 100000 de înmulțiri, `fact[i] = i! mod p`.
2. Un singur apel `modPow(fact[m], MOD-2)` pune `1/m!` în `invFact[m]` (`FabergeEggs.java:53`) — ~45 de înmulțiri (T4).
3. Bucla coborâtoare `invFact[i-1] = invFact[i] * i % MOD` (`FabergeEggs.java:55-56`) — încă 100000 de înmulțiri, umple restul inverselor.
4. Suma citește fiecare termen cu 2 înmulțiri: `fact[m] * invFact[i] % MOD * invFact[m-i] % MOD` (`FabergeEggs.java:59`) — 80000 de termeni.

Total ≈ 360000 de înmulțiri; rezultat `805097588` [OK] verificat prin rulare, identic cu metoda incrementală de la T6.

### T5·E2 — Modelul mental

De ce tabele: fără ele, fiecare `C(m,i)` calculat de la zero ar costa O(m) înmulțiri, iar suma de n termeni O(n·m) ≈ 8·10⁹ — tabelele mută costul pe „o dată O(m), apoi O(1) per citire".

Invariantul celor două tablouri: `fact[i] · invFact[i] ≡ 1` pentru orice `i` — sunt oglinzi. Memorie: două tablouri de `long` cu 100001 de intrări ≈ 800 KB fiecare, 1,6 MB total — banal pentru regimul A, fatal pentru regimul B (E3).

Vocabular: „precomputare" = muți munca dependentă doar de m înaintea buclei dependente de n; „amortizare" = costul construcției se împarte la numărul de citiri.

*Regula generală: un tabel precomputat rentează exact când numărul de citiri depășește costul construcției — altfel e doar memorie ocupată.*

### T5·E3 — Scenariul predictiv

Cu valorile regimului A: n=80000, m=100000 → construcție 200045 de înmulțiri, sumă 160000, rezultat `805097588` instantaneu.

Variație care schimbă verdictul: `n=5, m=100000` → construcția tot 200045 (depinde doar de m), suma doar 10 — plătești 200055 de operații pentru 5 termeni. Metoda incrementală (T6) ar fi făcut ~15.

Variația de eșec (de ce există T6): `m = 2^200` → `new long[m + 1]` e imposibil — `m` nu încape nici ca index de tablou (limita Java: `int`), nici măcar în `long`; nici măcar `fact[m]` conceptual nu se poate construi prin m înmulțiri (F4 pune numerele). Tabelele mor odată cu presupunerea „m e mic".

### T5·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

De ce `invFact` se construiește **coborând** dintr-un singur `modPow` (liniile 53-56), în loc de câte un `modPow(fact[i], MOD-2)` pentru fiecare `i`?

### T5·EQ — Răspunsul explica

Ca să plătești Fermat o singură dată, nu de 100000 de ori.

Un `modPow` costă ~45 de înmulțiri (30 de biți ai exponentului). Varianta „câte un modPow per element" ar costa `100000 · 45 ≈ 4,5 milioane` de înmulțiri. Varianta din cod folosește identitatea `1/(i-1)! = i · (1/i!)` — de aceea direcția e obligatoriu coborâtoare: `invFact[i-1] = invFact[i] * i % MOD` (`FabergeEggs.java:56`) are nevoie de `invFact[i]` deja calculat, iar singura valoare-sămânță cumpărată cu Fermat e vârful `invFact[m]` (`FabergeEggs.java:53`). Cost: 45 + 100000 de înmulțiri — de ~45 de ori mai ieftin.

De notat simetria: `fact` urcă (fiecare element are nevoie de cel de **dedesubt**), `invFact` coboară (fiecare are nevoie de cel de **deasupra**). Inversarea oricărei direcții rupe lanțul de dependențe.

Predicție vecină (fără răspuns cerut): dacă ai avea nevoie doar de `invFact[i]` pentru un singur `i`, un `modPow` direct pe `fact[i]` ar fi mai ieftin decât construcția întregului tablou — din nou regula amortizării de la E2.

## T6. Termenul incremental — C(m,i) fără factorialul lui m

### T6·E1 — Traseul mecanic

Metoda `height` (`FabergeEggs.java:71`) construiește fiecare combinare din precedenta, atingând m o singură dată.

1. `mModP = m.mod(p).longValue()` (`FabergeEggs.java:77`) — pentru `m = 2^200`, `mModP = 982924732`. E singura dată când m intră în aritmetică.
2. `term` pornește de la 1 = `C(m,0)` (`FabergeEggs.java:84`).
3. La pasul `i`: `factor = ((mModP - i + 1) % MOD + MOD) % MOD` (`FabergeEggs.java:86`), apoi `term = term * factor % MOD * inv[i] % MOD` (`FabergeEggs.java:87`) — adică `C(m,i) = C(m,i-1) · (m-i+1) / i`, cu împărțirea făcută prin `inv[i]` (T4).
4. `sum = (sum + term) % MOD` (`FabergeEggs.java:88`) acumulează.

Primii pași pentru `m = 2^200`: `i=1`: factor `982924732` → term `982924732`; `i=2`: factor `982924731` → term `779512128`. După 3000 de pași: `141903106` [OK] verificat prin rulare, sub 1 ms.

### T6·E2 — Modelul mental

De ce raportul e legal sub mod p: `C(m,i) = C(m,i-1) · (m-i+1) / i` e o **identitate între numere întregi** — ambele părți sunt același întreg, nu o fracție care „se întâmplă" să iasă. O identitate de întregi construită din +, − și × supraviețuiește reducerii mod p (T3), iar singura împărțire, `/i`, devine `· inv(i)` — legală pentru că `i < p` (T4·EQ).

Consecința decisivă: m apare în identitate doar prin factorii `(m − k)`, adică doar prin scăderi și înmulțiri — deci restul `mModP` îl înlocuiește perfect. Nu-ți trebuie `m!`, nu-ți trebuie tabele de mărimea lui m, nu-ți trebuie nici măcar m ca număr — doar restul lui, de 9 cifre.

Invariantul buclei: după pasul `i`, `term ≡ C(m,i) (mod p)`.

*Regula generală: când vecinii dintr-un șir diferă printr-un raport simplu, șirul întreg costă un pas per element — indiferent cât de monstruos e fiecare element în sine.*

### T6·E3 — Scenariul predictiv

Verificare pe un caz mic, `m = 14`: `i=1`: term = 14; `i=2`: factor = 13, term = `14 · 13 · inv(2) = 91` — exact `C(14,2)`; sum = 105. Aceeași buclă dă și 105-ul din T1/T2, și 141903106-ul regimului B.

Variația de limită: pentru `n=20, m=10`, capătul `min(n,m) = 10` (`FabergeEggs.java:73`) oprește bucla înainte ca factorul să atingă `m−i+1 = 0` la `i=11`; dacă bucla ar continua, termenul ar deveni 0 și ar rămâne 0 — corect, fiindcă `C(10, i>10) = 0` — dar ar fi pași irosiți. Suma rămâne `1023 = 2¹⁰ − 1`.

### T6·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Pentru m uriaș, `mModP - i + 1` poate ieși **negativ** — și, dacă m nimerește anumite valori, chiar **0**. În ambele cazuri rezultatul mai e corect?

### T6·EQ — Răspunsul explica

Da în ambele cazuri — unul e tratat de cod, celălalt e matematic corect de la sine.

**Negativ**: `mModP` e un rest fix (ex. 982924732), dar nimic nu-l împiedică să fie mai mic decât `i` pentru alt m. Java păstrează semnul la `%`, așa că expresia de la `FabergeEggs.java:86` normalizează explicit: `((x % MOD) + MOD) % MOD` aduce orice valoare în `[0, p)`. Fără `+ MOD`, un factor negativ ar otrăvi tot lanțul de termeni.

**Zero**: `mModP - i + 1 ≡ 0` înseamnă că întregul exact `m − i + 1` e divizibil cu p. Atunci p chiar divide numărătorul lui `C(m,i)`, iar numitorul `i!` nu-l poate anula (T4·EQ) — deci `C(m,i) ≡ 0 (mod p)` e adevărul, nu un accident. Mai mult, factorul acela rămâne în toate produsele următoare, deci și `C(m,j) ≡ 0` pentru `j ≥ i` — lanțul de termeni 0 pe care îl produce bucla e exact ce cere matematica.

Pentru datele regimului B concret: `mModP = 982924732` și `i ≤ 3000`, deci factorul stă mereu peste 982921733 — cazul „zero" nu apare aici, dar codul ar rămâne corect dacă ar apărea.

Predicție vecină (fără răspuns cerut): un test cu `m = p + 2` (deci `mModP = 2`) ar da factor 0 la `i = 3` — și într-adevăr `C(p+2, 3)` conține factorul `p`, deci restul lui e 0.

## T7. BigInteger în Java — cât din 2^200 intră în calcul

### T7·E1 — Traseul mecanic

Semnătura Codewars primește numere arbitrare: `public static BigInteger height(BigInteger n, BigInteger m)` (`FabergeEggs.java:71`). Metoda le atinge de exact cinci ori, toate înainte de buclă:

1. `BigInteger.valueOf(MOD)` construiește p ca BigInteger (`FabergeEggs.java:72`).
2. `n.min(m).min(BigInteger.valueOf(200_000))` — două comparații pe valorile **exacte** (`FabergeEggs.java:73`).
3. `.intValueExact()` — conversia capătului de buclă la `int` (aceeași linie).
4. `m.mod(p)` — reducerea, o singură dată (`FabergeEggs.java:77`).
5. `.longValue()` — coborârea restului în `long` (aceeași linie).

Din linia 79 în jos, zero operații BigInteger: tabloul `inv`, bucla, suma — totul pe `long`. Rezultatul urcă înapoi o singură dată, prin `BigInteger.valueOf(sum)` (`FabergeEggs.java:91`).

### T7·E2 — Modelul mental

De ce e nevoie de BigInteger: `long` are 64 de biți, maxim `9,22·10¹⁸`; kata-ul promite `m ≤ 2^200 ≈ 1,61·10⁶⁰` — cu 42 de ordine de mărime peste. `BigInteger` (pachetul `java.math`) e întregul cu precizie arbitrară: crește cât cifrele, dar fiecare operație costă proporțional cu lungimea lui, nu O(1) ca la `long`.

Principiul de design al metodei: **atinge numărul uriaș cât mai puțin și cât mai devreme**. Comparațiile care au nevoie de valorile exacte (`min` — vezi capcana P2 de la T3·E3) se fac pe BigInteger; imediat ce aritmetica permite, valoarea coboară în `long` prin `mod` și rămâne acolo. `m.mod(p)` parcurge cei ~200 de biți ai lui m o dată — cost neglijabil.

*Regula generală: BigInteger e un vehicul de transport pentru numere mari, nu un mediu de calcul — coboară în long la prima ocazie legală.*

### T7·E3 — Scenariul predictiv

Concret: `m = 2^200 = 1606938044258990275541962092341162602522202993782792835301376` (61 de cifre). `m.mod(p)` îl strânge la `982924732` (9 cifre), și tot restul calculului nu mai știe că m a fost vreodată uriaș.

Variația de eșec: `m.longValueExact()` în loc de `m.mod(p).longValue()` ar arunca `ArithmeticException: BigInteger out of long range` la primul test din regimul B — conversia „exactă" refuză orice valoare peste 64 de biți. Iar `intValueExact()` de pe linia 73 nu aruncă niciodată doar pentru că `min(200_000)` a plafonat deja valoarea (Q1).

### T7·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Kata garantează `n ≤ 80000`, care încape lejer în `int`. Atunci de ce mai există al doilea `min(BigInteger.valueOf(200_000))` pe linia 73 — ce s-ar întâmpla fără el?

### T7·EQ — Răspunsul explica

Fără el, `intValueExact()` ar putea arunca excepție pe intrări din afara contractului — plafonul face conversia totală (definită pentru orice intrare).

Filmul: `n.min(m)` e tot un BigInteger; dacă cineva ar chema metoda cu `n = m = 2^200`, minimul ar fi `2^200`, iar `intValueExact()` ar arunca `ArithmeticException`. Al doilea `min` garantează `cap ≤ 200000` înainte de conversie, orice ar veni. Plafonul 200000 e ales peste maximul din contract (`n ≤ 80000`), deci pe datele kata-ului nu taie niciodată nimic — e o **alegere defensivă de implementare, nu o cerință a kata-ului** [etichetă: decizie proprie, nu dovadă din enunț]. Efect secundar util: `cap` dimensionează și tabloul `inv` (`FabergeEggs.java:79`), deci plafonul limitează implicit și memoria.

Predicție vecină (fără răspuns cerut): pe datele reale ale kata-ului, ștergerea plafonului nu ar schimba niciun rezultat de test — dar un apel din afara contractului ar crăpa cu excepție în loc să întoarcă un răspuns lent și corect.

# Partea II — Fire de execuție

## F1. Ceri height(2,14) → recurența derulează → 105

### F1·E1 — Traseul mecanic

Declanșator: apelezi `FabergeEggs.heightByRecurrence(2, 14)`. Filmul complet, celulă cu celulă:

1. Alocare `f[3][15]`, totul 0 (`FabergeEggs.java:32`); marginile `e=0` și `t=0` rămân 0 pentru totdeauna.
2. Trecerea `e=1`, `t=1..14`: fiecare celulă adună rândul 0 (zerouri) → `f[1][t] = t`; după trecere rândul 1 e `1, 2, 3, …, 14`.
3. Trecerea `e=2`, `t=1..14`: `f[2][t] = f[1][t-1] + f[2][t-1] + 1` → `1, 3, 6, 10, …` — la fiecare pas se adaugă `t`; ultima celulă calculată e `f[2][14] = 105`.
4. `return f[2][14]` (`FabergeEggs.java:38`) → 105.

Aceeași valoare din celelalte două drumuri: `heightViaFactorials(2,14) = 105` și `height(2,14) = 105` [OK] verificat prin rulare — trei mecanisme, un singur număr.

### F1·E2 — Modelul mental

Ce înseamnă fizic 105: există o strategie reală de aruncare care acoperă un bloc de 105 etaje, și niciuna care să acopere 106.

Strategia: prima aruncare de la etajul **14**. Crapă → etajul critic e în `1..13`, ai 1 ou și 13 încercări → urci etaj cu etaj (T1·EQ). Rezistă → sari la `14 + 13 = 27`; următoarea săritură 12, apoi 11… Etaje acoperite: `14 + 13 + 12 + … + 1 = 105`. De ce prima săritură e exact 14: dacă oul crapă, partea de jos trebuie acoperită de `f(1, 13) = 13` etaje — deci etajul aruncării e cel mult `f(1,13) + 1 = 14`.

De reținut distincția (P6 din harta DP): tabloul dă **valoarea** 105, nu strategia; săriturile 14, 13, 12… le-am reconstruit noi din recurență — codul nu memorează nicăieri „de la ce etaj arunci".

*Regula generală: numărul dintr-o celulă DP e umbra unei strategii; strategia se reconstruiește mergând înapoi prin alegerile care au produs maximul.*

### F1·E3 — Scenariul predictiv

Predicții derivabile direct din film, cu valori:

- `height(2, 15)`: rândul 2 mai face un pas, `105 + 15 = 120`.
- `height(3, 14)`: al treilea rând peste sumele parțiale → echivalent `C(14,1)+C(14,2)+C(14,3) = 14+91+364 = 469`.
- Variații de margine: `height(0, 14) = 0` (n-ai ce arunca) și `height(2, 0) = 0` (n-ai voie să arunci) — ambele [OK] verificate prin rulare; sunt chiar exemplele din enunț.

### F1·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

În strategia lui 105, de ce prima aruncare e de la etajul 14 și nu de la 15? Concret: ce se strică dacă Tempter aruncă prima dată de la etajul 15?

### F1·EQ — Răspunsul explica

De la 15, cazul „crapă" devine imposibil de terminat garantat: rămân 14 etaje suspecte (1..14), dar doar 13 încercări și un singur ou.

Filmul eșecului: aruncarea 1 de la etajul 15, oul crapă. Cu ultimul ou trebuie mers crescător 1, 2, 3, … (orice săritură lasă ambiguitate — T1); în 13 încercări ajungi la etajul 13. Dacă adversarul pune etajul critic la 14, jurnalul tău se termină fără să-l fi distins de 15 — pierdut. Regula generală a primului pas: etajul primei aruncări = `f(e-1, t-1) + 1` — exact cât acoperă garantat ramura „crapă", plus etajul aruncării. Pentru (2,14): `f(1,13) + 1 = 14`.

Predicție vecină (fără răspuns cerut): din același motiv, a doua aruncare (după un „rezistă") e la `14 + f(1,12) + 1 = 27`, iar salturile scad mereu cu 1 — de aici suma `14+13+…+1`.

## F2. Regimul A: n=80000, m=100000 → tabele → sumă

### F2·E1 — Traseul mecanic

Declanșator: un test Codewars din primul regim de date („sometimes n ≤ 80000, m ≤ 100000") apelează soluția cu `n=80000, m=100000`. Filmul pe metoda de tabele:

1. `heightViaFactorials(80000, 100000)` (`FabergeEggs.java:46`) alocă `fact` și `invFact` de câte 100001 de `long` ≈ 1,6 MB total.
2. Urcarea `fact` — 100000 de înmulțiri (`FabergeEggs.java:51`); un `modPow` pentru vârful `invFact[100000]` (`FabergeEggs.java:53`); coborârea `invFact` — 100000 de înmulțiri (`FabergeEggs.java:55-56`).
3. Suma — 80000 de termeni × 2 înmulțiri (`FabergeEggs.java:58-62`).
4. Efect: `805097588`. [OK] verificat prin rulare, și confirmat independent de metoda incrementală: `height(80000, 100000) = 805097588` — cele două drumuri concordă pe exact intrarea de regim A maximă.

(Pe Codewars intrarea vine prin semnătura unică `height(BigInteger, BigInteger)` — metoda incrementală; `heightViaFactorials` există în repo ca ancoră didactică pentru drumul cu tabele.)

### F2·E2 — Modelul mental

Bugetul de timp al regimului A: ~360000 de înmulțiri per test; chiar dacă toate cele 150 de teste ar fi de mărime maximă, ~54 de milioane de înmulțiri pe `long` — zeci de milisecunde pe un procesor modern.

De ce există totuși două drumuri liniare: tabelele cumpără **acces aleator** — după construcție, orice `C(m,i)` costă O(1), în orice ordine; termenul incremental cumpără doar **acces secvențial** — `C(m,i)` există doar dacă ai trecut prin `C(m,1)…C(m,i-1)`. Kata-ul cere o sumă secvențială, deci incrementalul ajunge; tabelele ar deveni necesare abia dacă cererile ar sări (ex. „dă-mi C(m, 17), apoi C(m, 3)").

*Regula generală: alege structura după tiparul de acces — plătești tabele pentru salturi, plătești lanț pentru parcurgeri.*

### F2·E3 — Scenariul predictiv

Predicție pe o variație de intrare: `n=5, m=100000` pe drumul cu tabele → costul rămâne ~200045 de operații (construcția depinde doar de m), deși suma are 5 termeni. Pe drumul incremental → ~5 pași de buclă plus 5 inverse: cu patru ordine de mărime mai ieftin. Ambele întorc același număr.

Variația de eșec: drumul cu tabele hrănit cu regimul B (`m = 2^200`) moare la alocare — `new long[m+1]` nu se poate nici măcar exprima, pentru că indexul de tablou în Java e `int` (max ~2,1 miliarde), iar m are 61 de cifre. Regimul A e definiția exactă a teritoriului tabelelor: m mic, indexabil.

### F2·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Cine domină costul lui `heightViaFactorials(80000, 100000)`: construcția tabelelor sau bucla de sumă? Și cum se schimbă răspunsul la `n=5`?

### F2·EQ — Răspunsul explica

La (80000, 100000) sunt comparabile, cu construcția ușor în față; la (5, 100000) construcția e practic tot costul.

Contabilitatea exactă, pe linii: construcția = 100000 (urcarea `fact`, linia 51) + ~45 (`modPow`, linia 53) + 100000 (coborârea `invFact`, linia 56) ≈ 200045 de înmulțiri — **depinde doar de m**. Suma = 2 înmulțiri × 80000 de termeni = 160000 — **depinde doar de n**. Raport ~55:45. La `n=5`, suma scade la 10 înmulțiri, construcția rămâne 200045 — raport 20000:1. Morala e cea de la T5·E2: tabelele se amortizează doar când n e comparabil cu m; kata-ul regimului A (n până la 80000 din m=100000) e aproape de cazul ideal pentru ele.

Predicție vecină (fără răspuns cerut): pe drumul incremental costul e ~3 înmulțiri × n + construcția tabloului `inv` de mărime n — liniar **doar în n** pentru orice m; de aceea el e drumul care supraviețuiește și în regimul B (F3).

## F3. Regimul B: n=3000, m=2^200 → o reducere → lanț de 3000 de pași

### F3·E1 — Traseul mecanic

Declanșator: un test din al doilea regim („while sometimes n ≤ 3000, m ≤ 2^200") apelează `height(3000, 2^200)`. Filmul:

1. `p = BigInteger.valueOf(998244353)` (`FabergeEggs.java:72`).
2. `cap = min(3000, 2^200, 200000) = 3000` — comparațiile pe valorile exacte (`FabergeEggs.java:73`).
3. `mModP = 2^200 mod p = 982924732` — singura întâlnire aritmetică cu numărul de 61 de cifre (`FabergeEggs.java:77`).
4. Tabloul `inv[1..3000]` se umple în 3000 de pași (`FabergeEggs.java:79-83`).
5. Bucla principală rulează 3000 de iterații pe `long` (`FabergeEggs.java:86-89`): term 982924732, apoi 779512128, … sumă finală `141903106`.

[OK] verificat prin rulare: rezultat `141903106`, timp măsurat sub 1 ms.

### F3·E2 — Modelul mental

De ce forma asta e singura viabilă: orice algoritm care execută măcar o operație per unitate din m e mort din start — m are ordinul 10⁶⁰ (F4 pune anii). Enunțul folosește m în exact două roluri, și fiecare rol cere altă reprezentare (perechea P2 în acțiune):

- **rol de valoare aritmetică** — factorii `(m − k)` din combinare: aici restul `mModP` e un înlocuitor perfect (T6·E2);
- **rol de cantitate comparabilă** — „câți termeni are suma": aici trebuie valoarea exactă, pentru că ordinea nu supraviețuiește reducerii (T3·E3) — de aceea `min` se face înainte de `mod`.

Costul total: O(n) pași de buclă + O(biții lui m) pentru reducere = O(n + log m). „log m" ≈ 200 aici — mai ieftin decât o singură trecere prin tabloul `inv`.

*Regula generală: un număr uriaș nu se „calculează" — se întreabă: fiecare întrebare (restul lui? mai mic decât n?) are un răspuns mic, și doar răspunsurile intră în calcul.*

### F3·E3 — Scenariul predictiv

Predicții cu valorile reale: pentru `m = 2^200`, `n=1` → suma are un termen: `C(m,1) mod p = mModP = 982924732`. Pentru `n=2` → `982924732 + 779512128 = 1762436860`, redus: `1762436860 − 998244353 = 764192507`.

Variația de eșec (ordinea operațiilor): dacă ai reduce m **înainte** de `min` — `cap = min(n, mModP)` — atunci un m uriaș cu rest mic (ex. `m = p + 2`, rest 2) ar tăia suma la 2 termeni în loc de n: rezultat greșit, deși fiecare termen în sine ar fi corect. Linia 73 (min pe exact) înaintea liniei 77 (mod) nu e stil, e corectitudine.

### F3·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Câte operații BigInteger execută `height(3000, 2^200)` în total — și de ce bucla principală nu vede niciuna?

### F3·EQ — Răspunsul explica

Șase, toate consumate până la linia 77; bucla rulează exclusiv pe `long`.

Inventarul complet, în ordinea execuției: (1) `BigInteger.valueOf(MOD)` — construcție; (2) `n.min(m)` — comparație; (3) `.min(BigInteger.valueOf(200_000))` — încă o construcție + comparație (numărate împreună); (4) `.intValueExact()` — conversie; (5) `m.mod(p)` — singura operație aritmetică propriu-zisă; (6) `.longValue()` — conversie. Bucla nu vede BigInteger pentru că tot ce-i trebuie — capătul `cap` și restul `mModP` — a fost deja distilat în două numere primitive. De aceea 3000 de iterații durează microsecunde: fiecare iterație e 3 înmulțiri de `long` și un acces de tablou, fără alocări.

Predicție vecină (fără răspuns cerut): la `m = 2^400`, singurul cost care crește e pasul (5) — reducerea parcurge 400 de biți în loc de 200; bucla rămâne identică, tot 3000 de iterații.

## F4. De ce „naivul" pică — cele 150 de teste ca filtru

### F4·E1 — Traseul mecanic

Enunțul avertizează direct: „You will need more than just a naive linear algorithm for this task". Cum moare fiecare candidat naiv, cu numere:

1. **Tabloul DP din T1** pe regimul A: `heightByRecurrence(80000, 100000)` ar aloca `80001 × 100001` celule de `long` — 8·10⁹ celule ≈ **64 GB**. Alocarea de la `FabergeEggs.java:32` ar arunca `OutOfMemoryError` înainte de primul calcul [inferență: modul exact de eșec e dedus din dimensiune, nu rulat].
2. **„Liniar în m"** (orice buclă `for t = 1..m`) pe regimul B: `m = 2^200 ≈ 1,6·10⁶⁰` de pași; la 10⁹ operații/secundă, ≈ **5·10⁴³ ani**. Nu „încet" — în afara fizicii.
3. **Soluția livrată**: O(n + log m) per test (F3); 150 de teste × maximum 80000 de pași ≈ 12 milioane de operații în total — miimi de secundă.

Limita de timp Codewars: implicit 12 secunde [inferență, kata-ul nu o afișează].

### F4·E2 — Modelul mental

Ce promite de fapt „[linear]" din titlu (perechea P4): liniar **în n, numărul de termeni ai sumei** — nu în m. Cele două regimuri sunt construite ca un clește: regimul A (n=80000, m=100000) omoară orice algoritm peste liniar în n·ceva-mic; regimul B (m=2^200) omoară separat orice atingere a lui m dincolo de `mod` și `min`. Ca să treci ambele, ești forțat exact în forma din F3.

Versiunea de 3 kyu a kata-ului (fără „[linear]", n,m ≤ 20000) admite și tabloul DP complet — 4·10⁸ celule, greu dar posibil [inferență din limitele afișate în enunțul ei; netestat]. Sufixul din titlu e deci anunțul autorului: aici scara s-a schimbat, aduceți formula închisă.

*Regula generală: limitele de date sunt enunțul real al problemei — citește-le ca pe o listă de algoritmi interziși.*

### F4·E3 — Scenariul predictiv

Predicții pe submisii parțiale:

- Un submitter doar cu `heightViaFactorials`: trece toate testele de regim A, moare la **primul** test de regim B — m nu poate fi index de tablou (F2·E3). Cu 150 de teste random amestecate, eșecul vine aproape imediat.
- Un submitter doar cu `heightByRecurrence`: moare și mai devreme, la primul test de regim A mare (64 GB).
- Soluția incrementală singură (`height`): trece tot — regimul A e pentru ea doar „n ceva mai mare".

Variația-limită: chiar și `n = 80000` cu `m = 2^200` simultan (în afara regimurilor anunțate) ar rămâne sub 80000 de pași — forma din F3 nu are niciun parametru care să explodeze.

### F4·Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Recurența din T1 face O(1) muncă per celulă — o adunare. De ce „O(1) per celulă" nu înseamnă „destul de liniar" pentru testele acestui kata?

### F4·EQ — Răspunsul explica

Pentru că numărul de celule e produsul n·m, iar liniaritatea care contează se măsoară în totalul muncii, nu în munca per pas.

Cu numerele regimului A: `80000 × 100000 = 8·10⁹` celule — 8 miliarde de adunări (zeci de secunde) și 64 GB dacă ții tot tabloul. Chiar optimizarea clasică de memorie — rolling array, păstrezi doar 2 rânduri ≈ 1,6 MB — repară doar memoria: cele 8·10⁹ adunări rămân. Formula închisă (T2) rupe chiar produsul: din „n·m celule care depind una de alta" în „n termeni, fiecare obținut în O(1) din precedentul" (T6). Asta e diferența de clasă: `O(n·m) → O(n + m)` în regimul A, respectiv `O(n + log m)` în B.

Predicție vecină (fără răspuns cerut): pe versiunea de 3 kyu (n,m ≤ 20000), rolling array + recurență ar putea trece — 4·10⁸ adunări sunt secunde, nu zeci de minute; sufixul „[linear]" există exact ca să închidă și portița asta.

# Glosar

- **kyu** — scara de dificultate Codewars; 1 kyu = cel mai greu nivel obișnuit.
- **etaj critic** — cel mai înalt etaj de la care oul aruncat NU crapă; obiectul căutării.
- **garantat / cazul cel mai rău** — strategia trebuie să reușească indiferent de rezultatele aruncărilor (adversarul alege continuarea nefavorabilă).
- **DP (programare dinamică)** — rezolvarea prin subprobleme care se suprapun, cu rezultatele memorate (tabloul `f[e][t]`).
- **recurență** — regula care exprimă o valoare prin valori „mai mici": `f[e][t] = f[e-1][t-1] + f[e][t-1] + 1`.
- **C(m,i) / combinări** — numărul de moduri de a alege i obiecte din m; `C(m,i) = m!/(i!·(m-i)!)`.
- **identitatea lui Pascal** — `C(m,i) = C(m-1,i) + C(m-1,i-1)`; puntea dintre recurență și formula închisă.
- **mod / rest** — `a mod p` = restul împărțirii lui a la p; „congruent" = același rest.
- **p = 998244353** — modulul cerut de kata; număr prim; egal cu `119·2²³ + 1`.
- **invers modular** — `inv(a)` cu `a·inv(a) ≡ 1 (mod p)`; rolul împărțirii sub mod.
- **mica teoremă a lui Fermat** — pentru p prim și a nedivizibil cu p: `a^(p-1) ≡ 1`, deci `inv(a) = a^(p-2)`.
- **exponențiere rapidă** — ridicare la putere în O(log exponent) prin pătrate succesive (`modPow`).
- **factorial (m!)** — produsul `1·2·…·m`; `fact[]`/`invFact[]` sunt tabelele lui și ale inverselor.
- **termen incremental** — obținerea lui `C(m,i)` din `C(m,i-1)` prin raportul `(m-i+1)/i`.
- **regim A / regim B** — cele două intervale de date ale kata-ului: `n≤80000, m≤100000`, respectiv `n≤3000, m≤2^200`.
- **long / overflow** — întregul Java pe 64 de biți (max ~9,22·10¹⁸); depășirea se trunchiază silențios.
- **BigInteger** — întregul Java cu precizie arbitrară (`java.math`); operații cu cost proporțional cu lungimea numărului.
- **rolling array** — optimizare de memorie DP: păstrezi doar rândurile necesare (aici 2), nu tot tabloul.
- **NTT** — transformata numerică (varianta modulară a FFT); motivul probabil al popularității modulului 998244353 (inferență).

# Index de ancore în cod

Toate în `src/test/java/com/footballmanagergamesimulator/algorithms/dp/FabergeEggs.java` (fișier nou, necomis la data generării):

| Linie | Ce ancorează | Folosit în |
|---|---|---|
| 20 | constanta `MOD = 998_244_353` | T3·E1 |
| 31-38 | `heightByRecurrence` — tabloul DP | T1·E1, F1·E1 |
| 35 | celula recurenței `f[e][t]` | T1·E1, T1·EQ |
| 46-63 | `heightViaFactorials` — drumul cu tabele | T2·E1, T5·E1, F2·E1 |
| 50-52 | urcarea `fact[]` | T5·E1, F2·EQ |
| 53 | `invFact[m] = modPow(fact[m], MOD-2)` | T4·E1, T5·E1, T5·EQ |
| 55-56 | coborârea `invFact[]` | T5·E1, T5·EQ |
| 58-59 | bucla de sumă + citirea `C(m,i)` în O(1) | T2·E1, T3·EQ, T5·E1 |
| 71 | semnătura `height(BigInteger, BigInteger)` | T7·E1 |
| 73 | `cap = n.min(m).min(200000).intValueExact()` | T4·EQ, T6·E3, T7·E1, T7·EQ, F3·E1 |
| 77 | `mModP = m.mod(p).longValue()` — unica reducere a lui m | T6·E1, T7·E1, F3·E1 |
| 79-83 | tabloul de inverse în O(cap) | T4·E1, F3·E1 |
| 86 | normalizarea factorului `(mModP - i + 1)` | T6·E1, T6·EQ |
| 87-88 | lanțul `term` + acumularea `sum` | T3·E1, T3·EQ, T6·E1 |
| 94-105 | `modPow` — exponențierea rapidă | T4·E1 |

# Necunoscute și inferențe

Etichetate ca atare și în corpul documentului; centralizat:

1. **Limita de timp Codewars (12 s)** — valoarea implicită a platformei; kata-ul nu o afișează. Inferență.
2. **Motivul alegerii modulului 998244353** (prietenos cu NTT) — kata-ul dă doar valoarea; „de ce acest prim" e inferență din practica programării competitive.
3. **Modul exact de eșec al naivilor** (`OutOfMemoryError` la 64 GB; regimul B „în afara fizicii") — dedus din dimensiuni, nu executat.
4. **Versiunea de 3 kyu admite tabloul DP** — inferență din limitele ei (n,m ≤ 20000); netestat.
5. **Semnătura Java exactă cerută de Codewars** — `BigInteger height(BigInteger, BigInteger)` e convenția uzuală pentru acest kata; setup-ul de test Codewars nu a fost inspectat din interior. Inferență; soluția din repo e verificată doar local.
6. **Plafonul** `min(200_000)` — alegere defensivă proprie, nu cerință a kata-ului (T7·EQ).
7. Din harta inițială: repo-ul nu conținea niciun cod pentru kata; `FabergeEggs.java` a fost scris ca ancoră pe 2026-08-22 și verificat prin rulare (exemplele enunțului + cross-check pe 200 de perechi random + ambele regimuri la limite).

# Bilanț de acoperire

- Puncte de lucru: **11/11** (T1-T7, F1-F4)
- Explicații principale (E1+E2+E3): **33/33**
- Întrebări orientative (Q1): **11/11**
- Răspunsuri explica la întrebări (EQ): **11/11** — total 44 de aplicări explica
- Necunoscute rămase: 7, listate mai sus

Acest document este **complet generat**, nu parcurs: niciun criteriu de trecere din harta de învățare nu e bifat, iar bifarea cere predicțiile utilizatorului în sesiuni de lucru cu explicatie-completa.
