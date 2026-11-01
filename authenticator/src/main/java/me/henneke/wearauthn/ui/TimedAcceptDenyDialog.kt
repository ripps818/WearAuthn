package me.henneke.wearauthn.ui

import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP
import android.os.PowerManager.FULL_WAKE_LOCK
import android.os.SystemClock
import androidx.activity.ComponentDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextLayoutResult
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Text
import me.henneke.wearauthn.R
import me.henneke.wearauthn.ui.theme.WearAuthnTheme

private const val DEFAULT_TIMEOUT = 5_000L
private const val COUNTDOWN_TICK_MS = 100L

class TimedAcceptDenyDialog(context: Context) : ComponentDialog(context) {

    var messageLineBreaks: List<Int>? = null
        private set

    private var titleText by mutableStateOf<CharSequence?>(null)
    private var messageText by mutableStateOf<CharSequence?>(null)
    private var timeoutMs = DEFAULT_TIMEOUT
    private var remainingMs by mutableLongStateOf(DEFAULT_TIMEOUT)
    private var hasPositiveButton by mutableStateOf(false)
    private var hasNegativeButton by mutableStateOf(false)

    private var wakeOnShow = false
    private var vibrateOnShow = false
    private var positiveButtonListener: DialogInterface.OnClickListener? = null
    private var negativeButtonListener: DialogInterface.OnClickListener? = null
    private var timeoutListener: DialogInterface.OnCancelListener? = null
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    // The countdown deliberately lives on the dialog rather than inside the composition: the
    // buttons are emitted into a lazy list, so an item that is scrolled out of view (or that never
    // came into view because the request message is long) would otherwise never run the timer and
    // the request would stay approvable forever. It is driven off elapsedRealtime so that
    // recomposition, scrolling and dropped frames cannot extend the deadline.
    private val handler = Handler(Looper.getMainLooper())
    private var deadlineElapsedMs = 0L
    private var resolved = false

    private val countdownTick = object : Runnable {
        override fun run() {
            val remaining = deadlineElapsedMs - SystemClock.elapsedRealtime()
            remainingMs = remaining.coerceAtLeast(0)
            if (remaining <= 0) {
                resolve {
                    val listener = timeoutListener
                    if (listener != null)
                        listener.onCancel(this@TimedAcceptDenyDialog)
                    else
                        negativeButtonListener?.onClick(
                            this@TimedAcceptDenyDialog,
                            DialogInterface.BUTTON_NEGATIVE,
                        )
                }
            } else {
                handler.postDelayed(this, COUNTDOWN_TICK_MS.coerceAtMost(remaining))
            }
        }
    }

    /**
     * Runs [action] at most once over the lifetime of the dialog, so that a tap landing in the same
     * main-thread batch as the timeout cannot resume the caller's continuation twice.
     */
    private fun resolve(action: () -> Unit) {
        if (resolved) return
        resolved = true
        handler.removeCallbacks(countdownTick)
        action()
        dismiss()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setContentView(
            ComposeView(context).apply {
                setContent {
                    WearAuthnTheme {
                        WearListScreen(
                            title = titleText?.toString()?.takeIf { it.isNotBlank() }
                                ?: stringResource(R.string.app_name),
                        ) {
                            messageText?.let { message ->
                                item {
                                    Text(
                                        text = message.toString(),
                                        onTextLayout = ::captureLineBreaks,
                                    )
                                }
                            }
                            if (hasPositiveButton) {
                                item {
                                    WearButton(
                                        label = stringResource(R.string.generic_accept),
                                        onClick = {
                                            resolve {
                                                positiveButtonListener?.onClick(
                                                    this@TimedAcceptDenyDialog,
                                                    DialogInterface.BUTTON_POSITIVE,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                            if (hasNegativeButton) {
                                item {
                                    WearButton(
                                        label = stringResource(R.string.generic_deny),
                                        secondaryLabel = stringResource(
                                            R.string.status_timeout_seconds,
                                            ((remainingMs + 999) / 1_000).coerceAtLeast(0),
                                        ),
                                        colors = ButtonDefaults.filledTonalButtonColors(),
                                        onClick = {
                                            resolve {
                                                negativeButtonListener?.onClick(
                                                    this@TimedAcceptDenyDialog,
                                                    DialogInterface.BUTTON_NEGATIVE,
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        if (vibrateOnShow) wink(context)
        if (wakeOnShow) {
            @Suppress("DEPRECATION")
            wakeLock = context.powerManager?.newWakeLock(
                FULL_WAKE_LOCK or ACQUIRE_CAUSES_WAKEUP,
                "WearAuthn:WakeForDialog",
            )?.apply { acquire(timeoutMs) }
        }
        startCountdownIfNecessary()
    }

    override fun onStop() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        // The countdown intentionally keeps running: the pending request has to expire on wall
        // clock even while the dialog is stopped, e.g. because the screen turned off.
        super.onStop()
    }

    override fun dismiss() {
        handler.removeCallbacks(countdownTick)
        super.dismiss()
    }

    private fun startCountdownIfNecessary() {
        // Only arm the timer if there is somebody to report the timeout to; otherwise a timeout
        // would dismiss the dialog without ever answering the caller.
        if (resolved || deadlineElapsedMs != 0L) return
        if (timeoutListener == null && negativeButtonListener == null) return
        deadlineElapsedMs = SystemClock.elapsedRealtime() + timeoutMs
        remainingMs = timeoutMs
        handler.post(countdownTick)
    }

    fun setMessage(message: CharSequence?) {
        messageText = message
    }

    fun setNegativeButton(listener: DialogInterface.OnClickListener) {
        negativeButtonListener = listener
        hasNegativeButton = true
    }

    fun setPositiveButton(listener: DialogInterface.OnClickListener) {
        positiveButtonListener = listener
        hasPositiveButton = true
    }

    fun setTimeoutListener(listener: DialogInterface.OnCancelListener) {
        timeoutListener = listener
    }

    fun setTimeout(timeout: Long) {
        timeoutMs = timeout
        remainingMs = timeout
    }

    override fun setTitle(title: CharSequence?) {
        titleText = title
    }

    override fun setTitle(resId: Int) {
        titleText = if (resId == 0) null else context.getText(resId)
    }

    fun setVibrateOnShow(vibrateOnShow: Boolean) {
        this.vibrateOnShow = vibrateOnShow
    }

    fun setWakeOnShow(wakeOnShow: Boolean) {
        this.wakeOnShow = wakeOnShow
    }

    private fun captureLineBreaks(layout: TextLayoutResult) {
        messageLineBreaks = (0 until layout.lineCount - 1).map(layout::getLineEnd)
    }
}
