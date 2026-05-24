package dev.velocityupdater;

/**
 * Temporary compatibility launcher for scripts or jars that still refer to the
 * old Velocity-specific main class name.
 */
public final class VelocityAutoUpdater {
    private VelocityAutoUpdater() {
    }

    public static void main(String[] args) {
        dev.autoupdater.AutoUpdater.main(args);
    }
}
