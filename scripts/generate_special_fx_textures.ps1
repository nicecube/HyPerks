Add-Type -AssemblyName System.Drawing

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function New-Color {
    param(
        [Parameter(Mandatory = $true)][string]$Hex,
        [int]$Alpha = 255
    )

    $value = $Hex.TrimStart("#")
    if ($value.Length -ne 6) {
        throw "Invalid hex color: $Hex"
    }

    $r = [Convert]::ToInt32($value.Substring(0, 2), 16)
    $g = [Convert]::ToInt32($value.Substring(2, 2), 16)
    $b = [Convert]::ToInt32($value.Substring(4, 2), 16)
    return [System.Drawing.Color]::FromArgb($Alpha, $r, $g, $b)
}

function New-Canvas {
    $bmp = New-Object System.Drawing.Bitmap(64, 64, ([System.Drawing.Imaging.PixelFormat]::Format32bppArgb))
    $graphics = [System.Drawing.Graphics]::FromImage($bmp)
    $graphics.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
    $graphics.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
    $graphics.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
    $graphics.CompositingQuality = [System.Drawing.Drawing2D.CompositingQuality]::HighQuality
    $graphics.Clear([System.Drawing.Color]::FromArgb(0, 0, 0, 0))
    return @{
        Bitmap = $bmp
        Graphics = $graphics
    }
}

function Close-Canvas {
    param(
        [Parameter(Mandatory = $true)]$Canvas,
        [Parameter(Mandatory = $true)][string]$Path
    )

    try {
        $Canvas.Bitmap.Save($Path, [System.Drawing.Imaging.ImageFormat]::Png)
    } finally {
        $Canvas.Graphics.Dispose()
        $Canvas.Bitmap.Dispose()
    }
}

function Add-Glow {
    param(
        [Parameter(Mandatory = $true)][System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$CenterY,
        [float]$MaxRadius,
        [Parameter(Mandatory = $true)][System.Drawing.Color]$Color
    )

    for ($i = 6; $i -ge 1; $i--) {
        $ratio = $i / 6.0
        $alpha = [Math]::Max(14, [Math]::Min(118, [int](105 * $ratio)))
        $radius = $MaxRadius * $ratio
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb($alpha, $Color))
        try {
            $Graphics.FillEllipse($brush, $CenterX - $radius, $CenterY - $radius, $radius * 2, $radius * 2)
        } finally {
            $brush.Dispose()
        }
    }
}

