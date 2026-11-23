param(
    [string]$Database = "repair_codex_test",
    [string[]]$MavenArgs = @("-B", "test")
)

$ErrorActionPreference = "Stop"

if ($Database -notmatch '^[A-Za-z0-9_]{1,48}$') {
    throw "Database must contain only letters, digits, and underscores."
}

$projectRoot = Split-Path -Parent $PSScriptRoot
$envFile = Join-Path $projectRoot ".env"
if (-not (Test-Path -LiteralPath $envFile)) {
    throw "Missing .env. Copy .env.example to .env and configure local dependencies first."
}

$values = @{}
foreach ($line in Get-Content -LiteralPath $envFile) {
    if ($line -match '^\s*([^#\s=][^=]*)=(.*)$') {
        $value = $matches[2].Trim()
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$matches[1].Trim()] = $value
    }
}

if (-not $values["MYSQL_ROOT_PASSWORD"]) {
    throw ".env must define MYSQL_ROOT_PASSWORD."
}

# Use only local dependencies and a dedicated test database/bucket; never load AI keys.
$testHost = if ($values["MYSQL_HOST"]) { $values["MYSQL_HOST"] } else { "127.0.0.1" }
if ($testHost -notin @("127.0.0.1", "localhost")) {
    throw "Local tests require a loopback MYSQL_HOST."
}
if ($Database -notmatch '^repair_[A-Za-z0-9_]*test[A-Za-z0-9_]*$') {
    throw "Use a dedicated database named repair_*test*."
}

$environmentNames = @("MYSQL_USERNAME", "MYSQL_PASSWORD", "MYSQL_DATABASE", "MYSQL_HOST", "MYSQL_PORT",
    "MYSQL_PWD", "REDIS_HOST", "REDIS_PORT", "REDIS_PASSWORD", "MINIO_ENDPOINT", "MINIO_PUBLIC_BASE_URL",
    "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY", "MINIO_BUCKET", "REPAIR_DEMO_ENABLED")
$previous = @{}
foreach ($name in $environmentNames) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

Push-Location $projectRoot
try {
    # Match docker-compose.yml's MYSQL_ROOT_PASSWORD -> MYSQL_PASSWORD mapping.
    $env:MYSQL_USERNAME = if ($values["MYSQL_USERNAME"]) { $values["MYSQL_USERNAME"] } else { "root" }
    $env:MYSQL_PASSWORD = $values["MYSQL_ROOT_PASSWORD"]
    $env:MYSQL_DATABASE = $Database
    $env:MYSQL_HOST = $testHost
    $env:MYSQL_PORT = "13306"
    $env:REDIS_PASSWORD = $values["REDIS_PASSWORD"]
    $env:REDIS_HOST = "127.0.0.1"
    $env:REDIS_PORT = "6379"
    $env:MINIO_ENDPOINT = "http://127.0.0.1:9000"
    $env:MINIO_PUBLIC_BASE_URL = "http://127.0.0.1:9000"
    $env:MINIO_BUCKET = "repair-codex-test"
    $env:REPAIR_DEMO_ENABLED = "false"
    $env:MINIO_ACCESS_KEY = if ($values["MINIO_ACCESS_KEY"]) { $values["MINIO_ACCESS_KEY"] } else { $values["MINIO_ROOT_USER"] }
    $env:MINIO_SECRET_KEY = if ($values["MINIO_SECRET_KEY"]) { $values["MINIO_SECRET_KEY"] } else { $values["MINIO_ROOT_PASSWORD"] }
    # The local test DB is separate from the developer database configured in .env.
    $env:MYSQL_PWD = $env:MYSQL_PASSWORD
    $createDatabase = "CREATE DATABASE IF NOT EXISTS ``$Database`` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
    & docker exec -e MYSQL_PWD repair-mysql mysql "-u$($env:MYSQL_USERNAME)" -e $createDatabase
    if ($LASTEXITCODE -ne 0) {
        throw "Could not create or reach the local MySQL test database. Check that repair-mysql is running."
    }

    $wrapperProperties = Get-Content -LiteralPath (Join-Path (Get-Location) ".mvn/wrapper/maven-wrapper.properties")
    $distributionUrl = ($wrapperProperties | Where-Object { $_ -match '^distributionUrl=' } | Select-Object -First 1) -replace '^distributionUrl=', ''
    $distributionName = [System.IO.Path]::GetFileName(([Uri]$distributionUrl).AbsolutePath) -replace '\.zip$', ''
    $distributionRoot = Join-Path $env:USERPROFILE ".m2/wrapper/dists/$distributionName"
    $maven = Get-ChildItem -LiteralPath $distributionRoot -Filter "mvn.cmd" -File -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $maven) {
        throw "Maven Wrapper distribution is not cached at $distributionRoot. Run .\mvnw.cmd once to download it."
    }

    & $maven @MavenArgs
    $testExitCode = $LASTEXITCODE
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], "Process")
    }
    Pop-Location
}
exit $testExitCode
