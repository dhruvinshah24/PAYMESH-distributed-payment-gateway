$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location (Join-Path $root 'frontend')
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) { throw 'Node.js/npm is not on PATH. Install Node.js 20+.' }
if (-not (Test-Path 'node_modules')) { npm.cmd install }
Write-Host 'Starting PAYMESH frontend on http://localhost:5173 ...' -ForegroundColor Cyan
npm.cmd run dev -- --host 0.0.0.0
