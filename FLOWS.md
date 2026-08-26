# WaterBar Updater — Flows

Screen storyboard and flow charts for the app.
Diagrams are Mermaid; GitHub renders them directly in the browser.

---

## Contents

1. [Screen map](#1-screen-map)
2. [Connecting to a device](#2-connecting-to-a-device)
3. [Choosing the firmware source](#3-choosing-the-firmware-source)
4. [Updating from the server](#4-updating-from-the-server)
5. [Flashing](#5-flashing)
6. [Retry logic on HTTP 500](#6-retry-logic-on-http-500)
7. [Model detection and manual choice](#7-model-detection-and-manual-choice)
8. [Component and version parsing](#8-component-and-version-parsing)
9. [Network binding](#9-network-binding)

---

## 1. Screen map

Everything lives in one activity. There are no fragments; the wizard steps are
`LinearLayout` blocks whose visibility is toggled by `goTo()`.

```mermaid
graph TD
    S1["Step 1 — Connect<br/>logo, firmware source,<br/>AP Mode reminder"]
    S2["Step 2 — Pick component<br/>HMI / ADDON / RC<br/>+ forced install"]
    S3["Step 3 — Flashing<br/>progress, do not cut power"]

    LOG["Log dialog<br/>timestamped output"]
    SET["Setup dialog<br/>SSID, password, IP,<br/>server URL, SAS, language"]
    MOD["Device list<br/>photo, name, folder"]
    ARC["Archive dialog<br/>replaced images"]
    PICK["System folder picker"]
    FILE["System file picker"]

    S1 -->|"device answered"| S2
    S2 -->|"component picked"| S3
    S2 -->|"Back"| S1
    S3 -->|"finished or failed"| S2

    S1 -.->|"LOG"| LOG
    S2 -.->|"LOG"| LOG
    S3 -.->|"LOG"| LOG
    S1 -.->|"SETUP"| SET
    S1 -.->|"Instructions →"| MOD
    S1 -.->|"long press Update"| ARC
    S1 -.->|"Choose folder →"| PICK
    S2 -.->|"Forced install"| FILE

    style S3 fill:#7a2020,color:#fff
```

Step 3 is marked in red on purpose: it is the only place where the device is
written to, and it cannot be interrupted.

---

## 2. Connecting to a device

```mermaid
flowchart TD
    A["Connect to Water Bar"] --> B{"Wi-Fi enabled?"}
    B -->|no| B1["Open the system Wi-Fi panel<br/>re-check in onResume"]
    B -->|yes| C["attachToCurrentWifi()<br/>bind to the current network"]

    C --> D{"Any network<br/>attached?"}
    D -->|yes| E["ping 192.168.4.1"]
    D -->|no| G

    E --> F{"Device replied?"}
    F -->|yes| Z["get_info → bar_type →<br/>model → go to step 2"]
    F -->|no| E1["releaseAttachment()<br/>this was some other Wi-Fi"]
    E1 --> G

    G{"SSID prefix set<br/>in Setup?"} -->|no| G1["Ask for a prefix<br/>Android rejects match-all"]
    G -->|yes| H["System network picker<br/>filtered by prefix"]

    H --> I{"User picked<br/>a network?"}
    I -->|no| I1["Connect failed:<br/>AP not found or declined"]
    I -->|yes| J["ping 192.168.4.1"]

    J --> K{"Device replied?"}
    K -->|yes| Z
    K -->|no| K1["Joined, but no answer on the IP"]

    style Z fill:#1e5c2f,color:#fff
    style I1 fill:#7a2020,color:#fff
    style K1 fill:#7a2020,color:#fff
```

Two things worth knowing here.

The first attempt exists because the technician has usually already joined the
network by hand — asking again would be noise.

`removeCapability(NET_CAPABILITY_INTERNET)` does **not** limit the match to
networks without internet. Any Wi-Fi qualifies, office access points included.
The ping is the only real proof.

---

## 3. Choosing the firmware source

```mermaid
flowchart TD
    A["rescan()"] --> B{"Model known?<br/>detected or manual"}
    B -->|no| E
    B -->|yes| C{"Anything downloaded<br/>for this model?"}

    C -->|yes| D["Use filesDir/firmware/&lt;model&gt;<br/>already verified by sha256"]
    C -->|no| E{"Folder picked<br/>by hand?"}

    E -->|no| F["No folder selected"]
    E -->|yes| G{"Sub-folder named<br/>after the model?"}

    G -->|yes| H["Scan that sub-folder"]
    G -->|no| I["Scan the root<br/>and say so in the log"]

    D --> J["Refresh the component buttons"]
    F --> J
    H --> J
    I --> J

    style D fill:#1e5c2f,color:#fff
    style I fill:#6b5510,color:#fff
```

Downloaded firmware wins because it was checked against the server's hash. The
hand-picked folder stays as the fallback for a device that is not in the
manifest.

Which source was used goes into the log — "the wrong firmware" must never happen
silently.

---

## 4. Updating from the server

```mermaid
sequenceDiagram
    participant U as User
    participant App
    participant S as Blob storage
    participant FS as filesDir

    U->>App: Update from server
    App->>S: GET manifest.json<br/>Cache-Control: no-cache
    S-->>App: JSON with models and hashes

    loop for each component
        App->>FS: local version?
        FS-->>App: 0.03.132 or nothing

        alt server version is strictly newer
            App->>S: GET the .bin
            S-->>App: bytes (progress %)
            App->>App: verify sha256

            alt hash matches
                App->>FS: move the old file to archive/
                App->>FS: put the new file in place
            else mismatch
                App->>App: delete the download,<br/>report the error
            end
        else equal or older
            App->>App: skip, log the reason
        end
    end

    App->>App: rescan()
```

Only strictly newer versions are downloaded, so a deliberate local downgrade is
never undone behind the user's back.

Nothing is ever deleted: the replaced image is moved into `archive/` and stays
there until it is cleared by hand.

**This traffic does not use the bound network.** While the phone is attached to
the device's access point there is no internet on that interface, so the
download goes over office Wi-Fi or mobile data.

---

## 5. Flashing

```mermaid
flowchart TD
    A["Component picked on step 2"] --> B["Go to step 3<br/>picking IS the decision to flash"]
    B --> C["Read the file, compute sha256"]
    C --> D{"Device reachable?"}
    D -->|no| D1["Bar not reachable"]
    D -->|yes| E{"Component?"}

    E -->|RC| F["fota_prepare"]
    E -->|HMI / fizzz| G

    F --> G["POST /ota/upload<br/>raw octet-stream"]
    G --> H{"Response"}

    H -->|"body contains<br/>uploaded successfully"| I["Done"]
    H -->|"HTTP 500"| J["MSA busy → retry"]
    H -->|"link dropped"| K{"Was everything sent?"}
    H -->|other| L["Error"]

    K -->|yes| I2["The device rebooted —<br/>this is normal after HMI"]
    K -->|no| L

    I --> M["Back to step 2 after 4 s"]
    I2 --> M
    L --> M
    D1 --> M

    style I fill:#1e5c2f,color:#fff
    style I2 fill:#1e5c2f,color:#fff
    style L fill:#7a2020,color:#fff
    style D1 fill:#7a2020,color:#fff
```

Success is judged by the **body** of the response, not by the status code.

A dropped link after a full HMI upload is not a failure: the device reboots and
its access point disappears. Telling that apart from "the transfer did not
finish" is what stopped an endless retry loop.

---

## 6. Retry logic on HTTP 500

```mermaid
stateDiagram-v2
    [*] --> Uploading
    Uploading --> Success: body contains<br/>uploaded successfully
    Uploading --> Busy: HTTP 500 (MSA busy)
    Uploading --> Failed: any other error

    Busy --> Waiting: attempts left?
    Waiting --> Uploading: after 8 s
    Busy --> Failed: 5 attempts used up

    Success --> [*]
    Failed --> [*]
```

`HTTP 500` from this device means "the MSA is busy", not "something broke". Up
to 5 attempts, 8 seconds apart.

The countdown is shown on screen so the wait does not look like a freeze.

---

## 7. Model detection and manual choice

```mermaid
flowchart TD
    A["get_info reply"] --> B["bar_type field"]
    B --> C{"Matches a code<br/>in BarModel.ALL?"}

    C -->|"S"| D["Tamar → folder tamar"]
    C -->|"P2 / P3"| E["Primium 2/3 → folder primium23"]
    C -->|"P1"| F["Primium 1 → folder primium1"]
    C -->|no match| G["Model unknown<br/>scan the root folder"]

    D --> H["Clear any manual choice"]
    E --> H
    F --> H

    style G fill:#6b5510,color:#fff
```

Manual selection follows a separate, deliberately narrow path:

```mermaid
stateDiagram-v2
    [*] --> NoModel: app started

    NoModel --> ManualPick: tap a row in the device list
    ManualPick --> NoModel: Clear choice

    NoModel --> Detected: device replied with bar_type
    ManualPick --> Detected: device replied with bar_type

    Detected --> NoModel: disconnected

    note right of ManualPick
        Only possible while
        nothing is connected.
        For downloading ahead
        of a site visit.
    end note

    note right of Detected
        bar_type always wins and
        clears the manual choice.
        The device knows what it is;
        the user is guessing.
    end note
```

---

## 8. Component and version parsing

```mermaid
flowchart TD
    A["File name"] --> B{"Ends in .bin?"}
    B -->|no| Z["Ignore"]
    B -->|yes| C{"Starts with<br/>addon-fizz, or<br/>contains fizz?"}

    C -->|yes| D["fizzz"]
    C -->|no| E{"Starts with rc?"}

    E -->|yes| F["rc"]
    E -->|no| G{"Contains hmi?"}

    G -->|no| Z
    G -->|yes| H{"Contains enc?"}

    H -->|yes| I["hmi, encrypted<br/>preferred"]
    H -->|no| J["hmi, plain<br/>fallback"]

    D --> K["Pull the version out<br/>of the name"]
    F --> K
    I --> K
    J --> K

    K --> L{"Which component?"}
    L -->|hmi| M["Take the group that does<br/>NOT start with 00.<br/>→ 0.03.132"]
    L -->|fizzz| N["Take the group that<br/>starts with 00.<br/>→ 00.00.403"]
    L -->|rc| O["Take the first group"]
```

The version lives only in the file name. Renaming a firmware file breaks the
comparison.

---

## 9. Network binding

```mermaid
graph LR
    subgraph Phone
        APP["WaterBar Updater"]
        OTA["OtaClient<br/>socketFactory = bar network"]
        CAT["CatalogClient<br/>default route"]
    end

    subgraph "Wi-Fi — no internet"
        BAR["Water bar<br/>192.168.4.1"]
    end

    subgraph "Mobile data / office Wi-Fi"
        AZ["Blob storage<br/>manifest + .bin"]
    end

    APP --> OTA
    APP --> CAT
    OTA -->|"pinned sockets"| BAR
    CAT -->|"normal route"| AZ
```

Two HTTP clients on purpose. `OtaClient` is pinned to the device's network,
otherwise Android would route its requests through mobile data and they would
never arrive. `CatalogClient` is deliberately left unpinned so downloads work
while the device's access point is still attached.

If `bindProcessToNetwork` is ever introduced, downloads break — the whole
process would be forced onto the bar's network.