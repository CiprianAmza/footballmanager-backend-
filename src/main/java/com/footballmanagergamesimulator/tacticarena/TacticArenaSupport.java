package com.footballmanagergamesimulator.tacticarena;

import com.footballmanagergamesimulator.controller.TacticController;
import com.footballmanagergamesimulator.frontend.PersonalizedTacticView;
import com.footballmanagergamesimulator.frontend.PlayerView;
import com.footballmanagergamesimulator.model.PersonalizedTactic;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.framework.AopProxyUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Small helpers shared by the parent service and the child simulation runner. */
final class TacticArenaSupport {

    static final List<String> IMAGE_KINDS = List.of("tactic", "schedule", "competitions");

    private TacticArenaSupport() {
    }

    static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] gunzip(byte[] gz) {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** "Gegenpress 4-2-3-1!" → "gegenpress-4-2-3-1" (ASCII, at most 40 characters). */
    static String slug(String name) {
        String ascii = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (ascii.length() > 40) ascii = ascii.substring(0, 40).replaceAll("-+$", "");
        return ascii.isEmpty() ? "test" : ascii;
    }

    /** {@code GET /tactic/getPlayers/{teamId}} is a private handler; call it as the page does. */
    @SuppressWarnings("unchecked")
    static List<PlayerView> players(TacticController tactics, long teamId) {
        Object target = AopUtils.isAopProxy(tactics) ? AopProxyUtils.getSingletonTarget(tactics) : tactics;
        try {
            Method m = TacticController.class.getDeclaredMethod("getPlayers", String.class);
            m.setAccessible(true);
            return (List<PlayerView>) m.invoke(target, String.valueOf(teamId));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TacticController.getPlayers not reachable", e);
        }
    }

    /** A child process stops when the backend that started it is gone. */
    static void startParentWatchdog(long parentPid) {
        if (parentPid <= 0) return;
        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                boolean alive = ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false);
                if (!alive) {
                    System.err.println("[tactic-arena] parent process " + parentPid + " is gone; exiting");
                    Runtime.getRuntime().halt(4);
                }
            }
        }, "tactic-arena-parent-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Implementation-Version of the packaged application, or the newest class file time when run from classes. */
    static String appVersion() {
        String version = TacticArenaSupport.class.getPackage().getImplementationVersion();
        if (version != null) return version;
        try {
            var location = TacticArenaSupport.class.getProtectionDomain().getCodeSource().getLocation();
            java.nio.file.Path path = java.nio.file.Path.of(location.toURI());
            if (java.nio.file.Files.isRegularFile(path)) {
                return "build " + java.nio.file.Files.getLastModifiedTime(path).toInstant();
            }
            return "classes " + java.nio.file.Files.getLastModifiedTime(
                    path.resolve("com/footballmanagergamesimulator/Main.class")).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    static void copyInstructions(PersonalizedTacticView from, PersonalizedTactic to) {
        to.setTactic(from.getTactic());
        to.setMentality(from.getMentality());
        to.setTimeWasting(from.getTimeWasting());
        to.setInPossession(from.getInPossession());
        to.setPassingType(from.getPassingType());
        to.setTempo(from.getTempo());
        to.setDefensiveLine(from.getDefensiveLine());
        to.setPressing(from.getPressing());
        to.setWidth(from.getWidth());
        to.setDribbling(from.getDribbling());
        to.setFoulFrequency(from.getFoulFrequency());
        to.setFoulHardness(from.getFoulHardness());
        to.setTempoFragmentation(from.getTempoFragmentation());
        to.setWidePlay(from.getWidePlay());
        to.setTransition(from.getTransition());
        to.setRecovery(from.getRecovery());
    }
}
