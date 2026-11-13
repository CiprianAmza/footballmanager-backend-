package com.footballmanagergamesimulator.user;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.ManagerBackground;
import com.footballmanagergamesimulator.model.ManagerPlayingSeason;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.ManagerBackgroundRepository;
import com.footballmanagergamesimulator.repository.ManagerPlayingSeasonRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.FaceGenerator;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.TacticService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.MonthDay;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The human manager's own story (design DESIGN_START_CARIERA §2.2): birthday, nation, favourite club, preferred
 * tactic, playing career and coaching licence. The playing career sets his starting reputation; the rest is stored
 * and shown for now. Its consequences (chairman's patience, fans, the licence rule) come in later steps.
 */
@Service
public class ManagerBackgroundService {

    private final ManagerBackgroundRepository backgroundRepository;
    private final ManagerPlayingSeasonRepository seasonRepository;
    private final TeamRepository teamRepository;

    public ManagerBackgroundService(ManagerBackgroundRepository backgroundRepository,
                                    ManagerPlayingSeasonRepository seasonRepository,
                                    TeamRepository teamRepository) {
        this.backgroundRepository = backgroundRepository;
        this.seasonRepository = seasonRepository;
        this.teamRepository = teamRepository;
    }

    /** Rejects a request that names an unknown nation, club or tactic, or an impossible birthday. */
    public void validate(ManagerSetupRequest request) {
        if ((request.birthDay() == null) != (request.birthMonth() == null)) {
            throw new IllegalArgumentException("Give both the day and the month of birth, or neither.");
        }
        if (request.birthDay() != null) {
            try {
                MonthDay.of(request.birthMonth(), request.birthDay());
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("That day does not exist in that month.");
            }
        }
        if (request.birthNationId() != null
                && NationService.countries().stream().noneMatch(n -> n.id() == request.birthNationId())) {
            throw new IllegalArgumentException("Unknown nation.");
        }
        if (request.favoriteTeamId() != null && request.favoriteTeamId() > 0
                && !teamRepository.existsById(request.favoriteTeamId())) {
            throw new IllegalArgumentException("Unknown favourite club.");
        }
        if (request.preferredTactic() != null && !request.preferredTactic().isBlank()
                && !TacticService.existingFormations().contains(request.preferredTactic())) {
            throw new IllegalArgumentException("Unknown tactic.");
        }
    }

    /** Writes the story onto the manager: reputation from his playing career, preferred tactic, stored details. */
    @Transactional
    public void apply(Human manager, ManagerSetupRequest request) {
        validate(request);
        PlayingCareer career = request.playingCareer() == null ? PlayingCareer.NONE : request.playingCareer();
        CoachingLicence licence = request.coachingLicence() == null ? CoachingLicence.NONE : request.coachingLicence();
        manager.setManagerReputation(career.startingReputation());
        String tactic = request.preferredTactic();
        if (tactic != null && !tactic.isBlank()) {
            manager.setTacticStyle(tactic);
            manager.setKnownTactics(withTactic(manager.getKnownTactics(), tactic));
        }
        applyFace(manager, request.face(), request.birthNationId());

        ManagerBackground background = backgroundRepository.findByManagerId(manager.getId())
                .orElseGet(ManagerBackground::new);
        background.setManagerId(manager.getId());
        background.setBirthDay(request.birthDay() == null ? 0 : request.birthDay());
        background.setBirthMonth(request.birthMonth() == null ? 0 : request.birthMonth());
        background.setBirthNationId(request.birthNationId() == null ? -1 : request.birthNationId());
        background.setFavoriteTeamId(request.favoriteTeamId() == null ? 0 : request.favoriteTeamId());
        background.setPlayingCareer(career.name());
        background.setCoachingLicence(licence.name());
        backgroundRepository.save(background);

        seasonRepository.deleteAllByManagerId(manager.getId());
        List<ManagerPlayingSeason> seasons = new ArrayList<>();
        List<ManagerSetupRequest.PlayingSeason> given =
                request.playingSeasons() == null ? List.of() : request.playingSeasons();
        for (int i = 0; i < given.size(); i++) {
            ManagerSetupRequest.PlayingSeason line = given.get(i);
            ManagerPlayingSeason row = new ManagerPlayingSeason();
            row.setManagerId(manager.getId());
            row.setSeasonOrder(i + 1);
            row.setTeamName(line.teamName().trim());
            row.setGoals(line.goals());
            row.setAssists(line.assists());
            seasons.add(row);
        }
        seasonRepository.saveAll(seasons);
    }

    /** The face from the editor; the species follows the nation of birth, as for players. */
    static void applyFace(Human manager, ManagerSetupRequest.Face face, Long birthNationId) {
        if (face == null) return;
        manager.setBaseFaceId(face.baseFaceId());
        manager.setSkinTone(face.skinTone());
        manager.setHairStyle(face.hairStyle());
        manager.setHairColor(face.hairColor());
        manager.setEyeColor(face.eyeColor());
        manager.setFaceShape(face.faceShape());
        manager.setNoseShape(face.noseShape());
        manager.setEyeShape(face.eyeShape());
        manager.setMouthShape(face.mouthShape());
        manager.setBrowShape(face.browShape());
        manager.setSpecies(FaceGenerator.speciesFor(birthNationId == null ? -1 : birthNationId));
    }

    /** The preferred tactic is always one he knows, listed first. */
    static String withTactic(String known, String tactic) {
        List<String> list = new ArrayList<>();
        list.add(tactic);
        if (known != null) {
            for (String t : known.split(",")) {
                String trimmed = t.trim();
                if (!trimmed.isEmpty() && !list.contains(trimmed)) list.add(trimmed);
            }
        }
        return String.join(",", list);
    }

    /** The story as shown on the manager's profile; empty for a manager who has none (AI managers). */
    public Map<String, Object> view(long managerId) {
        Optional<ManagerBackground> stored = backgroundRepository.findByManagerId(managerId);
        if (stored.isEmpty()) return Map.of();
        ManagerBackground b = stored.get();
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("birthDay", b.getBirthDay());
        view.put("birthMonth", b.getBirthMonth());
        NationService.countries().stream().filter(n -> n.id() == b.getBirthNationId()).findFirst()
                .ifPresent(nation -> {
                    view.put("birthNation", nation.name());
                    view.put("birthNationFlag", nation.flagCode());
                });
        if (b.getFavoriteTeamId() > 0) {
            view.put("favoriteTeamId", b.getFavoriteTeamId());
            view.put("favoriteTeamName", teamRepository.findById(b.getFavoriteTeamId()).map(Team::getName).orElse(null));
        }
        view.put("playingCareer", careerLabel(b.getPlayingCareer()));
        view.put("coachingLicence", licenceLabel(b.getCoachingLicence()));
        List<Map<String, Object>> seasons = new ArrayList<>();
        for (ManagerPlayingSeason row : seasonRepository.findAllByManagerIdOrderBySeasonOrderAsc(managerId)) {
            seasons.add(Map.of("season", row.getSeasonOrder(), "teamName", row.getTeamName(),
                    "goals", row.getGoals(), "assists", row.getAssists()));
        }
        view.put("playingSeasons", seasons);
        return view;
    }

    /** What the creation screen offers: nations, formations, playing careers (with reputation) and licences. */
    public Map<String, Object> options() {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("nations", NationService.countries());
        options.put("tactics", TacticService.existingFormations());
        options.put("playingCareers", Arrays.stream(PlayingCareer.values())
                .map(c -> Map.of("value", c.name(), "label", c.label(), "reputation", c.startingReputation()))
                .toList());
        options.put("coachingLicences", Arrays.stream(CoachingLicence.values())
                .map(l -> Map.of("value", l.name(), "label", l.label()))
                .toList());
        // How many choices each part of the face has (indices 0..n-1), and the species of each nation.
        Map<String, Integer> faceParts = new LinkedHashMap<>();
        faceParts.put("skinTone", 6);
        faceParts.put("hairStyle", 20);
        faceParts.put("hairColor", 6);
        faceParts.put("eyeColor", 4);
        faceParts.put("faceShape", 18);
        faceParts.put("noseShape", 10);
        faceParts.put("eyeShape", 10);
        faceParts.put("mouthShape", 5);
        faceParts.put("browShape", 9);
        faceParts.put("baseFaceId", 10);
        options.put("faceParts", faceParts);
        Map<Long, String> species = new LinkedHashMap<>();
        NationService.countries().forEach(n -> species.put(n.id(), FaceGenerator.speciesFor(n.id())));
        options.put("speciesByNation", species);
        return options;
    }

    private static String careerLabel(String name) {
        try {
            return name == null ? null : PlayingCareer.valueOf(name).label();
        } catch (IllegalArgumentException unknown) {
            return name;
        }
    }

    private static String licenceLabel(String name) {
        try {
            return name == null ? null : CoachingLicence.valueOf(name).label();
        } catch (IllegalArgumentException unknown) {
            return name;
        }
    }
}
