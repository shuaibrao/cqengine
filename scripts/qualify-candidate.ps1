#Requires -Version 5.1
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Write-QualifyError([string]$Message) {
    [Console]::Error.WriteLine($Message)
}

function Get-Sha256Hex([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-Sha512Hex([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA512).Hash.ToLowerInvariant()
}

function Resolve-CanonicalFile([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item -is [System.IO.DirectoryInfo]) {
        throw "expected a file path: $Path"
    }
    return $item.FullName
}

function Resolve-CanonicalDirectory([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    if ($item -isnot [System.IO.DirectoryInfo]) {
        throw "expected a directory path: $Path"
    }
    return $item.FullName
}

function Test-NonWritablePath([string]$Path) {
    $item = Get-Item -LiteralPath $Path -Force
    try {
        $probe = Join-Path $item.FullName ('.cqengine-write-probe-' + [guid]::NewGuid().ToString('N'))
        if ($item -is [System.IO.DirectoryInfo]) {
            [System.IO.File]::WriteAllText($probe, 'probe')
            Remove-Item -LiteralPath $probe -Force
            return $false
        }
        $stream = [System.IO.File]::Open($item.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::ReadWrite, [System.IO.FileShare]::None)
        $stream.Dispose()
        return $false
    }
    catch {
        return $true
    }
}

function Get-ManifestValue([string]$ManifestPath, [string]$Key) {
    $prefix = "$Key="
    $manifestLines = @(Get-Content -LiteralPath $ManifestPath | Where-Object { $_.StartsWith($prefix) })
    if ($manifestLines.Count -ne 1) {
        throw "manifest key count for $Key was $($manifestLines.Count)"
    }
    return $manifestLines[0].Substring($prefix.Length)
}

function Invoke-OptionalGit {
    param(
        [Parameter(Mandatory = $true)][string]$Git,
        [Parameter(Mandatory = $true)][string]$WorkDir,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs
    )
    # Clearing configuration that may legitimately be absent; native stderr would otherwise terminate under 'Stop'.
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        & $Git -C $WorkDir @GitArgs 2>&1 | Out-Null
    }
    finally {
        $ErrorActionPreference = $previousPreference
    }
}

function Invoke-TrustedGit {
    param(
        [Parameter(Mandatory = $true)][string]$Git,
        [Parameter(Mandatory = $true)][string]$WorkDir,
        [Parameter(ValueFromRemainingArguments = $true)][string[]]$GitArgs
    )
    & $Git -C $WorkDir @GitArgs
    if ($LASTEXITCODE -ne 0) {
        throw "git $($GitArgs -join ' ') failed with exit $LASTEXITCODE"
    }
}

if ($args.Count -ne 0) {
    Write-QualifyError 'usage: scripts/qualify-candidate.ps1'
    exit 2
}

# The Windows counterpart of privileged Bash. A profile, module or dot-sourced script can shadow any cmdlet below
# with a function or alias, so every name this wrapper depends on must still resolve to its shipped cmdlet.
foreach ($qualifyCommandName in @(
        'Add-Content', 'Copy-Item', 'ForEach-Object', 'Get-ChildItem', 'Get-Command', 'Get-Content',
        'Get-FileHash', 'Get-Item', 'Get-Location', 'Join-Path', 'New-Item', 'Out-Null', 'Remove-Item',
        'Set-Content', 'Set-Location', 'Split-Path', 'Test-Path', 'Where-Object', 'Write-Host')) {
    # Get-FileHash ships as a module function rather than a cmdlet; a hostile override carries no shipped module.
    $resolved = Get-Command -Name $qualifyCommandName -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($null -eq $resolved -or $resolved.CommandType -notin @('Cmdlet', 'Function') -or
        $resolved.ModuleName -notlike 'Microsoft.PowerShell.*') {
        Write-QualifyError "qualification cmdlet is shadowed or unavailable: $qualifyCommandName"
        Write-QualifyError 'run powershell -NoProfile -ExecutionPolicy Bypass -File scripts\qualify-candidate.ps1'
        exit 1
    }
}

$scriptPath = Resolve-CanonicalFile $PSCommandPath
$scriptDirectory = Split-Path -Parent $scriptPath
$projectRoot = Resolve-CanonicalDirectory (Join-Path $scriptDirectory '..')

foreach ($outputPath in @(
        (Join-Path $projectRoot 'build'),
        (Join-Path $projectRoot 'benchmarks\build')
    )) {
    if (Test-Path -LiteralPath $outputPath) {
        $item = Get-Item -LiteralPath $outputPath -Force
        if ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) {
            Remove-Item -LiteralPath $outputPath -Force
            Write-QualifyError 'refusing to qualify a project whose build output path is a symbolic link'
            exit 1
        }
    }
}
Remove-Item -LiteralPath (Join-Path $projectRoot 'build') -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath (Join-Path $projectRoot 'benchmarks\build') -Recurse -Force -ErrorAction SilentlyContinue

$javaHome = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHome) -or -not [System.IO.Path]::IsPathRooted($javaHome)) {
    Write-QualifyError 'qualification requires an absolute JAVA_HOME'
    exit 1
}
$bootstrapJava = Resolve-CanonicalFile (Join-Path $javaHome 'bin\java.exe')
$bootstrapJavaSha256 = Get-Sha256Hex $bootstrapJava
$javaHome = Resolve-CanonicalDirectory (Split-Path -Parent (Split-Path -Parent $bootstrapJava))

