package com.footballmanagergamesimulator.economy;

import com.footballmanagergamesimulator.model.CoachPermissions;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.service.CoachPermissionService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChairmanCoachAuthorityService {
    private final PersonalAccountRepository accountRepository;
    private final ClubCapTableService capTableService;
    private final CoachPermissionService permissionService;

    public ChairmanCoachAuthorityService(PersonalAccountRepository accountRepository,
                                         ClubCapTableService capTableService,
                                         CoachPermissionService permissionService) {
        this.accountRepository = accountRepository;
        this.capTableService = capTableService;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public ClubDtos.CoachAuthorityView get(PersonProfile profile, long teamId) {
        requireControl(profile, teamId);
        CoachPermissions permissions = permissionService.getOrDefault(teamId);
        return view(teamId, permissions);
    }

    @Transactional
    public ClubDtos.CoachAuthorityView update(PersonProfile profile, long teamId,
                                               ClubDtos.CoachAuthorityRequest request) {
        requireControl(profile, teamId);
        CoachPermissions permissions = permissionService.getOrDefault(teamId);
        permissions.setCanBuyPlayers(request.managerTransfersAllowed());
        permissions.setCanNegotiateContracts(request.managerContractsAllowed());
        return view(teamId, permissionService.save(permissions));
    }

    private void requireControl(PersonProfile profile, long teamId) {
        if (profile.getCareerType() != CareerType.CHAIRMAN) {
            throw new EconomyConflictException("CHAIRMAN_REQUIRED", "A chairman career is required");
        }
        PersonalAccount account = accountRepository.findByProfileId(profile.getId())
                .orElseThrow(() -> new EconomyConflictException("ACCOUNT_NOT_FOUND", "Personal account is missing"));
        if (!capTableService.isController(account.getId(), teamId)) {
            throw new EconomyConflictException("CLUB_CONTROL_REQUIRED",
                    "Authenticated chairman does not control this club");
        }
    }

    private ClubDtos.CoachAuthorityView view(long teamId, CoachPermissions permissions) {
        return new ClubDtos.CoachAuthorityView(teamId, permissions.isCanBuyPlayers(),
                permissions.isCanNegotiateContracts());
    }
}
