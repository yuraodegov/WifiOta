package com.strauss.wifiota

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.net.wifi.WifiManager
import android.provider.Settings
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import android.widget.RadioButton
import android.widget.RadioGroup
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
    private lateinit var stepHeader: View
    private lateinit var step1: View
    private lateinit var step2: View
    private lateinit var step3: View
    private lateinit var searchButton: Button
    private lateinit var hmiButton: Button
    private lateinit var addonButton: Button
    private lateinit var rcButton: Button
    private lateinit var folderStatus: TextView
    private lateinit var summary: TextView
    private lateinit var uploadBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var flashButton: Button
    private lateinit var backButton: Button

    private lateinit var barNetwork: BarNetwork
    private var firmware = Firmware.Set()
    private var component = "hmi"
    private var step = 1
    private var wifiOn = true
    private var deviceInfo: DeviceInfo? = null
    /** Set when the user picked a .bin by hand instead of using the scan. */
    private var manualFile: DocumentFile? = null
    /** Model resolved from the bar's own report, once it is connected. */
    private var model: BarModel? = null

    /** Kept in memory, shown on demand - the log is for diagnosis, not decoration. */
    private val logLines = StringBuilder()

    private val pickFolder = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { onFolderPicked(it) } }

    private val pickSingleFile = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { onManualFilePicked(it) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the stored language before any view is inflated.
        prefs().getString("lang", null)?.let {
            if (AppCompatDelegate.getApplicationLocales().isEmpty) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(it))
            }
        }
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bar1 = findViewById(R.id.bar1)
        bar2 = findViewById(R.id.bar2)
        bar3 = findViewById(R.id.bar3)
        stepCounter = findViewById(R.id.stepCounter)
        stepTitle = findViewById(R.id.stepTitle)
        stepHint = findViewById(R.id.stepHint)
        stepHeader = findViewById(R.id.stepHeader)
        step1 = findViewById(R.id.step1)
        step2 = findViewById(R.id.step2)
        step3 = findViewById(R.id.step3)
        searchButton = findViewById(R.id.searchButton)
        hmiButton = findViewById(R.id.hmiButton)
        addonButton = findViewById(R.id.addonButton)
        rcButton = findViewById(R.id.rcButton)
        folderStatus = findViewById(R.id.folderStatus)
        summary = findViewById(R.id.summary)
        uploadBar = findViewById(R.id.uploadBar)
        progressText = findViewById(R.id.progressText)
        flashButton = findViewById(R.id.flashButton)
        backButton = findViewById(R.id.backButton)

        barNetwork = BarNetwork(this)

        findViewById<Button>(R.id.settingsButton).setOnClickListener { showSettings() }
        findViewById<Button>(R.id.logButton).setOnClickListener { showLog() }
        findViewById<Button>(R.id.folderButton).setOnClickListener { pickFolder.launch(null) }
        findViewById<TextView>(R.id.folderLink).setOnClickListener { pickFolder.launch(null) }
        findViewById<TextView>(R.id.instructionsLink).setOnClickListener { showModels() }

        searchButton.setOnClickListener { connect() }
        hmiButton.setOnClickListener { choose("hmi") }
        addonButton.setOnClickListener { choose("fizzz") }
        rcButton.setOnClickListener { choose("rc") }
        findViewById<Button>(R.id.manualButton).setOnClickListener {
            // Firmware images have no registered MIME type.
            pickSingleFile.launch(arrayOf("*/*"))
        }
        flashButton.setOnClickListener { flash() }
        backButton.setOnClickListener { goTo(step - 1) }

        goTo(1)
        restoreFolder()
    }

    override fun onResume() {
        super.onResume()
        // The user may have toggled Wi-Fi in the notification shade while away.
        refreshWifiState()
    }

    /** Without Wi-Fi there is nothing to connect to, so say that plainly. */
    private fun refreshWifiState() {
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiOn = wifi.isWifiEnabled
        if (step == 1) {
            searchButton.text =
                if (wifiOn) getString(R.string.connect_button) else getString(R.string.enable_wifi)
        }
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
        backButton.visibility = if (step == 2) View.VISIBLE else View.GONE
        // The home screen is a landing page, not a wizard step.
        stepHeader.visibility = if (step == 1) View.GONE else View.VISIBLE
        // The bottom bar shows exactly one action, whichever the step needs.
        searchButton.visibility = if (step == 1) View.VISIBLE else View.GONE
        // Step 3 runs on its own - no Flash button, and no way back mid-write.
        flashButton.visibility = View.GONE

        val done = resources.getColor(R.color.action_green, theme)
        val idle = resources.getColor(R.color.step_idle, theme)
        bar1.setBackgroundColor(if (step >= 1) done else idle)
        bar2.setBackgroundColor(if (step >= 2) done else idle)
        bar3.setBackgroundColor(if (step >= 3) done else idle)

        stepCounter.text = getString(R.string.step_of, step)
        when (step) {
            1 -> {
                stepTitle.text = getString(R.string.step1_title)
                stepHint.text = getString(R.string.step1_hint)
            }
            2 -> {
                stepTitle.text = getString(R.string.step2_title)
                stepHint.text = deviceInfo?.let { "Bar ${it.barType} - ${describeAll()}" }
                    ?: describeAll()
            }
            3 -> {
                stepTitle.text = getString(R.string.step3_title)
                stepHint.text = ""
            }
        }
    }

    // STEP 1
    /**
     * One button does the whole thing: attach, verify, move on.
     *
     * It first tries the Wi-Fi the phone is already on - if the user joined the
     * bar from system settings there is no dialog at all. Otherwise Android's
     * picker lists every AP matching the prefix. Either way the link is proven
     * with a ping before the app claims to be connected.
     */
    private fun connect() {
        val ssid = prefs().getString("ssid", "").orEmpty().trim()
        val host = prefs().getString("ip", DEFAULT_IP).orEmpty()

        if (!wifiOn) {
            // Opens the Wi-Fi panel directly; onResume will re-check on return.
            startActivity(Intent(Settings.Panel.ACTION_WIFI))
            return
        }

        searchButton.isEnabled = false
        searchButton.text = getString(R.string.identifying)

        lifecycleScope.launch {
            try {
                log("Looking at the Wi-Fi the phone is already on...")
                val existing = barNetwork.attachToCurrentWifi()
                if (existing != null && verify(existing, host)) return@launch

                if (existing != null) {
                    // Some other Wi-Fi without internet - not our device.
                    barNetwork.releaseAttachment()
                }

                // Android rejects a match-all pattern, so a prefix is mandatory.
                if (ssid.isEmpty()) {
                    stepHint.text = getString(R.string.enter_prefix)
                    return@launch
                }

                searchButton.text = getString(R.string.scanning)
                log("Searching for \"$ssid*\" ...")
                val picked = barNetwork.connect(ssid, prefs().getString("pass", ""))
                log("Joined - sockets are pinned to the bar")
                if (!verify(picked, host)) {
                    stepHint.text = "Joined, but the bar did not answer on $host"
                }
            } catch (e: Exception) {
                log("Connect failed: ${e.message}")
                stepHint.text = getString(R.string.not_connected, e.message ?: "")
            } finally {
                searchButton.isEnabled = true
                refreshWifiState()
            }
        }
    }

    /** Pings the bar, reads what it is running, then moves on to firmware. */
    private suspend fun verify(net: android.net.Network, host: String): Boolean {
        val client = OtaClient(net, host)
        val (ok, detail) = withContext(Dispatchers.IO) { client.ping() }
        log(if (ok) "Bar reachable: $detail" else "No answer: $detail")
        if (!ok) return false

        deviceInfo = withContext(Dispatchers.IO) { client.getInfo() }
        deviceInfo?.let {
            log("Bar ${it.barType}, HMI ${it.hmiVersion}")
            it.plugins.forEach { p ->
                log("  plugin ${p.type}: installed='${p.installed}' local='${p.local}' state=${p.state}")
            }
        } ?: log("Bar did not report its versions")

        model = BarModel.forBarType(deviceInfo?.barType)
        model?.let { log("Model: ${it.name} (folder \"${it.folder}\")") }
            ?: log("Unknown bar_type \"${deviceInfo?.barType}\" - no folder mapping")
        rescanForModel()

        refreshFirmwareButtons()
        goTo(2)
        return true
    }

    // STEP 2
    private fun choose(which: String) {
        manualFile = null
        component = which
        val file = fileFor(which) ?: return
        val name = file.name ?: ""
        val version = Firmware.versionFromName(name, which).ifEmpty { "?" }
        summary.text = "$name\nversion $version\ncomponent $which"
        uploadBar.visibility = View.INVISIBLE
        uploadBar.progress = 0
        progressText.text = ""
        goTo(3)
        // Picking a component IS the decision to flash it.
        flash()
    }

    private fun fileFor(which: String): DocumentFile? = manualFile ?: when (which) {
        "hmi" -> firmware.hmi
        "fizzz" -> firmware.addon
        else -> firmware.rc
    }

    /**
     * Hand-picked file: work out which component it is from the name, show what
     * is about to happen - including a downgrade - and only then flash.
     */
    private fun onManualFilePicked(uri: Uri) {
        val file = DocumentFile.fromSingleUri(this, uri) ?: return
        val name = file.name ?: "firmware.bin"

        if (!name.lowercase().endsWith(".bin")) {
            stepHint.text = "Not a .bin file: $name"
            return
        }

        val which = Firmware.componentFromName(name)
        if (which == null) {
            stepHint.text = "Cannot tell the component from \"$name\""
            return
        }

        val fileVer = Firmware.versionFromName(name, which).ifEmpty { "?" }
        val installed = deviceInfo?.installedVersion(which)
        val older = installed != null && fileVer != "?" &&
                compareVersions(fileVer, installed) < 0

        val message = buildString {
            append(name).append("\n\n")
            append("component: ").append(which).append('\n')
            append("file version: ").append(fileVer).append('\n')
            append("on the bar: ").append(installed ?: "unknown").append('\n')
            if (older) append("\n").append(getString(R.string.older_warning))
        }

        AlertDialog.Builder(this)
            .setTitle(getString(if (older) R.string.downgrade_q else R.string.install_q))
            .setMessage(message)
            .setPositiveButton(getString(if (older) R.string.downgrade else R.string.install)) { _, _ ->
                manualFile = file
                component = which
                summary.text = "$name\nversion $fileVer\ncomponent $which"
                uploadBar.visibility = View.INVISIBLE
                uploadBar.progress = 0
                progressText.text = ""
                goTo(3)
                flash()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
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

    /**
     * Re-scans using the sub-folder that belongs to the connected model.
     *
     * The user picks one root folder; each model keeps its own sub-folder inside
     * it. If the sub-folder is missing the root itself is used, and that is said
     * out loud rather than silently offering another model's firmware.
     */
    private fun rescanForModel() {
        val rootUri = prefs().getString("folder", null)?.let { Uri.parse(it) } ?: return
        scanFolder(rootUri)
    }

    private fun scanFolder(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri) ?: return
        lifecycleScope.launch {
            val wanted = model?.folder
            val dir = if (wanted == null) root else {
                val sub = withContext(Dispatchers.IO) {
                    root.listFiles().firstOrNull {
                        it.isDirectory && it.name?.equals(wanted, ignoreCase = true) == true
                    }
                }
                if (sub == null) {
                    log("No \"$wanted\" sub-folder in ${root.name} - scanning the root instead")
                    root
                } else sub
            }

            firmware = withContext(Dispatchers.IO) { Firmware.scan(dir) }
            refreshFirmwareButtons()
            if (step == 2) stepHint.text = describeAll()
            folderStatus.text = summariseFolder(dir.name)
            log("Folder scanned: ${dir.name}")
        }
    }

    /**
     * Caption and enabled state for one component.
     *
     * A component is "up to date" only when the bar actually told us what it is
     * running and that is not older than the file. When the bar stays silent -
     * an unplugged addon reports an empty version - the button stays available
     * rather than pretending to know.
     */
    private fun applyLabel(button: Button, title: String, file: DocumentFile?, which: String) {
        val name = file?.name
        if (name == null) {
            button.text = "$title - " + getString(R.string.none_found)
            button.setTextColor(resources.getColor(R.color.text_muted, theme))
            button.isEnabled = false
            return
        }

        val fileVer = Firmware.versionFromName(name, which).ifEmpty { "?" }
        val installed = deviceInfo?.installedVersion(which)

        if (installed == null) {
            button.text = "$title  v$fileVer"
            button.setTextColor(0xFFFFFFFF.toInt())
            button.isEnabled = true
            return
        }

        val upToDate = compareVersions(installed, fileVer) >= 0
        if (upToDate) {
            button.text = "$title  v$installed  ✓ " + getString(R.string.up_to_date)
            button.setTextColor(resources.getColor(R.color.ok, theme))
            button.isEnabled = false
        } else {
            button.text = "$title  v$installed → v$fileVer"
            button.setTextColor(0xFFFFFFFF.toInt())
            button.isEnabled = true
        }
    }

    private fun refreshFirmwareButtons() {
        applyLabel(hmiButton, "HMI", firmware.hmi, "hmi")
        applyLabel(addonButton, "ADDON", firmware.addon, "fizzz")
        applyLabel(rcButton, "RC", firmware.rc, "rc")
    }

    /** One line for the home screen: where firmware comes from and what is in it. */
    private fun summariseFolder(name: String?): String {
        val found = listOfNotNull(
            firmware.hmi?.let { "HMI" },
            firmware.addon?.let { "ADDON" },
            firmware.rc?.let { "RC" }
        )
        return if (found.isEmpty()) "${name ?: "Folder"} - no .bin files found"
        else "${name ?: "Folder"} - ${found.joinToString(", ")}"
    }

    private fun describeAll(): String =
        if (firmware.hmi == null && firmware.addon == null && firmware.rc == null)
            getString(R.string.no_bins)
        else getString(R.string.pick_what)

    // STEP 3
    private fun flash() {
        val net = barNetwork.network
            ?: run { progressText.text = getString(R.string.link_lost); return }
        val host = prefs().getString("ip", DEFAULT_IP).orEmpty()
        val file = fileFor(component) ?: return

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

                val outcome = withContext(Dispatchers.IO) {
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
                                progressText.text =
                                    if (percent >= 100) getString(R.string.all_sent, sizeKb)
                                    else getString(R.string.sending, percent, sizeKb)
                            }
                        },
                        onWait = { left ->
                            lifecycleScope.launch {
                                progressText.text = if (left > 0)
                                    getString(R.string.retry_in, left) else "Retrying now"
                            }
                        }
                    ) { line -> lifecycleScope.launch { log(line) } }
                }

                when (outcome) {
                    UploadOutcome.CONFIRMED, UploadOutcome.DELIVERED_UNCONFIRMED -> {
                        uploadBar.progress = 100
                        val confirmed = outcome == UploadOutcome.CONFIRMED
                        progressText.text = when {
                            component == "hmi" -> getString(R.string.delivered_hmi)
                            component == "fizzz" -> getString(R.string.delivered_addon)
                            else -> getString(R.string.delivered)
                        }
                        log(if (confirmed) "Bar confirmed: $name" else "Delivered without reply: $name")
                        kotlinx.coroutines.delay(3000)
                        // Back to the start: the AP is gone after a reboot anyway.
                        goTo(1)
                    }
                    UploadOutcome.FAILED -> {
                        progressText.text = getString(R.string.failed_see_log)
                        kotlinx.coroutines.delay(4000)
                        goTo(2)
                    }
                }
            } catch (e: Exception) {
                progressText.text = "Error: ${e.message}"
                log("Error: ${e.message}")
            } finally {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        val langGroup = view.findViewById<RadioGroup>(R.id.langGroup)
        val tc = view.findViewById<CheckBox>(R.id.tcCheck)
        val retry = view.findViewById<CheckBox>(R.id.retryCheck)

        // Reflect the language currently in force.
        val currentLang = AppCompatDelegate.getApplicationLocales()
            .takeIf { !it.isEmpty }?.get(0)?.language ?: "en"
        langGroup.check(if (currentLang.startsWith("iw") || currentLang.startsWith("he"))
            R.id.langIw else R.id.langEn)

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
            .setTitle(getString(R.string.setup))
            .setView(view)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                prefs().edit()
                    .putString("ssid", ssid.text.toString().trim())
                    .putString("pass", pass.text.toString())
                    .putString("ip", ip.text.toString().trim())
                    .putBoolean("tc", tc.isChecked)
                    .putBoolean("retry", retry.isChecked)
                    .apply()

                // Changing the locale recreates the activity, so do it last.
                val tag = if (langGroup.checkedRadioButtonId == R.id.langIw) "iw" else "en"
                if (tag != currentLang) {
                    prefs().edit().putString("lang", tag).apply()
                    AppCompatDelegate.setApplicationLocales(
                        LocaleListCompat.forLanguageTags(tag)
                    )
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * Device list. Once a bar is connected only that one is shown - there is no
     * point offering a choice the app has already made from the bar's report.
     */
    private fun showModels() {
        val shown = model?.let { listOf(it) } ?: BarModel.ALL

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }

        shown.forEach { m ->
            val row = layoutInflater.inflate(R.layout.item_model, container, false)
            row.findViewById<TextView>(R.id.modelName).text = m.name
            row.findViewById<TextView>(R.id.modelSubtitle).text = m.subtitle
            row.findViewById<TextView>(R.id.modelFolder).text = getString(R.string.folder_label, m.folder)
            row.findViewById<TextView>(R.id.modelBadge).text =
                if (model != null) getString(R.string.connected_badge) else ""
            // Photos are not available yet; the placeholder background stands in.
            row.findViewById<ImageView>(R.id.modelPhoto).setImageDrawable(null)
            container.addView(row)
        }

        val scroll = ScrollView(this).apply { addView(container) }
        AlertDialog.Builder(this)
            .setTitle(getString(if (model != null) R.string.connected_device else R.string.supported_devices))
            .setView(scroll)
            .setPositiveButton(getString(R.string.close), null)
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
            .setTitle(getString(R.string.log))
            .setView(scroll)
            .setPositiveButton(getString(R.string.close), null)
            .setNeutralButton(getString(R.string.clear)) { _, _ -> logLines.clear() }
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