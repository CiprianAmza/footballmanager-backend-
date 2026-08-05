package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.frontend.*;
import com.footballmanagergamesimulator.chairman.mandate.ChairmanTacticalMandateEnforcementService;
import com.footballmanagergamesimulator.chairman.mandate.EffectiveChairmanMandate;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.PlayerInstructionService;
import com.footballmanagergamesimulator.service.PlayerPersonalityService;
import com.footballmanagergamesimulator.service.PlayerRoleService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.service.PlayerValueService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import com.fasterxml.jackson.core.type.TypeReference;     // <--- ACESTA ESTE IMPORTUL CORECT
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/tactic")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}", allowedHeaders = "*")
public class TacticController {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    private static final String ADMIN_TOKEN = "admin-token-2026";

    @Autowired
    TeamRepository teamRepository;
    @Autowired
    HumanRepository humanRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    TacticService tacticService;
    @Autowired
    RoundRepository roundRepository;
    @Autowired
    PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired
    InjuryRepository injuryRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    PlayerPersonalityService playerPersonalityService = new PlayerPersonalityService();
    @Autowired
    PlayerRoleService playerRoleService;
    @Autowired
    PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    PlayerValueService playerValueService;
    @Autowired
    com.footballmanagergamesimulator.service.CoachPermissionService coachPermissionService;
    @Autowired
    com.footballmanagergamesimulator.service.NationService nationService;
    @Autowired @Lazy
    com.footballmanagergamesimulator.service.MatchSimulationOrchestrator matchSimulationOrchestrator;
    @Autowired
    CurrentUserService currentUserService;
    @Autowired
    ChairmanTacticalMandateEnforcementService mandateEnforcement;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/firstEleven")
    public void saveFirstEleven(String tactic) { // tactic format: GK=1231&DC=1331&DL=123...
        // TODO
    }

    @GetMapping("/getFormation/{teamId}")
    public PersonalizedTacticView getFormation(@PathVariable(name = "teamId") String teamId) {
        long _teamId = Long.parseLong(teamId);

        Optional<PersonalizedTactic> tacticOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(_teamId);

        if (tacticOpt.isEmpty()) {
            EffectiveChairmanMandate mandate = mandateEnforcement.mandate(_teamId);
            if (mandate.requiredFormation() == null && mandate.lockedSlots().isEmpty()) {
                return null; // no saved tactic and no active Chairman mandate
            }
            String effective = chooseEditFormation(_teamId, mandate);
            PersonalizedTacticView view = defaultTacticView(_teamId, effective);
            view.setFormationDataList(assistantSelection(_teamId, effective, Set.of(), false));
            return effectiveTacticView(_teamId, view, Set.of(), false);
        }

        PersonalizedTacticView view = effectiveTacticView(_teamId, toView(tacticOpt.get()), Set.of(), false);
        EffectiveChairmanMandate mandate = mandateEnforcement.mandate(_teamId);
        if ((view.getFormationDataList() == null || view.getFormationDataList().isEmpty())
                && (mandate.requiredFormation() != null || !mandate.lockedSlots().isEmpty())) {
            String effective = chooseEditFormation(_teamId, mandate);
            view.setTactic(effective);
            view.setFormationDataList(assistantSelection(_teamId, effective, Set.of(), false));
            view = effectiveTacticView(_teamId, view, Set.of(), false);
        }
        return view;
    }

    private PersonalizedTacticView toView(PersonalizedTactic savedTactic) {
        PersonalizedTacticView view = new PersonalizedTacticView();

        view.setTeamId(savedTactic.getTeamId());
        view.setTactic(savedTactic.getTactic());
        view.setMentality(savedTactic.getMentality());
        view.setTimeWasting(savedTactic.getTimeWasting());
        view.setInPossession(savedTactic.getInPossession());
        view.setPassingType(savedTactic.getPassingType());
        view.setTempo(savedTactic.getTempo());
        view.setDefensiveLine(savedTactic.getDefensiveLine());
        view.setPressing(savedTactic.getPressing());
        view.setWidth(savedTactic.getWidth());
        view.setDribbling(savedTactic.getDribbling());
        view.setFoulFrequency(savedTactic.getFoulFrequency());
        view.setFoulHardness(savedTactic.getFoulHardness());
        view.setTempoFragmentation(savedTactic.getTempoFragmentation());
        view.setWidePlay(savedTactic.getWidePlay());
        view.setTransition(savedTactic.getTransition());
        view.setRecovery(savedTactic.getRecovery());
        view.setPenaltyTakerId(savedTactic.getPenaltyTakerId());
        view.setFreeKickTakerId(savedTactic.getFreeKickTakerId());
        view.setCornerTakerLeftId(savedTactic.getCornerTakerLeftId());
        view.setCornerTakerRightId(savedTactic.getCornerTakerRightId());

        // Convertim JSON-ul salvat înapoi în List<FormationData> pentru frontend
        try {
            if (savedTactic.getFirst11() != null && !savedTactic.getFirst11().isEmpty()) {
                List<FormationData> formationList = objectMapper.readValue(
                        savedTactic.getFirst11(),
                        new TypeReference<List<FormationData>>() {}
                );
                view.setFormationDataList(formationList);
            }
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            // În caz de eroare de parsing, returnăm lista goală sau null
            view.setFormationDataList(new ArrayList<>());
        }

        return view;
    }

