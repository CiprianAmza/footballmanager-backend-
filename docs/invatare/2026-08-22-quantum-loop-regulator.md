Quantum Loop Regulator (kata Codewars 68eee8b2081f2b54db664608) — hartă de învățare

Țintă: să poți prezice, pas cu pas, cum o soluție găsește lungimea L ≤ 1000 a buclei în ≤ 100 de operații move/read/write și să justifici bilanțul de energie pe cazul cel mai rău, verificat pe o implementare de referință rulată local.

## Tehnologii (doar cele de pe fir)
- [ ] T1. Interfața QLR (move/read/write, fiecare costă 1) — singurele acțiuni permise; move(x) cu x oricât de mare costă tot 1
- [ ] T2. Aritmetică modulară (poziții pe cerc, mod L) — de ce „sar M" înseamnă „ajung la M mod L"
- [ ] T3. Divizori și lcm (cel mai mic multiplu comun) — cum un test „M e multiplu de L?" restrânge candidații 1..1000
- [ ] T4. Biți aleatori și fals pozitiv (starea inițială random) — de ce o potrivire de citire nu dovedește nimic, doar nepotrivirea infirmă
- [ ] T5. Simulator local de QLR (noi implementăm dispozitivul) — enunțul e complet („no hidden tests"), deci putem verifica soluția acasă

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. Metoda „trenului" din kata-ul prerechizit: marchezi nodul curent, mergi pas cu pas până regăsești marcajul → cost O(L), moare la E=100 cu L≤1000 (firul reprezentativ al problemei, arată de ce e nevoie de altceva)
- [ ] F2. Proba de multiplu: scrii o semnătură de biți, sari M noduri, citești → „sigur NU e multiplu" (nepotrivire) sau „probabil multiplu" (potrivire)
- [ ] F3. Strategia de căutare: din răspunsurile probelor restrângi candidații din 1..1000 până rămâne exact L (divizori, alegerea lui M la fiecare pas)
- [ ] F4. Bilanțul energetic: numeri fiecare move/read/write al strategiei → demonstrezi ≤ 100 pe cazul cel mai rău
- [ ] F5. Implementarea de referință + simulatorul: rulezi soluția pe multe L-uri și stări aleatoare → corectitudine și buget confirmate empiric

## Perechi de separat (risc de amestec)
- P1. Distanța săriturii vs costul ei — move(720720) costă 1 energie, nu 720720
- P2. „Am citit ce mă așteptam" vs „M e multiplu de L" — potrivirea e doar probabilă, nepotrivirea e certă
- P3. Poziția pointerului vs valoarea nodului — unde ești pe cerc vs bitul scris acolo
- P4. L (lungimea reală, necunoscută) vs M (candidatul testat) — un multiplu al lui L nu e L

## Necunoscute din discovery
- Repo-ul nu conține niciun cod pentru kata; ancorele fișier:linie cer o soluție de referință + simulator scrise local (propunere: `docs/invatare/qlr/`) — decizie de confirmat.
- Strategia exactă care încape în E=100 pentru orice L≤1000 nu e încă demonstrată (doar 3 rezolvitori pe Codewars) — inferență de validat cu simulatorul, nu fapt.
- Cum generează judecătorul Codewars L și starea inițială (seed fix? extreme?) — Necunoscut din enunț; garantat doar „no hidden tests".
