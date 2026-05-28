package dev.autoupdater;

import java.util.ArrayList;
import java.util.List;

final class TargetConfig {
    String name;
    boolean server;
    boolean enabled = true;
    boolean autoUpdate = true;
    boolean required;
    String source;
    List<String> fallbackSources = new ArrayList<>();
    String type = "auto";
    String project;
    String githubRepo;
    String platform;
    String loader;
    String gameVersion;
    String versionType;
    Boolean changeVersion;
    String channel;
    String pinBuild;
    String installAs;
    String java = "java";
    String javaArgs = "";
    String args = "";
    boolean autoDiscovered;
    String detectedPluginId;
    String detectedVersion;
    String detectedWebsite;
    String detectedMainClass;
    String detectedAuthors;
    String sourceOrigin;
    boolean sourceOriginUpdatedThisRun;
    boolean sourceDiscoveredThisRun;

    TargetConfig(String name, boolean server) {
        this.name = name;
        this.server = server;
        this.required = server;
    }

    String displayName() {
        if (name != null && !name.isBlank()) {
            return name;
        }
        return server ? "Server" : installAs;
    }

    TargetConfig copyWithSource(String source) {
        TargetConfig copy = new TargetConfig(name, server);
        copy.enabled = enabled;
        copy.autoUpdate = autoUpdate;
        copy.required = required;
        copy.source = source;
        copy.type = "auto";
        copy.project = project;
        copy.githubRepo = githubRepo;
        copy.platform = platform;
        copy.loader = loader;
        copy.gameVersion = gameVersion;
        copy.versionType = versionType;
        copy.changeVersion = changeVersion;
        copy.channel = channel;
        copy.pinBuild = pinBuild;
        copy.installAs = installAs;
        copy.java = java;
        copy.javaArgs = javaArgs;
        copy.args = args;
        copy.autoDiscovered = autoDiscovered;
        copy.detectedPluginId = detectedPluginId;
        copy.detectedVersion = detectedVersion;
        copy.detectedWebsite = detectedWebsite;
        copy.detectedMainClass = detectedMainClass;
        copy.detectedAuthors = detectedAuthors;
        copy.sourceOrigin = sourceOrigin;
        copy.sourceOriginUpdatedThisRun = sourceOriginUpdatedThisRun;
        copy.sourceDiscoveredThisRun = sourceDiscoveredThisRun;
        return copy;
    }
}
