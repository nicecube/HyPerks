param(
    [Parameter(Mandatory = $true)]
    [string]$Version,
    [string]$Repo = "nicecube/HyPerks",
    [string]$JarPath = "build/libs/HyPerks.jar",
    [string]$ReleaseNotesPath = "",
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

$git = Resolve-GitPath
$tag = "v$Version"
$releaseTitle = "HyPerks $tag"

if (-not $SkipBuild) {
    Run-Command -Executable ".\\gradlew.bat" -Arguments @("clean", "build")
}

if (-not (Test-Path $JarPath)) {
    throw "Jar not found at '$JarPath'. Run a build first."
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
