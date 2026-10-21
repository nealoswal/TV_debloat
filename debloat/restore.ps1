<#
  Restores the TV to the package state captured by apply.ps1's first backup.

  - Re-enables every package that was enabled at backup time and is disabled now
    (the debloated packages and, if you disabled it, Google's stock launcher).
  - Leaves alone packages that were already disabled before the backup.
  - Makes Google's stock launcher the default Home again.
  - Glass Home stays installed (just not default) unless you pass -RemoveGlassHome.

  Usage:
    powershell -ExecutionPolicy Bypass -File restore.ps1 -TvIp 192.168.1.50 -DryRun
    powershell -ExecutionPolicy Bypass -File restore.ps1 -TvIp 192.168.1.50 [-RemoveGlassHome]
#>
param(
    [Parameter(Mandatory = $true)][string]$TvIp,
    [string]$Backup = "",
    [switch]$RemoveGlassHome,
    [switch]$DryRun   # only list what would be re-enabled
)
$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$adb = if ($env:ADB) { $env:ADB } else { (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { throw "adb not found. Install Android platform-tools or set ADB=<path to adb>." }
if (-not $Backup) {
    # The oldest backup is the TV's original state.
    $Backup = Get-ChildItem (Join-Path $here "backup") -Directory | Sort-Object Name | Select-Object -First 1 -ExpandProperty FullName
}
$enabledFile = Join-Path $Backup "enabled.list"
if (-not (Test-Path $enabledFile)) { throw "Backup not found: $enabledFile" }
Write-Host "Using backup: $Backup"

$dev = "${TvIp}:5555"
& $adb connect $dev | Out-Host
function Adb([string[]]$a) { & $adb -s $dev @a }
# Stop here rather than misreading an unreachable TV as one with no packages.
$state = try { & $adb -s $dev get-state 2>$null } catch { "" }
if ($state -ne "device") {
    throw "Can't reach $dev. Is the TV on, with network debugging enabled and this PC authorised?"
}

$wasEnabled = Get-Content $enabledFile | Where-Object { $_ }
$nowDisabled = Adb @("shell", "pm", "list", "packages", "-d") | ForEach-Object { ($_ -replace "^package:", "").Trim() } | Where-Object { $_ }

$toEnable = $nowDisabled | Where-Object { $wasEnabled -contains $_ }
Write-Host "Re-enabling $($toEnable.Count) package(s)..."
if ($DryRun) { $toEnable | ForEach-Object { Write-Host "  would enable: $_" }; return }
foreach ($p in $toEnable) {
    $r = Adb @("shell", "pm", "enable", $p)
    Write-Host "  $r"
}

Write-Host "Restoring Google TV launcher as default Home..."
Adb @("shell", "cmd", "package", "set-home-activity", "com.google.android.tvlauncher/.MainActivity") | Out-Host

if ($RemoveGlassHome) {
    Write-Host "Removing Glass Home..."
    Adb @("uninstall", "app.glasshome.tv") | Out-Host
}

Adb @("shell", "input", "keyevent", "KEYCODE_HOME") | Out-Null

# Verify: anything enabled at backup time that is still disabled?
$still = Adb @("shell", "pm", "list", "packages", "-d") | ForEach-Object { ($_ -replace "^package:", "").Trim() } |
    Where-Object { $wasEnabled -contains $_ }
if ($still) { Write-Warning "Still disabled: $($still -join ', ')" } else { Write-Host "OK: package state matches the backup." }
Write-Host ("Home is now: " + (Adb @("shell", "cmd", "package", "resolve-activity", "--brief", "-c", "android.intent.category.HOME", "-a", "android.intent.action.MAIN") | Select-Object -Last 1))
