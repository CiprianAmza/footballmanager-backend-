# PROMPT — Livrabilul 1: Modelul canonic al obiectivelor de sezon (Club Season Vision)

## 0. Rol

Ești agentul care implementează **exclusiv Livrabilul 1** din planul „patron → obiective →
contract → recrutare → evaluare". Nu începe niciun alt livrabil. Orice problemă descoperită
în timpul lucrului se remediază **în interiorul acestui livrabil**, fără fază nouă.

## 1. Politica obligatorie a agentului

- Scrii **production code + teste unitare** (JUnit 5 + Mockito + AssertJ). Nimic altceva.
- Ai voie să rulezi: `mvn -q -DskipTests compile`, `mvn -q test-compile`, și unit teste
  **țintite**: `mvn -q -Dtest=ClubSeasonVision*Test test` (sau clasele exacte pe care le scrii).
- **Interzis**: teste de integrare, `*IT.java`, pornire de context Spring, H2/Flyway IT, E2E,
  `mvn verify`, `mvn test` fără `-Dtest`, orice suită completă.
- **Interzis**: mecanisme de compatibilitate, migrare sau backfill pentru salvări/baze vechi.
  Baza rulează `ddl-auto: create-drop` (`src/main/resources/application.yml`) — schema nouă
  este suficientă.
- Nu atingi frontend-ul (asta e Livrabilul 2).
- Nu modifici engine-ul de meci, transferurile, economia sau `ManagerCareerService` (Livrabilele 3–6).
- Nu introduci `Math.random()`, `Date.now()`, `LocalDate.now()` sau alte surse
  nedeterministe în generator/scoring. Ordinea colecțiilor returnate trebuie să fie stabilă.
- La final scrii raportul în secțiunea „Report" de mai jos (în acest fișier) și te oprești.
  Commit-ul pe master îl face utilizatorul.

## 2. Fișiere existente pe care le citești înainte de a scrie o linie

| Fișier | De ce |
|---|---|
| `src/main/java/com/footballmanagergamesimulator/model/SeasonObjective.java` | entitatea pe care o extinzi |
| `src/main/java/com/footballmanagergamesimulator/repository/SeasonObjectiveRepository.java` | repo existent |
| `src/main/java/com/footballmanagergamesimulator/service/SeasonObjectiveService.java` | generarea + evaluarea actuală (295 linii) |
| `src/main/java/com/footballmanagergamesimulator/controller/SeasonObjectiveController.java` | acces direct la repo, de înlocuit |
| `src/main/java/com/footballmanagergamesimulator/chairman/mandate/*` | **șablonul canonic** de urmat: entitate cu `@Version`, DTO record, service tranzacțional, excepție typed, migrare, teste unitare |
| `src/main/java/com/footballmanagergamesimulator/economy/ClubQueryService.java` (`dashboard`) | poarta reală de control al clubului |
| `src/main/java/com/footballmanagergamesimulator/economy/ClubController.java` | stilul de controller cu `CurrentUserService` + `PersonProfileService` |
| `src/main/java/com/footballmanagergamesimulator/economy/EconomyConflictException.java`, `EconomyApiExceptionHandler.java` | erorile typed și maparea lor HTTP |
| `src/main/java/com/footballmanagergamesimulator/service/ChairmanInboxNotificationService.java` | notificare patron, idempotentă prin `deduplicationKey` |
| `src/main/java/com/footballmanagergamesimulator/model/ManagerInbox.java`, `InboxAudience.java` | inbox manager (audience `MANAGER`) |
| `src/main/java/com/footballmanagergamesimulator/service/CalendarService.java` (`advancePhase`, ~linia 170) | punctul unde avansează ziua |
| `src/main/java/com/footballmanagergamesimulator/service/GameInitializationService.java:153`, `NewSeasonSetupProcessor.java:233`, `EndOfSeasonProcessor.java:398` | hook-urile de start/final de sezon |
| `src/main/resources/db/migration/h2/V9__chairman_scoped_inbox.sql` | stilul migrărilor H2 |

