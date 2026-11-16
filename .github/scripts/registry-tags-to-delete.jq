# Which image tags the registry keeps after a deploy (step 1.15). Input: `doctl registry repository list-tags --output
# json`. Arguments: --arg deployed (the tag just deployed), --argjson running (the tags the app ran before this deploy,
# a JSON array) and --argjson keep (how many tags to keep: 2 on the Starter registry, 20 on Basic).
#
# Kept, in order: the deployed tag, then every tag the app ran before (the dashboard's rollback target), then the most
# recently pushed other tags until `keep` is reached. A tag the app runs is never deleted, even beyond `keep`. Output:
# {"keep": [...], "delete": [...]}, deletions oldest first. Refuses (exit 5) when the deployed tag is missing, a tag
# name is unexpected, or `keep` is below 2, so a wrong call deletes nothing.
def dedupe: reduce .[] as $tag ([]; if index([$tag]) then . else . + [$tag] end);

if ($keep | type) != "number" or $keep < 2 then error("keep must be a number of at least 2") else . end
| map(select(.tag != null)) as $tags
| ($tags | map(.tag)) as $names
| if ($names | index([$deployed])) == null then error("the deployed tag is not in the repository; deleting nothing")
  else . end
| if ($names | map(test("^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$")) | all) | not
  then error("unexpected tag name in the repository; deleting nothing") else . end
| ([$deployed] + ($running | map(select(. as $tag | $names | index([$tag]))))) | dedupe | . as $must
| ($tags
   | map(select(.tag as $tag | $must | index([$tag]) | not))
   | sort_by(.updated_at) | reverse | map(.tag)) as $others
| ($must + $others[0:([$keep - ($must | length), 0] | max)]) as $kept
| {keep: $kept, delete: ($tags | sort_by(.updated_at) | map(.tag) | map(select(. as $tag | $kept | index([$tag]) | not)))}
