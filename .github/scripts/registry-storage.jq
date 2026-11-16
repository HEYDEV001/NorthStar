# The registry's storage against its plan's limit (step 1.15). Input: `doctl registry get --output json`. Argument:
# --argjson limit_mib (500 on Starter, 5120 on Basic). Output: one line for the log, and a warning line when usage is
# above 80% of the limit. DigitalOcean updates the figure after garbage collection finishes, so it is shown with the
# time it was measured.
.[0] as $registry
| (($registry.storage_usage_bytes // 0) / 1048576) as $used
| ($used * 100 / $limit_mib) as $percent
| "Registry storage: \($used | floor) MiB of \($limit_mib) MiB (\($percent | floor)%), measured \($registry.storage_usage_bytes_updated_at // "unknown")",
  (if $percent > 80 then
     "::warning::The container registry is at \($percent | floor)% of its \($limit_mib) MiB plan. Delete older tags or move to the Basic plan (docs/staging-runbook.md, image tags)."
   else empty end)