## 3. Decizie de arhitectură — obligatorie, nu opțională

**Nu** creăm un sistem paralel de obiective. `SeasonObjective` **devine rândul-copil** al noii
agregate `ClubSeasonVision`. Există exact o cale de generare (generatorul determinist) și exact
o cale de citire (serviciul nou). Evaluarea de final de sezon rămâne unde este, dar trebuie să
ignore în mod explicit tipurile noi de obiective pe care nu le știe încă evalua.

Pachet nou: `com.footballmanagergamesimulator.chairman.vision`.

## 4. Model canonic

### 4.1 `ClubSeasonVision` (entitate nouă)

Tabel `club_season_vision`, `uniqueConstraints = @UniqueConstraint(name = "uk_club_season_vision_team_season", columnNames = {"team_id", "season_number"})`.

| Câmp | Tip | Reguli |
|---|---|---|
| `id` | `long`, IDENTITY | |
| `teamId` | `long`, not null | |
| `seasonNumber` | `int`, not null | |
| `source` | `ClubVisionSource` enum STRING(20), not null | `HUMAN_CHAIRMAN`, `AI_CHAIRMAN`, `AUTO_DEFAULT` |
| `status` | `ClubVisionStatus` enum STRING(20), not null | `DRAFT`, `PUBLISHED`, `AUTO_PUBLISHED`, `COMPLETED` |
| `deadlineDay` | `int`, not null | ziua-limită de publicare (implicit 7) |
| `authorProfileId` | `Long`, nullable | profilul patronului care a editat/publicat; `null` pentru `AUTO_DEFAULT` |
| `publishedDay` | `int` | 0 dacă nepublicat |
| `updatedDay` | `int` | |
| `version` | `long` `@Version` | |
| `objectives` | `@OneToMany(mappedBy="vision", cascade=ALL, orphanRemoval=true)` către `SeasonObjective` | `getObjectives()` returnează **sortat determinist** (vezi 4.4) |

Metode: `replaceObjectives(List<SeasonObjective>)` (pattern identic cu
`ChairmanTacticalMandate.replaceSlots`), `sortedObjectives()`, `boolean isLocked()` →
`status != DRAFT`.

### 4.2 `SeasonObjective` extins (același fișier, aceeași tabelă `season_objective`)

Se **adaugă**:

| Câmp | Tip | Note |
|---|---|---|
| `vision` | `@ManyToOne(optional=false) @JoinColumn(name="vision_id")` | proprietarul |
| `type` | `SeasonObjectiveType` enum STRING(24), not null | vezi 4.3 |
| `comparator` | `ObjectiveComparator` enum STRING(8), not null | `AT_MOST` \| `AT_LEAST` |
| `targetPercent` | `int` | 0–100, folosit doar de `PLAY_STYLE` |
| `playStyleKey` | `String(48)`, nullable | doar `PLAY_STYLE` |
| `slotIndex` | `int`, not null | ordine stabilă în cadrul viziunii |
| `usagePlayers` | `@OneToMany(mappedBy="objective", cascade=ALL, orphanRemoval=true)` | doar `PLAYER_USAGE` |

Se **păstrează** câmpurile existente (`teamId`, `seasonNumber`, `competitionId`,
`competitionName`, `targetValue`, `actualValue`, `status`, `importance`, `description`).
`teamId`/`seasonNumber` se scriu întotdeauna din părinte la salvare — nu pot divergea.

Câmpul `String objectiveType` existent **se elimină**; toate scrierile/citirile trec pe enum-ul
`type`. Actualizează toate referințele din cod (`SeasonObjectiveService.evaluateSeasonObjectives`,
orice `"league_position"` / `"cup_round"` / `"european_round"` literal). `targetValue` devine
`long` (necesar pentru `WAGE_BILL_MAX` / `NET_SPEND_MAX`).

Constrângere: `uk_season_objective_slot UNIQUE (vision_id, slot_index)` și
`uk_season_objective_type_comp UNIQUE (vision_id, type, competition_id)`.

