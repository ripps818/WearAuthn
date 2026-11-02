// Based on HidDataSender.java of WearMouse, which comes with the following copyright notice
// and is licensed under the Apache License, Version 2.0:
// Copyright 2018 Google LLC All Rights Reserved.

package me.henneke.wearauthn.bthid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.ArraySet
import androidx.annotation.GuardedBy
import me.henneke.wearauthn.Logging
import me.henneke.wearauthn.bthid.api28.HidDeviceApp28
import me.henneke.wearauthn.bthid.api28.HidDeviceProfile28
import me.henneke.wearauthn.i
import me.henneke.wearauthn.w

/** Central point for enabling the HID SDP record and sending all data.  */
object HidDataSender : Logging {

    override val TAG = "HidDataSender"

    // Some stacks (seen on a OnePlus Watch 3) accept a registration request and then never answer.
    private const val REGISTRATION_TIMEOUT_MS = 5_000L
    private const val MAX_REGISTRATION_ATTEMPTS = 2

    // Unregistering while a host is still connected leaves the same stack unable to register again
    // until Bluetooth is toggled, so disconnect first and wait (bounded) for it to complete.
    private const val TEARDOWN_TIMEOUT_MS = 3_000L

    enum class AppRegistrationState {
        WAITING_FOR_SERVICE,
        REGISTERING,
        READY,
        FAILED,
    }

    private val hidDeviceApp: HidDeviceApp
    private val hidDeviceProfile: HidDeviceProfile

    init {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        hidDeviceApp = HidDeviceApp28()
        hidDeviceProfile = HidDeviceProfile28(bluetoothAdapter)
    }

    private val lock = Any()

    @GuardedBy("lock")
    private var ioListener: HidIntrDataListener? = null

    @GuardedBy("lock")
    private val profileListeners = ArraySet<ProfileListener>()

    @GuardedBy("lock")
    private var connectedDevice: BluetoothDevice? = null

    @GuardedBy("lock")
    private var waitingForDevice: BluetoothDevice? = null

    @GuardedBy("lock")
    private var reconnectAfterRegistrationDevice: BluetoothDevice? = null

    @GuardedBy("lock")
    private var profileProxy: BluetoothProfile? = null

    @GuardedBy("lock")
    private var registrationRequested = false

    @GuardedBy("lock")
    private var registrationAttempts = 0

    @GuardedBy("lock")
    private var teardownPending = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private val teardownTimeout = Runnable {
        synchronized(lock) {
            if (!teardownPending) return@Runnable
            w { "Hosts did not disconnect within ${TEARDOWN_TIMEOUT_MS}ms; unregistering anyway" }
            finishTeardownLocked()
        }
    }

    private val registrationTimeout = Runnable {
        synchronized(lock) {
            if (appRegistrationState != AppRegistrationState.REGISTERING) return@Runnable
            w { "No registration callback after ${REGISTRATION_TIMEOUT_MS}ms (attempt $registrationAttempts)" }
            hidDeviceApp.unregisterApp()
            registrationRequested = false
            if (registrationAttempts < MAX_REGISTRATION_ATTEMPTS) {
                ensureAppRegisteredLocked()
            } else {
                w { "Giving up on registration until the user retries" }
                appRegistrationState = AppRegistrationState.FAILED
                for (listener in profileListeners) {
                    listener.onAppStatusChanged(false)
                }
            }
        }
    }

    @Volatile
    var isAppRegistered: Boolean = false
        private set

    @Volatile
    var appRegistrationState: AppRegistrationState = AppRegistrationState.WAITING_FOR_SERVICE
        private set

