# layer2-bench-smoke.ps1 — end-to-end bench validation for the v0.9.0 Olympus bundle.
#
# Run on a tech bench AFTER reinstalling olympus and with FDRS open + a vehicle
# loaded (Ranger 1FTER4FH8NLD22858 confirmed working). Verifies the entire chain
# from CLI version through Layer 2 dispatch through readDID happy path.
#
# Usage (paste-safe 3-fragment pattern):
#   $a='https://raw.githubusercontent.com'
#   $b='/FutureAI-global/olympus-oem-bridge'
#   $c='/main/tools/layer2-bench-smoke.ps1'
#   irm "$a$b$c" | iex
#
# Exit codes:
#   0  all green — bundle is GOLDEN
#   1  any check failed — diagnosis printed; report back to coord PR #2484
#
# Reports a 6-row check table + diagnosis line.

$ErrorActionPreference = "Continue"

function Section($title) { Write-Host ""; Write-Host "## $title" -ForegroundColor Cyan }
function Ok($msg)   { Write-Host "  [ok]   $msg" -ForegroundColor Green }
function Bad($msg)  { Write-Host "  [bad]  $msg" -ForegroundColor Red }
function Warn($msg) { Write-Host "  [warn] $msg" -ForegroundColor Yellow }
function Key($k, $v) { Write-Host ("    {0,-22} {1}" -f $k, $v) }

Write-Host "# Layer 2 Bench Smoke · $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Magenta
Write-Host ""

$results = @{}

# ---------- Check 1: olympus --version ----------
Section "1. Olympus CLI version"
$olympusExe = Get-Command olympus -ErrorAction SilentlyContinue
if (-not $olympusExe) {
    Bad "olympus not on PATH — reinstall: irm https://streaming-api.futureai.com/api/v1/install/olympus.ps1 | iex"
    $results['version'] = $false
} else {
    Key "which olympus" $olympusExe.Source
    $verOutput = & olympus --version 2>&1 | Out-String
    Key "version output" ($verOutput.Trim() -replace "`r`n", " · " -replace "`n", " · ")
    if ($verOutput -match '\b0\.9\.\d+\b') {
        Ok "olympus 0.9.x detected"
        $results['version'] = $true
    } else {
        Bad "expected 0.9.x, got: $verOutput"
        $results['version'] = $false
    }
}

# ---------- Check 2: bridge :18082/health ----------
Section "2. Layer 2 bridge :18082/health"
try {
    $h = Invoke-WebRequest -Uri "http://localhost:18082/health" -TimeoutSec 5 -UseBasicParsing -ErrorAction Stop
    if ($h.StatusCode -eq 200) {
        $body = $h.Content | ConvertFrom-Json
        Key "bundleVersion" $body.bundleVersion
        Key "invokerAvailable" $body.invokerAvailable
        Key "commands exposed" $body.commandsExposed.Count
        if ($body.bundleVersion -match '^0\.2\.7$' -and $body.invokerAvailable -eq $true) {
            Ok "bridge reports v0.2.7 + invoker ready"
            $results['bridge'] = $true
        } elseif ($body.invokerAvailable -eq $true) {
            # Activator drift fix may not have shipped — log but accept invoker
            Warn "version is $($body.bundleVersion), expected 0.2.7 (Activator drift). Functional path still works."
            $results['bridge'] = $true
        } else {
            Bad "invoker not available · bridge present but unable to dispatch"
            $results['bridge'] = $false
        }
    } else {
        Bad "/health returned $($h.StatusCode)"
        $results['bridge'] = $false
    }
} catch {
    Bad "bridge unreachable on :18082 · $($_.Exception.Message)"
    Warn "is FDRS running? did you bounce FDRS after JAR install?"
    $results['bridge'] = $false
}

# ---------- Check 3: getCurrentVin (vehicle loaded?) ----------
Section "3. Vehicle loaded in FDRS"
$currentVin = $null
try {
    $r = Invoke-WebRequest -Uri "http://localhost:18082/commands/getCurrentVin" -Method POST -Body "{}" -ContentType "application/json" -TimeoutSec 10 -UseBasicParsing -ErrorAction Stop
    $body = $r.Content | ConvertFrom-Json
    $currentVin = if ($body.result -and $body.result.currentVin) { $body.result.currentVin } else { $null }
    if ($currentVin) {
        Ok "vehicle loaded: $currentVin"
        $results['vehicle'] = $true
    } else {
        Bad "no vehicle selected in FDRS — pick a vehicle from FDRS history or connect VCM3 + Identify Vehicle"
        $results['vehicle'] = $false
    }
} catch {
    Bad "getCurrentVin probe failed: $($_.Exception.Message)"
    $results['vehicle'] = $false
}

