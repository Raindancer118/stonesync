# StoneSync

Obsidian.MD-Plugin mit echtem Live-Multi-User-Sync (Yjs CRDT, Live-Cursor-Presence)
gegen einen selbst gehosteten Java-Server.

## Struktur

- `plugin/` — Obsidian-Plugin (TypeScript, esbuild, Yjs)
- `server/` — Sync-Backend (Java 21, Spring Boot, Postgres)
- `docker-compose.yml` — Server + Postgres lokal/self-hosted starten

## Server lokal starten

```
cp .env.example .env   # DB_PASSWORD setzen
docker compose up --build
```

## Plugin lokal bauen

```
cd plugin
npm install --legacy-peer-deps
npm test
npm run build
```

`main.js`, `manifest.json` nach `<Vault>/.obsidian/plugins/stonesync/` kopieren
und in Obsidian aktivieren.

## Status

MVP-Fokus: Plugin + Server mit echtem Multi-User-Sync direkt in Obsidian
(Text-Sync via Yjs, Live-Cursor-Presence, Attachment-Sync). Ein Web-Viewer
ist bewusst noch nicht Teil dieses Standes.
