package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.databind.JsonNode;
import com.footballmanagergamesimulator.user.CurrentUserService;
import com.footballmanagergamesimulator.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/** HTTP surface of Tactic Arena; the contract is {@code docs/TACTIC_ARENA_API.md}. */
@RestController
@RequestMapping("/tactic-arena")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}", allowedHeaders = "*")
public class TacticArenaController {

    @Autowired private TacticArenaService service;
    @Autowired private CurrentUserService currentUserService;
    @Autowired private com.footballmanagergamesimulator.controller.TacticController tacticController;

    // Static catalogs of the tactics editor, public here (the /tactic/... originals need a login).

    @GetMapping("/roles/{position}")
    public java.util.List<Map<String, Object>> roles(@PathVariable String position) {
        return tacticController.getRolesForPosition(position);
    }

    @GetMapping("/instructions/{position}")
    public java.util.List<Map<String, Object>> instructions(@PathVariable String position) {
        return tacticController.getInstructionsForPosition(position);
    }

    @GetMapping("/formationLayout/{tactic}")
    public java.util.List<com.footballmanagergamesimulator.controller.TacticController.FormationCell> formationLayout(@PathVariable String tactic) {
        return tacticController.formationLayout(tactic);
    }

    @GetMapping("/ratingTiers")
    public Map<String, Object> ratingTiers() {
        return tacticController.getRatingTiers();
    }

    @GetMapping("/baseline")
    public Map<String, Object> baseline() {
        return service.baselineStatus();
    }

    @PostMapping("/baseline/refresh")
    public Map<String, Object> refreshBaseline() {
        return service.refreshBaseline();
    }

    @PostMapping("/baseline/rebuild")
    public Map<String, Object> rebuildBaseline() {
        return service.rebuildBaseline();
    }

    @GetMapping("/clubs")
    public JsonNode clubs() {
        return service.clubs();
    }

    @GetMapping("/clubs/{teamId}/setup")
    public JsonNode setup(@PathVariable long teamId) {
        return service.setup(teamId);
    }

    @PostMapping("/tests")
    public Map<String, Object> create(@RequestBody TacticArenaService.CreateRequest request, HttpServletRequest http) {
        // Owner: the signed-in user's name, else the optional "owner" of the body, else "guest".
        User user = currentUserService.getUserOrNull(http);
        String owner = user != null ? user.getUsername()
                : request.owner() == null || request.owner().isBlank() ? "guest" : request.owner().trim();
        if (owner.length() > 40) owner = owner.substring(0, 40);
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
    public ResponseEntity<Map<String, Object>> error(ResponseStatusException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", e.getReason() == null ? e.getStatusCode().toString() : e.getReason());
        if (e.getStatusCode().value() == HttpStatus.CONFLICT.value()) body.put("baseline", service.baselineStatus());
        return ResponseEntity.status(e.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
