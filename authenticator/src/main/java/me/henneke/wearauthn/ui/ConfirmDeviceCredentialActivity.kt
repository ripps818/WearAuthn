package me.henneke.wearauthn.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.ButtonDefaults
import me.henneke.wearauthn.R
import me.henneke.wearauthn.ui.theme.WearAuthnTheme

const val EXTRA_CONFIRM_DEVICE_CREDENTIAL_RECEIVER =
    "me.henneke.wearauthn.common.EXTRA_CONFIRM_DEVICE_CREDENTIAL_RECEIVER"

private const val STATE_CREDENTIAL_PROMPT_LAUNCHED = "credential_prompt_launched"

/**
 * Asks the user to confirm their screen lock and reports the outcome to the requester that launched
 * this activity.
 *
 * Two invariants matter here and are relied upon by [me.henneke.wearauthn.fido.context.AuthenticatorContext]:
 *
 *  * Exactly one requester is served per instance. A confirmation must never authorize a request
 *    other than the one it was raised for, so this activity uses the default (`standard`) launch
 *    mode and never adopts a second receiver.
 *  * The requester is always answered exactly once, including when this activity is destroyed
 *    without an explicit decision — the task shares its affinity with an activity marked
 *    `clearTaskOnLaunch`, so relaunching the app from the launcher can tear the prompt down at any
 *    point. An unanswered requester would otherwise block on its continuation forever.
 */
class ConfirmDeviceCredentialActivity : ComponentActivity() {

    private var receiver: ResultReceiver? = null
    private var resultSent = false
    private var credentialPromptLaunched by mutableStateOf(false)

    private val credentialResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            returnResult(result.resultCode)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = intent.resultReceiverExtra(EXTRA_CONFIRM_DEVICE_CREDENTIAL_RECEIVER)
        credentialPromptLaunched =
            savedInstanceState?.getBoolean(STATE_CREDENTIAL_PROMPT_LAUNCHED) == true
        setContent {
            WearAuthnTheme {
                BackHandler { returnResult(Activity.RESULT_CANCELED) }
                WearListScreen(title = stringResource(R.string.prompt_device_credential_title)) {
                    item {
                        WearBodyItem(text = stringResource(R.string.prompt_device_credential_message))
                    }
                    item {
                        WearButton(
                            label = stringResource(R.string.generic_continue),
                            onClick = ::launchCredentialPrompt,
                            enabled = !credentialPromptLaunched,
                        )
                    }
                    item {
                        WearButton(
                            label = stringResource(R.string.generic_cancel),
                            onClick = { returnResult(Activity.RESULT_CANCELED) },
                            colors = ButtonDefaults.filledTonalButtonColors(),
                        )
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_CREDENTIAL_PROMPT_LAUNCHED, credentialPromptLaunched)
    }

    override fun onDestroy() {
        // isFinishing is true exactly when this instance is going away for good, which includes the
        // task being cleared out from under a pending prompt. A system-initiated recreation (config
        // change, "don't keep activities") re-reads the receiver from the intent and can still
        // answer, so it must not be reported as a cancellation here.
        if (isFinishing)
            sendResult(Activity.RESULT_CANCELED)
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun launchCredentialPrompt() {
        if (credentialPromptLaunched) return
        val keyguardManager = getSystemService(KeyguardManager::class.java)
        val launchIntent = keyguardManager?.createConfirmDeviceCredentialIntent(
            getString(R.string.prompt_device_credential_system_title),
            getString(R.string.prompt_device_credential_system_message),
        )
        if (launchIntent == null) {
            returnResult(Activity.RESULT_CANCELED)
            return
        }
        credentialPromptLaunched = true
        credentialResult.launch(launchIntent)
    }

    private fun returnResult(resultCode: Int) {
        sendResult(resultCode)
        setResult(resultCode)
        finish()
    }

    private fun sendResult(resultCode: Int) {
        if (resultSent) return
        resultSent = true
        receiver?.send(resultCode, Bundle.EMPTY)
    }
}

@Suppress("DEPRECATION")
private fun Intent.resultReceiverExtra(key: String): ResultReceiver? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, ResultReceiver::class.java)
    } else {
        getParcelableExtra(key)
    }
