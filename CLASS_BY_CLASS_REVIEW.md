# Football Manager Backend - Review Clasa-cu-clasa

Data: 2026-05-26
Stare analizata: worktree-ul curent, cu modificarile locale existente

## Cum sa citesti documentul

- `P0` = risc critic sau bug care poate compromite aplicatia / datele.
- `P1` = risc mare functional sau arhitectural.
- `P2` = risc mediu, important pentru mentenanta, corectitudine sau performanta.
- `P3` = risc mic / observatie de curatare / clasa simpla.

Acest document completeaza [AUDIT_REPORT.md](/Users/ciprian.amza/IdeaProjects/footballmanager-backend-/AUDIT_REPORT.md), dar il rearanjeaza in format "fiecare clasa separat". Pentru clasele cu multa logica intru mai adanc; pentru entitati, DTO-uri si repository-uri notez rolul si riscul concret, fiindca ele contin mult mai putin comportament.

Constatare globala importanta: build-ul nu trece in worktree-ul curent. `mvn -q -DskipTests compile` esueaza in `GameAdvanceService`, deci toate clasele trebuie citite avand in minte ca proiectul este intr-o stare intermediara.

## Core si Config

### `Main`
- Rol: punctul de intrare Spring Boot; activeaza JPA repositories, Kafka, scheduling si web security la nivel global.
- Review: clasa este mica si clara, dar porneste mai multe subsisteme decat foloseste efectiv proiectul in mod matur. Kafka si securitatea sunt activate global, desi implementarea reala a autentificarii si a fluxului Kafka este incompleta.
- Prioritate: `P2`.

### `RoundRobin`
- Rol: algoritm de generare round-robin pentru fixture scheduling.
- Review: una dintre cele mai curate clase din proiect. Are suprafata mica, logica determinista si este acoperita de test, ceea ce o face un exemplu bun de cod usor de mentinut.
- Prioritate: `P3`.

### `GlobalExceptionHandler`
- Rol: traduce `RuntimeException` in raspunsuri HTTP.
- Review: centralizarea erorilor este utila, dar handler-ul returneaza direct `e.getMessage()` catre client. Asta scurge detalii interne si trateaza prea multe cazuri printr-un singur tip de exceptie.
- Prioritate: `P1`.

### `SecurityConfiguration`
- Rol: configureaza view controllers pentru paginile Thymeleaf (`/`, `/login`, `/home`, `/hello`).
- Review: tehnic functioneaza, dar numele clasei induce in eroare: nu configureaza securitate propriu-zisa, ci doar mapping-uri MVC. Aceasta suprapunere de naming complica onboarding-ul.
- Prioritate: `P3`.

### `SwaggerConfig`
- Rol: configuratie Swagger bazata pe Springfox.
- Review: este practic configuratie moarta in contextul actual, pentru ca proiectul foloseste si `springdoc-openapi`, iar Springfox 3 este nepotrivit pentru Spring Boot 3. Clasa adauga confuzie si intretine dependinte legacy fara valoare reala.
- Prioritate: `P1`.

### `WebSecurityConfig`
- Rol: defineste `SecurityFilterChain`, `PasswordEncoder` si `DaoAuthenticationProvider`.
- Review: este una dintre cele mai problematice clase din proiect. `anyRequest().permitAll()` lasa API-ul deschis, CSRF este dezactivat, iar H2 console si Swagger sunt publice. In plus, infrastructura standard Spring Security exista, dar este ocolita de fluxul custom din `AuthController`.
- Prioritate: `P0`.

## User/Auth

### `AuthController`
- Rol: endpoint-uri REST pentru login simplificat si restaurarea sesiunii (`/api/auth/login`, `/api/auth/me`).
- Review: autentifica doar pe baza de username si creeaza automat userul daca nu exista. Nu verifica parola, nu emite token/cookie semnat si permite impersonare usoara prin `userId`. Este una dintre radacinile principale ale problemei de securitate.
- Prioritate: `P0`.

### `User`
- Rol: entitatea de persistenta pentru utilizatorul aplicatiei.
- Review: modelul a evoluat catre multi-user prin `teamId`, `lastTeamId`, `managerId` si `fired`, ceea ce este util. Totusi, campul `roles` nu este folosit corect in securitate, iar relatiile raman scalar IDs, fara integritate la nivel de model.
- Prioritate: `P2`.

### `UserContext`
- Rol: helper central pentru extragerea userului curent si a echipei din header-ul `X-User-Id`.
- Review: ideea de a centraliza contextul de utilizator este buna, dar implementarea se bazeaza pe un header falsificabil. In plus, metode ca `getAllHumanTeamIds()` si `isHumanTeam()` fac `userRepository.findAll()` si muta costul de performanta in zone foarte folosite.
- Prioritate: `P0`.

### `UserDetailsImpl`
- Rol: adaptor Spring Security peste entitatea `User`.
- Review: implementarea structurala este standard, dar autoritatile sunt hardcodate la `USER,ADMIN` in loc sa citeasca din `user.getRoles()`. Asta transforma orice autentificare Spring Security intr-un privilege escalation by design.
- Prioritate: `P0`.

### `UserDetailsServiceImpl`
- Rol: incarca userul dupa username pentru Spring Security.
- Review: clasa este simpla si corecta pentru use case-ul standard. Problema este ca fluxul principal al SPA-ului nici macar nu se bazeaza pe ea, deci infrastructura buna de aici nu compenseaza bypass-ul din `AuthController`.
- Prioritate: `P2`.

### `UserDto`
- Rol: DTO pentru formularul de inregistrare.
- Review: este un POJO simplu, dar nu foloseste Bean Validation (`@NotBlank`, `@Email`, `@Size`). Validarea este mutata manual in `UserService`, ceea ce fragmenteaza regulile si le face mai greu de reutilizat.
- Prioritate: `P2`.

### `UserRegistrationController`
- Rol: controller MVC pentru formularul server-side de inregistrare.
- Review: exista paralel cu SPA auth-ul din `AuthController`, ceea ce creeaza doua fluxuri de autentificare concurente. Acest controller foloseste mai mult modelul clasic Spring MVC, dar nu este sursa reala de sesiune pentru frontend-ul modern.
- Prioritate: `P2`.

### `UserRepository`
- Rol: repository pentru `User`, cu lookup dupa username.
- Review: repository-ul este minim si corect pentru ce face, dar proiectul ar beneficia de query-uri dedicate precum `existsByTeamId`, `findAllByTeamId` si selectie distincta a team IDs, ca sa evite `findAll()` in `UserContext`.
- Prioritate: `P2`.

