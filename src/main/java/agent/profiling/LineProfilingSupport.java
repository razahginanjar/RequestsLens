package agent.profiling;

import agent.core.AgentConfig;
import agent.model.LineHotspot;
import agent.model.RequestTrace;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Request-scoped source-line hotspot support.
 *
 * <p>The request thread only registers/unregisters a trace id. Sampling and
 * aggregation happen from profiler-owned background threads, keeping the target
 * request path lightweight.
 */
public final class LineProfilingSupport {

    private static final long MAX_SESSION_AGE_NS = TimeUnit.MINUTES.toNanos(10);

    private static volatile AgentConfig config;
    private static volatile boolean enabled;
    private static final ConcurrentHashMap<String, Session> SESSIONS = new ConcurrentHashMap<>();

    private LineProfilingSupport() {}

    public static void configure(AgentConfig agentConfig) {
        config = agentConfig;
        enabled = agentConfig != null && agentConfig.isLineProfilingActive();
        SESSIONS.clear();
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void requestStart(String traceId, Thread requestThread) {
        AgentConfig cfg = config;
        if (!enabled || cfg == null || traceId == null || traceId.isBlank()
                || requestThread == null) {
            return;
        }
        SESSIONS.put(traceId, new Session(traceId, requestThread,
            cfg.getLineSampleIntervalMs(), cfg.getLineMaxSamplesPerTrace(),
            cfg.getLineMaxLinesPerTrace(), cfg.getLineMaxTracePayloadBytes()));
    }

    public static void requestComplete(String traceId) {
        Session session = SESSIONS.get(traceId);
        if (session != null) {
            session.complete();
        }
    }

    public static void discard(String traceId) {
        if (traceId != null) {
            SESSIONS.remove(traceId);
        }
    }

    public static RequestTrace enrich(RequestTrace trace) {
        if (trace == null) return null;
        Session session = SESSIONS.remove(trace.traceId());
        if (session == null) return trace;
        LineProfileResult result = session.result();
        return trace.withLineProfile(result.hotspots(), result.sampleCount(),
            result.droppedSamples(), result.droppedHotspots(),
            result.truncated(), result.sampleIntervalMs());
    }

    public static int activeSessionCount() {
        int count = 0;
        for (Session session : SESSIONS.values()) {
            if (!session.completed) count++;
        }
        return count;
    }

    public static int completedSessionCount() {
        int count = 0;
        for (Session session : SESSIONS.values()) {
            if (session.completed) count++;
        }
        return count;
    }

    public static void sampleActiveRequests() {
        AgentConfig cfg = config;
        if (!enabled || cfg == null) return;
        long nowNs = System.nanoTime();
        for (Session session : SESSIONS.values()) {
            if (nowNs - session.startedNs > MAX_SESSION_AGE_NS) {
                SESSIONS.remove(session.traceId, session);
                continue;
            }
            if (session.completed) continue;
            sampleSession(cfg, session);
        }
    }

    public static void resetForTests() {
        enabled = false;
        config = null;
        SESSIONS.clear();
    }

    private static void sampleSession(AgentConfig cfg, Session session) {
        Thread thread = session.requestThread;
        if (thread == null || !thread.isAlive()) {
            session.complete();
            return;
        }

        StackTraceElement[] stack = thread.getStackTrace();
        StackTraceElement target = firstTargetFrame(cfg, stack);
        if (target == null) return;

        boolean cpuSample = thread.getState() == Thread.State.RUNNABLE;
        session.record(target, cpuSample);
    }

    private static StackTraceElement firstTargetFrame(AgentConfig cfg, StackTraceElement[] stack) {
        if (stack == null) return null;
        for (StackTraceElement frame : stack) {
            if (frame == null) continue;
            if (frame.getLineNumber() <= 0) continue;
            if (cfg.isLineProfilingTargetClass(frame.getClassName())) {
                return frame;
            }
        }
        return null;
    }

    private record LineKey(String className, String methodName, String fileName, int lineNumber) {}

    private record LineProfileResult(
        List<LineHotspot> hotspots,
        int sampleCount,
        int droppedSamples,
        int droppedHotspots,
        boolean truncated,
        long sampleIntervalMs
    ) {}

    private static final class MutableHotspot {
        final LineKey key;
        long samples;
        long cpuSamples;

        MutableHotspot(LineKey key) {
            this.key = key;
        }
    }

    private static final class Session {
        final String traceId;
        final Thread requestThread;
        final long startedNs = System.nanoTime();
        final long sampleIntervalMs;
        final int maxSamples;
        final int maxLines;
        final int maxPayloadBytes;
        final Map<LineKey, MutableHotspot> lines = new LinkedHashMap<>();
        volatile boolean completed;
        int sampleCount;
        int droppedSamples;
        int droppedHotspots;

        Session(String traceId, Thread requestThread, long sampleIntervalMs,
                int maxSamples, int maxLines, int maxPayloadBytes) {
            this.traceId = traceId;
            this.requestThread = requestThread;
            this.sampleIntervalMs = Math.max(1L, sampleIntervalMs);
            this.maxSamples = Math.max(1, maxSamples);
            this.maxLines = Math.max(1, maxLines);
            this.maxPayloadBytes = Math.max(1024, maxPayloadBytes);
        }

        synchronized void record(StackTraceElement frame, boolean cpuSample) {
            if (completed) return;
            if (sampleCount >= maxSamples) {
                droppedSamples++;
                return;
            }
            LineKey key = new LineKey(frame.getClassName(), frame.getMethodName(),
                frame.getFileName(), frame.getLineNumber());
            MutableHotspot hotspot = lines.get(key);
            if (hotspot == null) {
                if (lines.size() >= maxLines) {
                    droppedHotspots++;
                    return;
                }
                hotspot = new MutableHotspot(key);
                lines.put(key, hotspot);
            }
            hotspot.samples++;
            if (cpuSample) hotspot.cpuSamples++;
            sampleCount++;
        }

        void complete() {
            completed = true;
        }

        synchronized LineProfileResult result() {
            List<MutableHotspot> sorted = new ArrayList<>(lines.values());
            sorted.sort(Comparator
                .comparingLong((MutableHotspot h) -> h.samples).reversed()
                .thenComparing(h -> h.key.className())
                .thenComparing(h -> h.key.methodName())
                .thenComparingInt(h -> h.key.lineNumber()));

            List<LineHotspot> hotspots = new ArrayList<>();
            int payloadBytes = 0;
            int payloadDrops = 0;
            long intervalNs = TimeUnit.MILLISECONDS.toNanos(sampleIntervalMs);
            for (MutableHotspot h : sorted) {
                int estimatedBytes = estimatedPayloadBytes(h.key);
                if (hotspots.size() >= maxLines || payloadBytes + estimatedBytes > maxPayloadBytes) {
                    payloadDrops++;
                    continue;
                }
                payloadBytes += estimatedBytes;
                hotspots.add(new LineHotspot(
                    h.key.className(),
                    h.key.methodName(),
                    h.key.fileName(),
                    h.key.lineNumber(),
                    h.samples,
                    h.cpuSamples,
                    h.samples * intervalNs,
                    h.cpuSamples * intervalNs));
            }

            int totalDroppedHotspots = droppedHotspots + payloadDrops;
            return new LineProfileResult(List.copyOf(hotspots), sampleCount,
                droppedSamples, totalDroppedHotspots,
                droppedSamples > 0 || totalDroppedHotspots > 0, sampleIntervalMs);
        }

        private static int estimatedPayloadBytes(LineKey key) {
            return 96
                + charBytes(key.className())
                + charBytes(key.methodName())
                + charBytes(key.fileName());
        }

        private static int charBytes(String value) {
            return value == null ? 0 : value.length() * 2;
        }
    }
}
