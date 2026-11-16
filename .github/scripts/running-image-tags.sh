#!/usr/bin/env bash
# Prints, as a JSON array, the image tags the live staging app runs before this deploy (step 1.15), so the registry
# cleanup keeps them as the rollback target. Usage: running-image-tags.sh <app name>. Prints tag names only.
set -euo pipefail

app_id="$(doctl apps list --output json | jq -r --arg name "$1" '.[] | select(.spec.name == $name) | .id')"
doctl apps spec get "$app_id" --format json \
  | jq -c '[((.services // []) + (.workers // []) + (.jobs // []))[] | .image.tag // empty] | unique'