### `UserService`
- Rol: servicii de inregistrare si obtinere a utilizatorului autentificat.
- Review: combina responsabilitati de validare, persistenta si chiar stare mutabila de tip "chat" (`totalChat`, `lastMessageFrom`, `text`) intr-un singleton Spring. Validarea este manuala si are mesaje inconsistente, iar starea mutabila in serviciu este periculoasa intr-o aplicatie web.
- Prioritate: `P1`.

## Controllers

Observatie comuna pentru aproape toate controllerele: majoritatea sunt expuse public prin `permitAll()`, multe folosesc `teamId` din URL fara sa verifice ownership-ul, iar Bean Validation lipseste aproape complet.

### `AdminController`
- Rol: endpoint-uri admin pentru login, generare jucatori, injectare bani, predetermined scores si job offers.
- Review: este o clasa mare, cu multe responsabilitati distincte. Credențialele si tokenul admin sunt hardcodate, iar unele endpoint-uri mutative (`generatePlayer`, `injectMoney`) nu verifica deloc tokenul. Este un controller care modifica sever starea jocului si ar trebui izolat si securizat mult mai strict.
- Prioritate: `P0`.

### `AssistantManagerController`
- Rol: expune recomandari de la assistant manager pentru formatie, lineup, scouting si briefing.
- Review: controller relativ curat si subtire. Problema principala nu este clasa in sine, ci faptul ca depinde de infrastructura de auth slaba si de un `AssistantManagerService` care rupe layering-ul prin dependenta de `TacticController`.
- Prioritate: `P2`.

### `CareerController`
- Rol: gestioneaza pending job offers, accept/decline si resign pentru manager.
- Review: functional, controller-ul este clar delimitat pe un flux de cariera. Totusi, `getPending()` face cast dintr-un `Integer` potential `null` la `long`, ceea ce produce `NullPointerException` cand lipseste header-ul. Toata securizarea ramane dependenta de `X-User-Id`.
- Prioritate: `P1`.

### `CompetitionController`
- Rol: nucleul istoric al aplicatiei; contine initializare, competii, transfer window, meciuri, clasamente, sezon nou, joburi disponibile si foarte multa alta logica.
- Review: este god class-ul principal al proiectului. Are aproape `8000` linii, in jur de `40` endpoint-uri, `48` injectari, multe `findAll()`, foarte multe `save()` si state mutabil in campuri private. Amesteca HTTP, orchestration, simulare, persistenta si stare de joc intr-un singur singleton. Este principalul obstacol de mentenanta si de testare.
- Prioritate: `P0`.

### `ContractController`
- Rol: calculeaza salarii, reinnoiri, pre-contracte, clauze si promisiuni de playing time.
- Review: logica de business este substantiala si valoroasa, dar traieste direct in controller. Input-ul este slab validat, iar mutatiile pe `Human` si contracte cer limite mai stricte, tranzactii clare si verificari de ownership consistente.
- Prioritate: `P1`.

### `ControllerTest`
- Rol: endpoint de test pentru trimiterea de mesaje Kafka.
- Review: nu ar trebui sa existe in `src/main`. Expune `/messages`, construieste manual `KafkaTemplate` in handler si foloseste brokeri hardcodati. Este cod experimental care ar trebui mutat in test/support sau sters.
- Prioritate: `P1`.

### `FriendlyController`
- Rol: API pentru friendly-uri disponibile, programare, anulare si listare.
- Review: suprafata este mica si destul de bine delimitata. Totusi, fiind mutativ, controller-ul are nevoie de auth si ownership checks reale; altfel, programarea amicalelor ramane o actiune usor de abuzat.
- Prioritate: `P2`.

### `GameController`
- Rol: setup-ul jocului, avansarea calendarului, starea jocului, youth academy, sponsorships, awards, import/export de save.
- Review: al doilea controller cu risc foarte mare. Aici se concentreaza fluxul principal al jocului si endpoint-ul public distructiv `/import`. In plus, clasa mentine compatibilitate atat cu starea noua per-user, cat si cu starea veche din `Round`, ceea ce introduce doua surse de adevar.
- Prioritate: `P0`.

### `HistoryController`
- Rol: citire statistici istorice despre competitii si castigatori.
- Review: controller predominant read-only, relativ sigur din punct de vedere functional. Riscul este mai degraba de performanta si de expunere larga a datelor, nu de corectitudine brutala.
- Prioritate: `P3`.

### `HumanController`
- Rol: expune lista de jucatori, detalii de jucator si comparatii.
- Review: `buildPlayerView()` este util, dar controller-ul face compunere manuala de DTO-uri si introduce N+1 query patterns prin lookup-uri per jucator. Exista si semnale de Optional handling fragil si TODO-uri care confirma ca logica ar trebui mutata in service.
- Prioritate: `P2`.

### `InboxController`
- Rol: mesaje inbox pe echipa, unread count si marcarea mesajelor ca citite.
- Review: ideea de fallback pe `lastTeamId` pentru userii concediati este buna. In schimb, `markRead` si mai ales `markAllRead/{teamId}` nu verifica faptul ca mesajele apartin echipei userului curent, deci controller-ul permite modificarea inbox-ului altor echipe.
- Prioritate: `P1`.

### `InjuryController`
- Rol: accidentari active, istoric, accidentari pe jucator si risk assessment.
- Review: controller orientat pe analytics, fara mutatii directe. Problema principala este performanta: face query-uri suplimentare in bucle pentru nume de jucatori, istorice si scoreri, deci costul creste rapid pe loturi mari. Nu are nici ownership checks.
- Prioritate: `P2`.

### `KafkaConfig`
- Rol: configuratie Spring pentru `KafkaTemplate`.
- Review: functional este o clasa de configurare, nu un controller, deci pachetul este ales prost. Prezenta sa in `controller/` denota lipsa de separare clara intre layere.
- Prioritate: `P3`.

### `LoanController`
- Rol: ofertare de imprumuturi, exercitare buy option si recall.
- Review: contine logica de business semnificativa direct in controller si multe mutatii asupra jucatorilor, echipelor si loan-urilor. Pentru un flux atat de critic, lipsesc tranzactii explicite la nivelul controllerului si validari mai stricte pentru ownership si starea curenta a jucatorului.
- Prioritate: `P1`.

### `LoginController`
- Rol: controller MVC pentru pagina `/login`.
- Review: clasa este foarte mica, dar dubleaza suprafata de autentificare intr-un proiect care are deja un auth REST separat. Simptomul aici nu este complexitatea, ci inconsistența arhitecturala.
- Prioritate: `P3`.

