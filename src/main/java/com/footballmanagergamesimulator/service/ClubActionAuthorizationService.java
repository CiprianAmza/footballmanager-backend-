package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.economy.ClubCapTableService;
import com.footballmanagergamesimulator.economy.PersonalAccount;
import com.footballmanagergamesimulator.economy.PersonalAccountRepository;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.person.PersonProfileService;
import com.footballmanagergamesimulator.user.CareerRole;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Resolves the authenticated club actor for transfer and player-contract mutations. */
@Service
public class ClubActionAuthorizationService {
    public enum Action { TRANSFER, CONTRACT, ACQUISITION, CLUB_LEGACY }

    public record ClubActor(long teamId, boolean chairman) { }

    private final CurrentUserService currentUserService;
    private final PersonProfileService profileService;
    private final PersonalAccountRepository accountRepository;
    private final ClubCapTableService capTableService;
    private final CoachPermissionService permissionService;

    public ClubActionAuthorizationService(CurrentUserService currentUserService,
                                          PersonProfileService profileService,
                                          PersonalAccountRepository accountRepository,
                                          ClubCapTableService capTableService,
                                          CoachPermissionService permissionService) {
        this.currentUserService = currentUserService;
        this.profileService = profileService;
        this.accountRepository = accountRepository;
        this.capTableService = capTableService;
        this.permissionService = permissionService;
    }

    public ClubActor authorize(HttpServletRequest request, Map<String, Object> body, Action action) {
        User user = currentUserService.requireUser(request);
        Long requestedTeamId = requestedTeamId(body);
        if (user.getCareerRole() == CareerRole.CHAIRMAN) {
            if (requestedTeamId == null) {
                throw rejected(HttpStatus.BAD_REQUEST, "ACTING_CLUB_REQUIRED");
            }
            PersonProfile profile = profileService.requireForUser(user);
            PersonalAccount account = accountRepository.findByProfileId(profile.getId())
                    .orElseThrow(() -> rejected(HttpStatus.FORBIDDEN, "CHAIRMAN_ACCOUNT_REQUIRED"));
            if (!capTableService.isController(account.getId(), requestedTeamId)) {
                throw rejected(HttpStatus.FORBIDDEN, "CLUB_CONTROL_REQUIRED");
            }
            return new ClubActor(requestedTeamId, true);
        }

        long managerTeamId = currentUserService.requireTeamId(request);
        if (requestedTeamId != null && requestedTeamId != managerTeamId) {
            throw rejected(HttpStatus.FORBIDDEN, "MANAGER_TEAM_MISMATCH");
        }
        if ((action == Action.TRANSFER || action == Action.ACQUISITION)
                && !permissionService.canBuyPlayers(managerTeamId)) {
            throw rejected(HttpStatus.FORBIDDEN, "CHAIRMAN_TRANSFER_CONTROLLED");
        }
        if ((action == Action.CONTRACT || action == Action.ACQUISITION)
                && !permissionService.canNegotiateContracts(managerTeamId)) {
            throw rejected(HttpStatus.FORBIDDEN, "CHAIRMAN_CONTRACT_CONTROLLED");
        }
        return new ClubActor(managerTeamId, false);
    }

    private Long requestedTeamId(Map<String, Object> body) {
        Object raw = body == null ? null : body.get("teamId");
        if (raw == null || String.valueOf(raw).isBlank()) return null;
        try {
            long value = raw instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(raw));
            if (value <= 0) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException exception) {
            throw rejected(HttpStatus.BAD_REQUEST, "ACTING_CLUB_INVALID");
        }
    }

    private ResponseStatusException rejected(HttpStatus status, String code) {
        return new ResponseStatusException(status, code);
    }
}
