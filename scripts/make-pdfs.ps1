# Renders docs to PDFs via headless Chrome (diagrams rendered as vector SVG).
# Run:  powershell -File scripts/make-pdfs.ps1
$ErrorActionPreference = "Continue"   # Chrome prints progress to stderr; not fatal
$root   = Split-Path $PSScriptRoot -Parent
$docs   = Join-Path $root "docs"
$src    = Join-Path $docs "pdf-src"
$outDir = Join-Path $docs "pdf"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

$chrome = @(
  "$env:ProgramFiles\Google\Chrome\Application\chrome.exe",
  "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe",
  "$env:LOCALAPPDATA\Google\Chrome\Application\chrome.exe",
  "$env:ProgramFiles\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $chrome) { throw "Chrome/Edge not found" }

function Convert-One($htmlPath, $pdfPath) {
  if (Test-Path $pdfPath) { Remove-Item $pdfPath -Force }
  $uri = "file:///" + ($htmlPath -replace '\\','/')
  & $chrome --headless=new --disable-gpu --no-sandbox `
    --run-all-compositor-stages-before-draw --virtual-time-budget=25000 `
    --no-pdf-header-footer --print-to-pdf="$pdfPath" $uri 2>$null 1>$null
  $global:LASTEXITCODE = 0
  Start-Sleep -Seconds 2
  if (Test-Path $pdfPath) {
    "{0,-34} {1,6:N0} KB" -f (Split-Path $pdfPath -Leaf), ((Get-Item $pdfPath).Length/1KB)
  } else { "FAILED: $pdfPath" }
}

# combined
Convert-One (Join-Path $docs "ALL_DOCS.html") (Join-Path $docs "COMPLETE_DOCUMENTATION.pdf")
# per-doc
Get-ChildItem $src -Filter *.html | ForEach-Object {
  Convert-One $_.FullName (Join-Path $outDir ($_.BaseName + ".pdf"))
}
Write-Host "`nPDFs in: $outDir  (+ COMPLETE_DOCUMENTATION.pdf in docs/)"
