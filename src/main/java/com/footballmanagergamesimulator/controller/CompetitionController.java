package com.footballmanagergamesimulator.controller;
import com.footballmanagergamesimulator.algorithms.RoundRobin;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.frontend.TeamCompetitionView;
import com.footballmanagergamesimulator.frontend.TeamMatchView;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.nameGenerator.NameGenerator;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.HumanService;
import com.footballmanagergamesimulator.transfermarket.BuyPlanTransferView;
import com.footballmanagergamesimulator.transfermarket.CompositeTransferStrategy;
import com.footballmanagergamesimulator.transfermarket.PlayerTransferView;
import com.footballmanagergamesimulator.transfermarket.TransferPlayer;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.apache.commons.lang3.tuple.Pair;
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
    Round round;
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


    @GetMapping("/getPlayers/{teamId}")
    private List<PlayerView> getPlayers(@PathVariable(name = "teamId") String teamId) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        List<Human> getAllPlayers = humanRepository.findAllByTeamIdAndTypeId(Long.parseLong(teamId), TypeNames.HUMAN_TYPE);

        List<PlayerView> allPlayers = getAllPlayers
                .stream()
                .map(player -> adaptPlayer(player, team))
                .toList();

        return allPlayers;
    }

    @GetMapping("/getBestEleven/{teamId}")
    private List<PlayerView> getBestEleven(@PathVariable(name = "teamId") String teamId) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        List<Human> getBest11 = getBestElevenPlayers(team);
        List<PlayerView> bestEleven = getBest11
                .stream()
                .map(player -> adaptPlayer(player, team))
                .toList();

        return bestEleven;
    }

    private List<Human> getBestElevenPlayers(Team team) {

        return humanRepository
                .findAllByTeamIdAndTypeId(team.getId(), 1L)
                .stream()
                .sorted((x, y) -> Double.compare(y.getRating(), x.getRating()))
                .limit(11)
                .toList();
    }

    private PlayerView adaptPlayer(Human human, Team team) {

        PlayerView playerView = new PlayerView();
        playerView.setAge(human.getAge());
        playerView.setPosition(human.getPosition());
        playerView.setRating(human.getRating());
        playerView.setName(human.getName());
        playerView.setTeamName(team.getName());

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

    @GetMapping("/getCurrentSeason")
    public String getCurrentSeason() {

        return String.valueOf(round.getSeason());
    }

    @GetMapping("/getCurrentRound")
    public String getCurrentRound() {

        return String.valueOf(round.getRound() - 1);
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
            List<TeamCompetitionDetail> teamCompetitionDetails = teamCompetitionDetailRepository.findAll();
            for (int id = 1; id <= 5; id += 2) {
                int finalId = id;
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
                    if (id == 3L && index >= 11)
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
                    competitionTeamInfoCup.setCompetitionId(id + 1);
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

            // reset values
            this.resetCompetitionData();
            this.removeCompetitionData(round.getSeason() + 1);

            round.setRound(1);
            round.setSeason(round.getSeason() + 1);

            // add 1 year for each player
            _humanService.addOneYearToAge();
            _humanService.retirePlayers();

            for (Long teamId : teamIds) {
                TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
                if (teamFacilities != null)
                    _humanService.addRegens(teamFacilities, teamId);
            }

            for (long teamId : teamIds) {
                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, TypeNames.HUMAN_TYPE);
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

            for (String competitionId : List.of("1", "3", "5"))
                this.getFixturesForRound(competitionId, "1");

            if (round.getSeason() == 1) {
                List<Team> teams = teamRepository.findAll();
                Random random = new Random();
                for (Team team : teams) {
                    TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(team.getId());
                    int nrPlayers = 22;
                    for (int i = 0; i < nrPlayers; i++) {
                        String name = NameGenerator.generateName();
                        Human player = new Human();
                        player.setTeamId(team.getId());
                        player.setName(name);
                        player.setTypeId(1L);
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

                        int reputation = 100;
                        if (teamFacilities != null)
                            reputation = (int) teamFacilities.getSeniorTrainingLevel() * 10;
                        player.setRating(random.nextInt(reputation - 20, reputation + 20));
                        player.setTransferValue(calculateTransferValue(player.getAge(), player.getPosition(), player.getRating()));

                        humanRepository.save(player);
                    }
                }
            }
        }

        if (round.getRound() % 3 == 0) {
            for (long teamId : teamIds) {
                TeamFacilities teamFacilities = _teamFacilitiesRepository.findByTeamId(teamId);
                List<Human> players = humanRepository.findAllByTeamIdAndTypeId(teamId, 1L);
                for (Human player : players) {
                    player = _humanService.trainPlayer(player, teamFacilities);
                    humanRepository.save(player);
                }
            }
        }

        for (String competitionId : List.of("1", "3", "5"))
            this.simulateRound(competitionId, round.getRound() - 1 + "");

        for (String competitionId : List.of("2", "4")) {
            if (1 > 0) continue; // todo removing cup for moment

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

        competitionHistory.setSeasonNumber(seasonNumber);
        competitionHistory.setLastPosition(position);
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
        if (_competitionId == 1L || _competitionId == 3L || _competitionId == 5L) {
            // todo it should work for any competition of type Championship
            List<List<List<Long>>> schedule = roundRobin.getSchedule(participants);
            int currentRound = 1;

            for (int i = 0; i < 2; i++) {

                for (List<List<Long>> round : schedule) {

                    for (List<Long> match : round) {
                        long teamHomeId = match.get(0);
                        long teamAwayId = match.get(1);

                        CompetitionTeamInfoMatch competitionTeamInfoMatch = new CompetitionTeamInfoMatch();
                        competitionTeamInfoMatch.setCompetitionId(_competitionId);
                        competitionTeamInfoMatch.setRound(currentRound);
                        competitionTeamInfoMatch.setTeam1Id(teamHomeId);
                        competitionTeamInfoMatch.setTeam2Id(teamAwayId);
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

            List<Human> firstTeam = getBestEleven(teamId1);
            List<Human> secondTeam = getBestEleven(teamId2);

            double teamPower1 = getBestElevenRating(firstTeam);
            double teamPower2 = getBestElevenRating(secondTeam);

            List<Integer> limits = calculateLimits(teamPower1, teamPower2);
            int limitA = limits.get(0);
            int limitB = limits.get(1);

            teamScore1 = random.nextInt(limitA);
            teamScore2 = random.nextInt(limitB);

            if (_competitionId == 2L || _competitionId == 4L) {
                while (teamScore2 == teamScore1)
                    teamScore2 = random.nextInt(5);
            }

            updateTeam(teamId1, _competitionId, teamScore1, teamScore2);
            updateTeam(teamId2, _competitionId, teamScore2, teamScore1);


            if (nextRound != -1 && (_competitionId == 2L || _competitionId == 4L)) {
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

    private void updateTeam(long teamId, long competitionId, int scoreHome, int scoreAway) {

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
        } else if (scoreHome == scoreAway) {
            team.setForm(team.getForm() + "D");
            team.setDraws(team.getDraws() + 1);
            team.setPoints(team.getPoints() + 1);
        } else {
            team.setForm(team.getForm() + "L");
            team.setLoses(team.getLoses() + 1);
        }
        team.setGames(team.getGames() + 1);

        if (team.getForm().length() > 5)
            team.setForm(team.getForm().substring(team.getForm().length() - 5));

        teamCompetitionDetailRepository.save(team);
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

    private List<Human> getBestEleven(long teamId) {

        List<Human> players = humanRepository
                .findAllByTeamIdAndTypeId(teamId, TypeNames.HUMAN_TYPE)
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
        if (playerTransferView.getRating() < desiredPlayer.getMinRating() - 50)
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

    }

    private void initializeCompetitions() {

        List<List<Integer>> values = List.of(List.of(1, 1, 1), List.of(1, 2, 2), List.of(3, 1, 1),
                List.of(4, 2, 2), List.of(1, 3, 1));

        List<String> names = List.of("Gallactick Football First League", "Gallactick Football Cup",
                "Khess First League", "Khess Cup", "Khess Second League");

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
                List.of(5, 6, 16),
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
        long cupId = 6L;

        createTeamsAndCompetitions(teamNames, teamValues, facilities, addedModulo, leagueId, cupId);
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
}
