package agent.analysis;

import agent.model.JfrEvent;
import agent.model.LineHotspot;
import agent.model.LiveLogEvent;
import agent.model.MethodSpan;
import agent.model.RequestTrace;
import agent.profiling.asyncprofiler.AsyncProfilerController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Correlates one completed request trace with nearby JVM, log, and native
 * profiler evidence. Trace data is request-exact; JFR/log/native data is
 * time-window/process-wide evidence and is labeled that way in the response.
 */
public final class RequestInvestigationAnalyzer {

    private static final int MAX_FINDINGS = 10;
    private static final int MAX_HOTSPOTS = 12;
    private static final int MAX_EXTERNAL_SPANS = 10;

    private RequestInvestigationAnalyzer() {
    }

    public static RequestInvestigation investigate(
            RequestTrace trace,
            List<RequestTrace> recentTraces,
            List<JfrEvent> jfrEvents,
            List<LiveLogEvent> logEvents,
            AsyncProfilerController.Status asyncStatus,
            AsyncProfilerController.CollapsedSnapshot asyncSnapshot,
            int contextWindowMs,
            int eventLimit,
            int stackLimit,
            long nowMs) {
        if (trace == null) {
            return RequestInvestigation.unavailable("Trace is unavailable.");
        }

        TimeWindow window = timeWindow(trace, contextWindowMs);
        TraceInsightAnalyzer.TraceExplanation explanation =
            TraceInsightAnalyzer.explain(trace);
        TraceInsightAnalyzer.TraceComparison comparison =
            TraceInsightAnalyzer.compare(trace, recentTraces);

        List<InvestigationFinding> findings = new ArrayList<>();
        addTraceFindings(explanation, findings);

        JfrCorrelation jfr = correlateJfr(jfrEvents, window, eventLimit, findings);
        LogCorrelation logs = correlateLogs(logEvents, window, eventLimit, findings);
        AsyncCorrelation async = correlateAsync(asyncStatus, asyncSnapshot,
            trace, window, stackLimit, nowMs, findings);
        List<Hotspot> hotspots = hotspots(trace, explanation);
        List<ExternalSpan> externalSpans = externalSpans(trace);
        List<TimelineItem> timeline = timeline(window, jfr.events(), logs.events());

        return new RequestInvestigation(
            true,
            "",
            trace.traceId(),
            trace.method(),
            trace.path(),
            summary(explanation, comparison, jfr, logs, async),
            explanation.dominantSignal(),
            correlationConfidence(jfr, logs, async),
            window,
            explanation,
            comparison,
            bound(findings, MAX_FINDINGS),
            hotspots,
            externalSpans,
            jfr,
            logs,
            async,
            timeline
        );
    }

    private static TimeWindow timeWindow(RequestTrace trace, int contextWindowMs) {
        long requestEndMs = trace.timestampMs();
        long requestDurationMs = Math.max(1L, trace.totalWallNs() / 1_000_000L);
        long requestStartMs = Math.max(0L, requestEndMs - requestDurationMs);
        long context = Math.max(0L, contextWindowMs);
        return new TimeWindow(requestStartMs, requestEndMs, context, context,
            Math.max(0L, requestStartMs - context), requestEndMs + context);
    }

    private static void addTraceFindings(
            TraceInsightAnalyzer.TraceExplanation explanation,
            List<InvestigationFinding> findings) {
        if (explanation == null) return;
        for (TraceInsightAnalyzer.TraceIssue issue : explanation.issues()) {
            findings.add(new InvestigationFinding(issue.severity(), issue.category(),
                issue.title(), issue.detail(), "request-trace",
                issue.metricLabel(), issue.metricValue(), issue.percentOfTrace(),
                "exact-request"));
        }
    }

