$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $root 'backend')
if (-not (Get-Command mvn.cmd -ErrorAction SilentlyContinue)) { throw 'Maven is not on PATH. Install Maven 3.9+ or use your IDE Maven runner.' }
if (-not (Get-Command java.exe -ErrorAction SilentlyContinue)) { throw 'Java is not on PATH. Install JDK 17+.' }
Write-Host 'Starting PAYMESH backend on http://localhost:8080 ...' -ForegroundColor Cyan
mvn.cmd spring-boot:run
