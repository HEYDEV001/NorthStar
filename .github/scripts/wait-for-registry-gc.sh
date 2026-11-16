#!/usr/bin/env bash
# Waits until no garbage collection is running on the registry (step 1.15): the registry is read-only while one runs,
# so a push would fail. Gives up after about 15 minutes. `get-active` exits non-zero when none is running.
set -euo pipefail

for _ in $(seq 1 90); do
  if ! doctl registry garbage-collection get-active > /dev/null 2>&1; then
    exit 0
  fi
  echo "Registry garbage collection is running; waiting"
  sleep "${WAIT_SECONDS:-10}"
done
echo "::error::Registry garbage collection still running after 15 minutes; re-run this job once it finishes."
exit 1