### `ManagerController`
- Rol: profil manager, istoric, leaderboard si responsabilitati.
- Review: expune date utile, dar logica foloseste frecvent pattern-uri fragile precum `managers.get(0)` sau preluarea primului element ca "managerul corect". Datele de responsabilitati ar merita DTO-uri si validare mai serioasa.
- Prioritate: `P2`.

### `MatchController`
- Rol: schedule, calendar, preview, summary, live match si statistici de meci/echipa.
- Review: controller mare, dar in mare parte read-only. Problema sa este mai mult de compunere si complexitate decat de securitate mutativa. Totusi, suprafata larga si logica manuala de agregare il fac dificil de testat si refactorizat.
- Prioritate: `P2`.

### `MediaController`
- Rol: expune predictiile media.
- Review: este foarte simplu si relativ benign. Riscul sau principal vine din dependentele externe ale securitatii globale, nu din logica proprie.
- Prioritate: `P3`.

### `ScoutManagementController`
- Rol: ecosistem complet pentru scouts, assignment-uri, rapoarte si contracte.
- Review: este un controller mare (`~768` linii) cu multa logica de business si multe mutatii. Desi foloseste `UserContext` pentru echipa curenta in mai multe locuri, ramane prea incarcat pentru un controller si are multe `save()` directe. Este una dintre clasele care ar trebui sparte primele dupa `CompetitionController`.
- Prioritate: `P1`.

### `ScoutingController`
- Rol: expune rapoarte de scouting si calcule de transfer value.
- Review: suprafata este mica, dar seamana cu un controller secundar ramas paralel cu `ScoutManagementController`. Ar merita fie consolidat, fie redus la un facade foarte subtire.
- Prioritate: `P3`.

### `SeasonObjectiveController`
- Rol: citire obiective de sezon pe echipa si sezon curent.
- Review: clasa este mica si clara, dar ia `teamId` direct din path si intoarce obiectivele oricarei echipe fara ownership checks. In plus, depinde de `Round` ca sursa globala de sezon curent.
- Prioritate: `P2`.

### `ShortlistController`
- Rol: shortlist per user, add/remove/check/update notes.
- Review: este unul dintre controllerele mai bine aliniate cu modelul per-user, pentru ca lucreaza prin `UserContext` si `userId`. Totusi, validarea este minima, iar legarea la `Round` pentru metadata de sezon arata ca modelul global vechi inca influenteaza functionalitati noi.
- Prioritate: `P2`.

### `StaffController`
- Rol: overview staff si operatii de hire/fire coaches.
- Review: controller compact si destul de curat, cu delegare buna catre `StaffService`. Riscul vine in principal din auth/ownership si din faptul ca serviciul din spate genereaza si muta entitati semnificative.
- Prioritate: `P2`.

### `StatsController`
- Rol: dashboard si analytics pentru jucatori, competitii, campioni, form si comparatii.
- Review: este un controller foarte mare (`~1019` linii) dedicat aproape exclusiv citirii si agregarii datelor. Chiar daca este mai "safe" decat controllerele mutative, face multe agregari manuale si query-uri multiple, deci ramane o clasa greu de optimizat si testat.
- Prioritate: `P1`.

### `TacticController`
- Rol: lineup, formatii, tactici, roluri, ratinguri, best eleven, assistant help.
- Review: este o alta clasa foarte mare (`~915` linii, `~20` endpoint-uri) care amesteca mutatii, query-uri si logică de evaluare tactica. Are multe responsabilitati, iar faptul ca `AssistantManagerService` depinde de ea arata ca logica reutilizabila nu a fost inca extrasa suficient in servicii.
- Prioritate: `P1`.

### `TeamController`
- Rol: metadate echipa, lot, finante si raport financiar.
- Review: aparent simplu, dar `getTeamFinances()` face mult calcul manual si trage `findAll()` pe competitii si standings, apoi filtreaza in memorie. Din punct de vedere al design-ului, controller-ul ar trebui sa doar orienteze cererea, nu sa orchestreze calcul financiar complex.
- Prioritate: `P2`.

### `TrainingController`
- Rol: schedule de echipa, default schedule si individual training.
- Review: endpoint-ul de save face `deleteAll(existing)` urmat de `saveAll(schedules)` fara o tranzactie explicita si fara verificare ca `teamId` apartine userului. Functionalitatea e importanta, iar riscul de overwrite accidental sau de acces neautorizat este real.
- Prioritate: `P1`.

### `TransferController`
- Rol: citire transferuri cumparate/vandute pe sezon.
- Review: este unul dintre cele mai simple controllere din proiect. Faptul ca ramane separat de `TransferOfferController` este rezonabil, dar si aici lipsesc ownership checks daca datele ar trebui sa fie private.
- Prioritate: `P3`.

### `TransferOfferController`
- Rol: incoming/outgoing offers, make/respond, free agents si executie efective de transfer.
- Review: controller cu risc functional mare. Are multa logica mutativa, iar `respondToOffer()` nu valideaza suficient ca oferta apartine echipei userului curent. `executeTransfer()` traieste in controller, ceea ce face greu de testat atomiciate, bugete si side effects de inbox.
- Prioritate: `P0`.

## Services

Observatie comuna pentru servicii: aici sta cea mai multa valoare de business din proiect, dar multe servicii sunt inca prea mari, prea repository-heavy si uneori rup layering-ul prin dependente spre controllere.

### `AssistantManagerService`
- Rol: AI pentru recomandari tactice si de lot.
- Review: functionalitatea este interesanta si bine delimitata conceptual. Problema structurala este dependenta directa de `TacticController`, care inseamna ca serviciul reutilizeaza logica HTTP in loc de logica de domeniu extrasa. Este un simptom clar de separare insuficienta a responsabilitatilor.
- Prioritate: `P1`.

### `AwardService`
- Rol: ceremonii de premii, determinarea castigatorilor si notificari.
- Review: serviciu cu valoare de business buna si boundare destul de clara. Riscurile vin din calculul bazat pe multe query-uri si din lipsa testelor pe reguli de selectie si pe inbox side effects.
- Prioritate: `P2`.

### `BoardRequestService`
- Rol: sedinte de board, deadline-uri, fulfill requests si mesagerie.
- Review: concept bun si bine izolat ca functionalitate, dar se bazeaza pe cautari ale "primului manager" si pe fluxuri manuale de inbox. Ar beneficia mult de teste pe deadline-uri si pe tranzitii de status.
- Prioritate: `P2`.

