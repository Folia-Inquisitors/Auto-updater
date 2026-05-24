package dev.autoupdater;

import java.net.URI;
import java.time.Instant;

final class ResolvedDownload {
    final URI uri;
    final String label;
    final String sourceType;
    final String project;
    final String gameVersion;
    final String build;
    final String version;
    final Instant publishedAt;

    ResolvedDownload(URI uri, String label) {
        this(uri, label, "", "", "", "", "");
    }

    ResolvedDownload(URI uri, String label, String sourceType, String project, String gameVersion, String build) {
        this(uri, label, sourceType, project, gameVersion, build, "");
    }

    ResolvedDownload(URI uri, String label, String sourceType, String project, String gameVersion, String build, String version) {
        this(uri, label, sourceType, project, gameVersion, build, version, null);
    }

    ResolvedDownload(URI uri, String label, String sourceType, String project, String gameVersion, String build, String version, Instant publishedAt) {
        this.uri = uri;
        this.label = label;
        this.sourceType = sourceType;
        this.project = project;
        this.gameVersion = gameVersion;
        this.build = build;
        this.version = version;
        this.publishedAt = publishedAt;
    }
}
