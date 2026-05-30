# Football Manager Backend – Audit Tehnic

**Proiect:** `football-manager-simulator` (Spring Boot 3.0.4 / Java 17)
**Data auditului:** 17 mai 2026
**Scope:** arhitectură, calitate cod, securitate, performanță & DB
**Audit type:** profund (citire fișiere cheie + grep cross-cutting)

---

## 0. Sumar executiv

Proiectul este un simulator funcțional de Football Manager cu **~31.000 LoC Java**, **218 fișiere**, **214 endpoint-uri REST** și o suprafață de domeniu impresionantă (tactici, transferuri, scouting, finanțe, calendar, presă, academie de tineret, board requests etc.). Backendul demonstrează cunoștințe solide de modelare a domeniului și de Spring Boot.

În același timp, codul prezintă un **risc operațional ridicat dacă ar urma să fie expus public**, din cauza unor probleme critice de securitate și a unor decizii arhitecturale care vor împiedica scalarea și mentenanța. Cele mai importante constatări:

| # | Severitate | Constatare |
|---|---|---|
| 1 | **Critic – Securitate** | `WebSecurityConfig` permite `anyRequest().permitAll()`, CSRF dezactivat, H2 console publică |
| 2 | **Critic – Securitate** | `/api/auth/login` autentifică **fără parolă** – orice username = login automat |
| 3 | **Critic – Securitate** | Toți utilizatorii primesc rolurile `USER,ADMIN` hard-coded la login (privilege escalation by design) |
| 4 | **Critic – Securitate** | Credențiale Heroku Postgres reale (cluster RDS AWS, user, password bcrypt-like) commit-uite în `application.properties` (linii 15-26) |
| 5 | **Major – Arhitectură** | `CompetitionController.java` are **7.312 linii**, 46 dependențe injectate, 37 endpoint-uri, state mutabil în câmpuri private |
| 6 | **Major – Arhitectură** | Controllere injectate în alte controllere și servicii (`AdminController → CompetitionController`, `AssistantManagerService → TacticController`) – 27 de adnotări `@Lazy` ca workaround pentru dependențe circulare |
| 7 | **Major – Performanță** | `findAll()` apelat de 141 ori în controllere/servicii, fără paginare, multe în bucle (N+1 garantat) |
| 8 | **Major – Calitate** | 3 fișiere de test pentru 218 fișiere de producție (acoperire estimată <1%) |
| 9 | **Major – Calitate** | 0 utilizări `@Valid`/`@Validated` deși `spring-boot-starter-validation` e în `pom.xml` |
| 10 | **Mediu – Securitate/Logică** | Niciun endpoint nu verifică ownership-ul: orice user poate manipula resursele oricărei echipe via `@PathVariable teamId` |

Concluzia generală: **codul "merge" pentru un proiect educațional/demo single-user, dar nu este pregătit pentru producție multi-tenant**. Cele 10 puncte de mai sus se desfac mai jos în findings concrete cu referințe fișier:linie.

---

## 1. Arhitectură

### 1.1 Stack și structură pe pachete

Stack confirmat din `pom.xml` și `application.yml`:

- Spring Boot 3.0.4, Java 17
- Spring Web, Spring Security, Spring Data JPA, Spring Validation, Spring Kafka 3.0.5
- Persistență: H2 in-memory (default), PostgreSQL și MySQL drivers prezenți
- Lombok 1.18.42, Apache Commons Math 3.6.1, `org.json` 20160212
- Documentație: `springdoc-openapi-starter-webmvc-ui` 2.0.4 **și** `springfox-boot-starter` 3.0.0 (suprapunere – vezi 1.4)
- Frontend Angular (separat de acest repo)

Structura pachetelor sub `com.footballmanagergamesimulator` este orientată **strict pe layere**: `controller/`, `service/`, `repository/`, `model/`, plus pachete utilitare (`algorithms/`, `nameGenerator/`, `transfermarket/`, `util/`, `frontend/` – DTOs). Domeniul nu este modularizat (nu există `competition/`, `transfer/`, `training/` ca module independente), ceea ce explică creșterea necontrolată a `CompetitionController`.

### 1.2 God class: `CompetitionController.java`

```
7.312 linii
37 endpoint-uri (@*Mapping)
46 @Autowired
3 metode @Transactional
1 @PostConstruct cu logică grea de inițializare a DB
state mutabil în câmpuri (`transferWindowOpen`, `seasonTransitionInProgress`, …)
```

