# olympus-install.ps1 -- bundle-based installer for the olympus CLI on Windows.
#
# Usage (PowerShell 5.1 on Windows):
#   irm https://streaming-api.futureai.com/api/v1/install/olympus.ps1 | iex
#
# Flow (no git, no source access -- fully bundled):
#   1. Proxy autodetect (IE settings -> $env:HTTPS_PROXY)
#   2. Node 20+ prereq (winget install if missing)
#   3. Prompt email -> send magic-link code to tech's inbox
#   4. Prompt 6-digit code -> verify -> get JWT
#   5. Download bundle zip (JWT-gated) from streaming-api.futureai.com
#   6. Extract to $OLYMPUS_PREFIX\bundle
#   7. Write .cmd shims on PATH + broadcast env change
#   8. Deploy FDRS Layer 2 bridge JAR (UAC prompt for Program Files)
#   9. Persist auth tokens to ~/.olympus/auth.json so CLI is pre-signed-in
#  10. Run olympus doctor + show welcome
#
# Env var overrides (set before running):
#   $env:OLYMPUS_API_BASE     backend URL (default: https://streaming-api.futureai.com)
#   $env:OLYMPUS_PREFIX       install root (default: $HOME\.olympus)
#   $env:OLYMPUS_BIN_DIR      shim dir (default: $HOME\.local\bin)
#   $env:OLYMPUS_SKIP_WINGET  skip winget auto-install of node (default: 0)
#   $env:OLYMPUS_LOGIN_EMAIL  pre-set email for non-interactive auth (skips Read-Host)
#   $env:OLYMPUS_LOGIN_CODE   pre-set 6-digit code for non-interactive auth.
#                             When BOTH are set, the script skips its
#                             send-login-code call (the wrapper is expected
#                             to have done that out-of-band -- otherwise
#                             the fresh send would invalidate the wrapper's
#                             code). Both must be set or both unset.

$ErrorActionPreference = "Stop"

# Crystal-ball emoji built at runtime from scalar (U+1F52E). File stays
# pure ASCII so PowerShell 5.1's Windows-1252 read of non-BOM UTF-8 from
# raw.githubusercontent.com doesn't mangle the glyph.
$CrystalBall = [System.Char]::ConvertFromUtf32(0x1F52E)

# ---------- config ----------
# Phase 1.5 (channel split, 2026-05-06) · channel selection at install time.
#
# Three channels:
#   production - default · what every tech in the field gets · streaming-api.futureai.com
#   preview    - opt-in  · bench machines, soak channel       · streaming-api-preview.futureai.com
#   dev        - local-dev workstations · normally bypasses the installer entirely
#
# Resolution order:
#   1. $env:OLYMPUS_CHANNEL if set (e.g., $env:OLYMPUS_CHANNEL='preview' before iwr | iex)
#   2. ~/.olympus/channel.txt if present (preserves channel across reinstalls and autoupdate)
#   3. Default: production
#
# $env:OLYMPUS_API_BASE always wins if set (used for local-dev pointing at
# http://localhost:8080). Otherwise the API base resolves from channel.
$Prefix  = if ($env:OLYMPUS_PREFIX)   { $env:OLYMPUS_PREFIX }   else { Join-Path $HOME ".olympus" }
$ChannelFile = Join-Path $Prefix "channel.txt"
$Channel = if ($env:OLYMPUS_CHANNEL) {
    $env:OLYMPUS_CHANNEL
} elseif (Test-Path $ChannelFile) {
    $persisted = (Get-Content -Path $ChannelFile -Raw -ErrorAction SilentlyContinue)
    if ($persisted) { $persisted.Trim() } else { "production" }
} else {
    "production"
}
if ($Channel -notin @("production", "preview", "dev")) {
    Write-Host "[warn] OLYMPUS_CHANNEL='$Channel' is not one of production/preview/dev. Falling back to production." -ForegroundColor Yellow
    $Channel = "production"
}
$ChannelHostMap = @{
    "production" = "https://streaming-api.futureai.com"
    "preview"    = "https://streaming-api-preview.futureai.com"
    "dev"        = "https://streaming-api.futureai.com"
}
$ApiBase = if ($env:OLYMPUS_API_BASE) {
    $env:OLYMPUS_API_BASE
} else {
    $ChannelHostMap[$Channel]
}
$BinDir  = if ($env:OLYMPUS_BIN_DIR)  { $env:OLYMPUS_BIN_DIR }  else { Join-Path $HOME ".local\bin" }
$BundleDir = Join-Path $Prefix "bundle"
$AuthFile  = Join-Path $Prefix "auth.json"
$BundleJs  = Join-Path $BundleDir "cli\olympus.js"

