#!/bin/sh
# Shared helper: derive PG* env vars for `psql` from the JDBC-style DB_URL
# the app itself uses (DB_URL/DB_USER/DB_PASSWORD), so console commands and
# the app always talk to the same database without a second set of secrets.
# Usage: . pg-env.sh
set -eu

# DB_URL looks like: jdbc:postgresql://postgres:5432/stonesync
hostport_db=$(echo "$DB_URL" | sed 's#jdbc:postgresql://##')
hostport=$(echo "$hostport_db" | cut -d/ -f1)
export PGHOST=$(echo "$hostport" | cut -d: -f1)
export PGPORT=$(echo "$hostport" | cut -d: -f2)
export PGDATABASE=$(echo "$hostport_db" | cut -d/ -f2)
export PGUSER="$DB_USER"
export PGPASSWORD="$DB_PASSWORD"
