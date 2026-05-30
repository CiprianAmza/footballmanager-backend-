# Handoff — context pentru sesiunea următoare

Ultima actualizare: 17 mai 2026

## Context proiect

Football Manager Game Simulator — Spring Boot 3.0.4 backend + Angular frontend (separate repos).

- Backend: `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-`
- Frontend: `/Users/ciprian.amza/footballmanager-frontend-test` (sau cum se numește exact)

## Ce s-a făcut în sesiunea anterioară

### 1. Audit profund al backendului (FĂCUT, livrabil OK)

Raport complet: `AUDIT_REPORT.md` în rădăcina backendului. Acoperă 4 dimensiuni — arhitectură, calitate cod, securitate, performanță & DB — cu findings concrete și referințe fișier:linie.

**Findings critice (rezumat):**

- **Securitate critică**: `WebSecurityConfig` permite `anyRequest().permitAll()`, CSRF dezactivat, H2 console public; `/api/auth/login` autentifică fără parolă; `UserDetailsImpl` hard-codează rolurile `USER,ADMIN`; credențiale Heroku Postgres reale commit-uite în `application.properties` linii 14-27 (de rotit + curățat istoric git).
- **Arhitectură**: `CompetitionController.java` are 7.312 linii, 46 dependențe, state mutabil; 27 `@Lazy` ca workaround pentru cicluri; controllere injectate în controllere (AdminController → CompetitionController).
- **Performanță**: 141 `findAll()` fără paginare, 342 `save()` în bucle, N+1 garantat în `initialization()`, zero indexuri JPA, zero relații `@OneToMany`/`@ManyToOne`, `ddl-auto=update` fără Flyway.
- **Calitate**: 3 fișiere de test pentru 218 producție, 0 utilizări `@Valid`, 74 `System.out`/`printStackTrace`, `org.json:20160212` cu CVE-uri, Springfox 3 incompatibil cu Boot 3.

### 2. Încercare de aplicare: indexuri JPA + Flyway (REVERTATĂ COMPLET)

S-a încercat:
- adăugare `@Index` pe 39 entități (101 indexuri totale)
- dependență `flyway-core` în `pom.xml`
- profile-uri `bootstrap-schema` + `flyway` pentru migrare
- batching properties Hibernate în `application.yml`

**Ce a mers prost:**
- prima iterație folosea snake_case în `columnList` — eroare la pornire pentru că `@Index` așteaptă numele logice (camelCase / `@Column(name=...)` overrides)
- după fix la camelCase: app pornea dar **simularea s-a spart** — nu se mai jucau meciuri, schedule gol, clasament necalculat
- am scos batching și unique constraints — tot rupt
- **revert complet** la baseline: toate `@Index` scoase, Flyway scos, `application.yml` restaurat

**Stare actuală a codului:** identică cu cea de dinainte de orice intervenție de la mine. Comportamentul de runtime ar trebui să fie exact ca înainte.

**Fișiere obsolete care nu s-au putut șterge programatic** (le ștergi tu manual):
- `MIGRATION_TO_FLYWAY.md`
- `src/main/resources/application-bootstrap-schema.yml`
- `src/main/resources/application-flyway.yml`
- `src/main/resources/db/migration/README.md` (+ folderul gol `db/migration/`)

Toate au conținut "OBSOLET — safe de șters".

### 3. Ipoteză deschisă (de verificat în sesiunea nouă)

Spre finalul sesiunii precedente am ridicat posibilitatea că simularea spartă să fi avut **cauză pe frontend**, nu pe backend (cache vechi, build cu `environment` greșit, `X-User-Id` lipsă din interceptor, endpoint redenumit). Backendul revertat ar trebui să fie identic cu starea inițială.

**N-am putut investiga**: folderul frontend nu s-a mountat în sandbox-ul sesiunii curente (limitare tehnică — mount-urile noi nu se propagă în sesiunea existentă).

## Ce să faci în sesiunea nouă

1. **Pornește o sesiune nouă în Cowork cu AMBELE foldere selectate de la început** (backend + frontend).
2. **Verifică mai întâi dacă simularea funcționează** (fără modificări noi). Dacă da, problema a fost într-adevăr în schimbările mele — putem relua optimizările dar incremental, cu test după fiecare entitate. Dacă nu, problema persistă din altă cauză.
3. **Test rapid de Network tab** (în browser, F12 → Network → declanșează o zi):
   - apar request-uri către backend? La ce URL?
   - status code (200 / 4xx / 5xx)?
   - dacă 4xx, e prezent header-ul `X-User-Id`?
   - dacă 5xx, ce returnează în Response?
4. **Citiri prioritare în frontend** pentru debugging:
   - `src/environments/environment.ts` și `.prod.ts` — `apiUrl`/`baseUrl`
   - service-urile care apelează `/competition/`, `/game/`, `/managers/`
   - HTTP interceptor pentru `X-User-Id`
   - componenta de schedule / match simulation

## Prompt-uri sugerate pentru sesiunea nouă

**Dacă vrei doar investigarea frontend:**
> Uită-te în `footballmanager-frontend-test`. Există un raport de audit pe backend la `footballmanager-backend-/AUDIT_REPORT.md` și un handoff la `HANDOFF.md`. Vreau să-mi spui de ce nu se mai joacă meciurile, nu se calculează clasamentul și nu se vede schedule-ul. Începe cu Network tab debugging dacă-i nevoie să-mi explici.

**Dacă vrei să reiei optimizările indexurilor incremental:**
> Citește `footballmanager-backend-/HANDOFF.md`. Vreau să reluăm indexurile JPA, dar de data asta entitate cu entitate, cu mine confirmând după fiecare că simularea încă merge. Pornește cu Human.

**Dacă vrei doar securitatea (cele 4 chestii critice):**
> Citește `footballmanager-backend-/AUDIT_REPORT.md`. Vreau să rezolvăm doar cele 4 chestii P0 din secțiunea 5.1 (anyRequest().authenticated, login cu parolă, scoatere rol hardcodat ADMIN, rotire secrete). Începe.

## Locații importante

- Raport audit complet: `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-/AUDIT_REPORT.md`
- Acest handoff: `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-/HANDOFF.md`
- Backend cod: `/Users/ciprian.amza/IdeaProjects/footballmanager-backend-/src/main/java/com/footballmanagergamesimulator/`
- Frontend: `/Users/ciprian.amza/footballmanager-frontend-test/` (de mountat în sesiunea nouă)
