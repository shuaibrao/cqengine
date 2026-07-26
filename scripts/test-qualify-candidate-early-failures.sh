#!/usr/bin/bash -p

set -euo pipefail

if (( $# != 0 )); then
    echo "usage: scripts/test-qualify-candidate-early-failures.sh" >&2
    exit 2
fi

project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/cqengine-qualification-negative.XXXXXXXX")"

cleanup() {
    case "$test_root" in
        "${TMPDIR:-/tmp}"/cqengine-qualification-negative.*)
            rm -rf -- "$test_root"
            ;;
        *)
            echo "refusing to remove unexpected qualification test root: $test_root" >&2
            ;;
    esac
}
trap cleanup EXIT

create_fixture() {
    local name="$1"
    local fixture="$test_root/$name"
    mkdir -p -- "$fixture/scripts"
    cp -- "$project_root/scripts/qualify-candidate.sh" "$fixture/scripts/qualify-candidate.sh"
    chmod +x "$fixture/scripts/qualify-candidate.sh"
    printf '/build/\n/benchmarks/build/\n' > "$fixture/.gitignore"
    printf 'committed fixture input\n' > "$fixture/input.txt"
    git init --quiet --initial-branch=main "$fixture"
    git -C "$fixture" add .gitignore input.txt scripts/qualify-candidate.sh
    git -c core.hooksPath=/dev/null -c commit.gpgSign=false \
        -c user.name="CQEngine qualification fixture" \
        -c user.email=cqengine-qualification-fixture@invalid.example \
        -C "$fixture" commit --quiet -m "Create qualification fixture"
    git -C "$fixture" remote add upstream https://github.com/npgall/cqengine.git
    git -C "$fixture" config branch.main.remote upstream
    git -C "$fixture" config branch.main.merge refs/heads/master
    printf '%s\n' "$fixture"
}

seed_passing_outputs() {
    local fixture="$1"
    mkdir -p -- \
        "$fixture/build/libs" \
        "$fixture/build/local-release-evidence/qualification" \
        "$fixture/build/reports" \
        "$fixture/benchmarks/build/reports"
    printf 'status=passed\n' \
        > "$fixture/build/local-release-evidence/qualification/wrapper-completion.properties"
    printf 'sourceCommit=prior-passing-candidate\n' \
        > "$fixture/build/local-release-evidence/qualification/local-readiness-manifest.txt"
    printf 'prior artifact\n' > "$fixture/build/libs/prior.jar"
    printf 'prior report\n' > "$fixture/build/reports/prior.txt"
    printf 'prior benchmark report\n' > "$fixture/benchmarks/build/reports/prior.txt"
}

install_passing_gradle_fixture() {
    local fixture="$1"
    printf '%s\n' \
        '#!/usr/bin/bash' \
        'set -euo pipefail' \
        'trusted_git="${CQENGINE_TRUSTED_GIT:?}"' \
        'invocation_state="${CQENGINE_TEST_INVOCATION_STATE:?}"' \
        'if [[ ! -e "$invocation_state" ]]; then' \
        '    [[ "${*: -2}" == ":benchmarks:jmhLaneSelectionPreflight :stress-tests:compileJava" ]] || exit 41' \
        '    printf "%s\n" "${GRADLE_USER_HOME:?}" > "$invocation_state"' \
        '    printf "throw new GradleException(\"preflight home leaked\")\n" > "$GRADLE_USER_HOME/init.gradle"' \
        '    printf "preflight source poison\n" > preflight-source-poison.txt' \
        'else' \
        '    [[ "$(<"$invocation_state")" != "${GRADLE_USER_HOME:?}" ]] || exit 42' \
        '    [[ ! -e "$GRADLE_USER_HOME/init.gradle" ]] || exit 46' \
        '    [[ ! -e preflight-source-poison.txt ]] || exit 47' \
        '    [[ " $* " == *" clean releaseCheck "* ]] || exit 43' \
        '    source_commit="$("$trusted_git" -C "$PWD" rev-parse --verify HEAD)"' \
        '    source_tree="$("$trusted_git" -C "$PWD" rev-parse --verify "HEAD^{tree}")"' \
        '    mkdir -p build/local-release-evidence/qualification' \
        '    {' \
        '        printf "sourceCommit=%s\n" "$source_commit"' \
        '        printf "sourceTree=%s\n" "$source_tree"' \
        '        printf "command=scripts/qualify-candidate.sh\n"' \
        '        printf "phaseIsolation=separate-fresh-source-and-gradle-homes\n"' \
        '    } > build/local-release-evidence/qualification/local-readiness-manifest.txt' \
        '    printf "release\n" > "$invocation_state"' \
        'fi' \
        'printf "BUILD SUCCESSFUL\n"' \
        > "$fixture/gradlew"
    chmod +x "$fixture/gradlew"
    git -C "$fixture" add gradlew
    git -c core.hooksPath=/dev/null -c commit.gpgSign=false \
        -c user.name="CQEngine qualification fixture" \
        -c user.email=cqengine-qualification-fixture@invalid.example \
        -C "$fixture" commit --quiet -m "Add passing Gradle fixture"
}

