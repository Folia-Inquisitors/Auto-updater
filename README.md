# Auto-Updater
This is a highly configurable, simple plugin meant to be an auto updater. 

## Directions

 Set the host/panel startup jar to: auto-updater.jar

This is meant to be a simple standalone launcher jar for Minecraft servers. It runs first, updates configured jars, backs up anything it replaces, and then starts the real server jar.

# Official Discord 

https://discord.gg/aT9z7q7hX8

## Building instructions

./gradlew build

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


### Folia inquisitors

[<img src="https://github.com/Folia-Inquisitors.png" width=80 alt="Folia-Inquisitors">](https://github.com/orgs/Folia-Inquisitors/repositories)


## First Run
Server Root Shape
```text
auto-updater.jar
updater.yml
folia.jar
plugins/
```
