# Phase 1 — Agent Core & JVM Collection
## Full Implementation Guide

> **Goal by end of this phase:** The agent JAR attaches to any JVM, starts a
> background heap sampler and GC listener, and exposes raw JSON data at
> `http://localhost:7070/profiler/heap`, `/profiler/gc`, and `/profiler/status`.
>
> **Estimated time:** 5–7 days  
> **Branch:** `phase/1-agent-core`

---

## Before You Start

### What You Need Installed
- Java 17 JDK — verify with `java -version` (must show 17.x)
- Maven 3.9+ — verify with `mvn -version`
- An IDE (IntelliJ IDEA recommended — free Community edition is fine)
- Git

### Mental Model — What Is a Java Agent?

A Java agent is a JAR file the JVM loads **before** your application's `main()`
method runs. You tell the JVM about it with a startup flag:

```bash
java -javaagent:your-agent.jar -jar your-app.jar
```

The JVM looks inside `your-agent.jar`'s `MANIFEST.MF` for a class name under
the key `Premain-Class`, then calls that class's `premain()` method. After
`premain()` returns, the normal application starts. That's it.

Your `premain()` method has one job: **set everything up and return fast**.
It must not block. It must not do heavy work. It starts background daemon
threads and returns — those threads do the real work.

---

## Step 1 — Create the Maven Project

### 1.1 Project Structure

Create this directory layout manually or let your IDE generate a Maven project:

```
jvm-profiler-agent/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── agent/
│   │   │       ├── core/
│   │   │       ├── collector/
│   │   │       │   ├── heap/
│   │   │       │   └── gc/
│   │   │       ├── buffer/
│   │   │       ├── http/
│   │   │       └── model/
│   │   └── resources/
│   │       └── dashboard/
│   └── test/
│       └── java/
│           └── agent/
└── demo-app/
    ├── pom.xml
    └── src/main/java/demo/
```

### 1.2 Root pom.xml

This is the most important file in Phase 1. Read every comment — each section
is there for a specific reason.

Create `jvm-profiler-agent/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">

  <modelVersion>4.0.0</modelVersion>

  <!-- ── Project identity ──────────────────────────────────────────── -->
  <groupId>io.github.razahginanjar</groupId>
  <artifactId>jvm-profiler-agent</artifactId>
  <version>1.0.0-SNAPSHOT</version>
  <packaging>jar</packaging>

  <properties>
    <java.version>17</java.version>
    <maven.compiler.source>17</maven.compiler.source>
    <maven.compiler.target>17</maven.compiler.target>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>

    <!-- Dependency versions — change here, nowhere else -->
    <javalin.version>6.3.0</javalin.version>
    <jackson.version>2.17.0</jackson.version>
    <junit.version>5.10.2</junit.version>
    <mockito.version>5.11.0</mockito.version>
  </properties>

  <dependencies>

    <!-- ── HTTP server ────────────────────────────────────────────── -->
    <!--
      Javalin is a lightweight embedded HTTP server.
      We use it to serve the /profiler/* API endpoints.
      It has no Spring dependency — important because the target app
      may already have a Spring web server on a different port.
    -->
    <dependency>
      <groupId>io.javalin</groupId>
      <artifactId>javalin</artifactId>
      <version>${javalin.version}</version>
    </dependency>

    <!-- ── JSON serialization ──────────────────────────────────────── -->
    <!--
      Jackson converts Java objects to JSON for our HTTP responses.
      Javalin uses Jackson internally too, so this is also its dependency.
    -->
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
      <version>${jackson.version}</version>
    </dependency>

    <!-- ── SLF4J simple (Javalin needs it for its own logging) ──────── -->
    <dependency>
      <groupId>org.slf4j</groupId>
      <artifactId>slf4j-simple</artifactId>
      <version>2.0.12</version>
    </dependency>

    <!-- ── Testing ────────────────────────────────────────────────── -->
    <dependency>
      <groupId>org.junit.jupiter</groupId>
      <artifactId>junit-jupiter</artifactId>
      <version>${junit.version}</version>
      <scope>test</scope>
    </dependency>
    <dependency>
      <groupId>org.mockito</groupId>
      <artifactId>mockito-core</artifactId>
      <version>${mockito.version}</version>
      <scope>test</scope>
    </dependency>

  </dependencies>

  <build>
    <plugins>

      <!-- ── Compiler ───────────────────────────────────────────────── -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.13.0</version>
        <configuration>
          <source>17</source>
          <target>17</target>
        </configuration>
      </plugin>

      <!-- ── Surefire — runs JUnit 5 tests ────────────────────────── -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-surefire-plugin</artifactId>
        <version>3.2.5</version>
      </plugin>

      <!-- ── Shade — produces the fat agent JAR ──────────────────── -->
      <!--
        This is the most important plugin in the build.

        The shade plugin bundles all dependencies into a single JAR.
        Without it, the agent JAR would only contain your .class files
        and Javalin/Jackson would be missing at runtime.

        The <relocations> section renames the packages of shaded libraries.
        This is called "shading" or "relocation".

        Why relocate? Because the target application might also use Jackson
        (almost every Spring Boot app does). If both the agent and the app
        load Jackson from the same package names, one version wins and the
        other fails in unpredictable ways.

        After relocation, Jackson inside the agent lives at
        agent.shaded.jackson.* — completely separate from the app's Jackson.
      -->
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-shade-plugin</artifactId>
        <version>3.5.2</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals><goal>shade</goal></goals>
            <configuration>

              <!-- The shaded JAR becomes the main artifact -->
              <shadedArtifactAttached>false</shadedArtifactAttached>

              <!-- Suppress the WARNING about overlapping classes -->
              <filters>
                <filter>
                  <artifact>*:*</artifact>
                  <excludes>
                    <exclude>META-INF/*.SF</exclude>
                    <exclude>META-INF/*.DSA</exclude>
                    <exclude>META-INF/*.RSA</exclude>
                  </excludes>
                </filter>
              </filters>

              <!-- Relocate all shaded dependencies to agent.shaded.* -->
              <relocations>
                <relocation>
                  <pattern>io.javalin</pattern>
                  <shadedPattern>agent.shaded.javalin</shadedPattern>
                </relocation>
                <relocation>
                  <pattern>com.fasterxml.jackson</pattern>
                  <shadedPattern>agent.shaded.jackson</shadedPattern>
                </relocation>
                <relocation>
                  <pattern>org.slf4j</pattern>
                  <shadedPattern>agent.shaded.slf4j</shadedPattern>
                </relocation>
              </relocations>

              <!-- Write the MANIFEST.MF that makes this JAR an agent -->
              <transformers>
                <transformer implementation=
                  "org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                  <manifestEntries>
                    <!--
                      Premain-Class: the class whose premain() method the JVM
                      calls when -javaagent is used at startup.
                    -->
                    <Premain-Class>agent.core.AgentMain</Premain-Class>
                    <!--
                      Agent-Class: the class used when attaching to an already-
                      running JVM dynamically. Same class, different method name
                      (agentmain instead of premain).
                    -->
                    <Agent-Class>agent.core.AgentMain</Agent-Class>
                    <!--
                      These two permissions are needed for Byte Buddy
                      instrumentation (Phase 2). Add them now so you don't
                      have to rebuild the manifest later.
                    -->
                    <Can-Redefine-Classes>true</Can-Redefine-Classes>
                    <Can-Retransform-Classes>true</Can-Retransform-Classes>
                  </manifestEntries>
                </transformer>
                <!-- Merge service files from multiple JARs (needed by Javalin) -->
                <transformer implementation=
                  "org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
              </transformers>

            </configuration>
          </execution>
        </executions>
      </plugin>

    </plugins>
  </build>

</project>
```

