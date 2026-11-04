[CmdletBinding()]
param(
    [string]$AndroidSdk = '',
    [string]$CompileSdk = '',
    [string]$BuildToolsVersion = '',
    [string]$Keystore = '',
    [string]$KeystorePassword = 'android',
    [string]$KeyAlias = 'androiddebugkey',
    [string]$KeyPassword = 'android',
    [string]$Output = 'dist/OPPO-AirPods-Enhanced-v3.4.33.apk'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$root = $PSScriptRoot
$manifest = Join-Path $root 'AndroidManifest.xml'
$resDir = Join-Path $root 'res'
$xposedDir = Join-Path $root 'xposed'
$libxposed = Join-Path $root 'libs/libxposed-api.jar'

function Resolve-AndroidSdk {
    param([string]$Requested)
    if ($Requested) {
        if (-not (Test-Path -LiteralPath $Requested -PathType Container)) {
            throw ('Android SDK not found: ' + $Requested)
        }
        return (Resolve-Path -LiteralPath $Requested).Path
    }
    $candidates = @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)
    if ($env:LOCALAPPDATA) {
        $candidates += (Join-Path $env:LOCALAPPDATA 'Android/Sdk')
    }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }
    throw 'Android SDK not found. Pass -AndroidSdk or set ANDROID_SDK_ROOT.'
}

function Find-PlatformDir {
    param([string]$Sdk, [string]$Requested)
    $platformsRoot = Join-Path $Sdk 'platforms'
    if ($Requested) {
        $version = $Requested -replace '^android-', ''
        $path = Join-Path $platformsRoot ('android-' + $version)
        if (Test-Path -LiteralPath (Join-Path $path 'android.jar')) { return $path }
        throw ('Android platform not found: ' + $path)
    }
    $dirs = Get-ChildItem -LiteralPath $platformsRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^android-\d+(\.\d+)?$' } |
        Sort-Object {
            $parts = ($_.Name -replace '^android-', '') -split '\.'
            $major = [int]$parts[0]
            $minor = if ($parts.Length -gt 1) { [int]$parts[1] } else { 0 }
            $major * 1000 + $minor
        } -Descending
    if (-not $dirs) { throw ('No Android platform found under ' + $platformsRoot) }
    return $dirs[0].FullName
}

function Find-BuildToolsDir {
    param([string]$Sdk, [string]$Requested)
    $toolsRoot = Join-Path $Sdk 'build-tools'
    if ($Requested) {
        $path = Join-Path $toolsRoot $Requested
        if ((Test-Path -LiteralPath (Join-Path $path 'd8')) -or
            (Test-Path -LiteralPath (Join-Path $path 'd8.bat'))) { return $path }
        throw ('Android build-tools not found: ' + $path)
    }
    $dirs = Get-ChildItem -LiteralPath $toolsRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d+(\.\d+)*$' } |
        Sort-Object { [version]$_.Name } -Descending
    if (-not $dirs) { throw ('No Android build-tools found under ' + $toolsRoot) }
    return $dirs[0].FullName
}

function Resolve-SdkTool {
    param([string]$Directory, [string]$Name)
    foreach ($candidate in @($Name, ($Name + '.exe'), ($Name + '.bat'))) {
        $path = Join-Path $Directory $candidate
        if (Test-Path -LiteralPath $path) { return $path }
    }
    throw ('Build tool missing: ' + $Name + ' in ' + $Directory)
}

foreach ($path in @($manifest, $resDir, $xposedDir, $libxposed)) {
    if (-not (Test-Path -LiteralPath $path)) { throw ('Required path missing: ' + $path) }
}
foreach ($command in @('javac', 'jar', 'keytool')) {
    if (-not (Get-Command $command -ErrorAction SilentlyContinue)) {
        throw ($command + ' is not on PATH. Install a JDK 17+ and reopen the terminal.')
    }
}

$sdk = Resolve-AndroidSdk $AndroidSdk
$platform = Find-PlatformDir $sdk $CompileSdk
$buildTools = Find-BuildToolsDir $sdk $BuildToolsVersion
$androidJar = Join-Path $platform 'android.jar'
$aapt2 = Resolve-SdkTool $buildTools 'aapt2'
$d8 = Resolve-SdkTool $buildTools 'd8'
$zipalign = Resolve-SdkTool $buildTools 'zipalign'
$apksigner = Resolve-SdkTool $buildTools 'apksigner'
foreach ($tool in @($androidJar, $aapt2, $d8, $zipalign, $apksigner)) {
    if (-not (Test-Path -LiteralPath $tool)) { throw ('Build tool missing: ' + $tool) }
}

