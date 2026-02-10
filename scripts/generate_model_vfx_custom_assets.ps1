
param([string]$Root='.',[switch]$CleanExisting)
Add-Type -AssemblyName System.Drawing
Set-StrictMode -Version Latest
$ErrorActionPreference='Stop'

function ResolveP([string]$p){ if([IO.Path]::IsPathRooted($p)){return $p}; [IO.Path]::GetFullPath((Join-Path (Get-Location).Path $p)) }
$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
function J([string]$p,$o){ [System.IO.File]::WriteAllText($p, ($o|ConvertTo-Json -Depth 30), $script:Utf8NoBom) }
function V([double]$x,[double]$y,[double]$z){ [ordered]@{x=$x;y=$y;z=$z} }
function Q([double]$x,[double]$y,[double]$z,[double]$w){ [ordered]@{x=$x;y=$y;z=$z;w=$w} }
function QY([double]$deg){ $r=$deg*[Math]::PI/180; $s=[Math]::Sin($r/2); $c=[Math]::Cos($r/2); Q 0 $s 0 $c }
function TL([int]$x,[int]$y){ [ordered]@{offset=[ordered]@{x=$x;y=$y};mirror=[ordered]@{x=$false;y=$false};angle=0} }
function SNone(){ [ordered]@{type='none';offset=V 0 0 0;stretch=V 0 0 0;settings=[ordered]@{isPiece=$false};textureLayout=[ordered]@{};unwrapMode='custom';visible=$true;doubleSided=$false;shadingMode='flat'} }
function SBox([int]$sx,[int]$sy,[int]$sz,[int]$tx,[int]$ty,[string]$shade='flat'){ $f=TL $tx $ty; [ordered]@{type='box';offset=V 0 0 0;stretch=V 1 1 1;settings=[ordered]@{isPiece=$false;size=[ordered]@{x=$sx;y=$sy;z=$sz}};textureLayout=[ordered]@{back=$f;right=$f;front=$f;left=$f;top=$f;bottom=$f};unwrapMode='custom';visible=$true;doubleSided=$false;shadingMode=$shade} }
function SQuad([int]$sx,[int]$sy,[int]$tx,[int]$ty){ [ordered]@{type='quad';offset=V 0 0 0;stretch=V 1 1 1;settings=[ordered]@{isPiece=$false;size=[ordered]@{x=$sx;y=$sy;z=0};normal='+Z'};textureLayout=[ordered]@{front=(TL $tx $ty)};unwrapMode='custom';visible=$true;doubleSided=$true;shadingMode='fullbright'} }
$script:id=0
function N([string]$name,$pos,$shape,$ori=(Q 0 0 0 1),$children=@()){ $script:id++; [ordered]@{id=[string]$script:id;name=$name;position=$pos;orientation=$ori;shape=$shape;children=@($children)} }
function M($children){ $script:id=0; [ordered]@{nodes=@((N 'Root' (V 0 0 0) (SNone) (Q 0 0 0 1) $children));lod='auto'} }
function Ring([string]$prefix,[int]$count,[double]$radius,[double]$y,[int]$tx,[int]$ty,[string]$kind='box'){
  $r=@(); for($i=0;$i -lt $count;$i++){ $d=360*$i/$count; $rad=$d*[Math]::PI/180; $x=[Math]::Cos($rad)*$radius; $z=[Math]::Sin($rad)*$radius; $shape=if($kind -eq 'quad'){SQuad 6 12 $tx $ty}else{SBox 4 5 3 $tx $ty 'fullbright'}; $r += N ("$prefix$($i+1)") (V $x $y $z) $shape (QY (-$d)) }
  ,$r
}

