package me.henneke.wearauthn.ui

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentDialog
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.stringResource
import me.henneke.wearauthn.R
import me.henneke.wearauthn.fido.context.WebAuthnCredential
import me.henneke.wearauthn.ui.theme.WearAuthnTheme

private const val CREDENTIAL_CHOOSER_TIMEOUT_MS = 30_000L

@ExperimentalUnsignedTypes
class CredentialChooserDialog(
    private val credentials: Array<WebAuthnCredential>,
    context: Context,
    private val callback: (WebAuthnCredential?) -> Unit,
) : ComponentDialog(context) {

    private var chosenCredential: WebAuthnCredential? = null

    private val handler = Handler(Looper.getMainLooper())
    private var deadlineElapsedMs = 0L

    // Handler delays run on uptimeMillis, which stops advancing in deep sleep, so a single
    // postDelayed would overshoot the deadline by however long the watch dozed. Re-check against
    // elapsedRealtime on each wake up and re-post until the deadline has genuinely passed.
    private val timeout = object : Runnable {
        override fun run() {
            val remaining = deadlineElapsedMs - SystemClock.elapsedRealtime()
            if (remaining <= 0) cancel() else handler.postDelayed(this, remaining)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            ComposeView(context).apply {
                setContent {
                    WearAuthnTheme {
                        WearListScreen(title = stringResource(R.string.credential_chooser_title)) {
                            credentials.forEachIndexed { index, credential ->
                                val info = credential.getTwoLineInfo(index + 1)
                                item(key = credential.hashCode()) {
                                    WearButton(
                                        label = info.first.toString(),
                                        secondaryLabel = info.second?.toString(),
                                        onClick = {
                                            if (chosenCredential == null) chosenCredential = credential
                                            dismiss()
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
        setOnDismissListener { callback(chosenCredential) }
    }

    override fun onStart() {
        super.onStart()
        // Armed once, against elapsedRealtime, and left running while the dialog is stopped. The
        // previous lifecycle scoped delay was cancelled in onStop and restarted from the full 30s
        // in onStart, so turning the screen off and on repeatedly kept the pending request alive
        // indefinitely.
        if (deadlineElapsedMs == 0L) {
            deadlineElapsedMs = SystemClock.elapsedRealtime() + CREDENTIAL_CHOOSER_TIMEOUT_MS
            handler.postDelayed(timeout, CREDENTIAL_CHOOSER_TIMEOUT_MS)
        }
    }

    override fun dismiss() {
        handler.removeCallbacks(timeout)
        super.dismiss()
    }

}
