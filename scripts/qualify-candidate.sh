#!/usr/bin/bash -p

set -euo pipefail

unset BASH_ENV ENV CDPATH

if [[ ! -d /usr/bin || -w /usr/bin || ! -f /usr/bin/rm || ! -x /usr/bin/rm ||
    -L /usr/bin/rm || -w /usr/bin/rm || ! -f /usr/bin/realpath || ! -x /usr/bin/realpath ||
    -L /usr/bin/realpath || -w /usr/bin/realpath ]]; then
    echo "qualification cannot safely locate the project and invalidate evidence without trusted bootstrap tools" >&2
    exit 1
fi

script_path=""
if ! script_path="$(/usr/bin/realpath -e -- "${BASH_SOURCE[0]}" 2>/dev/null)"; then
    echo "qualification cannot resolve its authoritative script path" >&2
    exit 1
fi
script_directory="${script_path%/*}"
project_root="$(cd -- "$script_directory/.." && pwd -P)"
symlinked_output_paths=()
if [[ -L "$project_root/build" ]]; then
    symlinked_output_paths+=("$project_root/build")
fi
if [[ -L "$project_root/benchmarks" ]]; then
    symlinked_output_paths+=("$project_root/benchmarks")
elif [[ -L "$project_root/benchmarks/build" ]]; then
    symlinked_output_paths+=("$project_root/benchmarks/build")