# ---------- output helpers (plain ASCII so raw-github doesn't mangle glyphs) ----------
function Say($msg)  { Write-Host "[olympus] $msg" -ForegroundColor Cyan }
function Note($msg) { Write-Host "          $msg" -ForegroundColor DarkGray }
function Ok($msg)   { Write-Host "[ok]      $msg" -ForegroundColor Green }
function Warn($msg) { Write-Host "[warn]    $msg" -ForegroundColor Yellow }
function Die($msg)  { Write-Host "[FAIL]    $msg" -ForegroundColor Red; exit 1 }

# Refresh-Path: rebind current process's $env:PATH from Machine+User scopes
# so binaries installed mid-script resolve in this PS session without a new
# window.
function Refresh-Path {
    $m = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $u = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:PATH = @($m, $u) -ne $null -join ';'
}

# Broadcast WM_SETTINGCHANGE so Explorer + new shells see PATH changes.
function Broadcast-EnvChange {
    try {
        if (-not ([System.Management.Automation.PSTypeName]'Olympus.NativeMethods').Type) {
            Add-Type -Namespace Olympus -Name NativeMethods -MemberDefinition @'
[System.Runtime.InteropServices.DllImport("user32.dll", SetLastError=true, CharSet=System.Runtime.InteropServices.CharSet.Auto)]
public static extern System.IntPtr SendMessageTimeout(
    System.IntPtr hWnd, uint Msg, System.UIntPtr wParam, string lParam,
    uint fuFlags, uint uTimeout, out System.UIntPtr lpdwResult);
'@
        }
        $r = [System.UIntPtr]::Zero
        [void][Olympus.NativeMethods]::SendMessageTimeout(
            [System.IntPtr]0xffff, 0x1A, [System.UIntPtr]::Zero,
            "Environment", 2, 5000, [ref]$r)
    } catch {}
}

# ---------- STAGE A: proxy autodetect ----------
Say "Detecting proxy"
try {
    $ie = Get-ItemProperty -Path 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings' -ErrorAction Stop
    if ($ie.ProxyEnable -eq 1 -and $ie.ProxyServer) {
        $raw = [string]$ie.ProxyServer
        $url = if ($raw -match 'https=([^;]+)')    { "http://$($matches[1])" }
               elseif ($raw -match 'http=([^;]+)') { "http://$($matches[1])" }
               elseif ($raw -match '^https?://')   { $raw }
               else                                 { "http://$raw" }
        if (-not $env:HTTPS_PROXY) { $env:HTTPS_PROXY = $url }
        if (-not $env:HTTP_PROXY)  { $env:HTTP_PROXY  = $url }
        Note "using $url (from IE settings)"
    } elseif ($env:HTTPS_PROXY -or $env:HTTP_PROXY) {
        Note "using existing `$env:HTTPS_PROXY"
    } else {
        Note "no proxy configured (direct connection)"
    }
} catch {
    Note "skipped (no proxy detected)"
}

# ---------- STAGE B: Node prereq (no git, no npm needed -- bundle is pre-built) ----------
Say "Checking Node 20+"
$WingetAvailable = $null -ne (Get-Command winget -ErrorAction SilentlyContinue)
$SkipWinget = ($env:OLYMPUS_SKIP_WINGET -eq '1')

