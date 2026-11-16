package com.footballmanagergamesimulator.tacticarena;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Starts the game application again as a child process (same java, same classpath, same
 * command-line options) on its own H2 file database. Children always use H2, whatever
 * database the backend itself runs on.
 */
@Component
public class TacticArenaProcesses {

    @Autowired private Environment environment;
    @Autowired private ApplicationArguments applicationArguments;

    @Value("${tactic-arena.child-jvm-args:-Xmx3g}") private String childJvmArgs;
    @Value("${tactic-arena.work-dir:}") private String workDirSetting;

    Path workDir() {
        if (workDirSetting != null && !workDirSetting.isBlank()) return Path.of(workDirSetting);
        return Path.of(System.getProperty("java.io.tmpdir"), "fm-tactic-arena",
                environment.getProperty("server.port", "default"));
    }

    /** Start a child on the H2 database {@code <dbDir>/<dbName>} with the given extra properties. */
    Process start(Path processDir, Path dbDir, String dbName, Map<String, String> properties) throws IOException {
        String dbUrl = "jdbc:h2:file:" + dbDir.toAbsolutePath().toString().replace('\\', '/') + "/" + dbName;
        List<String> command = command(dbUrl, properties);
        File logFile = processDir.resolve("child.log").toFile();
        return new ProcessBuilder(command).directory(processDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(logFile)
                .redirectInput(ProcessBuilder.Redirect.from(nullFile()))
                .start();
    }

    private List<String> command(String dbUrl, Map<String, String> properties) {
        String java = ProcessHandle.current().info().command()
                .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        List<String> command = new ArrayList<>();
        command.add(java);
        for (String arg : childJvmArgs.trim().split("\\s+")) if (!arg.isBlank()) command.add(arg);
        command.add("-Dfile.encoding=UTF-8");
        String classpath = System.getProperty("java.class.path");
        boolean singleJar = classpath != null && !classpath.contains(File.pathSeparator) && classpath.endsWith(".jar");
        if (singleJar) {
            command.add("-jar");
            command.add(classpath);
        } else {
            command.add("-cp");
            command.add(classpath);
            command.add("com.footballmanagergamesimulator.Main");
        }

        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("server.port", "0");
        overrides.put("spring.datasource.url", dbUrl);
        overrides.put("spring.datasource.username", "sa");
        overrides.put("spring.datasource.password", "");
        overrides.put("spring.datasource.driver-class-name", "org.h2.Driver");
        overrides.put("spring.jpa.database-platform", "org.hibernate.dialect.H2Dialect");
        overrides.put("spring.flyway.locations", "classpath:db/migration/h2");
        overrides.put("facelab.enabled", "false");
        overrides.put("game.checkpoint.enabled", "false");
        overrides.put("bootstrap.rebuild-pre-built-data", "false");
        // A relative snapshot path would be resolved against the child's own folder.
        String snapshot = environment.getProperty("bootstrap.snapshot-path", "prebuilt-data.sql");
        overrides.put("bootstrap.snapshot-path", Path.of(snapshot).toAbsolutePath().toString());
        overrides.put("tactic-arena.export-dir", "");
        overrides.put("tactic-arena.git-push", "false");
        overrides.put("tactic-arena.child.parent-pid", String.valueOf(ProcessHandle.current().pid()));
        overrides.putAll(properties);
        for (String arg : applicationArguments.getSourceArgs()) {
            String key = arg.startsWith("--") ? arg.substring(2).split("=", 2)[0] : null;
            if (key != null && overrides.containsKey(key)) continue;
            command.add(arg);
        }
        overrides.forEach((key, value) -> command.add("--" + key + "=" + value));
        return command;
    }

    static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static File nullFile() {
        return new File(System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows") ? "NUL" : "/dev/null");
    }
}
