#!/bin/bash
set -e

# Start Trino in the background
/usr/lib/trino/bin/run-trino &
TRINO_PID=$!

# Wait for Trino to be ready
echo "Waiting for Trino to start..."
until trino --execute "SELECT 1" &>/dev/null; do
  sleep 2
done

echo "Trino is ready. Running initialization scripts..."

# Execute all SQL files in /docker-entrypoint-initdb.d/
if [ -d "/docker-entrypoint-initdb.d" ]; then
  for f in /docker-entrypoint-initdb.d/*.sql; do
    if [ -f "$f" ]; then
      echo "Executing $f..."
      trino --file "$f"
      echo "Completed $f"
    fi
  done
fi

echo "Initialization complete."

# Wait for Trino process
wait $TRINO_PID
