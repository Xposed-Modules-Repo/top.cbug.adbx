package top.cbug.adbx

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.cbug.adbx.store.Settings as AppSettings
import top.cbug.adbx.ui.StatusIndicatorView
import top.cbug.adbx.ui.WifiAdapter
import top.cbug.adbx.ui.WifiItem
import top.cbug.adbx.util.AdbHelper
import top.cbug.adbx.util.ShellUtils
import top.cbug.adbx.util.WifiHelper
import top.cbug.adbx.util.XposedStatus

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ADB_X_Main"
        private const val REQUEST_LOCATION = 1001
    }

    private val bgScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiAdapter = WifiAdapter()
    private var refreshInProgress = false

    private lateinit var cardXposedStatus: MaterialCardView
    private lateinit var tvXposedTitle: TextView
    private lateinit var tvXposedSubtitle: TextView
    private lateinit var chipXposedState: Chip

    private lateinit var siAdb: StatusIndicatorView
    private lateinit var siPairing: StatusIndicatorView
    private lateinit var siPort: StatusIndicatorView
    private lateinit var siWifi: StatusIndicatorView
    private lateinit var siRoot: StatusIndicatorView
    private lateinit var toggleAdb: MaterialButtonToggleGroup
    private lateinit var btnEnableAdb: MaterialButton
    private lateinit var btnDisableAdb: MaterialButton

    private lateinit var swFixedPort: MaterialSwitch
    private lateinit var tilPort: TextInputLayout
    private lateinit var etPort: TextInputEditText
    private lateinit var btnApplyPort: MaterialButton

    private lateinit var tvPairingCode: TextView
    private lateinit var btnPairingCode: MaterialButton
    private lateinit var btnSetPairingCode: MaterialButton

    private lateinit var swAutoEnable: MaterialSwitch
    private lateinit var swAutoDisable: MaterialSwitch
    private lateinit var swBootStart: MaterialSwitch

    private lateinit var cgTrusted: ChipGroup
    private lateinit var btnRefreshWifi: MaterialButton
    private lateinit var rvWifi: RecyclerView

    // Cached values for click-to-copy
    private var cachedLocalIp: String = ""
    private var cachedPort: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            setSupportActionBar(findViewById(R.id.toolbar))

            bindViews()
            siAdb.setLabel(getString(R.string.si_label_adb))
            siPairing.setLabel(getString(R.string.si_label_pairing))
            siPort.setLabel(getString(R.string.si_label_port))
            siWifi.setLabel(getString(R.string.si_label_wifi))
            siRoot.setLabel(getString(R.string.si_label_root))
            renderAllIndicatorsPending()

            AppSettings.load(this)

            swFixedPort.isChecked = AppSettings.fixedPortEnabled
            etPort.setText(AppSettings.fixedPort.toString())
            swAutoEnable.isChecked = AppSettings.autoEnable
            swAutoDisable.isChecked = AppSettings.autoDisable
            swBootStart.isChecked = AppSettings.bootStart

            setupListeners()
            renderTrustedChips()
            renderXposedStatus()

            mainHandler.post {
                requestNeededPermissions()
                doMinimalRefresh()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onCreate crashed", t)
            try {
                val tv = TextView(this)
                tv.text = getString(R.string.msg_init_failed, t.message ?: "")
                tv.setPadding(32, 32, 32, 32)
                setContentView(tv)
            } catch (_: Throwable) { }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        renderXposedStatus()
        renderTrustedChips()
    }

    override fun onDestroy() {
        super.onDestroy()
        bgScope.cancel()
    }

    private fun bindViews() {
        cardXposedStatus = findViewById(R.id.cardXposedStatus)
        tvXposedTitle = findViewById(R.id.tvXposedTitle)
        tvXposedSubtitle = findViewById(R.id.tvXposedSubtitle)
        chipXposedState = findViewById(R.id.chipXposedState)

        siAdb = findViewById(R.id.siAdb)
        siPairing = findViewById(R.id.siPairing)
        siPort = findViewById(R.id.siPort)
        siWifi = findViewById(R.id.siWifi)
        siRoot = findViewById(R.id.siRoot)
        toggleAdb = findViewById(R.id.toggleAdb)
        btnEnableAdb = findViewById(R.id.btnEnableAdb)
        btnDisableAdb = findViewById(R.id.btnDisableAdb)

        swFixedPort = findViewById(R.id.swFixedPort)
        tilPort = findViewById(R.id.tilPort)
        etPort = findViewById(R.id.etPort)
        btnApplyPort = findViewById(R.id.btnApplyPort)

        tvPairingCode = findViewById(R.id.tvPairingCode)
        btnPairingCode = findViewById(R.id.btnPairingCode)
        btnSetPairingCode = findViewById(R.id.btnSetPairingCode)

        swAutoEnable = findViewById(R.id.swAutoEnable)
        swAutoDisable = findViewById(R.id.swAutoDisable)
        swBootStart = findViewById(R.id.swBootStart)

        cgTrusted = findViewById(R.id.cgTrusted)
        btnRefreshWifi = findViewById(R.id.btnRefreshWifi)
        rvWifi = findViewById(R.id.rvWifi)
        rvWifi.layoutManager = LinearLayoutManager(this)
        rvWifi.adapter = wifiAdapter
    }

    private fun renderAllIndicatorsPending() {
        for (si in listOf(siAdb, siPairing, siPort, siWifi, siRoot)) {
            si.setState(StatusIndicatorView.State.UNKNOWN)
            si.setValue(getString(R.string.si_value_loading))
        }
        tvPairingCode.text = "—"
    }

    private fun renderXposedStatus() {
        val info = XposedStatus.probe(this)
        val ctx = this
        when (info.state) {
            XposedStatus.State.ACTIVE -> {
                tvXposedTitle.text = getString(R.string.xposed_active_title)
                tvXposedSubtitle.text = getString(R.string.xposed_active_subtitle)
                chipXposedState.setText(R.string.xposed_state_active)
                chipXposedState.setChipIconResource(R.drawable.ic_check_circle)
                chipXposedState.chipBackgroundColor =
                    ContextCompat.getColorStateList(ctx, R.color.status_ok)
                chipXposedState.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                cardXposedStatus.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.status_active_bg)
                )
            }
            XposedStatus.State.INACTIVE -> {
                tvXposedTitle.text = getString(R.string.xposed_inactive_title)
                val frame = info.frameworkPackages.joinToString(", ")
                tvXposedSubtitle.text =
                    getString(R.string.xposed_inactive_subtitle_with_frame, frame)
                chipXposedState.setText(R.string.xposed_state_inactive)
                chipXposedState.setChipIconResource(R.drawable.ic_warning)
                chipXposedState.chipBackgroundColor =
                    ContextCompat.getColorStateList(ctx, R.color.status_warn)
                chipXposedState.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                cardXposedStatus.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.status_inactive_bg)
                )
            }
            XposedStatus.State.UNKNOWN -> {
                tvXposedTitle.text = getString(R.string.xposed_inactive_title)
                tvXposedSubtitle.text = getString(R.string.xposed_inactive_subtitle_no_frame)
                chipXposedState.setText(R.string.xposed_state_unknown)
                chipXposedState.setChipIconResource(R.drawable.ic_warning)
                chipXposedState.chipBackgroundColor =
                    ContextCompat.getColorStateList(ctx, R.color.status_unknown)
                chipXposedState.setTextColor(ContextCompat.getColor(ctx, R.color.white))
                cardXposedStatus.setCardBackgroundColor(
                    ContextCompat.getColor(ctx, R.color.status_unknown_bg)
                )
            }
        }
    }

    private fun renderTrustedChips() {
        cgTrusted.removeAllViews()
        val trusted = AppSettings.trustedSet()
        if (trusted.isEmpty()) return
        for (ssid in trusted.sorted()) {
            val chip = Chip(this).apply {
                text = ssid
                isCloseIconVisible = true
                isCheckable = false
                setOnCloseIconClickListener {
                    AppSettings.removeTrusted(ssid)
                    AppSettings.save(this@MainActivity)
                    renderTrustedChips()
                }
            }
            cgTrusted.addView(chip)
        }
    }

    private fun setupListeners() {
        swFixedPort.setOnCheckedChangeListener { _, checked ->
            AppSettings.fixedPortEnabled = checked
            AppSettings.save(this)
        }
        btnApplyPort.setOnClickListener {
            val portText = etPort.text?.toString()?.trim().orEmpty()
            val port = portText.toIntOrNull()
            if (port == null || port < 1024 || port > 65535) {
                tilPort.error = getString(R.string.err_port_range)
                return@setOnClickListener
                }
                tilPort.error = null
                AppSettings.fixedPort = port
                AppSettings.save(this)
                toast(getString(R.string.msg_setting_port, port))
                bgScope.launch {
                val ok = AdbHelper.setFixedPort(port)
                withContext(Dispatchers.Main) {
                    toast(getString(if (ok) R.string.msg_fixed_port_ok else R.string.msg_fixed_port_fail, port))
                }
                doFullRefresh()
                }
                }
        btnRefreshWifi.setOnClickListener {
            requestNeededPermissions()
            refreshWifiList()
        }
        swAutoEnable.setOnCheckedChangeListener { _, checked ->
            AppSettings.autoEnable = checked
            AppSettings.save(this)
        }
        swAutoDisable.setOnCheckedChangeListener { _, checked ->
            AppSettings.autoDisable = checked
            AppSettings.save(this)
        }
        swBootStart.setOnCheckedChangeListener { _, checked ->
            AppSettings.bootStart = checked
            AppSettings.save(this)
        }
        btnPairingCode.setOnClickListener { doFullRefresh() }
        btnSetPairingCode.setOnClickListener { showSetPairingCodeDialog() }

        toggleAdb.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnEnableAdb -> bgScope.launch {
                    withContext(Dispatchers.Main) { toast(getString(R.string.msg_enabling_adb)) }
                    AdbHelper.enableWirelessAdb()
                    doFullRefresh()
                }
                R.id.btnDisableAdb -> bgScope.launch {
                    withContext(Dispatchers.Main) { toast(getString(R.string.msg_disabling_adb)) }
                    AdbHelper.disableWirelessAdb()
                    doFullRefresh()
                }
            }
        }

        cardXposedStatus.setOnClickListener { showXposedHelpDialog() }

        // Click-to-copy: Port card copies "ip:port"
        siPort.setCardOnClickListener {
            val text = if (cachedLocalIp.isNotEmpty() && cachedPort.isNotEmpty())
                "$cachedLocalIp:$cachedPort" else cachedPort
            if (text.isNotEmpty()) {
                copyToClipboard("ADB address", text)
                toast(getString(R.string.msg_copied, text))
            }
        }
        // Click-to-copy: WiFi card copies local IP
        siWifi.setCardOnClickListener {
            if (cachedLocalIp.isNotEmpty()) {
                copyToClipboard("Device IP", cachedLocalIp)
                toast(getString(R.string.msg_copied_ip, cachedLocalIp))
            }
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    private fun showXposedHelpDialog() {
        val info = XposedStatus.probe(this)
        val detected = if (info.frameworkPackages.isEmpty()) "  (none)"
            else info.frameworkPackages.joinToString("\n") { "  • $it" }
        val msg = buildString {
            appendLine("Hint: ${info.frameworkHint}")
            appendLine()
            appendLine("Framework packages detected:")
            appendLine(detected)
            appendLine()
            appendLine("Module state: ${info.state}")
            appendLine()
            appendLine("To activate:")
            appendLine("1. Open your Xposed manager (LSPosed, etc.)")
            appendLine("2. Enable the ADB_X module")
            appendLine("3. Set scope to: android + com.android.settings")
            appendLine("4. Reboot the device")
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.xposed_title))
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(getString(R.string.dialog_xposed_refresh)) { _: DialogInterface, _: Int -> renderXposedStatus() }
            .show()
    }

    private fun showSetPairingCodeDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.dialog_pairing_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getString(R.string.dialog_pairing_default))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.dialog_pairing_title))
            .setMessage(getString(R.string.dialog_pairing_msg))
            .setView(input)
            .setPositiveButton("Save") { _: DialogInterface, _: Int ->
                val code = input.text.toString().trim()
                if (code.length !in 6..8 || !code.all { it.isDigit() }) {
                    toast(getString(R.string.err_pairing_length))
                    return@setPositiveButton
                }
                AdbHelper.setPairingCode(code)
                toast(getString(R.string.msg_pairing_saved, code))
                doFullRefresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateIndicators(
        adbEnabled: Boolean,
        port: String,
        pairingPort: String,
        pairingCode: String,
        ssid: String,
        localIp: String,
        externalIp: String,
        hasRoot: Boolean,
        error: String? = null
    ) {
        if (isFinishing || isDestroyed) return
        cachedLocalIp = localIp
        cachedPort = port

        // ADB
        if (error != null) {
            siAdb.setState(StatusIndicatorView.State.UNKNOWN)
            siAdb.setValue(error)
        } else if (adbEnabled) {
            siAdb.setState(StatusIndicatorView.State.OK)
            siAdb.setValue(getString(R.string.si_value_enabled))
        } else {
            siAdb.setState(StatusIndicatorView.State.OFF)
            siAdb.setValue(getString(R.string.si_value_disabled))
        }

        // Port (clickable -> copies "ip:port")
        if (port.isNotBlank()) {
            siPort.setState(StatusIndicatorView.State.OK)
            siPort.setValue(port)
        } else {
            siPort.setState(StatusIndicatorView.State.OFF)
            siPort.setValue("—")
        }

        // Pairing — show code when available
        when {
            pairingCode.isNotBlank() -> {
                siPairing.setState(StatusIndicatorView.State.WARN)
                siPairing.setValue(getString(R.string.si_value_pairing_code, pairingCode))
            }
            pairingPort.isNotBlank() -> {
                siPairing.setState(StatusIndicatorView.State.WARN)
                siPairing.setValue(getString(R.string.si_value_pairing_active, pairingPort))
            }
            adbEnabled -> {
                siPairing.setState(StatusIndicatorView.State.OFF)
                siPairing.setValue(getString(R.string.si_value_pairing_idle))
            }
            else -> {
                siPairing.setState(StatusIndicatorView.State.OFF)
                siPairing.setValue("—")
            }
        }

        // WiFi — show SSID + internal IP + external IP
        if (ssid.isNotBlank()) {
            siWifi.setState(StatusIndicatorView.State.OK)
            val wifiText = buildString {
                append(ssid)
                if (localIp.isNotEmpty()) append("  |  $localIp")
                if (externalIp.isNotEmpty()) append("  |  $externalIp")
            }
            siWifi.setValue(wifiText)
        } else {
            siWifi.setState(StatusIndicatorView.State.OFF)
            val wifiFallback = when {
                localIp.isNotEmpty() -> localIp
                externalIp.isNotEmpty() -> "Ext: $externalIp"
                else -> getString(R.string.si_value_wifi_disconnected)
            }
            siWifi.setValue(wifiFallback)
        }

        // Root
        siRoot.setState(
            if (hasRoot) StatusIndicatorView.State.OK else StatusIndicatorView.State.UNKNOWN
        )
        siRoot.setValue(
            if (hasRoot) getString(R.string.si_value_available)
            else getString(R.string.si_value_unavailable)
        )
    }

    private fun doMinimalRefresh() {
        bgScope.launch {
            try {
                val currentSsid = try {
                    WifiHelper.getCurrentSsid(this@MainActivity)
                } catch (_: Exception) { "" }
                val portNonRoot = AdbHelper.getCurrentPortNonRoot()
                val adbEnabled = portNonRoot.isNotEmpty()
                val pairingPort = try {
                    AdbHelper.getPairingPort()
                } catch (_: Exception) { "" }
                val pairingCode = try {
                    AdbHelper.readPairingCode()
                } catch (_: Exception) { "" }
                val localIp = try {
                    WifiHelper.getLocalIpAddress(this@MainActivity)
                } catch (_: Exception) { "" }
                val hasRoot = ShellUtils.hasRoot()

                withContext(Dispatchers.Main) {
                    updateIndicators(
                        adbEnabled, portNonRoot, pairingPort, pairingCode,
                        currentSsid, localIp, "", hasRoot
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "doMinimalRefresh failed", t)
                withContext(Dispatchers.Main) {
                    updateIndicators(
                        adbEnabled = false, port = "", pairingPort = "",
                        pairingCode = "", ssid = "", localIp = "",
                        externalIp = "", hasRoot = false,
                        error = t.message ?: "error"
                    )
                }
            }
        }
    }

    private fun doFullRefresh() {
        if (refreshInProgress) return
        refreshInProgress = true
        bgScope.launch {
            try {
                val status = AdbHelper.getFullStatus(this@MainActivity)
                val currentSsid = try {
                    WifiHelper.getCurrentSsid(this@MainActivity)
                } catch (_: Exception) { "" }
                val localIp = try {
                    WifiHelper.getLocalIpAddress(this@MainActivity)
                } catch (_: Exception) { "" }
                // External IP is slow (HTTP request); only fetch on explicit refresh
                val externalIp = try {
                    WifiHelper.getExternalIpAddress()
                } catch (_: Exception) { "" }

                withContext(Dispatchers.Main) {
                    updateIndicators(
                        adbEnabled = status.enabled,
                        port = status.port,
                        pairingPort = status.pairingPort,
                        pairingCode = status.pairingCode,
                        ssid = currentSsid,
                        localIp = localIp,
                        externalIp = externalIp,
                        hasRoot = status.hasRoot
                    )
                    tvPairingCode.text = if (status.pairingCode.isNotBlank())
                        status.pairingCode else "—"
                }
            } catch (t: Throwable) {
                Log.w(TAG, "doFullRefresh failed", t)
                mainHandler.post {
                    updateIndicators(
                        adbEnabled = false, port = "", pairingPort = "",
                        pairingCode = "", ssid = "", localIp = "",
                        externalIp = "", hasRoot = false,
                        error = t.message ?: "error"
                    )
                }
            } finally {
                refreshInProgress = false
            }
        }
    }

    private fun refreshWifiList() {
        if (refreshInProgress) {
            toast(getString(R.string.msg_still_loading))
            return
        }
        refreshInProgress = true
        bgScope.launch {
            try {
                withContext(Dispatchers.Main) { btnRefreshWifi.text = getString(R.string.msg_scanning) }
                ShellUtils.probeRootFast()
                val networks = WifiHelper.getSavedNetworks(this@MainActivity)
                val items = networks.map { WifiItem(it.ssid, it.bssid, it.security) }
                withContext(Dispatchers.Main) {
                    wifiAdapter.update(items)
                    btnRefreshWifi.text = getString(R.string.btn_refresh_wifi)
                    val msg = if (items.isEmpty()) {
                        if (ShellUtils.hasRoot()) getString(R.string.msg_no_networks_root)
                        else getString(R.string.msg_no_networks_noroot)
                    } else {
                        getString(R.string.msg_loaded_networks, items.size)
                    }
                    toast(msg)
                    renderTrustedChips()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "refreshWifiList failed", t)
                withContext(Dispatchers.Main) {
                    btnRefreshWifi.text = getString(R.string.btn_refresh_wifi)
                    toast(getString(R.string.msg_wifi_refresh_fail, t.message ?: ""))
                }
            } finally {
                refreshInProgress = false
            }
        }
    }

    private fun toast(msg: String) {
        if (isFinishing) return
        mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
    }

    private fun requestNeededPermissions() {
        val missingPerms = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            missingPerms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            missingPerms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES)
                != PackageManager.PERMISSION_GRANTED) {
                missingPerms.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
        }
        if (missingPerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPerms.toTypedArray(), REQUEST_LOCATION)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
                toast(getString(R.string.msg_perm_granted))
            } else {
                toast(getString(R.string.msg_perm_denied))
            }
        }
    }
}