### `CalendarService`
- Rol: calendarul jocului, faze, claim/release pentru evenimente si avansare.
- Review: una dintre clasele mai curate si mai bine centrate. Faptul ca expune `claimEvent`, `releaseStuckEvents` si `advancePhase` arata o gandire buna pentru orchestration. Este un serviciu bun de folosit ca nucleu pentru un refactor mai amplu.
- Prioritate: `P2`.

### `CompetitionService`
- Rol: utilitar pentru generare de skill-uri si profiluri pe pozitie.
- Review: numele clasei este prea generic fata de responsabilitatea reala. Nu este serviciul central al competitiilor, ci mai degraba un helper de generare/evaluare a skill-urilor.
- Prioritate: `P3`.

### `CupBracketService`
- Rol: genereaza bracket-uri de cupa si propaga castigatorii.
- Review: boundary-ul este bun si relativ clar. Totusi, serviciul ramane destul de mare si gestioneaza operatii de persistenta sensibile pentru o logica knockout; trebuie protejat cu teste de bracket consistency.
- Prioritate: `P2`.

### `FacilityUpgradeService`
- Rol: upgrade-uri de infrastructura, niveluri de facilitati si notificari.
- Review: serviciu substantial, cu multe operatii de citire si actualizare. Exista suprafata pentru dublare de logica si multe lookup-uri repetate; pentru o functie financiara si de progres, tranzactiile si testele ar trebui intarite.
- Prioritate: `P2`.

### `FinanceService`
- Rol: tranzactii financiare, venituri, wages, debt, board confidence si rapoarte.
- Review: unul dintre serviciile cele mai importante pentru simulare. Contine multe reguli de business valoroase, dar si multe side effects. Fara teste solide, bug-urile din finante pot distorsiona intregul save game.
- Prioritate: `P1`.

### `FixtureSchedulingService`
- Rol: generare season calendar, match days, event-uri periodice si sincronizare day-to-match.
- Review: este o piesa critica pentru sanatatea simularii. Complexitatea e mare, iar faptul ca proiectul a avut probleme recente cu meciuri care nu se mai jucau arata cat de sensibil este acest serviciu la regresii.
- Prioritate: `P1`.

### `FriendlyMatchService`
- Rol: friendly scheduling, simulare, fitness effects, morale si auto-scheduling pre-season.
- Review: domeniu bine delimitat, dar serviciul este deja mare si plin de side effects. Pentru ca manipuleaza simultan calendar, loturi, fitness si inbox, are nevoie de teste de integrare mai serioase.
- Prioritate: `P2`.

### `GameAdvanceService`
- Rol: orchestratorul principal al trecerii timpului, procesarii event-urilor si lotului de side effects pe zi/faza.
- Review: este un alt centru de risc major. Are peste `1100` linii, `31` injectari, multe dependente `@Lazy`, foarte multe responsabilitati si chiar build break in worktree-ul curent. Daca `CompetitionController` este centrul istoric al domeniului, `GameAdvanceService` este centrul orchestration-ului actual si necesita stabilizare imediata.
- Prioritate: `P0`.

### `GoalAnimationService`
- Rol: genereaza cadrele animatiei de gol.
- Review: clasa este mare, dar are avantajul major ca este aproape pur algoritmica. Nu are dependente de DB si este o candidata foarte buna pentru teste unitare izolate. Este una dintre cele mai refactorizabile clase mari din proiect.
- Prioritate: `P2`.

### `HumanService`
- Rol: training pe jucatori, fallback training, retirement, regen si generare umani.
- Review: serviciu cu mai multe responsabilitati de lifecycle ale jucatorilor. Este util si central, dar prea lat: amesteca evolutie, creare, regen si curatare de statistici. Asta face dificil de izolat bug-urile.
- Prioritate: `P1`.

### `JobOfferService`
- Rol: genereaza si rezolva job offers pentru managerii umani.
- Review: este unul dintre serviciile mai coerente functional. Boundary-ul este clar, iar metodele sunt relativ curate. Totusi, depinde de sezonul din `Round` si de modelul global al userilor/echipelor, deci ramane sensibil la inconsistenta dintre sistemul vechi si cel nou.
- Prioritate: `P2`.

### `KafkaService`
- Rol: wrapper minimal pentru trimiterea de mesaje Kafka.
- Review: clasa este aproape goala si pare infrastructura incomplet folosita. Nu produce rau direct, dar intretine un subsistem care inca nu pare justificat de functionalitatea reala.
- Prioritate: `P3`.

### `LeagueConfigService`
- Rol: incarca configuratia ligilor si numarul de confruntari.
- Review: serviciu mic si util. Riscul lui este mic, mai ales de robustete la loading/config fallback, nu de domeniu.
- Prioritate: `P3`.

### `LiveMatchSimulationService`
- Rol: simulare live si generare de evenimente minute-by-minute.
- Review: serviciu substantial si important pentru UX-ul de live match. Logica este bogata si bine focalizata, dar fara seed control si teste deterministe debugging-ul devine greu atunci cand apar anomalii.
- Prioritate: `P2`.

### `MatchService`
- Rol: mapper/helper pentru schedule views si intrari de calendar.
- Review: serviciu mai degraba utilitar, rezonabil dimensionat. Nu este o sursa majora de risc, dar ar putea folosi DTO mapping mai explicit pentru a evita compunerea repetata in controllere.
- Prioritate: `P3`.

### `MatchSimulationService`
- Rol: simulare de scoruri, statistici, disponibilitate jucatori, scoreri si ratinguri.
- Review: este o extragere sanatoasa din `CompetitionController`, dar inca foarte mare. Are rol critic pentru corectitudinea meciurilor si ar trebui sa devina una dintre cele mai testate clase din proiect. Este un serviciu bun ca directie de refactor, dar nu este inca "terminat" ca separare.
- Prioritate: `P1`.

### `NationalTeamService`
- Rol: international break, callups, returnarea jucatorilor si notificari.
- Review: serviciu clar ca responsabilitate, dar important pentru consistency intre club si nationala. Are side effects multiple si depinde de detectia echipelor umane, deci trebuie verificat atent cand apar regresii in calendar.
- Prioritate: `P2`.

### `PlayerInstructionService`
- Rol: instructiuni pe pozitie si multiplicatori.
- Review: serviciu mic, aproape pur logic, cu risc redus. E bun candidat pentru a ramane simplu si bine testat.
- Prioritate: `P3`.

### `PlayerRoleService`
- Rol: roluri pe pozitie, suitability, effective rating si best role.
- Review: unul dintre serviciile cele mai valoroase pentru partea de fotbal. Complexitatea lui este justificata, dar fiind o componenta de scoring/evaluare, ar beneficia enorm de o suita de unit tests parametrizata.
- Prioritate: `P2`.