install_failing_preflight_gradle_fixture() {
    local fixture="$1"
    printf '%s\n' \
        '#!/usr/bin/bash' \
        'set -euo pipefail' \
        'mkdir -p build/reports' \
        'if [[ -e build/reports/mock-preflight-invoked.txt ]]; then' \
        '    printf "release invoked\n" > build/reports/mock-release-invoked.txt' \
        '    printf "release graph must not run after a failed preflight\n" >&2' \
        '    exit 45' \
        'fi' \
        '[[ "${*: -2}" == ":benchmarks:jmhLaneSelectionPreflight :stress-tests:compileJava" ]] || exit 41' \
        'printf "preflight invoked\n" > build/reports/mock-preflight-invoked.txt' \
        'printf "intentional preflight fixture failure\n" >&2' \
        'exit 44' \
        > "$fixture/gradlew"
    chmod +x "$fixture/gradlew"
    git -C "$fixture" add gradlew
    git -c core.hooksPath=/dev/null -c commit.gpgSign=false \
        -c user.name="CQEngine qualification fixture" \
        -c user.email=cqengine-qualification-fixture@invalid.example \
        -C "$fixture" commit --quiet -m "Add failing preflight Gradle fixture"
}

install_tee_failure_gradle_fixture() {
    local fixture="$1"
    printf '%s\n' \
        '#!/usr/bin/bash' \
        'set -euo pipefail' \
        'mkdir -p build/reports' \
        'if [[ -e build/reports/mock-preflight-invoked.txt ]]; then' \
        '    printf "release invoked\n" > build/reports/mock-release-invoked.txt' \
        '    exit 45' \
        'fi' \
        '[[ "${*: -2}" == ":benchmarks:jmhLaneSelectionPreflight :stress-tests:compileJava" ]] || exit 41' \
        'printf "preflight invoked\n" > build/reports/mock-preflight-invoked.txt' \
        'for ((attempt = 0; attempt < 500; attempt++)); do' \
        '    ancestor="$PPID"' \
        '    for ((depth = 0; depth < 4 && ancestor > 1; depth++)); do' \
        '        for child in $(<"/proc/$ancestor/task/$ancestor/children"); do' \
        '            if [[ -e "/proc/$child/exe" && "/proc/$child/exe" -ef /usr/bin/tee ]]; then' \
        '                kill -TERM "$child"' \
        '                printf "tee terminated\n" > build/reports/mock-tee-terminated.txt' \
        '                exit 0' \
        '            fi' \
        '        done' \
        '        next_ancestor=""' \
        '        while read -r key value _; do' \
        '            [[ "$key" == "PPid:" ]] && next_ancestor="$value"' \
        '        done < "/proc/$ancestor/status"' \
        '        ancestor="$next_ancestor"' \
        '    done' \
        '    /usr/bin/sleep 0.01' \
        'done' \
        'printf "could not find qualification tee process\n" >&2' \
        'exit 52' \
        > "$fixture/gradlew"
    chmod +x "$fixture/gradlew"
    git -C "$fixture" add gradlew
    git -c core.hooksPath=/dev/null -c commit.gpgSign=false \
        -c user.name="CQEngine qualification fixture" \
        -c user.email=cqengine-qualification-fixture@invalid.example \
        -C "$fixture" commit --quiet -m "Add tee-failure Gradle fixture"
}

assert_exact_property() {
    local file="$1"
    local key="$2"
    local expected="$3"
    if [[ ! -f "$file" ]] || ! awk -v key="$key" -v expected="$expected" '
        index($0, key "=") == 1 {
            count++
            actual = substr($0, length(key) + 2)
        }
        END { exit(count == 1 && actual == expected ? 0 : 1) }
    ' "$file"; then
        echo "expected exactly one $key=$expected property in $file" >&2
        if [[ -f "$file" ]]; then
            sed -n '1,160p' "$file" >&2
        fi
        exit 1
    fi
}