### 1.3 Verify the Build Works (Empty Project)

Before writing any Java, confirm Maven is configured correctly:

```bash
cd jvm-profiler-agent
mvn package -DskipTests
```

You should see `BUILD SUCCESS` and a JAR created at `target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar`.

If you see errors about Java version, check that `JAVA_HOME` points to Java 17:
```bash
echo $JAVA_HOME
java -version
```

---

## Step 2 — The Data Models

Create these first. Every other class depends on them.

### 2.1 HeapSnapshot

`src/main/java/agent/model/HeapSnapshot.java`

```java
package agent.model;

import java.util.Map;

/**
 * An immutable point-in-time snapshot of JVM heap memory.
 *
 * This is a Java record — it automatically generates a constructor,
 * getters, equals(), hashCode(), and toString(). No boilerplate needed.
 *
 * Why immutable? Because this object is written on the sampler thread
 * and read on the HTTP thread. Immutable objects are always thread-safe —
 * no synchronization needed.
 */
public record HeapSnapshot(
    /** When this snapshot was taken — milliseconds since epoch (System.currentTimeMillis()) */
    long timestampMs,

    /** Bytes currently in use on the heap */
    long usedBytes,

    /** Bytes committed (reserved by the JVM) — always >= usedBytes */
    long committedBytes,

    /** Maximum heap size — set by -Xmx flag. -1 if no limit. */
    long maxBytes,

    /**
     * Heap usage broken down by memory pool.
     * Keys are pool names like "PS Eden Space", "G1 Old Gen", "Metaspace".
     * Values are bytes used in that pool.
     *
     * Note: Map.copyOf() makes this map unmodifiable — consistent with
     * the immutability of the record.
     */
    Map<String, Long> poolUsage
) {}
```

### 2.2 GcEvent

`src/main/java/agent/model/GcEvent.java`

```java
package agent.model;

/**
 * An immutable record of a single garbage collection event.
 *
 * The JVM fires a notification after every GC cycle. We capture the
 * key fields from that notification and store them here.
 */
public record GcEvent(
    /** When the GC completed — milliseconds since epoch */
    long timestampMs,

    /**
     * Name of the GC collector, e.g. "G1 Young Generation",
     * "PS MarkSweep", "ZGC Cycles".
     * Useful for identifying which collector is running.
     */
    String gcName,

    /**
     * Why the GC was triggered, e.g. "G1 Evacuation Pause",
     * "Allocation Failure", "System.gc()".
     */
    String gcCause,

    /** How long this GC pause lasted in milliseconds */
    long durationMs,

    /** Heap bytes in use BEFORE this GC ran */
    long heapBeforeBytes,

    /** Heap bytes in use AFTER this GC ran — should be less than heapBeforeBytes */
    long heapAfterBytes
) {}
```

### 2.3 AgentStatus

`src/main/java/agent/model/AgentStatus.java`

```java
package agent.model;

/**
 * A snapshot of the agent's own health — returned by GET /profiler/status.
 *
 * This grows over time as new subsystems are added. Fields added in
 * later phases (sampling state, webhook failures, etc.) will be added here.
 */
public record AgentStatus(
    String instanceId,
    long   uptimeMs,
    long   agentHeapUsedBytes,
    long   droppedSamples,
    long   samplingDelays,
    long   lastSampleTimestampMs,
    long   baseIntervalMs
) {}
```

---

## Step 3 — AgentSelfMetrics

`src/main/java/agent/core/AgentSelfMetrics.java`

This was fully specified in the addendum. Create it exactly as written there.
Key points to remember when creating this class:

- `LongAdder` for all counters (incremented frequently)
- `volatile` for all gauges (written by one thread, read by another)
- `snapshot()` is the only public read method — returns an immutable record

