package com.footballmanagergamesimulator.tacticarena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Files on disk under {@code tactic-arena.export-dir}, so results survive a database wipe
 * and can be committed: {@code <dir>/<id>-<short-name>/result.json.gz} (the full test as
 * {@code GET /tactic-arena/tests/{id}} returns it, gzip) and
 * {@code <runIndex>-<kind>.webp|png} for every image. Missing tests are imported back at
 * startup, keyed by the test {@code uid}.
 */
@Component
public class TacticArenaExporter {

    private static final Logger log = LoggerFactory.getLogger(TacticArenaExporter.class);
    private static final Pattern IMAGE_FILE = Pattern.compile("(\\d)-(tactic|schedule|competitions)\\.(webp|png)");

    @Value("${tactic-arena.export-dir:}") private String exportDir;

    @Autowired @Lazy private TacticArenaService service;
    @Autowired private TacticArenaTestRepository testRepository;
    @Autowired private TacticArenaRunRepository runRepository;
    @Autowired private TacticArenaRunResultRepository resultRepository;
    @Autowired private TacticArenaImageRepository imageRepository;
    @Autowired private TacticArenaGitPublisher gitPublisher;
    @Autowired private ObjectMapper objectMapper;

    private Path root() {
        return exportDir == null || exportDir.isBlank() ? null : Path.of(exportDir.trim());
    }

    void exportTest(long testId) throws IOException {
        Path root = root();
        if (root == null) return;
        TacticArenaTest test = service.require(testId);
        Path folder = root.resolve(folderName(root, test));
        Files.createDirectories(folder);
        byte[] json = objectMapper.writeValueAsBytes(service.detail(test, true));
        Path tmp = folder.resolve("result.json.gz.tmp");
        Files.write(tmp, TacticArenaSupport.gzip(json));
        Files.move(tmp, folder.resolve("result.json.gz"), StandardCopyOption.REPLACE_EXISTING);
        List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(testId);
        for (TacticArenaImage image : imageRepository.findAllByTestId(testId)) {
            runs.stream().filter(r -> r.getId() == image.getRunId()).findFirst()
                    .ifPresent(run -> writeImage(folder, run.getRunIndex(), image.getKind(), image.getContentType(), image.getData()));
        }
        test.setExportFolder(folder.getFileName().toString());
        testRepository.save(test);
        log.info("[tactic-arena] test {} exported to {}", testId, folder);
        gitPublisher.request("Tactic test " + test.getId() + ": " + test.getName());
    }

    void exportImage(long testId, int runIndex, String kind, String contentType, byte[] data) {
        Path root = root();
        if (root == null) return;
        TacticArenaTest test = service.require(testId);
        if (test.getExportFolder() == null) return; // written with the test when it finishes
        writeImage(root.resolve(test.getExportFolder()), runIndex, kind, contentType, data);
        gitPublisher.request("Tactic test " + test.getId() + ": " + test.getName() + " (images)");
    }

    private String folderName(Path root, TacticArenaTest test) {
        if (test.getExportFolder() != null) return test.getExportFolder();
        String name = test.getId() + "-" + TacticArenaSupport.slug(test.getName());
        if (Files.exists(root.resolve(name).resolve("result.json.gz"))) {
            name = name + "-" + test.getUid().substring(0, 8);
        }
        return name;
    }

    private static void writeImage(Path folder, int runIndex, String kind, String contentType, byte[] data) {
        try {
            Files.createDirectories(folder);
            String ext = "image/png".equals(contentType) ? "png" : "webp";
            String other = "png".equals(ext) ? "webp" : "png";
            Files.write(folder.resolve(runIndex + "-" + kind + "." + ext), data);
            Files.deleteIfExists(folder.resolve(runIndex + "-" + kind + "." + other));
        } catch (IOException e) {
            log.warn("[tactic-arena] could not write image {}-{} in {}: {}", runIndex, kind, folder, e.toString());
        }
    }

