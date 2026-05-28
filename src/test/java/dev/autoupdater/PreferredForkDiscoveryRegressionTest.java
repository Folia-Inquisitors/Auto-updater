package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class PreferredForkDiscoveryRegressionTest {
    private PreferredForkDiscoveryRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        preferredForkBeatsWeakUpstreamAndCommitsMatchingProof();
        manualSourceIsNotReplacedByPreferredFork();
        preferredOwnerDoesNotBeatDescriptorMismatch();
        preferredForkCannotDowngrade();
        foliaProofIsRequiredForPreferredFork();
        preferredForkUsesExistingNestedDescriptorPaths();
        preferredForkUsesSupportedVelocityDescriptorPath();
        preferredForkLineageEnrichesSelectedSourceProof();
        repoHealthUnavailableDefersPreferredForkSelection();
        lineageUnavailableDoesNotRejectOtherwiseValidPreferredFork();
        preferredOwnerForkLineageDoesNotOverrideMismatch();
        preferredProbeRunsBeforeForkSearch();
        githubBudgetDefersPreferredForkDiscovery();
        preferredOwnersParseFromYamlList();
    }

    private static void preferredForkBeatsWeakUpstreamAndCommitsMatchingProof() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-fork");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Files.writeString(temp.resolve("updater.lock.yml"), """
            sourceProofs:
              plugins/DemoPlugin.jar:
                source: https://github.com/Official/DemoPlugin
                type: github-source
                repo: Official/DemoPlugin
                proof: descriptor-match
                descriptorPath: plugin.yml
                pluginId: DemoPlugin
                mainClass: com.example.demo.DemoPlugin
                foliaSupported: false
                verifiedAt: 2026-05-28T00:00:00Z
            """, StandardCharsets.UTF_8);

        Object config = preferredForkConfig(temp);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        target.sourceOrigin = "unresolved";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.folia.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> discovered = (List<Object>) invoke(updater, "discoverSourceCandidates",
            new Class<?>[] { TargetConfig.class }, target);
        require(!discovered.isEmpty(), "preferred fork was not discovered");
        invoke(updater, "applyDiscoveredSource", new Class<?>[] { TargetConfig.class, List.class }, target, discovered);

        require("https://github.com/Folia-Inquisitors/DemoPlugin".equals(target.source),
            "preferred fork was not selected");
        String lock = Files.readString(temp.resolve("updater.lock.yml"), StandardCharsets.UTF_8);
        require(lock.contains("source: https://github.com/Folia-Inquisitors/DemoPlugin"),
            "active sourceProof does not point to the selected preferred fork");
        require(!lock.contains("source: https://github.com/Official/DemoPlugin"),
            "non-winning upstream proof remained active");
    }

    private static void manualSourceIsNotReplacedByPreferredFork() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-manual");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object config = preferredForkConfig(temp);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "https://github.com/UserChosen/DemoPlugin";
        target.sourceOrigin = "manual";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(candidates.isEmpty(), "preferred fork discovery ran for a manual source");
        require("https://github.com/UserChosen/DemoPlugin".equals(target.source), "manual source was mutated");
    }

    private static void preferredOwnerDoesNotBeatDescriptorMismatch() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-mismatch");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("OtherPlugin", "2.1.0", "com.other.OtherPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(candidates.isEmpty(), "descriptor-mismatched preferred fork was selected");
    }

    private static void preferredForkCannotDowngrade() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-downgrade");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("DemoPlugin", "1.9.0", "com.example.demo.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(candidates.isEmpty(), "older preferred fork was selected");
    }

    private static void foliaProofIsRequiredForPreferredFork() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-folia-proof");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptorWithoutFolia("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin"));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(candidates.isEmpty(), "preferred fork without Folia proof was selected");
    }

    private static void preferredProbeRunsBeforeForkSearch() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-order");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        @SuppressWarnings("unchecked")
        List<String> trace = (List<String>) getField(updater, "testDiscoveryTrace");
        require(!candidates.isEmpty(), "preferred fork was not discovered in order test");
        require(!trace.isEmpty() && trace.get(0).startsWith("preferred:"), "preferred probe did not run first");
        require(trace.stream().noneMatch(item -> item.startsWith("forks:")),
            "broad GitHub fork search ran even though preferred fork was strongly selected");
    }

    private static void preferredForkUsesExistingNestedDescriptorPaths() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-nested");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "src/main/resources/paper-plugin.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(!candidates.isEmpty(), "preferred fork was not found through nested module paper-plugin.yml");
    }

    private static void preferredForkUsesSupportedVelocityDescriptorPath() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-velocity-path");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "src/main/resources/bungee.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false);

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(!candidates.isEmpty(), "preferred fork did not reuse existing supported bungee descriptor path");
    }

    private static void preferredForkLineageEnrichesSelectedSourceProof() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-lineage");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false,
            true, "Official/DemoPlugin", "Official/DemoPlugin");

        @SuppressWarnings("unchecked")
        List<Object> discovered = (List<Object>) invoke(updater, "discoverSourceCandidates",
            new Class<?>[] { TargetConfig.class }, target);
        require(!discovered.isEmpty(), "preferred fork with lineage was not discovered");
        invoke(updater, "applyDiscoveredSource", new Class<?>[] { TargetConfig.class, List.class }, target, discovered);

        String lock = Files.readString(temp.resolve("updater.lock.yml"), StandardCharsets.UTF_8);
        require(lock.contains("source: https://github.com/Folia-Inquisitors/DemoPlugin"),
            "lineage test active proof does not point to selected fork");
        require(lock.contains("forkRepo: Folia-Inquisitors/DemoPlugin"),
            "selected preferred fork proof did not retain forkRepo lineage");
        require(lock.contains("upstreamRepo: Official/DemoPlugin"),
            "selected preferred fork proof did not retain upstreamRepo lineage");
    }

    private static void repoHealthUnavailableDefersPreferredForkSelection() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-health-unavailable");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object config = preferredForkConfig(temp);
        Object budget = getField(config, "githubBudget");
        setField(budget, "coreUsed", 28);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin", true));

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        @SuppressWarnings("unchecked")
        java.util.Set<String> deferred = (java.util.Set<String>) getField(updater, "discoveryDeferred");
        require(candidates.isEmpty(), "preferred fork was selected even though repo health metadata was unavailable");
        require(deferred.stream().anyMatch(item -> item.contains("preferred fork discovery deferred")
                || item.contains("GitHub API budget reached")),
            "preferred fork repo-health deferral was not recorded");
    }

    private static void lineageUnavailableDoesNotRejectOtherwiseValidPreferredFork() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-lineage-unavailable");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("DemoPlugin", "2.1.0", "com.example.demo.DemoPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false,
            true, "Official/DemoPlugin", "Official/DemoPlugin");
        @SuppressWarnings("unchecked")
        Map<String, Integer> failAfter = (Map<String, Integer>) getField(updater, "testJsonFailureAfterHits");
        failAfter.put("https://api.github.com/repos/Folia-Inquisitors/DemoPlugin", 1);
        setField(updater, "githubRateLimited", true);

        @SuppressWarnings("unchecked")
        List<Object> discovered = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(!discovered.isEmpty(), "lineage unavailability rejected an otherwise valid preferred fork");
        invoke(updater, "applyDiscoveredSource", new Class<?>[] { TargetConfig.class, List.class }, target, discovered);
        String lock = Files.readString(temp.resolve("updater.lock.yml"), StandardCharsets.UTF_8);
        require(lock.contains("source: https://github.com/Folia-Inquisitors/DemoPlugin"),
            "lineage-unavailable proof does not point to selected preferred fork");
        require(!lock.contains("forkRepo:"), "lineage-unavailable proof unexpectedly recorded fork lineage");
    }

    private static void preferredOwnerForkLineageDoesNotOverrideMismatch() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-mismatch-fork");
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"), "DemoPlugin", "2.0.0", "com.example.demo.DemoPlugin", true);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", preferredForkConfig(temp));
        TargetConfig target = target("DemoPlugin", temp);
        target.source = "Not Found";
        addRaw(updater, "Folia-Inquisitors", "DemoPlugin", "plugin.yml",
            descriptor("OtherPlugin", "2.1.0", "com.other.OtherPlugin", true));
        addRepoMetadata(updater, "Folia-Inquisitors", "DemoPlugin", false, false,
            true, "Official/DemoPlugin", "Official/DemoPlugin");

        @SuppressWarnings("unchecked")
        List<Object> candidates = (List<Object>) invoke(updater, "discoverTargetedGithubSources",
            new Class<?>[] { TargetConfig.class, int.class, boolean.class }, target, 0, true);
        require(candidates.isEmpty(), "fork lineage/preferred owner overrode descriptor mismatch");
    }

    private static void githubBudgetDefersPreferredForkDiscovery() throws Exception {
        repoHealthUnavailableDefersPreferredForkSelection();
    }

    private static void preferredOwnersParseFromYamlList() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-preferred-parse");
        Path configPath = temp.resolve("updater.yml");
        Files.writeString(configPath, """
            discovery:
              preferredOwners:
                - Folia-Inquisitors
                - Inquisitors-transfers
              sourcePriority: github-release
            """, StandardCharsets.UTF_8);
        Object config = invokeStatic("dev.autoupdater.AutoUpdater$ConfigParser", "parse",
            new Class<?>[] { Path.class }, configPath);
        Object discovery = getField(config, "discovery");
        @SuppressWarnings("unchecked")
        List<String> owners = (List<String>) getField(discovery, "preferredOwners");
        require(owners.equals(List.of("Folia-Inquisitors", "Inquisitors-transfers")),
            "preferredOwners YAML list did not parse correctly");
    }

    private static Object preferredForkConfig(Path temp) throws Exception {
        Object config = newConfig();
        setField(config, "baseDir", temp);
        TargetConfig server = (TargetConfig) getField(config, "server");
        server.project = "folia";
        Object discovery = getField(config, "discovery");
        setField(discovery, "enabled", true);
        setField(discovery, "sourcePriority", new ArrayList<>(List.of("github-release")));
        setField(discovery, "preferredOwners", new ArrayList<>(List.of("Folia-Inquisitors", "Inquisitors-transfers")));
        return config;
    }

    private static TargetConfig target(String name, Path temp) {
        TargetConfig target = new TargetConfig(name, false);
        target.installAs = "plugins/" + name + ".jar";
        target.detectedPluginId = name;
        target.detectedVersion = "2.0.0";
        target.detectedMainClass = "com.example.demo.DemoPlugin";
        target.source = "";
        target.sourceOrigin = "unresolved";
        return target;
    }

    private static void addRaw(Object updater, String owner, String repo, String path, String body) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, String> raw = (Map<String, String>) getField(updater, "testGithubRawResponses");
        raw.put("https://raw.githubusercontent.com/" + owner + "/" + repo + "/HEAD/" + path, body);
    }

    private static void addRepoMetadata(Object updater, String owner, String repo, boolean archived, boolean disabled) throws Exception {
        addRepoMetadata(updater, owner, repo, archived, disabled, false, "", "");
    }

    private static void addRepoMetadata(Object updater, String owner, String repo, boolean archived, boolean disabled,
                                        boolean fork, String parentRepo, String sourceRepo) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) getField(updater, "testJsonResponses");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("full_name", owner + "/" + repo);
        root.put("archived", archived);
        root.put("disabled", disabled);
        root.put("fork", fork);
        if (!parentRepo.isBlank()) {
            Map<String, Object> parent = new LinkedHashMap<>();
            parent.put("full_name", parentRepo);
            root.put("parent", parent);
        }
        if (!sourceRepo.isBlank()) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("full_name", sourceRepo);
            root.put("source", source);
        }
        json.put("https://api.github.com/repos/" + owner + "/" + repo, root);
    }

    private static String descriptor(String name, String version, String main, boolean folia) {
        return descriptorWithoutFolia(name, version, main) + "folia-supported: " + folia + "\n";
    }

    private static String descriptorWithoutFolia(String name, String version, String main) {
        return "name: " + name + "\n"
            + "version: " + version + "\n"
            + "main: " + main + "\n"
            + "api-version: \"1.13\"\n";
    }

    private static void writePluginJar(Path path, String name, String version, String main, boolean folia) throws Exception {
        Files.createDirectories(path.getParent());
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(descriptor(name, version, main, folia).getBytes(StandardCharsets.UTF_8));
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

    private static Object invokeStatic(String className, String name, Class<?>[] parameterTypes, Object... args) throws Exception {
        Method method = Class.forName(className).getDeclaredMethod(name, parameterTypes);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
