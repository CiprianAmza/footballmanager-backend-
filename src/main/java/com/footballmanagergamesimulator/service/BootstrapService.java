package com.footballmanagergamesimulator.service;

import com.footballmanagergamesimulator.controller.TrainingController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.CompetitionTeamInfo;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Stadium;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.model.TeamFacilities;
import com.footballmanagergamesimulator.model.TeamPlayerHistoricalRelation;
import com.footballmanagergamesimulator.model.TrainingSchedule;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.CompetitionTeamInfoRepository;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.StadiumRepository;
import com.footballmanagergamesimulator.repository.TeamFacilitiesRepository;
import com.footballmanagergamesimulator.repository.TeamPlayerHistoricalRelationRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.repository.TrainingScheduleRepository;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Hardcoded first-time data seed: the 17 competitions, the 8 team groups
 * with their facilities, stadium tiers, training schedules, and managers,
 * plus the named special players assigned to their canonical clubs.
 *
 * <p>Invoked exactly once from {@code CompetitionController.initializeRound()}
 * (the {@code @PostConstruct} entry point) when the database has no Round row
 * for id=1. On every subsequent boot the controller just loads the existing
 * round and skips this entirely.
 */
@Service
public class BootstrapService {

    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired private TeamRepository teamRepository;
    @Autowired private HumanRepository humanRepository;
    @Autowired private TeamFacilitiesRepository teamFacilitiesRepository;
    @Autowired private StadiumRepository stadiumRepository;
    @Autowired private TrainingScheduleRepository trainingScheduleRepository;
    @Autowired private PlayerSkillsRepository playerSkillsRepository;
    @Autowired private TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;
    @Autowired private CompositeNameGenerator compositeNameGenerator;
    @Autowired private TacticService tacticService;
    @Autowired private CompetitionService competitionService;
    @Autowired private NationService nationService;
    @Autowired private FaceGenerator faceGenerator;

    /** Run the structural one-time seed in order: competitions → 8 team groups. */
    public void initialization() {
        initializeCompetitions();
        initializeTeams1();
        initializeTeams2();
        initializeTeams3();
        initializeTeams4();
        initializeTeams5();
        initializeTeams6();
        initializeTeams7();
        initializeTeams8();
    }

    private void initializeCompetitions() {
        List<List<Integer>> values = new ArrayList<>(List.of(List.of(1, 1, 1), List.of(1, 2, 2), List.of(3, 1, 1),
                List.of(3, 2, 2), List.of(3, 3, 3), List.of(2, 2, 1), List.of(2, 3, 2), List.of(4, 2, 1), List.of(4, 3, 2),
                List.of(0, 1, 4), List.of(0, 2, 5),
                List.of(5, 1, 1), List.of(5, 2, 2),
                List.of(6, 1, 1), List.of(6, 2, 2),
                List.of(7, 1, 1), List.of(7, 2, 2)));

        List<String> names = new ArrayList<>(List.of("Gallactick Football First League", "Gallactick Football Cup",
                "Khess First League", "Khess Cup", "Khess Second League", "Dong Championship", "Dong Cup", "FootieCup League",
                "FootieCup Cup", "League of Champions", "Stars Cup",
                "Cards League", "Cards Cup", "Literature League", "Literature Cup",
                "Eleven League", "Eleven Cup"));

        for (int i = 0; i < values.size(); i++) {
            Competition competition = new Competition();
            competition.setNationId(values.get(i).get(0));
            competition.setPrizesId(values.get(i).get(1));
            competition.setTypeId(values.get(i).get(2));
            competition.setName(names.get(i));

            competitionRepository.save(competition);
        }
    }

