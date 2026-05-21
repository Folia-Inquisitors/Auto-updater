# Velocity Auto Updater

A small standalone launcher jar for hosted Velocity servers.

It is meant to be the jar your host starts first:

```bash
java -jar velocity-auto-updater.jar run
```

Then it updates configured jars, backs up anything it replaces, and starts the real Velocity jar.

## What It Supports

- Hosted-safe updates by default.
- Auto-detects PaperMC, GeyserMC, and direct jar URLs.
- Replaces jars while keeping the same configured filename.
- Backs up old jars into `backups/`.
- Downloads into `cache/staging/` first.
- Validates that downloaded files are readable jars.
- Starts Velocity as a child process.
- Forwards console input/output.
- Optional scheduled restarts with warning commands.

Build-from-source/Git mode is intentionally not enabled in this first jar. That keeps the hosted setup predictable on panels that may restrict Git, Gradle, Maven, or long-running builds.

## Build

On Windows:

```powershell
.\build.ps1
```

The jar is written to:

```text
dist/velocity-auto-updater.jar
```

## First Run

Create a starter config:

```bash
java -jar velocity-auto-updater.jar init
```

Or place `updater.example.yml` next to the jar as `updater.yml`.

Useful commands:

```bash
java -jar velocity-auto-updater.jar check
java -jar velocity-auto-updater.jar update
java -jar velocity-auto-updater.jar run
```

## BisectHosting Shape

Put these next to each other in the server root:

```text
velocity-auto-updater.jar
updater.yml
velocity.jar
plugins/
```

Set the custom startup jar to:

```text
velocity-auto-updater.jar
```

The updater will keep launching `velocity.jar` after it finishes update checks.