### `PlayerSkillsService`
- Rol: formule de overall rating pe pozitie.
- Review: serviciu compact si aproape pur functional. Este de mare importanta pentru realismul jocului si tocmai de aceea merita acoperire de test superioara fata de cea actuala.
- Prioritate: `P2`.

### `PressConferenceService`
- Rol: generare si rezolvare press conference, plus inbox.
- Review: boundary bun, clasa relativ curata. Riscurile ei sunt moderate si tin mai ales de consistenta calendarului si de efectele moralei/imaginei.
- Prioritate: `P3`.

### `RoundService`
- Rol: placeholder service pentru `Round`.
- Review: practic gol. Semnaleaza o abstractie inceputa si nefolosita sau o mutare neterminata. In starea actuala, mai mult incurca orientarea in cod decat ajuta.
- Prioritate: `P3`.

### `SeasonTransitionService`
- Rol: end-of-season transition, obiective, istoric manageri, contract expiries, promotii/retrogradari, youth si pre-contracts.
- Review: domeniu critic, iar comentariile din clasa spun explicit ca migrarea din `CompetitionController` este in curs. Asta inseamna ca boundary-ul bun exista, dar increderea in completitudine este mica. Orice bug aici corupe sezonul urmator.
- Prioritate: `P1`.

### `SponsorshipService`
- Rol: oferte de sponsorizare, accept/reject si venituri sezoniere.
- Review: serviciu bine delimitat si ușor de inteles. In acelasi timp, interactiunea cu inbox-ul si finantele face importanta testarea regulilor de status si a momentelor in care se aplica veniturile.
- Prioritate: `P2`.

### `StaffService`
- Rol: generare staff, atribute coaching, hire/fire si overview.
- Review: serviciu consistent ca subdomeniu si mai bine separat decat multe altele. Totusi, pentru ca lucreaza cu mai multe tipuri de oameni si atributi, ar beneficia de o descompunere interna mai buna si de teste pe calculul ratingurilor.
- Prioritate: `P2`.

### `StatsService`
- Rol: helper pentru transformarea scorer-ilor in view models.
- Review: foarte mic si relativ inofensiv. Nu este o sursa relevanta de risc.
- Prioritate: `P3`.

### `SuspensionService`
- Rol: yellow/red cards, suspendari active si notificari.
- Review: serviciu important pentru realism si pentru eligibilitatea jucatorilor. Structura lui este rezonabila, dar regulile de cartonas si cumul sunt exact genul de logica ce necesita multe teste, nu doar incredere manuala.
- Prioritate: `P2`.

### `TacticService`
- Rol: helper pentru formatii, substitutions, manager tactic kit si pozitionare.
- Review: serviciu destul de bine conturat, cu logica reutilizabila ce ar trebui folosita si mai mult in afara controllerului. Nu este lipsit de complexitate, dar este intr-un loc mai bun decat daca ar trai integral in `TacticController`.
- Prioritate: `P2`.

### `TeamService`
- Rol: wrapper foarte subtire peste `HumanRepository`.
- Review: clasa este aproape un passthrough si ridica intrebarea daca merita sa existe. In forma actuala, nu adauga suficienta valoare pentru a justifica un service separat.
- Prioritate: `P3`.

### `TeamTalkService`
- Rol: team talks, individual talks, morale changes si reactie jucatori.
- Review: serviciu bogat in business logic, dar bine focalizat. Fiind un sistem pe morale si feedback, ar merita teste de reguli si o documentare mai buna a impactului numeric.
- Prioritate: `P2`.

### `TrainingService`
- Rol: sesiuni de training, focus attributes, role training, development change si match day fitness.
- Review: serviciu central pentru progresul jucatorilor. Are multe reguli implicite si mapping-uri interne, deci este sensibil la regresii silentioase. Este unul dintre serviciile unde lipsa testelor cantareste cel mai mult.
- Prioritate: `P1`.

### `YouthAcademyService`
- Rol: youth report, promovari, dezvoltare, release si generare potential/pozitie.
- Review: serviciu bine delimitat si interesant din punct de vedere de game design. Ramane totusi puternic legat de sezonul global si de mutatiile pe entitati, deci are nevoie de teste de integrare pentru consistency pe termen lung.
- Prioritate: `P2`.

## Repositories

Observatie comuna pentru repository-uri: majoritatea sunt simple si corecte ca intentie, dar proiectul depinde prea mult de ele direct din controllere. Riscul principal nu este in aceste interfete, ci in lipsa indexurilor si in felul in care sunt apelate in bucle sau prin `findAll()`.

