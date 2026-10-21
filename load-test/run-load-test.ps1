<#
.SYNOPSIS
  Runs the k6 transfer load test against a running LedgerCore instance, then verifies the
  money it moved never drifted - the total balance across the load test's own accounts must
  come out exactly where it started, since every transfer only ever moves money between them.

.EXAMPLE
  ./run-load-test.ps1 -PgPassword 'your-postgres-password'
#>
param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$PgHost = "localhost",
    [string]$PgUser = "postgres",
    [string]$PgPassword = $env:DB_PASSWORD,
    [string]$PgDatabase = "ledgercore",
    [int]$AccountCount = 100,
    [long]$OpeningBalance = 10000000
)

if (-not $PgPassword) {
    Write-Error "No Postgres password supplied - pass -PgPassword or set `$env:DB_PASSWORD first."
    exit 1
}

$RunId = Get-Date -Format "yyyyMMddHHmmss"
$env:BASE_URL = $BaseUrl
$env:ACCOUNT_COUNT = $AccountCount
$env:OPENING_BALANCE_MINOR_UNITS = $OpeningBalance
$env:RUN_ID = $RunId

$resultsDir = Join-Path $PSScriptRoot "..\docs\test-results"
New-Item -ItemType Directory -Force -Path $resultsDir | Out-Null
$summaryJsonPath = Join-Path $resultsDir "k6-summary-$RunId.json"
$scriptPath = Join-Path $PSScriptRoot "transfer-load-test.js"

Write-Output "=== Running k6 load test (run id: $RunId) ==="
k6 run --summary-export $summaryJsonPath $scriptPath
$k6ExitCode = $LASTEXITCODE

Write-Output ""
Write-Output "=== Verifying balance conservation for run $RunId ==="
$env:PGPASSWORD = $PgPassword
$expectedTotal = [int64]$AccountCount * [int64]$OpeningBalance
$query = "SELECT COALESCE(SUM(balance_minor_units),0) FROM accounts WHERE account_number LIKE 'LOADTEST-$RunId-%';"
$actualTotalRaw = & psql -U $PgUser -h $PgHost -d $PgDatabase -tAc $query
$actualTotal = [int64]($actualTotalRaw.Trim())
$drift = $actualTotal - $expectedTotal

Write-Output "Expected total (AccountCount x OpeningBalance): $expectedTotal"
Write-Output "Actual total across LOADTEST-$RunId-* accounts:  $actualTotal"
if ($drift -eq 0) {
    Write-Output "RESULT: PASS - balance conserved exactly, zero drift."
} else {
    Write-Output "RESULT: FAIL - balance drifted by $drift minor units."
}

$conservationReport = Join-Path $resultsDir "conservation-check-$RunId.txt"
@(
    "Run ID: $RunId"
    "Account count: $AccountCount"
    "Opening balance per account (minor units): $OpeningBalance"
    "Expected total: $expectedTotal"
    "Actual total:   $actualTotal"
    "Drift:          $drift"
    "Result:         $(if ($drift -eq 0) { 'PASS' } else { 'FAIL' })"
) | Set-Content -Path $conservationReport -Encoding utf8

Write-Output ""
Write-Output "k6 summary JSON:        $summaryJsonPath"
Write-Output "Conservation report:    $conservationReport"

exit $k6ExitCode
