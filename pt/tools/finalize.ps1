# Finalize the PT deck in PowerPoint (COM):
#   1. open pt/build/base.pptx (from build_deck.py)
#   2. replace the VIDEO_SLOT shape on slide 13 with pt/assets/demo_clip.mp4 (embedded, poster = demo_poster.jpg)
#   3. SaveAs PPTX with EmbedTrueTypeFonts = msoTrue  -> ppt/fonts/*.fntdata
#   3b. PDF backup copy next to it
#   4. export 1920x1080 PNG previews to pt/build/png/
# Usage: powershell -NoProfile -ExecutionPolicy Bypass -File pt/tools/finalize.ps1 [-NoVideo]
param([switch]$NoVideo)
$ErrorActionPreference = 'Stop'
$pt = Split-Path -Parent $PSScriptRoot
$src = Join-Path $pt 'build\base.pptx'
$out = Join-Path $pt ([char]0xB300 + [char]0xD559 + [char]0xC0DD + '_' + [char]0xAE40 + '&' + [char]0xC591 + '_Stay-or-Go.pptx')  # 대학생_김&양_Stay-or-Go.pptx
$png = Join-Path $pt 'build\png'
New-Item -ItemType Directory -Force $png | Out-Null
Get-ChildItem $png -Filter *.png | Remove-Item -Force

$app = New-Object -ComObject PowerPoint.Application
try {
  $pres = $app.Presentations.Open($src, $false, $false, $false)
  if (-not $NoVideo) {
    $s = $pres.Slides.Item(13)
    $slot = $null
    foreach ($sh in @($s.Shapes)) { if ($sh.Name -eq 'VIDEO_SLOT') { $slot = $sh } }
    if (-not $slot) { throw 'VIDEO_SLOT not found on slide 13' }
    $L = $slot.Left; $T = $slot.Top; $W = $slot.Width; $H = $slot.Height
    $vid = $s.Shapes.AddMediaObject2((Join-Path $pt 'assets\demo_clip.mp4'), 0, -1, $L, $T, $W, $H)
    $vid.Name = 'Demo video (backup, 65s, muted, 1x with one cut)'
    $vid.LockAspectRatio = 0
    $vid.Left = $L; $vid.Top = $T; $vid.Width = $W; $vid.Height = $H
    try { $vid.MediaFormat.SetDisplayPictureFromFile((Join-Path $pt 'assets\demo_poster.jpg')) } catch { "poster: $_" }
    $vid.AnimationSettings.PlaySettings.PlayOnEntry = 0
    $vid.AlternativeText = 'Stay-or-Go demo recording: Stay to Go switch at SOC 86%, Go hands route to navigation, drive lock'
    foreach ($sh in @($s.Shapes)) { if ($sh.Name -eq 'VIDEO_LABEL') { $sh.Delete() } }
    $slot.Delete()
  }
  if (Test-Path $out) { Remove-Item $out -Force }
  $pres.SaveAs($out, 24, -1)   # ppSaveAsOpenXMLPresentation, EmbedTrueTypeFonts = msoTrue
  $pdf = [IO.Path]::ChangeExtension($out, '.pdf')
  if (Test-Path $pdf) { Remove-Item $pdf -Force }
  $pres.SaveCopyAs($pdf, 32)   # ppSaveAsPDF (backup only; the submission is the PPTX)
  foreach ($f in $pres.Fonts) { "font: {0} embedded={1}" -f $f.Name, $f.Embedded }
  $i = 1
  foreach ($sl in $pres.Slides) { $sl.Export((Join-Path $png ('slide_{0:D2}.png' -f $i)), 'PNG', 1920, 1080); $i++ }
  "slides: " + $pres.Slides.Count
  $pres.Close()
} finally { $app.Quit() }
"saved: $out"
