package agent.profiling.asyncprofiler;

import agent.core.AgentConfig;
import agent.model.FlameNode;

import one.profiler.AsyncProfiler;
import one.profiler.Counter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Optional embedded async-profiler controller.
 *
 * <p>The native backend is only loaded when profiler.async.enabled=true. All
 * failures are captured as status so an unsupported platform never stops the
 * target JVM.
 */
public final class AsyncProfilerController {

    private static final Logger log =
        Logger.getLogger(AsyncProfilerController.class.getName());
    private static final Set<String> EVENTS =
        Set.of("cpu", "wall", "alloc", "lock", "itimer");

    private final AgentConfig config;
    private final Object lock = new Object();
    private final ScheduledExecutorService scheduler =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "requestlens-async-profiler");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });

    private AsyncProfiler profiler;
    private ScheduledFuture<?> stopFuture;
    private boolean initialized;
    private boolean available;
    private boolean running;
    private String version = "";
    private String activeEvent = "";
    private long activeInterval;
    private long startedAtMs;
    private long stoppedAtMs;
    private int activeDurationSeconds;
    private String lastMessage = "";
    private String lastError = "";
    private long startCount;
    private long stopCount;
    private long errorCount;
    private long lastSampleCount;
    private CollapsedSnapshot lastSnapshot = CollapsedSnapshot.empty();

    public AsyncProfilerController(AgentConfig config) {
        this.config = config;
    }

    public void initialize() {
        synchronized (lock) {
            initializeLocked();
        }
    }

    public CommandResult start(String requestedEvent, long requestedInterval,
                               int requestedDurationSeconds) {
        synchronized (lock) {
            if (!config.isAsyncProfilerEnabled()) {
                lastError = "Async profiler is disabled. Set profiler.async.enabled=true.";
                return new CommandResult(false, 400, lastError, statusLocked());
            }
            initializeLocked();
            if (!available || profiler == null) {
                String message = lastError.isBlank()
                    ? "Async profiler native backend is unavailable."
                    : lastError;
                return new CommandResult(false, 503, message, statusLocked());
            }
            if (running) {
                return new CommandResult(true, 200, "Async profiler is already running.",
                    statusLocked());
            }

            String event = normalizeEvent(requestedEvent, config.getAsyncProfilerEvent());
            if (!EVENTS.contains(event)) {
                lastError = "Unsupported async-profiler event: " + requestedEvent;
                return new CommandResult(false, 400, lastError, statusLocked());
            }
            long interval = boundedInterval(requestedInterval,
                config.getAsyncProfilerInterval());
            int durationSeconds = requestedDurationSeconds > 0
                ? Math.min(requestedDurationSeconds,
                    config.getAsyncProfilerDurationSeconds())
                : config.getAsyncProfilerDurationSeconds();

            try {
                cancelStopFutureLocked();
                profiler.start(event, interval);
                running = true;
                activeEvent = event;
                activeInterval = interval;
                activeDurationSeconds = durationSeconds;
                startedAtMs = System.currentTimeMillis();
                stoppedAtMs = 0L;
                lastSampleCount = 0L;
                lastSnapshot = CollapsedSnapshot.empty();
                lastMessage = "Async profiler started.";
                lastError = "";
                startCount++;
                stopFuture = scheduler.schedule(this::autoStop,
                    durationSeconds, TimeUnit.SECONDS);
                return new CommandResult(true, 200, lastMessage, statusLocked());
            } catch (Throwable t) {
                errorCount++;
                lastError = "Async profiler start failed: " + shortError(t);
                log.warning(lastError);
                return new CommandResult(false, 500, lastError, statusLocked());
            }
        }
    }

    public CommandResult stop() {
        synchronized (lock) {
            if (!config.isAsyncProfilerEnabled()) {
                lastError = "Async profiler is disabled. Set profiler.async.enabled=true.";
                return new CommandResult(false, 400, lastError, statusLocked());
            }
            initializeLocked();
            if (!available || profiler == null) {
                String message = lastError.isBlank()
                    ? "Async profiler native backend is unavailable."
                    : lastError;
                return new CommandResult(false, 503, message, statusLocked());
            }
            if (!running) {
                refreshSnapshotLocked();
                return new CommandResult(true, 200, "Async profiler is not running.",
                    statusLocked());
            }
            try {
                cancelStopFutureLocked();
                profiler.stop();
                running = false;
                stoppedAtMs = System.currentTimeMillis();
                stopCount++;
                refreshSnapshotLocked();
                lastMessage = "Async profiler stopped.";
                lastError = "";
                return new CommandResult(true, 200, lastMessage, statusLocked());
            } catch (Throwable t) {
                running = false;
                stoppedAtMs = System.currentTimeMillis();
                errorCount++;
                lastError = "Async profiler stop failed: " + shortError(t);
                log.warning(lastError);
                return new CommandResult(false, 500, lastError, statusLocked());
            }
        }
    }

    public Status status() {
        synchronized (lock) {
            return statusLocked();
        }
    }

    public CollapsedSnapshot snapshot(boolean refreshIfRunning) {
        synchronized (lock) {
            if (refreshIfRunning && running) {
                refreshSnapshotLocked();
            }
            return lastSnapshot;
        }
    }

    private void autoStop() {
        try {
            stop();
        } catch (Throwable failure) {
            // stop() already records failures.
        }
    }

    private void initializeLocked() {
        if (initialized) return;
        initialized = true;
        if (!config.isAsyncProfilerEnabled()) {
            available = false;
            lastMessage = "Async profiler is disabled.";
            return;
        }
        try {
            String libPath = config.getAsyncProfilerLibPath();
            profiler = libPath == null || libPath.isBlank()
                ? AsyncProfiler.getInstance()
                : AsyncProfiler.getInstance(libPath);
            version = profiler.getVersion();
            available = true;
            lastMessage = "Async profiler backend loaded.";
            lastError = "";
            log.info("Async profiler backend loaded - version=" + version
                + " platform=" + platform());
        } catch (Throwable t) {
            profiler = null;
            available = false;
            errorCount++;
            lastError = "Async profiler unavailable: " + shortError(t);
            log.warning(lastError);
        }
    }

    private void refreshSnapshotLocked() {
        if (!available || profiler == null) return;
        try {
            String collapsed = profiler.dumpCollapsed(Counter.SAMPLES);
            lastSampleCount = safeSamples();
            lastSnapshot = parseCollapsed(collapsed,
                config.getAsyncProfilerMaxCollapsedLines());
        } catch (Throwable t) {
            errorCount++;
            lastError = "Async profiler dump failed: " + shortError(t);
        }
    }

    private long safeSamples() {
        try {
            return profiler == null ? 0L : profiler.getSamples();
        } catch (Throwable failure) {
            errorCount++;
            return 0L;
        }
    }

    private Status statusLocked() {
        long now = System.currentTimeMillis();
        return new Status(
            config.isAsyncProfilerEnabled(),
            true,
            initialized,
            available,
            running,
            version,
            platform(),
            activeEvent.isBlank() ? config.getAsyncProfilerEvent() : activeEvent,
            activeInterval <= 0L ? config.getAsyncProfilerInterval() : activeInterval,
            activeDurationSeconds <= 0
                ? config.getAsyncProfilerDurationSeconds()
                : activeDurationSeconds,
            config.getAsyncProfilerMaxCollapsedLines(),
            config.getAsyncProfilerLibPath(),
            startedAtMs,
            stoppedAtMs,
            running && startedAtMs > 0L ? Math.max(0L, now - startedAtMs) : 0L,
            startCount,
            stopCount,
            errorCount,
            lastSampleCount,
            lastSnapshot.stackCount(),
            lastSnapshot.root().samples,
            lastSnapshot.truncated(),
            lastSnapshot.skippedLines(),
            lastMessage,
            lastError
        );
    }

    private void cancelStopFutureLocked() {
        if (stopFuture != null) {
            stopFuture.cancel(false);
            stopFuture = null;
        }
    }

    public static CollapsedSnapshot parseCollapsed(String collapsed, int maxLines) {
        if (collapsed == null || collapsed.isBlank()) {
            return CollapsedSnapshot.empty();
        }
        FlameNode root = new FlameNode("root");
        List<CollapsedStack> stacks = new ArrayList<>();
        int parsed = 0;
        int skipped = 0;
        boolean truncated = false;

        String[] lines = collapsed.split("\\R");
        for (String rawLine : lines) {
            if (rawLine == null || rawLine.isBlank()) continue;
            if (parsed >= Math.max(1, maxLines)) {
                truncated = true;
                break;
            }
            ParsedLine parsedLine = parseLine(rawLine);
            if (parsedLine == null || parsedLine.samples <= 0L) {
                skipped++;
                continue;
            }
            List<String> frames = normalizedFrames(parsedLine.stack);
            if (frames.isEmpty()) {
                skipped++;
                continue;
            }
            root.samples += parsedLine.samples;
            FlameNode node = root;
            for (String frame : frames) {
                node = node.child(frame);
                node.samples += parsedLine.samples;
            }
            stacks.add(new CollapsedStack(String.join(";", frames),
                parsedLine.samples, frames));
            parsed++;
        }
        stacks.sort(Comparator.comparingLong(CollapsedStack::samples).reversed());
        return new CollapsedSnapshot(root, List.copyOf(stacks), parsed, skipped,
            truncated);
    }

    private static ParsedLine parseLine(String line) {
        String trimmed = line.trim();
        int split = trimmed.lastIndexOf(' ');
        if (split <= 0 || split >= trimmed.length() - 1) return null;
        try {
            long samples = Long.parseLong(trimmed.substring(split + 1).trim());
            return new ParsedLine(trimmed.substring(0, split).trim(), samples);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static List<String> normalizedFrames(String stack) {
        String[] rawFrames = stack.split(";");
        List<String> frames = new ArrayList<>();
        for (String rawFrame : rawFrames) {
            String frame = normalizeFrame(rawFrame);
            if (frame.isBlank()) continue;
            if (isProfilerFrame(frame)) return List.of();
            frames.add(frame);
        }
        return frames;
    }

    private static String normalizeFrame(String frame) {
        if (frame == null) return "";
        String value = frame.trim();
        if (value.startsWith("[")) return value;
        return value.replace('/', '.');
    }

    private static boolean isProfilerFrame(String frame) {
        return frame.startsWith("agent.")
            || frame.startsWith("agent.shaded.")
            || frame.startsWith("one.profiler.");
    }

    private static String normalizeEvent(String requestedEvent, String fallback) {
        String event = requestedEvent == null || requestedEvent.isBlank()
            ? fallback
            : requestedEvent;
        return event.trim().toLowerCase(Locale.ROOT);
    }

    private static long boundedInterval(long requestedInterval, long fallback) {
        long value = requestedInterval > 0L ? requestedInterval : fallback;
        if (value < 1_000L) return 1_000L;
        return Math.min(value, 1_000_000_000L);
    }

    private static String platform() {
        return System.getProperty("os.name", "unknown") + "/"
            + System.getProperty("os.arch", "unknown");
    }

    private static String shortError(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        String type = t.getClass().getSimpleName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    private record ParsedLine(String stack, long samples) {
    }

    public record CommandResult(boolean success, int statusCode, String message,
                                Status status) {
    }

    public record Status(boolean configured,
                         boolean embedded,
                         boolean initialized,
                         boolean available,
                         boolean running,
                         String version,
                         String platform,
                         String event,
                         long interval,
                         int durationSeconds,
                         int maxCollapsedLines,
                         String libPath,
                         long startedAtMs,
                         long stoppedAtMs,
                         long activeDurationMs,
                         long startCount,
                         long stopCount,
                         long errorCount,
                         long sampleCount,
                         int stackCount,
                         long flamegraphSamples,
                         boolean truncated,
                         int skippedLines,
                         String message,
                         String error) {
    }

    public record CollapsedSnapshot(FlameNode root,
                                    List<CollapsedStack> stacks,
                                    int stackCount,
                                    int skippedLines,
                                    boolean truncated) {
        public static CollapsedSnapshot empty() {
            return new CollapsedSnapshot(new FlameNode("root"), List.of(), 0, 0, false);
        }
    }

    public record CollapsedStack(String stack, long samples, List<String> frames) {
    }
}
