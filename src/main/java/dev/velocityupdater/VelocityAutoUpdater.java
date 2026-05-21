package dev.velocityupdater;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URI;
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
import java.util.jar.JarFile;

public final class VelocityAutoUpdater {
    private static final String VERSION = "0.1.0";
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
        config.baseDir = configPath.getParent() == null ? Paths.get(".").toAbsolutePath().normalize() : configPath.getParent();
        config.validate();

        Updater updater = new Updater(config);
        switch (cli.command) {
            case "check":
                updater.printPlan();
                return 0;
            case "update":
                updater.updateAll();
                return 0;
            case "run":
                updater.updateAll();
                return new ServerRunner(config, updater).runServerLoop();
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
        System.out.println("Velocity Auto Updater " + VERSION);
        System.out.println();
        System.out.println("Usage:");
        System.out.println("  java -jar velocity-auto-updater.jar init [--config updater.yml]");
        System.out.println("  java -jar velocity-auto-updater.jar check [--config updater.yml]");
        System.out.println("  java -jar velocity-auto-updater.jar update [--config updater.yml]");
        System.out.println("  java -jar velocity-auto-updater.jar run [--config updater.yml]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init    Create a starter updater.yml");
        System.out.println("  check   Parse config and show detected update sources");
        System.out.println("  update  Download/update configured jars, then exit");
        System.out.println("  run     Update jars, start Velocity, and manage scheduled restarts");
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
        String mode = "hosted-safe";
        String onFailure = "keep-current";
        String userAgent = "velocity-auto-updater/" + VERSION + " (contact: your-email@example.com)";
        Path cacheDir = Paths.get("cache");
        Path backupDir = Paths.get("backups");
        TargetConfig server = new TargetConfig("Velocity", true);
        List<TargetConfig> plugins = new ArrayList<>();
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
            if (server.installAs == null || server.installAs.isBlank()) {
                server.installAs = "velocity.jar";
            }
            if (server.source == null || server.source.isBlank()) {
                Log.warn("No server.source configured. The updater will only launch the existing " + server.installAs + ".");
            }
            for (TargetConfig plugin : plugins) {
                if (plugin.installAs == null || plugin.installAs.isBlank()) {
                    if (plugin.name != null && !plugin.name.isBlank()) {
                        plugin.installAs = "plugins/" + safeName(plugin.name) + ".jar";
                    } else {
                        throw new IllegalArgumentException("Each plugin needs installAs when name is missing");
                    }
                }
            }
            restart.warnings.sort(Comparator.comparing((RestartWarning w) -> w.before).reversed());
        }

