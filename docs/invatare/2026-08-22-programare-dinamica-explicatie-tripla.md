# Programare dinamică — manual de explicație triplă

**Repo:** footballmanager-backend

**Branch:** master · **Commit la generare:** 8488435 ("Archive backend feature work and diagnostics")

**Data:** 2026-08-22

**Ținta:** pentru o problemă nouă să poți decide dacă programarea dinamică se aplică (substructură optimă + subprobleme suprapuse), să scrii starea, recurența și ordinea de umplere, să estimezi timpul și memoria înainte de a scrie cod, și să arăți unde în acest repo programarea dinamică ar înlocui un greedy actual.

---

## Cum se citește documentul

Fiecare punct de lucru are exact cinci unități, în această ordine:

| Unitate | Ce conține |
| --- | --- |
| E1 | Traseul mecanic — declanșator, actor exact, apel, efect, cu valori reale și ancore fișier:linie |
| E2 | Modelul mental — de ce există mecanismul, ce invariant ține, ce vocabular folosește |
| E3 | Scenariul predictiv — un exemplu numeric complet plus o variație de eșec sau o limită |
| Q1 | O întrebare orientativă, **simulată** — nu a pus-o utilizatorul, nu presupune că a greșit |
| EQ | Răspunsul la Q1, cu dovezi separate de inferențe |

**Legendă de identificatori:** `T1...T9` = tehnologii și concepte; `F1...F8` = fire de execuție; `P1...P6` = perechi de vocabulare care se amestecă ușor; `E1`, `E2`, `E3` = cele trei explicații ale unui punct; `Q1` și `EQ` = întrebarea orientativă și răspunsul ei.

**Marcaj epistemic:** afirmațiile verificate în cod poartă ancora `fișier:linie`. Ce nu se poate stabili din cod e scris explicit **Necunoscut din cod**. Ce e raționament, nu dovadă, e etichetat **inferență**.

---

## Harta confirmată

**Tehnologii (T)**

- T1. Recursie naivă (metodă care se autoapelează) — forma de plecare a oricărui DP
- T2. Memoizare (cache pe cheia stării) — varianta top-down
- T3. Tabulare (umplere iterativă a tabloului) — varianta bottom-up
- T4. Substructură optimă (optimul global se compune din optime parțiale) — primul test de aplicabilitate
- T5. Subprobleme suprapuse (aceeași stare recalculată) — al doilea test de aplicabilitate
- T6. Spațiul stării + recurența (ce indici definesc o stare) — proiectarea propriu-zisă
- T7. Complexitate = nr. stări × cost tranziție — estimarea dinainte de cod
- T8. Reconstrucția soluției (tablou de alegeri) — de la valoare optimă la decizie optimă
- T9. Optimizare de memorie (rolling array) — când e corectă și când nu

**Fire de execuție (F)**

- F1. Fibonacci(30) recursiv vs memoizat vs tabelat — aceeași valoare, patru costuri
- F2. Buget de transferuri + listă de ținte → rucsac 0/1 alege lotul de rating maxim
- F3. Sumă țintă + tranșe fixe → număr minim de tranșe sau „imposibil"
- F4. Două nume de jucător → tablou 2D de distanță de editare + drumul de operații
- F5. Amicale cu interval și valoare → setul fără suprapuneri de valoare maximă
- F6. Repo: piața de transferuri cheltuie bugetul jucător cu jucător (greedy)
- F7. Repo: XI-ul se completează poziție cu poziție (greedy)
- F8. Repo: `computeIfAbsent` și cache-urile din `MatchRoundSimulator` nu sunt memoizare

**Perechi de separat (P)**

- P1. Memoizare vs cache aplicativ
- P2. Top-down vs bottom-up
- P3. Greedy vs DP
- P4. Divide-et-impera vs DP
- P5. Stare vs indice de buclă
- P6. Valoare optimă vs soluție optimă

---

## Codul-laborator folosit ca ancoră

Repo-ul nu conținea nicio implementare de programare dinamică la generarea manualului (verificat: zero potriviri pentru `dp[`, `knapsack`, `bellman`, `editDistance`, recursie memoizată în `src/main/java`). Pentru ca E1 să aibă traseu mecanic real, manualul se ancorează într-un laborator scris în repo, sub pachetul de teste:

```
src/test/java/com/footballmanagergamesimulator/algorithms/dp/
  FibonacciLab.java      89 linii   T1 T2 T3 T5 T7 T9 F1
  SquadKnapsack.java     82 linii   T4 T6 T8 F2 F6
  CoinChange.java        75 linii   T6 F3
  EditDistance.java      79 linii   T8 F4
  FriendlySchedule.java  85 linii   F5
  DpLabRunner.java       93 linii   punctul de intrare al tuturor firelor
```

Sunt clase simple, fără Spring și fără JUnit: nu intră în suita de teste, se rulează manual. Toate numerele citate în manual vin din rularea de mai jos, nu din memorie.

Notă: același pachet mai conține două fișiere — `BecomeImmortal.java` și `FabergeEggs.java` — care **nu** au fost scrise pentru acest manual și la care manualul nu se referă nicăieri.

```
$ javac -d /tmp/dpout src/test/java/com/footballmanagergamesimulator/algorithms/dp/*.java
$ java -cp /tmp/dpout com.footballmanagergamesimulator.algorithms.dp.DpLabRunner

== F1 fibonacci(30) ==
naive   value=832040 work=2692537
memo    value=832040 work=59
table   value=832040 work=29
rolling value=832040 work=29
== F2 knapsack budget=90M ==
dp     Plan[bought=[Dima, Costea, Barbu], totalRating=230, spentM=90]
greedy Plan[bought=[Ilie, Costea], totalRating=161, spentM=90]
== F3 instalments [4, 6] ==
fee=7M count=UNREACHABLE split=[]
fee=10M count=2 split=[4, 6]
fee=14M count=3 split=[4, 4, 6]
== F4 edit distance Ronaldiho -> Ronaldinho ==
distance=1
ops=[keep R, keep o, keep n, keep a, keep l, keep d, keep i, insert n, keep h, keep o]
== F5 friendlies ==
dp     Plan[accepted=[Porto, Ajax], totalValueK=140]
greedy Plan[accepted=[Genk, Brugge, Porto], totalValueK=115]
```

Firele F6, F7 și F8 nu folosesc laboratorul: se ancorează în codul de producție existent.

---

# Capitolul T — Tehnologii

## T1. Recursie naivă — forma de plecare a oricărui DP

### E1 — Traseul mecanic

Declanșatorul e o singură linie: `DpLabRunner.fibonacci(30)` cheamă `FibonacciLab.reset()`, care pune contorul `work` pe 0 (`FibonacciLab.java:23`), apoi `FibonacciLab.naive(30)` (`DpLabRunner.java:42`).

Actorul e metoda `naive` (`FibonacciLab.java:32`) și face exact trei lucruri, în ordine:

```java
public static long naive(int n) {
    work++;                                  // linia 33 — se numără invocarea
    if (n < 2) {                             // linia 34 — cazul de bază
        return n;
    }
    return naive(n - 1) + naive(n - 2);      // linia 37 — cazul recursiv
}
```

Filmul unei singure invocări: JVM-ul intră în `naive(30)`, incrementează `work`, vede că 30 nu e sub 2, și evaluează întâi operandul stâng — `naive(29)`. Abia când acel apel s-a întors cu o valoare începe `naive(28)`. Când un cadru se întoarce, valoarea lui e adunată de apelant și cadrul dispare; **nimic nu se scrie nicăieri**. `naive(28)` va fi calculat din nou, de la zero, în ramura dreaptă, deși ramura stângă tocmai l-a calculat.

Efectul măsurat, la rularea din capitolul anterior: valoarea 832040 și `work = 2 692 537` invocări. Numărul nu e arbitrar: `2 × fib(31) − 1 = 2 × 1 346 269 − 1 = 2 692 537`. Contorizat pe un caz mic ca să se vadă legea: `naive(5)` face 15 invocări = `2 × fib(6) − 1 = 2 × 8 − 1` (măsurat).

### E2 — Modelul mental

Recursia naivă există pentru că e **transcrierea literală a definiției**. Definiția lui Fibonacci e deja o recurență — `fib(n) = fib(n−1) + fib(n−2)`, cu `fib(0)=0` și `fib(1)=1` — iar metoda `naive` nu adaugă nimic la ea: cazul de bază e prima ramură, cazul recursiv e a doua. De asta e mereu punctul de plecare corect: e singura formă în care nu poți greși recurența, pentru că nu o rescrii.

Invariantul care o definește: **o invocare nu are altă memorie decât propriile argumente**. Nu citește nimic din afara ei, nu lasă nimic în urmă. Consecința directă e că două invocări cu același argument nu se cunosc — costul se dublează la fiecare nivel de adâncime.

Vocabularul minim, definit la punctul de folosire:

- **Invocare** = o intrare în corpul metodei. `naive(28)` invocat de două ori înseamnă două invocări, nu una.
- **Cadru de stivă** (stack frame) = zona în care JVM-ul ține argumentele și variabilele acelei invocări; dispare la `return`.
- **Caz de bază** = ramura care se întoarce fără să mai cheme nimic (`n < 2`). Fără el, recursia nu se termină.
- **Adâncime** = câte cadre coexistă simultan pe cel mai lung lanț de apeluri.

*Regulă generală: recursia naivă e forma corectă a recurenței și forma greșită a calculului — scrii DP pornind de la ea, nu în locul ei.*

### E3 — Scenariul predictiv

Desfășurarea completă pentru `naive(5)`, cu numărul de invocări pe fiecare nod:

```
fib(5)
├── fib(4)
│   ├── fib(3)
│   │   ├── fib(2) → fib(1), fib(0)
│   │   └── fib(1)
│   └── fib(2) → fib(1), fib(0)
└── fib(3)
    ├── fib(2) → fib(1), fib(0)
    └── fib(1)
```

`fib(3)` apare de 2 ori, `fib(2)` de 3 ori, `fib(1)` de 5 ori. Total: 15 invocări pentru un rezultat de 5 (măsurat: `naive(5) work=15`).

Limita, cu cifre: la n = 30 costul e 2,7 milioane de invocări și rularea e instantanee. La n = 50 legea dă `2 × fib(51) − 1 = 40 730 022 147` invocări — de 15 000 de ori mai mult (**inferență din formulă, nerulat**; rularea ar dura ore). Punctul important e că *nu* stiva cedează: adâncimea maximă la `naive(50)` e 50 de cadre, o nimica toată. Cedează numărul de invocări.

A doua limită, alt tip: `long` ține până la `fib(92)`; `fib(93)` depășește `Long.MAX_VALUE` și rezultatul devine negativ în tăcere. Asta nu se repară cu DP — e o limită de tip de date, nu de algoritm.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

În vârful execuției lui `naive(30)`, câte cadre de stivă coexistă simultan — și dacă răspunsul e mic, atunci ce anume costă cele 2 692 537 de invocări?

### EQ — Răspuns

Maximum 30 de cadre. Cel mai lung lanț de apeluri e cel care coboară numai pe ramura `n − 1`: `naive(30) → naive(29) → ... → naive(1)`, adică 30 de cadre suprapuse (`FibonacciLab.java:37`, operandul stâng se evaluează primul). Stiva implicită a JVM-ului ține ordinul zecilor de mii de cadre, deci nu e nici pe departe presată.

Ce costă e **numărul total de invocări în timp, nu numărul lor simultan**. Cele 2 692 537 de invocări nu există odată: 30 sunt vii la un moment dat, restul s-au creat și au murit una după alta. Fiecare a costat un increment, o comparație și o adunare — nimic scump în sine, dar de 2,7 milioane de ori.

Distincția asta e ancora: **adâncime = memorie, număr de invocări = timp**. Recursia naivă e ieftină la memorie (30 de cadre) și scumpă la timp (2,7 milioane de invocări). Memoizarea din T2 nu atinge deloc adâncimea — rămâne 30 — dar taie invocările la 59.

Predicție pentru vecinătate, fără să fie nevoie de răspuns: dacă recurența ar fi `f(n) = f(n−1) + f(n−1)`, adâncimea ar rămâne tot 30, iar numărul de invocări ar fi `2^31 − 1`.

---

## T2. Memoizare — top-down, aceeași recursie cu un tablou în față

### E1 — Traseul mecanic

`DpLabRunner.fibonacci(30)` cheamă `FibonacciLab.memo(30)` (`DpLabRunner.java:46`), care e doar un ambalaj: creează un `HashMap` gol și îl dă mai departe (`FibonacciLab.java:55-56`).

Actorul real e `memo(int n, Map<Integer, Long> table)` (`FibonacciLab.java:41`):

```java
work++;                                      // linia 42
if (n < 2) return n;                         // linia 43-45 — caz de bază
Long cached = table.get(n);                  // linia 46 — se citește ÎNAINTE de coborâre
if (cached != null) return cached;           // linia 47-49 — retur imediat, fără recursie
long value = memo(n - 1, table) + memo(n - 2, table);
table.put(n, value);                         // linia 51 — se scrie DUPĂ ce copiii s-au întors
return value;
```

Filmul: `memo(30)` nu găsește nimic în tabel (e gol), deci coboară pe `memo(29)`, care coboară pe `memo(28)`... până la `memo(1)`, care se întoarce prin cazul de bază. Prima scriere în tabel se face abia la întoarcere, pentru `n = 2` — măsurat: ordinea cheilor scrise pentru `memo(6)` e exact `[2, 3, 4, 5, 6]`. Când `memo(30)` cere apoi al doilea operand, `memo(28)`, tabelul îl are deja: linia 47 se întoarce imediat, fără să mai atingă nimic sub el.

Efectul măsurat: aceeași valoare 832040, dar `work = 59` invocări în loc de 2 692 537. 59 = `2 × 30 − 1`. Dintre ele, 29 chiar calculează (stările 2...30, fiecare o singură dată) și 30 se întorc pe loc, din caz de bază sau din tabel.

### E2 — Modelul mental

Memoizarea nu schimbă algoritmul, schimbă **de câte ori are voie să se execute o stare**: exact o dată. Recurența, ordinea de coborâre și cazul de bază rămân identice cu T1 — se adaugă un singur contract: înainte să calculezi ceva, întreabă tabelul; după ce ai calculat, spune-i tabelului.

Invariantul: **după `table.put(n, value)`, starea `n` e finală și nu se mai recalculează niciodată**. Ține doar pentru că `fib(n)` depinde numai de `n` — aceeași intrare, mereu același rezultat. Dacă rezultatul ar depinde și de altceva (ora, un rând din DB, un câmp mutabil), tabelul ar servi un răspuns învechit fără să dea eroare.

Vocabular:

- **Stare** = tuplul de argumente care identifică unic o subproblemă. Aici starea e un singur întreg, `n`.
- **Cheia memo-ului** = reprezentarea stării. Regula tare: **cheia trebuie să conțină tot ce influențează rezultatul**, altfel tabelul minte.
- **Top-down** = pornești de la întrebarea mare și cobori spre cazurile de bază; ordinea de calcul ți-o impune stiva de apeluri, nu tu.

*Regulă generală: memoizarea e recursia naivă plus promisiunea că o stare se calculează o singură dată — promisiune valabilă exact cât timp cheia acoperă tot ce influențează valoarea.*

### E3 — Scenariul predictiv

`memo(6)`, pas cu pas: coborârea trece prin 6 → 5 → 4 → 3 → 2 → 1 (bază), 0 (bază). La întoarcere se scriu, în ordine, `2=1, 3=2, 4=3, 5=5, 6=8` (ordinea măsurată: `[2, 3, 4, 5, 6]`). Al doilea operand al fiecărui nivel — `memo(4)` pentru `memo(6)`, `memo(3)` pentru `memo(5)` — găsește tabelul deja plin și se întoarce fără să coboare. Total: 11 invocări = `2 × 6 − 1` (măsurat).

