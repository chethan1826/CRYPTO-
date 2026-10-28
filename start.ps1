$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$source = Join-Path $projectRoot 'backend/src/main/java/edu/demo/des/DesServer.java'
$output = Join-Path $projectRoot 'build/classes'
$bcryptJar = Join-Path $projectRoot 'lib/jbcrypt-0.4.jar'
if (-not (Test-Path $bcryptJar)) { throw 'The jBCrypt dependency is missing from the lib folder.' }
$javaRoot = Join-Path $env:ProgramFiles 'Java'
$compiler = Get-ChildItem -Path $javaRoot -Filter javac.exe -File -Recurse -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $compiler) { $compiler = Get-Command javac -ErrorAction Stop }
$compilerPath = $compiler.FullName
if (-not $compilerPath) { $compilerPath = $compiler.Source }
New-Item -ItemType Directory -Force -Path $output | Out-Null
& $compilerPath -encoding UTF-8 -cp $bcryptJar -d $output $source
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$runtime = Join-Path (Split-Path -Parent $compilerPath) 'java.exe'
if (-not (Test-Path $runtime)) { $runtime = (Get-Command java -ErrorAction Stop).Source }
& $runtime -cp "$output;$bcryptJar" edu.demo.des.DesServer $projectRoot
