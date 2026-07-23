package com.footballmanagergamesimulator.economy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.FinancialRecord;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.ControlType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileRepository;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.FinancialRecordRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.CareerOnboardingService;
import com.footballmanagergamesimulator.user.ManagerSetupRequest;
import com.footballmanagergamesimulator.user.RegisterRequest;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.user.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regent-phase1-economy;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=update",
        "simulation.matchday.parallel.enabled=false",
        "regent.enabled=true"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RegentPhase1EconomyIT {

    @Autowired private UserService userService;
    @Autowired private PersonProfileService profileService;
    @Autowired private PersonProfileRepository profileRepository;
    @Autowired private PersonalAccountRepository accountRepository;
    @Autowired private PersonalLedgerEntryRepository ledgerRepository;
    @Autowired private AssetCatalogItemRepository catalogRepository;
    @Autowired private OwnedAssetRepository ownedAssetRepository;
    @Autowired private PersonalAccountingService accountingService;
    @Autowired private CareerOnboardingService onboardingService;
    @Autowired private PersonalAssetService assetService;
    @Autowired private PersonalPayrollService payrollService;
    @Autowired private WealthQueryService wealthQueryService;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private FinancialRecordRepository financialRecordRepository;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void managerSetupMergesAiEconomyIntoStableUserProfile() {
        Team team = new Team();
        team.setName("Identity Merge FC");
        team = teamRepository.saveAndFlush(team);

        Human aiManager = person("Existing AI Manager", 4, team.getId());
        aiManager.setWealth(1_000_000L);
        aiManager.setCareerEarnings(200_000L);
        aiManager = humanRepository.saveAndFlush(aiManager);
        PersonProfile aiProfile = profileService.ensureForHuman(aiManager);
        PersonalAccount aiAccount = accountingService.ensureAccount(aiProfile);
        AssetCatalogItem apartment = catalogRepository.findAll().stream()
                .filter(item -> item.getCode().equals("APARTMENT_1_ROOM")).findFirst().orElseThrow();
        assetService.purchase(aiProfile, apartment.getId(), "AI-MANAGER-ASSET");

        User user = userService.register(new RegisterRequest("identity-manager", "identity-manager@example.com",
                "correct-password", "Identity Manager", CareerRole.MANAGER));
        PersonProfile registeredProfile = profileService.requireForUser(user);
        long stableProfileId = registeredProfile.getId();

        onboardingService.setupManager(user,
                new ManagerSetupRequest("Human Manager", 41, team.getId(), false));

        PersonProfile claimed = profileService.requireForUser(user);
        assertThat(claimed.getId()).isEqualTo(stableProfileId);
        assertThat(claimed.getHumanId()).isEqualTo(aiManager.getId());
        assertThat(profileRepository.findById(aiProfile.getId())).isEmpty();
        assertThat(accountRepository.findById(aiAccount.getId())).isEmpty();

        PersonalAccount merged = accountRepository.findByProfileId(stableProfileId).orElseThrow();
        assertThat(merged.getOwnerUserId()).isEqualTo(user.getId());
        assertThat(merged.getOwnerHumanId()).isEqualTo(aiManager.getId());
        assertThat(merged.getCashBalance()).isEqualTo(850_000L);
        assertThat(merged.getLifetimeCareerEarnings()).isEqualTo(200_000L);
        assertThat(accountRepository.findByOwnerHumanId(aiManager.getId())).get()
                .extracting(PersonalAccount::getId).isEqualTo(merged.getId());
        assertThat(ownedAssetRepository.findAllByAccountIdAndStatusOrderByIdAsc(
                merged.getId(), OwnedAssetStatus.OWNED))
                .singleElement()
                .satisfies(asset -> assertThat(asset.getProfileId()).isEqualTo(stableProfileId));
        accountingService.assertReconciled(merged.getId());
    }

    @Test
    void registrationAndLedgerAreExactAndIdempotencyRejectsPayloadReuse() {
        User user = registerChairman("ledger-chair", 1_000_000L);
        PersonProfile profile = profileService.requireForUser(user);
        PersonalAccount account = accountRepository.findByProfileId(profile.getId()).orElseThrow();

        assertThat(account.getCashBalance()).isEqualTo(1_000_000L);
        assertThat(account.getLifetimeCareerEarnings()).isZero();
        accountingService.assertReconciled(account.getId());

        var first = accountingService.post(profile.getId(), LedgerEntryType.ADMIN_ADJUSTMENT,
                25_000L, 0, 1, 2, "test-correlation", "test-idempotency",
                null, null, "Test credit");
        var replay = accountingService.post(profile.getId(), LedgerEntryType.ADMIN_ADJUSTMENT,
                25_000L, 0, 1, 2, "test-correlation", "test-idempotency",
                null, null, "Test credit");
        assertThat(first.replayed()).isFalse();
        assertThat(replay.replayed()).isTrue();
        assertThat(accountRepository.findByProfileId(profile.getId()).orElseThrow().getCashBalance())
                .isEqualTo(1_025_000L);
        assertThat(ledgerRepository.findAllByAccountId(account.getId(),
                org.springframework.data.domain.Pageable.unpaged()).getTotalElements()).isEqualTo(2);

        assertThatThrownBy(() -> accountingService.post(profile.getId(), LedgerEntryType.ADMIN_ADJUSTMENT,
                30_000L, 0, 1, 2, "test-correlation", "test-idempotency",
                null, null, "Changed payload"))
                .isInstanceOf(EconomyConflictException.class)
                .hasMessageContaining("different operation");
        accountingService.assertReconciled(account.getId());
    }

    @Test
    void concurrentPurchasesCannotDoubleSpendAndRetryCannotChangeCatalogItem() throws Exception {
        User user = registerChairman("concurrent-chair", 1_000_000L);
        PersonProfile profile = profileService.requireForUser(user);
        AssetCatalogItem luxuryCar = catalogRepository.findAll().stream()
                .filter(item -> item.getCode().equals("CAR_LUXURY")).findFirst().orElseThrow();
        AssetCatalogItem apartment = catalogRepository.findAll().stream()
                .filter(item -> item.getCode().equals("APARTMENT_1_ROOM")).findFirst().orElseThrow();

        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> first = pool.submit(() -> purchaseAfter(start, profile, luxuryCar.getId(), "race-a"));
            Future<Boolean> second = pool.submit(() -> purchaseAfter(start, profile, luxuryCar.getId(), "race-b"));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }

        PersonalAccount account = accountRepository.findByProfileId(profile.getId()).orElseThrow();
        assertThat(account.getCashBalance()).isEqualTo(250_000L);
        assertThat(ownedAssetRepository.findAllByAccountIdAndStatusOrderByIdAsc(
                account.getId(), OwnedAssetStatus.OWNED)).hasSize(1);
        accountingService.assertReconciled(account.getId());

        OwnedAsset purchased = ownedAssetRepository.findAllByAccountIdAndStatusOrderByIdAsc(
                account.getId(), OwnedAssetStatus.OWNED).get(0);
        String winningKey = purchased.getPurchaseIdempotencyKey();
        PersonalAssetService.AssetMutation replay = assetService.purchase(profile, luxuryCar.getId(), winningKey);
        assertThat(replay.replayed()).isTrue();
        assertThatThrownBy(() -> assetService.purchase(profile, apartment.getId(), winningKey))
                .isInstanceOf(EconomyConflictException.class)
                .hasMessageContaining("different asset");
    }

    @Test
    void payrollAndSigningBonusDebitClubAndCreditCareerIncomeExactlyOnce() {
        Team team = new Team();
        team.setName("Phase One FC");
        team.setTotalFinances(10_000_000L);
        team.setReputation(1_000);
        team = teamRepository.saveAndFlush(team);
        Human player = person("Phase Player", 1, team.getId());
        player.setWage(2_000L);
        player = humanRepository.saveAndFlush(player);
        Human manager = person("Phase Manager", 4, team.getId());
        manager.setSalary(3_000L);
        manager = humanRepository.saveAndFlush(manager);

        assertThat(payrollService.processTeam(team, 4, 30)).isEqualTo(5_000L);
        player.setWage(7_000L);
        humanRepository.saveAndFlush(player);
        assertThat(payrollService.processTeam(team, 4, 30)).isEqualTo(5_000L);
        payrollService.payCareerBonus(team, manager.getId(), 9_000L, 4, 30,
                "SIGNING:1", "Signing bonus");
        Team payingTeam = team;
        Human alternateRecipient = player;
        assertThatThrownBy(() -> payrollService.payCareerBonus(
                payingTeam, alternateRecipient.getId(), 9_000L, 4, 30,
                "SIGNING:1", "Signing bonus"))
                .isInstanceOf(EconomyConflictException.class)
                .hasMessageContaining("different payment");
        payrollService.payCareerBonus(team, manager.getId(), 9_000L, 4, 30,
                "SIGNING:1", "Signing bonus");

        assertThat(teamRepository.findById(team.getId()).orElseThrow().getTotalFinances())
                .isEqualTo(9_986_000L);
        List<FinancialRecord> clubEntries = financialRecordRepository
                .findAllByTeamIdAndSeasonNumber(team.getId(), 4);
        assertThat(clubEntries).filteredOn(entry -> entry.getCategory().equals("WAGES")).hasSize(1);
        assertThat(clubEntries).filteredOn(entry -> entry.getCategory().equals("BONUSES")).hasSize(1);

        PersonalAccount playerAccount = accountRepository.findByOwnerHumanId(player.getId()).orElseThrow();
        PersonalAccount managerAccount = accountRepository.findByOwnerHumanId(manager.getId()).orElseThrow();
        assertThat(playerAccount.getCashBalance()).isEqualTo(2_000L);
        assertThat(playerAccount.getLifetimeCareerEarnings()).isEqualTo(2_000L);
        assertThat(managerAccount.getCashBalance()).isEqualTo(12_000L);
        assertThat(managerAccount.getLifetimeCareerEarnings()).isEqualTo(12_000L);
        accountingService.assertReconciled(playerAccount.getId());
        accountingService.assertReconciled(managerAccount.getId());
    }

    @Test
    void rankingsHaveOneExactRowPerPersonAcrossRoleAndControlFilters() {
        registerChairman("ranking-chair", 2_000_000L);
        User manager = userService.register(new RegisterRequest("ranking-manager", "ranking-manager@example.com",
                "correct-password", "Ranking Manager", CareerRole.MANAGER));
        Human ai = person("Ranking AI Player", 1, null);
        ai.setWealth(333_000L);
        ai = humanRepository.saveAndFlush(ai);
        PersonProfile aiProfile = profileService.ensureForHuman(ai);
        accountingService.ensureAccount(aiProfile);

        EconomyDtos.RankingPage all = wealthQueryService.rankings("ALL", "ALL", "NET_WORTH", 0, 100);
        Set<Long> unique = all.content().stream().map(row -> row.profile().profileId()).collect(Collectors.toSet());
        assertThat(unique).hasSize(all.content().size());
        all.content().forEach(row -> assertThat(row.profile().wealth().netWorth().amount()).isEqualTo(
                row.profile().wealth().cash().amount()
                        + row.profile().wealth().assetValue().amount()
                        + row.profile().wealth().investmentValue().amount()
                        + row.profile().wealth().clubEquityValue().amount()));
        assertThat(wealthQueryService.rankings("CHAIRMEN", "USER", "CASH", 0, 100).content())
                .allMatch(row -> row.profile().careerType() == CareerType.CHAIRMAN
                        && row.profile().controlType() == ControlType.USER);
        assertThat(wealthQueryService.rankings("MANAGERS", "USER", "CAREER_EARNINGS", 0, 100).content())
                .anyMatch(row -> row.profile().profileId() == profileService.requireForUser(manager).getId());
        assertThat(wealthQueryService.rankings("PLAYERS", "AI", "NET_WORTH", 0, 100).content())
                .anyMatch(row -> row.profile().profileId() == aiProfile.getId());
    }

    @Test
    void apiDerivesOwnerAndPriceFromServerAndKeepsOtherUsersAssetsPrivate() throws Exception {
        registerChairman("api-chair-a", 1_000_000L);
        registerChairman("api-chair-b", 1_000_000L);
        MockHttpSession firstSession = login("api-chair-a");
        MockHttpSession secondSession = login("api-chair-b");
        long catalogId = catalogRepository.findAll().stream()
                .filter(item -> item.getCode().equals("APARTMENT_1_ROOM")).findFirst().orElseThrow().getId();

        MvcResult purchase = mockMvc.perform(post("/api/me/assets/purchases")
                        .session(firstSession).with(csrf()).contentType("application/json")
                        .content("{\"catalogItemId\":" + catalogId
                                + ",\"idempotencyKey\":\"api-price-proof\",\"price\":1,\"ownerProfileId\":999999}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asset.purchasePrice.amount").value(150000))
                .andExpect(jsonPath("$.account.cash.amount").value(850000))
                .andReturn();
        long ownedAssetId = objectMapper.readTree(purchase.getResponse().getContentAsString())
                .path("asset").path("id").asLong();

        mockMvc.perform(post("/api/me/assets/" + ownedAssetId + "/sell")
                        .session(secondSession).with(csrf()).contentType("application/json")
                        .content("{\"idempotencyKey\":\"not-owner\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/me/wealth").session(firstSession).header("X-User-Id", "999999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cash.amount").value(850000));
    }

    private boolean purchaseAfter(CountDownLatch start, PersonProfile profile, long itemId, String key)
            throws InterruptedException {
        start.await();
        try {
            assetService.purchase(profile, itemId, key);
            return true;
        } catch (EconomyConflictException exception) {
            assertThat(exception.getCode()).isEqualTo("INSUFFICIENT_FUNDS");
            return false;
        }
    }

    private User registerChairman(String username, long wealth) {
        return userService.register(new RegisterRequest(username, username + "@example.com",
                "correct-password", username, CareerRole.CHAIRMAN, wealth));
    }

    private Human person(String name, long typeId, Long teamId) {
        Human human = new Human();
        human.setName(name);
        human.setTypeId(typeId);
        human.setTeamId(teamId);
        human.setPosition(typeId == 1 ? "ST" : "Manager");
        return human;
    }

    private MockHttpSession login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login").with(csrf())
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\",\"password\":\"correct-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regentEnabled").value(true))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
