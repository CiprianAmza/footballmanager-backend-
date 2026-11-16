package com.footballmanagergamesimulator.tacticarena;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.model.Competition;
import com.footballmanagergamesimulator.model.Team;
import com.footballmanagergamesimulator.repository.CompetitionRepository;
import com.footballmanagergamesimulator.repository.TeamRepository;
import com.footballmanagergamesimulator.service.NationService;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/** HTTP surface of Tactic Arena; the contract is {@code docs/TACTIC_ARENA_API.md}. */
@RestController
@RequestMapping("/tactic-arena")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}", allowedHeaders = "*")
public class TacticArenaController {

    @Autowired private TacticArenaService service;
    @Autowired private TeamRepository teamRepository;
    @Autowired private CompetitionRepository competitionRepository;
    @Autowired private NationService nationService;
    @Autowired private TacticController tacticController;
    @Autowired private CurrentUserService currentUserService;

    @GetMapping("/clubs")
    public Map<String, Object> clubs() {
        Map<Long, List<Team>> teamsByCompetition = teamRepository.findAll().stream()
                .collect(Collectors.groupingBy(Team::getCompetitionId));
        List<Competition> leagues = competitionRepository.findAll().stream()
                .filter(Competition::isLeague)
                .filter(c -> teamsByCompetition.containsKey(c.getId()))
                .sorted(Comparator.comparingInt(Competition::getTier)
                        .thenComparing((Competition c) -> c.getReputation() == null ? 0 : c.getReputation(), Comparator.reverseOrder())
                        .thenComparing(Competition::getName, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Competition league : leagues) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("competitionId", league.getId());
            row.put("name", league.getName());
            row.put("nationId", league.getNationId());
            row.put("nationName", nationService.infoFor(league.getNationId()).name());
            row.put("tier", league.getTier());
            List<Map<String, Object>> teams = new ArrayList<>();
            teamsByCompetition.get(league.getId()).stream()
                    .sorted(Comparator.comparingInt(Team::getReputation).reversed().thenComparing(Team::getName))
                    .forEach(team -> teams.add(teamRow(team)));
            row.put("teams", teams);
            rows.add(row);
        }
        return Map.of("leagues", rows);
    }

    @GetMapping("/clubs/{teamId}/setup")
    public Map<String, Object> setup(@PathVariable long teamId) {
        Team team = teamRepository.findById(teamId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such club"));
        Map<String, Object> info = teamRow(team);
        info.put("competitionId", team.getCompetitionId());
        info.put("competitionName", competitionRepository.findById(team.getCompetitionId()).map(Competition::getName).orElse(null));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("team", info);
        body.put("players", TacticArenaSupport.players(tacticController, teamId));
        body.put("formationCatalog", tacticController.formationCatalog(String.valueOf(teamId)));
        body.put("savedTactic", tacticController.getFormation(String.valueOf(teamId)));
        body.put("teamView", tacticController.getTeamTacticView(teamId).getBody());
        return body;
    }

    private static Map<String, Object> teamRow(Team team) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("teamId", team.getId());
        row.put("name", team.getName());
        row.put("reputation", team.getReputation());
        row.put("color1", team.getColor1());
        row.put("color2", team.getColor2());
        return row;
    }

    @PostMapping("/tests")
    public Map<String, Object> create(@RequestBody TacticArenaService.CreateRequest request, HttpServletRequest http) {
        User user = currentUserService.getUserOrNull(http);
        String owner = user == null ? "unknown" : user.getUsername();
        return Map.of("id", service.create(request, owner));
    }

    @GetMapping("/tests")
    public Map<String, Object> list() {
        return service.list();
    }

    @GetMapping("/tests/{id}")
    public Map<String, Object> detail(@PathVariable long id) {
        return service.detail(id);
    }

    @PostMapping("/tests/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable long id) {
        return service.cancel(id);
    }

    @DeleteMapping("/tests/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/tests/{id}/runs/{runId}/images/{kind}")
    public ResponseEntity<Void> putImage(@PathVariable long id, @PathVariable long runId, @PathVariable String kind,
                                         @RequestHeader(value = "Content-Type", required = false) String contentType,
                                         @RequestBody byte[] body) {
        service.putImage(id, runId, kind, contentType, body);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/tests/{id}/runs/{runId}/images/{kind}")
    public ResponseEntity<byte[]> image(@PathVariable long id, @PathVariable long runId, @PathVariable String kind) {
        TacticArenaImage image = service.image(id, runId, kind);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.getContentType())).body(image.getData());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> error(ResponseStatusException e) {
        return ResponseEntity.status(e.getStatusCode())
                .body(Map.of("error", e.getReason() == null ? e.getStatusCode().toString() : e.getReason()));
    }
}
