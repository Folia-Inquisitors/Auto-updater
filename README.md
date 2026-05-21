# Auto-Updater

A standalone launcher jar for Minecraft servers. It runs first, updates configured jars, backs up anything it replaces, and then starts the real server jar.

```bash
java -jar auto-updater.jar run
```

## What It Supports

- Folia, Paper, Velocity, and other PaperMC downloads.
- `changeVersion: false` for safer server jar updates within the same configured version.
- Hangar plugin downloads.
- GitHub release jar downloads.
- Modrinth plugin downloads.
- SpigotMC free-resource downloads through Spiget.
- GeyserMC direct download URLs.
- Direct jar URLs and local jar paths.
- Stable `installAs` filenames.
- Backup and staging folders.
- Scheduled restarts with warning commands.
- Explicit fallback sources.
- Discovery policy reporting with trusted GitHub org/repo settings.

Build-from-source/Git mode is still not enabled. The config has trust settings for that future path, and hosted jars are preferred when they can avoid a build.

## Build

On Windows:

```powershell
.\build.ps1
```

The jar is written to:

```text
dist/auto-updater.jar
```

## First Run

Create a starter config:

```bash
java -jar auto-updater.jar init
```

Or place `updater.example.yml` next to the jar as `updater.yml`.

Useful commands:

```bash
java -jar auto-updater.jar check
java -jar auto-updater.jar discover
java -jar auto-updater.jar update
java -jar auto-updater.jar run
```

## Server Root Shape

```text
auto-updater.jar
updater.yml
folia.jar
plugins/
```

Set the host/panel startup jar to:

```text
auto-updater.jar
```
