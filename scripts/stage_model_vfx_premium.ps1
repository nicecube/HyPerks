param(
    [string]$Root = ".",
    [string]$TemplatesRelativePath = "docs/model-vfx-templates",
    [string]$RuntimeJsonRelativePath = "assets/Server/Models/HyPerksVFX",
    [switch]$ApplyToRuntimeJson,
    [switch]$RequireAssetsPresent,
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)

function Resolve-AbsolutePath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $PathValue))
}

function Resolve-AssetRefPath {
    param(
        [string]$AssetsRoot,
        [string]$AssetRef
    )

    if ([string]::IsNullOrWhiteSpace($AssetRef)) {
        return $null
    }

    $normalized = $AssetRef.Trim().Replace('\', '/')
    if ($normalized.StartsWith("Server/") -or $normalized.StartsWith("Common/")) {
        return Join-Path $AssetsRoot ($normalized.Replace('/', '\'))
    }

    $commonRoots = @("Characters/", "NPC/", "Items/", "VFX/", "Equipment/")
    foreach ($root in $commonRoots) {
        if ($normalized.StartsWith($root)) {
            return Join-Path (Join-Path $AssetsRoot "Common") ($normalized.Replace('/', '\'))
        }
    }

    return $null
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$PathValue,
        [Parameter(Mandatory = $true)][string]$Content
    )

    [System.IO.File]::WriteAllText($PathValue, $Content, $script:Utf8NoBom)
}

function Collect-AnimationRefs {
    param([object]$JsonObject)

    $refs = @()
    if ($null -eq $JsonObject -or -not $JsonObject.PSObject.Properties.Name.Contains("AnimationSets")) {
        return $refs
    }

    $sets = $JsonObject.AnimationSets
    if ($null -eq $sets) {
        return $refs
    }

    foreach ($set in $sets.PSObject.Properties) {
        if ($null -eq $set.Value -or $null -eq $set.Value.Animations) {
            continue
        }
        foreach ($entry in $set.Value.Animations) {
            if ($null -eq $entry) {
                continue
            }
            $animationRef = [string]$entry.Animation
            if (-not [string]::IsNullOrWhiteSpace($animationRef)) {
                $refs += $animationRef.Trim()
            }
        }
    }

    return $refs
}

$projectRoot = Resolve-AbsolutePath -PathValue $Root
$assetsRoot = Join-Path $projectRoot "assets"
$templatesDir = Join-Path $projectRoot $TemplatesRelativePath
$runtimeJsonDir = Join-Path $projectRoot $RuntimeJsonRelativePath

if (-not (Test-Path -Path $templatesDir -PathType Container)) {
    throw "Templates directory not found: $templatesDir"
}
if (-not (Test-Path -Path $runtimeJsonDir -PathType Container)) {
    throw "Runtime JSON directory not found: $runtimeJsonDir"
}

$templateFiles = Get-ChildItem -Path $templatesDir -Filter "*.template.json" -File | Sort-Object Name
if ($templateFiles.Count -le 0) {
    throw "No template files found in $templatesDir"
}

$requiredByDirectory = @{}
$summary = @()
$updatedRuntime = 0
$skippedRuntime = 0

foreach ($templateFile in $templateFiles) {
    $templateJson = Get-Content -Raw -Path $templateFile.FullName | ConvertFrom-Json
    $runtimeName = $templateFile.Name -replace '\.template\.json$', ''
    $runtimePath = Join-Path $runtimeJsonDir $runtimeName

    $modelRef = [string]$templateJson.Model
    $textureRef = [string]$templateJson.Texture
    $animationRefs = Collect-AnimationRefs -JsonObject $templateJson
    $allRefs = @($modelRef, $textureRef) + $animationRefs

    foreach ($assetRef in $allRefs) {
        if ([string]::IsNullOrWhiteSpace($assetRef)) {
            continue
        }

        $absoluteAsset = Resolve-AssetRefPath -AssetsRoot $assetsRoot -AssetRef $assetRef
        if ($null -eq $absoluteAsset) {
            continue
        }

        $directory = Split-Path -Parent $absoluteAsset
        $leafName = Split-Path -Leaf $absoluteAsset
        if (-not $requiredByDirectory.ContainsKey($directory)) {
            $requiredByDirectory[$directory] = New-Object System.Collections.Generic.HashSet[string]
        }
        [void]$requiredByDirectory[$directory].Add($leafName)
    }

    if ($ApplyToRuntimeJson) {
        if (-not (Test-Path -Path $runtimePath -PathType Leaf)) {
            $skippedRuntime++
            $summary += [pscustomobject]@{
                RuntimeFile = $runtimeName
                Status      = "SKIPPED"
                Reason      = "Runtime JSON missing"
            }
            continue
        }

        $missingRefs = @()
        if ($RequireAssetsPresent) {
            foreach ($assetRef in $allRefs) {
                $absoluteAsset = Resolve-AssetRefPath -AssetsRoot $assetsRoot -AssetRef $assetRef
                if ($null -ne $absoluteAsset -and -not (Test-Path -Path $absoluteAsset -PathType Leaf)) {
                    $missingRefs += $assetRef
                }
            }
        }

        if ($missingRefs.Count -gt 0) {
            $skippedRuntime++
            $summary += [pscustomobject]@{
                RuntimeFile = $runtimeName
                Status      = "SKIPPED"
                Reason      = "Missing referenced premium assets: $($missingRefs -join '; ')"
            }
            continue
        }

        $runtimeJson = Get-Content -Raw -Path $runtimePath | ConvertFrom-Json
        $runtimeJson.Model = $templateJson.Model
        $runtimeJson.Texture = $templateJson.Texture
        if ($templateJson.PSObject.Properties.Name.Contains("AnimationSets")) {
            $runtimeJson.AnimationSets = $templateJson.AnimationSets
        }

        if (-not $DryRun) {
            Write-Utf8NoBom -PathValue $runtimePath -Content ($runtimeJson | ConvertTo-Json -Depth 12)
        }

        $updatedRuntime++
        $summary += [pscustomobject]@{
            RuntimeFile = $runtimeName
            Status      = if ($DryRun) { "DRYRUN" } else { "UPDATED" }
            Reason      = "Applied premium template refs"
        }
    }
}

foreach ($directory in $requiredByDirectory.Keys | Sort-Object) {
    if (-not $DryRun -and -not (Test-Path -Path $directory -PathType Container)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }

    $readmePath = Join-Path $directory "_EXPECTED_FILES.txt"
    $lines = @(
        "Generated by scripts/stage_model_vfx_premium.ps1",
        "Required files for premium VFX references in this folder:"
    ) + ($requiredByDirectory[$directory] | Sort-Object | ForEach-Object { "- $_" })

    if (-not $DryRun) {
        Write-Utf8NoBom -PathValue $readmePath -Content ($lines -join [Environment]::NewLine)
    }
}

$summaryPath = Join-Path $templatesDir "_stage_summary.csv"
if (-not $DryRun) {
    $summaryText = $summary | ConvertTo-Csv -NoTypeInformation
    Write-Utf8NoBom -PathValue $summaryPath -Content ($summaryText -join [Environment]::NewLine)
}

Write-Host "Premium staging completed."
Write-Host "Templates: $($templateFiles.Count)"
Write-Host "Directories prepared: $($requiredByDirectory.Keys.Count)"
Write-Host "Runtime updated: $updatedRuntime"
Write-Host "Runtime skipped: $skippedRuntime"
if (-not $DryRun) {
    Write-Host "Summary: $summaryPath"
}