### 4.3 `SeasonObjectiveType` + semantica comparatorului

| Tip | Comparator | `targetValue` înseamnă | `competitionId` |
|---|---|---|---|
| `LEAGUE_POSITION` | `AT_MOST` | poziția maximă acceptată | obligatoriu |
| `CUP_ROUND` | `AT_LEAST` | runda minimă atinsă | obligatoriu |
| `EUROPEAN_ROUND` | `AT_LEAST` | runda minimă atinsă | obligatoriu |
| `YOUTH_PLAYERS` | `AT_LEAST` | nr. minim de jucători U21 folosiți | 0 |
| `YOUTH_MINUTES` | `AT_LEAST` | minute minime acordate U21 | 0 |
| `WAGE_BILL_MAX` | `AT_MOST` | plafon salarial (aceeași unitate ca salariile din `Human`) | 0 |
| `NET_SPEND_MAX` | `AT_MOST` | net spend maxim pe sezon | 0 |
| `PLAY_STYLE` | `AT_LEAST` | 0 (se folosește `targetPercent` + `playStyleKey`) | 0 |
| `PLAYER_USAGE` | `AT_LEAST` | 0 (se folosesc rândurile `usagePlayers`) | 0 |

Comparatorul este derivat **exclusiv** din tip printr-o metodă statică
`ObjectiveComparator SeasonObjectiveType.comparator()` — nu e configurabil din API, iar
validarea respinge orice request care trimite alt comparator.

### 4.4 Ordonare determinist

`sortedObjectives()` sortează după `slotIndex`, apoi `type.name()`, apoi `competitionId`, apoi `id`.
`ClubSeasonVisionObjectivePlayer` se sortează după `playerId`.

### 4.5 `ClubSeasonVisionObjectivePlayer` (entitate nouă)

Tabel `club_season_vision_objective_player`, `uk_vision_objective_player UNIQUE (objective_id, player_id)`.
Câmpuri: `id`, `objective` (`@ManyToOne` către `SeasonObjective`, `objective_id`, not null),
`playerId long`, `minStarts int`, `minMinutes int`. Cel puțin una dintre `minStarts`/`minMinutes` > 0.

### 4.6 Repositories noi

`ClubSeasonVisionRepository extends JpaRepository<ClubSeasonVision, Long>`:
- `Optional<ClubSeasonVision> findByTeamIdAndSeasonNumber(long teamId, int seasonNumber)`
- `@Lock(PESSIMISTIC_WRITE) Optional<ClubSeasonVision> findByTeamIdAndSeasonNumberForUpdate(...)`
  (`@Query` explicit, ca la `TeamRepository.findByIdForUpdate`)
- `List<ClubSeasonVision> findAllBySeasonNumberAndStatus(int seasonNumber, ClubVisionStatus status)`

`SeasonObjectiveRepository`: păstrează metodele existente, adaugă
`List<SeasonObjective> findAllByVisionId(long visionId)`.

## 5. Generatorul determinist de obiective implicite

Clasă nouă `DefaultClubVisionGenerator` (pachetul `chairman.vision`).

- API: `List<SeasonObjective> generate(long teamId, int season)` și
  `ClubSeasonVision generateVision(long teamId, int season, ClubVisionSource source, int deadlineDay)`.
- **Mută** logica de scalare existentă din `SeasonObjectiveService.generateSeasonObjectives`
  (predicted position din reputație, ținte per `typeId` 1/2/3/4/5, `CompetitionFormatConfig`)
  în acest generator, **fără să-i schimbi rezultatele numerice** pentru tipurile
  `LEAGUE_POSITION`, `CUP_ROUND`, `EUROPEAN_ROUND`. Este un refactor de mutare, nu de retunare.