Varianta de eșec, cu cifre din laboratorul de rucsac: presupune că ai memoiza `SquadKnapsack.best` după indicele țintei, ignorând bugetul rămas. Pe aceeași listă de 5 ținte, soluția reală depinde de buget — măsurat: la 60M rezultatul e `[Dima, Barbu]` cu rating 153, la 55M e `[Dima, Costea]` cu rating 151, la 90M e `[Dima, Costea, Barbu]` cu rating 230. Un tabel cheiat doar pe indice ar întoarce prima valoare scrisă pentru toate trei bugetele. Nu ar arunca excepție și nu ar încetini nimic — ar da pur și simplu un lot greșit.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Cine decide ordinea în care se umple tabelul la memoizare — și care e prima cheie scrisă la `memo(30)`?

### EQ — Răspuns

Ordinea o decide **stiva de apeluri**, nu tu, iar prima cheie scrisă la `memo(30)` este `2`.

Filmul: scrierea în tabel e la `FibonacciLab.java:51`, adică *după* linia 50, unde ambii copii trebuie să se fi întors deja. Prima invocare care ajunge la linia 51 e cea mai adâncă invocare care nu e caz de bază — `memo(2)`, ai cărei copii, `memo(1)` și `memo(0)`, se întorc direct prin `n < 2` la linia 43. Deci tabelul se umple **de la cazurile de bază în sus**, deși apelurile au pornit de sus în jos. Măsurat pe `memo(6)`: ordinea cheilor este `[2, 3, 4, 5, 6]`.

Asta e diferența operațională față de tabulare (T3): la memoizare, ordinea corectă de umplere apare de la sine, ca efect al recursiei; la tabulare, ordinea e responsabilitatea ta, scrisă în antetul buclei. Memoizarea nu poate umple în ordine greșită; tabularea poate.

Predicție pentru vecinătate: dacă `memo` ar scrie în tabel *înainte* de linia 50, ar scrie o valoare care încă nu există.

---

## T3. Tabulare — bottom-up, bucla ține locul stivei

### E1 — Traseul mecanic

`DpLabRunner.fibonacci(30)` cheamă `FibonacciLab.table(30)` (`DpLabRunner.java:50`). Actorul e `table(int n)` (`FibonacciLab.java:60`) și nu se autoapelează niciodată:

```java
long[] dp = new long[Math.max(2, n + 1)];    // linia 61 — 31 de sloturi pentru n = 30
dp[0] = 0;                                   // linia 62 — cazurile de bază, scrise explicit
dp[1] = 1;                                   // linia 63
for (int i = 2; i <= n; i++) {               // linia 64 — ordinea de umplere, aleasă de tine
    work++;                                  // linia 65
    dp[i] = dp[i - 1] + dp[i - 2];           // linia 66 — tranziția
}
return dp[n];                                // linia 68
```

Filmul: JVM-ul alocă `long[31]`, cu toate elementele 0. Scrie manual cele două cazuri de bază. Apoi bucla intră cu `i = 2`, citește `dp[1] = 1` și `dp[0] = 0`, scrie `dp[2] = 1`; la `i = 3` citește `dp[2]` (tocmai scris) și `dp[1]`, scrie `dp[3] = 2`; și tot așa până la `i = 30`. La ieșire, `dp[30] = 832040`.

Efectul măsurat: `work = 29`, adică exact 29 de adunări — câte stări netriviale există (2...30). Nicio invocare recursivă, nicio adâncime de stivă.

### E2 — Modelul mental

Tabularea răspunde la o singură întrebare: **în ce ordine pot calcula stările, astfel încât fiecare stare să găsească gata calculat tot ce citește?** Recurența e aceeași ca la T1 și T2. Ce se mută e responsabilitatea ordinii — de la stiva de apeluri, la antetul buclei tale.

Invariantul care trebuie să țină la fiecare iterație: **când corpul buclei rulează pentru `i`, toate stările pe care le citește (`i−1` și `i−2`) sunt deja finale**. Aici e garantat de `i` crescător. Într-un tablou 2D, invariantul devine o condiție pe ordinea rândurilor și a coloanelor — și nu mai e evident.

Vocabular:

- **Bottom-up** = pornești de la cazurile de bază și urci spre răspunsul cerut, în ordinea pe care o fixezi tu.
- **Ordinea de umplere** (fill order) = ordinea în care bucla vizitează stările. E o sortare topologică a dependențelor, scrisă de mână.
- **Tranziție** = expresia care produce valoarea unei stări din stări deja calculate (`dp[i] = dp[i-1] + dp[i-2]`).

Avantajul practic față de memoizare: fără stivă de recursie (deci fără limită de adâncime) și fără `HashMap` (deci fără boxing și fără hash pe fiecare acces). Dezavantajul: calculează **toate** stările de la 2 la n, chiar dacă unele n-ar fi fost atinse niciodată de sus în jos.

*Regulă generală: la tabulare, corectitudinea nu stă în recurență, ci în ordinea buclei — recurența poate fi perfectă și rezultatul zero.*

### E3 — Scenariul predictiv

`table(6)`: `dp` pornește `[0, 1, 0, 0, 0, 0, 0]`, apoi bucla scrie `dp[2]=1`, `dp[3]=2`, `dp[4]=3`, `dp[5]=5`, `dp[6]=8`. Cinci iterații, cinci adunări, `work = 5` (`2...6`), și returnează 8.

Varianta de eșec, măsurată, nu presupusă: dacă bucla ar merge invers, `for (int i = n; i >= 2; i--)`, prima iterație ar fi `i = 30`, care citește `dp[29]` și `dp[28]` — încă 0, valoarea implicită a tabloului. `dp[30] = 0 + 0 = 0`, și tot așa în jos. Rezultatul măsurat al acestei variante: **0**, fără nicio excepție și fără niciun avertisment.

Asta e limita reală a lui bottom-up: o ordine greșită de umplere nu se manifestă ca eroare, ci ca răspuns liniștit și greșit. La memoizare (T2), aceeași greșeală e imposibilă — ordinea nu e a ta.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

`table(30)` face 29 de adunări, iar `memo(30)` face 59 de invocări. Înseamnă asta că tabularea e de două ori mai rapidă — și care e, de fapt, numărul comparabil între cele două?

### EQ — Răspuns

Nu — cele două numere nu sunt aceeași unitate de măsură, deci raportul 59/29 nu e un raport de viteză.

Ce numără fiecare: la `memo`, contorul `work` se incrementează la **fiecare intrare în metodă** (`FibonacciLab.java:42`), inclusiv la cele 30 de intrări care se întorc imediat, prin caz de bază sau din tabel. La `table`, contorul se incrementează **în corpul buclei** (`FibonacciLab.java:65`), adică o dată per adunare efectivă.

Numărul comparabil e **numărul de stări calculate**: 29 la ambele (stările 2...30, fiecare o singură dată). Din perspectiva teoriei, ambele sunt O(n) — același ordin, aceeași recurență, același număr de tranziții.

Diferența reală de viteză vine din altceva, nu din contor: `memo` face 59 de apeluri de metodă, 29 de inserări într-un `HashMap<Integer, Long>` cu boxing și hashing pe fiecare acces, și ține până la 30 de cadre pe stivă. `table` face 29 de accesări directe într-un `long[]`, fără boxing și fără cadre. Constanta e mai mică la tabulare, ordinul e identic.

Ancora: **contoarele diferite măsoară evenimente diferite; complexitatea se citește în numărul de stări, nu în numărul de invocări.** De asta T7 pune complexitatea ca stări × tranziții, nu ca apeluri.

Predicție pentru vecinătate: `rolling(30)` face tot `work = 29` (măsurat) — pentru că numărul de stări nu s-a schimbat, doar memoria.

---

## T4. Substructură optimă — primul test de aplicabilitate

### E1 — Traseul mecanic

Actorul e `SquadKnapsack.best` (`SquadKnapsack.java:32`), care umple un tablou 2D `dp[i][b]`: „cel mai bun rating total obținut din primele `i` ținte, cheltuind cel mult `b` milioane". Tranziția e la liniile 39-45:

```java
int skip = dp[i - 1][b];                                  // linia 39 — nu-l cumpăr
if (target.feeM() > b) { dp[i][b] = skip; continue; }      // linia 40-42 — nu am bani
int buy = dp[i - 1][b - target.feeM()] + target.rating();  // linia 44 — îl cumpăr
dp[i][b] = Math.max(skip, buy);                            // linia 45
```

Filmul unei singure celule, cu valorile reale din rulare. Lista e Ilie (60M, 84), Barbu (35M, 79), Costea (30M, 77), Dima (25M, 74), Enache (20M, 71); bugetul 90M. Când bucla ajunge la `i = 4` (ținta Dima) și `b = 90`:

1. `skip = dp[3][90] = 161` — cel mai bun din primele trei ținte cu 90M (Ilie + Costea).
2. Dima costă 25 ≤ 90, deci se calculează și varianta „îl cumpăr": `dp[3][90 − 25] + 74 = dp[3][65] + 74`.
3. `dp[3][65] = 156` — cel mai bun din primele trei ținte cu 65M (Barbu + Costea).
4. `buy = 156 + 74 = 230`, `max(161, 230) = 230`, deci `dp[4][90] = 230`.

Fragment din tabloul real (rândurile sunt ținte adăugate cumulativ, coloanele bugete):

| | b=25 | b=30 | b=35 | b=65 | b=90 |
| --- | --- | --- | --- | --- | --- |
| i=0 (nimic) | 0 | 0 | 0 | 0 | 0 |
| i=1 Ilie | 0 | 0 | 0 | 84 | 84 |
| i=2 Barbu | 0 | 0 | 79 | 84 | 84 |
| i=3 Costea | 0 | 77 | 79 | 156 | 161 |
| i=4 Dima | 74 | 77 | 79 | 156 | **230** |
| i=5 Enache | 74 | 77 | 79 | 156 | 230 |

Ce s-a folosit ca să se producă 230: două numere, `161` și `156`. **Nicio listă de nume.**

### E2 — Modelul mental

Substructura optimă e proprietatea care face posibil tot restul: **soluția optimă a problemei mari conține soluții optime ale subproblemelor ei**. Testul practic e mai brutal decât definiția: „ca să decid ce fac cu ținta curentă, îmi ajunge valoarea optimă a subproblemelor rămase, sau am nevoie și de conținutul lor?"

La rucsac ajunge valoarea. `dp[4][90]` s-a compus din 161 și 156, fără să știe cine sunt jucătorii din spatele lor. Asta e ce înseamnă că **starea e un rezumat suficient**: tot ce mai influențează deciziile viitoare e captat de perechea (câte ținte am mai parcurs, cât buget mi-a rămas).

Vocabular:

- **Subproblemă** = aceeași întrebare, pe un domeniu mai mic. Aici: aceeași întrebare, dar cu mai puține ținte și mai puțini bani.
- **Rezumat suficient** = starea conține tot ce trebuie ca să continui; identitatea alegerilor trecute nu mai contează.
- **Compunere** = valoarea unei stări se obține din valorile altor stări, prin `max` sau `min`, plus un cost local.

Contraexemplul clasic, ca să nu pară că proprietatea e gratuită: cel mai *lung* drum simplu într-un graf nu are substructură optimă — cel mai lung drum de la A la C nu e format din cel mai lung drum de la A la B plus cel mai lung de la B la C, pentru că cele două pot refolosi noduri și drumul rezultat nu mai e simplu.

*Regulă generală: dacă ai nevoie să știi CE conține subsoluția, nu doar cât valorează, starea ta e incompletă — nu problema e nepotrivită pentru DP.*

### E3 — Scenariul predictiv

Predicție verificabilă pe același tablou: `dp[5][90]` (adăugăm Enache, 20M, 71). Alternativele sunt `skip = dp[4][90] = 230` și `buy = dp[4][70] + 71`. `dp[4][70]` e cel mult 156, deci `buy ≤ 227` și celula rămâne 230 — Enache nu intră. Valoarea măsurată în tablou: 230, iar planul returnat e `[Dima, Costea, Barbu]` cu 90M cheltuiți exact.

Varianta de eșec, concretă: presupune că regulamentul de salarizare face ca prețul lui Costea să crească la 40M dacă clubul l-a cumpărat deja pe Barbu. Celula `dp[3][65] = 156` (Barbu + Costea la 65M) devine o combinație imposibilă — costă 75M în realitate. Codul de la linia 44 ar folosi-o oricum, iar rezultatul final ar rămâne 230 pentru un lot care nu se poate cumpăra cu 90M. Fără excepție, fără avertisment.

Reparația nu e „DP nu se aplică", ci „starea e prea săracă": interacțiunea trebuie băgată în stare (de exemplu, o stare (indice, buget, l-am luat pe Barbu?) — care dublează numărul de stări). Asta e mecanismul prin care problemele de DP explodează: fiecare interacțiune neexprimată în stare devine o dimensiune nouă.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

`dp[3][65] = 156` reține doar numărul 156, nu și faptul că e vorba de Barbu + Costea. Dacă la final vrem lista jucătorilor cumpărați, de unde apare ea, dacă tabloul n-a păstrat-o niciodată?

### EQ — Răspuns

Din **al doilea parcurs al tabloului, în sens invers** — nu dintr-o listă păstrată în timpul umplerii.

Codul e la `SquadKnapsack.java:54-61`, imediat după bucla de umplere:

```java
int b = budgetM;
for (int i = n; i > 0; i--) {
    if (dp[i][b] != dp[i - 1][b]) {     // linia 55 — rândul i a schimbat ceva?
        bought.add(targets.get(i - 1).name());
        b -= targets.get(i - 1).feeM();
    }
}
```

Filmul, pe tabloul real: pornim din `dp[5][90] = 230` și `b = 90`. La `i = 5`: `dp[5][90] = 230` și `dp[4][90] = 230` — egale, deci Enache nu a fost cumpărat, `b` rămâne 90. La `i = 4`: `dp[4][90] = 230` dar `dp[3][90] = 161` — diferite, deci Dima a fost cumpărat; se adaugă la listă și `b` scade la 65. La `i = 3`: `dp[3][65] = 156` vs `dp[2][65] = 84` — diferite, Costea intră, `b = 35`. La `i = 2`: `dp[2][35] = 79` vs `dp[1][35] = 0` — Barbu intră, `b = 0`. La `i = 1`: `dp[1][0] = 0` = `dp[0][0]` — Ilie nu intră. Lista rezultată e exact cea măsurată la rulare: `[Dima, Costea, Barbu]`.

Ce face de fapt comparația de la linia 55: întreabă „a schimbat rândul `i` valoarea față de rândul de deasupra?". Dacă da, singura explicație posibilă e că ținta `i` a fost cumpărată în soluția optimă a acelei celule.

Ancora: **tabloul păstrează valori; deciziile se recuperează comparând valori vecine, la sfârșit.** Detaliile mecanismului sunt în T8, unde varianta alternativă e un al doilea tablou care reține direct alegerea.

Predicție pentru vecinătate: cu buget 55M în loc de 90M, mersul înapoi dă `[Dima, Costea]` și rating 151 (măsurat).

---

## T5. Subprobleme suprapuse — al doilea test de aplicabilitate

### E1 — Traseul mecanic

Suprapunerea nu e o proprietate abstractă, e un număr pe care îl poți contoriza. În arborele lui `naive(5)` (T1, E3), invocările pe stare sunt: `fib(4)` o dată, `fib(3)` de 2 ori, `fib(2)` de 3 ori, `fib(1)` de 5 ori. Total 15 invocări pentru **4 stări netriviale**.

