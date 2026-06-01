package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.config.PlayerCardConfig;
import com.footballmanagergamesimulator.frontend.PlayerCardView;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.stereotype.Service;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Service
public class PlayerCardService {

    private final HumanRepository humanRepository;
    private final PlayerSkillsRepository playerSkillsRepository;
    private final TeamRepository teamRepository;
    private final CompetitionRepository competitionRepository;
    private final PlayerCardConfig playerCardConfig;

    public PlayerCardService(HumanRepository humanRepository,
                             PlayerSkillsRepository playerSkillsRepository,
                             TeamRepository teamRepository,
                             CompetitionRepository competitionRepository,
                             PlayerCardConfig playerCardConfig) {
        this.humanRepository = humanRepository;
        this.playerSkillsRepository = playerSkillsRepository;
        this.teamRepository = teamRepository;
        this.competitionRepository = competitionRepository;
        this.playerCardConfig = playerCardConfig;
    }

    public Optional<PlayerCardView> getPlayerCard(long playerId) {
        Optional<Human> playerOptional = humanRepository.findById(playerId);
        if (playerOptional.isEmpty()) {
            return Optional.empty();
        }

        Human player = playerOptional.get();
        if (player.getTypeId() != TypeNames.PLAYER_TYPE) {
            return Optional.empty();
        }

        Optional<PlayerSkills> skillsOptional = playerSkillsRepository.findPlayerSkillsByPlayerId(playerId);
        if (skillsOptional.isEmpty()) {
            return Optional.empty();
        }

        PlayerSkills skills = skillsOptional.get();
        PlayerCardView cardView = new PlayerCardView();
        cardView.setPlayerId(player.getId());
        cardView.setName(player.getName());
        cardView.setPosition(firstNonBlank(player.getPosition(), skills.getPosition()));
        cardView.setOverall(computeOverall(skills));
        cardView.setPac(computeBucket(skills, "PAC"));
        cardView.setSho(computeBucket(skills, "SHO"));
        cardView.setPas(computeBucket(skills, "PAS"));
        cardView.setDri(computeBucket(skills, "DRI"));
        cardView.setDef(computeBucket(skills, "DEF"));
        cardView.setPhy(computeBucket(skills, "PHY"));
        cardView.setAge(player.getAge());
        cardView.setNationId(resolveNationId(player.getTeamId()));
        cardView.setFaceDescriptor(extractFaceDescriptor(player));
        return Optional.of(cardView);
    }

    int computeBucket(PlayerSkills skills, String bucket) {
        Map<String, Double> weights = playerCardConfig.bucketWeights(bucket);
        double weightedSum = 0.0;
        double totalWeight = 0.0;

        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            if (entry.getValue() <= 0.0) {
                continue;
            }
            Function<PlayerSkills, Integer> getter = PlayerSkillsService.GETTER_MAP.get(entry.getKey());
            if (getter == null) {
                throw new IllegalArgumentException("Unknown PlayerSkills attribute in player.card config: " + entry.getKey());
            }
            weightedSum += getter.apply(skills) * entry.getValue();
            totalWeight += entry.getValue();
        }

        if (totalWeight <= 0.0) {
            return 0;
        }

        double weightedAverage = weightedSum / totalWeight;
        return playerCardConfig.getAttributeScale().scaleToInt(weightedAverage);
    }

    int computeOverall(PlayerSkills skills) {
        return playerCardConfig.getOverallScale().scaleToInt(PlayerSkillsService.computeOverallRating(skills));
    }

    private Long resolveNationId(Long teamId) {
        if (teamId == null || teamId <= 0) {
            return null;
        }

        Optional<Team> team = teamRepository.findById(teamId);
        if (team.isEmpty()) {
            return null;
        }

        Optional<Competition> competition = competitionRepository.findById(team.get().getCompetitionId());
        if (competition.isEmpty()) {
            return null;
        }

        return competition.get().getNationId();
    }

    /** Compact face descriptor (the 5 indices the FE renders a layered face from). Built from the
     *  individual Human getters added with the nation/faces feature; null if none are present. */
    private Object extractFaceDescriptor(Human player) {
        java.util.Map<String, Object> face = new java.util.LinkedHashMap<>();
        invokeOptionalGetter(player, "getBaseFaceId").ifPresent(v -> face.put("baseFaceId", v));
        invokeOptionalGetter(player, "getSkinTone").ifPresent(v -> face.put("skinTone", v));
        invokeOptionalGetter(player, "getHairStyle").ifPresent(v -> face.put("hairStyle", v));
        invokeOptionalGetter(player, "getHairColor").ifPresent(v -> face.put("hairColor", v));
        invokeOptionalGetter(player, "getEyeColor").ifPresent(v -> face.put("eyeColor", v));
        invokeOptionalGetter(player, "getFaceShape").ifPresent(v -> face.put("faceShape", v));
        invokeOptionalGetter(player, "getNoseShape").ifPresent(v -> face.put("noseShape", v));
        invokeOptionalGetter(player, "getEyeShape").ifPresent(v -> face.put("eyeShape", v));
        invokeOptionalGetter(player, "getMouthShape").ifPresent(v -> face.put("mouthShape", v));
        return face.isEmpty() ? null : face;
    }

    private Optional<Object> invokeOptionalGetter(Human player, String getterName) {
        try {
            Method method = Human.class.getMethod(getterName);
            return Optional.ofNullable(method.invoke(player));
        } catch (NoSuchMethodException e) {
            return Optional.empty();
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Failed to read optional human field via " + getterName, e);
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred;
        }
        return fallback;
    }
}
