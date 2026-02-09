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

function New-RoundedPath {
    param(
        [Parameter(Mandatory = $true)][System.Drawing.RectangleF]$Rect,
        [float]$Radius = 10.0
    )

    $diameter = $Radius * 2.0
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddArc($Rect.X, $Rect.Y, $diameter, $diameter, 180, 90)
    $path.AddArc($Rect.Right - $diameter, $Rect.Y, $diameter, $diameter, 270, 90)
    $path.AddArc($Rect.Right - $diameter, $Rect.Bottom - $diameter, $diameter, $diameter, 0, 90)
    $path.AddArc($Rect.X, $Rect.Bottom - $diameter, $diameter, $diameter, 90, 90)
    $path.CloseFigure()
    return $path
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
        $alpha = [Math]::Max(12, [Math]::Min(120, [int](110 * $ratio)))
        $radius = $MaxRadius * $ratio
        $brush = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb($alpha, $Color))
        try {
            $Graphics.FillEllipse($brush, $CenterX - $radius, $CenterY - $radius, $radius * 2, $radius * 2)
        } finally {
            $brush.Dispose()
        }
    }
}

function Draw-TextPath {
    param(
        [Parameter(Mandatory = $true)][System.Drawing.Graphics]$Graphics,
        [Parameter(Mandatory = $true)][string]$Text,
        [Parameter(Mandatory = $true)][System.Drawing.RectangleF]$Rect,
        [float]$Size,
        [Parameter(Mandatory = $true)][System.Drawing.Color]$FillColor,
        [Parameter(Mandatory = $true)][System.Drawing.Color]$OutlineColor,
        [float]$OutlineWidth = 2.0
    )

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $family = New-Object System.Drawing.FontFamily("Segoe UI")
    $stringFormat = New-Object System.Drawing.StringFormat
    $stringFormat.Alignment = [System.Drawing.StringAlignment]::Center
    $stringFormat.LineAlignment = [System.Drawing.StringAlignment]::Center

    try {
        $path.AddString(
            $Text,
            $family,
            [int][System.Drawing.FontStyle]::Bold,
            $Size,
            $Rect,
            $stringFormat
        )
        $outline = New-Object System.Drawing.Pen($OutlineColor, $OutlineWidth)
        $outline.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
        $fill = New-Object System.Drawing.SolidBrush($FillColor)
        try {
            $Graphics.DrawPath($outline, $path)
            $Graphics.FillPath($fill, $path)
        } finally {
            $outline.Dispose()
            $fill.Dispose()
        }
    } finally {
        $stringFormat.Dispose()
        $family.Dispose()
        $path.Dispose()
    }
}

