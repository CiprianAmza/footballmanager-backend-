package com.footballmanagergamesimulator.controller;

import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.frontend.PlayerCardView;
import com.footballmanagergamesimulator.model.Human;
import com.footballmanagergamesimulator.model.Player;
import com.footballmanagergamesimulator.model.PlayerSkills;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.HumanRepository;
import com.footballmanagergamesimulator.repository.PlayerSkillsRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.service.PlayerCardService;
import com.footballmanagergamesimulator.service.PlayerSkillsService;
import com.footballmanagergamesimulator.service.PlayerMarketAvailabilityService;
import com.footballmanagergamesimulator.service.PlayerPreviewService;
import com.footballmanagergamesimulator.util.TypeNames;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;



@RestController
@RequestMapping("/humans")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
public class HumanController {

    HumanRepository humanRepository;
    TeamRepository teamRepository;
    PlayerSkillsRepository playerSkillsRepository;
    NationService nationService;
    PlayerCardService playerCardService;
    @Autowired PlayerMarketAvailabilityService marketAvailabilityService;
    @Autowired PlayerPreviewService playerPreviewService;

    @Autowired
    public HumanController(HumanRepository humanRepository,
                           TeamRepository teamRepository,
                           PlayerSkillsRepository playerSkillsRepository,
                           NationService nationService,
                           PlayerCardService playerCardService) {

        this.humanRepository = humanRepository;
        this.teamRepository = teamRepository;
        this.playerSkillsRepository = playerSkillsRepository;
        this.nationService = nationService;
        this.playerCardService = playerCardService;
    }

    @GetMapping("/allPlayers")
    public List<PlayerView> getAllPlayers() {

        return humanRepository
                .findAll()
                .stream()
                .filter(human -> human.getTypeId() == 1 && !human.isRetired())
                .map(this::buildPlayerView)
                .toList();
    }

    /** Search pool used by Scouting. Market state is returned so the client can
     * show available, transferred and loaned cohorts without another request. */
    @GetMapping("/scoutingPlayers")
    public List<PlayerView> getScoutingPlayers() {
        Map<Long, PlayerMarketAvailabilityService.MarketState> states =
                marketAvailabilityService.currentSeasonStates();
        List<Human> players = humanRepository.findAllByTypeId(TypeNames.PLAYER_TYPE).stream()
                .filter(player -> !player.isRetired())
                .toList();
        int season = marketAvailabilityService.currentSeason();
        Map<Long, PlayerPreviewService.Preview> previews = playerPreviewService.previews(players, season);
        return players.stream().map(player -> {
            PlayerView view = buildPlayerView(player, false);
            applyPreview(view, previews.get(player.getId()));
            applyMarketState(view, marketAvailabilityService.stateFor(player.getId(), states));
            return view;
        }).toList();
    }

    private void applyMarketState(PlayerView view, PlayerMarketAvailabilityService.MarketState state) {
        view.setMarketStatus(state.status());
        view.setTransferredThisSeason(state.transferredThisSeason());
        view.setLoanedThisSeason(state.loanedThisSeason());
        view.setLoaned(state.loaned());
        view.setParentTeamId(state.parentTeamId());
        view.setParentTeamName(state.parentTeamName());
        view.setLoanTeamId(state.loanTeamId());
        view.setLoanTeamName(state.loanTeamName());
    }

    @GetMapping("/playerPositions")
    public List<String> getPlayerPositions() {
        return marketAvailabilityService.activePlayerPositions();
    }

    @GetMapping("/{playerId}")
    public PlayerView getById(@PathVariable(name = "playerId") Long playerId) {

        if (playerId == null)
            return null;

        Optional<Human> human = humanRepository.findById(playerId);
        if (human.isEmpty())
            return null;

        PlayerView playerView = buildPlayerView(human.get());

        return playerView;
    }

    @GetMapping("/{playerId}/card")
    public PlayerCardView getCard(@PathVariable(name = "playerId") Long playerId) {

        if (playerId == null)
            return null;

        return playerCardService.getPlayerCard(playerId).orElse(null);
    }
    
    @GetMapping("/compare/{playerId1}/{playerId2}")
    public Map<String, Object> comparePlayers(@PathVariable long playerId1, @PathVariable long playerId2) {
        Map<String, Object> result = new LinkedHashMap<>();

        Human p1 = humanRepository.findById(playerId1).orElse(null);
        Human p2 = humanRepository.findById(playerId2).orElse(null);
        if (p1 == null || p2 == null) {
            result.put("error", "Player not found");
            return result;
        }

        result.put("player1", buildPlayerView(p1));
        result.put("player2", buildPlayerView(p2));
        return result;
    }

    public PlayerView buildPlayerView(Human player) { // todo move into service
        return buildPlayerView(player, true);
    }