assert_phase_sequence() {
    local file="$1"
    local expected="$2"
    local actual
    actual="$(grep -E '^CQENGINE_PHASE=' "$file" || true)"
    if [[ "$actual" != "$expected" ]]; then
        echo "qualification phase sequence was not exact in $file" >&2
        printf 'expected:\n%s\nactual:\n%s\n' "$expected" "$actual" >&2
        exit 1
    fi
}

expect_failure() {
    local fixture="$1"
    local expected_diagnostic="$2"
    local log="$3"
    if CQENGINE_JMH_MACHINE_LABEL=fixture-host \
        "$fixture/scripts/qualify-candidate.sh" > "$log" 2>&1; then
        echo "qualification unexpectedly succeeded for $fixture" >&2
        exit 1
    fi
    if ! grep -Fq -- "$expected_diagnostic" "$log"; then
        echo "qualification did not retain its expected diagnostic: $expected_diagnostic" >&2
        sed -n '1,120p' "$log" >&2
        exit 1
    fi
}

assert_passing_outputs_invalidated() {
    local fixture="$1"
    if [[ -e "$fixture/build" || -L "$fixture/build" ||
        -e "$fixture/benchmarks/build" || -L "$fixture/benchmarks/build" ]]; then
        echo "an early qualification failure retained prior passing outputs in $fixture" >&2
        exit 1
    fi
}

missing_label_fixture="$(create_fixture missing-machine-label)"
seed_passing_outputs "$missing_label_fixture"
missing_label_log="$test_root/missing-machine-label.log"
if env -u CQENGINE_JMH_MACHINE_LABEL \
    "$missing_label_fixture/scripts/qualify-candidate.sh" > "$missing_label_log" 2>&1; then
    echo "qualification unexpectedly accepted a missing machine label" >&2
    exit 1
fi
if ! grep -Fq -- \
    "set CQENGINE_JMH_MACHINE_LABEL to a 3-64 character machine label" \
    "$missing_label_log"; then
    echo "qualification did not retain the missing-label diagnostic" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$missing_label_fixture"

non_privileged_fixture="$(create_fixture non-privileged-bash)"
seed_passing_outputs "$non_privileged_fixture"
non_privileged_log="$test_root/non-privileged-bash.log"
if CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    /usr/bin/bash "$non_privileged_fixture/scripts/qualify-candidate.sh" \
    > "$non_privileged_log" 2>&1; then
    echo "qualification unexpectedly accepted non-privileged Bash" >&2
    exit 1
fi
if ! grep -Fq -- "qualification requires privileged Bash mode" "$non_privileged_log"; then
    echo "qualification did not retain the privileged-Bash diagnostic" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$non_privileged_fixture"

unexpected_argument_fixture="$(create_fixture unexpected-argument)"
seed_passing_outputs "$unexpected_argument_fixture"
unexpected_argument_log="$test_root/unexpected-argument.log"
if CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    "$unexpected_argument_fixture/scripts/qualify-candidate.sh" unexpected \
    > "$unexpected_argument_log" 2>&1; then
    echo "qualification unexpectedly accepted an argument" >&2
    exit 1
fi
if ! grep -Fq -- "usage: scripts/qualify-candidate.sh" "$unexpected_argument_log"; then
    echo "qualification did not retain the usage diagnostic" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$unexpected_argument_fixture"

invalid_java_fixture="$(create_fixture invalid-java-home)"
seed_passing_outputs "$invalid_java_fixture"
invalid_java_log="$test_root/invalid-java-home.log"
if JAVA_HOME="$test_root/missing-java-home" CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    "$invalid_java_fixture/scripts/qualify-candidate.sh" > "$invalid_java_log" 2>&1; then
    echo "qualification unexpectedly accepted an invalid JAVA_HOME" >&2
    exit 1
fi
if ! grep -Fq -- "qualification JAVA_HOME does not contain an executable java launcher" \
    "$invalid_java_log"; then
    echo "qualification did not retain the JAVA_HOME diagnostic" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$invalid_java_fixture"

bash_env_fixture="$(create_fixture hostile-bash-env)"
seed_passing_outputs "$bash_env_fixture"
printf 'dirty worktree input\n' >> "$bash_env_fixture/input.txt"
bash_env_file="$test_root/hostile-bash-env.sh"
bash_env_sentinel="$test_root/hostile-bash-env-executed"
{
    printf 'printf executed > %q\n' "$bash_env_sentinel"
    printf 'exit 0\n'
} > "$bash_env_file"
bash_env_log="$test_root/hostile-bash-env.log"
if BASH_ENV="$bash_env_file" CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    /usr/bin/bash -p "$bash_env_fixture/scripts/qualify-candidate.sh" > "$bash_env_log" 2>&1; then
    echo "qualification unexpectedly accepted a dirty source through BASH_ENV" >&2
    exit 1
