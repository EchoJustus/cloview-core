<#
.SYNOPSIS
  cloview-core verification on Windows (JVM / CLR-WPF / Flutter).

.DESCRIPTION
  Runs every check that cannot run in Claude's Linux sandbox and packages the results
  into verify\cloview-windows-verification-results.zip (upload that file for review).

  Steps (each one is independent: a failing step, even one that throws, never stops the
  next one; a run stopped early with Ctrl+C still writes the summary and the zip):
    01  OS + toolchain diagnostics                     -> out\01-env-check.json
    02  JVM unit tests + static check                  -> out\02-jvm-unit-tests.log (+ .json)
    03  JVM cljfx off-screen smoke (Greeter + kitchen sink)
                                                       -> out\03-jvm-cljfx-smoke.json (+ .log)
    04  stage target\clr-src, build CloViewHost, CLR unit tests, WPF smoke
                                                       -> out\04-clr-build-and-run.log
                                                          out\04-clr-unit-tests.json
    05  WPF off-screen smoke report                    -> out\05-clr-wpf-smoke.json
    06  Flutter version check, ClojureDart compile + widget tests (Greeter)
                                                       -> out\06-flutter-compile-and-test.log
    00  step summary                                   -> out\00-summary.json

  The fitness app and its acceptance benchmark live in cloview-examples, which has its own
  harness (verify\run-windows-checks.ps1 there) built on the same helpers.

  Usage (from the repo root):
    powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1
    powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1 -SkipFlutter
    powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1 -SkipWpf -SkipFlutter   # JavaFX only
    powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1 -ClrTargetFramework net8.0-windows
    powershell -ExecutionPolicy Bypass -File .\verify\run-windows-checks.ps1 -FlutterUpgrade

  -FlutterUpgrade runs `flutter upgrade` first when the installed Flutter is older than
  3.35 (the minimum of ClojureDart 0.9.20261002). It changes your Flutter SDK, so it is
  never done without this switch.

.NOTES
  ASCII only, Windows PowerShell 5.1 compatible (also runs under PowerShell 7).
  SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later
#>
[CmdletBinding()]
param(
  [Alias('SkipJavaFX')][switch]$SkipJvm,   # steps 02-03 (JVM unit tests, JavaFX smoke)
  [Alias('SkipWpf')][switch]$SkipClr,       # steps 04-05 (CloViewHost, CLR unit tests, WPF smoke)
  [switch]$SkipFlutter,
  [switch]$FlutterUpgrade,
  [string]$ClrTargetFramework = "",
  [switch]$NoZip
)

$ErrorActionPreference = 'Continue'
$ProgressPreference = 'SilentlyContinue'

# Environment changes below are undone at the end (the script may run inside your session).
$SavedEnv = @{}
foreach ($n in @('DOTNET_CLI_TELEMETRY_OPTOUT', 'DOTNET_NOLOGO', 'DOTNET_SKIP_FIRST_TIME_EXPERIENCE',
                 'JAVA_TOOL_OPTIONS')) {
  $SavedEnv[$n] = [Environment]::GetEnvironmentVariable($n, 'Process')
}
$SavedConsoleEncoding = $null
try { $SavedConsoleEncoding = [Console]::OutputEncoding } catch { }
function Restore-Session {
  foreach ($n in $SavedEnv.Keys) { [Environment]::SetEnvironmentVariable($n, $SavedEnv[$n], 'Process') }
  if ($null -ne $SavedConsoleEncoding) { try { [Console]::OutputEncoding = $SavedConsoleEncoding } catch { } }
}

# 00 - summary + zip. Called from the finally block at the end, so a run stopped early
# (Ctrl+C, or an exception between steps) still leaves 00-summary.json and the zip of what
# it produced, marked "aborted".
$RunCompleted = $false
function Complete-Run {
  if (($null -eq $Summary) -or ($null -eq $OutDir) -or -not (Test-Path -LiteralPath $OutDir)) { return }
  if (-not $RunCompleted -and -not $Summary.Contains('aborted')) {
    $Summary.aborted = 'stopped before the end (Ctrl+C or an unexpected error)'
  }
  $Summary.finished = (Get-Date).ToString('o')
  $Summary.expected_files = @('00-summary.json', '01-env-check.json', '02-jvm-unit-tests.log', '02-jvm-unit-tests.json',
                              '03-jvm-cljfx-smoke.json', '03-jvm-cljfx-smoke.log', '04-clr-build-and-run.log',
                              '04-clr-unit-tests.json', '05-clr-wpf-smoke.json', '06-flutter-compile-and-test.log')
  $present = @(Get-ChildItem -LiteralPath $OutDir -File | Where-Object { $_.Name -ne '.gitkeep' } | ForEach-Object { $_.Name })
  $present += '00-summary.json'
  $Summary.present_files = @($present | Sort-Object -Unique)
  $Summary.missing_files = @($Summary.expected_files | Where-Object { $present -notcontains $_ })
  Write-Utf8File (Join-Path $OutDir '00-summary.json') ($Summary | ConvertTo-Json -Depth 6)

  Write-Host ''
  Write-Host '==================== cloview verification ====================' -ForegroundColor Cyan
  foreach ($k in $Summary.steps.Keys) {
    $st = $Summary.steps[$k].status
    $color = 'Green'
    if ($st -eq 'failed') { $color = 'Red' } elseif ($st -ne 'ok') { $color = 'Yellow' }
    Write-Host ('  ' + $k.PadRight(30) + $st) -ForegroundColor $color
  }
  if ($Summary.Contains('aborted')) { Write-Host ('  aborted: ' + $Summary.aborted) -ForegroundColor Red }

  if (-not $NoZip) {
    if (Test-Path -LiteralPath $ZipPath) { Remove-Item -LiteralPath $ZipPath -Force }
    $zipItems = @(Get-ChildItem -LiteralPath $OutDir -File | Where-Object { $_.Name -ne '.gitkeep' } | ForEach-Object { $_.FullName })
    Compress-Archive -LiteralPath $zipItems -DestinationPath $ZipPath -Force
    Write-Host ''
    Write-Host ('Results: ' + $ZipPath) -ForegroundColor Cyan
    Write-Host 'Upload this zip for review.'
  }
}