- `AwardRepository` — repository simplu pentru cautari dupa sezon si castigator. Corect ca responsabilitate; are nevoie doar de indexuri potrivite in tabela `award`. Prioritate: `P3`.
- `BoardRequestRepository` — query-uri de status si sezon pe team. Boundary bun, dar foarte dependent de indexuri pe `(teamId, status)` si `(teamId, seasonNumber)`. Prioritate: `P3`.
- `CalendarEventRepository` — unul dintre repository-urile importante; pe langa query-uri de citire are si operatii de claim/release pentru concurenta. Aici merita atentie maxima la tranzactii si locking semantics. Prioritate: `P2`.
- `ClubCoefficientRepository` — query-uri standard pe team si sezon. Simplu si adecvat. Prioritate: `P3`.
- `CompetitionHistoryRepository` — lookup istoric dupa competitie sau echipa. Corect, cu risc mic. Prioritate: `P3`.
- `CompetitionRepository` — expune `findTypeIdById` si `findNameById`; foarte util, dar incurajeaza lookup-uri repetitive de scalar fields din multe locuri. Bun candidat pentru caching. Prioritate: `P2`.
- `CompetitionTeamInfoDetailRepository` — repository focalizat pe detalii de competitie/meci. Pare corect; devine important cand se lucreaza cu standings sau rezultate pe runda. Prioritate: `P3`.
- `CompetitionTeamInfoMatchRepository` — repository cheie pentru programari si meciuri. Are query-uri folosite intens si ar trebui sustinut de indexuri serioase; este critic pentru scheduling si head-to-head. Prioritate: `P2`.
- `CompetitionTeamInfoRepository` — standings/entries pe round, competitie, sezon si team. Bun ca API, dar foarte sensibil la volum si la query planning. Prioritate: `P2`.
- `FacilityUpgradeRepository` — query-uri standard pe echipa si stare de completare. Mic si clar. Prioritate: `P3`.
- `FinancialRecordRepository` — pe langa citire, are agregari sum. Important pentru rapoarte financiare si suficient de bine orientat. Prioritate: `P2`.
- `FriendlyMatchRepository` — repository important pentru friendlies si scheduling pe zi/status. Boundary rezonabil. Prioritate: `P3`.
- `GameCalendarRepository` — foarte mic; `findBySeason` intoarce lista, ceea ce sugereaza ca modelul permite mai multe calendare per sezon. Daca regula reala este "un singur calendar pe sezon", API-ul ar trebui sa reflecte asta mai clar. Prioritate: `P2`.
- `HumanRepository` — unul dintre repository-urile cele mai folosite din proiect. API-ul este util, dar lipsesc query-uri mai specifice care ar reduce `findAll()` si filtrarea in memorie. Prioritate: `P2`.
- `InjuryRepository` — ofera query-uri bune pentru active injuries si bulk checks. Important pentru performanta risk assessment si match availability. Prioritate: `P2`.
- `JobOfferRepository` — simplu si bine orientat pe user/status. Boundary bun. Prioritate: `P3`.
- `LoanRepository` — suport bun pentru parent/loan/status/season. Esential pentru flow-ul de imprumuturi. Prioritate: `P2`.
- `ManagerHistoryRepository` — read-oriented si clar. Risc mic. Prioritate: `P3`.
- `ManagerInboxRepository` — API corect pentru inbox si unread count. Problema reala apare in controllerele care il folosesc fara ownership checks. Prioritate: `P3`.
- `MatchEventRepository` — repository critic pentru timeline si statistica pe meci. Are deja query-uri dedicate rezonabile, dar va cere indexuri bune cand volumul creste. Prioritate: `P2`.
- `MatchStatsRepository` — query-uri utile pentru statistici si rapoarte pe sezon. Avand in vedere volumul posibil, indexarea dupa competitie/sezon/team este importanta. Prioritate: `P2`.
- `NationalTeamCallupRepository` — mic si clar. Prioritate: `P3`.
- `PersonalizedTacticRepository` — suficient de simplu; boundary bun pentru tacticile personalizate. Prioritate: `P3`.
- `PlayerInteractionRepository` — corect pentru interactiuni nerezolvate si per sezon. Prioritate: `P3`.
- `PlayerSkillsRepository` — minimal si important. Un singur lookup principal dupa `playerId`, ceea ce cere index clar si unicitate bine definita. Prioritate: `P2`.
- `PredeterminedScoreRepository` — bine orientat pe upsert logic si scoruri neconsumate. Important pentru tooling admin. Prioritate: `P3`.
- `PressConferenceRepository` — simplu si clar. Prioritate: `P3`.
- `RoundRepository` — doar CRUD default. Mic, dar faptul ca multe clase depind de `findById(1L)` arata cat de central a devenit implicit modelul `Round`. Prioritate: `P2`.
- `ScorerLeaderboardRepository` — query-uri putine si utile; important pentru leaderboard-uri. Prioritate: `P3`.
- `ScorerRepository` — repository foarte folosit pentru goluri, statistici si istoric pe player/team/competition. Boundary bun, dar query surface-ul mare il face sensibil la indexuri si volum. Prioritate: `P2`.
- `ScoutAssignmentRepository` — API bun pentru assignment-uri active, completate si expirate. Prioritate: `P3`.
- `ScoutRepository` — query-uri mici si coerente pentru scouts free-agent sau pe echipa. Prioritate: `P3`.
- `SeasonObjectiveRepository` — simplu si adecvat. Prioritate: `P3`.
- `ShortlistRepository` — bine centrat pe `userId` si `playerId`. Unul dintre repository-urile mai curate. Prioritate: `P3`.
- `SponsorshipRepository` — simplu si orientat pe team/status. Prioritate: `P3`.
- `StadiumRepository` — foarte mic; rol clar. Prioritate: `P3`.
- `SuspensionRepository` — bun pentru status activ si competitie. Important pentru eligibilitatea jucatorilor. Prioritate: `P2`.
- `TeamCompetitionDetailRepository` — lookup punctual pe team/competition. Util, cu risc mic. Prioritate: `P3`.
- `TeamCompetitionRelationRepository` — repository foarte subtire; pare subutilizat. Prioritate: `P3`.
- `TeamFacilitiesRepository` — extrem de mic si clar. Prioritate: `P3`.
- `TeamPlayerHistoricalRelationRepository` — util pentru istoric jucator-echipa. Prioritate: `P3`.
- `TeamRepository` — repository de baza, foarte folosit. `findNameById` este util, dar incurajeaza scalar lookups repetate in loc de agregari mai eficiente. Prioritate: `P2`.
- `TrainingScheduleRepository` — simplu si adecvat pentru schedule pe echipa/zi. Prioritate: `P3`.
- `TransferOfferRepository` — corect pentru incoming/outgoing/history, foarte important pentru UI-ul de transfer offers. Prioritate: `P2`.
- `TransferRepository` — simplu si suficient pentru istoricul transferurilor. Prioritate: `P3`.
- `YouthPlayerRepository` — query-uri basic si bune pentru youth squad/status. Prioritate: `P3`.

## Models

Observatie comuna pentru entitatile JPA: aproape toate folosesc scalar IDs in loc de relatii JPA, nu declara indexuri explicite si se bazeaza pe `@Data`/campuri primitive fara Bean Validation. Asta le face simple de serializat, dar muta integritatea si navigarea relatiei in servicii si controllere.

