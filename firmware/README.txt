firmware/  -  the image this APK carries
=========================================

Two files live here, and Gradle packs both into the APK as assets:

    manifest.json                     what is bundled
    <whatever manifest.json names>    the image itself

manifest.json fields:

    model      must match a BarModel id exactly - "Tamar" for now.
               The flash button stays disabled unless the connected bar
               reports this model, so a wrong value here does not flash the
               wrong device, it just blocks flashing entirely.
    component  "hmi". Only HMI is enabled in the pilot build.
    version    what the bar is told it is receiving, and what the button
               shows. Take it from the filename or the release note - the
               app never guesses it.
    file       the .bin sitting next to this manifest.
    sha256     optional, lowercase hex. When set it is verified twice: by
               Gradle at build time and by the app just before uploading.
               Leave it as "" to skip both checks.

Getting the hash (PowerShell, from the project root):

    (Get-FileHash "firmware\<name>.bin" -Algorithm SHA256).Hash.ToLower()

Swapping in a new version:

    1. Drop the new .bin here.
    2. Point "file" and "version" at it, refresh "sha256".
    3. Delete the old .bin - two images in this folder is not an error, but
       only the one the manifest names is ever used, and leaving the other
       behind makes the APK bigger for nothing.
    4. Rebuild. The build prints what it bundled and fails if anything is
       missing or does not hash to what the manifest claims.
