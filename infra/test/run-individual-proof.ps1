param([string[]]$Tests)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
Set-Location $projectRoot
$evidenceDirectory = Join-Path $projectRoot 'evaluation/individual-proof'
New-Item -ItemType Directory -Force -Path $evidenceDirectory | Out-Null
& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw 'Docker must be running before proof execution.' }
$source = Get-Content 'backend/src/test/java/io/guidein/integration/PlatformKernelIntegrationIT.java' -Raw
$methods = [regex]::Matches($source, '@Test\s+void\s+(\w+)') | ForEach-Object { $_.Groups[1].Value }
if ($Tests) { $methods = $methods | Where-Object { $_ -in $Tests } }
$failures = 0
foreach ($method in $methods) {
    Write-Host "Executing $method"
    & mvn --batch-mode --no-transfer-progress "-Dmaven.repo.local=$projectRoot/.m2/repository" -pl backend "-Dit.test=PlatformKernelIntegrationIT#$method" failsafe:integration-test failsafe:verify 2>&1 |
        Out-File -FilePath (Join-Path $evidenceDirectory "$method.log") -Encoding utf8
    $result = $LASTEXITCODE
    Copy-Item -LiteralPath 'backend/target/failsafe-reports/TEST-io.guidein.integration.PlatformKernelIntegrationIT.xml' -Destination (Join-Path $evidenceDirectory "$method.xml")
    Write-Host "$method exit=$result"
    if ($result -ne 0) { $failures++ }
}
if ($failures -gt 0) { throw "$failures individual proof tests failed; see retained XML evidence." }
