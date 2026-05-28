package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;

public final class StartupRollbackPolicyRegressionTest {
    private StartupRollbackPolicyRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Object serverUpdate = installedUpdate(target("Paper", true, "paper.jar"), Path.of("paper.jar"));
        Object pluginUpdate = installedUpdate(target("PluginA", false, "plugins/PluginA.jar"), Path.of("plugins/PluginA.jar"));

        Object batchRunner = runnerWithPolicy("rollbackBatch");
        Object batchHealth = startupHealth(List.of(serverUpdate, pluginUpdate));
        invoke(batchHealth, "observe", new Class<?>[] { String.class }, "[ERROR]: Could not load 'plugins/PluginA.jar' in 'plugins'");
        List<?> batchRollback = rollbackTargets(batchRunner, batchHealth, true);
        require(batchRollback.contains(serverUpdate), "batch policy did not include server update for plugin load failure");
        require(batchRollback.contains(pluginUpdate), "batch policy did not include matched plugin update");
        require(batchRollback.size() == 2, "batch policy rolled back unexpected updates");

        Object matchedRunner = runnerWithPolicy("rollbackMatchedOnly");
        Object matchedHealth = startupHealth(List.of(serverUpdate, pluginUpdate));
        invoke(matchedHealth, "observe", new Class<?>[] { String.class }, "[ERROR]: Could not load 'plugins/PluginA.jar' in 'plugins'");
        List<?> matchedRollback = rollbackTargets(matchedRunner, matchedHealth, true);
        require(!matchedRollback.contains(serverUpdate), "matched-only policy incorrectly included server update");
        require(matchedRollback.contains(pluginUpdate), "matched-only policy did not include matched plugin update");
        require(matchedRollback.size() == 1, "matched-only policy rolled back unexpected updates");

        Object exitHealth = startupHealth(List.of(serverUpdate, pluginUpdate));
        List<?> exitRollback = allUpdatedJars(exitHealth);
        require(exitRollback.contains(serverUpdate) && exitRollback.contains(pluginUpdate) && exitRollback.size() == 2,
            "nonzero startup exit should roll back the full server+plugin batch");

        Object pluginOnlyHealth = startupHealth(List.of(pluginUpdate));
        invoke(pluginOnlyHealth, "observe", new Class<?>[] { String.class }, "[ERROR]: Could not load 'plugins/PluginA.jar' in 'plugins'");
        List<?> pluginOnlyRollback = rollbackTargets(batchRunner, pluginOnlyHealth, true);
        require(pluginOnlyRollback.contains(pluginUpdate) && pluginOnlyRollback.size() == 1,
            "plugin-only load failure should roll back the updated plugin");

        Object serverOnlyHealth = startupHealth(List.of(serverUpdate));
        List<?> serverOnlyRollback = allUpdatedJars(serverOnlyHealth);
        require(serverOnlyRollback.contains(serverUpdate) && serverOnlyRollback.size() == 1,
            "server-only nonzero startup exit should roll back the updated server jar");
    }

    private static TargetConfig target(String name, boolean server, String installAs) {
        TargetConfig target = new TargetConfig(name, server);
        target.installAs = installAs;
        target.source = server ? "https://papermc.io/downloads/paper" : "https://example.test/" + name + ".jar";
        return target;
    }

    private static Object runnerWithPolicy(String policy) throws Exception {
        Object config = newInstance("dev.autoupdater.AutoUpdater$AppConfig");
        Object restart = getField(config, "restart");
        setField(restart, "startupRollbackPolicy", policy);
        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        return newInstance("dev.autoupdater.AutoUpdater$ServerRunner", config, updater);
    }

    private static Object startupHealth(List<Object> updates) throws Exception {
        Class<?> health = Class.forName("dev.autoupdater.AutoUpdater$StartupHealthMonitor");
        Constructor<?> ctor = health.getDeclaredConstructor(List.class);
        ctor.setAccessible(true);
        return ctor.newInstance(updates);
    }

    private static List<?> rollbackTargets(Object runner, Object health, boolean matchedPluginFailure) throws Exception {
        Object result = invoke(runner, "startupRollbackTargets",
            new Class<?>[] { health.getClass(), boolean.class },
            health, matchedPluginFailure);
        return (List<?>) result;
    }

    private static List<?> allUpdatedJars(Object health) throws Exception {
        return (List<?>) invoke(health, "allUpdatedJars", new Class<?>[0]);
    }

    private static Object installedUpdate(TargetConfig target, Path path) throws Exception {
        Class<?> update = Class.forName("dev.autoupdater.AutoUpdater$InstalledUpdate");
        Constructor<?> ctor = update.getDeclaredConstructor(
            TargetConfig.class,
            Path.class,
            Path.class,
            String.class,
            String.class,
            String.class
        );
        ctor.setAccessible(true);
        return ctor.newInstance(target, path, path.resolveSibling(path.getFileName() + ".bak"), target.source, "1.0.0", "hash");
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
