Become Immortal (kata Codewars 1 kyu) — hartă de învățare

Țintă: să poți calcula de mână elder_age(m,n,l,t) pe un exemplu mic prin descompunerea în cadrane, să prezici de ce brute force moare la m,n ~ 2^64 și unde anume te arde mod-ul aplicat greșit.

## Tehnologii (doar cele de pe fir)
- [ ] T1. XOR pe biți (sau-exclusiv, bit cu bit) — operația care dă timpul donat de fiecare worshipper (x xor y)
- [ ] T2. Structura XOR pe pătrat 2^k×2^k (fiecare rând = permutare a 0..2^k−1) — invariantul care face problema rezolvabilă
- [ ] T3. Suma aritmetică cu prag l (seria 1..(max−l−1) în formulă închisă) — valoarea unui bloc fără nicio buclă
- [ ] T4. Descompunerea în cadrane (tai la cea mai mare putere de 2 ≤ dimensiune) — recursia care înlocuiește dublul for
- [ ] T5. Aritmetică modulară cu t (mod pe parcurs + factorul par împărțit la 2 înainte de înmulțire) — capcana de overflow pe 64 de biți
- [ ] T6. Complexitate O(log max(m,n)) vs O(m·n) — de ce recursia termină la 2^64 iar brute force nu
- [ ] T7. Brute force de referință (dublu for cu max(0, (x^y)−l)) — oracolul contra căruia verifici soluția rapidă

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Dai m=8, n=5, l=1, t=100 la oracol → dublu for adună 40 de donații tăiate cu l → 5 (firul reprezentativ, măsurat)
- [ ] F2. Același input la soluția rapidă → tăiere în cadrane la 2^2=4 → aceleași 5, fără să atingă cele 40 de celule
- [ ] F3. Dai m=n=2^k (pătrat complet) → un singur bloc, formula închisă din T3, zero recursie
- [ ] F4. Dai m,n mari nealineate (ex. 545, 435, l=342, t=1000007) → recursie pe cadrane, adâncime = nr. de biți
- [ ] F5. Dai un produs par×impar peste 2^63 → varianta cu (a%t * b%t)%t dă alt rezultat decât împărțirea la 2 înainte → de ce
- [ ] F6. Fuzz: sute de inputuri mici aleatoare → fast == oracol pe fiecare (proba de corectitudine)

## Perechi de separat (risc de amestec)
- P1. Eticheta „Dynamic Programming" a kata-ului vs mecanismul real divide-et-impera — subproblemele NU se suprapun (P4 din planul programare-dinamică)
- P2. l ca prag (worshipper sub l nu donează) vs l ca pierdere (se scade din donație) — ambele trăiesc în același max(0, v−l)
- P3. mod t pe suma finală vs mod pe factori intermediari — unde e obligatoriu, unde împărțirea la 2 îl precede
- P4. m,n (dimensiuni, exclusive) vs indicii 0..m−1 (zero-indexed) — sursa clasică de off-by-one în formule
- P5. Puterea de 2 tăiată din dimensiunea mare vs din cea mică — ce cadran rămâne dreptunghi și ce cadran e pătrat închis

## Necunoscute din discovery
- Repo-ul nu conține nicio soluție a kata-ului (0 hit-uri pentru immortal/elderAge). Ancorele fișier:linie pentru T1-T7 și F1-F6 cer cod nou în pachetul-laborator existent `src/test/java/com/footballmanagergamesimulator/algorithms/dp/` (propunere: `BecomeImmortal.java` cu oracol + soluție rapidă + runner) — decizie de confirmat, nu cod existent.
- Descrierea kata nu fixează limitele exacte ale lui m,n (doar t ≤ 2^32−1); „~2^64" e inferență din testele publice ale kata-ului, neverificabilă din repo — **Necunoscut din cod**.
