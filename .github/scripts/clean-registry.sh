#!/usr/bin/env bash
# Keeps the staging registry inside its plan after a successful deploy (step 1.15): deletes every image tag except the
# one just deployed and the one the app ran before it (registry-tags-to-delete.jq decides), starts garbage collection,
# then prints the storage used and warns above 80% of the plan's limit.
#
# Usage: clean-registry.sh <deployed tag> <tags running before the deploy, as a JSON array>
# Environment: KEEP_TAGS (default 2; 20 on the Basic plan), LIMIT_MIB (default 500, the Starter plan). Needs doctl
# (authenticated, with registry delete) and jq. Prints only tag names and sizes.
set -euo pipefail

deployed="$1"
running="$2"
repository="northstar"
scripts="$(dirname "$0")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

doctl registry repository list-tags "$repository" --output json > "$work/tags.json"
jq -c --arg deployed "$deployed" --argjson running "$running" --argjson keep "${KEEP_TAGS:-2}" \
  -f "$scripts/registry-tags-to-delete.jq" "$work/tags.json" > "$work/plan.json"

echo "Keeping: $(jq -r '.keep | join(", ")' "$work/plan.json")"
delete="$(jq -r '.delete | join(" ")' "$work/plan.json")"
if [ -n "$delete" ]; then
  echo "Deleting: $delete"
  # Tag names are checked by registry-tags-to-delete.jq (no spaces), so splitting the list is safe.
  # shellcheck disable=SC2086
  doctl registry repository delete-tag "$repository" $delete --force
else
  echo "Nothing to delete"
fi

# The registry is read-only while garbage collection runs; the next deploy waits for it (wait-for-registry-gc.sh).
if doctl registry garbage-collection start --include-untagged-manifests --force > /dev/null 2> "$work/gc.err"; then
  echo "Garbage collection started"
elif doctl registry garbage-collection get-active > /dev/null 2>&1; then
  echo "::notice::A garbage collection is already running, so none was started; it frees the deleted tags' space too."
else
  echo "::error::Garbage collection could not start: $(head -c 300 "$work/gc.err")"
  exit 1
fi

doctl registry get --output json > "$work/registry.json"
jq -r --argjson limit_mib "${LIMIT_MIB:-500}" -f "$scripts/registry-storage.jq" "$work/registry.json"
