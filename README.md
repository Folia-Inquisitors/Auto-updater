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

## Default Config

```


```


## Building instructions

./gradlew build

# Official Discord 

https://discord.gg/aT9z7q7hX8

### Folia inquisitors

[<img src="https://github.com/Folia-Inquisitors.png" width=80 alt="Folia-Inquisitors">](https://github.com/orgs/Folia-Inquisitors/repositories)
