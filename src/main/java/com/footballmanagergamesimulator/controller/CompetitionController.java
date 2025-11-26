package com.footballmanagergamesimulator.controller;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.frontend.FormationData;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.CompositeNameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.CompetitionService;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.util.*;
import jakarta.annotation.PostConstruct;
import org.apache.commons.math3.distribution.EnumeratedDistribution;
import org.apache.commons.math3.util.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/competition")
@CrossOrigin(origins = "*")
public class CompetitionController {

    @Autowired
    private TeamRepository teamRepository;
    @Autowired
    private HumanRepository humanRepository;
    @Autowired
    private TeamCompetitionDetailRepository teamCompetitionDetailRepository;
    @Autowired
    private CompetitionTeamInfoRepository competitionTeamInfoRepository;
    @Autowired
    private CompetitionTeamInfoDetailRepository competitionTeamInfoDetailRepository;
    @Autowired
    private CompetitionTeamInfoMatchRepository competitionTeamInfoMatchRepository;
    @Autowired
    RoundRobin roundRobin;
    @Autowired
    CompetitionHistoryRepository competitionHistoryRepository;
    @Autowired
    CompetitionRepository competitionRepository;
    @Autowired
    HumanService _humanService;
    @Autowired
    TeamFacilitiesRepository _teamFacilitiesRepository;
    @Autowired
    CompositeTransferStrategy _compositeTransferStrategy;
    @Autowired
    TransferRepository transferRepository;
    @Autowired
    TacticService tacticService;
    @Autowired
    RoundRepository roundRepository;
    @Autowired
    PlayerSkillsRepository playerSkillsRepository;
    @Autowired
    TacticController tacticController;
    @Autowired
    PersonalizedTacticRepository personalizedTacticRepository;
    @Autowired
    ScorerRepository scorerRepository;
    @Autowired
    ScorerLeaderboardRepository scorerLeaderboardRepository;
    @Autowired
    CompetitionService competitionService;
    @Autowired
    CompositeNameGenerator compositeNameGenerator;
    @Autowired
    TeamPlayerHistoricalRelationRepository teamPlayerHistoricalRelationRepository;

    private final ObjectMapper objectMapper = new ObjectMapper(); // <--- Ai nevoie de asta


    Round round;

    @PostConstruct
    public void initializeRound() {

        round = new Round();
        round.setId(1L);
        round.setSeason(1);
        round.setRound(1);
        roundRepository.save(round);
    }

    @GetMapping("/getCurrentSeason")
    public String getCurrentSeason() {

        return String.valueOf(round.getSeason());
    }

    @GetMapping("/getCurrentRound")
    public String getCurrentRound() {

        return String.valueOf(round.getRound());
    }

    @GetMapping("/play")
    @Scheduled(fixedDelay = 3000L)
    public void play() {

        if (round.getRound() == 1 && round.getSeason() == 1) {

            competitionTeamInfoRepository.deleteAll();
            initialization();
        }

        List<Long> teamIds = getAllTeams();

        if (round.getRound() > 50) {

            // GF
            Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1); // leagues
            Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3); // second league
            leagueCompetitionIds.addAll(secondLeagueCompetitionIds);

            List<TeamCompetitionDetail> teamCompetitionDetails = teamCompetitionDetailRepository.findAll();
            for (Long id: leagueCompetitionIds) {
                int finalId = Math.toIntExact(id);
                List<TeamCompetitionDetail> teamCompetitionDetailList = teamCompetitionDetails.stream()
                        .filter(detail -> detail.getCompetitionId() == finalId)
                        .sorted((o1, o2) -> {
                            if (o1.getPoints() != o2.getPoints())
                                return o1.getPoints() < o2.getPoints() ? 1 : -1;
                            if (o1.getGoalDifference() != o2.getGoalDifference())
                                return o1.getGoalDifference() < o2.getGoalDifference() ? 1 : -1;
                            if (o1.getGoalsFor() != o2.getGoalsFor())
                                return o1.getGoalsFor() < o2.getGoalsFor() ? 1 : -1;
                            return 0;
                        }).toList();

                int index = 1;

                for (TeamCompetitionDetail teamCompetitionDetail : teamCompetitionDetailList) {

                    CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
                    if (id == 3L && index >= 11) // relegation from First League to Second League for Kess
                        competitionTeamInfo.setCompetitionId(5L);
                    else if (id == 5L && index <= 2)
                        competitionTeamInfo.setCompetitionId(3L);
                    else
                        competitionTeamInfo.setCompetitionId(id);

                    competitionTeamInfo.setSeasonNumber(Long.parseLong(getCurrentSeason()) + 1);
                    competitionTeamInfo.setRound(1L);
                    competitionTeamInfo.setTeamId(teamCompetitionDetail.getTeamId());

                    competitionTeamInfoRepository.save(competitionTeamInfo);

                    CompetitionTeamInfo competitionTeamInfoCup = new CompetitionTeamInfo();
                    competitionTeamInfoCup.setCompetitionId(Math.min(id + 1, 4));
                    competitionTeamInfoCup.setSeasonNumber(Long.parseLong(getCurrentSeason()) + 1);
                    competitionTeamInfoCup.setRound(index <= 4 ? 2L : 1L);
                    competitionTeamInfoCup.setTeamId(teamCompetitionDetail.getTeamId());

                    competitionTeamInfoRepository.save(competitionTeamInfoCup);

                    index++;
                }
            }

            // transfer market

            List<PlayerTransferView> playersForTransferMarket = new ArrayList<>();
            for (Long teamId : teamIds) {

                Team team = teamRepository.findById(teamId).orElse(new Team());
                playersForTransferMarket.addAll(_compositeTransferStrategy.playersToSell(team, humanRepository, getMinimumPositionNeeded()));
            }

            HashMap<String, List<PlayerTransferView>> transferMarket = new HashMap<>();
            for (PlayerTransferView playerTransferView : playersForTransferMarket) {
                if (transferMarket.containsKey(playerTransferView.getPosition()))
                    transferMarket.get(playerTransferView.getPosition()).add(playerTransferView);
                else
                    transferMarket.put(playerTransferView.getPosition(), new ArrayList<>(List.of(playerTransferView)));
            }

            Map<PlayerTransferView, List<BuyPlanTransferView>> buyPlan = new HashMap<>();
            for (Long teamId : teamIds) {

                Team team = teamRepository.findById(teamId).orElse(new Team());
                BuyPlanTransferView buyPlanTransferView = _compositeTransferStrategy.playersToBuy(team, humanRepository, getMaximumPositionAllowed());

                if (buyPlanTransferView == null)
                    continue;

                for (TransferPlayer clubPlan : buyPlanTransferView.getPositions()) {

                    List<PlayerTransferView> playersInMarket = transferMarket.get(clubPlan.getPosition());
                    if (playersInMarket == null) continue;
                    for (PlayerTransferView player : playersInMarket) {

                        if (canBeTransfered(player, buyPlanTransferView, clubPlan)) {
                            if (buyPlan.containsKey(player))
                                buyPlan.get(player).add(buyPlanTransferView);
                            else {
                                List<BuyPlanTransferView> buyPlanTransferViews = new ArrayList<>();
                                buyPlanTransferViews.add(buyPlanTransferView);
                                buyPlan.put(player, buyPlanTransferViews);
                            }
                        }
                    }
                }
            }

            HashMap<PlayerTransferView, BuyPlanTransferView> playerTransfered = new HashMap<>();
            List<Transfer> transfers = new ArrayList<>();
            for (Map.Entry<PlayerTransferView, List<BuyPlanTransferView>> pair : buyPlan.entrySet()) {

                if (pair.getValue().size() == 1) {
                    // we have only 1 option, so we have a winner
                    playerTransfered.put(pair.getKey(), pair.getValue().get(0));
                } else {
                    // TODO different task
                    // we need to apply the logic based on the player personality
                    // personalities:
                    /*
                    1. choosing a club by the bigger wage
                    2. choosing the club with the bigger reputation
                    3. choosing the club playing at a higher continental level
                    4. choosing the club where he could have a higher chance to play / be in first eleven
                     */

                    Collections.shuffle(pair.getValue());
                    playerTransfered.put(pair.getKey(), pair.getValue().get(0));
                }
                PlayerTransferView playerTransferView = pair.getKey();
                BuyPlanTransferView buyPlanTransferView = playerTransfered.get(playerTransferView);

                Team sellTeam = teamRepository.findById(playerTransferView.getTeamId()).get();
                Team buyTeam = teamRepository.findById(buyPlanTransferView.getTeamId()).get();

                Human human = humanRepository.findById(playerTransferView.getPlayerId()).get();
                human.setTeamId(buyTeam.getId());
                humanRepository.save(human);

                Transfer transfer = new Transfer();
                transfer.setPlayerId(human.getId());
                transfer.setPlayerName(human.getName());
                transfer.setPlayerTransferValue((long) playerTransferView.getRating());
                transfer.setSellTeamId(sellTeam.getId());
                transfer.setSellTeamName(sellTeam.getName());
                transfer.setBuyTeamId(buyTeam.getId());
                transfer.setBuyTeamName(buyTeam.getName());
                transfer.setRating(human.getRating());
                transfer.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                transfer.setPlayerAge(human.getAge());

                transferRepository.save(transfer);
                transfers.add(transfer);
            }