    private PlayerView buildPlayerView(Human player, boolean loadAllSkills) {

        PlayerView playerView = new PlayerView();
        Team team;

        // Being clubless no longer means retired: an expired contract drops a player
        // into free agency, and the old "teamId == null => retired" shorthand labelled
        // those players N/A as though their careers were over.
        playerView.setRetired(player.isRetired());
        playerView.setFreeAgent(player.getTeamId() == null && !player.isRetired());

        if (player.getTeamId() == null) {
            playerView.setTeamName(player.isRetired() ? "Retired" : "Free Agent");
        } else {
            Optional<Team> possibleTeam = teamRepository.findById(player.getTeamId());
            team = possibleTeam.get();
            playerView.setTeamName(team.getName()); // todo team can never be null, which means Free Agent is not yet possible - maybe set teamID to 0 in that case
        }

        playerView.setId(player.getId());
        playerView.setName(player.getName());
        playerView.setTeamId(player.getTeamId() != null ? player.getTeamId() : 0);
        playerView.setPosition(player.getPosition());
        playerView.setRating(player.getRating());
        playerView.setAge(player.getAge());

        playerView.setMorale(player.getMorale());
        playerView.setFitness(player.getFitness());
        playerView.setCurrentStatus(player.getCurrentStatus());

        playerView.setSalary(player.getSalary());
        playerView.setAgreedPlayingTime(player.getAgreedPlayingTime());
        playerView.setContractStartDate(player.getContractStartDate());
        playerView.setContractEndDate(player.getContractEndDate());

        playerView.setContractEndSeason(player.getContractEndSeason());
        playerView.setWage(player.getWage());
        playerView.setReleaseClause(player.getReleaseClause());
        playerView.setTransferValue(player.getTransferValue());
        playerView.setWillNeverLeave(player.isWillNeverLeave());
        playerView.setStayForward(player.isStayForward());

        playerView.setWealth(player.getWealth());
        playerView.setSeasonCreated(player.getSeasonCreated());

        playerView.setBestEverRating(player.getBestEverRating());
        playerView.setSeasonOfBestEverRating(player.getSeasonOfBestEverRating());

        List<String> skillNames = new ArrayList<>();
        List<Long> skillValues = new ArrayList<>();

        Optional<PlayerSkills> playerSkills = loadAllSkills
                ? playerSkillsRepository.findPlayerSkillsByPlayerId(player.getId()) : Optional.empty();

        if (playerSkills.isPresent()) {
            PlayerSkills ps = playerSkills.get();
            boolean isGK = "GK".equals(player.getPosition());

            // For GK: show GK attrs first, then mental + physical
            // For outfield: show Technical, Mental, Physical (skip GK attrs)
            if (isGK) {
                for (String attr : PlayerSkillsService.GOALKEEPER) {
                    skillNames.add(attr);
                    skillValues.add((long) PlayerSkillsService.GETTER_MAP.get(attr).apply(ps));
                }
            }
            for (String attr : PlayerSkillsService.TECHNICAL) {
                if (isGK) continue; // skip technical for GK display (low values not useful)
                skillNames.add(attr);
                skillValues.add((long) PlayerSkillsService.GETTER_MAP.get(attr).apply(ps));
            }
            for (String attr : PlayerSkillsService.MENTAL) {
                skillNames.add(attr);
                skillValues.add((long) PlayerSkillsService.GETTER_MAP.get(attr).apply(ps));
            }
            for (String attr : PlayerSkillsService.PHYSICAL) {
                skillNames.add(attr);
                skillValues.add((long) PlayerSkillsService.GETTER_MAP.get(attr).apply(ps));
            }
        }

        playerView.setSkillNames(skillNames);
        playerView.setSkillValues(skillValues);

        // Add physical profile
        playerView.setPreferredFoot(player.getPreferredFoot());
        playerView.setHeightCm(player.getHeightCm());
        playerView.setWeightKg(player.getWeightKg());

        // Nation (derived via team -> competition -> nationId)
        NationService.NationInfo nation = nationService.infoForTeam(player.getTeamId());
        playerView.setNationId(nation.id());
        playerView.setNationName(nation.name());
        playerView.setNationFlagCode(nation.flagCode());

        // Face descriptor
        playerView.setBaseFaceId(player.getBaseFaceId());
        playerView.setSkinTone(player.getSkinTone());
        playerView.setHairStyle(player.getHairStyle());
        playerView.setHairColor(player.getHairColor());
        playerView.setEyeColor(player.getEyeColor());
        playerView.setFaceShape(player.getFaceShape());
        playerView.setNoseShape(player.getNoseShape());
        playerView.setEyeShape(player.getEyeShape());
        playerView.setMouthShape(player.getMouthShape());
        playerView.setBrowShape(player.getBrowShape());
        playerView.setSpecies(player.getSpecies());

        return playerView;
    }

    private void applyPreview(PlayerView view, PlayerPreviewService.Preview preview) {
        if (preview == null) return;
        view.setSeasonAppearances(preview.appearances());
        view.setSeasonGoals(preview.goals());
        view.setSeasonAssists(preview.assists());
        view.setImportantAttributes(preview.importantAttributes());
    }
}