Exemple:

- `CompetitionController.java:147-150` – flag-uri de stare salvate în câmpuri private:
  ```java
  private boolean transferWindowOpen = false;
  private boolean seasonTransitionInProgress = false;
  private boolean teamTalkUsedThisRound = false;
  ```
  Singleton-ul Spring face ca aceste flag-uri să fie "globale pe JVM" – orice user vede aceeași stare. Combinat cu lipsa autentificării reale, simulatorul nu poate fi rulat pentru mai mulți utilizatori în paralel.

- `CompetitionController.java:157` – `@PostConstruct initializeRound()` șterge `competition_team_info` și repopulează DB-ul la fiecare boot. Asta merge pentru H2 in-memory, dar va distruge datele dacă se trece pe Postgres (vezi `application.properties` linia 15 unde Postgres a fost cândva configurat).

- `CompetitionController.java:119-136` – câmpuri `@Lazy` ca workaround pentru cicluri:
  ```java
  @Lazy ScoutManagementController scoutManagementController;
  @Lazy FinanceService financeService;
  @Lazy StaffService staffService;
  @Lazy SeasonTransitionService seasonTransitionService;
  ```

### 1.3 Inversare de dependență ratată: controllere injectate în controllere/servicii

- `AdminController.java:29` → `private CompetitionController competitionController;`
  Apoi `AdminController` îl folosește pentru `calculateTransferValue(...)` – logică de business care nu ar trebui să trăiască într-un controller.
- `AssistantManagerService.java:38` → `private TacticController tacticController;`
- `GameAdvanceService.java:28` → `@Autowired CompetitionController competitionController;`
- `LoanController.java:38` → `@Autowired CompetitionController competitionController;`

În total **27 de `@Lazy`** în proiect (rezultat al grafului de dependențe care nu se poate construi eager). Acesta este simptomul clasic al absenței unei separări *controller (HTTP) / service (business) / repository (date)*.

### 1.4 Documentație API duplicată

`pom.xml` declară **două** stack-uri OpenAPI/Swagger care nu sunt compatibile:

```
springdoc-openapi-starter-webmvc-ui 2.0.4  (Spring Boot 3 / OpenAPI 3)
springfox-boot-starter 3.0.0                (vechi, nu suportă Spring Boot 3)
springfox-swagger2 3.0.0                    (vechi)
```

`SwaggerConfig.java` folosește `Docket` din Springfox, dar Springfox 3 nu funcționează cu Spring Boot 3 (lipsește `javax.servlet`). Configurația e moartă; doar `springdoc` produce efectiv documentația la `/swagger-ui/**`.

### 1.5 CORS – configurare locală duplicată

Fiecare controller are propriul `@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")` – 26 de apariții. Mai potrivit: un singur `CorsConfigurationSource` la nivel de aplicație. Mai mult, `WebSecurityConfig.java:39` apelează `.cors()` fără a furniza explicit bean-ul, deci se bazează pe defaultul Spring.

### 1.6 DTO-uri vs entități

Pachetul `frontend/` conține DTO-uri (`PlayerView`, `TeamCompetitionView` etc.), dar multe controllere returnează direct entități JPA serializate cu Jackson (de ex. `ManagerController.getManagerHistory()` returnează `List<ManagerHistory>`). Asta:
- expune câmpuri interne (`@Id`, FK-uri),
- creează cuplaj fragil între schemă DB și contractul API,
- riscă cicluri de serializare dacă în viitor se adaugă relații JPA bidirecționale.

### 1.7 Pachet `controller/` cu fișier non-controller

`controller/KafkaConfig.java` este `@Configuration`, nu controller. Aparține în `config/`.
`controller/ControllerTest.java` definește un `@RestController` cu endpoint `/messages` care creează manual `KafkaTemplate`-uri în handler și citește din fișiere – cod experimental/demo care nu ar trebui în main sources.

---

## 2. Calitate cod

### 2.1 Acoperire teste catastrofală

```
src/main/java  : 218 fișiere, 30.893 LoC
src/test/java  : 3 fișiere
   - RoundRobinTest.java     (algoritm pur)
   - HumanServiceTest.java   (Mockito)
   - TacticServiceTest.java  (test direct)
```