            transfers.sort((o1, o2) -> {
                if (o1.getRating() == o2.getRating())
                    return 0;
                return o1.getRating() > o2.getRating() ? 1 : -1;
            });

            // save historical values
            Set<Long> competitions = competitionRepository.findAll()
                    .stream()
                    .mapToLong(Competition::getId)
                    .boxed()
                    .collect(Collectors.toSet());

            for (Long competitionId : competitions)
                this.saveHistoricalValues(competitionId, round.getSeason());
            this.saveAllPlayerTeamHistoricalRelations(round.getSeason());

            // reset values
            this.resetCompetitionData();
            this.removeCompetitionData(round.getSeason() + 1);
            this.addImprovementToOverachievers();

            round.setRound(1);
            round.setSeason(round.getSeason() + 1);
            roundRepository.save(round);

            // add 1 year for each player
            _humanService.addOneYearToAge();
            _humanService.retirePlayers();

            personalizedTacticRepository.deleteAll(); // remove old tactics, as some player may retire

            for (Long teamId : teamIds) {
                TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
                if (teamFacilities != null)
                    _humanService.addRegens(teamFacilities, teamId);
            }

            for (long teamId : teamIds) {
                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
                for (Human human : players) {
                    long seasonCreated = human.getSeasonCreated();
                    if (round.getSeason() - seasonCreated <= 2 && seasonCreated != 1L)
                        human.setCurrentStatus("Junior");
                    else if (round.getSeason() - seasonCreated <= 6 && seasonCreated != 1L)
                        human.setCurrentStatus("Intermediate");
                    else
                        human.setCurrentStatus("Senior");
                    human.setTransferValue(calculateTransferValue(human.getAge(), human.getPosition(), human.getRating()));
                    humanRepository.save(human);
                }
            }
        }

        if (round.getRound() == 1) {

            Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1); // leagues
            Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3); // second leagues
            leagueCompetitionIds.addAll(secondLeagueCompetitionIds);

            for (Long competitionId : leagueCompetitionIds)
                this.getFixturesForRound(String.valueOf(competitionId), "1");

            if (round.getSeason() == 1) {
                List<Team> teams = teamRepository.findAll();
                Random random = new Random();
                for (Team team : teams) {
                    TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(team.getId());
                    int nrPlayers = 22;
                    for (int i = 0; i < nrPlayers; i++) {
                        String name = compositeNameGenerator.generateName(team.getCompetitionId());
                        Human player = new Human();
                        player.setTeamId(team.getId());
                        player.setName(name);
                        player.setTypeId(TypeNames.PLAYER_TYPE);
                        if (i < 2)
                            player.setPosition("GK");
                        else if (i < 4)
                            player.setPosition("DL");
                        else if (i < 6)
                            player.setPosition("DR");
                        else if (i < 10)
                            player.setPosition("DC");
                        else if (i < 12)
                            player.setPosition("ML");
                        else if (i < 14)
                            player.setPosition("MR");
                        else if (i < 18)
                            player.setPosition("MC");
                        else
                            player.setPosition("ST");
                        player.setAge(random.nextInt(23, 30));
                        player.setSeasonCreated(1L);
                        player.setCurrentStatus("Senior");
                        player.setMorale(100);

                        int reputation = 100;
                        if (teamFacilities != null)
                            reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
                        player.setRating(random.nextInt(Math.max(10, reputation - 20), Math.max(11, reputation + 20)));
                        player.setTransferValue(calculateTransferValue(player.getAge(), player.getPosition(), player.getRating()));

                        player = humanRepository.save(player);

                        // save TeamPlayerHistoricalRelation for season 1
                        TeamPlayerHistoricalRelation teamPlayerHistoricalRelation = new TeamPlayerHistoricalRelation();
                        teamPlayerHistoricalRelation.setPlayerId(player.getId());
                        teamPlayerHistoricalRelation.setTeamId(team.getId());
                        teamPlayerHistoricalRelation.setSeasonNumber(1);
                        teamPlayerHistoricalRelation.setRating(player.getRating());
                        teamPlayerHistoricalRelationRepository.save(teamPlayerHistoricalRelation);

                        PlayerSkills playerSkills = new PlayerSkills();
                        playerSkills.setPlayerId(player.getId());
                        playerSkills.setPosition(player.getPosition());
                        competitionService.generateSkills(playerSkills, player.getRating());

                        playerSkillsRepository.save(playerSkills);
                    }

                    // create manager for each team
                    Human manager = new Human();
                    manager.setAge(random.nextInt(35, 70));
                    manager.setName(compositeNameGenerator.generateName(team.getCompetitionId()));
                    manager.setTeamId(team.getId());

                    int reputation = 100;
                    if (teamFacilities != null)
                        reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
                    manager.setRating(random.nextInt(reputation - 20, reputation + 20));

                    manager.setPosition("Manager");
                    manager.setSeasonCreated(1L);
                    manager.setTypeId(TypeNames.MANAGER_TYPE);
                    List<String> tactics = List.of("442", "433", "343", "352", "451");

                    manager.setTacticStyle(tactics.get(random.nextInt(0, tactics.size())));
                    humanRepository.save(manager);
                }
            }

            // initiate all Scorers
            List<Team> allTeams = teamRepository.findAll();
            for (Team team: allTeams) {
                List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
                List<CompetitionTeamInfo> competitions = competitionTeamInfoRepository.findAllByTeamIdAndSeasonNumber(team.getId(), round.getSeason());

                for (Human human: allPlayers) {

                    for (CompetitionTeamInfo competitionTeamInfo: competitions) {
                        Scorer scorer = new Scorer();
                        scorer.setPlayerId(human.getId());
                        scorer.setSeasonNumber((int) round.getSeason());
                        scorer.setTeamId(team.getId());
                        scorer.setOpponentTeamId(-1);
                        scorer.setPosition(human.getPosition());
                        scorer.setTeamScore(-1);
                        scorer.setOpponentScore(-1);
                        scorer.setCompetitionId(competitionTeamInfo.getCompetitionId());
                        scorer.setCompetitionTypeId((int) competitionRepository.findTypeIdById(competitionTeamInfo.getCompetitionId()));
                        scorer.setTeamName(team.getName());
                        scorer.setOpponentTeamName(null);
                        scorer.setCompetitionName(competitionRepository.findNameById(competitionTeamInfo.getCompetitionId()));
                        scorer.setRating(human.getRating());
                        scorer.setGoals(0);

                        scorerRepository.save(scorer);
                    }

                    if (scorerLeaderboardRepository.findByPlayerId(human.getId()).isEmpty()) {

                        ScorerLeaderboardEntry scorerLeaderboardEntry = new ScorerLeaderboardEntry();
                        scorerLeaderboardEntry.setPlayerId(human.getId());
                        scorerLeaderboardEntry.setAge(human.getAge());
                        scorerLeaderboardEntry.setName(human.getName());
                        scorerLeaderboardEntry.setGoals(0);
                        scorerLeaderboardEntry.setMatches(0);
                        scorerLeaderboardEntry.setCurrentRating(human.getRating());
                        scorerLeaderboardEntry.setBestEverRating(human.getRating());
                        scorerLeaderboardEntry.setSeasonOfBestEverRating((int) round.getSeason());
                        scorerLeaderboardEntry.setPosition(human.getPosition());
                        scorerLeaderboardEntry.setTeamId(human.getTeamId());
                        scorerLeaderboardEntry.setTeamName(team.getName());

                        scorerLeaderboardRepository.save(scorerLeaderboardEntry);
                    }

                    // reset stats for current season, as it just started
                    Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(human.getId());
                    ScorerLeaderboardEntry scorerLeaderboardEntry = resetCurrentSeasonStats(optionalScorerLeaderboardEntry);
                    scorerLeaderboardRepository.save(scorerLeaderboardEntry);
                }
            }
        }

        if (round.getRound() % 3 == 0) {
            for (long teamId : teamIds) {
                TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
                for (Human player : players) {
                    player = _humanService.trainPlayer(player, teamFacilities, Integer.parseInt(getCurrentSeason()));
                    humanRepository.save(player);
                }
            }
        }

        Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1); // leagues
        Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3); // second leagues
        Set<Long> cupCompetitionIds = getCompetitionIdsByCompetitionType(2); // cup



        for (Long competitionId : leagueCompetitionIds)
            this.simulateRound(String.valueOf(competitionId), round.getRound() - 1 + "");
        for (Long competitionId : secondLeagueCompetitionIds)
            this.simulateRound(String.valueOf(competitionId), round.getRound() - 1 + "");

        for (Long id : cupCompetitionIds) {

            String competitionId = String.valueOf(id);

            if (round.getRound() == 5) {
                this.getFixturesForRound(competitionId, "1");
                this.simulateRound(competitionId, "1");
            }
            if (round.getRound() == 10) {
                this.getFixturesForRound(competitionId, "2");
                this.simulateRound(competitionId, "2");
            }
            if (round.getRound() == 20) {
                this.getFixturesForRound(competitionId, "3");
                this.simulateRound(competitionId, "3");
            }
            if (round.getRound() == 35) {
                this.getFixturesForRound(competitionId, "4");
                this.simulateRound(competitionId, "4");
            }

        }
        round.setRound(round.getRound() + 1);
        roundRepository.save(round);
    }

    private static ScorerLeaderboardEntry resetCurrentSeasonStats(Optional<ScorerLeaderboardEntry> optionalScorerLeaderboardEntry) {

        ScorerLeaderboardEntry scorerLeaderboardEntry = optionalScorerLeaderboardEntry.get();
        scorerLeaderboardEntry.setCurrentSeasonGames(0);
        scorerLeaderboardEntry.setCurrentSeasonGoals(0);
        scorerLeaderboardEntry.setCurrentSeasonLeagueGames(0);
        scorerLeaderboardEntry.setCurrentSeasonLeagueGoals(0);
        scorerLeaderboardEntry.setCurrentSeasonCupGames(0);
        scorerLeaderboardEntry.setCurrentSeasonCupGoals(0);
        scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGames(0);
        scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGoals(0);
        return scorerLeaderboardEntry;
    }

    private void addImprovementToOverachievers() { // todo aici

        System.out.println("Starting rating adjusting for season " + getCurrentSeason());
        Random random = new Random();
        List<ScorerLeaderboardEntry> allEntries = scorerLeaderboardRepository.findAll();

        for (ScorerLeaderboardEntry entry: allEntries) {

            if (entry.getCurrentSeasonGames() < 20) continue; // play at least half of the games

            double ratio = 0D;
            if (entry.getCurrentSeasonLeagueGames() > 0 && entry.getCurrentSeasonLeagueGoals() > 0) {
                ratio = (double) entry.getCurrentSeasonLeagueGoals() / entry.getCurrentSeasonLeagueGames();
            } else if (entry.getCurrentSeasonSecondLeagueGames() > 0 && entry.getCurrentSeasonSecondLeagueGoals() > 0){
                ratio = (double) entry.getCurrentSeasonSecondLeagueGoals() / entry.getCurrentSeasonSecondLeagueGames();
            }

            int ratingIncrease = 0;
            if (ratio >= 1.0D) { // amazing ratio, should increase rating by far
                ratingIncrease = random.nextInt(15, 20);
            } else if (ratio >= 0.75) {
                ratingIncrease = random.nextInt(10, 15);
            } else if (ratio >= 0.5) {
                ratingIncrease = random.nextInt(7, 10);
            } else if (ratio >= 0.4) {
                ratingIncrease = random.nextInt(3, 7);
            } else {
                continue;
            }
            if (entry.getLeagueGoals() < entry.getSecondLeagueGoals()) { // this means that the goals were mainly scored in the second league, currently players can not be transferred during a season
                ratingIncrease /= 2; // decrease rating, as overachieving in the second league is not that impressive
            }

            Optional<Human> human = humanRepository.findById(entry.getPlayerId());
            if (human.isPresent()){
                Human player = human.get();
                System.out.println("Player " + player.getName() + " from team " + entry.getTeamName() + " had rating increased from " + player.getRating() + " to " + (player.getRating() + ratingIncrease) + " because of ratio of " + ratio);
                player.setRating(player.getRating() + ratingIncrease);
                humanRepository.save(player);

                entry.setCurrentRating(player.getRating());
                if (entry.getCurrentRating() > entry.getBestEverRating()) {
                    entry.setBestEverRating(entry.getCurrentRating());
                    entry.setSeasonOfBestEverRating((int) round.getSeason());
                }
                scorerLeaderboardRepository.save(entry);
            }
        }
    }

    private void saveAllPlayerTeamHistoricalRelations(long seasonNumber) {

        Set<Long> teamIds = new HashSet<>();
        List<TeamCompetitionDetail> teams = teamCompetitionDetailRepository.findAll();

        for (TeamCompetitionDetail teamCompetitionDetail: teams) {

            teamIds.add(teamCompetitionDetail.getTeamId());
        }

        for (Long teamId: teamIds) {

            List<Human> allTeamPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
            for (Human player: allTeamPlayers) {
                TeamPlayerHistoricalRelation teamPlayerHistoricalRelation = new TeamPlayerHistoricalRelation();
                teamPlayerHistoricalRelation.setPlayerId(player.getId());
                teamPlayerHistoricalRelation.setTeamId(teamId);
                teamPlayerHistoricalRelation.setSeasonNumber(seasonNumber + 1);
                teamPlayerHistoricalRelation.setRating(player.getRating());

                teamPlayerHistoricalRelationRepository.save(teamPlayerHistoricalRelation);
            }
        }
    }

    public void removeCompetitionData(Long seasonNumber) {

        teamCompetitionDetailRepository.deleteAll();

        List<CompetitionTeamInfo> competitionTeamInfos = competitionTeamInfoRepository.findAllBySeasonNumber(seasonNumber);

        for (CompetitionTeamInfo team : competitionTeamInfos) {

            TeamCompetitionDetail newTeam = new TeamCompetitionDetail();
            newTeam.setTeamId(team.getTeamId());
            newTeam.setCompetitionId(team.getCompetitionId());
            newTeam.setForm("");
            teamCompetitionDetailRepository.save(newTeam);
        }
    }

    public void resetCompetitionData() {

        competitionTeamInfoDetailRepository.deleteAll();

    }

    public void saveHistoricalValues(Long competitionId, Long seasonNumber) {

        List<TeamCompetitionDetail> teams = teamCompetitionDetailRepository.findAll()
                .stream()
                .filter(teamCompetitionDetail -> teamCompetitionDetail.getCompetitionId() == competitionId)
                .collect(Collectors.toList());

        Collections.sort(teams, (a, b) -> {
            int pointsA = a.getPoints();
            int pointsB = b.getPoints();
            if (pointsA != pointsB)
                return pointsA > pointsB ? -1 : 1;

            int gdA = a.getGoalDifference();
            int gdB = b.getGoalDifference();
            if (gdA != gdB)
                return gdA > gdB ? -1 : 1;

            return a.getGoalsFor() > b.getGoalsFor() ? -1 : 1;
        });

        for (int i = 0; i < teams.size(); i++) {
            TeamCompetitionDetail team = teams.get(i);
            if (team.getCompetitionId() != competitionId)
                continue;
            competitionHistoryRepository.save(this.adaptCompetitionHistory(team, seasonNumber, 1 + i));
        }
    }

    private CompetitionHistory adaptCompetitionHistory(TeamCompetitionDetail team, Long seasonNumber, long position) {

        CompetitionHistory competitionHistory = new CompetitionHistory();

        Competition competition = competitionRepository.findById(team.getCompetitionId()).get();

        competitionHistory.setSeasonNumber(seasonNumber);
        competitionHistory.setLastPosition(position);
        competitionHistory.setCompetitionTypeId(competition.getTypeId());
        competitionHistory.setCompetitionName(competition.getName());
        competitionHistory.setCompetitionId(team.getCompetitionId());
        competitionHistory.setTeamId(team.getTeamId());
        competitionHistory.setGames(team.getGames());
        competitionHistory.setWins(team.getWins());
        competitionHistory.setDraws(team.getDraws());
        competitionHistory.setLoses(team.getLoses());
        competitionHistory.setGoalsFor(team.getGoalsFor());
        competitionHistory.setGoalsAgainst(team.getGoalsAgainst());
        competitionHistory.setGoalDifference(team.getGoalDifference());
        competitionHistory.setPoints(team.getPoints());
        competitionHistory.setForm(team.getForm());

        return competitionHistory;
    }

    @GetMapping("/historical/getTeams/{seasonNumber}/{competitionId}")
    public List<TeamCompetitionView> getHistoricalTeamDetails(@PathVariable(name = "competitionId") long competitionId, @PathVariable(name = "seasonNumber") long seasonNumber) {

        List<CompetitionHistory> teamParticipants = competitionHistoryRepository
                .findAll()
                .stream()
                .filter(competitionHistory -> competitionHistory.getCompetitionId() == competitionId && competitionHistory.getSeasonNumber() == seasonNumber)
                .collect(Collectors.toList());

        List<TeamCompetitionView> teamCompetitionViews = new ArrayList<>();

        for (CompetitionHistory competitionHistory : teamParticipants)
            teamCompetitionViews.add(adaptTeam(teamRepository.findById(competitionHistory.getTeamId()).orElse(new Team()), competitionHistory));

        return teamCompetitionViews;
    }

    @GetMapping("/getTeams/{competitionId}")
    public List<TeamCompetitionView> getTeamDetails(@PathVariable(name = "competitionId") long competitionId) {

        List<Long> teamParticipantIds = competitionTeamInfoRepository
                .findAll()
                .stream()
                .filter(competitionTeamInfo -> competitionTeamInfo.getCompetitionId() == competitionId && competitionTeamInfo.getSeasonNumber() == Long.valueOf(getCurrentSeason()))
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .toList();

        List<TeamCompetitionView> teamCompetitionViews = new ArrayList<>();

        for (Long teamId : teamParticipantIds) {
            TeamCompetitionDetail teamCompetitionDetail = teamCompetitionDetailRepository.findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competitionId);
            Team team = teamRepository.findById(teamId).orElseGet(null);

            if (team == null || teamCompetitionDetail == null) {
                teamCompetitionDetail = new TeamCompetitionDetail();
                teamCompetitionDetail.setTeamId(teamId);
                teamCompetitionDetail.setCompetitionId(competitionId);
            }

            if (team != null) { // team should never be null
                TeamCompetitionView teamCompetitionView = adaptTeam(team, teamCompetitionDetail);
                teamCompetitionViews.add(teamCompetitionView);
            }
        }

        return teamCompetitionViews;
    }

    @GetMapping("/getAllCompetitions")
    public List<Competition> getAllCompetitions() {

        return competitionRepository
                .findAll();
    }

    @GetMapping("/getAllCompetitions/{typeId}")
    public List<Competition> getAllCompetitionsByTypeId(@PathVariable(name = "typeId") long typeId) {

        return competitionRepository
                .findAll()
                .stream()
                .filter(competition -> competition.getTypeId() == typeId)
                .collect(Collectors.toList());
    }

    private TeamCompetitionView adaptTeam(Team team, CompetitionHistory teamCompetitionHistory) {

        TeamCompetitionView teamCompetitionView = new TeamCompetitionView();

        // Team information
        teamCompetitionView.setTeamId(team.getId());
        teamCompetitionView.setName(team.getName());
        teamCompetitionView.setColor1(team.getColor1());
        teamCompetitionView.setColor2(team.getColor2());
        teamCompetitionView.setBorder(team.getBorder());

        // TeamCompetitionDetail
        teamCompetitionView.setGames(String.valueOf(teamCompetitionHistory.getGames()));
        teamCompetitionView.setWins(String.valueOf(teamCompetitionHistory.getWins()));
        teamCompetitionView.setDraws(String.valueOf(teamCompetitionHistory.getDraws()));
        teamCompetitionView.setLoses(String.valueOf(teamCompetitionHistory.getLoses()));
        teamCompetitionView.setGoalsFor(String.valueOf(teamCompetitionHistory.getGoalsFor()));
        teamCompetitionView.setGoalsAgainst(String.valueOf(teamCompetitionHistory.getGoalsAgainst()));
        teamCompetitionView.setGoalDifference(String.valueOf(teamCompetitionHistory.getGoalDifference()));
        teamCompetitionView.setPoints(String.valueOf(teamCompetitionHistory.getPoints()));
        teamCompetitionView.setForm(teamCompetitionHistory.getForm());

        return teamCompetitionView;
    }

    private TeamCompetitionView adaptTeam(Team team, TeamCompetitionDetail teamCompetitionDetail) {

        TeamCompetitionView teamCompetitionView = new TeamCompetitionView();

        // Team information
        teamCompetitionView.setTeamId(team.getId());
        teamCompetitionView.setName(team.getName());
        teamCompetitionView.setColor1(team.getColor1());
        teamCompetitionView.setColor2(team.getColor2());
        teamCompetitionView.setBorder(team.getBorder());

        // TeamCompetitionDetail
        teamCompetitionView.setGames(String.valueOf(teamCompetitionDetail.getGames()));
        teamCompetitionView.setWins(String.valueOf(teamCompetitionDetail.getWins()));
        teamCompetitionView.setDraws(String.valueOf(teamCompetitionDetail.getDraws()));
        teamCompetitionView.setLoses(String.valueOf(teamCompetitionDetail.getLoses()));
        teamCompetitionView.setGoalsFor(String.valueOf(teamCompetitionDetail.getGoalsFor()));
        teamCompetitionView.setGoalsAgainst(String.valueOf(teamCompetitionDetail.getGoalsAgainst()));
        teamCompetitionView.setGoalDifference(String.valueOf(teamCompetitionDetail.getGoalDifference()));
        teamCompetitionView.setPoints(String.valueOf(teamCompetitionDetail.getPoints()));
        teamCompetitionView.setForm(teamCompetitionDetail.getForm());
        teamCompetitionView.setPositions(teamCompetitionDetail.getLast10Positions());

        return teamCompetitionView;
    }

    private long getNextRound(long previousRound) {

        List<Long> rounds = List.of(1L, 2L, 3L, 4L);
        for (int i = 0; i < rounds.size(); i++)
            if (rounds.get(i) == previousRound)
                return i == rounds.size() - 1 ? -1 : rounds.get(i + 1);

        return -1;
    }

    @GetMapping("getResults/{competitionId}/{roundId}")
    public List<CompetitionTeamInfoDetail> getResults(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<CompetitionTeamInfoDetail> competitionTeamInfoDetails =
                competitionTeamInfoDetailRepository
                        .findAll()
                        .stream()
                        .filter(competitionTeamInfoDetail -> competitionTeamInfoDetail.getRoundId() == _roundId)
                        .filter(competitionTeamInfoDetail -> competitionTeamInfoDetail.getCompetitionId() == _competitionId)
                        .toList();

        return competitionTeamInfoDetails;

    }

    @GetMapping("getParticipants/{competitionId}/{roundId}")
    public List<Long> getParticipants(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<CompetitionTeamInfo> competitionTeamInfos = competitionTeamInfoRepository
                .findAllByRoundAndCompetitionIdAndSeasonNumber(_roundId, _competitionId, Long.parseLong(getCurrentSeason()));

        List<Long> participants = new ArrayList<>(competitionTeamInfos
                .stream()
                .mapToLong(CompetitionTeamInfo::getTeamId)
                .boxed()
                .collect(Collectors.toSet()));

        return participants;
    }

    @GetMapping("getFuturesMatches/{competitionId}/{roundId}")
    public List<TeamMatchView> getNotPlayedMatches(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<CompetitionTeamInfoMatch> futureMatches =
                competitionTeamInfoMatchRepository
                        .findAll()
                        .stream()
                        .filter(competitionTeamInfoMatch -> competitionTeamInfoMatch.getCompetitionId() == _competitionId && competitionTeamInfoMatch.getRound() == _roundId
                                && competitionTeamInfoMatch.getSeasonNumber().equals(getCurrentSeason()))
                        .toList();

        List<TeamMatchView> matchViews = new ArrayList<>();

        for (CompetitionTeamInfoMatch match : futureMatches)
            matchViews.add(adaptCompetitionTeamInfoMatch(match, _competitionId, _roundId));

        return matchViews;
    }

    private TeamMatchView adaptCompetitionTeamInfoMatch(CompetitionTeamInfoMatch match, long competitionId, long roundId) {

        TeamMatchView teamMatchView = new TeamMatchView();
        teamMatchView.setTeamName1(teamRepository.findById(match.getTeam1Id()).get().getName());
        teamMatchView.setTeamName2(teamRepository.findById(match.getTeam2Id()).get().getName());

        CompetitionTeamInfoDetail matchDetail = competitionTeamInfoDetailRepository.findCompetitionTeamInfoDetailByCompetitionIdAndRoundIdAndTeam1IdAndTeam2IdAndSeasonNumber(competitionId, roundId, match.getTeam1Id(), match.getTeam2Id(), Long.parseLong(getCurrentSeason()));
        if (matchDetail != null)
            teamMatchView.setScore(matchDetail.getScore());
        else
            teamMatchView.setScore("-");

        return teamMatchView;
    }

    @GetMapping("getFixtures/{competitionId}/{roundId}")
    public void getFixturesForRound(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);

        List<Long> participants = this.getParticipants(competitionId, roundId);

        // we need to get matches and to add them into CompetitionTeamInfoMatch
        Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1);
        Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3);

        if (leagueCompetitionIds.contains(_competitionId) || secondLeagueCompetitionIds.contains(_competitionId)) {
            List<List<List<Long>>> schedule = roundRobin.getSchedule(participants);
            int currentRound = 1;

            boolean reverse = true;

            for (int i = 0; i < 2; i++) {

                for (List<List<Long>> round : schedule) {

                    reverse = !reverse;
                    for (List<Long> match : round) {
                        long teamHomeId = match.get(0);
                        long teamAwayId = match.get(1);

                        CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                        competitionTeamInfoMatch.setCompetitionId(_competitionId);
                        competitionTeamInfoMatch.setRound(currentRound);
                        if (reverse) {
                            competitionTeamInfoMatch.setTeam1Id(teamHomeId);
                            competitionTeamInfoMatch.setTeam2Id(teamAwayId);
                        } else {
                            competitionTeamInfoMatch.setTeam1Id(teamAwayId);
                            competitionTeamInfoMatch.setTeam2Id(teamHomeId);
                        }
                        competitionTeamInfoMatch.setSeasonNumber(getCurrentSeason());
                        competitionTeamInfoMatchRepository.save(competitionTeamInfoMatch);
                    }
                    currentRound++;
                }
            }


        } else {

            Collections.shuffle(participants);
            for (int i = 0; i < participants.size(); i += 2) {
                long teamHomeId = participants.get(i);
                long teamAwayId = participants.get(i + 1);

                CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                competitionTeamInfoMatch.setCompetitionId(_competitionId);
                competitionTeamInfoMatch.setRound(_roundId);
                competitionTeamInfoMatch.setTeam1Id(teamHomeId);
                competitionTeamInfoMatch.setTeam2Id(teamAwayId);
                competitionTeamInfoMatch.setSeasonNumber(getCurrentSeason());
                competitionTeamInfoMatchRepository.save(competitionTeamInfoMatch);
            }
        }
    }

    @GetMapping("simulateRound/{competitionId}/{roundId}")
    public void simulateRound(@PathVariable(name = "competitionId") String competitionId, @PathVariable(name = "roundId") String roundId) {

        long _competitionId = Long.parseLong(competitionId);
        long _roundId = Long.parseLong(roundId);
        long nextRound = getNextRound(_roundId);

        Random random = new Random();
        List<CompetitionTeamInfoMatch> matches = competitionTeamInfoMatchRepository
                .findAll();

        matches = matches.stream().filter(x -> x.getRound() == _roundId && x.getCompetitionId() == _competitionId
                && x.getSeasonNumber().equals(getCurrentSeason())).toList();

        for (CompetitionTeamInfoMatch match : matches) {
            long teamId1 = match.getTeam1Id();
            long teamId2 = match.getTeam2Id();

            int teamScore1, teamScore2;

            String tactic1 = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE).get(0).getTacticStyle();
            String tactic2 = humanRepository.findAllByTeamIdAndTypeId(teamId1, TypeNames.MANAGER_TYPE).get(0).getTacticStyle();

            double teamPower1 = getBestElevenRatingByTactic(teamId1, tactic1);
            double teamPower2 = getBestElevenRatingByTactic(teamId2, tactic2);

            Optional<PersonalizedTactic> personalizedTactic1 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId1);
            Optional<PersonalizedTactic> personalizedTactic2 = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId2);

            if (personalizedTactic1.isPresent())
                teamPower1 = adjustTeamPowerByTacticalProperties(teamPower1, teamPower2, personalizedTactic1.get());

            if (personalizedTactic2.isPresent())
                teamPower2 = adjustTeamPowerByTacticalProperties(teamPower2, teamPower1, personalizedTactic2.get());

            List<Integer> limits = calculateLimits(teamPower1, teamPower2);
            int limitA = limits.get(0);
            int limitB = limits.get(1);

            teamScore1 = random.nextInt(limitA);
            teamScore2 = random.nextInt(limitB);

            Set<Long> cupCompetitionIds = getCompetitionIdsByCompetitionType(2); // cup
            if (cupCompetitionIds.contains(_competitionId)) {
                while (teamScore2 == teamScore1)
                    teamScore2 = random.nextInt(5);
            }

            getScorersForTeam(teamId1, teamId2, teamScore1, teamScore2, tactic1, Long.valueOf(competitionId));
            getScorersForTeam(teamId2, teamId1, teamScore2, teamScore1, tactic2, Long.valueOf(competitionId));

            updateTeam(teamId1, _competitionId, teamScore1, teamScore2, teamPower1 - teamPower2);
            updateTeam(teamId2, _competitionId, teamScore2, teamScore1, teamPower2 - teamPower1);

            if (nextRound != -1 && cupCompetitionIds.contains(_competitionId)) {
                CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
                competitionTeamInfo.setCompetitionId(_competitionId);
                competitionTeamInfo.setRound(nextRound);

                competitionTeamInfo.setTeamId(teamScore1 > teamScore2 ? teamId1 : teamId2);
                competitionTeamInfo.setSeasonNumber(Long.parseLong(getCurrentSeason()));
                competitionTeamInfoRepository.save(competitionTeamInfo);
            }

            CompetitionTeamInfoDetail competitionTeamInfoDetail = new CompetitionTeamInfoDetail();
            competitionTeamInfoDetail.setCompetitionId(_competitionId);
            competitionTeamInfoDetail.setRoundId(_roundId);
            competitionTeamInfoDetail.setTeam1Id(teamId1);
            competitionTeamInfoDetail.setTeam2Id(teamId2);
            competitionTeamInfoDetail.setTeamName1(teamRepository.findById(teamId1).get().getName());
            competitionTeamInfoDetail.setTeamName2(teamRepository.findById(teamId2).get().getName());
            competitionTeamInfoDetail.setScore(teamScore1 + " - " + teamScore2);
            competitionTeamInfoDetail.setSeasonNumber(Long.parseLong(getCurrentSeason()));
            competitionTeamInfoDetailRepository.save(competitionTeamInfoDetail);
        }

    }

    private double adjustTeamPowerByTacticalProperties(double teamRating, double opponentRating, PersonalizedTactic teamTactic) {

        double difference = teamRating - opponentRating;
        int percentage = 0;

        // 1. SAFETY FIRST: Setăm valori default (scăpăm de NullPointerException)
        // Aceste string-uri trebuie să fie identice cu ce trimite Frontend-ul
        String mentality = teamTactic.getMentality() != null ? teamTactic.getMentality() : "Balanced";
        String timeWasting = teamTactic.getTimeWasting() != null ? teamTactic.getTimeWasting() : "Sometimes";
        String inPossession = teamTactic.getInPossession() != null ? teamTactic.getInPossession() : "Standard";
        String passingType = teamTactic.getPassingType() != null ? teamTactic.getPassingType() : "Normal";
        String tempo = teamTactic.getTempo() != null ? teamTactic.getTempo() : "Standard";

        // --- LOGICA PENTRU MENTALITY VS DIFFERENCE ---

        if (difference > 500) { // Suntem mult mai buni
            if ("Very Attacking".equals(mentality)) percentage += 25;
            else if ("Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 10;
            else if ("Very Defensive".equals(mentality)) percentage -= 25;

        } else if (difference > 200) { // Suntem puțin mai buni
            if ("Very Attacking".equals(mentality)) percentage += 15;
            else if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15;

        } else if (difference >= -200 && difference <= 200) { // Meci echilibrat
            if ("Very Attacking".equals(mentality)) percentage -= 15; // Prea riscant
            else if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15; // Prea pasiv

        } else if (difference < -200 && difference > -500) { // Suntem mai slabi
            if ("Very Attacking".equals(mentality)) percentage -= 15;
            else if ("Attacking".equals(mentality)) percentage -= 5;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage += 15;

        } else if (difference < -500) { // Suntem mult mai slabi
            if ("Very Attacking".equals(mentality)) percentage -= 25;
            else if ("Attacking".equals(mentality)) percentage -= 10;
            else if ("Defensive".equals(mentality)) percentage += 10;
            else if ("Very Defensive".equals(mentality)) percentage += 25;
        }

        // --- LOGICA PENTRU TIME WASTING ---
        // Frontend trimite: 'Never', 'Sometimes', 'Frequently', 'Always'
        // Mapăm 'Frequently' și 'Always' ca fiind YES (trag de timp)

        if ("Frequently".equals(timeWasting) || "Always".equals(timeWasting)) {
            if ("Attacking".equals(mentality)) percentage -= 5;
            else if ("Very Attacking".equals(mentality)) percentage -= 10;
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage += 10;

        } else if ("Never".equals(timeWasting) || "Sometimes".equals(timeWasting)) {
            if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Very Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 10;
        }

        // --- LOGICA PENTRU POSSESSION ---
        // Frontend trimite: 'Keep Ball', 'Free Ball Early' (sau Standard)

        if ("Keep Ball".equals(inPossession)) {
            if ("Attacking".equals(mentality)) percentage += 10;
            else if ("Very Attacking".equals(mentality)) percentage -= 15; // Prea lent pentru very attacking
            else if ("Defensive".equals(mentality)) percentage += 5;
            else if ("Very Defensive".equals(mentality)) percentage -= 15; // Periculos sa tii mingea in aparare

        } else if ("Free Ball Early".equals(inPossession)) { // Sau 'Direct Passing'
            if ("Attacking".equals(mentality)) percentage += 5;
            else if ("Very Attacking".equals(mentality)) percentage += 10;
            else if ("Defensive".equals(mentality)) percentage -= 5;
            else if ("Very Defensive".equals(mentality)) percentage += 15; // Degajari lungi
        }

        // --- LOGICA PENTRU PASSING & TEMPO ---
        // Aici trebuie mare atenție la string-uri. Presupunem valorile standard.
        // Frontend Tempo: 'Much Lower', 'Lower', 'Standard', 'Higher', 'Much Higher'

        if ("Short".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage += 5;
            else if ("Lower".equals(tempo)) percentage += 10;
            else if ("Standard".equals(tempo)) percentage += 15;
            else if ("Higher".equals(tempo)) percentage += 20;
            else if ("Much Higher".equals(tempo)) percentage += 25; // Tiki Taka rapid

        } else if ("Normal".equals(passingType) || "Standard".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage -= 10;
            else if ("Lower".equals(tempo)) percentage -= 5;
            else if ("Standard".equals(tempo)) percentage += 0;
            else if ("Higher".equals(tempo)) percentage += 5;
            else if ("Much Higher".equals(tempo)) percentage += 10;

        } else if ("Long".equals(passingType) || "Direct".equals(passingType)) {
            if ("Much Lower".equals(tempo)) percentage -= 30; // Pase lungi lent = pierzi mingea
            else if ("Lower".equals(tempo)) percentage -= 25;
            else if ("Standard".equals(tempo)) percentage += 0;
            else if ("Higher".equals(tempo)) percentage += 25; // Contraatac rapid
            else if ("Much Higher".equals(tempo)) percentage += 30;
        }

        // Calcul final
        return teamRating + (teamRating * percentage / 100D);
    }
    @GetMapping("/getAllCompetitionTypes")
    public List<CompetitionType> getAllCompetitionTypes() {

        List<CompetitionType> competitionTypes = new ArrayList<>();

        CompetitionType championship = new CompetitionType();
        championship.setId(1);
        championship.setTypeName("Championship");
        championship.setTypeId(1);
        competitionTypes.add(championship);

        CompetitionType cup = new CompetitionType();
        cup.setId(2);
        cup.setTypeName("Cup");
        cup.setTypeId(2);
        competitionTypes.add(cup);

        return competitionTypes;
    }

    private void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway, double teamPowerDifference) {

        TeamCompetitionDetail team = teamCompetitionDetailRepository.findTeamCompetitionDetailByTeamIdAndCompetitionId(teamId, competitionId);
        if (team == null) {
            team = new TeamCompetitionDetail();
            team.setTeamId(teamId);
        }

        team.setCompetitionId(competitionId);
        team.setGoalsFor(team.getGoalsFor() + scoreHome);
        team.setGoalsAgainst(team.getGoalsAgainst() + scoreAway);
        team.setGoalDifference(team.getGoalsFor() - team.getGoalsAgainst());
        if (scoreHome > scoreAway) {
            team.setForm(team.getForm() + "W");
            team.setWins(team.getWins() + 1);
            team.setPoints(team.getPoints() + 3);

            updatePlayersMorale(teamId, calculateMoraleChangeForTeamDifference("W", teamPowerDifference));
        } else if (scoreHome == scoreAway) {
            team.setForm(team.getForm() + "D");
            team.setDraws(team.getDraws() + 1);
            team.setPoints(team.getPoints() + 1);
            updatePlayersMorale(teamId, calculateMoraleChangeForTeamDifference("D", teamPowerDifference));
        } else {
            team.setForm(team.getForm() + "L");
            team.setLoses(team.getLoses() + 1);
            updatePlayersMorale(teamId, calculateMoraleChangeForTeamDifference("L", teamPowerDifference));
        }
        team.setGames(team.getGames() + 1);

        if (team.getForm().length() > 5)
            team.setForm(team.getForm().substring(team.getForm().length() - 5));

        teamCompetitionDetailRepository.save(team);
    }

    private void updatePlayersMorale(long teamId, double morale) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE);
        allPlayers
                .stream()
                .forEach(player -> {
                    player.setMorale(player.getMorale() + morale);
                    player.setMorale(Math.min(player.getMorale(), 120D));
                    player.setMorale(Math.max(player.getMorale(), 30D));
                    humanRepository.save(player);
                });
    }

    private double calculateMoraleChangeForTeamDifference(String result, double teamPowerDifference) {

        Random random = new Random();

        if (result.equals("W")) {
            if (teamPowerDifference > 500) {
                return random.nextDouble(0, 2);
            } else if (teamPowerDifference > 200) {
                return random.nextDouble(1, 2);
            } else if (teamPowerDifference > 0) {
                return random.nextDouble(1, 4);
            } else if (teamPowerDifference > -200) {
                return random.nextDouble(2, 6);
            } else if (teamPowerDifference > -500) {
                return random.nextDouble(5, 10);
            } else {
                return random.nextDouble(7, 15);
            }
        } else if (result.equals("D")) {
            if (teamPowerDifference > 500) {
                return random.nextDouble(-8, -2);
            } else if (teamPowerDifference > 200) {
                return random.nextDouble(-5, 0);
            } else if (teamPowerDifference > 0) {
                return random.nextDouble(-2, 1);
            } else if (teamPowerDifference > -200) {
                return random.nextDouble(1, 3);
            } else if (teamPowerDifference > -500) {
                return random.nextDouble(2, 7);
            } else {
                return random.nextDouble(5, 10);
            }
        } else {
            if (teamPowerDifference > 500) {
                return random.nextDouble(-20, -5);
            } else if (teamPowerDifference > 200) {
                return random.nextDouble(-10, -3);
            } else if (teamPowerDifference > 0) {
                return random.nextDouble(-5, -2);
            } else if (teamPowerDifference > -200) {
                return random.nextDouble(-3, -1);
            } else if (teamPowerDifference > -500) {
                return random.nextDouble(-2, 0);
            } else {
                return random.nextDouble(-1, 0);
            }
        }
    }

    private List<Integer> calculateLimits(double power1, double power2) {

        int limitA = (int) (2 + (Math.abs(power1 - power2)) / 100);
        int limitB = 2;

        return power1 >= power2 ? List.of(limitA, limitB) : List.of(limitB, limitA);

    }

    private List<Long> getAllTeams() {

        return teamRepository.findAll()
                .stream()
                .map(Team::getId)
                .collect(Collectors.toList());

    }

    public HashMap<String, Integer> getMinimumPositionNeeded() {

        HashMap<String, Integer> minimumPositionNeeded = new HashMap<>();
        minimumPositionNeeded.put("GK", 1);
        minimumPositionNeeded.put("DL", 1);
        minimumPositionNeeded.put("DC", 2);
        minimumPositionNeeded.put("DR", 1);
        minimumPositionNeeded.put("MC", 2);
        minimumPositionNeeded.put("ML", 1);
        minimumPositionNeeded.put("MR", 1);
        minimumPositionNeeded.put("ST", 2);

        return minimumPositionNeeded;
    }

    public HashMap<String, Integer> getMaximumPositionAllowed() {

        HashMap<String, Integer> maximumPositionAllowed = new HashMap<>();
        maximumPositionAllowed.put("GK", 3);
        maximumPositionAllowed.put("DL", 3);
        maximumPositionAllowed.put("DC", 5);
        maximumPositionAllowed.put("DR", 3);
        maximumPositionAllowed.put("MC", 5);
        maximumPositionAllowed.put("ML", 3);
        maximumPositionAllowed.put("MR", 3);
        maximumPositionAllowed.put("ST", 5);

        return maximumPositionAllowed;
    }


    public long calculateTransferValue(long age, String position, double rating) {

        double value = rating * 10000;

        return (long) value;
    }

    private double getBestElevenRating(List<Human> players) {


        double bestElevenRating = 0;

        for (Human player : players)
            bestElevenRating += player.getRating();

        return bestElevenRating;
    }

    private double getBestElevenRatingByTactic(long teamId, String tactic) {

        Optional<PersonalizedTactic> personalizedTacticOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);

        if (personalizedTacticOpt.isPresent()) {
            PersonalizedTactic personalized = personalizedTacticOpt.get();
            double rating = 0;

            try {
                // 1. Convertim JSON-ul salvat în List<FormationData>
                List<FormationData> formationDataList = objectMapper.readValue(
                        personalized.getFirst11(),
                        new TypeReference<List<FormationData>>() {}
                );

                // 2. Iterăm prin listă
                for (FormationData data : formationDataList) {

                    // Ignorăm rezervele (indecșii >= 30 sunt pe bancă)
                    if (data.getPositionIndex() >= 30) continue;

                    // Găsim jucătorul
                    Optional<Human> playerOpt = humanRepository.findById(data.getPlayerId());
                    if (playerOpt.isEmpty()) continue;

                    Human player = playerOpt.get();

                    // 3. Aflăm ce poziție reprezintă indexul de pe grilă (ex: 27 -> "GK")
                    String tacticPosition = tacticService.getPositionFromIndex(data.getPositionIndex());

                    // 4. Calculăm rating-ul (logica ta originală)
                    if (player.getPosition().equals(tacticPosition)) {
                        // Joacă pe poziția lui naturală
                        rating += player.getRating() * player.getMorale() / 100D;
                    } else {
                        // Joacă pe altă poziție (penalizare)
                        // TODO: Poți rafina aici (ex: dacă e DC și joacă DR e mai ok decât dacă e ST și joacă GK)
                        rating += player.getRating() / 2 * player.getMorale() / 100D;
                    }
                }

                // add extra bonus logic here if needed

                return rating;

            } catch (Exception e) {
                e.printStackTrace();
                // Dacă parsarea eșuează, continuăm execuția spre fallback-ul de mai jos
            }
        }

        // --- FALLBACK (Dacă nu are tactică salvată sau a crăpat parsarea) ---
        // Folosește algoritmul automat pentru a determina cel mai bun 11
        List<PlayerView> playerViews = tacticController.getBestEleven(String.valueOf(teamId), tactic);

        return playerViews
                .stream()
                .mapToDouble(playerView -> playerView.getRating() * playerView.getMorale() / 100D)
                .sum();
    }

    private void getScorersForTeam(long teamId, long opponentTeamId, int teamScore, int opponentScore, String tactic, long competitionId) {

        long competitionTypeId = competitionRepository.findTypeIdById(competitionId);
        String teamName = teamRepository.findNameById(teamId);
        String opponentName = teamRepository.findNameById(opponentTeamId);
        String competitionName = competitionRepository.findNameById(competitionId);

        Optional<PersonalizedTactic> personalizedTacticOpt = personalizedTacticRepository.findPersonalizedTacticByTeamId(teamId);
        List<Scorer> possibleScorers = new ArrayList<>();
        List<Scorer> substitutions = new ArrayList<>();

        boolean loadedSuccessfully = false;

        // 1. ÎNCERCĂM SĂ ÎNCĂRCĂM TACTICA PERSONALIZATĂ (JSON)
        if (personalizedTacticOpt.isPresent()) {
            try {
                PersonalizedTactic personalized = personalizedTacticOpt.get();

                // Citim JSON-ul salvat
                List<FormationData> formationList = objectMapper.readValue(
                        personalized.getFirst11(),
                        new TypeReference<List<FormationData>>() {}
                );

                for (FormationData data : formationList) {
                    Optional<Human> playerOpt = humanRepository.findById(data.getPlayerId());
                    if (playerOpt.isEmpty()) continue;

                    Human player = playerOpt.get();

                    Scorer scorer = new Scorer();
                    scorer.setPlayerId(player.getId());
                    scorer.setSeasonNumber(Integer.parseInt(getCurrentSeason()));
                    scorer.setTeamId(teamId);
                    scorer.setOpponentTeamId(opponentTeamId);
                    scorer.setPosition(player.getPosition());
                    scorer.setTeamScore(teamScore);
                    scorer.setOpponentScore(opponentScore);
                    scorer.setCompetitionId(competitionId);
                    scorer.setCompetitionTypeId((int) competitionTypeId);
                    scorer.setTeamName(teamName);
                    scorer.setOpponentTeamName(opponentName);
                    scorer.setCompetitionName(competitionName);
                    scorer.setRating(player.getRating());

                    // 🔹 LOGICA NOUĂ: Index >= 30 înseamnă Rezervă
                    if (data.getPositionIndex() >= 30) {
                        scorer.setSubstitute(true);
                        substitutions.add(scorer);
                    } else {
                        scorer.setSubstitute(false);
                        possibleScorers.add(scorer);
                    }
                }

                if (!possibleScorers.isEmpty()) {
                    loadedSuccessfully = true;
                }

            } catch (Exception e) {
                System.err.println("Error parsing tactic JSON for team " + teamId + ": " + e.getMessage());
                // Nu dăm throw, ci lăsăm loadedSuccessfully = false ca să intre pe fallback
            }
        }

        // 2. FALLBACK: LOGICA STANDARD (Dacă nu are tactică sau a crăpat JSON-ul)
        if (!loadedSuccessfully) {
            // Aici e codul tău vechi din blocul "else", neatins
            List<PlayerView> playerViews = tacticController.getBestEleven(String.valueOf(teamId), tactic);
            playerViews.stream().map(playerView -> {
                Scorer scorer = new Scorer();
                scorer.setPlayerId(playerView.getId());
                scorer.setSeasonNumber(Integer.parseInt(getCurrentSeason()));
                scorer.setTeamId(teamId);
                scorer.setOpponentTeamId(opponentTeamId);
                scorer.setPosition(playerView.getPosition());
                scorer.setTeamScore(teamScore);
                scorer.setOpponentScore(opponentScore);
                scorer.setCompetitionId(competitionId);
                scorer.setCompetitionTypeId((int) competitionTypeId);
                scorer.setTeamName(teamName);
                scorer.setOpponentTeamName(opponentName);
                scorer.setCompetitionName(competitionName);
                scorer.setRating(playerView.getRating());
                scorer.setSubstitute(false);
                return scorer;
            }).forEach(possibleScorers::add);

            List<PlayerView> substitutionViews = tacticController.getSubstitutions(String.valueOf(teamId), tactic);
            substitutionViews.stream().map(playerView -> {
                Scorer scorer = new Scorer();
                scorer.setPlayerId(playerView.getId());
                scorer.setSeasonNumber(Integer.parseInt(getCurrentSeason()));
                scorer.setTeamId(teamId);
                scorer.setOpponentTeamId(opponentTeamId);
                scorer.setPosition(playerView.getPosition());
                scorer.setTeamScore(teamScore);
                scorer.setOpponentScore(opponentScore);
                scorer.setCompetitionId(competitionId);
                scorer.setCompetitionTypeId((int) competitionTypeId);
                scorer.setTeamName(teamName);
                scorer.setOpponentTeamName(opponentName);
                scorer.setCompetitionName(competitionName);
                scorer.setRating(playerView.getRating());
                scorer.setSubstitute(true);
                return scorer;
            }).forEach(substitutions::add);
        }

        // 3. SIMULARE SCHIMBĂRI ȘI GOLURI (Rămâne la fel)
        Random random = new Random();
        int substitutesDone = random.nextInt(0, Math.min(6, substitutions.size() + 1)); // fix bounds check
        if (!substitutions.isEmpty()) {
            Collections.shuffle(substitutions);
            // Asigură-te că nu ceri mai mulți decât ai
            for (int i = 0; i < Math.min(substitutesDone, substitutions.size()); i++) {
                possibleScorers.add(substitutions.get(i));
            }
        }

        List<Pair<Scorer, Double>> weightedPlayers = new ArrayList<>();
        for (Scorer scorer: possibleScorers) {
            if ("GK".equals(scorer.getPosition())) continue;
            weightedPlayers.add(new Pair<>(scorer, competitionService.getDifferentValueForScoringBasedOnPosition(scorer)));
        }

        if (!weightedPlayers.isEmpty()) {
            for (int i = 0; i < teamScore; i++) {
                try {
                    EnumeratedDistribution<Scorer> distribution = new EnumeratedDistribution<>(weightedPlayers);
                    Scorer selected = distribution.sample();
                    selected.setGoals(selected.getGoals() + 1);
                } catch (Exception e) {
                    System.err.println("Distribution error (negative weights?): " + e.getMessage());
                }
            }
        }

        for (Scorer scorer: possibleScorers) {

            scorerRepository.save(scorer);

            Optional<Human> possiblePlayer = humanRepository.findById(scorer.getPlayerId());
            if (possiblePlayer.isPresent()) {
                Human player = possiblePlayer.get();

                ScorerLeaderboardEntry scorerLeaderboardEntry = scorerLeaderboardRepository.findByPlayerId(player.getId()).get();
                scorerLeaderboardEntry.setAge(player.getAge());
                scorerLeaderboardEntry.setName(player.getName());

                scorerLeaderboardEntry.setName(player.getName());
                if (player.getTeamId() != null) {
                    scorerLeaderboardEntry.setTeamName(teamRepository.findNameById(player.getTeamId()));
                } else {
                    scorerLeaderboardEntry.setTeamName("Free Agent");
                }
                if (player.isRetired()) {
                    scorerLeaderboardEntry.setTeamName("Retired");
                }
                scorerLeaderboardEntry.setPosition(player.getPosition());
                scorerLeaderboardEntry.setActive(!player.isRetired());
                if (player.getRating() > scorerLeaderboardEntry.getBestEverRating()) {
                    scorerLeaderboardEntry.setBestEverRating(player.getRating());
                    scorerLeaderboardEntry.setSeasonOfBestEverRating(Integer.parseInt(getCurrentSeason()));
                }
                scorerLeaderboardEntry.setAge(player.getAge());
                scorerLeaderboardEntry.setCurrentRating(player.getRating());

                scorerLeaderboardEntry.setMatches(scorerLeaderboardEntry.getMatches() + 1);
                scorerLeaderboardEntry.setGoals(scorerLeaderboardEntry.getGoals() + scorer.getGoals());
                scorerLeaderboardEntry.setCurrentSeasonGoals(scorerLeaderboardEntry.getCurrentSeasonGoals() + scorer.getGoals());
                scorerLeaderboardEntry.setCurrentSeasonGames(scorerLeaderboardEntry.getCurrentSeasonGames() + 1);

                Set<Long> leagueCompetitionIds = getCompetitionIdsByCompetitionType(1); // leagues
                Set<Long> cupCompetitionIds = getCompetitionIdsByCompetitionType(2); // cup
                Set<Long> secondLeagueCompetitionIds = getCompetitionIdsByCompetitionType(3); // second leagues


                if (leagueCompetitionIds.contains(competitionId)) {

                    scorerLeaderboardEntry.setLeagueGoals(scorerLeaderboardEntry.getLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setLeagueMatches(scorerLeaderboardEntry.getLeagueMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonLeagueGoals(scorerLeaderboardEntry.getCurrentSeasonLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonLeagueGames(scorerLeaderboardEntry.getCurrentSeasonLeagueGames() + 1);

                } else if (cupCompetitionIds.contains(competitionId)) {

                    scorerLeaderboardEntry.setCupGoals(scorerLeaderboardEntry.getCupGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCupMatches(scorerLeaderboardEntry.getCupMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonCupGoals(scorerLeaderboardEntry.getCurrentSeasonCupGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonCupGames(scorerLeaderboardEntry.getCurrentSeasonCupGames() + 1);

                } else if (secondLeagueCompetitionIds.contains(competitionId)){

                    scorerLeaderboardEntry.setSecondLeagueGoals(scorerLeaderboardEntry.getSecondLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setSecondLeagueMatches(scorerLeaderboardEntry.getSecondLeagueMatches() + 1);
                    scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGoals(scorerLeaderboardEntry.getCurrentSeasonSecondLeagueGoals() + scorer.getGoals());
                    scorerLeaderboardEntry.setCurrentSeasonSecondLeagueGames(scorerLeaderboardEntry.getCurrentSeasonSecondLeagueGames() + 1);

                }
                scorerLeaderboardRepository.save(scorerLeaderboardEntry);
            }
        }

    }

    @GetMapping("/getCompetitionInfo/{id}")
    public Map<String, Object> getCompetitionInfo(@PathVariable Long id) {

        Competition comp = competitionRepository.findById(id).orElse(null);
        Map<String, Object> info = new HashMap<>();
        if (comp != null) {
            info.put("typeId", comp.getTypeId());
            info.put("name", comp.getName());
        }

        return info;
    }

    @GetMapping("/getCompetitionNameById/{competitionId}")
    public String getTeamNameByTeamId(@PathVariable(name = "competitionId") long competitionId) {

        return competitionRepository.findNameById(competitionId);
    }

    private List<Human> getBestEleven(long teamId) {

        List<Human> players = humanRepository
                .findAllByTeamIdAndTypeId(teamId, TypeNames.PLAYER_TYPE)
                .stream()
                .sorted(Comparator.comparing(Human::getRating))
                .toList();

        List<String> positions = getPositionsForBestEleven(teamId);
        List<Human> bestEleven = new ArrayList<>();

        for (String position : positions) {
            for (Human player : players) {
                if (player.getPosition().equals(position)) {
                    bestEleven.add(player);
                    break;
                }
            }
        }

        return bestEleven;
    }

    private List<String> getPositionsForBestEleven(long teamId) {

        return List.of("GK", "DL", "DC", "DC", "DR", "ML", "MC", "MC", "MR", "ST", "ST");
    }

    private boolean canBeTransfered(PlayerTransferView playerTransferView, BuyPlanTransferView clubPlan, TransferPlayer desiredPlayer) {

        if (playerTransferView.getAge() > clubPlan.getMaxAge())
            return false; // club does not want to buy player, too old
        if (playerTransferView.getDesiredReputation() - 1000 > clubPlan.getTeamReputation())
            return false; // club can't buy player, reputation too low
        if (!playerTransferView.getPosition().equals(desiredPlayer.getPosition()))
            return false; // not desired position
        if (playerTransferView.getRating() < desiredPlayer.getMinRating() - 10)
            return false; // player rating too low
        if (playerTransferView.getTeamId() == clubPlan.getTeamId())
            return false; // club already owns player

        return true;
    }

    private void initialization() {

        initializeCompetitions();
        initializeTeams1();
        initializeTeams2();
        initializeTeams3();
        initializeTeams4();
        initializeTeams5();

        initializeSpecialPlayers();
    }

    private void initializeCompetitions() {

        List<List<Integer>> values = List.of(List.of(1, 1, 1), List.of(1, 2, 2), List.of(3, 1, 1),
                List.of(3, 2, 2), List.of(1, 3, 3), List.of(3, 2, 1), List.of(3, 3, 2), List.of(4, 2, 1), List.of(4, 3, 2));

        List<String> names = List.of("Gallactick Football First League", "Gallactick Football Cup",
                "Khess First League", "Khess Cup", "Khess Second League", "Dong Championship", "Dong Cup", "FootieCup League",
                "FootieCup Cup");

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
                List.of(10000, 5),
                List.of(9000, 5),
                List.of(9000, 5),
                List.of(8600, 2),
                List.of(8000, 4),
                List.of(7900, 3),
                List.of(7000, 2),
                List.of(6900, 1),
                List.of(6000, 1),
                List.of(7000, 2),
                List.of(6700, 1),
                List.of(6500, 3));

        List<List<Integer>> facilities = List.of(
                List.of(16, 20, 20),
                List.of(15, 20, 18),
                List.of(15, 20, 18),
                List.of(10, 18, 16),
                List.of(10, 16, 16),
                List.of(10, 15, 15),
                List.of(10, 12, 14),
                List.of(10, 14, 13),
                List.of(10, 12, 12),
                List.of(8, 12, 10),
                List.of(7, 11, 9),
                List.of(6, 10, 9)
        );

        int addedModulo = 0;
        long leagueId = 1L;
        long cupId = 2L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
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
                List.of(10000, 5),
                List.of(9000, 5),
                List.of(9000, 5),
                List.of(8600, 2),
                List.of(8000, 4),
                List.of(7900, 3),
                List.of(7000, 2),
                List.of(6900, 1),
                List.of(6000, 1),
                List.of(7000, 2),
                List.of(6700, 1),
                List.of(6500, 3));

        List<List<Integer>> facilities = List.of(
                List.of(20, 20, 20),
                List.of(20, 20, 20),
                List.of(15, 20, 18),
                List.of(10, 18, 16),
                List.of(10, 16, 16),
                List.of(10, 15, 15),
                List.of(10, 12, 14),
                List.of(10, 14, 13),
                List.of(10, 12, 12),
                List.of(8, 12, 10),
                List.of(7, 11, 9),
                List.of(6, 10, 9)
        );

        int addedModulo = 12;
        long leagueId = 3L;
        long cupId = 4L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
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
                List.of(6000, 5),
                List.of(5500, 5),
                List.of(5500, 5),
                List.of(5400, 2),
                List.of(5300, 4),
                List.of(5200, 3),
                List.of(5000, 2),
                List.of(4900, 1),
                List.of(4800, 1),
                List.of(4300, 2),
                List.of(4200, 1),
                List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(7, 4, 1),
                List.of(6, 3, 4),
                List.of(5, 5, 5),
                List.of(10, 3, 4),
                List.of(5, 6, 10),
                List.of(4, 3, 1),
                List.of(5, 4, 3),
                List.of(6, 8, 3),
                List.of(7, 9, 1),
                List.of(8, 7, 4),
                List.of(7, 5, 5),
                List.of(6, 4, 3)
        );

        int addedModulo = 24;
        long leagueId = 5L;
        long cupId = 4L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
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
                List.of(6000, 5),
                List.of(5500, 5),
                List.of(5500, 5),
                List.of(5400, 2),
                List.of(5300, 4),
                List.of(5200, 3),
                List.of(5000, 2),
                List.of(4900, 1),
                List.of(4800, 1),
                List.of(4300, 2),
                List.of(4200, 1),
                List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(15, 10, 15),
                List.of(13, 8, 14),
                List.of(5, 5, 5),
                List.of(10, 3, 4),
                List.of(5, 6, 10),
                List.of(4, 3, 1),
                List.of(5, 4, 3),
                List.of(6, 8, 3),
                List.of(7, 9, 1),
                List.of(8, 7, 4),
                List.of(7, 5, 5),
                List.of(6, 4, 3)
        );

        int addedModulo = 36;
        long leagueId = 6L;
        long cupId = 7L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
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
                List.of(6000, 5),
                List.of(5500, 5),
                List.of(5500, 5),
                List.of(5400, 2),
                List.of(5300, 4),
                List.of(5200, 3),
                List.of(5000, 2),
                List.of(4900, 1),
                List.of(4800, 1),
                List.of(4300, 2),
                List.of(4200, 1),
                List.of(4100, 3));

        List<List<Integer>> facilities = List.of(
                List.of(15, 10, 15),
                List.of(13, 8, 14),
                List.of(5, 5, 5),
                List.of(10, 3, 4),
                List.of(5, 6, 10),
                List.of(4, 3, 1),
                List.of(5, 4, 3),
                List.of(6, 8, 3),
                List.of(7, 9, 1),
                List.of(8, 7, 4),
                List.of(7, 5, 5),
                List.of(6, 4, 3)
        );

        int addedModulo = 48;
        long leagueId = 8L;
        long cupId = 9L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
    }

    private void initializeSpecialPlayers() {

        Human Kvekrpur = new Human();
        Kvekrpur.setRating(300);
        Kvekrpur.setName("Kvekrpur");
        Kvekrpur.setTeamId(14L); // Tik Tok
        Kvekrpur.setAge(20);
        Kvekrpur.setMorale(100D);
        Kvekrpur.setSeasonCreated(1);
        Kvekrpur.setTypeId(1);
        Kvekrpur.setPosition("ST");
        Kvekrpur.setCurrentStatus("Junior");

        humanRepository.save(Kvekrpur);
    }

    private void createTeamsAndCompetitions(List<List<String>> teamNames, List<List<Integer>> teamValues, List<List<Integer>> facilities, int addedModulo, long leagueId, long cupId) {

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
            teamRepository.save(team);

            CompetitionTeamInfo competitionTeamInfo = new CompetitionTeamInfo();
            competitionTeamInfo.setSeasonNumber(1);
            competitionTeamInfo.setRound(1);
            competitionTeamInfo.setCompetitionId(leagueId);
            competitionTeamInfo.setTeamId(i + addedModulo + 1);
            competitionTeamInfoRepository.save(competitionTeamInfo);

            competitionTeamInfo = new CompetitionTeamInfo();
            competitionTeamInfo.setSeasonNumber(1);
            competitionTeamInfo.setRound(i <= 3 ? 2 : 1);
            competitionTeamInfo.setCompetitionId(cupId);
            competitionTeamInfo.setTeamId(i + addedModulo + 1);
            competitionTeamInfoRepository.save(competitionTeamInfo);

            TeamFacilities teamFacilities = new TeamFacilities();
            teamFacilities.setTeamId(i + addedModulo + 1);
            teamFacilities.setYouthAcademyLevel(facilities.get(i).get(0));
            teamFacilities.setYouthTrainingLevel(facilities.get(i).get(1));
            teamFacilities.setSeniorTrainingLevel(facilities.get(i).get(2));
            _teamFacilitiesRepository.save(teamFacilities);
        }
    }

    public Set<Long> getCompetitionIdsByCompetitionType(int competitionTypeId) {

        return competitionRepository
                .findAll()
                .stream()
                .filter(competition -> competition.getTypeId() == competitionTypeId)
                .map(Competition::getId)
                .collect(Collectors.toSet());
    }

    public Round getRound() {

        return round;
    }
}
