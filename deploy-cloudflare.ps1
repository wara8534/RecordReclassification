param([switch]$CheckOnly)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw 'Install Node.js 22+ before deploying.'
}
if (-not (Test-Path -LiteralPath 'node_modules/wrangler/bin/wrangler.js')) {
    & npm.cmd ci
    if ($LASTEXITCODE -ne 0) { throw 'npm ci failed.' }
}
& npm.cmd test
if ($LASTEXITCODE -ne 0) { throw 'Cloudflare routing tests failed.' }
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker is not installed. Install Docker Desktop and enable Linux containers, then retry.'
}
$dockerOs = & docker info --format '{{.OSType}}'
if ($LASTEXITCODE -ne 0) { throw 'Start Docker Desktop before deploying.' }
if ($dockerOs.Trim() -ne 'linux') { throw 'Switch Docker Desktop to Linux containers before deploying.' }
$accountCheck = & node node_modules/wrangler/bin/wrangler.js whoami --json
if ($LASTEXITCODE -ne 0) { throw 'Cloudflare account check failed. Run npm.cmd run cloudflare:login.' }
if ($CheckOnly) {
    Write-Host 'Checks completed. Ensure your Cloudflare account has Workers Paid enabled.'
    exit 0
}
Write-Host 'Deploying Cloudflare Containers. Workers Paid and usage charges apply.'
& npm.cmd run cloudflare:deploy
exit $LASTEXITCODE