```java
package agent.core;

import agent.model.AgentStatus;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.concurrent.atomic.LongAdder;

public final class AgentSelfMetrics {

    private final LongAdder droppedSamples    = new LongAdder();
    private final LongAdder samplingDelays     = new LongAdder();
    private volatile long   lastSampleTs      = 0L;
    private final long      startedAtMs       = System.currentTimeMillis();
    private final MemoryMXBean memBean        =
        ManagementFactory.getMemoryMXBean();

    // ── Increment (Tier 1 safe) ───────────────────────────────────────────
    public void incrementDroppedSamples()  { droppedSamples.increment(); }
    public void incrementSamplingDelays()  { samplingDelays.increment(); }

    // ── Setters (Tier 2 safe) ─────────────────────────────────────────────
    public void setLastSampleTs(long ts)   { lastSampleTs = ts; }

    // ── Snapshot (Tier 3 — HTTP thread only) ──────────────────────────────
    public AgentStatus snapshot(String instanceId, long baseIntervalMs) {
        return new AgentStatus(
            instanceId,
            System.currentTimeMillis() - startedAtMs,
            memBean.getHeapMemoryUsage().getUsed(),
            droppedSamples.sum(),
            samplingDelays.sum(),
            lastSampleTs,
            baseIntervalMs
        );
    }
}
```

---

## Step 4 — AgentConfig

`src/main/java/agent/core/AgentConfig.java`

The agent receives configuration two ways:
1. As a string argument after the JAR path: `-javaagent:agent.jar=port=8080,interval=5`
2. As JVM system properties: `-Dprofiler.http.port=8080`
3. As a properties file: `jvm-profiler.properties` in the working directory

Properties file wins over system properties wins over the argument string.
All fall back to hardcoded defaults.

```java
package agent.core;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Holds all agent configuration.
 *
 * Load once at startup via AgentConfig.load(args).
 * Pass the single instance everywhere via constructor injection.
 * Never load config inside a hot path.
 */
public final class AgentConfig {

    private static final Logger log = Logger.getLogger(AgentConfig.class.getName());

    // ── Defaults ──────────────────────────────────────────────────────────
    private static final int    DEFAULT_PORT     = 7070;
    private static final long   DEFAULT_INTERVAL = 10L;   // ms
    private static final String DEFAULT_INSTANCE_ID_SUFFIX = ":7070";

    // ── Fields ────────────────────────────────────────────────────────────
    private final int    httpPort;
    private final long   baseIntervalMs;
    private final String instanceId;

    private AgentConfig(int httpPort, long baseIntervalMs, String instanceId) {
        this.httpPort       = httpPort;
        this.baseIntervalMs = baseIntervalMs;
        this.instanceId     = instanceId;
    }

    /**
     * Load configuration from all sources.
     *
     * @param agentArgs the string passed after -javaagent:agent.jar=<HERE>
     *                  Can be null if no arguments were provided.
     */
    public static AgentConfig load(String agentArgs) {
        Properties props = new Properties();

        // Source 1 — properties file (highest priority)
        loadPropertiesFile(props);

        // Source 2 — system properties (override file)
        applySystemProperties(props);

        // Source 3 — agent argument string (lowest priority)
        // e.g. "port=8080,interval=5" → split on comma, then on =
        if (agentArgs != null && !agentArgs.isBlank()) {
            for (String pair : agentArgs.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    // Map short arg names to full property names
                    String key = switch (kv[0].trim()) {
                        case "port"     -> "profiler.http.port";
                        case "interval" -> "profiler.sampling.interval.ms";
                        default         -> kv[0].trim();
                    };
                    // Only set if not already set by higher-priority source
                    props.putIfAbsent(key, kv[1].trim());
                }
            }
        }

        // Parse final values with defaults
        int  port     = parseInt(props, "profiler.http.port",
                                  DEFAULT_PORT);
        long interval = parseLong(props, "profiler.sampling.interval.ms",
                                  DEFAULT_INTERVAL);
        String id     = props.getProperty("profiler.instance.id",
                                  resolveHostname() + ":" + port);

        // Validate
        if (interval < 5) {
            log.warning("profiler.sampling.interval.ms=" + interval
                + " is below minimum of 5ms. Resetting to 5ms.");
            interval = 5;
        }
        if (port < 1024 || port > 65535) {
            log.warning("profiler.http.port=" + port
                + " is invalid. Resetting to " + DEFAULT_PORT);
            port = DEFAULT_PORT;
        }

        log.info("AgentConfig loaded — port=" + port
            + " interval=" + interval + "ms instanceId=" + id);

        return new AgentConfig(port, interval, id);
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public int    getHttpPort()       { return httpPort; }
    public long   getBaseIntervalMs() { return baseIntervalMs; }
    public String getInstanceId()     { return instanceId; }

    // ── Private helpers ───────────────────────────────────────────────────

    private static void loadPropertiesFile(Properties props) {
        // Look for the file in the working directory
        try (InputStream in = new FileInputStream("jvm-profiler.properties")) {
            props.load(in);
            log.info("Loaded jvm-profiler.properties");
        } catch (IOException e) {
            // File not found is normal — all config is optional
            log.fine("No jvm-profiler.properties found — using defaults");
        }
    }

    private static void applySystemProperties(Properties props) {
        // Check all known property keys and pull from system properties
        String[] keys = {
            "profiler.http.port",
            "profiler.sampling.interval.ms",
            "profiler.instance.id"
        };
        for (String key : keys) {
            String val = System.getProperty(key);
            if (val != null) props.setProperty(key, val);
        }
    }

    private static int parseInt(Properties p, String key, int def) {
        try { return Integer.parseInt(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) {
            log.warning("Invalid value for " + key + " — using default " + def);
            return def;
        }
    }

    private static long parseLong(Properties p, String key, long def) {
        try { return Long.parseLong(p.getProperty(key, String.valueOf(def))); }
        catch (NumberFormatException e) {
            log.warning("Invalid value for " + key + " — using default " + def);
            return def;
        }
    }

    private static String resolveHostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "unknown"; }
    }
}
```

