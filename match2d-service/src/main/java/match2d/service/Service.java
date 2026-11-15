package match2d.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import match2d.engine.Sim;

/**
 * Serviciul HTTP al motorului de meci 2D, cu aceleasi adrese ca serviciul Python pe care il
 * inlocuieste. Backend-ul jocului ii trimite doua echipe (primul 11, ratinguri, tactica);
 * serviciul joaca meciul si intoarce un id plus adresa paginii de afisaj, care se pune intr-un
 * iframe.
 *
 * <pre>
 *   POST /matches        {"home": {...}, "away": {...}, "seed": 7, "minutes": 90}
 *                        -> {"id", "viewUrl", "score", "summary", "players", "highlights", ...}
 *   POST /results        acelasi corp (plus "ref") sau {"matches": [corp, ...]} (cel mult 512):
 *                        rezultatul fiecarui meci, fara pagina de afisaj si fara memorie
 *   GET  /view/{id}      pagina de afisaj (HTML, de pus in iframe)
 *   GET  /matches/{id}   cronologia bruta (JSON), pentru un afisaj propriu
 *   POST /simulate       acelasi corp, cu echipele deja in forma motorului; intoarce cronologia
 *                        intreaga ("summaryOnly": true = doar rezumatul si evenimentele)
 *   GET  /health         {"status": "ok", "engineVersion": ...}
 * </pre>
 *
 * <p>Meciurile stau doar in memorie (ultimele 24); dupa repornire se cer din nou.
 */
public final class Service {
    /** un grup de 512 meciuri pentru {@code /results} are ~1000 de echipe a cate 11 jucatori. */
    private static final int MAX_BODY = 96 * 1024 * 1024;
    private static final int KEEP = 24;
    private static final int MAX_RESULTS = 512;
    private static final List<String> HOW = List.of("penalty", "free_kick", "header");

    /** Un meci jucat, pastrat in memorie. */
    private static final class Item {
        Map<String, Object> timeline;
        String timelineJson;
        double took;
        String home;
        String away;
        long seed;
        double minutes;
    }

    /** Campul optional {@code knockout} al cererii: aggregate-ul, sau null (meci obisnuit). */
    private static int[] knockout(Map<String, Object> payload) {
        try {
            return Sim.parseKnockout(payload.get("knockout"));
        } catch (IllegalArgumentException e) {
            throw new TeamIo.TeamError(e.getMessage());
        }
    }