    private static JfrCorrelation correlateJfr(List<JfrEvent> source,
                                               TimeWindow window,
                                               int eventLimit,
                                               List<InvestigationFinding> findings) {
        List<JfrEventView> events = new ArrayList<>();
        Map<String, Long> categoryCounts = new LinkedHashMap<>();
        long totalDurationMs = 0L;
        long gcDurationMs = 0L;
        long waitDurationMs = 0L;
        int exceptionCount = 0;

        for (JfrEvent event : source == null ? List.<JfrEvent>of() : source) {
            if (!within(event.timestampMs(), window)) continue;
            String category = normalized(event.category());
            categoryCounts.merge(category, 1L, Long::sum);
            totalDurationMs += Math.max(0L, event.durationMs());
            if ("gc".equals(category)) {
                gcDurationMs += Math.max(0L, event.durationMs());
            }
            if ("thread".equals(category) || "lock".equals(category)) {
                waitDurationMs += Math.max(0L, event.durationMs());
            }
            if ("exception".equals(category)) {
                exceptionCount++;
            }
            events.add(new JfrEventView(event.timestampMs(), event.eventType(),
                category, event.name(), event.durationMs(), event.threadName(),
                event.message()));
        }
        events.sort(Comparator.comparingLong(JfrEventView::timestampMs));
        boolean limited = events.size() > eventLimit;
        events = limited ? new ArrayList<>(events.subList(0, eventLimit)) : events;

        if (gcDurationMs > 0L) {
            findings.add(new InvestigationFinding("warn", "jfr-gc",
                "GC activity overlapped the request window",
                "JFR captured " + gcDurationMs + " ms of GC activity near this request.",
                "time-window", "gc ms", gcDurationMs, 0.0, "time-window"));
        }
        if (waitDurationMs > 0L) {
            findings.add(new InvestigationFinding("warn", "jfr-wait",
                "Thread waiting or lock activity overlapped the request window",
                "JFR captured " + waitDurationMs + " ms of thread/lock activity near this request.",
                "time-window", "wait ms", waitDurationMs, 0.0, "time-window"));
        }
        if (exceptionCount > 0) {
            findings.add(new InvestigationFinding("crit", "jfr-exception",
                "Exceptions occurred near the request",
                "JFR captured " + exceptionCount + " exception event(s) inside the correlation window.",
                "time-window", "exceptions", exceptionCount, 0.0, "time-window"));
        }

        return new JfrCorrelation("time-window", categoryCounts.values().stream()
            .mapToLong(Long::longValue).sum(), totalDurationMs, categoryCounts,
            limited, events);
    }

    private static LogCorrelation correlateLogs(List<LiveLogEvent> source,
                                                TimeWindow window,
                                                int eventLimit,
                                                List<InvestigationFinding> findings) {
        List<LogEventView> events = new ArrayList<>();
        int warnCount = 0;
        int errorCount = 0;
        for (LiveLogEvent event : source == null ? List.<LiveLogEvent>of() : source) {
            if (!within(event.timestampMs(), window)) continue;
            String level = normalized(event.level()).toUpperCase(Locale.ROOT);
            boolean error = "ERROR".equals(level) || "SEVERE".equals(level)
                || (event.throwable() != null && !event.throwable().isBlank());
            boolean warn = "WARN".equals(level) || "WARNING".equals(level);
            if (error) errorCount++;
            if (warn) warnCount++;
            events.add(new LogEventView(event.timestampMs(), event.kind(),
                event.source(), level, event.loggerName(), event.threadName(),
                event.message(), error ? "crit" : warn ? "warn" : "info"));
        }
        events.sort(Comparator.comparingLong(LogEventView::timestampMs));
        boolean limited = events.size() > eventLimit;
        events = limited ? new ArrayList<>(events.subList(0, eventLimit)) : events;

        if (errorCount > 0) {
            findings.add(new InvestigationFinding("crit", "logs-error",
                "Error logs occurred near the request",
                errorCount + " error log event(s) were emitted inside the correlation window.",
                "time-window", "errors", errorCount, 0.0, "time-window"));
        } else if (warnCount > 0) {
            findings.add(new InvestigationFinding("warn", "logs-warn",
                "Warning logs occurred near the request",
                warnCount + " warning log event(s) were emitted inside the correlation window.",
                "time-window", "warnings", warnCount, 0.0, "time-window"));
        }

        return new LogCorrelation("time-window", events.size(), warnCount,
            errorCount, limited, events);
    }