function Draw-FireIceConeTexture {
    param([string]$OutPath)

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 24 -CenterY 31 -MaxRadius 25 -Color (New-Color "FF7A3E")
        Add-Glow -Graphics $g -CenterX 40 -CenterY 31 -MaxRadius 25 -Color (New-Color "64D9FF")

        $conePath = New-Object System.Drawing.Drawing2D.GraphicsPath
        try {
            $conePath.AddPolygon(@(
                    [System.Drawing.PointF]::new(32, 8),
                    [System.Drawing.PointF]::new(10, 54),
                    [System.Drawing.PointF]::new(54, 54)
                ))

            $coneBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
                [System.Drawing.PointF]::new(10, 8),
                [System.Drawing.PointF]::new(54, 54),
                (New-Color "FF7A3E"),
                (New-Color "64D9FF")
            )
            $conePen = New-Object System.Drawing.Pen((New-Color "F1FCFF"), 2.2)
            $conePen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
            try {
                $g.FillPath($coneBrush, $conePath)
                $g.DrawPath($conePen, $conePath)
            } finally {
                $coneBrush.Dispose()
                $conePen.Dispose()
            }
        } finally {
            $conePath.Dispose()
        }

        $splitPen = New-Object System.Drawing.Pen((New-Color "F7FDFF" 220), 1.6)
        try {
            $g.DrawLine($splitPen, 32, 11, 32, 52)
        } finally {
            $splitPen.Dispose()
        }

        $sparkWarm = New-Object System.Drawing.SolidBrush (New-Color "FFD17D" 230)
        $sparkCold = New-Object System.Drawing.SolidBrush (New-Color "B4ECFF" 230)
        try {
            $g.FillEllipse($sparkWarm, 14, 41, 5, 5)
            $g.FillEllipse($sparkWarm, 22, 31, 4, 4)
            $g.FillEllipse($sparkCold, 45, 42, 5, 5)
            $g.FillEllipse($sparkCold, 39, 30, 4, 4)
        } finally {
            $sparkWarm.Dispose()
            $sparkCold.Dispose()
        }
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function Draw-StormCloudTexture {
    param([string]$OutPath)

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 32 -CenterY 31 -MaxRadius 29 -Color (New-Color "86A7FF")

        $cloud = New-Object System.Drawing.SolidBrush (New-Color "DEE8FF" 240)
        $cloudDark = New-Object System.Drawing.SolidBrush (New-Color "A9BEE8" 220)
        $outline = New-Object System.Drawing.Pen((New-Color "F0F6FF"), 1.6)
        try {
            $g.FillEllipse($cloudDark, 14, 26, 36, 17)
            $g.FillEllipse($cloud, 16, 21, 15, 14)
            $g.FillEllipse($cloud, 25, 18, 17, 15)
            $g.FillEllipse($cloud, 37, 22, 13, 12)
            $g.DrawEllipse($outline, 14, 26, 36, 17)
        } finally {
            $cloud.Dispose()
            $cloudDark.Dispose()
            $outline.Dispose()
        }

        $bolt = New-Object System.Drawing.Drawing2D.GraphicsPath
        try {
            $bolt.AddPolygon(@(
                    [System.Drawing.PointF]::new(35, 34),
                    [System.Drawing.PointF]::new(28, 48),
                    [System.Drawing.PointF]::new(34, 48),
                    [System.Drawing.PointF]::new(29, 58),
                    [System.Drawing.PointF]::new(40, 43),
                    [System.Drawing.PointF]::new(34, 43)
                ))
            $boltBrush = New-Object System.Drawing.SolidBrush (New-Color "FFE98A" 240)
            $boltPen = New-Object System.Drawing.Pen((New-Color "FFF7D0"), 1.2)
            try {
                $g.FillPath($boltBrush, $bolt)
                $g.DrawPath($boltPen, $bolt)
            } finally {
                $boltBrush.Dispose()
                $boltPen.Dispose()
            }
        } finally {
            $bolt.Dispose()
        }

        $rainPen = New-Object System.Drawing.Pen((New-Color "A6D7FF" 220), 1.4)
        $rainPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $rainPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        try {
            $g.DrawLine($rainPen, 18, 45, 16, 53)
            $g.DrawLine($rainPen, 25, 46, 23, 55)
            $g.DrawLine($rainPen, 44, 45, 42, 54)
        } finally {
            $rainPen.Dispose()
        }
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function Draw-WingWangSigilTexture {
    param([string]$OutPath)

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 24 -CenterY 32 -MaxRadius 24 -Color (New-Color "64E4FF")
        Add-Glow -Graphics $g -CenterX 40 -CenterY 32 -MaxRadius 24 -Color (New-Color "FF7EDC")

        $outerPen = New-Object System.Drawing.Pen((New-Color "F5FBFF"), 2.0)
        try {
            $g.DrawEllipse($outerPen, 10, 10, 44, 44)
        } finally {
            $outerPen.Dispose()
        }

        $disc = [System.Drawing.Rectangle]::new(14, 14, 36, 36)
        $brushCyan = New-Object System.Drawing.SolidBrush (New-Color "6FE9FF" 220)
        $brushPink = New-Object System.Drawing.SolidBrush (New-Color "FF8ADD" 220)
        $dotDark = New-Object System.Drawing.SolidBrush (New-Color "1F2A52" 230)
        $dotLight = New-Object System.Drawing.SolidBrush (New-Color "F7FDFF" 235)
        try {
            $g.FillPie($brushCyan, $disc, 90, 180)
            $g.FillPie($brushPink, $disc, 270, 180)
            $g.FillEllipse($brushPink, 22, 18, 20, 20)
            $g.FillEllipse($brushCyan, 22, 34, 20, 20)
            $g.FillEllipse($dotDark, 28, 24, 7, 7)
            $g.FillEllipse($dotLight, 28, 38, 7, 7)
        } finally {
            $brushCyan.Dispose()
            $brushPink.Dispose()
            $dotDark.Dispose()
            $dotLight.Dispose()
        }

        $midPen = New-Object System.Drawing.Pen((New-Color "E9F7FF" 220), 1.4)
        try {
            $g.DrawEllipse($midPen, 14, 14, 36, 36)
        } finally {
            $midPen.Dispose()
        }
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function Draw-FireworksBurstTexture {
    param([string]$OutPath)

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 32 -CenterY 32 -MaxRadius 30 -Color (New-Color "FF8A6A")
        Add-Glow -Graphics $g -CenterX 32 -CenterY 32 -MaxRadius 24 -Color (New-Color "FFDF73")

        $rayPen = New-Object System.Drawing.Pen((New-Color "FFF3C4" 235), 2.0)
        $rayPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $rayPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        try {
            for ($i = 0; $i -lt 10; $i++) {
                $angle = ($i * 36) * [Math]::PI / 180.0
                $x2 = 32 + [Math]::Cos($angle) * 22
                $y2 = 32 + [Math]::Sin($angle) * 22
                $g.DrawLine($rayPen, 32, 32, [float]$x2, [float]$y2)
            }
        } finally {
            $rayPen.Dispose()
        }

        $core = New-Object System.Drawing.SolidBrush (New-Color "FFFFFF" 245)
        $pink = New-Object System.Drawing.SolidBrush (New-Color "FF9DD2" 230)
        $cyan = New-Object System.Drawing.SolidBrush (New-Color "9DE5FF" 230)
        try {
            $g.FillEllipse($core, 26, 26, 12, 12)
            $g.FillEllipse($pink, 12, 14, 7, 7)
            $g.FillEllipse($cyan, 45, 15, 7, 7)
            $g.FillEllipse($pink, 46, 45, 7, 7)
            $g.FillEllipse($cyan, 11, 45, 7, 7)
            $g.FillEllipse($core, 29, 8, 5, 5)
            $g.FillEllipse($core, 29, 51, 5, 5)
        } finally {
            $core.Dispose()
            $pink.Dispose()
            $cyan.Dispose()
        }
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

$target = Join-Path $PSScriptRoot "..\\assets\\Common\\Particles\\Textures\\HyPerks"
$target = [System.IO.Path]::GetFullPath($target)
New-Item -ItemType Directory -Force $target | Out-Null

Draw-FireIceConeTexture -OutPath (Join-Path $target "Aura_FireIce_Cone.png")
Draw-StormCloudTexture -OutPath (Join-Path $target "Aura_Storm_CloudBolt.png")
Draw-WingWangSigilTexture -OutPath (Join-Path $target "Aura_WingWang_Sigil.png")
Draw-FireworksBurstTexture -OutPath (Join-Path $target "Aura_Fireworks_Burst.png")

Write-Output "Special FX textures generated in: $target"