- `Award` — entitate simpla pentru premii sezoniere. Boundary clar, dar fara constrangeri explicite pentru tipul premiului sau referinta castigatorului. Prioritate: `P3`.
- `BoardRequest` — model pentru cerinte de board. Simplu si suficient, dar dependent de string statuses gestionate manual. Prioritate: `P3`.
- `CalendarEvent` — entitatea de baza pentru orchestration-ul calendarului. Foarte importanta functional; ar merita conventii mai stricte pentru `eventType`, `status` si `phase`. Prioritate: `P2`.
- `ClubCoefficient` — model pentru punctaj european/coeficient. Foarte simplu. Prioritate: `P3`.
- `Competition` — model central pentru competitie. Simplu, dar scalar si fara relatii directe spre echipe/runde. Prioritate: `P2`.
- `CompetitionEntry` — DTO simplu, nu entitate. Risc mic. Prioritate: `P3`.
- `CompetitionHistory` — istoric al competitiilor. Model util si clar. Prioritate: `P3`.
- `CompetitionTeamInfo` — model de standings/entry pe runda. Important pentru clasamente; ar beneficia de indexuri bune. Prioritate: `P2`.
- `CompetitionTeamInfoDetail` — detalii suplimentare pe competitie/team. Important pentru UI si standings. Prioritate: `P3`.
- `CompetitionTeamInfoMatch` — model de meci programat in competitie. Esential pentru fixture scheduling si una dintre entitatile cele mai sensibile la corectitudine. Prioritate: `P2`.
- `CompetitionType` — lookup entity mica. Prioritate: `P3`.
- `FacilityUpgrade` — model clar pentru upgrade-uri in progres. Prioritate: `P3`.
- `FinancialRecord` — model important pentru ledger-ul financiar. Ar merita enum-uri sau constrangeri mai stricte pentru categorii. Prioritate: `P2`.
- `FriendlyMatch` — model de friendly, putin mai bogat, util si clar. Prioritate: `P3`.
- `GameCalendar` — stare de sezon/zi/faza. Entitate critica pentru joc, dar in continuare destul de simpla structural. Prioritate: `P2`.
- `Human` — una dintre cele mai mari si mai importante entitati din tot proiectul. A ajuns un model multi-rol pentru jucatori, manageri si staff, cu multe campuri de contract, morale, training si AI. Este util, dar foarte "wide" si greu de evoluat fara bug-uri. Prioritate: `P1`.
- `HumanTeamRelation` — relatie istorica/legatura simpla. Risc mic. Prioritate: `P3`.
- `HumanType` — lookup entity mica. Prioritate: `P3`.
- `Injury` — model simplu si clar pentru accidentari. Prioritate: `P3`.
- `JobOffer` — model important pentru flow-ul de cariera. Contine suficient context ca sa sustina UI-ul; design bun per ansamblu. Prioritate: `P2`.
- `Loan` — model de imprumuturi, destul de bogat si critic pentru acel subdomeniu. Prioritate: `P2`.
- `ManagerHistory` — istoric manager, clar si util. Prioritate: `P3`.
- `ManagerInbox` — model de mesagerie interna. Simplitatea e buna, dar lipsesc constrangeri mai stricte pentru tip/categorie. Prioritate: `P3`.
- `MatchEvent` — model esential pentru timeline-ul meciului. Important functional si pentru analytics. Prioritate: `P2`.
- `MatchSquad` — model mai bogat, util pentru lotul de meci. Fara relatii, dar clar ca intentie. Prioritate: `P2`.
- `MatchStats` — entitate lata pentru toate statisticile de meci. Punct bun: are unique constraint pe combinatie de competitie/sezon/runda/echipe. Ramane totusi un model foarte mare, bun de tinut sub control cu teste si migration discipline. Prioritate: `P2`.
- `NationalTeamCallup` — model simplu pentru callups. Prioritate: `P3`.
- `PersonalizedTactic` — model bun pentru configuratii tactice salvate. Prioritate: `P3`.
- `Player` — nu este entitate JPA; este un DTO/demo model folosit in zona Kafka/test. Prezenta sa in pachetul `model` este ok, dar poate induce in eroare deoarece proiectul deja are `Human` ca model real de jucator. Prioritate: `P3`.
- `PlayerInteraction` — model pentru interactiuni de tip promises/unrest. Boundary bun si simplu. Prioritate: `P3`.
- `PlayerSkills` — una dintre cele mai mari entitati. Model "wide-table" pentru atribute FM-like, cu defaults si campuri tranzitorii de backward compatibility. Functioneaza, dar este foarte sensibil la drift si la lipsa testelor pe formule. Prioritate: `P1`.
- `PredeterminedScore` — model clar pentru tooling admin. Prioritate: `P3`.
- `PressConference` — model simplu pentru evenimente de presa. Prioritate: `P3`.
- `Round` — entitate mica, dar cu impact arhitectural disproportional: este folosita ca sursa globala de adevar pentru sezon si "human team". Aceasta centralitate istorica este una dintre radacinile inconsistentei multi-user. Prioritate: `P1`.
- `Scorer` — model cheie pentru goluri si performanta pe competitie. Foarte folosit, cere indexuri bune. Prioritate: `P2`.
- `ScorerEntry` — DTO simplu pentru prezentare scorers. Prioritate: `P3`.
- `ScorerLeaderboardEntry` — entitate de agregare pentru leaderboards. Utila, dar sensibila la duplicari/inconsistente daca upstream-ul nu este corect. Prioritate: `P2`.
- `Scout` — model de scout, rezonabil si clar. Prioritate: `P3`.
- `ScoutAssignment` — model important pentru managementul scouting assignments. Prioritate: `P3`.
- `SeasonObjective` — model simplu pentru obiective de sezon. Prioritate: `P3`.
- `Shortlist` — model curat, user-centric, unul dintre exemplele mai bune de data ownership in proiect. Prioritate: `P3`.
- `Sponsorship` — model simplu pentru sponsori/oferte. Prioritate: `P3`.
- `Stadium` — model de stadion cu ceva logica helper. Boundary bun si usor de inteles. Prioritate: `P3`.
- `Suspension` — model clar si important pentru eligibilitate. Prioritate: `P3`.
- `Team` — una dintre entitatile cheie. Destul de lata, dar coerenta pentru finante, reputatie si infrastructura. Inca depinde masiv de scalar IDs si de calcule externe. Prioritate: `P2`.
- `TeamCompetitionDetail` — model de standings/statistici pe competitie. Important functional, risc mediu. Prioritate: `P2`.
- `TeamCompetitionRelation` — relatie simpla si aparent subutilizata. Prioritate: `P3`.
- `TeamDataHubStats` — DTO/read model pentru analytics. Simplu si util. Prioritate: `P3`.
- `TeamFacilities` — model clar pentru niveluri de infrastructura. Prioritate: `P3`.
- `TeamPlayerHistoricalRelation` — model pentru istoric jucator-echipa; util si simplu. Prioritate: `P3`.
- `TeamTransferStrategyRelation` — relatie mica, orientata pe AI transfer strategy. Prioritate: `P3`.
- `TrainingSchedule` — model simplu pentru schedule-ul de echipa. Prioritate: `P3`.
- `Transfer` — model final de transfer executat. Boundary bun si util. Prioritate: `P3`.
- `TransferOffer` — model cheie pentru negocieri, dar simplu structural. Prioritate: `P2`.
- `TransferStrategy` — lookup/model simplu pentru strategii. Prioritate: `P3`.
- `YouthPlayer` — model pentru academia de tineret. Boundary bun; necesita doar atentie la lifecycle rules. Prioritate: `P3`.

## Frontend DTOs (`frontend/`)

