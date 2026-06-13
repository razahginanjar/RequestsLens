package agent.profiling;

import agent.model.MethodSpan;

import java.lang.instrument.Instrumentation;

/**
 * Records one object/array allocation against the method currently executing on
 * the request thread (Phase 6, Amendment A).
 *
 * <p>{@link #record(Object)} is invoked from bytecode that the allocation
 * instrumentation injects immediately after each {@code new}/array-creation site
 * inside traced methods. It must be cheap on the fast path and must never throw
 * into the application.
 *
 * <h2>Why a static helper the bytecode CALLS</h2>
 * The injected call runs inside the application's own classes (a different
 * classloader). Following the advice rules from earlier phases, the instrumented
 * code only invokes this public static method — it never touches agent fields.
 */
public final class AllocationRecorder {

    /** Set once at startup; used for exact shallow object sizing. */
    private static volatile Instrumentation instrumentation;

    private AllocationRecorder() {}

    public static void setInstrumentation(Instrumentation inst) {
        instrumentation = inst;
    }

    /**
     * Attribute one freshly-allocated object to the executing method span.
     *
     * @param obj the just-created object/array (DUP'd from the allocation site)
     */
    public static void record(Object obj) {
        // Fast gate: only do work while a request is being traced.
        MethodSpan span = RequestProfilingContext.currentTopSpan();
        if (span == null || obj == null) return;
        try {
            Instrumentation inst = instrumentation;
            long size = (inst != null) ? inst.getObjectSize(obj) : 0L;
            // Prefer a readable name (e.g. "byte[]" rather than the JVM descriptor "[B").
            Class<?> cls = obj.getClass();
            String type = cls.getCanonicalName();
            if (type == null) type = cls.getName();   // fallback for anonymous/local classes
            span.recordAlloc(type, size);
        } catch (Throwable t) {
            // Profiling must never break the application.
        }
    }
}
