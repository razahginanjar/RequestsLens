# Build And Release Guide

This project currently supports verified local builds and release artifact
preparation. It does not yet publish to Maven Central or a package registry.

Current project milestone: `v0.1.2`. The Maven artifact version is controlled
by `pom.xml` and is `0.1.2-SNAPSHOT` for the current development build. For a
published `v0.1.2` release, set it to `0.1.2` before tagging.

## Build Commands

Fast unit-test cycle:

```powershell
mvn test
```

Full verification:

```powershell
mvn verify
```

Release artifact verification:

```powershell
mvn -Prelease-artifacts clean verify
```

The `release-artifacts` profile adds a source jar beside the shaded Java agent
jar.

## Release Artifact Script

Prepare local release files:

```powershell
.\scripts\prepare-release.ps1
```

Validate the Maven version before building:

```powershell
.\scripts\prepare-release.ps1 -ExpectedVersion 0.1.2-SNAPSHOT
```

Output goes to:

```text
target/release/
```

The directory contains:

- the shaded agent jar,
- the source jar when the release profile is active,
- license, notice, README, and release guide files,
- SHA-256 checksum files,
- `RELEASE_SUMMARY.md` with version, commit, and file inventory.

## GitHub Actions

The repository includes:

- `.github/workflows/ci.yml` - runs `mvn clean verify` on Java 17 and 21 across
  Ubuntu and Windows.
- `.github/workflows/release-artifacts.yml` - builds `target/release/` on
  `v*` tag pushes and publishes those files to the matching GitHub Release.

The release workflow publishes GitHub Release assets only. It does not publish
to Maven Central or sign artifacts; those steps still need repository
credentials, maintainer identity, and signing keys.

Tag-push publishing:

```powershell
git tag v0.1.2
git push origin v0.1.2
```

The workflow uses the pushed tag as the GitHub Release tag. It runs
`scripts/prepare-release.ps1`, uploads `target/release/` as a workflow artifact,
and attaches the release files to the GitHub Release.

## Artifact Contract

Primary runtime artifact:

```text
target/requestlens-agent-<version>.jar
```

It must include Java agent manifest entries:

- `Premain-Class: agent.core.AgentMain`
- `Agent-Class: agent.core.AgentMain`
- `Can-Redefine-Classes: true`
- `Can-Retransform-Classes: true`

The jar is shaded so the target application does not need profiler runtime
dependencies on its classpath.

## Before Publishing

Before public binary publishing:

- Confirm `pom.xml` has final SCM, repository URL, and issue tracker metadata.
- Decide whether artifacts are published to GitHub Releases, Maven Central, or
  both.
- Add signing if publishing to a central package registry.
- Run and archive the overhead benchmark.
- Update `COMPATIBILITY.md` with the exact CI matrix result.
- Move new `changelog.md` entries from `Unreleased` to the release version.
