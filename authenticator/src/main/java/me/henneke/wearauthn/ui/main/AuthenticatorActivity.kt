package me.henneke.wearauthn.ui.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.nfc.NfcManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ResultReceiver
import android.provider.Settings
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import me.henneke.wearauthn.LogLevel
import me.henneke.wearauthn.Logging
import me.henneke.wearauthn.R
import me.henneke.wearauthn.bthid.HidDataSender
import me.henneke.wearauthn.bthid.HidDeviceProfile
import me.henneke.wearauthn.bthid.canUseAuthenticator
import me.henneke.wearauthn.bthid.canUseAuthenticatorViaBluetooth
import me.henneke.wearauthn.bthid.identifier
import me.henneke.wearauthn.fido.context.AuthenticatorContext
import me.henneke.wearauthn.fido.context.armUserVerificationFuse
import me.henneke.wearauthn.fido.context.getUserVerificationState
import me.henneke.wearauthn.i
import me.henneke.wearauthn.isDeveloperModeEnabled
import me.henneke.wearauthn.ui.ConfirmDeviceCredentialActivity
import me.henneke.wearauthn.ui.EXTRA_CONFIRM_DEVICE_CREDENTIAL_RECEIVER
import me.henneke.wearauthn.ui.WearAppScaffold
import me.henneke.wearauthn.ui.WearBodyItem
import me.henneke.wearauthn.ui.WearButton
import me.henneke.wearauthn.ui.WearScreen
import me.henneke.wearauthn.ui.WearSection
import me.henneke.wearauthn.ui.bluetoothAdapter
import me.henneke.wearauthn.ui.defaultSharedPreferences
import me.henneke.wearauthn.ui.hasBluetoothPermissions
import me.henneke.wearauthn.ui.theme.WearAuthnTheme

@ExperimentalUnsignedTypes
class AuthenticatorActivity : ComponentActivity(), CoroutineScope, Logging {

    override val TAG = "AuthenticatorActivity"
    override val coroutineContext = Dispatchers.IO + SupervisorJob()

    private var state by mutableStateOf(MainMenuState())
    private var screen by mutableStateOf(MainScreen.Menu)
    private var showPasswordlessConfirmation by mutableStateOf(false)
    private var receiverRegistered = false
    private var hidDeviceProfile: HidDeviceProfile? = null
    private var bluetoothNotice: String? = null
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

