package demo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LineHotspotTestTarget implements Runnable {

    private final CountDownLatch started;
    private final AtomicBoolean running;

    public LineHotspotTestTarget(CountDownLatch started, AtomicBoolean running) {
        this.started = started;
        this.running = running;
    }

    @Override
    public void run() {
        started.countDown();
        long value = 0L;
        while (running.get()) {
            value += System.nanoTime() & 7L;
            if ((value & 255L) == 3L) {
                Thread.yield();
            }
        }
    }
}
