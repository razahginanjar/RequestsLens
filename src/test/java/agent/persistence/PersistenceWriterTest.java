package agent.persistence;

import agent.core.AgentSelfMetrics;
import agent.model.HeapSnapshot;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link PersistenceWriter} (Phase 3 spec, Step 12.2).
 *
 * <p>The repository is mocked so these tests exercise only the writer's
 * queueing, drop-on-overflow, and flush-to-batch behaviour — not real SQLite.
 */
class PersistenceWriterTest {

    private static HeapSnapshot sample() {
        return new HeapSnapshot(System.currentTimeMillis(), 100L, 200L, 500L, Map.of());
    }

    @Test
    void enqueueIsNonBlockingWhenQueueFull() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        AgentSelfMetrics metrics  = new AgentSelfMetrics();

        PersistenceWriter writer = new PersistenceWriter(mockRepo, metrics);

        // Flood well past the queue capacity (5000) to force overflow.
        HeapSnapshot s = sample();
        for (int i = 0; i < 6000; i++) {
            writer.enqueueHeap(s);
        }

        // Overflow must be counted, and enqueue must never have blocked.
        assertTrue(metrics.snapshot("x", 10).droppedPersistenceSamples() > 0,
            "Overflow should have incremented droppedPersistenceSamples");
    }

    @Test
    void flushCallsBatchInsert() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        AgentSelfMetrics metrics  = new AgentSelfMetrics();
        PersistenceWriter writer  = new PersistenceWriter(mockRepo, metrics);

        writer.enqueueHeap(sample());
        writer.flush();

        // The single queued sample should be written in one batch of size 1.
        verify(mockRepo, times(1)).batchInsertHeap(argThat(list -> list.size() == 1));
    }

    @Test
    void flushOnEmptyQueuesDoesNotInsert() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        AgentSelfMetrics metrics  = new AgentSelfMetrics();
        PersistenceWriter writer  = new PersistenceWriter(mockRepo, metrics);

        // Nothing enqueued — flush must not call the repository at all.
        writer.flush();

        verify(mockRepo, never()).batchInsertHeap(any());
        verify(mockRepo, never()).batchInsertGc(any());
    }
}