function Atlas([string]$path,[string[]]$hex){
  $bmp=New-Object Drawing.Bitmap(256,256,[Drawing.Imaging.PixelFormat]::Format32bppArgb)
  $g=[Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode='HighQuality'; $g.Clear([Drawing.Color]::FromArgb(0,0,0,0))
  try{
    for($i=0;$i -lt 4;$i++){
      $h=$hex[$i].TrimStart('#'); $r=[Convert]::ToInt32($h.Substring(0,2),16); $gg=[Convert]::ToInt32($h.Substring(2,2),16); $b=[Convert]::ToInt32($h.Substring(4,2),16)
      $base=[Drawing.Color]::FromArgb(220,$r,$gg,$b)
      $br=New-Object Drawing.Drawing2D.LinearGradientBrush([Drawing.PointF]::new([float]($i*64),0),[Drawing.PointF]::new([float](($i+1)*64),64),$base,[Drawing.Color]::FromArgb(255,[Math]::Min(255,$r+30),[Math]::Min(255,$gg+30),[Math]::Min(255,$b+30)))
      $g.FillRectangle($br,$i*64,0,64,64); $br.Dispose()
    }
    $p=New-Object Drawing.Pen([Drawing.Color]::FromArgb(100,255,255,255),1.2)
    for($i=0;$i -lt 60;$i++){ $x=Get-Random -Minimum 4 -Maximum 252; $y=Get-Random -Minimum 72 -Maximum 252; $g.DrawEllipse($p,$x,$y,2,2) }
    $p.Dispose(); $bmp.Save($path,[Drawing.Imaging.ImageFormat]::Png)
  } finally { $g.Dispose(); $bmp.Dispose() }
}

function BuildModel([string]$id){
  switch($id){
    'FireIceCone_Rig' { return M @(
      (N 'BaseCone' (V 0 18 0) (SBox 20 30 20 128 0)),
      (N 'CorePillar' (V 0 20 0) (SBox 8 34 8 64 0 'fullbright')),
      (N 'FirePivot' (V 0 23 0) (SNone) (Q 0 0 0 1) @((N 'FireBladeA' (V 0 0 14) (SQuad 26 36 0 0)),(N 'FireBladeB' (V 0 0 -14) (SQuad 26 36 0 0) (QY 90)))),
      (N 'IcePivot' (V 0 16 0) (SNone) (Q 0 0 0 1) @((N 'IceBladeA' (V 0 0 12) (SQuad 24 32 64 0)),(N 'IceBladeB' (V 0 0 -12) (SQuad 24 32 64 0) (QY 90)))),
      (N 'OrbitPivot' (V 0 37 0) (SNone) (Q 0 0 0 1) (Ring 'Spark_' 6 16 0 192 0)),
      (N 'Crown' (V 0 43 0) (SBox 10 8 10 192 0 'fullbright'))
    ) }
    'FireIceCone_Rig_Core' { return M @((N 'CrystalCore' (V 0 16 0) (SBox 12 26 12 64 0 'fullbright')),(N 'InnerCore' (V 0 16 0) (SBox 6 30 6 0 0 'fullbright')),(N 'HaloPivot' (V 0 30 0) (SNone) (Q 0 0 0 1) (Ring 'Halo_' 8 12 0 192 0)),(N 'TopSpike' (V 0 38 0) (SBox 6 10 6 128 0))) }
    'FireIceCone_Rig_HelixFire' { return M @((N 'HelixPivot' (V 0 22 0) (SNone) (Q 0 0 0 1) @((N 'RibbonA' (V 0 8 13) (SQuad 24 28 0 0)),(N 'RibbonB' (V 0 0 -13) (SQuad 24 28 0 0) (QY 90)),(N 'RibbonC' (V 0 -8 13) (SQuad 24 28 0 0) (QY 180)))),(N 'HelixCore' (V 0 22 0) (SBox 6 18 6 192 0 'fullbright'))) }
    'FireIceCone_Rig_HelixIce' { return M @((N 'HelixPivot' (V 0 22 0) (SNone) (Q 0 0 0 1) @((N 'RibbonA' (V 0 8 13) (SQuad 24 28 64 0)),(N 'RibbonB' (V 0 0 -13) (SQuad 24 28 64 0) (QY 90)),(N 'RibbonC' (V 0 -8 13) (SQuad 24 28 64 0) (QY 180)))),(N 'HelixCore' (V 0 22 0) (SBox 6 18 6 192 0 'fullbright'))) }
    'StormClouds_Rig' { return M @((N 'CloudPivot' (V 0 30 0) (SNone) (Q 0 0 0 1) @((N 'CloudA' (V 0 0 0) (SBox 24 12 16 0 0)),(N 'CloudB' (V -10 1 4) (SBox 14 9 12 0 0)),(N 'CloudC' (V 11 1 -4) (SBox 13 8 11 0 0)) )) ,(N 'RainPivot' (V 0 13 0) (SNone) (Q 0 0 0 1) (Ring 'Rain_' 8 10 -3 192 0 'quad')), (N 'ChargeCore' (V 0 20 0) (SBox 6 8 6 128 0 'fullbright')), (N 'MistRing' (V 0 20 0) (SNone) (Q 0 0 0 1) (Ring 'Mist_' 10 18 0 64 0))) }
    'StormClouds_Rig_Core' { return M @((N 'StormHeart' (V 0 18 0) (SBox 10 14 10 64 0 'fullbright')),(N 'ArcPivot' (V 0 20 0) (SNone) (Q 0 0 0 1) @((N 'ArcA' (V 0 0 11) (SQuad 16 12 128 0)),(N 'ArcB' (V 11 0 0) (SQuad 16 12 128 0) (QY 90)),(N 'ArcC' (V 0 0 -11) (SQuad 16 12 128 0) (QY 180)),(N 'ArcD' (V -11 0 0) (SQuad 16 12 128 0) (QY -90))))) }
    'StormClouds_Rig_Bolt' { return M @((N 'BoltRoot' (V 0 20 0) (SNone) (Q 0 0 0 1) @((N 'Seg1' (V -2 8 0) (SBox 4 12 4 128 0 'fullbright')),(N 'Seg2' (V 2 -2 1) (SBox 4 12 4 128 0 'fullbright')),(N 'Seg3' (V -1 -12 -1) (SBox 4 12 4 128 0 'fullbright')))),(N 'Flash' (V 0 18 0) (SQuad 26 30 192 0))) }
    'StormClouds_Rig_Ring' { return M @((N 'RingPivot' (V 0 0 0) (SNone) (Q 0 0 0 1) (Ring 'Rune_' 14 20 18 64 0)),(N 'RingCore' (V 0 18 0) (SBox 8 6 8 0 0))) }
    'WingWangSigil_Rig' { return M @((N 'DiscBase' (V 0 18 0) (SBox 20 3 20 128 0)),(N 'DiscYin' (V -4 19 0) (SBox 10 3 10 0 0 'fullbright')),(N 'DiscYang' (V 4 19 0) (SBox 10 3 10 64 0 'fullbright')),(N 'GlyphPivot' (V 0 24 0) (SNone) (Q 0 0 0 1) (Ring 'Glyph_' 12 19 0 192 0)),(N 'Pulse' (V 0 19 0) (SQuad 34 34 192 0))) }
    'WingWangSigil_Rig_Inner' { return M @((N 'InnerPivot' (V 0 0 0) (SNone) (Q 0 0 0 1) ((Ring 'ISeg_' 10 11 19 0 0)+(Ring 'IGlyph_' 14 16 21 192 0))),(N 'InnerCore' (V 0 19 0) (SBox 6 3 6 0 0))) }
    'WingWangSigil_Rig_Mid' { return M @((N 'MidPivot' (V 0 0 0) (SNone) (Q 0 0 0 1) ((Ring 'MSeg_' 12 16 19 64 0)+(Ring 'MGlyph_' 16 21 21 192 0))),(N 'MidCore' (V 0 19 0) (SBox 6 3 6 64 0))) }
    'WingWangSigil_Rig_Outer' { return M @((N 'OuterPivot' (V 0 0 0) (SNone) (Q 0 0 0 1) ((Ring 'OSeg_' 14 21 19 128 0)+(Ring 'OGlyph_' 18 26 21 192 0))),(N 'OuterCore' (V 0 19 0) (SBox 6 3 6 128 0))) }
    'FireworksShow_Rig' { return M @((N 'Pedestal' (V 0 10 0) (SBox 16 12 16 128 0)),(N 'Barrel' (V 0 22 0) (SBox 10 20 10 0 0)),(N 'EmitterPivot' (V 0 24 0) (SNone) (Q 0 0 0 1) (Ring 'Spark_' 10 14 26 192 0))) }
    'FireworksShow_Rig_Launcher' { return M @((N 'Body' (V 0 20 0) (SBox 10 26 10 0 0)),(N 'Cap' (V 0 34 0) (SBox 8 6 8 64 0)),(N 'FinA' (V 0 10 8) (SQuad 10 10 64 0)),(N 'FinB' (V 8 10 0) (SQuad 10 10 64 0) (QY 90)),(N 'Fuse' (V 0 38 0) (SQuad 4 10 192 0))) }
    'FireworksShow_Rig_Burst' { return M @((N 'BurstPivot' (V 0 0 0) (SNone) (Q 0 0 0 1) (Ring 'Ray_' 16 18 22 192 0)),(N 'BurstCore' (V 0 22 0) (SBox 8 8 8 128 0 'fullbright')),(N 'Halo' (V 0 22 0) (SQuad 34 34 192 0))) }
    default { throw "Unknown model id: $id" }
  }
}

function KQ([int]$t,$q){ [ordered]@{time=$t;delta=$q;interpolationType='smooth'} }
function KV([int]$t,[double]$x,[double]$y,[double]$z){ [ordered]@{time=$t;delta=(V $x $y $z);interpolationType='smooth'} }
function Tr($p=@(),$o=@()){ [ordered]@{position=@($p);orientation=@($o);shapeStretch=@();shapeVisible=@();shapeUvOffset=@()} }
function Yaw([int]$d){ @( (KQ 0 (QY 0)),(KQ ([int]($d*0.25)) (QY 90)),(KQ ([int]($d*0.5)) (QY 180)),(KQ ([int]($d*0.75)) (QY -90)),(KQ $d (QY 0)) ) }
function Osc([int]$d,[int]$deg){ @( (KQ 0 (QY 0)),(KQ ([int]($d*0.25)) (QY $deg)),(KQ ([int]($d*0.5)) (QY 0)),(KQ ([int]($d*0.75)) (QY (-$deg))),(KQ $d (QY 0)) ) }
function Anim([int]$dur,[hashtable]$tracks){ [ordered]@{formatVersion=1;duration=$dur;holdLastKeyframe=$false;nodeAnimations=$tracks} }
function BuildAnim([string]$name){
  switch($name){
    'FireIceCone_Rig_Idle.blockyanim' { return Anim 90 ([ordered]@{'BaseCone'=(Tr @() (Yaw 90));'OrbitPivot'=(Tr @() (Yaw 45));'FirePivot'=(Tr @() (Osc 90 24));'IcePivot'=(Tr @() (Osc 90 -18))}) }
    'FireIceCone_Rig_Core_Idle.blockyanim' { return Anim 80 ([ordered]@{'HaloPivot'=(Tr @() (Yaw 80));'CrystalCore'=(Tr @((KV 0 0 16 0),(KV 40 0 17.6 0),(KV 80 0 16 0)) @())}) }
    'StormClouds_Rig_Ring_Idle.blockyanim' { return Anim 70 ([ordered]@{'RingPivot'=(Tr @() (Yaw 70))}) }
    'WingWangSigil_Rig_Idle.blockyanim' { return Anim 90 ([ordered]@{'GlyphPivot'=(Tr @() (Yaw 90))}) }
    'WingWangSigil_Rig_Mid_Idle.blockyanim' { return Anim 85 ([ordered]@{'MidPivot'=(Tr @() (Yaw 85))}) }
    'WingWangSigil_Rig_Outer_Idle.blockyanim' { return Anim 95 ([ordered]@{'OuterPivot'=(Tr @() (Yaw 95))}) }
    default { throw "Unknown anim id: $name" }
  }
}
$root=ResolveP $Root
$premium=Join-Path $root 'assets/Server/Models/HyPerksVFX/Premium'
$dirs=@{FireIceCone=(Join-Path $premium 'FireIceCone');StormClouds=(Join-Path $premium 'StormClouds');WingWangSigil=(Join-Path $premium 'WingWangSigil');FireworksShow=(Join-Path $premium 'FireworksShow')}
foreach($d in $dirs.Values){ if(!(Test-Path $d)){ New-Item -ItemType Directory -Force $d|Out-Null }; if($CleanExisting){ Get-ChildItem $d -File | Where-Object Name -ne '_EXPECTED_FILES.txt' | Remove-Item -Force } }

Atlas (Join-Path $dirs.FireIceCone 'FireIceCone_Atlas.png') @('A52708','0E4B7A','61201B','17454F')
Atlas (Join-Path $dirs.StormClouds 'StormClouds_Atlas.png') @('2A3559','2F3E6A','5B4B19','1C365F')
Atlas (Join-Path $dirs.WingWangSigil 'WingWangSigil_Atlas.png') @('1E3B60','5B1D59','2E3068','19364E')
Atlas (Join-Path $dirs.FireworksShow 'FireworksShow_Atlas.png') @('6B1020','2C155E','5A340B','27420B')

$models=@('FireIceCone_Rig','FireIceCone_Rig_Core','FireIceCone_Rig_HelixFire','FireIceCone_Rig_HelixIce','StormClouds_Rig','StormClouds_Rig_Core','StormClouds_Rig_Bolt','StormClouds_Rig_Ring','WingWangSigil_Rig','WingWangSigil_Rig_Inner','WingWangSigil_Rig_Mid','WingWangSigil_Rig_Outer','FireworksShow_Rig','FireworksShow_Rig_Launcher','FireworksShow_Rig_Burst')
foreach($m in $models){ $p=if($m.StartsWith('FireIceCone_')){$dirs.FireIceCone}elseif($m.StartsWith('StormClouds_')){$dirs.StormClouds}elseif($m.StartsWith('WingWangSigil_')){$dirs.WingWangSigil}else{$dirs.FireworksShow}; J (Join-Path $p "$m.blockymodel") (BuildModel $m) }

$anims=@('FireIceCone_Rig_Idle.blockyanim','FireIceCone_Rig_Core_Idle.blockyanim','StormClouds_Rig_Ring_Idle.blockyanim','WingWangSigil_Rig_Idle.blockyanim','WingWangSigil_Rig_Mid_Idle.blockyanim','WingWangSigil_Rig_Outer_Idle.blockyanim')
foreach($a in $anims){ $p=if($a.StartsWith('FireIceCone_')){$dirs.FireIceCone}elseif($a.StartsWith('StormClouds_')){$dirs.StormClouds}elseif($a.StartsWith('WingWangSigil_')){$dirs.WingWangSigil}else{$dirs.FireworksShow}; J (Join-Path $p $a) (BuildAnim $a) }

Write-Host "Custom premium assets generated in: $premium"
Write-Host "Models: $($models.Count) | Animations: $($anims.Count) | Textures: 4"