$buildDir = Join-Path $root 'build'
$buildFull = [System.IO.Path]::GetFullPath($buildDir)
$rootFull = [System.IO.Path]::GetFullPath($root)
$buildCompare = $buildFull.Replace('\', '/')
$rootCompare = $rootFull.Replace('\', '/').TrimEnd('/') + '/'
if (-not $buildCompare.StartsWith($rootCompare, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw ('Refusing to clean a build directory outside the repository: ' + $buildFull)
}
if (Test-Path -LiteralPath $buildFull) {
    Remove-Item -LiteralPath $buildFull -Recurse -Force
}
$classesDir = Join-Path $buildFull 'classes'
$dexDir = Join-Path $buildFull 'dex'
$genDir = Join-Path $buildFull 'gen'
New-Item -ItemType Directory -Force -Path $classesDir, $dexDir, $genDir | Out-Null

$sources = Get-ChildItem -LiteralPath (Join-Path $root 'src') -Recurse -Filter '*.java' |
    Sort-Object FullName | ForEach-Object { $_.FullName }
if (-not $sources) { throw 'No Java sources found under src/' }

Write-Host ('Compiling ' + $sources.Count + ' Java files...')
& javac -encoding UTF-8 -cp ($androidJar + [System.IO.Path]::PathSeparator + $libxposed) -d $classesDir $sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

$classesJar = Join-Path $buildFull 'classes.jar'
& jar cf $classesJar -C $classesDir .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }

& $d8 --min-api 33 --lib $androidJar --classpath $libxposed --output $dexDir $classesJar
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host 'Linking resources...'
& $aapt2 compile --dir $resDir -o (Join-Path $buildFull 'res.zip')
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }
& $aapt2 link -o (Join-Path $buildFull 'base.apk') -I $androidJar --manifest $manifest `
    -R (Join-Path $buildFull 'res.zip') --auto-add-overlay --java $genDir
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$unsigned = Join-Path $buildFull 'unsigned.apk'
Copy-Item -LiteralPath (Join-Path $buildFull 'base.apk') -Destination $unsigned -Force
$zip = [System.IO.Compression.ZipFile]::Open(
    $unsigned, [System.IO.Compression.ZipArchiveMode]::Update)
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, (Join-Path $dexDir 'classes.dex'), 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    foreach ($name in @('module.prop', 'scope.list', 'java_init.list')) {
        $source = Join-Path $xposedDir $name
        if (-not (Test-Path -LiteralPath $source)) { throw ('Xposed metadata missing: ' + $source) }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $zip, $source, ('META-INF/xposed/' + $name),
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
} finally {
    $zip.Dispose()
}

$aligned = Join-Path $buildFull 'aligned.apk'
& $zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$localReleaseKeystore = Join-Path $root '.keystore/opods-release.jks'
$localReleasePasswordFile = Join-Path $root '.keystore/opods-release.pass'
if (-not $Keystore -and (Test-Path -LiteralPath $localReleaseKeystore) -and
    (Test-Path -LiteralPath $localReleasePasswordFile)) {
    $Keystore = $localReleaseKeystore
    $KeystorePassword = [IO.File]::ReadAllText($localReleasePasswordFile).Trim()
    $KeyAlias = 'opods-release'
    $KeyPassword = $KeystorePassword
    Write-Host 'Using local release keystore'
}

if (-not $Keystore) {
    $Keystore = Join-Path $root '.keystore/oppo-airpods-debug.keystore'
}
$Keystore = [System.IO.Path]::GetFullPath($Keystore)
$keystoreDir = Split-Path -Parent $Keystore
if (-not (Test-Path -LiteralPath $keystoreDir)) {
    New-Item -ItemType Directory -Force -Path $keystoreDir | Out-Null
}
if (-not (Test-Path -LiteralPath $Keystore)) {
    Write-Host ('Generating local debug keystore: ' + $Keystore)
    & keytool -genkeypair -noprompt -keystore $Keystore -storepass $KeystorePassword `
        -keypass $KeyPassword -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 `
        -dname 'CN=Android Debug,O=Android,C=US'
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) {
    [System.IO.Path]::GetFullPath($Output)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $root $Output))
}
$outputDir = Split-Path -Parent $outputPath
if (-not (Test-Path -LiteralPath $outputDir)) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}
if (Test-Path -LiteralPath $outputPath) {
    Remove-Item -LiteralPath $outputPath -Force
}

Write-Host 'Signing APK...'
& $apksigner sign --ks $Keystore --ks-key-alias $KeyAlias `
    --ks-pass ('pass:' + $KeystorePassword) --key-pass ('pass:' + $KeyPassword) `
    --out $outputPath $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& $apksigner verify $outputPath | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'apksigner verification failed' }

$hash = (Get-FileHash -LiteralPath $outputPath -Algorithm SHA256).Hash
$size = (Get-Item -LiteralPath $outputPath).Length
Write-Host ('APK: ' + $outputPath)
Write-Host ('Size: ' + $size + ' bytes')
Write-Host ('SHA-256: ' + $hash)
