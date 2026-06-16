package agent.analysis;

import agent.model.MethodSpan;
import agent.model.RequestTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds human-readable, bounded explanations from completed request traces.
 *
 * <p>This class does not capture new data. It derives request-level hints from
 * the trace tree already stored by the profiler.
 */
public final class TraceInsightAnalyzer {

    private static final int MAX_ISSUES = 6;

    private TraceInsightAnalyzer() {
    }

    public static TraceExplanation explain(RequestTrace trace) {
        if (trace == null) {
            return new TraceExplanation("Trace is unavailable.", "unknown",
                List.of(), null, null, null, null, null);
        }

        SpanAccumulator spans = collectSpans(trace.root());
        TraceSpanSummary topSelfWall = spans.topSelfWall();
        TraceSpanSummary topSelfCpu = spans.topSelfCpu();
        TraceSpanSummary topSelfAlloc = spans.topSelfAlloc();
        TraceSpanSummary topExternal = spans.topExternalWall();
        TraceLineSummary topLine = topLine(trace.root());
        long totalWallNs = Math.max(1L, trace.totalWallNs());
        long totalAllocBytes = Math.max(0L, trace.totalAllocBytes());
        double cpuToWallPercent = percent(trace.totalCpuNs(), totalWallNs);
        double externalWallPercent = percent(spans.externalWallNs(), totalWallNs);

        List<TraceIssue> issues = new ArrayList<>();
        if (trace.truncated()) {
            issues.add(issue("warn", "capture-limit", "Trace capture was capped",
                "Increase trace.max.depth or trace.max.spans for this route if the missing subtree matters.",
                "dropped spans", trace.droppedSpans(), 0.0, null, null));
        }
        if (topExternal != null && externalWallPercent >= 25.0) {
            issues.add(issue("warn", "external", "External dependency time dominates",
                "SQL/HTTP spans account for " + rounded(externalWallPercent) + "% of request wall time.",
                "external wall", spans.externalWallNs(), externalWallPercent, topExternal, null));
        }
        if (topSelfWall != null && topSelfWall.percentOfTrace() >= 25.0) {
            issues.add(issue("info", "self-wall", "Method self time hotspot",
                topSelfWall.label() + " accounts for " + rounded(topSelfWall.percentOfTrace())
                    + "% of request wall time outside traced children.",
                "self wall", topSelfWall.selfWallNs(), topSelfWall.percentOfTrace(),
                topSelfWall, null));
        }
        if (cpuToWallPercent >= 70.0) {
            issues.add(issue("info", "cpu", "CPU-heavy request",
                "Request-thread CPU is " + rounded(cpuToWallPercent) + "% of wall time.",
                "cpu", trace.totalCpuNs(), cpuToWallPercent, topSelfCpu, null));
        } else if (cpuToWallPercent <= 20.0 && trace.totalWallNs() >= 20_000_000L) {
            issues.add(issue("info", "wait", "Mostly waiting, sleeping, or blocking",
                "Request-thread CPU is only " + rounded(cpuToWallPercent)
                    + "% of wall time; check I/O, locks, sleeps, or untraced dependencies.",
                "cpu", trace.totalCpuNs(), cpuToWallPercent, null, null));
        }
        if (topSelfAlloc != null && totalAllocBytes > 0
                && topSelfAlloc.selfAllocBytes() >= 64 * 1024L
                && percent(topSelfAlloc.selfAllocBytes(), totalAllocBytes) >= 20.0) {
            issues.add(issue("info", "allocation", "Allocation hotspot",
                topSelfAlloc.label() + " is the largest self-allocation source in this trace.",
                "self alloc", topSelfAlloc.selfAllocBytes(),
                percent(topSelfAlloc.selfAllocBytes(), totalAllocBytes), topSelfAlloc, null));
        }
        if (topLine != null && topLine.selfWallNs() > 0L) {
            issues.add(issue("info", "line", "Method-line hotspot",
                topLine.label() + " has the highest captured line self time.",
                "line self wall", topLine.selfWallNs(),
                percent(topLine.selfWallNs(), totalWallNs), null, topLine));
        }

        List<TraceIssue> boundedIssues = issues.size() <= MAX_ISSUES
            ? List.copyOf(issues)
            : List.copyOf(issues.subList(0, MAX_ISSUES));
        String dominantSignal = dominantSignal(trace, spans, topSelfWall,
            topSelfAlloc, cpuToWallPercent, externalWallPercent);
        return new TraceExplanation(summaryFor(dominantSignal, trace, topSelfWall,
                topExternal, cpuToWallPercent, externalWallPercent),
            dominantSignal, boundedIssues, topSelfWall, topSelfCpu, topSelfAlloc,
            topExternal, topLine);
    }

