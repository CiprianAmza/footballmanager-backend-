# Instrucțiuni pentru agent: Argus Football World

Copiază tot textul de mai jos într-o sesiune nouă de Claude Code, pornită din `C:\Users\cipriana\Documents\footballmanager-design`.

---

## Context

Lucrezi la **Argus Football World (AFW)**, jocul de fotbal în browser al utilizatorului: backend Java 17 / Spring Boot 3.0.4 / PostgreSQL, frontend Angular 15.

- Backend: https://github.com/CiprianAmza/footballmanager-backend- (branch `master`)
- Frontend: https://github.com/CiprianAmza/footballmanager-frontend-test (branch `main`)
- Copia locală: `C:\Users\cipriana\Projects\argus\` (o creează scriptul)
- Documentele de design (sursa de adevăr pentru ce se implementează):
  - `C:\Users\cipriana\Documents\footballmanager-design\DESIGN_START_CARIERA.md`
  - `C:\Users\cipriana\Documents\footballmanager-design\DESIGN_JUCATORI_SI_PIATA.md`

Cum lucrează utilizatorul:
- Răspunde-i **în română**, pas cu pas, **o singură întrebare pe rând**.
- Jocul e interpretarea lui, **nu o copie a Football Manager**. Nu compara fiecare idee cu FM.
- Motorul de meci, tactica și alegerea scorurilor sunt ideea lui. Nu le critica decât dacă îți cere.
- În documente: „aici” = AFW, „acolo” = FM oficial.

## Faza 1: pornește proiectul local

1. Rulează scriptul:
   ```powershell
   powershell -ExecutionPolicy Bypass -File C:\Users\cipriana\Documents\footballmanager-design\setup-afw.ps1
   ```
   Opțiuni: `-WithSave` (descarcă salvarea de 411 MB prin Git LFS), `-SkipBuild`, `-Stop`, `-JavaHome <cale>`.
2. Ce știm deja despre mașină (verificat 2026-11-10):
   - Java implicit e 8. Scriptul folosește automat JDK 21 din `C:\Users\cipriana\.jdks\ms-21.0.12.1`.
   - Node instalat e 22, proiectul documentează Node 20. Pe 2026-11-10 frontend-ul a compilat și a pornit cu Node 22, doar cu avertismente CSS. Dacă totuși `npm start` eșuează din cauza versiunii, **întreabă utilizatorul** înainte să instalezi Node 20.
   - Prima rulare completă, pe 2026-11-10, a mers: backend pornit în ~52 s (cu `/game/state` → 401 fără login, normal), frontend → 200.
   - Docker Desktop, Maven 3.9.6, git-lfs sunt instalate.
3. Dacă un pas eșuează:
   - citește logurile din `C:\Users\cipriana\Projects\argus\logs\`;
   - poți repara **mediul** (variabile, porturi, containere Docker ale proiectului);
   - **nu modifica codul proiectului** ca să-l faci să pornească fără să întrebi;
   - **nu instala software** fără să întrebi;
   - nu opri containere sau procese care nu aparțin acestui proiect.
4. Verifică, apoi raportează pe scurt ce merge și ce nu:
   - backend pe http://localhost:8086 și frontend pe http://localhost:4200;
   - crearea unui cont și login;
   - pornirea unei cariere noi (ecranul „New Career”);
   - avansarea cu o zi (butonul CONTINUE);
   - opțional, importul salvării (`-WithSave`, apoi comanda `curl.exe` afișată de script).
5. **Nu face commit și nu face push.**

Oprește-te după raport și așteaptă utilizatorul.

## Faza 2: implementare în pași (doar după ce utilizatorul spune „start”)

1. Citește ambele documente de design. Respectă deciziile marcate „decis”. Ce e marcat „de confirmat” sau „întrebare deschisă” se întreabă, nu se presupune.
2. Propune o **listă ordonată de pași mici**, fiecare testabil separat. Ordinea sugerată (utilizatorul decide):
   1. **Fundația ratingului:** f (atribute → rating), g exact (rating → atribute), Δ (creștere → atribute); scoaterea modificărilor directe de rating și a plafonului de potențial; două ratinguri (obișnuit și special). Restul depinde de asta.
   2. **Antrenamentul:** zile de actualizare pe ligă, contoare (doar echipe umane), buget lunar după vârstă, luna incredibilă, scăderea, atribute peste 20.
   3. **Creșterea de final de sezon** din statistici, raportată la ligă.
   4. **Agentul** ca entitate, punctele de contract, negocierea pe runde.
   5. **Prețul de transfer** din context (contract rămas, importanță, situația clubului, clauze) și transferurile AI împărțite pe zilele ferestrei, cu „wanted”.
   6. **Parcursul de start:** cont → manager → rol → ligi în focus → preview de club.
   7. **Prima zi:** articol de presă, întâlniri, conferință.
   8. **Structura de putere:** patron, DG, loialitatea staff-ului.
3. Pentru fiecare pas:
   - lucrează pe un branch separat (de exemplu `afw/rating-fundatie`);
   - scrie teste pentru comportamentul nou; rulează `mvn test` (și `mvn verify` când atingi fluxuri mari);
   - la schimbări care ating transferurile, compară volumul de transferuri cu cel de dinainte (`TransferWindowReportIT`, `TransferEconomyFuzzIT`, `AiTransferMarketBenchmarkIT`). Regula din design: piața nu trebuie să înghețe;
   - arată utilizatorului ce s-a schimbat și rezultatul testelor;
   - commit doar cu acordul lui; push niciodată fără să ceară.
4. Când o decizie din design se schimbă în timpul implementării, actualizează documentul de design corespunzător.
