package com.hydrotrap.floodalert

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * BLE broadcast flood/disaster alert — works for ANY flood-affected region,
 * not hardcoded to one city. Users tag their own alerts with a region name
 * (e.g. "Kathmandu", "Hyderabad - Jubilee Hills", "Rasuwa district") so the
 * same app works wherever it's needed.
 *
 * FIX in this version: the app used to crash after a few seconds if
 * Bluetooth was off when it opened, because it tried to grab the
 * advertiser/scanner from a disabled adapter (which returns null) without
 * checking first. This version checks Bluetooth state BEFORE touching
 * anything, prompts the user to turn it on if needed, and never assumes
 * anything Bluetooth-related exists without checking.
 */
class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = ParcelUuid(UUID.fromString("0000FEED-0000-1000-8000-00805F9B34FB"))
    private val MAX_TTL = 3
    private val REQUEST_ENABLE_BT = 2
    private val seenIds = mutableSetOf<Int>()
    private lateinit var logFile: File

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanner: BluetoothLeScanner? = null

    private lateinit var logView: TextView
    private lateinit var statusView: TextView
    private lateinit var relayCountView: TextView
    private lateinit var regionInput: EditText
    private lateinit var enableBtBtn: Button
    private var relayedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        logFile = File(filesDir, "flood_alert_log.txt")

        setContentView(buildUi())

        // ---- Step 1: does this device even have Bluetooth hardware? ----
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        if (bluetoothAdapter == null) {
            statusView.text = "Status: this device has no Bluetooth hardware — app can't work here"
            enableBtBtn.visibility = View.GONE
            return
        }

        requestBlePermissions()
    }

    // ---------- UI ----------
    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            setBackgroundColor(Color.parseColor("#F5F7FA"))
        }

        val title = TextView(this).apply {
            text = "Flood Alert Mesh"
            textSize = 24f
            setTextColor(Color.parseColor("#1B3F8B"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }

        statusView = TextView(this).apply {
            text = "Status: checking Bluetooth..."
            textSize = 13f
            setTextColor(Color.parseColor("#5A6B7A"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        enableBtBtn = Button(this).apply {
            text = "Enable Bluetooth"
            setBackgroundColor(Color.parseColor("#1B3F8B"))
            setTextColor(Color.WHITE)
            visibility = View.GONE
            setOnClickListener { promptEnableBluetooth() }
        }

        regionInput = EditText(this).apply {
            hint = "Your area/region (e.g. Kathmandu, Hyderabad - Jubilee Hills)"
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
        }

        val sosBtn = Button(this).apply {
            text = "SEND SOS ALERT"
            textSize = 18f
            setBackgroundColor(Color.parseColor("#D32F2F"))
            setTextColor(Color.WHITE)
            setPadding(0, 32, 0, 32)
            setOnClickListener {
                val region = regionInput.text.toString().ifBlank { "Unspecified area" }
                broadcastAlert("SOS Flood - $region", MAX_TTL)
            }
        }

        val customInput = EditText(this).apply {
            hint = "Or type a custom alert (e.g. Water rising fast)"
            setPadding(16, 16, 16, 16)
            setBackgroundColor(Color.WHITE)
        }
        val customBtn = Button(this).apply {
            text = "Broadcast Custom Alert"
            setOnClickListener {
                val region = regionInput.text.toString().ifBlank { "Unspecified area" }
                val msg = customInput.text.toString()
                if (msg.isNotBlank()) broadcastAlert("$msg - $region", MAX_TTL)
            }
        }

        relayCountView = TextView(this).apply {
            text = "Alerts relayed this session: 0"
            textSize = 13f
            setPadding(0, 24, 0, 8)
        }

        val logLabel = TextView(this).apply { text = "Activity log:"; textSize = 13f; setPadding(0, 16, 0, 4) }
        logView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#333333"))
            setBackgroundColor(Color.WHITE)
            setPadding(16, 16, 16, 16)
        }
        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 500)
            addView(logView)
        }

        root.addView(title); root.addView(statusView); root.addView(enableBtBtn)
        root.addView(regionInput)
        root.addView(sosBtn)
        root.addView(customInput); root.addView(customBtn)
        root.addView(relayCountView)
        root.addView(logLabel); root.addView(scroll)
        return root
    }

    // ---------- Permissions, THEN Bluetooth-enabled check — in that order ----------
    private fun requestBlePermissions() {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter { ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            checkBluetoothEnabled()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val allGranted = grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (allGranted) {
            checkBluetoothEnabled()
        } else {
            statusView.text = "Status: Bluetooth permission needed — restart app after granting"
        }
    }

    // ---------- THE ACTUAL FIX: check enabled state before ever touching advertiser/scanner ----------
    private fun checkBluetoothEnabled() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            statusView.text = "Status: Bluetooth is OFF — tap the button below to enable it"
            enableBtBtn.visibility = View.VISIBLE
            return
        }
        enableBtBtn.visibility = View.GONE
        setupBleAndStart(adapter)
    }

    private fun promptEnableBluetooth() {
        try {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT)
        } catch (e: SecurityException) {
            statusView.text = "Status: please turn on Bluetooth manually in system settings"
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT) {
            if (resultCode == Activity.RESULT_OK) {
                checkBluetoothEnabled() // re-check — this time it should be on
            } else {
                statusView.text = "Status: Bluetooth still off — app needs it to work"
            }
        }
    }

    private fun setupBleAndStart(adapter: BluetoothAdapter) {
        advertiser = adapter.bluetoothLeAdvertiser
        scanner = adapter.bluetoothLeScanner
        if (advertiser == null || scanner == null) {
            statusView.text = "Status: this device's Bluetooth doesn't support BLE advertising"
            return
        }
        statusView.text = "Status: ready — listening for nearby alerts"
        startListening()
    }

    // ---------- Wire format ----------
    private fun encode(ttl: Int, id: Int, text: String): ByteArray {
        val textBytes = text.toByteArray().copyOf(14)
        val buf = ByteArray(5 + textBytes.size)
        buf[0] = ttl.toByte()
        buf[1] = (id shr 24).toByte(); buf[2] = (id shr 16).toByte()
        buf[3] = (id shr 8).toByte();  buf[4] = id.toByte()
        System.arraycopy(textBytes, 0, buf, 5, textBytes.size)
        return buf
    }

    private fun decode(bytes: ByteArray): Triple<Int, Int, String> {
        val ttl = bytes[0].toInt()
        val id = ((bytes[1].toInt() and 0xFF) shl 24) or ((bytes[2].toInt() and 0xFF) shl 16) or
                 ((bytes[3].toInt() and 0xFF) shl 8) or (bytes[4].toInt() and 0xFF)
        val text = String(bytes.copyOfRange(5, bytes.size)).trim { it.code == 0 }
        return Triple(ttl, id, text)
    }

    // ---------- Broadcast / relay — every call checks state, never assumes ----------
    private fun broadcastAlert(message: String, ttl: Int, existingId: Int? = null) {
        val adv = advertiser
        if (adv == null) { log("Can't broadcast — Bluetooth not ready"); return }
        if (ttl <= 0) { log("TTL expired — not relaying further: \"$message\""); return }

        val id = existingId ?: (message.hashCode() xor System.currentTimeMillis().toInt())
        seenIds.add(id)

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(8_000)
            .build()

        val data = AdvertiseData.Builder()
            .addServiceUuid(SERVICE_UUID)
            .addServiceData(SERVICE_UUID, encode(ttl, id, message))
            .setIncludeDeviceName(false)
            .build()

        try {
            adv.startAdvertising(settings, data, advertiseCallback)
            log("Broadcasting (TTL=$ttl): \"$message\"")
        } catch (e: SecurityException) {
            log("Permission missing for advertising: ${e.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { log("Advertise failed: code $errorCode") }
    }

    private fun startListening() {
        val scn = scanner ?: return
        val filter = ScanFilter.Builder().setServiceUuid(SERVICE_UUID).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scn.startScan(listOf(filter), settings, scanCallback)
        } catch (e: SecurityException) {
            log("Permission missing for scanning: ${e.message}")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val bytes = result.scanRecord?.getServiceData(SERVICE_UUID) ?: return
            if (bytes.size < 5) return
            val (ttl, id, text) = decode(bytes)

            if (seenIds.contains(id)) return
            seenIds.add(id)

            log("Received (TTL=$ttl) from ${result.device.address}: \"$text\"")
            relayedCount++
            runOnUiThread { relayCountView.text = "Alerts relayed this session: $relayedCount" }

            broadcastAlert(text, ttl - 1, id)
        }
        override fun onScanFailed(errorCode: Int) { log("Scan failed: code $errorCode") }
    }

    // ---------- Offline logging ----------
    private fun log(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val line = "[$stamp] $text"
        runOnUiThread { logView.append("$line\n") }
        Log.d("FloodAlert", line)
        try { logFile.appendText("$line\n") } catch (e: Exception) { Log.e("FloodAlert", "log write failed", e) }
    }
}
