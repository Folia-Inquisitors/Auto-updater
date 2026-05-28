package dev.autoupdater;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Utf8BomConfigRegressionTest {
    private Utf8BomConfigRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempDirectory("autoupdater-bom-config");
        Path configPath = temp.resolve("updater.yml");
        byte[] bom = new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
        byte[] body = """
            discovery:
              enabled: false

            server:
              source: ""
              installAs: server.jar

            plugins:
            """.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);
        Files.write(configPath, bytes);

        Object config = parseConfig(configPath);
        Object discovery = getField(config, "discovery");
        Object enabled = getField(discovery, "enabled");
        require(Boolean.FALSE.equals(enabled), "UTF-8 BOM config did not parse discovery.enabled");
    }

    private static Object parseConfig(Path path) throws Exception {
        Class<?> parser = Class.forName("dev.autoupdater.AutoUpdater$ConfigParser");
        Method method = parser.getDeclaredMethod("parse", Path.class);
        method.setAccessible(true);
        return method.invoke(null, path);
    }

    private static Object getField(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
