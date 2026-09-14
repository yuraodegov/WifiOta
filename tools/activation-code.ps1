<#
    Issues an activation code for one phone.

    The technician reads out the device ID the app shows on first launch; this
    prints the code that will be accepted on that phone and on no other.

    Usage:
        .\activation-code.ps1 A7F3-21B9

    The secret must be the same one the APK was built with (ACTIVATION_SECRET
    in local.properties). Set it here, or as an environment variable, or the
    codes will not match what the app expects.
#>

param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string] $DeviceId
)

$secret = $env:ACTIVATION_SECRET
if (-not $secret) {
    # Fill this in rather than exporting the variable every time, if you prefer.
    # Keep this file out of the repository if you do.
    $secret = ""
}
if (-not $secret) {
    Write-Host "ACTIVATION_SECRET is not set." -ForegroundColor Red
    Write-Host "Set the environment variable, or edit `$secret in this script."
    exit 1
}

$id = $DeviceId.Trim().ToUpper()
if ($id -notmatch '^[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$') {
    Write-Host "That does not look like a device ID. Expected the form A7F3-21B9." -ForegroundColor Yellow
    Write-Host "Read it again from the app - I, L, O and U never appear in it."
    exit 1
}

$hmac = New-Object System.Security.Cryptography.HMACSHA256
$hmac.Key = [Text.Encoding]::UTF8.GetBytes($secret)
$h = $hmac.ComputeHash([Text.Encoding]::UTF8.GetBytes($id))

# RFC 4226 truncation, matching Activation.kt.
$offset = $h[$h.Length - 1] -band 0x0F
$value = (($h[$offset] -band 0x7F) -shl 24) -bor
         (($h[$offset + 1] -band 0xFF) -shl 16) -bor
         (($h[$offset + 2] -band 0xFF) -shl 8) -bor
          ($h[$offset + 3] -band 0xFF)

$code = "{0:D6}" -f ($value % 1000000)

Write-Host ""
Write-Host "  Device : $id"
Write-Host "  Code   : $code" -ForegroundColor Green
Write-Host ""
