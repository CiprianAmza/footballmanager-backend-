package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.model.ManagerInbox;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TeamAccessGuard {

    @Autowired
    private CurrentUserService currentUserService;

    public boolean canAccessTeam(HttpServletRequest request, long requestedTeamId) {
        return canAccessTeam(currentUserService.getUserOrNull(request), requestedTeamId);
    }

    public boolean canAccessTeam(User user, long requestedTeamId) {
        Long currentTeamId = positiveTeamId(user != null ? user.getTeamId() : null);
        return currentTeamId != null && currentTeamId == requestedTeamId;
    }

    public Long resolveInboxTeamId(HttpServletRequest request, long requestedTeamId) {
        return resolveInboxTeamId(currentUserService.getUserOrNull(request), requestedTeamId);
    }

    Long resolveInboxTeamId(User user, long requestedTeamId) {
        if (user == null) {
            return null;
        }

        Long currentTeamId = positiveTeamId(user.getTeamId());
        Long lastTeamId = positiveTeamId(user.getLastTeamId());

        if (requestedTeamId > 0) {
            if (currentTeamId != null && requestedTeamId == currentTeamId) {
                return currentTeamId;
            }
            if (currentTeamId == null && lastTeamId != null && requestedTeamId == lastTeamId) {
                return lastTeamId;
            }
            return null;
        }

        if (currentTeamId != null) {
            return currentTeamId;
        }
        return lastTeamId;
    }

    public boolean canAccessInboxMessage(HttpServletRequest request, ManagerInbox message) {
        if (message == null) {
            return false;
        }
        return canAccessInboxMessage(currentUserService.getUserOrNull(request), message);
    }

    boolean canAccessInboxMessage(User user, ManagerInbox message) {
        if (message == null) {
            return false;
        }
        Long accessibleTeamId = resolveInboxTeamId(user, message.getTeamId());
        return accessibleTeamId != null && accessibleTeamId == message.getTeamId();
    }

    private Long positiveTeamId(Long teamId) {
        return teamId != null && teamId > 0 ? teamId : null;
    }
}