function Draw-TagTexture {
    param(
        [string]$OutPath,
        [string]$Text,
        [string]$Primary,
        [string]$Secondary,
        [string]$Border,
        [string]$Accent
    )

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 32 -CenterY 32 -MaxRadius 29 -Color (New-Color $Primary)

        $rect = [System.Drawing.RectangleF]::new(4, 15, 56, 34)
        $path = New-RoundedPath -Rect $rect -Radius 11
        $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            [System.Drawing.PointF]::new($rect.X, $rect.Y),
            [System.Drawing.PointF]::new($rect.Right, $rect.Bottom),
            (New-Color $Primary),
            (New-Color $Secondary)
        )
        $borderPen = New-Object System.Drawing.Pen((New-Color $Border), 2.2)
        $borderPen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round

        try {
            $g.FillPath($grad, $path)
            $g.DrawPath($borderPen, $path)
        } finally {
            $grad.Dispose()
            $borderPen.Dispose()
            $path.Dispose()
        }

        $shine = New-Object System.Drawing.SolidBrush (New-Color "FFFFFF" 42)
        try {
            $g.FillEllipse($shine, 8, 16, 48, 10)
        } finally {
            $shine.Dispose()
        }

        $accentBrush = New-Object System.Drawing.SolidBrush (New-Color $Accent 230)
        try {
            $g.FillEllipse($accentBrush, 8, 28, 7, 7)
            $g.FillEllipse($accentBrush, 49, 28, 7, 7)
        } finally {
            $accentBrush.Dispose()
        }

        Draw-TextPath -Graphics $g -Text $Text -Rect ([System.Drawing.RectangleF]::new(0, 15, 64, 34)) -Size 14 `
            -FillColor (New-Color "F6FBFF") -OutlineColor (New-Color "1F2D68") -OutlineWidth 2.2
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function Draw-TrailTexture {
    param(
        [string]$OutPath,
        [string]$Text,
        [string]$Primary,
        [string]$Secondary,
        [string]$Edge
    )

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 28 -CenterY 32 -MaxRadius 27 -Color (New-Color $Primary)

        $trailPen = New-Object System.Drawing.Pen((New-Color $Secondary 170), 4.4)
        $trailPen.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $trailPen.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        $trailPen2 = New-Object System.Drawing.Pen((New-Color $Secondary 110), 2.0)
        $trailPen2.StartCap = [System.Drawing.Drawing2D.LineCap]::Round
        $trailPen2.EndCap = [System.Drawing.Drawing2D.LineCap]::Round
        try {
            $g.DrawBezier($trailPen, 6, 42, 18, 39, 30, 29, 53, 24)
            $g.DrawBezier($trailPen2, 4, 46, 18, 41, 32, 33, 55, 30)
        } finally {
            $trailPen.Dispose()
            $trailPen2.Dispose()
        }

        $core = [System.Drawing.RectangleF]::new(14, 16, 42, 24)
        $path = New-RoundedPath -Rect $core -Radius 8
        $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            [System.Drawing.PointF]::new($core.Left, $core.Top),
            [System.Drawing.PointF]::new($core.Right, $core.Bottom),
            (New-Color $Primary),
            (New-Color $Secondary)
        )
        $pen = New-Object System.Drawing.Pen((New-Color $Edge), 1.8)
        $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
        try {
            $g.FillPath($grad, $path)
            $g.DrawPath($pen, $path)
        } finally {
            $grad.Dispose()
            $pen.Dispose()
            $path.Dispose()
        }

        Draw-TextPath -Graphics $g -Text $Text -Rect ([System.Drawing.RectangleF]::new(14, 16, 42, 24)) -Size 11 `
            -FillColor (New-Color "FFFFFF") -OutlineColor (New-Color "1A255B") -OutlineWidth 1.6
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function New-ShieldPath {
    param([float]$X, [float]$Y, [float]$W, [float]$H)
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $points = @(
        [System.Drawing.PointF]::new($X + ($W * 0.50), $Y),
        [System.Drawing.PointF]::new($X + ($W * 0.90), $Y + ($H * 0.18)),
        [System.Drawing.PointF]::new($X + ($W * 0.84), $Y + ($H * 0.70)),
        [System.Drawing.PointF]::new($X + ($W * 0.50), $Y + $H),
        [System.Drawing.PointF]::new($X + ($W * 0.16), $Y + ($H * 0.70)),
        [System.Drawing.PointF]::new($X + ($W * 0.10), $Y + ($H * 0.18))
    )
    $path.AddPolygon($points)
    return $path
}

