# ASCII only: Windows PowerShell 5.1 otherwise misreads UTF-8 without BOM.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
  & "$root\.venv\Scripts\python.exe" -m pytest model-service\tests -q
  if ($LASTEXITCODE -ne 0) { throw 'FastAPI tests failed.' }

  # Stop development servers first: they share backend/target/classes with Maven tests.
  $backendListener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
  if ($backendListener) { throw 'Stop the development backend before running this script.' }
  mvn -f backend\pom.xml test
  if ($LASTEXITCODE -ne 0) { throw 'Spring Boot tests failed.' }

  npm --prefix frontend run build
  if ($LASTEXITCODE -ne 0) { throw 'Vue build failed.' }

  Write-Output 'Automated checks completed. MySQL DDL and credentials still require separate verification.'
} finally {
  Pop-Location
}