    /** Import every exported test whose uid is not in the database. */
    void importMissing() {
        Path root = root();
        if (root == null || !Files.isDirectory(root)) return;
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path folder : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path file = folder.resolve("result.json.gz");
                if (!Files.exists(file)) continue;
                try {
                    importFolder(folder, file);
                } catch (Exception e) {
                    log.warn("[tactic-arena] could not import {}: {}", folder, e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("[tactic-arena] could not read export folder {}: {}", root, e.toString());
        }
    }

    private void importFolder(Path folder, Path file) throws IOException {
        JsonNode node = objectMapper.readTree(TacticArenaSupport.gunzip(Files.readAllBytes(file)));
        String uid = node.path("uid").asText(null);
        if (uid == null || testRepository.findByUid(uid).isPresent()) return;

        TacticArenaTest test = new TacticArenaTest();
        test.setUid(uid);
        test.setName(node.path("name").asText());
        test.setOwner(text(node, "owner"));
        test.setFormation(text(node, "formation"));
        test.setTeamId(node.path("teamId").asLong());
        test.setTeamName(text(node, "teamName"));
        test.setLeagueId(node.path("leagueId").asLong());
        test.setLeagueName(text(node, "leagueName"));
        test.setSeason(node.path("season").asInt());
        test.setStartDay(node.path("startDay").asInt());
        test.setEndDay(node.path("endDay").asInt());
        test.setLeagueMatchesTotal(node.path("leagueMatchesTotal").asInt());
        test.setLeagueMatchesRemaining(node.path("leagueMatchesRemaining").asInt());
        test.setEngine(text(node, "engine"));
        test.setStatus(node.path("status").asText(TacticArenaEntities.DONE));
        test.setMessage(text(node, "message"));
        test.setCreatedAt(instant(node, "createdAt"));
        test.setFinishedAt(instant(node, "finishedAt"));
        test.setTacticJson(node.path("tactic").isMissingNode() ? null : objectMapper.writeValueAsString(node.path("tactic")));
        test.setExportFolder(folder.getFileName().toString());
        test = testRepository.save(test);

        for (JsonNode r : node.path("runs")) {
            TacticArenaRun run = new TacticArenaRun();
            run.setTestId(test.getId());
            run.setRunIndex(r.path("index").asInt());
            run.setKind(text(r, "kind"));
            run.setTeamId(r.path("teamId").asLong());
            run.setTeamName(text(r, "teamName"));
            run.setTeamReputation(r.path("teamReputation").asInt());
            run.setStatus(r.path("status").asText(TacticArenaEntities.DONE));
            run.setCurrentDay(r.path("currentDay").asInt());
            run.setSimulatedDays(r.path("simulatedDays").asInt());
            run.setTotalDays(r.path("totalDays").asInt());
            run.setMessage(text(r, "message"));
            run.setStartedAt(instant(r, "startedAt"));
            run.setFinishedAt(instant(r, "finishedAt"));
            JsonNode result = r.path("result");
            if (result.isObject()) run.setTotalsJson(objectMapper.writeValueAsString(service.totals(result)));
            run = runRepository.save(run);
            if (result.isObject()) {
                TacticArenaRunResult stored = new TacticArenaRunResult();
                stored.setRunId(run.getId());
                stored.setTestId(test.getId());
                stored.setGzJson(TacticArenaSupport.gzip(objectMapper.writeValueAsBytes(result)));
                resultRepository.save(stored);
            }
        }

        List<TacticArenaRun> runs = runRepository.findAllByTestIdOrderByRunIndexAsc(test.getId());
        try (Stream<Path> files = Files.list(folder)) {
            for (Path image : files.toList()) {
                Matcher m = IMAGE_FILE.matcher(image.getFileName().toString());
                if (!m.matches()) continue;
                int index = Integer.parseInt(m.group(1));
                TacticArenaRun run = runs.stream().filter(x -> x.getRunIndex() == index).findFirst().orElse(null);
                if (run == null) continue;
                TacticArenaImage stored = new TacticArenaImage();
                stored.setTestId(test.getId());
                stored.setRunId(run.getId());
                stored.setKind(m.group(2));
                stored.setContentType("png".equals(m.group(3)) ? "image/png" : "image/webp");
                stored.setCreatedAt(Instant.now());
                stored.setData(Files.readAllBytes(image));
                imageRepository.save(stored);
            }
        }
        log.info("[tactic-arena] imported test '{}' from {}", test.getName(), folder);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNull() || value.isMissingNode() ? null : value.asText();
    }

    private static Instant instant(JsonNode node, String field) {
        String value = text(node, field);
        try {
            return value == null ? null : Instant.parse(value);
        } catch (Exception e) {
            return null;
        }
    }
}
