package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.InboxAudience;
import com.footballmanagergamesimulator.model.ManagerInbox;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.ManagerInboxRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Applies the controlling chairman's transfer allocation to the club budget. */
@Service
public class ChairmanTransferBudgetService {
    private final PersonalAccountRepository accountRepository;
    private final ClubCapTableService capTableService;
    private final TeamRepository teamRepository;
    private final GameCalendarRepository calendarRepository;
    private final ManagerInboxRepository inboxRepository;
    private final MarketMutationLock marketMutationLock;

    public ChairmanTransferBudgetService(PersonalAccountRepository accountRepository,
                                         ClubCapTableService capTableService,
                                         TeamRepository teamRepository,
                                         GameCalendarRepository calendarRepository,
                                         ManagerInboxRepository inboxRepository,
                                         MarketMutationLock marketMutationLock) {
        this.accountRepository = accountRepository;
        this.capTableService = capTableService;
        this.teamRepository = teamRepository;
        this.calendarRepository = calendarRepository;
        this.inboxRepository = inboxRepository;
        this.marketMutationLock = marketMutationLock;
    }

    @Transactional
    public ClubDtos.TransferBudgetView update(PersonProfile profile, long teamId, long amount) {
        marketMutationLock.lock();
        try {
            if (profile.getCareerType() != CareerType.CHAIRMAN) {
                throw new EconomyConflictException("CHAIRMAN_REQUIRED", "A chairman career is required");
            }
            if (amount < 0) {
                throw new EconomyConflictException("TRANSFER_BUDGET_INVALID",
                        "Transfer budget cannot be negative");
            }
            PersonalAccount account = accountRepository.findByProfileId(profile.getId())
                    .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND",
                            "Personal account is missing"));
            if (!capTableService.isController(account.getId(), teamId)) {
                throw new EconomyConflictException("CLUB_CONTROL_REQUIRED",
                        "Authenticated chairman does not control this club");
            }
            Team team = teamRepository.findByIdForUpdate(teamId)
                    .orElseThrow(() -> new EconomyConflictException("CLUB_NOT_FOUND", "Club was not found"));
            long maximum = Math.max(0, team.getTotalFinances());
            if (amount > maximum) {
                throw new EconomyConflictException("TRANSFER_BUDGET_EXCEEDS_CLUB_FUNDS",
                        "Transfer budget cannot exceed the club treasury");
            }

            boolean changed = team.getTransferBudget() != amount;
            team.setTransferBudget(amount);
            teamRepository.save(team);
            if (changed) notifyClub(profile, team, amount);
            return new ClubDtos.TransferBudgetView(teamId, amount, maximum, team.getTotalFinances());
        } finally {
            marketMutationLock.unlock();
        }
    }

    private void notifyClub(PersonProfile chairman, Team team, long amount) {
        GameCalendar calendar = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        ManagerInbox message = new ManagerInbox();
        message.setTeamId(team.getId());
        message.setSeasonNumber(calendar == null ? 0 : calendar.getSeason());
        message.setRoundNumber(calendar == null ? 0 : calendar.getCurrentDay());
        message.setCategory("CHAIRMAN_TRANSFER_BUDGET");
        message.setTitle("Transfer budget set by chairman");
        message.setContent("The chairman has set " + team.getName()
                + "'s available transfer budget to " + amount + ".");
        message.setRead(false);
        message.setCreatedAt(System.currentTimeMillis());
        message.setRecipientProfileId(chairman.getId());
        message.setAudience(InboxAudience.BOTH);
        message.setDeduplicationKey("TRANSFER-BUDGET:" + team.getId() + ":" + UUID.randomUUID());
        inboxRepository.save(message);
    }
}