    private void initializeTeams1() {
        List<List<String>> teamNames = List.of(
                List.of("Shadows", "black", "grey", "25"),
                List.of("Ligthnings", "blue", "darkblue", "55"),
                List.of("Xenon", "green", "darkgreen", "35"),
                List.of("Snow Kids", "white", "blue", "65"),
                List.of("Wambas", "yellow", "green", "5"),
                List.of("Technoid", "grey", "green", "70"),
                List.of("Cyclops", "orange", "black", "45"),
                List.of("Red Tigers", "red", "grey", "25"),
                List.of("Akillian", "white", "grey", "35"),
                List.of("Rykers", "orange", "yellow", "60"),
                List.of("Pirates", "blue", "black", "95"),
                List.of("Elektras", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(10000, 5), List.of(9000, 5), List.of(9000, 5),
                List.of(8600, 2), List.of(8000, 4), List.of(7900, 3),
                List.of(7000, 2), List.of(6900, 1), List.of(6000, 1),
                List.of(7000, 2), List.of(6700, 1), List.of(6500, 3));

        List<List<Integer>> facilities = List.of(
                List.of(16, 20, 20), List.of(15, 20, 18), List.of(15, 20, 18),
                List.of(10, 18, 16), List.of(10, 16, 16), List.of(10, 15, 15),
                List.of(10, 12, 14), List.of(10, 14, 13), List.of(10, 12, 12),
                List.of(8, 12, 10), List.of(7, 11, 9), List.of(6, 10, 9));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 0, 1L, 2L);
    }

    private void initializeTeams2() {
        List<List<String>> teamNames = List.of(
                List.of("FC San Marino", "black", "grey", "25"),
                List.of("Tik Tok", "blue", "darkblue", "55"),
                List.of("No Merci", "green", "darkgreen", "35"),
                List.of("Karagandy", "white", "blue", "65"),
                List.of("Krioyv", "yellow", "green", "5"),
                List.of("Korbordi", "grey", "green", "70"),
                List.of("Kavantaly", "orange", "black", "45"),
                List.of("Kaspersky", "red", "grey", "25"),
                List.of("Kadaver", "white", "grey", "35"),
                List.of("Kavi Kan", "orange", "yellow", "60"),
                List.of("Koroga", "blue", "black", "95"),
                List.of("Kugantuna", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(10000, 5), List.of(9000, 5), List.of(9000, 5),
                List.of(8600, 2), List.of(8000, 4), List.of(7900, 3),
                List.of(7000, 2), List.of(6900, 1), List.of(6000, 1),
                List.of(7000, 2), List.of(6700, 1), List.of(6500, 3));

        List<List<Integer>> facilities = List.of(
                List.of(20, 20, 20), List.of(20, 20, 20), List.of(15, 20, 18),
                List.of(10, 18, 16), List.of(10, 16, 16), List.of(10, 15, 15),
                List.of(10, 12, 14), List.of(10, 14, 13), List.of(10, 12, 12),
                List.of(8, 12, 10), List.of(7, 11, 9), List.of(6, 10, 9));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 12, 3L, 4L);
    }

    private void initializeTeams3() {
        List<List<String>> teamNames = List.of(
                List.of("Karyo", "black", "grey", "25"),
                List.of("Korny", "blue", "darkblue", "55"),
                List.of("La Kavardi", "green", "darkgreen", "35"),
                List.of("Kadaveriki", "white", "blue", "65"),
                List.of("Konstenti", "yellow", "green", "5"),
                List.of("Kirokiri", "grey", "green", "70"),
                List.of("Kusparsky", "orange", "black", "45"),
                List.of("Kindonersky", "red", "grey", "25"),
                List.of("Kor Kory", "white", "grey", "35"),
                List.of("Kuvertini", "orange", "yellow", "60"),
                List.of("Kora", "blue", "black", "95"),
                List.of("Kuntuna", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(6000, 5), List.of(5500, 5), List.of(5500, 5),
                List.of(5400, 2), List.of(5300, 4), List.of(5200, 3),
                List.of(5000, 2), List.of(4900, 1), List.of(4800, 1),
                List.of(4300, 2), List.of(4200, 1), List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(7, 4, 1), List.of(6, 3, 4), List.of(5, 5, 5),
                List.of(10, 3, 4), List.of(5, 6, 10), List.of(4, 3, 1),
                List.of(5, 4, 3), List.of(6, 8, 3), List.of(7, 9, 1),
                List.of(8, 7, 4), List.of(7, 5, 5), List.of(6, 4, 3));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 24, 5L, 4L);
    }