        Path resolve(Path path) {
            if (path.isAbsolute()) {
                return path.normalize();
            }
            return baseDir.resolve(path).normalize();
        }
    }

    private static final class TargetConfig {
        String name;
        boolean server;
        boolean enabled = true;
        boolean required;
        String source;
        String type = "auto";
        String project;
        String platform;
        String channel;
        String installAs;
        String java = "java";
        String javaArgs = "";
        String args = "";

        TargetConfig(String name, boolean server) {
            this.name = name;
            this.server = server;
            this.required = server;
        }

        String displayName() {
            if (name != null && !name.isBlank()) {
                return name;
            }
            return server ? "Velocity" : installAs;
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
                case "type":
                    target.type = kv.value;
                    break;
                case "project":
                    target.project = kv.value;
                    break;
                case "platform":
                    target.platform = kv.value;
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

    private static final class KeyValue {
        final String key;
        final String value;

        KeyValue(String key, String value) {
            this.key = key;
            this.value = value;
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
            Log.info("Server install target: " + config.resolve(Paths.get(config.server.installAs)));
            for (TargetConfig target : allTargets()) {
                if (!target.enabled) {
                    Log.info("Skipping disabled target: " + target.displayName());
                    continue;
                }
                SourcePlan plan = resolveSource(target);
                Log.info(target.displayName() + ": type=" + plan.type + ", installAs=" + target.installAs + ", source=" + plan.description);
            }
            if (config.restart.enabled) {
                Log.info("Restart: every " + prettyDuration(config.restart.interval) + " using command '" + config.restart.stopCommand + "'");
            } else {
                Log.info("Restart: disabled");
            }
        }

        void updateAll() throws Exception {
            Files.createDirectories(config.resolve(config.cacheDir));
            Files.createDirectories(config.resolve(config.backupDir));
            for (TargetConfig target : allTargets()) {
                if (!target.enabled) {
                    Log.info("Skipping disabled target: " + target.displayName());
                    continue;
                }
                if (target.source == null || target.source.isBlank()) {
                    Log.info("No source configured for " + target.displayName() + "; keeping existing jar.");
                    continue;
                }
                try {
                    updateOne(target);
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
        }

        private List<TargetConfig> allTargets() {
            List<TargetConfig> targets = new ArrayList<>();
            targets.add(config.server);
            targets.addAll(config.plugins);
            return targets;
        }

        private void updateOne(TargetConfig target) throws Exception {
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
                    return;
                }
                backup(targetPath);
            }

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            moveReplace(staging, targetPath);
            Log.info("Installed " + target.displayName() + " -> " + targetPath + " (" + shortHash(newHash) + ")");
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
            if (lowerSource.contains("github.com/")
                && (lowerSource.contains("/releases/download/") || lowerSource.endsWith(".jar"))) {
                return "direct";
            }
            if (lowerSource.endsWith(".git") || lowerSource.startsWith("git@") || lowerSource.contains("github.com/")) {
                return "git";
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

        private void backup(Path targetPath) throws IOException {
            Path backups = config.resolve(config.backupDir);
            Files.createDirectories(backups);
            String filename = targetPath.getFileName().toString();
            String stamp = LocalDateTime.now().format(BACKUP_TIME);
            Path backup = backups.resolve(filename + "." + stamp + ".bak");
            Files.copy(targetPath, backup, StandardCopyOption.REPLACE_EXISTING);
            Log.info("Backed up " + targetPath.getFileName() + " -> " + backup);
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

        ResolvedDownload(URI uri, String label) {
            this.uri = uri;
            this.label = label;
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

    private static final class PaperMcResolver implements DownloadResolver {
        private final AppConfig config;
        private final HttpClient client;

        PaperMcResolver(AppConfig config, HttpClient client) {
            this.config = config;
            this.client = client;
        }

        @Override
        public ResolvedDownload resolve(TargetConfig target) throws Exception {
            String project = firstNonBlank(target.project, inferProject(target), "velocity");
            List<String> versions = loadVersions(project);
            if (versions.isEmpty()) {
                throw new IOException("PaperMC returned no versions for project " + project);
            }
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
            String source = lower(firstNonBlank(target.source, target.name, target.installAs, ""));
            if (source.contains("velocity")) {
                return "velocity";
            }
            if (source.contains("waterfall")) {
                return "waterfall";
            }
            return "paper";
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
                    return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build)));
                }
            }
            for (Map.Entry<String, Object> entry : downloads.entrySet()) {
                if (!entry.getKey().toLowerCase(Locale.ROOT).contains("server")) {
                    continue;
                }
                if (entry.getValue() instanceof Map<?, ?> map) {
                    String url = stringValue(castStringMap(map).get("url"));
                    if (!url.isBlank()) {
                        return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build)));
                    }
                }
            }
            for (Object value : downloads.values()) {
                if (value instanceof Map<?, ?> map) {
                    String url = stringValue(castStringMap(map).get("url"));
                    if (!url.isBlank()) {
                        return Optional.of(new ResolvedDownload(URI.create(url), label(project, version, build)));
                    }
                }
            }
            return Optional.empty();
        }

        private String label(String project, String version, Map<String, Object> build) {
            String number = firstNonBlank(stringValue(build.get("number")), stringValue(build.get("id")), "?");
            String channel = stringValue(build.get("channel"));
            return project + " " + version + " build " + number + (channel.isBlank() ? "" : " (" + channel + ")");
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

        int runServerLoop() throws Exception {
            Path serverJar = config.resolve(Paths.get(config.server.installAs));
            if (!Files.exists(serverJar)) {
                throw new IOException("Server jar does not exist: " + serverJar);
            }

            while (true) {
                Process process = startServer(serverJar);
                Thread outputThread = pipeOutput(process);
                Thread inputThread = pipeInput(process);

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
                updater.updateAll();
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

        private Thread pipeOutput(Process process) {
            Thread thread = new Thread(() -> {
                try (InputStream in = process.getInputStream()) {
                    in.transferTo(System.out);
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
        private static final String TEXT = ""
            + "# Velocity Auto Updater\n"
            + "# Run with: java -jar velocity-auto-updater.jar run\n"
            + "#\n"
            + "# ---------------------------------------------------------------------------\n"
            + "# Field Guide\n"
            + "# ---------------------------------------------------------------------------\n"
            + "#\n"
            + "# mode\n"
            + "#   What the updater is allowed to do.\n"
            + "#\n"
            + "#   Options:\n"
            + "#     hosted-safe\n"
            + "#       Recommended for BisectHosting and other server hosts.\n"
            + "#       The updater only downloads ready-made jar files from websites/APIs.\n"
            + "#       It will NOT run Git, Gradle, Maven, or compile source code.\n"
            + "#\n"
            + "#     auto\n"
            + "#       Allows the updater to auto-detect source types.\n"
            + "#       In this version, it still only supports hosted/downloaded jars.\n"
            + "#       Build-from-source is not included yet.\n"
            + "#\n"
            + "# onFailure\n"
            + "#   What happens if a jar cannot be updated.\n"
            + "#\n"
            + "#   Options:\n"
            + "#     keep-current\n"
            + "#       Recommended.\n"
            + "#       If the update fails but an old jar already exists, keep using the old jar.\n"
            + "#       This lets the server still start when a download site is temporarily down.\n"
            + "#\n"
            + "#     stop\n"
            + "#       Stop startup if an update fails.\n"
            + "#\n"
            + "# userAgent\n"
            + "#   Text sent with download requests.\n"
            + "#   PaperMC asks automated download clients to use a real User-Agent with\n"
            + "#   contact info, so replace your-email@example.com with your own email or site.\n"
            + "#\n"
            + "# name\n"
            + "#   A friendly label shown in updater logs.\n"
            + "#   It has no fixed options. You can name it whatever you want.\n"
            + "#   This does NOT control the jar filename.\n"
            + "#\n"
            + "#   Example:\n"
            + "#     name: Geyser\n"
            + "#\n"
            + "# source\n"
            + "#   Where the updater gets the new jar from.\n"
            + "#   This can be a supported website, a direct jar download URL, or a local jar path.\n"
            + "#\n"
            + "#   Examples:\n"
            + "#     PaperMC Velocity:\n"
            + "#       https://papermc.io/downloads/velocity\n"
            + "#\n"
            + "#     Geyser for Velocity:\n"
            + "#       https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/velocity\n"
            + "#\n"
            + "#     Floodgate for Velocity:\n"
            + "#       https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/velocity\n"
            + "#\n"
            + "#     Direct jar URL:\n"
            + "#       https://example.com/MyPlugin.jar\n"
            + "#\n"
            + "# type\n"
            + "#   Tells the updater how to understand the source.\n"
            + "#\n"
            + "#   Options:\n"
            + "#     auto\n"
            + "#       Recommended.\n"
            + "#       The updater guesses the correct type from the source URL.\n"
            + "#\n"
            + "#     papermc\n"
            + "#       Force the updater to treat the source as a PaperMC download.\n"
            + "#\n"
            + "#     geysermc\n"
            + "#       Force the updater to treat the source as a GeyserMC download.\n"
            + "#\n"
            + "#     direct\n"
            + "#       Treat the source as a direct jar download URL or local jar file.\n"
            + "#\n"
            + "# installAs\n"
            + "#   The exact file path and filename the downloaded jar will become.\n"
            + "#   This is what controls the final jar name.\n"
            + "#\n"
            + "#   Example:\n"
            + "#     installAs: plugins/Geyser-Velocity.jar\n"
            + "#\n"
            + "#   That means the downloaded file will be saved as:\n"
            + "#     plugins/Geyser-Velocity.jar\n"
            + "#\n"
            + "# required\n"
            + "#   Controls what happens when this jar is missing and cannot be downloaded.\n"
            + "#\n"
            + "#   Options:\n"
            + "#     false\n"
            + "#       If the update fails and an old jar exists, keep the old jar.\n"
            + "#       If no old jar exists, the server may still start without this plugin.\n"
            + "#\n"
            + "#     true\n"
            + "#       If the update fails and an old jar exists, keep the old jar.\n"
            + "#       If no old jar exists, stop startup.\n"
            + "#\n"
            + "# java\n"
            + "#   The Java command used to start Velocity.\n"
            + "#   Usually:\n"
            + "#     java\n"
            + "#\n"
            + "# javaArgs\n"
            + "#   Memory/Java options used when starting Velocity.\n"
            + "#\n"
            + "#   Example:\n"
            + "#     javaArgs: \"-Xms512M -Xmx1G\"\n"
            + "#\n"
            + "#   If your host already controls RAM settings, use:\n"
            + "#     javaArgs: \"\"\n"
            + "#\n"
            + "# args\n"
            + "#   Extra arguments passed to Velocity after the jar name.\n"
            + "#   Usually leave this empty:\n"
            + "#     args: \"\"\n"
            + "#\n"
            + "# restart.enabled\n"
            + "#   Turns scheduled restarts on or off.\n"
            + "#\n"
            + "#   Options:\n"
            + "#     true\n"
            + "#     false\n"
            + "#\n"
            + "# restart.interval\n"
            + "#   How often to restart.\n"
            + "#\n"
            + "#   Examples:\n"
            + "#     7d  = every 7 days\n"
            + "#     12h = every 12 hours\n"
            + "#     30m = every 30 minutes\n"
            + "#\n"
            + "# restart.stopCommand\n"
            + "#   Console command sent when it is time to stop Velocity.\n"
            + "#   Usually:\n"
            + "#     shutdown\n"
            + "#\n"
            + "# restart.warnings\n"
            + "#   Warning commands sent before the restart.\n"
            + "#   These are sent into the Velocity console.\n"
            + "#\n"
            + "# ---------------------------------------------------------------------------\n"
            + "\n"
            + "mode: hosted-safe\n"
            + "onFailure: keep-current\n"
            + "# PaperMC asks automated clients to use a real User-Agent with contact info.\n"
            + "userAgent: \"velocity-auto-updater/" + VERSION + " (contact: your-email@example.com)\"\n"
            + "\n"
            + "server:\n"
            + "  name: Velocity\n"
            + "  source: https://papermc.io/downloads/velocity\n"
            + "  type: auto\n"
            + "  installAs: velocity.jar\n"
            + "  java: java\n"
            + "  javaArgs: \"-Xms512M -Xmx1G\"\n"
            + "  args: \"\"\n"
            + "\n"
            + "plugins:\n"
            + "  - name: Geyser\n"
            + "    source: https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/velocity\n"
            + "    type: auto\n"
            + "    installAs: plugins/Geyser-Velocity.jar\n"
            + "    required: false\n"
            + "\n"
            + "  # Optional Floodgate example:\n"
            + "  # - name: Floodgate\n"
            + "  #   source: https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/velocity\n"
            + "  #   type: auto\n"
            + "  #   installAs: plugins/Floodgate-Velocity.jar\n"
            + "  #   required: false\n"
            + "\n"
            + "restart:\n"
            + "  enabled: true\n"
            + "  interval: 7d\n"
            + "  stopCommand: shutdown\n"
            + "  gracefulStopSeconds: 60\n"
            + "  warnings:\n"
            + "    - before: 2h\n"
            + "      command: \"alert Proxy restart in 2 hours for updates.\"\n"
            + "    - before: 30m\n"
            + "      command: \"alert Proxy restart in 30 minutes for updates.\"\n"
            + "    - before: 5m\n"
            + "      command: \"alert Proxy restart in 5 minutes for updates.\"\n"
            + "    - before: 1m\n"
            + "      command: \"alert Proxy restart in 1 minute for updates.\"\n";
    }
}