    private static AsyncCorrelation correlateAsync(
            AsyncProfilerController.Status status,
            AsyncProfilerController.CollapsedSnapshot snapshot,
            RequestTrace trace,
            TimeWindow window,
            int stackLimit,
            long nowMs,
            List<InvestigationFinding> findings) {
        AsyncProfilerController.CollapsedSnapshot safeSnapshot =
            snapshot == null ? AsyncProfilerController.CollapsedSnapshot.empty() : snapshot;
        long totalSamples = Math.max(0L, safeSnapshot.root().samples);
        Set<String> traceFrames = traceFrameTokens(trace.root());
        List<AsyncStackView> stacks = new ArrayList<>();
        for (AsyncProfilerController.CollapsedStack stack : safeSnapshot.stacks()) {
            if (stacks.size() >= Math.max(1, stackLimit)) break;
            String topFrame = topFrame(stack.frames());
            String match = matchedTraceFrame(stack.frames(), traceFrames);
            stacks.add(new AsyncStackView(stack.stack(), stack.samples(),
                percent(stack.samples(), Math.max(1L, totalSamples)), topFrame,
                !match.isBlank(), match));
        }

        boolean configured = status != null && status.configured();
        boolean available = status != null && status.available();
        boolean running = status != null && status.running();
        long startMs = status == null ? 0L : status.startedAtMs();
        long endMs = status == null ? 0L : status.stoppedAtMs();
        if (running) endMs = nowMs;
        boolean overlaps = startMs > 0L && endMs >= window.fromMs()
            && startMs <= window.toMs();
        String correlation = overlaps ? "overlapping-process-profile"
            : totalSamples > 0L ? "latest-process-profile" : "unavailable";

        if (!stacks.isEmpty() && stacks.get(0).percentOfProfile() >= 20.0) {
            AsyncStackView top = stacks.get(0);
            String scope = overlaps
                ? "The native profile overlaps this request window."
                : "The native profile is the latest process-wide snapshot and may not overlap this request.";
            findings.add(new InvestigationFinding("info", "async-profiler",
                "Native profiler hotspot is concentrated",
                scope + " Top stack: " + top.topFrame() + " at "
                    + rounded(top.percentOfProfile()) + "% of samples.",
                correlation, "samples", top.samples(),
                top.percentOfProfile(), correlation));
        }
        if (stacks.stream().anyMatch(AsyncStackView::matchedTraceFrame)) {
            findings.add(new InvestigationFinding("info", "async-trace-match",
                "Native stack matches traced application frames",
                "At least one native profiler stack contains a class or method from the selected request trace.",
                correlation, "matches", 1L, 0.0, correlation));
        }

        return new AsyncCorrelation(configured, available, running,
            status == null ? "" : status.event(),
            status == null ? "" : status.platform(),
            startMs, endMs, overlaps, correlation,
            status == null ? 0L : status.sampleCount(), totalSamples,
            safeSnapshot.stackCount(), safeSnapshot.truncated(),
            safeSnapshot.skippedLines(), stacks);
    }

    private static List<Hotspot> hotspots(
            RequestTrace trace,
            TraceInsightAnalyzer.TraceExplanation explanation) {
        List<Hotspot> result = new ArrayList<>();
        addSpanHotspot(result, "self-wall", explanation.topSelfWallSpan(), "selfWallNs");
        addSpanHotspot(result, "self-cpu", explanation.topSelfCpuSpan(), "selfCpuNs");
        addSpanHotspot(result, "allocation", explanation.topSelfAllocSpan(), "selfAllocBytes");
        addSpanHotspot(result, "external", explanation.topExternalSpan(), "wallNs");
        addLineHotspot(result, explanation.topLine(), trace.totalWallNs());
        addSampledLineHotspots(result, trace);
        result.sort(Comparator
            .comparingLong(RequestInvestigationAnalyzer::hotspotScore)
            .reversed());
        return dedupeHotspots(result, MAX_HOTSPOTS);
    }

    private static void addSpanHotspot(List<Hotspot> result, String category,
                                       TraceInsightAnalyzer.TraceSpanSummary span,
                                       String metric) {
        if (span == null) return;
        long value = switch (metric) {
            case "selfCpuNs" -> span.selfCpuNs();
            case "selfAllocBytes" -> span.selfAllocBytes();
            case "wallNs" -> span.wallNs();
            default -> span.selfWallNs();
        };
        result.add(new Hotspot(category, span.label(), span.className(),
            span.methodName(), "", value, span.wallNs(), span.selfWallNs(),
            span.cpuNs(), span.selfCpuNs(), span.allocBytes(),
            span.selfAllocBytes(), 0L, span.percentOfTrace()));
    }

