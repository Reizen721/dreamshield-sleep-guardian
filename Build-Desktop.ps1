$ErrorActionPreference = 'Stop'
$source = Join-Path $PSScriptRoot 'desktop'
$sdk = Join-Path $source 'deps\webview2'
$output = Join-Path $PSScriptRoot 'build'
$compiler = Join-Path $env:windir 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
if (-not (Test-Path -LiteralPath $compiler)) { throw 'The 64-bit .NET Framework C# compiler is required.' }
New-Item -ItemType Directory -Path $output -Force | Out-Null
$exe = Join-Path $output 'DreamShield.exe'
$sources = 'Program.cs','StartupMotion.cs','AssemblyInfo.cs' | ForEach-Object { Join-Path $source $_ }
$references = 'Microsoft.Web.WebView2.Core.dll','Microsoft.Web.WebView2.WinForms.dll' | ForEach-Object { '/r:' + (Join-Path $sdk $_) }
& $compiler /nologo /target:winexe /platform:x64 /langversion:5 /codepage:65001 /optimize+ "/out:$exe" `
    "/win32manifest:$source\app.manifest" "/win32icon:$source\DreamShield.ico" `
    /r:System.dll /r:System.Core.dll /r:System.Drawing.dll /r:System.Windows.Forms.dll `
    /r:System.Net.Http.dll /r:System.Web.Extensions.dll @references @sources
if ($LASTEXITCODE -ne 0) { throw 'Desktop build failed.' }
foreach ($name in 'Microsoft.Web.WebView2.Core.dll','Microsoft.Web.WebView2.WinForms.dll','WebView2Loader.dll') {
    Copy-Item -LiteralPath (Join-Path $sdk $name) -Destination $output -Force
}
Copy-Item -LiteralPath (Join-Path $source 'THIRD_PARTY_NOTICES.txt') -Destination $output -Force
Copy-Item -LiteralPath (Join-Path $source 'DreamShield.exe.config') -Destination $output -Force
Write-Output $exe
