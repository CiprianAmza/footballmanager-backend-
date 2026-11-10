Suma a patru pătrate (kata 1 kyu Codewars) — hartă de învățare

Țintă: pentru orice n până la 2^1024 să poți nara pas cu pas algoritmul care produce (a,b,c,d) cu a²+b²+c²+d²=n în timp polinomial în numărul de biți, să prezici din n mod 8 când e nevoie de al patrulea pătrat și să explici de ce randomizarea schimbă timpul, nu corectitudinea.

## Tehnologii (doar cele de pe fir)
- [ ] T1. Teorema lui Lagrange (orice n ≥ 0 e sumă de 4 pătrate) — garanția că soluția există mereu
- [ ] T2. BigInteger modular (modPow, sqrt, mod — întregi de 1024 biți) — forma nefamiliară: repo-ul îl folosește doar pentru bani
- [ ] T3. Clase de rest mod 8 (restul împărțirii la 8 decide cazul) — comutatorul care alege ramura algoritmului
- [ ] T4. Reducerea 4^k (n = 4^k·m; rezolvi m, înmulțești soluția cu 2^k) — micșorarea inputului fără pierderea soluției
- [ ] T5. isProbablePrime / Miller–Rabin (test de primalitate probabilist, rapid) — filtrul care validează candidatul prim
- [ ] T6. Rădăcina lui −1 mod p (z cu z² ≡ −1 mod p, prin modPow) — cheia descompunerii unui prim în două pătrate
- [ ] T7. Euclid trunchiat / Hermite–Serret (algoritmul lui Euclid oprit sub √p) — transformă z în perechea (a,b) cu a²+b²=p
- [ ] T8. Rabin–Shallit (alege x aleator, speră ca n−x² să fie prim ≡ 1 mod 4) — motorul de performanță, O(log² n) încercări așteptate

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Dai n mic, ex. 42 → căutare directă cu bucle imbricate găsește (a,b,c,d); verificarea a²+b²+c²+d²=n   (firul reprezentativ, baseline-ul lent)
- [ ] F2. Dai p prim ≡ 1 mod 4, ex. 13 → z²≡−1, Euclid trunchiat → (2,3); 4+9=13
- [ ] F3. Dai n uriaș ≡ 1 sau 2 mod 4 → x aleator, p = n−x² trece testul de primalitate → x + cele două pătrate ale lui p
- [ ] F4. Dai n = 4^k·m → cobori la m, rezolvi acolo, urci înmulțind cu 2^k
- [ ] F5. Dai n ≡ 7 mod 8 → scazi un pătrat ales ca să ieși din clasa care cere 4 pătrate → reintri în F3
- [ ] F6. Dai n ~ 2^1024 → filmul complet cap-coadă, cu numărul de încercări măsurat
- [ ] F7. Repo (contrast): BigInteger ca bani cu precizie fixă (DeterministicMarketPriceService) vs BigInteger ca aritmetică modulară aici — același tip, alt regim

## Perechi de separat (risc de amestec)
- P1. Prim ≡ 1 mod 4 vs prim ≡ 3 mod 4 — doar primul e sumă de două pătrate; testul de clasă vine înaintea descompunerii
- P2. Probabil prim vs prim dovedit — Miller–Rabin dă certitudine practică, nu demonstrație; unde ar strica un fals pozitiv
- P3. ⌊√n⌋ întreg (BigInteger.sqrt) vs √n real — d² ≤ n < (d+1)²; off-by-one la margine
- P4. Trei pătrate vs patru pătrate — exact numerele 4^k(8m+7) cer al patrulea pătrat; restul merg din trei
- P5. Randomizat-mereu-corect (Las Vegas: rezultat garantat, timp variabil) vs aproximativ — zarul alege doar cât durează

## Necunoscute din discovery
- Repo-ul nu conține teorie a numerelor: BigInteger apare doar în economy/ (bani), 0 hit-uri pentru modPow/isProbablePrime. Ancorele T/F cer cod nou în repo (propunere: `src/test/java/.../algorithms/numbertheory/FourSquaresLab.java`, ca pattern-ul din `algorithms/dp/`) — decizie de confirmat, nu cod existent.
- Limita de timp exactă a testelor kata (Java) nu apare în descriere — inferență: bugetul standard Codewars ~16s; descrierea spune doar „20 numere ~2^128 și 20 numere ~2^1024".