    public static TraceComparison compare(RequestTrace trace, List<RequestTrace> recentTraces) {
        if (trace == null) {
            return TraceComparison.empty("Trace is unavailable.");
        }
        List<RequestTrace> peers = new ArrayList<>();
        if (recentTraces != null) {
            for (RequestTrace candidate : recentTraces) {
                if (candidate == null) continue;
                if (trace.traceId().equals(candidate.traceId())) continue;
                if (trace.method().equals(candidate.method())
                        && trace.path().equals(candidate.path())) {
                    peers.add(candidate);
                }
            }
        }
        if (peers.isEmpty()) {
            return TraceComparison.empty("No comparable recent traces for "
                + trace.method() + " " + trace.path() + ".");
        }

        long baselineWallNs = averageLong(peers, RequestTrace::totalWallNs);
        long baselineCpuNs = averageLong(peers, RequestTrace::totalCpuNs);
        long baselineAllocBytes = averageLong(peers, RequestTrace::totalAllocBytes);
        long baselineSpans = averageLong(peers, t -> t.capturedSpans());
        long wallDeltaNs = trace.totalWallNs() - baselineWallNs;
        long cpuDeltaNs = trace.totalCpuNs() - baselineCpuNs;
        long allocDeltaBytes = trace.totalAllocBytes() - baselineAllocBytes;
        double wallDeltaPercent = percentDelta(trace.totalWallNs(), baselineWallNs);
        double cpuDeltaPercent = percentDelta(trace.totalCpuNs(), baselineCpuNs);
        double allocDeltaPercent = percentDelta(trace.totalAllocBytes(), baselineAllocBytes);
        String position = comparisonPosition(wallDeltaPercent);
        String summary = comparisonSummary(position, peers.size(), wallDeltaPercent);

        return new TraceComparison(peers.size(), position, summary,
            baselineWallNs, trace.totalWallNs(), wallDeltaNs, wallDeltaPercent,
            baselineCpuNs, trace.totalCpuNs(), cpuDeltaNs, cpuDeltaPercent,
            baselineAllocBytes, trace.totalAllocBytes(), allocDeltaBytes,
            allocDeltaPercent, baselineSpans, trace.capturedSpans());
    }

    public record TraceExplanation(
        String summary,
        String dominantSignal,
        List<TraceIssue> issues,
        TraceSpanSummary topSelfWallSpan,
        TraceSpanSummary topSelfCpuSpan,
        TraceSpanSummary topSelfAllocSpan,
        TraceSpanSummary topExternalSpan,
        TraceLineSummary topLine
    ) {
    }

    public record TraceIssue(
        String severity,
        String category,
        String title,
        String detail,
        String metricLabel,
        long metricValue,
        double percentOfTrace,
        TraceSpanSummary span,
        TraceLineSummary line
    ) {
    }

    public record TraceComparison(
        int peerCount,
        String position,
        String summary,
        long baselineWallNs,
        long currentWallNs,
        long wallDeltaNs,
        double wallDeltaPercent,
        long baselineCpuNs,
        long currentCpuNs,
        long cpuDeltaNs,
        double cpuDeltaPercent,
        long baselineAllocBytes,
        long currentAllocBytes,
        long allocDeltaBytes,
        double allocDeltaPercent,
        long baselineCapturedSpans,
        int currentCapturedSpans
    ) {
        static TraceComparison empty(String summary) {
            return new TraceComparison(0, "no-baseline", summary,
                0L, 0L, 0L, 0.0, 0L, 0L, 0L, 0.0, 0L, 0L, 0L, 0.0,
                0L, 0);
        }
    }

    public record TraceSpanSummary(
        String className,
        String methodName,
        String spanKind,
        String externalOperation,
        String externalResource,
        String label,
        long wallNs,
        long selfWallNs,
        long cpuNs,
        long selfCpuNs,
        long allocBytes,
        long selfAllocBytes,
        double percentOfTrace
    ) {
    }