    private static void addLineHotspot(List<Hotspot> result,
                                       TraceInsightAnalyzer.TraceLineSummary line,
                                       long totalWallNs) {
        if (line == null) return;
        result.add(new Hotspot("line-self", line.label(), line.className(),
            line.methodName(), sourceLocation(line.className(), line.lineNumber()),
            line.selfWallNs(), line.wallNs(), line.selfWallNs(),
            line.cpuNs(), line.selfCpuNs(), line.allocatedBytes(),
            line.allocatedBytes(), line.hits(),
            percent(line.selfWallNs(), Math.max(1L, totalWallNs))));
    }

    private static void addSampledLineHotspots(List<Hotspot> result,
                                               RequestTrace trace) {
        for (LineHotspot line : trace.lineHotspots()) {
            result.add(new Hotspot("sampled-line", lineLabel(line),
                line.className(), line.methodName(),
                sourceLocation(line.className(), line.lineNumber()),
                Math.max(line.estimatedWallNs(), line.allocatedBytes()),
                line.estimatedWallNs(), line.estimatedWallNs(),
                line.estimatedCpuNs(), line.estimatedCpuNs(),
                line.allocatedBytes(), line.allocatedBytes(),
                line.samples(), percent(line.estimatedWallNs(),
                    Math.max(1L, trace.totalWallNs()))));
        }
    }

    private static List<ExternalSpan> externalSpans(RequestTrace trace) {
        List<ExternalSpan> spans = new ArrayList<>();
        collectExternalSpans(trace.root(), trace.totalWallNs(), spans);
        spans.sort(Comparator.comparingLong(ExternalSpan::wallNs).reversed());
        return bound(spans, MAX_EXTERNAL_SPANS);
    }

    private static void collectExternalSpans(MethodSpan span, long totalWallNs,
                                             List<ExternalSpan> spans) {
        if (span == null) return;
        if (isExternal(span)) {
            spans.add(new ExternalSpan(span.spanKind, span.externalOperation,
                span.externalResource, spanLabel(span), span.wallNs,
                span.selfWallNs, percent(span.wallNs, Math.max(1L, totalWallNs))));
        }
        for (MethodSpan child : span.children) {
            collectExternalSpans(child, totalWallNs, spans);
        }
    }

    private static List<TimelineItem> timeline(TimeWindow window,
                                               List<JfrEventView> jfrEvents,
                                               List<LogEventView> logs) {
        List<TimelineItem> items = new ArrayList<>();
        items.add(new TimelineItem(window.requestStartMs(), "request", "request",
            "Request start", "", 0L, "info"));
        items.add(new TimelineItem(window.requestEndMs(), "request", "request",
            "Request end", "", 0L, "info"));
        for (JfrEventView event : jfrEvents) {
            items.add(new TimelineItem(event.timestampMs(), "jfr",
                event.category(), event.name(), event.message(),
                event.durationMs(), severityForJfr(event.category())));
        }
        for (LogEventView event : logs) {
            items.add(new TimelineItem(event.timestampMs(), "log",
                event.level(), event.loggerName(), event.message(), 0L,
                event.severity()));
        }
        items.sort(Comparator.comparingLong(TimelineItem::timestampMs));
        return items;
    }

    private static String summary(TraceInsightAnalyzer.TraceExplanation explanation,
                                  TraceInsightAnalyzer.TraceComparison comparison,
                                  JfrCorrelation jfr,
                                  LogCorrelation logs,
                                  AsyncCorrelation async) {
        List<String> parts = new ArrayList<>();
        parts.add(explanation.summary());
        if (comparison.peerCount() > 0) {
            parts.add(comparison.summary());
        }
        if (jfr.eventCount() > 0L) {
            parts.add("JFR has " + jfr.eventCount() + " event(s) in the correlation window.");
        }
        if (logs.errorCount() > 0 || logs.warnCount() > 0) {
            parts.add("Logs show " + logs.errorCount() + " error(s) and "
                + logs.warnCount() + " warning(s) near the request.");
        }
        if (async.profileSamples() > 0L) {
            parts.add(async.overlapsWindow()
                ? "The latest native profile overlaps this request window."
                : "A native profile is available, but it is not request-exact.");
        }
        return String.join(" ", parts);
    }