- Adaugă, determinist, obiectivele non-competiționale, în această ordine de `slotIndex`
  (după cele competiționale, sortate pe `competitionId` crescător):
  `YOUTH_PLAYERS`, `YOUTH_MINUTES`, `WAGE_BILL_MAX`, `NET_SPEND_MAX`.
  `PLAY_STYLE` și `PLAYER_USAGE` **nu** se generează implicit (rămân opționale pentru patron).
  Formule implicite, derivate exclusiv din date canonice (reputație club, lot actual, salarii
  curente) — documentează formula în Javadoc; fără magic numbers nedocumentate.
- Ranking la egalitate de reputație: `Team::getId` crescător. Aceeași intrare → același output,
  garantat prin test.
- `importance`: `CRITICAL` pentru `LEAGUE_POSITION`, `HIGH` pentru `EUROPEAN_ROUND`,
  `MEDIUM` pentru `CUP_ROUND`/`WAGE_BILL_MAX`/`NET_SPEND_MAX`, `LOW` pentru tipurile youth.

`SeasonObjectiveService.generateSeasonObjectives(int season)` devine un thin caller:
pentru fiecare echipă fără viziune pe sezonul respectiv creează `ClubSeasonVision` cu
`source = AI_CHAIRMAN` dacă clubul e controlat de un patron AI (sau nu e controlat de nimeni)
și `source = HUMAN_CHAIRMAN` + `status = DRAFT` dacă e controlat de un profil uman
(`ControlType.USER`). Idempotent: dacă există deja viziune pe `(teamId, season)`, sare peste.

## 6. Progresul

Clasă nouă `ClubSeasonVisionProgressService`. Un singur API public:
`ObjectiveProgress progress(SeasonObjective objective)` + `List<ObjectiveProgress> progress(ClubSeasonVision vision)`.

`record ObjectiveProgress(SeasonObjectiveType type, ObjectiveComparator comparator, long target, long actual, int targetPercent, int actualPercent, boolean evaluable, boolean onTrack, double ratio, String label)`.

- `actual` se citește **exclusiv** din datele canonice existente (standings
  `TeamCompetitionDetail`, `CompetitionTeamInfo.round`, minute/apariții existente, salarii
  `Human`, ledger-ul de transferuri). Nu introduci contoare noi în Livrabilul 1.
- Dacă o sursă de date nu e disponibilă pentru un tip, `evaluable = false`, `ratio = 0`,
  `onTrack = true` (neutru) — **nu** aruncă excepție și **nu** raportează fals eșec.
- `ratio` este clampat în `[0, 1]`; pentru `AT_MOST` este `1.0` cât timp `actual <= target`.
- Serviciul este pur: primește entități, nu scrie nimic în DB.

## 7. Deadline și auto-publicare

- Proprietate nouă în `application.yml`, sub blocul `chairman:` existent:
  `chairman.vision.publish-deadline-day: 7`, legată printr-un `@ConfigurationProperties`
  (extinde `ChairmanModeProperties` sau adaugă `ClubVisionProperties`; alege și motivează).
- `ClubSeasonVisionService.enforcePublishDeadline(int season, int day)`: pentru fiecare
  viziune `DRAFT` cu `day > deadlineDay` → înlocuiește obiectivele cu cele din
  `DefaultClubVisionGenerator`, setează `source = AUTO_DEFAULT`, `status = AUTO_PUBLISHED`,
  `publishedDay = day`, `authorProfileId = null`, notifică inbox-ul managerului.
- Apelat din `CalendarService.advancePhase`, imediat după `calendar.setCurrentDay(nextDay)`.
- **În plus**, aceeași verificare se aplică lazy pe calea de citire (`GET`), astfel încât un
  `GET` de după termen să nu returneze niciodată `DRAFT`. Această cale trebuie testabilă unitar.
- Viziunea publicată este **blocată**: `PUT` și `POST /publish` pe o viziune cu
  `status != DRAFT` → `SEASON_VISION_LOCKED`.
- Un patron care preia clubul după termen **moștenește** viziunea existentă: nu se regenerează
  nimic la schimbarea controlului (nu adaugă hook în `TakeoverService`).

## 8. API

