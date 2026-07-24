package com.footballmanagergamesimulator.chairman.mandate;

import com.footballmanagergamesimulator.economy.ClubQueryService;
import com.footballmanagergamesimulator.model.GameCalendar;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.person.CareerType;
import com.footballmanagergamesimulator.person.PersonProfile;
import com.footballmanagergamesimulator.repository.GameCalendarRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.TacticService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ChairmanTacticalMandateService {
    private final ChairmanTacticalMandateRepository mandateRepository;
    private final TeamRepository teamRepository;
    private final HumanRepository humanRepository;
    private final GameCalendarRepository calendarRepository;
    private final ClubQueryService clubQueryService;
    private final TacticService tacticService;
    @Autowired(required = false)
    private ApplicationEventPublisher eventPublisher;

    public ChairmanTacticalMandateService(ChairmanTacticalMandateRepository mandateRepository,
                                          TeamRepository teamRepository,
                                          HumanRepository humanRepository,
                                          GameCalendarRepository calendarRepository,
                                          ClubQueryService clubQueryService,
                                          TacticService tacticService) {
        this.mandateRepository = mandateRepository;
        this.teamRepository = teamRepository;
        this.humanRepository = humanRepository;
        this.calendarRepository = calendarRepository;
        this.clubQueryService = clubQueryService;
        this.tacticService = tacticService;
    }

    @Transactional(readOnly = true)
    public ChairmanTacticalMandateDtos.MandateView get(long teamId, PersonProfile principal) {
        requireChairman(principal);
        clubQueryService.dashboard(teamId, principal);
        return mandateRepository.findByTeamId(teamId)
                .map(this::view)
                .orElse(empty(teamId));
    }

    @Transactional
    public ChairmanTacticalMandateDtos.MandateView update(long teamId, PersonProfile principal,
                                                           ChairmanTacticalMandateDtos.UpdateRequest request) {
        requireChairman(principal);
        teamRepository.findByIdForUpdate(teamId).orElseThrow(() ->
                new ChairmanTacticalMandateException("CLUB_NOT_FOUND", "Club was not found"));
        clubQueryService.dashboard(teamId, principal);
        if (request == null) throw new ChairmanTacticalMandateException("INVALID_MANDATE_SLOT", "Request is required");

        List<ChairmanTacticalMandateDtos.LockedSlot> requested = request.lockedSlots() == null
                ? List.of() : List.copyOf(request.lockedSlots());
        if (requested.size() > 11) throw error("INVALID_MANDATE_SLOT", "A mandate cannot contain more than 11 slots");
        validateFormationAndSlots(request.requiredFormation(), requested);
        validatePlayers(teamId, requested);

        ChairmanTacticalMandate mandate = mandateRepository.findByTeamId(teamId).orElse(null);
        if (mandate == null) {
            if (request.expectedVersion() != 0) throw stale();
            mandate = new ChairmanTacticalMandate();
            mandate.setTeamId(teamId);
        } else if (apiVersion(mandate) != request.expectedVersion()) {
            throw stale();
        }

        mandate.setRequiredFormation(request.requiredFormation());
        mandate.setUpdatedByProfileId(principal.getId());
        GameCalendar calendar = calendarRepository.findTopByOrderBySeasonDesc().orElse(null);
        mandate.setUpdatedSeason(calendar == null ? 0 : calendar.getSeason());
        mandate.setUpdatedGameDay(calendar == null ? 0 : calendar.getCurrentDay());
        List<MandateSlot> slots = requested.stream().sorted(Comparator.comparingInt(ChairmanTacticalMandateDtos.LockedSlot::positionIndex))
                .map(slot -> new MandateSlot(slot.positionIndex(), slot.playerId())).toList();
        mandate.replaceSlots(slots);
        ChairmanTacticalMandateDtos.MandateView result = view(mandateRepository.saveAndFlush(mandate));
        if (eventPublisher != null) eventPublisher.publishEvent(new ChairmanTacticalMandateChangedEvent(teamId));
        return result;
    }

    private static void requireChairman(PersonProfile principal) {
        if (principal == null || principal.getCareerType() != CareerType.CHAIRMAN) {
            throw error("CHAIRMAN_REQUIRED", "A chairman career is required");
        }
    }

    private void validateFormationAndSlots(String formation, List<ChairmanTacticalMandateDtos.LockedSlot> slots) {
        List<String> canonicalFormations = tacticService.getAllExistingTactics();
        Set<Integer> valid = new HashSet<>();
        if (formation != null) {
            if (!canonicalFormations.contains(formation)) {
                throw error("FORMATION_NOT_FOUND", "Formation is not known: " + formation);
            }
            for (int index : tacticService.getFormationGridIndicesExact(formation)) valid.add(index);
        } else {
            Set<String> compatible = canonicalFormations.stream()
                    .filter(known -> slots.stream().allMatch(slot ->
                            contains(tacticService.getFormationGridIndicesExact(known), slot.positionIndex())))
                    .collect(java.util.stream.Collectors.toSet());
            if (!slots.isEmpty() && compatible.isEmpty()) {
                throw error("TACTICAL_MANDATE_INVALID", "No canonical formation contains all mandated slots");
            }
            for (String known : compatible) {
                for (int index : tacticService.getFormationGridIndicesExact(known)) valid.add(index);
            }
        }
        Set<Integer> positions = new HashSet<>();
        Set<Long> players = new HashSet<>();
        for (ChairmanTacticalMandateDtos.LockedSlot slot : slots) {
            if (slot == null || slot.positionIndex() < 0 || slot.positionIndex() >= 30 || !valid.contains(slot.positionIndex())) {
                throw error("INVALID_MANDATE_SLOT", "Mandate slot is not a real field slot");
            }
            if (!positions.add(slot.positionIndex())) throw error("DUPLICATE_MANDATE_SLOT", "Mandate slot is duplicated");
            if (!players.add(slot.playerId())) throw error("DUPLICATE_MANDATE_PLAYER", "Mandate player is duplicated");
        }
    }

    private static boolean contains(int[] values, int expected) {
        for (int value : values) if (value == expected) return true;
        return false;
    }

    private void validatePlayers(long teamId, List<ChairmanTacticalMandateDtos.LockedSlot> slots) {
        for (ChairmanTacticalMandateDtos.LockedSlot slot : slots) {
            Human player = humanRepository.findById(slot.playerId()).orElseThrow(() ->
                    error("MANDATED_PLAYER_NOT_FOUND", "Mandated player was not found"));
            if (player.getTeamId() == null || player.getTeamId() != teamId
                    || player.getTypeId() != TypeNames.PLAYER_TYPE || player.isRetired()) {
                throw error("MANDATED_PLAYER_NOT_ELIGIBLE", "Mandated player is not eligible for this club");
            }
        }
    }

    private ChairmanTacticalMandateDtos.MandateView view(ChairmanTacticalMandate value) {
        List<ChairmanTacticalMandateDtos.LockedSlot> slots = value.sortedSlots().stream()
                .map(slot -> new ChairmanTacticalMandateDtos.LockedSlot(slot.getPositionIndex(), slot.getRequiredPlayerId())).toList();
        return new ChairmanTacticalMandateDtos.MandateView(value.getTeamId(), value.getRequiredFormation(), slots,
                apiVersion(value), value.getUpdatedByProfileId(), value.getUpdatedSeason(), value.getUpdatedGameDay());
    }

    /** Converts the persisted zero-based JPA version to the one-based API contract. */
    private static long apiVersion(ChairmanTacticalMandate value) {
        return value.getVersion() + 1;
    }

    private static ChairmanTacticalMandateDtos.MandateView empty(long teamId) {
        return new ChairmanTacticalMandateDtos.MandateView(teamId, null, List.of(), 0, 0, 0, 0);
    }

    private static ChairmanTacticalMandateException stale() {
        return error("TACTICAL_MANDATE_STALE", "Tactical mandate version is stale");
    }

    private static ChairmanTacticalMandateException error(String code, String message) {
        return new ChairmanTacticalMandateException(code, message);
    }
}