Toate cele 214 endpoint-uri și toate serviciile complexe (`MatchSimulationService`, `GameAdvanceService`, `SeasonTransitionService`, `LiveMatchSimulationService`, logica de transferuri AI) sunt **netestate**. Dependența `spring-security-test` e inclusă, dar nu folosită.

### 2.2 Validare lipsă

```
grep -rn "@Valid\|@Validated" src/main/java → 0 rezultate
```

Cu toate că `spring-boot-starter-validation` e în clasa `pom.xml`. Toate endpoint-urile primesc fie `Map<String, Object>` "soup", fie obiecte fără adnotări `@NotNull`/`@Size`/`@Min`. Exemple:

- `AdminController.generatePlayer` – cast-uri brutale `(String) body.get("position")`, `(Number) body.get("rating")` care aruncă `ClassCastException`/`NullPointerException` la input invalid.
- `AuthController.login` ia un `Map<String, String>` în loc de DTO; nu validează lungime, format etc.
- `UserService.saveUser` face validare manuală în cod (lungime nume, "@" în email) – exact ce ar trebui delegat Bean Validation.

### 2.3 Logging cu `System.out` / `System.err` / `printStackTrace`

```
74 ocurențe System.out / System.err / e.printStackTrace
```

Exemple: `GameController.importGame` (linia ~750) face `e.printStackTrace()` într-un endpoint POST. Nu există SLF4J/Logback configurat; nu se poate filtra după nivel, nu se poate trimite în agregator. Recomandare: `@Slf4j` Lombok + `log.error("...", e)`.

### 2.4 Cod duplicat / "copy-paste" massiv

- 153 de injecții directe de `*Repository` în controllere (sare peste service layer).
- Logica de "ia echipa user-ului din header X-User-Id" e duplicată; există `UserContext.getTeamId(request)` dar este apelat în zeci de locuri în loc să existe un `@RequestScope` user/team holder injectat ca obiect.
- 67 de `new Random()` în loc de un singur bean injectat (sau `ThreadLocalRandom`); nu se poate face seed reproductibil pentru teste sau debug.
- `HumanController.buildPlayerView()` are deja TODO-ul autorului: `"todo move into service"` (`HumanController.java:79`).

### 2.5 Stare mutabilă în servicii

`UserService.java:21-22`:

```java
@Service
@Data @Getter @Setter @AllArgsConstructor @NoArgsConstructor
public class UserService {
    @Autowired public UserRepository userRepository;
    String totalChat = "", lastMessageFrom = "", text = "";
```

Trei câmpuri mutabile **într-un singleton** + Lombok `@Data` (generează `equals/hashCode` pe câmpuri mutabile). În producție multi-user, chat-ul s-ar amesteca între utilizatori.

### 2.6 Lombok – versiune neuzuală

`pom.xml` declară `lombok 1.18.42`, mai nouă decât ce am vizibil în knowledge cutoff (`1.18.36`). Dacă build-ul trece, e ok – dar verifică în `mvn dependency:resolve` că versiunea există efectiv în Maven Central și nu vine dintr-un repo proxy intern (risc de supply-chain). Indiferent de versiune, declararea ei și ca `<dependency>` și în `<annotationProcessorPaths>` cu același număr e redundantă; lasă doar processor path-ul pe Boot 3.

### 2.7 Dependențe duplicate / învechite

- `org.json:json:20160212` – versiune din 2016, cu CVE-uri cunoscute (`CVE-2022-45688`). Migrare către Jackson (deja prezent).
- `springfox-*` 3.0.0 – mort de ani buni, conflict cu Spring Boot 3 (vezi 1.4).
- `javax.xml.bind:jaxb-api:2.3.1` – `javax.*` namespace, în timp ce Spring Boot 3 a migrat pe `jakarta.*`.
- `javax.servlet:javax.servlet-api:4.0.1` – la fel, conflict cu `jakarta.servlet` (Boot 3).
- `mysql:mysql-connector-java:8.0.30` și `org.postgresql:postgresql:42.5.6` – ambele drivere incluse, dar doar unul e folosit la rulare; reduce footprint-ul JAR-ului.

### 2.8 `Procfile` și deploy

`Procfile`: `web: java -jar target/football-manager-simulator-0.0.1-SNAPSHOT.jar`

Pentru deploy pe Heroku, JAR-ul trebuie să fie construit (nu doar prezent în repo). Nu există `.heroku/` sau `system.properties` complet (e doar `java.runtime.version=17`, ok). Dar nu există nici un buildpack explicit și nici workflow CI.