$gitCmdCandidates = @(
    @(${env:ProgramFiles}, ${env:ProgramFiles(x86)}) |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { Join-Path $_ 'Git\cmd\git.exe' } |
        Where-Object { Test-Path -LiteralPath $_ -PathType Leaf }
)
if ($gitCmdCandidates.Count -lt 1) {
    Write-QualifyError 'qualification requires Git for Windows at Program Files\Git'
    exit 1
}
$trustedGit = Resolve-CanonicalFile $gitCmdCandidates[0]
$gitRoot = Resolve-CanonicalDirectory (Split-Path -Parent (Split-Path -Parent $trustedGit))
$gitUsrBin = Resolve-CanonicalDirectory (Join-Path $gitRoot 'usr\bin')
$gitCmd = Resolve-CanonicalDirectory (Join-Path $gitRoot 'cmd')

$trustedBash = Resolve-CanonicalFile (Join-Path $gitUsrBin 'bash.exe')
$trustedSh = Resolve-CanonicalFile (Join-Path $gitUsrBin 'sh.exe')
$trustedNproc = Resolve-CanonicalFile (Join-Path $gitUsrBin 'nproc.exe')
$trustedTar = Resolve-CanonicalFile (Join-Path $gitUsrBin 'tar.exe')
$javaBin = Resolve-CanonicalDirectory (Join-Path $javaHome 'bin')

foreach ($toolPath in @($trustedGit, $trustedBash, $trustedSh, $trustedNproc, $trustedTar)) {
    $parent = Split-Path -Parent $toolPath
    if (-not (Test-NonWritablePath $toolPath) -or -not (Test-NonWritablePath $parent)) {
        Write-QualifyError "qualification tool is not a trusted non-writable system executable: $toolPath"
        exit 1
    }
}

$trustedGitSha256 = Get-Sha256Hex $trustedGit
$trustedTarSha256 = Get-Sha256Hex $trustedTar
$trustedShSha256 = Get-Sha256Hex $trustedSh
$trustedBashSha256 = Get-Sha256Hex $trustedBash
$trustedNprocSha256 = Get-Sha256Hex $trustedNproc

$trustedPath = ($gitCmd, $gitUsrBin, $javaBin) -join ';'
$env:JAVA_HOME = $javaHome
$env:PATH = $trustedPath
$env:CQENGINE_TRUSTED_PATH = $trustedPath
$env:CQENGINE_TRUSTED_GIT = $trustedGit
$env:CQENGINE_TRUSTED_GIT_SHA256 = $trustedGitSha256
$env:CQENGINE_TRUSTED_TAR = $trustedTar
$env:CQENGINE_TRUSTED_TAR_SHA256 = $trustedTarSha256
$env:CQENGINE_TRUSTED_SH = $trustedSh
$env:CQENGINE_TRUSTED_SH_SHA256 = $trustedShSha256
$env:CQENGINE_TRUSTED_BASH = $trustedBash
$env:CQENGINE_TRUSTED_BASH_SHA256 = $trustedBashSha256
$env:CQENGINE_TRUSTED_NPROC = $trustedNproc
$env:CQENGINE_TRUSTED_NPROC_SHA256 = $trustedNprocSha256
$env:CQENGINE_TRUSTED_JAVA = $bootstrapJava
$env:CQENGINE_TRUSTED_JAVA_SHA256 = $bootstrapJavaSha256
$env:CQENGINE_QUALIFY_COMMAND = 'scripts/qualify-candidate.ps1'
$env:CQENGINE_RELEASE_INVOCATION = '1'
$env:GIT_CONFIG_NOSYSTEM = '1'
$env:GIT_CONFIG_GLOBAL = 'NUL'
$env:GIT_NO_REPLACE_OBJECTS = '1'
$env:GIT_ATTR_NOSYSTEM = '1'

