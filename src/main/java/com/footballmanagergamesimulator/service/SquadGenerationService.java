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
        // Squad quality is driven by team reputation (1..10000).
        // Mapping: rep=10000 → target rating 275 (top of 250-300 band),
        // rep=5000 → ~150, rep=1000 → ~50, rep=0 → ~25.
        // Per-player spread of ±25 below produces the full 250-300 band for
        // top teams and natural overlap between adjacent tiers.
        int teamRep = team.getReputation();
        int targetRating = 25 + (int) Math.round((teamRep / 10000.0) * 250.0);

        for (int i = 0; i < SQUAD_SIZE; i++) {
            Human player = buildOnePlayer(team, currentSeason, initialMorale, targetRating,
                    positionForIndex(i), random);
            player = humanRepository.save(player);

            persistHistoricalRelation(player, team.getId(), currentSeason);
            PlayerSkills skills = persistSkills(player, random);

            // Recompute rating from the generated attributes so the rating
            // reflects the actual skill profile rather than the random seed.
            double computedRating = PlayerSkillsService.computeOverallRating(skills);
            player.setRating(computedRating);
            player.setCurrentAbility((int) computedRating);
            player.setBestEverRating(computedRating);
            player.setTransferValue(TransferValueCalculator.calculate(
                    player.getAge(), player.getPosition(), computedRating));
            player.setWage(WageService.baseWage(computedRating));
            generatedSquad.add(player);
        }

        HumanService.assignShirtNumbers(generatedSquad);
        humanRepository.saveAll(generatedSquad);
        return generatedSquad;
    }

    private Human buildOnePlayer(Team team, int currentSeason, int initialMorale,
                                 int targetRating, String position, Random random) {
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

        // Spread of ±25 around the team's target rating gives each squad a
        // visible best-XI/squad-depth gap without crossing tiers.
        int playerRating = random.nextInt(Math.max(10, targetRating - 25), Math.max(11, targetRating + 25));
        player.setRating(playerRating);
        player.setCurrentAbility(playerRating);
        player.setPotentialAbility(playerRating + random.nextInt(10, 40));
        player.setBestEverRating(playerRating);

        HumanService.generatePhysicalProfile(player, random);

        long transferVal = TransferValueCalculator.calculate(player.getAge(), position, player.getRating());
        player.setTransferValue(transferVal);
        player.setContractEndSeason(currentSeason + random.nextInt(2, 6));
        player.setWage(WageService.baseWage(player.getRating()));
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

    private PlayerSkills persistSkills(Human player, Random random) {
        PlayerSkills skills = new PlayerSkills();
        skills.setPlayerId(player.getId());
        skills.setPosition(player.getPosition());
        competitionService.generateSkills(skills, player.getRating(), random);
        playerSkillsRepository.save(skills);
        return skills;
    }

    /** Index 0..21 → position: 2 GK, 1 DL, 1 WBL, 1 DR, 1 WBR, 4 DC, 1 ML, 1 AML, 1 MR, 1 AMR,
     *  2 MC, 1 DM, 1 AMC, 4 ST.
     *  Strat 3 v2: specialist NATURALS across the pitch — holding DM, attacking AMC, wing-backs
     *  (WBL/WBR) and wide attackers (AML/AMR) — so squads suit the fine-position formations without
     *  an out-of-position familiarity penalty. Fine positions are treated as their base archetype by
     *  non-tactical systems (rating/skills/physical/shirt via {@code TacticService.getBasePosition}). */
    private static String positionForIndex(int i) {
        if (i < 2)  return "GK";
        if (i < 3)  return "DL";
        if (i < 4)  return "WBL";
        if (i < 5)  return "DR";
        if (i < 6)  return "WBR";
        if (i < 10) return "DC";
        if (i < 11) return "ML";
        if (i < 12) return "AML";
        if (i < 13) return "MR";
        if (i < 14) return "AMR";
        if (i < 16) return "MC";
        if (i < 17) return "DM";
        if (i < 18) return "AMC";
        return "ST";
    }
}
