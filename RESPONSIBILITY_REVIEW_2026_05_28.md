# Responsibility Review 2026-05-28

Legendă: `OK` = nu pare să ducă mai mult decât ar trebui; `La limită` = încă acceptabilă, dar începe să se lățească; `Prea mult` = are prea multe responsabilități; `Mare, dar coerentă` = volum mare, dar predominant în același subdomeniu.

Observație: pentru clasele mari și sensibile am citit codul direct; pentru clasele mici și repetitive verdictul este sprijinit și de semnale structurale precum mărimea, dependențele și rolul în pachet.

Starea curentă a proiectului: `mvn -q -DskipTests compile` trece pe worktree-ul actual.

Câștiguri față de review-ul anterior: `CompetitionDisplayService`, `SeasonTransitionService` și `MatchSimulationOrchestrator` arată că ai început să spargi zonele mari. Direcția e bună; problema este că ultimele două sunt încă prea grele și au preluat multă logică procedurală din clasele vechi.

## Root

- `Main.java`: **OK**. Bootstrap-ul aplicației este mic și focalizat.

## Algorithms

- `RoundRobin.java`: **OK**. Algoritmul este scurt și are o singură responsabilitate.

## Config

- `GlobalExceptionHandler.java`: **OK**. Handler mic și clar.
- `SecurityConfiguration.java`: **OK**. Configurație izolată.
- `SwaggerConfig.java`: **OK**. Configurație mică și dedicată documentării.
- `WebSecurityConfig.java`: **OK**. Din perspectiva responsabilității este încă focalizată pe securitate.

## Controllers

- `AdminController.java`: **Prea mult**. Amestecă endpoint-uri administrative, bootstrap operațional și mutații sensibile.
- `AssistantManagerController.java`: **OK**. Controller mic și focalizat.
- `CareerController.java`: **OK**. Are puține endpoint-uri și o responsabilitate clară.
- `CompetitionController.java`: **Prea mult**. Încă ține prea multă logică de sezon, bootstrap, cache și orchestration pe lângă layer-ul web.
- `ContractController.java`: **Prea mult**. Gestionează prea multe fluxuri contractuale și decizii de business direct în controller.
- `ControllerTest.java`: **OK**. Nu este supradimensionată, dar nu ar trebui să stea în `src/main` ca controller expus.
- `FriendlyController.java`: **OK**. Subțire și bine delimitat.
- `GameController.java`: **Prea mult**. Rămâne un controller-gateway pentru setup, stare de joc, save/load și multe agregări.
- `HistoryController.java`: **OK**. Read-only și relativ bine delimitat.
- `HumanController.java`: **La limită**. Nu este uriașă, dar construiește view-uri și face mapare care ar merita împinse într-un service sau adaptor.
- `InboxController.java`: **OK**. Acum e mic și mult mai bine focusat.
- `InjuryController.java`: **OK**. Responsabilitate destul de clară.
- `KafkaConfig.java`: **OK**. Este mică, dar pachetul nu e potrivit pentru ea.
- `LoanController.java`: **La limită**. Combină ofertă de împrumut, finanțe, inbox și ownership checks în același loc.
- `LoginController.java`: **OK**. Foarte mică.
- `ManagerController.java`: **La limită**. Profilul de manager și leaderboard-ul sunt apropiate, dar controllerul face și agregare serioasă.
- `MatchController.java`: **Prea mult**. Amestecă schedule, preview, summary, evenimente și sesiuni live.
- `MediaController.java`: **OK**. Endpoint unic, focus clar.
- `ScoutManagementController.java`: **Prea mult**. Acoperă generare, angajare, assignment-uri și efecte financiare într-o singură clasă.
- `ScoutingController.java`: **OK**. Mică și destul de clară.
- `SeasonObjectiveController.java`: **OK**. Responsabilitate foarte îngustă.
- `ShortlistController.java`: **OK**. Mic și coerent.
- `StaffController.java`: **OK**. Subțire și focusat.
- `StatsController.java`: **Prea mult**. Are prea multă agregare și transformare de date pentru un controller.
- `TacticController.java`: **Prea mult**. Face persistență, calcul de best XI, transformare JSON și logică tactică într-un singur loc.
- `TeamController.java`: **La limită**. Îmbină metadata de echipă, lot și finanțe; încă merge, dar începe să se lățească.
- `TrainingController.java`: **OK**. Rămâne suficient de mic.
- `TransferController.java`: **OK**. Foarte mic și specializat.
- `TransferOfferController.java`: **Prea mult**. Fluxurile de ofertă, counter, accept și execuție merită mutate mai mult în servicii.