$nodePresent = $null -ne (Get-Command node -ErrorAction SilentlyContinue)
if (-not $nodePresent) {
    if ($SkipWinget) {
        Die "node not found and OLYMPUS_SKIP_WINGET=1. Install Node 20+ from https://nodejs.org/ then re-run."
    }
    if (-not $WingetAvailable) {
        Die "node not found and winget not available. Install Node 20+ from https://nodejs.org/ then re-run."
    }
    Note "node missing -> winget install OpenJS.NodeJS.LTS"
    try {
        $p = Start-Process -FilePath 'winget' -ArgumentList @(
            'install', '--silent', '--id', 'OpenJS.NodeJS.LTS', '-e',
            '--accept-package-agreements', '--accept-source-agreements',
            '--disable-interactivity'
        ) -NoNewWindow -Wait -PassThru -ErrorAction Stop
        if ($p.ExitCode -ne 0 -and $p.ExitCode -ne -1978335189) {
            Warn "winget install exited $($p.ExitCode) -- continuing in case node is still available"
        }
    } catch {
        Die "winget install failed: $($_.Exception.Message). Install Node 20+ from https://nodejs.org/"
    }
    Refresh-Path
    if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
        Die "node still not on PATH after winget install. Open a new PowerShell and re-run."
    }
}

# Verify major >= 20. parseInt(process.versions.node) -- no inner quoting.
$nodeMajor = [int](node -e "process.stdout.write(String(parseInt(process.versions.node)))")
if ($nodeMajor -lt 20) { Die "node $((node --version)) is too old (need 20+). Upgrade: https://nodejs.org/" }
Note "node $(node --version)"

# ---------- STAGE C: magic-link sign-in ----------
# Auth BEFORE download so the bundle endpoint can JWT-gate the response.
# Matches the existing olympus CLI login flow (send-login-code -> verify).
#
# Two paths:
#   1. Interactive (default): Read-Host email -> POST send-login-code ->
#      Read-Host the 6-digit code from the tech's inbox.
#   2. Non-interactive (wrapper agents, scripted onboarding): caller pre-sets
#      $env:OLYMPUS_LOGIN_EMAIL + $env:OLYMPUS_LOGIN_CODE having already
#      obtained the code out-of-band (e.g. POSTed send-login-code itself
#      and read the resulting email). The script SKIPS its own
#      send-login-code call in this path -- otherwise the freshly-issued
#      code would invalidate the wrapper's. (This is the exact failure mode
#      session-N hit during McGraw bench #2 onboarding 2026-05-01: the first
#      patched run sent a fresh code, invalidating the wrapper-obtained one.)
#
# In a non-interactive PowerShell host with neither env var set we Die
# with explicit instructions instead of hanging on Read-Host (the
# `irm | iex` failure mode that the McGraw 2 wrapper had to work around
# by patching the script in flight).
Say "Sign in to futureai.com"

$skipAuth = $env:GITHUB_ACTIONS -or $env:CI -or $env:OLYMPUS_SKIP_LOGIN
if ($skipAuth) {
    Die "auth skip env set but this installer REQUIRES auth to download the bundle. Unset `$env:OLYMPUS_SKIP_LOGIN and re-run."
}

$envEmail = $env:OLYMPUS_LOGIN_EMAIL
$envCode  = $env:OLYMPUS_LOGIN_CODE
$nonInteractive = [Console]::IsInputRedirected -or -not [Environment]::UserInteractive

