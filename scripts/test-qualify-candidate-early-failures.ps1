#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($args.Count -ne 0) {
    [Console]::Error.WriteLine('usage: scripts/test-qualify-candidate-early-failures.ps1')
    exit 2
}

$scriptDirectory = Split-Path -Parent (Get-Item -LiteralPath $PSCommandPath).FullName
$projectRoot = (Get-Item -LiteralPath (Join-Path $scriptDirectory '..')).FullName
$wrapperSource = Join-Path $projectRoot 'scripts\qualify-candidate.ps1'
$powerShell = Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'
$upstreamUrl = 'https://github.com/npgall/cqengine.git'

if (-not (Test-Path -LiteralPath $wrapperSource -PathType Leaf)) {
    throw "missing qualification wrapper: $wrapperSource"
}
if (-not (Test-Path -LiteralPath $powerShell -PathType Leaf)) {
    throw "missing Windows PowerShell host: $powerShell"
}

$fixtureJavaHome = Split-Path -Parent (Split-Path -Parent ([System.Diagnostics.Process]::GetCurrentProcess().Path))
if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    $fixtureJavaHome = (Get-Item -LiteralPath $env:JAVA_HOME).FullName
}
if (-not (Test-Path -LiteralPath (Join-Path $fixtureJavaHome 'bin\java.exe') -PathType Leaf)) {
    throw "these regressions need an absolute JAVA_HOME containing bin\java.exe; got: $fixtureJavaHome"
}

$testRoot = Join-Path ([System.IO.Path]::GetTempPath()) ('cqengine-qualification-negative.' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null

$script:failures = New-Object System.Collections.Generic.List[string]
$script:reports = New-Object System.Collections.Generic.List[string]

function Invoke-FixtureGit {
    param(
        [Parameter(Mandatory = $true)][string]$Fixture,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs
    )
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $output = & git -C $Fixture @GitArgs 2>&1
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($LASTEXITCODE -ne 0) {
        throw "fixture git $($GitArgs -join ' ') failed: $output"
    }
}

# The wrapper runs with system and global Git configuration disabled, so a fixture committed under an ambient
# core.autocrlf=true would read back as a dirty worktree. Fixture content is therefore always written with LF.
function Write-LfFile([string]$Path, [string[]]$Lines) {
    [System.IO.File]::WriteAllText($Path, ($Lines -join "`n") + "`n", (New-Object System.Text.UTF8Encoding $false))
}

function New-Fixture([string]$Name) {
    $fixture = Join-Path $testRoot $Name
    New-Item -ItemType Directory -Path (Join-Path $fixture 'scripts') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $fixture 'config\benchmark-hosts') -Force | Out-Null
    Copy-Item -LiteralPath $wrapperSource -Destination (Join-Path $fixture 'scripts\qualify-candidate.ps1')
    Write-LfFile (Join-Path $fixture '.gitignore') @('/build/', '/benchmarks/build/')
    Write-LfFile (Join-Path $fixture 'input.txt') @('committed fixture input')
    Write-LfFile (Join-Path $fixture 'config\benchmark-hosts\fixture-host.properties') @(
        'formatVersion=1'
        'machineLabel=fixture-host'
        'operatingSystemRegex=^Windows 11 10[.]0$'
        'kernelRegex=^10[.]0$'
        'architecture=amd64'
        'virtualization=virtual-machine-or-hypervisor'
        'wslVersion=none'
        'cpuModel=fixture'
        'cpuLogicalProcessors=12'
        'projectFileStoreType=NTFS'
        'temporaryFileStoreType=NTFS'
        'evidenceUse=machine-specific-development-baseline'
        'numericReadmeClaims=machine-specific-only'
    )
    & git init --quiet --initial-branch=main $fixture 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "could not initialise fixture repository: $fixture"
    }
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('add', '--all')
    Invoke-FixtureGit -Fixture $fixture -GitArgs @(
        '-c', 'core.hooksPath=NUL', '-c', 'commit.gpgSign=false',
        '-c', 'user.name=CQEngine qualification fixture',
        '-c', 'user.email=cqengine-qualification-fixture@invalid.example',
        'commit', '--quiet', '-m', 'Create qualification fixture'
    )
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('remote', 'add', 'upstream', $upstreamUrl)
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('config', 'branch.main.remote', 'upstream')
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('config', 'branch.main.merge', 'refs/heads/master')
    return $fixture
}