### 2.9 Cod commit-uit suspicios

- `node-installer.pkg` (84 MB) commit-uit în root – nu are ce căuta în repo Java.
- `out/` (build artefacte IntelliJ) este în repo, deși `.gitignore` cere ignorarea lui `target/`. `.gitignore` are duplicate (`target/` apare de două ori).
- `hollow_triangle` – fișier necunoscut în root, fără extensie.
- `footballmanagergamesimulator.iml` – fișier IntelliJ commit-uit.
- `.idea/shelf/Uncommitted_changes_before_Update_at_03_01_2025...` – istoric de "shelves" IDE commit-uit.

---

## 3. Securitate

### 3.1 [CRITIC] `permitAll()` pe orice request

`WebSecurityConfig.java:42-50`:

```java
.authorizeHttpRequests((requests) -> requests
    .requestMatchers("/", "/home", "/register", "/login", "/api/auth/**").permitAll()
    .requestMatchers("/swagger-ui/**", "/v3/api-docs/**").permitAll()
    .requestMatchers("/h2-console/**").permitAll()
    .anyRequest().permitAll() // TODO: change to .authenticated() once user-team ownership is implemented
)
```

Toată suprafața API (214 endpoint-uri) este publică. Chiar și TODO-ul recunoaște problema. Combinând cu lipsa ownership-ului, **orice atacator extern poate**:
- crea/șterge jucători (`/admin/generatePlayer`),
- injecta bani în orice echipă (`/admin/injectMoney`),
- importa un "save game" complet care șterge tot și inserează ce vrea atacatorul (`POST /import` – vezi 3.6),
- citi/modifica ce echipă vrea via `X-User-Id` arbitrar trimis din header.

### 3.2 [CRITIC] Autentificare fără parolă

`AuthController.java:23-50`:

```java
@PostMapping("/login")
public Map<String, Object> login(@RequestBody Map<String, String> body) {
    String username = body.get("username");
    ...
    // Find or create user
    Optional<User> existing = userRepository.findByUsername(username);
    User user;
    if (existing.isPresent()) {
        user = existing.get();
    } else {
        user = new User();
        user.setUsername(username);
        user.setActive(true);
        user.setRoles("USER");
        userRepository.save(user);
    }
    result.put("success", true);
    result.put("userId", user.getId());
    ...
}
```

- Nu există verificare de parolă.
- Account enumeration trivial: dacă username-ul nu există, e creat automat (deci nu se poate distinge "wrong password" de "user nou").
- Nu se emit niciun token / cookie de sesiune semnat. Frontendul "memorează" `userId`, iar restul cererilor trimit `X-User-Id: <numar>` (vezi `UserContext.java:14`) – atacatorul setează `X-User-Id: 1` și impersonează admin-ul.

`UserRegistrationController` (POST `/register`) face login cu parolă bcrypt – dar acest path nu produce sesiunea folosită de SPA. Avem deci două fluxuri concurente de auth, niciunul complet.

### 3.3 [CRITIC] Privilege escalation by design

`UserDetailsImpl.java:24-26`:

```java
this.authorities = Arrays.stream("USER,ADMIN".split(","))
        .map(SimpleGrantedAuthority::new)
        .collect(Collectors.toList());
```

Lista de roluri e **hard-coded**: orice utilizator care ajunge prin form-login Spring (`/login` Thymeleaf) primește automat și `ROLE_ADMIN`. Câmpul `User.roles` există ("USER"), dar e ignorat.

### 3.4 [CRITIC] Secrete commit-uite

`application.properties:14-27` conține (în comentarii) un connection string Heroku Postgres complet:

```
spring.datasource.url=jdbc:postgresql://c3l5o0rb2a6o4l.cluster-czz5s0kz4scl.eu-west-1.rds.amazonaws.com:5432/df061j2rqsomrf
spring.datasource.username=u43vqtjlia7r5c
spring.datasource.password=p9292642ff5fda9c62bb7df3c4bf31393a50a518512d6a2e8b6d534f9785c6dc5
```

Chiar dacă instanța nu mai există, credențialele rămân în istoricul git (`.git/`). **Acțiune imediată**: rotire parolă/instanță Heroku + `git filter-repo` pentru curățarea istoricului + adăugare în `.gitignore` a fișierelor cu secrete + folosire de variabile de mediu (`application.yml` deja are placeholder-ele `${DB_PASSWORD:}`).

