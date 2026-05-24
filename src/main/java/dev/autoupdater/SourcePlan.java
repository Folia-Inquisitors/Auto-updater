package dev.autoupdater;

final class SourcePlan {
    final String type;
    final String description;
    final DownloadResolver resolver;

    SourcePlan(String type, String description, DownloadResolver resolver) {
        this.type = type;
        this.description = description;
        this.resolver = resolver;
    }
}
