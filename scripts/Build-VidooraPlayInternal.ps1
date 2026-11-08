[CmdletBinding()]
param([ValidateRange(1,2100000000)][int]$VersionCode=1,[string]$ApiBaseUrl='http://127.0.0.1:8080/',[string]$ShareHost='links.invalid')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$signingRoot=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Vidoora\Signing'
$storePath=Join-Path $signingRoot 'vidoora-upload.p12'
$passwordPath=Join-Path $signingRoot 'upload-password.dpapi'
if(-not (Test-Path -LiteralPath $storePath) -or -not (Test-Path -LiteralPath $passwordPath)){throw 'Create your private Vidoora upload key first. Do not paste passwords into chat.'}
$protected=(Get-Content -LiteralPath $passwordPath -Raw).Trim()
$secret=ConvertTo-SecureString $protected
$pointer=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
$names=@('VIDOORA_UPLOAD_STORE_FILE','VIDOORA_UPLOAD_STORE_PASSWORD','VIDOORA_UPLOAD_KEY_ALIAS','VIDOORA_UPLOAD_KEY_PASSWORD')
$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name,'Process')}
try {
    $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    $env:VIDOORA_UPLOAD_STORE_FILE=$storePath;$env:VIDOORA_UPLOAD_STORE_PASSWORD=$plain
    $env:VIDOORA_UPLOAD_KEY_ALIAS='vidoora-upload';$env:VIDOORA_UPLOAD_KEY_PASSWORD=$plain
    if(-not $env:JAVA_HOME){$env:JAVA_HOME=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'}
    Push-Location $projectRoot
    try {
        & .\gradlew.bat :app:bundlePlayInternal "-PvidooraPlayVersionCode=$VersionCode" "-PvidooraInternalApiBaseUrl=$ApiBaseUrl" "-PvidooraShareHost=$ShareHost" '-Pkotlin.compiler.execution.strategy=in-process' --no-daemon --no-configuration-cache --no-watch-fs --max-workers=1 --console=plain
        if($LASTEXITCODE -ne 0){throw 'The signed internal bundle build failed.'}
    } finally {Pop-Location}
    Write-Host "Signed bundle: $projectRoot\app\build\outputs\bundle\playInternal\app-playInternal.aab"
} finally {
    foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name],'Process')}
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer);$plain=$null;$secret.Dispose()
}