### 3.5 [CRITIC] H2 console publică + CSRF disabled

```java
.requestMatchers("/h2-console/**").permitAll()
.csrf().disable()
```

`/h2-console` cu CSRF off → orice utilizator poate executa SQL arbitrar (DROP, INSERT) prin browser, dacă aplicația e accesibilă. Trebuie activată doar pe profil `dev` și protejată cu Basic Auth + IP whitelist.

### 3.6 [Major] Endpoint distructiv `POST /import`

`GameController.java:672-755`:

```java
@PostMapping("/import")
@Transactional
public Map<String, Object> importGame(@RequestBody Map<String, Object> save) {
    entityManager.createNativeQuery("SET REFERENTIAL_INTEGRITY FALSE").executeUpdate();
    List<Object> tables = entityManager.createNativeQuery(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC'").getResultList();
    for (Object row : tables) {
        String tableName = ...;
        entityManager.createNativeQuery("TRUNCATE TABLE \"" + tableName + "\" RESTART IDENTITY").executeUpdate();
    }
    ...
}
```

- Endpoint public (3.1) care **truncă întreaga bază de date** și inserează datele primite de la client cu native query construit prin concatenare de string (`importNative`, linia 845: `"INSERT INTO " + tableName + " (" + String.join(", ", columns) + ") VALUES (...)"`).
- Numele tabelei provine din schema H2, deci nu e atacator-controlled, dar numele coloanelor provin din **cheile JSON ale request-ului**, transformate cu `camelToSnake`. Sunt filtrate față de `INFORMATION_SCHEMA.COLUMNS` deci nu se poate injecta DDL, dar logica este foarte fragilă.

### 3.7 [Major] Lipsă verificare ownership

Toate endpoint-urile care primesc `@PathVariable long teamId` (peste 50 dintre cele 214) **acționează asupra acelui teamId fără să verifice că aparține user-ului apelant**. Exemple:

- `LoanController.getActiveLoans(@PathVariable long teamId)` – returnează datele oricărei echipe.
- `InboxController.markAllRead(@PathVariable teamId)` – marchează ca citite mesajele oricărei echipe.
- `TrainingController.saveSchedule(@PathVariable teamId, ...)` – salvează training-ul oricărei echipe.
- `TransferOfferController` toate endpoint-urile cu `@PathVariable teamId`.

`UserContext.getTeamId(request)` există dar e folosit doar pentru a determina "echipa mea curentă", nu pentru a valida că `pathTeamId == userTeamId`.

### 3.8 [Major] CSRF dezactivat global + form login activ

`csrf().disable()` + `formLogin()` activ = formularul de login este vulnerabil la login CSRF (atacatorul forțează victima să se logheze cu contul atacatorului pentru a captura date).

### 3.9 [Mediu] `GlobalExceptionHandler` returnează `e.getMessage()`

`GlobalExceptionHandler.java`:

```java
return ResponseEntity.badRequest().body(Map.of("error", message != null ? message : "Unknown error"));
```

Mesajul unei `RuntimeException` necunoscute poate leak-ui detalii interne (path-uri, stack, query SQL). Trebuie logat intern, dar returnat un mesaj generic clientului.

### 3.10 [Mediu] Dependențe cu CVE-uri cunoscute

- `org.json:20160212` – `CVE-2022-45688` (stack overflow în XML.toJSONObject).
- `Spring Boot 3.0.4` – ieșit din suport, multe CVE-uri rezolvate ulterior (`CVE-2024-22243` redirect SSRF, `CVE-2024-22262`, `CVE-2024-38816` path traversal). Upgrade la 3.3+/3.4+.
- `commons-math3:3.6.1` – ultimul release 2016, neîntreținut, dar fără CVE-uri critice cunoscute.

### 3.11 [Minor] CORS prea permisiv în dev

Defaultul `cors.allowed-origins:http://localhost:4200` este OK pentru dev, dar dacă cineva setează `cors.allowed-origins=*` în producție (fără să se uite la fiecare controller) rezultatul e CORS open. Mai bine: definiție centralizată + listă explicită + log warning la `*`.

---

## 4. Performanță & DB

### 4.1 `findAll()` în controllere/servicii – 141 ocurențe

Niciuna paginată. Exemple problematice:

