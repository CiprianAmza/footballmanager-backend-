package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Loan;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Transfer;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.LoanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TransferRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

/** Shared availability rules for transfer-market and scouting search results. */
@Service
public class PlayerMarketAvailabilityService {
    private final HumanRepository humanRepository;
    private final TransferRepository transferRepository;
    private final LoanRepository loanRepository;
    private final RoundRepository roundRepository;

    public PlayerMarketAvailabilityService(HumanRepository humanRepository,
                                           TransferRepository transferRepository,
                                           LoanRepository loanRepository,
                                           RoundRepository roundRepository) {
        this.humanRepository = humanRepository;
        this.transferRepository = transferRepository;
        this.loanRepository = loanRepository;
        this.roundRepository = roundRepository;
    }

    public int currentSeason() {
        return roundRepository.findById(1L).map(Round::getSeason).orElse(1L).intValue();
    }

    public Set<Long> unavailablePlayerIds() {
        Set<Long> ids = new LinkedHashSet<>();
        currentSeasonStates().forEach((playerId, state) -> {
            if (state.transferredThisSeason() || state.loanedThisSeason() || state.loaned()) ids.add(playerId);
        });
        return Set.copyOf(ids);
    }

    /** One request-scoped snapshot used by both Recruitment screens and their filters. */
    public Map<Long, MarketState> currentSeasonStates() {
        int season = currentSeason();
        Map<Long, MutableState> states = new LinkedHashMap<>();
        for (Transfer transfer : transferRepository.findAllBySeasonNumber(season)) {
            MutableState state = states.computeIfAbsent(transfer.getPlayerId(), ignored -> new MutableState());
            state.transferredThisSeason = true;
            state.transferFromTeamId = transfer.getSellTeamId();
            state.transferFromTeamName = transfer.getSellTeamName();
            state.transferToTeamId = transfer.getBuyTeamId();
            state.transferToTeamName = transfer.getBuyTeamName();
        }
        for (Loan loan : loanRepository.findAllBySeasonNumber(season)) {
            MutableState state = states.computeIfAbsent(loan.getPlayerId(), ignored -> new MutableState());
            state.loanedThisSeason = true;
            applyLoan(state, loan, "active".equalsIgnoreCase(loan.getStatus()));
        }
        for (Loan loan : loanRepository.findAllByStatus("active")) {
            MutableState state = states.computeIfAbsent(loan.getPlayerId(), ignored -> new MutableState());
            applyLoan(state, loan, true);
        }
        Map<Long, MarketState> result = new LinkedHashMap<>();
        states.forEach((playerId, state) -> result.put(playerId, state.freeze()));
        return Map.copyOf(result);
    }

    private void applyLoan(MutableState state, Loan loan, boolean active) {
        state.loaned = state.loaned || active;
        state.parentTeamId = loan.getParentTeamId();
        state.parentTeamName = loan.getParentTeamName();
        state.loanTeamId = loan.getLoanTeamId();
        state.loanTeamName = loan.getLoanTeamName();
    }

    public MarketState stateFor(long playerId, Map<Long, MarketState> snapshot) {
        return snapshot.getOrDefault(playerId, MarketState.available());
    }

    public List<String> activePlayerPositions() {
        return humanRepository.findDistinctActivePlayerPositions();
    }

    public record MarketState(boolean transferredThisSeason, boolean loanedThisSeason, boolean loaned,
                              long transferFromTeamId, String transferFromTeamName,
                              long transferToTeamId, String transferToTeamName,
                              long parentTeamId, String parentTeamName,
                              long loanTeamId, String loanTeamName) {
        public static MarketState available() {
            return new MarketState(false, false, false, 0, null, 0, null, 0, null, 0, null);
        }

        public String status() {
            if (loaned) return "LOANED";
            if (transferredThisSeason) return "TRANSFERRED_THIS_SEASON";
            return "AVAILABLE";
        }
    }

    private static final class MutableState {
        boolean transferredThisSeason;
        boolean loanedThisSeason;
        boolean loaned;
        long transferFromTeamId;
        String transferFromTeamName;
        long transferToTeamId;
        String transferToTeamName;
        long parentTeamId;
        String parentTeamName;
        long loanTeamId;
        String loanTeamName;

        MarketState freeze() {
            return new MarketState(transferredThisSeason, loanedThisSeason, loaned,
                    transferFromTeamId, transferFromTeamName, transferToTeamId, transferToTeamName,
                    parentTeamId, parentTeamName, loanTeamId, loanTeamName);
        }
    }
}
