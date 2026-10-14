#!/usr/bin/env bash
#
# Runs Qodana on the files changed between the merge-base with --base and HEAD, like the CI pr-mode run.
#
# Without --base, the base is main fetched from the repository URL into refs/qodana/base, so no remote
# name matters. --no-fetch reuses that ref, and stops if it was never fetched. An explicit --base is
# fetched only when it is <remote>/<branch> for an existing remote, unless --no-fetch.
#
# A diff run does `git checkout -f <merge-base>` and then `git checkout -f <HEAD sha>`, which silently
# discards uncommitted work. This script refuses to start if either checkout could destroy anything, and
# switches back to the original branch afterwards (the CLI leaves HEAD detached).
#
# Extra arguments are passed through to `qodana scan`, e.g. --show-report.
#
# Usage:
#   ./.qodana/diff-scan.sh
#   ./.qodana/diff-scan.sh --dry-run
#   ./.qodana/diff-scan.sh --base origin/main --no-fetch --show-report

set -euo pipefail

repo_url='https://github.com/intellij-elixir/intellij-elixir.git'
default_base='refs/qodana/base'
base="$default_base"
base_given=0
no_fetch=0
dry_run=0
qodana_args=()

while [ $# -gt 0 ]; do
    case "$1" in
        --base)
            base="$2"
            base_given=1
            shift 2
            ;;
        --no-fetch)
            no_fetch=1
            shift
            ;;
        --dry-run)
            dry_run=1
            shift
            ;;
        -h|--help)
            sed -n '2,18p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            qodana_args+=("$1")
            shift
            ;;
    esac
done

abort() {
    local reason="$1"
    shift
    echo "Aborting: $reason" >&2
    for path in "$@"; do
        echo "  $path" >&2
    done
    exit 1
}

root=$(git rev-parse --show-toplevel)
cd "$root"

git_dir=$(git rev-parse --git-dir)
for marker in MERGE_HEAD CHERRY_PICK_HEAD REVERT_HEAD rebase-merge rebase-apply BISECT_LOG; do
    if [ -e "$git_dir/$marker" ]; then
        abort "a git operation is in progress ($marker)."
    fi
done

if [ "$base_given" -eq 0 ]; then
    if [ "$no_fetch" -eq 0 ]; then
        git fetch --quiet "$repo_url" "+refs/heads/main:$default_base"
    elif ! git rev-parse --verify --quiet "$default_base" > /dev/null; then
        abort "$default_base was never fetched. Run once without --no-fetch."
    fi
elif [ "$no_fetch" -eq 0 ]; then
    remote="${base%%/*}"
    branch="${base#*/}"
    if [ "$branch" != "$base" ] && git remote | grep -qx "$remote"; then
        git fetch --quiet "$remote" "$branch"
    fi
fi

head=$(git rev-parse HEAD)
merge_base=$(git merge-base "$base" HEAD)
original_ref=$(git symbolic-ref --short -q HEAD || echo "$head")

mapfile -t dirty < <(git status --porcelain=v1 --untracked-files=no)
if [ "${#dirty[@]}" -gt 0 ]; then
    abort "uncommitted changes to tracked files would be discarded. Commit or stash them first." "${dirty[@]}"
fi

# git status does not report edits hidden behind these flags, but checkout -f overwrites them.
mapfile -t hidden < <(git ls-files -v | grep -E '^[a-zS] ' || true)
if [ "${#hidden[@]}" -gt 0 ]; then
    abort "files are marked assume-unchanged or skip-worktree, so their edits are invisible to git status." "${hidden[@]}"
fi

# Paths tracked at the merge-base but not at HEAD: an untracked or ignored file there gets overwritten.
mapfile -t only_at_base < <(git -c core.quotepath=off diff --name-only --no-renames --diff-filter=D "$merge_base" "$head")
occupied=()
for path in "${only_at_base[@]}"; do
    [ -e "$path" ] && occupied+=("$path")
done
if [ "${#occupied[@]}" -gt 0 ]; then
    abort "untracked or ignored files sit at paths the merge-base checkout would overwrite." "${occupied[@]}"
fi

mapfile -t changed < <(git diff --name-only "$merge_base" "$head")
if [ "${#changed[@]}" -eq 0 ]; then
    echo "No changes between $base and HEAD."
    exit 0
fi

scan_args=(scan --linter qodana-jvm-community --code-climate --within-docker false --diff-start "$merge_base" --diff-end "$head" "${qodana_args[@]}")
echo "Diff: ${#changed[@]} files, $merge_base..$head ($original_ref)"
echo "qodana ${scan_args[*]}"

if [ "$dry_run" -eq 1 ]; then
    exit 0
fi

exit_code=0
qodana "${scan_args[@]}" || exit_code=$?

if ! git checkout --quiet "$original_ref"; then
    echo "Could not switch back to $original_ref; run: git checkout $original_ref" >&2
fi

exit "$exit_code"
