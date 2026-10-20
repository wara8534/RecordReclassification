param([switch]$Test)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not $Test) {
    $serverPort = 8080
    if ($env:PORT) {
        if (-not [int]::TryParse($env:PORT, [ref]$serverPort) -or $serverPort -lt 1 -or $serverPort -gt 65535) {
            throw 'PORT must be a number between 1 and 65535.'
        }
    }
    $serverUrl = "http://127.0.0.1:$serverPort"
    $existingPolicy = $null
    try {
        $existingPolicy = Invoke-RestMethod -Uri "$serverUrl/api/classification-policy" -TimeoutSec 3
    } catch {
        # A server may not be running yet. Continue with normal startup.
    }
    if ($existingPolicy.sourceUrl -eq '/reference/information-disclosure-article9.pdf' -and $existingPolicy.criteria.Count -eq 8) {
        Write-Host "RecordOn is already running: $serverUrl" -ForegroundColor Green
        Write-Host 'Open this URL in your browser. No additional server is needed.'
        Write-Host 'To apply Java code changes, stop the existing server before starting it again.'
        exit 0
    }
}
$localJdk = Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot '.tools') -Directory -Filter 'jdk-*' -ErrorAction SilentlyContinue | Select-Object -First 1
if ($localJdk) { $env:JAVA_HOME = $localJdk.FullName; $env:Path = "$env:JAVA_HOME\bin;$env:Path" }
$localMaven = Join-Path $PSScriptRoot '.tools/apache-maven-3.9.11/bin/mvn.cmd'
$maven = if (Test-Path -LiteralPath $localMaven) { $localMaven } else { 'mvn' }
if (-not (Get-Command $maven -ErrorAction SilentlyContinue)) { throw 'Maven 3.6.3+ and JDK 17+ are required. See README.md.' }
$repository = Join-Path $PSScriptRoot '.tools/repository'
if ($Test) { & $maven "-Dmaven.repo.local=$repository" test }
else { & $maven "-Dmaven.repo.local=$repository" spring-boot:run }
exit $LASTEXITCODE
