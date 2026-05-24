package dev.autoupdater;

interface DownloadResolver {
    ResolvedDownload resolve(TargetConfig target) throws Exception;
}