Serviciu nou `ClubSeasonVisionService` (`@Transactional`), controller nou
`ClubSeasonVisionController`. DTO-uri `record` într-un singur fișier `ClubSeasonVisionDtos`,
exact ca `ChairmanTacticalMandateDtos`.

| Metodă | Rută | Autorizare |
|---|---|---|
| `GET` | `/api/chairman/clubs/{teamId}/season-vision/current` | `clubQueryService.dashboard(teamId, principal)` |
| `PUT` | `/api/chairman/clubs/{teamId}/season-vision/current` | idem + lock pesimist pe rând |
| `POST` | `/api/chairman/clubs/{teamId}/season-vision/current/publish` | idem |
| `GET` | `/api/club-vision/me/current` | `CurrentUserService` + `TeamAccessGuard.canAccessTeam` |

- Principalul patron se obține ca în `ClubController.currentProfile()`
  (`profileService.requireForUser(currentUserService.requireUser())`).
- Controlul real al clubului se verifică **doar** prin `clubQueryService.dashboard(...)`
  (aruncă `CHAIRMAN_REQUIRED` / `CLUB_CONTROL_REQUIRED`). Nu duplici logica de cap table.
- Versionare optimistă cu contract one-based, identic cu mandatul:
  `apiVersion = entity.version + 1`; `expectedVersion = 0` pentru viziune inexistentă;
  nepotrivire → `SEASON_VISION_STALE`.
- `PUT` primește lista completă de obiective (replace, nu patch). `slotIndex` se recalculează
  pe server din ordinea trimisă — clientul nu îl poate falsifica.
- Răspunsul `VisionView` include: `teamId`, `seasonNumber`, `source`, `status`, `deadlineDay`,
  `publishedDay`, `authorProfileId`, `version`, `editable` (boolean), lista de obiective cu
  progresul lor, și `recommended` — lista generată de `DefaultClubVisionGenerator` pentru
  același `(teamId, season)`, ca UI-ul să arate recomandarea sistemului înainte de modificare.
- `GET /api/club-vision/me/current` returnează **doar** viziunea publicată/auto-publicată a
  clubului managerului (fără `recommended`, fără câmpuri de editare). Viziune în `DRAFT` →
  `SEASON_VISION_NOT_PUBLISHED`, fără a expune conținutul draftului.

### 8.1 Excepție typed

`ClubSeasonVisionException extends EconomyConflictException` (același pattern ca
`ChairmanTacticalMandateException`). Coduri, toate folosite cel puțin o dată:

`CLUB_NOT_FOUND`, `SEASON_VISION_NOT_FOUND`, `SEASON_VISION_NOT_PUBLISHED`,
`SEASON_VISION_STALE`, `SEASON_VISION_LOCKED`, `UNKNOWN_OBJECTIVE_TYPE`,
`INVALID_OBJECTIVE_TARGET`, `INVALID_OBJECTIVE_COMPARATOR`, `DUPLICATE_OBJECTIVE`,
`OBJECTIVE_COMPETITION_NOT_ENTERED`, `OBJECTIVE_PLAYER_NOT_ELIGIBLE`,
`MANAGER_CLUB_REQUIRED`, `TOO_MANY_OBJECTIVES` (plafon 24 obiective/viziune,
16 jucători/obiectiv `PLAYER_USAGE`).

### 8.2 Validări la `PUT`

1. tipul e cunoscut; comparatorul coincide cu `type.comparator()`;
2. `LEAGUE_POSITION`/`CUP_ROUND`/`EUROPEAN_ROUND`: `competitionId` este o competiție în care
   echipa este efectiv înscrisă în sezonul curent, altfel `OBJECTIVE_COMPETITION_NOT_ENTERED`;
3. `LEAGUE_POSITION`: `1 <= targetValue <= nr. echipe din competiție`;
4. runde: `targetValue` în intervalul valid al formatului (`CompetitionFormatConfig`);
5. `targetValue >= 0` peste tot; `targetPercent` în `[1,100]` doar pentru `PLAY_STYLE`
   (altfel trebuie 0);