La n = 30, cifrele măsurate ale aceleiași metode: `naive(30) work = 2 692 537` invocări pentru **29 de stări netriviale** (2...30). Media e ~92 800 de invocări per stare distinctă.

Actorul care exploatează suprapunerea e linia 46 din `FibonacciLab`: `Long cached = table.get(n);`. Ea rulează la fiecare intrare în `memo`, înainte de orice coborâre. Rezultatul măsurat: din 59 de invocări la `memo(30)`, 29 chiar calculează (câte stări sunt) și 30 se întorc pe loc — fiecare dintre acele 30 e o suprapunere prinsă.

### E2 — Modelul mental

Substructura optimă (T4) spune că DP e **corect** pentru problema ta. Subproblemele suprapuse spun că DP e **util**. Sunt teste independente și amândouă trebuie să treacă:

- Substructură optimă fără suprapunere → memoizarea nu câștigă nimic; algoritmul corect e divide-et-impera simplu.
- Suprapunere fără substructură optimă → tabelul se umple, dar valorile compuse nu sunt optime.

Testul practic pentru suprapunere: **desenează două niveluri din arborele de recursie și caută același argument de două ori.** Dacă apare, ai suprapunere. La `fib(5)`: `fib(3)` apare și sub `fib(4)`, și direct sub `fib(5)`. Suficient.

Vocabular:

- **Stare distinctă** = un argument (sau tuplu de argumente) diferit. `fib(3)` invocat de 2 ori = o stare, două invocări.
- **Rată de refolosire** = invocări / stări distincte. La `naive(30)`: 2 692 537 / 29. La un divide-et-impera curat: 1.

*Regulă generală: numărul de stări distincte e ce plătești cu DP; numărul de invocări e ce plăteai fără el — raportul dintre ele e câștigul.*

### E3 — Scenariul predictiv

Aplică testul pe mergesort, ca să vezi cum arată un „nu": sortarea listei `[8, 3, 5, 1, 9, 2, 7, 4]` se sparge în `[8, 3, 5, 1]` și `[9, 2, 7, 4]`, apoi în patru perechi, apoi în opt elemente. Niciun subinterval nu apare de două ori — la fiecare nivel, subproblemele acoperă intervale **disjuncte**. Predicția: un tabel de memoizare pe (start, end) ar avea 100% rată de ratare — 15 intrări scrise, zero citite cu succes. Ăsta e P4: divide-et-impera taie în bucăți care nu se ating; DP taie în bucăți care se suprapun.

Al doilea scenariu, la limita opusă: dacă recurența ar fi `f(n) = f(n−1) + 1`, ai avea substructură optimă și *zero* suprapunere (fiecare stare e cerută o singură dată). Memoizarea ar adăuga un `HashMap` și un `get` per apel, fără să elimine nicio invocare — mai lent decât recursia goală, cu același rezultat.

Limita reală a metodei: suprapunerea se plătește în memorie. La `fib(30)` tabelul are 29 de intrări. La rucsacul din T4, tabloul are `(5+1) × (90+1) = 546` de celule. Dacă bugetul ar fi în euro, nu în milioane, același tablou ar avea `6 × 90 000 001` celule — corect matematic, imposibil în practică. Alegerea unității în care se măsoară bugetul e o decizie de proiectare a stării, nu un detaliu.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Dacă suprapunerea e ce face DP util, iar `memo(30)` prinde 30 de suprapuneri din 59 de invocări, de ce `table(30)` nu „prinde" nimic și totuși e la fel de rapid?

### EQ — Răspuns

Pentru că tabularea nu prinde suprapuneri — le **face imposibile din construcție**.

Memoizarea pornește de sus și descoperă, în timpul execuției, că o stare a mai fost cerută: linia 46 din `FibonacciLab` întreabă tabelul și, la 30 dintre cele 59 de invocări, primește un răspuns gata făcut. Există deci un moment în care o suprapunere „se întâmplă" și e prinsă.

Tabularea pornește de jos și vizitează fiecare stare exact o dată, în ordinea buclei (`FibonacciLab.java:64`). Nu există niciodată o a doua cerere pentru `dp[28]` — există o singură citire, la `i = 29`, și încă una la `i = 30`, dar amândouă citesc o valoare deja finală, nu declanșează niciun calcul. Nu e nimic de prins.

Numărul comparabil, iar, e numărul de stări calculate: 29 la ambele (măsurat: `memo work = 59` cu 29 de calcule efective; `table work = 29`). Suprapunerea a fost eliminată în ambele cazuri, prin mecanisme diferite: una o detectează la runtime, cealaltă o exclude prin ordinea de vizitare.

Ancora, ca mnemonic: **memo = „am mai calculat asta?"; tabulare = „calculez fiecare stare o dată, în ordine".** Aceeași complexitate, două mecanisme.

Predicție pentru vecinătate: pe o problemă unde doar o mică parte din stări sunt atinse de sus în jos, memoizarea calculează mai puțin decât tabularea, deși ambele au același ordin de complexitate.

---

## T6. Spațiul stării și recurența — proiectarea propriu-zisă

### E1 — Traseul mecanic

Declanșatorul: `DpLabRunner.instalments()` cheamă `CoinChange.minInstalments(10, new int[]{4, 6})` (`DpLabRunner.java:75`). Actorul e `CoinChange.minInstalments` (`CoinChange.java:26`):

```java
int[] dp = new int[feeM + 1];            // linia 27 — o stare per sumă, de la 0 la 10
Arrays.fill(dp, UNREACHABLE);            // linia 28 — nimic nu e atins încă
dp[0] = 0;                               // linia 29 — singurul caz de bază
for (int sum = 1; sum <= feeM; sum++) {          // linia 31 — ordinea de umplere
    for (int instalment : instalmentsM) {        // linia 32 — tranzițiile din starea sum
        if (instalment > sum) continue;          // linia 33-35
        int previous = dp[sum - instalment];     // linia 36
        if (previous == UNREACHABLE) continue;   // linia 37-39
        dp[sum] = Math.min(dp[sum], previous + 1); // linia 40
    }
}
```

Filmul pentru `feeM = 10`, tranșe `{4, 6}`. Tabloul pornește `[0, ∞, ∞, ∞, ∞, ∞, ∞, ∞, ∞, ∞, ∞]` (∞ = `UNREACHABLE`, adică `Integer.MAX_VALUE`).

- `sum = 1, 2, 3`: ambele tranșe sunt mai mari decât suma → `continue` la linia 33. Rămân ∞.
- `sum = 4`: tranșa 4 încape, `dp[0] = 0`, deci `dp[4] = 1`. Tranșa 6 nu încape.
- `sum = 6`: tranșa 4 duce la `dp[2] = ∞` → sărit la linia 37. Tranșa 6 duce la `dp[0] = 0` → `dp[6] = 1`.
- `sum = 8`: tranșa 4 → `dp[4] + 1 = 2`. Tranșa 6 → `dp[2] = ∞`, sărit. `dp[8] = 2`.
- `sum = 10`: tranșa 4 → `dp[6] + 1 = 2`. Tranșa 6 → `dp[4] + 1 = 2`. `dp[10] = 2`.

Efect măsurat: `fee=10M count=2 split=[4, 6]`. Iar pentru `fee = 7`: nicio tranșă nu ajunge la o stare atinsă, rezultatul rămâne `UNREACHABLE` — măsurat: `fee=7M count=UNREACHABLE split=[]`.

### E2 — Modelul mental

Proiectarea unui DP înseamnă să răspunzi la patru întrebări, în ordinea asta, **înainte** de a scrie cod:

1. **Ce trebuie să știu ca să pot continua?** → asta e starea. La tranșe: doar suma rămasă de acoperit. Un singur întreg, deci tabloul e 1D.
2. **Ce valoare rețin pentru o stare?** → aici, numărul minim de tranșe. Un singur `int`.
3. **Din ce stări se compune?** → tranzițiile: din `sum` pot ajunge scăzând orice tranșă disponibilă, cu cost 1.
4. **Care sunt cazurile de bază și în ce ordine umplu?** → `dp[0] = 0`, apoi sume crescătoare, pentru că `sum − instalment < sum` întotdeauna.

Invariantul: **când bucla ajunge la `sum`, toate stările mai mici sunt finale.** Garantat de faptul că tranșele sunt strict pozitive, deci orice tranziție privește strict în urmă.

Vocabular:

- **Spațiul stării** = mulțimea tuturor stărilor posibile. Aici: `{0, 1, ..., feeM}`, adică `feeM + 1` stări.
- **Sentinelă** = o valoare rezervată care înseamnă „nu e o valoare reală". Aici `UNREACHABLE = Integer.MAX_VALUE` (`CoinChange.java:17`) înseamnă „această sumă nu se poate forma".
- **Tranziție** = o muchie de la o stare la alta, cu un cost. Aici: `sum − 4` și `sum − 6`, ambele cu cost 1.

*Regulă generală: starea e răspunsul la „ce trebuie să știu ca să continui", nu la „pe unde a trecut bucla" — dacă cele două coincid, e o coincidență, nu o regulă (P5).*

### E3 — Scenariul predictiv

Predicție pe același tablou, alt input: `feeM = 14` cu tranșe `{4, 6}`. Stările atinse sunt 4, 6, 8, 10, 12, 14. `dp[14]` se compune din `dp[10] + 1 = 3` sau `dp[8] + 1 = 3` → 3. Măsurat: `fee=14M count=3 split=[4, 4, 6]`. Și `feeM = 12`: `dp[12] = min(dp[8]+1, dp[6]+1) = 2` — măsurat: `2`, cu `split=[6, 6]`.

Varianta de eșec, măsurată: dacă tabloul ar fi inițializat cu 0 în loc de sentinelă — pentru că „0 pare o valoare neutră" — atunci la `sum = 7`, tranșa 4 duce la `dp[3] = 0`, deci `dp[7] = 0 + 1 = 1`. Rularea variantei dă `dp = [0, 0, 0, 0, 1, 1, 1, 1]`: suma 7 raportează **o singură tranșă**, iar sumele 1, 2, 3 raportează zero tranșe. Toate sunt răspunsuri fals-plauzibile, fără nicio excepție aruncată.

Cauza e strict de proiectare a stării: `0` e deja o valoare legitimă (`dp[0] = 0`, cazul de bază), deci nu poate marca în plus „necalculabil". Sentinela trebuie să fie o valoare care nu poate apărea niciodată ca răspuns real.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

La rucsac (T4) starea are două dimensiuni — ținta și bugetul — iar la tranșe are una singură: suma. Ce anume din enunț decide numărul de dimensiuni?

### EQ — Răspuns

Decide **ce alegeri rămân disponibile după ce ai luat o decizie** — nu mărimea problemei și nici numărul de bucle.

La rucsac, fiecare țintă poate fi cumpărată **cel mult o dată**. Deci ca să continui, nu-ți ajunge să știi cât buget ți-a rămas: trebuie să știi și pe cine ai epuizat deja din listă. Două lucruri de reținut → două dimensiuni: `dp[i][b]` (`SquadKnapsack.java:34`).

La tranșe, aceeași tranșă poate fi folosită **de oricâte ori** — `split(14, {4,6})` întoarce `[4, 4, 6]`, cu 4 folosit de două ori. Deci nu contează deloc ce ai folosit până acum; ca să continui, ajunge să știi cât mai ai de acoperit. Un singur lucru de reținut → o dimensiune: `dp[sum]` (`CoinChange.java:27`).

Verificarea că distincția e reală, cu cifre măsurate: dacă rulezi rucsacul cu un singur tablou 1D și bucla de buget **crescătoare**, obții 290 în loc de 230 — pentru că într-o singură trecere aceeași țintă poate fi „cumpărată" de mai multe ori: 290 = 2 x Dima (2 x 74) + 2 x Enache (2 x 71), la exact 50M + 40M = 90M. Cu bucla de buget **descrescătoare**, același tablou 1D dă 230, corect: parcurgerea inversă garantează că `dp[b − fee]` e încă valoarea rândului anterior, adică fără ținta curentă.

Ancora: **dimensiunea suplimentară nu e „complexitate în plus", e regula „fiecare obiect o singură dată" scrisă în stare.** Când regula dispare, dispare și dimensiunea.

Predicție pentru vecinătate: dacă un jucător ar putea fi cumpărat de două ori (nu se poate — dar dacă ar fi vorba de loturi de echipament), starea ar redeveni 1D.

---

## T7. Complexitate = număr de stări × cost per tranziție

### E1 — Traseul mecanic

Numărătoarea se face pe cod, nu pe intuiție. La `SquadKnapsack.best` (`SquadKnapsack.java:32`) sunt exact două bucle imbricate:

```java
for (int i = 1; i <= n; i++) {           // linia 36 — n = 5 ținte
    for (int b = 0; b <= budgetM; b++) { // linia 38 — 91 de valori de buget (0...90)
```

- **Număr de stări** = celulele tabloului: `(n + 1) × (budgetM + 1) = 6 × 91 = 546`.
- **Cost per tranziție** = corpul buclei interioare: o scădere, o adunare, un `Math.max` — timp constant, două opțiuni examinate.
- **Timp total** = `n × (budgetM + 1) = 5 × 91 = 455` execuții ale corpului (numărat la rulare: `transitions=455`).
- **Memorie** = 546 de `int`-uri = 2184 de octeți.

La `FibonacciLab.table` (`FibonacciLab.java:60`) aceeași numărătoare dă: 29 de stări netriviale, o adunare per stare, `work = 29` măsurat, memorie `long[31]` = 248 de octeți.

### E2 — Modelul mental

Formula e una singură și se aplică înainte de a scrie o linie de cod:

> **timp ≈ (număr de stări) × (cost mediu al unei tranziții)**
> **memorie ≈ (număr de stări reținute simultan) × (dimensiunea unei valori)**

De asta ordinea de proiectare din T6 e obligatorie: dacă îți definești starea prima, poți calcula costul înainte să investești în implementare. „Rucsac cu 5 ținte și buget 90" e 455 de pași — scrii codul. „Rucsac cu 500 de ținte și buget în euro" e alt ordin de mărime — nu scrii codul, reproiectezi starea.

Vocabular:

- **Pseudo-polinomial** = un algoritm al cărui timp e polinomial în *valoarea* unui număr din input, nu în *lungimea* lui. `O(n × B)` e pseudo-polinomial: B = 90 se scrie cu 7 biți, dar produce 91 de coloane.
- **Cost per tranziție** = cât costă *o* actualizare de celulă. La rucsac e constant (2 opțiuni); la tranșe e proporțional cu numărul de tranșe; la orarul de amicale devine o căutare.

*Regulă generală: complexitatea unui DP se citește din dimensiunile tabloului înmulțite cu numărul de opțiuni examinate per celulă — nu din numărul de bucle scrise.*

### E3 — Scenariul predictiv

Predicție pentru `CoinChange.minInstalments`: stările sunt `feeM + 1`, iar bucla interioară (`CoinChange.java:32`) trece prin toate tranșele. Deci timp `O(feeM × numărul de tranșe)`. Pentru `feeM = 10` și 2 tranșe: cel mult 20 de execuții ale corpului. Pentru un fee de 250M în tranșe de 5, 10 și 25: `250 × 3 = 750`. Trivial.

Varianta care rupe totul, cu aritmetică explicită (**inferență din formulă, nerulat**): dacă bugetul rucsacului s-ar exprima în euro în loc de milioane, coloanele devin `90 000 001`. Tabloul are `6 × 90 000 001 ≈ 540` de milioane de `int`-uri ≈ **2,16 GB**, iar bucla interioară rulează 450 de milioane de ori. Aceeași problemă, aceeași recurență, același cod — dar o alegere de unitate care îl face imposibil de rulat.