    private static String correlationConfidence(JfrCorrelation jfr,
                                                LogCorrelation logs,
                                                AsyncCorrelation async) {
        boolean hasWindowSignals = jfr.eventCount() > 0L || logs.eventCount() > 0;
        if (async.overlapsWindow() && hasWindowSignals) {
            return "exact-trace-plus-overlapping-jvm-signals";
        }
        if (hasWindowSignals) {
            return "exact-trace-plus-time-window-signals";
        }
        if (async.profileSamples() > 0L) {
            return "exact-trace-plus-latest-process-profile";
        }
        return "exact-trace-only";
    }

    private static Set<String> traceFrameTokens(MethodSpan root) {
        Set<String> tokens = new LinkedHashSet<>();
        collectFrameTokens(root, tokens);
        return tokens;
    }

    private static void collectFrameTokens(MethodSpan span, Set<String> tokens) {
        if (span == null) return;
        if (span.className != null && !span.className.isBlank()
                && !"HTTP".equals(span.className)) {
            tokens.add(span.className);
            tokens.add(shortClass(span.className));
            if (span.methodName != null && !span.methodName.isBlank()) {
                tokens.add(span.className + "." + span.methodName);
                tokens.add(shortClass(span.className) + "." + span.methodName);
            }
        }
        for (MethodSpan child : span.children) {
            collectFrameTokens(child, tokens);
        }
    }

    private static String matchedTraceFrame(List<String> frames, Set<String> traceTokens) {
        for (String frame : frames == null ? List.<String>of() : frames) {
            for (String token : traceTokens) {
                if (!token.isBlank() && frame.contains(token)) {
                    return token;
                }
            }
        }
        return "";
    }

    private static String topFrame(List<String> frames) {
        if (frames == null || frames.isEmpty()) return "";
        return frames.get(frames.size() - 1);
    }

    private static boolean within(long timestampMs, TimeWindow window) {
        return timestampMs >= window.fromMs() && timestampMs <= window.toMs();
    }