6. `PLAY_STYLE`: `playStyleKey` non-blank și cunoscut de `TacticService`;
7. `PLAYER_USAGE`: fiecare `playerId` există, `typeId == TypeNames.PLAYER_TYPE`, `teamId`
   corect, ne-retras (același check ca `ChairmanTacticalMandateService.validatePlayers`);
8. fără duplicate `(type, competitionId)`; fără `playerId` duplicat în același obiectiv;
9. plafoanele din 8.1.

Validarea rulează **înainte** de orice mutație și nu lasă scrieri parțiale.

## 9. Publicarea

`POST .../publish`:
1. lock pesimist pe rândul viziunii;
2. `status == DRAFT` altfel `SEASON_VISION_LOCKED`;
3. validează din nou obiectivele persistate;
4. `status = PUBLISHED`, `source = HUMAN_CHAIRMAN`, `authorProfileId = principal.getId()`,
   `publishedDay = ziua curentă din `GameCalendar``;
5. mesaj în inbox-ul **managerului** (`InboxAudience.MANAGER`, `teamId`, categorie
   `SEASON_OBJECTIVES_PUBLISHED`) + confirmare în inbox-ul **patronului** prin
   `ChairmanInboxNotificationService.notify(...)` cu
   `deduplicationKey = "VISION:" + teamId + ":" + season + ":" + apiVersion`;
6. publicarea pe clubul A **nu** atinge nicio viziune a clubului B — fiecare club e o
   agregată independentă, cu propriul lock; interzis orice `findAll`-and-save în această cale.

## 10. Înlocuirea accesului direct

- `SeasonObjectiveController` (`/objectives/**`) — **șterge** clasa. Cine avea nevoie de citire
  folosește noile rute. Verifică prin grep că nu rămân referințe FE/BE în backend.
- `SeasonObjectiveService`: rămâne owner-ul ciclului de sezon, dar nu mai construiește obiective
  în linie — deleagă la `DefaultClubVisionGenerator` + `ClubSeasonVisionService`.
