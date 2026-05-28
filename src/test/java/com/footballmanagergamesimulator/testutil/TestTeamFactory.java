package com.footballmanagergamesimulator.testutil;

import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Builds synthetic teams + squads for fuzz / integration tests where we need
 * controllable reputation deltas (e.g., "strong rep 10000 vs weak rep 4000"
 * invariant). NOT for production use — bypasses the standard squad-generation
 * pipeline ({@code CompetitionService.generateSkills}) so tests stay fast and
 * deterministic.
 *
 * <p>Reputation → attribute mapping is linear: every PlayerSkills attribute on
 * the produced squad is set to {@code 1 + min(19, reputation/600)}, with a
 * small random ± jitter so the squad has natural variance. This means:
 * <ul>
 *   <li>rep 1200 → attrs ~3 (very weak)</li>
 *   <li>rep 4000 → attrs ~7-8 (mid)</li>
 *   <li>rep 10000 → attrs ~17 (top)</li>
 *   <li>rep 12000 → attrs ~20 (legendary)</li>
 * </ul>
 *
 * <p>The mapping is intentionally approximate — the goal is for the test to
 * see a clear power gap between teams, not to match production squads exactly.
 *
 * <p>{@code @Component} so Spring picks it up when test classes scan the
 * {@code testutil} package. Annotated {@code @Transactional} on writes so
 * each builder call is one DB unit.
 */
@Component
public class TestTeamFactory {

    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;

    /**
     * Default squad size — 11 starters + 14 bench/reserves, matches the
     * shape used by production squad generation.
     */
    public static final int DEFAULT_SQUAD_SIZE = 25;

    /**
     * Default position layout for a 25-man squad: 3 GKs, 8 defenders,
     * 8 midfielders, 6 strikers. Roughly mirrors production squads.
     */
    private static final String[] DEFAULT_POSITIONS = {
        "GK", "GK", "GK",
        "DL", "DC", "DC", "DC", "DC", "DR", "DL", "DR",
        "MC", "MC", "MC", "MC", "ML", "MR", "MC", "ML",
        "ST", "ST", "ST", "ST", "ST", "ST"
    };

    /**
     * Create a Team entity with the given name + reputation. No squad attached;
     * call {@link #generateSyntheticSquad(long, int, int, long)} after to
     * populate players.
     */
    @Transactional
    public Team createSyntheticTeam(String name, int reputation) {
        Team team = new Team();
        team.setName(name);
        team.setReputation(reputation);
        team.setCompetitionId(0L); // not attached to any competition
        team.setStadiumId(0L);
        team.setHistoryId(0L);
        team.setColor1("#888888");
        team.setColor2("#444444");
        return teamRepository.save(team);
    }

    /**
     * Generate a 25-man synthetic squad for the given team. Attribute targets
     * scale with reputation (higher rep → higher attrs). Uses the seed for
     * reproducible jitter — same seed produces the same squad.
     *
     * @param teamId       team to attach players to
     * @param reputation   drives the average attribute level
     * @param squadSize    number of players to generate (use {@link #DEFAULT_SQUAD_SIZE})
     * @param seed         RNG seed for the small per-attribute jitter
     * @return the saved Human entities (players), in position order
     */
    @Transactional
    public List<Human> generateSyntheticSquad(long teamId, int reputation, int squadSize, long seed) {
        if (squadSize <= 0 || squadSize > DEFAULT_POSITIONS.length) {
            throw new IllegalArgumentException(
                "squadSize must be in [1, " + DEFAULT_POSITIONS.length + "]");
        }
        Random rng = new Random(seed);
        int attrTarget = Math.max(1, Math.min(20, 1 + reputation / 600));

        List<Human> players = new ArrayList<>(squadSize);
        for (int i = 0; i < squadSize; i++) {
            String position = DEFAULT_POSITIONS[i];

            // 1. PlayerSkills row — every attribute set to ~attrTarget with ±2 jitter.
            PlayerSkills skills = new PlayerSkills();
            skills.setPosition(position);
            applyUniformAttributes(skills, attrTarget, rng);
            skills = playerSkillsRepository.save(skills);

            // 2. Human row — links to the skills row and the team.
            Human human = new Human();
            human.setName("TestPlayer-" + teamId + "-" + (i + 1));
            human.setTeamId(teamId);
            human.setSkillsId(skills.getId());
            human.setTypeId(TypeNames.PLAYER_TYPE);
            human.setPosition(position);
            human.setAge(18 + rng.nextInt(15)); // 18-32
            human.setShirtNumber(i + 1);
            human.setFitness(100);
            human.setMorale(50);
            human = humanRepository.save(human);

            // 3. Back-link skills → player id.
            skills.setPlayerId(human.getId());
            playerSkillsRepository.save(skills);

            players.add(human);
        }
        return players;
    }

