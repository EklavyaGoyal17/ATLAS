# Windows verification entry point. Uses only the public local example configuration.
# Truncation is performed only by guarded integration tests against atlas_test.
$ErrorActionPreference='Stop'
$atlasRoot=Split-Path -Parent $PSScriptRoot
$atlasPreviousToken=$env:ATLAS_API_TOKEN
$atlasPreviousTestUrl=$env:ATLAS_TEST_DB_URL
$atlasPreviousExternal=$env:ATLAS_E2E_EXTERNAL
function Invoke-AtlasCheck([string]$Name,[scriptblock]$Command) {
    Write-Host "Checking: $Name"
    & $Command
    if($LASTEXITCODE -ne 0){throw "$Name failed (exit $LASTEXITCODE)"}
}
Push-Location $atlasRoot
try {
    $env:ATLAS_API_TOKEN='atlas-local-development-token-change-me'
    $env:ATLAS_TEST_DB_URL='jdbc:postgresql://localhost:5433/atlas_test'
    $env:ATLAS_E2E_EXTERNAL='true'
    Invoke-AtlasCheck 'disposable PostgreSQL health' { docker compose --env-file .env.example --profile test up -d --wait test-db }
    Invoke-AtlasCheck 'backend unit, integration, concurrency, migration and recovery tests' { mvn -B -f backend/pom.xml verify }
    Invoke-AtlasCheck 'Java 21 Linux test runner build' { docker build --target build -t atlas-test-runner backend }
    Invoke-AtlasCheck 'Java 21 full suite including Linux symlink controls' { docker run --rm --network container:atlas-test-db-1 -e ATLAS_TEST_DB_URL=jdbc:postgresql://localhost:5432/atlas_test atlas-test-runner mvn -B verify }
    Push-Location (Join-Path $atlasRoot 'frontend')
    try {
        Invoke-AtlasCheck 'locked frontend dependencies' { npm.cmd ci }
        Invoke-AtlasCheck 'frontend TypeScript and production build' { npm.cmd run build }
        Invoke-AtlasCheck 'frontend dependency audit' { npm.cmd audit --audit-level=high }
        Invoke-AtlasCheck 'Chromium availability' { npx.cmd playwright install chromium }
    } finally { Pop-Location }
    Invoke-AtlasCheck 'Compose images and health' { docker compose --env-file .env.example up --build -d --wait db api console }
    Push-Location (Join-Path $atlasRoot 'frontend')
    try { Invoke-AtlasCheck 'browser workflows against actual containers' { npm.cmd run test:e2e } }
    finally { Pop-Location }
    Invoke-AtlasCheck 'API container restart and duplicate suppression' { node scripts/recovery-check.mjs }
    Invoke-AtlasCheck 'real Git history and tracked diff' { node scripts/git-evidence-check.mjs }
    Invoke-AtlasCheck 'bounded read-endpoint latency observations' { node scripts/workspace-load.mjs }
    Write-Host 'All verification stages passed. Local Compose application remains available at http://127.0.0.1:5173.'
} finally {
    $env:ATLAS_API_TOKEN=$atlasPreviousToken
    $env:ATLAS_TEST_DB_URL=$atlasPreviousTestUrl
    $env:ATLAS_E2E_EXTERNAL=$atlasPreviousExternal
    Pop-Location
}
