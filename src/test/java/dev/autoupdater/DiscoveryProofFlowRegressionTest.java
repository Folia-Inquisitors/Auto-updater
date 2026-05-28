package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public final class DiscoveryProofFlowRegressionTest {
    private DiscoveryProofFlowRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-proof-regression");
        Path configPath = temp.resolve("updater.yml");
        Files.writeString(configPath, """
            plugins:
              - name: DemoPlugin
                source: Not Found
                sourceOrigin: unresolved
                type: auto
                installAs: plugins/DemoPlugin.jar
            """, StandardCharsets.UTF_8);

        Object config = newInstance("dev.autoupdater.AutoUpdater$AppConfig");
        setField(config, "baseDir", temp);
        setField(config, "configPath", configPath);
        Object discovery = getField(config, "discovery");
        setField(discovery, "saveDiscoveredSources", true);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.installAs = "plugins/DemoPlugin.jar";
        target.source = "Not Found";
        target.sourceOrigin = "unresolved";
        target.detectedPluginId = "DemoPlugin";
        target.detectedMainClass = "dev.example.DemoPlugin";
        @SuppressWarnings("unchecked")
        List<TargetConfig> plugins = (List<TargetConfig>) getField(config, "plugins");
        plugins.add(target);

        String sourceA = "https://github.com/example/DemoPlugin";
        String sourceB = "https://github.com/example/DemoPlugin-Folia";
        Object descriptor = pluginDescriptor();

        setField(updater, "sourceProofCaptureDepth", 1);
        invoke(updater, "rememberSourceProof",
            new Class<?>[] { TargetConfig.class, String.class, String.class, String.class, descriptor.getClass(), String.class },
            target, sourceA, "github-source", "example/DemoPlugin", descriptor, "descriptor-match");
        invoke(updater, "rememberSourceProof",
            new Class<?>[] { TargetConfig.class, String.class, String.class, String.class, descriptor.getClass(), String.class },
            target, sourceB, "github-source", "example/DemoPlugin-Folia", descriptor, "descriptor-match");
        setField(updater, "sourceProofCaptureDepth", 0);

        Object candidateA = discoveryCandidate(sourceA, "example/DemoPlugin", 100);
        Object candidateB = discoveryCandidate(sourceB, "example/DemoPlugin-Folia", 10);
        invoke(updater, "applyDiscoveredSource",
            new Class<?>[] { TargetConfig.class, List.class },
            target, List.of(candidateA, candidateB));

        invoke(updater, "saveDiscoveredSourcesIfRequested", new Class<?>[0]);

        String configText = Files.readString(configPath, StandardCharsets.UTF_8);
        String lockText = Files.readString(temp.resolve("updater.lock.yml"), StandardCharsets.UTF_8);

        require(configText.contains("source: " + sourceA), "updater.yml did not save winning source A");
        require(!configText.contains("source: " + sourceB), "updater.yml unexpectedly saved losing source B");
        require(lockText.contains("source: " + sourceA), "updater.lock.yml active sourceProof did not commit source A");
        require(!lockText.contains("source: " + sourceB), "candidate B became active sourceProof");
    }

    private static Object pluginDescriptor() throws Exception {
        Class<?> info = Class.forName("dev.autoupdater.AutoUpdater$PluginJarInfo");
        Constructor<?> ctor = info.getDeclaredConstructor(
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            String.class,
            Set.class,
            Boolean.class,
            boolean.class,
            String.class,
            String.class
        );
        ctor.setAccessible(true);
        return ctor.newInstance(
            "DemoPlugin",
            "DemoPlugin",
            "1.0.0",
            "",
            "dev.example.DemoPlugin",
            "Example",
            "",
            Set.of("bukkit"),
            Boolean.TRUE,
            true,
            "plugin.yml",
            ""
        );
    }

    private static Object discoveryCandidate(String source, String project, int score) throws Exception {
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
            "github-source",
            source,
            project,
            "",
            project,
            "source descriptor matches installed plugin",
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
