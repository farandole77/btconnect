package com.btconnect

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * 주변 블루투스 기기를 검색하고, 선택한 기기와 페어링하는 화면.
 *
 * 블루투스 메뉴가 없는 안드로이드 TV 박스에서도 동작하도록 시스템 설정 화면에 의존하지 않고,
 * 페어링 중 PIN/번호 입력 요청도 이 화면에서 직접 처리한다.
 * 블루투스 API 호출 전에는 항상 [hasPermissions]로 권한을 확인한다.
 */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager?)?.adapter
    }

    /** 검색으로 찾은 기기 (주소 → 기기). 페어링된 기기는 어댑터에서 따로 가져온다. */
    private val foundDevices = LinkedHashMap<String, BluetoothDevice>()

    /** 검색 결과에 담겨 온 기기 이름 (주소 → 이름). */
    private val deviceNames = HashMap<String, String>()

    private lateinit var statusView: TextView
    private lateinit var scanButton: Button
    private val listAdapter = DeviceListAdapter()

    private var pairingDialog: AlertDialog? = null
    private var pairingAddress: String? = null
    private var permissionAsked = false
    private var locationWarned = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handleBroadcast(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.status)
        scanButton = findViewById(R.id.scan_button)
        scanButton.setOnClickListener { onScanButtonClick() }

        val listView = findViewById<ListView>(R.id.device_list)
        listView.adapter = listAdapter
        listView.emptyView = findViewById(R.id.empty)
        listView.setOnItemClickListener { _, _, position, _ -> pair(listAdapter.getItem(position)) }

        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 블루투스 브로드캐스트는 시스템 블루투스 앱이 보내므로 외부 수신을 허용해야 한다.
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }

        render()
    }

    override fun onStart() {
        super.onStart()
        val adapter = bluetoothAdapter ?: return render()
        if (!hasPermissions()) {
            if (!permissionAsked) requestBluetoothPermissions()
        } else if (adapter.isEnabled && !adapter.isDiscovering) {
            startScan()
        }
        render()
    }

    override fun onStop() {
        super.onStop()
        // 검색은 무선 대역을 많이 쓰므로 화면을 벗어나면 멈춘다.
        if (hasPermissions()) bluetoothAdapter?.cancelDiscovery()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        pairingDialog?.dismiss()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSIONS) return
        if (hasPermissions()) {
            if (bluetoothAdapter?.isEnabled == true) startScan()
        } else {
            toast(getString(R.string.permission_denied))
        }
        render()
    }

    private fun onScanButtonClick() {
        val adapter = bluetoothAdapter ?: return
        when {
            !hasPermissions() -> requestBluetoothPermissions()
            !adapter.isEnabled -> enableBluetooth()
            adapter.isDiscovering -> adapter.cancelDiscovery()
            else -> startScan()
        }
        render()
    }

    // region 권한 / 블루투스 켜기 / 위치

    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        else -> emptyArray()
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return requiredPermissions().all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val permanentlyDenied =
            permissionAsked && requiredPermissions().none { shouldShowRequestPermissionRationale(it) }
        if (permanentlyDenied) {
            // "다시 묻지 않음" 상태이면 앱 정보 화면에서 직접 허용해야 한다.
            try {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                )
                return
            } catch (e: ActivityNotFoundException) {
                // 앱 정보 화면이 없는 기기면 권한 요청을 한 번 더 시도한다.
            }
        }
        permissionAsked = true
        requestPermissions(requiredPermissions(), REQUEST_PERMISSIONS)
    }

    private fun enableBluetooth() {
        val adapter = bluetoothAdapter ?: return
        // Android 12 이하는 확인 창 없이 바로 켤 수 있다. TV 박스에는 확인 창 자체가 없는 경우가 많다.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            @Suppress("DEPRECATION")
            if (adapter.enable()) return
        }
        try {
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
        } catch (e: ActivityNotFoundException) {
            toast(getString(R.string.enable_failed))
        }
    }

    /** Android 6~11은 위치 설정이 꺼져 있으면 검색 결과가 오지 않는다 (특히 10, 11). */
    private fun needsLocationService(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT <= Build.VERSION_CODES.R

    private fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager? ?: return true
            return locationManager.isLocationEnabled
        }
        @Suppress("DEPRECATION")
        return Settings.Secure.getInt(
            contentResolver,
            Settings.Secure.LOCATION_MODE,
            Settings.Secure.LOCATION_MODE_OFF,
        ) != Settings.Secure.LOCATION_MODE_OFF
    }

    private fun showLocationDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.location_title)
            .setMessage(R.string.location_message)
            .setPositiveButton(R.string.location_open) { _, _ -> openLocationSettings() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openLocationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            AlertDialog.Builder(this)
                .setTitle(R.string.location_title)
                .setMessage(R.string.location_no_settings)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    // endregion

    // region 검색 / 페어링

    private fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (needsLocationService() && !isLocationEnabled() && !locationWarned) {
            locationWarned = true
            showLocationDialog()
        }
        if (adapter.isDiscovering) adapter.cancelDiscovery()
        // 이전 검색 결과는 지우되, 페어링이 진행 중인 기기는 목록에 남긴다.
        foundDevices.values.removeAll { it.bondState == BluetoothDevice.BOND_NONE }
        if (!adapter.startDiscovery()) toast(getString(R.string.scan_failed))
        render()
    }

    private fun pair(device: BluetoothDevice) {
        if (!hasPermissions()) return requestBluetoothPermissions()
        val name = displayName(device)
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> toast(getString(R.string.already_bonded, name))
            BluetoothDevice.BOND_BONDING -> toast(getString(R.string.bonding_in_progress, name))
            else -> {
                // 검색 중에는 페어링이 느려지거나 실패하므로 먼저 검색을 멈춘다.
                bluetoothAdapter?.cancelDiscovery()
                foundDevices[device.address] = device
                val started = device.createBond()
                toast(getString(if (started) R.string.pair_started else R.string.pair_start_failed, name))
            }
        }
        render()
    }

    private fun handleBroadcast(intent: Intent) {
        val device = intent.bluetoothDevice()
        when (intent.action) {
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> if (hasPermissions()) startScan()
                    BluetoothAdapter.STATE_OFF -> foundDevices.clear()
                }
            }
            BluetoothDevice.ACTION_FOUND, BluetoothDevice.ACTION_NAME_CHANGED -> {
                if (device != null) {
                    intent.getStringExtra(BluetoothDevice.EXTRA_NAME)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { deviceNames[device.address] = it }
                    if (intent.action == BluetoothDevice.ACTION_FOUND) foundDevices[device.address] = device
                }
            }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> if (device != null) onBondStateChanged(device, intent)
            BluetoothDevice.ACTION_PAIRING_REQUEST -> if (device != null) showPairingRequest(device, intent)
        }
        render()
    }

    private fun onBondStateChanged(device: BluetoothDevice, intent: Intent) {
        val state = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
        val previous = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)
        val name = displayName(device)
        when {
            state == BluetoothDevice.BOND_BONDED -> toast(getString(R.string.pair_success, name))
            state == BluetoothDevice.BOND_NONE && previous == BluetoothDevice.BOND_BONDING ->
                toast(getString(R.string.pair_failed, name))
        }
        if (state != BluetoothDevice.BOND_BONDING && pairingAddress == device.address) {
            pairingDialog?.dismiss()
            pairingDialog = null
            pairingAddress = null
        }
    }

    /**
     * 페어링 중 기기가 PIN 입력이나 번호 확인을 요구할 때 호출된다.
     * 시스템 페어링 창이 없는 TV 박스에서도 PIN 입력과 번호 안내를 할 수 있도록 직접 창을 띄운다.
     * 번호 비교 승인([BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION])은 시스템 권한이 필요해
     * 일반 앱이 대신 승인할 수 없으므로 안내만 한다.
     */
    private fun showPairingRequest(device: BluetoothDevice, intent: Intent) {
        val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, BluetoothDevice.ERROR)
        val key = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, BluetoothDevice.ERROR)
        val builder = AlertDialog.Builder(this).setTitle(getString(R.string.pairing_title, displayName(device)))

        when (variant) {
            BluetoothDevice.PAIRING_VARIANT_PIN, PAIRING_VARIANT_PIN_16_DIGITS -> {
                val input = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    setText(DEFAULT_PIN)
                    setSelection(DEFAULT_PIN.length)
                }
                val padding = (24 * resources.displayMetrics.density).toInt()
                val container = FrameLayout(this).apply {
                    setPadding(padding, 0, padding, 0)
                    addView(input)
                }
                builder.setMessage(R.string.pairing_pin_message)
                    .setView(container)
                    .setPositiveButton(R.string.pair) { _, _ ->
                        device.setPin(input.text.toString().toByteArray(Charsets.UTF_8))
                    }
                    .setNegativeButton(android.R.string.cancel, null)
            }
            PAIRING_VARIANT_DISPLAY_PASSKEY ->
                builder.setMessage(getString(R.string.pairing_display_passkey, formatKey(key, 6)))
                    .setPositiveButton(android.R.string.ok, null)
            PAIRING_VARIANT_DISPLAY_PIN ->
                builder.setMessage(getString(R.string.pairing_display_pin, formatKey(key, 4)))
                    .setPositiveButton(android.R.string.ok, null)
            BluetoothDevice.PAIRING_VARIANT_PASSKEY_CONFIRMATION ->
                builder.setMessage(getString(R.string.pairing_confirm, formatKey(key, 6)))
                    .setPositiveButton(android.R.string.ok, null)
            PAIRING_VARIANT_CONSENT ->
                builder.setMessage(R.string.pairing_consent)
                    .setPositiveButton(android.R.string.ok, null)
            else -> return
        }

        pairingDialog?.dismiss()
        pairingAddress = device.address
        pairingDialog = builder.show()
    }

    // endregion

    // region 화면 표시

    private fun render() {
        val adapter = bluetoothAdapter
        when {
            adapter == null -> {
                statusView.setText(R.string.status_unsupported)
                scanButton.visibility = View.GONE
            }
            !hasPermissions() -> {
                statusView.setText(R.string.status_no_permission)
                scanButton.setText(R.string.grant_permission)
            }
            !adapter.isEnabled -> {
                statusView.setText(
                    if (adapter.state == BluetoothAdapter.STATE_TURNING_ON) R.string.status_turning_on
                    else R.string.status_off
                )
                scanButton.setText(R.string.turn_on)
            }
            adapter.isDiscovering -> {
                statusView.setText(R.string.status_scanning)
                scanButton.setText(R.string.stop_scan)
            }
            else -> {
                statusView.setText(R.string.status_ready)
                scanButton.setText(R.string.scan)
            }
        }
        listAdapter.items = currentDevices()
    }

    /** 페어링된 기기를 먼저, 그다음 검색된 기기를 보여준다. 이름 없는 기기는 맨 뒤로 보낸다. */
    private fun currentDevices(): List<BluetoothDevice> {
        val adapter = bluetoothAdapter ?: return emptyList()
        if (!hasPermissions() || !adapter.isEnabled) return emptyList()
        val bonded = adapter.bondedDevices.orEmpty().sortedBy { displayName(it).lowercase(Locale.ROOT) }
        val bondedAddresses = bonded.map { it.address }.toSet()
        val others = foundDevices.values
            .filter { it.address !in bondedAddresses }
            .sortedBy { if (knownName(it) == null) 1 else 0 }
        return bonded + others
    }

    private fun knownName(device: BluetoothDevice): String? =
        deviceNames[device.address] ?: device.name?.takeIf { it.isNotBlank() }

    private fun displayName(device: BluetoothDevice): String =
        knownName(device) ?: getString(R.string.unknown_device)

    private fun stateText(device: BluetoothDevice): String = getString(
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> R.string.state_bonded
            BluetoothDevice.BOND_BONDING -> R.string.state_bonding
            else -> R.string.state_available
        }
    )

    private fun formatKey(key: Int, digits: Int): String = String.format(Locale.US, "%0${digits}d", key)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private inner class DeviceListAdapter : BaseAdapter() {
        var items: List<BluetoothDevice> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): BluetoothDevice = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(android.R.layout.simple_list_item_2, parent, false)
            val device = items[position]
            view.findViewById<TextView>(android.R.id.text1).text = displayName(device)
            view.findViewById<TextView>(android.R.id.text2).text = "${device.address} · ${stateText(device)}"
            return view
        }
    }

    // endregion

    private fun Intent.bluetoothDevice(): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        }

    private companion object {
        const val REQUEST_PERMISSIONS = 1
        const val REQUEST_ENABLE_BLUETOOTH = 2
        const val DEFAULT_PIN = "0000"

        // BluetoothDevice에 숨겨진(@hide) 페어링 방식 상수. 값은 AOSP에서 고정되어 있다.
        const val PAIRING_VARIANT_CONSENT = 3
        const val PAIRING_VARIANT_DISPLAY_PASSKEY = 4
        const val PAIRING_VARIANT_DISPLAY_PIN = 5
        const val PAIRING_VARIANT_PIN_16_DIGITS = 7
    }
}
