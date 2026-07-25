package com.footballmanagergamesimulator.compartment.calibration;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

record CalibrationRunConfig(long seed, Path outputDirectory, List<Integer> finishBuckets,
                            Stage fastSweep, FullLeague fullLeague, Path sourceFile) {
    private static final Path DEFAULT_FILE = Path.of("config", "compartment-calibration.yml");

    static CalibrationRunConfig load() {
        Path path = Path.of(System.getProperty("compartment.calibration.config",
                DEFAULT_FILE.toString())).toAbsolutePath().normalize();
        try (InputStream input = Files.newInputStream(path)) {
            Object document = new Yaml().load(input);
            Map<String, Object> root = map(document, "root");
            Map<String, Object> calibration = map(root.get("calibration"), "calibration");
            Stage sweep = stage(map(calibration.get("fast-sweep"), "calibration.fast-sweep"));
            Map<String, Object> leagueMap = map(calibration.get("full-league"), "calibration.full-league");
            FullLeague league = new FullLeague(
                    positiveInt(leagueMap.get("seasons"), "full-league.seasons"),
                    positiveInt(leagueMap.get("top-weight-count"), "full-league.top-weight-count"),
                    doubles(leagueMap.get("percentages"), "full-league.percentages"),
                    strings(leagueMap.get("selected-weights"), "full-league.selected-weights"));
            Path output = Path.of(string(calibration.get("output-directory"), "output-directory"));
            return new CalibrationRunConfig(
                    number(calibration.get("seed"), "seed").longValue(),
                    output,
                    integers(calibration.get("finish-buckets"), "finish-buckets"),
                    sweep, league, path);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load calibration config " + path, exception);
        }
    }

    private static Stage stage(Map<String, Object> values) {
        return new Stage(positiveInt(values.get("seasons"), "fast-sweep.seasons"),
                doubles(values.get("percentages"), "fast-sweep.percentages"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " must be a map");
        return (Map<String, Object>) map;
    }

    private static Number number(Object value, String name) {
        if (!(value instanceof Number number)) throw new IllegalArgumentException(name + " must be numeric");
        return number;
    }

    private static int positiveInt(Object value, String name) {
        int result = number(value, name).intValue();
        if (result < 1) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    private static String string(Object value, String name) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException(name + " must be non-empty");
        return text;
    }

    private static List<Double> doubles(Object value, String name) {
        if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException(name + " must be a non-empty list");
        List<Double> values = list.stream().map(item -> number(item, name).doubleValue()).toList();
        if (values.stream().anyMatch(item -> !Double.isFinite(item) || item == 0.0 || item <= -100.0)) {
            throw new IllegalArgumentException(name + " values must be finite, non-zero, and greater than -100");
        }
        return values;
    }

    private static List<Integer> integers(Object value, String name) {
        if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException(name + " must be a non-empty list");
        return list.stream().map(item -> positiveInt(item, name)).distinct().sorted().toList();
    }

    private static List<String> strings(Object value, String name) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException(name + " must be a list");
        return list.stream().map(item -> string(item, name)).distinct().toList();
    }

    record Stage(int seasons, List<Double> percentages) { }
    record FullLeague(int seasons, int topWeightCount, List<Double> percentages,
                      List<String> selectedWeights) { }
}