    /**
     * One-shot helper: create team + squad in a single call. Most fuzz tests
     * use this rather than the two-step API.
     */
    @Transactional
    public Team createSyntheticTeamWithSquad(String name, int reputation, long seed) {
        Team team = createSyntheticTeam(name, reputation);
        generateSyntheticSquad(team.getId(), reputation, DEFAULT_SQUAD_SIZE, seed);
        return team;
    }

    // ==================== private helpers ====================

    /**
     * Set every PlayerSkills attribute to {@code target ± 2}, clamped to
     * [1, 20]. Uniform distribution intentionally — fuzz tests want a clear
     * "this squad is stronger than that one" signal, not realistic spreads.
     */
    private void applyUniformAttributes(PlayerSkills s, int target, Random rng) {
        // Technical (14)
        s.setCorners(jitter(target, rng));
        s.setCrossing(jitter(target, rng));
        s.setDribbling(jitter(target, rng));
        s.setFinishing(jitter(target, rng));
        s.setFirstTouch(jitter(target, rng));
        s.setFreeKick(jitter(target, rng));
        s.setHeading(jitter(target, rng));
        s.setLongShots(jitter(target, rng));
        s.setLongThrows(jitter(target, rng));
        s.setMarking(jitter(target, rng));
        s.setPassing(jitter(target, rng));
        s.setPenaltyTaking(jitter(target, rng));
        s.setTackling(jitter(target, rng));
        s.setTechnique(jitter(target, rng));

        // Mental (14)
        s.setAggression(jitter(target, rng));
        s.setAnticipation(jitter(target, rng));
        s.setBravery(jitter(target, rng));
        s.setComposure(jitter(target, rng));
        s.setConcentration(jitter(target, rng));
        s.setDecisions(jitter(target, rng));
        s.setDetermination(jitter(target, rng));
        s.setFlair(jitter(target, rng));
        s.setLeadership(jitter(target, rng));
        s.setOffTheBall(jitter(target, rng));
        s.setPositioning(jitter(target, rng));
        s.setTeamwork(jitter(target, rng));
        s.setVision(jitter(target, rng));
        s.setWorkRate(jitter(target, rng));

        // Physical (8)
        s.setAcceleration(jitter(target, rng));
        s.setAgility(jitter(target, rng));
        s.setBalance(jitter(target, rng));
        s.setJumpingReach(jitter(target, rng));
        s.setNaturalFitness(jitter(target, rng));
        s.setPace(jitter(target, rng));
        s.setStamina(jitter(target, rng));
        s.setStrength(jitter(target, rng));

        // GK-specific (6) — set only for GKs (others get default 10 from @Column)
        if ("GK".equals(s.getPosition())) {
            s.setHandling(jitter(target, rng));
            s.setReflexes(jitter(target, rng));
            s.setOneOnOnes(jitter(target, rng));
            s.setCommandOfArea(jitter(target, rng));
            s.setKicking(jitter(target, rng));
            s.setThrowing(jitter(target, rng));
        }
    }

    private static int jitter(int target, Random rng) {
        int v = target + rng.nextInt(5) - 2; // [-2, +2]
        return Math.max(1, Math.min(20, v));
    }
}