    private static String normalized(String value) {
        return value == null || value.isBlank()
            ? "unknown"
            : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isExternal(MethodSpan span) {
        return span != null
            && span.spanKind != null
            && !span.spanKind.isBlank()
            && !"method".equals(span.spanKind)
            && !"request".equals(span.spanKind);
    }

    private static String spanLabel(MethodSpan span) {
        String base = shortClass(span.className) + "." + span.methodName;
        if (span.externalResource != null && !span.externalResource.isBlank()) {
            return base + " " + span.externalResource;
        }
        return base;
    }

    private static String lineLabel(LineHotspot line) {
        return shortClass(line.className()) + "." + line.methodName()
            + " " + sourceLocation(line.className(), line.lineNumber());
    }

    private static String sourceLocation(String className, int lineNumber) {
        return shortClass(className) + ":" + lineNumber;
    }

    private static String shortClass(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return "";
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    private static String severityForJfr(String category) {
        return switch (category) {
            case "gc", "thread", "lock" -> "warn";
            case "exception" -> "crit";
            default -> "info";
        };
    }

    private static long hotspotScore(Hotspot hotspot) {
        return Math.max(Math.max(hotspot.wallNs(), hotspot.cpuNs()),
            Math.max(hotspot.metricValue(), hotspot.allocBytes() / 1024L));
    }

    private static List<Hotspot> dedupeHotspots(List<Hotspot> source, int limit) {
        List<Hotspot> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Hotspot hotspot : source) {
            String key = hotspot.category() + ":" + hotspot.label()
                + ":" + hotspot.sourceLocation();
            if (seen.add(key)) {
                result.add(hotspot);
            }
            if (result.size() >= limit) break;
        }
        return List.copyOf(result);
    }

    private static <T> List<T> bound(List<T> source, int limit) {
        List<T> safe = source == null ? List.of() : source;
        if (safe.size() <= limit) return List.copyOf(safe);
        return List.copyOf(safe.subList(0, limit));
    }

    private static double percent(long value, long total) {
        if (total <= 0L) return 0.0;
        return rounded(100.0 * Math.max(0L, value) / (double) total);
    }

    private static double rounded(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    public record RequestInvestigation(
        boolean available,
        String message,
        String traceId,
        String method,
        String path,
        String summary,
        String dominantSignal,
        String confidence,
        TimeWindow timeWindow,
        TraceInsightAnalyzer.TraceExplanation traceExplanation,
        TraceInsightAnalyzer.TraceComparison traceComparison,
        List<InvestigationFinding> findings,
        List<Hotspot> hotspots,
        List<ExternalSpan> externalSpans,
        JfrCorrelation jfr,
        LogCorrelation logs,
        AsyncCorrelation asyncProfiler,
        List<TimelineItem> timeline
    ) {
        static RequestInvestigation unavailable(String message) {
            return new RequestInvestigation(false, message, "", "", "", message,
                "unavailable", "none", new TimeWindow(0L, 0L, 0L, 0L, 0L, 0L),
                TraceInsightAnalyzer.explain(null),
                TraceInsightAnalyzer.TraceComparison.empty(message),
                List.of(), List.of(), List.of(),
                new JfrCorrelation("time-window", 0L, 0L, Map.of(), false, List.of()),
                new LogCorrelation("time-window", 0, 0, 0, false, List.of()),
                AsyncCorrelation.empty(), List.of());
        }
    }

    public record TimeWindow(long requestStartMs,
                             long requestEndMs,
                             long contextBeforeMs,
                             long contextAfterMs,
                             long fromMs,
                             long toMs) {
    }

    public record InvestigationFinding(String severity,
                                       String category,
                                       String title,
                                       String detail,
                                       String evidenceSource,
                                       String metricLabel,
                                       long metricValue,
                                       double percentOfTrace,
                                       String correlation) {
    }

    public record Hotspot(String category,
                          String label,
                          String className,
                          String methodName,
                          String sourceLocation,
                          long metricValue,
                          long wallNs,
                          long selfWallNs,
                          long cpuNs,
                          long selfCpuNs,
                          long allocBytes,
                          long selfAllocBytes,
                          long samples,
                          double percentOfTrace) {
    }

    public record ExternalSpan(String kind,
                               String operation,
                               String resource,
                               String label,
                               long wallNs,
                               long selfWallNs,
                               double percentOfTrace) {
    }

    public record JfrCorrelation(String correlation,
                                 long eventCount,
                                 long totalDurationMs,
                                 Map<String, Long> categoryCounts,
                                 boolean limited,
                                 List<JfrEventView> events) {
    }

    public record JfrEventView(long timestampMs,
                               String eventType,
                               String category,
                               String name,
                               long durationMs,
                               String threadName,
                               String message) {
    }

    public record LogCorrelation(String correlation,
                                 int eventCount,
                                 int warnCount,
                                 int errorCount,
                                 boolean limited,
                                 List<LogEventView> events) {
    }

    public record LogEventView(long timestampMs,
                               String kind,
                               String source,
                               String level,
                               String loggerName,
                               String threadName,
                               String message,
                               String severity) {
    }

    public record AsyncCorrelation(boolean configured,
                                   boolean available,
                                   boolean running,
                                   String event,
                                   String platform,
                                   long profileStartMs,
                                   long profileEndMs,
                                   boolean overlapsWindow,
                                   String correlation,
                                   long sampleCount,
                                   long profileSamples,
                                   int stackCount,
                                   boolean truncated,
                                   int skippedLines,
                                   List<AsyncStackView> stacks) {
        static AsyncCorrelation empty() {
            return new AsyncCorrelation(false, false, false, "", "", 0L, 0L,
                false, "unavailable", 0L, 0L, 0, false, 0, List.of());
        }
    }

    public record AsyncStackView(String stack,
                                 long samples,
                                 double percentOfProfile,
                                 String topFrame,
                                 boolean matchedTraceFrame,
                                 String matchedFrame) {
    }

    public record TimelineItem(long timestampMs,
                               String kind,
                               String category,
                               String title,
                               String detail,
                               long durationMs,
                               String severity) {
    }
}
