param(
    [string]$Root = "."
)

Add-Type -AssemblyName System.Drawing
Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$script:NodeId = 0

function Resolve-AbsolutePath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return $PathValue
    }
    return [System.IO.Path]::GetFullPath((Join-Path (Get-Location).Path $PathValue))
}

function Write-JsonNoBom {
    param(
        [Parameter(Mandatory = $true)][string]$PathValue,
        [Parameter(Mandatory = $true)]$Data
    )
    [System.IO.File]::WriteAllText(
        $PathValue,
        ($Data | ConvertTo-Json -Depth 40),
        $script:Utf8NoBom
    )
}

function V([double]$x, [double]$y, [double]$z) { [ordered]@{ x = $x; y = $y; z = $z } }
function Q([double]$x, [double]$y, [double]$z, [double]$w) { [ordered]@{ x = $x; y = $y; z = $z; w = $w } }
function QY([double]$deg) {
    $rad = $deg * [Math]::PI / 180.0
    $s = [Math]::Sin($rad / 2.0)
    $c = [Math]::Cos($rad / 2.0)
    return (Q 0 $s 0 $c)
}

function TL([int]$x, [int]$y) {
    return [ordered]@{
        offset = [ordered]@{ x = $x; y = $y }
        mirror = [ordered]@{ x = $false; y = $false }
        angle = 0
    }
}

function SNone() {
    return [ordered]@{
        type = "none"
        offset = (V 0 0 0)
        stretch = (V 0 0 0)
        settings = [ordered]@{ isPiece = $false }
        textureLayout = [ordered]@{}
        unwrapMode = "custom"
        visible = $true
        doubleSided = $false
        shadingMode = "flat"
    }
}

function SBox(
    [int]$sx,
    [int]$sy,
    [int]$sz,
    [int]$tx,
    [int]$ty,
    [string]$shading = "flat"
) {
    $face = TL $tx $ty
    return [ordered]@{
        type = "box"
        offset = (V 0 0 0)
        stretch = (V 1 1 1)
        settings = [ordered]@{
            isPiece = $false
            size = [ordered]@{ x = $sx; y = $sy; z = $sz }
        }
        textureLayout = [ordered]@{
            back = $face
            right = $face
            front = $face
            left = $face
            top = $face
            bottom = $face
        }
        unwrapMode = "custom"
        visible = $true
        doubleSided = $false
        shadingMode = $shading
    }
}

function SQuad([int]$sx, [int]$sy, [int]$tx, [int]$ty) {
    return [ordered]@{
        type = "quad"
        offset = (V 0 0 0)
        stretch = (V 1 1 1)
        settings = [ordered]@{
            isPiece = $false
            size = [ordered]@{ x = $sx; y = $sy; z = 0 }
            normal = "+Z"
        }
        textureLayout = [ordered]@{
            front = (TL $tx $ty)
        }
        unwrapMode = "custom"
        visible = $true
        doubleSided = $true
        shadingMode = "fullbright"
    }
}

function N(
    [string]$name,
    $position,
    $shape,
    $orientation = $null,
    $children = @()
) {
    if ($null -eq $orientation) {
        $orientation = Q 0 0 0 1
    }
    $script:NodeId++
    return [ordered]@{
        id = [string]$script:NodeId
        name = $name
        position = $position
        orientation = $orientation
        shape = $shape
        children = @($children)
    }
}

function New-Model($children) {
    $script:NodeId = 0
    return [ordered]@{
        nodes = @(
            (N "Root" (V 0 0 0) (SNone) (Q 0 0 0 1) $children)
        )
        lod = "auto"
    }
}

function Ring-Boxes(
    [string]$prefix,
    [int]$count,
    [double]$radius,
    [double]$y,
    [int]$sx,
    [int]$sy,
    [int]$sz,
    [int]$tx,
    [int]$ty
) {
    $nodes = @()
    for ($i = 0; $i -lt $count; $i++) {
        $deg = (360.0 * $i) / $count
        $rad = $deg * [Math]::PI / 180.0
        $x = [Math]::Cos($rad) * $radius
        $z = [Math]::Sin($rad) * $radius
        $nodes += N ("$prefix$($i + 1)") (V $x $y $z) (SBox $sx $sy $sz $tx $ty "fullbright") (QY (-$deg))
    }
    return ,$nodes
}

