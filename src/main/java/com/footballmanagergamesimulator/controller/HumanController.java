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

        PlayerView playerView = new PlayerView();
        Team team;

        if (player.getTeamId() == null) { // player is retired
            playerView.setTeamName("N/A");
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

        playerView.setWealth(player.getWealth());
        playerView.setSeasonCreated(player.getSeasonCreated());

        playerView.setBestEverRating(player.getBestEverRating());
        playerView.setSeasonOfBestEverRating(player.getSeasonOfBestEverRating());

        List<String> skillNames = new ArrayList<>();
        List<Long> skillValues = new ArrayList<>();

        Optional<PlayerSkills> playerSkills = playerSkillsRepository.findPlayerSkillsByPlayerId(player.getId());

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

        return playerView;
    }
}
