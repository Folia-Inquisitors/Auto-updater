package dev.autoupdater;

final class HostedCandidate {
    final TargetConfig target;
    final SourcePlan plan;
    final ResolvedDownload download;

    HostedCandidate(TargetConfig target, SourcePlan plan, ResolvedDownload download) {
        this.target = target;
        this.plan = plan;
        this.download = download;
    }
}
