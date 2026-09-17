$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080/api'
$checks = @('/health','/state','/payments','/consistency','/ledger/verify','clock-sync','election')
foreach ($path in $checks) {
  $url = if ($path.StartsWith('/')) { "$base$path" } else { "$base/$path" }
  $r = Invoke-WebRequest $url -UseBasicParsing
  if ($r.StatusCode -ne 200) { throw "$url returned HTTP $($r.StatusCode)" }
  Write-Host "OK  $url" -ForegroundColor Green
}
Write-Host 'PAYMESH API smoke test passed.' -ForegroundColor Green
