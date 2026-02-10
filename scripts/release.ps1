param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$Repo = "nicecube/HyPerks",
    [string]$JarPath = "build/libs/HyPerks.jar",
    [string]$HytaleServerJar = "",
    [string]$ReleaseNotesPath = "",
    [switch]$StrictModelVfx,
    [switch]$SkipBuild,
    [switch]$SkipGitPush,
    [switch]$SkipGithubRelease
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-GitPath {
    if (Get-Command git -ErrorAction SilentlyContinue) {
        return "git"
    }

    $gitFallback = "C:\\Program Files\\Git\\cmd\\git.exe"
    if (Test-Path $gitFallback) {
        return $gitFallback
    }

    throw "git executable not found. Install Git or add it to PATH."
}

function Resolve-PowerShellPath {
    if (Get-Command powershell -ErrorAction SilentlyContinue) {
        return "powershell"
    }

    if (Get-Command pwsh -ErrorAction SilentlyContinue) {
        return "pwsh"
    }

    throw "No PowerShell executable found (powershell or pwsh)."
}

function Resolve-HytaleServerJar {
    param(
        [string]$CandidatePath
    )

    if (-not [string]::IsNullOrWhiteSpace($CandidatePath)) {
        if (-not (Test-Path $CandidatePath)) {
            throw "Provided Hytale server jar not found at '$CandidatePath'."
        }
        return $CandidatePath
    }

    $candidates = @(
        "..\\SharedRuntime\\HytaleServer.jar",
        ".\\HystaleJar\\HytaleServer.jar"
    )
    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    return ""
}

function Run-Command {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Command failed: $Executable $($Arguments -join ' ')"
    }
}

$psExe = Resolve-PowerShellPath
$resolvedHytaleServerJar = Resolve-HytaleServerJar -CandidatePath $HytaleServerJar
$git = $null
if (-not $SkipGitPush) {
    $git = Resolve-GitPath
}
$tag = "v$Version"
$releaseTitle = "HyPerks $tag"

if (-not $SkipBuild) {
    $buildArgs = @("clean", "build")
    if (-not [string]::IsNullOrWhiteSpace($resolvedHytaleServerJar)) {
        $buildArgs += "-Phytale.server.jar=$resolvedHytaleServerJar"
    }
    Run-Command -Executable ".\\gradlew.bat" -Arguments $buildArgs
}

if (-not (Test-Path $JarPath)) {
    throw "Jar not found at '$JarPath'. Run a build first."
}

if ($StrictModelVfx) {
    $auditScript = ".\\scripts\\audit_model_vfx_assets.ps1"
    if (-not (Test-Path $auditScript)) {
        throw "Strict model VFX mode enabled, but audit script not found at '$auditScript'."
    }

    Run-Command -Executable $psExe -Arguments @(
        "-ExecutionPolicy", "Bypass",
        "-File", $auditScript,
        "-Root", ".",
        "-FailOnWarnings"
    )
}

$releaseJar = "build/libs/HyPerks-$Version.jar"
Copy-Item -Force $JarPath $releaseJar

if (-not $SkipGitPush) {
    Run-Command -Executable $git -Arguments @("add", "-A")

    & $git commit -m "release: $tag"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "No new commit created (working tree may already be clean)."
    }

    Run-Command -Executable $git -Arguments @("tag", "-f", $tag)
    Run-Command -Executable $git -Arguments @("push", "origin", "main")
    Run-Command -Executable $git -Arguments @("push", "origin", $tag, "--force")
}

if (-not $SkipGithubRelease) {
    if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
        throw "GitHub CLI (gh) is required for release publishing. Install gh or use -SkipGithubRelease."
    }

    $notesFile = [System.IO.Path]::GetTempFileName()
    if ($ReleaseNotesPath -and (Test-Path $ReleaseNotesPath)) {
        Copy-Item -Force $ReleaseNotesPath $notesFile
    } else {
        @"
HyPerks automated release $tag

- Built from local repository
- Includes bundled assets and SQL persistence dependencies
"@ | Set-Content -Encoding UTF8 $notesFile
    }

    Run-Command -Executable "gh" -Arguments @(
        "release", "create", $tag, $releaseJar,
        "--repo", $Repo,
        "--title", $releaseTitle,
        "--notes-file", $notesFile,
        "--latest"
    )
}

Write-Host "Release pipeline completed for $tag"