function Draw-BadgeTexture {
    param(
        [string]$OutPath,
        [string]$Primary,
        [string]$Secondary,
        [string]$Edge,
        [string]$Label,
        [string]$IconColor
    )

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 32 -CenterY 29 -MaxRadius 30 -Color (New-Color $Primary)

        $shield = New-ShieldPath -X 9 -Y 4 -W 46 -H 52
        $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            [System.Drawing.PointF]::new(8, 4),
            [System.Drawing.PointF]::new(56, 56),
            (New-Color $Primary),
            (New-Color $Secondary)
        )
        $edgePen = New-Object System.Drawing.Pen((New-Color $Edge), 2.2)
        $edgePen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
        try {
            $g.FillPath($grad, $shield)
            $g.DrawPath($edgePen, $shield)
        } finally {
            $grad.Dispose()
            $edgePen.Dispose()
            $shield.Dispose()
        }

        $ringPen = New-Object System.Drawing.Pen((New-Color "EFFFFF" 200), 2.0)
        try {
            $g.DrawEllipse($ringPen, 19, 14, 26, 26)
        } finally {
            $ringPen.Dispose()
        }

        Draw-TextPath -Graphics $g -Text $Label -Rect ([System.Drawing.RectangleF]::new(13, 16, 38, 24)) -Size 11 `
            -FillColor (New-Color $IconColor) -OutlineColor (New-Color "18224E") -OutlineWidth 1.6

        Draw-TextPath -Graphics $g -Text "HP" -Rect ([System.Drawing.RectangleF]::new(10, 38, 44, 14)) -Size 8.5 `
            -FillColor (New-Color "F9FCFF") -OutlineColor (New-Color "253670") -OutlineWidth 1.1
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function Draw-AuraTexture {
    param(
        [string]$OutPath,
        [string]$CenterText,
        [string]$Primary,
        [string]$Secondary
    )

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 32 -CenterY 32 -MaxRadius 30 -Color (New-Color $Primary)

        $ringPenOuter = New-Object System.Drawing.Pen((New-Color $Primary 220), 4.6)
        $ringPenInner = New-Object System.Drawing.Pen((New-Color $Secondary 230), 2.2)
        try {
            $g.DrawEllipse($ringPenOuter, 7, 7, 50, 50)
            $g.DrawEllipse($ringPenInner, 12, 12, 40, 40)
        } finally {
            $ringPenOuter.Dispose()
            $ringPenInner.Dispose()
        }

        $coreBrush = New-Object System.Drawing.SolidBrush (New-Color "FFFFFF" 38)
        try {
            $g.FillEllipse($coreBrush, 18, 18, 28, 28)
        } finally {
            $coreBrush.Dispose()
        }

        Draw-TextPath -Graphics $g -Text $CenterText -Rect ([System.Drawing.RectangleF]::new(0, 24, 64, 18)) -Size 9.6 `
            -FillColor (New-Color "F7FBFF") -OutlineColor (New-Color "1D2A61") -OutlineWidth 1.3
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function Draw-StarPolygon {
    param(
        [System.Drawing.Graphics]$Graphics,
        [float]$CenterX,
        [float]$CenterY,
        [float]$OuterRadius,
        [float]$InnerRadius,
        [System.Drawing.Brush]$Fill,
        [System.Drawing.Pen]$Pen
    )

    $points = New-Object 'System.Collections.Generic.List[System.Drawing.PointF]'
    for ($i = 0; $i -lt 10; $i++) {
        $angle = (-90 + ($i * 36)) * [Math]::PI / 180.0
        $radius = if (($i % 2) -eq 0) { $OuterRadius } else { $InnerRadius }
        $x = $CenterX + [Math]::Cos($angle) * $radius
        $y = $CenterY + [Math]::Sin($angle) * $radius
        $points.Add([System.Drawing.PointF]::new([float]$x, [float]$y))
    }

    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    try {
        $path.AddPolygon($points.ToArray())
        $Graphics.FillPath($Fill, $path)
        $Graphics.DrawPath($Pen, $path)
    } finally {
        $path.Dispose()
    }
}

function Draw-TrophyTexture {
    param(
        [string]$OutPath,
        [string]$Type,
        [string]$Primary,
        [string]$Secondary
    )

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 32 -CenterY 31 -MaxRadius 30 -Color (New-Color $Primary)

        $discGrad = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            [System.Drawing.PointF]::new(10, 10),
            [System.Drawing.PointF]::new(54, 54),
            (New-Color $Primary),
            (New-Color $Secondary)
        )
        $discPen = New-Object System.Drawing.Pen((New-Color "F1F8FF" 210), 2.0)
        try {
            $g.FillEllipse($discGrad, 10, 8, 44, 44)
            $g.DrawEllipse($discPen, 10, 8, 44, 44)
        } finally {
            $discGrad.Dispose()
            $discPen.Dispose()
        }

        $iconBrush = New-Object System.Drawing.SolidBrush (New-Color "F8FDFF")
        $iconPen = New-Object System.Drawing.Pen((New-Color "263670"), 1.5)
        try {
            switch ($Type) {
                "star" {
                    Draw-StarPolygon -Graphics $g -CenterX 32 -CenterY 30 -OuterRadius 11 -InnerRadius 5.2 -Fill $iconBrush -Pen $iconPen
                }
                "rune" {
                    $iconPen.Width = 2.4
                    $g.DrawEllipse($iconPen, 20, 18, 24, 24)
                    $g.DrawLine($iconPen, 32, 17, 32, 43)
                    $g.DrawLine($iconPen, 22, 30, 42, 30)
                    $g.DrawLine($iconPen, 24, 22, 40, 38)
                    $g.DrawLine($iconPen, 40, 22, 24, 38)
                }
                "crown" {
                    $points = @(
                        [System.Drawing.PointF]::new(18, 36),
                        [System.Drawing.PointF]::new(22, 23),
                        [System.Drawing.PointF]::new(30, 31),
                        [System.Drawing.PointF]::new(32, 20),
                        [System.Drawing.PointF]::new(34, 31),
                        [System.Drawing.PointF]::new(42, 23),
                        [System.Drawing.PointF]::new(46, 36)
                    )
                    $crown = New-Object System.Drawing.Drawing2D.GraphicsPath
                    try {
                        $crown.AddPolygon($points)
                        $g.FillPath($iconBrush, $crown)
                        $g.DrawPath($iconPen, $crown)
                        $g.FillRectangle($iconBrush, 18, 36, 28, 5)
                        $g.DrawRectangle($iconPen, 18, 36, 28, 5)
                    } finally {
                        $crown.Dispose()
                    }
                }
            }
        } finally {
            $iconBrush.Dispose()
            $iconPen.Dispose()
        }
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

function Draw-FleurTexture {
    param(
        [string]$OutPath,
        [string]$Primary,
        [string]$Secondary,
        [string]$Accent,
        [string]$LabelColor
    )

    $canvas = New-Canvas
    $g = $canvas.Graphics

    try {
        Add-Glow -Graphics $g -CenterX 32 -CenterY 30 -MaxRadius 31 -Color (New-Color $Primary)

        $bg = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
            [System.Drawing.PointF]::new(8, 8),
            [System.Drawing.PointF]::new(56, 56),
            (New-Color $Primary),
            (New-Color $Secondary)
        )
        $bgPath = New-RoundedPath -Rect ([System.Drawing.RectangleF]::new(8, 6, 48, 52)) -Radius 12
        $bgPen = New-Object System.Drawing.Pen((New-Color "E8F6FF" 210), 2.0)
        try {
            $g.FillPath($bg, $bgPath)
            $g.DrawPath($bgPen, $bgPath)
        } finally {
            $bg.Dispose()
            $bgPen.Dispose()
            $bgPath.Dispose()
        }

        $fleur = New-Object System.Drawing.Drawing2D.GraphicsPath
        try {
            $fleur.AddEllipse(26, 12, 12, 16)
            $fleur.AddEllipse(18, 17, 10, 12)
            $fleur.AddEllipse(36, 17, 10, 12)
            $fleur.AddRectangle([System.Drawing.RectangleF]::new(29, 24, 6, 16))
            $fleur.AddEllipse(24, 35, 16, 8)

            $fill = New-Object System.Drawing.SolidBrush (New-Color $Accent)
            $pen = New-Object System.Drawing.Pen((New-Color "24306A"), 1.6)
            $pen.LineJoin = [System.Drawing.Drawing2D.LineJoin]::Round
            try {
                $g.FillPath($fill, $fleur)
                $g.DrawPath($pen, $fleur)
            } finally {
                $fill.Dispose()
                $pen.Dispose()
            }
        } finally {
            $fleur.Dispose()
        }

        Draw-TextPath -Graphics $g -Text "MCQC" -Rect ([System.Drawing.RectangleF]::new(8, 43, 48, 12)) -Size 8.2 `
            -FillColor (New-Color $LabelColor) -OutlineColor (New-Color "1A255B") -OutlineWidth 1.1
    } finally {
        Close-Canvas -Canvas $canvas -Path $OutPath
    }
}

