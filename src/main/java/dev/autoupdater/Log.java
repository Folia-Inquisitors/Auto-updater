package dev.autoupdater;

final class Log {
    private Log() {
    }

    static void info(String message) {
        System.out.println("[updater] " + message);
    }

    static void warn(String message) {
        System.out.println("[updater:warn] " + message);
    }

    static void error(String message) {
        System.err.println("[updater:error] " + message);
    }
}
