#!/bin/sh
# Shared helper for console commands that use the regular (non-/admin) API as a user.
# The CONSOLE_API_KEY's account needs to be an account-wide ADMIN for these to reach every
# vault - the bootstrap account is one by default, otherwise use ss-make-admin.
# Usage: . api-env.sh
set -eu

if [ -z "${CONSOLE_API_KEY:-}" ]; then
	echo "CONSOLE_API_KEY is not set on this container - these commands are unavailable." >&2
	exit 1
fi

API_BASE="http://localhost:8080/api"
API_AUTH="Authorization: Bearer $CONSOLE_API_KEY"