$target = Join-Path $PSScriptRoot "..\\assets\\Common\\Particles\\Textures\\HyPerks"
$target = [System.IO.Path]::GetFullPath($target)
New-Item -ItemType Directory -Force $target | Out-Null

# Tags
Draw-TagTexture -OutPath (Join-Path $target "Tag_VIP.png") -Text "VIP" -Primary "2D63FF" -Secondary "6FB4FF" -Border "D9EEFF" -Accent "5EF0FF"
Draw-TagTexture -OutPath (Join-Path $target "Tag_VIP_PLUS.png") -Text "VIP+" -Primary "2576E8" -Secondary "67E8FF" -Border "E2F7FF" -Accent "B8D0FF"
Draw-TagTexture -OutPath (Join-Path $target "Tag_MVP.png") -Text "MVP" -Primary "3654FF" -Secondary "B65BFF" -Border "F0E2FF" -Accent "5EF0FF"
Draw-TagTexture -OutPath (Join-Path $target "Tag_MVP_PLUS.png") -Text "MVP+" -Primary "2B46D9" -Secondary "E656D9" -Border "F7E4FF" -Accent "64EAFF"

# Trails
Draw-TrailTexture -OutPath (Join-Path $target "Trail_VIP.png") -Text "VIP" -Primary "2D63FF" -Secondary "7EDBFF" -Edge "E6F7FF"
Draw-TrailTexture -OutPath (Join-Path $target "Trail_VIP_PLUS.png") -Text "V+" -Primary "2576E8" -Secondary "5EF0FF" -Edge "E6FBFF"
Draw-TrailTexture -OutPath (Join-Path $target "Trail_MVP.png") -Text "MVP" -Primary "3552E6" -Secondary "CF69FF" -Edge "F4E7FF"
Draw-TrailTexture -OutPath (Join-Path $target "Trail_MVP_PLUS.png") -Text "M+" -Primary "2B42C7" -Secondary "ED5CD3" -Edge "FFE8FA"

