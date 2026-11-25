param([switch]$Test, [switch]$Ai)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if ($Test -and $Ai) { throw 'Use -Test or -Ai in separate runs.' }

function Find-DockerCli {
    $command = Get-Command docker -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command) { return $command.Source }
    $paths = @(
        (Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'),
        (Join-Path $env:ProgramFiles 'Docker/Docker/resources/bin/docker.exe')
    )
    return $paths | Where-Object { Test-Path -LiteralPath $_ -PathType Leaf } | Select-Object -First 1
}

function Test-AiEngine($baseUrl, $healthPath, $property, $expected) {
    try {
        $response = Invoke-RestMethod -Uri "$($baseUrl.TrimEnd('/'))/$healthPath" -TimeoutSec 3
        return $response.$property -eq $expected
    } catch {
        return $false
    }
}

function Start-AiEngines {
    $defaultQwen = 'http://127.0.0.1:8180'
    $defaultExtractor = 'http://127.0.0.1:8181'
    $qwen = if ($env:RECORDON_AI_QWEN_URL) { $env:RECORDON_AI_QWEN_URL } else { $defaultQwen }
    $extractor = if ($env:RECORDON_AI_EXTRACTOR_URL) { $env:RECORDON_AI_EXTRACTOR_URL } else { $defaultExtractor }
    foreach ($endpoint in @($qwen, $extractor)) {
        if ($endpoint.TrimEnd('/') -notmatch '^http://127\.0\.0\.1:(\d{1,5})$') {
            throw 'AI engine URLs must use http://127.0.0.1:<port>.'
        }
        if ([int]$Matches[1] -lt 1 -or [int]$Matches[1] -gt 65535) {
            throw 'AI engine ports must be between 1 and 65535.'
        }
    }
    $docker = Find-DockerCli
    if (-not $docker -or $qwen.TrimEnd('/') -ne $defaultQwen -or $extractor.TrimEnd('/') -ne $defaultExtractor) {
        $missing = @()
        if (-not (Test-AiEngine $qwen 'health' 'status' 'ok')) { $missing += "Qwen: $qwen/health" }
        if (-not (Test-AiEngine $extractor 'healthz' 'up' $true)) { $missing += "PaddleOCR and Whisper: $extractor/healthz" }
        if ($missing.Count -eq 0) {
            Write-Host 'Using the running local AI engines.' -ForegroundColor Green
            return
        }
        $detail = 'Unavailable AI engines: ' + ($missing -join ', ')
        if (-not $docker) {
            throw "$detail. Docker was not found. Install and start Docker Desktop, or start both local AI engines separately. See README.md. Run .\run.ps1 for rule-based classification."
        }
        throw "$detail. Start the engines at the configured URLs or clear the custom URLs to use Docker Compose."
    }

    & $docker compose version --short
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose is unavailable. Install or update Docker Desktop.' }
    try { $engineType = & $docker info --format '{{.OSType}}' 2>$null } catch {
        throw 'Docker is installed, but its engine is unavailable. Start Docker Desktop and wait until it is ready.'
    }
    if ($LASTEXITCODE -ne 0) { throw 'Docker is installed, but its engine is unavailable. Start Docker Desktop and wait until it is ready.' }
    if ($engineType -ne 'linux') { throw 'The AI engines require Docker Linux containers. Switch Docker Desktop to Linux containers.' }
    & $docker compose -f compose.ai.yaml up -d --build
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose could not start the AI engines. Check the build output above.' }
}
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
        if ($Ai) {
            $aiStatus = $null
            try { $aiStatus = Invoke-RestMethod -Uri "$serverUrl/api/records/ai/status" -TimeoutSec 3 } catch {}
            if (-not $aiStatus.enabled) { throw 'Stop the current RecordOn server before starting it with -Ai.' }
        }
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
if ($Test) {
    & $maven "-Dmaven.repo.local=$repository" test
    exit $LASTEXITCODE
}
if ($Ai) {
    try {
        Start-AiEngines
    } catch {
        [Console]::Error.WriteLine($_.Exception.Message)
        exit 1
    }
}
$previousAiSetting = $env:RECORDON_AI_ENABLED
try {
    $env:RECORDON_AI_ENABLED = if ($Ai) { 'true' } else { 'false' }
    & $maven "-Dmaven.repo.local=$repository" spring-boot:run
    $exitCode = $LASTEXITCODE
} finally {
    $env:RECORDON_AI_ENABLED = $previousAiSetting
}
exit $exitCode
