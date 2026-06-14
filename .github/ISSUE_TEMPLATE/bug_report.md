---
name: Bug report
about: Report a reproducible profiler bug
title: "bug: "
labels: bug
assignees: ""
---

## Summary

Describe the problem and the expected behavior.

## Environment

- Agent commit/version:
- Java version:
- OS:
- Spring Boot version:
- Target web stack: Spring MVC / other
- Persistence enabled: yes/no
- Auth enabled: yes/no
- Tracing enabled: yes/no

## Reproduction

Steps to reproduce:

1.
2.
3.

## Logs

Paste relevant profiler and target app logs. Redact tokens, credentials, and
customer data.

## Verification

- [ ] Target app starts without the agent.
- [ ] `mvn test` passes.
- [ ] `mvn verify` passes or the failure is included above.
