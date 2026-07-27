<#
.SYNOPSIS
Runs the API on the host JVM under Java Flight Recorder, for profiling under load.

.DESCRIPTION
The cluster can tell you that CPU moved; it cannot tell you where it went. This runs the same
application on the host with a recording attached, so a load test against localhost produces a profile
with hot methods, allocation sites and lock contention.

Deliberately not the container: the point is to isolate the application's own cost from TLS termination
and ingress, which happen in Traefik in the cluster and not here at all. That is also this harness's
limit — a flat profile here does not rule out cost that only exists in the cluster.

.EXAMPLE
  docker compose up -d redis
  ./profile-local.ps1
  # then, from the load tester:
  #   ./loadtest.exe -mode v2 -v2-addr ws://localhost:8080 -sockets 300 -party-size 5 `
  #                  -v2-activity tob -combat-pct 50 -ramp 20s -duration 3m -report 10s
  # then:
  #   jfr summary rec.jfr
  #   jfr print --events jdk.ExecutionSample rec.jfr | more
  # or open rec.jfr in JDK Mission Control.
#>
[CmdletBinding()]
param(
	# Recording length. Give the load test time to ramp before this expires — it starts when the JVM does.
	[int]$DurationSeconds = 240,

	[string]$Recording = "rec.jfr",

	# Matches the deployed default. Lower it to see the unaggregated cost, raise it to see the ceiling.
	[int]$AggregateMs = 300,

	# Skip the build when only re-running a profile.
	[switch]$NoBuild
)

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$redis = docker compose ps --status running --services 2>$null
if ($redis -notcontains "redis") {
	Write-Warning "Redis does not appear to be running. Start it with: docker compose up -d redis"
}

if (-not $NoBuild) {
	Write-Host "Building..." -ForegroundColor Cyan
	./gradlew.bat bootJar -q
}

$jar = Get-ChildItem build/libs/*.jar |
	Where-Object { $_.Name -notlike "*-plain.jar" } |
	Select-Object -First 1
if (-not $jar) {
	throw "No boot jar in build/libs. Run without -NoBuild."
}

if (Test-Path $Recording) {
	Remove-Item $Recording
}

# settings=profile is the denser of the two built-in configs: it samples often enough to see a hot
# send path, which the default 'default' config will happily miss.
$jfr = "-XX:StartFlightRecording=duration=${DurationSeconds}s,filename=$Recording,settings=profile,name=osparty"

Write-Host "Recording $DurationSeconds s to $Recording" -ForegroundColor Cyan
Write-Host "Drive it now, e.g.:" -ForegroundColor DarkGray
Write-Host "  ./loadtest.exe -mode v2 -v2-addr ws://localhost:8080 -sockets 300 -party-size 5 -ramp 20s -duration 3m" -ForegroundColor DarkGray

$env:SPRING_DATA_REDIS_HOST = "localhost"
$env:SPRING_DATA_REDIS_PORT = "6379"
$env:APP_PARTY_V2_ENABLED = "true"
$env:APP_PARTY_V2_AGGREGATE_MS = "$AggregateMs"
# One node, so it always owns every room it is asked to host: no redirects to chase locally.
$env:APP_PARTY_V2_NODE_ID = "local"

& java $jfr -jar $jar.FullName

Write-Host ""
if (Test-Path $Recording) {
	Write-Host "Wrote $Recording" -ForegroundColor Green
	Write-Host "  jfr summary $Recording" -ForegroundColor DarkGray
	Write-Host "  jfr print --events jdk.ExecutionSample $Recording" -ForegroundColor DarkGray
}
else {
	Write-Warning "No recording written — the JVM may have exited before $DurationSeconds s elapsed."
}
