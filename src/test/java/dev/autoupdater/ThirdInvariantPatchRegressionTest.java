package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class ThirdInvariantPatchRegressionTest {
    private ThirdInvariantPatchRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        explicitGithubSourcePrimaryBuildsFirst();
        staleGithubRepoDoesNotOverrideExplicitSource();
        serverBuildLockDoesNotPinForeverAndKnownBadIsSkipped();
        discoveryDisabledPreventsNormalRunSideEffects();
        persistedDiscoveredSourcesKeepAutoValidationThreshold();
    }

    private static void explicitGithubSourcePrimaryBuildsFirst() throws Exception {
        Object config = newConfig();
        Object buildFromSource = getField(config, "buildFromSource");
        setField(buildFromSource, "enabled", "auto");
        setField(buildFromSource, "onlyTrusted", false);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.installAs = "plugins/DemoPlugin.jar";
        target.source = "https://github.com/NewOwner/NewRepo";
        target.type = "github-source";
        target.githubRepo = "OldOwner/OldRepo";
        target.fallbackSources.add("https://modrinth.com/plugin/demo/versions");

        boolean primaryFirst = (Boolean) invoke(updater, "shouldBuildExplicitPrimarySourceFirst",
            new Class<?>[] { TargetConfig.class }, target);
        TargetConfig buildTarget = (TargetConfig) invoke(updater, "sourceBuildTarget",
            new Class<?>[] { TargetConfig.class }, target);

        require(primaryFirst, "explicit github-source primary was not marked for first build");
        require("https://github.com/NewOwner/NewRepo".equals(buildTarget.source),
            "sourceBuildTarget used stale githubRepo instead of the explicit GitHub source");
    }

    private static void staleGithubRepoDoesNotOverrideExplicitSource() throws Exception {
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", newConfig());
        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.installAs = "plugins/DemoPlugin.jar";
        target.type = "auto";
        target.githubRepo = "OldOwner/OldRepo";

        target.source = "https://example.com/MyPlugin.jar";
        require("direct".equals(detectType(updater, target)), "direct URL was hijacked by stale githubRepo");

        target.source = "https://hangar.papermc.io/example/DemoPlugin/versions";
        require("hangar".equals(detectType(updater, target)), "Hangar URL was hijacked by stale githubRepo");

        target.source = "https://modrinth.com/plugin/demo/versions";
        require("modrinth".equals(detectType(updater, target)), "Modrinth URL was hijacked by stale githubRepo");

        target.source = "";
        require("github-release".equals(detectType(updater, target)), "blank source with githubRepo did not resolve as GitHub release");

        target.source = "https://github.com/NewOwner/NewRepo";
        String repo = (String) invoke(updater, "gitRepoHint", new Class<?>[] { TargetConfig.class }, target);
        require("NewOwner/NewRepo".equals(repo), "explicit GitHub source URL did not beat stale githubRepo");

        target.type = "github-source";
        require("github-source".equals(detectType(updater, target)), "explicit type github-source was not preserved");
    }

    private static void serverBuildLockDoesNotPinForeverAndKnownBadIsSkipped() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-server-build-selection");
        Object config = newConfig();
        setField(config, "baseDir", temp);
        Object failureMemory = getField(config, "failureMemory");
        setField(failureMemory, "enabled", true);
        setField(failureMemory, "retryBadAfter", "never");

        TargetConfig server = new TargetConfig("Folia", true);
        server.project = "folia";
        server.gameVersion = "1.21.11";
        server.changeVersion = false;

        Object resolver = newInstance("dev.autoupdater.AutoUpdater$PaperMcResolver", config, HttpClient.newHttpClient());
        List<Map<String, Object>> builds = new ArrayList<>();
        builds.add(serverBuild("101"));
        builds.add(serverBuild("100"));

        ResolvedDownload latest = selectBuild(resolver, "folia", "1.21.11", server, "", builds);
        require("101".equals(latest.build), "latest build within locked game version was not selected");

        Files.writeString(temp.resolve("updater.lock.yml"), """
            serverProject: folia
            serverGameVersion: 1.21.11
            serverBuild: 100
            badServerBuilds:
              folia/1.21.11/101:
                project: folia
                gameVersion: 1.21.11
                build: 101
                reason: startup-exit-failed
                failedAt: 2026-05-28T00:00:00Z
            """, StandardCharsets.UTF_8);

        ResolvedDownload skippedBad = selectBuild(resolver, "folia", "1.21.11", server, "", builds);
        require("100".equals(skippedBad.build), "known-bad newer server build was retried instead of skipped");

        ResolvedDownload pinned = selectBuild(resolver, "folia", "1.21.11", server, "100", builds);
        require("100".equals(pinned.build), "explicit server pinBuild did not keep the pinned build");
    }

    private static void discoveryDisabledPreventsNormalRunSideEffects() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-discovery-disabled");
        Files.createDirectories(temp.resolve("plugins"));
        writePluginJar(temp.resolve("plugins/DemoPlugin.jar"));

        Object config = newConfig();
        setField(config, "baseDir", temp);
        Object discovery = getField(config, "discovery");
        setField(discovery, "enabled", false);
        setField(discovery, "scanInstalledPlugins", true);
        setField(discovery, "autoSwitchSource", true);

        @SuppressWarnings("unchecked")
        List<TargetConfig> plugins = (List<TargetConfig>) getField(config, "plugins");
        invoke(config, "autoAddInstalledPlugins", new Class<?>[0]);
        require(plugins.isEmpty(), "discovery.enabled=false still auto-added installed plugins");

        TargetConfig missing = new TargetConfig("MissingSource", false);
        missing.installAs = "plugins/MissingSource.jar";
        missing.source = "Not Found";
        missing.sourceOrigin = "unresolved";
        plugins.add(missing);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        invoke(updater, "autoSwitchMissingPluginSources", new Class<?>[0]);
        require("Not Found".equals(missing.source), "discovery.enabled=false still rewrote a missing source");
    }

    private static void persistedDiscoveredSourcesKeepAutoValidationThreshold() throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-validation-origin");
        Object config = newConfig();
        setField(config, "baseDir", temp);
        Object validation = getField(config, "validation");
        setField(validation, "minAutoInstallScore", 42);
        setField(validation, "minTrustedSourceScore", 87);

        SourcePlan plan = new SourcePlan("direct", "direct", ignored -> null);
        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.installAs = "plugins/DemoPlugin.jar";
        target.source = "https://example.com/DemoPlugin.jar";

        target.sourceOrigin = "discovered-unverified";
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        int unverified = (Integer) invoke(updater, "validationMinimum",
            new Class<?>[] { TargetConfig.class, SourcePlan.class }, target, plan);
        require(unverified == 42, "discovered-unverified source used trusted validation threshold after reload");

        target.sourceOrigin = "manual";
        int manual = (Integer) invoke(updater, "validationMinimum",
            new Class<?>[] { TargetConfig.class, SourcePlan.class }, target, plan);
        require(manual == 87, "manual source did not use trusted validation threshold");

        Files.writeString(temp.resolve("updater.lock.yml"), """
            sourceProofs:
              plugins/DemoPlugin.jar:
                source: https://example.com/DemoPlugin.jar
                type: direct
                repo: ""
                proof: descriptor-match
                descriptorPath: plugin.yml
                pluginId: DemoPlugin
                mainClass: com.example.DemoPlugin
                verifiedAt: 2026-05-28T00:00:00Z
            """, StandardCharsets.UTF_8);
        target.sourceOrigin = "discovered";
        Object proofAwareUpdater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        int proven = (Integer) invoke(proofAwareUpdater, "validationMinimum",
            new Class<?>[] { TargetConfig.class, SourcePlan.class }, target, plan);
        require(proven == 87, "descriptor-proven discovered source did not use the documented trusted threshold");
    }

    private static String detectType(Object updater, TargetConfig target) throws Exception {
        return (String) invoke(updater, "detectType", new Class<?>[] { String.class, TargetConfig.class }, target.source, target);
    }

    private static ResolvedDownload selectBuild(Object resolver, String project, String version, TargetConfig target,
                                                String pinnedBuild, List<Map<String, Object>> builds) throws Exception {
        @SuppressWarnings("unchecked")
        Optional<ResolvedDownload> selected = (Optional<ResolvedDownload>) invoke(resolver, "selectBuild",
            new Class<?>[] { String.class, String.class, TargetConfig.class, String.class, List.class },
            project, version, target, pinnedBuild, builds);
        return selected.orElseThrow(() -> new AssertionError("no server build selected"));
    }

    private static Map<String, Object> serverBuild(String number) {
        Map<String, Object> build = new LinkedHashMap<>();
        build.put("number", number);
        build.put("channel", "STABLE");
        Map<String, Object> download = new LinkedHashMap<>();
        download.put("url", "file:///tmp/server-" + number + ".jar");
        Map<String, Object> downloads = new LinkedHashMap<>();
        downloads.put("server:default", download);
        build.put("downloads", downloads);
        return build;
    }

    private static void writePluginJar(Path path) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path))) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write("""
                name: DemoPlugin
                version: 1.0.0
                main: com.example.DemoPlugin
                """.getBytes(StandardCharsets.UTF_8));
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