    private val activityResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshState() }

    private val permissionResult =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                bluetoothNotice = null
                registerHidDeviceProfile()
                refreshState()
            } else {
                bluetoothNotice = getString(R.string.status_bluetooth_permissions_required)
                refreshState()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(ambientObserver)
        setContent {
            WearAuthnTheme {
                // One app scaffold enclosing both screens, so TimeText stays put across the
                // menu <-> log level transition instead of being rebuilt with each swap.
                WearAppScaffold {
                    when (screen) {
                        MainScreen.Menu -> MainMenu(
                            state = state,
                            onBluetoothPermissions = ::requestBluetoothPermissions,
                            onBluetoothSettings = ::openBluetoothSettings,
                            onDiscoverable = ::requestDiscoverable,
                            onDevice = { HidDataSender.requestConnect(it) },
                            onBluetoothRetry = ::retryBluetooth,
                            onNfc = ::openNfcSettings,
                            onPasswordless = { showPasswordlessConfirmation = true },
                            onCredentials = ::openCredentials,
                            // Qualified: WearAppScaffold's content lambda has a BoxScope receiver,
                            // so a bare `this` here would be the BoxScope, not the activity.
                            onAbout = {
                                startActivity(
                                    Intent(this@AuthenticatorActivity, AboutActivity::class.java),
                                )
                            },
                            onLogLevel = { screen = MainScreen.LogLevel },
                        )
                        MainScreen.LogLevel -> LogLevelScreen(
                            selected = state.logLevel,
                            onSelect = ::setLogLevel,
                            onBack = { screen = MainScreen.Menu },
                        )
                    }
                    PasswordlessConfirmation(
                        visible = showPasswordlessConfirmation,
                        message = Html.fromHtml(
                            getString(R.string.prompt_single_factor_mode_message),
                            Html.FROM_HTML_MODE_LEGACY,
                        ).toString(),
                        onConfirm = {
                            showPasswordlessConfirmation = false
                            confirmDeviceCredential {
                                armUserVerificationFuse(this@AuthenticatorActivity)
                            }
                        },
                        onDismiss = { showPasswordlessConfirmation = false },
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerBluetoothReceiver()
        registerHidDeviceProfile()
        refreshState()
        launch {
            AuthenticatorContext.initAuthenticator(applicationContext)
            AuthenticatorContext.refreshCachedWebAuthnCredentialIfNecessary(applicationContext)
        }
    }

    override fun onResume() {
        super.onResume()
        registerHidDeviceProfile()
        refreshState()
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(bluetoothBroadcastReceiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    override fun onDestroy() {
        hidDeviceProfile?.let { HidDataSender.unregister(hidProfileListener, null) }
        hidDeviceProfile = null
        coroutineContext.cancelChildren()
        super.onDestroy()
    }

    private fun registerBluetoothReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_CLASS_CHANGED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
        }
        ContextCompat.registerReceiver(this, bluetoothBroadcastReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    private fun registerHidDeviceProfile() {
        if (hasBluetoothPermissions && bluetoothAdapter != null) {
            if (hidDeviceProfile == null) {
                hidDeviceProfile = HidDataSender.register(this, hidProfileListener, null)
            }
            HidDataSender.ensureAppRegistered()
        }
    }

    private fun retryBluetooth() {
        bluetoothNotice = null
        registerHidDeviceProfile()
        refreshState()
    }

    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasBluetoothPermissions) return
        permissionResult.launch(
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            ),
        )
    }

    @SuppressLint("MissingPermission")
    private fun openBluetoothSettings() {
        if (!hasBluetoothPermissions) {
            requestBluetoothPermissions()
            return
        }
        try {
            if (bluetoothAdapter?.isEnabled == true) {
                startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            } else {
                activityResult.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            }
        } catch (_: SecurityException) {
            bluetoothNotice = getString(R.string.status_bluetooth_permissions_required)
            refreshState()
        }
    }

    private fun requestDiscoverable() {
        activityResult.launch(
            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 60)
            },
        )
    }

    private fun openNfcSettings() {
        if (state.nfcEnabled == false) startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    }

    private fun openCredentials() {
        confirmDeviceCredential {
            startActivity(Intent(this, ResidentCredentialsList::class.java))
        }
    }

    private fun confirmDeviceCredential(onConfirmed: () -> Unit) {
        startActivity(
            Intent(this, ConfirmDeviceCredentialActivity::class.java).apply {
                putExtra(
                    EXTRA_CONFIRM_DEVICE_CREDENTIAL_RECEIVER,
                    object : ResultReceiver(Handler(mainLooper)) {
                        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                            if (resultCode == Activity.RESULT_OK) {
                                onConfirmed()
                                refreshState()
                            }
                        }
                    },
                )
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun refreshState() {
        val adapter = bluetoothAdapter
        val profile = hidDeviceProfile
        var bluetoothAccessAvailable = hasBluetoothPermissions
        var bluetoothEnabled = false
        var discoverable = false
        val devices = if (bluetoothAccessAvailable && adapter != null) {
            try {
                bluetoothEnabled = adapter.isEnabled
                discoverable = adapter.scanMode == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                adapter.bondedDevices
                    .filter { it.canUseAuthenticator }
                    .map { device ->
                        val connectionState = profile?.getConnectionState(device)
                            ?: BluetoothProfile.STATE_DISCONNECTED
                        HostDeviceState(
                            device = device,
                            name = device.identifier,
                            icon = deviceIcon(device),
                            enabled = device.canUseAuthenticatorViaBluetooth &&
                                HidDataSender.isAppRegistered &&
                                connectionState != BluetoothProfile.STATE_CONNECTING &&
                                connectionState != BluetoothProfile.STATE_DISCONNECTING,
                            status = when (connectionState) {
                                BluetoothProfile.STATE_CONNECTING -> getString(R.string.status_bluetooth_connecting)
                                BluetoothProfile.STATE_CONNECTED -> getString(R.string.status_bluetooth_connected)
                                else -> if (device.canUseAuthenticatorViaBluetooth) null
                                    else getString(R.string.status_bluetooth_use_via_nfc_instead)
                            },
                        )
                    }
                    .sortedWith(compareBy<HostDeviceState> { !it.enabled }.thenBy { it.name })
            } catch (_: SecurityException) {
                bluetoothAccessAvailable = false
                bluetoothEnabled = false
                discoverable = false
                bluetoothNotice = getString(R.string.status_bluetooth_permissions_required)
                emptyList()
            }
        } else {
            emptyList()
        }
        val nfcAdapter = getSystemService(NfcManager::class.java)?.defaultAdapter
        val verificationState = getUserVerificationState(this)
        val selectedLogLevel = defaultSharedPreferences.getString(
            getString(R.string.preference_log_level_key),
            LogLevel.Disabled.name,
        ) ?: LogLevel.Disabled.name
        state = MainMenuState(
            bluetoothEnabled = bluetoothEnabled,
            hasBluetoothPermission = bluetoothAccessAvailable,
            discoverable = discoverable,
            bluetoothNotice = bluetoothNotice,
            hidRegistrationState = HidDataSender.appRegistrationState,
            devices = devices,
            nfcEnabled = nfcAdapter?.isEnabled,
            userVerificationState = verificationState,
            screenLockEnabled = AuthenticatorContext.isScreenLockEnabled(this),
            developerMode = isDeveloperModeEnabled,
            logLevel = selectedLogLevel,
        )
    }

    private fun setLogLevel(level: LogLevel) {
        defaultSharedPreferences.edit { putString(getString(R.string.preference_log_level_key), level.name) }
        Logging.init(applicationContext, level.name)
        screen = MainScreen.Menu
        refreshState()
    }

    private val hidProfileListener = object : HidDataSender.ProfileListener {
        override fun onAppStatusChanged(registered: Boolean) {
            runOnUiThread {
                i { "onAppStatusChanged($registered)" }
                refreshState()
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            runOnUiThread {
                refreshState()
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    startActivity(Intent(this@AuthenticatorActivity, AuthenticatorAttachedActivity::class.java))
                }
            }
        }

        override fun onServiceStateChanged(proxy: BluetoothProfile?) {
            runOnUiThread(::refreshState)
        }
    }

    private val bluetoothBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshState()
        }
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

private enum class MainScreen { Menu, LogLevel }

private data class MainMenuState(
    val bluetoothEnabled: Boolean = false,
    val hasBluetoothPermission: Boolean = true,
    val discoverable: Boolean = false,
    val bluetoothNotice: String? = null,
    val hidRegistrationState: HidDataSender.AppRegistrationState =
        HidDataSender.AppRegistrationState.WAITING_FOR_SERVICE,
    val devices: List<HostDeviceState> = emptyList(),
    val nfcEnabled: Boolean? = null,
    val userVerificationState: Boolean? = null,
    val screenLockEnabled: Boolean = false,
    val developerMode: Boolean = false,
    val logLevel: String = LogLevel.Disabled.name,
)

private data class HostDeviceState(
    val device: BluetoothDevice,
    val name: String,
    val icon: Int,
    val enabled: Boolean,
    val status: String?,
)

@Composable
private fun MainMenu(
    state: MainMenuState,
    onBluetoothPermissions: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onDiscoverable: () -> Unit,
    onDevice: (BluetoothDevice) -> Unit,
    onBluetoothRetry: () -> Unit,
    onNfc: () -> Unit,
    onPasswordless: () -> Unit,
    onCredentials: () -> Unit,
    onAbout: () -> Unit,
    onLogLevel: () -> Unit,
) {
    WearScreen(title = stringResource(R.string.app_name)) {
        if (!state.hasBluetoothPermission) {
            item {
                WearButton(
                    label = stringResource(R.string.bluetooth_permissions_explanation),
                    iconRes = R.drawable.ic_bluetooth,
                    onClick = onBluetoothPermissions,
                )
            }
        }
        item { WearSection(stringResource(R.string.preference_category_bluetooth_title)) }
        state.bluetoothNotice?.let { notice ->
            item {
                WearBodyItem(
                    text = notice,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        if (state.hasBluetoothPermission && state.bluetoothEnabled) {
            when (state.hidRegistrationState) {
                HidDataSender.AppRegistrationState.READY -> Unit
                HidDataSender.AppRegistrationState.FAILED -> item {
                    WearButton(
                        label = stringResource(R.string.status_bluetooth_key_unavailable),
                        secondaryLabel = stringResource(R.string.status_bluetooth_tap_to_retry),
                        iconRes = R.drawable.ic_bluetooth,
                        onClick = onBluetoothRetry,
                    )
                }
                HidDataSender.AppRegistrationState.WAITING_FOR_SERVICE,
                HidDataSender.AppRegistrationState.REGISTERING -> item {
                    WearBodyItem(text = stringResource(R.string.status_bluetooth_key_starting))
                }
            }
        }
        state.devices.forEach { device ->
            item(key = device.device.address) {
                WearButton(
                    label = device.name,
                    secondaryLabel = device.status,
                    iconRes = device.icon,
                    enabled = device.enabled,
                    onClick = { onDevice(device.device) },
                )
            }
        }
        item {
            WearButton(
                label = stringResource(R.string.preference_bluetooth_settings_title),
                secondaryLabel = if (state.bluetoothEnabled) {
                    if (state.devices.isEmpty()) stringResource(R.string.status_bluetooth_tap_and_pair) else null
                } else stringResource(R.string.status_bluetooth_tap_to_enable),
                iconRes = R.drawable.ic_settings,
                onClick = onBluetoothSettings,
            )
        }
        item {
            // Not a switch: discoverability is a one-shot request that the system grants for one
            // minute and then drops on its own. A switch invites the user to turn it back off,
            // which is not something this control can do.
            WearButton(
                label = stringResource(R.string.preference_discoverable_title),
                secondaryLabel = stringResource(
                    if (state.discoverable) R.string.preference_discoverable_summary_on
                    else R.string.preference_discoverable_summary_off,
                ),
                iconRes = R.drawable.ic_bluetooth,
                enabled = state.bluetoothEnabled && state.hasBluetoothPermission && !state.discoverable,
                onClick = onDiscoverable,
            )
        }
        item { WearSection(stringResource(R.string.preference_category_nfc_title)) }
        item {
            WearButton(
                label = stringResource(R.string.preference_nfc_title),
                secondaryLabel = stringResource(
                    when (state.nfcEnabled) {
                        true -> R.string.status_nfc_explanation
                        false -> R.string.status_nfc_tap_and_enable
                        null -> R.string.status_nfc_not_available
                    },
                ),
                iconRes = if (state.nfcEnabled == false) R.drawable.ic_settings else R.drawable.ic_phone,
                enabled = state.nfcEnabled != null,
                onClick = onNfc,
            )
        }
        item { WearSection(stringResource(R.string.preference_category_advanced_title)) }
        item {
            val uvSummary = when (state.userVerificationState) {
                true -> R.string.preference_single_factor_mode_summary_active
                false -> if (state.screenLockEnabled) R.string.preference_single_factor_mode_summary_available
                    else R.string.preference_single_factor_mode_summary_enable_lock
                null -> R.string.preference_single_factor_mode_summary_disabled
            }
            // Also not a switch: arming the user verification fuse is irreversible short of a full
            // reset, so there is no "off" position to return to. The confirmation dialog this
            // opens is what carries the consent.
            WearButton(
                label = stringResource(R.string.preference_single_factor_mode_title),
                secondaryLabel = stringResource(uvSummary),
                iconRes = R.drawable.ic_person,
                enabled = state.userVerificationState == false && state.screenLockEnabled,
                onClick = onPasswordless,
            )
        }
        item {
            WearButton(
                label = stringResource(R.string.preference_credential_management_title),
                secondaryLabel = if (state.userVerificationState == false) {
                    stringResource(R.string.preference_manage_credentials_summary_disabled)
                } else null,
                iconRes = R.drawable.ic_key,
                enabled = state.userVerificationState != false,
                onClick = onCredentials,
            )
        }
        item {
            WearButton(
                label = stringResource(R.string.preference_about_title),
                iconRes = R.drawable.ic_info,
                onClick = onAbout,
            )
        }
        if (state.developerMode) {
            item {
                WearButton(
                    label = stringResource(R.string.preference_log_level_title),
                    secondaryLabel = state.logLevel,
                    iconRes = R.drawable.ic_bug_report,
                    onClick = onLogLevel,
                )
            }
        }
    }
}

@Composable
private fun LogLevelScreen(selected: String, onSelect: (LogLevel) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    WearScreen(title = stringResource(R.string.preference_log_level_dialog_title)) {
        item { WearBodyItem(text = stringResource(R.string.preference_log_level_dialog_message)) }
        LogLevel.entries.asReversed().forEach { level ->
            item {
                // A single choice list: RadioButton states the selection in the control itself,
                // rather than as a "Selected" caption the user has to read on every row.
                val spec = rememberTransformationSpec()
                RadioButton(
                    selected = selected == level.name,
                    onSelect = { onSelect(level) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    label = { Text(level.name) },
                )
            }
        }
    }
}

@Composable
private fun PasswordlessConfirmation(
    visible: Boolean,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = onConfirm) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) },
        title = { Text(stringResource(R.string.prompt_single_factor_mode_title)) },
        text = { Text(message) },
    )
}
