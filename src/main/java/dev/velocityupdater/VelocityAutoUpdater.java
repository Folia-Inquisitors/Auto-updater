package dev.velocityupdater;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class VelocityAutoUpdater {
    private static final String APP_NAME = "Auto-Updater";
    private static final String VERSION = "0.3.0";
    private static final String DEFAULT_CONFIG = "updater.yml";
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public static void main(String[] args) {
        int code;
        try {
            code = new VelocityAutoUpdater().run(args);
        } catch (Exception ex) {
            Log.error("Fatal error: " + ex.getMessage());
            if (Boolean.getBoolean("velocityUpdater.debug")) {
                ex.printStackTrace(System.err);
            }
            code = 1;
        }
        if (code != 0) {
            System.exit(code);
        }
    }

    private int run(String[] args) throws Exception {
        Cli cli = Cli.parse(args);
        if (cli.command.equals("help") || cli.command.equals("-h") || cli.command.equals("--help")) {
            printHelp();
            return 0;
        }

        Path configPath = cli.configPath.toAbsolutePath().normalize();
        if (cli.command.equals("init")) {
            return initConfig(configPath);
        }

        if (!Files.exists(configPath)) {
            Log.warn("No " + configPath.getFileName() + " found. Creating a starter config.");
            writeExampleConfig(configPath);
            Log.warn("Edit " + configPath + ", then run this jar again.");
            return 2;
        }

        AppConfig config = ConfigParser.parse(configPath);
        config.configPath = configPath;
        config.baseDir = configPath.getParent() == null ? Paths.get(".").toAbsolutePath().normalize() : configPath.getParent();
        config.validate();

        Updater updater = new Updater(config);
        switch (cli.command) {
            case "check":
                updater.printPlan();
                return 0;
            case "discover":
                updater.discover();
                return 0;
            case "update":
                updater.updateAll();
                return 0;
            case "run":
                List<InstalledUpdate> startupUpdates = updater.updateAll();
                return new ServerRunner(config, updater).runServerLoop(startupUpdates);
            default:
                Log.error("Unknown command: " + cli.command);
                printHelp();
                return 1;
        }
    }

    private int initConfig(Path configPath) throws IOException {
        if (Files.exists(configPath)) {
            Log.warn(configPath + " already exists; leaving it alone.");
            return 0;
        }
        writeExampleConfig(configPath);
        Log.info("Created " + configPath);
        return 0;
    }

    private static void writeExampleConfig(Path configPath) throws IOException {
        Path parent = configPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(configPath, ExampleConfig.TEXT, StandardCharsets.UTF_8);
    }

    private static void printHelp() {
        System.out.println(APP_NAME + " " + VERSION);
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar auto-updater.jar init [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar check [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar discover [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar update [--config updater.yml]");
        System.out.println("  java -jar auto-updater.jar run [--config updater.yml]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init    Create a starter updater.yml");
        System.out.println("  check   Parse config and show detected update sources");
        System.out.println("  discover  Suggest/update-source discovery plan without changing jars");
        System.out.println("  update  Download/update configured jars, then exit");
        System.out.println("  run     Update jars, start the configured server, and manage scheduled restarts");
    }

    private static final class Cli {
        final String command;
        final Path configPath;

        private Cli(String command, Path configPath) {
            this.command = command;
            this.configPath = configPath;
        }

        static Cli parse(String[] args) {
            String command = args.length == 0 ? "run" : args[0].trim();
            Path config = Paths.get(DEFAULT_CONFIG);
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (arg.equals("--config") || arg.equals("-c")) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--config needs a path");
                    }
                    config = Paths.get(args[++i]);
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            return new Cli(command, config);
        }
    }

    private static final class AppConfig {
        Path baseDir = Paths.get(".").toAbsolutePath().normalize();
        Path configPath;
        String mode = "hosted-safe";
        String onFailure = "keep-current";
        String userAgent = APP_NAME + "/" + VERSION + " (contact: your-email@example.com)";
        Path cacheDir = Paths.get("cache");
        Path backupDir = Paths.get("backups");
        TargetConfig server = new TargetConfig(null, true);
        List<TargetConfig> plugins = new ArrayList<>();
        DiscoveryConfig discovery = new DiscoveryConfig();
        BuildFromSourceConfig buildFromSource = new BuildFromSourceConfig();
        RestartConfig restart = new RestartConfig();

        void validate() {
            mode = lower(mode);
            onFailure = lower(onFailure);
            if (!mode.equals("hosted-safe") && !mode.equals("auto")) {
                throw new IllegalArgumentException("This build supports mode: hosted-safe or auto. Found: " + mode);
            }
            if (!onFailure.equals("keep-current") && !onFailure.equals("stop")) {
                throw new IllegalArgumentException("onFailure must be keep-current or stop");
            }
            if (server.changeVersion == null) {
                server.changeVersion = false;
            }
            applyServerAutoDefaults(server);
            if (server.source == null || server.source.isBlank()) {
                Log.warn("No server.source configured. The updater will only launch the existing " + server.installAs + ".");
            }
            autoAddInstalledPlugins();
            for (TargetConfig plugin : plugins) {
                if (plugin.changeVersion == null) {
                    plugin.changeVersion = true;
                }
                if (plugin.installAs == null || plugin.installAs.isBlank()) {
                    if (plugin.name != null && !plugin.name.isBlank()) {
                        plugin.installAs = "plugins/" + safeName(plugin.name) + ".jar";
                    } else {
                        throw new IllegalArgumentException("Each plugin needs installAs when name is missing");
                    }
                }
                enrichPluginFromInstalledJar(plugin);
            }
            restart.warnings.sort(Comparator.comparing((RestartWarning w) -> w.before).reversed());
        }

        private void applyServerAutoDefaults(TargetConfig server) {
            ServerJarDetection detected = detectExistingServerJar(baseDir, server);
            boolean sourceWasAuto = isAutoValue(server.source);
            if ((server.project == null || server.project.isBlank()) && sourceWasAuto && detected.hasProject()) {
                server.project = detected.project;
            }
            if (sourceWasAuto) {
                if (detected.hasProject()) {
                    server.source = paperMcDownloadSource(detected.project);
                    Log.info("Auto-detected server source: " + server.source);
                } else {
                    server.source = "";
                    Log.warn("server.source is auto, but no Paper/Folia/Velocity jar filename was detected. Set server.source manually to enable server jar updates.");
                }
            }
            String project = inferPaperMcProject(server);
            if (project.isBlank() && detected.hasProject()) {
                project = detected.project;
            }
            if (server.name == null || server.name.isBlank() || lower(server.name).equals("auto")) {
                server.name = project.isBlank() ? "Server" : title(project);
                Log.info("Auto-detected server name: " + server.name);
            }
            if (server.installAs == null || server.installAs.isBlank() || lower(server.installAs).equals("auto")) {
                server.installAs = sourceWasAuto && detected.path != null ? relativeConfigPath(baseDir, detected.path) : (project.isBlank() ? "server.jar" : project + ".jar");
                Log.info("Auto-detected server jar filename: " + server.installAs);
            }
            if (server.gameVersion != null && lower(server.gameVersion).equals("auto")) {
                Log.info("Server gameVersion is auto. The updater will use updater.lock.yml if present, otherwise it will lock the latest available PaperMC version on the next update.");
            }
        }

        private void autoAddInstalledPlugins() {
            if (!discovery.scanInstalledPlugins) {
                return;
            }
            Path pluginDir = resolve(Paths.get("plugins"));
            if (!Files.isDirectory(pluginDir)) {
                return;
            }
            Set<String> configured = new HashSet<>();
            for (TargetConfig plugin : plugins) {
                if (plugin.installAs != null && !plugin.installAs.isBlank()) {
                    configured.add(normalizedConfigPath(plugin.installAs));
                }
            }
            List<Path> jars = listJarFiles(pluginDir);
            for (Path jar : jars) {
                String installAs = relativeConfigPath(baseDir, jar);
                if (configured.contains(normalizedConfigPath(installAs))) {
                    continue;
                }
                PluginJarInfo info = readPluginJarInfo(jar);
                TargetConfig plugin = new TargetConfig(info.name, false);
                plugin.installAs = installAs;
                plugin.source = "";
                plugin.type = "auto";
                plugin.required = false;
                plugin.platform = inferredPluginPlatform(server);
                plugin.autoDiscovered = true;
                plugin.detectedPluginId = info.id;
                plugin.detectedVersion = info.version;
                plugin.detectedWebsite = info.website;
                plugins.add(plugin);
                configured.add(normalizedConfigPath(installAs));
                Log.info("Auto-discovered installed plugin: " + plugin.name + " -> " + plugin.installAs);
            }
        }

        private void enrichPluginFromInstalledJar(TargetConfig plugin) {
            if (plugin.installAs == null || plugin.installAs.isBlank()) {
                return;
            }
            Path jar = resolve(Paths.get(plugin.installAs));
            if (!Files.isRegularFile(jar)) {
                return;
            }
            PluginJarInfo info = readPluginJarInfo(jar);
            if ((plugin.name == null || plugin.name.isBlank() || isAutoValue(plugin.name)) && !info.name.isBlank()) {
                plugin.name = info.name;
            }
            if (plugin.detectedPluginId == null || plugin.detectedPluginId.isBlank()) {
                plugin.detectedPluginId = info.id;
            }
            if (plugin.detectedVersion == null || plugin.detectedVersion.isBlank()) {
                plugin.detectedVersion = info.version;
            }
            if (plugin.detectedWebsite == null || plugin.detectedWebsite.isBlank()) {
                plugin.detectedWebsite = info.website;
            }
        }

        Path resolve(Path path) {
            if (path.isAbsolute()) {
                return path.normalize();
            }
            return baseDir.resolve(path).normalize();
        }
    }

    private static final class DiscoveryConfig {
        boolean enabled = false;
        String mode = "suggest";
        List<String> sourcePriority = new ArrayList<>(List.of("github-release", "hangar", "modrinth", "spigot"));
        boolean checkAlternateSourcesWhenOutdated = true;
        int outdatedThresholdDays = 14;
        boolean autoSwitchSource = true;
        boolean saveDiscoveredSources = true;
        boolean scanInstalledPlugins = true;
    }

    private static final class BuildFromSourceConfig {
        boolean enabled = false;
        boolean onlyTrusted = true;
        boolean preferHostedIfSameVersion = true;
        List<String> trustedGithubOrgs = new ArrayList<>();
        List<String> trustedGithubRepos = new ArrayList<>();
    }

    private static final class TargetConfig {
        String name;
        boolean server;
        boolean enabled = true;
        boolean required;
        String source;
        List<String> fallbackSources = new ArrayList<>();
        String type = "auto";
        String project;
        String githubRepo;
        String platform;
        String loader;
        String gameVersion;
        String versionType;
        Boolean changeVersion;
        String channel;
        String installAs;
        String java = "java";
        String javaArgs = "";
        String args = "";
        boolean autoDiscovered;
        String detectedPluginId;
        String detectedVersion;
        String detectedWebsite;
        boolean sourceDiscoveredThisRun;

        TargetConfig(String name, boolean server) {
            this.name = name;
            this.server = server;
            this.required = server;
        }

        String displayName() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return server ? "Server" : installAs;
        }

        TargetConfig copyWithSource(String source) {
            TargetConfig copy = new TargetConfig(name, server);
            copy.enabled = enabled;
            copy.required = required;
            copy.source = source;
            copy.type = "auto";
            copy.project = project;
            copy.githubRepo = githubRepo;
            copy.platform = platform;
            copy.loader = loader;
            copy.gameVersion = gameVersion;
            copy.versionType = versionType;
            copy.changeVersion = changeVersion;
            copy.channel = channel;
            copy.installAs = installAs;
            copy.java = java;
            copy.javaArgs = javaArgs;
            copy.args = args;
            copy.autoDiscovered = autoDiscovered;
            copy.detectedPluginId = detectedPluginId;
            copy.detectedVersion = detectedVersion;
            copy.detectedWebsite = detectedWebsite;
            copy.sourceDiscoveredThisRun = sourceDiscoveredThisRun;
            return copy;
        }
    }

    private static final class RestartConfig {
        boolean enabled = false;
        Duration interval = Duration.ofDays(7);
        String stopCommand = "shutdown";
        int gracefulStopSeconds = 60;
        List<RestartWarning> warnings = new ArrayList<>();
    }

    private static final class RestartWarning {
        Duration before = Duration.ZERO;
        String command;
    }

    private static final class ConfigParser {
        static AppConfig parse(Path path) throws IOException {
            AppConfig config = new AppConfig();
            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            String section = "";
            String restartSubsection = "";
            TargetConfig currentPlugin = null;
            RestartWarning currentWarning = null;

            for (int lineNo = 1; lineNo <= lines.size(); lineNo++) {
                String raw = lines.get(lineNo - 1);
                String noComment = stripComment(raw);
                if (noComment.trim().isEmpty()) {
                    continue;
                }
                int indent = countIndent(noComment);
                String line = noComment.trim();

                if (indent == 0) {
                    currentPlugin = null;
                    currentWarning = null;
                    restartSubsection = "";
                    KeyValue kv = keyValue(line, lineNo);
                    if (kv.value.isEmpty()) {
                        section = lower(kv.key);
                    } else {
                        section = "";
                        applyTopLevel(config, kv);
                    }
                    continue;
                }

                switch (section) {
                    case "server":
                        applyTarget(config.server, keyValue(line, lineNo));
                        break;
                    case "discovery":
                        applyDiscovery(config.discovery, keyValue(line, lineNo));
                        break;
                    case "buildfromsource":
                    case "build_from_source":
                        applyBuildFromSource(config.buildFromSource, keyValue(line, lineNo));
                        break;
                    case "plugins":
                        if (line.startsWith("- ")) {
                            currentPlugin = new TargetConfig(null, false);
                            config.plugins.add(currentPlugin);
                            String rest = line.substring(2).trim();
                            if (!rest.isEmpty()) {
                                applyTarget(currentPlugin, keyValue(rest, lineNo));
                            }
                        } else if (currentPlugin != null) {
                            applyTarget(currentPlugin, keyValue(line, lineNo));
                        } else {
                            throw new IllegalArgumentException("Plugin entry before '-' at line " + lineNo);
                        }
                        break;
                    case "restart":
                        if (line.equals("warnings:")) {
                            restartSubsection = "warnings";
                            currentWarning = null;
                        } else if (restartSubsection.equals("warnings")) {
                            if (line.startsWith("- ")) {
                                currentWarning = new RestartWarning();
                                config.restart.warnings.add(currentWarning);
                                String rest = line.substring(2).trim();
                                if (!rest.isEmpty()) {
                                    applyWarning(currentWarning, keyValue(rest, lineNo));
                                }
                            } else if (currentWarning != null) {
                                applyWarning(currentWarning, keyValue(line, lineNo));
                            } else {
                                throw new IllegalArgumentException("Restart warning before '-' at line " + lineNo);
                            }
                        } else {
                            applyRestart(config.restart, keyValue(line, lineNo));
                        }
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown section '" + section + "' at line " + lineNo);
                }
            }
            return config;
        }

        private static void applyTopLevel(AppConfig config, KeyValue kv) {
            switch (lower(kv.key)) {
                case "mode":
                    config.mode = kv.value;
                    break;
                case "onfailure":
                case "on_failure":
                    config.onFailure = kv.value;
                    break;
                case "useragent":
                case "user_agent":
                    config.userAgent = kv.value;
                    break;
                case "cachedir":
                case "cache_dir":
                    config.cacheDir = Paths.get(kv.value);
                    break;
                case "backupdir":
                case "backup_dir":
                    config.backupDir = Paths.get(kv.value);
                    break;
                case "discoversources":
                case "discover_sources":
                    config.discovery.enabled = parseBoolean(kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown config key: " + kv.key);
            }
        }

        private static void applyTarget(TargetConfig target, KeyValue kv) {
            switch (lower(kv.key)) {
                case "name":
                    target.name = kv.value;
                    break;
                case "enabled":
                    target.enabled = parseBoolean(kv.value);
                    break;
                case "required":
                    target.required = parseBoolean(kv.value);
                    break;
                case "source":
                    target.source = kv.value;
                    break;
                case "fallbacksources":
                case "fallback_sources":
                    target.fallbackSources = parseList(kv.value);
                    break;
                case "type":
                    target.type = kv.value;
                    break;
                case "project":
                    target.project = kv.value;
                    break;
                case "githubrepo":
                case "github_repo":
                    target.githubRepo = kv.value;
                    break;
                case "platform":
                    target.platform = kv.value;
                    break;
                case "loader":
                    target.loader = kv.value;
                    break;
                case "gameversion":
                case "game_version":
                    target.gameVersion = kv.value;
                    break;
                case "versiontype":
                case "version_type":
                    target.versionType = kv.value;
                    break;
                case "changeversion":
                case "change_version":
                    target.changeVersion = parseBoolean(kv.value);
                    break;
                case "channel":
                    target.channel = kv.value;
                    break;
                case "installas":
                case "install_as":
                    target.installAs = kv.value;
                    break;
                case "java":
                    target.java = kv.value;
                    break;
                case "javaargs":
                case "java_args":
                    target.javaArgs = kv.value;
                    break;
                case "args":
                    target.args = kv.value;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown target key: " + kv.key);
            }
        }

        private static void applyDiscovery(DiscoveryConfig discovery, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    discovery.enabled = parseBoolean(kv.value);
                    break;
                case "mode":
                    discovery.mode = kv.value;
                    break;
                case "sourcepriority":
                case "source_priority":
                    discovery.sourcePriority = parseList(kv.value);
                    break;
                case "checkalternatesourceswhenoutdated":
                case "check_alternate_sources_when_outdated":
                    discovery.checkAlternateSourcesWhenOutdated = parseBoolean(kv.value);
                    break;
                case "outdatedthresholddays":
                case "outdated_threshold_days":
                    discovery.outdatedThresholdDays = Integer.parseInt(kv.value);
                    break;
                case "autoswitchsource":
                case "auto_switch_source":
                    discovery.autoSwitchSource = parseBoolean(kv.value);
                    break;
                case "savediscoveredsources":
                case "save_discovered_sources":
                    discovery.saveDiscoveredSources = parseBoolean(kv.value);
                    break;
                case "scaninstalledplugins":
                case "scan_installed_plugins":
                    discovery.scanInstalledPlugins = parseBoolean(kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown discovery key: " + kv.key);
            }
        }

        private static void applyBuildFromSource(BuildFromSourceConfig build, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    build.enabled = parseBoolean(kv.value);
                    break;
                case "onlytrusted":
                case "only_trusted":
                    build.onlyTrusted = parseBoolean(kv.value);
                    break;
                case "preferhostedifsameversion":
                case "prefer_hosted_if_same_version":
                    build.preferHostedIfSameVersion = parseBoolean(kv.value);
                    break;
                case "trustedgithuborgs":
                case "trusted_github_orgs":
                    build.trustedGithubOrgs = parseList(kv.value);
                    break;
                case "trustedgithubrepos":
                case "trusted_github_repos":
                    build.trustedGithubRepos = parseList(kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown buildFromSource key: " + kv.key);
            }
        }

        private static void applyRestart(RestartConfig restart, KeyValue kv) {
            switch (lower(kv.key)) {
                case "enabled":
                    restart.enabled = parseBoolean(kv.value);
                    break;
                case "interval":
                    restart.interval = parseDuration(kv.value);
                    break;
                case "stopcommand":
                case "stop_command":
                    restart.stopCommand = kv.value;
                    break;
                case "gracefulstopseconds":
                case "graceful_stop_seconds":
                    restart.gracefulStopSeconds = Integer.parseInt(kv.value);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown restart key: " + kv.key);
            }
        }

        private static void applyWarning(RestartWarning warning, KeyValue kv) {
            switch (lower(kv.key)) {
                case "before":
                    warning.before = parseDuration(kv.value);
                    break;
                case "command":
                    warning.command = kv.value;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown warning key: " + kv.key);
            }
        }

        private static KeyValue keyValue(String line, int lineNo) {
            int colon = findColon(line);
            if (colon < 0) {
                throw new IllegalArgumentException("Expected key: value at line " + lineNo + ": " + line);
            }
            String key = line.substring(0, colon).trim();
            String value = unquote(line.substring(colon + 1).trim());
            return new KeyValue(key, value);
        }

        private static int findColon(String line) {
            boolean single = false;
            boolean dbl = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && !dbl) {
                    single = !single;
                } else if (c == '"' && !single) {
                    dbl = !dbl;
                } else if (c == ':' && !single && !dbl) {
                    return i;
                }
            }
            return -1;
        }

        private static String stripComment(String line) {
            boolean single = false;
            boolean dbl = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && !dbl) {
                    single = !single;
                } else if (c == '"' && !single) {
                    dbl = !dbl;
                } else if (c == '#' && !single && !dbl) {
                    return line.substring(0, i);
                }
            }
            return line;
        }

        private static int countIndent(String line) {
            int count = 0;
            while (count < line.length() && line.charAt(count) == ' ') {
                count++;
            }
            return count;
        }

        private static String unquote(String value) {
            if (value.length() >= 2) {
                char first = value.charAt(0);
                char last = value.charAt(value.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return value.substring(1, value.length() - 1);
                }
            }
            return value;
        }
    }

    private static final class ConfigRewriter {
        static void saveDiscoveredPluginSources(AppConfig config, List<TargetConfig> targets) throws IOException {
            if (config.configPath == null) {
                Log.warn("Config path is unknown; discovered sources were not saved.");
                return;
            }
            List<String> lines = Files.exists(config.configPath)
                ? Files.readAllLines(config.configPath, StandardCharsets.UTF_8)
                : new ArrayList<>();
            boolean changed = false;
            int saved = 0;
            for (TargetConfig target : targets) {
                PluginBlock block = findPluginBlock(lines, target);
                if (block == null) {
                    appendPluginBlock(lines, target);
                    changed = true;
                    saved++;
                } else if (updatePluginBlock(lines, block, target)) {
                    changed = true;
                    saved++;
                }
            }
            if (!changed) {
                return;
            }

            String newline = detectNewline(config.configPath);
            String text = String.join(newline, lines) + newline;
            Path temp = config.configPath.resolveSibling(config.configPath.getFileName() + ".tmp");
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            try {
                Files.move(temp, config.configPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temp, config.configPath, StandardCopyOption.REPLACE_EXISTING);
            }
            Log.info("Saved discovered source" + (saved == 1 ? "" : "s") + " to " + config.configPath.getFileName() + ".");
        }

        private static String detectNewline(Path path) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                int lf = text.indexOf('\n');
                if (lf > 0 && text.charAt(lf - 1) == '\r') {
                    return "\r\n";
                }
            } catch (IOException ignored) {
                // Fall back to the platform newline.
            }
            return System.lineSeparator();
        }

        private static PluginBlock findPluginBlock(List<String> lines, TargetConfig target) {
            int pluginsStart = findTopLevelSection(lines, "plugins");
            if (pluginsStart < 0) {
                return null;
            }
            int pluginsEnd = findNextTopLevel(lines, pluginsStart + 1);
            for (int i = pluginsStart + 1; i < pluginsEnd; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty()) {
                    continue;
                }
                int indent = ConfigParser.countIndent(noComment);
                String line = noComment.trim();
                if (!line.startsWith("- ")) {
                    continue;
                }
                int end = i + 1;
                while (end < pluginsEnd) {
                    String candidateNoComment = ConfigParser.stripComment(lines.get(end));
                    String candidateTrimmed = candidateNoComment.trim();
                    if (!candidateTrimmed.isEmpty()) {
                        int candidateIndent = ConfigParser.countIndent(candidateNoComment);
                        if (candidateIndent <= indent && candidateTrimmed.startsWith("- ")) {
                            break;
                        }
                    }
                    end++;
                }
                PluginBlock block = new PluginBlock(i, end, indent, Math.max(indent + 2, 4));
                Map<String, String> fields = parsePluginFields(lines, block);
                if (matchesPluginBlock(fields, target)) {
                    return block;
                }
                i = end - 1;
            }
            return null;
        }

        private static int findTopLevelSection(List<String> lines, String section) {
            for (int i = 0; i < lines.size(); i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (noComment.trim().isEmpty() || ConfigParser.countIndent(noComment) != 0) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(noComment.trim(), 0);
                    if (kv.value.isEmpty() && lower(kv.key).equals(section)) {
                        return i;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Ignore lines that are not simple key/value entries.
                }
            }
            return -1;
        }

        private static int findNextTopLevel(List<String> lines, int start) {
            for (int i = start; i < lines.size(); i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                if (!noComment.trim().isEmpty() && ConfigParser.countIndent(noComment) == 0) {
                    return i;
                }
            }
            return lines.size();
        }

        private static Map<String, String> parsePluginFields(List<String> lines, PluginBlock block) {
            Map<String, String> fields = new HashMap<>();
            for (int i = block.start; i < block.end; i++) {
                String line = ConfigParser.stripComment(lines.get(i)).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (i == block.start && line.startsWith("- ")) {
                    line = line.substring(2).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                } else if (line.startsWith("- ")) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(line, 0);
                    fields.put(normalizedKey(kv.key), kv.value);
                } catch (IllegalArgumentException ignored) {
                    // Ignore nested or unsupported YAML syntax.
                }
            }
            return fields;
        }

        private static boolean matchesPluginBlock(Map<String, String> fields, TargetConfig target) {
            String installAs = fields.get("installas");
            if (installAs != null && target.installAs != null
                && normalizedConfigPath(installAs).equals(normalizedConfigPath(target.installAs))) {
                return true;
            }
            String name = fields.get("name");
            return name != null && target.name != null && lower(name).equals(lower(target.name));
        }

        private static boolean updatePluginBlock(List<String> lines, PluginBlock block, TargetConfig target) {
            boolean changed = false;
            changed |= upsertPluginKey(lines, block, "source", target.source);
            changed |= upsertPluginKey(lines, block, "type", "auto");
            if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                changed |= upsertPluginKey(lines, block, "githubRepo", target.githubRepo);
            }
            if (target.platform != null && !target.platform.isBlank()) {
                changed |= upsertPluginKey(lines, block, "platform", target.platform);
            }
            if (!target.fallbackSources.isEmpty()) {
                changed |= upsertPluginKey(lines, block, "fallbackSources", String.join(", ", target.fallbackSources));
            }
            if (target.installAs != null && !target.installAs.isBlank()) {
                changed |= upsertPluginKey(lines, block, "installAs", target.installAs);
            }
            changed |= upsertPluginKey(lines, block, "required", Boolean.toString(target.required));
            return changed;
        }

        private static boolean upsertPluginKey(List<String> lines, PluginBlock block, String key, String value) {
            String normalized = normalizedKey(key);
            String rendered = spaces(block.propertyIndent) + key + ": " + quoteYaml(value);
            for (int i = block.start; i < block.end; i++) {
                String noComment = ConfigParser.stripComment(lines.get(i));
                String line = noComment.trim();
                boolean firstInlineKey = false;
                if (i == block.start && line.startsWith("- ")) {
                    firstInlineKey = true;
                    line = line.substring(2).trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                } else if (line.startsWith("- ")) {
                    continue;
                }
                try {
                    KeyValue kv = ConfigParser.keyValue(line, 0);
                    if (normalizedKey(kv.key).equals(normalized)) {
                        String replacement = firstInlineKey
                            ? spaces(block.itemIndent) + "- " + key + ": " + quoteYaml(value)
                            : rendered;
                        if (lines.get(i).equals(replacement)) {
                            return false;
                        }
                        lines.set(i, replacement);
                        return true;
                    }
                } catch (IllegalArgumentException ignored) {
                    // Keep looking.
                }
            }
            lines.add(block.end, rendered);
            block.end++;
            return true;
        }

        private static void appendPluginBlock(List<String> lines, TargetConfig target) {
            int pluginsStart = findTopLevelSection(lines, "plugins");
            if (pluginsStart < 0) {
                if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) {
                    lines.add("");
                }
                lines.add("plugins:");
                pluginsStart = lines.size() - 1;
            }
            int insertAt = findNextTopLevel(lines, pluginsStart + 1);
            List<String> entry = pluginEntryLines(target);
            if (insertAt > 0 && !lines.get(insertAt - 1).isBlank()) {
                entry.add(0, "");
            }
            if (insertAt < lines.size() && !entry.get(entry.size() - 1).isBlank()) {
                entry.add("");
            }
            lines.addAll(insertAt, entry);
        }

        private static List<String> pluginEntryLines(TargetConfig target) {
            List<String> entry = new ArrayList<>();
            entry.add("  - name: " + quoteYaml(target.displayName()));
            entry.add("    source: " + quoteYaml(target.source));
            entry.add("    type: auto");
            if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                entry.add("    githubRepo: " + quoteYaml(target.githubRepo));
            }
            if (target.platform != null && !target.platform.isBlank()) {
                entry.add("    platform: " + quoteYaml(target.platform));
            }
            if (!target.fallbackSources.isEmpty()) {
                entry.add("    fallbackSources: " + quoteYaml(String.join(", ", target.fallbackSources)));
            }
            entry.add("    installAs: " + quoteYaml(target.installAs));
            entry.add("    required: " + target.required);
            return entry;
        }

        private static String normalizedKey(String key) {
            return lower(key).replace("_", "");
        }

        private static String spaces(int count) {
            return " ".repeat(Math.max(0, count));
        }

        private static final class PluginBlock {
            final int start;
            int end;
            final int itemIndent;
            final int propertyIndent;

            PluginBlock(int start, int end, int itemIndent, int propertyIndent) {
                this.start = start;
                this.end = end;
                this.itemIndent = itemIndent;
                this.propertyIndent = propertyIndent;
            }
        }
    }

    private static final class KeyValue {
        final String key;
        final String value;

        KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
        }
    }

    private static final class ServerJarDetection {
        final Path path;
        final String project;

        ServerJarDetection(Path path, String project) {
            this.path = path;
            this.project = project == null ? "" : project;
        }

        boolean hasProject() {
            return !project.isBlank();
        }
    }

    private static final class PluginJarInfo {
        final String id;
        final String name;
        final String version;
        final String website;

        PluginJarInfo(String id, String name, String version, String website) {
            this.id = firstNonBlank(id, "");
            this.name = firstNonBlank(name, "");
            this.version = firstNonBlank(version, "");
            this.website = firstNonBlank(website, "");
        }
    }

    private static final class DiscoveryCandidate {
        final String type;
        final String source;
        final String projectHint;
        final String latestVersion;
        final String label;
        final String reason;
        final int score;
        final int priority;

        DiscoveryCandidate(String type, String source, String projectHint, String latestVersion, String label, String reason, int score, int priority) {
            this.type = type;
            this.source = source;
            this.projectHint = projectHint;
            this.latestVersion = latestVersion;
            this.label = label;
            this.reason = reason;
            this.score = score;
            this.priority = priority;
        }
    }

    private static final class InstalledUpdate {
        final TargetConfig target;
        final Path targetPath;
        final Path backupPath;

        InstalledUpdate(TargetConfig target, Path targetPath, Path backupPath) {
            this.target = target;
            this.targetPath = targetPath;
            this.backupPath = backupPath;
        }

        boolean hasBackup() {
            return backupPath != null && Files.exists(backupPath);
        }
    }

    private static final class Updater {
        private final AppConfig config;
        private final HttpClient client;

        Updater(AppConfig config) {
            this.config = config;
            this.client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        }

        void printPlan() {
            Log.info("Mode: " + config.mode);
            Log.info("Failure behavior: " + config.onFailure);
            Log.info("Discovery: " + (config.discovery.enabled ? "enabled (" + config.discovery.mode + ")" : "disabled"));
            Log.info("Installed plugin scan: " + (config.discovery.scanInstalledPlugins ? "enabled" : "disabled"));
            Log.info("Build from source: " + (config.buildFromSource.enabled ? "enabled" : "disabled")
                + ", preferHostedIfSameVersion=" + config.buildFromSource.preferHostedIfSameVersion);
            Log.info("Server install target: " + config.resolve(Paths.get(config.server.installAs)));
            for (TargetConfig target : allTargets()) {
                if (!target.enabled) {
                    Log.info("Skipping disabled target: " + target.displayName());
                    continue;
                }
                if (target.source == null || target.source.isBlank() || isAutoValue(target.source)) {
                    Log.info(target.displayName() + ": no source configured, installAs=" + target.installAs);
                    continue;
                }
                SourcePlan plan = resolveSource(target);
                String fallbacks = target.fallbackSources.isEmpty() ? "" : ", fallbacks=" + target.fallbackSources.size();
                Log.info(target.displayName() + ": type=" + plan.type + ", installAs=" + target.installAs + ", source=" + plan.description + fallbacks);
            }
            if (config.restart.enabled) {
                Log.info("Restart: every " + prettyDuration(config.restart.interval) + " using command '" + config.restart.stopCommand + "'");
            } else {
                Log.info("Restart: disabled");
            }
        }

        void discover() {
            Log.info("Discovery mode: " + config.discovery.mode + " (" + (config.discovery.enabled ? "enabled" : "disabled in normal run") + ")");
            Log.info("Source priority: " + String.join(" -> ", config.discovery.sourcePriority));
            Log.info("Check alternate sources when outdated: " + config.discovery.checkAlternateSourcesWhenOutdated
                + " after " + config.discovery.outdatedThresholdDays + " days");
            Log.info("Auto-switch source: " + config.discovery.autoSwitchSource);
            Log.info("Save discovered sources: " + config.discovery.saveDiscoveredSources);
            Log.info("Scan installed plugins: " + config.discovery.scanInstalledPlugins);
            Log.info("Build from source: " + (config.buildFromSource.enabled ? "enabled" : "disabled")
                + ", onlyTrusted=" + config.buildFromSource.onlyTrusted
                + ", preferHostedIfSameVersion=" + config.buildFromSource.preferHostedIfSameVersion);
            if (!config.buildFromSource.trustedGithubOrgs.isEmpty()) {
                Log.info("Trusted GitHub orgs: " + String.join(", ", config.buildFromSource.trustedGithubOrgs));
            }
            if (!config.buildFromSource.trustedGithubRepos.isEmpty()) {
                Log.info("Trusted GitHub repos: " + String.join(", ", config.buildFromSource.trustedGithubRepos));
            }

            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server) {
                    continue;
                }
                Log.info("");
                Log.info("Discovery target: " + target.displayName());
                if (target.source != null && !target.source.isBlank() && !isAutoValue(target.source)) {
                    SourcePlan plan = resolveSource(target);
                    Log.info("Current source: " + plan.type + " -> " + plan.description);
                } else {
                    Log.info("Current source: none");
                }
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                DiscoveryCandidate best = discovered.isEmpty() ? null : discovered.get(0);
                if (best != null) {
                    Log.info("Best discovered source: " + best.type + " -> " + best.source
                        + " (score " + best.score + ", latest=" + firstNonBlank(best.latestVersion, "unknown") + ")");
                    Log.info("Why: " + best.reason);
                } else if (target.source == null || target.source.isBlank() || isAutoValue(target.source)) {
                    Log.warn("No reliable hosted source found. Keeping source empty until you set one manually.");
                }
                if (discovered.size() > 1) {
                    int shown = Math.min(3, discovered.size());
                    for (int i = 1; i < shown; i++) {
                        DiscoveryCandidate candidate = discovered.get(i);
                        Log.info("Alternate source: " + candidate.type + " -> " + candidate.source
                            + " (score " + candidate.score + ", latest=" + firstNonBlank(candidate.latestVersion, "unknown") + ")");
                    }
                }
                boolean autoSwitched = false;
                if (config.discovery.autoSwitchSource) {
                    if (best != null && needsDiscoveredSource(target)) {
                        Log.info("autoSwitchSource will use " + best.source + " for " + target.displayName() + ".");
                        applyDiscoveredSource(target, discovered);
                        autoSwitched = true;
                    } else {
                        Log.info("autoSwitchSource is enabled.");
                    }
                }
                if (target.autoDiscovered || target.source == null || target.source.isBlank() || isAutoValue(target.source)) {
                    Log.info("Suggested config entry:");
                    Log.info("  - name: " + target.displayName());
                    Log.info("    source: " + quoteYaml(best == null ? "" : best.source));
                    Log.info("    type: auto");
                    if (best != null && best.type.equals("github-release") && !best.projectHint.isBlank()) {
                        Log.info("    githubRepo: " + best.projectHint);
                    }
                    if (target.platform != null && !target.platform.isBlank()) {
                        Log.info("    platform: " + target.platform);
                    }
                    Log.info("    installAs: " + target.installAs);
                    Log.info("    required: false");
                }
                if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                    boolean trusted = isTrustedGithubRepo(target.githubRepo);
                    Log.info("GitHub repo hint: " + target.githubRepo + (trusted ? " (trusted)" : " (not trusted for source builds)"));
                    if (config.buildFromSource.preferHostedIfSameVersion) {
                        Log.info("Hosted jar preference: if a GitHub release/Hangar/Modrinth jar matches the build version, download it and skip compiling.");
                    }
                }
                if (target.fallbackSources.isEmpty()) {
                    Log.info("Fallback sources: none configured");
                } else {
                    for (String fallback : target.fallbackSources) {
                        TargetConfig candidate = target.copyWithSource(fallback);
                        SourcePlan plan = resolveSource(candidate);
                        Log.info("Fallback source: " + plan.type + " -> " + plan.description);
                    }
                }
                if (autoSwitched && config.discovery.saveDiscoveredSources) {
                    Log.info("saveDiscoveredSources will write this source back to the config.");
                }
            }
            saveDiscoveredSourcesIfRequested();
        }

        private void autoSwitchMissingPluginSources() {
            if (!config.discovery.autoSwitchSource) {
                return;
            }
            for (TargetConfig target : allTargets()) {
                if (!target.enabled || target.server || !needsDiscoveredSource(target)) {
                    continue;
                }
                Log.info("Auto-switch discovery for " + target.displayName() + ".");
                List<DiscoveryCandidate> discovered = discoverSourceCandidates(target);
                if (discovered.isEmpty()) {
                    Log.warn("No reliable hosted source found for " + target.displayName() + "; keeping existing jar.");
                    continue;
                }
                applyDiscoveredSource(target, discovered);
            }
            saveDiscoveredSourcesIfRequested();
        }

        private boolean needsDiscoveredSource(TargetConfig target) {
            return target.source == null || target.source.isBlank() || isAutoValue(target.source);
        }

        private void applyDiscoveredSource(TargetConfig target, List<DiscoveryCandidate> discovered) {
            DiscoveryCandidate best = discovered.get(0);
            target.source = best.source;
            target.type = "auto";
            if (best.type.equals("github-release") && !best.projectHint.isBlank()) {
                target.githubRepo = best.projectHint;
            }
            target.sourceDiscoveredThisRun = true;

            Set<String> fallbackSet = new HashSet<>(target.fallbackSources);
            for (DiscoveryCandidate candidate : discovered) {
                if (candidate.source.equals(best.source)) {
                    continue;
                }
                if (fallbackSet.add(candidate.source)) {
                    target.fallbackSources.add(candidate.source);
                }
                if (target.fallbackSources.size() >= 3) {
                    break;
                }
            }
            Log.info("Auto-switched " + target.displayName() + " source -> " + best.source
                + (target.fallbackSources.isEmpty() ? "" : " with " + target.fallbackSources.size() + " fallback(s)"));
        }

        private void saveDiscoveredSourcesIfRequested() {
            if (!config.discovery.saveDiscoveredSources) {
                return;
            }
            List<TargetConfig> changed = new ArrayList<>();
            for (TargetConfig target : config.plugins) {
                if (target.sourceDiscoveredThisRun && target.source != null && !target.source.isBlank()) {
                    changed.add(target);
                }
            }
            if (changed.isEmpty()) {
                return;
            }
            try {
                ConfigRewriter.saveDiscoveredPluginSources(config, changed);
            } catch (IOException ex) {
                Log.warn("Could not save discovered sources to config: " + ex.getMessage());
            }
        }

        private List<DiscoveryCandidate> discoverSourceCandidates(TargetConfig target) {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (target.detectedWebsite != null && !target.detectedWebsite.isBlank()) {
                addWebsiteCandidate(target, target.detectedWebsite, candidates, seen, 0);
            }
            List<String> priority = config.discovery.sourcePriority.isEmpty()
                ? List.of("github-release", "hangar", "modrinth", "spigot")
                : config.discovery.sourcePriority;
            for (int i = 0; i < priority.size(); i++) {
                String type = lower(priority.get(i));
                try {
                    switch (type) {
                        case "github":
                        case "github-release":
                            addCandidates(discoverGithubSources(target, i), candidates, seen);
                            break;
                        case "hangar":
                            addCandidates(discoverHangarSources(target, i), candidates, seen);
                            break;
                        case "modrinth":
                            addCandidates(discoverModrinthSources(target, i), candidates, seen);
                            break;
                        case "spigot":
                        case "spiget":
                            addCandidates(discoverSpigotSources(target, i), candidates, seen);
                            break;
                        default:
                            Log.warn("Unknown discovery source priority entry: " + type);
                            break;
                    }
                } catch (Exception ex) {
                    Log.warn("Discovery provider " + type + " failed for " + target.displayName() + ": " + ex.getMessage());
                }
            }
            candidates.sort(Comparator
                .comparingInt((DiscoveryCandidate c) -> c.priority)
                .thenComparing(Comparator.comparingInt((DiscoveryCandidate c) -> c.score).reversed())
                .thenComparing(c -> c.type));
            return candidates;
        }

        private void addCandidates(List<DiscoveryCandidate> source, List<DiscoveryCandidate> candidates, Set<String> seen) {
            for (DiscoveryCandidate candidate : source) {
                if (candidate.score < 50) {
                    Log.info("Ignoring weak/stale " + candidate.type + " candidate for " + candidate.label + " (" + candidate.reason + ")");
                    continue;
                }
                String key = lower(candidate.type + "|" + candidate.source);
                if (seen.add(key)) {
                    candidates.add(candidate);
                }
            }
        }

        private void addWebsiteCandidate(TargetConfig target, String website, List<DiscoveryCandidate> candidates, Set<String> seen, int priority) {
            String lowerWebsite = lower(website);
            try {
                if (lowerWebsite.contains("github.com/")) {
                    GithubRepo repo = repoFromGithubUrl(website);
                    latestGithubCandidate(target, repo, priority, "plugin metadata website").ifPresent(candidate -> addCandidates(List.of(candidate), candidates, seen));
                } else if (lowerWebsite.contains("hangar.papermc.io/")) {
                    TargetConfig candidateTarget = target.copyWithSource(website);
                    ResolvedDownload download = new HangarResolver(config, client).resolve(candidateTarget);
                    addCandidates(List.of(candidateFromResolved(target, "hangar", website, "", latestFromLabel(download.label), download.label, priority, "plugin metadata website")), candidates, seen);
                } else if (lowerWebsite.contains("modrinth.com/")) {
                    TargetConfig candidateTarget = target.copyWithSource(website);
                    ResolvedDownload download = new ModrinthResolver(config, client).resolve(candidateTarget);
                    addCandidates(List.of(candidateFromResolved(target, "modrinth", website, "", latestFromLabel(download.label), download.label, priority, "plugin metadata website")), candidates, seen);
                } else if (lowerWebsite.contains("spigotmc.org/resources") || lowerWebsite.contains("api.spiget.org/")) {
                    addCandidates(List.of(spigotCandidateFromSource(target, website, priority, "plugin metadata website")), candidates, seen);
                }
            } catch (Exception ex) {
                Log.warn("Metadata website did not resolve for " + target.displayName() + ": " + website + " (" + ex.getMessage() + ")");
            }
        }

        private List<DiscoveryCandidate> discoverGithubSources(TargetConfig target, int priority) throws Exception {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            if (target.githubRepo != null && !target.githubRepo.isBlank()) {
                latestGithubCandidate(target, repoFromGithubValue(target.githubRepo), priority, "configured githubRepo").ifPresent(candidates::add);
            }
            for (String term : discoverySearchTerms(target)) {
                URI uri = URI.create("https://api.github.com/search/repositories?q="
                    + urlEncode(term + " minecraft plugin")
                    + "&per_page=8");
                Object json = getJson(uri, "GitHub repository search");
                Object itemsObj = asMap(json).get("items");
                if (!(itemsObj instanceof List<?> items)) {
                    continue;
                }
                for (Object item : items) {
                    Map<String, Object> repoMap = asMap(item);
                    if (Boolean.TRUE.equals(repoMap.get("archived")) || Boolean.TRUE.equals(repoMap.get("disabled"))) {
                        continue;
                    }
                    String fullName = stringValue(repoMap.get("full_name"));
                    if (!fullName.contains("/")) {
                        continue;
                    }
                    String name = stringValue(repoMap.get("name"));
                    int match = nameMatchScore(target, name, fullName);
                    if (match < 35) {
                        continue;
                    }
                    latestGithubCandidate(target, repoFromGithubValue(fullName), priority, "GitHub search match: " + name).ifPresent(candidates::add);
                }
            }
            return candidates;
        }

        private Optional<DiscoveryCandidate> latestGithubCandidate(TargetConfig target, GithubRepo repo, int priority, String reason) {
            try {
                URI uri = URI.create("https://api.github.com/repos/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name) + "/releases?per_page=10");
                Object json = getJson(uri, "GitHub releases");
                if (!(json instanceof List<?> releases)) {
                    return Optional.empty();
                }
                for (Object item : releases) {
                    Map<String, Object> release = asMap(item);
                    if (Boolean.TRUE.equals(release.get("draft"))) {
                        continue;
                    }
                    if (target.versionType == null || target.versionType.isBlank()) {
                        if (Boolean.TRUE.equals(release.get("prerelease"))) {
                            continue;
                        }
                    }
                    Optional<Map<String, Object>> asset = githubJarAsset(release);
                    if (asset.isEmpty()) {
                        continue;
                    }
                    String version = firstNonBlank(stringValue(release.get("tag_name")), stringValue(release.get("name")));
                    String source = "https://github.com/" + repo.owner + "/" + repo.name;
                    String label = repo.owner + "/" + repo.name + " " + version + " " + stringValue(asset.get().get("name"));
                    return Optional.of(candidateFromResolved(target, "github-release", source, repo.owner + "/" + repo.name, version, label, priority, reason));
                }
            } catch (Exception ex) {
                Log.warn("GitHub releases lookup failed for " + repo.owner + "/" + repo.name + ": " + ex.getMessage());
            }
            return Optional.empty();
        }

        private Optional<Map<String, Object>> githubJarAsset(Map<String, Object> release) {
            Object assetsObj = release.get("assets");
            if (!(assetsObj instanceof List<?> assets)) {
                return Optional.empty();
            }
            List<Map<String, Object>> jars = new ArrayList<>();
            for (Object item : assets) {
                Map<String, Object> asset = asMap(item);
                String name = lower(stringValue(asset.get("name")));
                if (!name.endsWith(".jar")) {
                    continue;
                }
                if (name.contains("sources") || name.contains("javadoc") || name.contains("-dev") || name.contains("-plain")) {
                    continue;
                }
                jars.add(asset);
            }
            return jars.isEmpty() ? Optional.empty() : Optional.of(jars.get(0));
        }

        private List<DiscoveryCandidate> discoverModrinthSources(TargetConfig target, int priority) throws Exception {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            for (String term : discoverySearchTerms(target)) {
                URI uri = URI.create("https://api.modrinth.com/v2/search?query="
                    + urlEncode(term)
                    + "&facets=" + urlEncode("[[\"project_type:plugin\"]]")
                    + "&index=relevance&limit=8");
                Object json = getJson(uri, "Modrinth search");
                Object hitsObj = asMap(json).get("hits");
                if (!(hitsObj instanceof List<?> hits)) {
                    continue;
                }
                for (Object item : hits) {
                    Map<String, Object> hit = asMap(item);
                    String slug = stringValue(hit.get("slug"));
                    String title = stringValue(hit.get("title"));
                    String projectId = stringValue(hit.get("project_id"));
                    if (slug.isBlank()) {
                        continue;
                    }
                    int match = nameMatchScore(target, title, slug, projectId);
                    if (match < 35) {
                        continue;
                    }
                    TargetConfig candidateTarget = target.copyWithSource("https://modrinth.com/plugin/" + slug + "/versions");
                    candidateTarget.project = slug;
                    if (candidateTarget.loader == null || candidateTarget.loader.isBlank()) {
                        candidateTarget.loader = target.platform;
                    }
                    try {
                        ResolvedDownload download = new ModrinthResolver(config, client).resolve(candidateTarget);
                        String latest = latestFromLabel(download.label);
                        candidates.add(candidateFromResolved(target, "modrinth", candidateTarget.source, slug, latest, title, priority, "Modrinth search match: " + title));
                    } catch (Exception ex) {
                        Log.warn("Modrinth candidate did not resolve for " + slug + ": " + ex.getMessage());
                    }
                }
            }
            return candidates;
        }

        private List<DiscoveryCandidate> discoverHangarSources(TargetConfig target, int priority) throws Exception {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            for (String term : discoverySearchTerms(target)) {
                URI uri = URI.create("https://hangar.papermc.io/api/v1/projects?limit=8&offset=0&q=" + urlEncode(term));
                Object json = getJson(uri, "Hangar search");
                Object resultObj = asMap(json).get("result");
                if (!(resultObj instanceof List<?> results)) {
                    continue;
                }
                for (Object item : results) {
                    Map<String, Object> project = asMap(item);
                    HangarProject hangarProject = hangarProjectFromSearch(project);
                    if (hangarProject == null) {
                        continue;
                    }
                    String name = firstNonBlank(stringValue(project.get("name")), hangarProject.slug);
                    int match = nameMatchScore(target, name, hangarProject.slug, hangarProject.owner + "/" + hangarProject.slug);
                    if (match < 35) {
                        continue;
                    }
                    String source = "https://hangar.papermc.io/" + hangarProject.owner + "/" + hangarProject.slug + "/versions";
                    TargetConfig candidateTarget = target.copyWithSource(source);
                    candidateTarget.project = hangarProject.owner + "/" + hangarProject.slug;
                    try {
                        ResolvedDownload download = new HangarResolver(config, client).resolve(candidateTarget);
                        String latest = latestFromLabel(download.label);
                        candidates.add(candidateFromResolved(target, "hangar", source, candidateTarget.project, latest, name, priority, "Hangar search match: " + name));
                    } catch (Exception ex) {
                        Log.warn("Hangar candidate did not resolve for " + hangarProject.owner + "/" + hangarProject.slug + ": " + ex.getMessage());
                    }
                }
            }
            return candidates;
        }

        private List<DiscoveryCandidate> discoverSpigotSources(TargetConfig target, int priority) throws Exception {
            List<DiscoveryCandidate> candidates = new ArrayList<>();
            for (String term : discoverySearchTerms(target)) {
                URI uri = URI.create("https://api.spiget.org/v2/search/resources/" + urlEncode(term) + "?field=name&size=8");
                Object json = getJson(uri, "Spiget search");
                if (!(json instanceof List<?> resources)) {
                    continue;
                }
                for (Object item : resources) {
                    Map<String, Object> resource = asMap(item);
                    String id = normalizeNumericId(stringValue(resource.get("id")));
                    String name = stringValue(resource.get("name"));
                    if (id.isBlank()) {
                        continue;
                    }
                    int match = nameMatchScore(target, name, id);
                    if (match < 35) {
                        continue;
                    }
                    String source = "https://www.spigotmc.org/resources/" + safeName(name).toLowerCase(Locale.ROOT) + "." + id + "/";
                    candidates.add(spigotCandidateFromSource(target, source, priority, "Spigot search match: " + name));
                }
            }
            return candidates;
        }

        private DiscoveryCandidate spigotCandidateFromSource(TargetConfig target, String source, int priority, String reason) throws Exception {
            String id = normalizeNumericId(new SpigetResolver().inferResourceId(source));
            String latest = "";
            String label = "Spigot resource " + id;
            if (!id.isBlank()) {
                try {
                    Object resource = getJson(URI.create("https://api.spiget.org/v2/resources/" + urlEncode(id)), "Spiget resource");
                    if (resource instanceof Map<?, ?> map) {
                        Map<String, Object> resourceMap = castStringMap(map);
                        latest = stringValue(resourceMap.get("version"));
                        label = firstNonBlank(stringValue(resourceMap.get("name")), label);
                    }
                } catch (Exception ignored) {
                    // The download URL may still work even if the metadata lookup does not.
                }
                if (latest.isBlank()) {
                    try {
                        Object version = getJson(URI.create("https://api.spiget.org/v2/resources/" + urlEncode(id) + "/versions/latest"), "Spiget latest version");
                        if (version instanceof Map<?, ?> map) {
                            latest = stringValue(castStringMap(map).get("name"));
                        }
                    } catch (Exception ignored) {
                        // Latest version is optional for scoring.
                    }
                }
            }
            return candidateFromResolved(target, "spigot", source, id, latest, label, priority, reason);
        }

        private DiscoveryCandidate candidateFromResolved(TargetConfig target, String type, String source, String projectHint, String latestVersion, String label, int priority, String reason) {
            int match = nameMatchScore(target, label, source, projectHint);
            int score = 35 + match - (priority * 3);
            String localVersion = target.detectedVersion == null ? "" : target.detectedVersion;
            String versionReason = "";
            if (!localVersion.isBlank() && !latestVersion.isBlank()) {
                if (isClearlyOlderVersion(latestVersion, localVersion)) {
                    score -= 100;
                    versionReason = "; rejected-looking version: latest " + latestVersion + " appears older than local " + localVersion;
                } else {
                    int cmp = compareVersionValues(cleanVersion(latestVersion), cleanVersion(localVersion));
                    if (cmp == 0) {
                        score += 25;
                        versionReason = "; version matches local " + localVersion;
                    } else if (cmp > 0) {
                        score += 15;
                        versionReason = "; latest " + latestVersion + " appears newer than local " + localVersion;
                    } else {
                        versionReason = "; latest " + latestVersion + " is not clearly newer than local " + localVersion;
                    }
                }
            } else if (!localVersion.isBlank()) {
                score -= 25;
                versionReason = "; no comparable latest version found while local version is " + localVersion;
            }
            String fullReason = reason + "; name match score " + match + versionReason;
            return new DiscoveryCandidate(type, source, projectHint, latestVersion, label, fullReason, score, priority);
        }

        private Object getJson(URI uri, String apiName) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException(apiName + " failed with HTTP " + status + " for " + uri);
            }
            return new JsonParser(response.body()).parse();
        }

        private GithubRepo repoFromGithubUrl(String value) {
            List<String> parts = pathParts(URI.create(value));
            if (parts.size() < 2) {
                throw new IllegalArgumentException("GitHub URL needs owner/repo: " + value);
            }
            return new GithubRepo(parts.get(0), parts.get(1).replace(".git", ""));
        }

        private GithubRepo repoFromGithubValue(String value) {
            if (value.contains("github.com/")) {
                return repoFromGithubUrl(value);
            }
            String[] parts = value.split("/", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("GitHub repo needs Owner/Repo: " + value);
            }
            return new GithubRepo(parts[0], parts[1].replace(".git", ""));
        }

        private HangarProject hangarProjectFromSearch(Map<String, Object> project) {
            Object namespaceObj = project.get("namespace");
            if (namespaceObj instanceof Map<?, ?> rawNamespace) {
                Map<String, Object> namespace = castStringMap(rawNamespace);
                String owner = firstNonBlank(stringValue(namespace.get("owner")), stringValue(namespace.get("ownerName")), stringValue(namespace.get("author")));
                String slug = firstNonBlank(stringValue(namespace.get("slug")), stringValue(namespace.get("project")), stringValue(project.get("slug")), stringValue(project.get("name")));
                if (!owner.isBlank() && !slug.isBlank()) {
                    return new HangarProject(owner, slug);
                }
            } else if (namespaceObj != null) {
                String namespace = stringValue(namespaceObj);
                if (namespace.contains("/")) {
                    String[] parts = namespace.split("/", 2);
                    return new HangarProject(parts[0], parts[1]);
                }
            }
            String owner = firstNonBlank(stringValue(project.get("owner")), stringValue(project.get("ownerName")), stringValue(project.get("author")));
            String slug = firstNonBlank(stringValue(project.get("slug")), stringValue(project.get("name")));
            if (!owner.isBlank() && !slug.isBlank()) {
                return new HangarProject(owner, slug);
            }
            return null;
        }

        private List<String> discoverySearchTerms(TargetConfig target) {
            List<String> terms = new ArrayList<>();
            addSearchTerm(terms, target.detectedPluginId);
            addSearchTerm(terms, target.name);
            addSearchTerm(terms, target.installAs == null ? "" : stripJarName(target.installAs));
            return terms;
        }

        private void addSearchTerm(List<String> terms, String value) {
            String cleaned = cleanSearchTerm(value);
            if (cleaned.isBlank()) {
                return;
            }
            if (terms.stream().noneMatch(existing -> existing.equalsIgnoreCase(cleaned))) {
                terms.add(cleaned);
            }
        }

        private String cleanSearchTerm(String value) {
            String cleaned = value == null ? "" : value;
            cleaned = cleaned.replace('\\', '/');
            int slash = cleaned.lastIndexOf('/');
            if (slash >= 0) {
                cleaned = cleaned.substring(slash + 1);
            }
            if (cleaned.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                cleaned = cleaned.substring(0, cleaned.length() - 4);
            }
            cleaned = cleaned.replaceAll("(?i)[-_ ]?(bukkit|paper|spigot|folia|velocity|plugin)$", "");
            cleaned = cleaned.replaceAll("(?i)[-_ ]?v?\\d+(\\.\\d+){0,4}.*$", "");
            return cleaned.trim();
        }

        private String stripJarName(String value) {
            return cleanSearchTerm(value);
        }

        private int nameMatchScore(TargetConfig target, String... candidateValues) {
            List<String> needles = new ArrayList<>();
            addNormalizedName(needles, target.detectedPluginId);
            addNormalizedName(needles, target.name);
            addNormalizedName(needles, stripJarName(target.installAs));
            int best = 0;
            for (String needle : needles) {
                if (needle.length() < 3) {
                    continue;
                }
                for (String value : candidateValues) {
                    String haystack = normalizeName(value);
                    if (haystack.isBlank()) {
                        continue;
                    }
                    if (haystack.equals(needle)) {
                        best = Math.max(best, 60);
                    } else if (haystack.contains(needle) || needle.contains(haystack)) {
                        best = Math.max(best, Math.min(45, 20 + Math.min(needle.length(), haystack.length())));
                    }
                }
            }
            return best;
        }

        private void addNormalizedName(List<String> names, String value) {
            String normalized = normalizeName(cleanSearchTerm(value));
            if (!normalized.isBlank() && names.stream().noneMatch(existing -> existing.equals(normalized))) {
                names.add(normalized);
            }
        }

        private String normalizeName(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
        }

        private String normalizeNumericId(String value) {
            if (value == null) {
                return "";
            }
            String trimmed = value.trim();
            if (trimmed.matches("\\d+\\.0+")) {
                return trimmed.substring(0, trimmed.indexOf('.'));
            }
            return trimmed;
        }

        private String latestFromLabel(String label) {
            if (label == null || label.isBlank()) {
                return "";
            }
            List<String> tokens = List.of(label.split("\\s+"));
            for (String token : tokens) {
                String cleaned = cleanVersion(token);
                if (!cleaned.isBlank() && cleaned.matches(".*\\d.*")) {
                    return cleaned;
                }
            }
            return "";
        }

        private boolean isClearlyOlderVersion(String candidate, String local) {
            String cleanedCandidate = cleanVersion(candidate);
            String cleanedLocal = cleanVersion(local);
            if (cleanedCandidate.isBlank() || cleanedLocal.isBlank()) {
                return false;
            }
            if (!cleanedCandidate.matches(".*\\d.*") || !cleanedLocal.matches(".*\\d.*")) {
                return false;
            }
            return compareVersionValues(cleanedCandidate, cleanedLocal) < 0;
        }

        List<InstalledUpdate> updateAll() throws Exception {
            List<InstalledUpdate> installed = new ArrayList<>();
            Files.createDirectories(config.resolve(config.cacheDir));
            Files.createDirectories(config.resolve(config.backupDir));
            autoSwitchMissingPluginSources();
            for (TargetConfig target : allTargets()) {
                if (!target.enabled) {
                    Log.info("Skipping disabled target: " + target.displayName());
                    continue;
                }
                if (target.source == null || target.source.isBlank() || isAutoValue(target.source)) {
                    Log.info("No source configured for " + target.displayName() + "; keeping existing jar.");
                    continue;
                }
                try {
                    Optional<InstalledUpdate> update = updateOne(target);
                    update.ifPresent(installed::add);
                } catch (Exception ex) {
                    Path targetPath = config.resolve(Paths.get(target.installAs));
                    boolean mayContinue = config.onFailure.equals("keep-current") && Files.exists(targetPath);
                    if (!target.required && config.onFailure.equals("keep-current")) {
                        mayContinue = true;
                    }
                    if (mayContinue) {
                        Log.warn("Update failed for " + target.displayName() + ": " + ex.getMessage());
                        Log.warn("Keeping current jar for " + target.displayName() + ".");
                    } else {
                        throw ex;
                    }
                }
            }
            return installed;
        }

        private List<TargetConfig> allTargets() {
            List<TargetConfig> targets = new ArrayList<>();
            targets.add(config.server);
            targets.addAll(config.plugins);
            return targets;
        }

        private Optional<InstalledUpdate> updateOne(TargetConfig target) throws Exception {
            if (target.source == null || target.source.isBlank() || isAutoValue(target.source)) {
                Log.info("No source configured for " + target.displayName() + "; keeping existing jar.");
                return Optional.empty();
            }
            List<String> sources = new ArrayList<>();
            sources.add(target.source);
            sources.addAll(target.fallbackSources);
            Exception last = null;
            for (int i = 0; i < sources.size(); i++) {
                TargetConfig candidate = i == 0 ? target : target.copyWithSource(sources.get(i));
                try {
                    return updateOneFromSource(candidate);
                } catch (Exception ex) {
                    last = ex;
                    if (i + 1 < sources.size()) {
                        Log.warn("Source failed for " + target.displayName() + ": " + ex.getMessage());
                        Log.warn("Trying fallback source " + (i + 2) + " of " + sources.size() + ".");
                    }
                }
            }
            throw last == null ? new IOException("No source configured for " + target.displayName()) : last;
        }

        private Optional<InstalledUpdate> updateOneFromSource(TargetConfig target) throws Exception {
            SourcePlan plan = resolveSource(target);
            Log.info("Checking " + target.displayName() + " (" + plan.type + ")");
            ResolvedDownload download = plan.resolver.resolve(target);
            Path targetPath = config.resolve(Paths.get(target.installAs));
            Path stagingDir = config.resolve(config.cacheDir).resolve("staging");
            Files.createDirectories(stagingDir);
            Path staging = stagingDir.resolve(safeName(target.displayName()) + "-" + System.currentTimeMillis() + ".jar");

            download(download.uri, staging);
            validateJar(staging);

            String newHash = sha256(staging);
            if (Files.exists(targetPath)) {
                String oldHash = sha256(targetPath);
                if (oldHash.equalsIgnoreCase(newHash)) {
                    Files.deleteIfExists(staging);
                    Log.info(target.displayName() + " is already current (" + shortHash(newHash) + ").");
                    updateLockIfNeeded(target, download);
                    return Optional.empty();
                }
            }

            Path backupPath = Files.exists(targetPath) ? backup(targetPath) : null;
            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            moveReplace(staging, targetPath);
            Log.info("Installed " + target.displayName() + " -> " + targetPath + " (" + shortHash(newHash) + ")");
            updateLockIfNeeded(target, download);
            return Optional.of(new InstalledUpdate(target, targetPath, backupPath));
        }

        private boolean isTrustedGithubRepo(String repo) {
            String normalized = repo.trim();
            if (config.buildFromSource.trustedGithubRepos.stream().anyMatch(r -> r.equalsIgnoreCase(normalized))) {
                return true;
            }
            int slash = normalized.indexOf('/');
            if (slash <= 0) {
                return false;
            }
            String org = normalized.substring(0, slash);
            return config.buildFromSource.trustedGithubOrgs.stream().anyMatch(o -> o.equalsIgnoreCase(org));
        }

        private void updateLockIfNeeded(TargetConfig target, ResolvedDownload download) {
            if (!target.server || !"papermc".equals(download.sourceType) || download.gameVersion == null || download.gameVersion.isBlank()) {
                return;
            }
            try {
                Path lock = config.resolve(Paths.get("updater.lock.yml"));
                List<String> lines = List.of(
                    "# Auto-generated by " + APP_NAME + ".",
                    "# Keep this file if changeVersion is false and gameVersion is not set in updater.yml.",
                    "serverProject: " + download.project,
                    "serverGameVersion: \"" + download.gameVersion + "\"",
                    "serverBuild: \"" + download.build + "\""
                );
                Files.write(lock, lines, StandardCharsets.UTF_8);
                Log.info("Updated version lock -> " + lock.getFileName() + " (" + download.project + " " + download.gameVersion + ")");
            } catch (IOException ex) {
                Log.warn("Could not write updater.lock.yml: " + ex.getMessage());
            }
        }

        private SourcePlan resolveSource(TargetConfig target) {
            String type = target.type == null || target.type.isBlank() ? "auto" : lower(target.type);
            String source = target.source == null ? "" : target.source.trim();
            if (type.equals("auto")) {
                type = detectType(source, target);
            }
            if (config.mode.equals("hosted-safe") && (type.equals("git") || type.equals("github-source"))) {
                throw new IllegalArgumentException("Hosted-safe mode will not build from source for " + target.displayName());
            }
            switch (type) {
                case "papermc":
                    return new SourcePlan(type, source.isBlank() ? "PaperMC downloads API" : source, new PaperMcResolver(config, client));
                case "geysermc":
                    return new SourcePlan(type, source.isBlank() ? "GeyserMC downloads API" : source, new GeyserMcResolver(config));
                case "hangar":
                    return new SourcePlan(type, source.isBlank() ? "Hangar API" : source, new HangarResolver(config, client));
                case "github-release":
                case "github":
                    return new SourcePlan("github-release", source.isBlank() ? target.githubRepo : source, new GithubReleaseResolver(config, client));
                case "modrinth":
                    return new SourcePlan(type, source.isBlank() ? "Modrinth API" : source, new ModrinthResolver(config, client));
                case "spigot":
                case "spiget":
                    return new SourcePlan(type, source, new SpigetResolver());
                case "direct":
                    return new SourcePlan(type, source, new DirectResolver(config));
                default:
                    throw new IllegalArgumentException("Unsupported source type for " + target.displayName() + ": " + type);
            }
        }

        private String detectType(String source, TargetConfig target) {
            String lowerSource = lower(source);
            if (lowerSource.contains("papermc.io/downloads") || lowerSource.contains("papermc.io/software")
                || lowerSource.contains("fill.papermc.io") || lowerSource.contains("api.papermc.io")) {
                return "papermc";
            }
            if (lowerSource.contains("geysermc.org/download") && !lowerSource.contains("download.geysermc.org")) {
                return "geysermc";
            }
            if (lowerSource.contains("modrinth.com/") || lowerSource.contains("api.modrinth.com/")) {
                return "modrinth";
            }
            if (lowerSource.contains("hangar.papermc.io/")) {
                return "hangar";
            }
            if (lowerSource.contains("spigotmc.org/resources") || lowerSource.contains("api.spiget.org/")) {
                return "spigot";
            }
            if (lowerSource.contains("github.com/")
                && (lowerSource.contains("/releases/download/") || lowerSource.endsWith(".jar"))) {
                return "direct";
            }
            if (lowerSource.endsWith(".git") || lowerSource.startsWith("git@")) {
                return "git";
            }
            if (lowerSource.contains("github.com/") || (target.githubRepo != null && !target.githubRepo.isBlank())) {
                return "github-release";
            }
            if (lowerSource.contains("download.geysermc.org/v2/")) {
                return "direct";
            }
            if (lowerSource.startsWith("http://") || lowerSource.startsWith("https://")) {
                return "direct";
            }
            if (target.server && (target.project != null || lower(target.displayName()).contains("velocity"))) {
                return "papermc";
            }
            return "direct";
        }

        private void download(URI uri, Path destination) throws Exception {
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path source = Paths.get(uri);
                Log.info("Copying " + source);
                Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            Log.info("Downloading " + uri);
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Download failed with HTTP " + status + " from " + uri);
            }
            try (InputStream in = response.body()) {
                Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        private Path backup(Path targetPath) throws IOException {
            Path backups = config.resolve(config.backupDir);
            Files.createDirectories(backups);
            String filename = targetPath.getFileName().toString();
            String stamp = LocalDateTime.now().format(BACKUP_TIME);
            Path backup = backups.resolve(filename + "." + stamp + ".bak");
            Files.copy(targetPath, backup, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Backed up " + targetPath.getFileName() + " -> " + backup);
            return backup;
        }

        private static void validateJar(Path path) throws IOException {
            try (JarFile ignored = new JarFile(path.toFile(), false)) {
                // Opening the JarFile is enough to verify that this is a readable jar/zip.
            }
        }

        private static void moveReplace(Path from, Path to) throws IOException {
            try {
                Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private interface DownloadResolver {
        ResolvedDownload resolve(TargetConfig target) throws Exception;
    }

    private static final class SourcePlan {
        final String type;
        final String description;
        final DownloadResolver resolver;

        SourcePlan(String type, String description, DownloadResolver resolver) {
            this.type = type;
            this.description = description;
            this.resolver = resolver;
        }
    }

    private static final class ResolvedDownload {
        final URI uri;
        final String label;
        final String sourceType;
        final String project;
        final String gameVersion;
        final String build;

        ResolvedDownload(URI uri, String label) {
            this(uri, label, "", "", "", "");
        }

        ResolvedDownload(URI uri, String label, String sourceType, String project, String gameVersion, String build) {
            this.uri = uri;
            this.label = label;
            this.sourceType = sourceType;
            this.project = project;
            this.gameVersion = gameVersion;
            this.build = build;
        }
    }

    private static final class DirectResolver implements DownloadResolver {
        private final AppConfig config;

        DirectResolver(AppConfig config) {
            this.config = config;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) {
            if (target.source == null || target.source.isBlank()) {
                throw new IllegalArgumentException("Direct source needs a URL for " + target.displayName());
            }
            return new ResolvedDownload(sourceUri(target.source, config), target.source);
        }
    }

    private static final class GithubReleaseResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        GithubReleaseResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            GithubRepo repo = inferRepo(target);
            List<Map<String, Object>> releases = loadReleases(repo);
            for (Map<String, Object> release : releases) {
                if (Boolean.TRUE.equals(release.get("draft"))) {
                    continue;
                }
                if (target.versionType == null || target.versionType.isBlank()) {
                    if (Boolean.TRUE.equals(release.get("prerelease"))) {
                        continue;
                    }
                }
                Optional<Map<String, Object>> asset = findJarAsset(release);
                if (asset.isEmpty()) {
                    continue;
                }
                String url = stringValue(asset.get().get("browser_download_url"));
                if (url.isBlank()) {
                    continue;
                }
                String tag = firstNonBlank(stringValue(release.get("tag_name")), stringValue(release.get("name")), "latest");
                String filename = stringValue(asset.get().get("name"));
                return new ResolvedDownload(URI.create(url), "GitHub release " + repo.owner + "/" + repo.name + " " + tag + " " + filename);
            }
            throw new IOException("No GitHub release jar found for " + repo.owner + "/" + repo.name);
        }

        private List<Map<String, Object>> loadReleases(GithubRepo repo) throws Exception {
            URI uri = URI.create("https://api.github.com/repos/" + urlEncode(repo.owner) + "/" + urlEncode(repo.name) + "/releases?per_page=30");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("GitHub releases API failed with HTTP " + status + " for " + repo.owner + "/" + repo.name);
            }
            Object json = new JsonParser(response.body()).parse();
            if (!(json instanceof List<?> list)) {
                throw new IOException("GitHub releases API returned an unexpected response for " + repo.owner + "/" + repo.name);
            }
            List<Map<String, Object>> releases = new ArrayList<>();
            for (Object item : list) {
                releases.add(asMap(item));
            }
            return releases;
        }

        private Optional<Map<String, Object>> findJarAsset(Map<String, Object> release) {
            Object assetsObj = release.get("assets");
            if (!(assetsObj instanceof List<?> assets)) {
                return Optional.empty();
            }
            List<Map<String, Object>> jars = new ArrayList<>();
            for (Object item : assets) {
                Map<String, Object> asset = asMap(item);
                String name = lower(stringValue(asset.get("name")));
                if (!name.endsWith(".jar")) {
                    continue;
                }
                if (name.contains("sources") || name.contains("javadoc") || name.contains("-dev") || name.contains("-plain")) {
                    continue;
                }
                jars.add(asset);
            }
            return jars.isEmpty() ? Optional.empty() : Optional.of(jars.get(0));
        }

        private GithubRepo inferRepo(TargetConfig target) {
            String value = firstNonBlank(target.githubRepo, target.project, target.source, "");
            if (value.contains("github.com/")) {
                List<String> parts = pathParts(URI.create(value));
                if (parts.size() >= 2) {
                    return new GithubRepo(parts.get(0), parts.get(1).replace(".git", ""));
                }
            }
            if (value.contains("/")) {
                String[] parts = value.split("/", 2);
                return new GithubRepo(parts[0], parts[1].replace(".git", ""));
            }
            throw new IllegalArgumentException("GitHub release source needs a repo like Owner/Repo or https://github.com/Owner/Repo");
        }
    }

    private static final class GithubRepo {
        final String owner;
        final String name;

        GithubRepo(String owner, String name) {
            this.owner = owner;
            this.name = name;
        }
    }

    private static final class SpigetResolver implements DownloadResolver {
        @Override
        public ResolvedDownload resolve(TargetConfig target) {
            String resourceId = firstNonBlank(target.project, inferResourceId(target.source), "");
            if (resourceId.isBlank()) {
                throw new IllegalArgumentException("Spigot/Spiget source needs a Spigot resource URL or resource ID");
            }
            String url = "https://api.spiget.org/v2/resources/" + urlEncode(resourceId) + "/download";
            return new ResolvedDownload(URI.create(url), "Spiget resource " + resourceId);
        }

        private String inferResourceId(String source) {
            if (source == null || source.isBlank()) {
                return "";
            }
            if (source.matches("\\d+")) {
                return source;
            }
            if (source.contains("api.spiget.org/")) {
                List<String> parts = pathParts(URI.create(source));
                for (int i = 0; i < parts.size() - 1; i++) {
                    if (parts.get(i).equals("resources")) {
                        return parts.get(i + 1);
                    }
                }
            }
            if (source.contains("spigotmc.org/resources")) {
                List<String> parts = pathParts(URI.create(source));
                for (String part : parts) {
                    int dot = part.lastIndexOf('.');
                    if (dot >= 0 && dot + 1 < part.length()) {
                        String candidate = part.substring(dot + 1);
                        if (candidate.matches("\\d+")) {
                            return candidate;
                        }
                    }
                }
            }
            return "";
        }
    }

    private static final class GeyserMcResolver implements DownloadResolver {
        private final AppConfig config;

        GeyserMcResolver(AppConfig config) {
            this.config = config;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) {
            if (target.source != null && target.source.contains("download.geysermc.org/v2/")) {
                return new ResolvedDownload(URI.create(target.source), target.source);
            }
            String project = firstNonBlank(target.project, inferGeyserProject(target), "geyser");
            String platform = firstNonBlank(target.platform, inferPlatform(target), "velocity");
            String url = "https://download.geysermc.org/v2/projects/" + project
                + "/versions/latest/builds/latest/downloads/" + platform;
            return new ResolvedDownload(URI.create(url), "GeyserMC " + project + " " + platform);
        }

        private String inferGeyserProject(TargetConfig target) {
            String text = lower(firstNonBlank(target.name, target.installAs, target.source, ""));
            if (text.contains("floodgate")) {
                return "floodgate";
            }
            return "geyser";
        }

        private String inferPlatform(TargetConfig target) {
            String text = lower(firstNonBlank(target.name, target.installAs, target.source, ""));
            if (text.contains("spigot") || text.contains("paper")) {
                return "spigot";
            }
            if (text.contains("bungee")) {
                return "bungeecord";
            }
            if (text.contains("fabric")) {
                return "fabric";
            }
            if (text.contains("standalone")) {
                return "standalone";
            }
            return "velocity";
        }
    }

    private static final class HangarResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        HangarResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            HangarProject project = inferProject(target);
            String platform = firstNonBlank(target.platform, target.loader, "paper").toUpperCase(Locale.ROOT);
            String channel = firstNonBlank(target.channel, target.versionType, "Release");
            List<Map<String, Object>> versions = loadVersions(project);
            Optional<ResolvedDownload> preferred = findDownload(project, versions, platform, channel);
            if (preferred.isPresent()) {
                return preferred.get();
            }
            if (target.channel == null && target.versionType == null) {
                Optional<ResolvedDownload> any = findDownload(project, versions, platform, "");
                if (any.isPresent()) {
                    return any.get();
                }
            }
            throw new IOException("No Hangar download found for " + project.owner + "/" + project.slug + " on platform " + platform);
        }

        private List<Map<String, Object>> loadVersions(HangarProject project) throws Exception {
            URI uri = URI.create("https://hangar.papermc.io/api/v1/projects/"
                + urlEncode(project.owner) + "/" + urlEncode(project.slug) + "/versions?limit=100&offset=0");
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Hangar API failed with HTTP " + status + " for " + project.owner + "/" + project.slug);
            }
            Object json = new JsonParser(response.body()).parse();
            Object result = asMap(json).get("result");
            if (!(result instanceof List<?> list)) {
                throw new IOException("Hangar API returned an unexpected response for " + project.owner + "/" + project.slug);
            }
            List<Map<String, Object>> versions = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> version = asMap(item);
                if ("public".equalsIgnoreCase(stringValue(version.get("visibility")))) {
                    versions.add(version);
                }
            }
            versions.sort((a, b) -> stringValue(b.get("createdAt")).compareTo(stringValue(a.get("createdAt"))));
            return versions;
        }

        private Optional<ResolvedDownload> findDownload(HangarProject project, List<Map<String, Object>> versions, String platform, String channel) {
            for (Map<String, Object> version : versions) {
                if (!channel.isBlank()) {
                    Object channelObj = version.get("channel");
                    String channelName = channelObj instanceof Map<?, ?> map ? stringValue(castStringMap(map).get("name")) : "";
                    if (!channel.equalsIgnoreCase(channelName)) {
                        continue;
                    }
                }
                Object downloadsObj = version.get("downloads");
                if (!(downloadsObj instanceof Map<?, ?> rawDownloads)) {
                    continue;
                }
                Map<String, Object> downloads = castStringMap(rawDownloads);
                Object downloadObj = downloads.get(platform);
                if (!(downloadObj instanceof Map<?, ?>)) {
                    downloadObj = downloads.get(platform.toUpperCase(Locale.ROOT));
                }
                if (!(downloadObj instanceof Map<?, ?> rawDownload)) {
                    continue;
                }
                Map<String, Object> download = castStringMap(rawDownload);
                String externalUrl = stringValue(download.get("externalUrl"));
                String downloadUrl = stringValue(download.get("downloadUrl"));
                String url = firstNonBlank(downloadUrl, externalUrl);
                if (url.isBlank()) {
                    continue;
                }
                String name = stringValue(version.get("name"));
                return Optional.of(new ResolvedDownload(URI.create(url), "Hangar " + project.owner + "/" + project.slug + " " + name + " " + platform));
            }
            return Optional.empty();
        }

        private HangarProject inferProject(TargetConfig target) {
            if (target.project != null && target.project.contains("/")) {
                String[] parts = target.project.split("/", 2);
                return new HangarProject(parts[0], parts[1]);
            }
            String source = firstNonBlank(target.source, "");
            if (source.contains("hangar.papermc.io/")) {
                URI uri = URI.create(source);
                List<String> parts = pathParts(uri);
                if (parts.size() >= 2) {
                    return new HangarProject(parts.get(0), parts.get(1));
                }
            }
            throw new IllegalArgumentException("Hangar source needs a URL like https://hangar.papermc.io/Owner/Project/versions");
        }
    }

    private static final class HangarProject {
        final String owner;
        final String slug;

        HangarProject(String owner, String slug) {
            this.owner = owner;
            this.slug = slug;
        }
    }

    private static final class ModrinthResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        ModrinthResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            String project = firstNonBlank(target.project, inferProject(target), "");
            if (project.isBlank()) {
                throw new IllegalArgumentException("Could not infer Modrinth project slug for " + target.displayName());
            }

            List<Map<String, Object>> versions = loadVersions(project, target);
            if (versions.isEmpty()) {
                throw new IOException("Modrinth returned no versions for project " + project + filterDescription(target));
            }

            Optional<ResolvedDownload> release = findDownload(project, versions, "release");
            if (target.versionType != null && !target.versionType.isBlank()) {
                release = findDownload(project, versions, lower(target.versionType));
            }
            if (release.isPresent()) {
                return release.get();
            }

            Optional<ResolvedDownload> beta = findDownload(project, versions, "beta");
            if (beta.isPresent()) {
                return beta.get();
            }

            Optional<ResolvedDownload> alpha = findDownload(project, versions, "alpha");
            if (alpha.isPresent()) {
                return alpha.get();
            }

            Optional<ResolvedDownload> any = findDownload(project, versions, "");
            if (any.isPresent()) {
                return any.get();
            }
            throw new IOException("No downloadable jar file found on Modrinth for project " + project + filterDescription(target));
        }

        private List<Map<String, Object>> loadVersions(String project, TargetConfig target) throws Exception {
            StringBuilder url = new StringBuilder("https://api.modrinth.com/v3/project/")
                .append(urlEncode(project))
                .append("/version?include_changelog=false");
            if (target.loader != null && !target.loader.isBlank()) {
                url.append("&loaders=").append(urlEncode(jsonArray(target.loader)));
            }
            if (target.gameVersion != null && !target.gameVersion.isBlank()) {
                url.append("&game_versions=").append(urlEncode(jsonArray(target.gameVersion)));
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Modrinth API failed with HTTP " + status + " for project " + project);
            }

            Object json = new JsonParser(response.body()).parse();
            if (!(json instanceof List<?> list)) {
                throw new IOException("Modrinth API returned an unexpected response for project " + project);
            }

            List<Map<String, Object>> versions = new ArrayList<>();
            for (Object item : list) {
                Map<String, Object> version = asMap(item);
                if (!"listed".equalsIgnoreCase(stringValue(version.get("status")))) {
                    continue;
                }
                versions.add(version);
            }
            versions.sort((a, b) -> stringValue(b.get("date_published")).compareTo(stringValue(a.get("date_published"))));
            return versions;
        }

        private Optional<ResolvedDownload> findDownload(String project, List<Map<String, Object>> versions, String versionType) {
            for (Map<String, Object> version : versions) {
                if (!versionType.isBlank() && !versionType.equalsIgnoreCase(stringValue(version.get("version_type")))) {
                    continue;
                }
                Optional<Map<String, Object>> file = findFile(version);
                if (file.isEmpty()) {
                    continue;
                }
                String url = stringValue(file.get().get("url"));
                if (url.isBlank()) {
                    continue;
                }
                String versionNumber = stringValue(version.get("version_number"));
                String filename = stringValue(file.get().get("filename"));
                return Optional.of(new ResolvedDownload(URI.create(url), "Modrinth " + project + " " + versionNumber + " " + filename));
            }
            return Optional.empty();
        }

        private Optional<Map<String, Object>> findFile(Map<String, Object> version) {
            Object filesObj = version.get("files");
            if (!(filesObj instanceof List<?> files) || files.isEmpty()) {
                return Optional.empty();
            }

            List<Map<String, Object>> jars = new ArrayList<>();
            for (Object item : files) {
                Map<String, Object> file = asMap(item);
                String filename = lower(stringValue(file.get("filename")));
                String fileType = lower(stringValue(file.get("file_type")));
                if (!filename.endsWith(".jar")) {
                    continue;
                }
                if (fileType.equals("sources-jar") || fileType.equals("dev-jar") || fileType.equals("javadoc-jar")) {
                    continue;
                }
                jars.add(file);
            }

            for (Map<String, Object> jar : jars) {
                if (Boolean.TRUE.equals(jar.get("primary"))) {
                    return Optional.of(jar);
                }
            }
            return jars.isEmpty() ? Optional.empty() : Optional.of(jars.get(0));
        }

        private String inferProject(TargetConfig target) {
            String source = firstNonBlank(target.source, target.name, "");
            if (source.contains("modrinth.com/")) {
                URI uri = URI.create(source);
                String[] parts = uri.getPath().split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].equals("plugin") || parts[i].equals("mod") || parts[i].equals("datapack")) {
                        return parts[i + 1];
                    }
                    if (parts[i].equals("project")) {
                        return parts[i + 1];
                    }
                }
            }
            if (source.contains("api.modrinth.com/")) {
                URI uri = URI.create(source);
                String[] parts = uri.getPath().split("/");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].equals("project")) {
                        return parts[i + 1];
                    }
                }
            }
            return source;
        }

        private String filterDescription(TargetConfig target) {
            List<String> filters = new ArrayList<>();
            if (target.loader != null && !target.loader.isBlank()) {
                filters.add("loader=" + target.loader);
            }
            if (target.gameVersion != null && !target.gameVersion.isBlank()) {
                filters.add("gameVersion=" + target.gameVersion);
            }
            return filters.isEmpty() ? "" : " (" + String.join(", ", filters) + ")";
        }
    }

    private static final class PaperMcResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        PaperMcResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            String project = firstNonBlank(target.project, inferPaperMcProject(target), "paper");
            boolean allowVersionChange = target.changeVersion != null && target.changeVersion;
            String configuredVersion = lower(target.gameVersion).equals("auto") ? "" : firstNonBlank(target.gameVersion, "");
            String lockVersion = readLockValue(config, "serverGameVersion");
            String lockProject = readLockValue(config, "serverProject");
            if (!lockProject.isBlank() && !lockProject.equalsIgnoreCase(project)) {
                lockVersion = "";
            }
            String lockedVersion = allowVersionChange ? "" : firstNonBlank(configuredVersion, lockVersion, "");
            if (!allowVersionChange && lockedVersion.isBlank()) {
                Log.info("changeVersion is false and no server version is locked yet. Resolving latest " + project + " once, then writing updater.lock.yml.");
            }
            if (!lockedVersion.isBlank()) {
                Log.info("Using locked/configured " + project + " version: " + lockedVersion);
                Optional<ResolvedDownload> download = loadBuild(project, lockedVersion, target);
                if (download.isPresent()) {
                    return download.get();
                }
                throw new IOException("No downloadable PaperMC build found for " + project + " version " + lockedVersion);
            }

            List<String> versions = loadVersions(project);
            if (versions.isEmpty()) {
                throw new IOException("PaperMC returned no versions for project " + project);
            }
            versions.sort(VelocityAutoUpdater::compareVersionsNewestFirst);
            int attempts = Math.min(versions.size(), 15);
            for (int i = 0; i < attempts; i++) {
                String version = versions.get(i);
                Optional<ResolvedDownload> resolved = loadBuild(project, version, target);
                if (resolved.isPresent()) {
                    return resolved.get();
                }
            }
            throw new IOException("No downloadable PaperMC build found for " + project);
        }

        private String inferProject(TargetConfig target) {
            return inferPaperMcProject(target);
        }

        private List<String> loadVersions(String project) throws Exception {
            URI uri = URI.create("https://fill.papermc.io/v3/projects/" + project);
            Object json = getJson(uri);
            Object versions = asMap(json).get("versions");
            List<String> result = new ArrayList<>();
            if (versions instanceof Map<?, ?> map) {
                for (Object value : map.values()) {
                    if (value instanceof List<?> list) {
                        for (Object item : list) {
                            if (item != null) {
                                result.add(String.valueOf(item));
                            }
                        }
                    } else if (value != null) {
                        result.add(String.valueOf(value));
                    }
                }
            } else if (versions instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null) {
                        result.add(String.valueOf(item));
                    }
                }
            }
            return result;
        }

        private Optional<ResolvedDownload> loadBuild(String project, String version, TargetConfig target) throws Exception {
            URI uri = URI.create("https://fill.papermc.io/v3/projects/" + project + "/versions/" + version + "/builds");
            Object json = getJson(uri);
            if (!(json instanceof List<?> builds) || builds.isEmpty()) {
                return Optional.empty();
            }

            List<Map<String, Object>> maps = new ArrayList<>();
            for (Object build : builds) {
                maps.add(asMap(build));
            }

            List<String> channels = preferredChannels(target);
            for (String channel : channels) {
                for (Map<String, Object> build : maps) {
                    String buildChannel = stringValue(build.get("channel"));
                    if (channel.equalsIgnoreCase(buildChannel)) {
                        Optional<ResolvedDownload> download = downloadFromBuild(project, version, build);
                        if (download.isPresent()) {
                            return download;
                        }
                    }
                }
            }

            for (Map<String, Object> build : maps) {
                Optional<ResolvedDownload> download = downloadFromBuild(project, version, build);
                if (download.isPresent()) {
                    return download;
                }
            }
            return Optional.empty();
        }

        private List<String> preferredChannels(TargetConfig target) {
            if (target.channel != null && !target.channel.isBlank()) {
                return List.of(target.channel);
            }
            return List.of("RECOMMENDED", "STABLE", "DEFAULT", "BETA", "ALPHA", "EXPERIMENTAL");
        }

        private Optional<ResolvedDownload> downloadFromBuild(String project, String version, Map<String, Object> build) {
            Object downloadsObj = build.get("downloads");
            if (!(downloadsObj instanceof Map<?, ?> rawDownloads)) {
                return Optional.empty();
            }
            Map<String, Object> downloads = castStringMap(rawDownloads);
            Object preferred = downloads.get("server:default");
            if (preferred instanceof Map<?, ?> map) {
                String url = stringValue(castStringMap(map).get("url"));
                if (!url.isBlank()) {
                    return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build), "papermc", project, version, buildNumber(build)));
                }
            }
            for (Map.Entry<String, Object> entry : downloads.entrySet()) {
                if (!entry.getKey().toLowerCase(Locale.ROOT).contains("server")) {
                    continue;
                }
                if (entry.getValue() instanceof Map<?, ?> map) {
                    String url = stringValue(castStringMap(map).get("url"));
                    if (!url.isBlank()) {
                        return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build), "papermc", project, version, buildNumber(build)));
                    }
                }
            }
            for (Object value : downloads.values()) {
                if (value instanceof Map<?, ?> map) {
                    String url = stringValue(castStringMap(map).get("url"));
                    if (!url.isBlank()) {
                        return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build), "papermc", project, version, buildNumber(build)));
                    }
                }
            }
            return Optional.empty();
        }

        private String label(String project, String version, Map<String, Object> build) {
            String number = buildNumber(build);
            String channel = stringValue(build.get("channel"));
            return project + " " + version + " build " + number + (channel.isBlank() ? "" : " (" + channel + ")");
        }

        private String buildNumber(Map<String, Object> build) {
            return firstNonBlank(stringValue(build.get("number")), stringValue(build.get("id")), "?");
        }

        private Object getJson(URI uri) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(45))
                .header("User-Agent", config.userAgent)
                .GET()
                .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IOException("PaperMC API failed with HTTP " + status + " for " + uri);
            }
            return new JsonParser(response.body()).parse();
        }
    }

    private static final class ServerRunner {
        private final AppConfig config;
        private final Updater updater;

        ServerRunner(AppConfig config, Updater updater) {
            this.config = config;
            this.updater = updater;
        }

        int runServerLoop(List<InstalledUpdate> startupUpdates) throws Exception {
            Path serverJar = config.resolve(Paths.get(config.server.installAs));
            if (!Files.exists(serverJar)) {
                throw new IOException("Server jar does not exist: " + serverJar);
            }

            List<InstalledUpdate> pendingStartupUpdates = new ArrayList<>(startupUpdates);
            while (true) {
                Process process = startServer(serverJar);
                StartupHealthMonitor startupHealth = new StartupHealthMonitor(pendingStartupUpdates);
                Thread outputThread = pipeOutput(process, startupHealth);
                Thread inputThread = pipeInput(process);

                StartupResult startupResult = monitorStartupHealth(process, startupHealth);
                if (startupResult.rollbackAndRestart) {
                    outputThread.join(TimeUnit.SECONDS.toMillis(5));
                    inputThread.interrupt();
                    rollbackUpdates(startupResult.failedUpdates);
                    pendingStartupUpdates = Collections.emptyList();
                    Log.warn("Restarting once with the previous known-good jar(s).");
                    continue;
                }
                if (startupResult.processExited) {
                    outputThread.join(TimeUnit.SECONDS.toMillis(5));
                    inputThread.interrupt();
                    Log.info("Server exited with code " + startupResult.exitCode + ".");
                    return startupResult.exitCode;
                }

                RunResult result = config.restart.enabled
                    ? monitorWithRestart(process)
                    : waitForExit(process);

                outputThread.join(TimeUnit.SECONDS.toMillis(5));
                inputThread.interrupt();

                if (!result.scheduledRestart) {
                    Log.info("Server exited with code " + result.exitCode + ".");
                    return result.exitCode;
                }

                Log.info("Scheduled restart completed. Updating before next start.");
                pendingStartupUpdates = updater.updateAll();
            }
        }

        private Process startServer(Path serverJar) throws IOException {
            List<String> command = new ArrayList<>();
            command.add(config.server.java == null || config.server.java.isBlank() ? "java" : config.server.java);
            command.addAll(splitCommand(config.server.javaArgs));
            command.add("-jar");
            command.add(serverJar.getFileName().toString());
            command.addAll(splitCommand(config.server.args));

            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(serverJar.getParent() == null ? config.baseDir.toFile() : serverJar.getParent().toFile());
            builder.redirectErrorStream(true);
            Log.info("Starting server: " + String.join(" ", command));
            return builder.start();
        }

        private Thread pipeOutput(Process process, StartupHealthMonitor startupHealth) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println(line);
                        startupHealth.observe(line);
                    }
                } catch (IOException ignored) {
                    // Process streams close during normal shutdown.
                }
            }, "velocity-output");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private Thread pipeInput(Process process) {
            Thread thread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                     PrintWriter writer = new PrintWriter(process.getOutputStream(), true, StandardCharsets.UTF_8)) {
                    String line;
                    while (!Thread.currentThread().isInterrupted() && (line = reader.readLine()) != null) {
                        writer.println(line);
                    }
                } catch (IOException ignored) {
                    // Console input may close when the host stops the process.
                }
            }, "velocity-input");
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private RunResult waitForExit(Process process) throws InterruptedException {
            int exitCode = process.waitFor();
            return new RunResult(false, exitCode);
        }

        private StartupResult monitorStartupHealth(Process process, StartupHealthMonitor startupHealth) throws Exception {
            if (!startupHealth.enabled()) {
                return StartupResult.continueRunning();
            }
            Instant deadline = Instant.now().plusSeconds(120);
            while (process.isAlive() && Instant.now().isBefore(deadline)) {
                if (startupHealth.hasFailures()) {
                    Log.warn("Detected plugin load failure after update; stopping server to roll back.");
                    stopProcess(process);
                    return StartupResult.rollback(startupHealth.failedUpdates());
                }
                process.waitFor(1, TimeUnit.SECONDS);
            }
            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                if (startupHealth.hasFailures()) {
                    Log.warn("Detected plugin load failure during startup; rolling back recent updated plugin jar(s).");
                    return StartupResult.rollback(startupHealth.failedUpdates());
                }
                if (exitCode != 0 && startupHealth.hasUpdatedJars()) {
                    Log.warn("Server exited during startup after updates; rolling back recent updated jar(s).");
                    return StartupResult.rollback(startupHealth.allUpdatedJars());
                }
                return StartupResult.exited(exitCode);
            }
            Log.info("Startup health window passed for updated jar(s).");
            return StartupResult.continueRunning();
        }

        private void stopProcess(Process process) throws Exception {
            if (!process.isAlive()) {
                return;
            }
            try {
                sendCommand(process, config.restart.stopCommand);
            } catch (IOException ex) {
                Log.warn("Could not send stop command before rollback: " + ex.getMessage());
            }
            boolean stopped = process.waitFor(config.restart.gracefulStopSeconds, TimeUnit.SECONDS);
            if (!stopped) {
                Log.warn("Server did not stop before rollback; terminating process.");
                process.destroy();
                stopped = process.waitFor(15, TimeUnit.SECONDS);
                if (!stopped) {
                    process.destroyForcibly();
                    process.waitFor();
                }
            }
        }

        private void rollbackUpdates(List<InstalledUpdate> updates) throws IOException {
            for (InstalledUpdate update : updates) {
                if (update.hasBackup()) {
                    Files.copy(update.backupPath, update.targetPath, StandardCopyOption.REPLACE_EXISTING);
                    Log.warn("Rolled back " + update.target.displayName() + " -> " + update.targetPath.getFileName());
                } else {
                    Files.deleteIfExists(update.targetPath);
                    Log.warn("Removed failed new jar for " + update.target.displayName() + " because no previous jar existed.");
                }
            }
        }

        private RunResult monitorWithRestart(Process process) throws Exception {
            Instant restartAt = Instant.now().plus(config.restart.interval);
            Set<RestartWarning> sent = new HashSet<>();
            Log.info("Next scheduled restart at " + restartAt + ".");

            while (process.isAlive()) {
                Instant now = Instant.now();
                for (RestartWarning warning : config.restart.warnings) {
                    if (warning.command == null || warning.command.isBlank() || sent.contains(warning)) {
                        continue;
                    }
                    if (!now.isBefore(restartAt.minus(warning.before))) {
                        sendCommand(process, warning.command);
                        sent.add(warning);
                    }
                }
                if (!now.isBefore(restartAt)) {
                    Log.info("Restart interval reached; stopping server.");
                    sendCommand(process, config.restart.stopCommand);
                    boolean stopped = process.waitFor(config.restart.gracefulStopSeconds, TimeUnit.SECONDS);
                    if (!stopped) {
                        Log.warn("Server did not stop within " + config.restart.gracefulStopSeconds + " seconds; terminating process.");
                        process.destroy();
                        stopped = process.waitFor(15, TimeUnit.SECONDS);
                        if (!stopped) {
                            process.destroyForcibly();
                            process.waitFor();
                        }
                    }
                    return new RunResult(true, process.exitValue());
                }
                process.waitFor(1, TimeUnit.SECONDS);
            }
            return new RunResult(false, process.exitValue());
        }

        private void sendCommand(Process process, String command) throws IOException {
            Log.info("Console command -> " + command);
            OutputStream out = process.getOutputStream();
            out.write((command + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }

    private static final class RunResult {
        final boolean scheduledRestart;
        final int exitCode;

        RunResult(boolean scheduledRestart, int exitCode) {
            this.scheduledRestart = scheduledRestart;
            this.exitCode = exitCode;
        }
    }

    private static final class StartupResult {
        final boolean rollbackAndRestart;
        final boolean processExited;
        final int exitCode;
        final List<InstalledUpdate> failedUpdates;

        private StartupResult(boolean rollbackAndRestart, boolean processExited, int exitCode, List<InstalledUpdate> failedUpdates) {
            this.rollbackAndRestart = rollbackAndRestart;
            this.processExited = processExited;
            this.exitCode = exitCode;
            this.failedUpdates = failedUpdates;
        }

        static StartupResult continueRunning() {
            return new StartupResult(false, false, 0, Collections.emptyList());
        }

        static StartupResult exited(int exitCode) {
            return new StartupResult(false, true, exitCode, Collections.emptyList());
        }

        static StartupResult rollback(List<InstalledUpdate> failedUpdates) {
            return new StartupResult(true, false, 0, failedUpdates);
        }
    }

    private static final class StartupHealthMonitor {
        private final List<InstalledUpdate> updatedJars;
        private final List<InstalledUpdate> failedUpdates = Collections.synchronizedList(new ArrayList<>());

        StartupHealthMonitor(List<InstalledUpdate> updatedJars) {
            this.updatedJars = updatedJars.stream()
                .filter(update -> update != null && !update.target.server)
                .toList();
        }

        boolean enabled() {
            return !updatedJars.isEmpty();
        }

        boolean hasUpdatedJars() {
            return !updatedJars.isEmpty();
        }

        void observe(String line) {
            if (!enabled()) {
                return;
            }
            String lowerLine = normalizeLogLine(line);
            if (!looksLikePluginLoadFailure(lowerLine)) {
                return;
            }
            for (InstalledUpdate update : updatedJars) {
                if (lineMentionsUpdate(lowerLine, update) && !failedUpdates.contains(update)) {
                    failedUpdates.add(update);
                    Log.warn("Startup failure appears to mention updated plugin " + update.target.displayName() + ".");
                }
            }
        }

        boolean hasFailures() {
            return !failedUpdates.isEmpty();
        }

        List<InstalledUpdate> failedUpdates() {
            synchronized (failedUpdates) {
                return new ArrayList<>(failedUpdates);
            }
        }

        List<InstalledUpdate> allUpdatedJars() {
            return new ArrayList<>(updatedJars);
        }

        private boolean lineMentionsUpdate(String lowerLine, InstalledUpdate update) {
            for (String token : updateTokens(update)) {
                if (!token.isBlank() && lowerLine.contains(token)) {
                    return true;
                }
            }
            return false;
        }

        private List<String> updateTokens(InstalledUpdate update) {
            List<String> tokens = new ArrayList<>();
            tokens.add(normalizeLogLine(update.target.displayName()));
            tokens.add(normalizeLogLine(update.target.detectedPluginId));
            tokens.add(normalizeLogLine(update.target.installAs));
            if (update.targetPath.getFileName() != null) {
                tokens.add(normalizeLogLine(update.targetPath.getFileName().toString()));
                String filename = update.targetPath.getFileName().toString();
                if (lower(filename).endsWith(".jar")) {
                    tokens.add(normalizeLogLine(filename.substring(0, filename.length() - 4)));
                }
            }
            return tokens.stream().filter(token -> token.length() >= 3).distinct().toList();
        }

        private boolean looksLikePluginLoadFailure(String lowerLine) {
            return lowerLine.contains("could not load")
                || lowerLine.contains("failed to load")
                || lowerLine.contains("error occurred while enabling")
                || lowerLine.contains("error occurred while loading")
                || lowerLine.contains("exception loading")
                || lowerLine.contains("invalid plugin.yml")
                || lowerLine.contains("invalid plugin descriptor")
                || lowerLine.contains("unknown dependency")
                || lowerLine.contains("missing dependency")
                || lowerLine.contains("is not a valid plugin")
                || (lowerLine.contains("disabling") && lowerLine.contains("error"));
        }

        private String normalizeLogLine(String value) {
            return value == null ? "" : value.toLowerCase(Locale.ROOT).replace('\\', '/');
        }
    }

    private static final class JsonParser {
        private final String text;
        private int pos;

        JsonParser(String text) {
            this.text = text;
        }

        Object parse() {
            Object value = parseValue();
            skipWhitespace();
            if (pos != text.length()) {
                throw new IllegalArgumentException("Trailing JSON at character " + pos);
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON");
            }
            char c = text.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' && text.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (c == 'f' && text.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            if (c == 'n' && text.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            throw new IllegalArgumentException("Unexpected JSON character '" + c + "' at " + pos);
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (peek('}')) {
                    pos++;
                    return map;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    pos++;
                    return list;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw new IllegalArgumentException("Bad JSON escape");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"':
                        case '\\':
                        case '/':
                            sb.append(esc);
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw new IllegalArgumentException("Bad JSON unicode escape");
                            }
                            String hex = text.substring(pos, pos + 4);
                            sb.append((char) Integer.parseInt(hex, 16));
                            pos += 4;
                            break;
                        default:
                            throw new IllegalArgumentException("Bad JSON escape: \\" + esc);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("Unclosed JSON string");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek('-')) {
                pos++;
            }
            while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                pos++;
            }
            boolean decimal = false;
            if (peek('.')) {
                decimal = true;
                pos++;
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < text.length() && (text.charAt(pos) == 'e' || text.charAt(pos) == 'E')) {
                decimal = true;
                pos++;
                if (pos < text.length() && (text.charAt(pos) == '+' || text.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < text.length() && Character.isDigit(text.charAt(pos))) {
                    pos++;
                }
            }
            String value = text.substring(start, pos);
            return decimal ? Double.parseDouble(value) : Long.parseLong(value);
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at JSON character " + pos);
            }
            pos++;
        }

        private boolean peek(char c) {
            return pos < text.length() && text.charAt(pos) == c;
        }
    }

    private static final class Log {
        static void info(String message) {
            System.out.println("[updater] " + message);
        }

        static void warn(String message) {
            System.out.println("[updater:warn] " + message);
        }

        static void error(String message) {
            System.err.println("[updater:error] " + message);
        }
    }

    private static boolean parseBoolean(String value) {
        String v = lower(value);
        return v.equals("true") || v.equals("yes") || v.equals("on") || v.equals("1");
    }

    private static List<String> parseList(String value) {
        if (value == null || value.isBlank()) {
            return new ArrayList<>();
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed = trimmed.substring(1, trimmed.length() - 1);
        }
        List<String> result = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                result.add(ConfigParser.unquote(item));
            }
        }
        return result;
    }

    private static Duration parseDuration(String value) {
        String v = lower(value).trim();
        if (v.isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }
        long total = 0L;
        int start = 0;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (Character.isLetter(c)) {
                if (start == i) {
                    throw new IllegalArgumentException("Bad duration: " + value);
                }
                long amount = Long.parseLong(v.substring(start, i));
                switch (c) {
                    case 'd':
                        total += Duration.ofDays(amount).toSeconds();
                        break;
                    case 'h':
                        total += Duration.ofHours(amount).toSeconds();
                        break;
                    case 'm':
                        total += Duration.ofMinutes(amount).toSeconds();
                        break;
                    case 's':
                        total += amount;
                        break;
                    default:
                        throw new IllegalArgumentException("Bad duration unit '" + c + "' in " + value);
                }
                start = i + 1;
            }
        }
        if (start != v.length()) {
            total += Long.parseLong(v.substring(start));
        }
        return Duration.ofSeconds(total);
    }

    private static String prettyDuration(Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds % 86400 == 0) {
            return (seconds / 86400) + "d";
        }
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            byte[] buffer = new byte[8192];
            while (in.read(buffer) >= 0) {
                // DigestInputStream updates the digest.
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 12 ? String.valueOf(hash) : hash.substring(0, 12);
    }

    private static String safeName(String value) {
        String cleaned = value == null ? "unnamed" : value.replaceAll("[^A-Za-z0-9._-]+", "-");
        cleaned = cleaned.replaceAll("-+", "-");
        if (cleaned.isBlank()) {
            return "unnamed";
        }
        return cleaned;
    }

    private static String cleanVersion(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        cleaned = cleaned.replaceFirst("(?i)^version[:=]", "");
        cleaned = cleaned.replaceFirst("(?i)^release[-_ ]?", "");
        cleaned = cleaned.replaceFirst("(?i)^v(?=\\d)", "");
        cleaned = cleaned.replaceAll("[^A-Za-z0-9._+-]+", "");
        return cleaned;
    }

    private static String quoteYaml(String value) {
        if (value == null || value.isBlank()) {
            return "\"\"";
        }
        if (value.matches("[A-Za-z0-9_./:@?=&%+,-]+")) {
            return value;
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static boolean isAutoValue(String value) {
        return value != null && value.trim().equalsIgnoreCase("auto");
    }

    private static ServerJarDetection detectExistingServerJar(Path baseDir, TargetConfig target) {
        List<Path> candidates = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        if (target.installAs != null && !target.installAs.isBlank() && !isAutoValue(target.installAs)) {
            addJarCandidate(baseDir, candidates, seen, Paths.get(target.installAs));
        }
        for (String filename : List.of("folia.jar", "paper.jar", "velocity.jar", "waterfall.jar", "server.jar")) {
            addJarCandidate(baseDir, candidates, seen, Paths.get(filename));
        }
        for (Path jar : listJarFiles(baseDir)) {
            String filename = lower(jar.getFileName().toString());
            if (filename.equals("auto-updater.jar") || filename.equals("velocity-auto-updater.jar")) {
                continue;
            }
            if (inferPaperMcProjectFromText(filename).isBlank() && !filename.equals("server.jar")) {
                continue;
            }
            addJarCandidate(baseDir, candidates, seen, jar);
        }

        Path firstExisting = null;
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            if (firstExisting == null) {
                firstExisting = candidate;
            }
            String project = inferPaperMcProjectFromText(candidate.getFileName().toString());
            if (!project.isBlank()) {
                return new ServerJarDetection(candidate, project);
            }
        }
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            String project = readServerProjectFromJar(candidate);
            if (!project.isBlank()) {
                return new ServerJarDetection(candidate, project);
            }
        }
        return new ServerJarDetection(firstExisting, "");
    }

    private static void addJarCandidate(Path baseDir, List<Path> candidates, Set<Path> seen, Path path) {
        Path candidate = path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
        if (seen.add(candidate)) {
            candidates.add(candidate);
        }
    }

    private static List<Path> listJarFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return Collections.emptyList();
        }
        try (var stream = Files.list(dir)) {
            return stream
                .filter(Files::isRegularFile)
                .filter(path -> lower(path.getFileName().toString()).endsWith(".jar"))
                .sorted(Comparator.comparing(path -> lower(path.getFileName().toString())))
                .toList();
        } catch (IOException ex) {
            Log.warn("Could not scan jar directory " + dir + ": " + ex.getMessage());
            return Collections.emptyList();
        }
    }

    private static String readServerProjectFromJar(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            if (file.getManifest() != null) {
                String mainClass = lower(file.getManifest().getMainAttributes().getValue("Main-Class"));
                String title = lower(file.getManifest().getMainAttributes().getValue("Implementation-Title"));
                String manifestText = mainClass + " " + title;
                if (manifestText.contains("velocity")) {
                    return "velocity";
                }
                if (manifestText.contains("folia")) {
                    return "folia";
                }
                if (manifestText.contains("paper")) {
                    return "paper";
                }
            }
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                String name = lower(entries.nextElement().getName());
                if (name.contains("com/velocitypowered/proxy/")) {
                    return "velocity";
                }
                if (name.contains("io/papermc/folia/")) {
                    return "folia";
                }
                if (name.contains("io/papermc/paperclip/") || name.contains("io/papermc/paper/")) {
                    return "paper";
                }
            }
        } catch (IOException ex) {
            Log.warn("Could not inspect server jar " + jar.getFileName() + ": " + ex.getMessage());
        }
        return "";
    }

    private static PluginJarInfo readPluginJarInfo(Path jar) {
        String filename = jar.getFileName().toString();
        String fallbackName = filename.substring(0, filename.length() - ".jar".length());
        try (JarFile file = new JarFile(jar.toFile())) {
            PluginJarInfo velocityInfo = readVelocityPluginInfo(file);
            if (!velocityInfo.name.isBlank() || !velocityInfo.id.isBlank()) {
                return new PluginJarInfo(velocityInfo.id, firstNonBlank(velocityInfo.name, velocityInfo.id), velocityInfo.version, velocityInfo.website);
            }
            for (String entry : List.of("paper-plugin.yml", "plugin.yml", "bungee.yml")) {
                String name = readYamlEntryValue(file, entry, "name");
                if (!name.isBlank()) {
                    String version = readYamlEntryValue(file, entry, "version");
                    String website = readYamlEntryValue(file, entry, "website");
                    return new PluginJarInfo(name, name, version, website);
                }
            }
        } catch (IOException ex) {
            Log.warn("Could not inspect plugin jar " + jar.getFileName() + ": " + ex.getMessage());
        }
        return new PluginJarInfo(fallbackName, fallbackName, "", "");
    }

    private static PluginJarInfo readVelocityPluginInfo(JarFile file) throws IOException {
        String text = readJarEntry(file, "velocity-plugin.json");
        if (text.isBlank()) {
            return new PluginJarInfo("", "", "", "");
        }
        try {
            Map<String, Object> json = asMap(new JsonParser(text).parse());
            String id = stringValue(json.get("id"));
            String name = stringValue(json.get("name"));
            String version = stringValue(json.get("version"));
            String website = firstNonBlank(stringValue(json.get("url")), stringValue(json.get("website")));
            return new PluginJarInfo(id, name, version, website);
        } catch (RuntimeException ex) {
            return new PluginJarInfo("", "", "", "");
        }
    }

    private static String readYamlEntryValue(JarFile file, String entryName, String key) throws IOException {
        String text = readJarEntry(file, entryName);
        if (text.isBlank()) {
            return "";
        }
        for (String raw : text.split("\\R")) {
            String line = ConfigParser.stripComment(raw).trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = ConfigParser.findColon(line);
            if (colon < 0) {
                continue;
            }
            String foundKey = line.substring(0, colon).trim();
            if (foundKey.equalsIgnoreCase(key)) {
                return ConfigParser.unquote(line.substring(colon + 1).trim());
            }
        }
        return "";
    }

    private static String readJarEntry(JarFile file, String entryName) throws IOException {
        JarEntry entry = file.getJarEntry(entryName);
        if (entry == null || entry.isDirectory()) {
            return "";
        }
        try (InputStream in = file.getInputStream(entry)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String relativeConfigPath(Path baseDir, Path path) {
        Path absoluteBase = baseDir.toAbsolutePath().normalize();
        Path absolutePath = path.toAbsolutePath().normalize();
        try {
            return normalizeSlashes(absoluteBase.relativize(absolutePath).toString());
        } catch (IllegalArgumentException ex) {
            return normalizeSlashes(absolutePath.toString());
        }
    }

    private static String normalizedConfigPath(String path) {
        String normalized = normalizeSlashes(path).trim();
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return lower(normalized);
    }

    private static String normalizeSlashes(String value) {
        return value.replace('\\', '/');
    }

    private static String inferredPluginPlatform(TargetConfig server) {
        String project = inferPaperMcProject(server);
        if (project.equals("velocity") || project.equals("waterfall")) {
            return project;
        }
        if (project.equals("paper") || project.equals("folia")) {
            return "paper";
        }
        return "";
    }

    private static String paperMcDownloadSource(String project) {
        return "https://papermc.io/downloads/" + project;
    }

    private static String inferPaperMcProjectFromText(String text) {
        String source = lower(text);
        for (String project : List.of("folia", "velocity", "waterfall", "paper")) {
            if (source.contains(project)) {
                return project;
            }
        }
        return "";
    }

    private static String inferPaperMcProject(TargetConfig target) {
        String source = lower(firstNonBlank(target.source, target.project, target.name, target.installAs, ""));
        if (source.contains("papermc.io/downloads/")) {
            String[] parts = source.split("/");
            String last = parts.length == 0 ? "" : parts[parts.length - 1];
            if (isPaperMcProject(last)) {
                return last;
            }
        }
        return inferPaperMcProjectFromText(source);
    }

    private static boolean isPaperMcProject(String value) {
        return value.equals("paper") || value.equals("folia") || value.equals("velocity") || value.equals("waterfall");
    }

    private static String title(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        if (value.equalsIgnoreCase("papermc")) {
            return "PaperMC";
        }
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private static URI sourceUri(String source, AppConfig config) {
        try {
            URI uri = URI.create(source);
            if (uri.getScheme() != null && uri.getScheme().length() > 1) {
                return uri;
            }
        } catch (IllegalArgumentException ignored) {
            // Treat non-URI values as local paths.
        }
        return config.resolve(Paths.get(source)).toUri();
    }

    private static List<String> pathParts(URI uri) {
        String path = uri.getPath() == null ? "" : uri.getPath();
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String readLockValue(AppConfig config, String key) {
        Path lock = config.resolve(Paths.get("updater.lock.yml"));
        if (!Files.exists(lock)) {
            return "";
        }
        try {
            for (String raw : Files.readAllLines(lock, StandardCharsets.UTF_8)) {
                String line = ConfigParser.stripComment(raw).trim();
                if (line.isEmpty() || !line.contains(":")) {
                    continue;
                }
                KeyValue kv = ConfigParser.keyValue(line, 0);
                if (kv.key.equals(key)) {
                    return kv.value;
                }
            }
        } catch (Exception ex) {
            Log.warn("Could not read updater.lock.yml: " + ex.getMessage());
        }
        return "";
    }

    private static int compareVersionsNewestFirst(String a, String b) {
        return compareVersionValues(b, a);
    }

    private static int compareVersionValues(String a, String b) {
        List<String> aa = versionTokens(a);
        List<String> bb = versionTokens(b);
        int max = Math.max(aa.size(), bb.size());
        for (int i = 0; i < max; i++) {
            String av = i < aa.size() ? aa.get(i) : "0";
            String bv = i < bb.size() ? bb.get(i) : "0";
            boolean an = av.matches("\\d+");
            boolean bn = bv.matches("\\d+");
            int cmp;
            if (an && bn) {
                cmp = Long.compare(Long.parseLong(av), Long.parseLong(bv));
            } else {
                cmp = av.compareToIgnoreCase(bv);
            }
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    private static List<String> versionTokens(String value) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean numeric = false;
        boolean started = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isLetterOrDigit(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
                started = false;
                continue;
            }
            boolean cNumeric = Character.isDigit(c);
            if (started && cNumeric != numeric) {
                tokens.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
            numeric = cNumeric;
            started = true;
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String jsonArray(String csv) {
        List<String> values = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                values.add("\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
            }
        }
        return "[" + String.join(",", values) + "]";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return castStringMap(map);
        }
        throw new IllegalArgumentException("Expected JSON object but found " + Objects.toString(value));
    }

    private static Map<String, Object> castStringMap(Map<?, ?> raw) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<String> splitCommand(String command) {
        if (command == null || command.isBlank()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean single = false;
        boolean dbl = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (c == '\'' && !dbl) {
                single = !single;
            } else if (c == '"' && !single) {
                dbl = !dbl;
            } else if (Character.isWhitespace(c) && !single && !dbl) {
                if (current.length() > 0) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            result.add(current.toString());
        }
        return result;
    }

    private static final class ExampleConfig {
        private static final String TEXT = """
            # Auto-Updater
            # Run with: java -jar auto-updater.jar run
            # The editable config is first. Detailed notes are at the bottom.

            mode: hosted-safe
            onFailure: keep-current
            userAgent: "Auto-Updater/%s (contact: your-email@example.com)"

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

            server:
              name: auto
              # source: auto detects an existing server jar. If this is a first
              # install with no server jar yet, use a PaperMC URL from the examples above.
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
                githubRepo: ViaVersion/ViaVersion
                platform: paper
                fallbackSources: https://hangar.papermc.io/ViaVersion/ViaVersion/versions, https://modrinth.com/plugin/viaversion/versions
                installAs: plugins/ViaVersion.jar
                required: false

              - name: ViaBackwards
                source: https://github.com/ViaVersion/ViaBackwards
                type: auto
                githubRepo: ViaVersion/ViaBackwards
                platform: paper
                fallbackSources: https://hangar.papermc.io/ViaVersion/ViaBackwards/versions, https://modrinth.com/plugin/viabackwards/versions
                installAs: plugins/ViaBackwards.jar
                required: false

              - name: ViaRewind
                source: https://github.com/ViaVersion/ViaRewind
                type: auto
                githubRepo: ViaVersion/ViaRewind
                platform: paper
                fallbackSources: https://hangar.papermc.io/ViaVersion/ViaRewind/versions, https://modrinth.com/plugin/viarewind/versions
                installAs: plugins/ViaRewind.jar
                required: false

              # Optional Modrinth example:
              # - name: FancyHolograms
              #   source: https://modrinth.com/plugin/fancyholograms/versions
              #   type: auto
              #   loader: paper
              #   installAs: plugins/FancyHolograms.jar
              #   required: false

              # Optional Spigot/Spiget example:
              # - name: EpicHomes
              #   source: https://www.spigotmc.org/resources/epichomes-26-1-x-support.109590/
              #   type: auto
              #   installAs: plugins/EpicHomes.jar
              #   required: false

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

            # ---------------------------------------------------------------------------
            # Config Notes
            # ---------------------------------------------------------------------------
            #
            # mode
            #   hosted-safe:
            #     Recommended for hosted panels and normal servers.
            #     Downloads ready-made jars only. It will not run Git, Gradle, Maven,
            #     or compile source code.
            #   auto:
            #     Allows auto-detection features. In this version, updates still use
            #     hosted/downloaded jars only.
            #
            # onFailure
            #   keep-current:
            #     Recommended. If an update fails and an old jar exists, keep using it.
            #     This helps the server still start when a download site is down.
            #   stop:
            #     Stop startup if a required update cannot be completed.
            #
            # startup rollback
            #   After installing plugin updates, Auto-Updater watches early server console
            #   output for load failures that mention an updated plugin. If one is found,
            #   it restores the previous jar backup, restarts once, and keeps the server on
            #   the last jar that actually loaded. If no previous jar existed, it removes
            #   the failed new jar.
            #
            # userAgent
            #   Sent to download APIs. PaperMC asks automated clients to include a real
            #   contact string, so replace your-email@example.com.
            #
            # discovery.enabled
            #   Turns discovery reporting on or off.
            #
            # discovery.mode
            #   suggest:
            #     Report suggestions without rewriting your config.
            #     If saveDiscoveredSources is true, confident missing plugin sources can
            #     still be written back.
            #
            # discovery.sourcePriority
            #   Preferred order when choosing good discovered plugin sources.
            #   Supported source families: github-release, hangar, modrinth, spigot.
            #
            # discovery.checkAlternateSourcesWhenOutdated
            #   If true, discovery compares version strings where APIs expose them and
            #   rejects sources that look clearly older than the installed plugin jar.
            #
            # discovery.outdatedThresholdDays
            #   How old a source can look before discovery treats it as stale.
            #
            # discovery.autoSwitchSource
            #   If true, plugins with no source can use the best discovered hosted source
            #   automatically for that run, with other strong matches added as fallbacks.
            #   Existing explicit sources are left alone.
            #
            # discovery.saveDiscoveredSources
            #   If true, auto-switched plugin sources are written back into this config.
            #   Existing plugin entries are patched by installAs/name. Newly scanned plugins
            #   are appended under plugins: with the discovered source and fallbacks.
            #
            # discovery.scanInstalledPlugins
            #   If true, scans plugins/ for jars that are not already listed.
            #   It can fill name, installAs, platform, and required.
            #   It also searches GitHub, Hangar, Modrinth, and Spigot for likely update
            #   sources, then prints the best YAML entry it can safely suggest.
            #
            # buildFromSource.enabled
            #   Future Git build switch. Keep false for hosted-safe behavior.
            #
            # buildFromSource.onlyTrusted
            #   Future safety switch. If building is enabled later, only trusted GitHub
            #   orgs/repos should be allowed to run build scripts.
            #
            # buildFromSource.preferHostedIfSameVersion
            #   Future optimization. If a trusted hosted jar matches the version that would
            #   be built from Git, download the hosted jar and skip compiling.
            #
            # buildFromSource.trustedGithubOrgs / trustedGithubRepos
            #   GitHub orgs/repos you explicitly trust for future source builds.
            #
            # server.name
            #   Display name. Use auto to derive Paper, Folia, Velocity, etc.
            #
            # server.source
            #   Where the server jar comes from.
            #   auto:
            #     Detects an existing paper.jar, folia.jar, velocity.jar, or waterfall.jar
            #     and maps it to the matching PaperMC download source.
            #   PaperMC examples:
            #     https://papermc.io/downloads/folia
            #     https://papermc.io/downloads/paper
            #     https://papermc.io/downloads/velocity
            #
            # server.type
            #   auto:
            #     Recommended. Detects the source type from the URL.
            #   papermc:
            #     Treats source as PaperMC server software.
            #
            # server.installAs
            #   Final server jar path/name. installAs: auto uses the detected jar name.
            #
            # server.gameVersion
            #   auto:
            #     On first install, lock the newest available PaperMC version in
            #     updater.lock.yml. Future runs keep that locked version when
            #     changeVersion is false.
            #   Exact version:
            #     Use a specific Minecraft/server version you intentionally want.
            #
            # server.changeVersion
            #   false:
            #     Recommended. Stay on the configured or locked gameVersion.
            #   true:
            #     Allow the server jar to jump to the newest available version.
            #
            # server.java
            #   Java command used to launch the server. Usually java.
            #
            # server.javaArgs
            #   JVM options/memory for the launched server.
            #   If your host panel controls memory, set this to "".
            #
            # server.args
            #   Extra arguments after the server jar name. Usually "".
            #
            # plugins[].name
            #   Friendly display name. It does not have to match the jar filename.
            #
            # plugins[].source
            #   Where the plugin jar comes from.
            #   GitHub release example: https://github.com/ViaVersion/ViaVersion
            #   Hangar example: https://hangar.papermc.io/ViaVersion/ViaVersion/versions
            #   Modrinth example: https://modrinth.com/plugin/fancyholograms/versions
            #   Spigot example: https://www.spigotmc.org/resources/epichomes-26-1-x-support.109590/
            #   Direct jar example: https://example.com/MyPlugin.jar
            #
            # plugins[].type
            #   auto:
            #     Recommended. Detects the source type from the URL.
            #   github-release:
            #     Download the newest release jar from a GitHub repository.
            #   hangar:
            #     Download from Hangar.
            #   modrinth:
            #     Download from Modrinth.
            #   spigot:
            #     Download from Spigot through Spiget when available without login.
            #   geysermc:
            #     Use GeyserMC download endpoints.
            #   direct:
            #     Use a direct jar URL or local jar path.
            #
            # plugins[].githubRepo
            #   Optional repo hint like Owner/Repo. Useful for discovery and GitHub sources.
            #
            # plugins[].platform
            #   Platform for Hangar/GeyserMC downloads. Common: paper, velocity, waterfall.
            #   For Folia plugins, paper is usually the closest platform.
            #
            # plugins[].loader
            #   Modrinth loader filter. Examples: paper, velocity, fabric.
            #
            # plugins[].versionType
            #   Optional Modrinth release type: release, beta, or alpha.
            #
            # plugins[].channel
            #   Optional Hangar channel filter. If omitted, Release builds are preferred.
            #
            # plugins[].fallbackSources
            #   Comma-separated backup sources to try if the main source fails.
            #
            # plugins[].installAs
            #   Final plugin jar path/name, such as plugins/ViaVersion.jar.
            #
            # plugins[].required
            #   false:
            #     If update fails and no old jar exists, the server may still start without
            #     this plugin.
            #   true:
            #     If update fails and no old jar exists, stop startup.
            #   Either way, if an old jar exists and onFailure is keep-current, the updater
            #   keeps the previous jar.
            #
            # restart.enabled
            #   Turns scheduled restarts on or off.
            #
            # restart.interval
            #   How often to restart. Examples: 7d, 12h, 30m.
            #
            # restart.stopCommand
            #   Console command sent when it is time to stop.
            #   Folia/Paper usually use stop. Velocity usually uses shutdown.
            #
            # restart.gracefulStopSeconds
            #   Seconds to wait after sending stopCommand before forcing the process closed.
            #
            # restart.warnings
            #   Console commands sent before restart. before accepts values like 2h, 30m,
            #   5m, or 1m.
            """.formatted(VERSION);
    }
}
