package dev.autoupdater;

final class SourceOwnerSignal {
    final int scoreDelta;
    final boolean conflict;
    final String reason;

    SourceOwnerSignal(int scoreDelta, boolean conflict, String reason) {
        this.scoreDelta = scoreDelta;
        this.conflict = conflict;
        this.reason = reason;
    }
}