function Ring-Quads(
    [string]$prefix,
    [int]$count,
    [double]$radius,
    [double]$y,
    [int]$sx,
    [int]$sy,
    [int]$tx,
    [int]$ty,
    [double]$angleOffset = 0.0
) {
    $nodes = @()
    for ($i = 0; $i -lt $count; $i++) {
        $deg = ((360.0 * $i) / $count) + $angleOffset
        $rad = $deg * [Math]::PI / 180.0
        $x = [Math]::Cos($rad) * $radius
        $z = [Math]::Sin($rad) * $radius
        $nodes += N ("$prefix$($i + 1)") (V $x $y $z) (SQuad $sx $sy $tx $ty) (QY (-$deg))
    }
    return ,$nodes
}

function New-Color([string]$hex, [int]$alpha = 255) {
    $value = $hex.TrimStart("#")
    $r = [Convert]::ToInt32($value.Substring(0, 2), 16)
    $g = [Convert]::ToInt32($value.Substring(2, 2), 16)
    $b = [Convert]::ToInt32($value.Substring(4, 2), 16)
    return [System.Drawing.Color]::FromArgb($alpha, $r, $g, $b)
}

function New-Canvas256() {
    $bmp = New-Object System.Drawing.Bitmap(256, 256, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb))
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::HighQuality
    $g.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $g.Clear([System.Drawing.Color]::FromArgb(0, 0, 0, 0))
    return @{ Bitmap = $bmp; Graphics = $g }
}

function Save-CanvasPng {
    param(
        [Parameter(Mandatory = $true)]$Canvas,
        [Parameter(Mandatory = $true)][string]$OutPath
    )
    try {
        $Canvas.Bitmap.Save($OutPath, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $Canvas.Graphics.Dispose()
        $Canvas.Bitmap.Dispose()
    }
}

function Fill-TileGradient(
    [System.Drawing.Graphics]$g,
    [int]$tileX,
    [int]$tileY,
    [string]$c1,
    [string]$c2
) {
    $x = $tileX * 64
    $y = $tileY * 64
    $rect = [System.Drawing.RectangleF]::new($x, $y, 64, 64)
    $brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
        [System.Drawing.PointF]::new($x, $y),
        [System.Drawing.PointF]::new($x + 64, $y + 64),
        (New-Color $c1),
        (New-Color $c2)
    )
    try {
        $g.FillRectangle($brush, $rect)
    } finally {
        $brush.Dispose()
    }
}