if ($envEmail -and $envCode) {
    # Non-interactive path: wrapper obtained the code out-of-band.
    $email = $envEmail
    $code  = ($envCode -replace '\D', '')
    Note "email + code from `$env:OLYMPUS_LOGIN_EMAIL/`$env:OLYMPUS_LOGIN_CODE (skipping send-login-code; assuming code obtained out-of-band)"
} elseif ($envEmail -or $envCode) {
    Die "OLYMPUS_LOGIN_EMAIL and OLYMPUS_LOGIN_CODE must both be set or both unset (got email=$([bool]$envEmail), code=$([bool]$envCode)). Either set both for non-interactive auth, or unset both for the interactive prompt."
} elseif ($nonInteractive) {
    Die "non-interactive PowerShell detected and login env vars are unset. Either run from an interactive PS host, OR set `$env:OLYMPUS_LOGIN_EMAIL=you@futureai.com plus `$env:OLYMPUS_LOGIN_CODE=<6-digit code> before re-running. The 6-digit code is obtained out-of-band by POSTing email to $ApiBase/api/v1/auth/send-login-code."
} else {
    # Interactive path.
    $email = Read-Host "  your futureai.com email"
    if (-not $email) { Die "email required" }

    # Step 1: request a code via email
    try {
        $body = @{ email = $email } | ConvertTo-Json -Compress
        $resp = Invoke-RestMethod -Uri "$ApiBase/api/v1/auth/send-login-code" `
            -Method POST -Body $body -ContentType 'application/json' `
            -TimeoutSec 15 -ErrorAction Stop
        Note "verification code sent to $email (check your inbox, valid 10 minutes)"
    } catch {
        $msg = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
        Die "failed to send login code: $msg"
    }

    # Step 2: read code from interactive input
    $code = Read-Host "  6-digit code from email"
    $code = ($code -replace '\D', '')
}
if ($code.Length -ne 6) { Die "invalid code (expected 6 digits, got $($code.Length))" }

