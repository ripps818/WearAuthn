package me.henneke.wearauthn.ui.main

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.ComponentName
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.support.wearable.complications.ComplicationProviderService
import android.support.wearable.complications.ProviderUpdateRequester
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.google.android.gms.common.util.Hex
import me.henneke.wearauthn.Logging
import me.henneke.wearauthn.R
import me.henneke.wearauthn.bthid.HidDataSender
import me.henneke.wearauthn.bthid.HidDeviceProfile
import me.henneke.wearauthn.bthid.HidIntrDataListener
import me.henneke.wearauthn.bthid.InputHostWrapper
import me.henneke.wearauthn.bthid.defaultAdapter
import me.henneke.wearauthn.bthid.identifier
import me.henneke.wearauthn.bthid.isBluetoothEnabled
import me.henneke.wearauthn.complication.ShortcutComplicationProviderService
import me.henneke.wearauthn.d
import me.henneke.wearauthn.e
import me.henneke.wearauthn.fido.context.AuthenticatorStatus
import me.henneke.wearauthn.fido.hid.TransactionManager
import me.henneke.wearauthn.i
import me.henneke.wearauthn.ui.WearButton
import me.henneke.wearauthn.ui.WearListScreen
import me.henneke.wearauthn.ui.theme.WearAuthnTheme
import me.henneke.wearauthn.v
import me.henneke.wearauthn.w

@ExperimentalUnsignedTypes
class AuthenticatorAttachedActivity : ComponentActivity(), Logging {

    override val TAG = "AuthenticatorAttachedActivity"

    private var transactionManager: TransactionManager? = null
    private var hidDeviceProfile: HidDeviceProfile? = null
    private lateinit var authenticatorContext: HidAuthenticatorContext
    private var deviceName by mutableStateOf<String?>(null)
    private var connectionStatus by mutableStateOf("")
    private var isConnecting by mutableStateOf(false)
    private var isReconnecting by mutableStateOf(false)
    private var isAmbient by mutableStateOf(false)

    private val ambientObserver by lazy {
        AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    isAmbient = true
                }

                override fun onExitAmbient() {
                    isAmbient = false
                }
            },
        )
    }

    private val hidIntrDataListener = object : HidIntrDataListener {
        override fun onIntrData(
            device: BluetoothDevice,
            reportId: Byte,
            data: ByteArray,
            host: InputHostWrapper,
        ) {
            i { "Received report" }
            d { "Report ID: $reportId" }
            v { "<- ${Hex.bytesToStringUppercase(data)}" }
            transactionManager?.handleReport(data) {
                for (rawReport in it) {
                    v { "-> ${Hex.bytesToStringUppercase(rawReport)}" }
                    host.sendReport(device, reportId.toInt(), rawReport)
                }
            }
        }
    }

    private val hidProfileListener = object : HidDataSender.ProfileListener {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            runOnUiThread {
                deviceName = device.identifier
                when (state) {
                    BluetoothProfile.STATE_CONNECTING -> {
                        connectionStatus = getString(R.string.status_bluetooth_connecting)
                        isConnecting = true
                        isReconnecting = false
                    }
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectionStatus = getString(R.string.status_bluetooth_connected)
                        isConnecting = false
                        isReconnecting = false
                    }
                    BluetoothProfile.STATE_DISCONNECTING,
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connectionStatus = getString(R.string.status_bluetooth_reconnecting)
                        isConnecting = false
                        isReconnecting = true
                    }
                }
            }
        }

        override fun onAppStatusChanged(registered: Boolean) {
            if (!registered) {
                runOnUiThread {
                    connectionStatus = getString(R.string.status_bluetooth_reconnecting)
                    isReconnecting = true
                }
            }
        }

        override fun onServiceStateChanged(proxy: BluetoothProfile?) = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContent {
            WearAuthnTheme {
                BackHandler { finish() }
                WearListScreen(title = deviceName ?: stringResource(R.string.app_name)) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 6.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .background(
                                        color = if (isAmbient) {
                                            Color.Transparent
                                        } else if (isConnecting || isReconnecting) {
                                            MaterialTheme.colorScheme.surfaceContainerHigh
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_bluetooth),
                                    contentDescription = null,
                                    tint = if (isAmbient || isConnecting || isReconnecting) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.primary
                                    },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                    item {
                        Text(
                            text = connectionStatus.ifEmpty {
                                stringResource(R.string.status_bluetooth_connected)
                            },
                            color = if (isAmbient || isConnecting || isReconnecting) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                    item {
                        Text(
                            text = stringResource(R.string.connected_to_device_explanation),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 18.dp, vertical = 4.dp),
                        )
                    }
                    if (!isAmbient) {
                        item {
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        item {
                            WearButton(
                                label = stringResource(R.string.action_disconnect),
                                onClick = { finish() },
                                colors = ButtonDefaults.filledTonalButtonColors(),
                            )
                        }
                    }
                }
            }
        }
        authenticatorContext = HidAuthenticatorContext(this)
        hidDeviceProfile = HidDataSender.register(this, hidProfileListener, hidIntrDataListener)
    }

    @SuppressLint("MissingPermission")
    override fun onStart() {
        super.onStart()
        if (!isBluetoothEnabled) startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        HidDataSender.ensureAppRegistered()
        transactionManager = TransactionManager(authenticatorContext)
        val profile = hidDeviceProfile ?: run {
            e { "hidDeviceProfile is null" }
            finish()
            return
        }
        if (profile.connectedDevices.isEmpty() && intent.hasExtra(EXTRA_DEVICE)) {
            if (intent.hasExtra(ComplicationProviderService.EXTRA_COMPLICATION_ID)) {
                ProviderUpdateRequester(
                    this,
                    ComponentName(this, ShortcutComplicationProviderService::class.java),
                ).requestUpdateAll()
            }
            val device = intent.bluetoothDeviceExtra(EXTRA_DEVICE)
            if (device == null || device !in defaultAdapter.bondedDevices) {
                startActivity(Intent(this, AuthenticatorActivity::class.java))
                finish()
                return
            }
            deviceName = device.identifier
            connectionStatus = getString(R.string.status_bluetooth_connecting)
            isConnecting = true
            isReconnecting = false
            hidProfileListener.onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTING)
            HidDataSender.requestConnect(device)
        } else if (profile.connectedDevices.isEmpty()) {
            connectionStatus = getString(R.string.status_bluetooth_reconnecting)
            isReconnecting = true
        } else {
            val connectedDevice = profile.connectedDevices.first()
            deviceName = connectedDevice.identifier
            connectionStatus = getString(R.string.status_bluetooth_connected)
            isConnecting = false
            isReconnecting = false
            hidProfileListener.onConnectionStateChanged(connectedDevice, BluetoothProfile.STATE_CONNECTED)
        }
    }

    override fun onResume() {
        super.onResume()
        HidDataSender.ensureAppRegistered()
    }

    override fun onStop() {
        super.onStop()
        if (authenticatorContext.status != AuthenticatorStatus.IDLE) {
            w { "onStop() called during authenticator action" }
            return
        }
        HidDataSender.requestConnect(null)
        transactionManager = null
    }

    override fun onDestroy() {
        HidDataSender.unregister(hidProfileListener, hidIntrDataListener)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DEVICE = "me.henneke.wearauthn.extra.DEVICE"
    }
}

@Suppress("DEPRECATION")
private fun Intent.bluetoothDeviceExtra(key: String): BluetoothDevice? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, BluetoothDevice::class.java)
    } else {
        getParcelableExtra(key)
    }
