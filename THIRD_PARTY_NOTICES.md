# Third-Party Notices

The shaded agent jar bundles runtime dependencies. This file tracks dependency
review scope for open-source releases; it is not a substitute for a legal
review before publishing binaries.

## Direct Runtime Dependencies

Declared in `pom.xml`:

- Javalin
- Jackson Databind, Jackson Core, Jackson Annotations
- Byte Buddy
- xerial sqlite-jdbc
- async-profiler platform artifacts for embedded native profiling on supported
  Linux/macOS targets
- SLF4J Simple and SLF4J API
- Jetty artifacts pulled through Javalin and aligned by the Jetty BOM
- Kotlin standard library pulled through Javalin
- ASM artifacts pulled through Jetty/Javalin dependencies

## Direct Test Dependencies

- JUnit Jupiter
- Mockito

## Release Review Requirements

Before a public binary release:

- Generate the full dependency tree from a clean checkout.
- Verify each runtime dependency license and notice requirement.
- Confirm shaded packages do not remove required notices.
- Confirm sqlite-jdbc native-library redistribution requirements.
- Confirm async-profiler native-library redistribution requirements for each
  bundled platform artifact.
- Update this file with exact dependency versions and license summaries.

Useful commands:

```powershell
mvn dependency:tree
mvn dependency:list
```
