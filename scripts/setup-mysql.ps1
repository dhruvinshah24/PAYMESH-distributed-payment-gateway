$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$schema = Join-Path $root 'database\schema.sql'

function Find-MySql {
  $cmd = Get-Command mysql.exe -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $candidates = @(
    'C:\Program Files\MySQL\MySQL Server 8.4\bin\mysql.exe',
    'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
    'C:\xampp\mysql\bin\mysql.exe'
  )
  foreach ($p in $candidates) { if (Test-Path $p) { return $p } }
  throw 'mysql.exe not found. Install MySQL Server 8.x and ensure mysql.exe is on PATH.'
}

$mysql = Find-MySql
$user = if ($env:DB_USER) { $env:DB_USER } else { 'root' }
$password = if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { 'root' }
$host = if ($env:DB_HOST) { $env:DB_HOST } else { 'localhost' }
$port = if ($env:DB_PORT) { $env:DB_PORT } else { '3306' }

Write-Host "Using MySQL: $mysql"
Write-Host "Importing: $schema"
Write-Host "Database host: ${host}:${port} user=$user"

& $mysql --host=$host --port=$port --user=$user --password=$password --default-character-set=utf8mb4 --show-warnings < $schema
if ($LASTEXITCODE -ne 0) { throw "Schema import failed with exit code $LASTEXITCODE" }

Write-Host 'PAYMESH database setup completed successfully.' -ForegroundColor Green