function Draw-FireIce-Atlas([string]$outPath) {
    $canvas = New-Canvas256
    $g = $canvas.Graphics
    $rand = [System.Random]::new(91571)
    try {
        Fill-TileGradient $g 0 0 "5C1307" "F1591F"   # Fire
        Fill-TileGradient $g 1 0 "0C2B4B" "74D3FF"   # Ice
        Fill-TileGradient $g 2 0 "2A1E33" "6A5B2B"   # Hybrid crack
        Fill-TileGradient $g 3 0 "3E2A10" "EEDB8F"   # Glow sparks

        # Fire streaks
        $firePenA = New-Object System.Drawing.Pen((New-Color "FFB075" 200), 1.8)
        $firePenB = New-Object System.Drawing.Pen((New-Color "FFD9AE" 150), 1.1)
        try {
            for ($i = 0; $i -lt 90; $i++) {
                $sx = $rand.Next(2, 60)
                $sy = $rand.Next(4, 62)
                $ex = $sx + $rand.Next(-8, 8)
                $ey = $sy - $rand.Next(6, 20)
                $g.DrawLine($firePenA, $sx, $sy, $ex, $ey)
            }
            for ($i = 0; $i -lt 45; $i++) {
                $sx = $rand.Next(3, 61)
                $sy = $rand.Next(8, 62)
                $g.DrawLine($firePenB, $sx, $sy, $sx + $rand.Next(-4, 4), $sy - $rand.Next(4, 12))
            }
        } finally {
            $firePenA.Dispose()
            $firePenB.Dispose()
        }

        # Ice fractures
        $icePenA = New-Object System.Drawing.Pen((New-Color "D8F6FF" 220), 1.5)
        $icePenB = New-Object System.Drawing.Pen((New-Color "8ED8FF" 160), 1.0)
        try {
            for ($i = 0; $i -lt 75; $i++) {
                $sx = 64 + $rand.Next(3, 61)
                $sy = $rand.Next(3, 61)
                $ex = $sx + $rand.Next(-14, 14)
                $ey = $sy + $rand.Next(-14, 14)
                $g.DrawLine($icePenA, $sx, $sy, $ex, $ey)
            }
            for ($i = 0; $i -lt 50; $i++) {
                $sx = 64 + $rand.Next(2, 62)
                $sy = $rand.Next(2, 62)
                $g.DrawLine($icePenB, $sx, $sy, $sx + $rand.Next(-8, 8), $sy + $rand.Next(-8, 8))
            }
        } finally {
            $icePenA.Dispose()
            $icePenB.Dispose()
        }

        # Hybrid cracks + embers
        $hybridCrack = New-Object System.Drawing.Pen((New-Color "FBE0A8" 150), 1.2)
        $hybridDark = New-Object System.Drawing.Pen((New-Color "241928" 180), 1.8)
        $emberBrush = New-Object System.Drawing.SolidBrush((New-Color "FFD2A8" 180))
        try {
            for ($i = 0; $i -lt 60; $i++) {
                $sx = 128 + $rand.Next(2, 62)
                $sy = $rand.Next(2, 62)
                $mx = $sx + $rand.Next(-6, 6)
                $my = $sy + $rand.Next(-6, 6)
                $ex = $mx + $rand.Next(-6, 6)
                $ey = $my + $rand.Next(-6, 6)
                $g.DrawLine($hybridDark, $sx, $sy, $mx, $my)
                $g.DrawLine($hybridCrack, $mx, $my, $ex, $ey)
            }
            for ($i = 0; $i -lt 80; $i++) {
                $x = 128 + $rand.Next(2, 62)
                $y = $rand.Next(2, 62)
                $r = $rand.Next(1, 3)
                $g.FillEllipse($emberBrush, $x, $y, $r, $r)
            }
        } finally {
            $hybridCrack.Dispose()
            $hybridDark.Dispose()
            $emberBrush.Dispose()
        }

        # Glow tile stars
        $glowBrush = New-Object System.Drawing.SolidBrush((New-Color "FFF2C8" 210))
        $glowPen = New-Object System.Drawing.Pen((New-Color "FFF8E2" 200), 1.0)
        try {
            for ($i = 0; $i -lt 120; $i++) {
                $x = 192 + $rand.Next(1, 63)
                $y = $rand.Next(1, 63)
                $s = $rand.Next(1, 4)
                $g.FillEllipse($glowBrush, $x, $y, $s, $s)
            }
            for ($i = 0; $i -lt 20; $i++) {
                $cx = 192 + $rand.Next(8, 56)
                $cy = $rand.Next(8, 56)
                $g.DrawLine($glowPen, $cx - 3, $cy, $cx + 3, $cy)
                $g.DrawLine($glowPen, $cx, $cy - 3, $cx, $cy + 3)
            }
        } finally {
            $glowBrush.Dispose()
            $glowPen.Dispose()
        }
    } finally {
        Save-CanvasPng -Canvas $canvas -OutPath $outPath
    }
}

