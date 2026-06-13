package agent.collector.gc;

import agent.buffer.RingBuffer;
import agent.model.GcEvent;

import com.sun.management.GarbageCollectionNotificationInfo;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.logging.Logger;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

/**
 * Listens for JVM garbage collection events via JMX notifications.
 *
 * <h2>How GC notifications work</h2>
 * Each GC collector (there can be multiple — e.g. young gen collector
 * and old gen collector) is represented by a GarbageCollectorMXBean.
 * These beans implement NotificationEmitter, which means we can register
 * a listener that gets called after each GC cycle.
 *
 * The notification contains a GarbageCollectionNotificationInfo object
 * with the GC name, cause, duration, and before/after heap sizes.
 *
 * <h2>Why no polling?</h2>
 * We could poll GcMXBean.getCollectionCount() every N ms, but that
 * misses GCs that happen between polls and gives no duration information.
 * The listener approach is event-driven — we get called exactly once
 * per GC with complete information.
 */
public final class GcListener {

    private static final Logger log = Logger.getLogger(GcListener.class.getName());

    private final RingBuffer<GcEvent> buffer;

    public GcListener(RingBuffer<GcEvent> buffer) {
        this.buffer = buffer;
    }

    /**
     * Registers this listener on all available GC beans.
     * Call once at agent startup.
     */
    public void attach() {
        int registered = 0;

        for (GarbageCollectorMXBean gcBean : ManagementFactory.getGarbageCollectorMXBeans()) {
            /*
             * GarbageCollectorMXBean extends NotificationEmitter.
             * We cast to NotificationEmitter to call addNotificationListener().
             * The listener (this::onNotification) is called after every GC.
             *
             * Parameters:
             *   listener — our callback method
             *   filter   — null means "receive all notifications"
             *   handback — null means "no extra data passed to listener"
             */
            if (gcBean instanceof NotificationEmitter emitter) {
                emitter.addNotificationListener(this::onNotification, null, null);
                registered++;
                log.fine("Registered GC listener on: " + gcBean.getName());
            }
        }

        log.info("GcListener attached to " + registered + " GC beans");
    }

    /**
     * Called by the JVM after each GC event.
     *
     * This runs on a JVM-internal thread. Treat it as Tier 1:
     * no logging, no allocation, no blocking.
     *
     * @param notification the JMX notification object
     * @param handback     always null (we passed null when registering)
     */
    private void onNotification(Notification notification, Object handback) {
        /*
         * Not all notifications from GC beans are GC notifications.
         * Check the type string before processing.
         */
        if (!GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION
                .equals(notification.getType())) {
            return;
        }

        /*
         * GarbageCollectionNotificationInfo wraps the notification's
         * user data (a CompositeData object) and gives us typed access.
         */
        try {
            GarbageCollectionNotificationInfo info =
                GarbageCollectionNotificationInfo.from(
                    (CompositeData) notification.getUserData());

            // Null check — some GC implementations (ZGC concurrent phases)
            // fire notifications with null info
            if (info == null || info.getGcInfo() == null) return;

            var gcInfo = info.getGcInfo();

            // Total heap before = sum of used bytes across all memory pools before GC
            long heapBefore = gcInfo.getMemoryUsageBeforeGc().values().stream()
                .mapToLong(usage -> usage.getUsed())
                .sum();

            long heapAfter = gcInfo.getMemoryUsageAfterGc().values().stream()
                .mapToLong(usage -> usage.getUsed())
                .sum();

            GcEvent event = new GcEvent(
                System.currentTimeMillis(),
                info.getGcName(),
                info.getGcCause(),
                gcInfo.getDuration(),
                heapBefore,
                heapAfter
            );

            // Write to ring buffer — allocation-free, lock-free
            buffer.write(event);

        } catch (Exception e) {
            // Catch-all: we must never throw from a JMX notification listener.
            // Throwing here can destabilize the JVM.
            // No logging — we are on a Tier 1 JVM internal thread.
        }
    }
}