function Add-PassingOutputs([string]$Fixture) {
    $qualification = Join-Path $Fixture 'build\local-release-evidence\qualification'
    New-Item -ItemType Directory -Path $qualification -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Fixture 'build\libs') -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Fixture 'benchmarks\build\reports') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $qualification 'wrapper-completion.properties') -Value 'status=passed'
    Set-Content -LiteralPath (Join-Path $qualification 'local-readiness-manifest.txt') `
        -Value 'sourceCommit=prior-passing-candidate'
    Set-Content -LiteralPath (Join-Path $Fixture 'build\libs\prior.jar') -Value 'prior artifact'
    Set-Content -LiteralPath (Join-Path $Fixture 'benchmarks\build\reports\prior.txt') -Value 'prior benchmark report'
}

function Invoke-FixtureWrapper {
    param(
        [Parameter(Mandatory = $true)][string]$Fixture,
        [string]$MachineLabel = 'fixture-host',
        [switch]$OmitMachineLabel,
        [string]$JavaHomeOverride
    )
    $previousLabel = $env:CQENGINE_JMH_MACHINE_LABEL
    $previousJavaHome = $env:JAVA_HOME
    try {
        if ($OmitMachineLabel) {
            Remove-Item -LiteralPath 'Env:CQENGINE_JMH_MACHINE_LABEL' -ErrorAction SilentlyContinue
        }
        else {
            $env:CQENGINE_JMH_MACHINE_LABEL = $MachineLabel
        }
        $env:JAVA_HOME = if ($PSBoundParameters.ContainsKey('JavaHomeOverride')) {
            $JavaHomeOverride
        }
        else {
            $fixtureJavaHome
        }
        # Diagnostics arrive on stderr as ErrorRecord objects, which would terminate under 'Stop'.
        $previousPreference = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        try {
            $output = & $powerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass `
                -File (Join-Path $Fixture 'scripts\qualify-candidate.ps1') 2>&1
            return [pscustomobject]@{
                ExitCode = $LASTEXITCODE
                Output   = (@($output) | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine
            }
        }
        finally {
            $ErrorActionPreference = $previousPreference
        }
    }
    finally {
        if ($null -eq $previousLabel) {
            Remove-Item -LiteralPath 'Env:CQENGINE_JMH_MACHINE_LABEL' -ErrorAction SilentlyContinue
        }
        else {
            $env:CQENGINE_JMH_MACHINE_LABEL = $previousLabel
        }
        if ($null -eq $previousJavaHome) {
            Remove-Item -LiteralPath 'Env:JAVA_HOME' -ErrorAction SilentlyContinue
        }
        else {
            $env:JAVA_HOME = $previousJavaHome
        }
    }
}

function Assert-QualifyFailure {
    param(
        [Parameter(Mandatory = $true)][string]$Case,
        [Parameter(Mandatory = $true)][string]$Fixture,
        [Parameter(Mandatory = $true)][string]$ExpectedDiagnostic,
        [switch]$OmitMachineLabel,
        [string]$MachineLabel = 'fixture-host',
        [string]$JavaHomeOverride
    )
    $invocation = @{ Fixture = $Fixture; MachineLabel = $MachineLabel }
    if ($OmitMachineLabel) {
        $invocation['OmitMachineLabel'] = $true
    }
    if ($PSBoundParameters.ContainsKey('JavaHomeOverride')) {
        $invocation['JavaHomeOverride'] = $JavaHomeOverride
    }
    $result = Invoke-FixtureWrapper @invocation
    if ($result.ExitCode -eq 0) {
        $script:failures.Add("${Case}: qualification unexpectedly succeeded")
        return
    }
    if ($result.Output -notlike "*$ExpectedDiagnostic*") {
        $script:failures.Add("${Case}: missing diagnostic '$ExpectedDiagnostic'; got: $($result.Output)")
        return
    }
    $script:reports.Add("PASS $Case")
}

function Assert-OutputsInvalidated([string]$Case, [string]$Fixture) {
    foreach ($retained in @('build', 'benchmarks\build')) {
        if (Test-Path -LiteralPath (Join-Path $Fixture $retained)) {
            $script:failures.Add("${Case}: an early failure retained prior passing output $retained")
        }
    }
}

