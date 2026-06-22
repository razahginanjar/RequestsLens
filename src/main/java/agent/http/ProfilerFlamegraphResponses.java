package agent.http;

import agent.model.FlameNode;

import io.javalin.http.Context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ProfilerFlamegraphResponses {

    private static final double DEFAULT_MIN_PCT = 1.0;
    private static final int DEFAULT_MAX_DEPTH = 6;
    private static final int DEFAULT_MAX_CHILDREN = 40;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_CHILDREN = 200;

    private ProfilerFlamegraphResponses() {}

    static Options options(Context ctx) {
        return new Options(
            boundedDoubleQuery(ctx, "minPct", DEFAULT_MIN_PCT, 0.0, 100.0),
            boundedIntQuery(ctx, "maxDepth", DEFAULT_MAX_DEPTH, 1, MAX_DEPTH),
            boundedIntQuery(ctx, "maxChildren", DEFAULT_MAX_CHILDREN, 1, MAX_CHILDREN));
    }

    static Map<String, Object> response(FlameNode snapshot, Options options) {
        FlameNode root = snapshot == null ? new FlameNode("root") : snapshot;
        Stats stats = new Stats();
        Map<String, Object> response = nodeResponse(root,
            Math.max(1L, root.samples), 0, options, stats);
        response.put("enabled", true);
        response.put("redacted", false);
        response.put("bounded", stats.hiddenFrames > 0);
        response.put("minPct", options.minPct());
        response.put("maxDepth", options.maxDepth());
        response.put("maxChildren", options.maxChildren());
        response.put("hiddenFrames", stats.hiddenFrames);
        response.put("hiddenSamples", stats.hiddenSamples);
        return response;
    }

    static Map<String, Object> redacted(long samples, Options options) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("enabled", true);
        response.put("redacted", true);
        response.put("frame", "root");
        response.put("samples", samples);
        response.put("children", Map.of());
        response.put("bounded", false);
        response.put("minPct", options.minPct());
        response.put("maxDepth", options.maxDepth());
        response.put("maxChildren", options.maxChildren());
        response.put("hiddenFrames", 0);
        response.put("hiddenSamples", 0L);
        response.put("message", ProfilerHttpServer.REDACTION_MESSAGE);
        return response;
    }

    private static Map<String, Object> nodeResponse(FlameNode node,
                                                    long totalSamples,
                                                    int depth,
                                                    Options options,
                                                    Stats stats) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("frame", node.frame);
        response.put("samples", node.samples);
        response.put("percentOfTotal", round1(percentOf(node.samples, totalSamples)));

        Map<String, Object> children = new LinkedHashMap<>();
        HiddenChildren hidden = new HiddenChildren();
        List<FlameNode> sorted = new ArrayList<>(node.children.values());
        sorted.sort(Comparator.comparingLong((FlameNode child) -> child.samples).reversed());

        int emitted = 0;
        for (FlameNode child : sorted) {
            boolean overDepth = depth + 1 > options.maxDepth();
            boolean overChildren = emitted >= options.maxChildren();
            boolean underThreshold = percentOf(child.samples, totalSamples) < options.minPct();
            if (overDepth || overChildren || underThreshold) {
                hidden.add(child, hiddenReason(overDepth, overChildren, underThreshold));
                continue;
            }
            children.put(child.frame,
                nodeResponse(child, totalSamples, depth + 1, options, stats));
            emitted++;
        }
        if (hidden.samples > 0L) {
            stats.hiddenFrames += hidden.frames;
            stats.hiddenSamples += hidden.samples;
            children.put("(other-" + depth + ")", hiddenNode(hidden, totalSamples));
        }

        response.put("children", children);
        return response;
    }

    private static String hiddenReason(boolean overDepth, boolean overChildren,
                                       boolean underThreshold) {
        if (overDepth) return "depth";
        if (overChildren) return "children";
        if (underThreshold) return "threshold";
        return "unknown";
    }

    private static Map<String, Object> hiddenNode(HiddenChildren hidden,
                                                  long totalSamples) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("frame", "Other frames");
        response.put("samples", hidden.samples);
        response.put("percentOfTotal", round1(percentOf(hidden.samples, totalSamples)));
        response.put("children", Map.of());
        response.put("synthetic", true);
        response.put("hiddenFrameCount", hidden.frames);
        response.put("hiddenReason", hidden.reason == null ? "unknown" : hidden.reason);
        return response;
    }

    private static int nodeCount(FlameNode root) {
        int count = 0;
        Deque<FlameNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            FlameNode node = stack.pop();
            count++;
            for (FlameNode child : node.children.values()) {
                stack.push(child);
            }
        }
        return count;
    }

    private static double percentOf(long samples, long totalSamples) {
        if (totalSamples <= 0L) return 0.0;
        return 100.0 * Math.max(0L, samples) / (double) totalSamples;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static int boundedIntQuery(Context ctx, String name, int def, int min, int max) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            int value = Integer.parseInt(raw);
            if (value < min) return min;
            return Math.min(value, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double boundedDoubleQuery(Context ctx, String name, double def,
                                             double min, double max) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return def;
        try {
            double value = Double.parseDouble(raw);
            if (!Double.isFinite(value)) return def;
            if (value < min) return min;
            return Math.min(value, max);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static record Options(double minPct, int maxDepth, int maxChildren) {
    }

    private static final class Stats {
        private int hiddenFrames;
        private long hiddenSamples;
    }

    private static final class HiddenChildren {
        private int frames;
        private long samples;
        private String reason;

        void add(FlameNode node, String reason) {
            frames += nodeCount(node);
            samples += Math.max(0L, node.samples);
            if (this.reason == null) {
                this.reason = reason;
            } else if (!this.reason.equals(reason)) {
                this.reason = "mixed";
            }
        }
    }
}
