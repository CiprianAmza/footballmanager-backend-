package match2d.league;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import match2d.service.Json;

/**
 * Liga la televizor in Java: serverul HTTP, ceasul si linia de comanda din {@code league_tv.py},
 * cu aceleasi adrese, acelasi JSON si acelasi fisier de stare.
 *
 * <pre>
 *   java -jar match2d.jar league [--port 8796] [--speed 4] [--state FISIER] [--new] [--pause 30] ...
 * </pre>
 */
public final class LeagueServer {
    private static final String RES = "/match2d/league/";
    private static final Map<String, String> PAGES = Map.of("", "tv.html", "/tv", "tv.html",
            "/clasament", "standings.html");
    private static final Map<String, String> SCRIPTS = Map.of("/match_sheet.js", "match_sheet.js");
    private static final String ERROR_STYLE = "<!doctype html><meta charset=\"utf-8\"><body style=\"font:16px "
            + "system-ui;background:#10151c;color:#e8edf2;padding:40px\">";
    private static final String ERROR_MESSAGE = "<!DOCTYPE HTML>\n<html lang=\"en\">\n    <head>\n"
            + "        <meta charset=\"utf-8\">\n        <title>Error response</title>\n    </head>\n    <body>\n"
            + "        <h1>Error response</h1>\n        <p>Error code: %d</p>\n        <p>Message: %s.</p>\n"
            + "        <p>Error code explanation: %d - %s.</p>\n    </body>\n</html>\n";

    private final League league;
    private final boolean quiet;
    private final String viewerTemplate;