    private void initializeTeams4() {
        List<List<String>> teamNames = List.of(
                List.of("Ding Dong", "black", "grey", "25"),
                List.of("Dinamo Kanibali", "blue", "darkblue", "55"),
                List.of("Grobienii", "green", "darkgreen", "35"),
                List.of("Grodienii", "white", "blue", "65"),
                List.of("Artistii", "yellow", "green", "5"),
                List.of("Mumiile", "grey", "green", "70"),
                List.of("Vikingii", "orange", "black", "45"),
                List.of("Vanatorii", "red", "grey", "25"),
                List.of("Faraonii", "white", "grey", "35"),
                List.of("Kuvertini", "orange", "yellow", "60"),
                List.of("Kora", "blue", "black", "95"),
                List.of("Kuntuna", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(6000, 5), List.of(5500, 5), List.of(5500, 5),
                List.of(5400, 2), List.of(5300, 4), List.of(5200, 3),
                List.of(5000, 2), List.of(4900, 1), List.of(4800, 1),
                List.of(4300, 2), List.of(4200, 1), List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(15, 10, 15), List.of(13, 8, 14), List.of(5, 5, 5),
                List.of(10, 3, 4), List.of(5, 6, 10), List.of(4, 3, 1),
                List.of(5, 4, 3), List.of(6, 8, 3), List.of(7, 9, 1),
                List.of(8, 7, 4), List.of(7, 5, 5), List.of(6, 4, 3));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 36, 6L, 7L);
    }

