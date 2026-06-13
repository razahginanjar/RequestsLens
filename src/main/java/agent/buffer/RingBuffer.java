package agent.buffer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A fixed-size bounded FIFO buffer for passing data from producer threads to a
 * consumer thread with predictable memory use.
 *
 * <p>When full, the oldest value is overwritten. The method still writes the new
 * value and returns {@code false} so callers can increment a drop counter.
 *
 * <p>This implementation supports multiple producers. Endpoint samples and
 * request traces are written by application request threads, so plain array
 * writes plus an atomic index are not enough to provide consistent drain/snapshot
 * behavior. A short in-process lock protects writers and readers.
 *
 * @param <T> the type of element stored
 */
public final class RingBuffer<T> {

    private final ArrayDeque<T> deque;
    private final int capacity;
    private final ReentrantLock lock = new ReentrantLock();

    public RingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException(
                "RingBuffer capacity must be > 0, got: " + capacity);
        }
        this.capacity = capacity;
        this.deque = new ArrayDeque<>(capacity);
    }

    /**
     * Writes a value into the buffer.
     *
     * @return {@code true} if no existing value was overwritten, {@code false}
     *         if the oldest value was dropped to make room
     */
    public boolean write(T value) {
        Objects.requireNonNull(value, "value");
        lock.lock();
        try {
            boolean accepted = deque.size() < capacity;
            if (!accepted) {
                deque.removeFirst();
            }
            deque.addLast(value);
            return accepted;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Adds all currently buffered values to {@code destination}, then clears the
     * buffer. Values are drained oldest-first.
     */
    public void drainTo(List<T> destination) {
        Objects.requireNonNull(destination, "destination");
        lock.lock();
        try {
            destination.addAll(deque);
            deque.clear();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns all currently buffered values without clearing them. Values are
     * returned oldest-first.
     */
    public List<T> snapshot() {
        lock.lock();
        try {
            return new ArrayList<>(deque);
        } finally {
            lock.unlock();
        }
    }

    public int capacity() {
        return capacity;
    }
}