- `GameController.exportGame` (linia 616+) – serializează `roundRepository.findAll()`, `gameCalendarRepository.findAll()`, `teamRepository.findAll()`, `competitionRepository.findAll()`, plus încă ~20 de `findAll()` pe entități mari (`humans`, `playerSkills`, `matchEvents`, `scorers`, `financialRecords`, `competitionTeamInfoMatches`). Răspunsul poate ușor ajunge la zeci de MB după câteva sezoane. Recomandare: streaming JSON (`StreamingResponseBody`) + cursor paging la nivel de repo.

- `HumanController.getAllPlayers` – `humanRepository.findAll().stream().filter(...).map(this::buildPlayerView).toList()`. Pentru fiecare jucător `buildPlayerView` face `teamRepository.findById(...)` (N+1). Două probleme cumulate.

- `UserContext.isHumanTeam(long teamId)` – `userRepository.findAll().stream().anyMatch(...)`. Trebuie `existsByTeamId(long teamId)` în repository.

- `UserContext.getAllHumanTeamIds()` – similar, ar trebui `@Query("SELECT DISTINCT u.teamId FROM User u WHERE u.teamId IS NOT NULL")`.

### 4.2 N+1 garantat în `processEndOfSeason` & `initialization`

`CompetitionController.java:275-322` (în `initialization()`):

```java
for (Team team : allTeams) {
    List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(...);
    List<CompetitionTeamInfo> competitions = competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(...);
    for (Human human : allPlayers) {
        for (CompetitionTeamInfo competitionTeamInfo : competitions) {
            ...
            scorer.setCompetitionTypeId(
                competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()) != null ?
                competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()).intValue() : 0); // DOUĂ apeluri pe iterație
            ...
            scorer.setCompetitionName(competitionRepository.findNameById(competitionTeamInfo.getCompetitionId()));
            scorerRepository.save(scorer);   // SAVE în interiorul triplu-for-loop
        }
        if (scorerLeaderboardRepository.findByPlayerId(human.getId()).isEmpty()) { ... }
        Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry =
            scorerLeaderboardRepository.findByPlayerId(human.getId());  // re-citire
        scorerLeaderboardRepository.save(scorerLeaderboardEntry);
    }
}
```

Pentru N echipe, M jucători/echipă, K competiții/echipă rezultă **N × M × K × ~6 query-uri**. Cu 20 echipe × 22 jucători × 3 competiții = ~7.900 query-uri doar aici. La pornire blocează DB-ul.

Recomandări:
- batch save: `saveAll(...)` pentru `scorerRepository` în afara buclei.
- cache local pentru `competitionRepository.findTypeIdById` (sau preîncărcare o singură dată).
- `findByPlayerId` apelat de două ori consecutiv – salvează rezultatul.

### 4.3 `saveAll`/`save` în bucle: 342 ocurențe

```
grep .save( în controller/service → 342
```

Combinat cu lipsa `@Transactional` în multe servicii, fiecare `save` deschide propria tranzacție și face un round-trip DB. Sub Postgres, asta înseamnă latențe inacceptabile.

### 4.4 Lipsă completă de relații JPA și indexuri

```
grep "@OneToMany|@ManyToOne|..." model/  → 0
grep "@Index|@Table(indexes" model/      → 0
```

- Toate relațiile sunt FK "scalare" (`long teamId;`), navigate manual prin `findById`. Asta înseamnă că ORM-ul nu poate face JOIN/FETCH; orice "lazy load" e de fapt un nou query.
- Nu sunt definite indexuri pe câmpuri folosite intens în `findBy*`: `Human.teamId`, `Human.typeId`, `CompetitionTeamInfoMatch.competitionId`, `Scorer.playerId` etc. Pe H2 dev nu se simte; pe Postgres cu zeci de mii de rânduri va deveni dureros rapid.

Recomandare: adăugare `@Table(indexes = {@Index(columnList = "team_id, type_id"), ...})` pe entități cheie.

### 4.5 Inițializare grea în `@PostConstruct`

`CompetitionController.initializeRound()` (`CompetitionController.java:157`) face la fiecare boot:
- `competitionTeamInfoRepository.deleteAll()`,
- `initialization()` – creează echipe, jucători, manageri, scoreri, calendare,
- generează fixturi pentru toate ligile,
- generează obiective de sezon.

Probleme:
- imposibil de rulat cu o BD persistentă (Postgres) fără să distrugă datele;
- crește semnificativ "startup time";
- ar trebui mutat în `CommandLineRunner` activat doar pe profilul `dev`/H2 sau într-un `@EventListener(ApplicationReadyEvent.class)` care detectează DB gol.

