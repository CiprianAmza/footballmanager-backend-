Programare dinamică — hartă de învățare

Țintă: pentru o problemă nouă să poți decide dacă DP se aplică (substructură optimă + subprobleme suprapuse), să scrii starea, recurența și ordinea de umplere, să estimezi timp/memorie, și să arăți unde în acest repo DP ar înlocui un greedy actual.

## Tehnologii (doar cele de pe fir)
- [ ] T1. Recursie naivă (metodă care se autoapelează) — forma de plecare a oricărui DP
- [ ] T2. Memoizare (cache pe cheia stării) — varianta top-down, recursie + tablou de rezultate
- [ ] T3. Tabulare (umplere iterativă a tabloului) — varianta bottom-up, fără stivă de apeluri
- [ ] T4. Substructură optimă (optimul global se compune din optime parțiale) — primul test de aplicabilitate
- [ ] T5. Subprobleme suprapuse (aceeași stare recalculată de multe ori) — al doilea test de aplicabilitate
- [ ] T6. Spațiul stării + recurența (ce indici definesc o stare) — proiectarea propriu-zisă
- [ ] T7. Complexitate = nr. stări × cost tranziție — cum estimezi timpul și memoria înainte de a scrie cod
- [ ] T8. Reconstrucția soluției (tablou de alegeri / parent) — de la valoarea optimă la decizia optimă
- [ ] T9. Optimizare de memorie (rolling array, 2 rânduri) — de ce e corectă și când NU e

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Ceri Fibonacci(40) recursiv → ~2^n apeluri; același input cu memo → n apeluri (firul reprezentativ, măsurat)
- [ ] F2. Dai buget + listă de jucători cu preț și rating → rucsac 0/1 alege lotul de valoare maximă sub buget
- [ ] F3. Dai o sumă țintă și un set de monede → nr. minim de monede sau „imposibil"
- [ ] F4. Dai două șiruri → tablou 2D de distanță de editare + reconstrucția drumului de operații
- [ ] F5. Dai meciuri cu interval de timp și valoare → DP pe secvență alege setul fără conflicte de valoare maximă
- [ ] F6. Repo: strategia de transferuri cheltuie bugetul jucător cu jucător (greedy) → unde DP dă alt lot
- [ ] F7. Repo: XI-ul se completează slot cu slot (greedy) → problema de asignare, unde DP dă alt XI
- [ ] F8. Repo: `computeIfAbsent` cachează încărcări din DB, nu subprobleme → de ce nu e DP (contrast)

## Perechi de separat (risc de amestec)
- P1. Memoizare vs cache aplicativ — ambele sunt Map-uri, dar cheia unuia e o stare a recurenței, a celuilalt un id de rând
- P2. Top-down vs bottom-up — cine decide ordinea de calcul: stiva de apeluri sau buclele tale
- P3. Greedy vs DP — greedy alege irevocabil la fiecare pas, DP compară toate deciziile pentru fiecare stare
- P4. Divide-et-impera vs DP — subprobleme disjuncte (mergesort) vs subprobleme suprapuse
- P5. Stare vs indice de buclă — `i` din `for` nu e automat stare; starea e ce trebuie să știi ca să continui
- P6. Valoare optimă vs soluție optimă — tabloul dă numărul; drumul cere reconstrucție separată

## Necunoscute din discovery
- Repo-ul nu conține nicio implementare DP: 0 hit-uri pentru knapsack/bellman/edit distance/tablou `dp[`; nicio recursie memoizată. Ancorele mecanice pentru T1-T9 și F1-F5 cer cod DP nou scris în repo (propunere: `src/test/java/.../dp/`) — decizie de confirmat, nu cod existent.
- Nu se poate stabili din cod dacă selecția XI (`LineupRatingService`) a fost vreodată gândită ca problemă de optimizare — **Necunoscut din cod**.