function Draw-Storm-Atlas([string]$outPath) {
    $canvas = New-Canvas256
    $g = $canvas.Graphics
    $rand = [System.Random]::new(47219)
    try {
        Fill-TileGradient $g 0 0 "2C3A57" "6D87AD"   # Soft clouds
        Fill-TileGradient $g 1 0 "1A273E" "3F587C"   # Dark cloud layer
        Fill-TileGradient $g 2 0 "111A2D" "2A3356"   # Lightning
        Fill-TileGradient $g 3 0 "172A43" "35506E"   # Rain/mist

        # Cloud blobs tile 0 and tile 1
        foreach ($tile in @(0, 1)) {
            $baseX = $tile * 64
            for ($i = 0; $i -lt 140; $i++) {
                $x = $baseX + $rand.Next(0, 64)
                $y = $rand.Next(4, 62)
                $w = $rand.Next(7, 20)
                $h = $rand.Next(6, 16)
                $alpha = if ($tile -eq 0) { $rand.Next(35, 95) } else { $rand.Next(25, 75) }
                $c = if ($tile -eq 0) { New-Color "D7E6FF" $alpha } else { New-Color "A6B9D5" $alpha }
                $b = New-Object System.Drawing.SolidBrush($c)
                try {
                    $g.FillEllipse($b, $x - ($w / 2), $y - ($h / 2), $w, $h)
                } finally {
                    $b.Dispose()
                }
            }
        }

        # Lightning arcs tile 2
        $boltPenA = New-Object System.Drawing.Pen((New-Color "FFF5BC" 230), 2.0)
        $boltPenB = New-Object System.Drawing.Pen((New-Color "BDEBFF" 170), 1.2)
        try {
            for ($i = 0; $i -lt 18; $i++) {
                $x = 128 + $rand.Next(6, 58)
                $y = $rand.Next(2, 12)
                for ($s = 0; $s -lt 5; $s++) {
                    $nx = $x + $rand.Next(-9, 10)
                    $ny = $y + $rand.Next(7, 13)
                    $g.DrawLine($boltPenA, $x, $y, $nx, $ny)
                    $g.DrawLine($boltPenB, $x + 1, $y, $nx + 1, $ny)
                    $x = $nx
                    $y = $ny
                }
            }
        } finally {
            $boltPenA.Dispose()
            $boltPenB.Dispose()
        }

        # Rain streaks + mist points tile 3
        $rainPen = New-Object System.Drawing.Pen((New-Color "A7D7FF" 180), 1.4)
        $mistBrush = New-Object System.Drawing.SolidBrush((New-Color "DAEEFF" 110))
        try {
            for ($i = 0; $i -lt 120; $i++) {
                $x = 192 + $rand.Next(1, 63)
                $y = $rand.Next(0, 62)
                $len = $rand.Next(5, 13)
                $g.DrawLine($rainPen, $x, $y, $x + $rand.Next(-2, 3), $y + $len)
            }
            for ($i = 0; $i -lt 110; $i++) {
                $x = 192 + $rand.Next(1, 63)
                $y = $rand.Next(1, 63)
                $r = $rand.Next(1, 4)
                $g.FillEllipse($mistBrush, $x, $y, $r, $r)
            }
        } finally {
            $rainPen.Dispose()
            $mistBrush.Dispose()
        }
    } finally {
        Save-CanvasPng -Canvas $canvas -OutPath $outPath
    }
}