Limita e deci în proiectarea stării, nu în algoritm. Reparațiile obișnuite: rotunjirea bugetului la o unitate mai mare (milioane), sau schimbarea a ceea ce se pune în tablou — de exemplu `dp[i][rating] = costul minim`, ceea ce mută dimensiunea explozivă din buget în rating, unde valorile sunt mici (max 5 × 84 = 420 de coloane).

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Dacă rucsacul 0/1 se rezolvă în 455 de pași măsurați, de ce e clasificat ca problemă grea (NP-hard) și nu ca una rezolvată?

### EQ — Răspuns

Pentru că cei 455 de pași se măsoară față de **valoarea** bugetului, iar clasificarea se face față de **lungimea** inputului.

Filmul concret: inputul acestei rulări e „5 ținte, buget 90". Scris pe hârtie, bugetul ocupă 2 cifre zecimale, adică 7 biți. Tabloul are însă 91 de coloane — aproximativ `2^7`. Dacă mai adaugi o cifră la buget (900 în loc de 90), inputul crește cu un caracter, iar tabloul crește de zece ori. Cu un buget de 30 de biți, tabloul ar avea peste un miliard de coloane, deși inputul are tot câteva zeci de caractere.

Deci `O(n × B)` e polinomial în B, dar B însuși e exponențial în numărul de biți cu care se scrie B. De aici termenul **pseudo-polinomial**: rapid când numerele sunt mici, exploziv când numerele sunt mari, chiar dacă inputul rămâne scurt.

Ce înseamnă practic pentru codul acesta: `SquadKnapsack.best` e o soluție perfect utilizabilă pentru bugete exprimate în milioane, cu ținte puține — regimul în care lucrează piața de transferuri din repo. Nu devine o soluție generală pentru rucsac.

Ancora: **„timp polinomial" se raportează la câți biți are inputul, nu la cât de mari sunt numerele din el.**

Predicție pentru vecinătate: același argument se aplică la `CoinChange` — tabloul are `feeM + 1` intrări, deci un fee de 2 miliarde e imposibil de tabelat, deși se scrie cu 10 cifre.

---

## T8. Reconstrucția soluției — de la valoare optimă la decizie optimă

### E1 — Traseul mecanic

Sunt două mecanisme distincte în laborator, iar diferența dintre ele e memoria consumată.

**Mecanismul A — comparație între valori vecine**, fără memorie suplimentară (`SquadKnapsack.java:54-61`). Detaliat în T4/EQ: mersul înapoi întreabă la fiecare rând `dp[i][b] != dp[i-1][b]?` și deduce decizia din diferență.

**Mecanismul B — tablou de alegeri**, cu memorie suplimentară (`CoinChange.split`, `CoinChange.java:47`):

```java
int[] choice = new int[feeM + 1];              // linia 49 — ce tranșă a produs starea
...
if (dp[sum - instalment] + 1 < dp[sum]) {
    dp[sum] = dp[sum - instalment] + 1;
    choice[sum] = instalment;                  // linia 61 — se reține decizia, nu doar valoarea
}
...
for (int sum = feeM; sum > 0; sum -= choice[sum]) {  // linia 70 — mers înapoi pe alegeri
    parts.add(choice[sum]);
}
```

Filmul pentru `split(14, {4, 6})`: la umplere se scriu `choice[4] = 4`, `choice[6] = 6`, `choice[8] = 4`, `choice[10] = 4`, `choice[12] = 6`, `choice[14] = 4`. Mersul înapoi pornește de la `sum = 14`, ia `choice[14] = 4`, sare la `sum = 10`, ia 4, sare la 6, ia `choice[6] = 6`, sare la 0 și se oprește. Rezultat măsurat: `split=[4, 4, 6]`.

**Mecanismul C — mers înapoi prin tabloul întreg**, la `EditDistance.operations` (`EditDistance.java:54`): tabloul 2D e recalculat, apoi parcurs din colț spre origine, iar la fiecare pas se întreabă care dintre cele trei vecine explică valoarea curentă (liniile 61-74).

### E2 — Modelul mental

Un tablou de DP răspunde la o singură întrebare: **cât valorează optimul**. Nu răspunde la „din ce e făcut". Sunt două întrebări distincte și de asta au nevoie de doi pași distincți — asta e perechea P6.

Cele trei mecanisme diferă doar prin unde ții informația despre decizie:

| Mecanism | Memorie suplimentară | Cost la reconstrucție | Condiție |
| --- | --- | --- | --- |
| A. Comparație de valori vecine | zero | O(numărul de rânduri) | tabloul complet trebuie păstrat |
| B. Tablou de alegeri | un tablou în plus | O(lungimea soluției) | se scrie în timpul umplerii |
| C. Mers înapoi prin tablou | zero | O(dimensiunilor tabloului) | tabloul complet trebuie păstrat |

Invariantul comun: **reconstrucția are nevoie ori de tabloul complet, ori de tabloul de alegeri.** Dacă arunci ambele — cazul optimizării de memorie din T9 — mai ai valoarea, dar nu mai ai soluția.

Vocabular:

- **Valoare optimă** = numărul din colțul tabloului (230, 3, 1).
- **Soluție optimă** = mulțimea sau secvența de decizii care produce numărul (`[Dima, Costea, Barbu]`, `[4, 4, 6]`, lista de operații).

*Regulă generală: umplerea tabloului calculează valoarea; soluția se recuperează într-un al doilea parcurs, invers — și acel parcurs are propriile lui cerințe de memorie.*

### E3 — Scenariul predictiv

Predicție pe `EditDistance.operations("Ronaldiho", "Ronaldinho")`. Tabloul are `10 × 11` celule, iar colțul `dp[9][10] = 1`. Mersul înapoi pornește de acolo: ultimele caractere coincid (`o` = `o`), deci pasul e „keep o" și se merge pe diagonală; la fel pentru `h`; la poziția unde sursa are `i` și ținta are `n`, valoarea nu se explică prin diagonală, ci prin celula din stânga — deci pasul e „insert n". Rezultat măsurat:

```
ops=[keep R, keep o, keep n, keep a, keep l, keep d, keep i, insert n, keep h, keep o]
```

Zece pași pentru distanța 1: nouă „keep" (care nu costă) și o inserare (care costă 1). **Numărul de pași din reconstrucție nu e egal cu valoarea optimă** — pașii gratuiți apar și ei în drum.

Varianta de eșec, măsurată: dacă tabloul de distanță e umplut cu doar două rânduri rulante (rândul curent și cel anterior), valoarea finală iese corect — 1, identic cu tabloul complet. Dar mersul înapoi devine imposibil: la pasul din mijloc, rândurile de deasupra nu mai există în memorie. Optimizarea nu strică valoarea, șterge soluția.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Reconstrucția rucsacului întoarce `[Dima, Costea, Barbu]`. E aceasta *singura* combinație care dă 230 la 90M, sau doar una dintre ele — și cine decide, dacă sunt mai multe?

### EQ — Răspuns

Doar **una** dintre ele, iar alegerea o face condiția de la `SquadKnapsack.java:55`, nu problema.

Punctul cheie e ce se întâmplă la egalitate. Bucla înapoi întreabă `dp[i][b] != dp[i - 1][b]`. Dacă rândul `i` **nu** a schimbat valoarea, ținta `i` e declarată necumpărată și mersul continuă în sus. Deci la egalitate — adică atunci când există și o soluție fără ținta `i`, la fel de bună — algoritmul preferă mereu varianta **fără** ținta curentă, care e ținta cu indicele cel mai mare încă neexaminat.

Filmul pe date reale: la `i = 5`, `dp[5][90] = 230` și `dp[4][90] = 230`. Sunt egale, deci Enache e sărit. Asta *nu* dovedește că nu există nicio soluție optimă care să-l conțină pe Enache — dovedește doar că există una la fel de bună fără el, iar codul o alege pe aceea.