    LeagueServer(League league, boolean quiet) throws IOException {
        this.league = league;
        this.quiet = quiet;
        this.viewerTemplate = new String(resource("/match2d/viewer_template.html"), StandardCharsets.UTF_8);
    }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = LeagueServer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IOException("lipseste din arhiva: " + path);
            }
            return in.readAllBytes();
        }
    }

    /** Pagina de afisaj cu cronologia inclusa (ca {@code viewer_html} din Python). */
    String viewerHtml(Map<String, Object> timeline) {
        // fazele rezumatului vin din cronologie; segments da eroare clara daca lipsesc
        Map<String, Object> data = new LinkedHashMap<>(timeline);
        data.put("highlights", Playlist.segments(timeline));
        String payload = Py.dumps(data, true);
        return viewerTemplate.replace("/*__MATCH_DATA__*/null", payload.replace("</", "<\\/"));
    }

    // ------------------------------------------------------------------ HTTP
    private void log(String message) {
        if (!quiet && !message.contains("/api/state")) {
            System.out.println(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + message);
            System.out.flush();
        }
    }

    private static String requestLine(HttpExchange ex) {
        return ex.getRequestMethod() + " " + ex.getRequestURI() + " " + ex.getProtocol();
    }

    private void send(HttpExchange ex, int status, byte[] data, String ctype, Map<String, String> headers)
            throws IOException {
        log("\"" + requestLine(ex) + "\" " + status + " -");
        Headers h = ex.getResponseHeaders();
        h.set("Server", "LeagueTV/1");
        h.set("Content-Type", ctype);
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                h.set(e.getKey(), e.getValue());
            }
        }
        h.set("Cache-Control", "no-store");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : data.length);
        if (!head) {
            ex.getResponseBody().write(data);
        }
    }

    private void send(HttpExchange ex, int status, String body, String ctype, Map<String, String> headers)
            throws IOException {
        send(ex, status, body.getBytes(StandardCharsets.UTF_8), ctype, headers);
    }

    private void sendJson(HttpExchange ex, int status, Object obj) throws IOException {
        send(ex, status, Py.dumps(obj, false), "application/json; charset=utf-8", null);
    }

    private static Map<String, Object> err(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("error", message);
        return out;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            String method = ex.getRequestMethod();
            String raw = ex.getRequestURI().getRawPath();
            if (raw == null) {
                raw = "";
            }
            if (raw.startsWith("//")) {
                raw = "/" + raw.replaceFirst("^/+", "");
            }
            String path = raw.replaceFirst("/+$", "");
            if (method.equals("GET")) {
                doGet(ex, path);
            } else if (method.equals("POST")) {
                doPost(ex, path);
            } else {
                sendError(ex, 501, "Unsupported method ('" + method + "')", "Server does not support this operation");
            }
        } catch (RuntimeException | IOException e) {
            // ca in Python: eroarea se scrie in consola, iar legatura se inchide fara raspuns
            System.err.println("----------------------------------------");
            System.err.println("Exception occurred during processing of request from " + ex.getRemoteAddress());
            e.printStackTrace();
            throw e;
        } finally {
            ex.close();
        }
    }

    private void sendError(HttpExchange ex, int code, String message, String explain) throws IOException {
        log("code " + code + ", message " + message);
        String body = String.format(ERROR_MESSAGE, code, htmlEscape(message), code, htmlEscape(explain));
        log("\"" + requestLine(ex) + "\" " + code + " -");
        Headers h = ex.getResponseHeaders();
        h.set("Server", "LeagueTV/1");
        h.set("Connection", "close");
        h.set("Content-Type", "text/html;charset=utf-8");
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(code, head ? -1 : data.length);
        if (!head) {
            ex.getResponseBody().write(data);
        }
    }

    private static String htmlEscape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void doGet(HttpExchange ex, String path) throws IOException {
        if (PAGES.containsKey(path)) {
            send(ex, 200, resource(RES + PAGES.get(path)), "text/html; charset=utf-8", null);
        } else if (path.equals("/api/state")) {
            sendJson(ex, 200, league.state());
        } else if (path.equals("/health")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("status", "ok");
            out.put("phase", league.phase);
            out.put("match", (long) league.cur + 1);
            out.put("total", (long) league.fixtures.size());
            sendJson(ex, 200, out);
        } else if (path.startsWith("/api/sheet/")) {
            League.Reply r = league.sheetResponse(Py.index(path.substring("/api/sheet/".length())));
            sendJson(ex, r.code(), r.body());
        } else if (SCRIPTS.containsKey(path)) {
            send(ex, 200, resource(RES + SCRIPTS.get(path)), "text/javascript; charset=utf-8", null);
        } else if (path.startsWith("/replay/")) {
            // /replay/N = meciul jucat N (de la 0, ca /match/N) de revazut; /replay/N/save = descarcat
            String rest = path.substring("/replay/".length());
            boolean save = rest.endsWith("/save");
            int index = Py.index(save ? rest.substring(0, rest.length() - "/save".length()) : rest);
            League.Reply r = league.replayPage(index, this::viewerHtml);
            if (r.code() != 200) {
                send(ex, r.code(), ERROR_STYLE + ((String) r.body()).replace("<", "&lt;"),
                        "text/html; charset=utf-8", null);
            } else if (save) {
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Disposition", "attachment; filename=\"" + r.fileName() + "\"");
                send(ex, 200, (String) r.body(), "text/html; charset=utf-8", headers);
            } else {
                send(ex, 200, (String) r.body(), "text/html; charset=utf-8", null);
            }
        } else if (path.startsWith("/match/")) {
            int index = Py.index(path.substring("/match/".length()));
            byte[] page = league.matchPage(index, this::viewerHtml, 0L);
            if (page == null) {
                send(ex, 404, ERROR_STYLE + "Meciul acesta nu e in direct acum.", "text/html; charset=utf-8", null);
            } else {
                send(ex, 200, page, "text/html; charset=utf-8", null);
            }
        } else {
            sendJson(ex, 404, err("adresa necunoscuta"));
        }
    }

    /** {@code body.get(key)}; pe un corp care nu e obiect, Python cade cu AttributeError (fara raspuns). */
    private static Object field(Object body, String key) {
        if (!(body instanceof Map<?, ?> map)) {
            throw new IllegalStateException("'" + (body instanceof List ? "list" : String.valueOf(body))
                    + "' object has no attribute 'get'");
        }
        return map.get(key);
    }

    private void doPost(HttpExchange ex, String path) throws IOException {
        Object body;
        try {
            String cl = ex.getRequestHeaders().getFirst("Content-Length");
            int length;
            if (cl == null || cl.isEmpty()) {
                length = 0;
            } else {
                java.math.BigInteger v = Py.parseInt(cl);
                if (v == null) {
                    throw new IllegalArgumentException("Content-Length");
                }
                length = v.min(java.math.BigInteger.valueOf(4096)).max(java.math.BigInteger.valueOf(-1)).intValue();
            }
            if (length != 0) {
                byte[] data = length > 0 ? ex.getRequestBody().readNBytes(length) : ex.getRequestBody().readAllBytes();
                String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString();
                body = Json.parse(text);
            } else {
                body = new LinkedHashMap<>();
            }
        } catch (IllegalArgumentException | CharacterCodingException e) {
            sendJson(ex, 400, err("corpul cererii nu e JSON"));
            return;
        }
        if (path.equals("/api/speed")) {
            Double speed = Py.toFloat(field(body, "speed"));
            if (speed == null) {
                sendJson(ex, 400, err("\"speed\" trebuie sa fie un numar"));
                return;
            }
            league.setSpeed(speed);
        } else if (path.equals("/api/mode")) {
            try {
                league.setMode(field(body, "mode"));
            } catch (IllegalArgumentException e) {
                sendJson(ex, 400, err("\"mode\" trebuie sa fie \"full\" sau \"highlights\""));
                return;
            }
        } else if (path.equals("/api/replays")) {
            if (!(field(body, "on") instanceof Boolean on)) {
                sendJson(ex, 400, err("\"on\" trebuie sa fie true sau false"));
                return;
            }
            league.setReplays(on);
        } else if (path.equals("/api/skip")) {
            league.skip();
        } else if (path.equals("/api/new-season")) {
            if (!league.phase().equals("finished") && !Py.truthy(field(body, "force"))) {
                sendJson(ex, 409, err("sezonul nu s-a terminat"));
                return;
            }
            league.newSeason();
        } else {
            sendJson(ex, 404, err("adresa necunoscuta"));
            return;
        }
        sendJson(ex, 200, league.state());
    }

    /** Tine liga in miscare si cand nu se uita nimeni la pagina. */
    static void runClock(League league, long everyMs) {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(everyMs);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    league.advance();
                } catch (RuntimeException exc) {    // ceasul nu are voie sa moara dintr-o eroare trecatoare
                    System.out.println("ceasul ligii: " + exc);
                    System.out.flush();
                }
            }
        }, "ceasul-ligii");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ linia de comanda
    private static final String[][] OPTIONS = {
        // nume, fel (v = cu valoare, f = comutator), implicit
        {"--teams", "v"}, {"--host", "v"}, {"--port", "v"}, {"--seed", "v"}, {"--meetings", "v"},
        {"--minutes", "v"}, {"--speed", "v"}, {"--state", "v"}, {"--new", "f"}, {"--pause", "v"},
        {"--inline", "f"}, {"--engine", "v"}, {"--jar", "v"}, {"--help", "f"},
    };
    private static final String USAGE = "usage: match2d.jar league [-h] [--teams TEAMS] [--host HOST] [--port PORT] "
            + "[--seed SEED]\n                          [--meetings MEETINGS] [--minutes MINUTES] [--speed SPEED]\n"
            + "                          [--state STATE] [--new] [--pause SECUNDE] [--inline]\n"
            + "                          [--engine {auto,python}] [--jar CALE]\n";
    private static final String HELP = "\nLiga la televizor: un meci in direct, non-stop\n\noptions:\n"
            + "  -h, --help            show this help message and exit\n"
            + "  --teams TEAMS         (implicit: echipele Diamond League din arhiva)\n"
            + "  --host HOST\n  --port PORT\n"
            + "  --seed SEED           tragerea la sorti si meciurile\n"
            + "  --meetings MEETINGS   de cate ori joaca fiecare cu fiecare\n"
            + "  --minutes MINUTES     durata unui meci simulat\n"
            + "  --speed SPEED         viteza transmisiei fata de timpul real\n"
            + "  --state STATE         (implicit: league_tv_state.json langa arhiva)\n"
            + "  --new                 nu relua sezonul salvat\n"
            + "  --pause SECUNDE       cat sta fisa meciului pe ecran dupa fluierul final (implicit 30)\n"
            + "  --inline              acceptat si ignorat (meciurile se joaca oricum in proces)\n"
            + "  --engine {auto,python}\n"
            + "                        acceptat si ignorat (motorul e mereu cel Java)\n"
            + "  --jar CALE            acceptat si ignorat (motorul e chiar aceasta arhiva)\n";

    private static void fail(String message) {
        System.err.print(USAGE);
        System.err.println("match2d.jar league: error: " + message);
        System.exit(2);
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            String value = null;
            if (a.equals("-h")) {
                a = "--help";
            }
            if (!a.startsWith("--")) {
                fail("unrecognized arguments: " + a);
            }
            int eq = a.indexOf('=');
            if (eq >= 0) {
                value = a.substring(eq + 1);
                a = a.substring(0, eq);
            }
            List<String[]> hits = new ArrayList<>();
            for (String[] o : OPTIONS) {
                if (o[0].equals(a)) {
                    hits.clear();
                    hits.add(o);
                    break;
                }
                if (o[0].startsWith(a)) {
                    hits.add(o);
                }
            }
            if (hits.isEmpty()) {
                fail("unrecognized arguments: " + args[i]);
            }
            if (hits.size() > 1) {
                StringBuilder sb = new StringBuilder();
                for (String[] h : hits) {
                    sb.append(sb.length() > 0 ? ", " : "").append(h[0]);
                }
                fail("ambiguous option: " + a + " could match " + sb);
            }
            String[] opt = hits.get(0);
            if (opt[1].equals("f")) {
                if (value != null) {
                    fail("argument " + opt[0] + ": ignored explicit argument '" + value + "'");
                }
                out.put(opt[0], "true");
            } else {
                if (value == null) {
                    // ca argparse: urmatorul argument nu poate fi o alta optiune (un numar negativ, da)
                    if (i + 1 >= args.length || (args[i + 1].startsWith("-")
                            && !args[i + 1].matches("-\\d+$|-\\d*\\.\\d+$"))) {
                        fail("argument " + opt[0] + ": expected one argument");
                    }
                    value = args[++i];
                }
                out.put(opt[0], value);
            }
        }
        return out;
    }

    private static long intOption(Map<String, String> o, String name, long fallback) {
        String v = o.get(name);
        if (v == null) {
            return fallback;
        }
        java.math.BigInteger b = Py.parseInt(v);
        if (b == null || b.bitLength() > 63) {
            fail("argument " + name + ": invalid int value: '" + v + "'");
        }
        return b.longValue();
    }

    private static double floatOption(Map<String, String> o, String name, double fallback) {
        String v = o.get(name);
        if (v == null) {
            return fallback;
        }
        Double d = Py.parseFloat(v);
        if (d == null) {
            fail("argument " + name + ": invalid float value: '" + v + "'");
        }
        return d;
    }

    /** Dosarul arhivei (sau al claselor compilate), pentru fisierul de stare implicit. */
    static Path jarPath() {
        try {
            return Path.of(LeagueServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException | RuntimeException e) {
            return Path.of("match2d.jar").toAbsolutePath();
        }
    }

    static Path defaultState() {
        Path jar = jarPath();
        Path dir = Files.isDirectory(jar) ? jar : jar.getParent();
        return (dir != null ? dir : Path.of(".")).resolve("league_tv_state.json");
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws IOException {
        Map<String, String> o = parse(args);
        if (o.containsKey("--help")) {
            System.out.print(USAGE + HELP);
            return;
        }
        String engine = o.getOrDefault("--engine", "auto");
        if (!engine.equals("auto") && !engine.equals("python")) {
            fail("argument --engine: invalid choice: '" + engine + "' (choose from 'auto', 'python')");
        }
        String host = o.getOrDefault("--host", "127.0.0.1");
        long port = intOption(o, "--port", 8796);
        long seed = intOption(o, "--seed", 2026);
        long meetings = intOption(o, "--meetings", 4);
        double minutes = floatOption(o, "--minutes", 90.0);
        double speed = floatOption(o, "--speed", 4.0);
        double pause = floatOption(o, "--pause", League.PAUSE_SECONDS);
        Path state = o.containsKey("--state") ? Path.of(o.get("--state")) : defaultState();
        // --inline, --engine, --jar: au sens doar in Python; se accepta si se ignora
        byte[] teamsJson = o.containsKey("--teams") ? Files.readAllBytes(Path.of(o.get("--teams")))
                : resource(RES + "diamond_league.json");
        Map<String, Object> file = (Map<String, Object>) Json.parse(new String(teamsJson, StandardCharsets.UTF_8));
        List<Map<String, Object>> teams = (List<Map<String, Object>>) file.get("teams");
        if (o.containsKey("--new")) {
            Files.deleteIfExists(state);
        }
        // un singur fir de lucru, ca ProcessPoolExecutor(max_workers=1): meciul urmator se joaca in fundal
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "meciuri");
            t.setDaemon(true);
            return t;
        });
        League league = new League(teams, seed, (int) meetings, minutes, speed, executor, LeagueServer::now,
                League::simulateFixture, state, League.LINEUP_SECONDS, Py.max(0.0, pause), "Diamond League",
                "full", true, 1.0);
        runClock(league, 500);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, (int) port), 64);
        LeagueServer handler = new LeagueServer(league, false);
        server.createContext("/", ex -> handler.handle(ex));
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println(String.format("%s la televizor: http://%s:%d  (%d etape, %d meciuri, de la meciul %d, "
                        + "motor %s; Ctrl+C opreste)", league.name, host, port, league.rounds.size(),
                league.fixtures.size(), league.cur + 1, "Java, arhiva " + jarPath()));
        System.out.flush();
    }

    /** Ceasul serverului, in secunde (ca {@code time.time()}). */
    static double now() {
        Instant i = Instant.now();
        return i.getEpochSecond() + i.getNano() / 1e9;
    }
}