function Build-FireIce-Rig {
    $children = @()

    $coneTiers = @(
        (N "ConeTier_0" (V 0 4 0) (SBox 26 8 26 0 0)),
        (N "ConeTier_1" (V 0 11 0) (SBox 22 8 22 0 0)),
        (N "ConeTier_2" (V 0 18 0) (SBox 18 8 18 64 0)),
        (N "ConeTier_3" (V 0 25 0) (SBox 14 8 14 64 0)),
        (N "ConeTier_4" (V 0 32 0) (SBox 10 8 10 64 0)),
        (N "ConeApex" (V 0 39 0) (SBox 6 6 6 192 0 "fullbright"))
    )
    $children += $coneTiers
    $children += (N "CoreSpine" (V 0 20 0) (SBox 6 40 6 128 0 "fullbright"))

    $fireVeils = Ring-Quads "FireVeil_" 6 15 18 30 40 0 0
    $iceVeils = Ring-Quads "IceVeil_" 6 16 18 28 38 64 0 30
    $children += (N "FireShellPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $fireVeils)
    $children += (N "IceShellPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $iceVeils)

    $sparks = Ring-Boxes "Spark_" 12 17 23 3 3 3 192 0
    $children += (N "SparkOrbitPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $sparks)

    $ground = Ring-Quads "GroundSlice_" 8 13 2 10 8 128 0 22.5
    $children += (N "GroundHaloPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $ground)

    return (New-Model $children)
}

function Build-FireIce-Core {
    $children = @()
    $children += @(
        (N "InnerTier_0" (V 0 8 0) (SBox 12 8 12 64 0 "fullbright")),
        (N "InnerTier_1" (V 0 15 0) (SBox 10 8 10 64 0 "fullbright")),
        (N "InnerTier_2" (V 0 22 0) (SBox 8 8 8 192 0 "fullbright")),
        (N "InnerTier_3" (V 0 29 0) (SBox 6 8 6 192 0 "fullbright")),
        (N "InnerTier_4" (V 0 36 0) (SBox 4 8 4 192 0 "fullbright"))
    )
    $haloA = Ring-Boxes "HaloA_" 10 9 20 2 2 2 192 0
    $haloB = Ring-Boxes "HaloB_" 12 13 27 2 2 2 192 0
    $children += (N "HaloPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) ($haloA + $haloB))
    return (New-Model $children)
}

function Build-FireIce-Helix([string]$tileMode) {
    $children = @()
    $segments = @()
    $tile = if ($tileMode -eq "fire") { 0 } else { 64 }
    for ($i = 0; $i -lt 9; $i++) {
        $deg = 40.0 * $i
        $rad = $deg * [Math]::PI / 180.0
        $radius = 8.5 + (($i % 2) * 1.5)
        $x = [Math]::Cos($rad) * $radius
        $z = [Math]::Sin($rad) * $radius
        $y = -12 + ($i * 5)
        $segments += N ("Ribbon_$i") (V $x $y $z) (SQuad 20 18 $tile 0) (QY (-$deg))
    }
    $children += (N "HelixPivot" (V 0 22 0) (SNone) (Q 0 0 0 1) $segments)
    $children += (N "HelixCore" (V 0 22 0) (SBox 6 34 6 192 0 "fullbright"))
    return (New-Model $children)
}

function Build-Storm-Rig {
    $children = @()

    $puffs = @(
        @{ n = "CloudCore"; x = 0; y = 30; z = 0; sx = 20; sy = 12; sz = 16; tx = 0 },
        @{ n = "CloudL1"; x = -11; y = 30; z = 4; sx = 14; sy = 10; sz = 12; tx = 0 },
        @{ n = "CloudR1"; x = 11; y = 30; z = -4; sx = 14; sy = 10; sz = 12; tx = 0 },
        @{ n = "CloudF"; x = 0; y = 29; z = 10; sx = 16; sy = 9; sz = 10; tx = 64 },
        @{ n = "CloudB"; x = 0; y = 29; z = -10; sx = 16; sy = 9; sz = 10; tx = 64 },
        @{ n = "CloudU1"; x = -5; y = 34; z = -2; sx = 10; sy = 7; sz = 8; tx = 64 },
        @{ n = "CloudU2"; x = 6; y = 34; z = 1; sx = 10; sy = 7; sz = 8; tx = 64 },
        @{ n = "CloudU3"; x = 0; y = 35; z = -7; sx = 9; sy = 6; sz = 8; tx = 64 }
    )
    foreach ($p in $puffs) {
        $children += N $p.n (V $p.x $p.y $p.z) (SBox $p.sx $p.sy $p.sz $p.tx 0)
    }

    $rain = Ring-Quads "Rain_" 14 11 14 5 16 192 0 12.5
    $children += (N "RainPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $rain)

    $mist = Ring-Boxes "Mist_" 16 17 17 4 4 3 64 0
    $children += (N "MistPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $mist)

    $boltSlices = @(
        (N "BoltSlice_A" (V 0 20 9) (SQuad 14 24 128 0)),
        (N "BoltSlice_B" (V 9 20 0) (SQuad 14 24 128 0) (QY 90)),
        (N "BoltSlice_C" (V 0 20 -9) (SQuad 14 24 128 0) (QY 180)),
        (N "BoltSlice_D" (V -9 20 0) (SQuad 14 24 128 0) (QY -90))
    )
    $children += (N "BoltPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $boltSlices)
    $children += (N "ChargeCore" (V 0 20 0) (SBox 6 8 6 192 0 "fullbright"))

    return (New-Model $children)
}

function Build-Storm-Core {
    $children = @()
    $children += (N "StormHeart" (V 0 19 0) (SBox 10 14 10 64 0 "fullbright"))
    $children += (N "StormHeartInner" (V 0 19 0) (SBox 6 18 6 192 0 "fullbright"))
    $children += @(
        (N "CoreCloudA" (V -7 23 0) (SBox 8 6 7 0 0)),
        (N "CoreCloudB" (V 7 23 0) (SBox 8 6 7 0 0)),
        (N "CoreCloudC" (V 0 23 7) (SBox 7 6 8 0 0)),
        (N "CoreCloudD" (V 0 23 -7) (SBox 7 6 8 0 0))
    )
    $arcs = Ring-Quads "Arc_" 6 11 20 14 12 128 0 30
    $children += (N "ArcPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) $arcs)
    return (New-Model $children)
}

function Build-Storm-Bolt {
    $children = @()
    $children += @(
        (N "Seg_0" (V -2 30 0) (SBox 4 10 4 128 0 "fullbright")),
        (N "Seg_1" (V 2 21 1) (SBox 4 10 4 128 0 "fullbright")),
        (N "Seg_2" (V -1 12 -1) (SBox 4 10 4 128 0 "fullbright")),
        (N "Seg_3" (V 2 3 1) (SBox 4 10 4 128 0 "fullbright")),
        (N "Seg_4" (V -2 -6 -1) (SBox 4 10 4 128 0 "fullbright"))
    )
    $children += @(
        (N "FlashA" (V 0 16 0) (SQuad 20 34 192 0)),
        (N "FlashB" (V 0 16 0) (SQuad 20 34 192 0) (QY 90))
    )
    return (New-Model $children)
}

function Build-Storm-Ring {
    $children = @()
    $runes = Ring-Quads "Rune_" 18 20 19 7 9 128 0
    $glyphs = Ring-Boxes "Glyph_" 14 14 19 2 2 2 192 0
    $children += (N "RingPivot" (V 0 0 0) (SNone) (Q 0 0 0 1) ($runes + $glyphs))
    $children += (N "RingCore" (V 0 19 0) (SBox 8 6 8 64 0 "fullbright"))
    return (New-Model $children)
}

function KQ([int]$t, $q) { [ordered]@{ time = $t; delta = $q; interpolationType = "smooth" } }
function KV([int]$t, [double]$x, [double]$y, [double]$z) { [ordered]@{ time = $t; delta = (V $x $y $z); interpolationType = "smooth" } }
function Tr($p = @(), $o = @()) {
    return [ordered]@{
        position = @($p)
        orientation = @($o)
        shapeStretch = @()
        shapeVisible = @()
        shapeUvOffset = @()
    }
}

function Anim([int]$duration, $tracks) {
    return [ordered]@{
        formatVersion = 1
        duration = $duration
        holdLastKeyframe = $false
        nodeAnimations = $tracks
    }
}

function YawCycle([int]$d) {
    return @(
        (KQ 0 (QY 0)),
        (KQ ([int]($d * 0.25)) (QY 90)),
        (KQ ([int]($d * 0.50)) (QY 180)),
        (KQ ([int]($d * 0.75)) (QY -90)),
        (KQ $d (QY 0))
    )
}

function Build-FireIce-Rig-Idle {
    return Anim 96 ([ordered]@{
        FireShellPivot = (Tr @() (YawCycle 96))
        IceShellPivot = (Tr @() @(
            (KQ 0 (QY 0)),
            (KQ 24 (QY -90)),
            (KQ 48 (QY -180)),
            (KQ 72 (QY 90)),
            (KQ 96 (QY 0))
        ))
        SparkOrbitPivot = (Tr @() (YawCycle 64))
        GroundHaloPivot = (Tr @() (YawCycle 120))
        ConeTier_4 = (Tr @(
            (KV 0 0 32 0),
            (KV 48 0 33.4 0),
            (KV 96 0 32 0)
        ) @())
        ConeApex = (Tr @(
            (KV 0 0 39 0),
            (KV 48 0 40.2 0),
            (KV 96 0 39 0)
        ) @())
    })
}

function Build-FireIce-Core-Idle {
    return Anim 88 ([ordered]@{
        HaloPivot = (Tr @() (YawCycle 88))
        InnerTier_4 = (Tr @(
            (KV 0 0 36 0),
            (KV 44 0 37.2 0),
            (KV 88 0 36 0)
        ) @())
    })
}

function Build-Storm-Ring-Idle {
    return Anim 72 ([ordered]@{
        RingPivot = (Tr @() (YawCycle 72))
        RingCore = (Tr @(
            (KV 0 0 19 0),
            (KV 36 0 20.2 0),
            (KV 72 0 19 0)
        ) @())
    })
}

$projectRoot = Resolve-AbsolutePath -PathValue $Root
$commonVfxRoot = Join-Path $projectRoot "assets/Common/VFX/HyPerks"
$fireDir = Join-Path $commonVfxRoot "FireIceCone"
$stormDir = Join-Path $commonVfxRoot "StormClouds"

New-Item -ItemType Directory -Force -Path $fireDir | Out-Null
New-Item -ItemType Directory -Force -Path $stormDir | Out-Null

Draw-FireIce-Atlas -outPath (Join-Path $fireDir "FireIceCone_Atlas.png")
Draw-Storm-Atlas -outPath (Join-Path $stormDir "StormClouds_Atlas.png")

Write-JsonNoBom -PathValue (Join-Path $fireDir "FireIceCone_Rig.blockymodel") (Build-FireIce-Rig)
Write-JsonNoBom -PathValue (Join-Path $fireDir "FireIceCone_Rig_Core.blockymodel") (Build-FireIce-Core)
Write-JsonNoBom -PathValue (Join-Path $fireDir "FireIceCone_Rig_HelixFire.blockymodel") (Build-FireIce-Helix "fire")
Write-JsonNoBom -PathValue (Join-Path $fireDir "FireIceCone_Rig_HelixIce.blockymodel") (Build-FireIce-Helix "ice")
Write-JsonNoBom -PathValue (Join-Path $fireDir "FireIceCone_Rig_Idle.blockyanim") (Build-FireIce-Rig-Idle)
Write-JsonNoBom -PathValue (Join-Path $fireDir "FireIceCone_Rig_Core_Idle.blockyanim") (Build-FireIce-Core-Idle)

Write-JsonNoBom -PathValue (Join-Path $stormDir "StormClouds_Rig.blockymodel") (Build-Storm-Rig)
Write-JsonNoBom -PathValue (Join-Path $stormDir "StormClouds_Rig_Core.blockymodel") (Build-Storm-Core)
Write-JsonNoBom -PathValue (Join-Path $stormDir "StormClouds_Rig_Bolt.blockymodel") (Build-Storm-Bolt)
Write-JsonNoBom -PathValue (Join-Path $stormDir "StormClouds_Rig_Ring.blockymodel") (Build-Storm-Ring)
Write-JsonNoBom -PathValue (Join-Path $stormDir "StormClouds_Rig_Ring_Idle.blockyanim") (Build-Storm-Ring-Idle)

Write-Output "Upgraded premium assets generated:"
Write-Output " - $fireDir"
Write-Output " - $stormDir"