fi
if ! grep -Fq -- "release qualification requires a clean Git worktree" "$bash_env_log"; then
    echo "qualification did not ignore hostile BASH_ENV startup code" >&2
    sed -n '1,120p' "$bash_env_log" >&2
    exit 1
fi
if [[ -e "$bash_env_sentinel" ]]; then
    echo "qualification executed hostile BASH_ENV startup code" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$bash_env_fixture"

dirty_fixture="$(create_fixture dirty-worktree)"
seed_passing_outputs "$dirty_fixture"
printf 'dirty worktree input\n' >> "$dirty_fixture/input.txt"
expect_failure \
    "$dirty_fixture" \
    "release qualification requires a clean Git worktree" \
    "$test_root/dirty-worktree.log"
assert_passing_outputs_invalidated "$dirty_fixture"

symlink_invocation_fixture="$(create_fixture symlink-invocation)"
seed_passing_outputs "$symlink_invocation_fixture"
printf 'dirty worktree input\n' >> "$symlink_invocation_fixture/input.txt"
symlink_launcher_root="$test_root/symlink-launcher"
symlink_launcher="$symlink_launcher_root/qualify-candidate.sh"
symlink_sentinel="$symlink_launcher_root/build/local-release-evidence/qualification/sentinel.txt"
mkdir -p -- "$(dirname "$symlink_sentinel")"
printf 'must survive\n' > "$symlink_sentinel"
ln -s -- "$symlink_invocation_fixture/scripts/qualify-candidate.sh" "$symlink_launcher"
if CQENGINE_JMH_MACHINE_LABEL=fixture-host "$symlink_launcher" \
    > "$test_root/symlink-invocation.log" 2>&1; then
    echo "qualification unexpectedly accepted the dirty symlink-invocation fixture" >&2
    exit 1
fi
if ! grep -Fq -- "release qualification requires a clean Git worktree" \
    "$test_root/symlink-invocation.log"; then
    echo "qualification did not resolve a symlinked invocation to the authoritative project" >&2
    exit 1
fi
if [[ ! -f "$symlink_sentinel" ]]; then
    echo "qualification deleted outputs beside a symlink launcher" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$symlink_invocation_fixture"

path_shim_fixture="$(create_fixture hostile-path-shims)"
install_passing_gradle_fixture "$path_shim_fixture"
seed_passing_outputs "$path_shim_fixture"
path_shim_directory="$test_root/path-shims"
path_shim_sentinel="$test_root/path-shim-executed.log"
mkdir -p -- "$path_shim_directory"
for shim_name in bash git java nproc sh tar; do
    {
        printf '#!/usr/bin/bash\n'
        printf 'printf "%%s\\n" "$0" >> %q\n' "$path_shim_sentinel"
        printf 'exit 99\n'
    } > "$path_shim_directory/$shim_name"
    chmod +x "$path_shim_directory/$shim_name"
done
path_shim_log="$test_root/hostile-path-shims.log"
path_shim_invocation_state="$test_root/hostile-path-shims.gradle-state"
if ! PATH="$path_shim_directory:$PATH" \
    CQENGINE_TEST_INVOCATION_STATE="$path_shim_invocation_state" \
    CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    "$path_shim_fixture/scripts/qualify-candidate.sh" > "$path_shim_log" 2>&1; then
    echo "qualification failed the clean PATH-shim fixture" >&2
    sed -n '1,160p' "$path_shim_log" >&2
    exit 1
fi
path_shim_qualification_directory="$path_shim_fixture/build/local-release-evidence/qualification"
path_shim_completion="$path_shim_qualification_directory/wrapper-completion.properties"
path_shim_retained_log="$path_shim_qualification_directory/qualification.log"
assert_exact_property "$path_shim_completion" status passed
assert_exact_property "$path_shim_completion" preflightGradleExitCode 0
assert_exact_property "$path_shim_completion" preflightTeeExitCode 0
assert_exact_property "$path_shim_completion" releaseGradleExitCode 0
assert_exact_property "$path_shim_completion" releaseTeeExitCode 0
assert_exact_property "$path_shim_completion" gradleExitCode 0
assert_exact_property "$path_shim_completion" teeExitCode 0
assert_exact_property "$path_shim_completion" wrapperExitCode 0
assert_exact_property "$path_shim_completion" validationStatus passed
assert_exact_property "$path_shim_completion" phaseIsolation separate-fresh-source-and-gradle-homes
assert_exact_property "$path_shim_completion" repositoryMode local
assert_phase_sequence \
    "$path_shim_retained_log" \
    $'CQENGINE_PHASE=release-preflight\nCQENGINE_PHASE=release-check'
