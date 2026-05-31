package com.footballmanagergamesimulator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.model.CoachPermissions;
import com.footballmanagergamesimulator.repository.CoachPermissionsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Guardian for the owner-imposed coach-permission matrix (Boardroom Faza 4).
 * Every coach action point consults this before acting; an AI coach is bound by
 * the same toggles. Missing row ⇒ fully permissive default, so enforcement is
 * additive and a team that no owner has touched behaves exactly as before.
 */
@Service
public class CoachPermissionService {

    @Autowired
    private CoachPermissionsRepository repository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** One locked starter: which grid slot, which player. */
    public record LockedSlot(int positionIndex, long playerId) {}

    /** Never null. Returns a transient permissive default when no row exists. */
    public CoachPermissions getOrDefault(long teamId) {
        return repository.findByTeamId(teamId).orElseGet(() -> {
            CoachPermissions p = new CoachPermissions();
            p.setTeamId(teamId);
            return p; // entity defaults are all-permissive
        });
    }

    public boolean canBuyPlayers(long teamId) { return getOrDefault(teamId).isCanBuyPlayers(); }
    public boolean canSellPlayers(long teamId) { return getOrDefault(teamId).isCanSellPlayers(); }
    public boolean canNegotiateContracts(long teamId) { return getOrDefault(teamId).isCanNegotiateContracts(); }
    public boolean canPickXI(long teamId) { return getOrDefault(teamId).isCanPickXI(); }
    public boolean canChangeFormationTactics(long teamId) { return getOrDefault(teamId).isCanChangeFormationTactics(); }
    public boolean canSetTraining(long teamId) { return getOrDefault(teamId).isCanSetTraining(); }
    public boolean canSetSetPieces(long teamId) { return getOrDefault(teamId).isCanSetSetPieces(); }

    /** Max single offer the coach may make; -1 = no cap. */
    public long transferBudgetCap(long teamId) { return getOrDefault(teamId).getTransferBudgetCap(); }

    /** Parsed owner XI locks for the team (empty when none). */
    public List<LockedSlot> lockedSlots(long teamId) {
        return parseLockedSlots(getOrDefault(teamId).getLockedSlots());
    }

    public List<LockedSlot> parseLockedSlots(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<LockedSlot> parsed = objectMapper.readValue(json, new TypeReference<List<LockedSlot>>() {});
            return parsed != null ? parsed : new ArrayList<>();
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public String writeLockedSlots(List<LockedSlot> slots) {
        try {
            return objectMapper.writeValueAsString(slots != null ? slots : new ArrayList<>());
        } catch (Exception e) {
            throw new RuntimeException("Error serializing locked slots", e);
        }
    }

    public CoachPermissions save(CoachPermissions permissions) {
        return repository.save(permissions);
    }

    /**
     * How heavily the owner restricts this coach: one point per OFF toggle plus
     * one per locked slot. Feeds the press arrogance/humiliation dynamics (Faza 6).
     */
    public int countRestrictions(long teamId) {
        return countOffToggles(teamId) + lockedSlots(teamId).size();
    }

    /** Number of permission toggles the owner has switched OFF (excludes XI locks). */
    public int countOffToggles(long teamId) {
        CoachPermissions p = getOrDefault(teamId);
        int n = 0;
        if (!p.isCanBuyPlayers()) n++;
        if (!p.isCanSellPlayers()) n++;
        if (!p.isCanNegotiateContracts()) n++;
        if (!p.isCanPickXI()) n++;
        if (!p.isCanChangeFormationTactics()) n++;
        if (!p.isCanSetTraining()) n++;
        if (!p.isCanSetSetPieces()) n++;
        return n;
    }
}
