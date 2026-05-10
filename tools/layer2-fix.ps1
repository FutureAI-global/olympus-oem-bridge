# layer2-fix.ps1 — JAR version check + Felix cache wipe + FDRS relaunch.
#
# Usage:
#   $a='https://raw.githubusercontent.com'
#   $b='/FutureAI-global/olympus-oem-bridge'
#   $c='/main/tools/layer2-fix.ps1'
#   irm "$a$b$c" | iex
#
# Or save + run locally with .\layer2-fix.ps1
#
# What it does:
#   1. Probe FDRS install dir (registry + Program Files)
#   2. Crack the deployed JAR open and read its real Bundle-Version
#      (so we know if the file on disk is actually v0.2.7 or stale 0.2.5)
#   3. Stop FDRS process if running
#   4. Wipe Felix bundle cache (forces fresh class load)
#   5. Relaunch FDRS
#   6. Wait + poll bridge /health endpoint
#   7. Report what version Felix is now serving + diagnose mismatch

$ErrorActionPreference = "Continue"

function Section($title) { Write-Host ""; Write-Host "## $title" -ForegroundColor Cyan }
function Key($k, $v) { Write-Host ("  {0,-22} {1}" -f $k, $v) }
function Ok($msg)   { Write-Host "  [ok]   $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  [warn] $msg" -ForegroundColor Yellow }
function Bad($msg)  { Write-Host "  [bad]  $msg" -ForegroundColor Red }

Write-Host "# Layer 2 Bridge Fix · $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta

# ---------- 1. Find FDRS ----------
Section "1. Locate FDRS"
$fdrsDir = $null
foreach ($k in @('HKLM:\SOFTWARE\WOW6432Node\Ford Motor Company\FDRS','HKLM:\SOFTWARE\Ford Motor Company\FDRS')) {
    try {
        $v = Get-ItemProperty -Path $k -ErrorAction Stop
        if ($v.InstallLocation -and (Test-Path $v.InstallLocation)) { $fdrsDir = $v.InstallLocation; break }
    } catch {}
}
if (-not $fdrsDir) {
    foreach ($c in @('C:\Program Files (x86)\Ford Motor Company\FDRS','C:\Program Files\Ford Motor Company\FDRS')) {
        if (Test-Path $c) { $fdrsDir = $c; break }
    }
}
if (-not $fdrsDir) {
    Bad "FDRS not found. Install FDRS, then run irm install/olympus.ps1 again."
    exit 1
}
Key "FDRS at" $fdrsDir

# ---------- 2. Read JAR's actual Bundle-Version ----------
Section "2. Inspect JAR Bundle-Version"
$jars = Get-ChildItem -Path (Join-Path $fdrsDir "bundle") -Filter "futureai-fdrs-layer2-bridge-*.jar" -ErrorAction SilentlyContinue
if (-not $jars) {
    Bad "no JAR in $fdrsDir\bundle. Re-run irm install/olympus.ps1."
    exit 1
}
$jar = $jars | Select-Object -First 1
Key "JAR file" "$($jar.Name) ($([math]::Round($jar.Length/1KB,1)) KB)"

$jarVersion = $null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
    $manifest = $zip.Entries | Where-Object { $_.FullName -eq 'META-INF/MANIFEST.MF' }
    if ($manifest) {
        $reader = New-Object IO.StreamReader($manifest.Open())
        $manifestText = $reader.ReadToEnd()
        $reader.Close()
        if ($manifestText -match 'Bundle-Version:\s*([^\r\n]+)') {
            $jarVersion = $Matches[1].Trim()
        }
    }
    $zip.Dispose()
} catch {
    Warn "couldn't read JAR manifest: $($_.Exception.Message)"
}

if ($jarVersion) {
    Key "JAR Bundle-Version" $jarVersion
    if ($jarVersion -ne "0.2.7") {
        Bad "JAR file content is $jarVersion, expected 0.2.7 — bundle was built with stale JAR. Cache wipe alone won't fix this."
        Write-Host ""
        Write-Host "  This is a bundle-build bug — the v0.9.0 olympus bundle shipped a stale JAR." -ForegroundColor Yellow
        Write-Host "  Cache wipe will still proceed in case Felix had something even older." -ForegroundColor Yellow
    } else {
        Ok "JAR is the expected 0.2.7 — Felix cache is the problem"
    }
}

