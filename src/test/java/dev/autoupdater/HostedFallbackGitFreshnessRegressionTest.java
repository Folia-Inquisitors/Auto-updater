package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class HostedFallbackGitFreshnessRegressionTest {
    private HostedFallbackGitFreshnessRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Object config = newInstance("dev.autoupdater.AutoUpdater$AppConfig");
        Object buildFromSource = getField(config, "buildFromSource");
        setField(buildFromSource, "enabled", "auto");
        setField(buildFromSource, "onlyTrusted", true);
        setField(buildFromSource, "preferHostedIfSameVersion", true);
        setField(buildFromSource, "trustedGithubRepos", List.of("example/DemoPlugin"));

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        TargetConfig target = new TargetConfig("DemoPlugin", false);
        target.installAs = "plugins/DemoPlugin.jar";
        target.source = "https://modrinth.com/plugin/demo-plugin/versions";
        target.type = "github-source";
        target.githubRepo = "example/DemoPlugin";

        Instant gitCommit = Instant.parse("2026-01-02T00:00:00Z");
        HostedCandidate hostedA = hostedCandidate(
            target.copyWithSource("https://modrinth.com/plugin/demo-plugin/versions"),
            "modrinth",
            "2.0.0",
            gitCommit.plusSeconds(86_400)
        );
        HostedCandidate hostedB = hostedCandidate(
            target.copyWithSource("https://hangar.papermc.io/example/DemoPlugin/versions"),
            "hangar",
            "1.9.0",
            gitCommit.minusSeconds(86_400)
        );

        boolean buildBeforeFreshestCandidate = shouldBuildFromNewerGitSource(updater, target, hostedA, gitCommit);
        boolean buildBeforeFallbackCandidate = shouldBuildFromNewerGitSource(updater, target, hostedB, gitCommit);

        require(!buildBeforeFreshestCandidate, "Git should not beat hosted candidate A when A is newer than Git");
        require(buildBeforeFallbackCandidate, "Git should be reconsidered and beat hosted candidate B when B is older than Git");
    }

    private static HostedCandidate hostedCandidate(TargetConfig target, String type, String version, Instant publishedAt) {
        SourcePlan plan = new SourcePlan(type, target.source, ignored -> new ResolvedDownload(URI.create(target.source), type, type, "", "", "", version, publishedAt));
        ResolvedDownload download = new ResolvedDownload(URI.create(target.source), type + " " + version, type, "", "", "", version, publishedAt);
        return new HostedCandidate(target, plan, download);
    }

    private static boolean shouldBuildFromNewerGitSource(Object updater, TargetConfig target, HostedCandidate hosted, Instant gitCommit) throws Exception {
        Object result = invoke(updater, "shouldBuildFromNewerGitSource",
            new Class<?>[] { TargetConfig.class, HostedCandidate.class, Optional.class },
            target, hosted, Optional.of(gitCommit));
        return Boolean.TRUE.equals(result);
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
