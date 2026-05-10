# layer2-doctor.ps1 — diagnose FDRS Layer 2 bridge state on a tech bench.
#
# Usage:
#   irm https://streaming-api.futureai.com/api/v1/install/layer2-doctor.ps1 | iex
#   # or save + run locally:
#   .\layer2-doctor.ps1
#
# Outputs a single Markdown-formatted report. Paste the whole output back
# to whoever asked you to run this — it has every state needed to diagnose
# why the bridge isn't being detected.

$ErrorActionPreference = "Continue"

function Section($title) { Write-Host ""; Write-Host "## $title" -ForegroundColor Cyan }
function Key($k, $v) { Write-Host ("  {0,-22} {1}" -f $k, $v) }
function Ok($msg)   { Write-Host "  [ok]   $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "  [warn] $msg" -ForegroundColor Yellow }
function Bad($msg)  { Write-Host "  [bad]  $msg" -ForegroundColor Red }

Write-Host "# Layer 2 Bridge Doctor · $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host ""
Write-Host "Computer: $env:COMPUTERNAME · User: $env:USERNAME"

# ---------- 1. FDRS install detection ----------
Section "1. FDRS install location"
$fdrsDir = $null
foreach ($k in @('HKLM:\SOFTWARE\WOW6432Node\Ford Motor Company\FDRS', 'HKLM:\SOFTWARE\Ford Motor Company\FDRS')) {
    try {
        $v = Get-ItemProperty -Path $k -ErrorAction Stop
        if ($v.InstallLocation -and (Test-Path $v.InstallLocation)) {
            $fdrsDir = $v.InstallLocation
            Key "registry $k" "InstallLocation=$($v.InstallLocation)"
            break
        }
    } catch {}
}
if (-not $fdrsDir) {
    foreach ($c in @('C:\Program Files (x86)\Ford Motor Company\FDRS', 'C:\Program Files\Ford Motor Company\FDRS')) {
        if (Test-Path $c) { $fdrsDir = $c; Key "fallback path" $c; break }
    }
}
if (-not $fdrsDir) { Bad "FDRS not detected in registry OR Program Files. Install FDRS first." }
else { Ok "FDRS at: $fdrsDir" }

# ---------- 2. JAR deployment status ----------
Section "2. Layer 2 bridge JAR in FDRS bundle"
if ($fdrsDir) {
    $bundleDir = Join-Path $fdrsDir "bundle"
    if (-not (Test-Path $bundleDir)) {
        Bad "FDRS bundle dir missing: $bundleDir"
    } else {
        Key "bundle dir" $bundleDir
        $jars = Get-ChildItem -Path $bundleDir -Filter "futureai-fdrs-layer2-bridge-*.jar" -ErrorAction SilentlyContinue
        if (-not $jars) {
            Bad "no futureai-fdrs-layer2-bridge-*.jar in $bundleDir — STAGE G never deployed it"
        } else {
            foreach ($j in $jars) {
                Key "JAR" "$($j.Name) · $([math]::Round($j.Length/1KB,1)) KB · mtime $($j.LastWriteTime)"
            }
            if ($jars.Count -gt 1) { Warn "more than one JAR present — Felix may load the wrong one" }
        }
    }
}

# ---------- 3. Felix cache state ----------
Section "3. Felix bundle cache"
$felixCache = "$env:PROGRAMDATA\Ford Motor Company\FDRS\fdrs\felix-cache"
if (Test-Path $felixCache) {
    $bundles = Get-ChildItem $felixCache -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '^bundle\d+$' }
    Key "felix cache" "$felixCache · $($bundles.Count) cached bundles"
    foreach ($b in $bundles) {
        $loc = Join-Path $b.FullName "bundle.location"
        if (Test-Path $loc) {
            $url = (Get-Content $loc -ErrorAction SilentlyContinue) -join " "
            if ($url -match "futureai-fdrs-layer2-bridge") {
                Key "  $($b.Name)" $url
            }
        }
    }
} else {
    Warn "Felix cache dir not present yet — FDRS hasn't run its first scan"
}

