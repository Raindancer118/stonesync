<div align="center">

# StoneSync

**Real-time multi-user collaboration for Obsidian, on your own server.**

Multiple people editing the same vault, at the same time, with live cursors —
no cloud subscription, no vendor lock-in, self-hosted on hardware you control.

[![CI](https://github.com/Raindancer118/stonesync/actions/workflows/ci.yml/badge.svg)](https://github.com/Raindancer118/stonesync/actions/workflows/ci.yml)
[![License: AGPL v3](https://img.shields.io/badge/License-AGPL%20v3-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/server-Java%2021%20%2F%20Spring%20Boot-orange)](server)
[![Obsidian Plugin](https://img.shields.io/badge/plugin-TypeScript%20%2F%20Yjs-8a2be2)](plugin)

</div>

---

## What is this?

Obsidian is single-player by design. StoneSync makes it multiplayer.

Install the plugin, point it at your own StoneSync server, and every note
becomes a shared, conflict-free document — edited by however many people have
access, with each person's cursor visible to everyone else in real time.
Behind the scenes it's a [Yjs](https://yjs.dev) CRDT, so concurrent edits
never produce merge conflicts or "(Conflicted copy)" files: everyone's
changes just converge.

```mermaid
flowchart LR
    subgraph Devices
        A[Obsidian<br/>Desktop] 
        B[Obsidian<br/>Mobile]
        C[Obsidian<br/>Desktop]
    end
    A <-- WebSocket<br/>ticket auth --> S[(StoneSync Server<br/>Spring Boot)]
    B <-- WebSocket<br/>ticket auth --> S
    C <-- WebSocket<br/>ticket auth --> S
    S <--> DB[(Postgres<br/>metadata + Yjs log)]
    S <--> FS[/Filesystem<br/>attachments/]
```

## How it stays fast *and* honest

The server never tries to understand your notes. It's a deliberately "dumb"
relay: Yjs updates are opaque binary blobs it stores and forwards, never
parses. That keeps the Java side small and lets the actual CRDT logic — the
part that has to be correct — live entirely in the well-tested Yjs library on
the client.

| Concern | How StoneSync handles it |
|---|---|
| **Conflicting edits** | CRDT merge (Yjs) — no conflict files, no lost writes |
| **Live cursors / presence** | Separate ephemeral channel, never persisted |
| **Auth on the sync socket** | Short-lived, single-use tickets (Obsidian's WebSocket client can't send custom headers, so long-lived keys never touch the URL or a proxy log) |
| **Access control** | Every document/attachment/socket operation is checked against real vault membership — no security-through-obscurity on UUIDs |
| **Attachments** | Content-addressed by a *server-verified* SHA-256, uploaded only once per unique file |
| **Renames** | Pure metadata update — the document's identity (and its edit history) never changes, so links don't break |
| **Deletes** | Tombstoned, not dropped — offline devices reconcile safely when they reconnect |

## Project layout

```
StoneSync/
├── plugin/             Obsidian plugin — TypeScript, esbuild, Yjs
├── server/              Sync backend — Java 21, Spring Boot, Postgres
├── docker-compose.yml   Server + Postgres, self-hosted
└── .github/workflows/   CI (build + test) and release automation
```

## Getting started

### Run the server

```bash
cp .env.example .env      # set DB_PASSWORD
docker compose up --build
```

On first boot with `BOOTSTRAP_ADMIN_EMAIL` set, the server prints a one-time
admin API key to its logs — that's your master key for creating additional
users, vaults, and devices via `/api/admin/*`.

### Build the plugin

```bash
cd plugin
npm install --legacy-peer-deps
npm test
npm run build
```

Copy `main.js`, `manifest.json` and `styles.css` into
`<your-vault>/.obsidian/plugins/stonesync/`, then enable the plugin in
Obsidian's settings and point it at your server + API key.

### See it working

```bash
cd plugin
npm test                                    # unit tests (incl. the CM6/editor binding)
STONESYNC_URL=http://localhost:8080 \
STONESYNC_API_KEY=... STONESYNC_VAULT_ID=... npm run test:e2e
```

The end-to-end suite drives two real clients against a running server: edits in both
directions, live cursor presence appearing and disappearing, and a late joiner catching up
on the full history.

## What "live" means here

* Every **open** Markdown editor gets its own sync session - not just the focused pane, so a
  note sitting in a background tab (or on the other device's screen) keeps receiving edits.
* Remote cursors and selections are rendered inline, with each collaborator's name and color;
  the status bar shows who else is in the current note.
* Presence is announced to newcomers too, so someone joining a note sees the people already
  in it immediately, instead of only once they move their cursor.
* Notes you do *not* have open are not rewritten under you - they sync the moment you open
  them (creations and deletions still arrive live, vault-wide).

## Status

This is the MVP: the plugin and server deliver real, working multi-user
sync directly inside Obsidian — text sync, live cursors, attachment sync,
vault-scoped access control. A standalone web viewer (for reviewing a vault
without installing Obsidian) is a deliberately separate milestone and not
part of this release yet.

## License

[AGPL-3.0](LICENSE) — if you run a modified version of this server as a
network service, its source must remain available to its users.
