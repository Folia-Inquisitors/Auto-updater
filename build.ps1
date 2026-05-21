$ErrorActionPreference = "Stop"

$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildDir = Join-Path $Root "build"
$ClassesDir = Join-Path $BuildDir "classes"
$DistDir = Join-Path $Root "dist"
$JarPath = Join-Path $DistDir "velocity-auto-updater.jar"

function Assert-InWorkspace {
    param([string] $PathToCheck)
    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $resolvedPath = [System.IO.Path]::GetFullPath($PathToCheck)
    if (-not $resolvedPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to touch path outside workspace: $resolvedPath"
    }
}

Assert-InWorkspace $BuildDir
Assert-InWorkspace $DistDir

if (Test-Path $ClassesDir) {
    Assert-InWorkspace $ClassesDir
    Remove-Item -LiteralPath $ClassesDir -Recurse -Force
}

New-Item -ItemType Directory -Force -Path $ClassesDir | Out-Null
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$sources = Get-ChildItem -Path (Join-Path $Root "src/main/java") -Recurse -Filter "*.java" | Select-Object -ExpandProperty FullName
if (-not $sources) {
    throw "No Java sources found."
}

& javac --release 17 -encoding UTF-8 -d $ClassesDir $sources
if ($LASTEXITCODE -ne 0) {
    throw "javac failed with exit code $LASTEXITCODE"
}

if (Test-Path $JarPath) {
    Assert-InWorkspace $JarPath
    Remove-Item -LiteralPath $JarPath -Force
}

$manifestPath = Join-Path $BuildDir "MANIFEST.MF"
$manifest = @(
    "Manifest-Version: 1.0"
    "Main-Class: dev.velocityupdater.VelocityAutoUpdater"
    "Implementation-Version: 0.2.0"
    ""
) -join "`r`n"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($manifestPath, $manifest, $utf8NoBom)

$jarCommand = Get-Command jar.exe -ErrorAction SilentlyContinue
if (-not $jarCommand) {
    $jarCommand = Get-ChildItem "C:\Program Files\Java" -Recurse -Filter jar.exe -ErrorAction SilentlyContinue | Select-Object -First 1
}
if (-not $jarCommand) {
    throw "Could not find jar.exe. Install a JDK or add jar.exe to PATH."
}

$jarExe = if ($jarCommand.Source) { $jarCommand.Source } else { $jarCommand.FullName }
& $jarExe --create --file $JarPath --manifest $manifestPath -C $ClassesDir .
if ($LASTEXITCODE -ne 0) {
    throw "jar failed with exit code $LASTEXITCODE"
}

Write-Host "Built $JarPath"
