package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.AdminPlayerMovement;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.AdminPlayerMovementRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import com.footballmanagergamesimulator.user.UserContext;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Administrative transfer, free-agent and multi-season loan operations. */
@Service
public class AdminTransferService {

    public static final String PERMANENT = "PERMANENT";
    public static final String FREE_AGENT = "FREE_AGENT";
    public static final String LOAN = "LOAN";
    public static final String NOW = "NOW";
    public static final String START_OF_SEASON = "START_OF_SEASON";
    public static final String PENDING = "PENDING";
    public static final String COMPLETED = "COMPLETED";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    @Autowired private AdminPlayerMovementRepository movementRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private TransferRepository transferRepository;
    @Autowired private LoanRepository loanRepository;
    @Autowired private RoundRepository roundRepository;
    @Autowired private FinanceService financeService;
    @Autowired private MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired private ManagerInboxRepository managerInboxRepository;
    @Autowired private UserContext userContext;
    @Autowired private TransferOfferLifecycleService transferOfferLifecycleService;

    public record MovementCommand(
            String type,
            Long playerId,
            Long destinationTeamId,
            Long transferFee,
            Long wage,
            Integer contractSeasons,
            Integer loanSeasons,
            Integer parentWageContribution,
            String executionMode,
            Integer executionSeason) {
    }

    public record PlayerOption(
            long id,
            String name,
            String position,
            int age,
            double rating,
            Long teamId,
            String teamName,
            long wage,
            long transferValue,
            int contractEndSeason,
            int contractSeasonsRemaining,
            boolean activeLoan) {
    }

    public record TransferAdminState(int currentSeason, List<AdminPlayerMovement> movements) {
    }

    public TransferAdminState state() {
        return new TransferAdminState(currentSeason(), movementRepository.findAllByOrderByCreatedAtDesc());
    }

    public List<PlayerOption> playerOptions(Long sourceTeamId, boolean freeAgents, String query) {
        int season = currentSeason();
        List<Human> players = new ArrayList<>();
        if (freeAgents) {
            players.addAll(humanRepository.findAllByTypeIdAndRetiredFalseAndTeamIdIsNull(TypeNames.PLAYER_TYPE));
            players.addAll(humanRepository.findAllByTypeIdAndRetiredFalseAndTeamId(TypeNames.PLAYER_TYPE, 0L));
        } else {
            if (sourceTeamId == null || sourceTeamId <= 0) {
                throw new IllegalArgumentException("sourceTeamId is required for club players");
            }
            players.addAll(humanRepository.findAllByTeamIdAndTypeId(sourceTeamId, TypeNames.PLAYER_TYPE));
        }

        String normalizedQuery = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        Set<Long> activeLoanPlayerIds = loanRepository.findAllByStatus("active").stream()
                .map(Loan::getPlayerId)
                .collect(Collectors.toSet());
        Map<Long, String> teamNames = teamRepository.findAllById(players.stream()
                        .map(Human::getTeamId)
                        .filter(Objects::nonNull)
                        .filter(id -> id > 0)
                        .collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Team::getId, Team::getName));