Consecință practică, importantă când rezultatul ajunge în fața unui utilizator: **valoarea optimă e unică, soluția optimă nu.** Două implementări corecte ale aceluiași DP pot întoarce loturi diferite, cu același rating total, iar diferența vine exclusiv din cum se rup egalitățile. Dacă vrei un criteriu secundar (de exemplu „la rating egal, lotul mai ieftin"), el trebuie scris explicit — fie în tranziție, fie în comparația de la reconstrucție.

Ancora: **egalitățile din tablou sunt puncte de decizie tăcute; codul le rupe deterministic, dar arbitrar.**

Predicție pentru vecinătate: dacă bucla de reconstrucție ar folosi `>=` în loc de `!=` pe o problemă cu egalități, ai obține un alt lot cu aceeași valoare.

---

## T9. Optimizarea de memorie — când poți arunca rândurile vechi

### E1 — Traseul mecanic

Actorul e `FibonacciLab.rolling` (`FibonacciLab.java:75`), care nu alocă niciun tablou:

```java
long twoBack = 0;                    // linia 79 — dp[i-2]
long oneBack = 1;                    // linia 80 — dp[i-1]
for (int i = 2; i <= n; i++) {
    work++;
    long current = oneBack + twoBack; // linia 83 — aceeași tranziție ca la table()
    twoBack = oneBack;                // linia 84 — fereastra alunecă
    oneBack = current;                // linia 85
}
return oneBack;                       // linia 87
```

Filmul pentru `n = 5`: pornim cu `(twoBack, oneBack) = (0, 1)`. `i=2`: current = 1, fereastra devine (1, 1). `i=3`: current = 2, fereastra (1, 2). `i=4`: current = 3, fereastra (2, 3). `i=5`: current = 5, fereastra (3, 5). Se întoarce 5.

Efectul măsurat pentru n = 30: valoare 832040 — identică cu `table(30)` — și `work = 29`, tot identic. Diferența e strict memoria: `table` alocă `long[31]` (248 de octeți), `rolling` ține două `long`-uri (16 octeți).

### E2 — Modelul mental

Întrebarea care decide dacă optimizarea e legală e una singură: **cât de departe în urmă privește tranziția?**

`dp[i] = dp[i-1] + dp[i-2]` privește doi pași în urmă. Deci în orice moment doar două stări mai pot fi citite în viitor; toate celelalte sunt moarte și pot fi aruncate. Fereastra de două variabile e exact cât trebuie.

Invariantul de menținut: **la începutul iterației `i`, variabilele reținute conțin exact stările pe care tranziția lui `i` le va citi.** Liniile 84-85 sunt cele care restabilesc invariantul pentru iterația următoare; ordinea lor contează (dacă `oneBack` s-ar actualiza înainte de `twoBack`, fereastra ar conține de două ori aceeași valoare).

Vocabular:

- **Fereastră de dependență** = mulțimea stărilor anterioare pe care le citește o tranziție. La Fibonacci: `{i−1, i−2}`. La rucsac: rândul `i−1` întreg.
- **Rolling array** = păstrarea doar a ferestrei, nu a întregului tablou.

*Regulă generală: poți arunca o stare exact când nicio stare viitoare nu o mai citește — fereastra de dependență, nu intuiția, spune care sunt acelea.*

### E3 — Scenariul predictiv

Trei variante măsurate, cu trei verdicte diferite:

**1. Recurență cu fereastră mai largă decât variabilele păstrate — greșit, în tăcere.** Tribonacci (`f(n) = f(n−1) + f(n−2) + f(n−3)`) privește trei pași în urmă. Rulat cu tablou complet, `f(12) = 504`. Rulat cu o fereastră de două variabile, același `n` dă **89** — care e chiar Fibonacci(11), pentru că fereastra prea îngustă a transformat recurența în alta. Nicio excepție, niciun avertisment; doar alt algoritm.

**2. Rucsac cu tablou 1D — corect doar cu ordinea potrivită.** Tabloul 2D de la T4 dă 230. Comprimat la un singur rând, cu bucla de buget **crescătoare**, dă **290** (fiecare țintă poate fi reutilizată). Cu bucla **descrescătoare**, dă **230**, corect — pentru că citirile se fac din zona încă neactualizată, adică din rândul anterior.

**3. Distanță de editare cu două rânduri rulante — valoare corectă, soluție pierdută.** Rulat cu două rânduri, rezultatul e 1, exact ca la tabloul complet `10 × 11`. Dar lista de operații nu mai poate fi produsă: mersul înapoi din T8 are nevoie de rândurile intermediare, care au fost suprascrise.

Limita generală: optimizarea de memorie e ultima transformare pe care o faci, nu prima, și se face numai dacă **nu** ai nevoie de reconstrucție.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Varianta cu două rânduri rulante a dat pentru distanța de editare exact 1, adică răspunsul corect. Ce anume s-a pierdut, dacă valoarea e aceeași?

### EQ — Răspuns

S-a pierdut **capacitatea de a spune ce operații compun acel 1** — cu alte cuvinte, soluția, nu valoarea (perechea P6 din T8).

Filmul concret. Tabloul complet pentru „Ronaldiho" → „Ronaldinho" are 10 rânduri și 11 coloane; `EditDistance.operations` (`EditDistance.java:54`) pornește din colțul `dp[9][10]` și urcă pas cu pas, întrebând la fiecare celulă care vecin explică valoarea: diagonala (`dp[i-1][j-1]`), sus (`dp[i-1][j]`) sau stânga (`dp[i][j-1]`). Ca să răspundă la asta la rândul 4, are nevoie de rândul 3 — care, în varianta cu două rânduri, a fost suprascris de rândul 5 cu mult înainte.

Ce rămâne disponibil în varianta optimizată: ultima celulă, `1`. Ce nu mai există: cei 10 pași măsurați ai reconstrucției — `[keep R, keep o, keep n, keep a, keep l, keep d, keep i, insert n, keep h, keep o]`.

Compromisul e cuantificabil: tabloul complet ocupă `10 × 11 = 110` de `int`-uri și permite reconstrucția; varianta rulantă ocupă `2 × 11 = 22` și nu o permite. Pe două nume scurte, economia e irelevantă. Pe două șiruri de câte 100 000 de caractere, tabloul complet ar cere ~40 GB, iar varianta rulantă 800 KB — acolo compromisul devine singura opțiune, iar reconstrucția se recuperează prin alt algoritm (Hirschberg), nu prin tabloul păstrat.

Ancora: **rolling array păstrează răspunsul „cât", nu răspunsul „cum".**

Predicție pentru vecinătate: la rucsacul comprimat pe 1D (varianta descrescătoare, măsurată 230), aceeași pierdere se aplică — valoarea 230 e disponibilă, lista `[Dima, Costea, Barbu]` nu.

---

# Capitolul F — Fire de execuție

## F1. Fibonacci(30): aceeași valoare, patru costuri

### E1 — Traseul mecanic

Declanșatorul e `DpLabRunner.main`, care cheamă `fibonacci(30)` (`DpLabRunner.java:31`). Metoda `fibonacci(int n)` (`DpLabRunner.java:38`) rulează același input prin patru implementări, cu același protocol de măsurare pentru fiecare:

```java
FibonacciLab.reset();                  // linia 41 — contorul pe 0
long naive = FibonacciLab.naive(n);    // linia 42
long naiveWork = FibonacciLab.work();  // linia 43 — se citește IMEDIAT după
```

Secvența se repetă identic pentru `memo` (liniile 45-47), `table` (49-51) și `rolling` (53-55). `reset()` e obligatoriu între variante pentru că `work` e un câmp `static` partajat de toate metodele clasei (`FibonacciLab.java:18`) — fără el, al doilea număr l-ar include pe primul.

Efectul, exact cum îl tipărește rularea:

```
naive   value=832040 work=2692537
memo    value=832040 work=59
table   value=832040 work=29
rolling value=832040 work=29
```

Patru implementări, **o singură valoare**: 832040. Diferența e integral în coloana `work`.

### E2 — Modelul mental

Firul acesta există ca să stabilească un lucru și numai unul: **echivalența rezultatului și neechivalența costului**. Toate patru codifică aceeași recurență; niciuna nu e „mai deșteaptă" matematic. Ce se schimbă e câtă muncă acceptă implementarea să repete.

Ce înseamnă cele trei numere, ca ordine de mărime:

- 2 692 537 — recursia naivă: fiecare stare recalculată de zeci de mii de ori (T5).
- 59 — memoizarea: fiecare stare calculată o dată, plus invocările care se întorc din tabel (T2).
- 29 — tabularea și varianta rulantă: exact atâtea tranziții câte stări netriviale sunt (T3, T9).

Invariantul pe care se sprijină comparația: **contorul măsoară evenimente diferite la variante diferite** — intrări în metodă la primele două, iterații de buclă la ultimele două. De asta 59 vs 29 nu e un raport de viteză, ci două unități diferite (detaliat în T3/EQ). Numărul comparabil între toate patru e „câte stări s-au calculat": 29 peste tot, în afară de varianta naivă.

*Regulă generală: când compari implementări, verifică întâi ce numără contorul — altfel compari două unități de măsură diferite și tragi concluzii false.*

### E3 — Scenariul predictiv

Predicție înainte de rulare, pentru `n = 35`, folosind legile stabilite la T1 și T2: naiv `2 × fib(36) − 1 = 2 × 14 930 352 − 1 = 29 860 703`; memo `2 × 35 − 1 = 69`; tabelar `35 − 1 = 34`.

Rulat: `naive(35) work=29860703`, `memo(35) work=69`, `table(35) work=34`. Toate trei confirmate.

Ce înseamnă asta pentru intuiția de cost: de la n = 30 la n = 35 — cinci unități — varianta naivă crește de 11 ori, iar celelalte cu 10 și respectiv 5 unități. Creșterea exponențială nu se vede pe cazuri mici; se vede în raportul dintre două cazuri mici.

Varianta la limită: dacă `memo` primește un tabel **deja plin** dintr-o rulare anterioară, `memo(30)` face `work = 1` (măsurat, cu tabelul de 29 de intrări). O singură invocare: incrementează contorul, întreabă tabelul la linia 46, primește valoarea și se întoarce de la linia 48 fără să coboare deloc. Ăsta e cazul care face memoizarea atractivă când aceeași funcție e chemată de multe ori cu argumente care se repetă între apeluri — și e exact ce distinge un memo persistent de unul local (P1, detaliat la F8).

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

`table(30)` și `rolling(30)` raportează amândouă `work = 29` și amândouă întorc 832040. Dacă sunt identice și la muncă, și la rezultat, ce anume justifică existența ambelor în laborator?

### EQ — Răspuns

Memoria, și numai memoria — iar diferența e vizibilă doar dacă te uiți la alocări, nu la contor.

`table(30)` alocă `long[31]` la `FibonacciLab.java:61`: 31 de valori de câte 8 octeți, adică 248 de octeți plus antetul tabloului. La ieșire, toate cele 31 de valori sunt disponibile: `dp[7]` e încă acolo dacă îl vrei.

`rolling(30)` nu alocă nimic: două variabile locale, `twoBack` și `oneBack` (`FibonacciLab.java:79-80`), adică 16 octeți pe stivă. La ieșire nu mai există nicio valoare intermediară — `fib(7)` a fost calculat și suprascris.

Deci diferența nu e de viteză (același număr de tranziții, `work = 29` la ambele, măsurat), ci de **ce rămâne disponibil după**. Alegerea între ele e o întrebare despre nevoile ulterioare, nu despre performanță:

- ai nevoie de valorile intermediare (pentru reconstrucție, pentru un răspuns la mai multe întrebări, pentru afișare) → tabloul complet;
- ai nevoie doar de răspunsul final și tabloul ar fi mare → fereastra rulantă (T9).

La `n = 30` diferența e între 248 și 16 octeți, deci irelevantă. La un tablou de rucsac cu buget în euro (T7), diferența e între gigaocteți și kiloocteți, deci decisivă.

Ancora: **contorul măsoară timpul; alocarea măsoară memoria — iar două implementări pot fi identice pe una și foarte diferite pe cealaltă.**

Predicție pentru vecinătate: dacă cineva cere `fib(k)` pentru toți k de la 0 la 30, varianta cu tablou răspunde din memorie, iar cea rulantă trebuie rulată de la capăt pentru fiecare k.

---

## F2. Buget de transferuri: rucsac 0/1 vs cumpărături în ordinea ratingului

### E1 — Traseul mecanic

Declanșatorul e `DpLabRunner.knapsack(90)` (`DpLabRunner.java:63`), care rulează aceeași listă de ținte prin două decizii diferite:

```java
SquadKnapsack.Plan exact  = SquadKnapsack.best(SHORTLIST, budgetM);            // linia 65
SquadKnapsack.Plan greedy = SquadKnapsack.greedyByRating(SHORTLIST, budgetM);  // linia 66
```

Lista e definită la `DpLabRunner.java:13-18`: Ilie 60M/84, Barbu 35M/79, Costea 30M/77, Dima 25M/74, Enache 20M/71.

Ce face fiecare actor:

- `best` (`SquadKnapsack.java:32`) umple tabloul 546 de celule descris la T4, apoi reconstruiește lista prin mers înapoi (liniile 54-61).
- `greedyByRating` (`SquadKnapsack.java:66`) sortează lista descrescător după rating (linia 68), apoi parcurge o singură dată și cumpără orice mai încape (liniile 73-79). Nicio decizie nu se revizuiește.

Efectul măsurat, la buget 90M:

```
dp     Plan[bought=[Dima, Costea, Barbu], totalRating=230, spentM=90]
greedy Plan[bought=[Ilie, Costea],        totalRating=161, spentM=90]
```

Ambele au cheltuit **exact 90M**. Diferența de rating total e 69 de puncte.

### E2 — Modelul mental

Firul acesta izolează perechea P3: greedy vs DP. Diferența nu e „unul e mai inteligent", ci **momentul în care o decizie devine irevocabilă**.

- Greedy: la fiecare pas alege ce pare cel mai bun *acum* (ratingul cel mai mare care încape) și nu mai revine niciodată asupra alegerii. Costul: `O(n log n)` pentru sortare, o singură trecere.
- DP: pentru fiecare stare (câte ținte am parcurs, cât buget mi-a rămas) compară ambele variante — cumpăr / nu cumpăr — și le păstrează pe amândouă până când una devine imposibil de câștigat. Costul: 455 de tranziții.

De ce pierde greedy aici, concret: primul lucru pe care îl face e să-l cumpere pe Ilie, cel mai bine cotat (84), la 60M. Din cele 30M rămase mai încape doar Costea (77). Total 161. Ilie a consumat două treimi din buget pentru un singur rating de 84, iar decizia nu mai poate fi retrasă când se vede că Barbu + Costea + Dima ar fi dat 230 pe aceiași bani.

Vocabular:

- **Greedy (lacom)** = alege optimul local la fiecare pas, fără revenire.
- **Irevocabil** = odată luată decizia, stările alternative nu mai sunt reprezentate nicăieri — asta e diferența tehnică față de DP, care ține toate alternativele vii, în tablou.

*Regulă generală: greedy și DP diferă prin ce se întâmplă cu alternativele — greedy le aruncă la fiecare pas, DP le păstrează într-o celulă până se dovedesc inferioare.*

### E3 — Scenariul predictiv

Comportamentul la bugete diferite, măsurat:

| Buget | DP | rating DP | cheltuit DP | Greedy | rating greedy |
| --- | --- | --- | --- | --- | --- |
| 55M | Dima + Costea | 151 | 55M | Barbu + Enache | 150 |
| 60M | Dima + Barbu | 153 | 60M | Ilie | 84 |
| 70M | Costea + Barbu | 156 | **65M** | Ilie | 84 |
| 90M | Dima + Costea + Barbu | 230 | 90M | Ilie + Costea | 161 |
| 120M | Enache + Dima + Costea + Barbu | 301 | **110M** | Ilie + Barbu + Dima | 237 |

Trei lucruri de citit în tabel:

1. Cel mai prost caz pentru greedy e la 60M — cumpără un singur jucător de 84 și blochează bugetul, în timp ce DP ia doi și obține 153.
2. La 70M și la 120M, DP **cheltuie mai puțin** decât are: 65M din 70M, 110M din 120M. Restul nu poate cumpăra nimic util, iar tabloul nu are niciun motiv să-l consume — `dp[i][b]` înseamnă „cel mult `b`", nu „exact `b`".
3. La 55M diferența e de un singur punct (151 vs 150). Greedy nu e mereu dezastruos; e nesigur. Nu poți ști dinainte pe care input pierde.

Limita firului: laboratorul are 5 ținte și un buget cu 91 de valori. La 500 de ținte și buget de 5 miliarde exprimat în euro, tabloul devine imposibil (T7), iar greedy rămâne singurul care rulează — moment în care întrebarea nu mai e „care e optim", ci „cât de mult pierd cu greedy".

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

La bugetul de 90M, ambele metode cheltuie exact 90M. Dacă banii cheltuiți sunt identici, ce resursă a irosit de fapt varianta greedy?

### EQ — Răspuns

A irosit **eficiența banilor**, adică ratingul cumpărat per milion — și asta se vede doar dacă compari raportul, nu suma.

Cifrele exacte, din rulare: greedy cumpără Ilie (60M pentru 84) și Costea (30M pentru 77), adică 161 de puncte de rating pentru 90M — **1,79 puncte pe milion**. DP cumpără Barbu (35M/79), Costea (30M/77) și Dima (25M/74), adică 230 de puncte pentru aceiași 90M — **2,56 puncte pe milion**.

Unde s-a produs pierderea, ca film: singura decizie diferită între cele două planuri e prima. Greedy sortează după rating și îl ia pe Ilie, care are cel mai mare rating absolut (84) și cel mai prost raport (84 / 60 = 1,4 puncte pe milion). DP nu are noțiunea de „cel mai bun jucător"; are doar celula `dp[1][90] = 84` pusă alături de `dp[1][30] = 0`, iar mai târziu, la `dp[3][90]`, varianta care îl conține pe Ilie (161) pierde în fața celei care nu-l conține.

Nuanța importantă, ca să nu rămână ideea greșită că „ordonarea după raport rezolvă problema": nu o rezolvă. Un greedy care ar sorta după rating/milion l-ar lua pe Enache (3,55), apoi Dima (2,96), apoi Costea (2,57), apoi Barbu — 20 + 25 + 30 = 75M, mai rămân 15M și nimic nu mai încape, total 222. Tot sub 230. Raportul e o euristică mai bună, nu o soluție: rucsacul 0/1 nu se rezolvă prin nicio ordonare fixă, pentru că optimul depinde de cum se completează sumele, nu de o valoare individuală (**calcul aritmetic pe datele măsurate, nu rulare separată**).

Ancora: **greedy optimizează obiectul curent; DP optimizează combinația.**

Predicție pentru vecinătate: la un buget de 60M, aceeași ordonare după raport ar da Enache + Dima = 145, mai bine decât cei 84 ai greedy-ului după rating, dar tot sub cei 153 ai DP-ului.

---

## F3. Sumă țintă în tranșe fixe: minim, sau „imposibil"

### E1 — Traseul mecanic

Declanșatorul e `DpLabRunner.instalments()` (`DpLabRunner.java:71`), care fixează tranșele la `{4, 6}` și rulează trei fee-uri: 7, 10 și 14 (`DpLabRunner.java:74`). Pentru fiecare cheamă două metode diferite pe același input:

```java
int count = CoinChange.minInstalments(fee, instalments);   // linia 75 — doar numărul
... CoinChange.split(fee, instalments)                     // linia 78 — și componența
```

Efectul măsurat:

```
fee=7M  count=UNREACHABLE split=[]
fee=10M count=2           split=[4, 6]
fee=14M count=3           split=[4, 4, 6]
```

Mecanica umplerii e detaliată la T6/E1; ce contează aici e **cum arată eșecul**: pentru 7, tabloul rămâne cu `UNREACHABLE` în poziția finală, iar `split` verifică asta explicit la `CoinChange.java:66` și întoarce listă goală în loc să încerce un mers înapoi imposibil.

### E2 — Modelul mental

Firul acesta există pentru un singur lucru: **la DP, „nu există soluție" trebuie să fie o valoare reprezentabilă în tablou, nu o excepție.**

Motivul e structural. Tabloul se umple de jos în sus, iar starea `sum` e privită de mai multe ori, din tranziții diferite. Dacă lipsa soluției ar fi semnalată prin excepție, prima stare imposibilă ar opri toată umplerea — deși stările de deasupra ei pot fi perfect atinse (pentru 7 tranșe imposibile, dar 8 posibile cu 4 + 4).

Vocabular:

- **Stare neatinsă** = o stare pentru care nu există nicio secvență validă de tranziții pornind de la cazurile de bază. Aici: sumele 1, 2, 3, 5, 7, 9, 11...
- **Sentinelă** = valoarea rezervată care marchează starea neatinsă. Aici `UNREACHABLE = Integer.MAX_VALUE` (`CoinChange.java:17`).
- **Propagare** = o stare neatinsă nu trebuie să contamineze stările care depind de ea; de asta linia 37 sare peste tranziție în loc s-o folosească.

*Regulă generală: într-un tablou de DP, „imposibil" e o valoare ca oricare alta — și trebuie aleasă astfel încât să nu poată fi confundată cu un răspuns real.*

### E3 — Scenariul predictiv

Predicție pe stări vecine, cu aceleași tranșe `{4, 6}`: sumele atinse sunt exact cele pare mai mari sau egale cu 4 (4, 6, 8, 10, 12, 14...), plus 0. Deci 9 trebuie să fie imposibil, iar 12 posibil în 2 tranșe. Măsurat: `fee=9 → 2147483647` (adică `UNREACHABLE`), `fee=12 → 2, split=[6, 6]`. Confirmate.

Varianta de eșec, măsurată — ce se întâmplă dacă scoți garda de la `CoinChange.java:37-39` și lași `Math.min` să se descurce singur: pentru `sum = 5`, tranziția prin tranșa 4 citește `dp[1] = Integer.MAX_VALUE` și calculează `MAX_VALUE + 1`, care în Java **se rotește** la `Integer.MIN_VALUE`. Rezultatul rulat pe fee = 7:

```
dp = [0, 2147483647, 2147483647, 2147483647, 1, -2147483648, -2147483648, -2147483648]
```

Suma 5 raportează `-2 147 483 648` tranșe, iar suma 7 la fel. Nu doar că răspunsul e greșit — e negativ, adică în afara domeniului oricărei mărimi numărabile, și niciun `min` de mai târziu nu-l va mai putea învinge.

Asta e limita concretă a sentinelelor de tip „valoare extremă": ele funcționează doar cât timp nimeni nu face aritmetică peste ele. De aceea garda de la linia 37 nu e o optimizare, e o condiție de corectitudine.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

`minInstalments` și `split` umplu același tablou, cu aceeași recurență. Dacă `split` întoarce oricum și componența, de ce mai există metoda care întoarce doar numărul?

### EQ — Răspuns

Pentru că cele două plătesc costuri diferite, iar diferența e un tablou întreg de memorie.

Ce alocă fiecare, exact:

- `minInstalments` (`CoinChange.java:26`) alocă un singur tablou, `dp`, de `feeM + 1` întregi (linia 27).
- `split` (`CoinChange.java:47`) alocă două: `dp` **și** `choice`, ambele de `feeM + 1` întregi (liniile 48-49). Linia 61 scrie în `choice` de fiecare dată când `dp` se îmbunătățește.

Deci `split` costă dublu în memorie și puțin mai mult în timp, ca să poată răspunde la a doua întrebare — care e perechea P6 din T8: valoarea optimă și soluția optimă sunt două rezultate diferite, iar al doilea nu e gratuit.

Când contează separarea: dacă tot ce te interesează e „încape în 3 tranșe sau nu?", plătești un singur tablou. Dacă vrei să și afișezi tranșele, plătești două. La `feeM = 14` diferența e de 60 de octeți și e absurd s-o discuți; la un tablou 2D de rucsac cu 546 de celule, tabloul de alegeri ar adăuga încă 546; la o problemă cu milioane de stări, decizia devine reală.

Nuanță verificabilă în cod: `split` nu apelează `minInstalments` și nici invers — sunt două umpleri independente ale aceluiași tablou. E duplicare deliberată, ca fiecare variantă să poată fi citită singură; într-un cod de producție ai păstra una singură.

Ancora: **„cât" și „din ce" sunt două întrebări, iar a doua se plătește separat.**

Predicție pentru vecinătate: la un fee imposibil, `split` alocă totuși ambele tablouri, le umple complet, și abia la linia 66 descoperă că nu are ce reconstrui.

---

## F4. Două nume de jucător: tabloul 2D și drumul de operații

### E1 — Traseul mecanic

Declanșatorul e `DpLabRunner.names("Ronaldiho", "Ronaldinho")` (`DpLabRunner.java:34`), scenariul fiind un fișier de salvare care scrie numele ușor diferit față de baza de date. Metoda cheamă două lucruri (`DpLabRunner.java:84-85`): `EditDistance.distance` și `EditDistance.operations`.

Actorul comun e `EditDistance.table` (`EditDistance.java:20`), care construiește un tablou `10 × 11` — un rând per prefix al sursei (9 caractere + prefixul vid), o coloană per prefix al țintei (10 + vid). Cazurile de bază sunt scrise explicit înainte de bucle:

```java
for (int i = 0; i <= rows; i++) dp[i][0] = i;   // linia 26-28 — sursa -> șir vid: i ștergeri
for (int j = 0; j <= cols; j++) dp[0][j] = j;   // linia 29-31 — șir vid -> țintă: j inserări
```

Tranziția e la liniile 35-43: dacă ultimele caractere ale celor două prefixe coincid, celula copiază diagonala fără cost (linia 36); altfel ia minimul dintre trei vecine și adaugă 1.

Tabloul umplut, cu valorile reale:

```
       R  o  n  a  l  d  i  n  h  o
    0  1  2  3  4  5  6  7  8  9 10
R   1  0  1  2  3  4  5  6  7  8  9
o   2  1  0  1  2  3  4  5  6  7  8
n   3  2  1  0  1  2  3  4  5  6  7
a   4  3  2  1  0  1  2  3  4  5  6
l   5  4  3  2  1  0  1  2  3  4  5
d   6  5  4  3  2  1  0  1  2  3  4
i   7  6  5  4  3  2  1  0  1  2  3
h   8  7  6  5  4  3  2  1  1  1  2
o   9  8  7  6  5  4  3  2  2  2  1
```

Efectul măsurat: `distance=1`, iar diagonala de zerouri se rupe exact la rândul `h`, adică acolo unde ținta are un `n` în plus.

### E2 — Modelul mental

Firul acesta e primul unde starea are **două dimensiuni care nu sunt „poziție și buget", ci „cât am consumat din fiecare din cele două intrări"**. Starea `(i, j)` înseamnă: am transformat primele `i` caractere din sursă în primele `j` caractere din țintă. Valoarea e costul minim al acelei transformări.

Cele trei tranziții sunt cele trei operații permise, și fiecare consumă altceva:

- **Ștergere** — consum din sursă, nimic din țintă: vine din `dp[i-1][j]`.
- **Inserare** — nimic din sursă, consum din țintă: vine din `dp[i][j-1]`.
- **Înlocuire / potrivire** — consum din amândouă: vine din `dp[i-1][j-1]`.

Invariantul: **când se calculează `dp[i][j]`, toate cele trei vecine sunt deja finale.** Buclele imbricate crescătoare (`EditDistance.java:33-34`) garantează asta: rândul de deasupra e complet, iar în rândul curent coloana din stânga a fost deja scrisă.

Vocabular:

- **Prefix** = primele `k` caractere ale unui șir. Starea nu vorbește despre caractere individuale, ci despre prefixe.
- **Diagonală gratuită** = tranziția fără cost, folosită când caracterele coincid (linia 36). Șirul de zerouri de pe diagonala tabloului e semnătura ei vizuală.

*Regulă generală: când problema consumă două intrări în ritmuri independente, starea are câte o dimensiune per intrare — iar tranzițiile sunt exact modurile în care poți consuma.*

### E3 — Scenariul predictiv

Predicție pe direcția inversă: `Ronaldinho → Ronaldiho`. Aceleași două șiruri, rolurile schimbate. Distanța trebuie să rămână 1, iar operația de inserare trebuie să devină ștergere. Măsurat:

```
distance=1  ops=[keep R, keep o, keep n, keep a, keep l, keep d, keep i, delete n, keep h, keep o]
```

Confirmă simetria distanței și asimetria drumului: numărul e același în ambele sensuri, operațiile nu.

Al doilea scenariu, cu nume care nu seamănă: `Ianis → Ionut`. Măsurat: `distance=3`, cu `ops=[keep I, replace a with o, keep n, replace i with u, replace s with t]`. Cinci pași în reconstrucție pentru distanța 3 — două potriviri gratuite și trei înlocuiri.

Limita practică a firului: distanța nu spune nimic despre **sensul** diferenței. „Ianis" și „Ionut" sunt la distanța 3 unul de altul, la fel ca multe perechi de nume complet fără legătură; folosită ca reguli de potrivire automată la import, o distanță mică e un indiciu, nu o dovadă. Tabloul răspunde exact la întrebarea pusă — câte editări — și la nicio alta.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Distanța e 1, dar reconstrucția întoarce 10 operații. Cum pot fi 10 pași un cost de 1 — și ce anume se numără, de fapt, în valoarea din colț?

### EQ — Răspuns

Se numără **doar editările**, iar 9 dintre cei 10 pași nu sunt editări.

Filmul reconstrucției, pe tabloul de mai sus. Mersul înapoi (`EditDistance.java:54`) pornește din `dp[9][10] = 1` și, la fiecare pas, întreabă prin ce vecin se explică valoarea curentă:

- Când ultimele caractere ale prefixelor coincid, prima ramură (`EditDistance.java:61`) merge pe diagonală și emite `keep X`. Celula sursă are **aceeași valoare** — costul nu crește. S-au înregistrat 9 astfel de pași: `R, o, n, a, l, d, i, h, o`.
- Un singur pas trece printr-o celulă cu valoare mai mică cu 1 — cel care emite `insert n`. Acolo se plătește unitatea.

Deci `1 = numărul de pași care au crescut costul`, iar `10 = numărul de pași făcuți prin tablou`. Sunt două mărimi diferite: prima e valoarea optimă, a doua e lungimea drumului de reconstrucție.

Mărimea drumului nu e nici ea arbitrară: fiecare pas consumă cel puțin un caracter dintr-unul din cele două șiruri, iar drumul trebuie să ajungă din colțul `(9, 10)` în `(0, 0)`. Deci lungimea lui e între `max(9, 10) = 10` și `9 + 10 = 19`. Aici a fost minimul posibil, 10, pentru că aproape fiecare pas a consumat din ambele șiruri.

Ancora, valabilă la orice DP cu reconstrucție: **valoarea din colț numără costul, nu pașii — mulți pași pot fi gratuiți.**

Predicție pentru vecinătate: pentru `Ianis → Ionut`, ambele șiruri au 5 caractere, drumul are exact 5 pași, iar 3 dintre ei costă (măsurat: distanță 3, 5 operații).

---

## F5. Amicale de pre-sezon: setul fără suprapuneri de valoare maximă

### E1 — Traseul mecanic

Declanșatorul e `DpLabRunner.friendlies()` (`DpLabRunner.java:88`), cu cinci oferte definite la `DpLabRunner.java:20-25`: Ajax (zilele 1-6, 90k), Genk (2-3, 30k), Brugge (4-5, 35k), Porto (7-9, 50k), Vitesse (8-10, 20k).

Actorul e `FriendlySchedule.best` (`FriendlySchedule.java:28`) și lucrează în trei etape distincte:

**1. Sortare după ziua de final** (linia 30). Ordinea devine: Genk (final 3), Brugge (5), Ajax (6), Porto (9), Vitesse (10).

**2. Calculul „ultimei oferte compatibile"** (liniile 34-43): pentru fiecare ofertă `i`, se caută în urmă ultima ofertă care se termină **strict înainte** de ziua ei de start. Rezultatul măsurat: `previous = [-1, 0, -1, 2, 2]`. Citit pe rând: Genk nu are nimic înainte; Brugge (start 4) are Genk (final 3); Ajax (start 1) nu are nimic; Porto (start 7) are Ajax (final 6, indice 2); Vitesse (start 8) are tot Ajax.

**3. Umplerea tabloului** (liniile 45-51): pentru fiecare ofertă, `max(o refuz, o accept + optimul de dinaintea ei)`. Valorile măsurate: `dp = [0, 30, 65, 90, 140, 140]`.

Filmul celulei decisive, `dp[4]` (oferta Porto): refuz → `dp[3] = 90`; accept → `50 + dp[previous[3] + 1] = 50 + dp[3] = 140`. Câștigă acceptarea. Iar la `dp[5]` (Vitesse): refuz → 140; accept → `20 + dp[3] = 110`. Câștigă refuzul.

Efectul măsurat: `dp Plan[accepted=[Porto, Ajax], totalValueK=140]`, față de `greedy Plan[accepted=[Genk, Brugge, Porto], totalValueK=115]`.

### E2 — Modelul mental

Firul acesta introduce singura noutate structurală față de F2: **tranziția nu mai merge la vecinul imediat.** La rucsac, „nu cumpăr" ducea la `dp[i-1][b]` — rândul de deasupra. Aici, „accept oferta" duce la `dp[previous[i] + 1]`, adică un salt înapoi peste toate ofertele care se suprapun cu ea.

De ce e nevoie de sortarea după ziua de final: ea garantează că, atunci când examinezi oferta `i`, toate ofertele compatibile cu ea sunt **înaintea** ei în listă, deci deja calculate. Fără sortare, invariantul „tot ce citesc e final" cade.

Vocabular:

- **Ofertă compatibilă** = una care se termină strict înainte ca cea curentă să înceapă. Aici Genk (final 3) e compatibilă cu Brugge (start 4), iar Ajax (final 6) e compatibil cu Porto (start 7). Genk și Ajax nu sunt compatibile: zilele 2-3 cad în interiorul intervalului 1-6.
- **`previous[i]`** = indicele ultimei oferte compatibile cu `i`, sau `−1` dacă nu există. Convenția `dp[previous[i] + 1]` transformă acel `−1` în `dp[0] = 0`, fără nicio ramificație suplimentară.
- **Deplasarea cu 1** între tablouri: `previous` e indexat de la 0 pe oferte, `dp` are o poziție în plus la început pentru „nicio ofertă". De asta apare `previous[i - 1]` în bucla lui `dp` (linia 49).

*Regulă generală: când deciziile se exclud pe intervale, tranziția „accept" sare la ultima stare compatibilă — iar sortarea e ce face saltul calculabil.*

### E3 — Scenariul predictiv

Predicție: dacă Ajax ar valora 60k în loc de 90k, acceptarea lui nu ar mai bate combinația Genk + Brugge (30 + 35 = 65). Atunci `dp[3]` ar deveni 65, iar planul optim ar fi Genk + Brugge + Porto = 115 — exact ce alege și greedy. Măsurat pe varianta cu Ajax la 60k: `dp Plan[accepted=[Porto, Brugge, Genk], totalValueK=115]`, `greedy 115`. Confirmat: cele două metode coincid când optimul chiar e „cât mai multe oferte".

Al doilea scenariu, complementar: dacă Ajax e scos complet din listă, DP-ul dă tot 115, cu aceleași trei oferte (măsurat). Deci întreaga diferență de 25k dintre DP și greedy în rularea principală vine dintr-o singură decizie: a accepta o ofertă lungă și scumpă în locul a două scurte și ieftine.

Limita: `previous` se calculează cu o căutare liniară înapoi (`FriendlySchedule.java:37`), deci pasul acesta e `O(n²)`. Pe cinci oferte e irelevant; pe zece mii ar trebui înlocuit cu o căutare binară pe capetele sortate, ceea ce ar face totul `O(n log n)`. Recurența nu se schimbă — se schimbă doar costul unei tranziții (T7).

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Varianta greedy acceptă trei amicale, iar DP-ul doar două. Dacă greedy joacă mai multe meciuri și tot pierde, ce anume optimizează el, de fapt?

### EQ — Răspuns

Optimizează **numărul de oferte acceptate**, nu valoarea lor — și pe inputul din rulare cele două obiective se bat cap în cap.

Ce face exact `greedyEarliestFinish` (`FriendlySchedule.java:69`): sortează după ziua de final, apoi acceptă orice ofertă care începe după ultima acceptată (liniile 76-82). Primul lucru pe care îl vede după sortare e Genk (final 3), deci îl ia. Din acel moment ziua 3 e consumată, iar Ajax — care începe în ziua 1 — devine imposibil, indiferent cât valorează.

Cifrele: greedy strânge Genk (30) + Brugge (35) + Porto (50) = 115k din trei meciuri. DP strânge Ajax (90) + Porto (50) = 140k din două.

Nuanța care contează: **algoritmul greedy „cel mai devreme final" este demonstrabil optim** — dar pentru altă problemă, aceea în care toate ofertele valorează la fel și vrei cât mai multe. Nu e o euristică proastă aplicată prost; e un algoritm corect aplicat unei probleme pe care nu o rezolvă. Din clipa în care ofertele au valori diferite, criteriul „termină cel mai devreme" nu mai are nicio legătură cu obiectivul.

Verificarea că relația nu e generală: cu Ajax la 60k, greedy și DP dau amândouă 115 (măsurat). Greedy nu e mereu mai slab — e mai slab exact când o ofertă lungă valorează mai mult decât cele scurte pe care le blochează.

Ancora: **înainte să judeci un algoritm greedy, verifică ce funcție maximizează el — de multe ori e una vecină cu a ta, nu a ta.**

Predicție pentru vecinătate: dacă toate cele cinci oferte ar valora 50k, greedy ar da 150 (trei meciuri) și DP ar da tot 150 — aceeași valoare, aceleași meciuri.

---

## F6. Repo: piața de transferuri cheltuie bugetul jucător cu jucător

### E1 — Traseul mecanic

Declanșatorul real e sfârșitul de sezon: `EndOfSeasonProcessor` construiește planurile de cumpărare ale cluburilor, apoi cheamă `matchAndSettle` (`EndOfSeasonProcessor.java:771`).

Bugetul fiecărui club e fixat înainte de matching (`EndOfSeasonProcessor.java:755`):

```java
budgetLeft.computeIfAbsent(teamId, id -> {
    Team fresh = teamRepository.findById(id).orElse(team);
    return Math.min(fresh.getTransferBudget(), plan.getSpendingCap());
});
```

Actorul care cheltuie e `matchAndSettle` (`EndOfSeasonProcessor.java:843`) și lucrează astfel:

1. **Ordonează piața o singură dată**, descrescător după rating, cu id-ul jucătorului ca departajare (liniile 852-855). Ordinea asta nu se mai schimbă niciodată.
2. **Parcurge candidații unul câte unul** (linia 865). Pentru fiecare, calculează taxa (liniile 866-868) și caută printre toate planurile de cumpărare un club care are slotul deschis și îl acceptă.
3. **Filtrul de buget** e la linia 898: `if (budgetLeft.getOrDefault(buyerId, 0L) < fee) { sawEligibleButBroke = true; continue; }` — clubul care nu-și permite jucătorul e sărit, iar cazul e numărat separat în funnel.
4. **Departajarea între cluburile care își permit** e la liniile 902-908: câștigă clubul cu `plan.getXiAverage()` cel mai mare — „jucătorul merge la cel mai puternic club care îl vrea".
5. **Tranzacția se comite și bugetul scade** (linia 923): `budgetLeft.merge(bestBuyerId, -fee, Long::sum)`.

Efectul: bugetul unui club se consumă în ordinea în care apar jucătorii **pe piață**, de la rating mare la rating mic. Nicio decizie nu se reevaluează după ce a fost comisă.

### E2 — Modelul mental

Structural, asta e exact varianta greedy din F2, montată pe date reale: sortare descrescătoare după rating, o singură trecere, cumpără dacă îți permiți. Diferența față de laborator e că aici bucla exterioară e pe **jucători**, nu pe cluburi — un jucător e oferit tuturor cluburilor, iar clubul cel mai puternic care îl vrea și îl poate plăti îl ia.

Consecința e cea măsurată în laborator la buget 60M: un club cu 60M care întâlnește devreme un jucător de 84 la 60M îl cumpără și rămâne fără bani, deși doi jucători de 79 și 74 la 35M + 25M i-ar fi dat mai mult. Codul nu are cum să compare cele două variante: în momentul deciziei, ceilalți doi jucători nici măcar nu au fost încă examinați — sunt mai jos în lista sortată după rating.

Vocabular (definit aici, nu presupus):

- **`plan` / `BuyPlanTransferView`** = ce vrea un club: pozițiile deschise și pragurile lui de calitate.
- **`openSlots`** = pozițiile rămase neocupate ale unui club în fereastra curentă; se scade la fiecare transfer comis (linia 922).
- **`MatchingPass`** = piața rulează de două ori pe fereastră: `PRIMARY` (intenția reală) și `CLEARANCE`, unde pragurile scad cu 35 de puncte de rating și taxele se înmulțesc cu 0,6 (`MatchingPass.java:14-21`). A doua trecere relaxează criteriile, **nu** reevaluează deciziile primei treceri.

*Regulă generală: o piață care parcurge o listă sortată o singură dată e greedy prin construcție — a doua trecere de clearance adaugă șanse, nu revizuiri.*

### E3 — Scenariul predictiv

Predicție pe mecanica din cod, cu cifrele laboratorului transpuse: un club cu `budgetLeft = 90M` care întâlnește candidații în ordinea 84, 79, 77, 74, 71 va lua întâi jucătorul de 84 (dacă are slot deschis și trece pragul), rămânând cu 30M; apoi îl poate lua doar pe cel de 77. Total 161 pe 90M — exact cifra măsurată în laborator pentru varianta greedy, față de 230 pentru DP.

Ce **nu** se poate afirma din cod, și e important să fie separat: nu am rulat piața reală ca să măsor pierderea în joc. Diferența de 69 de puncte e din laborator, pe o listă construită pentru a expune fenomenul, nu din simulator — **inferență despre forma algoritmului, nu măsurătoare pe piața reală**.

Ce contrazice o transpunere naivă a rucsacului aici (limita reală a firului):

1. Bucla exterioară e pe jucători, comună tuturor cluburilor. Un rucsac per club ar cere ca fiecare club să-și aleagă lotul independent — dar doi clienți nu pot cumpăra același jucător. Problema globală e una de **asignare**, nu câte un rucsac independent per club.
2. Filtrul de eligibilitate (`canBeTransfered`, linia 891) depinde de sloturi deschise și de praguri care se schimbă în timpul rulării.
3. Prioritatea „weak links first" (linia 889) e o regulă de joc, nu o funcție de maximizat: la `PRIMARY`, un club cu o gaură critică nu are voie să cumpere altceva.

Deci fraza corectă nu e „aici trebuie rucsac", ci: **subproblema „acest club, acest buget, acest set de candidați acceptabili" e un rucsac 0/1, iar piața o rezolvă greedy.**

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Piața rulează de două ori pe fereastră, `PRIMARY` și `CLEARANCE`. Nu înseamnă asta că deciziile *se* revizuiesc, deci că algoritmul nu e chiar greedy?

### EQ — Răspuns

Nu. A doua trecere schimbă **ce e disponibil**, nu ce s-a decis deja.

Ce face concret `CLEARANCE`, din cod: e o valoare de enum cu doi parametri (`MatchingPass.java:21`) — `ratingRelaxation = 35` și `feeMultiplier = 0.6`. Prima coboară pragul de rating pe care un club îl cere de la un candidat; a doua reduce taxa (`applyDiscount`, `MatchingPass.java:40`, folosit la `EndOfSeasonProcessor.java:868`).

Ce **nu** face: nu atinge transferurile comise. Un transfer intrat în `settled` e definitiv — banii au fost deja scăzuți la linia 923, iar slotul a fost închis la linia 922. Când începe clearance-ul, clubul din exemplu are deja 30M în loc de 90M și un slot mai puțin. Relaxarea îi permite să cumpere jucători mai slabi, mai ieftin; nu îi permite să-l dea înapoi pe cel de 84 și să reconsidere combinația.

Diferența față de DP, formulată exact: la DP, alternativa „fără Ilie" continuă să existe în tablou, în celula `dp[3][90]`, până în ultima iterație, când e comparată direct cu alternativa „cu Ilie" — și pierde, 230 la 161. La piața din repo, alternativa „fără jucătorul de 84" nu e reprezentată nicăieri din clipa în care transferul s-a comis; nu există niciun loc din care ar putea reveni.

Ancora: **o a doua trecere cu praguri mai relaxate mărește mulțimea de opțiuni viitoare; revizuirea ar însemna anularea unei decizii trecute — două lucruri diferite.**

Predicție pentru vecinătate: un al treilea `MatchingPass`, cu praguri și mai joase, ar crește numărul de transferuri fără să schimbe niciunul din cele deja făcute.

---

## F7. Repo: XI-ul se completează poziție cu poziție

### E1 — Traseul mecanic

Declanșatorul e orice cerere de primă echipă fără formație salvată de utilizator: `getBestElevenPlayers` (`TacticController.java:1046`) delegă selecția către `selectStarterSlots` (`TacticController.java:1063`).

Actorul lucrează în patru etape:

1. **Grupare pe poziția naturală** (`TacticController.java:1090`):
   `positionToPlayers.computeIfAbsent(playerView.getPosition(), k -> new ArrayList<>()).add(playerView);`
   Fiecare jucător intră într-o singură găleată — cea a poziției lui.
2. **Ordonarea sloturilor formației** (liniile 1099-1101), stabilă, ca să fie determinist cine umple o gaură.
3. **Bucla lacomă, poziție cu poziție** (liniile 1102-1122): pentru fiecare poziție, jucătorii din găleata ei se sortează descrescător după `aptness` (linia 1108), se iau primii `needed` (liniile 1112-1115), iar restul trec în `restPlayers` (liniile 1116-1118). Sloturile rămase neacoperite se pun într-o coadă `openSlots` (liniile 1119-1121).
4. **Umplerea din surplus** (liniile 1125-1129): cei mai apți jucători rămași, indiferent de poziția lor naturală, sunt puși în sloturile deschise, în ordinea cozii.

Criteriul unic de comparare e la `TacticController.java:1150`:

```java
private double aptness(PlayerView player) {
    return player.getRating() * playerValueService.fitnessFactor(player.getFitness());
}
```

Efect: fiecare poziție își ia cei mai apți jucători ai ei, în ordinea fixată la pasul 2, fără să știe ce nevoi au pozițiile care urmează.

### E2 — Modelul mental

Problema de fundal are un nume: **asignare** — ai 11 sloturi și N jucători, fiecare pereche (jucător, slot) are o valoare, și vrei asignarea de valoare totală maximă cu fiecare jucător folosit cel mult o dată.

Codul rezolvă altceva, mai simplu: pentru fiecare poziție, cei mai buni jucători *ai acelei poziții*. E greedy pe două niveluri deodată — greedy în ordinea pozițiilor și greedy în interiorul fiecărei poziții.

Unde apare diferența, ca mecanism: un jucător apare într-o singură găleată, cea a poziției lui naturale (pasul 1). Dacă poziția lui e vizitată prima și el e cel mai apt acolo, e luat acolo — chiar dacă ar fi fost singurul om decent pentru o poziție vizitată mai târziu, care rămâne să fie umplută din `restPlayers` de un jucător mult mai slab.

Vocabular:

- **`aptness`** = rating × factor de formă. E singurul scor de comparare la selecție; nu depinde de slot.
- **Slot deschis (`openSlots`)** = o poziție a formației pentru care nu au fost destui jucători pe poziția naturală; se umple ulterior, din surplus.
- **Asignare** = potrivirea unu-la-unu între două mulțimi, cu un cost per pereche.

*Regulă generală: „cel mai bun jucător la fiecare poziție, în ordine" e greedy pe asignare — rezultatul e corect ca formă, dar nu garantează suma maximă.*

### E3 — Scenariul predictiv

Scenariu concret, construit pe mecanica citită în cod (**inferență din cod, nerulat pe o bază de date reală**): formație cu un slot DC și un slot DM; lotul are trei jucători — X, cu poziția naturală DC și aptness 85; Y, cu poziția naturală DC și aptness 70; Z, cu poziția naturală DM și aptness 45.

Ordinea sloturilor e fixată de `getValueForTacticDisplay` (linia 1100), deci deterministă. Presupunând că DC vine înaintea lui DM: găleata DC conține X și Y, sortate 85, 70; se ia X. Găleata DM conține Z, aptness 45; se ia Z. Total 85 + 45 = 130, iar Y (70) ajunge pe bancă.

Dacă X ar fi fost folosit pe DM și Y pe DC, suma ar fi fost 70 + 85 = 155 minus penalizarea de nefamiliaritate a lui X pe DM. Alternativa nici nu e evaluată: la momentul deciziei pentru DC, bucla nu știe ce se va găsi în găleata DM.

Ce contrazice concluzia „deci trebuie DP aici" — trei limite reale:

1. **Penalizarea de poziție există deja în altă parte**: `playerValueService.familiarityFactor(naturalPos, usedPos)` e aplicat la calculul ratingurilor de meci (`LineupRatingService.java:154`), nu la selecție. Selecția compară `aptness`, care nu depinde de slot; scorul real depinde. Cele două nu sunt aceeași funcție.
2. **Asignarea optimă nu se rezolvă natural cu DP pe stări simple**: algoritmul standard e Hungarian, `O(n³)`; varianta DP e pe submulțimi de sloturi, `O(2^sloturi × jucători)`, adică 2048 × N pentru 11 sloturi — rulabilă, dar nu evident mai bună.
3. **Nu se poate stabili din cod dacă selecția a fost vreodată gândită ca problemă de optimizare** — comentariul de la liniile 1057-1062 descrie intenția ca „cei mai apți jucători pe poziție, restul umplu golurile". **Necunoscut din cod.**

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

Dacă `aptness` nu ține cont de slot, dar ratingul de meci ține (prin `familiarityFactor`), înseamnă că XI-ul ales și XI-ul evaluat folosesc două funcții diferite. Cine le împacă?

### EQ — Răspuns

Nu le împacă nimeni — sunt două etape succesive, cu criterii diferite, iar a doua nu o poate corecta pe prima.

Filmul, pe cod:

1. **Selecția** (`TacticController.selectStarterSlots`, linia 1063) alege cine joacă și pe ce slot, comparând `aptness = rating × fitnessFactor` (linia 1150). Nu apare nicăieri poziția slotului în acest scor.
2. **Evaluarea** (`LineupRatingService`, liniile 153-157) ia XI-ul deja ales și calculează pentru fiecare jucător `positional × familiarityFactor(naturalPos, usedPos) × moraleFactor × fitnessFactor × instructionMultiplier`. Aici slotul contează: un jucător pus în afara poziției lui primește un factor sub 1.

Deci ordinea e: alegi cu un criteriu independent de slot, apoi ești punctat cu un criteriu dependent de slot. Un jucător mutat în `openSlots` la liniile 1125-1129 e ales pentru că e cel mai apt din surplus, și abia apoi penalizat pentru că e într-un slot străin.

E aceeași structură ca la F6: **decizia se ia cu o informație mai săracă decât cea folosită pentru a o judeca.** Diferența e că la piață informația lipsă e viitorul (ce jucători urmează), iar aici e slotul (unde va ajunge jucătorul).

Ce ar însemna „a le împăca", concret: să folosești în selecție exact scorul care va fi folosit la evaluare, adică să compari perechi (jucător, slot), nu jucători. Din acel moment problema devine formal o asignare, iar greedy-ul pe poziții nu mai e suficient. **Inferență din structura codului; nu am rulat nicio comparație numerică între cele două criterii pe date reale.**

Ancora: **când criteriul de selecție și criteriul de evaluare diferă, algoritmul optimizează ceva ce nu se măsoară.**

Predicție pentru vecinătate: pe un lot echilibrat, unde fiecare poziție are jucători proprii, cele două criterii dau același XI — diferența apare abia când un slot rămâne descoperit și se umple din surplus.

---

## F8. Repo: `computeIfAbsent` și cache-urile din simulator nu sunt memoizare

### E1 — Traseul mecanic

În repo apar două structuri care seamănă vizual cu un tabel de memoizare, dar au alt rol.

**Cazul 1 — grupare, nu cache.** `AbstractTransferStrategy.java:139`:

```java
coverByPosition.computeIfAbsent(basePosition, key -> new ArrayList<>()).add(player);
```

Actorul iterează lotul o singură dată și pune fiecare jucător în lista poziției lui. `computeIfAbsent` creează aici o **listă goală** la prima apariție a unei poziții. Nu se citește niciun rezultat calculat anterior: valoarea produsă de lambda nu depinde de cheie și nu e răspunsul la nicio subproblemă. Același tipar la `TacticController.java:1090` și la `PlayerCapabilityService.java:189`.

**Cazul 2 — cache de rezultate scumpe.** `MatchRoundSimulator.java:195-204` declară șapte hărți la nivel de câmp:

```java
private final Map<Long, Double> simpleRatingCache = new ConcurrentHashMap<>();
private final Map<Long, List<PlayerView>> bestElevenCache = new ConcurrentHashMap<>();
...
```

Comentariul de deasupra (linia 194) spune explicit „lifetime: app". Cheia e id-ul echipei sau al jucătorului, iar valoarea e rezultatul unei recalculări costisitoare (XI-ul, ratingul simplu, tactica).

Aici chiar există refolosire: a doua interogare pentru aceeași echipă nu recalculează. Structural, e același gest ca la `FibonacciLab.memo` — întreabă harta, calculează doar la ratare.

### E2 — Modelul mental

Perechea P1 se separă pe **ce identifică cheia**, nu pe tipul de date. Ambele sunt `Map`. Diferența e ce înseamnă intrarea:

| | Memoizare (T2) | Cache aplicativ (repo) |
| --- | --- | --- |
| Cheia | o **stare** dintr-o recurență (`n`, `(i, b)`) | un **identificator de entitate** (`teamId`, `playerId`) |
| Valoarea | rezultatul unei subprobleme din același calcul | rezultatul unei operații scumpe, de obicei o citire din DB plus agregare |
| Durata | cât ține un singur calcul de sus în jos | cât ține aplicația (`MatchRoundSimulator.java:194`) |
| Invalidarea | nu există — valoarea e matematic finală | obligatorie: datele se schimbă (transferuri, accidentări) |
| Dacă îl scoți | același rezultat, mult mai lent | același rezultat, mai lent — sau, dacă e învechit, alt rezultat |

Rândul decisiv e **invalidarea**. `fib(30)` e 832040 pentru totdeauna; XI-ul echipei 7 nu e. De asta `MatchRoundSimulator` are, pe lângă cache-urile de lungă durată, o zonă separată de stare per rundă, declarată la linia 206 și curățată la finalul rundei — două regimuri de valabilitate, în aceeași clasă.

Vocabular:

- **Memoizare** = tabel de stări al unei recurențe, viu cât ține calculul.
- **Cache** = copie a unui rezultat scump, vie cât timp datele-sursă nu s-au schimbat.
- **Invalidare** = ștergerea intrărilor care nu mai reflectă sursa.

*Regulă generală: dacă intrarea din hartă poate deveni greșită fără ca funcția să se schimbe, e cache — și are nevoie de o poveste de invalidare; memoizarea nu are.*

### E3 — Scenariul predictiv

Predicție pe cazul 1 (`computeIfAbsent` de grupare): dacă un jucător apare de două ori în lotul iterat, nu se întâmplă nimic special — a doua oară găsește lista deja creată și adaugă al doilea element. Un memo ar fi returnat prima valoare și ar fi ignorat a doua. Comportamentele diferă exact pentru că valoarea nu e „răspunsul la cheie", ci un recipient.

Predicție pe cazul 2 (cache de echipă): dacă echipa 7 cumpără un jucător după ce `bestElevenCache` a fost populat, iar cache-ul nu e invalidat, a doua interogare întoarce XI-ul de dinaintea transferului. Nu e o eroare de calcul, e o intrare învechită. **Nu am rulat scenariul; e o inferență din durata de viață declarată la `MatchRoundSimulator.java:194` și din faptul că valoarea derivă din date mutabile.**

Contrastul măsurat, ca ancoră numerică: la `FibonacciLab.memo(30)` cu un tabel deja plin dintr-o rulare anterioară, `work = 1` — o singură intrare în metodă, un `get`, retur. E exact câștigul pe care îl urmărește și un cache aplicativ. Ce nu are cache-ul aplicativ e garanția că valoarea din tabel e încă adevărată.

### Q1 — Întrebare orientativă (simulată, nu pusă de utilizator)

`FibonacciLab.memo` primește harta ca parametru, iar `MatchRoundSimulator` o ține ca `private final` pe clasă. E asta doar o diferență de stil, sau schimbă ceva?

### EQ — Răspuns

Schimbă **cine controlează durata de viață a tabelului** — și asta e diferența operațională dintre memoizare și cache.

La `FibonacciLab.memo(int n, Map<Integer, Long> table)` (`FibonacciLab.java:41`), tabelul e un parametru. Cine cheamă decide dacă e proaspăt sau refolosit. Varianta scurtă, `memo(int n)` (liniile 55-56), creează un `HashMap` nou la fiecare apel, deci tabelul moare odată cu calculul: `memo(30)` face 59 de invocări de fiecare dată. Varianta cu tabel refolosit face 1 (măsurat). Ambele sunt corecte, pentru că `fib` nu se schimbă niciodată.

La `MatchRoundSimulator` (liniile 195-204) hărțile sunt câmpuri `private final` pe un bean singleton, deci trăiesc cât aplicația — nimeni din exterior nu poate cere o versiune proaspătă pasând altă hartă. Consecința e că orice reîmprospătare trebuie să fie o ștergere explicită, scrisă în clasă.

De ce e `ConcurrentHashMap`, nu `HashMap`: același obiect e citit din mai multe fire de simulare simultan — comentariul de la liniile 207-208 spune că simulatorul e singleton și fiecare worker are nevoie de propria vedere asupra stării de rundă. Memoizarea din laborator nu are problema asta: tabelul e local unui singur calcul, într-un singur fir.

Ancora: **parametru = tabel al unui calcul; câmp pe singleton = tabel al aplicației, cu tot ce implică asta (concurență și invalidare).**

Predicție pentru vecinătate: dacă `FibonacciLab.memo` ar promova tabelul la câmp `static`, ar câștiga viteză între apeluri și nu ar risca nimic — pentru că `fib(n)` nu depinde de date mutabile. Același gest pe `bestElevenCache` e exact ce impune o strategie de invalidare.

---

# Glosar

| Termen | Definiție |
| --- | --- |
| Adâncime (de recursie) | Câte cadre de stivă coexistă simultan pe cel mai lung lanț de apeluri. Măsoară memoria, nu timpul (T1). |
| Aptness (repo) | Scorul de selecție al unui jucător: `rating × fitnessFactor`. Nu depinde de slotul ocupat (`TacticController.java:1150`). |
| Asignare | Potrivirea unu-la-unu între două mulțimi (jucători și sloturi), cu o valoare per pereche și obiectivul sumei maxime (F7). |
| Bottom-up | Umplerea tabloului de la cazurile de bază spre răspuns, în ordinea fixată de buclele tale (T3). |
| Cache aplicativ | Copie a unui rezultat scump, valabilă cât timp datele-sursă nu s-au schimbat; are nevoie de invalidare (F8). |
| Cadru de stivă | Zona în care JVM-ul ține argumentele și variabilele unei invocări; dispare la `return` (T1). |
| Caz de bază | Ramura care se întoarce fără să mai cheme nimic (`n < 2`, `dp[0] = 0`). |
| Cheia memo-ului | Reprezentarea stării într-un tabel de memoizare. Trebuie să conțină tot ce influențează rezultatul (T2). |
| `CLEARANCE` (repo) | A doua trecere a pieței de transferuri: praguri de rating mai mici cu 35 și taxe × 0,6 (`MatchingPass.java:21`). |
| Cost per tranziție | Cât costă o singură actualizare de celulă. Al doilea factor din formula de complexitate (T7). |
| Divide-et-impera | Împărțire în subprobleme **disjuncte** (mergesort). Fără suprapunere, memoizarea nu ajută (P4, T5). |
| Fereastră de dependență | Mulțimea stărilor anterioare pe care le citește o tranziție. Decide dacă un rolling array e legal (T9). |
| Greedy (lacom) | Alege optimul local la fiecare pas și nu revine niciodată asupra alegerii (P3, F2). |
| Invalidare | Ștergerea intrărilor de cache care nu mai reflectă sursa. Memoizarea nu are nevoie de ea; cache-ul da (F8). |
| Invocare | O intrare în corpul unei metode. Două invocări cu același argument înseamnă o stare, două invocări (T5). |
| Memoizare | Tabel de stări ale unei recurențe, viu cât ține un calcul; recursie + „am mai calculat asta?" (T2). |
| Ordine de umplere | Ordinea în care bucla vizitează stările; o sortare topologică a dependențelor, scrisă de mână (T3). |
| Prefix | Primele `k` caractere ale unui șir. Starea din distanța de editare vorbește despre prefixe, nu caractere (F4). |
| Pseudo-polinomial | Timp polinomial în *valoarea* unui număr din input, nu în *lungimea* lui. `O(n × B)` la rucsac (T7). |
| Rată de refolosire | Invocări împărțite la stări distincte. La `naive(30)`: 2 692 537 / 29 (T5). |
| Recurență | Relația care exprimă valoarea unei stări prin valorile altor stări. |
| Rolling array | Păstrarea doar a ferestrei de dependență, nu a întregului tablou. Pierde reconstrucția (T9). |
| Rucsac 0/1 | Fiecare obiect e luat întreg sau deloc, sub o limită de capacitate; maximizezi valoarea totală (F2). |
| Sentinelă | Valoare rezervată care marchează „nu e un răspuns real". Aici `UNREACHABLE = Integer.MAX_VALUE` (T6, F3). |
| Soluție optimă | Mulțimea sau secvența de decizii care produce valoarea optimă. Nu e unică (P6, T8). |
| Spațiul stării | Mulțimea tuturor stărilor posibile; dimensiunea tabloului (T6). |
| Stare | Tuplul care identifică unic o subproblemă: tot ce trebuie să știi ca să poți continua (T6). |
| Stare neatinsă | Stare fără nicio secvență validă de tranziții pornind de la cazurile de bază (F3). |
| Substructură optimă | Optimul global se compune din optime ale subproblemelor; primul test de aplicabilitate (T4). |
| Subprobleme suprapuse | Aceeași stare e cerută de mai multe ori; al doilea test de aplicabilitate (T5). |
| Tabulare | Umplerea iterativă a tabloului, fără recursie (T3). |
| Top-down | Pornirea de la întrebarea mare spre cazurile de bază; ordinea o impune stiva de apeluri (T2). |
| Tranziție | Expresia care produce valoarea unei stări din stări deja calculate. |
| Valoare optimă | Numărul din colțul tabloului. Unic, spre deosebire de soluția optimă (P6). |

---

# Index de ancore în cod

**Laborator (`src/test/java/com/footballmanagergamesimulator/algorithms/dp/`)**

| Ancoră | Ce se găsește acolo | Folosit la |
| --- | --- | --- |
| `FibonacciLab.java:32` | recursia naivă, cu contorul de invocări | T1, F1 |
| `FibonacciLab.java:41` | memoizarea top-down; citirea tabelului la linia 46, scrierea la 51 | T2, T5, F1 |
| `FibonacciLab.java:60` | tabularea bottom-up; ordinea de umplere la linia 64 | T3, F1 |
| `FibonacciLab.java:75` | fereastra rulantă de două variabile | T9, F1 |
| `SquadKnapsack.java:32` | umplerea tabloului 2D; tranziția la liniile 39-45 | T4, T7, F2 |
| `SquadKnapsack.java:54` | reconstrucția prin comparație între rânduri vecine | T4, T8 |
| `SquadKnapsack.java:66` | varianta greedy, sortată după rating | F2 |
| `CoinChange.java:17` | sentinela `UNREACHABLE` | T6, F3 |
| `CoinChange.java:26` | starea 1D și garda de propagare (liniile 37-39) | T6, F3 |
| `CoinChange.java:47` | tabloul de alegeri `choice` și mersul înapoi (liniile 61, 70) | T8, F3 |
| `EditDistance.java:20` | tabloul 2D, cazurile de bază la liniile 26-31 | F4 |
| `EditDistance.java:54` | mersul înapoi care numește operațiile | T8, F4 |
| `FriendlySchedule.java:28` | sortarea după final, `previous[]`, umplerea | F5 |
| `FriendlySchedule.java:69` | greedy „cel mai devreme final" | F5 |
| `DpLabRunner.java:38` | protocolul de măsurare `reset` → rulare → `work` | F1 |

**Cod de producție**

| Ancoră | Ce se găsește acolo | Folosit la |
| --- | --- | --- |
| `EndOfSeasonProcessor.java:755` | fixarea bugetului: `min(transferBudget, spendingCap)` | F6 |
| `EndOfSeasonProcessor.java:843` | `matchAndSettle`: ordonarea pieței (852-855), bucla pe candidați (865) | F6 |
| `EndOfSeasonProcessor.java:898` | filtrul de buget: clubul care nu-și permite e sărit | F6 |
| `EndOfSeasonProcessor.java:923` | comiterea tranzacției: `budgetLeft.merge(bestBuyerId, -fee, ...)` | F6 |
| `MatchingPass.java:14` | cele două treceri ale pieței: `PRIMARY` și `CLEARANCE` | F6 |
| `TacticController.java:1063` | `selectStarterSlots`: selecția greedy poziție cu poziție | F7 |
| `TacticController.java:1090` | gruparea pe poziție naturală, cu `computeIfAbsent` | F7, F8 |
| `TacticController.java:1150` | `aptness` — criteriul de selecție, independent de slot | F7 |
| `LineupRatingService.java:154` | `familiarityFactor` — criteriul de evaluare, dependent de slot | F7 |
| `MatchRoundSimulator.java:194` | cache-uri de rezultate, cu durata de viață declarată „app" | F8 |
| `AbstractTransferStrategy.java:139` | `computeIfAbsent` de grupare (nu memoizare) | F8 |
| `PlayerCapabilityService.java:189` | același tipar de grupare | F8 |

---

# Necunoscute și inferențe

Tot ce nu e măsurat sau verificat direct în cod, adunat într-un singur loc.

**Verificat prin căutare în cod**

1. Repo-ul nu conținea nicio implementare de programare dinamică înainte de acest manual: zero potriviri pentru `dp[`, `knapsack`, `bellman`, `editDistance`, `memo` recursiv în `src/main/java`. Laboratorul din `src/test/java/.../algorithms/dp/` a fost scris pentru acest manual.

**Inferențe din formulă sau aritmetică, nerulate**

2. `naive(50)` ar face `2 × fib(51) − 1 = 40 730 022 147` invocări (T1/E3). Calculat, nu rulat.
3. Un tablou de rucsac cu bugetul în euro ar avea `6 × 90 000 001` celule ≈ 2,16 GB (T7/E3). Aritmetică, nu rulare.
4. Un greedy ordonat după raportul rating/milion ar obține 222 la buget 90M (F2/EQ). Calculat pe datele măsurate, fără rulare separată.
5. Reconstrucția distanței de editare are între 10 și 19 pași pentru șiruri de 9 și 10 caractere (F4/EQ). Limite deduse, nu enumerate.

**Inferențe din structura codului de producție, nerulate pe date reale**

6. Pierderea de 69 de puncte de rating dintre greedy și DP (F2) e măsurată în laborator, pe o listă construită ca să expună fenomenul. **Nu am rulat piața de transferuri reală ca să măsor pierderea în joc.**
7. Scenariul X / Y / Z din F7/E3 (doi fundași centrali și un mijlocaș defensiv) e construit din mecanica citită în `selectStarterSlots`, nu rulat pe o bază de date.
8. Afirmația că selecția și evaluarea folosesc criterii diferite (F7/EQ) e citită din cod; **nu am rulat nicio comparație numerică între cele două criterii pe un lot real.**
9. Scenariul de cache învechit din F8/E3 (`bestElevenCache` după un transfer) e dedus din durata de viață declarată la `MatchRoundSimulator.java:194` și din faptul că valoarea derivă din date mutabile. Nu a fost provocat experimental.

**Necunoscut din cod**

10. Nu se poate stabili din cod dacă selecția XI-ului (`TacticController.selectStarterSlots`) a fost vreodată gândită ca problemă de optimizare. Comentariul de la liniile 1057-1062 descrie intenția ca „cei mai apți jucători pe poziție, restul umplu golurile", fără să menționeze vreun obiectiv de maximizare globală.
11. Pachetul `algorithms/dp/` mai conține `BecomeImmortal.java` și `FabergeEggs.java`, apărute în directorul de lucru în timpul generării și scrise în afara acestui manual. Nu sunt referite de niciun punct de lucru și nu au fost verificate aici.
12. Nu se poate stabili din cod dacă cache-urile din `MatchRoundSimulator` sunt invalidate undeva în afara ciclului de rundă; comentariul spune „lifetime: app", dar manualul nu a urmărit toate căile de scriere.

---

# Bilanț de acoperire

| Mărime | Valoare |
| --- | --- |
| Puncte de lucru (frunze din hartă) | 17 (T1-T9, F1-F8) |
| Explicații principale (E1 + E2 + E3) | 51 din 51 |
| Întrebări orientative (Q1) | 17 din 17 |
| Răspunsuri la întrebări (EQ) | 17 din 17 |
| Total aplicări ale metodei explica | 68 |
| Necunoscute și inferențe declarate | 11 |
| Fișiere de laborator scrise pentru manual | 6 (503 linii Java), toate rulate |

**Documentul este complet generat. Asta nu înseamnă că materia e învățată** — bifele de mai sus măsoară acoperirea documentației, nu înțelegerea. Criteriile de trecere din plan rămân netăiate; ele se închid doar prin predicții corecte făcute de tine pe inputuri noi.
