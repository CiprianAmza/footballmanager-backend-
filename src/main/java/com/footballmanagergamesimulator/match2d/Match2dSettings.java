package com.footballmanagergamesimulator.match2d;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Switches for drawing real fixtures with the 2D match engine ({@code match2d.*}). The score
 * is always the classic engine's; the mode only says which fixtures also get a 2D match that
 * reproduces it. With {@code mode: off} (the default) the game never calls the engine.
 */
@Component
@ConfigurationProperties(prefix = "match2d")
public class Match2dSettings {

    public enum Mode {
        /** No match is drawn by the 2D engine. */
        OFF,
        /** The 2D engine draws the matches of a human-managed club. */
        HUMAN,
        /** The 2D engine draws every match. */
        ALL
    }

    public enum Fallback {
        /** A match the 2D engine cannot draw (no answer, error, target missed) stays wholly classic, with a log line. */
        CLASSIC,
        /** A match the 2D engine cannot draw fails the round. */
        FAIL
    }

    private Mode mode = Mode.OFF;
    private Fallback fallback = Fallback.CLASSIC;
    private Service service = new Service();

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode == null ? Mode.OFF : mode; }
    public Fallback getFallback() { return fallback; }
    public void setFallback(Fallback fallback) { this.fallback = fallback == null ? Fallback.CLASSIC : fallback; }
    public Service getService() { return service; }
    public void setService(Service service) { this.service = service == null ? new Service() : service; }

    public static class Service {
        private String url = "http://localhost:8791";
        private int timeoutSeconds = 60;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    }
}
