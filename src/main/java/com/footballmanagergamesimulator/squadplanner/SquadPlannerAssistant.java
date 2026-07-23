package com.footballmanagergamesimulator.squadplanner;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.service.GameStateService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Deterministic Squad Planner assistant.
 *
 * Contract (see FORGE.md Q4):
 *  - NO RNG. Ranking is a stable sort by rating desc, potential desc, id asc.
 *  - NEVER overwrites or clears a {@link SquadPlanSlot#isLocked() locked} slot.
 *  - Only fills empty slots (playerId == null) and appends depth up to MIN_DEPTH.
 *  - Flags {@link SquadPlanSlot#setRecruitmentNeed(boolean) recruitmentNeed}
 *    where the squad cannot cover a position to MIN_DEPTH.
 *  - A player already placed anywhere in the horizon, or marked planned
 *    sale/loan, is not reused.
 */
@Service
public class SquadPlannerAssistant {

    private static final long PLAYER_TYPE_ID = 1L;

    private final SquadPlanSlotRepository slotRepository;
    private final HumanRepository humanRepository;
    private final GameStateService gameStateService;

    public SquadPlannerAssistant(SquadPlanSlotRepository slotRepository,
                                 HumanRepository humanRepository,
                                 GameStateService gameStateService) {
        this.slotRepository = slotRepository;
        this.humanRepository = humanRepository;
        this.gameStateService = gameStateService;
    }

    public List<SquadPlanSlot> fillPlan(int userId, long teamId, int seasonOffset) {
        List<SquadPlanSlot> slots = new ArrayList<>(
                slotRepository.findAllByTeamIdAndSeasonOffset(teamId, seasonOffset));

        // Players already committed to the plan, or explicitly leaving, are unavailable.
        Set<Long> unavailable = new HashSet<>();
        for (SquadPlanSlot s : slots) {
            if (s.getPlayerId() != null) unavailable.add(s.getPlayerId());
        }

        // Available players grouped by canonical position, ranked deterministically.
        Map<String, Deque<Human>> pool = new HashMap<>();
        List<Human> players = new ArrayList<>(humanRepository.findAllByTeamIdAndTypeId(teamId, PLAYER_TYPE_ID));
        players.sort(Comparator
                .comparingDouble(Human::getRating).reversed()
                .thenComparing(Comparator.comparingInt(Human::getPotentialAbility).reversed())
                .thenComparingLong(Human::getId));
        for (Human h : players) {
            if (unavailable.contains(h.getId())) continue;
            String pos = canonical(h.getPosition());
            pool.computeIfAbsent(pos, k -> new ArrayDeque<>()).addLast(h);
        }

        int base = gameStateService.currentSeason();
        List<SquadPlanSlot> dirty = new ArrayList<>();

        for (String position : SquadPlannerService.CANONICAL_POSITIONS) {
            List<SquadPlanSlot> posSlots = new ArrayList<>();
            for (SquadPlanSlot s : slots) {
                if (position.equals(s.getPosition())) posSlots.add(s);
            }
            posSlots.sort(Comparator.comparingInt(SquadPlanSlot::getDepthOrder));
            Deque<Human> avail = pool.getOrDefault(position, new ArrayDeque<>());

            int effectiveDepth = 0;
            int maxDepthOrder = 0;
            for (SquadPlanSlot s : posSlots) {
                maxDepthOrder = Math.max(maxDepthOrder, s.getDepthOrder());
                if (s.isLocked()) {
                    if (isEffective(s)) effectiveDepth++;
                    continue; // never touch a locked slot
                }
                if (s.getPlayerId() == null && !s.isPlannedSale() && !s.isPlannedLoan()) {
                    Human pick = avail.pollFirst();
                    if (pick != null) {
                        s.setPlayerId(pick.getId());
                        s.setRecruitmentNeed(false);
                        dirty.add(s);
                        effectiveDepth++;
                    } else {
                        if (!s.isRecruitmentNeed()) {
                            s.setRecruitmentNeed(true);
                            dirty.add(s);
                        }
                    }
                } else if (isEffective(s)) {
                    effectiveDepth++;
                }
            }

            // Append depth until MIN_DEPTH is reached.
            while (effectiveDepth < SquadPlannerService.MIN_DEPTH) {
                Human pick = avail.pollFirst();
                SquadPlanSlot ns = newSlot(userId, teamId, seasonOffset, base, position, ++maxDepthOrder);
                if (pick != null) {
                    ns.setPlayerId(pick.getId());
                    effectiveDepth++;
                } else {
                    ns.setRecruitmentNeed(true);
                    dirty.add(ns);
                    break; // no more players; one recruitment marker is enough
                }
                dirty.add(ns);
            }
        }

        slotRepository.saveAll(dirty);
        return plannerViewSorted(teamId, seasonOffset);
    }

    private boolean isEffective(SquadPlanSlot s) {
        return s.getPlayerId() != null && !s.isPlannedSale() && !s.isPlannedLoan();
    }

    private SquadPlanSlot newSlot(int userId, long teamId, int seasonOffset, int base,
                                  String position, int depthOrder) {
        SquadPlanSlot s = new SquadPlanSlot();
        s.setUserId(userId);
        s.setTeamId(teamId);
        s.setSeasonOffset(seasonOffset);
        s.setBaseSeason(base);
        s.setPosition(position);
        s.setDepthOrder(depthOrder);
        return s;
    }

    private List<SquadPlanSlot> plannerViewSorted(long teamId, int seasonOffset) {
        List<SquadPlanSlot> out = slotRepository.findAllByTeamIdAndSeasonOffset(teamId, seasonOffset);
        out.sort(Comparator.comparing(SquadPlanSlot::getPosition)
                .thenComparingInt(SquadPlanSlot::getDepthOrder));
        return out;
    }

    /** Map a raw player position string onto a canonical bucket; unknown → as-is uppercased. */
    private String canonical(String raw) {
        if (raw == null || raw.isBlank()) return "MC";
        return raw.trim().toUpperCase(Locale.ROOT);
    }
}
