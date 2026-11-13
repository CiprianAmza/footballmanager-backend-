package com.footballmanagergamesimulator.service.board;

import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.ManagerBackgroundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.user.CoachingLicence;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.Optional;

/**
 * Applies the licence rule to real managers and clubs. Only human managers have a licence on record (their
 * background); AI managers always count as qualified.
 */
@Service
public class LicenceService {

    private final ManagerBackgroundRepository backgroundRepository;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final HumanRepository humanRepository;

    public LicenceService(ManagerBackgroundRepository backgroundRepository, TeamRepository teamRepository,
                          CompetitionRepository competitionRepository, HumanRepository humanRepository) {
        this.backgroundRepository = backgroundRepository;
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
        this.humanRepository = humanRepository;
    }

    /** How a manager stands at a club: the licence the league asks for, his, his official role, the front coach. */
    public record Registration(String requiredLicence, String heldLicence, LicenceRule.Role role,
                               boolean qualified, String frontCoachName) {}

    /** Empty for a manager with no licence on record (AI managers). */
    public Optional<CoachingLicence> held(long managerId) {
        return backgroundRepository.findByManagerId(managerId).map(b -> {
            try {
                return CoachingLicence.valueOf(b.getCoachingLicence());
            } catch (RuntimeException unknown) {
                return CoachingLicence.NONE;
            }
        });
    }

    public CoachingLicence required(long teamId) {
        int tier = teamRepository.findById(teamId)
                .flatMap(t -> competitionRepository.findById(t.getCompetitionId()))
                .map(Competition::getTier).orElse(1);
        return LicenceRule.required(tier);
    }

    public boolean qualifies(long managerId, long teamId) {
        return held(managerId).map(h -> LicenceRule.qualifies(h, required(teamId))).orElse(true);
    }

    /** True when this famous club will not take this manager at all. */
    public boolean refuses(long managerId, long teamId) {
        Optional<CoachingLicence> held = held(managerId);
        if (held.isEmpty()) return false;
        int reputation = teamRepository.findById(teamId).map(Team::getReputation).orElse(0);
        return LicenceRule.refuses(reputation, held.get(), required(teamId));
    }

    /** Refusal for a would-be manager not yet saved (career creation): by the licence he declares. */
    public boolean refuses(CoachingLicence held, long teamId) {
        int reputation = teamRepository.findById(teamId).map(Team::getReputation).orElse(0);
        return LicenceRule.refuses(reputation, held == null ? CoachingLicence.NONE : held, required(teamId));
    }

    public Registration registration(long managerId, long teamId) {
        CoachingLicence required = required(teamId);
        Optional<CoachingLicence> held = held(managerId);
        if (held.isEmpty()) return new Registration(required.label(), null, LicenceRule.Role.HEAD_COACH, true, null);
        LicenceRule.Role role = LicenceRule.role(held.get(), required);
        String front = role == LicenceRule.Role.HEAD_COACH ? null : frontCoach(teamId).map(Human::getName).orElse(null);
        return new Registration(required.label(), held.get().label(), role, role == LicenceRule.Role.HEAD_COACH, front);
    }

    /** The club's assistant manager (else first-team coach) stands in as head coach on paper. */
    public Optional<Human> frontCoach(long teamId) {
        Optional<Human> assistant = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.ASSISTANT_MANAGER_TYPE)
                .stream().filter(h -> !h.isRetired()).min(Comparator.comparingLong(Human::getId));
        if (assistant.isPresent()) return assistant;
        return humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.FIRST_TEAM_COACH_TYPE)
                .stream().filter(h -> !h.isRetired()).min(Comparator.comparingLong(Human::getId));
    }
}
