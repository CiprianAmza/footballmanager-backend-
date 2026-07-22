package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Round;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.RoundRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.JobOfferService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CareerOnboardingService {

    private final UserRepository userRepository;
    private final HumanRepository humanRepository;
    private final TeamRepository teamRepository;
    private final RoundRepository roundRepository;
    private final JobOfferService jobOfferService;
    private final PersonProfileService profileService;

    public CareerOnboardingService(UserRepository userRepository,
                                   HumanRepository humanRepository,
                                   TeamRepository teamRepository,
                                   RoundRepository roundRepository,
                                   JobOfferService jobOfferService,
                                   PersonProfileService profileService) {
        this.userRepository = userRepository;
        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.roundRepository = roundRepository;
        this.jobOfferService = jobOfferService;
        this.profileService = profileService;
    }

    @Transactional
    public Map<String, Object> setupManager(User user, ManagerSetupRequest request) {
        requireRole(user, CareerRole.MANAGER);
        if (user.getManagerId() != null) {
            Human existing = humanRepository.findById(user.getManagerId())
                    .orElseThrow(() -> new IllegalStateException("Manager profile is missing"));
            return managerResult(user, existing, user.getTeamId() == null);
        }

        boolean freeAgent = request.freeAgent() || request.teamId() == null || request.teamId() <= 0;
        Human manager;
        if (freeAgent) {
            manager = new Human();
            manager.setName(request.managerName().trim());
            manager.setAge(request.managerAge());
            manager.setTeamId(0L);
            manager.setTypeId(TypeNames.MANAGER_TYPE);
            manager.setManagerReputation(500);
            manager = humanRepository.save(manager);

            user.setTeamId(null);
            user.setLastTeamId(null);
            user.setManagerId(manager.getId());
            user.setFired(true);
            user.setEverManaged(false);
            user.setInitialOffersGenerated(false);
        } else {
            Team team = teamRepository.findById(request.teamId())
                    .orElseThrow(() -> new IllegalArgumentException("Team not found"));
            List<Human> managers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.MANAGER_TYPE);
            manager = managers.isEmpty() ? new Human() : managers.get(0);
            manager.setName(request.managerName().trim());
            manager.setAge(request.managerAge());
            manager.setTeamId(team.getId());
            manager.setTypeId(TypeNames.MANAGER_TYPE);
            manager = humanRepository.save(manager);

            Round round = roundRepository.findAll().stream().findFirst().orElseGet(Round::new);
            round.setHumanTeamId(team.getId());
            round.setManagerName(manager.getName());
            round.setManagerAge(manager.getAge());
            roundRepository.save(round);

            user.setTeamId(team.getId());
            user.setLastTeamId(team.getId());
            user.setManagerId(manager.getId());
            user.setFired(false);
            user.setEverManaged(true);
        }

        userRepository.save(user);
        profileService.attachManager(user, manager);

        if (freeAgent && !user.isInitialOffersGenerated()) {
            jobOfferService.generateInitialFreeAgentOffers(user.getId(), 3);
            user.setInitialOffersGenerated(true);
            userRepository.save(user);
        }
        return managerResult(user, manager, freeAgent);
    }

    @Transactional
    public Map<String, Object> setupChairman(User user) {
        requireRole(user, CareerRole.CHAIRMAN);
        user.setTeamId(null);
        user.setLastTeamId(null);
        user.setManagerId(null);
        user.setFired(false);
        user.setEverManaged(false);
        userRepository.save(user);
        PersonProfile profile = profileService.requireForUser(user);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("careerRole", CareerRole.CHAIRMAN);
        result.put("profileId", profile.getId());
        result.put("teamId", null);
        result.put("managerId", null);
        return result;
    }

    public Map<String, Object> status(User user) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("careerRole", user.getCareerRole());
        if (user.getCareerRole() == CareerRole.CHAIRMAN) {
            result.put("setupComplete", true);
            result.put("chairman", true);
            result.put("humanTeamId", null);
            result.put("managerId", null);
            return result;
        }
        boolean freeAgent = user.isFired() && user.getManagerId() != null && !user.isEverManaged();
        boolean complete = (user.getTeamId() != null && user.getTeamId() > 0) || freeAgent;
        result.put("setupComplete", complete);
        result.put("freeAgent", freeAgent);
        result.put("managerFired", user.isFired());
        result.put("humanTeamId", user.getTeamId());
        result.put("managerId", user.getManagerId());
        return result;
    }

    private static void requireRole(User user, CareerRole expected) {
        if (user.getCareerRole() != expected) {
            throw new IllegalStateException("Career role does not allow this onboarding flow");
        }
    }

    private static Map<String, Object> managerResult(User user, Human manager, boolean freeAgent) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("success", true);
        result.put("careerRole", CareerRole.MANAGER);
        result.put("managerName", manager.getName());
        result.put("managerId", manager.getId());
        result.put("humanTeamId", user.getTeamId());
        result.put("freeAgent", freeAgent);
        return result;
    }
}
