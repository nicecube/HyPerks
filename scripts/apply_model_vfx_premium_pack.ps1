param(
    [string]$Root = ".",
    [string]$AssetsZip = "..\\SharedRuntime\\Assets.zip",
    [string]$RuntimeJsonRelativePath = "assets/Server/Models/HyPerksVFX",
    [string]$PremiumRelativePath = "assets/Server/Models/HyPerksVFX/Premium",
    [string]$TemplatesRelativePath = "docs/model-vfx-templates",
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

function Ensure-Directory {
    param([string]$PathValue)
    if (-not (Test-Path -Path $PathValue -PathType Container)) {
        New-Item -ItemType Directory -Path $PathValue -Force | Out-Null
    }
}

function Set-JsonFile {
    param(
        [string]$PathValue,
        [object]$JsonObject
    )
    [System.IO.File]::WriteAllText($PathValue, ($JsonObject | ConvertTo-Json -Depth 12), $script:Utf8NoBom)
}

function Ensure-AnimationSet {
    param(
        [object]$JsonObject,
        [string]$AnimationRef
    )

    if ([string]::IsNullOrWhiteSpace($AnimationRef)) {
        if ($JsonObject.PSObject.Properties.Name.Contains("AnimationSets")) {
            $JsonObject.PSObject.Properties.Remove("AnimationSets")
        }
        return
    }

    $JsonObject | Add-Member -NotePropertyName AnimationSets -NotePropertyValue (
        [ordered]@{
            Idle = [ordered]@{
                Animations = @(
                    [ordered]@{
                        Animation = $AnimationRef
                        BlendingDuration = 0.08
                        Speed = 1.0
                    }
                )
            }
        }
    ) -Force
}

function Copy-ZipEntryToFile {
    param(
        [System.IO.Compression.ZipArchive]$ZipArchive,
        [string]$EntryPath,
        [string]$DestinationPath,
        [bool]$DryRunMode
    )

    $normalizedEntry = $EntryPath.Replace('\', '/')
    $entry = $ZipArchive.Entries | Where-Object { $_.FullName -eq $normalizedEntry } | Select-Object -First 1
    if ($null -eq $entry) {
        throw "Entry not found in Assets.zip: $normalizedEntry"
    }

    if ($DryRunMode) {
        return
    }

    $destinationDir = Split-Path -Parent $DestinationPath
    Ensure-Directory -PathValue $destinationDir

    $entryStream = $entry.Open()
    try {
        $fileStream = [System.IO.File]::Open($DestinationPath, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write)
        try {
            $entryStream.CopyTo($fileStream)
        } finally {
            $fileStream.Dispose()
        }
    } finally {
        $entryStream.Dispose()
    }
}

Add-Type -AssemblyName System.IO.Compression.FileSystem

$projectRoot = Resolve-AbsolutePath -PathValue $Root
$assetsZipPath = Resolve-AbsolutePath -PathValue (Join-Path $projectRoot $AssetsZip)
$runtimeJsonDir = Join-Path $projectRoot $RuntimeJsonRelativePath
$premiumRootDir = Join-Path $projectRoot $PremiumRelativePath
$templatesDir = Join-Path $projectRoot $TemplatesRelativePath

if (-not (Test-Path -Path $assetsZipPath -PathType Leaf)) {
    throw "Assets.zip not found: $assetsZipPath"
}
if (-not (Test-Path -Path $runtimeJsonDir -PathType Container)) {
    throw "Runtime JSON directory not found: $runtimeJsonDir"
}
Ensure-Directory -PathValue $premiumRootDir
Ensure-Directory -PathValue $templatesDir

$mappings = @(
    @{ Target = "FireIceCone_Rig.json";           Profile = "FireIceCone";   Model = "Common/Items/Weapons/Staff/Crystal_Flame.blockymodel";      Texture = "Common/Items/Weapons/Staff/Crystal_Flame_Texture.png";      Animation = "Common/Items/Weapons/Staff/Crystal_Flame/SpinningFire4.blockyanim" },
    @{ Target = "FireIceCone_Rig_Core.json";      Profile = "FireIceCone";   Model = "Common/Items/Weapons/Staff/Crystal_Flame.blockymodel";      Texture = "Common/Items/Weapons/Staff/Crystal_Flame_Texture.png";      Animation = "Common/Items/Weapons/Staff/Crystal_Flame/SpinningFire4.blockyanim" },
    @{ Target = "FireIceCone_Rig_HelixFire.json"; Profile = "FireIceCone";   Model = "Common/Items/Weapons/Staff/Crystal_Red.blockymodel";        Texture = "Common/Items/Weapons/Staff/Crystal_Red_Texture.png";        Animation = "" },
    @{ Target = "FireIceCone_Rig_HelixIce.json";  Profile = "FireIceCone";   Model = "Common/Items/Weapons/Staff/Crystal_Ice.blockymodel";        Texture = "Common/Items/Weapons/Staff/Crystal_Ice_Texture.png";        Animation = "" },

    @{ Target = "StormClouds_Rig.json";           Profile = "StormClouds";   Model = "Common/Items/Back/Cape_Long.blockymodel";                   Texture = "Common/Items/Back/Cape_Long_Texture.png";                   Animation = "" },
    @{ Target = "StormClouds_Rig_Core.json";      Profile = "StormClouds";   Model = "Common/Items/Back/Cape_Long.blockymodel";                   Texture = "Common/Items/Back/Cape_Long_Texture.png";                   Animation = "" },
    @{ Target = "StormClouds_Rig_Bolt.json";      Profile = "StormClouds";   Model = "Common/Items/Weapons/Staff/Crystal_Purple.blockymodel";     Texture = "Common/Items/Weapons/Staff/Crystal_Purple_Texture.png";     Animation = "" },
    @{ Target = "StormClouds_Rig_Ring.json";      Profile = "StormClouds";   Model = "Common/Blocks/Miscellaneous/Platform_Magic.blockymodel";    Texture = "Common/Blocks/Miscellaneous/Platform_Magic_Blue2.png";      Animation = "Common/Blocks/Miscellaneous/Platform_Magic_Idle.blockyanim" },

    @{ Target = "WingWangSigil_Rig.json";         Profile = "WingWangSigil"; Model = "Common/Blocks/Miscellaneous/Platform_Magic.blockymodel";    Texture = "Common/Blocks/Miscellaneous/Platform_Magic_Blue2.png";      Animation = "Common/Blocks/Miscellaneous/Platform_Magic_Idle.blockyanim" },
    @{ Target = "WingWangSigil_Rig_Inner.json";   Profile = "WingWangSigil"; Model = "Common/Blocks/Miscellaneous/Platform_MagicInactive.blockymodel"; Texture = "Common/Blocks/Miscellaneous/Platform_Magic_Blue.png";     Animation = "" },
    @{ Target = "WingWangSigil_Rig_Mid.json";     Profile = "WingWangSigil"; Model = "Common/Blocks/Miscellaneous/Platform_Magic.blockymodel";    Texture = "Common/Blocks/Miscellaneous/Platform_Magic_Blue2.png";      Animation = "Common/Blocks/Miscellaneous/Platform_Magic_Idle.blockyanim" },
    @{ Target = "WingWangSigil_Rig_Outer.json";   Profile = "WingWangSigil"; Model = "Common/Blocks/Miscellaneous/Platform_Magic_Exit.blockymodel"; Texture = "Common/Blocks/Miscellaneous/Platform_Magic_Red.png";       Animation = "Common/Blocks/Miscellaneous/Platform_Magic_Idle.blockyanim" },

    @{ Target = "FireworksShow_Rig.json";         Profile = "FireworksShow"; Model = "Common/Items/Weapons/Staff/Wizard.blockymodel";             Texture = "Common/Items/Weapons/Staff/Wizard_Texture.png";             Animation = "" },
    @{ Target = "FireworksShow_Rig_Launcher.json";Profile = "FireworksShow"; Model = "Common/Items/Weapons/Staff/Wizard.blockymodel";             Texture = "Common/Items/Weapons/Staff/Wizard_Texture.png";             Animation = "" },
    @{ Target = "FireworksShow_Rig_Burst.json";   Profile = "FireworksShow"; Model = "Common/Items/Weapons/Throwing_Knife/Shuriken.blockymodel";  Texture = "Common/Items/Weapons/Throwing_Knife/Shuriken_Texture.png";  Animation = "" }
)

$zip = [System.IO.Compression.ZipFile]::OpenRead($assetsZipPath)
try {
    $copied = New-Object System.Collections.Generic.HashSet[string]
    $updated = 0

    foreach ($mapping in $mappings) {
        $profileDir = Join-Path $premiumRootDir $mapping.Profile
        Ensure-Directory -PathValue $profileDir

        $modelLeaf = [System.IO.Path]::GetFileName($mapping.Model)
        $textureLeaf = [System.IO.Path]::GetFileName($mapping.Texture)
        $animationLeaf = if ([string]::IsNullOrWhiteSpace($mapping.Animation)) { "" } else { [System.IO.Path]::GetFileName($mapping.Animation) }

        $destModel = Join-Path $profileDir $modelLeaf
        $destTexture = Join-Path $profileDir $textureLeaf
        $destAnimation = if ([string]::IsNullOrWhiteSpace($animationLeaf)) { "" } else { Join-Path $profileDir $animationLeaf }

        if ($copied.Add($destModel)) {
            Copy-ZipEntryToFile -ZipArchive $zip -EntryPath $mapping.Model -DestinationPath $destModel -DryRunMode:$DryRun
        }
        if ($copied.Add($destTexture)) {
            Copy-ZipEntryToFile -ZipArchive $zip -EntryPath $mapping.Texture -DestinationPath $destTexture -DryRunMode:$DryRun
        }
        if (-not [string]::IsNullOrWhiteSpace($mapping.Animation) -and $copied.Add($destAnimation)) {
            Copy-ZipEntryToFile -ZipArchive $zip -EntryPath $mapping.Animation -DestinationPath $destAnimation -DryRunMode:$DryRun
        }

        $modelRef = "Server/Models/HyPerksVFX/Premium/$($mapping.Profile)/$modelLeaf"
        $textureRef = "Server/Models/HyPerksVFX/Premium/$($mapping.Profile)/$textureLeaf"
        $animationRef = if ([string]::IsNullOrWhiteSpace($mapping.Animation)) { "" } else { "Server/Models/HyPerksVFX/Premium/$($mapping.Profile)/$animationLeaf" }

        $runtimePath = Join-Path $runtimeJsonDir $mapping.Target
        if (-not (Test-Path -Path $runtimePath -PathType Leaf)) {
            throw "Runtime JSON not found: $runtimePath"
        }
        $runtimeJson = Get-Content -Raw -Path $runtimePath | ConvertFrom-Json
        $runtimeJson.Model = $modelRef
        $runtimeJson.Texture = $textureRef
        Ensure-AnimationSet -JsonObject $runtimeJson -AnimationRef $animationRef
        if (-not $DryRun) {
            Set-JsonFile -PathValue $runtimePath -JsonObject $runtimeJson
        }

        $templatePath = Join-Path $templatesDir ($mapping.Target + ".template.json")
        if (Test-Path -Path $templatePath -PathType Leaf) {
            $templateJson = Get-Content -Raw -Path $templatePath | ConvertFrom-Json
            $templateJson.Model = $modelRef
            $templateJson.Texture = $textureRef
            Ensure-AnimationSet -JsonObject $templateJson -AnimationRef $animationRef
            if (-not $DryRun) {
                Set-JsonFile -PathValue $templatePath -JsonObject $templateJson
            }
        }

        $updated++
    }

    Write-Host "Premium pack applied."
    Write-Host "Mappings processed: $updated"
    Write-Host "Files copied from Assets.zip: $($copied.Count)"
    if ($DryRun) {
        Write-Host "DryRun mode: no files were written."
    }
} finally {
    $zip.Dispose()
}