if [[ -e "$path_shim_sentinel" ]]; then
    echo "qualification executed a caller-controlled PATH shim" >&2
    exit 1
fi
if grep -Fq -- "getcwd() failed" "$path_shim_log"; then
    echo "qualification removed the preflight source while it was still the working directory" >&2
    exit 1
fi

hosted_fixture="$(create_fixture hosted-repository)"
git -C "$hosted_fixture" remote add origin https://github.com/shuaibrao/cqengine.git
git -C "$hosted_fixture" config branch.main.remote origin
git -C "$hosted_fixture" config branch.main.merge refs/heads/main
install_passing_gradle_fixture "$hosted_fixture"
hosted_state="$test_root/hosted-repository.gradle-state"
hosted_log="$test_root/hosted-repository.log"
if ! CQENGINE_TEST_INVOCATION_STATE="$hosted_state" \
    CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    "$hosted_fixture/scripts/qualify-candidate.sh" > "$hosted_log" 2>&1; then
    echo "qualification failed the exact hosted-repository fixture" >&2
    sed -n '1,160p' "$hosted_log" >&2
    exit 1
fi
assert_exact_property \
    "$hosted_fixture/build/local-release-evidence/qualification/wrapper-completion.properties" \
    repositoryMode \
    hosted

failed_preflight_fixture="$(create_fixture failed-release-preflight)"
install_failing_preflight_gradle_fixture "$failed_preflight_fixture"
seed_passing_outputs "$failed_preflight_fixture"
failed_preflight_log="$test_root/failed-release-preflight.log"
set +e
CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    "$failed_preflight_fixture/scripts/qualify-candidate.sh" > "$failed_preflight_log" 2>&1
failed_preflight_status=$?
set -e
if [[ "$failed_preflight_status" != "44" ]]; then
    echo "failed-preflight wrapper status was $failed_preflight_status, expected 44" >&2
    sed -n '1,160p' "$failed_preflight_log" >&2
    exit 1
fi
if ! grep -Fq -- "intentional preflight fixture failure" "$failed_preflight_log"; then
    echo "qualification did not retain the intentional preflight diagnostic" >&2
    exit 1
fi
failed_preflight_qualification_directory="$failed_preflight_fixture/build/local-release-evidence/qualification"
failed_preflight_completion="$failed_preflight_qualification_directory/wrapper-completion.properties"
failed_preflight_retained_log="$failed_preflight_qualification_directory/qualification.log"
assert_exact_property "$failed_preflight_completion" status failed
assert_exact_property "$failed_preflight_completion" preflightGradleExitCode 44
assert_exact_property "$failed_preflight_completion" preflightTeeExitCode 0
assert_exact_property "$failed_preflight_completion" releaseGradleExitCode -1
assert_exact_property "$failed_preflight_completion" releaseTeeExitCode -1
assert_exact_property "$failed_preflight_completion" gradleExitCode 44
assert_exact_property "$failed_preflight_completion" teeExitCode 0
assert_exact_property "$failed_preflight_completion" wrapperExitCode 44
assert_exact_property "$failed_preflight_completion" validationStatus not-run
assert_exact_property "$failed_preflight_completion" phaseIsolation separate-fresh-source-and-gradle-homes
assert_phase_sequence \
    "$failed_preflight_retained_log" \
    "CQENGINE_PHASE=release-preflight"
if [[ ! -f "$failed_preflight_fixture/build/reports/mock-preflight-invoked.txt" ]]; then
    echo "failed-preflight fixture did not retain proof of the preflight invocation" >&2
    exit 1
fi
if [[ -e "$failed_preflight_fixture/build/reports/mock-release-invoked.txt" ]]; then
    echo "qualification invoked the release graph after a failed release preflight" >&2
    exit 1
fi
if [[ -e "$failed_preflight_qualification_directory/local-readiness-manifest.txt" ||
    -e "$failed_preflight_fixture/build/libs/prior.jar" ||
    -e "$failed_preflight_fixture/build/reports/prior.txt" ||
    -e "$failed_preflight_fixture/benchmarks/build/reports/prior.txt" ]]; then
    echo "failed preflight retained stale passing release evidence" >&2
    exit 1
