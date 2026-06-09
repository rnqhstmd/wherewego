# App icon generator (Windows / PowerShell + System.Drawing).
#  Brand "wherewego (Korean: woorigagal-jido)": cream bg (#FAF8F5) + terracotta map pin (#C4622D) + cream hole.
#  Output: 1024x1024 opaque (24bpp, no alpha) PNG -> AppIcon.appiconset/AppIcon-1024.png
#  Run: powershell -ExecutionPolicy Bypass -File ios/Tools/make-appicon.ps1
Add-Type -AssemblyName System.Drawing

$size = 1024
$out  = Join-Path $PSScriptRoot "..\WhereWeGo\Resources\Assets.xcassets\AppIcon.appiconset\AppIcon-1024.png"
$out  = [System.IO.Path]::GetFullPath($out)

$bmp = New-Object System.Drawing.Bitmap($size, $size, [System.Drawing.Imaging.PixelFormat]::Format24bppRgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode   = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality

$cream  = [System.Drawing.Color]::FromArgb(250, 248, 245)   # #FAF8F5
$terra  = [System.Drawing.Color]::FromArgb(196, 98, 45)     # #C4622D
$shadow = [System.Drawing.Color]::FromArgb(38, 26, 26, 46)  # ink, low alpha ground shadow

# Background (full-bleed cream; iOS rounds corners automatically)
$g.Clear($cream)

# Ground shadow (soft ellipse under the pin tip)
$shBrush = New-Object System.Drawing.SolidBrush($shadow)
$g.FillEllipse($shBrush, 399, 824, 226, 52)

# Pin: head circle + tail triangle (GraphicsPath union -> teardrop)
# Winding fill so overlapping subpaths UNION (default even-odd would XOR the overlap = white seam).
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$path.FillMode = [System.Drawing.Drawing2D.FillMode]::Winding
$path.AddEllipse(312, 210, 400, 400)   # head center (512,410), radius 200
$p1 = New-Object System.Drawing.PointF([single]362, [single]542)
$p2 = New-Object System.Drawing.PointF([single]662, [single]542)
$p3 = New-Object System.Drawing.PointF([single]512, [single]832)   # pin tip
$pts = @($p1, $p2, $p3)
$path.AddPolygon($pts)
$terraBrush = New-Object System.Drawing.SolidBrush($terra)
$g.FillPath($terraBrush, $path)

# Inner cream hole
$creamBrush = New-Object System.Drawing.SolidBrush($cream)
$g.FillEllipse($creamBrush, 430, 328, 164, 164)   # center (512,410), radius 82

$g.Dispose()
$dir = [System.IO.Path]::GetDirectoryName($out)
if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Force -Path $dir | Out-Null }
$bmp.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
Write-Output "saved: $out"
