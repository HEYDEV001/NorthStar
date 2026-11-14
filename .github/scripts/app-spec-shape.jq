# The parts of an App Platform spec that .do/staging.app.yaml owns, without a single value (step 1.15): the region,
# each component's kind, size, count, command, port, health path and image repository, auto-deploy, and each
# variable's name, scope and type. Values are left out because the live spec carries all of them (secrets encrypted);
# image tags because each deploy sets them. Defaults are filled in so a field App Platform adds does not count as drift.
def variables:
  [(.envs // [])[] | {key, scope: (.scope // "RUN_AND_BUILD_TIME"), type: (.type // "GENERAL")}] | sort_by(.key);

def component($kind):
  {
    kind: $kind,
    name,
    job_kind: (.kind // null),
    size: .instance_size_slug,
    count: (.instance_count // 1),
    run_command: (.run_command // null),
    http_port: (.http_port // null),
    health_path: (.health_check.http_path // null),
    image: {
      registry_type: .image.registry_type,
      repository: .image.repository,
      deploy_on_push: (.image.deploy_on_push.enabled // false)
    },
    variables: variables
  };

{
  name,
  region,
  app_variables: ([(.envs // [])[] | .key] | sort),
  components: (
    [(.services // [])[] | component("service")]
    + [(.workers // [])[] | component("worker")]
    + [(.jobs // [])[] | component("job")]
    | sort_by(.kind, .name)
  )
}