fi

tee_failure_fixture="$(create_fixture failed-qualification-log-tee)"
install_tee_failure_gradle_fixture "$tee_failure_fixture"
seed_passing_outputs "$tee_failure_fixture"
tee_failure_stdout="$test_root/failed-qualification-log-tee.stdout"
tee_failure_stderr="$test_root/failed-qualification-log-tee.stderr"
set +e
CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    "$tee_failure_fixture/scripts/qualify-candidate.sh" \
    > "$tee_failure_stdout" 2> "$tee_failure_stderr"
tee_failure_status=$?
set -e
if [[ "$tee_failure_status" != "1" ]]; then
    echo "tee-failure fixture returned status $tee_failure_status, expected 1" >&2
    sed -n '1,160p' "$tee_failure_stderr" >&2
    exit 1
fi
if [[ "$(<"$tee_failure_stdout")" != "CQENGINE_PHASE=release-preflight" ]]; then
    echo "tee-failure fixture did not begin with the preflight phase marker" >&2
    exit 1
fi
tee_failure_qualification_directory="$tee_failure_fixture/build/local-release-evidence/qualification"
tee_failure_completion="$tee_failure_qualification_directory/wrapper-completion.properties"
tee_failure_retained_log="$tee_failure_qualification_directory/qualification.log"
assert_exact_property "$tee_failure_completion" status failed
assert_exact_property "$tee_failure_completion" preflightGradleExitCode 0
if awk -F= '$1 == "preflightTeeExitCode" { count++; value = $2 } END {
    exit(count == 1 && value != "0" ? 0 : 1)
}' "$tee_failure_completion"; then
    tee_failure_exit_code="$(awk -F= '$1 == "preflightTeeExitCode" { print $2 }' \
        "$tee_failure_completion")"
else
    echo "tee-failure completion did not retain one nonzero preflight tee status" >&2
    sed -n '1,160p' "$tee_failure_completion" >&2
    exit 1
fi
assert_exact_property "$tee_failure_completion" releaseGradleExitCode -1
assert_exact_property "$tee_failure_completion" releaseTeeExitCode -1
assert_exact_property "$tee_failure_completion" gradleExitCode 0
assert_exact_property "$tee_failure_completion" teeExitCode "$tee_failure_exit_code"
assert_exact_property "$tee_failure_completion" wrapperExitCode 1
assert_exact_property "$tee_failure_completion" validationStatus failed
assert_exact_property "$tee_failure_completion" phaseIsolation separate-fresh-source-and-gradle-homes
assert_phase_sequence "$tee_failure_retained_log" "CQENGINE_PHASE=release-preflight"
if [[ ! -f "$tee_failure_fixture/build/reports/mock-tee-terminated.txt" ||
    -e "$tee_failure_fixture/build/reports/mock-release-invoked.txt" ]]; then
    echo "qualification invoked the release graph after a failed preflight tee" >&2
    exit 1
fi

global_fsmonitor_fixture="$(create_fixture hostile-global-fsmonitor)"
seed_passing_outputs "$global_fsmonitor_fixture"
printf 'dirty worktree input\n' >> "$global_fsmonitor_fixture/input.txt"
global_fsmonitor_home="$test_root/global-fsmonitor-home"
global_fsmonitor_hook="$test_root/global-fsmonitor-hook.sh"
global_fsmonitor_sentinel="$test_root/global-fsmonitor-executed"
mkdir -p -- "$global_fsmonitor_home"
{
    printf '#!/usr/bin/bash\n'
    printf 'printf executed > %q\n' "$global_fsmonitor_sentinel"
    printf 'printf "token\\0"\n'
} > "$global_fsmonitor_hook"
chmod +x "$global_fsmonitor_hook"
printf '[core]\n\tfsmonitor = %s\n' "$global_fsmonitor_hook" > "$global_fsmonitor_home/.gitconfig"
global_fsmonitor_log="$test_root/hostile-global-fsmonitor.log"
if HOME="$global_fsmonitor_home" GIT_ATTR_NOSYSTEM=0 CQENGINE_JMH_MACHINE_LABEL=fixture-host \
    "$global_fsmonitor_fixture/scripts/qualify-candidate.sh" > "$global_fsmonitor_log" 2>&1; then
    echo "qualification unexpectedly accepted a dirty source hidden by global fsmonitor" >&2
    exit 1
fi
if ! grep -Fq -- "release qualification requires a clean Git worktree" "$global_fsmonitor_log"; then
    echo "qualification did not ignore hostile global Git configuration" >&2
    exit 1
