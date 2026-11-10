Bermuda Rectangle (Codeforces 2257D, Div. 2) — hartă de învățare

Țintă: să poți calcula de mână răspunsul unui query (S, x, y) prin scara divizorilor, să prezici care coloane contribuie y și care S/d, și de ce brute force pe celule moare la S=10^14.

## Tehnologii (doar cele de pe fir)
- [ ] T1. Divizorii lui S prin trial division (împărțiri de probă până la √S) — fiecare divizor a dă dreptunghiul-candidat a × S/a
- [ ] T2. Scara divizorilor (staircase, uniunea dreptunghiurilor) — coloana i are înălțimea S / (cel mai mic divizor ≥ i); invariantul care face query-ul rezolvabil
- [ ] T3. Sume parțiale pe trepte (prefix sums, arii cumulate) — aria primelor j trepte într-o singură citire, fără buclă per query
- [ ] T4. Căutare binară pe divizorii sortați (lower/upper bound) — găsește treapta lui x și ultimul divizor ≤ prag
- [ ] T5. Împărțirea întreagă S/y ca prag (floor, rotunjire în jos) — separă coloanele cu înălțime ≥ y de restul
- [ ] T6. Complexitate O(√S + q·log d(S)) vs O(x·y) per query — de ce limitele (S≤10^14, Σ√S≤10^7, Σq≤3·10^5) forțează scara
- [ ] T7. Oracol brute force (dublu for pe celule + test de acoperire pe divizori) — proba de corectitudine pe inputuri mici

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Dai S=6, query (4,5) la oracol → testează 20 de celule contra divizorilor {1,2,3,6} → 11 (exemplul ilustrat din enunț)
- [ ] F2. Același query la soluția rapidă → scara cu trepte la divizori + min(înălțime, y) pe coloane → același 11, fără să atingă celulele
- [ ] F3. Dai query (6,6) care acoperă toată scara lui S=6 → suma ariilor treptelor → 14 (toată uniunea)
- [ ] F4. Dai query (1,1) sub prima treaptă → o singură celulă, mereu acoperită de 1×S → 1
- [ ] F5. Dai S=8 (divizori {1,2,4,8}), query (5,6) → treaptă tăiată de x + prag S/y → 15
- [ ] F6. Fuzz: sute de perechi (S, x, y) mici aleatoare → fast == oracol pe fiecare (proba de corectitudine)

## Perechi de separat (risc de amestec)
- P1. Divizorul a (lățimea) vs S/a (înălțimea) — aceeași pereche de numere, două roluri; schimbi rolul, schimbi treapta
- P2. Celula (pătratul unitate, 1-indexată) vs punctul/colțul de coordonate — sursa off-by-one la testul de acoperire
- P3. Dreptunghiul query (x,y) vs dreptunghiul Bermuda (a, S/a) — ambele ancorate în (0,0), doar unul e necunoscut
- P4. Binary search pe x (câte coloane numeri) vs binary search pe pragul S/y (care coloane dau y întreg) — două căutări, ținte diferite
- P5. floor(S/y) (pragul aritmetic) vs cel mai mare divizor ≤ S/y (treapta reală) — pragul cade de obicei ÎNTRE divizori

## Necunoscute din discovery
- Repo-ul nu conține nicio soluție pentru 2257D (0 hit-uri). Ancorele fișier:linie cer cod nou în pachetul-laborator existent `src/test/java/com/footballmanagergamesimulator/algorithms/numbertheory/` (propunere: `BermudaRectangle.java` cu oracol + soluție rapidă + runner, după modelul `FourSquaresLab.java`) — decizie de confirmat, nu cod existent.
- Tag-urile oficiale includ și „two pointers"; varianta cu two pointers nu e acoperită de planul ăsta (aleg varianta cu binary search) — inferență din pagina problemei, tutorialul nu a fost citit.