        return players.stream()
                .filter(player -> !player.isRetired())
                .filter(player -> !player.isWillNeverLeave())
                .filter(player -> normalizedQuery.isEmpty()
                        || player.getName().toLowerCase(Locale.ROOT).contains(normalizedQuery))
                .sorted(Comparator.comparingDouble(Human::getRating).reversed()
                        .thenComparing(Human::getName))
                .limit(250)
                .map(player -> new PlayerOption(
                        player.getId(),
                        player.getName(),
                        player.getPosition(),
                        player.getAge(),
                        Math.round(player.getRating() * 10.0) / 10.0,
                        player.getTeamId(),
                        player.getTeamId() == null || player.getTeamId() <= 0
                                ? "Free Agent" : teamNames.getOrDefault(player.getTeamId(), "Unknown"),
                        player.getWage(),
                        player.getTransferValue(),
                        player.getContractEndSeason(),
                        Math.max(0, player.getContractEndSeason() - season),
                        activeLoanPlayerIds.contains(player.getId())))
                .toList();
    }

    @Transactional
    public AdminPlayerMovement create(MovementCommand command) {
        int season = currentSeason();
        NormalizedCommand normalized = normalizeAndValidate(command, season);
        Human player = normalized.player();
        Team destination = normalized.destination();
        Team source = normalized.source();

        if (movementRepository.existsByPlayerIdAndStatus(player.getId(), PENDING)) {
            throw new IllegalStateException("Player already has a pending Admin movement");
        }

        AdminPlayerMovement movement = new AdminPlayerMovement();
        movement.setMovementType(normalized.type());
        movement.setExecutionMode(normalized.executionMode());
        movement.setStatus(PENDING);
        movement.setPlayerId(player.getId());
        movement.setPlayerName(player.getName());
        movement.setSourceTeamId(source == null ? null : source.getId());
        movement.setSourceTeamName(source == null ? "Free Agent" : source.getName());
        movement.setDestinationTeamId(destination.getId());
        movement.setDestinationTeamName(destination.getName());
        movement.setTransferFee(normalized.transferFee());
        movement.setWage(normalized.wage());
        movement.setContractSeasons(normalized.contractSeasons());
        movement.setLoanSeasons(normalized.loanSeasons());
        movement.setParentWageContribution(normalized.parentWageContribution());
        movement.setCreatedSeason(season);
        movement.setExecutionSeason(normalized.executionSeason());
        movement.setCreatedAt(System.currentTimeMillis());
        movement = movementRepository.save(movement);

        if (NOW.equals(normalized.executionMode())) {
            executeMovement(movement, season);
            movement.setStatus(COMPLETED);
            movement.setCompletedAt(System.currentTimeMillis());
            movement = movementRepository.save(movement);
        }
        return movement;
    }

    @Transactional
    public AdminPlayerMovement cancel(long movementId) {
        AdminPlayerMovement movement = movementRepository.findById(movementId)
                .orElseThrow(() -> new IllegalArgumentException("Admin movement not found: " + movementId));
        if (!PENDING.equals(movement.getStatus())) {
            throw new IllegalStateException("Only pending movements can be cancelled");
        }
        movement.setStatus(CANCELLED);
        movement.setCompletedAt(System.currentTimeMillis());
        return movementRepository.save(movement);
    }

    /** Cancels editor-scheduled moves when a player is protected afterwards. */
    @Transactional
    public int cancelPendingForPlayer(long playerId) {
        List<AdminPlayerMovement> pending = movementRepository.findAllByPlayerIdAndStatus(playerId, PENDING);
        long now = System.currentTimeMillis();
        for (AdminPlayerMovement movement : pending) {
            movement.setStatus(CANCELLED);
            movement.setCompletedAt(now);
            movement.setFailureReason("Cancelled because player was marked as never leaving");
        }
        if (!pending.isEmpty()) movementRepository.saveAll(pending);
        return pending.size();
    }

    /** Runs during the old -> new season transaction, after due loans returned. */
    @Transactional
    public int executeScheduledForSeason(int newSeason) {
        List<AdminPlayerMovement> due = movementRepository
                .findAllByStatusAndExecutionSeasonLessThanEqualOrderByIdAsc(PENDING, newSeason);
        int completed = 0;
        for (AdminPlayerMovement movement : due) {
            try {
                executeMovement(movement, newSeason);
                movement.setStatus(COMPLETED);
                movement.setCompletedAt(System.currentTimeMillis());
                movement.setFailureReason(null);
                completed++;
            } catch (IllegalArgumentException | IllegalStateException exception) {
                movement.setStatus(FAILED);
                movement.setCompletedAt(System.currentTimeMillis());
                movement.setFailureReason(exception.getMessage());
            }
            movementRepository.save(movement);
        }
        if (!due.isEmpty()) {
            System.out.println("=== ADMIN TRANSFER QUEUE: " + completed + "/" + due.size()
                    + " movement(s) completed for season " + newSeason + " ===");
        }
        return completed;
    }

    private NormalizedCommand normalizeAndValidate(MovementCommand command, int currentSeason) {
        if (command == null || command.playerId() == null) {
            throw new IllegalArgumentException("playerId is required");
        }
        String type = upper(command.type());
        if (!Set.of(PERMANENT, FREE_AGENT, LOAN).contains(type)) {
            throw new IllegalArgumentException("type must be PERMANENT, FREE_AGENT or LOAN");
        }
        String executionMode = upper(command.executionMode());
        if (!Set.of(NOW, START_OF_SEASON).contains(executionMode)) {
            throw new IllegalArgumentException("executionMode must be NOW or START_OF_SEASON");
        }
        Human player = humanRepository.findById(command.playerId())
                .orElseThrow(() -> new IllegalArgumentException("Player not found: " + command.playerId()));
        if (player.isRetired() || player.getTypeId() != TypeNames.PLAYER_TYPE) {
            throw new IllegalArgumentException("Only active players can be moved");
        }
        if (player.isWillNeverLeave()) {
            throw new IllegalArgumentException("Player is editor-protected and will never leave their club");
        }
        if (command.destinationTeamId() == null || command.destinationTeamId() <= 0) {
            throw new IllegalArgumentException("destinationTeamId is required");
        }
        Team destination = teamRepository.findById(command.destinationTeamId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Destination team not found: " + command.destinationTeamId()));
        Team source = player.getTeamId() == null || player.getTeamId() <= 0
                ? null : teamRepository.findById(player.getTeamId()).orElse(null);

        if (FREE_AGENT.equals(type) && source != null) {
            throw new IllegalArgumentException("Selected player is not a free agent");
        }
        if (!FREE_AGENT.equals(type) && source == null) {
            throw new IllegalArgumentException("Selected player does not belong to a club");
        }
        if (source != null && source.getId() == destination.getId()) {
            throw new IllegalArgumentException("Source and destination teams must be different");
        }
        if (!loanRepository.findAllByPlayerIdAndStatus(player.getId(), "active").isEmpty()) {
            throw new IllegalStateException("Player is already on loan");
        }

        int executionSeason = NOW.equals(executionMode)
                ? currentSeason
                : number(command.executionSeason(), currentSeason + 1);
        if (START_OF_SEASON.equals(executionMode)
                && (executionSeason <= currentSeason || executionSeason > currentSeason + 100)) {
            throw new IllegalArgumentException("executionSeason must be between Season "
                    + (currentSeason + 1) + " and Season " + (currentSeason + 100));
        }

        long fee = FREE_AGENT.equals(type) ? 0 : nonNegative(command.transferFee(), "transferFee");
        long wage = LOAN.equals(type) ? player.getWage() : positive(command.wage(), "wage");
        int contractSeasons = LOAN.equals(type) ? 0 : ranged(command.contractSeasons(), 1, 100, "contractSeasons");
        int loanSeasons = LOAN.equals(type) ? ranged(command.loanSeasons(), 1, 100, "loanSeasons") : 0;
        int parentContribution = LOAN.equals(type)
                ? ranged(number(command.parentWageContribution(), 0), 0, 100, "parentWageContribution") : 0;

        if (LOAN.equals(type)) {
            validateLoanDuration(player, executionSeason, loanSeasons);
        }
        return new NormalizedCommand(type, executionMode, executionSeason, player, source, destination,
                fee, wage, contractSeasons, loanSeasons, parentContribution);
    }

    private void executeMovement(AdminPlayerMovement movement, int season) {
        Human player = humanRepository.findById(movement.getPlayerId())
                .orElseThrow(() -> new IllegalStateException("Player no longer exists"));
        if (player.isRetired()) throw new IllegalStateException("Player has retired");
        if (player.isWillNeverLeave()) {
            throw new IllegalStateException("Player is editor-protected and will never leave their club");
        }
        Team destination = teamRepository.findById(movement.getDestinationTeamId())
                .orElseThrow(() -> new IllegalStateException("Destination team no longer exists"));

        Long actualTeamId = player.getTeamId();
        if (FREE_AGENT.equals(movement.getMovementType())) {
            if (actualTeamId != null && actualTeamId > 0) {
                throw new IllegalStateException("Player is no longer a free agent");
            }
            executePermanent(player, null, destination, movement, season);
            return;
        }

        if (movement.getSourceTeamId() == null || !Objects.equals(actualTeamId, movement.getSourceTeamId())) {
            throw new IllegalStateException("Player no longer belongs to " + movement.getSourceTeamName());
        }
        if (!loanRepository.findAllByPlayerIdAndStatus(player.getId(), "active").isEmpty()) {
            throw new IllegalStateException("Player is already on loan");
        }
        Team source = teamRepository.findById(movement.getSourceTeamId())
                .orElseThrow(() -> new IllegalStateException("Source team no longer exists"));
        if (LOAN.equals(movement.getMovementType())) {
            validateLoanDuration(player, season, movement.getLoanSeasons());
            executeLoan(player, source, destination, movement, season);
        } else {
            executePermanent(player, source, destination, movement, season);
        }
    }

    private void executePermanent(Human player, Team source, Team destination,
                                  AdminPlayerMovement movement, int season) {
        long oldWage = player.getWage();
        long fee = movement.getTransferFee();
        long sellOnFee = 0;
        long sellOnRecipientTeamId = 0;
        int sellOnPercentage = player.getSellOnPercentage();
        if (source != null && sellOnPercentage > 0 && player.getSellOnClubId() > 0) {
            sellOnFee = fee * sellOnPercentage / 100;
            sellOnRecipientTeamId = player.getSellOnClubId();
        }

        player.setTeamId(destination.getId());
        player.setWage(movement.getWage());
        player.setSalary(movement.getWage());
        player.setContractEndSeason(season + movement.getContractSeasons());
        player.setPreContractTeamId(0);
        player.setSeasonMatchesPlayed(0);
        player.setConsecutiveBenched(0);
        player.setWantsTransfer(false);
        player.setSellOnPercentage(0);
        player.setSellOnClubId(0);
        humanRepository.save(player);
        transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());

        if (source != null) {
            financeService.recordExpense(destination.getId(), season, 1,
                    "TRANSFER_BUY", "Admin transfer: " + player.getName(), fee);
            Team freshDestination = teamRepository.findById(destination.getId()).orElse(destination);
            freshDestination.setTransferBudget(freshDestination.getTransferBudget() - fee);
            freshDestination.setSalaryBudget(freshDestination.getSalaryBudget() + movement.getWage());
            teamRepository.save(freshDestination);

            financeService.recordTransaction(source.getId(), season, 1,
                    "TRANSFER_SALE", "Admin transfer: " + player.getName(), fee - sellOnFee);
            Team freshSource = teamRepository.findById(source.getId()).orElse(source);
            freshSource.setSalaryBudget(Math.max(0, freshSource.getSalaryBudget() - oldWage));
            teamRepository.save(freshSource);

            if (sellOnFee > 0 && sellOnRecipientTeamId > 0) {
                financeService.recordTransaction(sellOnRecipientTeamId, season, 1,
                        "SELL_ON_FEE", "Sell-on fee for " + player.getName()
                                + " (" + sellOnPercentage + "%)", sellOnFee);
            }
        } else {
            Team freshDestination = teamRepository.findById(destination.getId()).orElse(destination);
            freshDestination.setSalaryBudget(freshDestination.getSalaryBudget() + movement.getWage());
            teamRepository.save(freshDestination);
        }

        Transfer transfer = new Transfer();
        transfer.setPlayerId(player.getId());
        transfer.setPlayerName(player.getName());
        transfer.setPlayerTransferValue(fee);
        transfer.setSellTeamId(source == null ? 0 : source.getId());
        transfer.setSellTeamName(source == null ? "Free Agent" : source.getName());
        transfer.setBuyTeamId(destination.getId());
        transfer.setBuyTeamName(destination.getName());
        transfer.setRating(player.getRating());
        transfer.setSeasonNumber(season);
        transfer.setPlayerAge(player.getAge());
        transfer.setSellOnFeePaid(sellOnFee);
        transfer.setSellOnRecipientTeamId(sellOnRecipientTeamId);
        transferRepository.save(transfer);

        invalidate(source, destination);
        sendInbox(source, season, "Admin Transfer Completed",
                player.getName() + " joined " + destination.getName() + " for " + fee + ".");
        sendInbox(destination, season, "Admin Transfer Completed",
                player.getName() + " joined from " + (source == null ? "free agency" : source.getName())
                        + " for " + fee + ".");
    }

    private void executeLoan(Human player, Team source, Team destination,
                             AdminPlayerMovement movement, int season) {
        long fee = movement.getTransferFee();
        long destinationWageShare = player.getWage()
                * (100L - movement.getParentWageContribution()) / 100L;

        player.setTeamId(destination.getId());
        player.setSeasonMatchesPlayed(0);
        player.setConsecutiveBenched(0);
        humanRepository.save(player);
        transferOfferLifecycleService.removeActiveOffersForPlayer(player.getId());

        financeService.recordExpense(destination.getId(), season, 1,
                "LOAN_FEE", "Admin loan fee for " + player.getName(), fee);
        Team freshDestination = teamRepository.findById(destination.getId()).orElse(destination);
        freshDestination.setTransferBudget(freshDestination.getTransferBudget() - fee);
        freshDestination.setSalaryBudget(freshDestination.getSalaryBudget() + destinationWageShare);
        teamRepository.save(freshDestination);

        financeService.recordTransaction(source.getId(), season, 1,
                "LOAN_FEE", "Admin loan fee received for " + player.getName(), fee);
        Team freshSource = teamRepository.findById(source.getId()).orElse(source);
        freshSource.setSalaryBudget(Math.max(0, freshSource.getSalaryBudget() - destinationWageShare));
        teamRepository.save(freshSource);

        Loan loan = new Loan();
        loan.setPlayerId(player.getId());
        loan.setPlayerName(player.getName());
        loan.setParentTeamId(source.getId());
        loan.setParentTeamName(source.getName());
        loan.setLoanTeamId(destination.getId());
        loan.setLoanTeamName(destination.getName());
        loan.setSeasonNumber(season);
        loan.setStartSeason(season);
        loan.setEndSeason(season + movement.getLoanSeasons() - 1);
        loan.setStatus("active");
        loan.setLoanFee(fee);
        loan.setParentWageContribution(movement.getParentWageContribution());
        loanRepository.save(loan);

        invalidate(source, destination);
        String description = player.getName() + " loaned to " + destination.getName()
                + " through Season " + loan.getEndSeason() + ".";
        sendInbox(source, season, "Admin Loan Completed", description);
        sendInbox(destination, season, "Admin Loan Completed", description);
    }

    private void validateLoanDuration(Human player, int startSeason, int loanSeasons) {
        int contractSeasonsRemaining = player.getContractEndSeason() - startSeason;
        if (contractSeasonsRemaining <= 0) {
            throw new IllegalArgumentException("Player's contract expires before the loan can start");
        }
        if (loanSeasons > contractSeasonsRemaining) {
            throw new IllegalArgumentException("Loan can last at most " + contractSeasonsRemaining
                    + " season(s), matching the player's remaining contract");
        }
    }

    private void invalidate(Team source, Team destination) {
        if (source != null) matchSimulationOrchestrator.invalidateRatingCache(source.getId());
        matchSimulationOrchestrator.invalidateRatingCache(destination.getId());
    }

    private void sendInbox(Team team, int season, String title, String content) {
        if (team == null || !userContext.isHumanTeam(team.getId())) return;
        ManagerInbox inbox = new ManagerInbox();
        inbox.setTeamId(team.getId());
        inbox.setSeasonNumber(season);
        inbox.setRoundNumber(1);
        inbox.setTitle(title);
        inbox.setContent(content);
        inbox.setCategory("transfer");
        inbox.setRead(false);
        inbox.setCreatedAt(System.currentTimeMillis());
        managerInboxRepository.save(inbox);
    }

    private int currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L).intValue();
    }

    private static String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private static int number(Integer value, int fallback) {
        return value == null ? fallback : value;
    }

    private static long nonNegative(Long value, String field) {
        if (value == null || value < 0) throw new IllegalArgumentException(field + " must be at least 0");
        return value;
    }

    private static long positive(Long value, String field) {
        if (value == null || value <= 0) throw new IllegalArgumentException(field + " must be greater than 0");
        return value;
    }

    private static int ranged(Integer value, int min, int max, String field) {
        if (value == null || value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private record NormalizedCommand(
            String type,
            String executionMode,
            int executionSeason,
            Human player,
            Team source,
            Team destination,
            long transferFee,
            long wage,
            int contractSeasons,
            int loanSeasons,
            int parentWageContribution) {
    }
}