fi
if [[ -e "$global_fsmonitor_sentinel" ]]; then
    echo "qualification executed the hostile global fsmonitor hook" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$global_fsmonitor_fixture"

included_fsmonitor_fixture="$(create_fixture hostile-included-fsmonitor)"
seed_passing_outputs "$included_fsmonitor_fixture"
included_fsmonitor_config="$test_root/included-fsmonitor.gitconfig"
included_fsmonitor_hook="$test_root/included-fsmonitor-hook.sh"
included_fsmonitor_sentinel="$test_root/included-fsmonitor-executed"
{
    printf '#!/usr/bin/bash\n'
    printf 'printf executed > %q\n' "$included_fsmonitor_sentinel"
    printf 'printf "token\\0"\n'
} > "$included_fsmonitor_hook"
chmod +x "$included_fsmonitor_hook"
printf '[core]\n\tfsmonitor = %s\n' "$included_fsmonitor_hook" > "$included_fsmonitor_config"
git -C "$included_fsmonitor_fixture" config --local include.path "$included_fsmonitor_config"
expect_failure \
    "$included_fsmonitor_fixture" \
    "release qualification rejects local Git include/includeIf configuration" \
    "$test_root/hostile-included-fsmonitor.log"
if [[ -e "$included_fsmonitor_sentinel" ]]; then
    echo "qualification executed a fsmonitor hook from rejected local include configuration" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$included_fsmonitor_fixture"

clean_filter_fixture="$(create_fixture hostile-clean-filter)"
seed_passing_outputs "$clean_filter_fixture"
clean_filter_attributes="$test_root/hostile-clean-filter.attributes"
clean_filter_hook="$test_root/hostile-clean-filter.sh"
clean_filter_sentinel="$test_root/hostile-clean-filter-executed"
printf 'input.txt filter=hide\n' > "$clean_filter_attributes"
{
    printf '#!/usr/bin/bash\n'
    printf 'printf executed > %q\n' "$clean_filter_sentinel"
    printf "/usr/bin/printf 'committed fixture input\\n'\n"
} > "$clean_filter_hook"
chmod +x "$clean_filter_hook"
git -C "$clean_filter_fixture" config --local core.attributesfile "$clean_filter_attributes"
git -C "$clean_filter_fixture" config --local filter.hide.clean "$clean_filter_hook"
printf 'dirty worktree input\n' > "$clean_filter_fixture/input.txt"
expect_failure \
    "$clean_filter_fixture" \
    "release qualification rejects unexpected local Git configuration" \
    "$test_root/hostile-clean-filter.log"
if [[ -e "$clean_filter_sentinel" ]]; then
    echo "qualification executed a clean filter before rejecting local configuration" >&2
    exit 1
fi
assert_passing_outputs_invalidated "$clean_filter_fixture"

replacement_ref_fixture="$(create_fixture replacement-ref)"
seed_passing_outputs "$replacement_ref_fixture"
reviewed_commit="$(git -C "$replacement_ref_fixture" rev-parse HEAD)"
printf 'replacement content\n' > "$replacement_ref_fixture/input.txt"
git -C "$replacement_ref_fixture" add input.txt
git -c core.hooksPath=/dev/null -c commit.gpgSign=false \
    -c user.name="CQEngine qualification fixture" \
    -c user.email=cqengine-qualification-fixture@invalid.example \
    -C "$replacement_ref_fixture" commit --quiet -m "Create replacement object"
replacement_commit="$(git -C "$replacement_ref_fixture" rev-parse HEAD)"
git -C "$replacement_ref_fixture" reset --hard --quiet "$reviewed_commit"
git -C "$replacement_ref_fixture" replace "$reviewed_commit" "$replacement_commit"
expect_failure \
    "$replacement_ref_fixture" \
    "release qualification rejects Git replacement refs" \
    "$test_root/replacement-ref.log"
assert_passing_outputs_invalidated "$replacement_ref_fixture"

grafts_fixture="$(create_fixture legacy-grafts)"
seed_passing_outputs "$grafts_fixture"
mkdir -p -- "$grafts_fixture/.git/info"
git -C "$grafts_fixture" rev-parse HEAD > "$grafts_fixture/.git/info/grafts"
expect_failure \
    "$grafts_fixture" \
    "release qualification rejects legacy Git graft state" \
    "$test_root/legacy-grafts.log"
assert_passing_outputs_invalidated "$grafts_fixture"