---

## Step 5 — RingBuffer

`src/main/java/agent/buffer/RingBuffer.java`

This is the most technically interesting class in Phase 1. Read the comments
carefully — they explain concepts you will use throughout the project.

```java
package agent.buffer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A fixed-size circular buffer for passing data from a producer thread
 * to a consumer thread with minimal overhead.
 *
 * <h2>Why a ring buffer?</h2>
 * The heap sampler (producer) runs 100 times per second and must not block.
 * The aggregation daemon (consumer) reads samples every few seconds.
 * A ring buffer lets the producer always write immediately — it overwrites
 * the oldest slot if the consumer is behind.
 *
 * <h2>Thread safety</h2>
 * - One producer thread calls write() — uses AtomicLong for the write index.
 * - One consumer thread calls drainTo() — reads all written slots.
 * - The Object[] array is written and read with plain assignments.
 *   This is safe because AtomicLong.getAndIncrement() acts as a
 *   happens-before barrier: the consumer sees all writes made before the
 *   index was incremented.
 *
 * <h2>Capacity</h2>
 * Default 1000 slots. At 10ms sampling interval, this is 10 seconds of history.
 * When full, the oldest slot is silently overwritten — no blocking, no exception.
 *
 * @param <T> the type of element stored (HeapSnapshot, GcEvent, etc.)
 */
public final class RingBuffer<T> {

    private final Object[]   slots;
    private final int        capacity;
    private final AtomicLong writeIndex = new AtomicLong(0);

    public RingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException(
            "RingBuffer capacity must be > 0, got: " + capacity);
        this.capacity = capacity;
        this.slots    = new Object[capacity];
    }

    /**
     * Writes a value into the next slot.
     *
     * This method is allocation-free — it only increments an AtomicLong
     * and writes a reference into an array. Safe to call from Tier 1 paths.
     *
     * @return true if written to an empty slot (no data lost)
     *         false if an older value was overwritten (sample dropped)
     */
    public boolean write(T value) {
        long idx         = writeIndex.getAndIncrement();
        int  slot        = (int) (idx % capacity);
        boolean wasEmpty = slots[slot] == null;
        slots[slot]      = value;
        return wasEmpty;
    }

    /**
     * Reads all currently available values into the provided list.
     *
     * Call this from the consumer (aggregation daemon) thread only.
     * Clears each slot after reading so the next write knows it is empty.
     *
     * @param destination the list to add values into
     */
    @SuppressWarnings("unchecked")
    public void drainTo(List<T> destination) {
        long currentWrite = writeIndex.get();
        // Start from the oldest unread slot
        long startIdx = Math.max(0, currentWrite - capacity);

        for (long i = startIdx; i < currentWrite; i++) {
            int slot = (int) (i % capacity);
            T value  = (T) slots[slot];
            if (value != null) {
                destination.add(value);
                slots[slot] = null;  // clear so we know it is read
            }
        }
    }

    /**
     * Returns a snapshot of all current values without clearing.
     * Use for the HTTP API — returns what is available right now.
     */
    @SuppressWarnings("unchecked")
    public List<T> snapshot() {
        List<T> result = new ArrayList<>(capacity);
        long currentWrite = writeIndex.get();
        long startIdx     = Math.max(0, currentWrite - capacity);

        for (long i = startIdx; i < currentWrite; i++) {
            T value = (T) slots[(int) (i % capacity)];
            if (value != null) result.add(value);
        }
        return result;
    }

    public int capacity() { return capacity; }
}
```

---

## Step 6 — CollectorRegistry

`src/main/java/agent/core/CollectorRegistry.java`

This is the central holder for all shared state. Every component receives
it via constructor injection. There is no static state in this project —
everything flows through this class.

```java
package agent.core;

import agent.buffer.RingBuffer;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;

/**
 * Central registry holding all shared buffers and metrics.
 *
 * <h2>Why a registry?</h2>
 * Without a registry, components would need to pass dozens of individual
 * buffers and counters to each other. The registry groups them cleanly
 * and makes dependency injection straightforward.
 *
 * <h2>No static state</h2>
 * This class is instantiated once in AgentMain and passed everywhere.
 * There are no static fields in this project — static state causes
 * hidden dependencies that make testing and debugging very difficult.
 */
public final class CollectorRegistry {

    // ── Buffers ───────────────────────────────────────────────────────────
    private final RingBuffer<HeapSnapshot> heapBuffer;
    private final RingBuffer<GcEvent>      gcBuffer;

    // ── Self-monitoring ───────────────────────────────────────────────────
    private final AgentSelfMetrics selfMetrics;

    public CollectorRegistry() {
        // 1000 slots = 10 seconds at 10ms interval
        this.heapBuffer  = new RingBuffer<>(1000);
        this.gcBuffer    = new RingBuffer<>(500);
        this.selfMetrics = new AgentSelfMetrics();
    }

    // ── Getters ───────────────────────────────────────────────────────────
    public RingBuffer<HeapSnapshot> heapBuffer()  { return heapBuffer; }
    public RingBuffer<GcEvent>      gcBuffer()    { return gcBuffer; }
    public AgentSelfMetrics         selfMetrics() { return selfMetrics; }
}
```

---

## Step 7 — HeapSampler

`src/main/java/agent/collector/heap/HeapSampler.java`

