package agent.buffer;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RingBufferTest {

    @Test
    void writesAndReadsASingleValue() {
        RingBuffer<String> buffer = new RingBuffer<>(10);
        buffer.write("hello");

        List<String> result = buffer.snapshot();
        assertEquals(1, result.size());
        assertEquals("hello", result.get(0));
    }

    @Test
    void returnsAllWrittenValues() {
        RingBuffer<Integer> buffer = new RingBuffer<>(10);
        for (int i = 0; i < 5; i++) buffer.write(i);

        List<Integer> result = buffer.snapshot();
        assertEquals(5, result.size());
    }

    @Test
    void wrapsAroundAtCapacity() {
        // Buffer capacity = 3. Write 5 values.
        // The oldest 2 should be overwritten.
        RingBuffer<Integer> buffer = new RingBuffer<>(3);
        buffer.write(1);
        buffer.write(2);
        buffer.write(3);
        buffer.write(4);  // overwrites slot 0 (value 1)
        buffer.write(5);  // overwrites slot 1 (value 2)

        List<Integer> result = buffer.snapshot();
        assertEquals(3, result.size());
        // The 3 most recent values should be 3, 4, 5
        assertTrue(result.contains(3));
        assertTrue(result.contains(4));
        assertTrue(result.contains(5));
    }

    @Test
    void returnsFalseWhenOverwriting() {
        RingBuffer<String> buffer = new RingBuffer<>(2);
        assertTrue(buffer.write("a"));   // slot was empty — true
        assertTrue(buffer.write("b"));   // slot was empty — true
        assertFalse(buffer.write("c"));  // slot had "a" — overwrite — false
    }

    @Test
    void drainToClearsValues() {
        RingBuffer<String> buffer = new RingBuffer<>(10);
        buffer.write("x");
        buffer.write("y");

        List<String> drained = new ArrayList<>();
        buffer.drainTo(drained);
        assertEquals(2, drained.size());

        // After draining, snapshot should be empty
        List<String> afterDrain = buffer.snapshot();
        assertEquals(0, afterDrain.size());
    }

    @Test
    void capacityMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer<>(0));
        assertThrows(IllegalArgumentException.class, () -> new RingBuffer<>(-1));
    }

    @Test
    void supportsConcurrentProducers() throws InterruptedException {
        int threads = 8;
        int perThread = 250;
        RingBuffer<Integer> buffer = new RingBuffer<>(threads * perThread);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        buffer.write(base + i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));

        List<Integer> result = buffer.snapshot();
        assertEquals(threads * perThread, result.size());
        assertEquals(threads * perThread, result.stream().distinct().count());
    }
}
