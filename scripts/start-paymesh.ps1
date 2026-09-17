$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

Write-Host '========================================='
Write-Host ' PAYMESH LOCAL STARTUP' -ForegroundColor Cyan
Write-Host '========================================='

& (Join-Path $PSScriptRoot 'setup-mysql.ps1')

Start-Process powershell -ArgumentList '-NoExit','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-backend.ps1')
Start-Sleep -Seconds 3
Start-Process powershell -ArgumentList '-NoExit','-ExecutionPolicy','Bypass','-File',(Join-Path $PSScriptRoot 'start-frontend.ps1')
Start-Sleep -Seconds 5
Start-Process 'http://localhost:5173'

Write-Host 'PAYMESH launch requested. Browser: http://localhost:5173' -ForegroundColor Green
