# Auto-Updater
The other auto updaters were too confusing for me. This is meant to be a simple, auto updater that auto updates the server jar, and the plugin used.

## Directions

Use "auto-updater.jar" as the startup jar

This is meant to be a simple standalone launcher jar for Minecraft servers. It runs first, updates configured jars, backs up anything it replaces, and then starts the real server jar.

## First Run
Server Root Shape
```text
auto-updater.jar
updater.yml
folia.jar
plugins/
```

## What It Supports

- Folia, Paper, Velocity, and other PaperMC downloads.
- `changeVersion: false` for safer server jar updates within the same configured version.
- Hangar plugin downloads.
- GitHub release jar downloads.
- Modrinth plugin downloads.
- SpigotMC downloads
- Direct jar URLs and local jar paths.
- searchable stable `installAs` filenames.
- Backup and staging folders.
- Scheduled restarts with warning commands.
- Explicit fallback sources.
- Fallback on prevous working version if fails to load.
- Discovery policy reporting with trusted GitHub org/repo settings.
- Saves discovered plugin sources back into `updater.yml`.
- Per-plugin `autoUpdate` opt-out.
- Failure memory for plugin jars that fail startup.

## Default Config

```
mode: hosted-safe
onFailure: keep-current
userAgent: "Auto-Updater/0.3.0 (contact: your-email@example.com)"

discovery:
  enabled: true
  mode: suggest
  sourcePriority: github-release, hangar, modrinth, spigot
  checkAlternateSourcesWhenOutdated: true
  outdatedThresholdDays: 14
  autoSwitchSource: true
  saveDiscoveredSources: true
  scanInstalledPlugins: true

buildFromSource:
  enabled: false
  onlyTrusted: true
  preferHostedIfSameVersion: true
  trustedGithubOrgs: PaperMC, GeyserMC, ViaVersion
  trustedGithubRepos: Inquisitors-transfers/MyCustomPlugin

failureMemory:
  enabled: true
  retryBadAfter: never

server:
  name: auto
  source: auto
  type: auto
  installAs: auto
  gameVersion: auto
  changeVersion: false
  java: java
  javaArgs: "-Xms16G -Xmx32G"
  args: ""

plugins:
  - name: ViaVersion
    source: https://github.com/ViaVersion/ViaVersion
    type: auto
    autoUpdate: true
    githubRepo: ViaVersion/ViaVersion
    platform: paper
    fallbackSources: https://hangar.papermc.io/ViaVersion/ViaVersion/versions, https://modrinth.com/plugin/viaversion/versions
    installAs: plugins/ViaVersion.jar
    required: false

  - name: ViaBackwards
    source: https://github.com/ViaVersion/ViaBackwards
    type: auto
    autoUpdate: true
    githubRepo: ViaVersion/ViaBackwards
    platform: paper
    fallbackSources: https://hangar.papermc.io/ViaVersion/ViaBackwards/versions, https://modrinth.com/plugin/viabackwards/versions
    installAs: plugins/ViaBackwards.jar
    required: false

  - name: ViaRewind
    source: https://github.com/ViaVersion/ViaRewind
    type: auto
    autoUpdate: true
    githubRepo: ViaVersion/ViaRewind
    platform: paper
    fallbackSources: https://hangar.papermc.io/ViaVersion/ViaRewind/versions, https://modrinth.com/plugin/viarewind/versions
    installAs: plugins/ViaRewind.jar
    required: false

restart:
  enabled: true
  interval: 7d
  stopCommand: stop
  gracefulStopSeconds: 60
  warnings:
    - before: 2h
      command: "say Server restart in 2 hours for updates."
    - before: 30m
      command: "say Server restart in 30 minutes for updates."
    - before: 5m
      command: "say Server restart in 5 minutes for updates."
    - before: 1m
      command: "say Server restart in 1 minute for updates."

```


## Building instructions

./gradlew build

# Official Discord 

https://discord.gg/aT9z7q7hX8

### Folia inquisitors

[<img src="https://github.com/Folia-Inquisitors.png" width=80 alt="Folia-Inquisitors">](https://github.com/orgs/Folia-Inquisitors/repositories)
