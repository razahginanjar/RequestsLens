package agent.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

final class SourceCodeService {

    static final long MAX_SOURCE_FILE_BYTES = 1_048_576L;

    private static final Pattern SAFE_CLASS_NAME =
        Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*");

    SourceLookup lookup(String sourceRoots, String className, int lineNumber,
                        int contextLines) {
        if (!isSafeClassName(className)) {
            return SourceLookup.unavailable(className, lineNumber,
                "Class name is invalid");
        }
        if (lineNumber < 1) {
            return SourceLookup.unavailable(className, lineNumber,
                "Line number must be greater than zero");
        }

        String relativePath = outerClassName(className).replace('.', '/') + ".java";
        for (Path root : roots(sourceRoots)) {
            SourceLookup lookup = lookupInRoot(root, relativePath, className,
                lineNumber, contextLines);
            if (lookup.sourceAvailable()) {
                return lookup;
            }
        }
        return SourceLookup.unavailable(className, lineNumber,
            "Source file was not found in configured source roots");
    }

    static boolean isSafeClassName(String className) {
        return className != null
            && className.length() <= 256
            && SAFE_CLASS_NAME.matcher(className).matches()
            && !className.contains("..");
    }

    static int rootCount(String sourceRoots) {
        return roots(sourceRoots).size();
    }

    static String outerClassName(String className) {
        if (className == null) return "";
        int dollar = className.indexOf('$');
        return dollar > 0 ? className.substring(0, dollar) : className;
    }

    private SourceLookup lookupInRoot(Path root, String relativePath,
                                      String className, int lineNumber,
                                      int contextLines) {
        Path source = root.resolve(relativePath).normalize();
        if (!source.startsWith(root) || !Files.isRegularFile(source)) {
            return SourceLookup.unavailable(className, lineNumber,
                "Source file was not found in configured source roots");
        }

        try {
            long size = Files.size(source);
            if (size > MAX_SOURCE_FILE_BYTES) {
                return SourceLookup.unavailable(className, lineNumber,
                    "Source file is above the profiler source view size limit");
            }

            List<String> allLines = Files.readAllLines(source, StandardCharsets.UTF_8);
            if (lineNumber > allLines.size()) {
                return SourceLookup.unavailable(className, lineNumber,
                    "Line number is outside the source file");
            }

            int context = Math.max(0, contextLines);
            int start = Math.max(1, lineNumber - context);
            int end = Math.min(allLines.size(), lineNumber + context);
            List<SourceLine> lines = new ArrayList<>();
            for (int i = start; i <= end; i++) {
                lines.add(new SourceLine(i, allLines.get(i - 1), i == lineNumber));
            }

            return new SourceLookup(true, className, source.getFileName().toString(),
                toApiPath(root.relativize(source)), lineNumber, start, end,
                allLines.size(), lines, "");
        } catch (IOException e) {
            return SourceLookup.unavailable(className, lineNumber,
                "Source file could not be read");
        }
    }

    private static List<Path> roots(String sourceRoots) {
        List<Path> roots = new ArrayList<>();
        if (sourceRoots == null || sourceRoots.isBlank()) return roots;
        for (String rawRoot : sourceRoots.split(",")) {
            String value = rawRoot.trim();
            if (value.isBlank()) continue;
            try {
                Path root = Path.of(value);
                if (!root.isAbsolute()) {
                    root = Path.of("").toAbsolutePath().resolve(root);
                }
                root = root.normalize();
                if (Files.isDirectory(root)) {
                    roots.add(root);
                }
            } catch (InvalidPathException ignored) {
                // Invalid source roots are ignored; startup config still reports the count.
            }
        }
        return roots;
    }

    private static String toApiPath(Path path) {
        return path.toString().replace('\\', '/');
    }

    record SourceLookup(
        boolean sourceAvailable,
        String className,
        String fileName,
        String sourcePath,
        int requestedLine,
        int startLine,
        int endLine,
        int totalLines,
        List<SourceLine> lines,
        String message
    ) {
        static SourceLookup unavailable(String className, int requestedLine,
                                        String message) {
            return new SourceLookup(false, className, "", "", requestedLine,
                0, 0, 0, List.of(), message);
        }
    }

    record SourceLine(int lineNumber, String text, boolean highlight) {}
}
