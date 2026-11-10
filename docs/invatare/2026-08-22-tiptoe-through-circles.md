Tiptoe through the circles (kata 1 kyu) — hartă de învățare

Țintă: să poți construi singur graful de tangente + arce pentru A→B printre cercuri interzise, să rulezi Dijkstra pe el și să prezici pentru o configurație dată dacă răspunsul e o lungime sau -1.0.

## Tehnologii (doar cele de pe fir)
- [ ] T1. Tangente punct–cerc (2 drepte din punct exterior) — cum ajungi de la A/B pe un cerc
- [ ] T2. Tangente comune cerc–cerc (exterioare + interioare, max 4) — cum treci de pe un cerc pe altul
- [ ] T3. Test segment blocat (distanță centru–segment < rază) — validarea fiecărei muchii drepte
- [ ] T4. Arc de cerc (lungime = rază × unghi) — deplasarea pe circumferință între două puncte de tangență
- [ ] T5. Test arc blocat (alt cerc acoperă o porțiune de arc) — validarea muchiilor curbe
- [ ] T6. Construcția grafului geometric (noduri generate din tangente, nu date) — asamblarea problemei
- [ ] T7. Dijkstra (drum minim cu coadă de priorități) — căutarea pe graful construit
- [ ] T8. Cazuri limită (A/B în cerc, cercuri suprapuse/conținute) — sursa lui -1.0 și a bug-urilor

## Fire de execuție (ordinea învățării, nu a pachetelor)
- [ ] F1. A, B, zero cercuri pe segment → răspuns = distanța euclidiană   (firul reprezentativ)
- [ ] F2. Un cerc taie segmentul AB → tangente din A, arc pe cerc, tangente spre B → drum minim
- [ ] F3. Două cercuri pe traseu → tangente comune între ele + arce → drum combinat prin ambele
- [ ] F4. Cercuri care se suprapun formează zid A↔B → niciun drum valid → -1.0
- [ ] F5. Test mare (40–119 cercuri) → O(n²) perechi × validare O(n) → de ce mai încape în timp
- [ ] F6. Reconstrucție: pentru un test fix din kata, prezici traseul (ce tangente, ce arce) înainte de rulare

## Perechi de separat (risc de amestec)
- P1. Tangentă exterioară vs interioară — care dispare când cercurile se intersectează
- P2. Coardă vs arc — linia dreaptă dintre punctele de tangență NU e drumul, arcul e
- P3. Atinge vs taie — segment tangent la cerc (permis) vs segment care intră în cerc (blocat)
- P4. Graf clasic vs graf geometric — nodurile nu există în input, le generezi din tangente
- P5. atan2 vs acos — unghi orientat cu semn vs unghi absolut; sursa arcelor „pe partea greșită"
- P6. -1.0 vs drum ocolitor lung — imposibil geometric, nu doar scump

## Necunoscute din discovery
- Repo-ul nu conține geometrie de tangente/Dijkstra: 0 hit-uri. Ancorele cer cod nou (propunere, după precedentul dp/: `src/test/java/.../algorithms/geometry/TiptoeThroughCircles.java` + teste din kata) — decizie de confirmat.
- Din enunțul preluat prin API nu rezultă dacă A/B pot fi în interiorul unui cerc și dacă atingerea tangențială e explicit permisă — **Necunoscut din enunț**, de verificat pe testele fixe.
