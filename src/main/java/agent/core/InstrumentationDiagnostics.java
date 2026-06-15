package agent.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runtime counters for Byte Buddy instrumentation decisions.
 *
 * <p>The goal is operational diagnosis: when request traces are empty, the
 * dashboard can show whether application classes were discovered, transformed,
 * already loaded, missing line metadata, or failed during transformation.
 */
public final class InstrumentationDiagnostics {

    private static final int RECENT_LIMIT = 20;

    private final Set<String> discoveredTraceClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> alreadyLoadedTraceClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> transformedTraceClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> transformedFrameworkClasses = ConcurrentHashMap.newKeySet();
    private final Set<String> lineNumberClasses = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, Integer> transformedTraceMethods = new ConcurrentHashMap<>();
    private final AtomicBoolean lineNumberDiagnosticsEnabled = new AtomicBoolean();

    private final Object recentLock = new Object();
    private final ArrayDeque<String> recentTransformedTraceClasses = new ArrayDeque<>();
    private final ArrayDeque<InstrumentationError> recentErrors = new ArrayDeque<>();

    public void enableLineNumberDiagnostics() {
        lineNumberDiagnosticsEnabled.set(true);
    }

    public void recordTraceClassDiscovered(String className, boolean alreadyLoaded) {
        if (className == null || className.isBlank()) return;
        discoveredTraceClasses.add(className);
        if (alreadyLoaded) {
            alreadyLoadedTraceClasses.add(className);
        }
    }

    public void recordFrameworkTransformation(String className) {
        if (className == null || className.isBlank()) return;
        transformedFrameworkClasses.add(className);
    }

    public void recordTraceTransformation(String className, boolean alreadyLoaded,
                                          int methodCount) {
        if (className == null || className.isBlank()) return;
        transformedTraceClasses.add(className);
        if (alreadyLoaded) {
            alreadyLoadedTraceClasses.add(className);
        }
        transformedTraceMethods.put(className, Math.max(0, methodCount));
        addRecent(recentTransformedTraceClasses, className);
    }

    public void recordLineNumberClass(String className) {
        if (className == null || className.isBlank()) return;
        lineNumberClasses.add(className);
    }

    public void recordError(String className, Throwable error) {
        String type = className == null || className.isBlank() ? "(unknown)" : className;
        String message = error == null ? "(unknown)" : error.getClass().getSimpleName()
            + (error.getMessage() == null ? "" : ": " + error.getMessage());
        synchronized (recentLock) {
            recentErrors.addFirst(new InstrumentationError(type, message,
                System.currentTimeMillis()));
            while (recentErrors.size() > RECENT_LIMIT) {
                recentErrors.removeLast();
            }
        }
    }

    public Snapshot snapshot() {
        TreeSet<String> missingLineNumbers = missingLineNumberClasses();
        List<String> missingLineNumberExamples = missingLineNumberExamples(missingLineNumbers);
        return new Snapshot(
            discoveredTraceClasses.size(),
            alreadyLoadedTraceClasses.size(),
            transformedTraceClasses.size(),
            transformedFrameworkClasses.size(),
            transformedTraceMethods.values().stream().mapToInt(Integer::intValue).sum(),
            lineNumberDiagnosticsEnabled.get(),
            lineNumberDiagnosticsEnabled.get() ? lineNumberClasses.size() : 0,
            lineNumberDiagnosticsEnabled.get() ? missingLineNumbers.size() : 0,
            lineNumberDiagnosticsEnabled.get() ? missingLineNumberExamples : List.of(),
            recentTransformedTraceClassExamples(),
            recentErrorExamples()
        );
    }

    private List<String> recentTransformedTraceClassExamples() {
        synchronized (recentLock) {
            return List.copyOf(recentTransformedTraceClasses);
        }
    }

    private List<InstrumentationError> recentErrorExamples() {
        synchronized (recentLock) {
            return List.copyOf(recentErrors);
        }
    }

    private TreeSet<String> missingLineNumberClasses() {
        TreeSet<String> missing = new TreeSet<>(transformedTraceClasses);
        missing.removeAll(lineNumberClasses);
        return missing;
    }

    private List<String> missingLineNumberExamples(TreeSet<String> missing) {
        if (missing.isEmpty()) return List.of();
        List<String> examples = new ArrayList<>();
        for (String name : missing) {
            examples.add(name);
            if (examples.size() >= RECENT_LIMIT) break;
        }
        return Collections.unmodifiableList(examples);
    }

    private void addRecent(ArrayDeque<String> target, String value) {
        synchronized (recentLock) {
            target.remove(value);
            target.addFirst(value);
            while (target.size() > RECENT_LIMIT) {
                target.removeLast();
            }
        }
    }

    public record Snapshot(
        int discoveredTraceClasses,
        int alreadyLoadedTraceClasses,
        int transformedTraceClasses,
        int transformedFrameworkClasses,
        int transformedTraceMethods,
        boolean lineNumberDiagnosticsEnabled,
        int lineNumberClasses,
        int classesWithoutLineNumbers,
        List<String> classesWithoutLineNumberExamples,
        List<String> recentTransformedTraceClasses,
        List<InstrumentationError> recentErrors
    ) {
        public Map<String, Object> toMap() {
            return toMap(true);
        }

        public Map<String, Object> toMap(boolean exposeSensitiveDetails) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("discoveredTraceClasses", discoveredTraceClasses);
            response.put("alreadyLoadedTraceClasses", alreadyLoadedTraceClasses);
            response.put("transformedTraceClasses", transformedTraceClasses);
            response.put("transformedFrameworkClasses", transformedFrameworkClasses);
            response.put("transformedTraceMethods", transformedTraceMethods);
            response.put("lineNumberDiagnosticsEnabled", lineNumberDiagnosticsEnabled);
            response.put("lineNumberClasses", lineNumberClasses);
            response.put("classesWithoutLineNumbers", classesWithoutLineNumbers);
            response.put("classesWithoutLineNumberExamples",
                exposeSensitiveDetails ? classesWithoutLineNumberExamples : List.of());
            response.put("recentTransformedTraceClasses",
                exposeSensitiveDetails ? recentTransformedTraceClasses : List.of());
            response.put("recentErrors", exposeSensitiveDetails ? recentErrors : List.of());
            return response;
        }
    }

    public record InstrumentationError(
        String className,
        String message,
        long timestampMs
    ) {}
}
