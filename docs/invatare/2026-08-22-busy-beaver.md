Busy Beaver (Codeforces 2257E, Round 1117 Div. 2) — hartă de învățare

Țintă: să poți rula de mână cele două faze (maximizezi capitalul construind segmente profitabile în ordinea pragului, apoi întinzi fiecare clădire independent) pe un exemplu mic și să prezici pragul de intrare și profitul net al oricărui segment fără simulare etaj cu etaj.

## Tehnologii (doar cele de pe fir)
- [ ] T1. Profitul net al unui prefix (suma b−a pe etajele construite) — banii finali depind doar de CE s-a construit, nu de ordine
      - [ ] T1.1 Ce se plătește și ce se primește la construirea UNUI etaj, și în ce ordine?
            cod: BusyBeaver.java:24-25 (câmpurile a, b) + 122-123
            trecere: prezice soldul după un etaj dat (a, b, sold inițial), inclusiv cazul neplătibil
      - [ ] T1.2 Cum se calculează banii unei stări întregi doar din etajele construite?
            cod: BusyBeaver.java:155-159 (solveOracle recalculează money din stare)
            trecere: calculează banii pentru o stare dată (x + suma b−a) fără să deruleze ordinea
      - [ ] T1.3 De ce două ordini diferite ale acelorași etaje dau aceiași bani, dar nu sunt amândouă jucabile?
            cod: BusyBeaver.java:171 (garda money >= a la fiecare pas)
            trecere: prezice care din două ordini date e legală la un x dat
- [ ] T2. Pragul de intrare al unui segment (capitalul minim ca soldul să nu scadă sub 0 pe parcurs) — maximul deficitului curent
- [ ] T3. Segmentarea unui proiect în segmente minime cu profit net ≥ 0 (tăiere greedy) — orice prefix propriu al unui segment e strict negativ
- [ ] T4. PriorityQueue pe prag (java.util.PriorityQueue, extrage-minim) — bucla „construiește cel mai ieftin segment accesibil"
- [ ] T5. Simularea independentă din Faza 2 (etaj cu etaj, pornind din înălțimea Fazei 1) — banii NU se pierd între încercările pe clădiri diferite
- [ ] T6. Limitele și tipul long (x ≤ 10^18, Σm ≤ 2·10^5, a,b ≤ 10^9) — de ce long ajunge fără BigInteger
- [ ] T7. Oracolul brute force (BFS pe vectorul de înălțimi; banii = funcție de vector) — proba contra căreia verifici greedy-ul

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Exemplul 2 din enunț (x=3, două clădiri) → etajul 1 al clădirii 2 finanțează clădirea 1 → răspuns „2 1"   (firul reprezentativ)
- [ ] F2. Exemplul 1 (x=6, o clădire fără niciun segment profitabil) → Faza 1 nu construiește nimic → Faza 2 urcă singură toate cele 4 etaje
- [ ] F3. Un input cu 3 clădiri → bucla PriorityQueue construiește segmentele în ordinea pragului → capitalul maxim atins
- [ ] F4. Aceleași date în Faza 2 → înălțimi finale independente, tie-break pe indexul mic, înălțime 0 → index 1
- [ ] F5. Un prefix propriu de segment pare că ar „finanța" altă clădire → de ce e mereu pierdere → capitalul Fazei 1 e maximal
- [ ] F6. Fuzz: sute de inputuri mici aleatoare → fast == oracol BFS pe fiecare (proba de corectitudine)

## Perechi de separat (risc de amestec)
- P1. profitul net al segmentului (cu cât cresc banii la final) vs pragul de intrare (câți bani trebuie SĂ AI la start) — două numere diferite pe același segment
- P2. înălțimea construită în Faza 1 (persistentă, plătită o dată, comună) vs simularea Fazei 2 (ipotetică, per clădire, aruncată între încercări)
- P3. ordinea etajelor în aceeași clădire (fixă, 1→m) vs ordinea între clădiri (liberă, cu întrețesere) — exact ambiguitatea reclamată la enunț
- P4. a (plătit ÎNAINTE de etaj) vs b (primit IMEDIAT după etaj) — momentul fluxului de bani care definește pragul

## Necunoscute din discovery
- Repo-ul nu conține nicio soluție (0 hit-uri pentru „2257"/„beaver" în src și docs). Ancorele fișier:linie cer cod nou: propunere `src/test/java/com/footballmanagergamesimulator/algorithms/greedy/BusyBeaver.java` (oracol BFS + soluția cu două faze + runner), pachet nou `greedy/` lângă dp/geometry/numbertheory — decizie de confirmat, nu cod existent.
- Soluția în două faze și complexitatea O(Σm·log n) vin din editorialul oficial (blog Codeforces 156058), nu din cod verificabil local — inferență externă până la implementare + fuzz-ul din F6.