## Frontend

- `CalendarEntryView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `FormationData.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `GoalAnimationData.java`: **OK**. DTO ceva mai mare, dar încă strict pentru payload-ul animațiilor.
- `LiveMatchData.java`: **OK**. DTO mare, dar coerent cu fluxul live.
- `ManagerBestTeamTacticView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `ManagerTeamTacticView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `MatchPreviewView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `MatchSummaryView.java`: **OK**. View model de sumar, corect dimensionat.
- `MediaPredictionView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `PersonalizedTacticView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `PlayerCompetitionWinnerLeaderboardView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `PlayerCompetitionWinnerView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `PlayerView.java`: **OK**. View model normal pentru UI.
- `ScheduleView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `TacticView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `TeamCompetitionView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `TeamMatchView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.
- `Top3FinishersCompetitionView.java`: **OK**. View sau DTO de frontend, cu responsabilitate strict de transport de date.

## Model

- `Award.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `BoardRequest.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `CalendarEvent.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `ClubCoefficient.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Competition.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `CompetitionEntry.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `CompetitionHistory.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `CompetitionTeamInfo.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `CompetitionTeamInfoDetail.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `CompetitionTeamInfoMatch.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `CompetitionType.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `FacilityUpgrade.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `FinancialRecord.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `FriendlyMatch.java`: **OK**. Entity ceva mai mare, dar încă un singur concept.
- `GameCalendar.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Human.java`: **Prea mult**. Entitatea concentrează câmpuri de jucător, manager și staff; este un model-polimorf prea încărcat.
- `HumanTeamRelation.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `HumanType.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Injury.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `JobOffer.java`: **OK**. Entity mai bogată, dar nu suprasolicitată ca responsabilitate.
- `Loan.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `ManagerHistory.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `ManagerInbox.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `MatchEvent.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `MatchSquad.java`: **OK**. DTO sau entity de componență; mare, dar coerent.
- `MatchStats.java`: **OK**. Este un DTO sau entity mare, dar coerent statistic.
- `NationalTeamCallup.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `PersonalizedTactic.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Player.java`: **La limită**. Ca mărime e ok, dar coexistă cu `Human` și lasă impresia unui model împărțit neclar.
- `PlayerInteraction.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `PlayerSkills.java`: **OK**. Este mare, dar reprezintă un singur concept de date.
- `PredeterminedScore.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `PressConference.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Round.java`: **La limită**. Mică, dar încă ține state global eterogen de sezon și setup.
- `Scorer.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `ScorerEntry.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `ScorerLeaderboardEntry.java`: **OK**. Model de leaderboard, mare dar linear.
- `Scout.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `ScoutAssignment.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `SeasonObjective.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Shortlist.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Sponsorship.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Stadium.java`: **OK**. Entity puțin mai bogată, dar încă în limite bune.
- `Suspension.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Team.java`: **OK**. Aggregate root normal pentru echipă.
- `TeamCompetitionDetail.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `TeamCompetitionRelation.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `TeamDataHubStats.java`: **OK**. DTO mai bogat, dar cu o singură destinație clară.
- `TeamFacilities.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `TeamPlayerHistoricalRelation.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `TeamTransferStrategyRelation.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `TrainingSchedule.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `Transfer.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `TransferOffer.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `TransferStrategy.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.
- `YouthPlayer.java`: **OK**. Entity sau DTO de domeniu cu scope în general corect.

## Name Generator

- `AbstractNameGeneratorStrategy.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.
- `CompositeNameGenerator.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.
- `DongNameGenerator.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.
- `ElevenNameGenerator.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.
- `KessNameGenerator.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.
- `NameGenerator.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.
- `NameGeneratorStrategy.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.
- `NameGeneratorUtil.java`: **OK**. Clasă mică și foarte specifică generatorului de nume.

## Repositories

