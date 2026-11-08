# Run manually in PowerShell. Passwords are entered privately and are never printed.
[CmdletBinding()]
param()
$ErrorActionPreference='Stop'
$signingRoot=Join-Path ([Environment]::GetFolderPath('LocalApplicationData')) 'Vidoora\Signing'
$storePath=Join-Path $signingRoot 'vidoora-upload.p12'
$passwordPath=Join-Path $signingRoot 'upload-password.dpapi'
if((Test-Path -LiteralPath $storePath) -or (Test-Path -LiteralPath $passwordPath)){throw 'Signing material already exists. Nothing was overwritten.'}
$jdkRoot=$env:JAVA_HOME
if(-not $jdkRoot){$jdkRoot=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'}
$keytool=Join-Path $jdkRoot 'bin\keytool.exe'
if(-not (Test-Path -LiteralPath $keytool)){throw 'Set JAVA_HOME to your installed JDK first.'}
$first=Read-Host 'Choose a strong Vidoora upload-key password (save it in your password manager)' -AsSecureString
$second=Read-Host 'Enter it again' -AsSecureString
$firstPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($first)
$secondPtr=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($second)
try {
    $plain=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($firstPtr)
    $confirm=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($secondPtr)
    if($plain.Length -lt 12 -or $plain -cne $confirm){throw 'Passwords must match and contain at least 12 characters.'}
    New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
    $env:VIDOORA_NEW_UPLOAD_PASSWORD=$plain
    & $keytool -genkeypair -keystore $storePath -storetype PKCS12 -alias vidoora-upload -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Vidoora Upload' -storepass:env VIDOORA_NEW_UPLOAD_PASSWORD -keypass:env VIDOORA_NEW_UPLOAD_PASSWORD
    if($LASTEXITCODE -ne 0){throw 'Key generation failed; signing was not configured.'}
    $first | ConvertFrom-SecureString | Set-Content -LiteralPath $passwordPath -Encoding UTF8
    & $keytool -exportcert -rfc -keystore $storePath -alias vidoora-upload -file (Join-Path $signingRoot 'upload-certificate.pem') -storepass:env VIDOORA_NEW_UPLOAD_PASSWORD
    if($LASTEXITCODE -ne 0){throw 'Certificate export failed. Keep the generated key and password safe.'}
    Write-Host "Upload key saved privately in $signingRoot"
    Write-Host 'Back up the .p12 file securely and keep its password separately in your password manager.'
    Write-Host 'The DPAPI file works only with this Windows user profile; it is not a portable password backup.'
} finally {
    Remove-Item Env:VIDOORA_NEW_UPLOAD_PASSWORD -ErrorAction SilentlyContinue
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($firstPtr)
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secondPtr)
    $plain=$null;$confirm=$null;$first.Dispose();$second.Dispose()
}
