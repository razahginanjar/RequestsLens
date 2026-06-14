# Feature Scope

This document records framework scope decisions for the profiler agent.

## Current Framework Boundary

The agent currently has two layers:

- Generic JVM monitoring: heap, GC, process/system CPU, profiler-thread CPU,
  embedded profiler HTTP APIs, SQLite persistence, and stack-sampling
  flamegraph data.
- Spring MVC integration: endpoint grouping, request-trace roots, and bean
  memory ranking.

The generic JVM layer can run beside many JVM applications. The Spring MVC
integration is framework-specific because it instruments:

- `org.springframework.web.servlet.DispatcherServlet#doDispatch` for request
  start/end, route pattern, heap delta, request CPU, and trace root creation.
- `org.springframework.context.support.AbstractApplicationContext#refresh` for
  Spring bean discovery.

That means Quarkus and Micronaut are not supported for endpoint/request/bean
profiling today. They may still expose generic JVM metrics, but their HTTP
requests will not appear as first-class endpoint rows until framework adapters
are added.

## Quarkus Feasibility

Quarkus support is possible in JVM mode, but it is a heavy update.

Why it is not a small switch:

- Quarkus HTTP is built on Vert.x. Servlet support is optional through Undertow,
  and RESTEasy can run without Servlet involvement.
- A Quarkus adapter must capture request start/end around the Vert.x/RESTEasy
  path, resolve route templates, and create the request trace context that
  Spring currently creates in `DispatcherServletAdvice`.
- Bean discovery would need an Arc/CDI adapter rather than Spring
  `ApplicationContext` reflection.
- Quarkus native executable support is out of scope for this Java-agent path.
  Native-image support would need a separate design, not only new Byte Buddy
  advice.

Recommended first target: Quarkus JVM mode with RESTEasy Reactive/RESTEasy on
the default Vert.x HTTP stack.

## Micronaut Feasibility

Micronaut support is possible in JVM mode, but it is also a heavy update.

Why it is not a small switch:

- Micronaut HTTP server behavior is based on Netty and framework filters, not
  Spring `DispatcherServlet`.
- A Micronaut adapter must capture request start/end through the Micronaut HTTP
  server/filter path, resolve route templates, and create the request trace
  context.
- Bean discovery would need a Micronaut `BeanContext` adapter rather than
  Spring `ApplicationContext` reflection.

Recommended first target: Micronaut JVM mode with the default Netty HTTP
server.

## Required Architecture Update

Before implementing Quarkus or Micronaut, split the current
`SpringInstrumentation` responsibilities into adapter-oriented pieces:

1. `TraceInstrumentation`
   Installs package-scoped method/allocation instrumentation independent of any
   web framework.

2. `FrameworkAdapter`
   A small internal interface with methods such as `name()`, `install()`,
   `supportsEndpointProfiling()`, and `supportsBeanDiscovery()`.

3. `SpringMvcAdapter`
   Moves the current DispatcherServlet and ApplicationContext instrumentation
   out of the generic installer.

4. `QuarkusAdapter`
   Installs Vert.x/RESTEasy request advice and Arc/CDI bean discovery.

5. `MicronautAdapter`
   Installs Micronaut/Netty request advice and BeanContext discovery.

6. API capability reporting
   `/profiler/status` and `/profiler/api` should report detected frameworks,
   active adapters, and unsupported requested adapters.

7. Test fixtures
   Add `demo-quarkus` and `demo-micronaut` fixtures plus Failsafe integration
   tests that launch each app with the packaged agent.

## Proposed Config

Default behavior should stay conservative:

```text
profiler.framework.adapters=auto
```

Planned values:

- `auto`: install adapters when known framework classes are present.
- `spring`: install Spring MVC adapter only.
- `quarkus`: install Quarkus adapter only.
- `micronaut`: install Micronaut adapter only.
- comma-separated values such as `spring,micronaut`.

## Suggested Milestones

| Milestone | Scope |
| --- | --- |
| P3a | Document scope and current support boundary |
| P3b | Split generic tracing from Spring-specific instrumentation |
| P3c | Add adapter capability reporting to status/API |
| P3d | Add Micronaut JVM adapter and fixture |
| P3e | Add Quarkus JVM adapter and fixture |
| P3f | Expand compatibility matrix and smoke/integration docs |

## Weight

Supporting Quarkus and Micronaut properly is a multi-branch feature, not a
single-day patch. The main cost is not CPU/heap monitoring; those are already
generic. The main cost is HTTP request lifecycle interception, route-template
resolution, bean discovery, and integration test fixtures for each framework.

## Reference Docs

- Quarkus HTTP reference: https://quarkus.io/guides/http-reference
- Quarkus native executable guide: https://quarkus.io/guides/building-native-image
- Micronaut HTTP filters: https://docs.micronaut.io/latest/guide/#filters
