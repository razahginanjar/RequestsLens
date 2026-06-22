package agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

final class AgentConfigSourceLoader {

    private static final Logger log =
        Logger.getLogger(AgentConfigSourceLoader.class.getName());
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    private static final String CONFIG_PATH_PROPERTY = "profiler.config.path";
    private static final String[] AUTO_CONFIG_NAMES = {
        "requestlens-agent.yaml",
        "requestlens-agent.yml",
        "requestlens.yaml",
        "requestlens.yml"
    };

    private AgentConfigSourceLoader() {}

    static void loadPropertiesFile(Properties props) {
        try (InputStream in = new FileInputStream("jvm-profiler.properties")) {
            props.load(in);
            log.info("Loaded jvm-profiler.properties");
        } catch (IOException e) {
            log.fine("No jvm-profiler.properties found - using defaults");
        }
    }

    static AgentConfigFileLoad loadYamlConfig(Properties props, String agentArgs,
                                              String[] knownPropertyKeys) {
        String explicitPath = explicitConfigPath(agentArgs);
        if (!explicitPath.isBlank()) {
            Path path = Path.of(explicitPath).toAbsolutePath().normalize();
            return loadYamlConfigFile(props, path, false, true,
                knownPropertyKeys);
        }

        Path discovered = discoverYamlConfig(Path.of("").toAbsolutePath().normalize());
        if (discovered == null) {
            log.fine("No RequestLens YAML config found in working directory - using defaults and inline args");
            return AgentConfigFileLoad.none();
        }
        return loadYamlConfigFile(props, discovered, true, false,
            knownPropertyKeys);
    }

    static Path discoverYamlConfig(Path directory) {
        if (directory == null) return null;
        for (String name : AUTO_CONFIG_NAMES) {
            Path candidate = directory.resolve(name).normalize();
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return null;
    }

    static void applySystemProperties(Properties props, String[] knownPropertyKeys) {
        for (String key : knownPropertyKeys) {
            String val = System.getProperty(key);
            if (val != null) props.setProperty(key, val);
        }
    }

    private static String explicitConfigPath(String agentArgs) {
        String systemPath = System.getProperty(CONFIG_PATH_PROPERTY, "").trim();
        if (!systemPath.isBlank()) return systemPath;
        if (agentArgs == null || agentArgs.isBlank()) return "";
        for (String pair : agentArgs.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) continue;
            String key = kv[0].trim();
            if (key.equals("config")
                    || key.equals("config.file")
                    || key.equals("config.path")
                    || key.equals("profiler.config.path")) {
                return kv[1].trim();
            }
        }
        return "";
    }

