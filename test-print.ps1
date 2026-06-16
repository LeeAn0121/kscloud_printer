$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$uiScript = Join-Path $scriptDir "tools\printer-test-ui.ps1"

powershell.exe -ExecutionPolicy Bypass -File $uiScript