Observatie comuna: aceste clase sunt, in mare parte, view models curate. Ele nu sunt problema proiectului; dimpotriva, arata directia buna de a nu expune mereu entitatile brute. Riscul apare doar cand sunt folosite inconsecvent fata de entitatile JPA.

- `CalendarEntryView` — DTO pentru calendar. Simplu si util. Prioritate: `P3`.
- `FormationData` — DTO de formatie. Fara logica, risc mic. Prioritate: `P3`.
- `GoalAnimationData` — DTO mai bogat pentru animatia de gol. Bine separat de logica din `GoalAnimationService`. Prioritate: `P3`.
- `LiveMatchData` — DTO pentru live match payload. Clar si util. Prioritate: `P3`.
- `ManagerBestTeamTacticView` — view model mic pentru recomandari tactice. Prioritate: `P3`.
- `ManagerTeamTacticView` — DTO pentru tacticile managerului. Prioritate: `P3`.
- `MatchPreviewView` — model de prezentare pentru preview de meci. Prioritate: `P3`.
- `MatchSummaryView` — DTO de summary post-match. Boundary bun. Prioritate: `P3`.
- `MediaPredictionView` — view model foarte mic, fara riscuri speciale. Prioritate: `P3`.
- `PersonalizedTacticView` — DTO pentru tactici personalizate. Clar. Prioritate: `P3`.
- `PlayerCompetitionWinnerLeaderboardView` — DTO de leaderboard pentru castigatori. Prioritate: `P3`.
- `PlayerCompetitionWinnerView` — DTO simplu pentru castigatori. Prioritate: `P3`.
- `PlayerView` — unul dintre cele mai folosite DTO-uri de prezentare. Boundary foarte util; merita pastrat ca model separat de `Human`. Prioritate: `P2`.
- `ScheduleView` — DTO pentru program/schedule. Simplu si util. Prioritate: `P3`.
- `TacticView` — DTO foarte mic pentru tactici. Prioritate: `P3`.
- `TeamCompetitionView` — view model important pentru standings/competitie. Boundary bun. Prioritate: `P2`.
- `TeamMatchView` — DTO de meci pe echipa. Prioritate: `P3`.
- `Top3FinishersCompetitionView` — DTO mic pentru istoric top 3. Prioritate: `P3`.

## Transfer Market Strategies (`transfermarket/`)

Observatie comuna: aici se vede un pattern Strategy bun. Aceste clase sunt una dintre zonele mai elegante ale proiectului. Riscul principal este mai degraba lipsa testelor si faptul ca unele clase nu sunt integrate uniform cu restul serviciilor.

- `AbstractTransferStrategy` — baza abstracta pentru strategiile de transfer. Directie buna de design. Prioritate: `P3`.
- `AcademyTransferStrategy` — strategie orientata spre academy/youth. Clar separata, buna pentru extensie. Prioritate: `P3`.
- `BuyFreeSellHighTransferStrategy` — strategie AI cu intentie clara. Merita teste pe selectie si profitabilitate. Prioritate: `P3`.
- `BuyMidSellMidTransferStrategy` — strategie intermediara, utila pentru varietate AI. Prioritate: `P3`.
- `BuyPlanTransferView` — DTO simplu pentru plan de transfer. Prioritate: `P3`.
- `BuyTopSellWorstTransferStrategy` — strategie AI usor de inteles; risc mic, dar testarea ar ajuta. Prioritate: `P3`.
- `BuyYoungSellHighTransferStrategy` — una dintre strategiile cele mai "gamey", dar clar modelata. Prioritate: `P3`.
- `CompositeTransferStrategy` — compozitor de strategii, ceea ce este o decizie de design buna. Este una dintre clasele curate din acest pachet. Prioritate: `P2`.
- `PlayerTransferView` — view/helper mai bogat pentru candidati la transfer. Util, dar ar merita clarificat daca este DTO pur sau model de domeniu auxiliar. Prioritate: `P3`.
- `TransferPlayer` — DTO foarte mic. Prioritate: `P3`.
- `TransferStrategy` — interfata/baza pentru strategii. Simplu si corect. Prioritate: `P3`.
- `TransferStrategyUtil` — helper utilitar foarte mic. Prioritate: `P3`.

## Name Generators (`nameGenerator/`)

- `AbstractNameGeneratorStrategy` — baza abstracta mica si curata pentru generatoare. Prioritate: `P3`.
- `CompositeNameGenerator` — combinator de strategii de nume, una dintre implementările mai elegante din proiect. Boundary bun si extensibil. Prioritate: `P2`.
- `DongNameGenerator` — strategie concreta de nume; simpla. Prioritate: `P3`.
- `ElevenNameGenerator` — strategie concreta de nume; simpla. Prioritate: `P3`.
- `KessNameGenerator` — strategie concreta de nume; simpla. Prioritate: `P3`.
- `NameGenerator` — clasa veche/alternativa care pare mai putin moderna decat `CompositeNameGenerator`. Poate crea confuzie daca ambele raman active conceptual. Prioritate: `P3`.
- `NameGeneratorStrategy` — interfata mica si potrivita. Prioritate: `P3`.
- `NameGeneratorUtil` — helper minimal, risc foarte mic. Prioritate: `P3`.

## Utils

- `InPossession` — enum/utilitar simplu pentru logica de meci. Prioritate: `P3`.
- `Mentality` — enum/utilitar simplu, important semantic dar fara risc tehnic. Prioritate: `P3`.
- `PassingType` — enum/utilitar simplu. Prioritate: `P3`.
- `Tempo` — enum/utilitar simplu. Prioritate: `P3`.
- `TimeWasting` — enum/utilitar simplu. Prioritate: `P3`.
- `TypeNames` — colectie centrala de constante pentru tipuri de `Human`. Foarte folosita si utila, dar dependenta de long constants manuale o face fragila la drift fata de datele seed. Prioritate: `P2`.

## Concluzie practica

Daca ar trebui sa prioritizez refactorul dupa acest review clasa-cu-clasa, ordinea cea mai sanatoasa ar fi:

1. `WebSecurityConfig`, `AuthController`, `UserContext`, `UserDetailsImpl`, `AdminController`, `TransferOfferController`.
2. `GameAdvanceService`, `CompetitionController`, `GameController`, `FixtureSchedulingService`, `SeasonTransitionService`.
3. `TacticController`, `StatsController`, `ScoutManagementController`, `FinanceService`, `TrainingService`, `HumanService`.

Clasele simple din `frontend/`, `repository/`, `nameGenerator/`, `transfermarket/` si multe entitati JPA nu sunt "problema" proiectului; ele devin riscante doar pentru ca nucleul orchestration-ului si securitatii este inca instabil.
