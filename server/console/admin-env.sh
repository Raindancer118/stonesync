#!/bin/sh
# Shared helper for console commands that write through the Admin REST API
# (rather than raw SQL) so they go through the app's own business logic.
# Requires CONSOLE_API_KEY to be set on the container (a dedicated admin
# API key, separate from any individual operator's own key).
# Usage: . admin-env.sh
set -eu

if [ -z "${CONSOLE_API_KEY:-}" ]; then
	echo "CONSOLE_API_KEY is not set on this container - admin write commands are unavailable." >&2
	exit 1
fi

ADMIN_BASE="http://localhost:8080/api/admin"
ADMIN_AUTH="Authorization: Bearer $CONSOLE_API_KEY"
