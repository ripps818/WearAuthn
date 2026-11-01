package me.henneke.wearauthn.ui

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ResultReceiver
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.lifecycleScope
import androidx.wear.activity.ConfirmationActivity
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.henneke.wearauthn.R
import me.henneke.wearauthn.fido.context.AuthenticatorContext
import me.henneke.wearauthn.ui.theme.WearAuthnTheme

const val EXTRA_MANAGE_SPACE_RECEIVER = "me.henneke.wearauthn.common.EXTRA_MANAGE_SPACE_RECEIVER"

@ExperimentalUnsignedTypes
class ManageSpaceActivity : ComponentActivity() {

    private var step by mutableStateOf(ResetStep.FirstWarning)
    private var receiver: ResultReceiver? = null
    private var resultSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = intent.manageSpaceReceiver()
        setContent {
            WearAuthnTheme {
                BackHandler { returnResult(Activity.RESULT_CANCELED) }
                when (step) {
                    ResetStep.FirstWarning -> WearListScreen(title = stringResource(R.string.app_family_name)) {
                        item {
                            WearBodyItem(
                                text = Html.fromHtml(
                                    getString(R.string.prompt_delete_all_data_first_step_message),
                                    Html.FROM_HTML_MODE_LEGACY,
                                ).toString(),
                            )
                        }
                        item {
                            WearButton(
                                label = stringResource(R.string.generic_continue),
                                onClick = { step = ResetStep.FinalWarning },
                            )
                        }
                        item {
                            WearButton(
                                label = stringResource(R.string.generic_cancel),
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                onClick = { returnResult(Activity.RESULT_CANCELED) },
                            )
                        }
                    }
                    ResetStep.FinalWarning -> WearListScreen(title = stringResource(R.string.app_family_name)) {
                        item {
                            WearBodyItem(
                                text = Html.fromHtml(
                                    getString(R.string.prompt_delete_all_data_second_step_message),
                                    Html.FROM_HTML_MODE_LEGACY,
                                ).toString(),
                            )
                        }
                        item {
                            WearButton(
                                label = stringResource(R.string.button_delete),
                                onClick = ::deleteAllData,
                            )
                        }
                        item {
                            WearButton(
                                label = stringResource(R.string.generic_cancel),
                                colors = ButtonDefaults.filledTonalButtonColors(),
                                onClick = { returnResult(Activity.RESULT_CANCELED) },
                            )
                        }
                    }
                    ResetStep.Deleting -> Box(
                        modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        wink(this)
    }

    private fun deleteAllData() {
        if (step == ResetStep.Deleting) return
        step = ResetStep.Deleting
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                AuthenticatorContext.deleteAllData(this@ManageSpaceActivity)
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent(this@ManageSpaceActivity, ConfirmationActivity::class.java).apply {
                            putExtra(
                                ConfirmationActivity.EXTRA_ANIMATION_TYPE,
                                ConfirmationActivity.SUCCESS_ANIMATION,
                            )
                            putExtra(
                                ConfirmationActivity.EXTRA_MESSAGE,
                                getString(R.string.message_deleted_all_data),
                            )
                        },
                    )
                    returnResult(Activity.RESULT_OK)
                }
            } catch (_: CancellationException) {
                returnResult(Activity.RESULT_CANCELED)
            }
        }
    }

    override fun onDestroy() {
        // Answer a requester that is blocked on our result even if we are torn down without an
        // explicit decision; see ConfirmDeviceCredentialActivity for the same invariant.
        if (isFinishing)
            sendResult(Activity.RESULT_CANCELED)
        super.onDestroy()
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

private enum class ResetStep { FirstWarning, FinalWarning, Deleting }

@Suppress("DEPRECATION")
private fun Intent.manageSpaceReceiver(): ResultReceiver? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(EXTRA_MANAGE_SPACE_RECEIVER, ResultReceiver::class.java)
    } else {
        getParcelableExtra(EXTRA_MANAGE_SPACE_RECEIVER)
    }
