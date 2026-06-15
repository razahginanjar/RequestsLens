package agent.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Suggests application package prefixes from a jar's class entries.
 *
 * <p>This is intentionally conservative. It ignores Spring Boot loader classes
 * and common dependency packages, then finds the common package prefix of the
 * remaining application classes.
 */
public final class JarPackageDiscovery {

    private static final int TOP_PACKAGE_LIMIT = 10;

    private JarPackageDiscovery() {}

    public static DiscoveryResult discoverRuntime() {
        for (Path candidate : runtimeJarCandidates()) {
            DiscoveryResult result = discover(candidate, "runtime");
            if (result.available()) return result;
        }
        return unavailable("", "runtime", "No runtime jar candidate found in sun.java.command or java.class.path");
    }

    public static DiscoveryResult discover(Path jarPath) {
        return discover(jarPath, "query");
    }

    private static DiscoveryResult discover(Path jarPath, String source) {
        if (jarPath == null) {
            return unavailable("", source, "Jar path is required");
        }
        Path normalized = jarPath.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) {
            return unavailable(normalized.toString(), source, "Jar file was not found");
        }
        if (!normalized.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            return unavailable(normalized.toString(), source, "Path is not a .jar file");
        }

        try (JarFile jar = new JarFile(normalized.toFile())) {
            List<String> classEntries = classEntries(jar);
            Map<String, Integer> packageCounts = new TreeMap<>();
            int classCount = 0;
            for (String entry : classEntries) {
                String packageName = packageName(entry);
                if (packageName.isBlank() || isExcludedPackage(packageName)) continue;
                classCount++;
                packageCounts.merge(packageName, 1, Integer::sum);
            }
            if (packageCounts.isEmpty()) {
                return unavailable(normalized.toString(), source,
                    "No application packages found in jar class entries");
            }
            String suggested = suggestedPackage(packageCounts);
            List<PackageCount> top = topPackages(packageCounts);
            List<String> warnings = new ArrayList<>();
            if (suggested.isBlank()) {
                warnings.add("Could not find a stable common package prefix");
            } else if (suggested.indexOf('.') < 0 && packageCounts.size() > 1) {
                warnings.add("Suggested package is broad; inspect topPackages before using it");
            }
            return new DiscoveryResult(true, normalized.toString(), source, classCount,
                packageCounts.size(), suggested, top, warnings);
        } catch (IOException e) {
            return unavailable(normalized.toString(), source,
                "Failed to read jar: " + e.getMessage());
        }
    }

    private static List<Path> runtimeJarCandidates() {
        List<Path> candidates = new ArrayList<>();
        String command = System.getProperty("sun.java.command", "");
        String first = firstCommandToken(command);
        if (first.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            candidates.add(Path.of(first));
        }
        String classPath = System.getProperty("java.class.path", "");
        if (!classPath.isBlank()) {
            for (String raw : classPath.split(java.io.File.pathSeparator)) {
                if (raw == null || raw.isBlank()) continue;
                String lower = raw.toLowerCase(Locale.ROOT);
                if (lower.endsWith(".jar") && !lower.contains("requestlens-agent")) {
                    Path path = Path.of(raw);
                    if (!candidates.contains(path)) candidates.add(path);
                }
            }
        }
        return candidates;
    }

    private static String firstCommandToken(String command) {
        if (command == null || command.isBlank()) return "";
        String trimmed = command.trim();
        if (trimmed.startsWith("\"")) {
            int end = trimmed.indexOf('"', 1);
            return end > 1 ? trimmed.substring(1, end) : trimmed.substring(1);
        }
        int space = trimmed.indexOf(' ');
        return space < 0 ? trimmed : trimmed.substring(0, space);
    }

    private static List<String> classEntries(JarFile jar) {
        List<String> entries = new ArrayList<>();
        boolean bootClasses = jar.stream()
            .map(JarEntry::getName)
            .anyMatch(name -> name.startsWith("BOOT-INF/classes/") && name.endsWith(".class"));

        jar.stream()
            .map(JarEntry::getName)
            .filter(name -> name.endsWith(".class"))
            .forEach(name -> {
                if (bootClasses) {
                    if (name.startsWith("BOOT-INF/classes/")) {
                        entries.add(name.substring("BOOT-INF/classes/".length()));
                    }
                } else if (!name.startsWith("META-INF/")
                        && !name.startsWith("BOOT-INF/lib/")
                        && !name.startsWith("org/springframework/boot/loader/")) {
                    entries.add(name);
                }
            });
        return entries;
    }

    private static String packageName(String classEntry) {
        String noSuffix = classEntry.substring(0, classEntry.length() - ".class".length());
        int inner = noSuffix.indexOf('$');
        if (inner >= 0) noSuffix = noSuffix.substring(0, inner);
        int slash = noSuffix.lastIndexOf('/');
        if (slash <= 0) return "";
        return noSuffix.substring(0, slash).replace('/', '.');
    }

    private static boolean isExcludedPackage(String packageName) {
        String p = packageName + ".";
        return p.startsWith("agent.")
            || p.startsWith("java.")
            || p.startsWith("javax.")
            || p.startsWith("jakarta.")
            || p.startsWith("jdk.")
            || p.startsWith("sun.")
            || p.startsWith("org.springframework.")
            || p.startsWith("org.apache.catalina.")
            || p.startsWith("org.apache.commons.")
            || p.startsWith("org.apache.http.")
            || p.startsWith("org.apache.logging.")
            || p.startsWith("org.apache.maven.")
            || p.startsWith("org.apache.tomcat.")
            || p.startsWith("org.eclipse.jetty.")
            || p.startsWith("org.slf4j.")
            || p.startsWith("org.sqlite.")
            || p.startsWith("org.objectweb.asm.")
            || p.startsWith("net.bytebuddy.")
            || p.startsWith("io.javalin.")
            || p.startsWith("com.fasterxml.jackson.")
            || p.startsWith("kotlin.")
            || p.startsWith("org.jetbrains.");
    }

    private static String suggestedPackage(Map<String, Integer> packageCounts) {
        List<String> packages = new ArrayList<>(packageCounts.keySet());
        String[] common = packages.get(0).split("\\.");
        int commonLen = common.length;
        for (int i = 1; i < packages.size(); i++) {
            String[] parts = packages.get(i).split("\\.");
            commonLen = Math.min(commonLen, parts.length);
            int j = 0;
            while (j < commonLen && common[j].equals(parts[j])) j++;
            commonLen = j;
            if (commonLen == 0) break;
        }
        if (commonLen > 0) {
            return String.join(".", java.util.Arrays.copyOf(common, commonLen));
        }
        PackageCount top = topPackages(packageCounts).get(0);
        String[] parts = top.packageName().split("\\.");
        int len = Math.min(parts.length, 3);
        return String.join(".", java.util.Arrays.copyOf(parts, len));
    }

    private static List<PackageCount> topPackages(Map<String, Integer> packageCounts) {
        return packageCounts.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey))
            .limit(TOP_PACKAGE_LIMIT)
            .map(e -> new PackageCount(e.getKey(), e.getValue()))
            .toList();
    }

    private static DiscoveryResult unavailable(String jarPath, String source, String message) {
        return new DiscoveryResult(false, jarPath == null ? "" : jarPath, source, 0, 0,
            "", List.of(), List.of(message));
    }

    public record DiscoveryResult(
        boolean available,
        String jarPath,
        String source,
        int classCount,
        int packageCount,
        String suggestedPackage,
        List<PackageCount> topPackages,
        List<String> warnings
    ) {
        public Map<String, Object> toMap(boolean exposeSensitiveDetails) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("available", available);
            response.put("source", source);
            response.put("jarPath", exposeSensitiveDetails ? jarPath : "(redacted)");
            response.put("classCount", classCount);
            response.put("packageCount", packageCount);
            response.put("suggestedPackage", exposeSensitiveDetails ? suggestedPackage : "(redacted)");
            response.put("suggestedTracePackages", exposeSensitiveDetails ? suggestedPackage : "(redacted)");
            response.put("suggestedLinePackages", exposeSensitiveDetails ? suggestedPackage : "(redacted)");
            response.put("topPackages", exposeSensitiveDetails ? topPackages : List.of());
            response.put("warnings", warnings);
            return response;
        }
    }

    public record PackageCount(String packageName, int classCount) {}
}