### 4.6 `ddl-auto=update` default

`application.yml`:

```yaml
ddl-auto: ${DDL_AUTO:update}
```

`update` este OK pentru prototip, dar nu acceptabil în producție (lipsesc migrările). Recomandare: Flyway sau Liquibase, cu `ddl-auto=validate`.

### 4.7 Lipsă connection pool tuning

Spring Boot 3 folosește HikariCP cu defaulturi (10 conexiuni). Nu este setat nimic explicit; pentru un simulator care face mii de query-uri pe sezon, e necesar `spring.datasource.hikari.maximum-pool-size`, `idle-timeout`, `max-lifetime`.

### 4.8 Lipsă cache

Nu există `@EnableCaching` și niciun `@Cacheable`. Lookup-uri intens reutilizate (numele echipei după id, tipul competiției) sunt re-query-uite la fiecare iterație. Caffeine + `@Cacheable("teamNames")` pe `TeamRepository.findNameById` ar elimina sute de query-uri pe round.

### 4.9 `@Transactional` la nivel de controller

`@Transactional public void processEndOfSeason(...)` în `CompetitionController` – plasarea adnotării în controller funcționează numai dacă apelul vine din afara clasei (proxy-ul Spring). În `CompetitionController` se apelează `processEndOfSeason` din scheduler? `GameAdvanceService.competitionController.processEndOfSeason(...)` – ok, e apel extern → tranzacție pornește. Dar dacă cineva refactorizează și apelează intern, va eșua silent.
Best practice: `@Transactional` pe servicii, nu pe controllere.

### 4.10 Kafka prezent dar fără consumer/producer configurat

`pom.xml` include `spring-kafka` și `kafka-clients`, există `KafkaConfig` (doar `KafkaTemplate`) și `KafkaService`, și un `ControllerTest /messages` care creează manual conexiuni cu broker hardcodat `localhost:39092`. Nu există topic-uri reale folosite în business logic. Probabil cod mort sau în lucru → suprafață de risc gratuită (cost dependențe, atac surface).

---

## 5. Recomandări prioritizate

### 5.1 P0 – De rezolvat înainte de orice deploy public

1. **Rotire credențiale Postgres** scoase la 3.4 și rescriere istoric git.
2. **Securitate**:
   - `.anyRequest().authenticated()` în `WebSecurityConfig`.
   - Înlocuire `AuthController.login` cu autentificare reală pe parolă bcrypt + emitere JWT (sau cookie HTTPOnly).
   - Eliminare hard-codare `"USER,ADMIN"` din `UserDetailsImpl`; citește din `user.getRoles()`.
   - Mutare H2 console doar pe profil `dev`; ștergere ulterioară.
3. **Ownership check**: middleware (`HandlerInterceptor`) sau metode utility `assertOwns(request, teamId)` apelate la fiecare endpoint cu `@PathVariable teamId`.
4. **Reactivare CSRF** sau (mai potrivit pentru SPA) trecere la JWT stateless + dezactivare form login.
5. **Restricționare endpoint-uri admin** (`/admin/**`, `/import`, `/export`) cu `@PreAuthorize("hasRole('ADMIN')")` real.

### 5.2 P1 – Refactor structural

6. **Spargere `CompetitionController` 7.312 linii**:
   - `SeasonLifecycleService` (initialization, end-of-season),
   - `MatchOrchestrationService` (simulateRound),
   - `FixtureQueryController` (read-only HTTP),
   - `TransferWindowService` (state).
   - State (`transferWindowOpen` etc.) → entitate `GameState` în DB.
7. **Inversare dependențe controller→controller**: extrage logica în service comun (`TransferValueService`, `TacticEvaluationService`).
8. **Logger SLF4J peste tot**; șterge `System.out`, `printStackTrace`.
9. **Bean Validation** (`@Valid @RequestBody Dto`) cu DTO-uri tipate, nu `Map<String, Object>`.
10. **Curățare pom**: scoate Springfox + `org.json` + `javax.servlet`/`jaxb-api` + driver-ul DB nefolosit; upgrade Spring Boot la `3.3.x`/`3.4.x` LTS.

### 5.3 P2 – Performanță & DB