try {
    $body = @{ email = $email; code = $code } | ConvertTo-Json -Compress
    $resp = Invoke-RestMethod -Uri "$ApiBase/api/v1/auth/verify-login-code" `
        -Method POST -Body $body -ContentType 'application/json' `
        -TimeoutSec 15 -ErrorAction Stop
    # Server returns { user: {...}, tokens: { accessToken, refreshToken } }.
    # Flatten to the on-disk shape the CLI's AuthTokensOnDisk expects so
    # auth.json matches what auth-client.ts writes after a fresh login.
    if (-not $resp.tokens.accessToken -or -not $resp.tokens.refreshToken) {
        Die "verify returned no accessToken"
    }
    # Decode JWT exp (seconds since epoch) -> ms. base64url -> base64 first.
    $payloadB64 = $resp.tokens.accessToken.Split('.')[1].Replace('-','+').Replace('_','/')
    while ($payloadB64.Length % 4) { $payloadB64 += '=' }
    $expSec = ([System.Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($payloadB64)) | ConvertFrom-Json).exp
    $tokens = [PSCustomObject]@{
        accessToken       = $resp.tokens.accessToken
        refreshToken      = $resp.tokens.refreshToken
        email             = if ($resp.user.email) { $resp.user.email } else { $email }
        accessExpiresAtMs = [int64]$expSec * 1000
    }
    Ok "signed in as $($tokens.email)"
} catch {
    $msg = if ($_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { $_.Exception.Message }
    Die "failed to verify code: $msg"
}

# Persist auth so the CLI auto-logs-in on first run. WriteAllText with
# UTF8Encoding(false) avoids PS 5.1's BOM, which Node's JSON.parse rejects
# as malformed at byte 0.
New-Item -ItemType Directory -Path $Prefix -Force | Out-Null
$authJson = $tokens | ConvertTo-Json
[System.IO.File]::WriteAllText($AuthFile, $authJson, [System.Text.UTF8Encoding]::new($false))
Note "saved auth to $AuthFile"

# Phase 1.5 · persist the channel choice so reinstalls + autoupdates
# stay on the chosen channel without the user having to re-set
# $env:OLYMPUS_CHANNEL each time.
[System.IO.File]::WriteAllText($ChannelFile, $Channel, [System.Text.UTF8Encoding]::new($false))
Note "channel: $Channel ($ApiBase)"

# ---------- STAGE D: download bundle ----------
Say "Downloading olympus bundle"
$bundleZip = Join-Path $env:TEMP "olympus-bundle.zip"

# Step 1 · fetch the expected SHA-256 BEFORE the zip. This is the
# integrity gate that replaced JWT-gating on 2026-05-08. Trust path is
# HTTPS to streaming-api.futureai.com — TLS proves the digest came
# from a server holding the cert, and the cert is pinned to the
# domain. Match this against the downloaded zip's hash before
# extracting.
$expectedSha256 = $null
try {
    $shaResp = Invoke-WebRequest -Uri "$ApiBase/api/v1/install/olympus/bundle.sha256" `
        -TimeoutSec 30 -UseBasicParsing -ErrorAction Stop
    $expectedSha256 = ($shaResp.Content | Out-String).Trim().ToLower()
    if ($expectedSha256 -notmatch '^[0-9a-f]{64}$') {
        Die "bundle.sha256 returned malformed digest: '$expectedSha256'"
    }
    Note "expected sha256: $($expectedSha256.Substring(0, 16))..."
} catch {
    Die "bundle.sha256 fetch failed: $($_.Exception.Message)"
}

# Step 2 · download the bundle. No Authorization header — bundle is
# public (integrity verified via sha256 above, not transport auth).
try {
    Invoke-WebRequest -Uri "$ApiBase/api/v1/install/olympus/bundle.zip" `
        -OutFile $bundleZip `
        -TimeoutSec 300 -UseBasicParsing -ErrorAction Stop
    $sizeMb = [math]::Round((Get-Item $bundleZip).Length / 1MB, 1)
    Note "downloaded $sizeMb MB -> $bundleZip"
} catch {
    $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
    Die "bundle download failed (status=$status): $($_.Exception.Message)"
}

# Step 3 · verify the downloaded bytes match. PS 5.1 ships Get-FileHash
# built-in. Mismatch = either the download was tampered in transit
# (TLS already prevents this for streaming-api.futureai.com), the
# server-side bundle is being rotated mid-download, or the file
# corrupted on disk. Either way, refuse to extract.
$actualSha256 = (Get-FileHash -Path $bundleZip -Algorithm SHA256).Hash.ToLower()
if ($actualSha256 -ne $expectedSha256) {
    Remove-Item -Path $bundleZip -Force -ErrorAction SilentlyContinue
    Die "bundle integrity check failed -- expected $expectedSha256 but got $actualSha256"
}
Note "sha256 verified ($($actualSha256.Substring(0, 16))...)"

# ---------- STAGE E: extract ----------
Say "Extracting bundle"
if (Test-Path $BundleDir) { Remove-Item -Path $BundleDir -Recurse -Force -ErrorAction SilentlyContinue }
New-Item -ItemType Directory -Path $BundleDir -Force | Out-Null
try {
    # PERF: Use .NET ZipFile.ExtractToDirectory instead of Expand-Archive.
    # On 113 MB bundles with ~thousands of small files in node_modules,
    # PS 5.1's Expand-Archive takes 5-15 minutes (single-threaded, per-file
    # pipeline overhead). .NET's managed API completes the same extract in
    # 30-90 seconds because it uses a single managed stream and avoids the
    # PowerShell object pipeline. Drop-in functional replacement.
    Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction Stop
    [System.IO.Compression.ZipFile]::ExtractToDirectory($bundleZip, $BundleDir)
    if (-not (Test-Path $BundleJs)) { Die "extracted bundle missing expected $BundleJs -- corrupted download?" }
    Ok "extracted to $BundleDir"
} catch {
    Die "extract failed: $($_.Exception.Message)"
} finally {
    Remove-Item -Path $bundleZip -Force -ErrorAction SilentlyContinue
}

# ---------- STAGE E.1: install externalized runtime deps ----------
# build-cli.js externalizes ~26 packages (AWS/Anthropic/MCP/Prisma/etc)
# so the bundle ships ~75 MB instead of ~400 MB. Node resolves them at
# runtime via node_modules/ adjacent to the bundle.
#
# 2026-04-28 (#1916): trust the bundle's package.json if build-cli.js
# wrote one. The legacy fallback below was the v0.7.2 ship-day root cause:
# the inline manifest pinned @prisma/client to a wildcard and clobbered
# the bundle's pinned manifest, causing install-time npm to float prisma
# to v7.x while the bundle was generated for ^6.19.1. The bundle's
# manifest is now canonical -- generated from backend/package.json pins
# by writeBundleRuntimePackageJson() with a `prisma generate` postinstall.
# Only fall back to the inline manifest for legacy bundles that predate
# this change (no package.json shipped); the fallback now pins prisma
# concretely instead of using a wildcard.
Say "Installing runtime dependencies (one-time, ~25s)"
$bundlePkgJson = Join-Path $BundleDir "package.json"
if (-not (Test-Path $bundlePkgJson)) {
    Say "Bundle did not ship a package.json -- writing legacy fallback (no postinstall, no prisma generate)"
    $bundlePkgContent = @'
{
  "name": "olympus-bundle-runtime",
  "version": "0.0.0",
  "private": true,
  "dependencies": {
    "@anthropic-ai/sdk": "*",
    "@anthropic-ai/sandbox-runtime": "*",
    "@aws-sdk/client-bedrock-runtime": "*",
    "@aws-sdk/client-cloudwatch-logs": "*",
    "@aws-sdk/client-secrets-manager": "*",
    "@commander-js/extra-typings": "*",
    "@google-cloud/secret-manager": "*",
    "@modelcontextprotocol/sdk": "*",
    "@prisma/client": "^6.19.1",
    "prisma": "^6.9.0",
    "bufferutil": "*",
    "encoding": "*",
    "ioredis": "*",
    "utf-8-validate": "*"
  }
}
'@
    [System.IO.File]::WriteAllText($bundlePkgJson, $bundlePkgContent, [System.Text.UTF8Encoding]::new($false))
} else {
    Ok "Using bundle's pinned package.json (prisma generate runs as postinstall)"
}
# Prefer npm.cmd (cmd shim) over npm (which PowerShell resolves to npm.ps1
# and gets blocked by default Restricted execution policy on most tech
# machines). cmd shim runs through cmd.exe and bypasses the policy entirely.
$npmExe = $null
foreach ($cand in @('npm.cmd','npm.exe')) {
    $found = Get-Command $cand -ErrorAction SilentlyContinue
    if ($found) { $npmExe = $found.Source; break }
}
if (-not $npmExe) {
    # Fall back to bare npm but force a Bypass scope on this process so npm.ps1 runs.
    Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force -ErrorAction SilentlyContinue
    $found = Get-Command npm -ErrorAction SilentlyContinue
    if ($found) { $npmExe = $found.Source }
}
if (-not $npmExe) { Die "npm not on PATH (Node should have installed it). Open a new PowerShell and re-run." }
Push-Location $BundleDir
try {
    # IMPORTANT: do NOT use `2>&1` on a native exe in PS 5.1 -- it wraps each
    # stderr line as a NativeCommandError ErrorRecord, and with the script's
    # global $ErrorActionPreference="Stop" even a benign `npm notice` halts
    # the install. Let npm's stderr stream straight to the host; we still
    # check $LASTEXITCODE for actual failure.
    & $npmExe install --no-audit --no-fund --omit=dev --silent
    if ($LASTEXITCODE -ne 0) { Die "npm install failed (exit $LASTEXITCODE). Re-run with: cd `"$BundleDir`"; npm.cmd install" }
    if (-not (Test-Path (Join-Path $BundleDir "node_modules\@anthropic-ai\sdk"))) {
        Die "npm install completed but @anthropic-ai/sdk not in node_modules -- check npm logs"
    }
    Ok "runtime deps installed"
} finally {
    Pop-Location
}

# ---------- STAGE E.2: bundle hotfixes ----------
# K's v0.2.7 build was cut from a source tree predating the
# `exitOnCtrlC: false` fix in diagnostic-chat-entry.ts:238. Patch the
# bundled file in place so single Ctrl-C aborts the in-flight stream
# (with the §7 red flash) instead of exiting the TUI. Idempotent.
$diagChatJs = Join-Path $BundleDir "cli\tui\diagnostic-chat.js"
if (Test-Path $diagChatJs) {
    $diagText = [System.IO.File]::ReadAllText($diagChatJs)
    if ($diagText.Contains("await render2(element);")) {
        $patched = $diagText.Replace("await render2(element);", "await render2(element, { exitOnCtrlC: false });")
        [System.IO.File]::WriteAllBytes($diagChatJs, [System.Text.UTF8Encoding]::new($false).GetBytes($patched))
        Note "applied Ctrl-C safety patch (single-press aborts; /exit or Ctrl-D to leave)"
    }
}

# ---------- STAGE F: shims + PATH live-inject ----------
Say "Installing olympus + crystal-ball shims"
New-Item -ItemType Directory -Path $BinDir -Force | Out-Null

function Write-Shim($name, $bundlePath, $targetDir) {
    $shimPath = Join-Path $targetDir "$name.cmd"
    # v0.7.7.32 · `chcp 65001 >nul 2>&1` switches cmd.exe to UTF-8 before
    # spawning node so Ink TUI emojis (crystal ball, box-drawing, em dashes)
    # render as their actual characters instead of cp1252 mojibake (≡ƒö« for
    # 🔮, Γöé for │, etc.). When the console renders 4 chars where Ink wrote
    # a 2-column emoji, every cursor-up + clear-line + redraw lands on wrong
    # columns → typed chars scatter across rows and the live region overlaps
    # the transcript. UTF-8 console makes display widths match Ink's column
    # math → cursor positioning correct → no race. Idempotent: safe to run
    # on consoles already in 65001.
    $body = "@echo off`r`nchcp 65001 >nul 2>&1`r`nif not defined OLYMPUS_TRACKER_URL set OLYMPUS_TRACKER_URL=https://streaming-api.futureai.com`r`nnode `"$bundlePath`" %*`r`n"
    Set-Content -Path $shimPath -Value $body -Encoding ASCII -NoNewline
    Note "shim: $shimPath"
}

Write-Shim "olympus"    $BundleJs $BinDir
Write-Shim $CrystalBall $BundleJs $BinDir

Set-Content -Path (Join-Path $Prefix ".installed-by-olympus-ps1") -Value "1" -Encoding ASCII

# User-PATH append + broadcast + session refresh
$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$userPathDirs = if ($userPath) { $userPath -split ';' } else { @() }
if (-not ($userPathDirs -contains $BinDir)) {
    $newUserPath = if ($userPath) { "$BinDir;$userPath" } else { $BinDir }
    [Environment]::SetEnvironmentVariable('Path', $newUserPath, 'User')
    Note "added $BinDir to User PATH (persisted)"
} else {
    Note "$BinDir already on User PATH"
}
Refresh-Path
if ($env:PATH -notmatch [regex]::Escape($BinDir)) { $env:PATH = "$BinDir;$env:PATH" }
Broadcast-EnvChange
Ok "shims installed + PATH refreshed"

# ---------- STAGE G: FDRS Layer 2 bridge deploy ----------
Say "Deploying FDRS Layer 2 bridge"
try {
    $fdrsInstallDir = $null
    foreach ($k in @('HKLM:\SOFTWARE\WOW6432Node\Ford Motor Company\FDRS', 'HKLM:\SOFTWARE\Ford Motor Company\FDRS')) {
        try {
            $v = Get-ItemProperty -Path $k -ErrorAction Stop
            if ($v.InstallLocation -and (Test-Path $v.InstallLocation)) { $fdrsInstallDir = $v.InstallLocation; break }
        } catch {}
    }
    if (-not $fdrsInstallDir) {
        foreach ($c in @('C:\Program Files (x86)\Ford Motor Company\FDRS', 'C:\Program Files\Ford Motor Company\FDRS')) {
            if (Test-Path $c) { $fdrsInstallDir = $c; break }
        }
    }
    if (-not $fdrsInstallDir) {
        Note "FDRS not detected -- bridge will deploy next time you install/run FDRS"
    } else {
        Note "FDRS found at $fdrsInstallDir"
        # JAR location is layout-dependent — K's build-cli.js (#1797) puts it at
        # $BundleDiridrs-layer2-bridge, the legacy hand-zip put it at
        # $BundleDirdrs-layer2-bridge. Recursive search covers both so future
        # build-pipeline layout shifts don't break the FDRS deploy step again.
        $jarSrc = Get-ChildItem -Path $BundleDir -Recurse -Filter "futureai-fdrs-layer2-bridge-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $jarSrc) {
            Warn "bundle missing FDRS Layer 2 JAR -- skipping Layer 2 deploy"
        } else {
            $bundleFdrsDir = Join-Path $fdrsInstallDir "bundle"
            $jarDst = Join-Path $bundleFdrsDir $jarSrc.Name

            # Probe writability
            $needsElevation = $false
            if (Test-Path $bundleFdrsDir) {
                $probe = Join-Path $bundleFdrsDir ".olympus-write-probe"
                try {
                    New-Item -Path $probe -ItemType File -Force -ErrorAction Stop | Out-Null
                    Remove-Item -Path $probe -Force -ErrorAction SilentlyContinue
                } catch {
                    $needsElevation = $true
                }
            } else {
                Warn "FDRS bundle dir not present at $bundleFdrsDir -- skipping"
                $needsElevation = $null  # signal skip
            }

            if ($needsElevation -eq $true) {
                Note "bundle dir needs admin -- prompting UAC for one elevated copy"
                try {
                    $ps = @(
                        '-NoProfile', '-NonInteractive', '-Command',
                        "Copy-Item -Path '$($jarSrc.FullName)' -Destination '$jarDst' -Force"
                    )
                    Start-Process -FilePath 'powershell' -ArgumentList $ps -Verb RunAs -Wait -ErrorAction Stop | Out-Null
                    if (Test-Path $jarDst) { Ok "Layer 2 JAR deployed (elevated)" } else { Warn "elevated copy completed but JAR not at destination" }
                } catch {
                    Warn "UAC declined or elevation failed: $($_.Exception.Message) -- JAR will load next time you run 'olympus fdrs-layer2 install' as admin"
                }
            } elseif ($needsElevation -eq $false) {
                Copy-Item -Path $jarSrc.FullName -Destination $jarDst -Force
                Ok "Layer 2 JAR deployed -> $jarDst"
            }
        }
    }
} catch {
    Warn "Layer 2 deploy threw: $($_.Exception.Message)"
}

# ---------- STAGE H: first-run marker + sanity ----------
Set-Content -Path (Join-Path $Prefix ".first-run") -Value "1" -Encoding ASCII

Say "Verifying"
$resolved = (Get-Command olympus -ErrorAction SilentlyContinue).Source
if (-not $resolved) {
    Warn "olympus not on PATH in this session -- open a new PowerShell, or run: node `"$BundleJs`" --version"
} else {
    Note "which olympus -> $resolved"
    & olympus --version
    if ($LASTEXITCODE -eq 0) { Ok "olympus --version works" } else { Warn "olympus --version returned non-zero" }
}

# ---------- done ----------
Say "Done"
Write-Host ""
Write-Host "Next steps" -ForegroundColor White
Write-Host "  olympus --help                   see available commands" -ForegroundColor Cyan
Write-Host "  olympus doctor                   preflight your environment (FDRS, Bedrock, auth)" -ForegroundColor Cyan
Write-Host "  olympus chat <VIN>               start a conversational diagnostic" -ForegroundColor Cyan
Write-Host "  $CrystalBall                                bare crystal-ball emoji -> VIN picker" -ForegroundColor Cyan
Write-Host ""
Write-Host "Signed in" -ForegroundColor White
Write-Host "  you are signed in as $($tokens.email) -- the CLI picks up the token from $AuthFile" -ForegroundColor DarkGray
Write-Host ""
Write-Host "Install location" -ForegroundColor White
Write-Host "  bundle:  $BundleDir" -ForegroundColor DarkGray
Write-Host "  shims:   $BinDir\olympus.cmd + $BinDir\$CrystalBall.cmd" -ForegroundColor DarkGray
Write-Host "  auth:    $AuthFile (protect like a password)" -ForegroundColor DarkGray
Write-Host ""
Write-Host "Update" -ForegroundColor White
Write-Host "  re-run: irm $ApiBase/api/v1/install/olympus.ps1 | iex" -ForegroundColor DarkGray
Write-Host "  uninstall: olympus uninstall --yes" -ForegroundColor DarkGray
Write-Host ""
