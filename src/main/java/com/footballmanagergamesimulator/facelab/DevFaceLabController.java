package com.footballmanagergamesimulator.facelab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * DEV-ONLY Face Lab endpoint: serves the current generation of face genomes to
 * {@code /dev/face-gallery} and persists the 1-100 ratings the user gives them.
 *
 * <p>Wiring: the bean only exists when {@code facelab.enabled=true} (set in the local
 * {@code application.properties}, absent from the packaged {@code application.yml}), so
 * nothing here is reachable in a production deployment.
 *
 * <p>Storage is plain files under {@code facelab.data-dir} — the Python side
 * ({@code face-lab/}) reads and writes the exact same layout, so the two halves never
 * need a shared database:
 * <pre>
 *   &lt;data-dir&gt;/generations/gen-&lt;N&gt;.json   { "generation": N, "genomes": [ … ] }
 *   &lt;data-dir&gt;/votes.jsonl                 one {genome, rating, ts, generation} per line
 *   &lt;data-dir&gt;/pairs.jsonl                 one A/B comparison per line
 * </pre>
 *
 * <p>The genome schema itself is deliberately opaque here (stored as raw JSON): it is
 * owned by face-genome.ts and mirrored in face-lab/facelab/genome.py, so adding an axis
 * never requires a backend change.
 */
@RestController
@RequestMapping("/api/dev/facelab")
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:4200}")
@ConditionalOnProperty(name = "facelab.enabled", havingValue = "true")
public class DevFaceLabController {

    private static final Pattern GEN_FILE = Pattern.compile("^gen-(\\d+)\\.json$");
    /** Guards against a runaway client filling the disk with generation files. */
    private static final int MAX_GENERATION = 10_000;
    private static final int MAX_GENOMES_PER_BATCH = 500;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path dataDir;

