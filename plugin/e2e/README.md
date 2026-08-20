# End-to-end suite

Runs the real plugin networking stack (ticket handshake -> WebSocket -> Yjs relay -> awareness)
against a real StoneSync server, with two clients on the same document.

```bash
export STONESYNC_URL=http://localhost:8080
export STONESYNC_API_KEY=<api key printed by the server bootstrap>
export STONESYNC_VAULT_ID=<vault uuid>
npm run test:e2e
```

Without those env vars the suite skips itself, so `npm test` stays offline-friendly.