    public record TraceLineSummary(
        String className,
        String methodName,
        String fileName,
        int lineNumber,
        String label,
        long hits,
        long wallNs,
        long selfWallNs,
        long cpuNs,
        long selfCpuNs,
        long allocatedBytes,
        long allocationCount
    ) {
    }

    private static TraceIssue issue(String severity, String category, String title,
                                    String detail, String metricLabel,
                                    long metricValue, double percentOfTrace,
                                    TraceSpanSummary span,
                                    TraceLineSummary line) {
        return new TraceIssue(severity, category, title, detail, metricLabel,
            metricValue, round1(percentOfTrace), span, line);
    }

    private static String dominantSignal(RequestTrace trace, SpanAccumulator spans,
                                         TraceSpanSummary topSelfWall,
                                         TraceSpanSummary topSelfAlloc,
                                         double cpuToWallPercent,
                                         double externalWallPercent) {
        if (trace.truncated()) return "capture-limit";
        if (externalWallPercent >= 35.0) return "external";
        if (cpuToWallPercent >= 70.0) return "cpu";
        if (topSelfAlloc != null && trace.totalAllocBytes() > 0
                && topSelfAlloc.selfAllocBytes() >= 64 * 1024L
                && percent(topSelfAlloc.selfAllocBytes(), trace.totalAllocBytes()) >= 35.0) {
            return "allocation";
        }
        if (topSelfWall != null && topSelfWall.percentOfTrace() >= 25.0) {
            return "self-wall";
        }
        if (cpuToWallPercent <= 20.0 && trace.totalWallNs() >= 20_000_000L
                && spans.externalWallNs() == 0L) {
            return "wait";
        }
        return "balanced";
    }

    private static String summaryFor(String signal, RequestTrace trace,
                                     TraceSpanSummary topSelfWall,
                                     TraceSpanSummary topExternal,
                                     double cpuToWallPercent,
                                     double externalWallPercent) {
        return switch (signal) {
            case "capture-limit" -> "Trace hit capture limits; missing subtrees may hide the real hotspot.";
            case "external" -> "External dependency spans account for "
                + rounded(externalWallPercent) + "% of request wall time"
                + (topExternal == null ? "." : ", led by " + topExternal.label() + ".");
            case "cpu" -> "Request is CPU-heavy at " + rounded(cpuToWallPercent)
                + "% CPU-to-wall time.";
            case "allocation" -> "Request is allocation-heavy; inspect the top allocation span.";
            case "self-wall" -> topSelfWall == null
                ? "Request has method self-time hotspots."
                : "Most visible self time is in " + topSelfWall.label() + ".";
            case "wait" -> "Request wall time is much higher than request-thread CPU; inspect I/O, sleeps, locks, or untraced dependencies.";
            default -> "No single dominant bottleneck stands out from the captured trace.";
        };
    }

    private static SpanAccumulator collectSpans(MethodSpan root) {
        SpanAccumulator accumulator = new SpanAccumulator();
        collectSpans(root, true, Math.max(1L, root == null ? 1L : root.wallNs), accumulator);
        return accumulator;
    }

    private static void collectSpans(MethodSpan span, boolean root,
                                     long totalWallNs, SpanAccumulator accumulator) {
        if (span == null) return;
        if (!root) {
            TraceSpanSummary summary = spanSummary(span, totalWallNs);
            accumulator.add(summary);
            if (isExternal(span)) {
                accumulator.externalWallNs += Math.max(0L, span.wallNs);
            }
        }
        for (MethodSpan child : span.children) {
            collectSpans(child, false, totalWallNs, accumulator);
        }
    }

    private static TraceSpanSummary spanSummary(MethodSpan span, long totalWallNs) {
        String label = spanLabel(span);
        return new TraceSpanSummary(span.className, span.methodName, span.spanKind,
            span.externalOperation, span.externalResource, label, span.wallNs,
            span.selfWallNs, span.cpuNs, span.selfCpuNs, span.allocBytes,
            span.selfAllocBytes, round1(percent(span.selfWallNs, totalWallNs)));
    }

    private static TraceLineSummary topLine(MethodSpan root) {
        List<TraceLineSummary> lines = new ArrayList<>();
        collectLines(root, lines);
        return lines.stream()
            .max(Comparator.comparingLong(TraceLineSummary::selfWallNs)
                .thenComparingLong(TraceLineSummary::allocatedBytes))
            .orElse(null);
    }