```java
package agent.collector.heap;

import agent.buffer.RingBuffer;
import agent.core.AgentConfig;
import agent.core.AgentSelfMetrics;
import agent.model.HeapSnapshot;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryPoolMXBean;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Periodically samples JVM heap usage via JMX and writes snapshots
 * to the ring buffer.
 *
 * <h2>JMX — Java Management Extensions</h2>
 * JMX is a built-in JDK API for monitoring JVM internals.
 * MemoryMXBean gives us total heap usage.
 * MemoryPoolMXBeans give us per-generation usage (Eden, Old Gen, etc.).
 * Both are available with zero dependencies — they are part of the JDK.
 *
 * <h2>Daemon threads</h2>
 * The ScheduledExecutorService uses daemon threads. Daemon threads do not
 * prevent the JVM from shutting down. If we used non-daemon threads, the
 * target application would never be able to exit cleanly.
 *
 * Always use daemon threads in Java agent code.
 */
public final class HeapSampler {

    private static final Logger log = Logger.getLogger(HeapSampler.class.getName());

    private final RingBuffer<HeapSnapshot> buffer;
    private final AgentSelfMetrics         selfMetrics;
    private final AgentConfig              config;

    // JMX beans — read-only, thread-safe, obtained once at construction
    private final MemoryMXBean         memoryBean;
    private final List<MemoryPoolMXBean> poolBeans;

    // Delay detection — tracks when we expected the next tick
    private volatile long expectedNextTickMs = 0L;

    public HeapSampler(RingBuffer<HeapSnapshot> buffer,
                       AgentSelfMetrics selfMetrics,
                       AgentConfig config) {
        this.buffer      = buffer;
        this.selfMetrics = selfMetrics;
        this.config      = config;
        // Obtain JMX beans once at construction — safe and efficient
        this.memoryBean  = ManagementFactory.getMemoryMXBean();
        this.poolBeans   = ManagementFactory.getMemoryPoolMXBeans();
    }

    /**
     * Starts the sampling daemon. Returns immediately.
     * The actual sampling happens on a background thread.
     */
    public void start() {
        // newSingleThreadScheduledExecutor — one thread, scheduled at fixed rate
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                Thread t = new Thread(runnable, "profiler-heap-sampler");
                t.setDaemon(true);   // MUST be daemon
                t.setPriority(Thread.MIN_PRIORITY);  // Low priority — don't steal from the app
                return t;
            }
        );

        // Schedule: start after 1 second, then repeat every N milliseconds
        // initialDelay=1000ms gives the target app time to start before we sample
        scheduler.scheduleAtFixedRate(
            this::sample,
            1000,
            config.getBaseIntervalMs(),
            TimeUnit.MILLISECONDS
        );

        log.info("HeapSampler started — interval=" + config.getBaseIntervalMs() + "ms");
    }

    /**
     * Takes a single heap snapshot and writes it to the ring buffer.
     *
     * This method runs on the sampler daemon thread.
     * It is Tier 1 — no logging, no allocation, no blocking.
     */
    void sample() {
        long nowMs = System.currentTimeMillis();

        // ── Delay detection ───────────────────────────────────────────────
        if (expectedNextTickMs > 0) {
            long delay    = nowMs - expectedNextTickMs;
            long halfInterval = config.getBaseIntervalMs() / 2;
            if (delay > halfInterval) {
                // We were paused (GC or CPU pressure) — count it
                selfMetrics.incrementSamplingDelays();
                // No logging — this is Tier 1
            }
        }
        expectedNextTickMs = nowMs + config.getBaseIntervalMs();

        // ── Read heap totals from JMX ─────────────────────────────────────
        var heapUsage = memoryBean.getHeapMemoryUsage();

        // ── Read per-pool breakdown ───────────────────────────────────────
        // Build a map of pool-name → bytes-used
        // We build this map here on the sampler thread so the snapshot is
        // complete and immutable when it reaches the consumer.
        Map<String, Long> poolUsage = new HashMap<>();
        for (MemoryPoolMXBean pool : poolBeans) {
            long used = pool.getUsage().getUsed();
            if (used >= 0) {  // -1 means "not supported" for this pool
                poolUsage.put(pool.getName(), used);
            }
        }

        // ── Build snapshot ────────────────────────────────────────────────
        HeapSnapshot snapshot = new HeapSnapshot(
            nowMs,
            heapUsage.getUsed(),
            heapUsage.getCommitted(),
            heapUsage.getMax(),
            Map.copyOf(poolUsage)   // immutable copy — safe to share across threads
        );

        // ── Write to ring buffer ──────────────────────────────────────────
        boolean written = buffer.write(snapshot);
        if (!written) {
            selfMetrics.incrementDroppedSamples();
            // No logging here — Tier 1
        }

        // ── Update last-sample timestamp ──────────────────────────────────
        selfMetrics.setLastSampleTs(nowMs);
    }
}
```

---

## Step 8 — GcListener

`src/main/java/agent/collector/gc/GcListener.java`

```java
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
```

---

## Step 9 — ProfilerHttpServer

`src/main/java/agent/http/ProfilerHttpServer.java`

