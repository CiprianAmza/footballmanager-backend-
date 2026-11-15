package match2d.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import match2d.engine.Sim;
import match2d.engine.Trace;
import match2d.engine.Wt;

/**
 * Punctul de intrare al motorului de meci 2D in Java.
 *
 * <pre>
 *   java -jar match2d.jar serve [--port 8791] [--host 127.0.0.1] [--public-url URL]
 *                                             microserviciul HTTP (vezi {@link Service})
 *   java -jar match2d.jar league [--port 8796] [--state FISIER] ...
 *                                             liga la televizor (vezi match2d.league.LeagueServer)
 *   java -jar match2d.jar batch [--threads N] &lt; lot.json &gt; rezultate.json
 *                                             multe meciuri in paralel, pentru masuratori
 *   java -jar match2d.jar run cerere.json [iesire.json]
 *                                             un meci, cu cronologia intreaga
 *   java -jar match2d.jar trace cerere.json DE_LA PANA_LA
 *                                             starea pas cu pas, pentru compararea cu Python
 *   java -jar match2d.jar weights             toate ponderile din {@link Wt}, cu valorile lor
 * </pre>
 *
 * <p>O cerere e {@code {"home": {...}, "away": {...}, "seed": 1, "minutes": 90}}. Un lot e
 * {@code {"jobs": [cerere, ...], "weights": {"speed.pace": 1.0}, "events": ["GOAL", "SHOT"]}};
 * rezultatul e o lista, in ordinea cererilor, cu {@code summary}, {@code players}, {@code highlights}
 * si evenimentele cerute.
 */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        switch (cmd) {
            case "serve" -> serve(args);
            case "league" -> match2d.league.LeagueServer.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            case "batch" -> {
                // un lot care esueaza (si din lipsa de memorie) iese cu cod de eroare, nu ramane agatat
                try {
                    batch(args);
                } catch (Throwable e) {
                    System.err.println("lotul a esuat: " + e);
                    e.printStackTrace();
                    System.err.flush();
                    System.exit(1);
                }
            }
            case "weights" -> {
                // toate ponderile cu valoarea lor curenta, cate una pe rand: mecanism.cheie = valoare
                for (Map.Entry<String, Double> e : Wt.table().entrySet()) {
                    System.out.println(e.getKey() + " = " + e.getValue());
                }
            }
            case "run" -> {
                if (args.length < 2) {
                    usage();
                }
                run(Path.of(args[1]), args.length > 2 ? Path.of(args[2]) : null);
            }
            case "trace" -> {
                if (args.length < 4) {
                    usage();
                }
                Map<String, Object> req = request(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8));
                System.out.print(Trace.run(team(req, "home"), team(req, "away"),
                        number(req, "seed", 1).longValue(), number(req, "minutes", 90.0).doubleValue(),
                        Integer.parseInt(args[2]), Integer.parseInt(args[3])));
            }
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("folosire: serve [--port N] | league [--port 8796] [--state FISIER] ... | "
                + "batch [--threads N] < lot.json | "
                + "run <cerere.json> [iesire.json] | trace <cerere.json> <de la pasul> <pana la pasul> | weights");
        System.exit(2);
    }

    private static String option(String[] args, String name, String fallback) {
        for (int i = 1; i + 1 < args.length; i++) {
            if (args[i].equals(name)) {
                return args[i + 1];
            }
        }
        return fallback;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(String text) {
        return (Map<String, Object>) Json.parse(text);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> team(Map<String, Object> req, String side) {
        return (Map<String, Object>) req.get(side);
    }

    private static Number number(Map<String, Object> req, String key, Number fallback) {
        Object v = req.get(key);
        return v == null ? fallback : (Number) v;
    }

    private static void serve(String[] args) throws IOException {
        String host = option(args, "--host", "127.0.0.1");
        int port = Integer.parseInt(option(args, "--port", "8791"));
        new Service(option(args, "--public-url", null)).start(host, port);
        System.out.println("Motorul de meci 2D (Java) asculta pe http://" + host + ":" + port
                + "  (Ctrl+C il opreste)");
    }

    private static void run(Path request, Path output) throws IOException {
        Map<String, Object> req = request(Files.readString(request, StandardCharsets.UTF_8));
        long seed = number(req, "seed", 1).longValue();
        double minutes = number(req, "minutes", 90.0).doubleValue();
        int hz = number(req, "recordHz", 10).intValue();
        boolean keep = req.get("keepFrames") == null || Boolean.TRUE.equals(req.get("keepFrames"));
        long t0 = System.nanoTime();
        Sim sim = new Sim(team(req, "home"), team(req, "away"), seed, minutes, hz, keep);
        int[] knockout = Sim.parseKnockout(req.get("knockout"));
        if (knockout != null) {
            sim.knockout(knockout);         // meci eliminatoriu: prelungiri si departajare la egalitate
        }
        Map<String, Object> timeline = sim.run();
        double took = (System.nanoTime() - t0) / 1e9;
        String json = Json.write(timeline);
        if (output != null) {
            Files.writeString(output, json, StandardCharsets.UTF_8);
            int[] score = sim.score();
            System.out.printf("scor %d - %d, simulat in %.2f s%n", score[0], score[1], took);
        } else {
            System.out.write(json.getBytes(StandardCharsets.UTF_8));
            System.out.flush();
        }
    }

    /** Multe meciuri in paralel: citeste lotul de la intrarea standard, scrie rezultatele la iesire. */
    @SuppressWarnings("unchecked")
    private static void batch(String[] args) throws IOException, InterruptedException, ExecutionException {
        Map<String, Object> lot = request(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        Object weights = lot.get("weights");
        if (weights instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                Wt.set(String.valueOf(e.getKey()), ((Number) e.getValue()).doubleValue());
            }
        }
        Set<String> eventTypes = new HashSet<>();
        boolean allEvents = Boolean.TRUE.equals(lot.get("events"));
        if (lot.get("events") instanceof List<?> list) {
            for (Object t : list) {
                eventTypes.add(String.valueOf(t));
            }
        }
        List<Map<String, Object>> jobs = (List<Map<String, Object>>) lot.get("jobs");
        int threads = Integer.parseInt(option(args, "--threads",
                String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors() - 1))));
        // fire daemon: daca firul principal moare, procesul nu mai e tinut in viata de ele
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread th = new Thread(r, "lot");
            th.setDaemon(true);
            return th;
        });
        try {
            batchRun(jobs, pool, threads, allEvents, eventTypes);
        } finally {
            pool.shutdownNow();
        }
    }

    @SuppressWarnings("unchecked")
    private static void batchRun(List<Map<String, Object>> jobs, ExecutorService pool, int threads,
                                 boolean allEvents, Set<String> eventTypes)
            throws IOException, InterruptedException, ExecutionException {
        long t0 = System.nanoTime();
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        for (Map<String, Object> job : jobs) {
            futures.add(pool.submit(() -> {
                long seed = number(job, "seed", 1).longValue();
                double minutes = number(job, "minutes", 90.0).doubleValue();
                // statisticile de pozitie se esantioneaza la fiecare cadru: 10 pe secunda, ca in uneltele Python
                int hz = number(job, "recordHz", 10).intValue();
                Sim sim = new Sim(team(job, "home"), team(job, "away"), seed, minutes, hz, false);
                int[] knockout = Sim.parseKnockout(job.get("knockout"));
                if (knockout != null) {
                    sim.knockout(knockout);
                }
                Map<String, Object> timeline = sim.run();
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("summary", timeline.get("summary"));
                if (knockout != null) {
                    out.put("knockout", timeline.get("knockout"));
                }
                if (timeline.containsKey("players")) {
                    // notele si fazele importante (doar pe drumul nou)
                    out.put("players", timeline.get("players"));
                    out.put("highlights", timeline.get("highlights"));
                }
                if (allEvents) {
                    out.put("events", timeline.get("events"));
                } else if (!eventTypes.isEmpty()) {
                    List<Object> kept = new ArrayList<>();
                    for (Map<String, Object> e : (List<Map<String, Object>>) timeline.get("events")) {
                        if (eventTypes.contains(e.get("type"))) {
                            kept.add(e);
                        }
                    }
                    out.put("events", kept);
                }
                return out;
            }));
        }
        List<Object> results = new ArrayList<>();
        for (Future<Map<String, Object>> f : futures) {
            results.add(f.get());
        }
        System.out.write(Json.write(results).getBytes(StandardCharsets.UTF_8));
        System.out.flush();
        System.err.printf("%d meciuri in %.1f s pe %d fire%n", jobs.size(), (System.nanoTime() - t0) / 1e9, threads);
    }
}
