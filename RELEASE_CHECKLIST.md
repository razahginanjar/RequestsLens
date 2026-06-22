# Release Checklist

Use this checklist before publishing a public release.

## Repository Metadata

- Add real repository URL, SCM metadata, and issue tracker metadata to
  `pom.xml`.
- Confirm Maven coordinates: `io.github.razahginanjar:requestlens-agent`.
- Confirm project name, description, and license metadata.
- Confirm `LICENSE`, `NOTICE`, and `THIRD_PARTY_NOTICES.md`.
- Confirm README badges only after real CI and release URLs exist.

## Verification

- Run `mvn clean verify`.
- Run `mvn -Prelease-artifacts clean verify`.
- Run `scripts/prepare-release.ps1 -ExpectedVersion <version>`.
- Run the manual smoke test in `smoke_test.md`.
- Run the optional persistence smoke test.
- Run `scripts/run-overhead-benchmark.ps1` and archive the Markdown/CSV result.
- Confirm `stress-test.sh` still works against the demo app or update it.
- Check the shaded jar starts a target app without internet access.

## Compatibility

- Update `COMPATIBILITY.md` with exact tested versions.
- Test at least Java 17 and 21 before a stable release.
- Test at least one Linux environment before publishing binaries.
- Record unsupported frameworks and known limitations.

## Security

- Set a high-entropy auth token in all public examples that expose the profiler
  beyond loopback.
- Confirm sensitive fields are redacted when auth is disabled and host is not
  loopback-only.
- Verify CORS remains disabled by default.
- Verify security reporting is enabled in the repository host.

## Changelog And Versioning

- Move new `changelog.md` entries from `Unreleased` to the release version.
- Update Maven `<version>`.
- For the current `v0.1.7` cycle, use `0.1.7-SNAPSHOT` for development builds
  or `0.1.7` for a tagged release.
- Tag the commit.
- Keep generated artifacts out of git.

## Publishing

- Build from a clean checkout.
- Publish source and binary artifacts from the same commit.
- Attach checksum files if distributing jars directly.
- Include benchmark and compatibility notes in the release notes.
- Push a `v*` tag to trigger `.github/workflows/release-artifacts.yml`, which
  builds `target/release/` and publishes the files to a GitHub Release.
