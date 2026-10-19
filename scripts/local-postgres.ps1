param([ValidateSet('start','stop','status')][string]$Action='start')
$ErrorActionPreference='Stop'
$atlasRoot=Split-Path -Parent $PSScriptRoot
$atlasRuntime=Join-Path $atlasRoot '.runtime'
$atlasBin=Join-Path $atlasRuntime 'postgres\bin'
$atlasData=Join-Path $atlasRuntime 'pgdata'
$atlasLog=Join-Path $atlasRuntime 'postgres.log'

# Optional Windows fallback, independent of Docker. Changes only this checkout's .runtime.
if($Action -eq 'stop') {
    & "$atlasBin\pg_ctl.exe" -D $atlasData -m fast -w stop
    exit $LASTEXITCODE
}
if($Action -eq 'status') {
    & "$atlasBin\pg_ctl.exe" -D $atlasData status
    exit $LASTEXITCODE
}
if(!(Test-Path -LiteralPath "$atlasBin\postgres.exe")) {
    $atlasDownload=Join-Path $atlasRuntime 'pg-download'
    New-Item -ItemType Directory -Force -Path $atlasDownload | Out-Null
    $atlasJar=Join-Path $atlasDownload 'postgres.jar'
    $atlasUrl='https://repo.maven.apache.org/maven2/io/zonky/test/postgres/embedded-postgres-binaries-windows-amd64/17.11.0/embedded-postgres-binaries-windows-amd64-17.11.0.jar'
    Invoke-WebRequest -Uri $atlasUrl -OutFile $atlasJar
    $atlasChecksum='98040fae18dd9633ff95932125b0cecf0a45a1a9312e216e2a33ad03a31d4251'
    if((Get-FileHash -LiteralPath $atlasJar -Algorithm SHA256).Hash.ToLower() -ne $atlasChecksum) { throw 'PostgreSQL artifact checksum mismatch' }
    & tar -xf $atlasJar -C $atlasDownload
    if($LASTEXITCODE -ne 0){throw 'Artifact extraction failed'}
    $atlasExtract=Join-Path $atlasRuntime 'postgres'
    New-Item -ItemType Directory -Force -Path $atlasExtract | Out-Null
    & tar -xf (Join-Path $atlasDownload 'postgres-windows-x86_64.txz') -C $atlasExtract
    if($LASTEXITCODE -ne 0){throw 'PostgreSQL extraction failed'}
}
if(!(Test-Path -LiteralPath (Join-Path $atlasData 'PG_VERSION'))) {
    $atlasPasswordFile=Join-Path $atlasRuntime 'pg-password'
    try {
        [IO.File]::WriteAllText($atlasPasswordFile,'atlas-local-only')
        & "$atlasBin\initdb.exe" -D $atlasData -U atlas "--pwfile=$atlasPasswordFile" --auth=scram-sha-256 --encoding=UTF8 --locale=C
        if($LASTEXITCODE -ne 0){throw 'PostgreSQL initialization failed'}
    } finally { Remove-Item -LiteralPath $atlasPasswordFile -ErrorAction SilentlyContinue }
}
& "$atlasBin\pg_ctl.exe" -D $atlasData status
if($LASTEXITCODE -ne 0){
    & "$atlasBin\pg_ctl.exe" -D $atlasData -l $atlasLog -o '-h 127.0.0.1 -p 5433' -w start
    if($LASTEXITCODE -ne 0){throw "PostgreSQL failed to start; see $atlasLog"}
}
$atlasDriver=Join-Path $atlasRuntime 'postgresql-42.7.13.jar'
if(!(Test-Path -LiteralPath $atlasDriver)) {
    Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/postgresql/postgresql/42.7.13/postgresql-42.7.13.jar' -OutFile $atlasDriver
}
if((Get-FileHash -LiteralPath $atlasDriver -Algorithm SHA256).Hash.ToLower() -ne '6e0e4cc2d8cae902084f8a2b18728b073a6fd9d1f87c9d8bff8f298c18185b93') { throw 'JDBC artifact checksum mismatch' }
& java -cp $atlasDriver (Join-Path $PSScriptRoot 'CreateDatabases.java')
if($LASTEXITCODE -ne 0){throw 'Database bootstrap failed'}
Write-Output 'PostgreSQL ready on 127.0.0.1:5433 (atlas and disposable atlas_test). No Docker required.'
Write-Output 'For native API: $env:ATLAS_DB_URL="jdbc:postgresql://localhost:5433/atlas"'
