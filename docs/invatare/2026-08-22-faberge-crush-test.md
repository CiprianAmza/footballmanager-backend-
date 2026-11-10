Faberge easter eggs crush test [linear] (Codewars 1 kyu) — hartă de învățare

Țintă: pentru orice pereche (n ouă, m încercări) să poți prezice formula înălțimii maxime, să scrii soluția care trece ambele regimuri de date sub mod 998244353 și să estimezi costul calculului.

## Tehnologii (doar cele de pe fir)
- [ ] T1. Recurența egg-drop (DP: f(n,m)=f(n-1,m-1)+f(n,m-1)+1) — de unde vine înălțimea maximă
- [ ] T2. Coeficienți binomiali (C(m,i) = combinări de m luate câte i) — forma închisă: height = Σ C(m,i), i=1..n
- [ ] T3. Aritmetică modulară (calcul cu resturi la primul 998244353) — de ce rezultatul se cere mod p
- [ ] T4. Invers modular (împărțirea sub modulo, prin mica teoremă a lui Fermat) — cum împarți la i! sub mod p
- [ ] T5. Factoriali + inverse precomputate (tabele O(m) construite o dată) — regimul n≤80000, m≤100000
- [ ] T6. Termen incremental (C(m,i+1) = C(m,i)·(m−i)·inv(i+1)) — regimul m≤2^200, fără factorial de m
- [ ] T7. BigInteger în Java (întregi peste 64 de biți) — doar mod/subtract, cât intră m≤2^200 în calcul

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Ceri height(2,14) → recurența DP derulează → 105; de aici iese formula Σ C(m,i)
- [ ] F2. Test cu n=80000, m=100000 → tabele factorial/invers → suma C(m,i) mod p în O(n+m)
- [ ] F3. Test cu n=3000, m=2^200 → m se reduce mod p, termen incremental → suma în O(n)
- [ ] F4. Suita Codewars (150 teste random, ambele regimuri) → de ce „naiv liniar" pe m pică la timp

## Perechi de separat (risc de amestec)
- P1. n (ouă) vs m (încercări) — care e sus în binom (m) și care taie suma (n); inversarea lor dă alt răspuns
- P2. Rezultatul exact vs rezultatul mod p — reducerea e sigură la +/×, dar interzisă la comparații și împărțiri directe
- P3. Împărțire obișnuită vs înmulțire cu inversul modular — sub mod p nu există „/", doar ×inv
- P4. Liniar în m vs liniar în n — F2 e liniar în m (tabele), F3 trebuie să fie liniar în n (m e uriaș)

## Necunoscute din discovery
- Repo-ul nu conține cod pentru kata; există doar `src/test/java/.../algorithms/dp/` (FibonacciLab, SquadKnapsack, CoinChange, EditDistance) ca ancore pentru DP general. Ancorele E1 cer scrierea soluției `FabergeEggs.java` în același director — propunere, de confirmat.
- Limita de timp exactă a testelor Codewars (implicit 12s) — inferență, neverificată din kata.
- Tema se sprijină pe harta existentă `2026-08-22-programare-dinamica.md` (T1–T3 de acolo, toate nebifate); planul acela rămâne separat.
