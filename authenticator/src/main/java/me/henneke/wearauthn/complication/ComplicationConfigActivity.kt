package me.henneke.wearauthn.complication

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.support.wearable.complications.ComplicationProviderService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import me.henneke.wearauthn.R
import me.henneke.wearauthn.bthid.canUseAuthenticator
import me.henneke.wearauthn.bthid.defaultAdapter
import me.henneke.wearauthn.bthid.identifier
import me.henneke.wearauthn.ui.WearBodyItem
import me.henneke.wearauthn.ui.WearButton
import me.henneke.wearauthn.ui.WearListScreen
import me.henneke.wearauthn.ui.hasBluetoothPermissions
import me.henneke.wearauthn.ui.theme.WearAuthnTheme

class ComplicationConfigActivity : ComponentActivity() {

    private var devices by mutableStateOf<List<BluetoothDevice>>(emptyList())

    private val complicationId by lazy {
        intent.getIntExtra(ComplicationProviderService.EXTRA_CONFIG_COMPLICATION_ID, -1)
            .also { check(it >= 0) { "No complication ID provided." } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearAuthnTheme {
                WearListScreen(title = stringResource(R.string.preference_screen_title_shortcut_picker)) {
                    if (devices.isEmpty()) {
                        item { WearBodyItem(text = stringResource(R.string.status_no_paired_devices)) }
                    }
                    devices.forEach { device ->
                        item(key = device.address) {
                            WearButton(
                                label = device.identifier,
                                iconRes = deviceIcon(device),
                                onClick = { selectDevice(device) },
                            )
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        devices = if (hasBluetoothPermissions) {
            defaultAdapter.bondedDevices.filter { it.canUseAuthenticator }.sortedBy { it.identifier }
        } else {
            emptyList()
        }
    }

    private fun selectDevice(device: BluetoothDevice) {
        ShortcutComplicationProviderService.setDeviceShortcut(this, complicationId, device.address)
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object {
        @SuppressLint("MissingPermission")
        private fun deviceIcon(device: BluetoothDevice): Int = when (device.bluetoothClass?.majorDeviceClass) {
            BluetoothClass.Device.Major.AUDIO_VIDEO -> R.drawable.ic_headset
            BluetoothClass.Device.Major.COMPUTER -> R.drawable.ic_computer
            BluetoothClass.Device.Major.PHONE -> R.drawable.ic_phone
            BluetoothClass.Device.Major.WEARABLE -> R.drawable.ic_watch
            else -> R.drawable.ic_bluetooth
        }
    }
}