```java
package agent.http;

import agent.core.AgentConfig;
import agent.core.CollectorRegistry;
import agent.model.GcEvent;
import agent.model.HeapSnapshot;

import io.javalin.Javalin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Embedded HTTP server that exposes profiling data as JSON.
 *
 * Uses Javalin — a lightweight HTTP framework that starts in under 100ms
 * and has no dependency on Spring or any other web framework.
 *
 * All routes are read-only GET endpoints. The server runs on a daemon
 * thread pool so it does not prevent JVM shutdown.
 */
public final class ProfilerHttpServer {

    private static final Logger log = Logger.getLogger(ProfilerHttpServer.class.getName());

    private final CollectorRegistry registry;
    private final AgentConfig       config;

    public ProfilerHttpServer(CollectorRegistry registry, AgentConfig config) {
        this.registry = registry;
        this.config   = config;
    }

    /**
     * Starts the HTTP server. Returns immediately.
     * The server runs on Javalin's internal daemon thread pool.
     */
    public void start() {
        Javalin app = Javalin.create(cfg -> {
            // Suppress Javalin's own startup banner — we have our own logging
            cfg.showJavalinBanner = false;
        });

        registerRoutes(app);

        // start() is non-blocking — the server runs on daemon threads
        app.start(config.getHttpPort());

        log.info("ProfilerHttpServer started on port " + config.getHttpPort());
        log.info("Dashboard: http://localhost:" + config.getHttpPort() + "/profiler/dashboard");
    }

    private void registerRoutes(Javalin app) {

        // ── GET /profiler/heap ────────────────────────────────────────────
        app.get("/profiler/heap", ctx -> {
            List<HeapSnapshot> samples = registry.heapBuffer().snapshot();

            // Build the response map — LinkedHashMap preserves insertion order
            // which makes the JSON more readable
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("sampleCount", samples.size());

            // Include the most recent snapshot as a "current" object for convenience
            if (!samples.isEmpty()) {
                HeapSnapshot latest = samples.get(samples.size() - 1);
                response.put("current", Map.of(
                    "timestampMs",   latest.timestampMs(),
                    "usedBytes",     latest.usedBytes(),
                    "committedBytes",latest.committedBytes(),
                    "maxBytes",      latest.maxBytes(),
                    "usedMb",        latest.usedBytes() / (1024 * 1024),
                    "poolUsage",     latest.poolUsage()
                ));
            }

            // Include all samples for charting
            response.put("samples", samples);

            ctx.json(response);
        });

        // ── GET /profiler/gc ──────────────────────────────────────────────
        app.get("/profiler/gc", ctx -> {
            List<GcEvent> events = registry.gcBuffer().snapshot();

            // Compute summary statistics
            long totalPauseMs   = events.stream().mapToLong(GcEvent::durationMs).sum();
            long maxPauseMs     = events.stream().mapToLong(GcEvent::durationMs).max().orElse(0);
            double avgPauseMs   = events.isEmpty() ? 0.0
                : (double) totalPauseMs / events.size();

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("eventCount",    events.size());
            response.put("totalPauseMs",  totalPauseMs);
            response.put("maxPauseMs",    maxPauseMs);
            response.put("avgPauseMs",    Math.round(avgPauseMs * 100.0) / 100.0);
            response.put("events",        events);

            ctx.json(response);
        });

        // ── GET /profiler/status ──────────────────────────────────────────
        app.get("/profiler/status", ctx -> {
            ctx.json(registry.selfMetrics()
                .snapshot(config.getInstanceId(), config.getBaseIntervalMs()));
        });

        // ── GET /profiler/summary ─────────────────────────────────────────
        app.get("/profiler/summary", ctx -> {
            List<HeapSnapshot> heapSamples = registry.heapBuffer().snapshot();
            List<GcEvent>      gcEvents    = registry.gcBuffer().snapshot();

            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("instanceId", config.getInstanceId());
            summary.put("heapSampleCount", heapSamples.size());
            summary.put("gcEventCount",    gcEvents.size());

            if (!heapSamples.isEmpty()) {
                HeapSnapshot latest = heapSamples.get(heapSamples.size() - 1);
                summary.put("currentHeapUsedMb",
                    latest.usedBytes() / (1024 * 1024));
            }

            ctx.json(summary);
        });

        // ── GET /profiler/dashboard ───────────────────────────────────────
        // Placeholder for Phase 5 — returns a simple HTML page for now
        app.get("/profiler/dashboard", ctx -> {
            ctx.contentType("text/html");
            ctx.result("""
                <!DOCTYPE html>
                <html>
                <head><title>JVM Profiler Agent</title></head>
                <body>
                  <h1>JVM Profiler Agent</h1>
                  <p>Phase 1 — API only. Dashboard coming in Phase 5.</p>
                  <ul>
                    <li><a href="/profiler/heap">/profiler/heap</a></li>
                    <li><a href="/profiler/gc">/profiler/gc</a></li>
                    <li><a href="/profiler/status">/profiler/status</a></li>
                    <li><a href="/profiler/summary">/profiler/summary</a></li>
                  </ul>
                </body>
                </html>
                """);
        });
    }
}
```

---

## Step 10 — AgentMain

`src/main/java/agent/core/AgentMain.java`

This is the entry point. Keep it simple — its only job is to wire everything
together and return.

```java
package agent.core;

import agent.collector.gc.GcListener;
import agent.collector.heap.HeapSampler;
import agent.http.ProfilerHttpServer;

import java.lang.instrument.Instrumentation;
import java.util.logging.Logger;

/**
 * Java agent entry point.
 *
 * The JVM calls premain() before the target application's main() method.
 * This method must:
 *   1. Complete quickly (under 500ms)
 *   2. Start all background daemon threads
 *   3. Return — the application then starts normally
 *
 * Think of premain() like a constructor: set things up, then get out.
 */
public final class AgentMain {

    private static final Logger log = Logger.getLogger(AgentMain.class.getName());

    /**
     * Called by the JVM when -javaagent: is used at startup.
     *
     * @param agentArgs      the string after -javaagent:agent.jar=<agentArgs>
     *                       null if no arguments were provided
     * @param instrumentation the JVM Instrumentation API — needed for Byte Buddy
     *                        in Phase 2. Stored but not used in Phase 1.
     */
    public static void premain(String agentArgs, Instrumentation instrumentation) {
        log.info("JVM Profiler Agent starting...");

        try {
            // 1. Load configuration from all sources
            AgentConfig config = AgentConfig.load(agentArgs);

            // 2. Create the registry that all components share
            CollectorRegistry registry = new CollectorRegistry();

            // 3. Start the heap sampler daemon
            new HeapSampler(
                registry.heapBuffer(),
                registry.selfMetrics(),
                config
            ).start();

            // 4. Start the GC event listener
            new GcListener(registry.gcBuffer()).attach();

            // 5. Start the HTTP server
            new ProfilerHttpServer(registry, config).start();

            log.info("JVM Profiler Agent started successfully — "
                + "port=" + config.getHttpPort()
                + " instanceId=" + config.getInstanceId());

        } catch (Exception e) {
            // If agent setup fails, log and continue — never crash the target app
            log.severe("JVM Profiler Agent failed to start: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Called when attaching to an already-running JVM dynamically.
     * Delegates to premain() — same setup logic.
     */
    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        premain(agentArgs, instrumentation);
    }
}
```

