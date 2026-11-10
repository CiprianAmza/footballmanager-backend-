Algoritmul lui Kruskal — hartă de învățare

Țintă: pe un graf mic, dat un ordin de muchii sortate, să prezici pentru fiecare muchie acceptat/respins (via DSU) și costul total final al MST-ului, fără să deseneze graful.

## Tehnologii (doar cele de pe fir)
- [ ] T0. Vocabular de bază: graf, nod, muchie, cost/greutate — obiectele cu care lucrează Kruskal, zero cunoștințe presupuse
      - [ ] T0.1 Ce e un nod?
            cod: exemplul F1 (tabelul cu muchiile 3-4, 1-4, 1-3, 1-2, 2-4)
            trecere: numește câte noduri are graful din F1 și cum se numesc
      - [ ] T0.2 Ce e o muchie?
            cod: exemplul F1
            trecere: numește câte muchii are graful din F1 și le listează
      - [ ] T0.3 Ce e costul/greutatea unei muchii?
            cod: exemplul F1
            trecere: spune costul muchiei 1-2 din F1 fără să se uite la tabel
      - [ ] T0.4 Ce e un graf, ca întreg (noduri + muchii + costuri împreună)?
            cod: exemplul F1 + un mini-exemplu nou (2 noduri, 1 muchie)
            trecere: dat un tabel jucărie nou (3 noduri, 2 muchii), spune câte noduri/muchii are și costurile lor
- [ ] T1. Sortarea muchiilor crescător după cost — ordinea fixă în care sunt evaluate candidatele
- [ ] T2. DSU / union-find (structura care răspunde „sunt deja în același grup?")
      - [ ] T2.1 find(x) — interogare: urcă la rădăcina/reprezentantul grupului lui x, nu schimbă nimic
      - [ ] T2.2 union(x,y) — comandă: contopește grupul lui x cu grupul lui y
- [ ] T3. Criteriul de acceptare a unei muchii (find(u)≠find(v)) și condiția de oprire (n−1 muchii adăugate)

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Graf 4 noduri, muchii 3-4(4) 1-4(5) 1-3(6) 1-2(10) 2-4(15) → parcurgere pas cu pas → MST cost 19   (firul reprezentativ, deja atins parțial informal)
- [ ] F2. Pe același graf, muchia 1-3(6) → find(1)=find(3) → respinsă fără să caute alt drum în graf
- [ ] F3. Graf cu 2 componente separate, nicio muchie între ele → Kruskal nu ajunge la n−1 muchii → pădure de acoperire minimă (MSF), nu un singur arbore
- [ ] F4. Două muchii cu cost egal, ordine de tie-break diferită → costul total al MST rămâne același, structura arborelui poate diferi
- [ ] F5. Fuzz: comparație costului total cu un brute-force pe grafuri mici aleatoare → aceeași sumă (proba de corectitudine)

## Perechi de separat (risc de amestec)
- P1. arbore de acoperire (orice subgraf conex, n−1 muchii, fără cicluri) vs MST (cel cu cost minim dintre toți) — deja atins informal
- P2. componentă conexă (grup deja legat) vs muchie candidată (pereche încă neevaluată) — ce anume verifică find()
- P3. find (interogare, citește) vs union (comandă, schimbă structura DSU) — confuzie tipică de rol
- P4. costul total al MST (sumă pe toate muchiile alese) vs costul unei singure muchii — două numere diferite pe același graf

## Necunoscute din discovery
- Repo-ul nu conține nicio implementare Kruskal/DSU (0 hit-uri reale pentru „kruskal"/DSU în src; hit-urile pentru „union"/„disjoint" sunt din alte contexte — transfer, matchday). Ancorele cod:linie cer cod nou: propunere `src/test/java/com/footballmanagergamesimulator/algorithms/greedy/Kruskal.java` (DSU + Kruskal + fuzz vs brute-force) — decizie de confirmat, nu cod existent.
