package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds the initial 22-man squad for a team at season-1 setup. Lifted out
 * of {@link com.footballmanagergamesimulator.controller.CompetitionController}
 * where the same generation loop was duplicated in two places (the
 * {@code initializeRound} bootstrap path and the season-1 branch of
 * {@code play}). Owns the position template, the rating range derived from
 * the team's facility reputation, the per-player save chain (Human → skills
 * → historical relation), the post-generation rating recompute, and the
 * batched shirt-number assignment.
 */
@Service
public class SquadGenerationService {

    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private CompetitionService competitionService;
    @Autowired private CompositeNameGenerator compositeNameGenerator;

    /** Standard 22-man squad template (2 GK, 2 DL, 2 DR, 4 DC, 2 ML, 2 MR,
     *  4 MC, 4 ST). Indices map deterministically to positions so the squad
     *  always has the same shape regardless of team. */
    private static final int SQUAD_SIZE = 22;

    /**
     * Build, persist, and return a 22-man squad for {@code team} at the given
     * {@code currentSeason}. The caller owns the {@link Random} so tests can
     * seed it for determinism. {@code initialMorale} differs across the two
     * original call sites (70 vs 100) — both are exposed verbatim.
     *
     * <p>Side effects: saves {@link Human}s (twice each — once for the ID,
     * again post-shirt-assignment), a {@link TeamPlayerHistoricalRelation}
     * row per player, and a {@link PlayerSkills} row per player. Returns the
     * in-memory squad with final ratings + shirt numbers applied.
     */
    public List<Human> generateInitialSquad(Team team, TeamFacilities facilities,
                                            int currentSeason, int initialMorale, Random random) {
        List<Human> generatedSquad = new ArrayList<>(SQUAD_SIZE);
        int reputation = (facilities != null) ? (int) facilities.getSeniorTrainingLevel() * 10 : 100;

        for (int i = 0; i < SQUAD_SIZE; i++) {
            Human player = buildOnePlayer(team, currentSeason, initialMorale, reputation,
                    positionForIndex(i), random);
            player = humanRepository.save(player);

            persistHistoricalRelation(player, team.getId(), currentSeason);
            PlayerSkills skills = persistSkills(player);

            // Recompute rating from the generated attributes so the rating
            // reflects the actual skill profile rather than the random seed.
            double computedRating = PlayerSkillsService.computeOverallRating(skills);
            player.setRating(computedRating);
            player.setCurrentAbility((int) computedRating);
            player.setBestEverRating(computedRating);
            player.setTransferValue(TransferValueCalculator.calculate(
                    player.getAge(), player.getPosition(), computedRating));
            player.setWage((long) (computedRating * 50));
            generatedSquad.add(player);
        }

        HumanService.assignShirtNumbers(generatedSquad);
        humanRepository.saveAll(generatedSquad);
        return generatedSquad;
    }

    private Human buildOnePlayer(Team team, int currentSeason, int initialMorale,
                                 int reputation, String position, Random random) {
        Human player = new Human();
        player.setTeamId(team.getId());
        player.setName(compositeNameGenerator.generateName(team.getCompetitionId()));
        player.setTypeId(TypeNames.PLAYER_TYPE);
        player.setPosition(position);
        player.setAge(random.nextInt(23, 30));
        player.setSeasonCreated((long) currentSeason);
        player.setCurrentStatus("Senior");
        player.setMorale(initialMorale);
        player.setFitness(100);

        int playerRating = random.nextInt(Math.max(10, reputation - 20), Math.max(11, reputation + 20));
        player.setRating(playerRating);
        player.setCurrentAbility(playerRating);
        player.setPotentialAbility(playerRating + random.nextInt(10, 40));
        player.setBestEverRating(playerRating);

        HumanService.generatePhysicalProfile(player, random);

        long transferVal = TransferValueCalculator.calculate(player.getAge(), position, player.getRating());
        player.setTransferValue(transferVal);
        player.setContractEndSeason(currentSeason + random.nextInt(2, 6));
        player.setWage((long) (player.getRating() * 50));
        player.setReleaseClause(random.nextInt(10) < 3 ? 0 : transferVal * 2);
        return player;
    }

    private void persistHistoricalRelation(Human player, long teamId, int currentSeason) {
        TeamPlayerHistoricalRelation rel = new TeamPlayerHistoricalRelation();
        rel.setPlayerId(player.getId());
        rel.setTeamId(teamId);
        rel.setSeasonNumber(currentSeason);
        rel.setRating(player.getRating());
        teamPlayerHistoricalRelationRepository.save(rel);
    }

    private PlayerSkills persistSkills(Human player) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPlayerId(player.getId());
        skills.setPosition(player.getPosition());
        competitionService.generateSkills(skills, player.getRating());
        playerSkillsRepository.save(skills);
        return skills;
    }

    /** Index 0..21 → position. Mirrors the original controller's template
     *  exactly: 2 GK, 2 DL, 2 DR, 4 DC, 2 ML, 2 MR, 4 MC, 4 ST. */
    private static String positionForIndex(int i) {
        if (i < 2)  return "GK";
        if (i < 4)  return "DL";
        if (i < 6)  return "DR";
        if (i < 10) return "DC";
        if (i < 12) return "ML";
        if (i < 14) return "MR";
        if (i < 18) return "MC";
        return "ST";
    }
}