- `AwardRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `BoardRequestRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `CalendarEventRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ClubCoefficientRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `CompetitionHistoryRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `CompetitionRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `CompetitionTeamInfoDetailRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `CompetitionTeamInfoMatchRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `CompetitionTeamInfoRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `FacilityUpgradeRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `FinancialRecordRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `FriendlyMatchRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `GameCalendarRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `HumanRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `InjuryRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `JobOfferRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `LoanRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ManagerHistoryRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ManagerInboxRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `MatchEventRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `MatchStatsRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `NationalTeamCallupRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `PersonalizedTacticRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `PlayerInteractionRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `PlayerSkillsRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `PredeterminedScoreRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `PressConferenceRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `RoundRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ScorerLeaderboardRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ScorerRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ScoutAssignmentRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ScoutRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `SeasonObjectiveRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `ShortlistRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `SponsorshipRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `StadiumRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `SuspensionRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TeamCompetitionDetailRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TeamCompetitionRelationRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TeamFacilitiesRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TeamPlayerHistoricalRelationRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TeamRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TrainingScheduleRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TransferOfferRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `TransferRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.
- `YouthPlayerRepository.java`: **OK**. Repository standard, cu responsabilitate de acces la date.

## Services

- `AssistantManagerService.java`: **La limită**. Are o temă unitară, dar a adunat mai multe tipuri de recomandări și logică auxiliară.
- `AwardService.java`: **La limită**. Este încă rezonabilă, dar începe să concentreze prea multe reguli de premiere.
- `BoardRequestService.java`: **OK**. Focus bun pe un singur subdomeniu.
- `BootstrapService.java`: **Mare, dar coerentă**. Este mare pentru că bootstrap-ul jocului e mare; tema rămâne totuși unitară.
- `CalendarService.java`: **OK**. Responsabilitate clară.
- `CompetitionDisplayService.java`: **Mare, dar coerentă**. Extragere bună din controller; încă e mare, dar e omogenă și read-only.
- `CompetitionService.java`: **OK**. Are scop îngust în jurul generării de skill-uri și logicii asociate.
- `CupBracketService.java`: **Mare, dar coerentă**. Brackets și afișarea lor țin de același subdomeniu.
- `EuropeanCompetitionService.java`: **Mare, dar coerentă**. Este foarte mare, dar cea mai mare parte a codului rămâne în același domeniu european.
- `FacilityUpgradeService.java`: **La limită**. Încă suportă mai multe fluxuri de upgrade și evaluare într-o singură clasă.
- `FinanceService.java`: **La limită**. Îmbină tranzacții, rapoarte și reguli de buget; începe să devină un hub financiar.
- `FixtureSchedulingService.java`: **Mare, dar coerentă**. E mare, dar aproape totul ține de calendar și programare de meciuri.
- `FriendlyMatchService.java`: **La limită**. Subdomeniul e clar, dar clasa acoperă prea multe etape ale fluxului de amicale.
- `GameAdvanceService.java`: **Prea mult**. Rămâne unul dintre orchestratorii centrali ai jocului și ține prea multe sub-fluxuri.
- `GoalAnimationService.java`: **Mare, dar coerentă**. Subsistem foarte mare, dar încă relativ unitar ca responsabilitate.
- `HumanService.java`: **Prea mult**. Îmbină training, spawning de manageri și alte operații asupra `Human`.
- `JobOfferService.java`: **La limită**. Are o temă clară, dar adună generare, evaluare și side-effects în aceeași clasă.
- `KafkaService.java`: **OK**. Foarte mic și clar.
- `LeagueConfigService.java`: **OK**. Mic și focalizat.
- `LineupRatingService.java`: **La limită**. Rating-ul de lineup e unitar, dar clasa a crescut spre un mini-motor tactic.
- `LiveMatchSession.java`: **Mare, dar coerentă**. Este motorul unei sesiuni live; mare, dar mult mai coerent decât un controller-god.
- `LiveMatchSimulationService.java`: **La limită**. Gestionează sesiuni, chei, snapshot-uri și integrare cu motorul live; merită o nouă împărțire.
- `MatchService.java`: **OK**. Destul de bine delimitat.
- `MatchSimulationOrchestrator.java`: **Prea mult**. A preluat mult din `CompetitionController`, dar este încă un orchestrator prea greu.
- `MatchSimulationService.java`: **Prea mult**. Conține scoring, availability, rezultate, reputație și alte responsabilități conexe dar distincte.
- `NationalTeamService.java`: **OK**. Responsabilitate destul de clară.
- `PlayerInstructionService.java`: **OK**. Scop îngust.
- `PlayerRoleService.java`: **La limită**. Rolurile sunt un subdomeniu coerent, dar logica a crescut mult.
- `PlayerSkillsService.java`: **OK**. Se ocupă clar de modelul de skill-uri.
- `PressConferenceService.java`: **OK**. Dimensiune și focus bune.
- `RoundService.java`: **OK**. Minimală.
- `SeasonObjectiveService.java`: **OK**. Încă rezonabilă ca dimensiune și scope.
- `SeasonTransitionService.java`: **Prea mult**. Extragere utilă, dar a devenit noul container pentru foarte multe responsabilități de sezon.
- `SponsorshipService.java`: **OK**. Subdomeniu clar și dimensiune acceptabilă.
- `SquadGenerationService.java`: **OK**. Focus bun.
- `StaffService.java`: **La limită**. A început să concentreze generare, evaluare și management de staff.
- `StatsService.java`: **OK**. Service mic și util.
- `SuspensionService.java`: **La limită**. Responsabilitatea e unitară, dar logica procedurală a crescut mult.
- `TacticService.java`: **OK**. Separare bună pentru utilitare tactice.
- `TeamKitResolver.java`: **OK**. Clar și specializat.
- `TeamPostMatchService.java`: **La limită**. Extragere bună, dar încă atinge standings, morale, inbox și score overrides.
- `TeamService.java`: **OK**. Mică și focusată.
- `TeamTalkService.java`: **La limită**. Subdomeniu clar, dar a crescut spre un pachet mare de reguli conversaționale.
- `TrainingService.java`: **La limită**. Încă îmbină salvarea programului, efecte și calcul de antrenament.
- `TransferMarketService.java`: **OK**. Destul de bine delimitat.
- `TransferValueCalculator.java`: **OK**. Utilitar clar.
- `YouthAcademyService.java`: **OK**. A rămas într-un scope acceptabil.

## Transfer Market

- `AbstractTransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `AcademyTransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `BuyFreeSellHighTransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `BuyMidSellMidTransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `BuyPlanTransferView.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `BuyTopSellWorstTransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `BuyYoungSellHighTransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `CompositeTransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `PlayerTransferView.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `TransferPlayer.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `TransferStrategy.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.
- `TransferStrategyUtil.java`: **OK**. Strategie sau view mică și coerentă în subdomeniul transferurilor.