# Skip readDID checks if no vehicle loaded
if ($results['vehicle'] -ne $true) {
    Section "4. read_did 0xF190 0x726 (skipped · no vehicle)"
    Section "5. read_did_batch (skipped · no vehicle)"
    $results['readDid'] = $false
    $results['readDidBatch'] = $false
} else {
    # ---------- Check 4: read_did 0xF190 0x726 (VIN on BCM) ----------
    Section "4. read_did 0xF190 0x726 (VIN on BCM)"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $body = '{"didNumber":"0xF190","nodeAddress":"0x726"}'
        $r = Invoke-WebRequest -Uri "http://localhost:18082/commands/readDID" -Method POST -Body $body -ContentType "application/json" -TimeoutSec 30 -UseBasicParsing -ErrorAction Stop
        $sw.Stop()
        $resp = $r.Content | ConvertFrom-Json
        $vinValue = $null
        if ($resp.result -and $resp.result.subfields) {
            $vinSubfield = $resp.result.subfields | Where-Object { $_.name -eq 'VIN' -or $_.dataType -eq 'ASCII' } | Select-Object -First 1
            if ($vinSubfield) { $vinValue = $vinSubfield.convertedValue }
        }
        if ($vinValue -and $vinValue.Length -ge 17) {
            Ok "VIN: $vinValue (latency: $($sw.ElapsedMilliseconds)ms)"
            $results['readDid'] = $true
        } else {
            Bad "no VIN ASCII in subfields · raw: $($r.Content.Substring(0, [math]::Min(200, $r.Content.Length)))"
            $results['readDid'] = $false
        }
    } catch {
        $sw.Stop()
        Bad "readDID failed: $($_.Exception.Message)"
        $results['readDid'] = $false
    }

    # ---------- Check 5: read_did_batch ----------
    Section "5. read_did_batch [F190, F18C, F1A4] @ BCM 0x726"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $body = '{"nodeAddress":"0x726","dids":["0xF190","0xF18C","0xF1A4"]}'
        $r = Invoke-WebRequest -Uri "http://localhost:18082/commands/readDIDBatch" -Method POST -Body $body -ContentType "application/json" -TimeoutSec 60 -UseBasicParsing -ErrorAction Stop
        $sw.Stop()
        $resp = $r.Content | ConvertFrom-Json
        if ($resp.result -and $resp.result.reads) {
            $populated = ($resp.result.reads | Where-Object { $_.ok -eq $true -and $_.result }).Count
            Key "populated DIDs" "$populated of $($resp.result.reads.Count)"
            Key "latency" "$($sw.ElapsedMilliseconds)ms"
            if ($populated -ge 2) {
                Ok "batch returned $populated populated DIDs"
                $results['readDidBatch'] = $true
            } else {
                Bad "expected at least 2 populated DIDs (VIN + ECU_SERIAL), got $populated"
                $results['readDidBatch'] = $false
            }
        } else {
            Bad "unexpected response shape · raw: $($r.Content.Substring(0, [math]::Min(200, $r.Content.Length)))"
            $results['readDidBatch'] = $false
        }
    } catch {
        $sw.Stop()
        Bad "readDIDBatch failed: $($_.Exception.Message)"
        $results['readDidBatch'] = $false
    }
}

# ---------- Check 6: olympus boots without module-not-found ----------
Section "6. Olympus boot (no module-not-found)"
if (-not $olympusExe) {
    Warn "olympus not on PATH — skipping boot check"
    $results['boot'] = $false
} else {
    $bundleJs = Join-Path $HOME ".olympus\bundle\cli\olympus.js"
    if (-not (Test-Path $bundleJs)) {
        Bad "bundle olympus.js not found at $bundleJs"
        $results['boot'] = $false
    } else {
        # Run --version with stderr captured to catch module-not-found
        $stderrFile = Join-Path $env:TEMP "olympus-smoke-stderr.txt"
        $proc = Start-Process -FilePath "node" -ArgumentList @($bundleJs, "--version") -RedirectStandardError $stderrFile -RedirectStandardOutput "NUL" -PassThru -NoNewWindow -Wait
        $stderr = Get-Content $stderrFile -Raw -ErrorAction SilentlyContinue
        Remove-Item $stderrFile -ErrorAction SilentlyContinue
        if ($proc.ExitCode -eq 0 -and -not ($stderr -match "Cannot find module")) {
            Ok "olympus boots clean (no module-not-found)"
            $results['boot'] = $true
        } else {
            Bad "boot failed · exit=$($proc.ExitCode) · stderr: $($stderr -replace "`n", " · " | ForEach-Object { $_.Substring(0, [math]::Min(300, $_.Length)) })"
            $results['boot'] = $false
        }
    }
}

# ---------- Final diagnosis ----------
Write-Host ""
Write-Host "=== SMOKE RESULTS ===" -ForegroundColor Magenta
$total = $results.Count
$passed = ($results.Values | Where-Object { $_ -eq $true }).Count
foreach ($k in @('version','bridge','vehicle','readDid','readDidBatch','boot')) {
    if ($results.ContainsKey($k)) {
        $sigil = if ($results[$k]) { "✓" } else { "✗" }
        $color = if ($results[$k]) { "Green" } else { "Red" }
        Write-Host ("  {0} {1}" -f $sigil, $k) -ForegroundColor $color
    }
}
Write-Host ""

if ($passed -eq $total) {
    Write-Host "🎯 GOLDEN · all $total checks passed · v0.9.0 bundle is healthy end-to-end" -ForegroundColor Green
    Write-Host "Report this output to coord PR #2484 for sign-off." -ForegroundColor DarkGray
    exit 0
} else {
    Write-Host "✗ FAILED · $($total - $passed) of $total checks failed" -ForegroundColor Red
    Write-Host "Paste this output back to coord PR #2484 for diagnosis." -ForegroundColor DarkGray
    exit 1
}
