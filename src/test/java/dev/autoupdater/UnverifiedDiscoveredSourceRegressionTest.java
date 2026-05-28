package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class UnverifiedDiscoveredSourceRegressionTest {
    private UnverifiedDiscoveredSourceRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-unverified-discovery");
        Path configPath = temp.resolve("updater.yml");
        Files.writeString(configPath, """
            discovery:
              saveDiscoveredSources: true

            plugins:
              # Unresolved sources
              - name: DemoPlugin
                source: Not Found
                sourceOrigin: unresolved
                type: auto
                installAs: plugins/DemoPlugin.jar
                required: false
            """, StandardCharsets.UTF_8);

        Object config = newInstance("dev.autoupdater.AutoUpdater$AppConfig");
        setField(config, "baseDir", temp);
        setField(config, "configPath", configPath);
        Object discovery = getField(config, "discovery");
        setField(discovery, "saveDiscoveredSources", true);

        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.source = "Not Found";
        target.sourceOrigin = "unresolved";
        target.type = "auto";
        target.installAs = "plugins/DemoPlugin.jar";
        @SuppressWarnings("unchecked")
        List<TargetConfig> plugins = (List<TargetConfig>) getField(config, "plugins");
        plugins.add(target);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        Object candidate = discoveryCandidate(
            "https://modrinth.com/plugin/demo-plugin/versions",
            "modrinth",
            "name match only; descriptor could not be verified",
            60
        );
        invoke(updater, "applyDiscoveredSource", new Class<?>[] { TargetConfig.class, List.class }, target, List.of(candidate));
        invoke(updater, "saveDiscoveredSourcesIfRequested", new Class<?>[0]);

        String configText = Files.readString(configPath, StandardCharsets.UTF_8);
        require(configText.contains("sourceOrigin: discovered-unverified"), "unproven machine discovery was not saved as discovered-unverified");
        require(!configText.contains("sourceOrigin: discovered\n"), "unproven machine discovery was saved as descriptor-proven discovered");

        target.sourceDiscoveredThisRun = false;
        target.sourceOriginUpdatedThisRun = false;
        invoke(updater, "markManualSourceOrigins", new Class<?>[0]);
        require("discovered-unverified".equals(target.sourceOrigin), "unverified machine discovery was promoted to manual on next startup");

        boolean manual = Boolean.TRUE.equals(invoke(updater, "isManualConfiguredSource", new Class<?>[] { TargetConfig.class }, target));
        require(!manual, "unverified machine discovery should remain updater-owned, not user-manual");
    }

    private static Object discoveryCandidate(String source, String type, String reason, int score) throws Exception {
        Class<?> candidate = Class.forName("dev.autoupdater.AutoUpdater$DiscoveryCandidate");
        Constructor<?> ctor = candidate.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            int.class,
            int.class
        );
        ctor.setAccessible(true);
        return ctor.newInstance(
            type,
            source,
            "demo-plugin",
            "1.0.0",
            "DemoPlugin",
            reason,
            score,
            0
        );
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
