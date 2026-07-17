# Contributing to openJiuwen agent-solution

Thank you for your interest in contributing. This repository provides runtime extension adapters, AgentCore SDK extensions, concrete Agents, and examples. Contributions may target any of these independent projects, documentation, tests, or integration feedback.

## Before You Start

- Runtime infrastructure changes usually belong in [agent-runtime-java](https://gitcode.com/openJiuwen/agent-runtime-java). This repository should stay focused on runtime extension adapters and example wiring.
- Agent execution core changes usually belong in [agent-core-java](https://gitcode.com/openJiuwen/agent-core-java).
- Read [README.md](README.md), [README.zh.md](README.zh.md), and the adapter design documents before making changes:
  - [agent-service-adapters-agentcore-ext-design.md](common/agent-runtime-ext-java/doc/agent-service-adapters-agentcore-ext-design.md)
  - [agent-service-adapters-versatile-design.md](common/agent-runtime-ext-java/doc/agent-service-adapters-versatile-design.md)

## Development Setup

1. Install **Java 17+** and **Maven 3.9+**.
2. Make sure matching dependencies are available:
   - `com.openjiuwen:agent-runtime-java:0.1.0`
   - `com.openjiuwen:agent-core-java:0.1.13`
3. Build the extension modules:

   ```bash
   mvn -f common/agent-core-ext-java/pom.xml clean install
   mvn -f common/agents/pom.xml clean install
   mvn -f common/agent-runtime-ext-java/pom.xml clean install
   ```

4. Build the example projects you are changing:

   ```bash
   mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean install
   mvn -f common/example/agentcore-ext-remote-a2a-tool-demo/pom.xml clean install
   mvn -f common/example/agentcore-ext-deepagent-remote-a2a-demo/pom.xml clean install
   mvn -f common/example/multi-deep-research-demo/pom.xml clean install
   ```

## Running Tests

Run tests for the extension modules:

```bash
mvn -f common/agent-core-ext-java/pom.xml clean test
mvn -f common/agents/pom.xml clean test
mvn -f common/agent-runtime-ext-java/pom.xml clean test
```

Run tests for an individual example when your change affects it:

```bash
mvn -f common/example/versatile-a2a-adapter-demo/pom.xml clean test
```

Some examples may depend on local configuration, remote services, or runtime wiring. If a test cannot be run locally, explain the reason and include the verification you did run in the pull request.

## Code Guidelines

- Keep adapter responsibilities separate from runtime and core responsibilities.
- Keep `agent-runtime-ext-java`, `agent-core-ext-java`, and `agents` as independent Maven projects.
- Keep core SDK extensions free of Spring and register SDK behavior explicitly.
- Do not reimplement HTTP ingress, A2A protocol handling, remote card discovery, communication, or session orchestration in this repository; those belong to `agent-runtime-java`.
- Keep Agent execution behavior in `agent-core-java` unless the change is specifically about adapter wiring.
- Match the naming, style, and testing pattern of the module you edit.
- Avoid unrelated refactors in contribution pull requests.
- Keep public configuration keys, Maven coordinates, and behavior changes deliberate and documented.

## Documentation

- Update [README.md](README.md) and [README.zh.md](README.zh.md) when changing scope, quick-start steps, supported examples, Maven coordinates, or user-facing behavior.
- Update [common/README.md](common/README.md) when changing the `common` module layout or build process.
- Update the adapter design documents when changing adapter architecture, boundaries, or data flow.
- Keep example README files aligned with their startup commands and request scripts.

## Pull Requests

1. Create a feature branch from the branch indicated by maintainers.
2. Keep the pull request focused on one topic.
3. Describe **what** changed and **why**.
4. Link related issues or design discussions when available.
5. Include test results, affected modules, and compatibility notes.
6. For behavior changes, mention impact on A2A delegation, HTTP/SSE adaptation, metadata forwarding, configuration keys, or example startup flow.

## Issues

When reporting bugs, include:

- Java and Maven versions
- Module or example name
- Steps to reproduce
- Expected vs actual behavior
- Relevant configuration and logs

For feature requests, explain the use case and why it belongs in `agent-solution` rather than `agent-runtime-java` or `agent-core-java`.

## License

By contributing, you agree that your contributions will be licensed under the [Apache License 2.0](LICENSE).
