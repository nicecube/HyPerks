param(
    [string]$Root = ".",
    [string]$OutputRelativePath = "docs/model-vfx-templates"
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

function Read-JsonFile {
    param([string]$PathValue)
    return Get-Content -Raw -Path $PathValue | ConvertFrom-Json
}

function Write-Utf8NoBom {
    param(
        [Parameter(Mandatory = $true)][string]$PathValue,
        [Parameter(Mandatory = $true)][string]$Content
    )
    [System.IO.File]::WriteAllText($PathValue, $Content, $script:Utf8NoBom)
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
        Model      = "VFX/HyPerks/$profile/$modelName"
        Texture    = "VFX/HyPerks/$profile/$textureName"
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
                        Animation        = "VFX/HyPerks/$profile/$idleAnimName"
                        BlendingDuration = 0.08
                        Speed            = 1.00
                    }
                )
            }
        }
    }

    $outputPath = Join-Path $outputDir "$fileName.template.json"
    Write-Utf8NoBom -PathValue $outputPath -Content ($template | ConvertTo-Json -Depth 10)

    $manifest += [pscustomobject]@{
        FileTemplate = [System.IO.Path]::GetFileName($outputPath)
        ModelRef     = $template.Model
        TextureRef   = $template.Texture
        AnimationRef = if ($null -ne $template.AnimationSets) { $template.AnimationSets.Idle.Animations[0].Animation } else { "" }
    }
}

$manifestPath = Join-Path $outputDir "_premium_manifest.csv"
$manifestCsv = $manifest | ConvertTo-Csv -NoTypeInformation
Write-Utf8NoBom -PathValue $manifestPath -Content ($manifestCsv -join [Environment]::NewLine)

Write-Host "Generated premium templates in: $outputDir"
Write-Host "Manifest: $manifestPath"
