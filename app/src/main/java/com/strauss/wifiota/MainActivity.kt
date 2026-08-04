package com.strauss.wifiota

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var ssidField: EditText
    private lateinit var passField: EditText
    private lateinit var ipField: EditText
    private lateinit var tcCheck: CheckBox
    private lateinit var retryCheck: CheckBox
    private lateinit var statusLabel: TextView
    private lateinit var hmiLabel: TextView
    private lateinit var addonLabel: TextView
    private lateinit var rcLabel: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var connectButton: Button
    private val actionButtons = mutableListOf<Button>()

    private lateinit var barNetwork: BarNetwork
    private var firmware = Firmware.Set()

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onFolderPicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ssidField = findViewById(R.id.ssidField)
        passField = findViewById(R.id.passField)
        ipField = findViewById(R.id.ipField)
        tcCheck = findViewById(R.id.tcCheck)
        retryCheck = findViewById(R.id.retryCheck)
        statusLabel = findViewById(R.id.statusLabel)
        hmiLabel = findViewById(R.id.hmiLabel)
        addonLabel = findViewById(R.id.addonLabel)
        rcLabel = findViewById(R.id.rcLabel)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        connectButton = findViewById(R.id.connectButton)

        barNetwork = BarNetwork(this)
        restoreSettings()

        connectButton.setOnClickListener { connect() }
        findViewById<Button>(R.id.folderButton).setOnClickListener {
            pickFolder.launch(null)
        }

        val ping = findViewById<Button>(R.id.pingButton)
        val hmi = findViewById<Button>(R.id.hmiButton)
        val addon = findViewById<Button>(R.id.addonButton)
        val rc = findViewById<Button>(R.id.rcButton)
        actionButtons += listOf(ping, hmi, addon, rc)

        ping.setOnClickListener { runPing() }
        hmi.setOnClickListener { flash("hmi") }
        addon.setOnClickListener { flash("fizzz") }
        rc.setOnClickListener { flash("rc") }

        setActionsEnabled(false)
        restoreFolder()
    }

    override fun onPause() {
        super.onPause()
        saveSettings()
    }

    override fun onDestroy() {
        super.onDestroy()
        barNetwork.release()
    }

    // CONNECT
    private fun connect() {
        val ssid = ssidField.text.toString().trim()
        if (ssid.isEmpty()) { log("Enter the bar SSID first"); return }

        connectButton.isEnabled = false
        lifecycleScope.launch {
            try {
                log("Joining $ssid ... approve the system dialog")
                barNetwork.connect(ssid, passField.text.toString())
                log("Joined - this app's sockets are now pinned to the bar")
                setStatus("LINK BOUND")
                setActionsEnabled(true)
            } catch (e: Exception) {
                log("Connect failed: ${e.message}")
                setStatus("NO LINK")
            } finally {
                connectButton.isEnabled = true
            }
        }
    }

    // PING
    private fun runPing() {
        val net = barNetwork.network ?: run { log("Not bound - press Connect"); return }
        val host = ipField.text.toString().trim()
        lifecycleScope.launch {
            log("Pinging $host...")
            val (ok, detail) = withContext(Dispatchers.IO) { OtaClient(net, host).ping() }
            log(if (ok) "Bar reachable: $detail" else "No link: $detail")
            setStatus(if (ok) "LINK OK" else "NO LINK")
        }
    }

    // FLASH
    private fun flash(component: String) {
        val net = barNetwork.network ?: run { log("Not bound - press Connect"); return }
        val host = ipField.text.toString().trim()

        val file = when (component) {
            "hmi" -> firmware.hmi
            "fizzz" -> firmware.addon
            else -> firmware.rc
        }
        if (file == null) { log("[ERROR] No matching .bin in the selected folder"); return }

        setActionsEnabled(false)
        // The upload takes minutes; a sleeping screen can drop the Wi-Fi link.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            try {
                val name = file.name ?: "firmware.bin"
                setStatus("UPLOADING")
                log("Hashing $name ...")
                val (bytes, sha) = withContext(Dispatchers.IO) {
                    Firmware.readAndHash(this@MainActivity, file)
                }
                log("  sha256 = $sha")

                val client = OtaClient(net, host)
                val (reachable, detail) = withContext(Dispatchers.IO) { client.ping() }
                if (!reachable) {
                    log("[ERROR] Bar not reachable: $detail")
                    setStatus("FAILED")
                    return@launch
                }

                // RC follows the official flow: fota_prepare first, then upload
                // with NO component and NO transactionComplete.
                val isRc = component == "rc"
                if (isRc) {
                    log("Sending fota_prepare...")
                    val (_, pd) = withContext(Dispatchers.IO) { client.prepareFota() }
                    log("fota_prepare: $pd")
                }

                val version = Firmware.versionFromName(name, component)
                // Read the checkboxes here: they can only be touched on the UI thread.
                val tc = if (isRc) false else tcCheck.isChecked
                val retry = retryCheck.isChecked
                val ok = withContext(Dispatchers.IO) {
                    client.upload(
                        bytes = bytes,
                        version = version,
                        sha = sha,
                        component = if (isRc) "" else component,
                        transactionComplete = tc,
                        autoRetry = retry
                    ) { line -> lifecycleScope.launch { log(line) } }
                }

                if (ok) {
                    if (component == "fizzz") {
                        log("Accepted. HMI now pushes it to the STM32 over MSA (~1-2 min). " +
                            "Do NOT cut power/WiFi. Check 'ver' on HC.")
                    }
                    if (component == "hmi") {
                        log("Accepted. The bar reboots into the new firmware now. " +
                            "Wait for the AP to return before flashing the addon.")
                    }
                    setStatus("SUCCESS")
                } else {
                    setStatus("FAILED")
                }
            } catch (e: Exception) {
                log("Error: ${e.message}")
                setStatus("FAILED")
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                setActionsEnabled(true)
            }
        }
    }

    // FOLDER
    private fun onFolderPicked(uri: Uri) {
        // Persist access so the folder survives an app restart.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        prefs().edit().putString("folder", uri.toString()).apply()
        scanFolder(uri)
    }

    private fun restoreFolder() {
        prefs().getString("folder", null)?.let { scanFolder(Uri.parse(it)) }
    }

    private fun scanFolder(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri) ?: return
        lifecycleScope.launch {
            firmware = withContext(Dispatchers.IO) { Firmware.scan(root) }
            hmiLabel.text = describe(firmware.hmi, "hmi", "HMI")
            addonLabel.text = describe(firmware.addon, "fizzz", "ADDON")
            rcLabel.text = describe(firmware.rc, "rc", "RC")
            log("Folder scanned: ${root.name}")
        }
    }

    private fun describe(file: DocumentFile?, component: String, label: String): String {
        val name = file?.name ?: return "$label: (none found)"
        val v = Firmware.versionFromName(name, component).ifEmpty { "?" }
        return "$label: $name  (v$v)"
    }

    // UI HELPERS
    private fun setActionsEnabled(enabled: Boolean) {
        actionButtons.forEach { it.isEnabled = enabled }
    }

    private fun setStatus(text: String) { statusLabel.text = text }

    private fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logView.append("[$ts]  $line\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun prefs() = getSharedPreferences("wifi_ota", Context.MODE_PRIVATE)

    private fun restoreSettings() = with(prefs()) {
        ssidField.setText(getString("ssid", ""))
        passField.setText(getString("pass", ""))
        ipField.setText(getString("ip", "192.168.4.1"))
    }

    private fun saveSettings() = prefs().edit()
        .putString("ssid", ssidField.text.toString())
        .putString("pass", passField.text.toString())
        .putString("ip", ipField.text.toString())
        .apply()
}