## User

- `AuthController.java`: **OK**. Ca responsabilitate este îngustă, chiar dacă modelul de auth merită în continuare hardening.
- `CurrentUserService.java`: **OK**. Bine focusat pe userul curent.
- `TeamAccessGuard.java`: **OK**. Responsabilitate clară de ownership și access.
- `User.java`: **OK**. Entity simplă.
- `UserContext.java`: **La limită**. A rămas un shim hibrid între request-context și director global de echipe umane.
- `UserDetailsImpl.java`: **OK**. Wrapper mic pentru Spring Security.
- `UserDetailsServiceImpl.java`: **OK**. Mic și specializat.
- `UserDto.java`: **OK**. DTO simplu.
- `UserRegistrationController.java`: **OK**. Responsabilitate îngustă.
- `UserRepository.java`: **OK**. Repository standard.
- `UserService.java`: **OK**. Mic și destul de clar.

## Util

- `InPossession.java`: **OK**. Utilitar sau enum foarte focalizat.
- `Mentality.java`: **OK**. Utilitar sau enum foarte focalizat.
- `PassingType.java`: **OK**. Utilitar sau enum foarte focalizat.
- `Tempo.java`: **OK**. Utilitar sau enum foarte focalizat.
- `TimeWasting.java`: **OK**. Utilitar sau enum foarte focalizat.
- `TypeNames.java`: **OK**. Utilitar sau enum foarte focalizat.

## Rezumat

- Clasele care încă duc clar prea mult: `CompetitionController`, `GameController`, `MatchController`, `StatsController`, `TacticController`, `TransferOfferController`, `AdminController`, `ScoutManagementController`, `ContractController`, `GameAdvanceService`, `MatchSimulationOrchestrator`, `MatchSimulationService`, `SeasonTransitionService`, `HumanService`, `Human`.
- Clase mari, dar într-o zonă mai sănătoasă decât înainte: `CompetitionDisplayService`, `EuropeanCompetitionService`, `GoalAnimationService`, `LiveMatchSession`, `FixtureSchedulingService`, `CupBracketService`, `BootstrapService`.
- Dacă aș prioritiza următoarea rundă de refactor, aș merge în ordinea: `GameController` -> `MatchController` -> `MatchSimulationService` -> `SeasonTransitionService` -> `Human` și model split.