    private val managingProfileListener = object : ProfileListener {
        override fun onServiceStateChanged(proxy: BluetoothProfile?) {
            synchronized(lock) {
                i { "HID Device profile service ${if (proxy != null) "connected" else "disconnected"}" }
                profileProxy = proxy
                registrationRequested = false
                if (proxy == null) {
                    val wasRegistered = isAppRegistered
                    isAppRegistered = false
                    appRegistrationState = AppRegistrationState.WAITING_FOR_SERVICE
                    if (wasRegistered) {
                        for (listener in profileListeners) {
                            listener.onAppStatusChanged(false)
                        }
                    }
                } else {
                    ensureAppRegisteredLocked()
                }
                updateDeviceList()
                for (listener in profileListeners) {
                    listener.onServiceStateChanged(proxy)
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            synchronized(lock) {
                i { "Host connection state: ${connectionStateName(state)}" }
                if (teardownPending) {
                    if (activeHosts().isEmpty()) {
                        i { "Hosts disconnected; unregistering app" }
                        finishTeardownLocked()
                    }
                    return
                }
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        // A new connection was established. If we weren't expecting that, it
                        // must be an incoming one. In that case, we shouldn't try to disconnect
                        // from it.
                        waitingForDevice = device
                        reconnectAfterRegistrationDevice = null
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        // If we are being disconnected from a device we were waiting to connect to,
                        // we ran into a connection timeout and should stop waiting.
                        if (device == waitingForDevice) {
                            waitingForDevice = null
                        }
                    }
                }
                updateDeviceList()
                for (listener in profileListeners) {
                    listener.onConnectionStateChanged(device, state)
                }
            }
        }

        override fun onAppStatusChanged(registered: Boolean) {
            synchronized(lock) {
                i { "App registration status: registered=$registered" }
                mainHandler.removeCallbacks(registrationTimeout)
                if (registered) {
                    registrationAttempts = 0
                }
                if (!registered && connectedDevice != null) {
                    reconnectAfterRegistrationDevice = connectedDevice
                }
                isAppRegistered = registered
                registrationRequested = false
                appRegistrationState = if (registered) {
                    AppRegistrationState.READY
                } else {
                    AppRegistrationState.FAILED
                }

                for (listener in profileListeners) {
                    listener.onAppStatusChanged(registered)
                }
                if (registered) {
                    reconnectAfterRegistrationDevice?.let { waitingForDevice = it }
                    updateDeviceList()
                }
            }
        }
    }

    /** Compound interface that listens to both device and service state changes.  */
    interface ProfileListener : HidDeviceApp.DeviceStateListener,
        HidDeviceProfile.ServiceStateListener

    /**
     * Ensure that the HID Device SDP record is registered and start listening for the profile proxy
     * and HID Host connection state changes.
     *
     * @param context Context that is required to get the HID profile proxy.
     * @param profileListener Callback that will receive the profile events.
     * @param ioListener Callback that will receive requests from connected devices.
     * @return Interface for managing the paired HID Host devices.
     */
    fun register(
        context: Context,
        profileListener: ProfileListener,
        ioListener: HidIntrDataListener?
    ): HidDeviceProfile {
        synchronized(lock) {
            if (ioListener != null) {
                this.ioListener = ioListener
                hidDeviceApp.registerIoListener(ioListener)
            }

            if (teardownPending) {
                // The app came back before teardown finished: keep the existing registration.
                i { "New listener during teardown; keeping registration" }
                teardownPending = false
                mainHandler.removeCallbacks(teardownTimeout)
                profileListeners.add(profileListener)
                ensureAppRegisteredLocked()
                return hidDeviceProfile
            }

            if (!profileListeners.add(profileListener)) {
                // This user is already registered
                ensureAppRegisteredLocked()
                return hidDeviceProfile
            }
            if (profileListeners.size > 1) {
                // There are already some users
                ensureAppRegisteredLocked()
                return hidDeviceProfile
            }

            hidDeviceProfile.registerServiceListener(
                context.applicationContext,
                managingProfileListener
            )
            hidDeviceApp.registerDeviceListener(managingProfileListener)
        }
        return hidDeviceProfile
    }

    /** Retry registration after returning from pairing, permissions or screen-lock UI. */
    fun ensureAppRegistered(): Boolean = synchronized(lock) {
        if (appRegistrationState == AppRegistrationState.FAILED) {
            registrationAttempts = 0
        }
        ensureAppRegisteredLocked()
    }

    @GuardedBy("lock")
    private fun ensureAppRegisteredLocked(): Boolean {
        if (isAppRegistered || registrationRequested) return true
        val proxy = profileProxy ?: run {
            appRegistrationState = AppRegistrationState.WAITING_FOR_SERVICE
            return false
        }
        appRegistrationState = AppRegistrationState.REGISTERING
        registrationAttempts++
        registrationRequested = hidDeviceApp.registerApp(proxy)
        if (registrationRequested) {
            i { "Requested app registration (attempt $registrationAttempts)" }
            mainHandler.removeCallbacks(registrationTimeout)
            mainHandler.postDelayed(registrationTimeout, REGISTRATION_TIMEOUT_MS)
        } else {
            w { "Bluetooth stack rejected the app registration request" }
            appRegistrationState = AppRegistrationState.FAILED
        }
        return registrationRequested
    }

