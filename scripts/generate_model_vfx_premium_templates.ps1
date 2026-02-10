param(
    [string]$Root = ".",
    [string]$OutputRelativePath = "docs/model-vfx-templates"
)

$ErrorActionPreference = "Stop"

function Resolve-AbsolutePath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $PathValue))
}

function Read-JsonFile {
    param([string]$PathValue)
    return Get-Content -Raw -Path $PathValue | ConvertFrom-Json
}

$projectRoot = Resolve-AbsolutePath -PathValue $Root
$sourceDir = Join-Path $projectRoot "assets/Server/Models/HyPerksVFX"
$outputDir = Join-Path $projectRoot $OutputRelativePath

if (-not (Test-Path -Path $sourceDir -PathType Container)) {
    throw "Source directory not found: $sourceDir"
}

if (-not (Test-Path -Path $outputDir -PathType Container)) {
    New-Item -ItemType Directory -Path $outputDir | Out-Null
}

$profileByFile = @{
    "FireIceCone_Rig.json"            = "FireIceCone"
    "FireIceCone_Rig_Core.json"       = "FireIceCone"
    "FireIceCone_Rig_HelixFire.json"  = "FireIceCone"
    "FireIceCone_Rig_HelixIce.json"   = "FireIceCone"
    "StormClouds_Rig.json"            = "StormClouds"
    "StormClouds_Rig_Core.json"       = "StormClouds"
    "StormClouds_Rig_Bolt.json"       = "StormClouds"
    "StormClouds_Rig_Ring.json"       = "StormClouds"
    "WingWangSigil_Rig.json"          = "WingWangSigil"
    "WingWangSigil_Rig_Inner.json"    = "WingWangSigil"
    "WingWangSigil_Rig_Mid.json"      = "WingWangSigil"
    "WingWangSigil_Rig_Outer.json"    = "WingWangSigil"
    "FireworksShow_Rig.json"          = "FireworksShow"
    "FireworksShow_Rig_Launcher.json" = "FireworksShow"
    "FireworksShow_Rig_Burst.json"    = "FireworksShow"
}

$manifest = @()

foreach ($entry in $profileByFile.GetEnumerator() | Sort-Object Name) {
    $fileName = $entry.Key
    $profile = $entry.Value
    $sourcePath = Join-Path $sourceDir $fileName
    if (-not (Test-Path -Path $sourcePath -PathType Leaf)) {
        continue
    }

    $json = Read-JsonFile -PathValue $sourcePath
    $baseName = [System.IO.Path]::GetFileNameWithoutExtension($fileName)
    $modelName = "$baseName.blockymodel"
    $textureName = "$profile`_Atlas.png"
    $idleAnimName = "$baseName`_Idle.blockyanim"

    $template = [ordered]@{
        Model      = "Server/Models/HyPerksVFX/Premium/$profile/$modelName"
        Texture    = "Server/Models/HyPerksVFX/Premium/$profile/$textureName"
        EyeHeight  = $json.EyeHeight
        HitBox     = $json.HitBox
        MinScale   = $json.MinScale
        MaxScale   = $json.MaxScale
    }

    if ($null -ne $json.AnimationSets) {
        $template.AnimationSets = [ordered]@{
            Idle = [ordered]@{
                Animations = @(
                    [ordered]@{
                        Animation        = "Server/Models/HyPerksVFX/Premium/$profile/$idleAnimName"
                        BlendingDuration = 0.08
                        Speed            = 1.00
                    }
                )
            }
        }
    }

    $outputPath = Join-Path $outputDir "$fileName.template.json"
    ($template | ConvertTo-Json -Depth 10) | Set-Content -Encoding UTF8 -Path $outputPath

    $manifest += [pscustomobject]@{
        FileTemplate = [System.IO.Path]::GetFileName($outputPath)
        ModelRef     = $template.Model
        TextureRef   = $template.Texture
        AnimationRef = if ($null -ne $template.AnimationSets) { $template.AnimationSets.Idle.Animations[0].Animation } else { "" }
    }
}

$manifestPath = Join-Path $outputDir "_premium_manifest.csv"
$manifest | Export-Csv -Path $manifestPath -NoTypeInformation -Encoding UTF8

Write-Host "Generated premium templates in: $outputDir"
Write-Host "Manifest: $manifestPath"