# ---------- 4. Bridge listening on 18082 ----------
Section "4. Bridge HTTP endpoint"
$port18082 = $null
try {
    $port18082 = Test-NetConnection -ComputerName localhost -Port 18082 -InformationLevel Quiet -WarningAction SilentlyContinue
} catch {}
if ($port18082) {
    Ok "TCP port 18082 listening"
    try {
        $h = Invoke-WebRequest -Uri "http://localhost:18082/health" -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        Key "GET /health" "$($h.StatusCode) $($h.StatusDescription)"
        Key "response body" ($h.Content -replace "`n"," " -replace "`r","")
    } catch {
        Warn "/health threw: $($_.Exception.Message)"
    }
    try {
        $s = Invoke-WebRequest -Uri "http://localhost:18082/getVehicleModelStatus" -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
        Key "vehicle status" ($s.Content.Substring(0, [math]::Min(200, $s.Content.Length)) -replace "`n"," ")
    } catch {
        Key "vehicle status" "(N/A · $($_.Exception.Message))"
    }
} else {
    Bad "TCP port 18082 NOT listening — bridge bundle didn't start"
}

# ---------- 5. FDRS running? ----------
Section "5. FDRS process"
$fdrsProcs = Get-Process -Name FDRS -ErrorAction SilentlyContinue
if ($fdrsProcs) {
    foreach ($p in $fdrsProcs) {
        Key "FDRS pid" "$($p.Id) · started $($p.StartTime) · CPU $([math]::Round($p.CPU,1))s · WS $([math]::Round($p.WS/1MB,0)) MB"
    }
    Ok "FDRS running"
} else {
    Bad "FDRS process not running — close + relaunch needed for Felix to load the bridge"
}

# ---------- 6. Olympus install state ----------
Section "6. Olympus CLI"
$olympusBundle = "$HOME\.olympus\bundle"
if (Test-Path "$olympusBundle\cli\package.json") {
    try {
        $pkg = Get-Content "$olympusBundle\cli\package.json" -Raw | ConvertFrom-Json
        Key "bundle version" $pkg.version
        Key "bundle path" $olympusBundle
    } catch { Warn "couldnt parse $olympusBundle\cli\package.json: $($_.Exception.Message)" }
}
$auth = "$HOME\.olympus\auth.json"
if (Test-Path $auth) {
    Key "signed in as" "(auth.json present, $((Get-Item $auth).LastWriteTime))"
}

# ---------- summary ----------
Section "diagnosis summary"
$hasJar = $false
if ($fdrsDir) {
    $hasJar = (Get-ChildItem (Join-Path $fdrsDir "bundle") -Filter "futureai-fdrs-layer2-bridge-*.jar" -ErrorAction SilentlyContinue).Count -gt 0
}
if (-not $fdrsDir) {
    Bad "FDRS not installed — install FDRS, then re-run 'irm https://streaming-api.futureai.com/api/v1/install/olympus.ps1 | iex'"
} elseif (-not $hasJar) {
    Bad "JAR missing — re-run install: irm https://streaming-api.futureai.com/api/v1/install/olympus.ps1 | iex"
} elseif (-not $fdrsProcs) {
    Bad "FDRS not running — launch FDRS so Felix can scan + load the bridge bundle"
} elseif (-not $port18082) {
    Bad "JAR deployed + FDRS running but port 18082 not listening — close FDRS, wipe felix-cache, relaunch:"
    Write-Host "    Stop-Process -Name FDRS -Force" -ForegroundColor Gray
    Write-Host "    Remove-Item '$felixCache' -Recurse -Force" -ForegroundColor Gray
    Write-Host "    Start-Process '$fdrsDir\FDRS.exe'" -ForegroundColor Gray
} else {
    Ok "all green — bridge listening on 18082, FDRS running, JAR deployed"
}
