#!/usr/bin/env bash
# Fails when the live staging app differs from .do/staging.app.yaml (step 1.15): its components, sizes, commands,
# health path, auto-deploy setting, variable names, scopes and types, and the fixed values the repo sets. The deploy
# changes only image tags on the live spec, so this keeps the repo the source of truth without ever sending a secret.
#
# The live spec holds every value (secrets encrypted). It stays in a temporary folder and nothing from it is printed
# but component and variable names. Usage: check-staging-spec.sh <app name>; needs doctl (authenticated), jq and yq.
set -euo pipefail

app_name="$1"
repo_spec=".do/staging.app.yaml"
scripts="$(dirname "$0")"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

app_id="$(doctl apps list --output json | jq -r --arg name "$app_name" '.[] | select(.spec.name == $name) | .id')"
if [ -z "$app_id" ]; then
  echo "::error::App $app_name does not exist yet. The image is pushed; create the app from it (docs/staging-runbook.md, first deploy), then re-run this job."
  exit 1
fi

doctl apps spec get "$app_id" --format json > "$work/live.json"
yq -o=json '.' "$repo_spec" > "$work/repo.json"
jq -S -f "$scripts/app-spec-shape.jq" "$work/repo.json" > "$work/repo-shape.json"
jq -S -f "$scripts/app-spec-shape.jq" "$work/live.json" > "$work/live-shape.json"

if ! diff -u --label "$repo_spec" --label "live app" "$work/repo-shape.json" "$work/live-shape.json"; then
  echo "::error::The live app differs from $repo_spec (diff above; names, sizes and types only). Make them match: edit the live spec in the dashboard's App Spec editor, or this file (docs/staging-runbook.md)."
  exit 1
fi

mismatched="$(jq -r -n --slurpfile repo "$work/repo.json" --slurpfile live "$work/live.json" \
  -f "$scripts/app-spec-fixed-values.jq")"
if [ -n "$mismatched" ]; then
  echo "::error::These variables do not have the value $repo_spec sets: $mismatched"
  exit 1
fi

echo "The live app matches $repo_spec"