# Everything below runs inside try/catch/finally, so the environment is restored and the
# summary written even when the run is stopped with Ctrl+C (try does not open a new scope:
# functions and variables stay script-level). Inside a try, an exception skips the rest of
# the block, so every step below is its own try/catch and the helpers never throw.
try {
$env:DOTNET_CLI_TELEMETRY_OPTOUT = '1'
$env:DOTNET_NOLOGO = '1'
$env:DOTNET_SKIP_FIRST_TIME_EXPERIENCE = '1'

# Native tools write UTF-8 (flutter, dart, python, and Java with the options below);
# Windows PowerShell 5.1 would otherwise decode their output with the OEM code page and
# log mojibake (the Stage 2-1 run logged 'Flutter 3.29.3 ?? channel stable').
$Utf8NoBom = New-Object System.Text.UTF8Encoding($false)
try {
  [Console]::OutputEncoding = $Utf8NoBom
  $OutputEncoding = $Utf8NoBom
} catch { }
# (Python runs with -X utf8 on its command line: -I makes it ignore PYTHONUTF8.)
$javaOpts = @()
if (-not [string]::IsNullOrEmpty($env:JAVA_TOOL_OPTIONS)) { $javaOpts += $env:JAVA_TOOL_OPTIONS }
foreach ($prop in @('file.encoding', 'stdout.encoding', 'stderr.encoding', 'sun.stdout.encoding', 'sun.stderr.encoding')) {
  if (-not ("$env:JAVA_TOOL_OPTIONS" -match ('-D' + [regex]::Escape($prop) + '='))) { $javaOpts += ('-D' + $prop + '=UTF-8') }
}
$env:JAVA_TOOL_OPTIONS = ($javaOpts -join ' ')

$VerifyDir = $PSScriptRoot
# ProviderPath, not Path: on a UNC location Path is 'Microsoft.PowerShell.Core\FileSystem::\\...'
$RepoRoot = (Resolve-Path -LiteralPath (Join-Path $VerifyDir '..')).ProviderPath
$OutDir = Join-Path $VerifyDir 'out'
$ZipPath = Join-Path $VerifyDir 'cloview-windows-verification-results.zip'

# fresh results, but keep the tracked verify\out\.gitkeep
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
Get-ChildItem -LiteralPath $OutDir -Force | Where-Object { $_.Name -ne '.gitkeep' } | Remove-Item -Recurse -Force

$Summary = [ordered]@{
  suite    = 'cloview-windows-verification'
  stage    = '2-2b'
  started  = (Get-Date).ToString('o')
  repo     = $RepoRoot
  warnings = @()
  steps    = [ordered]@{}
}

if ($RepoRoot.StartsWith('\\')) {
  $w = 'The repository is on a network or WSL path (' + $RepoRoot + '). cmd-based tools such as flutter.bat ' +
       'cannot run there; copy the repository to a local drive (e.g. C:\src\cloview-core) and run this script again.'
  $Summary.warnings += $w
  Write-Host ('WARNING: ' + $w) -ForegroundColor Yellow
}

# ---------------------------------------------------------------------------
# Helpers

# The file helpers never throw: a file briefly locked by an antivirus or a sync client
# costs a retry (or one lost line), never the rest of a step.
$LogWriteFailures = 0
function Write-Utf8File([string]$Path, [string]$Text) {
  try { [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom) }
  catch {
    Start-Sleep -Milliseconds 200
    try { [System.IO.File]::WriteAllText($Path, $Text, $Utf8NoBom) }
    catch { Write-Host ('WARNING: could not write ' + $Path + ': ' + $_.Exception.Message) -ForegroundColor Yellow }
  }
}

function Add-Log([string]$Log, [string]$Text) {
  try { [System.IO.File]::AppendAllText($Log, $Text + [Environment]::NewLine, $Utf8NoBom) }
  catch {
    $err = $_
    $script:LogWriteFailures++
    if ($script:LogWriteFailures -le 20) {
      Start-Sleep -Milliseconds 50
      try { [System.IO.File]::AppendAllText($Log, $Text + [Environment]::NewLine, $Utf8NoBom); return } catch { }
    }
    if ($script:LogWriteFailures -le 5) {
      Write-Host ('WARNING: could not append to ' + $Log + ': ' + $err.Exception.Message) -ForegroundColor Yellow
    }
  }
}

function Write-Section([string]$Log, [string]$Title) {
  $line = '=' * 78
  Add-Log $Log ''
  Add-Log $Log $line
  Add-Log $Log ('== ' + $Title + '   [' + (Get-Date).ToString('HH:mm:ss') + ']')
  Add-Log $Log $line
  Write-Host ''
  Write-Host ('>> ' + $Title) -ForegroundColor Cyan
}

function Test-Tool([string]$Name) {
  return ($null -ne (Get-Command $Name -ErrorAction SilentlyContinue))
}

# Run a native command (or PowerShell function such as the ClojureTools `clj`),
# append stdout+stderr to $Log, return the exit code. Arguments are passed as an
# array of strings, so '-M:test' is never parsed as a PowerShell parameter.
function Invoke-Logged([string]$Log, [string]$Exe, [string[]]$Arguments, [string]$WorkDir = $RepoRoot) {
  Add-Log $Log ('$ ' + $Exe + ' ' + ($Arguments -join ' ') + '    (in ' + $WorkDir + ')')
  Push-Location -LiteralPath $WorkDir
  $global:LASTEXITCODE = 0
  $code = 0
  try {
    & $Exe @Arguments 2>&1 | ForEach-Object {
      $s = "$_"
      Add-Log $Log $s
      Write-Host $s
    }
    $code = $global:LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
  } catch {
    Add-Log $Log ('!! exception: ' + $_.Exception.Message)
    $code = -1
  } finally {
    Pop-Location
  }
  Add-Log $Log ('-> exit code ' + $code)
  return [int]$code
}

function Set-Step([string]$Id, [string]$Status, [System.Collections.IDictionary]$Extra) {
  $entry = [ordered]@{ status = $Status }
  if ($Extra) { foreach ($k in $Extra.Keys) { $entry[$k] = $Extra[$k] } }
  $Summary.steps[$Id] = $entry
  $color = 'Green'
  if ($Status -eq 'failed') { $color = 'Red' } elseif ($Status -ne 'ok') { $color = 'Yellow' }
  Write-Host ('[' + $Id + '] ' + $Status) -ForegroundColor $color
}

# An unexpected exception inside a step: the steps it interrupted (those of $Ids without a
# result yet, else the last one) are marked failed with the message; the run goes on.
function Register-StepException([string[]]$Ids, [string]$Log, $Err) {
  $msg = "$($Err.Exception.Message)"
  Add-Log $Log ('!! unexpected exception: ' + $msg)
  Add-Log $Log ("$($Err.InvocationInfo.PositionMessage)")
  $open = @($Ids | Where-Object { -not $Summary.steps.Contains($_) })
  if ($open.Count -eq 0) { $open = @($Ids[-1]) }
  foreach ($id in $open) {
    if (-not $Summary.steps.Contains($id)) { $Summary.steps[$id] = [ordered]@{} }
    $Summary.steps[$id]['status'] = 'failed'
    $Summary.steps[$id]['exception'] = $msg
  }
  Write-Host ('[' + ($open -join ', ') + '] failed with an unexpected exception: ' + $msg) -ForegroundColor Red
}

function Get-ToolInfo([string]$Name, [string[]]$VersionArgs) {
  $info = [ordered]@{ present = $false }
  $cmd = Get-Command $Name -ErrorAction SilentlyContinue
  if ($null -eq $cmd) { return $info }
  $info.present = $true
  $info.kind = "$($cmd.CommandType)"
  $info.source = "$($cmd.Source)"
  try {
    $global:LASTEXITCODE = 0
    $out = & $Name @VersionArgs 2>&1 | ForEach-Object { "$_" }
    $info.exit_code = $global:LASTEXITCODE
    $info.output = (($out | Select-Object -First 25) -join "`n").Trim()
  } catch {
    $info.error = $_.Exception.Message
  }
  return $info
}

function Get-DotnetMajors {
  $sdkMajors = @()
  $desktopMajors = @()
  if (Test-Tool 'dotnet') {
    foreach ($l in (& dotnet --list-sdks 2>$null)) {
      if ("$l" -match '^(\d+)\.') { $sdkMajors += [int]$Matches[1] }
    }
    foreach ($l in (& dotnet --list-runtimes 2>$null)) {
      if ("$l" -match '^Microsoft\.WindowsDesktop\.App (\d+)\.') { $desktopMajors += [int]$Matches[1] }
    }
  }
  return @{ sdk = $sdkMajors; desktop = $desktopMajors }
}

# Highest of net10/net9/net8-windows that the newest installed SDK can build (SDK >= N)
# and that some installed Microsoft.WindowsDesktop.App runtime can run: major >= N,
# because CloViewHost.csproj sets RollForward=Major (a net10 build runs on runtime 11).
function Select-ClrTfm {
  if ($ClrTargetFramework -ne '') { return $ClrTargetFramework }
  $m = Get-DotnetMajors
  if ($m.sdk.Count -eq 0 -or $m.desktop.Count -eq 0) { return $null }
  $sdkMax = ($m.sdk | Measure-Object -Maximum).Maximum
  $desktopMax = ($m.desktop | Measure-Object -Maximum).Maximum
  foreach ($n in @(10, 9, 8)) {
    if (($sdkMax -ge $n) -and ($desktopMax -ge $n)) { return ('net' + $n + '.0-windows') }
  }
  return $null
}

function Copy-SourceTree([string]$FromDir, [string]$ToDir, [string[]]$Extensions) {
  $count = 0
  if (-not (Test-Path -LiteralPath $FromDir)) { return 0 }
  $base = (Resolve-Path -LiteralPath $FromDir).ProviderPath
  Get-ChildItem -LiteralPath $base -Recurse -File | Where-Object { $Extensions -contains $_.Extension } | ForEach-Object {
    $rel = $_.FullName.Substring($base.Length).TrimStart('\', '/')
    $dest = Join-Path $ToDir $rel
    $destDir = Split-Path -Parent $dest
    if (-not (Test-Path -LiteralPath $destDir)) { New-Item -ItemType Directory -Force -Path $destDir | Out-Null }
    Copy-Item -LiteralPath $_.FullName -Destination $dest -Force
    $count++
  }
  return $count
}

function Get-Clj {
  if (Test-Tool 'clj') { return 'clj' }
  if (Test-Tool 'clojure') { return 'clojure' }
  return $null
}

# The legacy ClojureTools installer (win-install.ps1) puts the module in a folder of the
# PSModulePath of the shell that ran it. Installed from PowerShell 7 it often lands in
# Documents\PowerShell\Modules, which Windows PowerShell 5.1 does not search.
function Find-ClojureToolsModule {
  $roots = @()
  $docs = [Environment]::GetFolderPath('MyDocuments')
  if ($docs) { $roots += (Join-Path $docs 'PowerShell\Modules'); $roots += (Join-Path $docs 'WindowsPowerShell\Modules') }
  if ($env:ProgramFiles) {
    $roots += (Join-Path $env:ProgramFiles 'PowerShell\Modules')
    $roots += (Join-Path $env:ProgramFiles 'PowerShell\7\Modules')
    $roots += (Join-Path $env:ProgramFiles 'WindowsPowerShell\Modules')
  }
  foreach ($r in $roots) {
    $p = Join-Path $r 'ClojureTools\ClojureTools.psd1'
    if (Test-Path -LiteralPath $p) { return $p }
  }
  return $null
}

# Flutter version as [int[]] (major, minor, patch), or $null.
function Get-FlutterVersion {
  if (-not (Test-Tool 'flutter')) { return $null }
  try {
    $out = (& flutter --version 2>&1 | ForEach-Object { "$_" }) -join "`n"
    if ($out -match 'Flutter (\d+)\.(\d+)\.(\d+)') {
      return @([int]$Matches[1], [int]$Matches[2], [int]$Matches[3])
    }
  } catch { }
  return $null
}

# ClojureDart 0.9.20261002 (cljd.flutter) needs Flutter >= 3.35.0.
$MinFlutter = @(3, 35, 0)
function Test-FlutterVersionOk($v) {
  if ($null -eq $v) { return $false }
  for ($i = 0; $i -lt 3; $i++) {
    if ($v[$i] -gt $MinFlutter[$i]) { return $true }
    if ($v[$i] -lt $MinFlutter[$i]) { return $false }
  }
  return $true
}

# Flutter creates symlinks for plugins with Windows/Linux implementations (shared_preferences,
# path_provider): that needs Windows Developer Mode, or an elevated shell. $true/$false.
function Test-SymlinkSupport {
  try {
    $k = Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\AppModelUnlock' -ErrorAction Stop
    if ($k.AllowDevelopmentWithoutDevLicense -eq 1) { return $true }
  } catch { }
  try {
    $id = [Security.Principal.WindowsIdentity]::GetCurrent()
    return (New-Object Security.Principal.WindowsPrincipal($id)).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
  } catch { return $false }
}

# A Python 3.7+ that really runs: 'py -3', 'python', 'python3' (skips the Microsoft Store
# alias, which exists on PATH but only opens the Store). Returns @{ exe; args } or $null.
function Find-Python {
  foreach ($cand in @(@{ exe = 'py'; args = @('-3') }, @{ exe = 'python'; args = @() }, @{ exe = 'python3'; args = @() })) {
    if (Test-Tool $cand.exe) {
      try {
        $global:LASTEXITCODE = 0
        $v = & $cand.exe @($cand.args + @('--version')) 2>&1 | ForEach-Object { "$_" }
        # the baseline's checkers need Python 3.7+ (date.fromisoformat)
        if ($global:LASTEXITCODE -eq 0 -and ("$v" -match 'Python 3\.(\d+)') -and ([int]$Matches[1] -ge 7)) { return $cand }
      } catch { }
    }
  }
  return $null
}

$Clj = Get-Clj
$Tfm = Select-ClrTfm
$PsExe = (Get-Process -Id $PID).Path

# The official Windows Clojure CLI is a PowerShell module (ClojureTools): `clj` is an
# alias of the function Invoke-Clojure, not an exe. Two consequences handled here:
#  * Invoke-Clojure only recognises an alias argument split the way PowerShell splits a
#    typed `-M:test`, i.e. two arguments '-M:' 'test' (same for -X: -T: -A:); an unsplit
#    '-M:test' would be handed to clojure.main as a REPL argument;
#  * Invoke-Clojure sets $ErrorActionPreference='Stop', so under Windows PowerShell 5.1 a
#    2>&1 around it turns Java's first stderr line into a terminating error.
# A function/script `clj` is therefore run in a child PowerShell (its stderr is captured
# there as a plain native stream), with the alias arguments pre-split.
$CljKind = 'none'
$CljPrelude = ''
$CljNeedsProfile = $false
if ($null -eq $Clj) {
  $ctModule = Find-ClojureToolsModule
  if ($null -ne $ctModule) {
    $Clj = 'Invoke-Clojure'
    $CljKind = 'function'
    $CljPrelude = "Import-Module '" + ($ctModule -replace "'", "''") + "'; Invoke-Clojure"
    $Summary.clj_module_outside_psmodulepath = $ctModule
  }
} else {
  $ci = Get-Command $Clj -ErrorAction SilentlyContinue
  if ($null -ne $ci -and "$($ci.CommandType)" -eq 'Alias') { $ci = $ci.ResolvedCommand }
  $ct = "$($ci.CommandType)"
  if ($ct -eq 'Function') {
    $CljKind = 'function'
    $CljPrelude = $ci.Name
    if ($null -ne $ci.Module) {
      $modFile = Join-Path $ci.Module.ModuleBase ($ci.Module.Name + '.psd1')
      if (-not (Test-Path -LiteralPath $modFile)) { $modFile = $ci.Module.Path }
      $CljPrelude = "Import-Module '" + ($modFile -replace "'", "''") + "'; " + $ci.Name
    } else {
      $CljNeedsProfile = $true   # defined in a profile script
    }
  } elseif ($ct -eq 'ExternalScript') {
    $CljKind = 'script'
    $CljPrelude = "& '" + ($ci.Path -replace "'", "''") + "'"
  } else {
    $CljKind = 'app'
  }
}
$CljIsFunction = ($CljKind -eq 'function' -or $CljKind -eq 'script')

function ConvertTo-CljArgs([string[]]$Arguments) {
  $out = New-Object System.Collections.Generic.List[string]
  foreach ($a in $Arguments) {
    if ($CljIsFunction -and ($a -match '^(-[MXTA]:)(.+)$')) {
      $out.Add($Matches[1])
      $out.Add($Matches[2])
    } else {
      $out.Add($a)
    }
  }
  return ,($out.ToArray())
}

function Invoke-Clj([string]$Log, [string[]]$Arguments, [string]$WorkDir = $RepoRoot) {
  $cljArgs = ConvertTo-CljArgs $Arguments
  if (-not $CljIsFunction) { return (Invoke-Logged $Log $Clj $cljArgs $WorkDir) }
  $quoted = @()
  foreach ($a in $cljArgs) { $quoted += ("'" + ($a -replace "'", "''") + "'") }
  $cd = 'Set-Location -LiteralPath ''' + ($WorkDir -replace "'", "''") + '''; '
  $utf8 = 'try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false) } catch { }; '
  $command = '$ProgressPreference = ''SilentlyContinue''; ' + $utf8 + $cd + $CljPrelude + ' ' + ($quoted -join ' ') + '; exit $LASTEXITCODE'
  Add-Log $Log ('# clj is a PowerShell ' + $CljKind + '; running in a child PowerShell: ' + $command)
  $encoded = [Convert]::ToBase64String([System.Text.Encoding]::Unicode.GetBytes($command))
  $psArgs = @('-NonInteractive', '-ExecutionPolicy', 'Bypass', '-OutputFormat', 'Text', '-EncodedCommand', $encoded)
  if (-not $CljNeedsProfile) { $psArgs = @('-NoProfile') + $psArgs }
  return (Invoke-Logged $Log $PsExe $psArgs $WorkDir)
}

# Copy a Flutter example to target\flutter-<name> (absolute :local/root paths), run
# `clj -M:cljd init` once (flutter create), then add the Dart packages the effect runners
# use. ClojureDart's own pubspec "bridge" (`flutter pub add name:{"path":...}`) loses its
# JSON quotes through flutter.bat, so cloview declares no bridges and adds packages here.
# $FlutterVer: when it differs from the version that built the folder last time, the
# ClojureDart build output is cleaned (`clj -M:cljd clean`) before compiling again.
$DartPackages = @('http', 'shared_preferences', 'path_provider')
function Initialize-FlutterProject([string]$Log, [string]$ExampleRel, [string]$Dir, [string[]]$ExtraTestRels, [string]$FlutterVer) {
  $r = [ordered]@{}
  $example = Join-Path $RepoRoot $ExampleRel
  New-Item -ItemType Directory -Force -Path $Dir | Out-Null
  $sourceExts = @('.clj', '.cljc', '.cljd', '.edn', '.dart')
  foreach ($d in @('src', 'test')) {
    $dest = Join-Path $Dir $d
    if (Test-Path -LiteralPath $dest) { Remove-Item -LiteralPath $dest -Recurse -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $dest) { Add-Log $Log ('warning: could not delete ' + $dest + ' completely (file in use?); copying over it') }
    # file by file, so an existing folder is never nested inside itself
    $n = Copy-SourceTree (Join-Path $example $d) $dest $sourceExts
    Add-Log $Log ('copied ' + $n + ' files of ' + $ExampleRel + '\' + $d)
  }
  foreach ($t in $ExtraTestRels) {
    $n = Copy-SourceTree (Join-Path $RepoRoot $t) (Join-Path $Dir 'test') $sourceExts
    Add-Log $Log ('copied ' + $n + ' files of ' + $t + ' into ' + (Join-Path $Dir 'test'))
  }
  $depsText = [System.IO.File]::ReadAllText((Join-Path $example 'deps.edn'))
  $depsText = [regex]::Replace($depsText, ':local/root\s+"([^"]+)"', {
      param($m)
      $abs = [System.IO.Path]::GetFullPath((Join-Path $example $m.Groups[1].Value))
      ':local/root "' + ($abs -replace '\\', '/') + '"'
    })
  Write-Utf8File (Join-Path $Dir 'deps.edn') $depsText
  Add-Log $Log $depsText

  $pubspec = Join-Path $Dir 'pubspec.yaml'
  if (-not (Test-Path -LiteralPath $pubspec)) {
    Write-Section $Log ('clj -M:cljd init (flutter create) in ' + $Dir)
    $r.init_exit = Invoke-Clj $Log @('-M:cljd', 'init', '--platforms=windows,android,linux,macos') $Dir
    # cljd's init exits 0 even when flutter create failed: the pubspec is the real signal
    if (($r.init_exit -eq 0) -and -not (Test-Path -LiteralPath $pubspec)) { $r.init_exit = 'no-pubspec' }
    $sample = Join-Path $Dir 'test\widget_test.dart'
    if (Test-Path -LiteralPath $sample) { Remove-Item -LiteralPath $sample -Force; Add-Log $Log 'removed flutter create sample test\widget_test.dart' }
  } else {
    $r.init_exit = 0
    Add-Log $Log 'pubspec.yaml exists - init skipped'
  }

  # a Flutter upgrade invalidates ClojureDart's output built with the previous SDK
  $stamp = Join-Path $Dir '.cloview-flutter-version'
  $prev = $null
  if (Test-Path -LiteralPath $stamp) { try { $prev = ([System.IO.File]::ReadAllText($stamp)).Trim() } catch { } }
  if ((Test-Path -LiteralPath (Join-Path $Dir '.clojuredart')) -and ($prev -ne $FlutterVer)) {
    Write-Section $Log ('clj -M:cljd clean  (built with Flutter ' + $(if ($prev) { $prev } else { 'unknown' }) + ', now ' + $FlutterVer + ')')
    $r.clean_exit = Invoke-Clj $Log @('-M:cljd', 'clean') $Dir
  }
  if ($FlutterVer) { Write-Utf8File $stamp $FlutterVer }

  if (Test-Path -LiteralPath $pubspec) {
    $missingPkgs = {
      $t = ''
      try { $t = [System.IO.File]::ReadAllText($pubspec) } catch { }   # unreadable: all missing (pub add is idempotent)
      @($DartPackages | Where-Object { -not ($t -match ('(?m)^\s+' + [regex]::Escape($_) + ':')) })
    }
    $missing = @(& $missingPkgs)
    if ($missing.Count -gt 0) {
      Write-Section $Log ('flutter pub add ' + ($missing -join ' '))
      $r.pub_add_exit = Invoke-Logged $Log 'flutter' (@('pub', 'add') + $missing) $Dir
      if (($r.pub_add_exit -ne 0) -and (@(& $missingPkgs).Count -eq 0)) {
        # The packages were added and resolved; what failed is Flutter's plugin symlink step
        # (shared_preferences / path_provider have Windows implementations). Compiling and
        # `flutter test` work without it; running the Windows app needs Developer Mode.
        Add-Log $Log ('pub add: packages are in pubspec.yaml, but Flutter could not finish (exit ' + $r.pub_add_exit +
                      '). Usually this is "Building with plugins requires symlink support": enable Windows Developer Mode ' +
                      '(start ms-settings:developers). Continuing: compile and tests do not need the symlinks.')
        $r.pub_add_warning = 'plugin-symlinks'
        $r.pub_add_exit = 0
      }
    } else {
      $r.pub_add_exit = 0
      Add-Log $Log ('Dart packages already present: ' + ($DartPackages -join ' '))
    }
  } else {
    $r.pub_add_exit = 'no-pubspec'
  }
  return $r
}

# Text appended to $Log since $Before (its earlier content), to look for a compile error.
function Get-LogTail([string]$Log, [string]$Before) {
  $now = Read-Log $Log
  return $now.Substring([Math]::Min($Before.Length, $now.Length))
}
function Read-Log([string]$Log) {
  try { if (Test-Path -LiteralPath $Log) { return [System.IO.File]::ReadAllText($Log) } } catch { }
  return ''
}

# `clj -M:cljd-diag compile`: the same compile, printing the full cause chain of an error
# (cljd's own message names only the outermost namespace).
function Invoke-CljdDiagnostics([string]$Log, [string]$Dir) {
  Write-Section $Log 'Compile error diagnostics: clj -M:cljd-diag compile --offline (full cause chain)'
  return (Invoke-Clj $Log @('-M:cljd-diag', 'compile', '--offline') $Dir)
}

# ---------------------------------------------------------------------------
# 01 - environment

try {   # (each step: try { ... } catch { Register-StepException ... }, see above)
$envStart = Get-Date
$os = [ordered]@{
  version_string = [System.Environment]::OSVersion.VersionString
  is_64bit = [System.Environment]::Is64BitOperatingSystem
  culture = (Get-Culture).Name
  ui_culture = (Get-UICulture).Name
  oem_code_page = [System.Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage
}
try {
  $w = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
  $os.caption = $w.Caption
  $os.build = $w.BuildNumber
} catch { $os.caption = 'unknown' }

$dotnetMajors = Get-DotnetMajors
$FlutterVersion = Get-FlutterVersion
$Python = Find-Python
$SymlinkOk = Test-SymlinkSupport
$envReport = [ordered]@{
  generated = (Get-Date).ToString('o')
  os = $os
  powershell = "$($PSVersionTable.PSVersion)"
  repo = $RepoRoot
  tools = [ordered]@{
    java = Get-ToolInfo 'java' @('-version')
    clj = Get-ToolInfo 'clj' @('-Sdescribe')
    clojure = Get-ToolInfo 'clojure' @('--version')
    bb = Get-ToolInfo 'bb' @('--version')
    git = Get-ToolInfo 'git' @('--version')
    dotnet = Get-ToolInfo 'dotnet' @('--info')
    cljr = Get-ToolInfo 'cljr' @('--version')
    flutter = Get-ToolInfo 'flutter' @('--version')
    dart = Get-ToolInfo 'dart' @('--version')
  }
  flutter = [ordered]@{
    version = $(if ($null -ne $FlutterVersion) { $FlutterVersion -join '.' } else { $null })
    minimum = ($MinFlutter -join '.')
    ok = (Test-FlutterVersionOk $FlutterVersion)
    symlink_support = $SymlinkOk
  }
  python = $(if ($null -ne $Python) { ($Python.exe + ' ' + ($Python.args -join ' ')).Trim() } else { $null })
  dotnet = [ordered]@{
    sdk_majors = $dotnetMajors.sdk
    windows_desktop_runtime_majors = $dotnetMajors.desktop
    selected_clr_tfm = $Tfm
    dotnet_global_tools = ''
  }
  java_tool_options = $env:JAVA_TOOL_OPTIONS
}
if (Test-Tool 'dotnet') {
  $envReport.dotnet.dotnet_global_tools = ((& dotnet tool list -g 2>&1 | ForEach-Object { "$_" }) -join "`n")
}
Write-Utf8File (Join-Path $OutDir '01-env-check.json') ($envReport | ConvertTo-Json -Depth 6)
Set-Step '01-env-check' 'ok' @{ duration_s = [int]((Get-Date) - $envStart).TotalSeconds; clj = $Clj; clj_kind = $CljKind; clr_tfm = $Tfm
                                 flutter = $envReport.flutter.version; flutter_ok = $envReport.flutter.ok; python = $envReport.python }
if (($null -ne $FlutterVersion) -and -not (Test-FlutterVersionOk $FlutterVersion)) {
  $w = 'Flutter ' + ($FlutterVersion -join '.') + ' is older than 3.35, the minimum of ClojureDart 0.9.20261002 ' +
       '(cljd.flutter uses AlignmentGeometry.topLeft). Run `flutter upgrade` (3.47.x stable recommended) or pass -FlutterUpgrade.'
  $Summary.warnings += $w
  Write-Host ('WARNING: ' + $w) -ForegroundColor Yellow
}
if ((Test-Tool 'flutter') -and -not $SkipFlutter -and -not $SymlinkOk) {
  $w = 'Windows Developer Mode is off and this shell is not elevated. Flutter needs symlink support for ' +
       'plugins (shared_preferences, path_provider): `flutter pub add` will report "Building with plugins requires ' +
       'symlink support". Compile and tests still run; running the Flutter Windows app needs Developer Mode ' +
       '(open it with: start ms-settings:developers).'
  $Summary.warnings += $w
  Write-Host ('WARNING: ' + $w) -ForegroundColor Yellow
}
} catch { Register-StepException @('01-env-check') (Join-Path $OutDir '01-env-check.log') $_ }

# ---------------------------------------------------------------------------
# 02 - JVM unit tests (+ static check)

$log02 = Join-Path $OutDir '02-jvm-unit-tests.log'
try {
$start = Get-Date
if ($SkipJvm) {
  Add-Log $log02 'skipped (-SkipJvm)'
  Set-Step '02-jvm-unit-tests' 'skipped' @{ reason = '-SkipJvm' }
} elseif ($null -eq $Clj) {
  Add-Log $log02 'skipped: neither clj nor clojure is on PATH (install the Clojure CLI for Windows)'
  Set-Step '02-jvm-unit-tests' 'skipped' @{ reason = 'clj not found' }
} else {
  Write-Section $log02 'JVM unit tests: core, fx, bridge, licensing, key tool, initializer + cljfx description snapshots'
  $c1 = Invoke-Clj $log02 @('-M:test', '--out', 'verify/out/02-jvm-unit-tests.json')
  Write-Section $log02 'Static check: calls/arities, architecture rules, ClojureDart host-pass rule'
  $c2 = Invoke-Clj $log02 @('-M:static-check')
  $status = 'ok'
  if ($c1 -ne 0 -or $c2 -ne 0) { $status = 'failed' }
  Set-Step '02-jvm-unit-tests' $status @{ unit_tests_exit = $c1; static_check_exit = $c2; duration_s = [int]((Get-Date) - $start).TotalSeconds }
}
} catch { Register-StepException @('02-jvm-unit-tests') $log02 $_ }

# ---------------------------------------------------------------------------
# 03 - JVM cljfx smoke

$log03 = Join-Path $OutDir '03-jvm-cljfx-smoke.log'
$json03 = 'verify/out/03-jvm-cljfx-smoke.json'
$CljfxGit = $false
try {
$start = Get-Date
if ($SkipJvm -or ($null -eq $Clj)) {
  Add-Log $log03 'skipped'
  Set-Step '03-jvm-cljfx-smoke' 'skipped' @{ reason = 'JVM step skipped or clj missing' }
} else {
  Write-Section $log03 'JavaFX off-screen smoke (Greeter + kitchen sink) via cljfx 1.10.363 from Clojars'
  $c = Invoke-Clj $log03 @('-M:smoke-javafx', $json03)
  if ($c -ne 0 -and -not (Test-Path -LiteralPath (Join-Path $RepoRoot $json03))) {
    Write-Section $log03 'Retry with cljfx from GitHub (:cljfx-git alias)'
    $c = Invoke-Clj $log03 @('-M:smoke-javafx:cljfx-git', $json03)
    $CljfxGit = $true
  }
  $status = 'ok'
  if ($c -ne 0) { $status = 'failed' }
  Set-Step '03-jvm-cljfx-smoke' $status @{ exit = $c; cljfx_from_git = $CljfxGit; duration_s = [int]((Get-Date) - $start).TotalSeconds }
}
} catch { Register-StepException @('03-jvm-cljfx-smoke') $log03 $_ }

# ---------------------------------------------------------------------------
# 04/05 - CLR: cljr -Spath, staging, CloViewHost build, CLR unit tests, WPF smoke

$log04 = Join-Path $OutDir '04-clr-build-and-run.log'
$start = Get-Date
$stagedDir = Join-Path $RepoRoot 'target\clr-src'
$hostOut = Join-Path $RepoRoot 'target\clr-host'
$HostExe = Join-Path $hostOut 'CloViewHost.exe'
$HostBuilt = $false
try {
if ($SkipClr) {
  Add-Log $log04 'skipped (-SkipClr)'
  Set-Step '04-clr-build-and-run' 'skipped' @{ reason = '-SkipClr' }
  Set-Step '05-clr-wpf-smoke' 'skipped' @{ reason = '-SkipClr' }
} else {
  $clr = [ordered]@{}

  Write-Section $log04 'cljr -Spath in modules\wpf (tooling check; the host does not depend on it)'
  if (Test-Tool 'cljr') {
    $clr.cljr_spath_exit = Invoke-Logged $log04 'cljr' @('-Spath') (Join-Path $RepoRoot 'modules\wpf')
  } else {
    Add-Log $log04 'cljr not on PATH (optional): dotnet tool install -g Clojure.Main ; dotnet tool install -g Clojure.Cljr --version 0.1.0-beta1'
    $clr.cljr_spath_exit = 'not-installed'
  }

  Write-Section $log04 'Stage .clj/.cljc/.cljr sources into target\clr-src'
  if (Test-Path -LiteralPath $stagedDir) { Remove-Item -LiteralPath $stagedDir -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $stagedDir | Out-Null
  # keep in sync with clr-source-dirs in verify/bb/cloview/tasks.clj
  $sourceDirs = @('modules\core\src', 'modules\core\test', 'modules\fx\src', 'modules\fx\test',
                  'modules\bridge\src', 'modules\bridge\test', 'modules\licensing\src', 'modules\licensing\test',
                  'modules\wpf\src', 'verify\src', 'examples\greeter\shared\src', 'examples\greeter\wpf\src')
  $staged = 0
  foreach ($d in $sourceDirs) {
    $n = Copy-SourceTree (Join-Path $RepoRoot $d) $stagedDir @('.clj', '.cljc', '.cljr')
    Add-Log $log04 ('  ' + $d + ' -> ' + $n + ' files')
    $staged += $n
  }
  Add-Log $log04 ('staged ' + $staged + ' files into ' + $stagedDir)
  $clr.staged_files = $staged

  Write-Section $log04 ('dotnet build CloViewHost.csproj (TargetFramework ' + $Tfm + ')')
  if (-not (Test-Tool 'dotnet')) {
    Add-Log $log04 'dotnet not on PATH: install the .NET 10 SDK (or 8/9)'
    $clr.build_exit = 'no-dotnet'
  } elseif ($null -eq $Tfm) {
    Add-Log $log04 'no usable .NET SDK (>= 8) + Microsoft.WindowsDesktop.App runtime (>= 8) found - install the .NET 10 SDK'
    $clr.build_exit = 'no-tfm'
  } else {
    if (Test-Path -LiteralPath $hostOut) { Remove-Item -LiteralPath $hostOut -Recurse -Force -ErrorAction SilentlyContinue }
    if (Test-Path -LiteralPath $HostExe) { Add-Log $log04 ('warning: could not delete ' + $hostOut + ' - is CloViewHost.exe still running?') }
    $clr.tfm = $Tfm
    $clr.build_exit = Invoke-Logged $log04 'dotnet' @('build', (Join-Path $RepoRoot 'modules\wpf\host-template\CloViewHost.csproj'),
                                                      '-c', 'Release', ('-p:CloViewTargetFramework=' + $Tfm), '-o', $hostOut)
  }

  if (($clr.build_exit -is [int]) -and ($clr.build_exit -eq 0) -and (Test-Path -LiteralPath $HostExe)) {
    $HostBuilt = $true
    Write-Section $log04 'CloViewHost --run-tests  (core, fx, bridge, licensing unit tests on ClojureCLR; also loads all WPF namespaces)'
    $clr.unit_tests_exit = Invoke-Logged $log04 $HostExe @('--src', $stagedDir, '--run-tests',
                                                           '--out', (Join-Path $OutDir '04-clr-unit-tests.json'))

    Write-Section $log04 'CloViewHost --headless-smoke  (off-screen WPF Greeter + kitchen sink)'
    $clr.smoke_exit = Invoke-Logged $log04 $HostExe @('--src', $stagedDir, '--headless-smoke',
                                                      '--out', (Join-Path $OutDir '05-clr-wpf-smoke.json'))
  } else {
    Add-Log $log04 ('CloViewHost was not built in this run (' + $HostExe + ') - skipping CLR runs')
    $clr.unit_tests_exit = 'not-run'
    $clr.smoke_exit = 'not-run'
  }

  $s4 = 'ok'
  if (-not ($clr.build_exit -is [int]) -or $clr.build_exit -ne 0 -or $clr.unit_tests_exit -ne 0) { $s4 = 'failed' }
  $clr.duration_s = [int]((Get-Date) - $start).TotalSeconds
  Set-Step '04-clr-build-and-run' $s4 $clr

  $s5 = 'failed'
  if ($clr.smoke_exit -is [int] -and $clr.smoke_exit -eq 0) { $s5 = 'ok' }
  if (-not (Test-Path -LiteralPath (Join-Path $OutDir '05-clr-wpf-smoke.json'))) {
    Write-Utf8File (Join-Path $OutDir '05-clr-wpf-smoke.json') ((@{ suite = 'clr-wpf-smoke'; ok = $false; error = 'smoke did not run - see 04-clr-build-and-run.log' }) | ConvertTo-Json)
  }
  Set-Step '05-clr-wpf-smoke' $s5 @{ exit = $clr.smoke_exit }
}
} catch { Register-StepException @('04-clr-build-and-run', '05-clr-wpf-smoke') $log04 $_ }

# ---------------------------------------------------------------------------
# 06 - Flutter version check, ClojureDart compile + Flutter widget tests (Greeter)

$log06 = Join-Path $OutDir '06-flutter-compile-and-test.log'
$start = Get-Date
$FlutterReady = $false
$FlutterVerText = 'unknown'
try {
if ($SkipFlutter) {
  Add-Log $log06 'skipped (-SkipFlutter)'
  Set-Step '06-flutter-compile-and-test' 'skipped' @{ reason = '-SkipFlutter' }
} elseif (-not (Test-Tool 'flutter')) {
  Add-Log $log06 'skipped: flutter is not on PATH'
  Set-Step '06-flutter-compile-and-test' 'skipped' @{ reason = 'flutter not found' }
} elseif ($null -eq $Clj) {
  Add-Log $log06 'skipped: clj is not on PATH (ClojureDart runs on the Clojure CLI)'
  Set-Step '06-flutter-compile-and-test' 'skipped' @{ reason = 'clj not found' }
} else {
  $fl = [ordered]@{}
  Write-Section $log06 'flutter --version'
  $fl.flutter_version_exit = Invoke-Logged $log06 'flutter' @('--version')
  $v = Get-FlutterVersion
  if (-not (Test-FlutterVersionOk $v) -and $FlutterUpgrade) {
    Write-Section $log06 'flutter upgrade  (-FlutterUpgrade: the installed Flutter is older than 3.35)'
    $fl.flutter_upgrade_exit = Invoke-Logged $log06 'flutter' @('upgrade')
    $v = Get-FlutterVersion
  }
  $fl.flutter_version = $(if ($null -ne $v) { $v -join '.' } else { 'unknown' })
  if ($null -eq $v) {
    Add-Log $log06 'Could not read the Flutter version from `flutter --version` (see its output above). Compile and tests skipped.'
    $fl.reason = 'flutter-version-unparsed'
    $fl.duration_s = [int]((Get-Date) - $start).TotalSeconds
    Set-Step '06-flutter-compile-and-test' 'failed' $fl
  } elseif (-not (Test-FlutterVersionOk $v)) {
    Add-Log $log06 ('Flutter ' + $fl.flutter_version + ' is too old: ClojureDart 0.9.20261002 needs Flutter >= 3.35.0 ' +
                    '(cljd.flutter uses AlignmentGeometry.topLeft, added in Flutter 3.35). Every ClojureDart Flutter app ' +
                    'fails to compile on older versions. Run `flutter upgrade` (3.47.x stable recommended), or run this ' +
                    'script with -FlutterUpgrade. Compile and tests skipped.')
    $fl.reason = 'flutter-too-old'
    $fl.duration_s = [int]((Get-Date) - $start).TotalSeconds
    Set-Step '06-flutter-compile-and-test' 'failed' $fl
  } else {
    $FlutterReady = $true
    $FlutterVerText = $fl.flutter_version
    $flutterDir = Join-Path $RepoRoot 'target\flutter-greeter'
    Write-Section $log06 'Prepare target\flutter-greeter (copy example, absolute :local/root paths, Dart packages)'
    $prep = Initialize-FlutterProject $log06 'examples\greeter\flutter' $flutterDir @() $fl.flutter_version
    foreach ($k in $prep.Keys) { $fl[$k] = $prep[$k] }

    Write-Section $log06 'clj -M:cljd compile  (Greeter app: greeter.flutter.main and the cloview.flutter UI layer)'
    $fl.compile_app_exit = Invoke-Clj $log06 @('-M:cljd', 'compile') $flutterDir
    if ($fl.compile_app_exit -ne 0) { $fl.diag_app_exit = Invoke-CljdDiagnostics $log06 $flutterDir }

    Write-Section $log06 'clj -M:cljd compile cloview.flutter.effects  (effect runners: http, storage, file, timer, lan-sync)'
    $fl.compile_effects_exit = Invoke-Clj $log06 @('-M:cljd', 'compile', 'cloview.flutter.effects') $flutterDir

    Write-Section $log06 'clj -M:test:cljd test  (widget tests: Greeter flow + kitchen sink)'
    $fl.widget_tests_exit = Invoke-Clj $log06 @('-M:test:cljd', 'test') $flutterDir

    $s6 = 'ok'
    if ($fl.init_exit -ne 0 -or $fl.pub_add_exit -ne 0 -or $fl.compile_app_exit -ne 0 -or $fl.compile_effects_exit -ne 0 -or $fl.widget_tests_exit -ne 0) { $s6 = 'failed' }
    $fl.duration_s = [int]((Get-Date) - $start).TotalSeconds
    Set-Step '06-flutter-compile-and-test' $s6 $fl
  }
}
} catch { Register-StepException @('06-flutter-compile-and-test') $log06 $_ }

$RunCompleted = $true
} catch {
  # the steps catch their own exceptions: this one happened between steps
  $err = $_
  if ($null -ne $Summary) { $Summary.aborted = ('exception: ' + $err.Exception.Message) }
  Write-Host ('ERROR: ' + $err.Exception.Message) -ForegroundColor Red
  Write-Host ("$($err.InvocationInfo.PositionMessage)")
} finally {
  # restore first: it needs no cmdlets, so it also completes while Ctrl+C stops the run
  Restore-Session
  try { Complete-Run } catch { Write-Host ('ERROR: could not write the summary or the zip: ' + $_.Exception.Message) -ForegroundColor Red }
}
exit 0