fi
if (( ${#symlinked_output_paths[@]} != 0 )); then
    /usr/bin/rm -f -- "${symlinked_output_paths[@]}"
    echo "refusing to qualify a project whose build output path is a symbolic link" >&2
    exit 1
fi
/usr/bin/rm -rf -- "$project_root/build" "$project_root/benchmarks/build"

if [[ "$-" != *p* ]]; then
    echo "qualification requires privileged Bash mode; execute scripts/qualify-candidate.sh directly" >&2
    exit 1
fi

if (( $# != 0 )); then
    echo "usage: scripts/qualify-candidate.sh" >&2
    exit 2
fi

if [[ ! -x /usr/bin/realpath || ! -x /usr/bin/sha256sum || ! -x /usr/bin/dirname ||
    ! -x /usr/bin/awk ]]; then
    echo "qualification requires the trusted /usr/bin bootstrap tools" >&2
    exit 1
fi

bootstrap_java_home="${JAVA_HOME:-}"
if [[ -z "$bootstrap_java_home" || "$bootstrap_java_home" != /* ]]; then
    echo "qualification requires an absolute JAVA_HOME" >&2
    exit 1
fi
bootstrap_java=""
if ! bootstrap_java="$(/usr/bin/realpath -e -- "$bootstrap_java_home/bin/java" 2>/dev/null)" ||
    [[ ! -f "$bootstrap_java" || ! -x "$bootstrap_java" ]]; then
    echo "qualification JAVA_HOME does not contain an executable java launcher" >&2
    exit 1
fi
bootstrap_java_sha256="$(/usr/bin/sha256sum "$bootstrap_java" | /usr/bin/awk '{print $1}')"
JAVA_HOME="$(/usr/bin/dirname "$(/usr/bin/dirname "$bootstrap_java")")"

PATH=/usr/bin:/bin
export JAVA_HOME PATH
hash -r

for required_command in \
    awk bash cp date dirname env find git mkdir mktemp nproc realpath rm sh sha256sum sha512sum stat tar tee; do
    if [[ "$(type -t "$required_command" || true)" != "file" ]] ||
        [[ -z "$(type -P "$required_command" || true)" ]]; then
        echo "required qualification executable is unavailable or shadowed: $required_command" >&2
        exit 1
    fi
done

trusted_system_tool() {
    local name="$1"
    local resolved
    local parent
    resolved="$(realpath -e -- "$(type -P "$name")")"
    parent="$(dirname "$resolved")"
    if [[ "$resolved" != /usr/bin/* || ! -f "$resolved" || ! -x "$resolved" ||
        -w "$resolved" || -w "$parent" ]]; then
        echo "qualification tool is not a trusted non-writable system executable: $name ($resolved)" >&2
        return 1
    fi
    printf '%s\n' "$resolved"
}

trusted_git="$(trusted_system_tool git)"
trusted_tar="$(trusted_system_tool tar)"
trusted_sh="$(trusted_system_tool sh)"
trusted_bash="$(trusted_system_tool bash)"
trusted_nproc="$(trusted_system_tool nproc)"
trusted_git_sha256="$(sha256sum "$trusted_git" | awk '{print $1}')"
trusted_tar_sha256="$(sha256sum "$trusted_tar" | awk '{print $1}')"
trusted_sh_sha256="$(sha256sum "$trusted_sh" | awk '{print $1}')"
trusted_bash_sha256="$(sha256sum "$trusted_bash" | awk '{print $1}')"
trusted_nproc_sha256="$(sha256sum "$trusted_nproc" | awk '{print $1}')"

while IFS= read -r unsafe_environment_variable; do
    unset "$unsafe_environment_variable"
done < <(env | awk -F= '$1 ~ /^GIT_/ || $1 ~ /^ORG_GRADLE_PROJECT_/ { print $1 }')

export GIT_CONFIG_NOSYSTEM=1
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_NO_REPLACE_OBJECTS=1
export GIT_ATTR_NOSYSTEM=1
export CQENGINE_TRUSTED_PATH="$PATH"
export CQENGINE_TRUSTED_GIT="$trusted_git"
export CQENGINE_TRUSTED_GIT_SHA256="$trusted_git_sha256"
export CQENGINE_TRUSTED_TAR="$trusted_tar"
export CQENGINE_TRUSTED_TAR_SHA256="$trusted_tar_sha256"
export CQENGINE_TRUSTED_SH="$trusted_sh"
export CQENGINE_TRUSTED_SH_SHA256="$trusted_sh_sha256"
export CQENGINE_TRUSTED_BASH="$trusted_bash"
export CQENGINE_TRUSTED_BASH_SHA256="$trusted_bash_sha256"
export CQENGINE_TRUSTED_NPROC="$trusted_nproc"
export CQENGINE_TRUSTED_NPROC_SHA256="$trusted_nproc_sha256"
export CQENGINE_TRUSTED_JAVA="$bootstrap_java"
export CQENGINE_TRUSTED_JAVA_SHA256="$bootstrap_java_sha256"

temporary_root="${TMPDIR:-/tmp}"
preflight_gradle_user_home="$(mktemp -d "$temporary_root/cqengine-release-gradle-home.XXXXXXXX")"
release_gradle_user_home=""
candidate_root="$(mktemp -d "$temporary_root/cqengine-release-source.XXXXXXXX")"
qualification_log="$(mktemp "$temporary_root/cqengine-release-output.XXXXXXXX.log")"
qualification_start="$(mktemp "$temporary_root/cqengine-release-start.XXXXXXXX")"

cleanup() {
    for isolated_gradle_home in "$preflight_gradle_user_home" "$release_gradle_user_home"; do
        if [[ -z "$isolated_gradle_home" ]]; then
            continue
        fi
        case "$isolated_gradle_home" in
            "$temporary_root"/cqengine-release-gradle-home.*)
                rm -rf -- "$isolated_gradle_home"
                ;;
            *)
                echo "refusing to remove unexpected Gradle home: $isolated_gradle_home" >&2
                ;;
        esac
    done
    case "$qualification_log" in
        "$temporary_root"/cqengine-release-output.*.log)
            rm -f -- "$qualification_log"
            ;;
        *)
            echo "refusing to remove unexpected qualification log: $qualification_log" >&2
            ;;
    esac
    case "$candidate_root" in
        "$temporary_root"/cqengine-release-source.*)
            rm -rf -- "$candidate_root"
            ;;
        *)
            echo "refusing to remove unexpected candidate source: $candidate_root" >&2
            ;;
    esac
    case "$qualification_start" in
        "$temporary_root"/cqengine-release-start.*)
            rm -f -- "$qualification_start"
            ;;
        *)
            echo "refusing to remove unexpected qualification marker: $qualification_start" >&2
            ;;
    esac
}
trap cleanup EXIT

machine_label="${CQENGINE_JMH_MACHINE_LABEL:-}"
if [[ ! "$machine_label" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$ ]]; then
    echo "set CQENGINE_JMH_MACHINE_LABEL to a 3-64 character machine label" >&2
    exit 2
fi

replace_refs="$("$trusted_git" -C "$project_root" for-each-ref --format='%(refname)' refs/replace/)"
if [[ -n "$replace_refs" ]]; then
    echo "release qualification rejects Git replacement refs: $replace_refs" >&2
    exit 1
fi
grafts_path="$("$trusted_git" -C "$project_root" rev-parse --path-format=absolute --git-path info/grafts)"
shallow_path="$("$trusted_git" -C "$project_root" rev-parse --path-format=absolute --git-path shallow)"
if [[ -e "$grafts_path" || -L "$grafts_path" ]]; then
    echo "release qualification rejects legacy Git graft state" >&2
    exit 1
fi
if [[ -e "$shallow_path" || -L "$shallow_path" ]]; then
    echo "release qualification requires a complete non-shallow repository" >&2
    exit 1
fi

git_local_config_values() {
    local key="$1"
    local output
    local exit_code=0
    output="$("$trusted_git" -C "$project_root" config --local --no-includes --get-all "$key" 2>&1)" ||
        exit_code=$?
    if (( exit_code > 1 )); then
        echo "could not inspect raw local Git setting $key: $output" >&2
        return "$exit_code"
    fi
    if (( exit_code == 0 )); then
        printf '%s\n' "$output"
    fi
}

local_include_keys=""
local_include_exit_code=0
local_include_keys="$(
    "$trusted_git" -C "$project_root" config --local --no-includes --name-only --get-regexp \
        '^(include|includeif[.].*)[.]path$' 2>&1
)" || local_include_exit_code=$?
if (( local_include_exit_code > 1 )); then
    echo "could not inspect raw local Git include settings: $local_include_keys" >&2
    exit 1
fi
if [[ -n "$local_include_keys" ]]; then
    echo "release qualification rejects local Git include/includeIf configuration" >&2
    exit 1
fi
origin_url="$(git_local_config_values remote.origin.url)"
if [[ -z "$origin_url" ]]; then
    repository_mode=local
elif [[ "$origin_url" == "https://github.com/shuaibrao/cqengine.git" ]]; then
    repository_mode=hosted
else
    echo "release qualification requires an exact local or hosted repository configuration" >&2
    exit 1
fi
if [[ "$(git_local_config_values remote.upstream.url)" != \
    "https://github.com/npgall/cqengine.git" ]]; then
    echo "release qualification requires an exact local or hosted repository configuration" >&2
    exit 1
fi

unexpected_local_git_keys=()
declare -A local_git_key_counts=()
while IFS= read -r -d '' local_git_key; do
    normalized_local_git_key="${local_git_key,,}"
    local_git_key_counts["$normalized_local_git_key"]=$((
        ${local_git_key_counts["$normalized_local_git_key"]:-0} + 1
    ))
    case "$normalized_local_git_key" in
        branch.main.merge | branch.main.remote | core.bare | core.filemode | core.logallrefupdates | \
            core.repositoryformatversion | core.autocrlf | core.eol | remote.origin.fetch | \
            remote.origin.url | remote.upstream.fetch | remote.upstream.url)
            ;;
        *)
            unexpected_local_git_keys+=("$local_git_key")
            ;;
    esac
done < <("$trusted_git" -C "$project_root" config --local --no-includes --null --name-only --list)
if (( ${#unexpected_local_git_keys[@]} != 0 )); then
    echo "release qualification rejects unexpected local Git configuration: ${unexpected_local_git_keys[*]}" >&2
    exit 1
fi

require_exact_local_git_config() {
    local key="$1"
    local expected="$2"
    local actual="$(git_local_config_values "$key")"
    if [[ "${local_git_key_counts["${key,,}"]:-0}" != "1" || "$actual" != "$expected" ]]; then
        if [[ "$key" == remote.*.url || "$key" == remote.*.fetch ]]; then
            echo "release qualification requires an exact local or hosted repository configuration" >&2
            exit 1
        fi
        echo "release qualification requires exact local Git setting $key=$expected" >&2
        exit 1
    fi
}
require_exact_local_git_config core.repositoryformatversion 0
require_exact_local_git_config core.filemode true
require_exact_local_git_config core.bare false
require_exact_local_git_config core.logallrefupdates true
require_exact_local_git_config remote.upstream.url https://github.com/npgall/cqengine.git
require_exact_local_git_config remote.upstream.fetch '+refs/heads/*:refs/remotes/upstream/*'
if [[ "$repository_mode" == hosted ]]; then
    require_exact_local_git_config remote.origin.url https://github.com/shuaibrao/cqengine.git
    require_exact_local_git_config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'
elif (( ${local_git_key_counts["remote.origin.url"]:-0} != 0 ||
    ${local_git_key_counts["remote.origin.fetch"]:-0} != 0 )); then
    echo "release qualification rejects partial or unexpected origin configuration in local mode" >&2
    exit 1
fi

branch_config_count=$((${local_git_key_counts["branch.main.remote"]:-0} + \
    ${local_git_key_counts["branch.main.merge"]:-0}))
detached_config_count=$((${local_git_key_counts["core.autocrlf"]:-0} + \
    ${local_git_key_counts["core.eol"]:-0}))
if (( branch_config_count == 2 && detached_config_count == 0 )); then
    if [[ "$repository_mode" == hosted ]]; then
        require_exact_local_git_config branch.main.remote origin
        require_exact_local_git_config branch.main.merge refs/heads/main
    else
        require_exact_local_git_config branch.main.remote upstream
        require_exact_local_git_config branch.main.merge refs/heads/master
    fi
elif (( branch_config_count == 0 && detached_config_count == 2 )); then
    require_exact_local_git_config core.autocrlf false
    require_exact_local_git_config core.eol lf
else
    echo "release qualification requires exact main-branch or detached-checkout Git configuration" >&2
    exit 1
fi

if [[ -n "$("$trusted_git" -C "$project_root" status --porcelain=v1 --untracked-files=all)" ]]; then
    echo "release qualification requires a clean Git worktree" >&2
    exit 1
fi
if [[ -n "$("$trusted_git" -C "$project_root" ls-files -v | awk 'substr($0, 1, 1) != "H"')" ]]; then
    echo "release qualification rejects assume-unchanged, skip-worktree, sparse or unmerged index entries" >&2
    exit 1
fi
git_toplevel="$("$trusted_git" -C "$project_root" rev-parse --show-toplevel)"
git_toplevel="$(cd "$git_toplevel" && pwd -P)"
if [[ "$git_toplevel" != "$project_root" ]]; then
    echo "release qualification requires the physical project root to be the Git worktree root" >&2
    exit 1
fi
source_commit="$("$trusted_git" -C "$project_root" rev-parse --verify HEAD)"
source_tree="$("$trusted_git" -C "$project_root" rev-parse --verify 'HEAD^{tree}')"
expected_remotes=upstream
[[ "$repository_mode" == hosted ]] && expected_remotes=$'origin\nupstream'
if [[ "$("$trusted_git" -C "$project_root" remote)" != "$expected_remotes" ]]; then
    echo "release qualification requires an exact local or hosted repository configuration" >&2
    exit 1
fi

if [[ -n "$(find "$preflight_gradle_user_home" -mindepth 1 -print -quit)" ]] ||
    [[ -n "$(find "$candidate_root" -mindepth 1 -print -quit)" ]]; then
    echo "temporary qualification directories were not empty" >&2
    exit 1
fi

materialize_candidate() {
    local destination="$1"
    "$trusted_git" -c core.hooksPath=/dev/null clone \
        --no-checkout \
        --no-hardlinks \
        --quiet \
        "$project_root" \
        "$destination"
    "$trusted_git" -C "$destination" config core.autocrlf false
    "$trusted_git" -C "$destination" config core.eol lf
    "$trusted_git" -C "$destination" config --unset-all branch.main.remote || true
    "$trusted_git" -C "$destination" config --unset-all branch.main.merge || true
    if [[ "$repository_mode" == hosted ]]; then
        "$trusted_git" -C "$destination" remote set-url origin https://github.com/shuaibrao/cqengine.git
        "$trusted_git" -C "$destination" config remote.origin.fetch '+refs/heads/*:refs/remotes/origin/*'
    else
        "$trusted_git" -C "$destination" remote remove origin
    fi
    "$trusted_git" -C "$destination" remote add upstream https://github.com/npgall/cqengine.git
    "$trusted_git" -C "$destination" update-ref --no-deref HEAD "$source_commit"
    "$trusted_git" -C "$destination" read-tree "$source_commit"
    "$trusted_git" -c core.attributesFile=/dev/null -C "$project_root" \
        archive --format=tar "$source_commit" |
        "$trusted_tar" -xf - -C "$destination"

    if [[ "$("$trusted_git" -C "$destination" rev-parse --verify HEAD)" != "$source_commit" ]] ||
        [[ "$("$trusted_git" -C "$destination" rev-parse --verify 'HEAD^{tree}')" != "$source_tree" ]] ||
        [[ -n "$("$trusted_git" -C "$destination" status --porcelain=v1 --untracked-files=all)" ]]; then
        echo "detached candidate does not exactly represent the committed source" >&2
        return 1
    fi
}

materialize_candidate "$candidate_root"

printf 'project=%s\nstate=created-empty\n' "$candidate_root" \
    > "$preflight_gradle_user_home/.cqengine-clean-release-home"

cd "$candidate_root"
run_gradle() {
    local isolated_gradle_home="$1"
    shift
    env \
        -u JAVA_TOOL_OPTIONS \
        -u JDK_JAVA_OPTIONS \
        -u _JAVA_OPTIONS \
        -u JAVA_OPTS \
        -u GRADLE_OPTS \
        CQENGINE_RELEASE_INVOCATION=1 \
        CQENGINE_JMH_MACHINE_LABEL="$machine_label" \
        GRADLE_USER_HOME="$isolated_gradle_home" \
        "$candidate_root/gradlew" \
        --project-dir "$candidate_root" \
        --no-daemon \
        --no-build-cache \
        --no-parallel \
        --no-configuration-cache \
        --dependency-verification strict \
        --console=plain \
        "$@"
}

printf 'CQENGINE_PHASE=release-preflight\n' > "$qualification_log"
printf 'CQENGINE_PHASE=release-preflight\n'
set +e
run_gradle "$preflight_gradle_user_home" \
    :benchmarks:jmhLaneSelectionPreflight \
    :stress-tests:compileJava 2>&1 | tee -a "$qualification_log"
preflight_pipeline_status=("${PIPESTATUS[@]}")
preflight_gradle_exit_code="${preflight_pipeline_status[0]}"
preflight_tee_exit_code="${preflight_pipeline_status[1]}"
set -e

release_gradle_exit_code=-1
release_tee_exit_code=-1
gradle_exit_code="$preflight_gradle_exit_code"
tee_exit_code="$preflight_tee_exit_code"
if (( preflight_gradle_exit_code == 0 && preflight_tee_exit_code == 0 )); then
    cd /
    case "$candidate_root" in
        "$temporary_root"/cqengine-release-source.*)
            rm -rf -- "$candidate_root"
            ;;
        *)
            echo "refusing to replace unexpected preflight candidate source: $candidate_root" >&2
            exit 1
            ;;
    esac
    candidate_root="$(mktemp -d "$temporary_root/cqengine-release-source.XXXXXXXX")"
    if [[ -n "$(find "$candidate_root" -mindepth 1 -print -quit)" ]]; then
        echo "release candidate source was not created empty" >&2
        exit 1
    fi
    materialize_candidate "$candidate_root"
    cd "$candidate_root"

    release_gradle_user_home="$(mktemp -d "$temporary_root/cqengine-release-gradle-home.XXXXXXXX")"
    if [[ -n "$(find "$release_gradle_user_home" -mindepth 1 -print -quit)" ]]; then
        echo "release Gradle home was not created empty" >&2
        exit 1
    fi
    printf 'project=%s\nstate=created-empty\n' "$candidate_root" \
        > "$release_gradle_user_home/.cqengine-clean-release-home"
    printf 'CQENGINE_PHASE=release-check\n' >> "$qualification_log"
    printf 'CQENGINE_PHASE=release-check\n'
    set +e
    run_gradle "$release_gradle_user_home" clean releaseCheck 2>&1 | tee -a "$qualification_log"
    release_pipeline_status=("${PIPESTATUS[@]}")
    release_gradle_exit_code="${release_pipeline_status[0]}"
    release_tee_exit_code="${release_pipeline_status[1]}"
    gradle_exit_code="$release_gradle_exit_code"
    tee_exit_code="$release_tee_exit_code"
    set -e
fi

candidate_qualification_directory="$candidate_root/build/local-release-evidence/qualification"
candidate_readiness_manifest="$candidate_qualification_directory/local-readiness-manifest.txt"

manifest_value() {
    local key="$1"
    awk -v key="$key" '
        index($0, key "=") == 1 {
            count++
            value = substr($0, length(key) + 2)
        }
        END {
            if (count != 1) {
                exit 1
            }
            print value
        }
    ' "$candidate_readiness_manifest"
}

wrapper_exit_code="$gradle_exit_code"
validation_status="not-run"
if (( tee_exit_code != 0 )); then
    wrapper_exit_code=1
    validation_status="failed"
    printf 'wrapper validation failed: qualification-log tee exited %s\n' "$tee_exit_code" \
        >> "$qualification_log"
elif [[ ! -s "$qualification_log" ]]; then
    wrapper_exit_code=1
    validation_status="failed"
    printf 'wrapper validation failed: qualification log is empty\n' >> "$qualification_log"
elif (( gradle_exit_code == 0 )) &&
    ! awk '
        $0 == "CQENGINE_PHASE=release-check" { release = 1; next }
        release && /^BUILD SUCCESSFUL/ { found = 1 }
        END { exit(found ? 0 : 1) }
    ' "$qualification_log"; then
    wrapper_exit_code=1
    validation_status="failed"
    printf 'wrapper validation failed: qualification log has no Gradle success marker\n' \
        >> "$qualification_log"
elif (( gradle_exit_code == 0 )); then
    validation_status="failed"
    validation_error=""
    if [[ ! -f "$candidate_readiness_manifest" || -L "$candidate_readiness_manifest" ||
        ! -s "$candidate_readiness_manifest" ]]; then
        validation_error="releaseCheck produced no regular, non-empty readiness manifest"
    elif [[ ! "$candidate_readiness_manifest" -nt "$qualification_start" ]]; then
        validation_error="releaseCheck did not produce a fresh readiness manifest"
    elif ! manifest_commit="$(manifest_value sourceCommit)"; then
        validation_error="readiness manifest must contain exactly one sourceCommit"
    elif ! manifest_tree="$(manifest_value sourceTree)"; then
        validation_error="readiness manifest must contain exactly one sourceTree"
    elif ! manifest_command="$(manifest_value command)"; then
        validation_error="readiness manifest must contain exactly one command"
    elif ! manifest_phase_isolation="$(manifest_value phaseIsolation)"; then
        validation_error="readiness manifest must contain exactly one phaseIsolation"
    elif [[ "$manifest_commit" != "$source_commit" ]]; then
        validation_error="readiness manifest sourceCommit does not match the qualified commit"
    elif [[ "$manifest_tree" != "$source_tree" ]]; then
        validation_error="readiness manifest sourceTree does not match the qualified tree"
    elif [[ "$manifest_command" != "scripts/qualify-candidate.sh" ]]; then
        validation_error="readiness manifest records the wrong qualification command"
    elif [[ "$manifest_phase_isolation" != "separate-fresh-source-and-gradle-homes" ]]; then
        validation_error="readiness manifest records the wrong qualification phase isolation"
    elif [[ "$("$trusted_git" -C "$candidate_root" rev-parse --verify HEAD)" != "$source_commit" ]]; then
        validation_error="candidate Git HEAD changed during release qualification"
    elif [[ "$("$trusted_git" -C "$candidate_root" rev-parse --verify 'HEAD^{tree}')" != "$source_tree" ]]; then
        validation_error="candidate Git tree changed during release qualification"
    elif [[ -n "$("$trusted_git" -C "$candidate_root" status --porcelain=v1 --untracked-files=all)" ]]; then
        validation_error="candidate Git worktree changed during release qualification"
    elif [[ "$("$trusted_git" -C "$project_root" rev-parse --verify HEAD)" != "$source_commit" ]] ||
        [[ "$("$trusted_git" -C "$project_root" rev-parse --verify 'HEAD^{tree}')" != "$source_tree" ]]; then
        validation_error="source Git commit changed during release qualification"
    else
        validation_status="passed"
    fi

    if [[ "$validation_status" != "passed" ]]; then
        wrapper_exit_code=1
        printf 'wrapper validation failed: %s\n' "$validation_error" | tee -a "$qualification_log" >&2
    fi
fi

copy_candidate_output() {
    local relative="$1"
    local source="$candidate_root/$relative"
    local destination="$project_root/$relative"
    if [[ -L "$destination" ]]; then
        echo "refusing to replace symbolic-link output path: $destination" >&2
        return 1
    fi
    rm -rf -- "$destination"
    if [[ ! -e "$source" ]]; then
        return
    fi
    mkdir -p -- "$(dirname "$destination")"
    cp -a -- "$source" "$destination"
}

for retained_output in \
    build/generated-release-evidence \
    build/libs \
    build/local-release-evidence \
    build/local-repository \
    build/publications \
    build/reports \
    build/test-results \
    benchmarks/build/reports; do
    copy_candidate_output "$retained_output"
done

qualification_directory="$project_root/build/local-release-evidence/qualification"
retained_log="$qualification_directory/qualification.log"
completion_record="$qualification_directory/wrapper-completion.properties"
readiness_manifest="$qualification_directory/local-readiness-manifest.txt"
mkdir -p -- "$qualification_directory"
cp -- "$qualification_log" "$retained_log"

readiness_sha256="absent"
readiness_sha512="absent"
if [[ -f "$readiness_manifest" && ! -L "$readiness_manifest" ]]; then
    readiness_sha256="$(sha256sum "$readiness_manifest" | awk '{print $1}')"
    readiness_sha512="$(sha512sum "$readiness_manifest" | awk '{print $1}')"
fi

qualification_status="failed"
if (( wrapper_exit_code == 0 )); then
    qualification_status="passed"
fi
{
    printf 'formatVersion=1\n'
    printf 'status=%s\n' "$qualification_status"
    printf 'preflightGradleExitCode=%s\n' "$preflight_gradle_exit_code"
    printf 'preflightTeeExitCode=%s\n' "$preflight_tee_exit_code"
    printf 'releaseGradleExitCode=%s\n' "$release_gradle_exit_code"
    printf 'releaseTeeExitCode=%s\n' "$release_tee_exit_code"
    printf 'gradleExitCode=%s\n' "$gradle_exit_code"
    printf 'teeExitCode=%s\n' "$tee_exit_code"
    printf 'wrapperExitCode=%s\n' "$wrapper_exit_code"
    printf 'validationStatus=%s\n' "$validation_status"
    printf 'phaseIsolation=separate-fresh-source-and-gradle-homes\n'
    printf 'sourceCommit=%s\n' "$source_commit"
    printf 'sourceTree=%s\n' "$source_tree"
    printf 'repositoryMode=%s\n' "$repository_mode"
    printf 'sourceMaterialization=detached-clone-git-archive\n'
    printf 'jmhMachineLabel=%s\n' "$machine_label"
    printf 'trustedPath=%s\n' "$PATH"
    printf 'trustedGit=%s sha256:%s\n' "$trusted_git" "$trusted_git_sha256"
    printf 'trustedTar=%s sha256:%s\n' "$trusted_tar" "$trusted_tar_sha256"
    printf 'trustedShell=%s sha256:%s\n' "$trusted_sh" "$trusted_sh_sha256"
    printf 'trustedBash=%s sha256:%s\n' "$trusted_bash" "$trusted_bash_sha256"
    printf 'trustedNproc=%s sha256:%s\n' "$trusted_nproc" "$trusted_nproc_sha256"
    printf 'trustedJava=%s sha256:%s\n' "$bootstrap_java" "$bootstrap_java_sha256"
    printf 'gitConfigGlobal=disabled\n'
    printf 'gitConfigSystem=disabled\n'
    printf 'gitObjectReplacement=disabled\n'
    printf 'gitSystemAttributes=disabled\n'
    printf 'completedAt=%s\n' "$(date -u +'%Y-%m-%dT%H:%M:%SZ')"
    printf 'command=scripts/qualify-candidate.sh\n'
    printf 'qualificationLogSha256=%s\n' "$(sha256sum "$retained_log" | awk '{print $1}')"
    printf 'qualificationLogSha512=%s\n' "$(sha512sum "$retained_log" | awk '{print $1}')"
    printf 'readinessManifestSha256=%s\n' "$readiness_sha256"
    printf 'readinessManifestSha512=%s\n' "$readiness_sha512"
} > "$completion_record"

exit "$wrapper_exit_code"
