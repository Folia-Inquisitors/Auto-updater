package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SourceOriginCleanupPersistenceRegressionTest {
    private SourceOriginCleanupPersistenceRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-source-origin-cleanup");
        Path configPath = temp.resolve("updater.yml");
        Files.writeString(configPath, """
            discovery:
              saveDiscoveredSources: false

            plugins:
              # Discovered sources
              - name: DemoPlugin
                source: https://github.com/example/DemoPlugin
                sourceOrigin: discovered
                type: auto
                installAs: plugins/DemoPlugin.jar
                required: false
            """, StandardCharsets.UTF_8);

        Object config = newInstance("dev.autoupdater.AutoUpdater$AppConfig");
        setField(config, "baseDir", temp);
        setField(config, "configPath", configPath);
        Object discovery = getField(config, "discovery");
        setField(discovery, "saveDiscoveredSources", false);

        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.source = "https://github.com/example/DemoPlugin";
        target.sourceOrigin = "discovered";
        target.type = "auto";
        target.installAs = "plugins/DemoPlugin.jar";
        @SuppressWarnings("unchecked")
        List<TargetConfig> plugins = (List<TargetConfig>) getField(config, "plugins");
        plugins.add(target);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        invoke(updater, "markManualSourceOrigins", new Class<?>[0]);
        invoke(updater, "saveDiscoveredSourcesIfRequested", new Class<?>[0]);

        String configText = Files.readString(configPath, StandardCharsets.UTF_8);
        require(configText.contains("sourceOrigin: manual"), "manual source-origin cleanup was not persisted");
        require(configText.contains("# Manual sources"), "plugin was not reorganized into the manual source group");
        require(!configText.contains("sourceOrigin: discovered"), "stale discovered sourceOrigin remained in updater.yml");
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
