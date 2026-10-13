$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $projectRoot 'backend/src/main/java/edu/demo/des/DesServer.java'
$output = Join-Path $projectRoot 'build/classes'
$javaRoot = Join-Path $env:ProgramFiles 'Java'
$compiler = Get-ChildItem -Path $javaRoot -Filter javac.exe -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $compiler) { $compiler = Get-Command javac -ErrorAction Stop }
New-Item -ItemType Directory -Force -Path $output | Out-Null
& $compiler.Source -encoding UTF-8 -d $output $source
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$runtime = Join-Path (Split-Path -Parent $compiler.Source) 'java.exe'
if (-not (Test-Path $runtime)) { $runtime = (Get-Command java -ErrorAction Stop).Source }
& $runtime -cp $output edu.demo.des.DesServer $projectRoot
