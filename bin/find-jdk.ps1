# Prints the path to the best java.exe (highest major version) or nothing. Used by launch.cmd.
# Pinned by JFR_AGENT_JAVA_HOME or "javaHome" in %USERPROFILE%\.jfr-agent\config.json.
$ErrorActionPreference = 'SilentlyContinue'
function Major($home_) {
  $rel = Join-Path $home_ 'release'
  if (-not (Test-Path $rel)) { return 0 }
  $line = Select-String -Path $rel -Pattern '^JAVA_VERSION="?(\d+)(?:\.(\d+))?' | Select-Object -First 1
  if (-not $line) { return 0 }
  $a = [int]$line.Matches[0].Groups[1].Value
  if ($a -eq 1 -and $line.Matches[0].Groups[2].Success) { return [int]$line.Matches[0].Groups[2].Value }
  return $a
}
$pinned = $env:JFR_AGENT_JAVA_HOME
$cfg = Join-Path $env:USERPROFILE '.jfr-agent\config.json'
if (-not $pinned -and (Test-Path $cfg)) { $pinned = (Get-Content $cfg -Raw | ConvertFrom-Json).javaHome }
if ($pinned -and (Test-Path (Join-Path $pinned 'bin\java.exe'))) { Write-Output (Join-Path $pinned 'bin\java.exe'); exit 0 }

$roots = @()
foreach ($v in 'ProgramFiles','ProgramFiles(x86)','LOCALAPPDATA') {
  $pf = [Environment]::GetEnvironmentVariable($v)
  if ($pf) { foreach ($vendor in 'Java','Eclipse Adoptium','Microsoft','Amazon Corretto','Zulu','BellSoft','Semeru','Programs\Eclipse Adoptium') { $roots += (Join-Path $pf $vendor) } }
}
$roots += (Join-Path $env:USERPROFILE '.jdks'), (Join-Path $env:USERPROFILE '.gradle\jdks'), (Join-Path $env:USERPROFILE 'scoop\apps\temurin-jdk'), (Join-Path $env:USERPROFILE '.sdkman\candidates\java')
$cands = @()
if ($env:JAVA_HOME) { $cands += $env:JAVA_HOME }
foreach ($r in $roots) { if (Test-Path $r) { $cands += (Get-ChildItem $r -Directory | ForEach-Object { $_.FullName }) } }
$onPath = (Get-Command java.exe -ErrorAction SilentlyContinue).Source
if ($onPath) { $cands += (Split-Path (Split-Path $onPath)) }
$best = 0; $bestHome = $null
foreach ($c in $cands) {
  if (-not (Test-Path (Join-Path $c 'bin\java.exe'))) { continue }
  $m = Major $c
  if ($m -gt $best) { $best = $m; $bestHome = $c }
}
if ($bestHome) { Write-Output (Join-Path $bestHome 'bin\java.exe') }