    /**
     * Public scouting view of a team's actual tactic plus the strongest formation/line-up
     * available to the current squad. The response is deliberately complete (XI + seven
     * substitutes) so other clubs can inspect it without opening the editor or guessing the
     * AI manager's preferred formation in the browser.
     */
    @GetMapping("/teamView/{teamId}")
    public ResponseEntity<?> getTeamTacticView(@PathVariable long teamId) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) {
            return ResponseEntity.notFound().build();
        }

        List<TacticView> rankedTactics;
        try {
            rankedTactics = getAllPossibleTactics(String.valueOf(teamId));
        } catch (RuntimeException ignored) {
            // The editor must remain usable when availability/rating data is temporarily
            // unavailable. Formation names are a static tactical contract, not match state.
            rankedTactics = formationCatalog(String.valueOf(teamId));
        }
        String bestTactic = rankedTactics.isEmpty() ? "442" : rankedTactics.get(0).getTacticName();

        Human manager = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE)
                .stream().findFirst().orElse(null);
        Optional<PersonalizedTactic> saved = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);

        String managerFormation = saved.map(PersonalizedTactic::getTactic)
                .filter(this::isKnownFormation)
                .orElseGet(() -> manager != null && isKnownFormation(manager.getTacticStyle())
                        ? manager.getTacticStyle() : bestTactic);
        String effectiveManagerFormation = mandateEnforcement.effectiveFormation(teamId, managerFormation);
        String effectiveBestTactic = mandateEnforcement.effectiveFormation(teamId, bestTactic);

        PersonalizedTacticView managerView = saved.map(this::toView)
                .orElseGet(() -> defaultTacticView(teamId, effectiveManagerFormation));
        managerView.setTactic(effectiveManagerFormation);
        Set<Long> unavailable = safeUnavailableIds(teamId);
        managerView = effectiveTacticView(teamId, managerView, unavailable, true);
        List<FormationData> managerFormationData = managerView.getFormationDataList();
        long managerStarters = managerFormationData == null ? 0
                : managerFormationData.stream().filter(data -> data.getPositionIndex() < 30).count();
        if (managerStarters < 11) {
            managerView.setFormationDataList(new ArrayList<>(mandateEnforcement.completeFormation(managerFormationData,
                    assistantSelection(teamId, effectiveManagerFormation, unavailable, true))));
        }
        ensureSevenSubstitutes(teamId, managerView.getFormationDataList(), unavailable);

        PersonalizedTacticView bestView = defaultTacticView(teamId, effectiveBestTactic);
        bestView.setFormationDataList(assistantSelection(teamId, effectiveBestTactic, unavailable, true));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("teamId", teamId);
        response.put("teamName", team.getName());
        response.put("managerId", manager != null ? manager.getId() : null);
        response.put("managerName", manager != null ? manager.getName() : "No appointed manager");
        response.put("managerTacticSource", saved.isPresent() ? "SAVED" : "MANAGER_PREFERENCE");
        response.put("managerTactic", managerView);
        response.put("bestPossibleTactic", bestView);
        EffectiveChairmanMandate chairmanMandate = mandateEnforcement.mandate(teamId);
        response.put("chairmanRequiredFormation", chairmanMandate.requiredFormation());
        response.put("chairmanLockedSlots", chairmanMandate.lockedSlots());
        return ResponseEntity.ok(response);
    }

    private PersonalizedTacticView effectiveTacticView(long teamId, PersonalizedTacticView source,
                                                        Set<Long> unavailable, boolean runtime) {
        String effectiveFormation = mandateEnforcement.effectiveFormation(teamId, source.getTactic());
        source.setTactic(effectiveFormation);
        if (source.getFormationDataList() != null) {
            List<FormationData> effective = mandateEnforcement.enforceFormation(teamId, effectiveFormation,
                    source.getFormationDataList(), legacyLocksUnlessCanonicalMandate(teamId), unavailable, runtime);
            source.setFormationDataList(new ArrayList<>(effective));
        }
        return source;
    }

    private List<com.footballmanagergamesimulator.service.CoachPermissionService.LockedSlot>
    legacyLocksUnlessCanonicalMandate(long teamId) {
        EffectiveChairmanMandate mandate = mandateEnforcement.mandate(teamId);
        return mandate.requiredFormation() != null || !mandate.lockedSlots().isEmpty()
                ? List.of()
                : coachPermissionService.lockedSlots(teamId);
    }

    private boolean isKnownFormation(String tactic) {
        if (tactic == null || tactic.isBlank()) return false;
        try {
            return tacticService.getFormationGridIndices(tactic).length == 11;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private String chooseEditFormation(long teamId, EffectiveChairmanMandate mandate) {
        if (mandate.requiredFormation() != null) return mandate.requiredFormation();
        if (containsAllLocks("442", mandate)) return "442";
        return tacticService.getAllExistingTactics().stream()
                .filter(formation -> containsAllLocks(formation, mandate))
                .findFirst().orElse("442");
    }

    private boolean containsAllLocks(String formation, EffectiveChairmanMandate mandate) {
        try {
            Set<Integer> grid = Arrays.stream(tacticService.getFormationGridIndicesExact(formation))
                    .boxed().collect(Collectors.toSet());
            return mandate.lockedSlots().stream().allMatch(lock -> grid.contains(lock.positionIndex()));
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private PersonalizedTacticView defaultTacticView(long teamId, String tactic) {
        PersonalizedTacticView view = new PersonalizedTacticView();
        view.setTeamId(teamId);
        view.setTactic(tactic);
        view.setMentality("Balanced");
        view.setTimeWasting("Sometimes");
        view.setInPossession("Standard");
        view.setPassingType("Normal");
        view.setTempo("Standard");
        view.setDefensiveLine("Standard");
        view.setPressing("Normal");
        view.setWidth("Balanced");
        view.setDribbling("Standard");
        view.setFoulFrequency("Normal");
        view.setFoulHardness("Medium");
        view.setTempoFragmentation("Normal");
        view.setWidePlay("Shoot");
        view.setTransition("Balanced");
        view.setRecovery("Standard");
        view.setFormationDataList(new ArrayList<>());
        return view;
    }

    private void ensureSevenSubstitutes(long teamId, List<FormationData> formation, Set<Long> unavailableIds) {
        Set<Long> usedPlayers = formation.stream().map(FormationData::getPlayerId).collect(Collectors.toSet());
        Set<Integer> occupiedBenchSlots = formation.stream()
                .map(FormationData::getPositionIndex)
                .filter(index -> index >= 30 && index <= 36)
                .collect(Collectors.toSet());
        Iterator<Human> candidates = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                .stream()
                .filter(player -> !player.isRetired() && !usedPlayers.contains(player.getId()))
                .filter(player -> unavailableIds == null || !unavailableIds.contains(player.getId()))
                .sorted((left, right) -> Double.compare(right.getRating(), left.getRating()))
                .iterator();
        for (int index = 30; index <= 36 && candidates.hasNext(); index++) {
            if (occupiedBenchSlots.contains(index)) continue;
            Human player = candidates.next();
            FormationData substitute = new FormationData();
            substitute.setPositionIndex(index);
            substitute.setPlayerId(player.getId());
            formation.add(substitute);
        }
    }

    /**
     * Endpoint ACTUALIZAT pentru a salva pozițiile exacte (JSON)
     */
    @PostMapping("/saveFormation")
    public ResponseEntity<?> saveFormation(@RequestBody PersonalizedTacticView personalizedTacticView,
                                           HttpServletRequest request) {

        long teamId = personalizedTacticView.getTeamId();
        boolean adminOverride = ADMIN_TOKEN.equals(request.getHeader(ADMIN_TOKEN_HEADER));
        User currentUser = currentUserService.getUserOrNull(request);
        boolean ownsTeam = currentUser != null && Objects.equals(currentUser.getTeamId(), teamId);
        if (!adminOverride && !ownsTeam) {
            return ResponseEntity.status(403).body(Map.of(
                    "error", "You can only edit the tactic of your own team"
            ));
        }

        CoachPermissions perms = coachPermissionService.getOrDefault(teamId);
        PersonalizedTactic existing = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId).orElse(null);
        EffectiveChairmanMandate chairmanMandate = mandateEnforcement.mandate(teamId);
        boolean canonicalMandateActive = chairmanMandate.requiredFormation() != null
                || !chairmanMandate.lockedSlots().isEmpty();

        // Resolve the manager permission first, then overlay the Chairman mandate. The
        // persisted tactic is always this effective value; the request must never win later.
        String managerFormationAllowed = adminOverride || perms.isCanChangeFormationTactics() || existing == null
                ? personalizedTacticView.getTactic() : existing.getTactic();
        String effectiveTactic = mandateEnforcement.effectiveFormation(teamId, managerFormationAllowed);

        PersonalizedTactic personalizedTactic = new PersonalizedTactic();
        personalizedTactic.setTeamId(teamId);

        // ---- Starting XI ----
        // When the owner has barred XI changes, keep the previously saved XI; otherwise take the
        // coach's submission. Owner-locked slots are then forced on top (server-side validation,
        // not just UI), so a coach cannot move a player the owner pinned to a slot.
        List<FormationData> xi;
        if (!adminOverride && !canonicalMandateActive && !perms.isCanPickXI()
                && existing != null && existing.getFirst11() != null) {
            xi = parseFirst11(existing.getFirst11());
        } else {
            xi = personalizedTacticView.getFormationDataList() != null
                    ? new ArrayList<>(personalizedTacticView.getFormationDataList())
                    : new ArrayList<>();
        }
        // The canonical mandate is authoritative: when present, only its exact locked slots
        // restrict the manager. Legacy all-XI/slot permissions must not make every other slot
        // read-only after the Chairman selected only a subset of players.
        xi = mandateEnforcement.enforceFormation(teamId, effectiveTactic, xi,
                adminOverride || canonicalMandateActive
                        ? List.of()
                        : coachPermissionService.parseLockedSlots(perms.getLockedSlots()),
                Set.of(), false);
        String shadowError = forceLockedShadowsAndValidate(xi);
        if (shadowError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", shadowError));
        }
        String specialRoleError = validateSpecialRoles(xi);
        if (specialRoleError != null) {
            return ResponseEntity.badRequest().body(Map.of("error", specialRoleError));
        }
        try {
            personalizedTactic.setFirst11(objectMapper.writeValueAsString(xi));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Error converting formation to JSON");
        }

        // ---- Formation + instructions ----
        if (adminOverride || perms.isCanChangeFormationTactics() || existing == null) {
            personalizedTactic.setTactic(effectiveTactic);
            personalizedTactic.setMentality(personalizedTacticView.getMentality());
            personalizedTactic.setTimeWasting(personalizedTacticView.getTimeWasting());
            personalizedTactic.setInPossession(personalizedTacticView.getInPossession());
            personalizedTactic.setPassingType(personalizedTacticView.getPassingType());
            personalizedTactic.setTempo(personalizedTacticView.getTempo());
            personalizedTactic.setDefensiveLine(personalizedTacticView.getDefensiveLine());
            personalizedTactic.setPressing(personalizedTacticView.getPressing());
            personalizedTactic.setWidth(personalizedTacticView.getWidth());
            personalizedTactic.setDribbling(personalizedTacticView.getDribbling());
            personalizedTactic.setFoulFrequency(personalizedTacticView.getFoulFrequency());
            personalizedTactic.setFoulHardness(personalizedTacticView.getFoulHardness());
            personalizedTactic.setTempoFragmentation(personalizedTacticView.getTempoFragmentation());
            personalizedTactic.setWidePlay(personalizedTacticView.getWidePlay());
            personalizedTactic.setTransition(personalizedTacticView.getTransition());
            personalizedTactic.setRecovery(personalizedTacticView.getRecovery());
        } else {
            // Owner fixed the formation/instructions → keep the existing ones, ignore the coach's.
            personalizedTactic.setTactic(effectiveTactic);
            personalizedTactic.setMentality(existing.getMentality());
            personalizedTactic.setTimeWasting(existing.getTimeWasting());
            personalizedTactic.setInPossession(existing.getInPossession());
            personalizedTactic.setPassingType(existing.getPassingType());
            personalizedTactic.setTempo(existing.getTempo());
            personalizedTactic.setDefensiveLine(existing.getDefensiveLine());
            personalizedTactic.setPressing(existing.getPressing());
            personalizedTactic.setWidth(existing.getWidth());
            personalizedTactic.setDribbling(existing.getDribbling());
            personalizedTactic.setFoulFrequency(existing.getFoulFrequency());
            personalizedTactic.setFoulHardness(existing.getFoulHardness());
            personalizedTactic.setTempoFragmentation(existing.getTempoFragmentation());
            personalizedTactic.setWidePlay(existing.getWidePlay());
            personalizedTactic.setTransition(existing.getTransition());
            personalizedTactic.setRecovery(existing.getRecovery());
        }

        // ---- Set-piece takers ----
        if (adminOverride || perms.isCanSetSetPieces() || existing == null) {
            personalizedTactic.setPenaltyTakerId(personalizedTacticView.getPenaltyTakerId());
            personalizedTactic.setFreeKickTakerId(personalizedTacticView.getFreeKickTakerId());
            personalizedTactic.setCornerTakerLeftId(personalizedTacticView.getCornerTakerLeftId());
            personalizedTactic.setCornerTakerRightId(personalizedTacticView.getCornerTakerRightId());
        } else {
            personalizedTactic.setPenaltyTakerId(existing.getPenaltyTakerId());
            personalizedTactic.setFreeKickTakerId(existing.getFreeKickTakerId());
            personalizedTactic.setCornerTakerLeftId(existing.getCornerTakerLeftId());
            personalizedTactic.setCornerTakerRightId(existing.getCornerTakerRightId());
        }

        if (existing != null) {
            personalizedTactic.setId(existing.getId());
        }
        personalizedTactic.setTactic(effectiveTactic);
        personalizedTacticRepository.save(personalizedTactic);
        return ResponseEntity.ok().build();
    }

    /** Parse a {@code first11} JSON string into FormationData, tolerating malformed/empty input. */
    private List<FormationData> parseFirst11(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            List<FormationData> parsed = objectMapper.readValue(json, new TypeReference<List<FormationData>>() {});
            return parsed != null ? new ArrayList<>(parsed) : new ArrayList<>();
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private static String validateSpecialRoles(List<FormationData> formation) {
        int shooters = 0;
        for (FormationData row : formation) {
            if (row == null || row.getSpecialRole() == null || row.getSpecialRole().isBlank()) continue;
            if (!"SHOOTER".equalsIgnoreCase(row.getSpecialRole().trim())) {
                return "Unknown special role: " + row.getSpecialRole();
            }
            if (row.getPositionIndex() >= 30) return "SHOOTER must be in the starting eleven";
            shooters++;
        }
        return shooters > 1 ? "A team may designate at most one SHOOTER" : null;
    }

    /**
     * A persistent stay-forward player is an immutable SHADOW. Tactical SHADOW selections are
     * otherwise allowed without a team limit, but only in the attacking positions requested by
     * the game design. This runs after Chairman/owner locks so the persisted XI is authoritative.
     */
    private String forceLockedShadowsAndValidate(List<FormationData> formation) {
        Map<Long, Human> players = humanRepository.findAllById(formation.stream()
                        .filter(Objects::nonNull)
                        .map(FormationData::getPlayerId)
                        .filter(id -> id > 0)
                        .collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(Human::getId, player -> player));

        for (FormationData row : formation) {
            if (row == null) continue;
            if (row.getPositionIndex() >= 30) {
                if (row.isShadow()) return "SHADOW must be in the starting eleven";
                // A persistent Shadow sitting on the bench has no active match role yet.
                continue;
            }
            Human player = players.get(row.getPlayerId());
            if (player != null && player.isStayForward()) row.setShadow(true);
            if (!row.isShadow()) continue;
            String position = tacticService.getPositionFromIndex(row.getPositionIndex());
            if (!Set.of("AML", "ML", "AMR", "MR", "AMC", "MC", "ST").contains(position)) {
                return "SHADOW is available only at AML/ML, AMR/MR, AMC/MC or ST";
            }
        }
        return null;
    }

    /**
     * Force the owner's locked players into their pinned slots: drop any incoming entry that
     * holds a locked player or sits on a locked slot, then add the locked assignments. This is
     * the server-side guarantee behind XI-locking — the coach cannot override it via the API.
     */
    private void applyOwnerLocks(List<FormationData> xi, List<com.footballmanagergamesimulator.service.CoachPermissionService.LockedSlot> locks) {
        if (locks == null || locks.isEmpty()) return;
        Set<Long> lockedPlayers = locks.stream().map(l -> l.playerId()).collect(Collectors.toSet());
        Set<Integer> lockedIndices = locks.stream().map(l -> l.positionIndex()).collect(Collectors.toSet());
        xi.removeIf(fd -> lockedPlayers.contains(fd.getPlayerId()) || lockedIndices.contains(fd.getPositionIndex()));
        for (com.footballmanagergamesimulator.service.CoachPermissionService.LockedSlot l : locks) {
            FormationData fd = new FormationData();
            fd.setPositionIndex(l.positionIndex());
            fd.setPlayerId(l.playerId());
            xi.add(fd);
        }
    }

    @GetMapping("/getPlayers/{teamId}")
    private List<PlayerView> getPlayers(@PathVariable(name = "teamId") String teamId) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        List<Human> getAllPlayers = humanRepository.findAllByTeamIdAndTypeId(Long.parseLong(teamId), TypeNames.PLAYER_TYPE);

        List<PlayerView> allPlayers = getAllPlayers
                .stream()
                .map(player -> adaptPlayer(player, team))
                .toList();

        int season = roundRepository.findById(1L).map(round -> (int) round.getSeason()).orElse(1);
        Map<Long, List<Scorer>> seasonRows = scorerRepository.findAllByTeamIdAndSeasonNumber(_teamId, season)
                .stream().collect(Collectors.groupingBy(Scorer::getPlayerId));
        for (PlayerView player : allPlayers) {
            List<Scorer> rows = seasonRows.getOrDefault(player.getId(), List.of());
            player.setSeasonAppearances(rows.size());
            player.setSeasonGoals(rows.stream().mapToInt(Scorer::getGoals).sum());
            player.setSeasonAssists(rows.stream().mapToInt(Scorer::getAssists).sum());
            List<Scorer> rated = rows.stream().filter(row -> row.getRating() > 0 && row.getRating() <= 10).toList();
            player.setSeasonAverageRating(rated.stream().mapToDouble(Scorer::getRating).average().orElse(0));
            player.setLastFiveAverageRating(rated.stream().sorted(Comparator.comparingLong(Scorer::getId).reversed())
                    .limit(5).mapToDouble(Scorer::getRating).average().orElse(0));
        }

        return allPlayers;
    }

    @GetMapping("/getAllPossibleTactics/{teamId}")
    private List<TacticView> getAllPossibleTactics(@PathVariable(name = "teamId") String teamId) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        List<TacticView> tacticViews = new ArrayList<>();
        Set<String> effectiveNames = new HashSet<>();
        List<String> allTactics = tacticService.getAllExistingTactics();
        for (String tactic: allTactics) {
            String effectiveTactic = mandateEnforcement.effectiveFormation(_teamId, tactic);
            if (!effectiveNames.add(effectiveTactic)) continue;
            List<PlayerView> bestEleven = getBestElevenPlayers(team,
                    tacticService.getRoomInTeamByTactic(effectiveTactic), effectiveTactic);

            TacticView tacticView = new TacticView();
            tacticView.setTacticName(effectiveTactic);
            tacticView.setTotalRating(bestEleven.stream().mapToDouble(PlayerView::getRating).sum());

            tacticViews.add(tacticView);
        }

        return tacticViews
                .stream()
                .sorted((x, y) -> Double.compare(y.getTotalRating(), x.getTotalRating()))
                .toList();
    }

    /**
     * Lightweight editor catalog. Unlike {@code getAllPossibleTactics}, this endpoint does not
     * inspect injuries, suspensions, player ratings or match state. A formation picker must not
     * disappear because an unrelated runtime/read-model query is temporarily unavailable.
     */
    @GetMapping("/formationCatalog/{teamId}")
    public List<TacticView> formationCatalog(@PathVariable(name = "teamId") String teamId) {
        long parsedTeamId = Long.parseLong(teamId);
        if (!teamRepository.existsById(parsedTeamId)) {
            throw new RuntimeException("Team not found.");
        }

        List<TacticView> result = new ArrayList<>();
        Set<String> effectiveNames = new LinkedHashSet<>();
        for (String formation : tacticService.getAllExistingTactics()) {
            String effective = mandateEnforcement.effectiveFormation(parsedTeamId, formation);
            if (!effectiveNames.add(effective)) continue;
            TacticView view = new TacticView();
            view.setTacticName(effective);
            view.setTotalRating(0);
            result.add(view);
        }
        return List.copyOf(result);
    }

    /** Minimal manager-editor mandate read model. It deliberately performs no lineup, match or
     * availability work, so the UI can always explain and enforce Chairman restrictions. */
    @GetMapping("/editorMandate/{teamId}")
    public Map<String, Object> editorMandate(@PathVariable long teamId) {
        if (!teamRepository.existsById(teamId)) {
            throw new RuntimeException("Team not found.");
        }
        EffectiveChairmanMandate mandate = mandateEnforcement.mandate(teamId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("teamId", teamId);
        response.put("requiredFormation", mandate.requiredFormation());
        response.put("lockedSlots", mandate.lockedSlots());
        return response;
    }

    /** One pitch cell of a formation: the 0..29 grid index and the position label rendered there. */
    public record FormationCell(int index, String position) {}

    /** The pitch layout (allowed cells + their position labels) for ANY formation key — the single
     *  source of truth so the frontend can offer every formation the engine/AI use (all 15), instead
     *  of a hardcoded subset. Frontend: list formations from /tactic/getAllPossibleTactics, then call
     *  this on selection to highlight + label the cells. */
    @GetMapping("/formationLayout/{tactic}")
    public List<FormationCell> formationLayout(@PathVariable(name = "tactic") String tactic) {
        int[] indices = tacticService.getFormationGridIndices(tactic);
        List<FormationCell> cells = new ArrayList<>(indices.length);
        for (int idx : indices) {
            cells.add(new FormationCell(idx, tacticService.getPositionFromIndex(idx)));
        }
        return cells;
    }

    @GetMapping("/getBestEleven/{teamId}/{tactic}")
    public List<PlayerView> getBestEleven(@PathVariable(name = "teamId") String teamId, @PathVariable(name = "tactic", required = false) String tactic) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        return getAutomaticSelection(_teamId, tactic).startingXI();
    }

    @GetMapping("/getSubstitutions/{teamId}/{tactic}")
    public List<PlayerView> getSubstitutions(@PathVariable(name = "teamId") String teamId, @PathVariable(name = "tactic", required = false) String tactic) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        return getAutomaticSelection(_teamId, tactic).bench();
    }

    /**
     * Returns the available individual player instructions per position.
     * FM-style instructions that modify how a player behaves in a match.
     */
    @GetMapping("/instructions/{position}")
    public List<Map<String, Object>> getInstructionsForPosition(@PathVariable String position) {
        return PlayerInstructionService.getInstructionsForPosition(position);
    }

    /**
     * Returns the rating VALUES at each tier-percentile cutoff, computed from all
     * non-retired players in the world. Frontend uses this to color any rating
     * number (in lists, tables, pitch shirts) by tier with no per-player query.
     *
     *   tier      includes ratings >= the returned value
     *   LEGENDARY  top 2%
     *   WORLD CLASS top 10% (down to legendary cutoff)
     *   VERY GOOD   top 25%
     *   GOOD        top 50%
     *   AVERAGE     top 80%
     *   WEAK        below
     */
    @GetMapping("/ratingTiers")
    public Map<String, Object> getRatingTiers() {
        List<Double> ratings = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(h -> !h.isRetired())
                .map(Human::getRating)
                .sorted()
                .toList();

        Map<String, Object> tiers = new LinkedHashMap<>();
        if (ratings.isEmpty()) {
            tiers.put("legendary",  100.0);
            tiers.put("worldClass", 90.0);
            tiers.put("veryGood",   75.0);
            tiers.put("good",       60.0);
            tiers.put("average",    40.0);
        } else {
            // pct percentile (e.g. 0.98 = top 2% cutoff) → rating at that index
            tiers.put("legendary",  ratings.get(Math.min(ratings.size() - 1, (int) (ratings.size() * 0.98))));
            tiers.put("worldClass", ratings.get(Math.min(ratings.size() - 1, (int) (ratings.size() * 0.90))));
            tiers.put("veryGood",   ratings.get(Math.min(ratings.size() - 1, (int) (ratings.size() * 0.75))));
            tiers.put("good",       ratings.get(Math.min(ratings.size() - 1, (int) (ratings.size() * 0.50))));
            tiers.put("average",    ratings.get(Math.min(ratings.size() - 1, (int) (ratings.size() * 0.20))));
        }
        // Color for each tier (matches the values used in the player card header)
        Map<String, String> colors = new LinkedHashMap<>();
        colors.put("legendary",  "#f1c40f");
        colors.put("worldClass", "#9b59b6");
        colors.put("veryGood",   "#3498db");
        colors.put("good",       "#27ae60");
        colors.put("average",    "#7f8c8d");
        colors.put("weak",       "#8d6e63");
        tiers.put("colors", colors);
        tiers.put("totalPlayers", ratings.size());
        return tiers;
    }

    /**
     * FIFA-style player card data: returns overall rating, position, the 6 most
     * relevant attributes for that position, plus the player's global tier based
     * on STRICT percentile rank across all non-retired players in the world.
     *
     * Tier thresholds (top X% = tier):
     *   2%  LEGENDARY      99-100th percentile
     *   10% WORLD_CLASS    90-98th
     *   25% VERY_GOOD      75-89th
     *   50% GOOD           50-74th
     *   80% AVERAGE        20-49th
     *  100% WEAK           0-19th
     */
    @GetMapping("/playerCard/{playerId}")
    public Map<String, Object> getPlayerCard(@PathVariable long playerId) {
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null) return Map.of("error", "Player not found");

        PlayerSkills skills = playerSkillsRepository.findPlayerSkillsByPlayerId(playerId).orElse(null);
        Team team = player.getTeamId() != null && player.getTeamId() > 0
                ? teamRepository.findById(player.getTeamId()).orElse(null) : null;

        // Compute global percentile rank against all active players.
        // Sorted-list approach is simpler than a query: load all ratings once.
        List<Double> allRatings = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(h -> !h.isRetired())
                .map(Human::getRating)
                .sorted()
                .toList();
        double myRating = player.getRating();
        int countBelowOrEqual = 0;
        for (double r : allRatings) {
            if (r <= myRating) countBelowOrEqual++;
            else break; // sorted, so we can stop
        }
        double percentile = allRatings.isEmpty() ? 50.0
                : (100.0 * countBelowOrEqual / allRatings.size());

        String tier;
        String tierColor;
        if (percentile >= 98.0)      { tier = "LEGENDARY";   tierColor = "#f1c40f"; } // gold
        else if (percentile >= 90.0) { tier = "WORLD CLASS"; tierColor = "#9b59b6"; } // purple
        else if (percentile >= 75.0) { tier = "VERY GOOD";   tierColor = "#3498db"; } // blue
        else if (percentile >= 50.0) { tier = "GOOD";        tierColor = "#27ae60"; } // green
        else if (percentile >= 20.0) { tier = "AVERAGE";     tierColor = "#7f8c8d"; } // gray
        else                          { tier = "WEAK";        tierColor = "#8d6e63"; } // brown

        // Position-relevant top attributes (6 each)
        List<Map<String, Object>> topAttrs = skills != null
                ? topAttributesFor(player.getPosition(), skills)
                : List.of();

        Map<String, Object> card = new LinkedHashMap<>();
        card.put("playerId", playerId);
        card.put("name", player.getName());
        card.put("age", player.getAge());
        card.put("position", player.getPosition());
        card.put("rating", Math.round(player.getRating() * 10.0) / 10.0);
        card.put("morale", Math.round(player.getMorale() * 10.0) / 10.0);
        card.put("fitness", Math.round(player.getFitness() * 10.0) / 10.0);
        card.put("preferredFoot", player.getPreferredFoot());
        card.put("heightCm", player.getHeightCm());
        card.put("weightKg", player.getWeightKg());
        card.put("tier", tier);
        card.put("tierColor", tierColor);
        card.put("percentile", Math.round(percentile * 10.0) / 10.0);
        card.put("teamName", team != null ? team.getName() : null);
        card.put("teamColor1", team != null ? team.getColor1() : null);
        card.put("teamColor2", team != null ? team.getColor2() : null);
        card.put("topAttributes", topAttrs);
        return card;
    }

    /**
     * Returns the 6 most relevant attributes for the player's position
     * (label + value, value on 1-20 scale).
     */
    private List<Map<String, Object>> topAttributesFor(String position, PlayerSkills s) {
        // Use base position so AMC, AML, AMR map to MC/ML/MR
        String pos = TacticService.getBasePosition(position);
        if (pos == null) pos = "MC";

        String[][] attrs;
        switch (pos) {
            case "GK":
                attrs = new String[][]{
                    {"Reflexes", String.valueOf(s.getReflexes())},
                    {"Handling", String.valueOf(s.getHandling())},
                    {"One on Ones", String.valueOf(s.getOneOnOnes())},
                    {"Command of Area", String.valueOf(s.getCommandOfArea())},
                    {"Positioning", String.valueOf(s.getPositioning())},
                    {"Concentration", String.valueOf(s.getConcentration())},
                };
                break;
            case "DC":
                attrs = new String[][]{
                    {"Marking", String.valueOf(s.getMarking())},
                    {"Tackling", String.valueOf(s.getTackling())},
                    {"Heading", String.valueOf(s.getHeading())},
                    {"Positioning", String.valueOf(s.getPositioning())},
                    {"Strength", String.valueOf(s.getStrength())},
                    {"Anticipation", String.valueOf(s.getAnticipation())},
                };
                break;
            case "DL": case "DR":
                attrs = new String[][]{
                    {"Tackling", String.valueOf(s.getTackling())},
                    {"Marking", String.valueOf(s.getMarking())},
                    {"Pace", String.valueOf(s.getPace())},
                    {"Crossing", String.valueOf(s.getCrossing())},
                    {"Stamina", String.valueOf(s.getStamina())},
                    {"Acceleration", String.valueOf(s.getAcceleration())},
                };
                break;
            case "ML": case "MR":
                attrs = new String[][]{
                    {"Crossing", String.valueOf(s.getCrossing())},
                    {"Dribbling", String.valueOf(s.getDribbling())},
                    {"Pace", String.valueOf(s.getPace())},
                    {"Stamina", String.valueOf(s.getStamina())},
                    {"Technique", String.valueOf(s.getTechnique())},
                    {"Acceleration", String.valueOf(s.getAcceleration())},
                };
                break;
            case "ST":
                attrs = new String[][]{
                    {"Finishing", String.valueOf(s.getFinishing())},
                    {"Composure", String.valueOf(s.getComposure())},
                    {"Heading", String.valueOf(s.getHeading())},
                    {"Off the Ball", String.valueOf(s.getOffTheBall())},
                    {"Pace", String.valueOf(s.getPace())},
                    {"Acceleration", String.valueOf(s.getAcceleration())},
                };
                break;
            default: // MC and everything else
                attrs = new String[][]{
                    {"Passing", String.valueOf(s.getPassing())},
                    {"Vision", String.valueOf(s.getVision())},
                    {"First Touch", String.valueOf(s.getFirstTouch())},
                    {"Decisions", String.valueOf(s.getDecisions())},
                    {"Stamina", String.valueOf(s.getStamina())},
                    {"Composure", String.valueOf(s.getComposure())},
                };
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (String[] kv : attrs) {
            Map<String, Object> attr = new LinkedHashMap<>();
            attr.put("label", kv[0]);
            attr.put("value", Integer.parseInt(kv[1]));
            result.add(attr);
        }
        return result;
    }

    /**
     * Auto-suggest set piece takers based on player attributes.
     */
    @GetMapping("/suggestSetPieceTakers/{teamId}")
    public Map<String, Object> suggestSetPieceTakers(@PathVariable long teamId) {
        List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        if (players.isEmpty()) return Map.of();

        List<Long> playerIds = players.stream().map(Human::getId).toList();
        List<PlayerSkills> allSkills = playerSkillsRepository.findAllByPlayerIdIn(playerIds);
        Map<Long, PlayerSkills> skillsMap = new HashMap<>();
        for (PlayerSkills ps : allSkills) skillsMap.put(ps.getPlayerId(), ps);

        Human bestPenalty = null; int bestPenaltyVal = 0;
        Human bestFreeKick = null; int bestFreeKickVal = 0;
        Human bestCornerL = null; int bestCornerLVal = 0;
        Human bestCornerR = null; int bestCornerRVal = 0;

        for (Human player : players) {
            if ("GK".equals(player.getPosition())) continue;
            PlayerSkills sk = skillsMap.get(player.getId());
            if (sk == null) continue;

            int penVal = sk.getPenaltyTaking() * 3 + sk.getComposure() * 2 + sk.getFinishing();
            if (penVal > bestPenaltyVal) { bestPenaltyVal = penVal; bestPenalty = player; }

            int fkVal = sk.getFreeKick() * 3 + sk.getTechnique() * 2 + sk.getCrossing();
            if (fkVal > bestFreeKickVal) { bestFreeKickVal = fkVal; bestFreeKick = player; }

            int corVal = sk.getCorners() * 3 + sk.getCrossing() * 2 + sk.getTechnique();
            if (corVal > bestCornerLVal) {
                bestCornerRVal = bestCornerLVal; bestCornerR = bestCornerL;
                bestCornerLVal = corVal; bestCornerL = player;
            } else if (corVal > bestCornerRVal) {
                bestCornerRVal = corVal; bestCornerR = player;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        if (bestPenalty != null) { result.put("penaltyTakerId", bestPenalty.getId()); result.put("penaltyTakerName", bestPenalty.getName()); }
        if (bestFreeKick != null) { result.put("freeKickTakerId", bestFreeKick.getId()); result.put("freeKickTakerName", bestFreeKick.getName()); }
        if (bestCornerL != null) { result.put("cornerTakerLeftId", bestCornerL.getId()); result.put("cornerTakerLeftName", bestCornerL.getName()); }
        if (bestCornerR != null) { result.put("cornerTakerRightId", bestCornerR.getId()); result.put("cornerTakerRightName", bestCornerR.getName()); }
        return result;
    }

    @GetMapping("/roles/{position}")
    public List<Map<String, Object>> getRolesForPosition(@PathVariable String position) {
        return playerRoleService.getRolesForPosition(position).stream()
                .map(role -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", role.name);
                    m.put("description", role.description);
                    m.put("duties", role.duties);
                    m.put("keyAttributes", role.keyAttributes.keySet());
                    return m;
                }).toList();
    }

    @GetMapping("/roleSuitability/{playerId}/{roleName}")
    public Map<String, Object> getRoleSuitability(@PathVariable long playerId, @PathVariable String roleName) {
        Optional<PlayerSkills> skillsOpt = playerSkillsRepository.findPlayerSkillsByPlayerId(playerId);
        if (skillsOpt.isEmpty()) return Map.of("error", "Player skills not found");
        PlayerSkills skills = skillsOpt.get();

        double suitability = playerRoleService.computeRoleSuitability(skills, roleName);
        double effectiveRating = playerRoleService.computeEffectiveRating(skills, roleName);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playerId", playerId);
        result.put("roleName", roleName);
        result.put("suitability", Math.round(suitability * 10.0) / 10.0);
        result.put("effectiveRating", Math.round(effectiveRating * 10.0) / 10.0);
        result.put("overallRating", Math.round(PlayerSkillsService.computeOverallRating(skills) * 10.0) / 10.0);
        return result;
    }

    @GetMapping("/allRoleSuitabilities/{playerId}")
    public List<Map<String, Object>> getAllRoleSuitabilities(@PathVariable long playerId) {
        Human player = humanRepository.findById(playerId).orElse(null);
        if (player == null) return List.of();
        PlayerSkills skills = playerSkillsRepository.findPlayerSkillsByPlayerId(playerId).orElse(null);
        if (skills == null) return List.of();

        List<PlayerRoleService.RoleDef> roles = playerRoleService.getRolesForPosition(player.getPosition());
        return roles.stream().map(role -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("roleName", role.name);
            m.put("duties", role.duties);
            m.put("suitability", Math.round(playerRoleService.computeRoleSuitability(skills, role.name) * 10.0) / 10.0);
            m.put("effectiveRating", Math.round(playerRoleService.computeEffectiveRating(skills, role.name) * 10.0) / 10.0);
            return m;
        }).sorted((a, b) -> Double.compare((double) b.get("suitability"), (double) a.get("suitability"))).toList();
    }

//    @PostMapping("/saveFormation")
//    public void saveFormation(@RequestBody PersonalizedTacticView personalizedTacticView) {
//
//        List<FormationData> formationDataList = personalizedTacticView.getFormationDataList();
//        if (formationDataList.size() < 11) return;
//
//        StringBuilder tactic = new StringBuilder();
//        for (FormationData formationData: formationDataList) {
//
//            String position = tacticService.getPositionFromIndex(formationData.getPositionIndex());
//            Human player = humanRepository.findById(formationData.getPlayerId()).get();
//
//            tactic.append(String.format("%s:%s,", position, player.getId()));
//        }
//
//        PersonalizedTactic personalizedTactic = new PersonalizedTactic();
//        personalizedTactic.setTeamId(personalizedTacticView.getTeamId());
//        personalizedTactic.setFirst11(tactic.toString());
//        personalizedTactic.setTactic(personalizedTacticView.getTactic());
//        personalizedTactic.setMentality(personalizedTacticView.getMentality());
//        personalizedTactic.setTimeWasting(personalizedTacticView.getTimeWasting());
//        personalizedTactic.setInPossession(personalizedTacticView.getInPossession());
//        personalizedTactic.setPassingType(personalizedTacticView.getPassingType());
//        personalizedTactic.setTempo(personalizedTacticView.getTempo());
//
//        Optional<PersonalizedTactic> existingTactic = personalizedTacticRepository.findPersonalizedTacticByTeamId(personalizedTactic.getTeamId());
//        if (existingTactic.isPresent()) {
//            personalizedTacticRepository.delete(existingTactic.get());
//        }
//
//        personalizedTacticRepository.save(personalizedTactic);
//    }

    /**
     * Pairs a selected starter with the base tactic-position slot they occupy. The slot may
     * differ from the player's natural position when an out-of-position filler is used —
     * downstream {@code PlayerValueService} applies a familiarity penalty for that mismatch.
     */
    public record StarterSlot(PlayerView player, String usedPosition) {}

    /** One Chairman-aware automatic selection shared by XI and bench consumers. */
    public record AutomaticSelection(List<PlayerView> startingXI, List<PlayerView> bench) {
        public AutomaticSelection {
            startingXI = List.copyOf(startingXI == null ? List.of() : startingXI);
            bench = List.copyOf(bench == null ? List.of() : bench);
        }
    }

    public AutomaticSelection getAutomaticSelection(long teamId, String tactic) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) throw new RuntimeException("Team not found.");
        String effectiveTactic = mandateEnforcement.effectiveFormation(teamId, tactic);
        Map<String, Integer> tacticFormat = tacticService.getRoomInTeamByTactic(effectiveTactic);
        List<PlayerView> startingXI = getBestElevenPlayers(team, tacticFormat, effectiveTactic);
        Map<String, Integer> substitutionFormat = tacticService.getSubstitutionsInTeamByTactic(effectiveTactic);
        List<PlayerView> bench = getBestSubstitutions(team, substitutionFormat, startingXI);
        Set<Long> starterIds = startingXI.stream().map(PlayerView::getId).collect(Collectors.toSet());
        bench = bench.stream().filter(player -> !starterIds.contains(player.getId())).distinct().limit(7).toList();
        return new AutomaticSelection(startingXI, bench);
    }

    /**
     * Same selection as {@link #getBestEleven}, but preserves which base-position slot each
     * starter occupies (so the match-value engine can apply position familiarity).
     */
    public List<StarterSlot> getBestElevenWithSlots(String teamId, String tactic) {
        long _teamId = Long.parseLong(teamId);
        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");
        // Display order (by the player's natural position) matches the legacy getBestEleven
        // ordering, so consumers that map slots → players keep a stable, deterministic sequence.
        String effectiveTactic = mandateEnforcement.effectiveFormation(_teamId, tactic);
        return selectStarterSlots(team, tacticService.getRoomInTeamByTactic(effectiveTactic), effectiveTactic)
                .stream()
                .sorted(Comparator.comparingInt(s ->
                        tacticService.getValueForTacticDisplay(s.player().getPosition())))
                .toList();
    }

    private List<PlayerView> getBestElevenPlayers(Team team, Map<String, Integer> tacticFormat, String formation) {
        return selectStarterSlots(team, tacticFormat, formation)
                .stream()
                .map(StarterSlot::player)
                .sorted((p1, p2) ->
                        Integer.compare(
                                tacticService.getValueForTacticDisplay(p1.getPosition()),
                                tacticService.getValueForTacticDisplay(p2.getPosition())))
                .toList();
    }

    /**
     * Pick the eleven starters for a tactic and record the slot each occupies.
     * Players are chosen by "aptness" = generic skill ({@code rating}) × fitness, so a fitter
     * player is preferred when ratings are close. Per position the best apt players fill the
     * required slots; remaining slots are filled by the best apt leftovers (out of position).
     */
    private List<StarterSlot> selectStarterSlots(Team team, Map<String, Integer> tacticFormat, String formation) {

        Set<Long> unavailableIds = safeUnavailableIds(team.getId());

        // The enforcement service owns Chairman -> legacy precedence and exact conflict rules.
        List<StarterSlot> slots = new ArrayList<>();
        Set<Long> lockedPlayerIds = new HashSet<>();
        Map<String, Integer> lockedCountByPos = new HashMap<>();
        for (EffectiveChairmanMandate.Slot lock : mandateEnforcement.resolvedLockedSlots(team.getId(), formation,
                legacyLocksUnlessCanonicalMandate(team.getId()), unavailableIds)) {
            Human locked = humanRepository.findById(lock.playerId()).orElse(null);
            if (locked == null || lockedPlayerIds.contains(locked.getId())) continue;
            String slotPos = TacticService.getBasePosition(tacticService.getPositionFromIndex(lock.positionIndex()));
            slots.add(new StarterSlot(adaptPlayer(locked, team), slotPos));
            lockedPlayerIds.add(locked.getId());
            lockedCountByPos.merge(slotPos, 1, Integer::sum);
        }

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE)
                .stream()
                .filter(player -> !unavailableIds.contains(player.getId()))
                .filter(player -> !lockedPlayerIds.contains(player.getId()))
                .toList();
        List<PlayerView> players = allPlayers.stream().map(player -> adaptPlayer(player, team)).toList();
        Map<String, List<PlayerView>> positionToPlayers = new HashMap<>();

        for (PlayerView playerView: players) {
            positionToPlayers.computeIfAbsent(playerView.getPosition(), k -> new ArrayList<>()).add(playerView);
        }

        List<PlayerView> restPlayers = new ArrayList<>();
        Deque<String> openSlots = new ArrayDeque<>();
        int available = 11 - slots.size();

        // Stable position order so out-of-position fillers map to open slots deterministically
        // (familiarity now depends on the slot, and tacticFormat is an unordered Map.of).
        List<Map.Entry<String, Integer>> orderedSlots = tacticFormat.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> tacticService.getValueForTacticDisplay(e.getKey())))
                .toList();
        for (Map.Entry<String, Integer> entry: orderedSlots) {
            String position = entry.getKey();
            // Slots already taken by owner-locked players at this position no longer need filling.
            int needed = Math.max(0, entry.getValue() - lockedCountByPos.getOrDefault(position, 0));

            List<PlayerView> playersForThisPosition = positionToPlayers.getOrDefault(position, new ArrayList<>()).stream()
                    .sorted((x, y) -> Double.compare(aptness(y), aptness(x)))
                    .toList();

            int taken = Math.min(needed, playersForThisPosition.size());
            for (int i = 0; i < taken; i++) {
                slots.add(new StarterSlot(playersForThisPosition.get(i), position));
                available -= 1;
            }
            for (int i = needed; i < playersForThisPosition.size(); i++) {
                restPlayers.add(playersForThisPosition.get(i));
            }
            for (int i = taken; i < needed; i++) {
                openSlots.add(position);
            }
        }

        restPlayers.sort((x, y) -> Double.compare(aptness(y), aptness(x)));
        int fill = Math.min(available, restPlayers.size());
        for (int i = 0; i < fill; i++) {
            String slot = openSlots.isEmpty() ? "MC" : openSlots.poll();
            slots.add(new StarterSlot(restPlayers.get(i), slot));
        }

        if (slots.size() < 11) {
            System.out.println("WARNING: Team " + team.getName() + " only has " + slots.size() + " available players! Filling with youth placeholders.");
        }
        while (slots.size() < 11) {
            String slot = openSlots.isEmpty() ? "MC" : openSlots.poll();
            PlayerView playerView = new PlayerView();
            playerView.setAge(16);
            playerView.setName("Youth Player");
            playerView.setPosition(slot);
            playerView.setRating(10);
            playerView.setFitness(100);
            playerView.setMorale(70);
            slots.add(new StarterSlot(playerView, slot));
        }

        return slots;
    }

    /** Squad-selection "aptness": generic skill weighted by fitness (an unfit star may be benched). */
    private double aptness(PlayerView player) {
        return player.getRating() * playerValueService.fitnessFactor(player.getFitness());
    }

    private List<PlayerView> getBestSubstitutions(Team team, Map<String, Integer> substitutionFormat, List<PlayerView> playersInFirstEleven) {

        Set<Long> unavailableIds = safeUnavailableIds(team.getId());
        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE)
                .stream()
                .filter(player -> !unavailableIds.contains(player.getId()))
                .toList();
        List<PlayerView> players = allPlayers.stream().map(player -> adaptPlayer(player, team)).toList();
        Map<String, List<PlayerView>> positionToPlayers = new HashMap<>();

        for (PlayerView playerView: players) {
            if (playersInFirstEleven.contains(playerView)) continue;
            if (!positionToPlayers.containsKey(playerView.getPosition()))
                positionToPlayers.put(playerView.getPosition(), new ArrayList<>());
            positionToPlayers.get(playerView.getPosition()).add(playerView);
        }

        Map<String, List<PlayerView>> bestEleven = new HashMap<>();
        int available = 5;
        List<PlayerView> restPlayers = new ArrayList<>();

        for (Map.Entry<String, Integer> entry: substitutionFormat.entrySet()) {
            String position = entry.getKey();
            Integer needed = entry.getValue();
            bestEleven.put(position, new ArrayList<>());

            List<PlayerView> playersForThisPosition = positionToPlayers.getOrDefault(position, new ArrayList<>()).stream()
                    .sorted((x, y) -> Double.compare(y.getRating(), x.getRating()))
                    .toList();

            for (int i = 0; i < Math.min(needed, playersForThisPosition.size()); i++) {
                bestEleven.get(position).add(playersForThisPosition.get(i));
                available -= 1;
            }

            for (int i = needed; i < playersForThisPosition.size(); i++) {
                restPlayers.add(playersForThisPosition.get(i));
            }
        }

        List<PlayerView> firstEleven = new ArrayList<>();
        for (List<PlayerView> playerViews: bestEleven.values())
            firstEleven.addAll(playerViews);

        firstEleven.sort((p1, p2) -> Double.compare(p2.getRating(), p1.getRating()));
        for (int i = 0; i < Math.min(available, restPlayers.size()); i++) { // todo handle case where team does not have at least 11 players...
            firstEleven.add(restPlayers.get(i));
        }

        return firstEleven
                .stream()
                .sorted((p1, p2) ->
                        Integer.compare(
                                tacticService.getValueForTacticDisplay(p1.getPosition()),
                                tacticService.getValueForTacticDisplay(p2.getPosition())))
                .toList();
    }

    /** Editor/read-model availability must degrade to an empty exclusion set, never a 500 that
     * clears the complete tactics screen. Runtime simulation still uses the strict repositories. */
    private Set<Long> safeUnavailableIds(long teamId) {
        try {
            return matchSimulationOrchestrator.roundUnavailableIds(teamId);
        } catch (RuntimeException ignored) {
            return Set.of();
        }
    }

    private PlayerView adaptPlayer(Human human, Team team) {

        PlayerView playerView = new PlayerView();
        playerView.setId(human.getId());
        playerView.setAge(human.getAge());
        playerView.setPosition(human.getPosition());
        playerView.setRating(human.getRating());
        playerView.setCurrentAbility(human.getCurrentAbility());
        playerView.setPotentialAbility(human.getPotentialAbility());
        playerView.setPersonalityLabel(playerPersonalityService.profile(human).personalityLabel());
        playerView.setName(human.getName());
        playerView.setTeamId(team.getId());
        playerView.setTeamName(team.getName());
        playerView.setMorale(human.getMorale());
        playerView.setFitness(human.getFitness());
        playerView.setSalary(human.getSalary());
        playerView.setCurrentStatus(human.getCurrentStatus());
        playerView.setAgreedPlayingTime(human.getAgreedPlayingTime());
        playerView.setContractEndSeason(human.getContractEndSeason());
        playerView.setWage(human.getWage());
        playerView.setReleaseClause(human.getReleaseClause());
        playerView.setTransferValue(human.getTransferValue());
        playerView.setWealth(human.getWealth());
        playerView.setStayForward(human.isStayForward());
        playerView.setPreferredFoot(human.getPreferredFoot());
        playerView.setHeightCm(human.getHeightCm());
        playerView.setWeightKg(human.getWeightKg());

        // Nation (so squad/pitch faces can show the per-nation signature).
        com.footballmanagergamesimulator.service.NationService.NationInfo nation = nationService.infoForTeam(team.getId());
        playerView.setNationId(nation.id());
        playerView.setNationName(nation.name());
        playerView.setNationFlagCode(nation.flagCode());

        // Face descriptor so the squad/pitch can render the player's actual face (not a default one).
        playerView.setBaseFaceId(human.getBaseFaceId());
        playerView.setSkinTone(human.getSkinTone());
        playerView.setHairStyle(human.getHairStyle());
        playerView.setHairColor(human.getHairColor());
        playerView.setEyeColor(human.getEyeColor());
        playerView.setFaceShape(human.getFaceShape());
        playerView.setNoseShape(human.getNoseShape());
        playerView.setEyeShape(human.getEyeShape());
        playerView.setMouthShape(human.getMouthShape());
        playerView.setBrowShape(human.getBrowShape());
        playerView.setSpecies(human.getSpecies());

        return playerView;
    }

    @GetMapping("/getTeamTotalSkills/{competitionId}")
    private List<Pair<String, Double>> getTeamTotalSkills(@PathVariable(name = "competitionId") String competitionId) {

        long _competitionId = Long.parseLong(competitionId);

        List<Pair<String, Double>> teamTotalSkills = new ArrayList<>();
        for (Team team : teamRepository.findAllByCompetitionId(_competitionId)) {
            double totalSkill = getTotalTeamSkill(team.getId());
            Pair<String, Double> pair = Pair.of(team.getName(), totalSkill);
            teamTotalSkills.add(pair);
        }

        return teamTotalSkills
                .stream()
                .sorted((x, y) -> Double.compare(y.getValue(), x.getValue()))
                .toList();
    }

    private double getTotalTeamSkill(long teamId) {

        return humanRepository
                .findAll()
                .stream()
                .filter(human -> human.getTeamId() == teamId)
                .map(Human::getRating)
                .sorted((a, b) -> Double.compare(b, a))
                .limit(11)
                .reduce(Double::sum).orElse(0D);
    }

    @GetMapping("/getTeamRatingByManagerTacticForCompetitionId/{competitionId}")
    public List<ManagerTeamTacticView> getTeamRatingByManagerTacticForCompetitionId(
            @PathVariable(name = "competitionId") long competitionId) {

        return getCurrentTeamSkillsAccordingToManagerFavoriteTactic(competitionId);
    }

    /**
     *
     * @param competitionId -> If competitionId is 0, then it will display all the teams from all the competitions. If competitionId is not 0, it will display the teams
     *                      only for that particular competition
     * @param flag -> If flag is false (default), it will sort the entities by the rating of the used tactics. That is, the tactics that the current managers
     *             will use in a game. If the flag is set to true, the entities will be sorted by the best possible tactic available, so kind of a "what would
     *             be the leaderboard if each team would use the best available tactic".
     * @return
     */
    @GetMapping("/getTeamRatingByManagerTacticForCompetitionIdAndBestPossibleTactic/{competitionId}/{flag}")
    public List<ManagerBestTeamTacticView> getTeamRatingByManagerTacticForCompetitionIdAndBestPossibleTactic(@PathVariable(name = "competitionId") long competitionId, @PathVariable(name = "flag", required = false) Boolean flag) {

        List<ManagerBestTeamTacticView> managerBestTeamTacticViews = new ArrayList<>();
        List<ManagerTeamTacticView> currentTeamSkills = getCurrentTeamSkillsAccordingToManagerFavoriteTactic(competitionId);

        for (ManagerTeamTacticView managerTeamTacticView: currentTeamSkills) {

            List<TacticView> allTactics = getAllPossibleTactics(String.valueOf(managerTeamTacticView.getTeamId()));

            ManagerBestTeamTacticView managerBestTeamTacticView = new ManagerBestTeamTacticView();
            managerBestTeamTacticView.setManagerTeamTacticView(managerTeamTacticView);
            managerBestTeamTacticView.setBestPossibleTacticName(allTactics.get(0).getTacticName());
            managerBestTeamTacticView.setBestPossibleTacticRating(allTactics.get(0).getTotalRating());

            managerBestTeamTacticViews.add(managerBestTeamTacticView);
        }

        if (flag)
            managerBestTeamTacticViews.sort((mbttv1, mbttv2) -> Double.compare(mbttv2.getBestPossibleTacticRating(), mbttv1.getBestPossibleTacticRating()));

        return managerBestTeamTacticViews;
    }

    // competitionId = 0 => for all teams all over the game
    private List<ManagerTeamTacticView> getCurrentTeamSkillsAccordingToManagerFavoriteTactic(long competitionId) {

        Set<Long> teamIds = competitionTeamInfoRepository
                .findAll()
                .stream()
                .filter(competitionTeamInfo -> competitionId == 0 || competitionTeamInfo.getCompetitionId() == competitionId)
                .filter(competitionTeamInfo -> competitionTeamInfo.getSeasonNumber() == roundRepository.findById(1L).get().getSeason())
                .map(CompetitionTeamInfo::getTeamId)
                .collect(Collectors.toSet());

        List<ManagerTeamTacticView> managerTeamTacticViews = new ArrayList<>();

        for (Long teamId: teamIds) {
            Team team = teamRepository.findById(teamId).get();
            Human manager = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.MANAGER_TYPE).get(0); // todo check later, what if the team has no manager?

            ManagerTeamTacticView managerTeamTacticView = new ManagerTeamTacticView();
            managerTeamTacticView.setManagerName(manager.getName());
            managerTeamTacticView.setManagerId(manager.getId());
            managerTeamTacticView.setTeamName(team.getName());
            managerTeamTacticView.setTeamId(team.getId());
            managerTeamTacticView.setTactic(manager.getTacticStyle());

            double rating = getBestEleven(String.valueOf(teamId), manager.getTacticStyle())
                    .stream()
                    .mapToDouble(PlayerView::getRating)
                    .sum();

            managerTeamTacticView.setTacticRating(rating);

            managerTeamTacticViews.add(managerTeamTacticView);
        }

        return managerTeamTacticViews
                .stream()
                .sorted((mttv1, mttv2) -> Double.compare(mttv2.getTacticRating(), mttv1.getTacticRating()))
                .toList();
    }

    /**
     * Ask Assistant: auto-select the best 11 + 7 substitutes for a given formation.
     * Returns List<FormationData> with positionIndex and playerId, ready for the frontend grid.
     */
    @GetMapping("/askAssistant/{teamId}/{tactic}")
    public List<FormationData> askAssistant(@PathVariable long teamId, @PathVariable String tactic) {
        Team team = teamRepository.findById(teamId).orElse(null);
        if (team == null) throw new RuntimeException("Team not found.");

        String effectiveTactic = mandateEnforcement.effectiveFormation(teamId, tactic);
        return assistantSelection(teamId, effectiveTactic,
                safeUnavailableIds(teamId), true);
    }

    private List<FormationData> assistantSelection(long teamId, String effectiveTactic,
                                                   Set<Long> unavailableIds, boolean runtime) {
        // Get grid indices for the effective formation
        int[] gridIndices = tacticService.getFormationGridIndices(effectiveTactic);

        List<Human> availablePlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                .stream()
                .filter(p -> !p.isRetired() && (!runtime || unavailableIds == null || !unavailableIds.contains(p.getId())))
                .sorted((a, b) -> Double.compare(b.getRating(), a.getRating()))
                .collect(Collectors.toList());

        // Group players by base position
        Map<String, List<Human>> playersByPos = new HashMap<>();
        for (Human p : availablePlayers) {
            String basePos = TacticService.getBasePosition(p.getPosition());
            playersByPos.computeIfAbsent(basePos, k -> new ArrayList<>()).add(p);
        }

        // Assign best player to each grid index
        Set<Long> usedPlayerIds = new HashSet<>();
        List<FormationData> result = new ArrayList<>();

        // Chairman locks first; legacy locks follow only when they do not conflict.
        Set<Integer> lockedIndices = new HashSet<>();
        for (EffectiveChairmanMandate.Slot lock : mandateEnforcement.resolvedLockedSlots(teamId, effectiveTactic,
                legacyLocksUnlessCanonicalMandate(teamId), unavailableIds)) {
            FormationData fd = new FormationData();
            fd.setPositionIndex(lock.positionIndex());
            fd.setPlayerId(lock.playerId());
            result.add(fd);
            usedPlayerIds.add(lock.playerId());
            lockedIndices.add(lock.positionIndex());
        }

        for (int idx : gridIndices) {
            if (lockedIndices.contains(idx)) continue; // owner pinned this slot
            String gridPos = tacticService.getPositionFromIndex(idx);
            String basePos = TacticService.getBasePosition(gridPos);

            Human bestPlayer = findBestAvailablePlayer(playersByPos, basePos, usedPlayerIds);
            if (bestPlayer != null) {
                usedPlayerIds.add(bestPlayer.getId());
                FormationData fd = new FormationData();
                fd.setPositionIndex(idx);
                fd.setPlayerId(bestPlayer.getId());
                result.add(fd);
            }
        }

        // Fill any remaining empty slots (if not enough players for exact positions) with best unused
        if (result.size() < gridIndices.length) {
            List<Human> remaining = availablePlayers.stream()
                    .filter(p -> !usedPlayerIds.contains(p.getId()))
                    .toList();
            int needed = gridIndices.length - result.size();
            Set<Integer> usedIndices = result.stream().map(FormationData::getPositionIndex).collect(Collectors.toSet());
            int ri = 0;
            for (int idx : gridIndices) {
                if (!usedIndices.contains(idx) && ri < remaining.size() && ri < needed) {
                    FormationData fd = new FormationData();
                    fd.setPositionIndex(idx);
                    fd.setPlayerId(remaining.get(ri).getId());
                    usedPlayerIds.add(remaining.get(ri).getId());
                    result.add(fd);
                    ri++;
                }
            }
        }

        // Add 7 substitutes (positionIndex 30-36) — best remaining players by rating
        List<Human> subsPool = availablePlayers.stream()
                .filter(p -> !usedPlayerIds.contains(p.getId()))
                .limit(7)
                .toList();
        for (int i = 0; i < subsPool.size(); i++) {
            FormationData fd = new FormationData();
            fd.setPositionIndex(30 + i);
            fd.setPlayerId(subsPool.get(i).getId());
            result.add(fd);
        }

        return result;
    }

    private Human findBestAvailablePlayer(Map<String, List<Human>> playersByPos, String basePos, Set<Long> usedIds) {
        List<Human> candidates = playersByPos.getOrDefault(basePos, List.of());
        for (Human p : candidates) {
            if (!usedIds.contains(p.getId())) return p;
        }
        return null;
    }
}