# Badges
Draw-BadgeTexture -OutPath (Join-Path $target "Badge_Gold.png") -Primary "295DFF" -Secondary "6EDCFF" -Edge "E5F6FF" -Label "VIP" -IconColor "F9FCFF"
Draw-BadgeTexture -OutPath (Join-Path $target "Badge_Platinum.png") -Primary "4677FF" -Secondary "9BE2FF" -Edge "EEF9FF" -Label "V+" -IconColor "F7FCFF"
Draw-BadgeTexture -OutPath (Join-Path $target "Badge_Diamond.png") -Primary "314EE4" -Secondary "CC6AFF" -Edge "F4E8FF" -Label "MVP" -IconColor "FFFFFF"
Draw-BadgeTexture -OutPath (Join-Path $target "Badge_Crest.png") -Primary "2C3FCE" -Secondary "E85CD4" -Edge "FCEBFF" -Label "M+" -IconColor "FFFFFF"

# Auras
Draw-AuraTexture -OutPath (Join-Path $target "Aura_VIP.png") -CenterText "VIP" -Primary "2D63FF" -Secondary "72DBFF"
Draw-AuraTexture -OutPath (Join-Path $target "Aura_VIP_PLUS.png") -CenterText "VIP+" -Primary "2970E8" -Secondary "53F1FF"
Draw-AuraTexture -OutPath (Join-Path $target "Aura_MVP.png") -CenterText "MVP" -Primary "3552E6" -Secondary "CC67FF"
Draw-AuraTexture -OutPath (Join-Path $target "Aura_MVP_PLUS.png") -CenterText "MVP+" -Primary "2B42C7" -Secondary "E857CE"

# Trophies
Draw-TrophyTexture -OutPath (Join-Path $target "Trophy_Crown.png") -Type "crown" -Primary "2B4EDF" -Secondary "A86CFF"
Draw-TrophyTexture -OutPath (Join-Path $target "Trophy_Rune.png") -Type "rune" -Primary "2C5DE8" -Secondary "56E4FF"
Draw-TrophyTexture -OutPath (Join-Path $target "Trophy_Star.png") -Type "star" -Primary "3650E4" -Secondary "E45CD6"

# MCQC Fleur (main + 3 variants)
Draw-FleurTexture -OutPath (Join-Path $target "MCQC_Fleur.png") -Primary "294FD7" -Secondary "67B9FF" -Accent "FFE6A6" -LabelColor "F8FCFF"
Draw-FleurTexture -OutPath (Join-Path $target "MCQC_Fleur_A.png") -Primary "294FD7" -Secondary "67B9FF" -Accent "FFE6A6" -LabelColor "F8FCFF"
Draw-FleurTexture -OutPath (Join-Path $target "MCQC_Fleur_B.png") -Primary "2B3CB8" -Secondary "CC62FF" -Accent "62EEFF" -LabelColor "FFFFFF"
Draw-FleurTexture -OutPath (Join-Path $target "MCQC_Fleur_C.png") -Primary "365FD8" -Secondary "8AE7FF" -Accent "F4F8FF" -LabelColor "EAF4FF"

Write-Output "Texture regeneration complete: $target"