---

## Step 11 — Build the Agent JAR

```bash
cd jvm-profiler-agent
mvn package -DskipTests
```

Expected output (last few lines):
```
[INFO] Replacing original artifact with shaded artifact.
[INFO] BUILD SUCCESS
```

The agent JAR is at `target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar`.

### Verify Shade Relocation

Run this to confirm Javalin and Jackson are relocated inside the JAR:

```bash
jar -tf target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar | grep "agent/shaded"
```

You should see output like:
```
agent/shaded/javalin/Javalin.class
agent/shaded/jackson/databind/ObjectMapper.class
...
```

If you see `io/javalin/` or `com/fasterxml/` without the `agent/shaded/` prefix,
the relocation is broken — check the pom.xml shade configuration.

---

## Step 12 — Create the Demo App

The demo app is a simple Spring Boot application used to test the agent.

Create `demo-app/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.4</version>
  </parent>

  <groupId>io.github.razahginanjar</groupId>
  <artifactId>jvm-profiler-demo-app</artifactId>
  <version>1.0.0-SNAPSHOT</version>

  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

Create `demo-app/src/main/java/demo/DemoApplication.java`:

```java
package demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;

@SpringBootApplication
public class DemoApplication {
    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }
}

@RestController
class DemoController {

    // Simple endpoint — returns a greeting
    @GetMapping("/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Hello from the demo app!");
    }

    // Slow endpoint — simulates a 200ms database call
    @GetMapping("/slow")
    public Map<String, String> slow() throws InterruptedException {
        Thread.sleep(200);
        return Map.of("message", "That took a while...");
    }

    // Memory endpoint — allocates and returns a large list
    // Useful for testing heap growth detection later
    @GetMapping("/allocate")
    public Map<String, Object> allocate() {
        byte[] data = new byte[1024 * 1024];  // 1MB allocation
        return Map.of("message", "Allocated 1MB", "size", data.length);
    }
}
```

Build the demo app:
```bash
cd demo-app
mvn package -DskipTests
```

---

## Step 13 — Run and Verify

### 13.1 Attach the Agent to the Demo App

```bash
java \
  -javaagent:../jvm-profiler-agent/target/jvm-profiler-agent-1.0.0-SNAPSHOT.jar \
  -jar target/jvm-profiler-demo-app-1.0.0-SNAPSHOT.jar
```

You should see log lines like:
```
INFO agent.core.AgentConfig: AgentConfig loaded — port=7070 interval=10ms instanceId=your-host:7070
INFO agent.collector.heap.HeapSampler: HeapSampler started — interval=10ms
INFO agent.collector.gc.GcListener: GcListener attached to 2 GC beans
INFO agent.http.ProfilerHttpServer: ProfilerHttpServer started on port 7070
INFO agent.core.AgentMain: JVM Profiler Agent started successfully
  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \   ← Spring Boot starts after agent
...
```

### 13.2 Call the Endpoints

Open a new terminal and run:

```bash
# Heap data
curl -s http://localhost:7070/profiler/heap | python3 -m json.tool

# GC events (may be empty if no GC has occurred yet — that is fine)
curl -s http://localhost:7070/profiler/gc | python3 -m json.tool

# Agent status
curl -s http://localhost:7070/profiler/status | python3 -m json.tool

# Trigger some activity on the demo app
curl http://localhost:8080/hello
curl http://localhost:8080/slow
curl http://localhost:8080/allocate
```

### 13.3 Expected Output

`GET /profiler/heap` should return something like:
```json
{
  "sampleCount": 87,
  "current": {
    "timestampMs": 1748000000000,
    "usedBytes": 52428800,
    "committedBytes": 134217728,
    "maxBytes": 4294967296,
    "usedMb": 50,
    "poolUsage": {
      "G1 Eden Space": 10485760,
      "G1 Old Gen": 41943040,
      "Metaspace": 45678912
    }
  },
  "samples": [ ... ]
}
```

`GET /profiler/status` should return:
```json
{
  "instanceId": "your-host:7070",
  "uptimeMs": 15432,
  "agentHeapUsedBytes": 4194304,
  "droppedSamples": 0,
  "samplingDelays": 0,
  "lastSampleTimestampMs": 1748000000000,
  "baseIntervalMs": 10
}
```

If `droppedSamples` is 0 — great. If it is non-zero — the ring buffer is too
small or the sampler is running too fast. This is unlikely at 10ms interval
with 1000 slots.

### 13.4 Verify Daemon Threads

While the app is running, open another terminal and run:

```bash
# Find the process ID
jps -l
# Output example: 12345 demo.DemoApplication

# Show all threads
jstack 12345 | grep "profiler"
```

You should see:
```
"profiler-heap-sampler" #25 daemon prio=1 ...
```

The word `daemon` must be there. If it is not, the thread setup is wrong.

---

## Step 14 — Write the Unit Tests

### 14.1 RingBuffer Tests

`src/test/java/agent/buffer/RingBufferTest.java`

```java
package agent.buffer;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
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
}
```

### 14.2 AgentSelfMetrics Tests

`src/test/java/agent/core/AgentSelfMetricsTest.java`

```java
package agent.core;