Get-ChildItem Env: | Where-Object {
    $_.Name -match '^(GIT_|ORG_GRADLE_PROJECT_)' -and
    $_.Name -notin @('GIT_CONFIG_NOSYSTEM', 'GIT_CONFIG_GLOBAL', 'GIT_NO_REPLACE_OBJECTS', 'GIT_ATTR_NOSYSTEM')
} | ForEach-Object {
    Remove-Item -LiteralPath ("Env:{0}" -f $_.Name) -Force
}
foreach ($unsafeName in @('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'JAVA_OPTS', 'GRADLE_OPTS')) {
    if (Test-Path -LiteralPath ("Env:{0}" -f $unsafeName)) {
        Remove-Item -LiteralPath ("Env:{0}" -f $unsafeName) -Force
    }
}

$machineLabel = $env:CQENGINE_JMH_MACHINE_LABEL
if ($machineLabel -notmatch '^[A-Za-z0-9][A-Za-z0-9._-]{2,63}$') {
    Write-QualifyError 'set CQENGINE_JMH_MACHINE_LABEL to a 3-64 character machine label'
    exit 2
}

$hostRecord = Join-Path $projectRoot ("config\benchmark-hosts\{0}.properties" -f $machineLabel)
if (-not (Test-Path -LiteralPath $hostRecord -PathType Leaf)) {
    Write-QualifyError "missing benchmark host approval record: $hostRecord"
    exit 1
}

$temporaryRoot = if (-not [string]::IsNullOrWhiteSpace($env:TEMP)) { $env:TEMP } else { $env:TMP }
$temporaryRoot = Resolve-CanonicalDirectory $temporaryRoot
$preflightGradleUserHome = Join-Path $temporaryRoot ("cqengine-release-gradle-home." + [guid]::NewGuid().ToString('N'))
$releaseGradleUserHome = $null
$candidateRoot = Join-Path $temporaryRoot ("cqengine-release-source." + [guid]::NewGuid().ToString('N'))
$qualificationLog = Join-Path $temporaryRoot ("cqengine-release-output." + [guid]::NewGuid().ToString('N') + '.log')
$qualificationStart = Join-Path $temporaryRoot ("cqengine-release-start." + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $preflightGradleUserHome | Out-Null
New-Item -ItemType Directory -Path $candidateRoot | Out-Null
New-Item -ItemType File -Path $qualificationLog | Out-Null
New-Item -ItemType File -Path $qualificationStart | Out-Null

function Assert-EmptyDirectory([string]$Path, [string]$Description) {
    if (@(Get-ChildItem -LiteralPath $Path -Force).Count -ne 0) {
        Write-QualifyError "$Description was not created empty: $Path"
        exit 1
    }
}

Assert-EmptyDirectory $preflightGradleUserHome 'preflight Gradle home'
Assert-EmptyDirectory $candidateRoot 'release candidate source'

function Remove-QualificationTemp {
    foreach ($isolatedGradleHome in @($preflightGradleUserHome, $releaseGradleUserHome)) {
        if ([string]::IsNullOrWhiteSpace($isolatedGradleHome)) {
            continue
        }
        if ($isolatedGradleHome -like (Join-Path $temporaryRoot 'cqengine-release-gradle-home.*')) {
            Remove-Item -LiteralPath $isolatedGradleHome -Recurse -Force -ErrorAction SilentlyContinue
        }
        else {
            Write-QualifyError "refusing to remove unexpected Gradle home: $isolatedGradleHome"
        }
    }
    if ($qualificationLog -like (Join-Path $temporaryRoot 'cqengine-release-output.*.log')) {
        Remove-Item -LiteralPath $qualificationLog -Force -ErrorAction SilentlyContinue
    }
    else {
        Write-QualifyError "refusing to remove unexpected qualification log: $qualificationLog"
    }
    if ($candidateRoot -like (Join-Path $temporaryRoot 'cqengine-release-source.*')) {
        Remove-Item -LiteralPath $candidateRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    else {
        Write-QualifyError "refusing to remove unexpected candidate source: $candidateRoot"
    }
    if ($qualificationStart -like (Join-Path $temporaryRoot 'cqengine-release-start.*')) {
        Remove-Item -LiteralPath $qualificationStart -Force -ErrorAction SilentlyContinue
    }
    else {
        Write-QualifyError "refusing to remove unexpected qualification marker: $qualificationStart"
    }
}

try {
    $replaceRefs = & $trustedGit -C $projectRoot for-each-ref '--format=%(refname)' refs/replace/
    if ($LASTEXITCODE -ne 0) {
        throw 'could not inspect Git replace refs'
    }
    if (-not [string]::IsNullOrWhiteSpace($replaceRefs)) {
        Write-QualifyError "release qualification rejects Git replacement refs: $replaceRefs"
        exit 1
    }
    $graftsPath = & $trustedGit -C $projectRoot rev-parse --path-format=absolute --git-path info/grafts
    $shallowPath = & $trustedGit -C $projectRoot rev-parse --path-format=absolute --git-path shallow
    if ((Test-Path -LiteralPath $graftsPath) -or (Test-Path -LiteralPath $shallowPath)) {
        Write-QualifyError 'release qualification rejects grafts or shallow repositories'
        exit 1
    }

    function Get-LocalGitConfigValues([string]$Key) {
        $output = & $trustedGit -C $projectRoot config --local --no-includes --get-all $Key 2>&1
        $code = $LASTEXITCODE
        if ($code -gt 1) {
            throw "could not inspect raw local Git setting ${Key}: $output"
        }
        if ($code -eq 0) {
            return @($output)
        }
        return @()
    }

    $localIncludeKeys = & $trustedGit -C $projectRoot config --local --no-includes --name-only --get-regexp '^(include|includeif[.].*)[.]path$' 2>&1
    $includeExit = $LASTEXITCODE
    if ($includeExit -gt 1) {
        throw "could not inspect raw local Git include settings: $localIncludeKeys"
    }
    if ($includeExit -eq 0 -and -not [string]::IsNullOrWhiteSpace(($localIncludeKeys | Out-String).Trim())) {
        Write-QualifyError 'release qualification rejects local Git include/includeIf configuration'
        exit 1
    }

    $originUrlValues = @(Get-LocalGitConfigValues 'remote.origin.url')
    $repositoryMode = $null
    if ($originUrlValues.Count -eq 0) {
        $repositoryMode = 'local'
    }
    elseif ($originUrlValues.Count -eq 1 -and $originUrlValues[0] -eq 'https://github.com/shuaibrao/cqengine.git') {
        $repositoryMode = 'hosted'
    }
    else {
        Write-QualifyError 'release qualification requires an exact local or hosted repository configuration'
        exit 1
    }
    $upstreamUrlValues = @(Get-LocalGitConfigValues 'remote.upstream.url')
    if ($upstreamUrlValues.Count -ne 1 -or $upstreamUrlValues[0] -ne 'https://github.com/npgall/cqengine.git') {
        Write-QualifyError 'release qualification requires an exact local or hosted repository configuration'
        exit 1
    }

    $localGitKeys = @(& $trustedGit -C $projectRoot config --local --no-includes --name-only --list)
    if ($LASTEXITCODE -ne 0) {
        throw 'could not list local Git configuration'
    }
    $localGitKeyCounts = @{}
    $unexpectedLocalGitKeys = New-Object System.Collections.Generic.List[string]
    foreach ($localGitKey in $localGitKeys) {
        $normalized = $localGitKey.ToLowerInvariant()
        if (-not $localGitKeyCounts.ContainsKey($normalized)) {
            $localGitKeyCounts[$normalized] = 0
        }
        $localGitKeyCounts[$normalized] = $localGitKeyCounts[$normalized] + 1
        switch ($normalized) {
            { $_ -in @(
                    'branch.main.merge', 'branch.main.remote', 'core.bare', 'core.filemode', 'core.logallrefupdates',
                    'core.repositoryformatversion', 'core.autocrlf', 'core.eol', 'core.symlinks', 'core.ignorecase',
                    'remote.origin.fetch', 'remote.origin.url', 'remote.upstream.fetch', 'remote.upstream.url'
                ) } { }
            default { $unexpectedLocalGitKeys.Add($localGitKey) }
        }
    }
    if ($unexpectedLocalGitKeys.Count -ne 0) {
        Write-QualifyError ("release qualification rejects unexpected local Git configuration: {0}" -f ($unexpectedLocalGitKeys -join ' '))
        exit 1
    }

    function Require-ExactLocalGitConfig([string]$Key, [string]$Expected) {
        $actualValues = @(Get-LocalGitConfigValues $Key)
        $count = 0
        if ($localGitKeyCounts.ContainsKey($Key.ToLowerInvariant())) {
            $count = $localGitKeyCounts[$Key.ToLowerInvariant()]
        }
        $actual = if ($actualValues.Count -eq 1) { $actualValues[0] } else { ($actualValues -join "`n") }
        if ($count -ne 1 -or $actual -ne $Expected) {
            if ($Key -like 'remote.*.url' -or $Key -like 'remote.*.fetch') {
                Write-QualifyError 'release qualification requires an exact local or hosted repository configuration'
                exit 1
            }
            Write-QualifyError "release qualification requires exact local Git setting ${Key}=${Expected}"
            exit 1
        }
    }

    Require-ExactLocalGitConfig 'core.repositoryformatversion' '0'
    Require-ExactLocalGitConfig 'core.filemode' 'false'
    Require-ExactLocalGitConfig 'core.bare' 'false'
    Require-ExactLocalGitConfig 'core.logallrefupdates' 'true'
    Require-ExactLocalGitConfig 'core.symlinks' 'false'
    Require-ExactLocalGitConfig 'core.ignorecase' 'true'
    Require-ExactLocalGitConfig 'remote.upstream.url' 'https://github.com/npgall/cqengine.git'
    Require-ExactLocalGitConfig 'remote.upstream.fetch' '+refs/heads/*:refs/remotes/upstream/*'
    if ($repositoryMode -eq 'hosted') {
        Require-ExactLocalGitConfig 'remote.origin.url' 'https://github.com/shuaibrao/cqengine.git'
        Require-ExactLocalGitConfig 'remote.origin.fetch' '+refs/heads/*:refs/remotes/origin/*'
    }
    elseif (($localGitKeyCounts['remote.origin.url'] -as [int]) -gt 0 -or ($localGitKeyCounts['remote.origin.fetch'] -as [int]) -gt 0) {
        Write-QualifyError 'release qualification rejects partial or unexpected origin configuration in local mode'
        exit 1
    }

    $branchConfigCount = ([int]($localGitKeyCounts['branch.main.remote'] -as [int])) + ([int]($localGitKeyCounts['branch.main.merge'] -as [int]))
    $detachedConfigCount = ([int]($localGitKeyCounts['core.autocrlf'] -as [int])) + ([int]($localGitKeyCounts['core.eol'] -as [int]))
    if ($branchConfigCount -eq 2 -and $detachedConfigCount -eq 0) {
        if ($repositoryMode -eq 'hosted') {
            Require-ExactLocalGitConfig 'branch.main.remote' 'origin'
            Require-ExactLocalGitConfig 'branch.main.merge' 'refs/heads/main'
        }
        else {
            Require-ExactLocalGitConfig 'branch.main.remote' 'upstream'
            Require-ExactLocalGitConfig 'branch.main.merge' 'refs/heads/master'
        }
    }
    elseif ($branchConfigCount -eq 0 -and $detachedConfigCount -eq 2) {
        Require-ExactLocalGitConfig 'core.autocrlf' 'false'
        Require-ExactLocalGitConfig 'core.eol' 'lf'
    }
    else {
        Write-QualifyError 'release qualification requires exact main-branch or detached-checkout Git configuration'
        exit 1
    }

    $status = & $trustedGit -C $projectRoot status --porcelain=v1 --untracked-files=all
    if ($LASTEXITCODE -ne 0) {
        throw 'git status failed'
    }
    if (-not [string]::IsNullOrWhiteSpace($status)) {
        Write-QualifyError 'release qualification requires a clean Git worktree'
        exit 1
    }
    $lsFiles = & $trustedGit -C $projectRoot ls-files -v
    if ($LASTEXITCODE -ne 0) {
        throw 'git ls-files failed'
    }
    # -cne: git marks assume-unchanged and skip-worktree entries with the lowercase tag, and -ne ignores case.
    $badIndex = @($lsFiles | Where-Object { $_.Length -gt 0 -and $_.Substring(0, 1) -cne 'H' })
    if ($badIndex.Count -ne 0) {
        Write-QualifyError 'release qualification rejects assume-unchanged, skip-worktree, sparse or unmerged index entries'
        exit 1
    }
    $gitToplevel = Resolve-CanonicalDirectory (& $trustedGit -C $projectRoot rev-parse --show-toplevel)
    if ($gitToplevel -ne $projectRoot) {
        Write-QualifyError 'release qualification requires the physical project root to be the Git worktree root'
        exit 1
    }
    $sourceCommit = (& $trustedGit -C $projectRoot rev-parse --verify HEAD).Trim()
    $sourceTree = (& $trustedGit -C $projectRoot rev-parse --verify 'HEAD^{tree}').Trim()
    $expectedRemotes = if ($repositoryMode -eq 'hosted') { @('origin', 'upstream') } else { @('upstream') }
    $actualRemotes = @(& $trustedGit -C $projectRoot remote)
    if (($actualRemotes -join "`n") -ne ($expectedRemotes -join "`n")) {
        Write-QualifyError 'release qualification requires an exact local or hosted repository configuration'
        exit 1
    }

    function Materialize-Candidate([string]$Destination) {
        if (Test-Path -LiteralPath $Destination) {
            Remove-Item -LiteralPath $Destination -Recurse -Force
        }
        New-Item -ItemType Directory -Path $Destination | Out-Null
        & $trustedGit -c core.hooksPath=NUL clone --no-checkout --no-hardlinks --quiet $projectRoot $Destination
        if ($LASTEXITCODE -ne 0) {
            throw 'detached candidate clone failed'
        }
        Invoke-TrustedGit -Git $trustedGit -WorkDir $Destination -GitArgs @('config', 'core.autocrlf', 'false')
        Invoke-TrustedGit -Git $trustedGit -WorkDir $Destination -GitArgs @('config', 'core.eol', 'lf')
        Invoke-OptionalGit -Git $trustedGit -WorkDir $Destination -GitArgs @('config', '--unset-all', 'branch.main.remote')
        Invoke-OptionalGit -Git $trustedGit -WorkDir $Destination -GitArgs @('config', '--unset-all', 'branch.main.merge')
        if ($repositoryMode -eq 'hosted') {
            Invoke-TrustedGit -Git $trustedGit -WorkDir $Destination -GitArgs @(
                'remote', 'set-url', 'origin', 'https://github.com/shuaibrao/cqengine.git'
            )
            Invoke-TrustedGit -Git $trustedGit -WorkDir $Destination -GitArgs @(
                'config', 'remote.origin.fetch', '+refs/heads/*:refs/remotes/origin/*'
            )
        }
        else {
            Invoke-OptionalGit -Git $trustedGit -WorkDir $Destination -GitArgs @('remote', 'remove', 'origin')
        }
        Invoke-OptionalGit -Git $trustedGit -WorkDir $Destination -GitArgs @('remote', 'remove', 'upstream')
        Invoke-TrustedGit -Git $trustedGit -WorkDir $Destination -GitArgs @(
            'remote', 'add', 'upstream', 'https://github.com/npgall/cqengine.git'
        )
        Invoke-TrustedGit -Git $trustedGit -WorkDir $Destination -GitArgs @('update-ref', '--no-deref', 'HEAD', $sourceCommit)
        Invoke-TrustedGit -Git $trustedGit -WorkDir $Destination -GitArgs @('read-tree', $sourceCommit)
        $archiveFile = Join-Path $temporaryRoot ("cqengine-archive." + [guid]::NewGuid().ToString('N') + '.tar')
        try {
            & $trustedGit -c core.attributesFile=NUL -C $projectRoot archive --format=tar -o $archiveFile $sourceCommit
            if ($LASTEXITCODE -ne 0) {
                throw 'git archive failed'
            }
            & $trustedTar -xf $archiveFile -C $Destination
            if ($LASTEXITCODE -ne 0) {
                throw 'tar extract of git archive failed'
            }
        }
        finally {
            if (Test-Path -LiteralPath $archiveFile) {
                Remove-Item -LiteralPath $archiveFile -Force -ErrorAction SilentlyContinue
            }
        }
        $candidateCommit = (& $trustedGit -C $Destination rev-parse --verify HEAD).Trim()
        $candidateTree = (& $trustedGit -C $Destination rev-parse --verify 'HEAD^{tree}').Trim()
        $candidateStatus = & $trustedGit -C $Destination status --porcelain=v1 --untracked-files=all
        if ($candidateCommit -ne $sourceCommit -or $candidateTree -ne $sourceTree -or -not [string]::IsNullOrWhiteSpace($candidateStatus)) {
            throw 'detached candidate does not exactly represent the committed source'
        }
    }

    Materialize-Candidate $candidateRoot
    [System.IO.File]::WriteAllText(
        (Join-Path $preflightGradleUserHome '.cqengine-clean-release-home'),
        ("project={0}`nstate=created-empty`n" -f $candidateRoot)
    )

    function Invoke-ReleaseGradle([string]$IsolatedGradleHome, [string[]]$GradleArgs) {
        $env:GRADLE_USER_HOME = $IsolatedGradleHome
        $env:CQENGINE_RELEASE_INVOCATION = '1'
        $env:CQENGINE_QUALIFY_COMMAND = 'scripts/qualify-candidate.ps1'
        $env:CQENGINE_JMH_MACHINE_LABEL = $machineLabel
        $gradlew = Join-Path $candidateRoot 'gradlew.bat'
        $allArgs = @(
            '--project-dir', $candidateRoot,
            '--no-daemon',
            '--no-build-cache',
            '--no-parallel',
            '--no-configuration-cache',
            '--dependency-verification', 'strict',
            '--console=plain'
        ) + $GradleArgs
        $previousLocation = Get-Location
        $previousPreference = $ErrorActionPreference
        # Native stderr merged with 2>&1 surfaces as ErrorRecord objects that would terminate under 'Stop'.
        $ErrorActionPreference = 'Continue'
        try {
            Set-Location -LiteralPath $candidateRoot
            & $gradlew @allArgs 2>&1 | ForEach-Object {
                $line = if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.ToString() } else { $_ }
                Add-Content -LiteralPath $qualificationLog -Value $line
                Write-Host $line
            }
            return $LASTEXITCODE
        }
        finally {
            $ErrorActionPreference = $previousPreference
            Set-Location -LiteralPath $previousLocation
        }
    }

    function Append-Log([string]$Text) {
        Add-Content -LiteralPath $qualificationLog -Value $Text
        Write-Host $Text
    }

    Append-Log 'CQENGINE_PHASE=release-preflight'
    $preflightGradleExitCode = 1
    $preflightTeeExitCode = 0
    try {
        $preflightGradleExitCode = Invoke-ReleaseGradle $preflightGradleUserHome @(
            ':benchmarks:jmhLaneSelectionPreflight',
            ':stress-tests:compileJava'
        )
    }
    catch {
        $preflightTeeExitCode = 1
        Append-Log $_.Exception.Message
    }

    $releaseGradleExitCode = -1
    $releaseTeeExitCode = -1
    $gradleExitCode = $preflightGradleExitCode
    $teeExitCode = $preflightTeeExitCode
    if ($preflightGradleExitCode -eq 0 -and $preflightTeeExitCode -eq 0) {
        if ($candidateRoot -like (Join-Path $temporaryRoot 'cqengine-release-source.*')) {
            Remove-Item -LiteralPath $candidateRoot -Recurse -Force
        }
        else {
            throw "refusing to replace unexpected preflight candidate source: $candidateRoot"
        }
        $candidateRoot = Join-Path $temporaryRoot ("cqengine-release-source." + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $candidateRoot | Out-Null
        Assert-EmptyDirectory $candidateRoot 'release candidate source'
        Materialize-Candidate $candidateRoot
        $releaseGradleUserHome = Join-Path $temporaryRoot ("cqengine-release-gradle-home." + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $releaseGradleUserHome | Out-Null
        Assert-EmptyDirectory $releaseGradleUserHome 'release Gradle home'
        [System.IO.File]::WriteAllText(
            (Join-Path $releaseGradleUserHome '.cqengine-clean-release-home'),
            ("project={0}`nstate=created-empty`n" -f $candidateRoot)
        )
        Append-Log 'CQENGINE_PHASE=release-check'
        try {
            $releaseGradleExitCode = Invoke-ReleaseGradle $releaseGradleUserHome @('clean', 'releaseCheck')
            $releaseTeeExitCode = 0
        }
        catch {
            $releaseTeeExitCode = 1
            Append-Log $_.Exception.Message
        }
        $gradleExitCode = $releaseGradleExitCode
        $teeExitCode = $releaseTeeExitCode
    }

    $candidateQualificationDirectory = Join-Path $candidateRoot 'build\local-release-evidence\qualification'
    $candidateReadinessManifest = Join-Path $candidateQualificationDirectory 'local-readiness-manifest.txt'
    $wrapperExitCode = $gradleExitCode
    $validationStatus = 'not-run'
    if ($teeExitCode -ne 0) {
        $wrapperExitCode = 1
        $validationStatus = 'failed'
        Append-Log ("wrapper validation failed: qualification-log tee exited {0}" -f $teeExitCode)
    }
    elseif (-not (Test-Path -LiteralPath $qualificationLog) -or (Get-Item -LiteralPath $qualificationLog).Length -eq 0) {
        $wrapperExitCode = 1
        $validationStatus = 'failed'
        Append-Log 'wrapper validation failed: qualification log is empty'
    }
    elseif ($gradleExitCode -eq 0) {
        $logLines = Get-Content -LiteralPath $qualificationLog
        $inRelease = $false
        $foundSuccess = $false
        foreach ($line in $logLines) {
            if ($line -eq 'CQENGINE_PHASE=release-check') {
                $inRelease = $true
                continue
            }
            if ($inRelease -and $line -match '^BUILD SUCCESSFUL') {
                $foundSuccess = $true
            }
        }
        if (-not $foundSuccess) {
            $wrapperExitCode = 1
            $validationStatus = 'failed'
            Append-Log 'wrapper validation failed: qualification log has no Gradle success marker'
        }
        else {
            $validationStatus = 'failed'
            $validationError = $null
            if (-not (Test-Path -LiteralPath $candidateReadinessManifest) -or
                ((Get-Item -LiteralPath $candidateReadinessManifest).Attributes -band [System.IO.FileAttributes]::ReparsePoint) -or
                (Get-Item -LiteralPath $candidateReadinessManifest).Length -eq 0) {
                $validationError = 'releaseCheck produced no regular, non-empty readiness manifest'
            }
            elseif ((Get-Item -LiteralPath $candidateReadinessManifest).LastWriteTimeUtc -lt (Get-Item -LiteralPath $qualificationStart).LastWriteTimeUtc) {
                $validationError = 'releaseCheck did not produce a fresh readiness manifest'
            }
            else {
                try {
                    $manifestCommit = Get-ManifestValue $candidateReadinessManifest 'sourceCommit'
                    $manifestTree = Get-ManifestValue $candidateReadinessManifest 'sourceTree'
                    $manifestCommand = Get-ManifestValue $candidateReadinessManifest 'command'
                    $manifestPhaseIsolation = Get-ManifestValue $candidateReadinessManifest 'phaseIsolation'
                    if ($manifestCommit -ne $sourceCommit) {
                        $validationError = 'readiness manifest sourceCommit does not match the qualified commit'
                    }
                    elseif ($manifestTree -ne $sourceTree) {
                        $validationError = 'readiness manifest sourceTree does not match the qualified tree'
                    }
                    elseif ($manifestCommand -ne 'scripts/qualify-candidate.ps1') {
                        $validationError = 'readiness manifest records the wrong qualification command'
                    }
                    elseif ($manifestPhaseIsolation -ne 'separate-fresh-source-and-gradle-homes') {
                        $validationError = 'readiness manifest records the wrong qualification phase isolation'
                    }
                    elseif ((& $trustedGit -C $candidateRoot rev-parse --verify HEAD).Trim() -ne $sourceCommit) {
                        $validationError = 'candidate Git HEAD changed during release qualification'
                    }
                    elseif ((& $trustedGit -C $candidateRoot rev-parse --verify 'HEAD^{tree}').Trim() -ne $sourceTree) {
                        $validationError = 'candidate Git tree changed during release qualification'
                    }
                    elseif (-not [string]::IsNullOrWhiteSpace((& $trustedGit -C $candidateRoot status --porcelain=v1 --untracked-files=all))) {
                        $validationError = 'candidate Git worktree changed during release qualification'
                    }
                    elseif ((& $trustedGit -C $projectRoot rev-parse --verify HEAD).Trim() -ne $sourceCommit -or
                        (& $trustedGit -C $projectRoot rev-parse --verify 'HEAD^{tree}').Trim() -ne $sourceTree) {
                        $validationError = 'source Git commit changed during release qualification'
                    }
                    else {
                        $validationStatus = 'passed'
                    }
                }
                catch {
                    $validationError = $_.Exception.Message
                }
            }
            if ($validationStatus -ne 'passed') {
                $wrapperExitCode = 1
                $message = "wrapper validation failed: $validationError"
                Append-Log $message
                Write-QualifyError $message
            }
        }
    }

    function Copy-CandidateOutput([string]$Relative) {
        $source = Join-Path $candidateRoot $Relative
        $destination = Join-Path $projectRoot $Relative
        if ((Test-Path -LiteralPath $destination) -and
            ((Get-Item -LiteralPath $destination -Force).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
            throw "refusing to replace symbolic-link output path: $destination"
        }
        if (Test-Path -LiteralPath $destination) {
            Remove-Item -LiteralPath $destination -Recurse -Force
        }
        if (-not (Test-Path -LiteralPath $source)) {
            return
        }
        $parent = Split-Path -Parent $destination
        if (-not (Test-Path -LiteralPath $parent)) {
            New-Item -ItemType Directory -Path $parent -Force | Out-Null
        }
        Copy-Item -LiteralPath $source -Destination $destination -Recurse -Force
    }

    foreach ($retainedOutput in @(
            'build\generated-release-evidence',
            'build\libs',
            'build\local-release-evidence',
            'build\local-repository',
            'build\publications',
            'build\reports',
            'build\test-results',
            'benchmarks\build\reports'
        )) {
        Copy-CandidateOutput $retainedOutput
    }

    $qualificationDirectory = Join-Path $projectRoot 'build\local-release-evidence\qualification'
    New-Item -ItemType Directory -Path $qualificationDirectory -Force | Out-Null
    $retainedLog = Join-Path $qualificationDirectory 'qualification.log'
    $completionRecord = Join-Path $qualificationDirectory 'wrapper-completion.properties'
    $readinessManifest = Join-Path $qualificationDirectory 'local-readiness-manifest.txt'
    Copy-Item -LiteralPath $qualificationLog -Destination $retainedLog -Force

    $readinessSha256 = 'absent'
    $readinessSha512 = 'absent'
    if ((Test-Path -LiteralPath $readinessManifest) -and
        -not ((Get-Item -LiteralPath $readinessManifest).Attributes -band [System.IO.FileAttributes]::ReparsePoint)) {
        $readinessSha256 = Get-Sha256Hex $readinessManifest
        $readinessSha512 = Get-Sha512Hex $readinessManifest
    }

    $qualificationStatus = if ($wrapperExitCode -eq 0) { 'passed' } else { 'failed' }
    $completedAt = [DateTime]::UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'")
    @(
        'formatVersion=1'
        "status=$qualificationStatus"
        "preflightGradleExitCode=$preflightGradleExitCode"
        "preflightTeeExitCode=$preflightTeeExitCode"
        "releaseGradleExitCode=$releaseGradleExitCode"
        "releaseTeeExitCode=$releaseTeeExitCode"
        "gradleExitCode=$gradleExitCode"
        "teeExitCode=$teeExitCode"
        "wrapperExitCode=$wrapperExitCode"
        "validationStatus=$validationStatus"
        'phaseIsolation=separate-fresh-source-and-gradle-homes'
        "sourceCommit=$sourceCommit"
        "sourceTree=$sourceTree"
        "repositoryMode=$repositoryMode"
        'sourceMaterialization=detached-clone-git-archive'
        "jmhMachineLabel=$machineLabel"
        "trustedPath=$trustedPath"
        "trustedGit=$trustedGit sha256:$trustedGitSha256"
        "trustedTar=$trustedTar sha256:$trustedTarSha256"
        "trustedShell=$trustedSh sha256:$trustedShSha256"
        "trustedBash=$trustedBash sha256:$trustedBashSha256"
        "trustedNproc=$trustedNproc sha256:$trustedNprocSha256"
        "trustedJava=$bootstrapJava sha256:$bootstrapJavaSha256"
        'gitConfigGlobal=disabled'
        'gitConfigSystem=disabled'
        'gitObjectReplacement=disabled'
        'gitSystemAttributes=disabled'
        "completedAt=$completedAt"
        'command=scripts/qualify-candidate.ps1'
        ("qualificationLogSha256={0}" -f (Get-Sha256Hex $retainedLog))
        ("qualificationLogSha512={0}" -f (Get-Sha512Hex $retainedLog))
        "readinessManifestSha256=$readinessSha256"
        "readinessManifestSha512=$readinessSha512"
    ) | Set-Content -LiteralPath $completionRecord

    exit $wrapperExitCode
}
finally {
    Remove-QualificationTemp
}
