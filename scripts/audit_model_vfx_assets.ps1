param(
    [string]$Root = ".",
    [switch]$CheckPremiumTemplates,
    [string]$TemplatesRelativePath = "docs/model-vfx-templates",
    [switch]$FailOnWarnings
)

$ErrorActionPreference = "Stop"

function Resolve-AbsolutePath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $PathValue))
}

$projectRoot = Resolve-AbsolutePath -PathValue $Root
$assetsRoot = Join-Path $projectRoot "assets"
$modelsDir = Join-Path $assetsRoot "Server/Models/HyPerksVFX"

if (-not (Test-Path -Path $modelsDir -PathType Container)) {
    Write-Error "Models directory not found: $modelsDir"
    exit 2
}

$requiredJson = @(
    "FireIceCone_Rig.json",
    "FireIceCone_Rig_Core.json",
    "FireIceCone_Rig_HelixFire.json",
    "FireIceCone_Rig_HelixIce.json",
    "StormClouds_Rig.json",
    "StormClouds_Rig_Core.json",
    "StormClouds_Rig_Bolt.json",
    "StormClouds_Rig_Ring.json",
    "WingWangSigil_Rig.json",
    "WingWangSigil_Rig_Inner.json",
    "WingWangSigil_Rig_Mid.json",
    "WingWangSigil_Rig_Outer.json",
    "FireworksShow_Rig.json",
    "FireworksShow_Rig_Launcher.json",
    "FireworksShow_Rig_Burst.json"
)

$missing = @()
$warnings = @()
$checked = 0

function Resolve-AssetRefPath {
    param(
        [string]$AssetRef
    )

    if ([string]::IsNullOrWhiteSpace($AssetRef)) {
        return $null
    }

    $normalized = $AssetRef.Trim().Replace('\', '/')
    if ($normalized.StartsWith("Server/") -or $normalized.StartsWith("Common/")) {
        return Join-Path $assetsRoot ($normalized.Replace('/', '\'))
    }

    $commonRoots = @("Characters/", "NPC/", "Items/", "VFX/", "Equipment/")
    foreach ($root in $commonRoots) {
        if ($normalized.StartsWith($root)) {
            return Join-Path (Join-Path $assetsRoot "Common") ($normalized.Replace('/', '\'))
        }
    }

    return $null
}

function Collect-AnimationRefs {
    param(
        [object]$JsonObject
    )

    $refs = @()
    if ($null -eq $JsonObject) {
        return $refs
    }
    if (-not $JsonObject.PSObject.Properties.Name.Contains("AnimationSets")) {
        return $refs
    }

    $animationSets = $JsonObject.AnimationSets
    if ($null -eq $animationSets) {
        return $refs
    }

    foreach ($setProperty in $animationSets.PSObject.Properties) {
        $setValue = $setProperty.Value
        if ($null -eq $setValue) {
            continue
        }

        $animations = $setValue.Animations
        if ($null -eq $animations) {
            continue
        }

        foreach ($entry in $animations) {
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

foreach ($file in $requiredJson) {
    $path = Join-Path $modelsDir $file
    if (-not (Test-Path -Path $path -PathType Leaf)) {
        $missing += $file
        continue
    }

    $checked++
    try {
        $json = Get-Content -Raw -Path $path | ConvertFrom-Json
    } catch {
        $warnings += "Invalid JSON: $file"
        continue
    }

    if (-not $json.PSObject.Properties.Name.Contains("Model") -or [string]::IsNullOrWhiteSpace([string]$json.Model)) {
        $warnings += "Missing Model field: $file"
    }
    if (-not $json.PSObject.Properties.Name.Contains("Texture") -or [string]::IsNullOrWhiteSpace([string]$json.Texture)) {
        $warnings += "Missing Texture field: $file"
    }

    $modelRef = [string]$json.Model
    $textureRef = [string]$json.Texture

    if ($modelRef -like "Items/Projectiles/*" -or $textureRef -like "Items/Projectiles/*") {
        $warnings += "Placeholder vanilla reference detected: $file -> Model='$modelRef' Texture='$textureRef'"
    }

    $modelPath = Resolve-AssetRefPath -AssetRef $modelRef
    if ($null -ne $modelPath -and -not (Test-Path -Path $modelPath -PathType Leaf)) {
        $warnings += "Referenced model file missing: $file -> $modelRef"
    }

    $texturePath = Resolve-AssetRefPath -AssetRef $textureRef
    if ($null -ne $texturePath -and -not (Test-Path -Path $texturePath -PathType Leaf)) {
        $warnings += "Referenced texture file missing: $file -> $textureRef"
    }

    $animationRefs = Collect-AnimationRefs -JsonObject $json
    foreach ($animationRef in $animationRefs) {
        $animationPath = Resolve-AssetRefPath -AssetRef $animationRef
        if ($null -ne $animationPath -and -not (Test-Path -Path $animationPath -PathType Leaf)) {
            $warnings += "Referenced animation file missing: $file -> $animationRef"
        }
    }
}

if ($CheckPremiumTemplates) {
    $templatesDir = Join-Path $projectRoot $TemplatesRelativePath
    if (-not (Test-Path -Path $templatesDir -PathType Container)) {
        $warnings += "Template directory not found: $templatesDir"
    } else {
        $templateFiles = Get-ChildItem -Path $templatesDir -Filter "*.template.json" -File
        if ($templateFiles.Count -le 0) {
            $warnings += "No premium template JSON files found in: $templatesDir"
        } else {
            foreach ($template in $templateFiles) {
                try {
                    $templateJson = Get-Content -Raw -Path $template.FullName | ConvertFrom-Json
                } catch {
                    $warnings += "Invalid template JSON: $($template.Name)"
                    continue
                }

                $templateRefs = @()
                if ($templateJson.PSObject.Properties.Name.Contains("Model")) {
                    $templateRefs += [string]$templateJson.Model
                }
                if ($templateJson.PSObject.Properties.Name.Contains("Texture")) {
                    $templateRefs += [string]$templateJson.Texture
                }
                $templateRefs += Collect-AnimationRefs -JsonObject $templateJson

                foreach ($assetRef in $templateRefs) {
                    if ([string]::IsNullOrWhiteSpace($assetRef)) {
                        continue
                    }
                    $assetPath = Resolve-AssetRefPath -AssetRef $assetRef
                    if ($null -ne $assetPath -and -not (Test-Path -Path $assetPath -PathType Leaf)) {
                        $warnings += "Premium template reference missing: $($template.Name) -> $assetRef"
                    }
                }
            }
        }
    }
}

Write-Host "Model VFX audit completed."
Write-Host "Directory: $modelsDir"
Write-Host "Checked JSON files: $checked / $($requiredJson.Count)"

if ($missing.Count -gt 0) {
    Write-Host ""
    Write-Host "Missing JSON files:" -ForegroundColor Yellow
    foreach ($item in $missing) {
        Write-Host " - $item"
    }
}

if ($warnings.Count -gt 0) {
    Write-Host ""
    Write-Host "Warnings:" -ForegroundColor Yellow
    foreach ($item in $warnings) {
        Write-Host " - $item"
    }
}

if ($missing.Count -gt 0) {
    exit 1
}

if ($FailOnWarnings -and $warnings.Count -gt 0) {
    exit 1
}

exit 0
