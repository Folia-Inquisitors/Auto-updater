package dev.autoupdater;

final class GithubRepo {
    final String owner;
    final String name;
    final String ref;

    GithubRepo(String owner, String name) {
        this(owner, name, "");
    }

    GithubRepo(String owner, String name, String ref) {
        this.owner = owner;
        this.name = name;
        this.ref = ref == null ? "" : ref;
    }
}
