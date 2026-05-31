package com.footballmanagergamesimulator.integration;

import com.footballmanagergamesimulator.config.MatchEngineConfig;
import com.footballmanagergamesimulator.model.ClubShareholding;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.FinanceService;
import com.footballmanagergamesimulator.service.OwnershipService;
import com.footballmanagergamesimulator.service.ShareMarketService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boardroom Faza 1-2: buying shares spends wealth and raises the stake; crossing
 * the ownership threshold makes {@code isOwner} true; an owner can invest wealth
 * into the club (raising club finances) and withdraw it back (the reverse).
 * Self-contained — creates its own Human + Team rows in the bootstrapped DB.
 */
@SpringBootTest
@DisplayName("Boardroom — shares, ownership threshold, invest/withdraw")
class BoardroomServiceIT {

    @Autowired private ShareMarketService shareMarketService;
    @Autowired private OwnershipService ownershipService;
    @Autowired private FinanceService financeService;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private MatchEngineConfig engineConfig;

    private long humanId;
    private long teamId;

    @BeforeEach
    void setUp() {
        Team team = new Team();
        team.setName("Boardroom Test FC");
        team.setTotalFinances(10_000_000L);
        team.setReputation(1000);
        team.setBoardConfidence(50);
        team = teamRepository.save(team);
        teamId = team.getId();

        Human human = new Human();
        human.setName("Test Tycoon");
        human.setTypeId(TypeNames.MANAGER_TYPE);
        human.setWealth(1_000_000_000L);
        human = humanRepository.save(human);
        humanId = human.getId();
    }

    @Test
    void buyingShares_spendsWealthAndRaisesStake() {
        long wealthBefore = humanRepository.findById(humanId).orElseThrow().getWealth();
        long cost = shareMarketService.quoteCost(teamId, 10.0);

        ClubShareholding holding = shareMarketService.buyShares(humanId, teamId, 10.0);

        assertThat(holding.getPercent()).isEqualTo(10.0);
        long wealthAfter = humanRepository.findById(humanId).orElseThrow().getWealth();
        assertThat(wealthAfter).isEqualTo(wealthBefore - cost);
        assertThat(ownershipService.isOwner(humanId, teamId)).isFalse();
    }

    @Test
    void crossingThreshold_makesIsOwnerTrue() {
        double threshold = engineConfig.getBoardroom().getOwnershipThresholdPercent();

        shareMarketService.buyShares(humanId, teamId, threshold + 1.0);

        assertThat(ownershipService.isOwner(humanId, teamId)).isTrue();
        assertThat(ownershipService.ownedClubIds(humanId)).contains(teamId);
    }

    @Test
    void invest_movesMoneyFromWealthToClub_withdrawReverses() {
        shareMarketService.buyShares(humanId, teamId,
                engineConfig.getBoardroom().getOwnershipThresholdPercent() + 1.0);
        assertThat(ownershipService.isOwner(humanId, teamId)).isTrue();

        Round round = roundRepository.findById(1L).orElse(new Round());
        int season = (int) round.getSeason();
        int day = (int) round.getRound();

        long wealthBefore = humanRepository.findById(humanId).orElseThrow().getWealth();
        long clubBefore = teamRepository.findById(teamId).orElseThrow().getTotalFinances();
        long amount = 5_000_000L;

        // Invest: wealth down, club finances up.
        Human h = humanRepository.findById(humanId).orElseThrow();
        h.setWealth(h.getWealth() - amount);
        humanRepository.save(h);
        financeService.recordTransaction(teamId, season, day, "OWNER_INJECTION", "test invest", amount);

        assertThat(humanRepository.findById(humanId).orElseThrow().getWealth()).isEqualTo(wealthBefore - amount);
        assertThat(teamRepository.findById(teamId).orElseThrow().getTotalFinances()).isEqualTo(clubBefore + amount);

        // Withdraw: club finances down, wealth up (back to start).
        long clubMid = teamRepository.findById(teamId).orElseThrow().getTotalFinances();
        financeService.recordExpense(teamId, season, day, "OWNER_INJECTION", "test withdraw", amount);
        Human h2 = humanRepository.findById(humanId).orElseThrow();
        h2.setWealth(h2.getWealth() + amount);
        humanRepository.save(h2);

        assertThat(teamRepository.findById(teamId).orElseThrow().getTotalFinances()).isEqualTo(clubMid - amount);
        assertThat(humanRepository.findById(humanId).orElseThrow().getWealth()).isEqualTo(wealthBefore);
    }

    @Test
    void sellingShares_creditsWealthAndLowersStake() {
        shareMarketService.buyShares(humanId, teamId, 20.0);
        long wealthAfterBuy = humanRepository.findById(humanId).orElseThrow().getWealth();
        long proceeds = shareMarketService.quoteCost(teamId, 5.0);

        ClubShareholding holding = shareMarketService.sellShares(humanId, teamId, 5.0);

        assertThat(holding.getPercent()).isEqualTo(15.0);
        assertThat(humanRepository.findById(humanId).orElseThrow().getWealth())
                .isEqualTo(wealthAfterBuy + proceeds);
    }
}
