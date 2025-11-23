package com.footballmanagergamesimulator.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.footballmanagergamesimulator.frontend.*;
import com.footballmanagergamesimulator.model.*;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import com.footballmanagergamesimulator.repository.*;
import com.footballmanagergamesimulator.service.TacticService;
import com.footballmanagergamesimulator.util.TypeNames;
import jakarta.annotation.PostConstruct;
import com.fasterxml.jackson.core.type.TypeReference;     // <--- ACESTA ESTE IMPORTUL CORECT
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.parameters.P;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/tactic")
@CrossOrigin(origins = "*")
public class TacticController {

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
            return null; // Frontend-ul va primi null și va folosi tactica default
        }

        PersonalizedTactic savedTactic = tacticOpt.get();
        PersonalizedTacticView view = new PersonalizedTacticView();

        view.setTeamId(savedTactic.getTeamId());
        view.setTactic(savedTactic.getTactic());
        view.setMentality(savedTactic.getMentality());
        view.setTimeWasting(savedTactic.getTimeWasting());
        view.setInPossession(savedTactic.getInPossession());
        view.setPassingType(savedTactic.getPassingType());
        view.setTempo(savedTactic.getTempo());

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
     * Endpoint ACTUALIZAT pentru a salva pozițiile exacte (JSON)
     */
    @PostMapping("/saveFormation")
    public void saveFormation(@RequestBody PersonalizedTacticView personalizedTacticView) {

        List<FormationData> formationDataList = personalizedTacticView.getFormationDataList();

        // Nu mai refuzăm salvarea dacă sunt < 11 jucători, poate managerul vrea să salveze o tactică incompletă temporar
        // if (formationDataList.size() < 11) return;

        PersonalizedTactic personalizedTactic = new PersonalizedTactic();
        personalizedTactic.setTeamId(personalizedTacticView.getTeamId());

        // 🔹 MODIFICARE MAJORĂ: Salvăm lista ca JSON String pentru a păstra positionIndex
        // Vechea metodă cu "GK:1,DC:2" pierdea informația despre unde exact pe grilă se află jucătorul.
        try {
            String jsonFormation = objectMapper.writeValueAsString(formationDataList);
            personalizedTactic.setFirst11(jsonFormation);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            throw new RuntimeException("Error converting formation to JSON");
        }

        personalizedTactic.setTactic(personalizedTacticView.getTactic());
        personalizedTactic.setMentality(personalizedTacticView.getMentality());
        personalizedTactic.setTimeWasting(personalizedTacticView.getTimeWasting());
        personalizedTactic.setInPossession(personalizedTacticView.getInPossession());
        personalizedTactic.setPassingType(personalizedTacticView.getPassingType());
        personalizedTactic.setTempo(personalizedTacticView.getTempo());

        Optional<PersonalizedTactic> existingTactic = personalizedTacticRepository.findPersonalizedTacticByTeamId(personalizedTactic.getTeamId());
        if (existingTactic.isPresent()) {
            // Păstrăm ID-ul pentru update, ca să nu ștergem și să inserăm iar (mai eficient)
            personalizedTactic.setId(existingTactic.get().getId());
            personalizedTacticRepository.save(personalizedTactic);
        } else {
            personalizedTacticRepository.save(personalizedTactic);
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

        return allPlayers;
    }

    @GetMapping("/getAllPossibleTactics/{teamId}")
    private List<TacticView> getAllPossibleTactics(@PathVariable(name = "teamId") String teamId) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        List<TacticView> tacticViews = new ArrayList<>();
        List<String> allTactics = tacticService.getAllExistingTactics();
        for (String tactic: allTactics) {
            List<PlayerView> bestEleven = getBestElevenPlayers(team, tacticService.getRoomInTeamByTactic(tactic));

            TacticView tacticView = new TacticView();
            tacticView.setTacticName(tactic);
            tacticView.setTotalRating(bestEleven.stream().mapToDouble(PlayerView::getRating).sum());

            tacticViews.add(tacticView);
        }

        return tacticViews
                .stream()
                .sorted((x, y) -> Double.compare(y.getTotalRating(), x.getTotalRating()))
                .toList();
    }

    @GetMapping("/getBestEleven/{teamId}/{tactic}")
    public List<PlayerView> getBestEleven(@PathVariable(name = "teamId") String teamId, @PathVariable(name = "tactic", required = false) String tactic) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        Map<String, Integer> tacticFormat = tacticService.getRoomInTeamByTactic(tactic);

        return getBestElevenPlayers(team, tacticFormat);
    }

    @GetMapping("/getSubstitutions/{teamId}/{tactic}")
    public List<PlayerView> getSubstitutions(@PathVariable(name = "teamId") String teamId, @PathVariable(name = "tactic", required = false) String tactic) {

        long _teamId = Long.parseLong(teamId);

        Team team = teamRepository.findById(_teamId).orElse(null);
        if (team == null)
            throw new RuntimeException("Team not found.");

        Map<String, Integer> tacticFormat = tacticService.getRoomInTeamByTactic(tactic);

        Map<String, Integer> substitutionFormat = tacticService.getSubstitutionsInTeamByTactic(tactic);

        List<PlayerView> firstEleven = getBestElevenPlayers(team, tacticFormat);
        List<PlayerView> substitutions = getBestSubstitutions(team, substitutionFormat, firstEleven);

        return substitutions;
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

    private List<PlayerView> getBestElevenPlayers(Team team, Map<String, Integer> tacticFormat) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
        List<PlayerView> players = allPlayers.stream().map(player -> adaptPlayer(player, team)).toList();
        Map<String, List<PlayerView>> positionToPlayers = new HashMap<>();

        for (PlayerView playerView: players) {
            if (!positionToPlayers.containsKey(playerView.getPosition()))
                positionToPlayers.put(playerView.getPosition(), new ArrayList<>());
            positionToPlayers.get(playerView.getPosition()).add(playerView);
        }

        Map<String, List<PlayerView>> bestEleven = new HashMap<>();
        int available = 11;
        List<PlayerView> restPlayers = new ArrayList<>();

        for (Map.Entry<String, Integer> entry: tacticFormat.entrySet()) {
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

        for (int i = firstEleven.size(); i < 11; i++) {
            PlayerView playerView = new PlayerView();
            playerView.setAge(16);
            playerView.setName("Generated");
            playerView.setPosition("MC");
            playerView.setRating(10);

            firstEleven.add(playerView);
        }

        return firstEleven
                .stream()
                .sorted((p1, p2) ->
                        Integer.compare(
                                tacticService.getValueForTacticDisplay(p1.getPosition()),
                                tacticService.getValueForTacticDisplay(p2.getPosition())))
                .toList();
    }

    private List<PlayerView> getBestSubstitutions(Team team, Map<String, Integer> substitutionFormat, List<PlayerView> playersInFirstEleven) {

        List<Human> allPlayers = humanRepository.findAllByTeamIdAndTypeId(team.getId(), TypeNames.PLAYER_TYPE);
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

    private PlayerView adaptPlayer(Human human, Team team) {

        PlayerView playerView = new PlayerView();
        playerView.setId(human.getId());
        playerView.setAge(human.getAge());
        playerView.setPosition(human.getPosition());
        playerView.setRating(human.getRating());
        playerView.setName(human.getName());
        playerView.setTeamName(team.getName());
        playerView.setMorale(human.getMorale());

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
    public List<ManagerTeamTacticView> getTeamRatingByManagerTacticForCompetitionId(long competitionId) {

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
}