    private final Map<String, Item> store = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) {
            return size() > KEEP;
        }
    };
    private final String publicUrl;
    private final String viewerTemplate;
    /** firele pe care se joaca grupurile din {@code /results}: cate unul pe procesor. */
    private final ExecutorService resultsPool = Executors.newFixedThreadPool(
            Math.max(1, Runtime.getRuntime().availableProcessors()), r -> {
                Thread th = new Thread(r, "results");
                th.setDaemon(true);
                return th;
            });

    public Service(String publicUrl) throws IOException {
        this.publicUrl = publicUrl;
        try (InputStream in = Service.class.getResourceAsStream("/match2d/viewer_template.html")) {
            if (in == null) {
                throw new IOException("lipseste pagina de afisaj din jar (match2d/viewer_template.html)");
            }
            this.viewerTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    public HttpServer start(String host, int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 64);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors())));
        server.start();
        return server;
    }

    // ------------------------------------------------------------------ meciuri
    private static Object readJson(HttpExchange ex) throws IOException {
        byte[] data = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (data.length == 0 || data.length > MAX_BODY) {
            throw new TeamIo.TeamError("corpul cererii lipseste sau e prea mare");
        }
        try {
            return Json.parse(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new TeamIo.TeamError("corpul cererii nu e JSON valid");
        }
    }

    private static boolean isMatch(Object parsed) {
        return parsed instanceof Map && ((Map<?, ?>) parsed).containsKey("home")
                && ((Map<?, ?>) parsed).containsKey("away");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        Object parsed = readJson(ex);
        if (!isMatch(parsed)) {
            throw new TeamIo.TeamError("cererea trebuie sa contina \"home\" si \"away\"");
        }
        return (Map<String, Object>) parsed;
    }

    private static double number(Map<String, Object> payload, String key, double fallback) {
        Object v = payload.get(key);
        if (v == null) {
            return fallback;
        }
        if (v instanceof Number n) {
            return n.doubleValue() == 0.0 ? fallback : n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (NumberFormatException e) {
            throw new TeamIo.TeamError("\"seed\" si \"minutes\" trebuie sa fie numere");
        }
    }

    private static Object sorted(Object v) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new TreeMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), sorted(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new ArrayList<>();
            for (Object item : list) {
                out.add(sorted(item));
            }
            return out;
        }
        return v;
    }

    private static String key(Map<String, Object> home, Map<String, Object> away, long seed, double minutes,
                              int[] knockout) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-1");
            // fara knockout cheia e exact cea dinainte; cu el, aggregate-ul intra in id
            List<Object> parts = knockout == null ? List.of(sorted(home), sorted(away), seed, minutes)
                    : List.of(sorted(home), sorted(away), seed, minutes,
                    Map.of("knockout", Map.of("aggregate", List.of(knockout[0], knockout[1]))));
            byte[] hash = sha.digest(Json.write(parts).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Un meci cerut, cu echipele normalizate si id-ul lui. */
    private static final class Request {
        Map<String, Object> home;
        Map<String, Object> away;
        long seed;
        double minutes;
        int[] knockout;
        String id;
    }

    private static Request prepare(Map<String, Object> payload) {
        Request m = new Request();
        m.home = TeamIo.normalize(payload.get("home"), "#d62828");
        m.away = TeamIo.normalize(payload.get("away"), "#1d4ed8");
        if (m.away.get("color").equals(m.home.get("color"))) {
            // doua echipe de aceeasi culoare nu se disting pe teren
            m.away.put("color", !m.home.get("color").equals("#f5f7fa") ? "#f5f7fa" : "#1f2933");
        }
        m.seed = (long) number(payload, "seed", 1);
        m.minutes = Math.max(1.0, Math.min(120.0, number(payload, "minutes", 90)));
        m.knockout = knockout(payload);
        m.id = key(m.home, m.away, m.seed, m.minutes, m.knockout);
        return m;
    }

    private static Item play(Request m, boolean keepFrames) {
        long t0 = System.nanoTime();
        Sim sim = new Sim(m.home, m.away, m.seed, m.minutes, m.minutes <= 20 ? 10 : 5, keepFrames);
        if (m.knockout != null) {
            sim.knockout(m.knockout);
        }
        Item item = new Item();
        item.timeline = sim.run();
        item.took = (System.nanoTime() - t0) / 1e9;
        item.home = (String) m.home.get("name");
        item.away = (String) m.away.get("name");
        item.seed = m.seed;
        item.minutes = m.minutes;
        return item;
    }

    /** Joaca meciul cerut (sau il ia din memorie) si intoarce rezumatul lui, fara cronologie. */
    Map<String, Object> createMatch(Map<String, Object> payload) {
        Request m = prepare(payload);
        Item item;
        synchronized (store) {
            item = store.get(m.id);
        }
        boolean cached = item != null;
        if (item == null) {
            item = play(m, true);
            synchronized (store) {
                store.put(m.id, item);
            }
        }
        return result(m.id, item, cached, false, null);
    }

    private static Integer uid(Object v) {
        return v instanceof Number n ? Integer.valueOf(n.intValue()) : null;
    }

    /**
     * Rezultatul unui meci: campurile lui {@code POST /matches} ({@code cached} doar daca e dat), iar
     * pe drumul nou si golurile cu marcator/pasator, {@code engineVersion}, {@code players}, {@code highlights}.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(String id, Item item, Boolean cached, boolean hasRef, Object ref) {
        Map<String, Object> tl = item.timeline;
        Map<String, Object> summary = (Map<String, Object>) tl.get("summary");
        List<Map<String, Object>> players = (List<Map<String, Object>>) tl.get("players");
        Map<Integer, Map<String, Object>> byUid = new HashMap<>();
        if (players != null) {
            for (Map<String, Object> p : players) {
                byUid.put(uid(p.get("uid")), p);
            }
        }
        List<Map<String, Object>> goals = new ArrayList<>();
        Object knockout = tl.get("knockout");
        int period = 0;             // la meciul eliminatoriu: 0 = cele 90 de minute, 1 si 2 = prelungirile
        for (Map<String, Object> e : (List<Map<String, Object>>) tl.get("events")) {
            if ("EXTRA_TIME".equals(e.get("type")) || "EXTRA_HALF_TIME".equals(e.get("type"))) {
                period++;
            }
            if ("GOAL".equals(e.get("type"))) {
                Map<String, Object> g = new LinkedHashMap<>();
                long minute = (long) Math.floor(((Number) e.get("t")).doubleValue() / 60) + 1;
                if (knockout != null) {
                    // golul din prelungirile cerute de timp nu iese din perioada lui: 1-90, 91-105, 106-120
                    minute = period == 0 ? Math.min(minute, 90) : period == 1 ? Math.max(91, Math.min(minute, 105))
                            : Math.max(106, Math.min(minute, 120));
                }
                g.put("minute", minute);
                g.put("team", e.get("team"));
                g.put("text", e.getOrDefault("text", ""));
                if (players != null) {
                    // marcatorul (la autogol: cel care l-a dat), pasatorul, felul golului
                    Integer p = uid(e.get("p"));
                    Integer own = uid(e.get("own"));
                    boolean isOwn = p == null || (own != null && own.equals(p));
                    Map<String, Object> scorer = byUid.get(isOwn ? own : p);
                    Map<String, Object> assist = isOwn ? null : byUid.get(uid(e.get("assist")));
                    g.put("playerId", scorer != null ? scorer.get("id") : null);
                    g.put("player", scorer != null ? scorer.get("name") : null);
                    g.put("assistId", assist != null ? assist.get("id") : null);
                    g.put("assist", assist != null ? assist.get("name") : null);
                    g.put("own", isOwn);
                    Object how = e.get("how");
                    g.put("how", how != null && HOW.contains(how) ? how : null);
                }
                goals.add(g);
            }
        }
        List<Object> tactics = new ArrayList<>();
        List<Object> ignored = new ArrayList<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) tl.get("teams")) {
            tactics.add(t.get("tactic"));
            ignored.addAll((List<Object>) t.get("tacticIgnored"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("home", item.home);
        out.put("away", item.away);
        out.put("score", summary.get("score"));
        out.put("seed", item.seed);
        out.put("minutes", item.minutes);
        out.put("goals", goals);
        // tactica efectiv jucata de fiecare echipa si valorile pe care motorul nu le-a inteles
        out.put("tactics", tactics);
        out.put("tacticIgnored", ignored);
        out.put("summary", summary);
        out.put("simulatedInMs", (long) (item.took * 1000));
        if (cached != null) {
            out.put("cached", cached);
        }
        out.put("engine", "java");
        if (hasRef) {
            out.put("ref", ref);
        }
        if (knockout != null) {
            out.put("knockout", knockout);
        }
        if (players != null) {
            out.put("engineVersion", tl.get("version"));
            out.put("players", players);
            out.put("highlights", tl.get("highlights"));
        }
        return out;
    }

    /** Un element din {@code /results}: rezultatul meciului sau {@code {"ref", "error"}}. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> oneResult(Object raw) {
        Object ref = raw instanceof Map<?, ?> map ? map.get("ref") : null;
        try {
            if (!isMatch(raw)) {
                throw new TeamIo.TeamError("meciul trebuie sa contina \"home\" si \"away\"");
            }
            Map<String, Object> payload = (Map<String, Object>) raw;
            Request m = prepare(payload);
            // ca in batch: fara cadre si fara memorie
            return result(m.id, play(m, false), null, payload.containsKey("ref"), ref);
        } catch (RuntimeException e) {
            String message = e instanceof TeamIo.TeamError ? e.getMessage()
                    : (e instanceof ClassCastException || e instanceof NullPointerException
                    ? "cerere gresita: " + e : String.valueOf(e));
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("ref", ref);
            err.put("error", message);
            return err;
        }
    }

    /** {@code POST /results}: un meci (raspunsul e rezultatul) sau un grup (raspunsul e {"results": [...]}). */
    private int results(HttpExchange ex) throws IOException {
        Object parsed = readJson(ex);
        if (parsed instanceof Map<?, ?> map && map.containsKey("matches")) {
            if (!(map.get("matches") instanceof List<?> list)) {
                throw new TeamIo.TeamError("\"matches\" trebuie sa fie o lista de meciuri");
            }
            if (list.size() > MAX_RESULTS) {
                throw new TeamIo.TeamError("cel mult " + MAX_RESULTS + " meciuri pe cerere (au venit "
                        + list.size() + ")");
            }
            List<Future<Map<String, Object>>> futures = new ArrayList<>();
            for (Object raw : list) {
                futures.add(resultsPool.submit(() -> oneResult(raw)));
            }
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                try {
                    out.add(futures.get(i).get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("cererea a fost intrerupta", e);
                } catch (ExecutionException e) {
                    // o eroare care nu e RuntimeException (de ex. lipsa de memorie) strica doar meciul ei
                    Object raw = list.get(i);
                    Map<String, Object> err = new LinkedHashMap<>();
                    err.put("ref", raw instanceof Map<?, ?> m ? m.get("ref") : null);
                    err.put("error", String.valueOf(e.getCause()));
                    out.add(err);
                }
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("results", out);
            return json(ex, 200, body);
        }
        if (!isMatch(parsed)) {
            throw new TeamIo.TeamError("cererea trebuie sa contina \"home\" si \"away\" sau \"matches\"");
        }
        Map<String, Object> one = oneResult(parsed);
        return json(ex, one.containsKey("id") ? 200 : 400, one);
    }

    /** Joaca un meci cu echipele deja in forma motorului si intoarce cronologia (sau doar rezumatul). */
    @SuppressWarnings("unchecked")
    static Map<String, Object> simulate(Map<String, Object> payload) {
        long seed = (long) number(payload, "seed", 1);
        double minutes = number(payload, "minutes", 90);
        boolean summaryOnly = Boolean.TRUE.equals(payload.get("summaryOnly"));
        int hz = payload.get("recordHz") instanceof Number n ? n.intValue() : (minutes <= 20 ? 10 : 5);
        Sim sim = new Sim((Map<String, Object>) payload.get("home"), (Map<String, Object>) payload.get("away"),
                seed, minutes, hz, !summaryOnly);
        int[] knockout = knockout(payload);
        if (knockout != null) {
            sim.knockout(knockout);
        }
        return sim.run();
    }

    private String viewerHtml(Item item) {
        if (item.timelineJson == null) {
            item.timelineJson = Json.write(item.timeline).replace("</", "<\\/");
        }
        return viewerTemplate.replace("/*__MATCH_DATA__*/null", item.timelineJson);
    }

    // ------------------------------------------------------------------ HTTP
    private void handle(HttpExchange ex) throws IOException {
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int status = 200;
        try {
            if (method.equals("OPTIONS")) {
                ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
                ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                ex.sendResponseHeaders(204, -1);
                status = 204;
            } else if (method.equals("GET") && (path.equals("/") || path.equals("/health"))) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("status", "ok");
                out.put("engine", "java");
                out.put("engineVersion", Sim.VERSION);
                synchronized (store) {
                    out.put("matchesInMemory", store.size());
                }
                status = json(ex, 200, out);
            } else if (method.equals("GET") && path.startsWith("/view/")) {
                Item item;
                synchronized (store) {
                    item = store.get(path.substring("/view/".length()));
                }
                if (item == null) {
                    status = send(ex, 404, "<!doctype html><meta charset=\"utf-8\"><body style=\"font:16px "
                            + "system-ui;background:#10151c;color:#e8edf2;padding:40px\">"
                            + "Meciul nu mai e in memoria serviciului. Cere-l din nou din joc.",
                            "text/html; charset=utf-8");
                } else {
                    status = send(ex, 200, viewerHtml(item), "text/html; charset=utf-8");
                }
            } else if (method.equals("GET") && path.startsWith("/matches/")) {
                Item item;
                synchronized (store) {
                    item = store.get(path.substring("/matches/".length()));
                }
                status = item == null ? json(ex, 404, Map.of("error", "meci necunoscut"))
                        : json(ex, 200, item.timeline);
            } else if (method.equals("POST") && path.equals("/matches")) {
                Map<String, Object> result = createMatch(body(ex));
                String host = ex.getRequestHeaders().getFirst("Host");
                String base = publicUrl != null ? publicUrl : "http://" + (host != null ? host : "localhost");
                result.put("viewUrl", base + "/view/" + result.get("id"));
                status = json(ex, 200, result);
            } else if (method.equals("POST") && path.equals("/results")) {
                status = results(ex);
            } else if (method.equals("POST") && path.equals("/simulate")) {
                status = json(ex, 200, simulate(body(ex)));
            } else {
                status = json(ex, 404, Map.of("error", "adresa necunoscuta"));
            }
        } catch (TeamIo.TeamError | ClassCastException | NullPointerException e) {
            String message = e instanceof TeamIo.TeamError ? e.getMessage() : "cerere gresita: " + e;
            status = json(ex, 400, Map.of("error", message));
        } catch (RuntimeException e) {
            status = json(ex, 500, Map.of("error", String.valueOf(e)));
        } finally {
            System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " \""
                    + method + " " + ex.getRequestURI() + "\" " + status);
            ex.close();
        }
    }

    private static int json(HttpExchange ex, int status, Object obj) throws IOException {
        return send(ex, status, Json.write(obj), "application/json; charset=utf-8");
    }

    private static int send(HttpExchange ex, int status, String body, String type) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, data.length);
        ex.getResponseBody().write(data);
        return status;
    }
}
