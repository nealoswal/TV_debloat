<#
  Backs up the TV's package/settings state, then disables every package in
  debloat-list.txt with "pm disable-user". Nothing is uninstalled.

  Usage:
    powershell -ExecutionPolicy Bypass -File apply.ps1 -TvIp 192.168.1.50 -DryRun
    powershell -ExecutionPolicy Bypass -File apply.ps1 -TvIp 192.168.1.50

  Needs adb on PATH (or ADB=<path>), network debugging enabled on the TV and this PC
  authorised (accept the "Allow debugging?" prompt on the TV the first time).
  The list was made for a Sony BRAVIA 2015 on Android TV 8.0: review it for your model.
#>
param(
    [Parameter(Mandatory = $true)][string]$TvIp,
    [switch]$DryRun
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = if ($env:ADB) { $env:ADB } else { (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { throw "adb not found. Install Android platform-tools or set ADB=<path to adb>." }

$dev = "${TvIp}:5555"
& $adb connect $dev | Out-Host
function Adb([string[]]$a) { & $adb -s $dev @a }
# Stop here rather than misreading an unreachable TV as one with no packages.
$state = try { & $adb -s $dev get-state 2>$null } catch { "" }
if ($state -ne "device") {
    throw "Can't reach $dev. Is the TV on, with network debugging enabled and this PC authorised?"
}

$list = Get-Content (Join-Path $here "debloat-list.txt") | ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and -not $_.StartsWith("#") }
$installed = Adb @("shell", "pm", "list", "packages") | ForEach-Object { ($_ -replace "^package:", "").Trim() }
$targets = $list | Where-Object { $installed -contains $_ }
$missing = $list | Where-Object { $installed -notcontains $_ }
if ($missing) { Write-Host "Not on this TV (skipped): $($missing -join ', ')" }

if ($DryRun) {
    Write-Host "Would disable $($targets.Count) package(s):"
    $targets | ForEach-Object { Write-Host "  $_" }
    return
}

# Backup first: the restore script re-enables exactly what was enabled here.
$backup = Join-Path $here ("backup\" + (Get-Date -Format "yyyyMMdd-HHmmss"))
New-Item -ItemType Directory -Force $backup | Out-Null
Adb @("shell", "pm", "list", "packages", "-e") | ForEach-Object { ($_ -replace "^package:", "").Trim() } |
    Where-Object { $_ } | Sort-Object | Set-Content -Encoding utf8 (Join-Path $backup "enabled.list")
Adb @("shell", "pm", "list", "packages", "-d") | ForEach-Object { ($_ -replace "^package:", "").Trim() } |
    Where-Object { $_ } | Sort-Object | Set-Content -Encoding utf8 (Join-Path $backup "disabled.list")
foreach ($t in "global", "secure", "system") {
    Adb @("shell", "settings", "list", $t) | Set-Content -Encoding utf8 (Join-Path $backup "settings-$t.txt")
}
Adb @("shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.HOME",
      "-a", "android.intent.action.MAIN") | Select-Object -Last 1 | Set-Content -Encoding utf8 (Join-Path $backup "default-home.txt")
Write-Host "Backup saved to $backup (keep it: it may contain device identifiers, don't publish it)"

foreach ($p in $targets) {
    $r = Adb @("shell", "pm", "disable-user", "--user", "0", $p)
    Write-Host "  $r"
}
Write-Host "Done. Undo everything with: restore.ps1 -TvIp $TvIp"
