<#
.SYNOPSIS
Runs Qodana on the files changed between the merge-base with -Base and HEAD, like the CI pr-mode run.

.DESCRIPTION
Without -Base, the base is main fetched from the repository URL into refs/qodana/base, so no remote
name matters. -NoFetch reuses that ref, and stops if it was never fetched. An explicit -Base is
fetched only when it is <remote>/<branch> for an existing remote, unless -NoFetch.

A diff run does `git checkout -f <merge-base>` and then `git checkout -f <HEAD sha>`, which silently
discards uncommitted work. This script refuses to start if either checkout could destroy anything, and
switches back to the original branch afterwards (the CLI leaves HEAD detached).

Extra arguments are passed through to `qodana scan`, e.g. `--show-report`.

.EXAMPLE
./.qodana/diff-scan.ps1
./.qodana/diff-scan.ps1 -DryRun
./.qodana/diff-scan.ps1 -Base origin/main -NoFetch --show-report
#>
[CmdletBinding(PositionalBinding = $false)]
param(
    [string] $Base = 'refs/qodana/base',
    [switch] $NoFetch,
    [switch] $DryRun,
    [Parameter(ValueFromRemainingArguments)] [string[]] $QodanaArgs = @()
)

$ErrorActionPreference = 'Stop'

function Invoke-Git {
    $output = & git @args
    if ($LASTEXITCODE -ne 0) { throw "git $args failed with exit code $LASTEXITCODE" }
    $output
}

function Stop-Unsafe([string] $reason, [string[]] $paths = @()) {
    Write-Host "Aborting: $reason" -ForegroundColor Red
    $paths | ForEach-Object { Write-Host "  $_" }
    exit 1
}

$root = Invoke-Git rev-parse --show-toplevel
Set-Location $root

$gitDir = Invoke-Git rev-parse --git-dir
foreach ($marker in 'MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REVERT_HEAD', 'rebase-merge', 'rebase-apply', 'BISECT_LOG') {
    if (Test-Path (Join-Path $gitDir $marker)) { Stop-Unsafe "a git operation is in progress ($marker)." }
}

if (-not $PSBoundParameters.ContainsKey('Base')) {
    if (-not $NoFetch) {
        Invoke-Git fetch --quiet 'https://github.com/intellij-elixir/intellij-elixir.git' "+refs/heads/main:$Base" | Out-Null
    }
    else {
        & git rev-parse --verify --quiet $Base | Out-Null
        if ($LASTEXITCODE -ne 0) { Stop-Unsafe "$Base was never fetched. Run once without -NoFetch." }
    }
}
elseif (-not $NoFetch) {
    $remote, $branch = $Base -split '/', 2
    if ($branch -and (& git remote) -contains $remote) { Invoke-Git fetch --quiet $remote $branch | Out-Null }
}

$head = Invoke-Git rev-parse HEAD
$mergeBase = Invoke-Git merge-base $Base HEAD
$originalRef = & git symbolic-ref --short -q HEAD
if ($LASTEXITCODE -ne 0) { $originalRef = $head }

$dirty = @(Invoke-Git status --porcelain=v1 --untracked-files=no)
if ($dirty.Count) { Stop-Unsafe 'uncommitted changes to tracked files would be discarded. Commit or stash them first.' $dirty }

# git status does not report edits hidden behind these flags, but checkout -f overwrites them.
$hidden = @(Invoke-Git ls-files -v | Where-Object { $_ -cmatch '^[a-zS] ' })
if ($hidden.Count) { Stop-Unsafe 'files are marked assume-unchanged or skip-worktree, so their edits are invisible to git status.' $hidden }

# Paths tracked at the merge-base but not at HEAD: an untracked or ignored file there gets overwritten.
$onlyAtBase = @(Invoke-Git -c core.quotepath=off diff --name-only --no-renames --diff-filter=D $mergeBase $head)
$occupied = @($onlyAtBase | Where-Object { Test-Path -LiteralPath $_ })
if ($occupied.Count) { Stop-Unsafe 'untracked or ignored files sit at paths the merge-base checkout would overwrite.' $occupied }

$changed = @(Invoke-Git diff --name-only $mergeBase $head)
if (-not $changed.Count) { Write-Host "No changes between $Base and HEAD."; exit 0 }

$scanArgs = @('scan', '--linter', 'qodana-jvm-community', '--code-climate', '--within-docker', 'false', '--diff-start', $mergeBase, '--diff-end', $head) + $QodanaArgs
Write-Host "Diff: $($changed.Count) files, $mergeBase..$head ($originalRef)"
Write-Host "qodana $($scanArgs -join ' ')"
if ($DryRun) { exit 0 }

try {
    & qodana @scanArgs
    $exitCode = $LASTEXITCODE
}
finally {
    & git checkout --quiet $originalRef
    if ($LASTEXITCODE -ne 0) { Write-Host "Could not switch back to $originalRef; run: git checkout $originalRef" -ForegroundColor Red }
}
exit $exitCode
