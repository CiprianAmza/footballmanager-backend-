package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.util.List;

@Service
public class ClubQueryService {
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final PersonalAccountRepository accountRepository;
    private final ClubCapTableService capTableService;
    private final ClubValuationService valuationService;
    private final ClubFinancialPolicyService policyService;
    private final TakeoverQuoteRepository quoteRepository;
    private final RegentEconomyProperties properties;

    public ClubQueryService(TeamRepository teamRepository,
                            CompetitionRepository competitionRepository,
                            PersonalAccountRepository accountRepository,
                            ClubCapTableService capTableService,
                            ClubValuationService valuationService,
                            ClubFinancialPolicyService policyService,
                            TakeoverQuoteRepository quoteRepository,
                            RegentEconomyProperties properties) {
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
        this.accountRepository = accountRepository;
        this.capTableService = capTableService;
        this.valuationService = valuationService;
        this.policyService = policyService;
        this.quoteRepository = quoteRepository;
        this.properties = properties;
    }

    @Transactional
    public List<ClubDtos.ClubSummary> clubs(long principalProfileId) {
        return clubs(ClubCatalogScope.ALL, principalProfileId);
    }

    @Transactional
    public List<ClubDtos.ClubSummary> clubs(ClubCatalogScope scope, long principalProfileId) {
        capTableService.ensureAllMigrated();
        Long principalAccount = accountRepository.findByProfileId(principalProfileId)
                .map(PersonalAccount::getId).orElse(null);
        java.util.Map<Long, String> competitionNames = competitionRepository.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(Competition::getId, Competition::getName));
        return teamRepository.findAll().stream().sorted(java.util.Comparator.comparingLong(Team::getId))
                .map(team -> {
                    ClubCapTableService.CapTable cap = capTableService.view(team.getId());
                    ClubCapTableService.Holding controller = controller(cap);
                    ClubCapTableService.Holding principal = principal(cap, principalAccount);
                    long principalShares = principal == null ? 0 : principal.quantity();
                    boolean held = principalShares > 0;
                    boolean controlled = principalAccount != null
                            && principalAccount.equals(cap.controllingAccountId());
                    if (scope == ClubCatalogScope.HELD && !held
                            || scope == ClubCatalogScope.CONTROLLED && !controlled) return null;
                    ClubValuationService.Valuation valuation = valuationService.value(team.getId());
                    return new ClubDtos.ClubSummary(team.getId(), team.getName(), team.getCompetitionId(),
                            competitionNames.get(team.getCompetitionId()), money(valuation.totalValue()),
                            controller == null ? null : controller.profileId(),
                            controller == null ? null : controller.displayName(),
                            principalShares, stakeBps(principalShares, cap.issuedShares()),
                            money(valuationService.equityValue(valuation, principalShares, cap.issuedShares())),
                            held, controlled);
                }).filter(java.util.Objects::nonNull).toList();
    }

    @Transactional
    public ClubDtos.Dashboard dashboard(long teamId, PersonProfile principal) {
        if (principal.getCareerType() != CareerType.CHAIRMAN) {
            throw new EconomyConflictException("CHAIRMAN_REQUIRED", "A chairman career is required");
        }
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new EconomyConflictException("CLUB_NOT_FOUND", "Club was not found"));
        ClubCapTableService.CapTable cap = capTableService.ensureMigrated(teamId);
        Long principalAccount = accountRepository.findByProfileId(principal.getId())
                .map(PersonalAccount::getId).orElse(null);
        if (principalAccount == null || !principalAccount.equals(cap.controllingAccountId())) {
            throw new EconomyConflictException("CLUB_CONTROL_REQUIRED",
                    "Authenticated chairman does not control this club");
        }
        ClubValuationService.Valuation valuation = valuationService.value(teamId);
        ClubFinancialPolicyService.Policy policy = policyService.policy(team);
        return new ClubDtos.Dashboard(teamId, team.getName(), valuation(valuation), capTable(cap, valuation),
                treasury(policy), true);
    }

    @Transactional
    public ClubDtos.CapTableView ownership(long teamId) {
        ClubCapTableService.CapTable cap = capTableService.ensureMigrated(teamId);
        return capTable(cap, valuationService.value(teamId));
    }

    public ClubDtos.TakeoverQuoteView quote(TakeoverService.QuoteResult result) {
        TakeoverQuote value = result.quote();
        return new ClubDtos.TakeoverQuoteView(value.getQuoteKey(), value.getTeamId(), value.getSharesToAcquire(),
                money(value.getUnitPrice()), value.getPremiumBps(), money(value.getTotalConsideration()),
                value.getValuationFormulaVersion(), value.getValuationStateVersion(), value.getInstrumentVersion(),
                value.getExpiresAbsoluteDay(), value.getStatus(), result.replayed());
    }

    public ClubDtos.TakeoverExecutionView execution(TakeoverService.ExecutionResult result) {
        TakeoverExecution value = result.execution();
        TakeoverQuote quote = quoteRepository.findById(value.getQuoteId()).orElseThrow();
        return new ClubDtos.TakeoverExecutionView(value.getExecutionKey(), quote.getQuoteKey(), value.getTeamId(),
                value.getSharesAcquired(), money(value.getUnitPrice()), money(value.getTotalConsideration()),
                money(value.getCashBalanceAfter()), value.getQuantityAfter(), value.getSeasonNumber(),
                value.getGameDay(), result.replayed());
    }

    public ClubDtos.TreasuryTransferView transfer(ClubTreasuryService.TransferResult result) {
        ClubCashTransfer value = result.transfer();
        return new ClubDtos.TreasuryTransferView(value.getTransferKey(), value.getTeamId(), value.getDirection(),
                money(value.getAmount()), money(value.getPersonalBalanceAfter()), money(value.getClubBalanceAfter()),
                money(value.getDistributableBefore()), value.getCorrelationId(), value.getSeasonNumber(),
                value.getGameDay(), result.replayed());
    }

    private ClubDtos.ValuationView valuation(ClubValuationService.Valuation value) {
        return new ClubDtos.ValuationView(value.formulaVersion(), value.stateVersion(),
                money(value.squadMarketValue()), money(value.clubCash()), money(value.debt()),
                money(value.dueObligations()), money(value.netCash()), money(value.stadiumFacilitiesValue()),
                money(value.reputationBrandValue()), value.recentPerformanceBps(),
                money(value.recentPerformanceValue()), money(value.totalValue()));
    }

    private ClubDtos.CapTableView capTable(ClubCapTableService.CapTable value,
                                           ClubValuationService.Valuation valuation) {
        ClubCapTableService.Holding controller = controller(value);
        List<ClubDtos.HoldingView> holdings = value.holdings().stream().map(holding ->
                new ClubDtos.HoldingView(holding.profileId(), holding.displayName(), holding.protectedUser(),
                        holding.quantity(), stakeBps(holding.quantity(), value.issuedShares()),
                        money(valuationService.equityValue(valuation, holding.quantity(), value.issuedShares())),
                        holding.controlling())).toList();
        return new ClubDtos.CapTableView(value.issuedShares(), value.freeFloat(), value.controlThresholdBps(),
                controller == null ? null : controller.profileId(),
                controller == null ? null : controller.displayName(), value.version(), holdings);
    }

    private ClubDtos.TreasuryView treasury(ClubFinancialPolicyService.Policy value) {
        return new ClubDtos.TreasuryView(money(value.treasuryBalance()), money(value.debt()),
                money(value.monthlyWages()), money(value.protectedReserve()), money(value.dueObligations()),
                money(value.distributableCash()), value.withdrawalRestricted());
    }

    private static ClubCapTableService.Holding controller(ClubCapTableService.CapTable value) {
        return value.holdings().stream().filter(ClubCapTableService.Holding::controlling).findFirst().orElse(null);
    }

    private static ClubCapTableService.Holding principal(ClubCapTableService.CapTable value,
                                                          Long accountId) {
        if (accountId == null) return null;
        return value.holdings().stream().filter(holding -> holding.accountId() == accountId)
                .findFirst().orElse(null);
    }

    private static int stakeBps(long quantity, long supply) {
        if (quantity == 0) return 0;
        return BigInteger.valueOf(quantity).multiply(BigInteger.valueOf(10_000L))
                .divide(BigInteger.valueOf(supply)).intValueExact();
    }

    private EconomyDtos.Money money(long amount) {
        return new EconomyDtos.Money(amount, properties.getEconomy().getCurrency(), 0);
    }
}
