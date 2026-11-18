param(
  [switch]$WithMySql
)

# ASCII only: Windows PowerShell 5.1 otherwise misreads UTF-8 without BOM.
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$services = @(
  @{ Name = 'model-service'; Port = 8000 },
  @{ Name = 'backend'; Port = 8080 },
  @{ Name = 'frontend'; Port = 5173 }
)

function Test-Port([string]$Name, [int]$Port) {
  $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
  if ($listener) { throw "$Name requires port $Port, which is already in use. Stop the conflicting process yourself." }
}

function Test-Command([string]$Name) {
  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "Command not found: $Name. Install the prerequisites listed in README.md."
  }
}

foreach ($service in $services) { Test-Port $service.Name $service.Port }
Test-Command 'mvn'
Test-Command 'npm'

$python = Join-Path $root '.venv\Scripts\python.exe'
if (-not (Test-Path $python)) { throw 'Missing .venv. Create it and install model-service/requirements-dev.txt.' }
if (-not (Test-Path (Join-Path $root 'backend\pom.xml'))) { throw 'Missing backend/pom.xml.' }
if (-not (Test-Path (Join-Path $root 'frontend\node_modules'))) { throw 'Missing frontend/node_modules. Run npm --prefix frontend ci.' }

if ($WithMySql) {
  foreach ($name in 'DB_URL', 'DB_USERNAME', 'DB_PASSWORD') {
    if (-not [Environment]::GetEnvironmentVariable($name)) {
      throw "MySQL mode requires environment variable $name. Inject it securely; never commit it."
    }
  }
} else {
  Write-Warning 'In-memory contract demo: DEGRADED and database=BYPASS. This does not test MySQL.'
}

$runtime = Join-Path $root 'runtime'
New-Item -ItemType Directory -Path $runtime -Force | Out-Null
$jobs = @()

try {
  $jobs += Start-Process -FilePath $python -ArgumentList '-m','uvicorn','app.main:app','--host','127.0.0.1','--port','8000' `
    -WorkingDirectory (Join-Path $root 'model-service') -RedirectStandardOutput (Join-Path $runtime 'model.log') -RedirectStandardError (Join-Path $runtime 'model-error.log') -PassThru

  $mvnArguments = @('spring-boot:run')
  if ($WithMySql) { $mvnArguments += '-Dspring-boot.run.profiles=mysql' }
  $jobs += Start-Process -FilePath 'mvn' -ArgumentList $mvnArguments -WorkingDirectory (Join-Path $root 'backend') `
    -RedirectStandardOutput (Join-Path $runtime 'backend.log') -RedirectStandardError (Join-Path $runtime 'backend-error.log') -PassThru

  $jobs += Start-Process -FilePath 'npm.cmd' -ArgumentList 'run','dev','--prefix','frontend' -WorkingDirectory $root `
    -RedirectStandardOutput (Join-Path $runtime 'frontend.log') -RedirectStandardError (Join-Path $runtime 'frontend-error.log') -PassThru

  $deadline = [DateTime]::UtcNow.AddSeconds(90)
  $modelReady = $false
  $apiReady = $false
  $status = 'UNKNOWN'
  while ([DateTime]::UtcNow -lt $deadline -and -not ($modelReady -and $apiReady)) {
    Start-Sleep -Milliseconds 600
    foreach ($job in $jobs) {
      if ($job.HasExited) { throw "Service process $($job.Id) exited. See runtime/*-error.log." }
    }
    if (-not $modelReady) {
      try { $modelReady = (Invoke-RestMethod -Uri 'http://127.0.0.1:8000/health' -TimeoutSec 2).status -eq 'UP' } catch {}
    }
    if (-not $apiReady) {
      try {
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:8080/api/v1/health' -TimeoutSec 3 -UseBasicParsing
        $httpCode = [int]$response.StatusCode
        $content = $response.Content
      } catch {
        $httpCode = 0
        $content = ''
        $errorResponse = $_.Exception.Response
        if ($errorResponse) {
          $httpCode = [int]$errorResponse.StatusCode
          $reader = New-Object System.IO.StreamReader($errorResponse.GetResponseStream())
          $content = $reader.ReadToEnd()
          $reader.Dispose()
        }
      }
      if ($httpCode -eq 200 -or $httpCode -eq 503) {
        try {
          $payload = $content | ConvertFrom-Json
          $status = [string]$payload.status
          $apiReady = ($status -eq 'UP' -and $httpCode -eq 200) -or ($status -eq 'DEGRADED' -and $httpCode -eq 503)
        } catch {}
      }
    }
  }
  if (-not ($modelReady -and $apiReady)) { throw 'Health-check timeout. See runtime/ logs.' }
  if ($WithMySql -and $status -ne 'UP') { throw 'MySQL mode is not fully healthy. See runtime/ logs.' }

  Write-Output ''
  Write-Output 'Frontend: http://127.0.0.1:5173'
  Write-Output 'FastAPI docs: http://127.0.0.1:8000/docs'
  Write-Output 'Java health: http://127.0.0.1:8080/api/v1/health'
  Write-Output "Aggregate health: $status"
  Write-Output 'Press Ctrl+C to stop only the processes started by this script.'
  while ($true) {
    Start-Sleep -Seconds 1
    foreach ($job in $jobs) {
      if ($job.HasExited) { throw "Service process $($job.Id) exited. See runtime/ logs." }
    }
  }
} finally {
  foreach ($job in $jobs) {
    if ($job -and -not $job.HasExited) {
      # Only terminate the process trees whose root PIDs were created above.
      & taskkill.exe /PID $job.Id /T /F | Out-Null
    }
  }
}