    private static AgentConfigFileLoad loadYamlConfigFile(Properties props,
                                                          Path path,
                                                          boolean autoDiscovered,
                                                          boolean explicit,
                                                          String[] knownPropertyKeys) {
        if (!Files.isRegularFile(path)) {
            if (explicit) {
                log.warning("RequestLens YAML config not found: " + path
                    + " - using defaults, legacy properties, inline args, and system properties");
            }
            return new AgentConfigFileLoad(false, path.toString(), autoDiscovered);
        }
        try (InputStream in = Files.newInputStream(path)) {
            JsonNode root = YAML_MAPPER.readTree(in);
            Map<String, String> values = new LinkedHashMap<>();
            flattenYaml(root, "", values);
            int applied = 0;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                String propertyKey = yamlPathToProperty(entry.getKey(), knownPropertyKeys);
                if (propertyKey == null) {
                    log.warning("Ignoring unknown RequestLens YAML key: " + entry.getKey());
                    continue;
                }
                props.setProperty(propertyKey, entry.getValue());
                applied++;
            }
            log.info("RequestLens YAML config loaded: " + path + " (" + applied
                + " setting" + (applied == 1 ? "" : "s") + ")");
            return new AgentConfigFileLoad(true, path.toString(), autoDiscovered);
        } catch (IOException | RuntimeException e) {
            log.warning("Failed to load RequestLens YAML config " + path + ": "
                + e.getClass().getSimpleName() + ": " + e.getMessage());
            return new AgentConfigFileLoad(false, path.toString(), autoDiscovered);
        }
    }

    private static void flattenYaml(JsonNode node, String path,
                                    Map<String, String> values) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String childPath = path.isBlank()
                    ? field.getKey()
                    : path + "." + field.getKey();
                flattenYaml(field.getValue(), childPath, values);
            }
            return;
        }
        if (node.isArray()) {
            StringBuilder joined = new StringBuilder();
            for (JsonNode item : node) {
                if (item == null || item.isNull()) continue;
                if (item.isContainerNode()) {
                    log.warning("Ignoring nested object/array item under RequestLens YAML key: " + path);
                    continue;
                }
                String value = textValue(item).trim();
                if (value.isBlank()) continue;
                if (joined.length() > 0) joined.append(',');
                joined.append(value);
            }
            if (joined.length() > 0) {
                values.put(path, joined.toString());
            }
            return;
        }
        String value = textValue(node).trim();
        if (!value.isBlank()) {
            values.put(path, value);
        }
    }

    private static String textValue(JsonNode node) {
        String value = node.asText();
        return value == null ? "" : value;
    }

    private static String yamlPathToProperty(String path, String[] knownPropertyKeys) {
        String normalized = normalizeConfigPath(path);
        String canonical = canonicalPropertyByNormalizedPath(knownPropertyKeys).get(normalized);
        if (canonical != null) return canonical;
        return yamlAliases().get(normalized);
    }

    private static Map<String, String> canonicalPropertyByNormalizedPath(
            String[] knownPropertyKeys) {
        Map<String, String> keys = new LinkedHashMap<>();
        for (String key : knownPropertyKeys) {
            keys.put(normalizeConfigPath(key), key);
        }
        return keys;
    }

    private static Map<String, String> yamlAliases() {
        Map<String, String> aliases = new LinkedHashMap<>();
        alias(aliases, "http.port", "profiler.http.port");
        alias(aliases, "http.host", "profiler.http.host");
        alias(aliases, "http.authtoken", "profiler.auth.token");
        alias(aliases, "auth.token", "profiler.auth.token");
        alias(aliases, "http.auth.token", "profiler.auth.token");
        alias(aliases, "http.cors.enabled", "profiler.http.cors.enabled");
        alias(aliases, "http.cors.origins", "profiler.http.cors.allowed.origins");
        alias(aliases, "http.cors.allowedorigins", "profiler.http.cors.allowed.origins");
        alias(aliases, "cors.enabled", "profiler.http.cors.enabled");
        alias(aliases, "cors.origins", "profiler.http.cors.allowed.origins");

        alias(aliases, "sampling.intervalms", "profiler.sampling.interval.ms");
        alias(aliases, "intervalms", "profiler.sampling.interval.ms");
        alias(aliases, "cpu.intervalms", "profiler.cpu.sampling.interval.ms");
        alias(aliases, "sampling.adaptive.enabled", "profiler.sampling.adaptive.enabled");
        alias(aliases, "sampling.adaptive.maxrps", "profiler.sampling.adaptive.max.rps");
        alias(aliases, "sampling.adaptive.multiplier", "profiler.sampling.adaptive.multiplier");
        alias(aliases, "sampling.profiler.enabled", "profiler.sampling.profiler.enabled");
        alias(aliases, "sampling.profiler.intervalms", "profiler.sampling.profiler.interval.ms");

        alias(aliases, "persistence.enabled", "profiler.persistence.enabled");
        alias(aliases, "persistence.path", "profiler.persistence.path");
        alias(aliases, "persistence.retentiondays", "profiler.persistence.retention.days");

        alias(aliases, "alert.gcoverheadthreshold", "profiler.alert.gc.overhead.threshold");
        alias(aliases, "alert.webhookurl", "profiler.alert.webhook.url");
        alias(aliases, "leak.windowms", "profiler.leak.detection.window.ms");
        alias(aliases, "leak.detection.windowms", "profiler.leak.detection.window.ms");

        alias(aliases, "trace.enabled", "profiler.trace.enabled");
        alias(aliases, "trace.packages", "profiler.trace.packages");
        alias(aliases, "trace.samplerate", "profiler.trace.sample.rate");
        alias(aliases, "trace.maxdepth", "profiler.trace.max.depth");
        alias(aliases, "trace.maxspans", "profiler.trace.max.spans");
        alias(aliases, "trace.allocationdetail", "profiler.trace.alloc.detail.enabled");
        alias(aliases, "trace.allocdetail", "profiler.trace.alloc.detail.enabled");

        alias(aliases, "line.enabled", "profiler.line.enabled");
        alias(aliases, "line.mode", "profiler.line.mode");
        alias(aliases, "line.packages", "profiler.line.packages");
        alias(aliases, "line.intervalms", "profiler.line.sample.interval.ms");
        alias(aliases, "line.allocation", "profiler.line.alloc.enabled");
        alias(aliases, "line.allocationenabled", "profiler.line.alloc.enabled");
        alias(aliases, "line.maxsamples", "profiler.line.max.samples.per.trace");
        alias(aliases, "line.maxsamplespertrace", "profiler.line.max.samples.per.trace");
        alias(aliases, "line.maxlines", "profiler.line.max.lines.per.trace");
        alias(aliases, "line.maxlinespertrace", "profiler.line.max.lines.per.trace");
        alias(aliases, "line.maxpayloadbytes", "profiler.line.max.trace.payload.bytes");
        alias(aliases, "line.maxtracepayloadbytes", "profiler.line.max.trace.payload.bytes");

        alias(aliases, "source.enabled", "profiler.source.enabled");
        alias(aliases, "source.roots", "profiler.source.roots");
        alias(aliases, "source.contextlines", "profiler.source.context.lines");

        alias(aliases, "debug.enabled", "profiler.debug.enabled");
        alias(aliases, "debug.captureargs", "profiler.debug.capture.args");
        alias(aliases, "debug.capturereturn", "profiler.debug.capture.return");
        alias(aliases, "debug.maxsnapshots", "profiler.debug.max.snapshots.per.trace");
        alias(aliases, "debug.maxsnapshotspertrace", "profiler.debug.max.snapshots.per.trace");
        alias(aliases, "debug.maxsnapshotsperspan", "profiler.debug.max.snapshots.per.span");
        alias(aliases, "debug.maxvaluelength", "profiler.debug.max.value.length");

        alias(aliases, "logs.enabled", "profiler.logs.enabled");
        alias(aliases, "logs.maxevents", "profiler.logs.max.events");
        alias(aliases, "jfr.enabled", "profiler.jfr.enabled");
        alias(aliases, "jfr.maxevents", "profiler.jfr.max.events");
        alias(aliases, "jfr.thresholdms", "profiler.jfr.threshold.ms");

        alias(aliases, "async.enabled", "profiler.async.enabled");
        alias(aliases, "async.event", "profiler.async.event");
        alias(aliases, "async.interval", "profiler.async.interval");
        alias(aliases, "async.durationseconds", "profiler.async.duration.seconds");
        alias(aliases, "async.maxcollapsedlines", "profiler.async.max.collapsed.lines");
        alias(aliases, "async.libpath", "profiler.async.lib.path");
        alias(aliases, "asyncprofiler.enabled", "profiler.async.enabled");
        alias(aliases, "asyncprofiler.event", "profiler.async.event");
        alias(aliases, "asyncprofiler.interval", "profiler.async.interval");
        alias(aliases, "asyncprofiler.durationseconds", "profiler.async.duration.seconds");
        alias(aliases, "asyncprofiler.maxcollapsedlines", "profiler.async.max.collapsed.lines");
        alias(aliases, "asyncprofiler.libpath", "profiler.async.lib.path");
        return aliases;
    }

    private static void alias(Map<String, String> aliases, String yamlPath,
                              String propertyKey) {
        aliases.put(normalizeConfigPath(yamlPath), propertyKey);
    }

    private static String normalizeConfigPath(String path) {
        if (path == null || path.isBlank()) return "";
        String[] parts = path.split("\\.");
        StringBuilder normalized = new StringBuilder();
        for (String part : parts) {
            String token = normalizeConfigToken(part);
            if (token.isBlank()) continue;
            if (normalized.length() > 0) normalized.append('.');
            normalized.append(token);
        }
        return normalized.toString();
    }

    private static String normalizeConfigToken(String token) {
        if (token == null) return "";
        return token.trim().toLowerCase(Locale.ROOT)
            .replace("-", "")
            .replace("_", "");
    }
}