    private void initializeTeams5() {
        List<List<String>> teamNames = List.of(
                List.of("EuroFlava", "red", "white", "25"),
                List.of("Kossack Team", "green", "darkgreen", "55"),
                List.of("Pro Lapad Sport", "red", "darkred", "35"),
                List.of("FC Blue", "white", "blue", "65"),
                List.of("Athletic Sohatu", "yellow", "green", "5"),
                List.of("Tutucea Team", "grey", "green", "70"),
                List.of("FC Arges IV", "orange", "black", "45"),
                List.of("ManCester Sibiu", "red", "grey", "25"),
                List.of("FC Angells", "white", "grey", "35"),
                List.of("Club 16", "orange", "yellow", "60"),
                List.of("FC Spicul Tamaseni", "blue", "black", "95"),
                List.of("Chris Team", "pink", "lila", "9"));

        List<List<Integer>> teamValues = List.of(
                List.of(6000, 5), List.of(5500, 5), List.of(5500, 5),
                List.of(5400, 2), List.of(5300, 4), List.of(5200, 3),
                List.of(5000, 2), List.of(4900, 1), List.of(4800, 1),
                List.of(4300, 2), List.of(4200, 1), List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(15, 10, 15), List.of(13, 8, 14), List.of(5, 5, 5),
                List.of(10, 3, 4), List.of(5, 6, 10), List.of(4, 3, 1),
                List.of(5, 4, 3), List.of(6, 8, 3), List.of(7, 9, 1),
                List.of(8, 7, 4), List.of(7, 5, 5), List.of(6, 4, 3));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 48, 8L, 9L);
    }

    private void initializeTeams6() {
        List<List<String>> teamNames = List.of(
                List.of("Yu Gi Oh", "purple", "gold", "45"),
                List.of("Duel Masters", "red", "black", "30"),
                List.of("Cardisio", "blue", "silver", "60"),
                List.of("Bidaman", "green", "white", "15"),
                List.of("Bay Blade", "orange", "grey", "75"),
                List.of("Pokemon", "yellow", "red", "50"),
                List.of("Dragon Ball Z", "orange", "blue", "20"),
                List.of("Digimon", "cyan", "green", "85"));

        List<List<Integer>> teamValues = List.of(
                List.of(5000, 3), List.of(4800, 4), List.of(4600, 2),
                List.of(4400, 3), List.of(4200, 5), List.of(4000, 2),
                List.of(3800, 1), List.of(3600, 4));

        List<List<Integer>> facilities = List.of(
                List.of(8, 8, 8), List.of(7, 7, 7), List.of(6, 8, 6),
                List.of(7, 6, 5), List.of(5, 7, 6), List.of(6, 5, 7),
                List.of(5, 6, 5), List.of(4, 5, 4));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 60, 12L, 13L);
    }

    private void initializeTeams7() {
        List<List<String>> teamNames = List.of(
                List.of("Toamna Patriarhului", "brown", "gold", "40"),
                List.of("Carabusul de Aur", "gold", "black", "55"),
                List.of("Moara cu Noroc", "green", "brown", "25"),
                List.of("Ion Tara FC", "white", "blue", "70"),
                List.of("Enigma Otiliei", "purple", "white", "35"),
                List.of("Harap Alb FC", "white", "red", "10"),
                List.of("Morometii", "darkgreen", "grey", "80"),
                List.of("Baltagul FC", "brown", "red", "45"),
                List.of("Ultima Noapte", "darkblue", "white", "60"),
                List.of("Floare Albastra", "blue", "lightblue", "15"),
                List.of("Rascoala FC", "red", "orange", "90"),
                List.of("Padurea Spinzuratilor", "darkgreen", "black", "30"),
                List.of("Maitreyi FC", "orange", "gold", "50"),
                List.of("Fram Ursul Polar", "white", "cyan", "65"),
                List.of("Don Quijote FC", "red", "yellow", "20"),
                List.of("Gatsby United", "gold", "white", "75"),
                List.of("Moby Dick FC", "blue", "grey", "5"),
                List.of("Sherlock FC", "brown", "darkbrown", "85"));

        List<List<Integer>> teamValues = List.of(
                List.of(4500, 3), List.of(4300, 4), List.of(4100, 2),
                List.of(4000, 3), List.of(3900, 5), List.of(3800, 2),
                List.of(3700, 1), List.of(3600, 4), List.of(3500, 3),
                List.of(3400, 2), List.of(3300, 1), List.of(3200, 3),
                List.of(3100, 4), List.of(3000, 2), List.of(2900, 1),
                List.of(2800, 3), List.of(2700, 2), List.of(2600, 1));

        List<List<Integer>> facilities = List.of(
                List.of(6, 6, 6), List.of(5, 7, 5), List.of(6, 5, 4),
                List.of(5, 6, 5), List.of(4, 5, 6), List.of(5, 4, 3),
                List.of(4, 5, 4), List.of(3, 4, 5), List.of(5, 3, 4),
                List.of(4, 4, 3), List.of(3, 5, 3), List.of(4, 3, 4),
                List.of(3, 4, 3), List.of(3, 3, 4), List.of(4, 3, 3),
                List.of(3, 3, 3), List.of(3, 2, 3), List.of(2, 3, 2));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 68, 14L, 15L);
    }

    private void initializeTeams8() {
        List<List<String>> teamNames = List.of(
                List.of("Inazuma Japan", "blue", "yellow", "40"),
                List.of("Zero", "black", "red", "55"),
                List.of("FF Raimon", "orange", "blue", "30"),
                List.of("Royal Academy", "purple", "white", "70"),
                List.of("Zeus", "gold", "white", "45"),
                List.of("Occult", "darkgreen", "black", "60"),
                List.of("Kirkwood", "red", "white", "35"),
                List.of("Gemini Storm", "cyan", "blue", "50"),
                List.of("Epsilon", "white", "purple", "25"),
                List.of("Diamond Dust", "lightblue", "white", "65"),
                List.of("Prominence", "red", "orange", "15"),
                List.of("The Genesis", "black", "gold", "80"),
                List.of("Knights of Queen", "white", "red", "90"),
                List.of("Orpheus", "blue", "white", "20"),
                List.of("Fire Dragon", "red", "yellow", "75"),
                List.of("Little Gigant", "green", "yellow", "10"),
                List.of("Unicorn", "blue", "red", "85"),
                List.of("Desert Lion", "gold", "brown", "95"),
                List.of("Big Waves", "cyan", "white", "5"),
                List.of("The Empire", "grey", "black", "50"));

        List<List<Integer>> teamValues = List.of(
                List.of(10000, 5), List.of(8000, 4), List.of(7800, 3),
                List.of(7600, 5), List.of(7400, 4), List.of(7200, 2),
                List.of(7000, 3), List.of(6800, 4), List.of(6600, 5),
                List.of(6400, 2), List.of(6200, 1), List.of(6000, 5),
                List.of(5800, 3), List.of(5600, 4), List.of(5400, 1),
                List.of(5200, 2), List.of(5000, 3), List.of(4800, 1),
                List.of(4600, 2), List.of(4400, 4));

        List<List<Integer>> facilities = List.of(
                List.of(14, 18, 17), List.of(13, 17, 16), List.of(13, 16, 15),
                List.of(12, 15, 14), List.of(12, 14, 13), List.of(11, 14, 13),
                List.of(11, 13, 12), List.of(10, 12, 11), List.of(10, 11, 11),
                List.of(9, 11, 10), List.of(9, 10, 10), List.of(8, 10, 9),
                List.of(8, 9, 9), List.of(7, 9, 8), List.of(7, 8, 8),
                List.of(6, 8, 7), List.of(6, 7, 7), List.of(5, 7, 6),
                List.of(5, 6, 6), List.of(4, 5, 5));

        createTeamsAndCompetitions(teamNames, teamValues, facilities, 86, 16L, 17L);
    }

    /**
     * Seed the named players after the ordinary squads have been generated. This ordering lets the
     * players receive complete attributes, contracts, faces, history and non-conflicting shirt numbers
     * instead of becoming partial Human rows that only happen to carry a high headline rating.
     */
    public void initializeSpecialPlayers() {
        List<SpecialPlayerSeed> seeds = List.of(
                new SpecialPlayerSeed("Tik Tok", "Kvekrpur", "ST", 20, 300),
                new SpecialPlayerSeed("Tik Tok", "Dostoievski", "ST", 15, 300),
                new SpecialPlayerSeed("Tik Tok", "Kabutov", "DM", 15, 300),
                new SpecialPlayerSeed("Tik Tok", "Mozart", "GK", 15, 300),
                new SpecialPlayerSeed("FC San Marino", "Shakespeare", "ST", 15, 300),
                new SpecialPlayerSeed("FC San Marino", "Beethoven", "GK", 15, 300),
                new SpecialPlayerSeed("FC San Marino", "Rampardos", "MC", 15, 300),
                new SpecialPlayerSeed("Inazuma Japan", "Saviola", "AMC", 15, 300),
                new SpecialPlayerSeed("Inazuma Japan", "Umbreon", "AML", 15, 280),
                new SpecialPlayerSeed("Inazuma Japan", "Itexoa", "MC", 15, 280));

        Map<String, Team> teamsByName = teamRepository.findAll().stream()
                .collect(Collectors.toMap(Team::getName, team -> team, (first, duplicate) -> first));
        Map<Long, List<Human>> additionsByTeam = new LinkedHashMap<>();

        for (SpecialPlayerSeed seed : seeds) {
            Team team = teamsByName.get(seed.teamName());
            if (team == null) {
                throw new IllegalStateException("Cannot seed " + seed.playerName()
                        + ": team not found: " + seed.teamName());
            }

            Random playerRandom = new Random(seed.playerName().hashCode());
            Human player = new Human();
            player.setTeamId(team.getId());
            player.setName(seed.playerName());
            player.setTypeId(TypeNames.PLAYER_TYPE);
            player.setPosition(seed.position());
            player.setAge(seed.age());
            player.setSeasonCreated(1);
            player.setCurrentStatus(seed.age() <= 18 ? "Junior" : "Senior");
            player.setMorale(80);
            player.setFitness(80);
            player.setRating(seed.rating());
            player.setCurrentAbility((int) seed.rating());
            player.setPotentialAbility((int) seed.rating() + 50);
            player.setBestEverRating(seed.rating());
            player.setSeasonOfBestEverRating(1);
            HumanService.generatePhysicalProfile(player, playerRandom);
            player.setTransferValue(TransferValueCalculator.calculate(
                    seed.age(), seed.position(), seed.rating()));
            player.setContractEndSeason(6);
            player.setWage(WageService.baseWage(seed.rating()));
            player.setReleaseClause(player.getTransferValue() * 2);
            player.setWillNeverLeave(true);

            player = humanRepository.save(player);
            faceGenerator.assignFace(player, nationService.nationIdForTeam(team.getId()));

            PlayerSkills skills = new PlayerSkills();
            skills.setPlayerId(player.getId());
            skills.setPosition(seed.position());
            competitionService.generateSkills(skills, seed.rating(), playerRandom);
            PlayerSkillsService.calibrateOverallRating(skills, seed.rating());
            playerSkillsRepository.save(skills);

            TeamPlayerHistoricalRelation relation = new TeamPlayerHistoricalRelation();
            relation.setPlayerId(player.getId());
            relation.setTeamId(team.getId());
            relation.setSeasonNumber(1);
            relation.setRating(seed.rating());
            teamPlayerHistoricalRelationRepository.save(relation);

            additionsByTeam.computeIfAbsent(team.getId(), ignored -> new ArrayList<>()).add(player);
        }

        // Re-number the complete affected squads once, after all named players are present.
        for (Map.Entry<Long, List<Human>> entry : additionsByTeam.entrySet()) {
            List<Human> completeSquad = humanRepository
                    .findAllByTeamIdAndTypeId(entry.getKey(), TypeNames.PLAYER_TYPE).stream()
                    .filter(player -> !player.isRetired())
                    .collect(Collectors.toCollection(ArrayList::new));
            for (Human addition : entry.getValue()) {
                if (completeSquad.stream().noneMatch(player -> player.getId() == addition.getId())) {
                    completeSquad.add(addition);
                }
            }
            HumanService.assignShirtNumbers(completeSquad);
            humanRepository.saveAll(completeSquad);
        }
    }

    private record SpecialPlayerSeed(String teamName, String playerName, String position,
                                     int age, double rating) {}

    /**
     * Backfills the editor flag for warm saves and old pre-built snapshots. The
     * names below are the hand-authored canonical players created by this seed.
     */
    public int ensureSpecialPlayersNeverLeave() {
        Set<String> protectedNames = Set.of(
                "Kvekrpur", "Dostoievski", "Kabutov", "Mozart",
                "Shakespeare", "Beethoven", "Rampardos",
                "Saviola", "Umbreon", "Itexoa");
        List<Human> changed = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> protectedNames.contains(player.getName()))
                .filter(player -> !player.isWillNeverLeave())
                .peek(player -> {
                    player.setWillNeverLeave(true);
                    player.setWantsTransfer(false);
                    player.setPreContractTeamId(0);
                })
                .toList();
        if (!changed.isEmpty()) humanRepository.saveAll(changed);
        return changed.size();
    }

    private void createTeamsAndCompetitions(List<List<String>> teamNames, List<List<Integer>> teamValues,
                                            List<List<Integer>> facilities, int addedModulo, long leagueId, long cupId) {
        for (int i = 0; i < teamNames.size(); i++) {

            Team team = new Team();
            team.setId(i + addedModulo + 1);
            team.setName(teamNames.get(i).get(0));
            team.setColor1(teamNames.get(i).get(1));
            team.setColor2(teamNames.get(i).get(2));
            team.setBorder(teamNames.get(i).get(3));
            team.setReputation(teamValues.get(i).get(0));
            team.setStrategy((long) teamValues.get(i).get(1));
            team.setCompetitionId(leagueId);
            team.setTransferBudget(0L);
            team.setTotalFinances(0L);
            // Stadium capacity based on reputation: 10,000 base + reputation * 8
            int stadiumCapacity = 10_000 + teamValues.get(i).get(0) * 8;
            team.setStadiumCapacity(stadiumCapacity);
            team.setStadiumName(teamNames.get(i).get(0) + " Stadium");
            team.setBoardConfidence(50);
            teamRepository.save(team);

            CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
            competitionTeamInfo.setSeasonNumber(1);
            competitionTeamInfo.setRound(1);
            competitionTeamInfo.setCompetitionId(leagueId);
            competitionTeamInfo.setTeamId(i + addedModulo + 1);
            competitionTeamInfoRepository.save(competitionTeamInfo);

            int numTeams = teamNames.size();
            int numCupRounds = (int) Math.ceil(Math.log(numTeams) / Math.log(2));
            int numByes = (int) Math.pow(2, numCupRounds) - numTeams;

            competitionTeamInfo = new CompetitionTeamInfo();
            competitionTeamInfo.setSeasonNumber(1);
            competitionTeamInfo.setRound(i < numByes ? 2 : 1);
            competitionTeamInfo.setCompetitionId(cupId);
            competitionTeamInfo.setTeamId(i + addedModulo + 1);
            competitionTeamInfoRepository.save(competitionTeamInfo);

            TeamFacilities teamFacilities = new TeamFacilities();
            teamFacilities.setTeamId(i + addedModulo + 1);
            teamFacilities.setYouthAcademyLevel(facilities.get(i).get(0));
            teamFacilities.setYouthTrainingLevel(facilities.get(i).get(1));
            teamFacilities.setSeniorTrainingLevel(facilities.get(i).get(2));
            teamFacilities.setScoutingLevel(Math.min(20, Math.max(1, (facilities.get(i).get(0) + facilities.get(i).get(1)) / 2)));
            teamFacilitiesRepository.save(teamFacilities);

            Stadium stadium = new Stadium();
            stadium.setTeamId(i + addedModulo + 1);
            stadium.setStadiumName(teamNames.get(i).get(0) + " Stadium");
            stadium.setCapacity(stadiumCapacity);
            int rep = teamValues.get(i).get(0);
            if (rep >= 9000) {
                stadium.setVipBoxesLevel(5);
                stadium.setCateringLevel(4);
                stadium.setFanShopLevel(4);
                stadium.setFastFoodLevel(3);
                stadium.setHeadquartersLevel(5);
                stadium.setTrainingPitchLevel(5);
                stadium.setParkingLevel(4);
            } else if (rep >= 7000) {
                stadium.setVipBoxesLevel(3);
                stadium.setCateringLevel(2);
                stadium.setFanShopLevel(2);
                stadium.setFastFoodLevel(2);
                stadium.setHeadquartersLevel(3);
                stadium.setTrainingPitchLevel(3);
                stadium.setParkingLevel(2);
            } else if (rep >= 5000) {
                stadium.setVipBoxesLevel(1);
                stadium.setCateringLevel(1);
                stadium.setFanShopLevel(1);
                stadium.setFastFoodLevel(1);
                stadium.setHeadquartersLevel(2);
                stadium.setTrainingPitchLevel(2);
                stadium.setParkingLevel(1);
            }
            stadiumRepository.save(stadium);

            long currentTeamId = i + addedModulo + 1;
            List<TrainingSchedule> defaultSchedule = TrainingController.buildDefaultSchedule(currentTeamId);
            trainingScheduleRepository.saveAll(defaultSchedule);

            Human manager = new Human();
            manager.setName(compositeNameGenerator.generateName(1L));
            manager.setTypeId(TypeNames.MANAGER_TYPE);
            manager.setTeamId(currentTeamId);
            manager.setManagerReputation(team.getReputation() / 3);
            manager.setAge(35 + new Random().nextInt(20)); // 35-54
            manager.setSeasonCreated(1);
            manager.setMorale(70D);
            manager.setFitness(100D);
            manager.setRating(0);
            String[] kit = tacticService.buildManagerTacticKit((int) manager.getRating(), new Random());
            manager.setTacticStyle(kit[0]);
            manager.setKnownTactics(kit[1]);
            humanRepository.save(manager);
        }
    }
}