    /**
     * Stop listening for the profile events. When the last profileListener is unregistered, the SD record
     * for HID Device will also be unregistered.
     *
     * @param profileListener Callback for profile events.
     * @param ioListener Callback for device requests.
     */
    fun unregister(profileListener: ProfileListener, ioListener: HidIntrDataListener?) {
        synchronized(lock) {
            if (this.ioListener == ioListener) {
                this.ioListener = null
                hidDeviceApp.unregisterIoListener()
            }

            if (!profileListeners.remove(profileListener)) {
                // This user was removed before
                return
            }
            if (!profileListeners.isEmpty()) {
                // Some users are still left
                return
            }

            mainHandler.removeCallbacks(registrationTimeout)
            registrationAttempts = 0
            waitingForDevice = null
            reconnectAfterRegistrationDevice = null

            val hosts = activeHosts()
            if (hosts.isEmpty()) {
                i { "Last listener gone; unregistering app" }
                finishTeardownLocked()
                return
            }
            i { "Last listener gone; disconnecting ${hosts.size} host(s) before unregistering" }
            teardownPending = true
            for (device in hosts) {
                hidDeviceProfile.disconnect(device)
            }
            mainHandler.postDelayed(teardownTimeout, TEARDOWN_TIMEOUT_MS)
        }
    }

    @GuardedBy("lock")
    private fun finishTeardownLocked() {
        teardownPending = false
        mainHandler.removeCallbacks(teardownTimeout)

        hidDeviceApp.unregisterDeviceListener()
        hidDeviceApp.setDevice(null)
        hidDeviceApp.unregisterApp()

        hidDeviceProfile.unregisterServiceListener()

        connectedDevice = null
        waitingForDevice = null
        reconnectAfterRegistrationDevice = null
        profileProxy = null
        registrationRequested = false
        isAppRegistered = false
        appRegistrationState = AppRegistrationState.WAITING_FOR_SERVICE
    }

    private fun activeHosts(): List<BluetoothDevice> =
        hidDeviceProfile.getDevicesMatchingConnectionStates(
            intArrayOf(BluetoothProfile.STATE_CONNECTED, BluetoothProfile.STATE_CONNECTING)
        )

    private fun connectionStateName(state: Int) = when (state) {
        BluetoothProfile.STATE_CONNECTED -> "connected"
        BluetoothProfile.STATE_CONNECTING -> "connecting"
        BluetoothProfile.STATE_DISCONNECTING -> "disconnecting"
        BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
        else -> "unknown ($state)"
    }

    /**
     * Initiate connection sequence for the specified HID Host. If another device is already
     * connected, it will be disconnected first. If the parameter is `null`, then the service
     * will only disconnect from the current device.
     *
     * @param device New HID Host to connect to or `null` to disconnect.
     */
    fun requestConnect(device: BluetoothDevice?) {
        synchronized(lock) {
            i { if (device != null) "Connect requested" else "Disconnect requested" }
            waitingForDevice = device
            reconnectAfterRegistrationDevice = null
            if (!isAppRegistered) {
                // Request will be fulfilled as soon as the app becomes registered.
                return
            }

            connectedDevice = null
            updateDeviceList()

            if (device != null && device == connectedDevice) {
                for (listener in profileListeners) {
                    listener.onConnectionStateChanged(device, BluetoothProfile.STATE_CONNECTED)
                }
            }
        }
    }

    private fun updateDeviceList() {
        var connected: BluetoothDevice? = null

        // If we are connected to some device, but want to connect to another (or disconnect
        // completely), then we should disconnect all other devices first.
        for (device in hidDeviceProfile.connectedDevices) {
            if (device == waitingForDevice || device == connectedDevice) {
                connected = device
            } else {
                hidDeviceProfile.disconnect(device)
            }
        }

        // If there is nothing going on, and we want to connect, then do it.
        waitingForDevice?.let {
            if (hidDeviceProfile
                    .getDevicesMatchingConnectionStates(
                        intArrayOf(
                            BluetoothProfile.STATE_CONNECTED,
                            BluetoothProfile.STATE_CONNECTING,
                            BluetoothProfile.STATE_DISCONNECTING
                        )
                    )
                    .isEmpty()
            ) {
                hidDeviceProfile.connect(it)
            }
        }

        if (connectedDevice == null && connected != null) {
            connectedDevice = connected
            waitingForDevice = null
        } else if (connectedDevice != null && connected == null) {
            connectedDevice = null
        }
        hidDeviceApp.setDevice(connectedDevice)
    }


}