shallow_fixture="$(create_fixture shallow-repository)"
seed_passing_outputs "$shallow_fixture"
git -C "$shallow_fixture" rev-parse HEAD > "$shallow_fixture/.git/shallow"
expect_failure \
    "$shallow_fixture" \
    "release qualification requires a complete non-shallow repository" \
    "$test_root/shallow-repository.log"
assert_passing_outputs_invalidated "$shallow_fixture"

remote_fixture="$(create_fixture wrong-remote)"
seed_passing_outputs "$remote_fixture"
git -C "$remote_fixture" remote set-url upstream https://invalid.example/cqengine.git
expect_failure \
    "$remote_fixture" \
    "release qualification requires an exact local or hosted repository configuration" \
    "$test_root/wrong-remote.log"
assert_passing_outputs_invalidated "$remote_fixture"

origin_fixture="$(create_fixture wrong-origin)"
seed_passing_outputs "$origin_fixture"
git -C "$origin_fixture" remote add origin https://invalid.example/cqengine.git
git -C "$origin_fixture" config branch.main.remote origin
git -C "$origin_fixture" config branch.main.merge refs/heads/main
expect_failure \
    "$origin_fixture" \
    "release qualification requires an exact local or hosted repository configuration" \
    "$test_root/wrong-origin.log"
assert_passing_outputs_invalidated "$origin_fixture"

rewritten_remote_fixture="$(create_fixture rewritten-wrong-remote)"
seed_passing_outputs "$rewritten_remote_fixture"
git -C "$rewritten_remote_fixture" remote set-url upstream placeholder://cqengine
git -C "$rewritten_remote_fixture" config \
    url.https://github.com/npgall/cqengine.git.insteadOf placeholder://cqengine
expect_failure \
    "$rewritten_remote_fixture" \
    "release qualification requires an exact local or hosted repository configuration" \
    "$test_root/rewritten-wrong-remote.log"
assert_passing_outputs_invalidated "$rewritten_remote_fixture"

build_link_fixture="$(create_fixture build-link)"
build_link_target="$test_root/build-link-target"
mkdir -p -- "$build_link_target/local-release-evidence/qualification"
printf 'must survive\n' > "$build_link_target/local-release-evidence/qualification/sentinel.txt"
ln -s -- "$build_link_target" "$build_link_fixture/build"
expect_failure \
    "$build_link_fixture" \
    "refusing to qualify a project whose build output path is a symbolic link" \
    "$test_root/build-link.log"
test -f "$build_link_target/local-release-evidence/qualification/sentinel.txt"
if [[ -e "$build_link_fixture/build" || -L "$build_link_fixture/build" ]]; then
    echo "qualification retained the rejected build output symlink" >&2
    exit 1
fi

benchmarks_link_fixture="$(create_fixture benchmarks-parent-link)"
benchmarks_link_target="$test_root/benchmarks-parent-link-target"
mkdir -p -- "$benchmarks_link_target/build/reports"
printf 'must survive\n' > "$benchmarks_link_target/build/reports/sentinel.txt"
ln -s -- "$benchmarks_link_target" "$benchmarks_link_fixture/benchmarks"
expect_failure \
    "$benchmarks_link_fixture" \
    "refusing to qualify a project whose build output path is a symbolic link" \
    "$test_root/benchmarks-parent-link.log"
test -f "$benchmarks_link_target/build/reports/sentinel.txt"
if [[ -e "$benchmarks_link_fixture/benchmarks" || -L "$benchmarks_link_fixture/benchmarks" ]]; then
    echo "qualification retained the rejected benchmarks output symlink" >&2
    exit 1
fi

benchmarks_build_link_fixture="$(create_fixture benchmarks-build-link)"
benchmarks_build_link_target="$test_root/benchmarks-build-link-target"
mkdir -p -- "$benchmarks_build_link_fixture/benchmarks" "$benchmarks_build_link_target/reports"
printf 'must survive\n' > "$benchmarks_build_link_target/reports/sentinel.txt"
ln -s -- "$benchmarks_build_link_target" "$benchmarks_build_link_fixture/benchmarks/build"
expect_failure \
    "$benchmarks_build_link_fixture" \
    "refusing to qualify a project whose build output path is a symbolic link" \
    "$test_root/benchmarks-build-link.log"
test -f "$benchmarks_build_link_target/reports/sentinel.txt"
if [[ -e "$benchmarks_build_link_fixture/benchmarks/build" ||
    -L "$benchmarks_build_link_fixture/benchmarks/build" ]]; then
    echo "qualification retained the rejected benchmarks/build output symlink" >&2
    exit 1
fi

printf 'qualification early-failure regressions passed\n'