- `evaluateSeasonObjectives`: continuă să evalueze **doar** `LEAGUE_POSITION`, `CUP_ROUND`,
  `EUROPEAN_ROUND` (comportament numeric identic cu cel actual, inclusiv corecția „max round"
  și cazul „win the cup"). Tipurile noi rămân `active` la final de sezon în Livrabilul 1 —
  nu inventezi evaluare pentru ele. Marchează viziunea `COMPLETED` la finalul evaluării.
- Nu atingi `ManagerCareerService.checkManagerFiring` — regulile de concediere sunt Livrabilul 6.

## 11. Migrare Flyway

Fișier nou `src/main/resources/db/migration/h2/V10__club_season_vision.sql`, în stilul lui
`V8`: `CREATE TABLE club_season_vision`, `ALTER TABLE season_objective` (coloane noi + FK +
unique + drop `objective_type`), `CREATE TABLE club_season_vision_objective_player`.
Numele constrângerilor din SQL trebuie să coincidă exact cu cele din adnotările JPA.
`mysql/` și `postgresql/` nu se ating (sunt la V1).

## 12. Teste unitare cerute (Mockito, fără context Spring)

Fiecare test are un nume care descrie comportamentul, nu metoda.

**`DefaultClubVisionGeneratorTest`**
1. același input produce exact același output (rulează generatorul de două ori, comparare listă completă);
2. ținta de ligă scalează cu poziția prezisă și e clampată la `[1, nrEchipe]`;
3. egalitate de reputație → tie-break pe `teamId`, ordine stabilă;
4. echipă în cupă+ligă+LoC → obiectivele apar în ordinea de `slotIndex` prescrisă în §5;
5. tipurile `PLAY_STYLE` și `PLAYER_USAGE` nu sunt generate implicit.

**`ClubSeasonVisionServiceTest`**
6. `GET` fără viziune existentă returnează recomandarea generatorului cu `version = 0` și `editable = true`;
7. `PUT` cu `expectedVersion` greșit → `SEASON_VISION_STALE`, fără salvare (`verify(repo, never()).save(any())`);
8. `PUT` pe viziune `PUBLISHED` → `SEASON_VISION_LOCKED`;
9. `PUT` cu competiție în care echipa nu joacă → `OBJECTIVE_COMPETITION_NOT_ENTERED`;
10. `PUT` cu `LEAGUE_POSITION` = 0 sau > nrEchipe → `INVALID_OBJECTIVE_TARGET`;
11. `PUT` cu comparator nepotrivit cu tipul → `INVALID_OBJECTIVE_COMPARATOR`;
12. `PUT` cu două obiective `(type, competitionId)` identice → `DUPLICATE_OBJECTIVE`;
13. `PUT` cu jucător din alt club / retras → `OBJECTIVE_PLAYER_NOT_ELIGIBLE`;
14. `PUT` peste plafoane → `TOO_MANY_OBJECTIVES`;
15. `PUT` valid: `slotIndex` recalculat 0..n-1 în ordinea trimisă, `teamId`/`seasonNumber`
    propagate din părinte, versiune API incrementată;
16. `publish` valid → `PUBLISHED` + `authorProfileId` + exact un mesaj manager și unul patron,
    cu `deduplicationKey` prescris;
17. `publish` de două ori → al doilea apel `SEASON_VISION_LOCKED` și **niciun** mesaj nou;
18. publicarea clubului A nu produce nicio scriere pe viziunea clubului B
    (două viziuni în mock; `verify` pe save-uri);
19. patron fără control real (`dashboard` aruncă `CLUB_CONTROL_REQUIRED`) → propagă eroarea,
    zero scrieri;
20. `enforcePublishDeadline(season, day > deadline)` pe `DRAFT` → `AUTO_PUBLISHED` +
    `AUTO_DEFAULT` + obiective implicite + notificare manager;
21. `enforcePublishDeadline` cu `day <= deadline` → niciun efect;
22. `GET` după termen pe o viziune rămasă `DRAFT` → returnează `AUTO_PUBLISHED` (enforce lazy);
23. `GET /api/club-vision/me/current` pe viziune `DRAFT` → `SEASON_VISION_NOT_PUBLISHED`,
    fără obiective în payload;
24. manager care cere alt club → `MANAGER_CLUB_REQUIRED`.

**`ClubSeasonVisionProgressServiceTest`**
25. `LEAGUE_POSITION` (`AT_MOST`): poziție mai bună → `onTrack`, mai slabă → `!onTrack`, `ratio` clampat;
26. `CUP_ROUND`/`EUROPEAN_ROUND` (`AT_LEAST`): runda maximă atinsă contează, nu prima;
27. `WAGE_BILL_MAX` peste plafon → `!onTrack`, `ratio == 0`;
28. tip fără sursă de date → `evaluable = false`, `onTrack = true`, fără excepție.

**`ClubSeasonVisionTest`** (entitate)
29. `getObjectives()` returnează ordine deterministă indiferent de ordinea de inserare;
30. `replaceObjectives` setează părintele și golește orfanii.

## 13. Definition of done

- `mvn -q test-compile` curat.
- Toate testele unitare de mai sus verzi, rulate țintit.
- `grep -rn "\"league_position\"\|\"cup_round\"\|\"european_round\"\|/objectives" src/main` →
  zero rezultate.
- Zero teste de integrare adăugate sau rulate.
- Raport scris mai jos: fișiere adăugate/modificate, deciziile luate (inclusiv unde ai pus
  proprietatea de deadline și de ce), formulele implicite pentru obiectivele non-competiționale,
  tipurile lăsate neevaluate la final de sezon, și orice întrebare deschisă pentru reviewer.

## 14. Report (completează aici)

- Fișiere noi:
- Fișiere modificate:
- Decizii:
- Teste rulate + rezultat:
- Întrebări deschise:
