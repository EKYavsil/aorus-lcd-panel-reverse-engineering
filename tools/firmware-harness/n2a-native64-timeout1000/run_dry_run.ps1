$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $Here
try {
    dotnet .\N2aFirmwareHarness.dll
} finally {
    Pop-Location
}


