# Auto-Updater
The other auto updaters were too confusing for me. This is meant to be a simple, auto updater that auto updates the server jar, and the plugin used.

## Directions

Use "auto-updater.jar" as the startup jar

This is meant to be a simple standalone launcher jar for Minecraft servers. It runs first, updates configured jars, backs up anything it replaces, and then starts the real server jar.

## What It Supports

- Folia, Paper, Velocity
- Hangar plugin downloads.
- GitHub release jar downloads. Will compile jars.
- Modrinth plugin downloads.
- Direct jar URLs and local jar paths.
- Scheduled restarts with warning commands.
- Per-plugin `autoUpdate` opt-out.
- Failure memory for plugin jars that fail startup. It will restart with peviously working jars.
## Default Config

```
mode: hosted-safe
onFailure: keep-current
userAgent: "Auto-Updater/0.3.0 (contact: your-email@example.com)"

newPluginLinks: []
# Paste plugin links here, then run the updater. Examples:
# newPluginLinks:
#   - https://github.com/Folia-Inquisitors/ExamplePlugin
#   - https://modrinth.com/plugin/example/versions
#   - https://hangar.papermc.io/Owner/Example/versions

discovery:
  enabled: true
  mode: suggest
  sourcePriority: github-release, hangar, modrinth
  checkAlternateSourcesWhenOutdated: true
  outdatedThresholdDays: 14
  autoSwitchSource: true
  saveDiscoveredSources: true
  scanInstalledPlugins: true
  pruneMissingInstalledPlugins: true
  retryDeferredAfterStartup: true
  pathfindingDebug: false
  pathfindingDebugPlugin: ""
  pathfindingDebugFile: architecture-pathfinding.debug
  # preferredOwners:
  #   - Folia-Inquisitors
  #   - Inquisitors-transfers

buildFromSource:
  enabled: auto
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
  pinBuild: ""
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
  startupRollbackPolicy: rollbackBatch
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