# ---------- 3. Stop FDRS ----------
Section "3. Stop FDRS"
$procs = Get-Process -Name FDRS -ErrorAction SilentlyContinue
if ($procs) {
    foreach ($p in $procs) { Key "killing FDRS pid" $p.Id }
    Stop-Process -Name FDRS -Force -ErrorAction SilentlyContinue
    Start-Sleep -Seconds 3
    Ok "FDRS stopped"
} else {
    Key "FDRS process" "(not running)"
}

# ---------- 4. Wipe Felix cache ----------
Section "4. Wipe Felix cache"
$felixCache = "$env:PROGRAMDATA\Ford Motor Company\FDRS\fdrs\felix-cache"
if (Test-Path $felixCache) {
    $count = (Get-ChildItem $felixCache -Directory -ErrorAction SilentlyContinue).Count
    try {
        Remove-Item $felixCache -Recurse -Force -ErrorAction Stop
        Ok "wiped $count cached bundles from $felixCache"
    } catch {
        Bad "couldn't wipe cache: $($_.Exception.Message)"
        Warn "may need to run this PowerShell as Administrator"
    }
} else {
    Key "felix cache" "(not present, nothing to wipe)"
}

# ---------- 5. Relaunch FDRS ----------
Section "5. Relaunch FDRS"
$fdrsExe = Join-Path $fdrsDir "FDRS.exe"
if (-not (Test-Path $fdrsExe)) {
    Bad "FDRS.exe not at $fdrsExe"
    exit 1
}
Start-Process $fdrsExe
Ok "launched FDRS"
Key "waiting for Felix" "30s for bundle scan + start"
$start = Get-Date
1..30 | ForEach-Object { Start-Sleep -Seconds 1; Write-Host -NoNewline "." }
Write-Host ""

# ---------- 6. Verify bridge endpoint ----------
Section "6. Verify bridge"
$reachable = $false
$serving = $null
1..6 | ForEach-Object {
    if (-not $reachable) {
        try {
            $h = Invoke-WebRequest -Uri "http://localhost:18082/health" -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
            if ($h.StatusCode -eq 200) {
                $reachable = $true
                $body = $h.Content | ConvertFrom-Json
                $serving = $body.bundleVersion
                Ok "bridge listening on 18082"
                Key "/health bundleVersion" $serving
                Key "invokerAvailable" $body.invokerAvailable
                Key "commandsExposed" "$($body.commandsExposed.Count) commands"
            }
        } catch {
            Start-Sleep -Seconds 5
        }
    }
}

if (-not $reachable) {
    Bad "bridge not listening after 60s — Felix may have failed to start the bundle"
    Warn "check FDRS logs at $env:LOCALAPPDATA\Ford Motor Company\FDRS\Logs"
}

# ---------- 7. Diagnosis ----------
Section "diagnosis"
if (-not $reachable) {
    Bad "FDRS Layer 2 bridge did not come up"
} elseif ($jarVersion -and $serving -and $jarVersion -ne $serving) {
    Bad "version mismatch: JAR file says $jarVersion but Felix is serving $serving"
    Warn "this means Felix is loading a DIFFERENT JAR somewhere — check $fdrsDir\bundle\ for duplicates"
} elseif ($serving -eq "0.2.7") {
    Ok "🎯 Layer 2 bridge serving v0.2.7 — should now work end-to-end"
    Write-Host ""
    Write-Host "  Olympus TUI should now detect the bridge automatically. If it still says " -NoNewline
    Write-Host "FDRS off" -ForegroundColor Yellow -NoNewline
    Write-Host ", restart the olympus session (Ctrl-C, run olympus again)."
} elseif ($serving -eq "0.2.5") {
    Bad "Felix is serving v0.2.5 — the JAR file content is stale (bundle build bug)"
    Warn "this can't be fixed locally — the v0.9.0 olympus bundle shipped a stale JAR"
    Warn "report back: 'JAR is 0.2.5 internally — bundle build needs a rebuild'"
} else {
    Warn "unexpected serving version: $serving"
}
