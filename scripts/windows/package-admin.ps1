#requires -Version 5.1
[CmdletBinding()]
param([switch]$SkipBuild)
$ErrorActionPreference = 'Stop'
$repositoryRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$version = (Get-Content -LiteralPath (Join-Path $repositoryRoot 'package.json') -Raw | ConvertFrom-Json).version
if (!$SkipBuild) {
    & pnpm.cmd --dir (Join-Path $repositoryRoot 'apps\admin\frontend') build
    if ($LASTEXITCODE -ne 0) { throw 'Administrator web build failed.' }
    & mvn.cmd -f (Join-Path $repositoryRoot 'apps\admin\backend\pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Administrator backend build failed.' }
}
$sourceJar = Join-Path $repositoryRoot 'apps\admin\backend\target\web-backend-0.1.0-SNAPSHOT.jar'
$sourceWeb = Join-Path $repositoryRoot 'apps\admin\frontend\dist'
if (!(Test-Path -LiteralPath $sourceJar -PathType Leaf) -or !(Test-Path -LiteralPath (Join-Path $sourceWeb 'index.html') -PathType Leaf)) { throw 'Build the backend and web before packaging.' }
$packageDirectory = Join-Path $repositoryRoot "dist\backend-v$version"
$webDirectory = Join-Path $packageDirectory 'web'
New-Item -ItemType Directory -Path $webDirectory -Force | Out-Null
Copy-Item -LiteralPath $sourceJar -Destination (Join-Path $packageDirectory 'backend.jar') -Force
Get-ChildItem -LiteralPath $sourceWeb -Force | Copy-Item -Destination $webDirectory -Recurse -Force
$webFiles = @(Get-ChildItem -LiteralPath $webDirectory -Recurse -File | ForEach-Object {
    [ordered]@{ path = $_.FullName.Substring($webDirectory.Length + 1).Replace('\','/'); sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
})
$manifest = [ordered]@{ version = $version; createdAt = [DateTime]::UtcNow.ToString('o'); backendSha256 = (Get-FileHash -LiteralPath (Join-Path $packageDirectory 'backend.jar') -Algorithm SHA256).Hash; web = $webFiles }
$manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $packageDirectory 'package-manifest.json') -Encoding utf8
@'
#requires -Version 5.1
$ErrorActionPreference = 'Stop'
$repository = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
& (Join-Path $repository 'scripts\windows\start-admin-package.ps1') -PackageDirectory $PSScriptRoot @args
'@ | Set-Content -LiteralPath (Join-Path $packageDirectory 'start.ps1') -Encoding utf8
@"
Codex Quota Guard administrator package $version

Keep this folder in the project's dist directory. Run start.ps1 to launch the
packaged Java API and production web assets using this project's existing Node
dependencies. Java 17 and the existing protected Windows configuration are required.
This is a local project package, not a self-contained offline server installer.

Web: http://localhost:8000/#/user/login
API: http://127.0.0.1:18080

The launcher prints the exact stop command. Existing database credentials and
encryption keys are read from the protected configuration, never from this package.
No credentials, database contents or private backup files are included in this ZIP.
"@ | Set-Content -LiteralPath (Join-Path $packageDirectory 'README.txt') -Encoding utf8
$archive = Join-Path $repositoryRoot "dist\backend-v$version.zip"
Compress-Archive -LiteralPath $packageDirectory -DestinationPath $archive -CompressionLevel Optimal -Force
[pscustomobject]@{ Version = $version; Directory = $packageDirectory; Archive = $archive; BackendSha256 = $manifest.backendSha256 }