11. **Indexuri JPA** pe `Human.teamId`, `Human.typeId`, `Scorer.playerId`, `CompetitionTeamInfoMatch.competitionId`, `MatchEvent.matchId` etc.
12. **Migrare Flyway/Liquibase** + `ddl-auto=validate`.
13. Înlocuire `findAll().stream().filter(...)` cu query custom (`@Query` sau derived).
14. Batching: `saveAll` în loc de `save` în bucle, plus `@Transactional` la nivel de serviciu pentru a strânge într-un singur commit operațiile end-of-season.
15. Caffeine + `@Cacheable` pe lookup-uri statice ("name by id", "type by id").
16. Mutare `@PostConstruct initializeRound()` pe profil dev / detectie DB gol; introducere data seed via SQL/JSON încărcat doar la prima rulare.

### 5.4 P3 – Calitate cod & teste

17. Scrie un fundament minim de teste:
    - `@WebMvcTest` pentru fiecare controller cu happy/sad path.
    - `@DataJpaTest` pentru repository-uri cu query-uri custom.
    - Teste de integrare pentru `MatchSimulationService`, `SeasonTransitionService` (acoperă bug-urile cele mai dureroase).
18. Adaugă pipeline CI (GitHub Actions) cu `mvn verify` + JaCoCo (target 60% pe servicii).
19. Înlocuiește `new Random()` cu bean injectat – seed-abil în teste.
20. Reactivează `.gitignore` + șterge artefactele commit-uite (`node-installer.pkg`, `out/`, `.idea/shelf/`, `hollow_triangle`, `*.iml`).

---

## 6. Constatări pozitive

Pentru echilibru, lucruri bine făcute:

- **Modelarea domeniului** este bogată și coerentă: 50+ entități acoperă realist Football Manager (`Suspension`, `Award`, `BoardRequest`, `Sponsorship`, `FacilityUpgrade`, `MedicalCentre` implicit via `Injury` + fitness).
- **`RoundRobinTest.java`** demonstrează că autorul știe să scrie teste când vrea.
- **`UserContext`** este o abstracție corectă (chiar dacă subutilizată) pentru a centraliza accesul la user-ul curent.
- Folosirea unei strategii `CompositeNameGenerator` cu pattern Strategy (`AbstractNameGeneratorStrategy`, `DongNameGenerator`, `ElevenNameGenerator`, `KessNameGenerator`) e elegantă.
- Pattern Strategy similar în `transfermarket/` (`AbstractTransferStrategy`, `BuyYoungSellHighTransferStrategy`, `CompositeTransferStrategy`).
- **`MatchSimulationService`** este conștient extras din `CompetitionController` ("will be migrated incrementally" – linia 19); direcția e bună, trebuie continuată.
- README clar, screenshot, descriere a feature-urilor.
- DTO-urile din pachetul `frontend/` arată că există conștientizarea separării API ↔ entități, doar că nu e aplicată consistent.

---

## 7. Referințe rapide (cheat-sheet pentru remediere)

| Fișier | Linie | Problemă | Tipul |
|---|---|---|---|
| `config/WebSecurityConfig.java` | 39, 42-50 | `csrf().disable()`, `anyRequest().permitAll()` | Securitate critic |
| `user/AuthController.java` | 23-50 | Login fără parolă, account auto-create | Securitate critic |
| `user/UserDetailsImpl.java` | 24-26 | Rol `ADMIN` hard-coded pentru toți | Securitate critic |
| `application.properties` | 14-27 | Credențiale Postgres în comentarii | Securitate critic |
| `controller/CompetitionController.java` | 1-7312 | God class, state mutabil, init în `@PostConstruct` | Arhitectură |
| `controller/AdminController.java` | 29 | Controller→Controller injection | Arhitectură |
| `service/UserService.java` | 21-22 | Stare mutabilă în singleton | Calitate |
| `controller/GameController.java` | 672-755 | `/import` truncă DB-ul – endpoint public | Securitate major |
| `controller/HumanController.java` | 39-44 | `findAll()` + N+1 în map | Performanță |
| `controller/CompetitionController.java` | 275-322 | Triplu for-loop cu save + N+1 | Performanță |
| `pom.xml` | – | `org.json:20160212`, Springfox 3, `javax.*` în Boot 3 | Calitate/Securitate |
| – | 218 vs 3 | 1.4% acoperire (estimată) | Calitate |

---

*Raport generat pe baza unui audit profund al codului – fiecare referință fișier:linie a fost verificată direct în sursă.*
