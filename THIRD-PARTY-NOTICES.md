# Third-party notices

StoneSync is proprietary (see [LICENSE](LICENSE)) but builds on open-source components.
Each remains under its own license; the list below is what ships in a StoneSync
distribution. Test-only dependencies are marked as such - they are not part of any
distributed artifact.

## Obsidian plugin (`plugin/main.js`)

| Component | License |
|---|---|
| [Yjs](https://github.com/yjs/yjs) | MIT |
| [y-protocols](https://github.com/yjs/y-protocols) | MIT |
| [y-codemirror.next](https://github.com/yjs/y-codemirror.next) | MIT |
| [Obsidian API typings](https://github.com/obsidianmd/obsidian-api) | MIT |
| esbuild, TypeScript, Vitest, jsdom (build/test only) | MIT / Apache-2.0 |

CodeMirror 6 (`@codemirror/*`, MIT) is **not** bundled - the plugin uses the copy that
Obsidian provides at runtime.

## Sync server (`server/`)

| Component | License |
|---|---|
| [Spring Boot / Spring Framework / Spring Security](https://spring.io) | Apache-2.0 |
| [Hibernate ORM](https://hibernate.org) (via Spring Data JPA) | Apache-2.0 (LGPL-2.1 for versions before 5.5) |
| [Flyway Community](https://github.com/flyway/flyway) | Apache-2.0 |
| [Eclipse JGit](https://www.eclipse.org/jgit/) | EDL-1.0 (BSD-3-Clause) |
| [PostgreSQL JDBC Driver](https://jdbc.postgresql.org) | BSD-2-Clause |
| [Project Lombok](https://projectlombok.org) | MIT |
| [JUnit 5](https://junit.org/junit5/), [AssertJ](https://assertj.github.io/doc/), [Mockito](https://site.mockito.org), [Testcontainers](https://testcontainers.com) (test only) | EPL-2.0 / Apache-2.0 / MIT |
| [PostgreSQL](https://www.postgresql.org) (runtime dependency, not distributed) | PostgreSQL License |

Apache-2.0 requires that a copy of the license and any NOTICE file accompany binary
distributions; the Spring Boot fat jar carries these inside `META-INF`. When shipping
StoneSync to a customer, ship this file alongside it.
