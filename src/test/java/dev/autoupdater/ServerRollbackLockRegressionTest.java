package dev.autoupdater;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ServerRollbackLockRegressionTest {
    private ServerRollbackLockRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-server-rollback-regression");
        Path configPath = temp.resolve("updater.yml");
        Files.writeString(configPath, "server:\n  installAs: paper.jar\n", StandardCharsets.UTF_8);

        Path serverJar = temp.resolve("paper.jar");
        Path backupJar = temp.resolve("paper.jar.old.bak");
        Files.writeString(backupJar, "old-server-build", StandardCharsets.UTF_8);
        Files.writeString(serverJar, "new-server-build", StandardCharsets.UTF_8);
        Files.writeString(temp.resolve("updater.lock.yml"), """
            serverProject: paper
            serverGameVersion: 1.21.11
            serverBuild: 200
            """, StandardCharsets.UTF_8);

        Object config = newInstance("dev.autoupdater.AutoUpdater$AppConfig");
        setField(config, "baseDir", temp);
        setField(config, "configPath", configPath);

        TargetConfig server = new TargetConfig("Paper", true);
        server.installAs = "paper.jar";
        server.source = "https://papermc.io/downloads/paper";
        server.project = "paper";
        server.gameVersion = "1.21.11";
        setField(config, "server", server);

        Object updater = newInstance("dev.autoupdater.AutoUpdater$Updater", config);
        Object runner = newInstance("dev.autoupdater.AutoUpdater$ServerRunner", config, updater);
        Object previousLock = newServerLockSnapshot("paper", "1.21.11", "100");
        Object update = newInstalledUpdate(server, serverJar, backupJar, previousLock);

        invoke(runner, "rollbackUpdates", new Class<?>[] { List.class }, List.of(update));

        String jarText = Files.readString(serverJar, StandardCharsets.UTF_8);
        String lockText = Files.readString(temp.resolve("updater.lock.yml"), StandardCharsets.UTF_8);
        require(jarText.equals("old-server-build"), "rollback did not restore old server jar");
        require(lockText.contains("serverProject: paper"), "rollback removed server project lock");
        require(lockText.contains("serverGameVersion: 1.21.11"), "rollback removed server version lock");
        require(lockText.contains("serverBuild: 100"), "rollback did not restore old server build lock");
        require(!lockText.contains("serverBuild: 200"), "rollback left failed server build locked as current");
    }

    private static Object newServerLockSnapshot(String project, String gameVersion, String build) throws Exception {
        Class<?> snapshot = Class.forName("dev.autoupdater.AutoUpdater$ServerLockSnapshot");
        Constructor<?> ctor = snapshot.getDeclaredConstructor(String.class, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(project, gameVersion, build);
    }

    private static Object newInstalledUpdate(TargetConfig server, Path serverJar, Path backupJar, Object previousLock) throws Exception {
        Class<?> update = Class.forName("dev.autoupdater.AutoUpdater$InstalledUpdate");
        Class<?> snapshot = Class.forName("dev.autoupdater.AutoUpdater$ServerLockSnapshot");
        Constructor<?> ctor = update.getDeclaredConstructor(
            TargetConfig.class,
            Path.class,
            Path.class,
            String.class,
            String.class,
            String.class,
            snapshot
        );
        ctor.setAccessible(true);
        return ctor.newInstance(server, serverJar, backupJar, server.source, "1.21.11", "newhash", previousLock);
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
