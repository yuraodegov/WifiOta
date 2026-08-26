# WaterBar Updater

Android app for updating Strauss water bar firmware over Wi-Fi.
A Kotlin port of the desktop `wifi_ota_gui.py`.

A technician connects the phone to the device's access point; the app reads the
installed versions, compares them with the available files and uploads new ones
over HTTP.

- **Repository:** `github.com/yuraodegov/WifiOta`
- **Package:** `com.strauss.wifiota.v2`
- **Tested on:** Samsung A56, Android 16

---

## Contents

1. [How it works](#how-it-works)
2. [The three-step wizard](#the-three-step-wizard)
3. [Model detection](#model-detection)
4. [Firmware sources](#firmware-sources)
5. [Updating from a server](#updating-from-a-server)
6. [Device protocol](#device-protocol)
7. [Settings](#settings)
8. [Localization](#localization)
9. [Code layout](#code-layout)
10. [Building and installing](#building-and-installing)
11. [Known limitations](#known-limitations)
12. [Not done yet](#not-done-yet)

---

## How it works

In AP Mode the device brings up its own access point with no internet access.
The phone joins it, and everything happens over plain HTTP against
`192.168.4.1`.

The hard part is making Android send requests into that network at all. By
default the system routes traffic through whichever interface has internet —
mobile data — and the request never reaches the device.

The answer is `WifiNetworkSpecifier` plus binding sockets to that specific
network:

```kotlin
val client = OkHttpClient.Builder()
    .socketFactory(network.socketFactory)
    .build()
```

Every socket is created by a factory that belongs to the bar's network. Mobile
data keeps working for everything else — which is exactly what makes it possible
to download firmware from a server without leaving the device.

### Connecting in two stages

**First.** The Wi-Fi the phone is already on is checked. If it is a bar, nothing
is asked. This is the common case: the technician has already picked the network
by hand.

**Second.** Otherwise the system network picker is shown, filtered by an SSID
prefix. That dialog is drawn by Android and cannot be replaced with a custom one.

Worth knowing: `removeCapability(NET_CAPABILITY_INTERNET)` drops the
*requirement* for internet, it does not restrict the match to networks without
it. The only thing that proves this is a bar is the ping to `192.168.4.1`
afterwards.

---

## The three-step wizard

The order is enforced; the next step's buttons stay locked until the previous
one completes.

### Step 1 — connect

Logo, firmware source card, a reminder to switch the device into AP Mode, and
the connect button. The "Instructions →" link opens the list of supported
devices.

### Step 2 — pick a component

Three buttons: `HMI`, `ADDON`, `RC`. Each one spells out what will happen:

| Button reads | Meaning |
|---|---|
| `HMI – not found` | no file, button disabled |
| `HMI  v0.03.133` | file present, version on the device unknown |
| `HMI  v0.03.132  ✓ up to date` | same version installed, button disabled |
| `HMI  v0.03.132 → v0.03.133` | an update is available |

Below them is a red forced-install button: pick any `.bin` by hand, including an
older one. Red because going backwards is not a routine action. A dialog warns
about the downgrade before anything is written.

### Step 3 — flashing

A progress bar with a real percentage, a status line, and a warning not to cut
power. There is no `Back` and no `Flash` button here — **an upload in progress
cannot be cancelled**.

Flashing starts by itself when a component is picked on step 2: picking the
component *is* the decision to flash it.

---

## Model detection

The device reports a `bar_type` field in its `get_info` reply. That value
selects the firmware folder.

| Model | Folder | `bar_type` codes | Status |
|---|---|---|---|
| Tamar | `tamar` | `S` | confirmed on a live device, 21 Aug 2026 |
| Primium 1 | `primium1` | `P1` | **unconfirmed**, a guess |
| Primium 2 / 3 | `primium23` | `P2`, `P3` | `P3` confirmed, `P2` is a guess |

The table lives in `BarModel.kt`. Never add a code "just in case": an extra
entry cannot help, but it can match another model's device to the wrong folder
and offer the wrong firmware.

### Picking a model by hand

A model can be selected by tapping a row in the device list — **but only while
no bar is connected**. It exists so firmware can be downloaded ahead of a site
visit.

The moment a device answers, its own `bar_type` overrides the manual choice and
clears it. The device knows what it is; the user is guessing. If the manual
choice disagreed with reality, a line about it is written to the log.

---

## Firmware sources

The app looks for files in two places, in this order of priority:

**1. Downloaded from the server** — `filesDir/firmware/<model>/`. Preferred,
because these files were already verified against the sha256 in the manifest.

**2. A hand-picked folder** — chosen through the system folder picker. The
fallback for a device that is not in the manifest, or a build that never reached
the server.

Which source won is written to the log, so "the wrong firmware" never happens
silently.

### How files are recognised

The scan is breadth-first, up to three levels deep, and the shallowest match
wins. A folder named `archive` is always skipped.

| File name | Component |
|---|---|
| starts with `addon-fizz`, or contains `fizz` | `fizzz` |
| starts with `rc` | `rc` |
| contains `hmi` and `enc` | `hmi`, encrypted — preferred |
| contains `hmi` | `hmi`, plain |

The version is pulled out of the name with `\d{1,2}\.\d{2,3}\.\d{2,3}`. For HMI
the group that does *not* start with `00.` is taken; for the addon it is the
other way round:

```
element-p-hmi-0.03.132_enc.bin  →  hmi,   0.03.132
addon-fizz-00.00.403.bin        →  fizzz, 00.00.403
```

**Do not rename firmware files** — the version is read from the name and
nowhere else.

---

## Updating from a server

The "Update from server" link sits in the firmware source card. It stays hidden
until a server address is set in Setup.

### Why a manifest is needed

Azure Blob Storage does not list files behind a folder URL. A link to a "folder"
tells the app nothing. So a `manifest.json` sits next to the firmware; it is
fetched first and describes everything else:

```json
{
  "updated": "2026-08-20",
  "models": {
    "primium23": {
      "hmi":   { "version": "0.03.132",
                 "file": "element-p-hmi-0.03.132_enc.bin",
                 "sha256": "ab12...", "size": 1892352 },
      "fizzz": { "version": "00.00.403",
                 "file": "addon-fizz-00.00.403.bin",
                 "sha256": "cd34...", "size": 262144 }
    }
  }
}
```

The keys under `models` are the same folder names `BarModel` uses. A file's URL
is built as `<base>/<model>/<file>` unless the entry carries an absolute `url`.

### Layout on the server

```
firmware/manifest.json
firmware/tamar/element-p-hmi-0.03.132_enc.bin
firmware/primium23/addon-fizz-00.00.403.bin
```

### Generating the manifest

Hashes are never written by hand — sooner or later they drift away from the
files and the app starts rejecting perfectly good firmware. There is a script:

```powershell
.\tools\make-manifest.ps1 -Root "C:\path\to\firmware"
```

It walks the sub-folders, hashes every `.bin`, reads versions out of the names
and writes `manifest.json` into the root.

### Update logic

1. The manifest is fetched with `Cache-Control: no-cache` — otherwise a CDN
   happily serves a stale copy and a new build would be missed.
2. For each component the server version is compared with the local one.
3. Only something **strictly newer** is downloaded. Equal or older is left
   alone, so a deliberate local downgrade is not undone behind the user's back.
4. The download is verified against its sha256. On mismatch the file is deleted
   — a bad image must not be left where the scanner can find it.
5. The previous file for that component is **moved into `archive/`, not
   deleted**. If the name collides, a numeric suffix is added.

The archive opens on a long press of the same link, with a button to clear it.

If a device is connected, only its model is updated. If not, every model in the
manifest is, so the phone can be filled up before going out.

### About the network

While the phone is bound to the bar's access point there is no internet on that
interface. The downloader deliberately does **not** use the bound network — it
goes out over the normal route, office Wi-Fi or mobile data. Downloading and
flashing are separate phases by nature.

---

## Device protocol

### Addresses

- Access point SSID: starts with `WaterBar`
- IP: `192.168.4.1`

### The command path uses an underscore

```
/ap_tk=tk&command=get_info     ← works
/ap?tk=tk&command=get_info     ← answers "Error" to every command
```

This is not a typo. The difference cost several days of debugging. **Do not
"fix" it back.**

### Available commands

Only three work:

| Path | Purpose |
|---|---|
| `/ap_tk=tk&command=get_info` | versions and device type |
| `/ap_tk=tk&command=fota_prepare` | preparation before an RC upload |
| `/ota/upload` | image upload |

Tried and **not** working (all answer `Error`): `get_vsn`, `get_sn`,
`get_serial`, `get_serial_number`, `get_device_info`, `get_status`, `get_mac`,
`get_id`, `get_version`, `get_all`, `get_param`, `get_params`, `info`, `status`.

The interface is built narrowly for OTA, not as a diagnostic channel. The serial
number is not reachable over Wi-Fi at all — only over the serial link.

### The get_info reply

```json
{"bar_type":"P3","ver_hmi":"0.03.132","hardware":"",
 "plugins":[{"type":"fizzz","state":0,
             "ver_installed":"","ver_local":"00.00.403"}]}
```

`ver_installed` is empty when no addon is attached — there is nothing to compare
against. On some devices **both** addon version fields come back empty.

### Upload

A raw POST, **not** multipart:

```
POST http://192.168.4.1/ota/upload
     ?version=<ver>&sha256=<sha>&component=<hmi|fizzz>&transactionComplete=true
Content-Type: application/octet-stream
Body: contents of the .bin
```

Success is judged **by the response body**: it must contain
`uploaded successfully`.

RC is uploaded differently — without `component` and without
`transactionComplete`, after `fota_prepare`.

### Retries

`HTTP 500` means "MSA busy", not failure. The app waits 8 seconds and retries,
up to 5 attempts in total.

One case is handled separately: after a successful HMI upload the device reboots
and its access point disappears. A dropped link at that moment is not an error,
and there is nothing left to retry.

Timeouts: 5 seconds to connect, 300 seconds to transfer.

---

## Settings

The `SETUP` screen:

| Field | Purpose |
|---|---|
| SSID prefix | access point name prefix, **case sensitive** |
| Password | access point password |
| IP | device address, `192.168.4.1` by default |
| Firmware server URL | container base, e.g. `https://<acct>.blob.core.windows.net/firmware` |
| SAS token | for a private container; empty for a public one |
| Auto-retry on HTTP 500 | retries while the MSA is busy |
| Language | English / Hebrew |

On the SSID case: `setSsidPattern` compares literally. If the access point is
called `WATER_BAR_xxx`, the prefix `Water` will not match, however similar it
looks.

---

## Localization

Two languages: English (`values/strings.xml`) and Hebrew
(`values-iw/strings.xml`). Every UI string lives in resources.

The folder is `values-iw`, not `values-he` — Android uses the legacy ISO codes
(`iw`, `in`, `ji`), and `values-he` is simply never picked up.

The flashing log is deliberately left in English: it is technical output with
timestamps, read by engineers.

---

## Code layout

```
app/src/main/java/com/strauss/wifiota/
├── MainActivity.kt      the wizard, all UI logic
├── BarNetwork.kt        binds sockets to the device's access point
├── OtaClient.kt         ping, get_info, fota_prepare, upload with progress
├── DeviceInfo.kt        JSON parsing, version comparison
├── Firmware.kt          finding .bin files, version parsing, sha256
├── FirmwareStore.kt     the app's private folder, archive of replaced images
├── FirmwareCatalog.kt   manifest model and parsing
├── CatalogClient.kt     HTTP client for the firmware server
└── BarModel.kt          model table: bar_type → name → folder
```

```
app/src/main/res/
├── layout/          activity_main, dialog_settings, item_model
├── drawable/        button and card backgrounds, logo
├── drawable-xxhdpi/ device photos
├── values/          colors.xml, strings.xml
└── values-iw/       strings.xml (Hebrew)
```

### Separation of concerns

`FwSource` is a shared abstraction over a firmware file: it may live in the
app's private storage or have been picked through the system dialog. Everything
above that class works with a name and bytes, never with a `Uri` or a path.

`BarModel` knows nothing about Android — the photo is stored as a plain resource
id.

---

## Building and installing

### Environment

| Component | Version |
|---|---|
| AGP | 8.5.2 |
| Gradle | 8.7 |
| Kotlin | 1.9.24 |
| JDK | 17 (Temurin) |
| compileSdk / targetSdk | 34 |
| minSdk | 29 |

`minSdk 29` because `WifiNetworkSpecifier` arrived in Android 10. Nothing older
can do this at all.

Dependencies: `core-ktx`, `appcompat`, `material`,
`kotlinx-coroutines-android`, `documentfile`, `okhttp`.

### Local build

```powershell
cd "C:\Users\Yura_Od\Desktop\WIFI\WifiOta2"
.\gradlew.bat assembleDebug
```

APK: `app\build\outputs\apk\debug\app-debug.apk`

### Installing over Wi-Fi

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" connect <address>:<port>
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" -s <address>:<port> `
    install -r "app\build\outputs\apk\debug\app-debug.apk"
```

The wireless debugging port changes every time the feature is toggled; read it
off the phone in the developer options. Pairing is done once.

### CI build

`.github/workflows/build.yml` builds the debug APK and publishes it as an
artifact.

---

## Known limitations

**The emulator is useless here.** Its virtual Wi-Fi will never join a device's
access point. Only the visual layout can be checked on an emulator.

**An upload cannot be interrupted.** A deliberate decision: stopping halfway
would leave the device in an undefined state.

**Downloaded firmware does not survive an uninstall.** `filesDir` is wiped along
with the app. Anything that must be kept lives on the server.

**The password and the SAS token sit in `SharedPreferences` in clear text.**
Acceptable for an internal tool, but the SAS should be issued read-only and with
a short expiry.

**The corporate network blocks `.jar`, `.aar` and `.zip`.** Any new dependency
needs the phone's hotspot. Always check the size of a downloaded file — the
filter truncates content silently.

---

## Not done yet

**`bar_type` codes for Primium 1 and Primium 2.** Connect to each device and
run:

```powershell
curl.exe -i "http://192.168.4.1/ap_tk=tk&command=get_info"
```

**The AP Mode instructions screen** — a step-by-step description per model. The
texts are missing.

**A native speaker's review of the Hebrew translation.**

**Downloading from a server has never been exercised for real** — the code is
written, but Azure is not available yet. It can be tested with a local HTTP
server, no Azure involved:

```powershell
cd "C:\Users\Yura_Od\Desktop\WIFI"
python -m http.server 8080 --bind 0.0.0.0
```

Then set `http://<laptop address>:8080/firmware` in Setup and leave the SAS
field empty.

**Traceability by serial number.** Blocked in the firmware: there is no command
to read the serial over Wi-Fi. The right fix is to ask for a serial field inside
the existing `get_info` reply rather than a new command.