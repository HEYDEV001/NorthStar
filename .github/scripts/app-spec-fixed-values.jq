# The fixed values .do/staging.app.yaml sets (plain variables with a value in the repo) that the live app does not
# have, as "component.KEY" names joined by ", "; empty when all match (step 1.15). Run with
# jq -r -n --slurpfile repo repo.json --slurpfile live live.json. Prints names only, never a value.
def fixed:
  [((.services // []) + (.workers // []) + (.jobs // []))[]
   | .name as $component
   | (.envs // [])[]
   | select((.type // "GENERAL") == "GENERAL" and .value != null)
   | {key: ($component + "." + .key), value}];

($repo[0] | fixed) as $wanted
| ($live[0] | fixed | map({(.key): .value}) | add // {}) as $live_values
| [$wanted[] | select($live_values[.key] != .value) | .key]
| join(", ")
