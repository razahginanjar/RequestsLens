package agent.collector.cpu;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import agent.model.CpuSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CpuSamplerTest {

    @Test
    void samplePublishesLiveSnapshot() {
        CollectorRegistry registry = new CollectorRegistry(10L);
        AgentConfig config = AgentConfig.load("cpu.interval=1000");
        CpuSampler sampler = new CpuSampler(registry, config);

        sampler.sample();

        CpuSnapshot latest = registry.getLatestCpuSnapshot();
        assertNotNull(latest);
        assertTrue(latest.timestampMs() > 0);
        assertTrue(latest.availableProcessors() > 0);
        assertTrue(latest.processCpuLoadPercent() >= -1.0);
        assertTrue(latest.systemCpuLoadPercent() >= -1.0);

        List<CpuSnapshot> samples = registry.cpuBuffer().snapshot();
        assertEquals(1, samples.size());
        assertEquals(latest.timestampMs(), samples.get(0).timestampMs());
        assertEquals(latest.timestampMs(),
            registry.selfMetrics().snapshot("x", 10L).lastCpuSampleTimestampMs());
    }
}