import org.junit.jupiter.api.Test;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class AgentSelfMetricsTest {

    @Test
    void allCountersStartAtZero() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        var snap = m.snapshot("test:7070", 10L);

        assertEquals(0, snap.droppedSamples());
        assertEquals(0, snap.samplingDelays());
        assertEquals(0, snap.lastSampleTimestampMs());
    }

    @Test
    void incrementDroppedSamplesAccurately() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.incrementDroppedSamples();
        m.incrementDroppedSamples();
        assertEquals(2, m.snapshot("x", 10).droppedSamples());
    }

    @Test
    void setLastSampleTsIsVisible() {
        AgentSelfMetrics m = new AgentSelfMetrics();
        m.setLastSampleTs(12345L);
        assertEquals(12345L, m.snapshot("x", 10).lastSampleTimestampMs());
    }

    @Test
    void countersAreConcurrentlySafe() throws InterruptedException {
        AgentSelfMetrics m = new AgentSelfMetrics();
        int threads    = 8;
        int perThread  = 1000;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                for (int j = 0; j < perThread; j++) {
                    m.incrementDroppedSamples();
                }
            });
        }
        pool.shutdown();
        pool.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals((long) threads * perThread,
            m.snapshot("x", 10).droppedSamples());
    }

    @Test
    void agentHeapUsedBytesIsPositive() {
        AgentSelfMetrics m  = new AgentSelfMetrics();
        var snap = m.snapshot("x", 10);
        assertTrue(snap.agentHeapUsedBytes() > 0,
            "Agent heap usage should be measurable");
    }
}
```

### 14.3 AgentConfig Tests

`src/test/java/agent/core/AgentConfigTest.java`

```java
package agent.core;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentConfigTest {

    @Test
    void loadsDefaultsWhenNoArgsProvided() {
        AgentConfig config = AgentConfig.load(null);
        assertEquals(7070, config.getHttpPort());
        assertEquals(10L,  config.getBaseIntervalMs());
        assertNotNull(config.getInstanceId());
    }

    @Test
    void parsesPortFromArgString() {
        AgentConfig config = AgentConfig.load("port=9090");
        assertEquals(9090, config.getHttpPort());
    }

    @Test
    void parsesMultipleArgsFromArgString() {
        AgentConfig config = AgentConfig.load("port=8888,interval=20");
        assertEquals(8888, config.getHttpPort());
        assertEquals(20L,  config.getBaseIntervalMs());
    }

    @Test
    void clampsIntervalBelowMinimum() {
        AgentConfig config = AgentConfig.load("interval=1");
        // 1ms is below the 5ms minimum — should be clamped to 5
        assertEquals(5L, config.getBaseIntervalMs());
    }

    @Test
    void handlesInvalidPortGracefully() {
        AgentConfig config = AgentConfig.load("port=notanumber");
        // Invalid port falls back to default
        assertEquals(7070, config.getHttpPort());
    }

    @Test
    void handlesNullArgsGracefully() {
        assertDoesNotThrow(() -> AgentConfig.load(null));
    }

    @Test
    void handlesEmptyArgsGracefully() {
        assertDoesNotThrow(() -> AgentConfig.load(""));
    }
}
```

### 14.4 Run All Tests

```bash
mvn test
```

Expected output:
```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

---

## Step 15 — Phase 1 Checklist

Before marking Phase 1 complete, verify every item:

- [ ] `mvn package` succeeds and produces a JAR
- [ ] `jar -tf target/agent.jar | grep agent/shaded` shows relocated classes
- [ ] Agent attaches to demo app without any exception in the output
- [ ] `GET /profiler/heap` returns JSON with `usedBytes`, `committedBytes`, `maxBytes`
- [ ] `GET /profiler/gc` returns a JSON array (may be empty — that is fine)
- [ ] `GET /profiler/status` returns `droppedSamples: 0` on a healthy run
- [ ] `jstack <pid> | grep profiler` shows `daemon` thread
- [ ] All 16 unit tests pass
- [ ] `droppedSamples` is 0 after 30 seconds of running

Once all boxes are checked:

```bash
git checkout develop
git merge --no-ff phase/1-agent-core -m "Merge Phase 1: Agent Core & JVM Collection"
git tag phase-1-complete
git push origin develop --tags
git checkout -b phase/2-spring-integration
```

---

## Troubleshooting Phase 1

**Problem:** `ClassNotFoundException: io.javalin.Javalin`
**Cause:** Shade plugin is not bundling Javalin or relocation broke the class names
**Fix:** Check pom.xml shade configuration. Run `jar -tf agent.jar | grep javalin`

---

**Problem:** Agent logs appear but Spring Boot fails to start
**Cause:** Port conflict — Javalin default is 7070, Spring Boot default is 8080. These are different ports and should not conflict. If you changed config, verify.
**Fix:** Check if something else is already on port 7070: `lsof -i :7070`

---

**Problem:** `GET /profiler/gc` always returns an empty array
**Cause:** No GC has occurred yet. The JVM may not GC during short tests.
**Fix:** Call `/allocate` on the demo app many times to force heap pressure, or run `System.gc()` via JConsole.

---

**Problem:** `samplingDelays` keeps growing rapidly
**Cause:** The system is under CPU pressure or GC is very frequent
**Fix:** This is informational — it means the sampler is being delayed. Normal on a loaded system. If it happens on an idle system, check if something else is consuming CPU.

---

*End of Phase 1 Implementation Guide.*
*Next: [Phase 2 — Spring Boot Integration](./PHASE-2-SPRING-INTEGRATION.md)*
