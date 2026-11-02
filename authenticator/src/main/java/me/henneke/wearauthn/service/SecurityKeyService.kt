package me.henneke.wearauthn.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import me.henneke.wearauthn.Logging
import me.henneke.wearauthn.R
import me.henneke.wearauthn.bthid.HidDataSender
import me.henneke.wearauthn.i
import me.henneke.wearauthn.ui.hasBluetoothPermissions
import me.henneke.wearauthn.ui.main.AuthenticatorActivity
import me.henneke.wearauthn.w

/**
 * Keeps the Bluetooth HID registration alive while the screen is off.
 *
 * Android's HidDeviceService unregisters apps that leave the foreground, and on some stacks (seen
 * on a OnePlus Watch 3) that leaves Bluetooth unable to register the app again until it is
 * toggled. A foreground service counts as foreground, so holding a HidDataSender listener here
 * keeps the registration for as long as the service runs.
 */
class SecurityKeyService : Service(), Logging {

    override val TAG = "SecurityKeyService"

    private var registered = false

    private val profileListener = object : HidDataSender.ProfileListener {
        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) = Unit
        override fun onAppStatusChanged(registered: Boolean) = Unit
        override fun onServiceStateChanged(proxy: BluetoothProfile?) = Unit
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            i { "Stopped from notification" }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasBluetoothPermissions) {
            w { "Missing Bluetooth permissions; not starting" }
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (!registered) {
            i { "Holding HID registration" }
            HidDataSender.register(this, profileListener, null)
            registered = true
        }
        HidDataSender.ensureAppRegistered()
        return START_STICKY
    }

    override fun onDestroy() {
        if (registered) {
            i { "Releasing HID registration" }
            HidDataSender.unregister(profileListener, null)
            registered = false
        }
        super.onDestroy()
    }

    private fun buildNotification() = run {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_security_key),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, AuthenticatorActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, SecurityKeyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_outline)
            .setContentTitle(getString(R.string.notification_security_key_ready))
            .setContentText(getString(R.string.notification_security_key_ready_text))
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.notification_action_turn_off), stop)
        OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_launcher_outline)
            .setTouchIntent(openApp)
            .setStatus(Status.forPart(Status.TextPart(getString(R.string.notification_security_key_ready))))
            .build()
            .apply(applicationContext)
        builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "security_key"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "me.henneke.wearauthn.action.STOP_SECURITY_KEY"

        /** Starts the service if the Bluetooth permissions it needs have been granted. */
        fun start(context: Context) {
            if (!context.hasBluetoothPermissions) return
            ContextCompat.startForegroundService(
                context,
                Intent(context, SecurityKeyService::class.java),
            )
        }
    }
}
