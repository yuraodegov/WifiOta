package com.strauss.wifiota

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
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

/**
 * Three-step wizard: connect -> pick firmware -> flash.
 *
 * The order is enforced rather than suggested: a step's controls stay disabled
 * until the previous one actually succeeded, so nothing gets flashed over a
 * link that was never verified.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var bar1: View
    private lateinit var bar2: View
    private lateinit var bar3: View
    private lateinit var stepCounter: TextView
    private lateinit var stepTitle: TextView
    private lateinit var stepHint: TextView
    private lateinit var step1: View
    private lateinit var step2: View
    private lateinit var step3: View
    private lateinit var searchButton: Button
    private lateinit var pingButton: Button
    private lateinit var hmiButton: Button
    private lateinit var addonButton: Button
    private lateinit var rcButton: Button
    private lateinit var summary: TextView
    private lateinit var uploadBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var flashButton: Button
    private lateinit var backButton: Button

    private lateinit var barNetwork: BarNetwork
    private var firmware = Firmware.Set()
    private var component = "hmi"
    private var step = 1

    /** Kept in memory, shown on demand - the log is for diagnosis, not decoration. */
    private val logLines = StringBuilder()

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onFolderPicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bar1 = findViewById(R.id.bar1)
        bar2 = findViewById(R.id.bar2)
        bar3 = findViewById(R.id.bar3)
        stepCounter = findViewById(R.id.stepCounter)
        stepTitle = findViewById(R.id.stepTitle)
        stepHint = findViewById(R.id.stepHint)
        step1 = findViewById(R.id.step1)
        step2 = findViewById(R.id.step2)
        step3 = findViewById(R.id.step3)
        searchButton = findViewById(R.id.searchButton)
        pingButton = findViewById(R.id.pingButton)
        hmiButton = findViewById(R.id.hmiButton)
        addonButton = findViewById(R.id.addonButton)
        rcButton = findViewById(R.id.rcButton)
        summary = findViewById(R.id.summary)
        uploadBar = findViewById(R.id.uploadBar)
        progressText = findViewById(R.id.progressText)
        flashButton = findViewById(R.id.flashButton)
        backButton = findViewById(R.id.backButton)

        barNetwork = BarNetwork(this)

        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.logButton).setOnClickListener { showLog() }
        findViewById<Button>(R.id.folderButton).setOnClickListener { pickFolder.launch(null) }

        searchButton.setOnClickListener { connect() }
        pingButton.setOnClickListener { runPing() }
        hmiButton.setOnClickListener { choose("hmi") }
        addonButton.setOnClickListener { choose("fizzz") }
        rcButton.setOnClickListener { choose("rc") }
        flashButton.setOnClickListener { flash() }
        backButton.setOnClickListener { goTo(step - 1) }

        goTo(1)
        restoreFolder()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only give the network back when the app is really closing. A rotation
        // or any other config change must not tear down a live link.
        if (isFinishing) barNetwork.release()
    }

    // STEP NAVIGATION
    private fun goTo(target: Int) {
        step = target.coerceIn(1, 3)
        step1.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3.visibility = if (step == 3) View.VISIBLE else View.GONE
        backButton.visibility = if (step == 1) View.GONE else View.VISIBLE

        val done = resources.getColor(R.color.action_green, theme)
        val idle = resources.getColor(R.color.step_idle, theme)
        bar1.setBackgroundColor(if (step >= 1) done else idle)
        bar2.setBackgroundColor(if (step >= 2) done else idle)
        bar3.setBackgroundColor(if (step >= 3) done else idle)

        stepCounter.text = "Step $step of 3"
        when (step) {
            1 -> {
                stepTitle.text = "Connect to the bar"
                stepHint.text = "Pick the device in the system dialog."
            }
            2 -> {
                stepTitle.text = "Choose firmware"
                stepHint.text = describeAll()
            }
            3 -> {
                stepTitle.text = "Flash firmware"
                stepHint.text = ""
            }
        }
    }

    // STEP 1
    private fun connect() {
        val ssid = prefs().getString("ssid", "").orEmpty().trim()
        // Android rejects a match-all pattern, so a prefix is mandatory. Case matters.
        if (ssid.isEmpty()) {
            stepHint.text = "Open Setup and enter a name prefix, e.g. Water"
            return
        }

        searchButton.isEnabled = false
        lifecycleScope.launch {
            try {
                stepHint.text = "Searching for \"$ssid*\" - pick the bar in the dialog"
                log("Searching for \"$ssid*\" ...")
                barNetwork.connect(ssid, prefs().getString("pass", ""))
                log("Joined - sockets are pinned to the bar")
                stepHint.text = "Connected. Check the link before flashing."
                pingButton.isEnabled = true
            } catch (e: Exception) {
                log("Connect failed: ${e.message}")
                stepHint.text = "Not connected: ${e.message}"
            } finally {
                searchButton.isEnabled = true
            }
        }
    }

    private fun runPing() {
        val net = barNetwork.network ?: run { stepHint.text = "Link lost - search again"; return }
        val host = prefs().getString("ip", DEFAULT_IP).orEmpty()
        pingButton.isEnabled = false
        lifecycleScope.launch {
            stepHint.text = "Pinging $host ..."
            val (ok, detail) = withContext(Dispatchers.IO) { OtaClient(net, host).ping() }
            log(if (ok) "Bar reachable: $detail" else "No link: $detail")
            pingButton.isEnabled = true
            if (ok) goTo(2) else stepHint.text = "Bar did not answer: $detail"
        }
    }

    // STEP 2
    private fun choose(which: String) {
        component = which
        val file = fileFor(which) ?: return
        val name = file.name ?: ""
        val version = Firmware.versionFromName(name, which).ifEmpty { "?" }
        summary.text = "$name\nversion $version\ncomponent $which"
        uploadBar.visibility = View.INVISIBLE
        uploadBar.progress = 0
        progressText.text = ""
        goTo(3)
    }

    private fun fileFor(which: String) = when (which) {
        "hmi" -> firmware.hmi
        "fizzz" -> firmware.addon
        else -> firmware.rc
    }

    private fun onFolderPicked(uri: Uri) {
        // Persist access so the folder survives an app restart.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
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
            hmiButton.isEnabled = firmware.hmi != null
            addonButton.isEnabled = firmware.addon != null
            rcButton.isEnabled = firmware.rc != null
            hmiButton.text = label("HMI", firmware.hmi, "hmi")
            addonButton.text = label("ADDON", firmware.addon, "fizzz")
            rcButton.text = label("RC", firmware.rc, "rc")
            if (step == 2) stepHint.text = describeAll()
            log("Folder scanned: ${root.name}")
        }
    }

    private fun label(title: String, file: DocumentFile?, which: String): String {
        val name = file?.name ?: return "$title - none found"
        val v = Firmware.versionFromName(name, which).ifEmpty { "?" }
        return "$title  v$v"
    }

    private fun describeAll(): String =
        if (firmware.hmi == null && firmware.addon == null && firmware.rc == null)
            "No .bin files yet - choose the folder."
        else "Pick what to flash."

    // STEP 3
    private fun flash() {
        val net = barNetwork.network
            ?: run { progressText.text = "Link lost - go back and search"; return }
        val host = prefs().getString("ip", DEFAULT_IP).orEmpty()
        val file = fileFor(component) ?: return

        flashButton.isEnabled = false
        backButton.isEnabled = false
        // The upload takes minutes; a sleeping screen can drop the Wi-Fi link.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        lifecycleScope.launch {
            try {
                val name = file.name ?: "firmware.bin"
                progressText.text = "Reading and hashing $name"
                val (bytes, sha) = withContext(Dispatchers.IO) {
                    Firmware.readAndHash(this@MainActivity, file)
                }
                log("$name  sha256 = $sha")

                val client = OtaClient(net, host)
                val (reachable, detail) = withContext(Dispatchers.IO) { client.ping() }
                if (!reachable) {
                    progressText.text = "Bar not reachable"
                    log("[ERROR] Bar not reachable: $detail")
                    return@launch
                }

                // RC follows the official flow: fota_prepare first, then upload
                // with NO component and NO transactionComplete.
                val isRc = component == "rc"
                if (isRc) {
                    val (_, pd) = withContext(Dispatchers.IO) { client.prepareFota() }
                    log("fota_prepare: $pd")
                }

                val version = Firmware.versionFromName(name, component)
                val tc = if (isRc) false else prefs().getBoolean("tc", true)
                val retry = prefs().getBoolean("retry", true)
                val sizeKb = bytes.size / 1024

                uploadBar.visibility = View.VISIBLE
                uploadBar.progress = 0

                val ok = withContext(Dispatchers.IO) {
                    client.upload(
                        bytes = bytes,
                        version = version,
                        sha = sha,
                        component = if (isRc) "" else component,
                        transactionComplete = tc,
                        autoRetry = retry,
                        onProgress = { percent ->
                            lifecycleScope.launch {
                                // Bytes handed to the socket - the kernel buffers,
                                // so this is always slightly ahead of the bar.
                                // Cap at 99: only the bar's reply proves receipt.
                                uploadBar.progress = minOf(percent, 99)
                                progressText.text = "Sending $percent% of $sizeKb KB"
                            }
                        },
                        onWait = { left ->
                            lifecycleScope.launch {
                                progressText.text = if (left > 0)
                                    "Bar busy - retrying in ${left}s" else "Retrying now"
                            }
                        }
                    ) { line -> lifecycleScope.launch { log(line) } }
                }

                if (ok) {
                    // The bar answered "uploaded successfully" - transfer is over.
                    uploadBar.progress = 100
                    progressText.text = when (component) {
                        "hmi" -> "Transferred. The bar reboots now - connect again."
                        "fizzz" -> "Transferred. HMI is pushing it to the STM32 (1-2 min). " +
                            "Do not cut power. Check 'ver' on HC."
                        else -> "Transferred."
                    }
                    log("Upload accepted: $name")
                    kotlinx.coroutines.delay(1500)
                    // Flashing HMI drops the AP, so that one has to start over.
                    goTo(if (component == "hmi") 1 else 2)
                } else {
                    progressText.text = "Failed - open Log for details"
                }
            } catch (e: Exception) {
                progressText.text = "Error: ${e.message}"
                log("Error: ${e.message}")
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                flashButton.isEnabled = true
                backButton.isEnabled = true
            }
        }
    }

    // DIALOGS
    private fun showSettings() {
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        val ssid = view.findViewById<EditText>(R.id.ssidField)
        val pass = view.findViewById<EditText>(R.id.passField)
        val ip = view.findViewById<EditText>(R.id.ipField)
        val showPass = view.findViewById<CheckBox>(R.id.showPass)
        val tc = view.findViewById<CheckBox>(R.id.tcCheck)
        val retry = view.findViewById<CheckBox>(R.id.retryCheck)

        with(prefs()) {
            ssid.setText(getString("ssid", ""))
            pass.setText(getString("pass", ""))
            ip.setText(getString("ip", DEFAULT_IP))
            tc.isChecked = getBoolean("tc", true)
            retry.isChecked = getBoolean("retry", true)
        }

        showPass.setOnCheckedChangeListener { _, checked ->
            pass.inputType = if (checked)
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            else
                android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            // Changing inputType resets the caret - put it back at the end.
            pass.setSelection(pass.text.length)
        }

        AlertDialog.Builder(this)
            .setTitle("Setup")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                prefs().edit()
                    .putString("ssid", ssid.text.toString().trim())
                    .putString("pass", pass.text.toString())
                    .putString("ip", ip.text.toString().trim())
                    .putBoolean("tc", tc.isChecked)
                    .putBoolean("retry", retry.isChecked)
                    .apply()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLog() {
        val text = TextView(this).apply {
            setPadding(40, 30, 40, 30)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            text = if (logLines.isEmpty()) "Empty" else logLines.toString()
        }
        val scroll = ScrollView(this).apply { addView(text) }
        AlertDialog.Builder(this)
            .setTitle("Log")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setNeutralButton("Clear") { _, _ -> logLines.clear() }
            .show()
    }

    private fun log(line: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        logLines.append("[$ts]  ").append(line).append('\n')
    }

    private fun prefs() = getSharedPreferences("wifi_ota", Context.MODE_PRIVATE)

    private companion object {
        const val DEFAULT_IP = "192.168.4.1"
    }
}