    public DevFaceLabController(@Value("${facelab.data-dir:face-lab/data}") String dataDir) {
        this.dataDir = Paths.get(dataDir).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ status

    @GetMapping("/status")
    public Map<String, Object> status() {
        List<Integer> generations = listGenerations();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataDir", dataDir.toString());
        out.put("generations", generations);
        out.put("latestGeneration", generations.isEmpty() ? -1 : generations.get(generations.size() - 1));
        out.put("voteCount", countLines(votesFile()));
        out.put("pairCount", countLines(pairsFile()));
        return out;
    }

    // ------------------------------------------------------------------ batch

    /** Serve one generation of genomes; defaults to the newest one on disk. */
    @GetMapping("/batch")
    public ResponseEntity<?> batch(@RequestParam(required = false) Integer generation) {
        List<Integer> generations = listGenerations();
        if (generations.isEmpty())
            return ResponseEntity.status(404).body(Map.of("error", "no generation on disk yet"));

        int gen = generation != null ? generation : generations.get(generations.size() - 1);
        Path file = generationFile(gen);
        if (!Files.isRegularFile(file))
            return ResponseEntity.status(404).body(Map.of("error", "generation " + gen + " not found"));

        try {
            JsonNode node = mapper.readTree(Files.readAllBytes(file));
            ObjectNode out = mapper.createObjectNode();
            out.put("generation", gen);
            out.set("genomes", node.has("genomes") ? node.get("genomes") : mapper.createArrayNode());
            out.put("source", file.toString());
            return ResponseEntity.ok(out);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "unreadable: " + e.getMessage()));
        }
    }

    /** Store a generation produced elsewhere (the gallery's local sampler, or face-lab/). */
    @PostMapping("/generation")
    public ResponseEntity<?> putGeneration(@RequestBody ObjectNode body) {
        int gen = body.path("generation").asInt(-1);
        if (gen < 0 || gen > MAX_GENERATION)
            return ResponseEntity.badRequest().body(Map.of("error", "generation must be 0.." + MAX_GENERATION));
        JsonNode genomes = body.path("genomes");
        if (!genomes.isArray() || genomes.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "genomes must be a non-empty array"));
        if (genomes.size() > MAX_GENOMES_PER_BATCH)
            return ResponseEntity.badRequest().body(Map.of("error", "at most " + MAX_GENOMES_PER_BATCH + " genomes"));

        ObjectNode out = mapper.createObjectNode();
        out.put("generation", gen);
        out.put("savedAt", Instant.now().toString());
        out.set("genomes", genomes);
        try {
            Files.createDirectories(generationsDir());
            Files.write(generationFile(gen), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(out));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "write failed: " + e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("generation", gen, "genomes", genomes.size()));
    }

    // ------------------------------------------------------------------ votes

    /** Append 1-100 ratings. One JSON object per line so the Python side can stream it. */
    @PostMapping("/votes")
    public ResponseEntity<?> putVotes(@RequestBody ObjectNode body) {
        int gen = body.path("generation").asInt(0);
        JsonNode votes = body.path("votes");
        if (!votes.isArray() || votes.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "votes must be a non-empty array"));

        String ts = Instant.now().toString();
        List<String> lines = new ArrayList<>();
        for (JsonNode v : votes) {
            int rating = v.path("rating").asInt(-1);
            if (rating < 1 || rating > 100)
                return ResponseEntity.badRequest().body(Map.of("error", "rating must be 1..100, got " + rating));
            ObjectNode row = mapper.createObjectNode();
            row.put("ts", ts);
            row.put("generation", gen);
            row.put("genomeId", v.path("genomeId").asText(""));
            row.put("rating", rating);
            row.set("genome", v.path("genome"));
            try {
                lines.add(mapper.writeValueAsString(row));
            } catch (IOException e) {
                return ResponseEntity.status(500).body(Map.of("error", "serialise failed: " + e.getMessage()));
            }
        }
        try {
            appendLines(votesFile(), lines);
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "write failed: " + e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("stored", lines.size(), "totalVotes", countLines(votesFile())));
    }

    /** Append one A/B preference (optional signal — the 1-100 ratings stay the primary one). */
    @PostMapping("/pairs")
    public ResponseEntity<?> putPair(@RequestBody ObjectNode body) {
        String winner = body.path("winnerId").asText("");
        if (winner.isEmpty())
            return ResponseEntity.badRequest().body(Map.of("error", "winnerId required"));
        ObjectNode row = body.deepCopy();
        row.put("ts", Instant.now().toString());
        try {
            appendLines(pairsFile(), List.of(mapper.writeValueAsString(row)));
        } catch (IOException e) {
            return ResponseEntity.status(500).body(Map.of("error", "write failed: " + e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("stored", 1, "totalPairs", countLines(pairsFile())));
    }

    // ------------------------------------------------------------------ paths / io

    private Path generationsDir() { return dataDir.resolve("generations"); }

    /** Built from a validated int, so no user-controlled path segment ever reaches the FS. */
    private Path generationFile(int gen) { return generationsDir().resolve("gen-" + gen + ".json"); }

    private Path votesFile() { return dataDir.resolve("votes.jsonl"); }

    private Path pairsFile() { return dataDir.resolve("pairs.jsonl"); }

    private void appendLines(Path file, List<String> lines) throws IOException {
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        Files.write(file, sb.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private List<Integer> listGenerations() {
        if (!Files.isDirectory(generationsDir())) return List.of();
        try (Stream<Path> s = Files.list(generationsDir())) {
            List<Integer> out = new ArrayList<>();
            s.forEach(p -> {
                Matcher m = GEN_FILE.matcher(p.getFileName().toString());
                if (m.matches()) out.add(Integer.parseInt(m.group(1)));
            });
            Collections.sort(out);
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private long countLines(Path file) {
        if (!Files.isRegularFile(file)) return 0;
        try (Stream<String> s = Files.lines(file, StandardCharsets.UTF_8)) {
            return s.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            return 0;
        }
    }
}
