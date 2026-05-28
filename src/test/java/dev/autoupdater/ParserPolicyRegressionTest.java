package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ParserPolicyRegressionTest {
    private ParserPolicyRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        restartWarningsCanAppearBeforeOrAfterRestartKeys();
        strictBooleanAndConfigValidationRejectsTypos();
        foliaAutoSourcesRequireFoliaProofButManualSourcesWarnAndContinue();
        discoverModeDoesNotReplaceStaleSourcesWhenAutoSwitchIsFalse();
    }

    private static void restartWarningsCanAppearBeforeOrAfterRestartKeys() throws Exception {
        Object after = parseConfig("""
            restart:
              warnings:
                - before: 5m
                  command: say restart
              startupRollbackPolicy: rollbackBatch
              enabled: true
            """);
        Object restartAfter = getField(after, "restart");
        require("rollbackBatch".equals(getField(restartAfter, "startupRollbackPolicy")),
            "restart key after warnings was not parsed as a restart key");
        require(((List<?>) getField(restartAfter, "warnings")).size() == 1,
            "warning entry after restart-key parse was lost");

        Object before = parseConfig("""
            restart:
              enabled: true
              startupRollbackPolicy: rollbackMatchedOnly
              warnings:
                - before: 30m
                  command: say 30
                - before: 5m
                  command: say 5
            """);
        Object restartBefore = getField(before, "restart");
        require("rollbackMatchedOnly".equals(getField(restartBefore, "startupRollbackPolicy")),
            "restart key before warnings was not parsed");
        require(((List<?>) getField(restartBefore, "warnings")).size() == 2,
            "multiple restart warnings were not parsed");

        Object none = parseConfig("""
            restart:
              enabled: false
              startupRollbackPolicy: rollbackBatch
            """);
        require(((List<?>) getField(getField(none, "restart"), "warnings")).isEmpty(),
            "config with no restart warnings should have an empty warning list");
    }

    private static void strictBooleanAndConfigValidationRejectsTypos() throws Exception {
        expectParseError("""
            plugins:
              - name: Demo
                autoUpdate: ture
            """, "autoUpdate typo was silently accepted");

        Object valid = parseConfig("""
            discovery:
              enabled: false
            """);
        require(!((Boolean) getField(getField(valid, "discovery"), "enabled")),
            "discovery.enabled: false was not accepted");

        expectParseError("""
            buildFromSource:
              enabled: maybe
            """, "buildFromSource.enabled typo was silently accepted");

        expectParseError("""
            plugins:
              - name: Demo
                source: https://example.com/Demo.jar
                sourceOrigin: typo
            """, "unknown sourceOrigin was silently accepted");
    }

    private static void foliaAutoSourcesRequireFoliaProofButManualSourcesWarnAndContinue() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-folia-policy");
        Object config = newConfig();
        setField(config, "baseDir", temp);
        TargetConfig server = new TargetConfig("Folia", true);
        server.project = "folia";
        setField(config, "server", server);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        SourcePlan plan = new SourcePlan("direct", "direct", ignored -> null);
        ResolvedDownload download = new ResolvedDownload(URI.create("file:///tmp/Demo.jar"), "demo", "direct", "", "", "", "1.0.0");

        Path incomingGeneric = temp.resolve("incoming-generic.jar");
        writePluginJar(incomingGeneric, "DemoPlugin", false);
        TargetConfig discovered = pluginTarget("discovered-unverified");
        expectInvokeError(updater, "validateDownloadedJar",
            new Class<?>[] { TargetConfig.class, SourcePlan.class, ResolvedDownload.class, Path.class, Path.class },
            new Object[] { discovered, plan, download, incomingGeneric, temp.resolve("missing-current.jar") },
            "auto-discovered Folia update without folia-supported proof was allowed");

        Path currentFolia = temp.resolve("current-folia.jar");
        writePluginJar(currentFolia, "DemoPlugin", true);
        TargetConfig manualWithCurrentFolia = pluginTarget("manual");
        expectInvokeError(updater, "validateDownloadedJar",
            new Class<?>[] { TargetConfig.class, SourcePlan.class, ResolvedDownload.class, Path.class, Path.class },
            new Object[] { manualWithCurrentFolia, plan, download, incomingGeneric, currentFolia },
            "Folia support downgrade from current jar was allowed");

        TargetConfig manual = pluginTarget("manual");
        invoke(updater, "validateDownloadedJar",
            new Class<?>[] { TargetConfig.class, SourcePlan.class, ResolvedDownload.class, Path.class, Path.class },
            manual, plan, download, incomingGeneric, temp.resolve("no-current.jar"));
    }

    private static void discoverModeDoesNotReplaceStaleSourcesWhenAutoSwitchIsFalse() throws Exception {
        Object config = newConfig();
        Object discovery = getField(config, "discovery");
        setField(discovery, "autoSwitchSource", false);

        TargetConfig votifier = new TargetConfig("Votifier", false);
        votifier.enabled = true;
        votifier.autoUpdate = true;
        votifier.installAs = "plugins/Votifier.jar";
        votifier.source = "https://github.com/IchBinJoe/Votifier";
        votifier.sourceOrigin = "discovered-unverified";
        @SuppressWarnings("unchecked")
        List<TargetConfig> plugins = (List<TargetConfig>) getField(config, "plugins");
        plugins.add(votifier);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        invoke(updater, "migrateKnownStaleDiscoveredSourcesIfAutoSwitchEnabled", new Class<?>[0]);
        require("https://github.com/IchBinJoe/Votifier".equals(votifier.source),
            "stale source was replaced even though autoSwitchSource=false");

        setField(discovery, "autoSwitchSource", true);
        invoke(updater, "migrateKnownStaleDiscoveredSourcesIfAutoSwitchEnabled", new Class<?>[0]);
        require("https://github.com/NuVotifier/NuVotifier".equals(votifier.source),
            "stale source was not replaced when autoSwitchSource=true");

        TargetConfig manual = new TargetConfig("Votifier", false);
        manual.enabled = true;
        manual.autoUpdate = true;
        manual.installAs = "plugins/ManualVotifier.jar";
        manual.source = "https://github.com/IchBinJoe/Votifier";
        manual.sourceOrigin = "manual";
        plugins.clear();
        plugins.add(manual);
        invoke(updater, "migrateKnownStaleDiscoveredSourcesIfAutoSwitchEnabled", new Class<?>[0]);
        require("https://github.com/IchBinJoe/Votifier".equals(manual.source),
            "manual stale source was replaced");
    }

    private static TargetConfig pluginTarget(String sourceOrigin) {
        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.installAs = "plugins/DemoPlugin.jar";
        target.source = "https://example.com/DemoPlugin.jar";
        target.sourceOrigin = sourceOrigin;
        target.platform = "paper";
        return target;
    }

    private static Object parseConfig(String text) throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-parser-policy");
        Path config = temp.resolve("updater.yml");
        Files.writeString(config, text, StandardCharsets.UTF_8);
        Class<?> parser = Class.forName("dev.autoupdater.AutoUpdater$ConfigParser");
        Method parse = parser.getDeclaredMethod("parse", Path.class);
        parse.setAccessible(true);
        return parse.invoke(null, config);
    }

    private static void expectParseError(String text, String message) throws Exception {
        try {
            parseConfig(text);
            throw new AssertionError(message);
        } catch (Exception ex) {
            // Any reflective parse exception is the expected failure.
        }
    }

    private static void expectInvokeError(Object target, String name, Class<?>[] parameterTypes, Object[] args, String message) throws Exception {
        try {
            invoke(target, name, parameterTypes, args);
            throw new AssertionError(message);
        } catch (Exception ex) {
            // Any reflective invocation exception is the expected failure.
        }
    }

    private static void writePluginJar(Path path, String name, boolean foliaSupported) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            String folia = foliaSupported ? "folia-supported: true\n" : "";
            jar.write(("""
                name: %s
                version: 1.0.0
                main: com.example.%s
                %s""").formatted(name, name, folia).getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static Object newConfig() throws Exception {
        return newInstance("dev.autoupdater.AutoUpdater$AppConfig");
    }

    private static Object newInstance(String className, Object... args) throws Exception {
        Class<?> type = Class.forName(className);
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            if (ctor.getParameterCount() == args.length) {
                ctor.setAccessible(true);
                return ctor.newInstance(args);
            }
        }
        throw new NoSuchMethodException(className);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = target.getClass().getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