try {
    # 1. Missing machine label.
    $fixture = New-Fixture 'missing-machine-label'
    Add-PassingOutputs $fixture
    Assert-QualifyFailure -Case 'missing-machine-label' -Fixture $fixture -OmitMachineLabel `
        -ExpectedDiagnostic 'set CQENGINE_JMH_MACHINE_LABEL to a 3-64 character machine label'
    Assert-OutputsInvalidated 'missing-machine-label' $fixture

    # 2. Syntactically invalid machine label.
    $fixture = New-Fixture 'invalid-machine-label'
    Add-PassingOutputs $fixture
    Assert-QualifyFailure -Case 'invalid-machine-label' -Fixture $fixture -MachineLabel 'ab' `
        -ExpectedDiagnostic 'set CQENGINE_JMH_MACHINE_LABEL to a 3-64 character machine label'
    Assert-OutputsInvalidated 'invalid-machine-label' $fixture

    # 3. Label without a reviewed host approval record.
    $fixture = New-Fixture 'unapproved-machine-label'
    Add-PassingOutputs $fixture
    Assert-QualifyFailure -Case 'unapproved-machine-label' -Fixture $fixture -MachineLabel 'unreviewed-host' `
        -ExpectedDiagnostic 'missing benchmark host approval record'
    Assert-OutputsInvalidated 'unapproved-machine-label' $fixture

    # 4. Relative JAVA_HOME.
    $fixture = New-Fixture 'relative-java-home'
    Add-PassingOutputs $fixture
    Assert-QualifyFailure -Case 'relative-java-home' -Fixture $fixture -JavaHomeOverride 'relative\jdk' `
        -ExpectedDiagnostic 'qualification requires an absolute JAVA_HOME'
    Assert-OutputsInvalidated 'relative-java-home' $fixture

    # 5. Dirty worktree.
    $fixture = New-Fixture 'dirty-worktree'
    Add-PassingOutputs $fixture
    Write-LfFile (Join-Path $fixture 'input.txt') @('uncommitted edit')
    Assert-QualifyFailure -Case 'dirty-worktree' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification requires a clean Git worktree'
    Assert-OutputsInvalidated 'dirty-worktree' $fixture

    # 6. Untracked file.
    $fixture = New-Fixture 'untracked-file'
    Write-LfFile (Join-Path $fixture 'stray.txt') @('untracked')
    Assert-QualifyFailure -Case 'untracked-file' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification requires a clean Git worktree'

    # 7. assume-unchanged index entry.
    $fixture = New-Fixture 'assume-unchanged-index'
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('update-index', '--assume-unchanged', 'input.txt')
    Assert-QualifyFailure -Case 'assume-unchanged-index' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification rejects assume-unchanged, skip-worktree, sparse or unmerged index entries'

    # 8. Wrong upstream remote.
    $fixture = New-Fixture 'wrong-upstream-remote'
    Add-PassingOutputs $fixture
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('remote', 'set-url', 'upstream', 'https://invalid.example/cqengine.git')
    Assert-QualifyFailure -Case 'wrong-upstream-remote' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification requires an exact local or hosted repository configuration'
    Assert-OutputsInvalidated 'wrong-upstream-remote' $fixture

    # 9. Missing upstream remote.
    $fixture = New-Fixture 'missing-upstream-remote'
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('remote', 'remove', 'upstream')
    Assert-QualifyFailure -Case 'missing-upstream-remote' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification requires an exact local or hosted repository configuration'

    # 10. Unreviewed origin remote.
    $fixture = New-Fixture 'wrong-origin-remote'
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('remote', 'add', 'origin', 'https://invalid.example/cqengine.git')
    Assert-QualifyFailure -Case 'wrong-origin-remote' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification requires an exact local or hosted repository configuration'

    # 11. Git replacement ref.
    $fixture = New-Fixture 'replacement-ref'
    Add-PassingOutputs $fixture
    $reviewedCommit = (& git -C $fixture rev-parse HEAD).Trim()
    Write-LfFile (Join-Path $fixture 'input.txt') @('replacement content')
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('add', 'input.txt')
    Invoke-FixtureGit -Fixture $fixture -GitArgs @(
        '-c', 'core.hooksPath=NUL', '-c', 'commit.gpgSign=false',
        '-c', 'user.name=CQEngine qualification fixture',
        '-c', 'user.email=cqengine-qualification-fixture@invalid.example',
        'commit', '--quiet', '-m', 'Create replacement object'
    )
    $replacementCommit = (& git -C $fixture rev-parse HEAD).Trim()
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('reset', '--hard', '--quiet', $reviewedCommit)
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('replace', $reviewedCommit, $replacementCommit)
    Assert-QualifyFailure -Case 'replacement-ref' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification rejects Git replacement refs'
    Assert-OutputsInvalidated 'replacement-ref' $fixture

    # 12. Legacy grafts.
    $fixture = New-Fixture 'legacy-grafts'
    New-Item -ItemType Directory -Path (Join-Path $fixture '.git\info') -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $fixture '.git\info\grafts') -Value (& git -C $fixture rev-parse HEAD).Trim()
    Assert-QualifyFailure -Case 'legacy-grafts' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification rejects grafts or shallow repositories'

    # 13. Shallow repository marker.
    $fixture = New-Fixture 'shallow-repository'
    Set-Content -LiteralPath (Join-Path $fixture '.git\shallow') -Value (& git -C $fixture rev-parse HEAD).Trim()
    Assert-QualifyFailure -Case 'shallow-repository' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification rejects grafts or shallow repositories'

    # 14. Local include configuration, which can attach hostile hooks and filters.
    $fixture = New-Fixture 'local-include-configuration'
    $includedConfig = Join-Path $testRoot 'hostile-include.config'
    Set-Content -LiteralPath $includedConfig -Value @('[core]', "`tfsmonitor = NUL")
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('config', '--local', 'include.path', $includedConfig)
    Assert-QualifyFailure -Case 'local-include-configuration' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification rejects local Git include/includeIf configuration'

    # 15. Unexpected local configuration key.
    $fixture = New-Fixture 'unexpected-local-configuration'
    Invoke-FixtureGit -Fixture $fixture -GitArgs @('config', '--local', 'core.fsmonitor', 'true')
    Assert-QualifyFailure -Case 'unexpected-local-configuration' -Fixture $fixture `
        -ExpectedDiagnostic 'release qualification rejects unexpected local Git configuration'

    # 16. Symbolic-link build output. Creating one needs Developer Mode or elevation, so an unsupported
    # host is reported rather than counted as a pass.
    $fixture = New-Fixture 'build-output-symlink'
    $linkTarget = Join-Path $testRoot 'build-link-target'
    New-Item -ItemType Directory -Path (Join-Path $linkTarget 'local-release-evidence\qualification') -Force | Out-Null
    $sentinel = Join-Path $linkTarget 'local-release-evidence\qualification\sentinel.txt'
    Set-Content -LiteralPath $sentinel -Value 'must survive'
    $linkCreated = $true
    try {
        New-Item -ItemType SymbolicLink -Path (Join-Path $fixture 'build') -Target $linkTarget -ErrorAction Stop | Out-Null
    }
    catch {
        $linkCreated = $false
        $script:reports.Add('SKIP build-output-symlink: this host cannot create symbolic links (needs Developer Mode or elevation)')
    }
    if ($linkCreated) {
        Assert-QualifyFailure -Case 'build-output-symlink' -Fixture $fixture `
            -ExpectedDiagnostic 'refusing to qualify a project whose build output path is a symbolic link'
        if (-not (Test-Path -LiteralPath $sentinel)) {
            $script:failures.Add('build-output-symlink: qualification deleted content through the rejected link')
        }
        if (Test-Path -LiteralPath (Join-Path $fixture 'build')) {
            $script:failures.Add('build-output-symlink: qualification retained the rejected build output symlink')
        }
    }
}
finally {
    if ($testRoot -like (Join-Path ([System.IO.Path]::GetTempPath()) 'cqengine-qualification-negative.*')) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    else {
        [Console]::Error.WriteLine("refusing to remove unexpected qualification test root: $testRoot")
    }
}

$script:reports | ForEach-Object { Write-Host $_ }
if ($script:failures.Count -ne 0) {
    $script:failures | ForEach-Object { [Console]::Error.WriteLine($_) }
    [Console]::Error.WriteLine("$($script:failures.Count) qualification early-failure regressions failed")
    exit 1
}
Write-Host 'qualification early-failure regressions passed'