    private static void collectLines(MethodSpan span, List<TraceLineSummary> lines) {
        if (span == null) return;
        for (MethodSpan.LineStat stat : span.lineStats.values()) {
            String file = stat.fileName == null || stat.fileName.isBlank()
                ? shortClass(span.className) + ".java"
                : stat.fileName;
            String label = shortClass(span.className) + "." + span.methodName
                + " " + file + ":" + stat.lineNumber;
            lines.add(new TraceLineSummary(span.className, span.methodName, file,
                stat.lineNumber, label, stat.hits, stat.wallNs, stat.selfWallNs,
                stat.cpuNs, stat.selfCpuNs, stat.allocatedBytes,
                stat.allocationCount));
        }
        for (MethodSpan child : span.children) {
            collectLines(child, lines);
        }
    }

    private static String spanLabel(MethodSpan span) {
        String classPart = shortClass(span.className);
        String base = classPart.isBlank()
            ? span.methodName
            : classPart + "." + span.methodName;
        if (isExternal(span) && span.externalResource != null
                && !span.externalResource.isBlank()) {
            return base + " " + span.externalResource;
        }
        return base;
    }

    private static boolean isExternal(MethodSpan span) {
        return span != null
            && span.spanKind != null
            && !span.spanKind.isBlank()
            && !"method".equals(span.spanKind)
            && !"request".equals(span.spanKind);
    }

    private static String shortClass(String fqcn) {
        if (fqcn == null || fqcn.isBlank()) return "";
        int i = fqcn.lastIndexOf('.');
        return i < 0 ? fqcn : fqcn.substring(i + 1);
    }

    private static long averageLong(List<RequestTrace> traces, LongExtractor extractor) {
        if (traces.isEmpty()) return 0L;
        long total = 0L;
        for (RequestTrace trace : traces) {
            total += extractor.value(trace);
        }
        return total / traces.size();
    }

    private static double percent(long value, long total) {
        if (total <= 0L) return 0.0;
        return round1(100.0 * (double) Math.max(0L, value) / (double) total);
    }

    private static double percentDelta(long current, long baseline) {
        if (baseline <= 0L) return 0.0;
        return round1(100.0 * (double) (current - baseline) / (double) baseline);
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String rounded(double value) {
        return String.valueOf(round1(value));
    }

    private static String comparisonPosition(double wallDeltaPercent) {
        if (wallDeltaPercent >= 20.0) return "slower";
        if (wallDeltaPercent <= -20.0) return "faster";
        return "similar";
    }

    private static String comparisonSummary(String position, int peerCount,
                                            double wallDeltaPercent) {
        return switch (position) {
            case "slower" -> "This request is " + rounded(wallDeltaPercent)
                + "% slower than " + peerCount + " recent peer trace(s).";
            case "faster" -> "This request is " + rounded(Math.abs(wallDeltaPercent))
                + "% faster than " + peerCount + " recent peer trace(s).";
            default -> "This request is within 20% of " + peerCount
                + " recent peer trace(s).";
        };
    }

    private interface LongExtractor {
        long value(RequestTrace trace);
    }

    private static final class SpanAccumulator {
        private final List<TraceSpanSummary> spans = new ArrayList<>();
        private long externalWallNs;

        void add(TraceSpanSummary span) {
            spans.add(span);
        }

        long externalWallNs() {
            return externalWallNs;
        }

        TraceSpanSummary topSelfWall() {
            return top(Comparator.comparingLong(TraceSpanSummary::selfWallNs));
        }

        TraceSpanSummary topSelfCpu() {
            return top(Comparator.comparingLong(TraceSpanSummary::selfCpuNs));
        }

        TraceSpanSummary topSelfAlloc() {
            return top(Comparator.comparingLong(TraceSpanSummary::selfAllocBytes));
        }

        TraceSpanSummary topExternalWall() {
            return spans.stream()
                .filter(span -> span.spanKind() != null
                    && !"method".equals(span.spanKind())
                    && !"request".equals(span.spanKind()))
                .max(Comparator.comparingLong(TraceSpanSummary::wallNs))
                .orElse(null);
        }

        private TraceSpanSummary top(Comparator<TraceSpanSummary> comparator) {
            return spans.stream().max(comparator).orElse(null);
        }
    }
}
