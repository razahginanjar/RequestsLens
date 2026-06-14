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
 * Unit tests for {@link PersistenceWriter}.
 *
 * <p>The repository is mocked so these tests exercise only the writer's
 * queueing, drop-on-overflow, flush metrics, and failure accounting.
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

        HeapSnapshot s = sample();
        for (int i = 0; i < PersistenceWriter.QUEUE_CAPACITY + 1000; i++) {
            writer.enqueueHeap(s);
        }

        assertTrue(metrics.snapshot("x", 10).droppedPersistenceSamples() > 0,
            "Overflow should have incremented droppedPersistenceSamples");
    }

    @Test
    void flushCallsBatchInsertAndRecordsSuccess() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        when(mockRepo.batchInsertHeap(any())).thenAnswer(invocation ->
            ((java.util.List<?>) invocation.getArgument(0)).size());
        AgentSelfMetrics metrics  = new AgentSelfMetrics();
        PersistenceWriter writer  = new PersistenceWriter(mockRepo, metrics);

        writer.enqueueHeap(sample());
        writer.flush();

        verify(mockRepo, times(1)).batchInsertHeap(argThat(list -> list.size() == 1));
        var snap = metrics.snapshot("x", 10);
        assertEquals(1, snap.persistenceFlushes());
        assertEquals(0, snap.persistenceFlushFailures());
        assertEquals(1, snap.persistedHeapSamples());
    }

    @Test
    void flushOnEmptyQueuesRecordsFlushButDoesNotInsert() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        AgentSelfMetrics metrics  = new AgentSelfMetrics();
        PersistenceWriter writer  = new PersistenceWriter(mockRepo, metrics);

        writer.flush();

        verify(mockRepo, never()).batchInsertHeap(any());
        verify(mockRepo, never()).batchInsertGc(any());
        assertEquals(1, metrics.snapshot("x", 10).persistenceFlushes());
    }

    @Test
    void flushFailureIsCountedAndRethrown() {
        SqliteRepository mockRepo = Mockito.mock(SqliteRepository.class);
        when(mockRepo.batchInsertHeap(any())).thenThrow(
            new PersistenceException("boom", new RuntimeException("sqlite")));
        AgentSelfMetrics metrics  = new AgentSelfMetrics();
        PersistenceWriter writer  = new PersistenceWriter(mockRepo, metrics);

        writer.enqueueHeap(sample());

        assertThrows(PersistenceException.class, writer::flush);
        var snap = metrics.snapshot("x", 10);
        assertEquals(0, snap.persistenceFlushes());
        assertEquals(1, snap.persistenceFlushFailures());
    }
}
