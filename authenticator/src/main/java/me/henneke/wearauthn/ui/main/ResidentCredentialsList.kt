package me.henneke.wearauthn.ui.main

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.Text
import com.google.android.gms.common.util.Hex
import me.henneke.wearauthn.Logging
import me.henneke.wearauthn.R
import me.henneke.wearauthn.fido.context.AuthenticatorContext
import me.henneke.wearauthn.fido.context.WebAuthnCredential
import me.henneke.wearauthn.ui.WearAppScaffold
import me.henneke.wearauthn.ui.WearButton
import me.henneke.wearauthn.ui.WearDetailItem
import me.henneke.wearauthn.ui.WearScreen
import me.henneke.wearauthn.ui.WearSection
import me.henneke.wearauthn.ui.theme.WearAuthnTheme
import me.henneke.wearauthn.sha256
import me.henneke.wearauthn.w

@ExperimentalUnsignedTypes
class ResidentCredentialsList : ComponentActivity(), Logging {

    override val TAG = "ResidentCredentialsList"

    private var groups by mutableStateOf<List<CredentialGroup>>(emptyList())
    private var selected by mutableStateOf<CredentialItem?>(null)
    private var showDeleteConfirmation by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshCredentials()
        setContent {
            WearAuthnTheme {
                WearAppScaffold {
                    val item = selected
                    if (item == null) {
                        CredentialList(groups = groups, onSelect = { selected = it })
                    } else {
                        BackHandler { selected = null }
                        WearScreen(title = item.rpId) {
                            item { WearSection(stringResource(R.string.credential_section_site)) }
                            item {
                                WearDetailItem(
                                    label = stringResource(R.string.credential_label_rp_id),
                                    value = item.rpId,
                                )
                            }
                            item.credential.rpName?.takeUnless { it.isBlank() }?.let { rpName ->
                                item {
                                    WearDetailItem(
                                        label = stringResource(R.string.credential_label_rp_name),
                                        value = rpName,
                                    )
                                }
                            }
                            item { WearSection(stringResource(R.string.credential_section_account)) }
                            item.credential.userDisplayName?.takeUnless { it.isBlank() }?.let { displayName ->
                                item {
                                    WearDetailItem(
                                        label = stringResource(R.string.credential_label_display_name),
                                        value = displayName,
                                    )
                                }
                            }
                            item.credential.userName?.takeUnless { it.isBlank() }?.let { userName ->
                                item {
                                    WearDetailItem(
                                        label = stringResource(R.string.credential_label_username),
                                        value = userName,
                                    )
                                }
                            }
                            item.credential.userId?.let { userId ->
                                item {
                                    WearDetailItem(
                                        label = stringResource(R.string.credential_label_user_id),
                                        value = userId.groupedHex(),
                                    )
                                }
                            }
                            item.credential.userIcon?.takeUnless { it.isBlank() }?.let { icon ->
                                item {
                                    WearDetailItem(
                                        label = stringResource(R.string.credential_label_user_icon),
                                        value = icon,
                                    )
                                }
                            }
                            item { WearSection(stringResource(R.string.credential_section_credential)) }
                            item.credential.creationDate?.let { creationDate ->
                                item {
                                    WearDetailItem(
                                        label = stringResource(R.string.credential_label_created),
                                        value = listOf(
                                            DateFormat.getMediumDateFormat(this@ResidentCredentialsList)
                                                .format(creationDate),
                                            DateFormat.getTimeFormat(this@ResidentCredentialsList)
                                                .format(creationDate),
                                        ).joinToString(" "),
                                    )
                                }
                            }
                            item {
                                WearDetailItem(
                                    label = stringResource(R.string.credential_label_hardware_backed),
                                    value = stringResource(
                                        if (item.credential.isKeyMaterialInTEE) R.string.generic_yes
                                        else R.string.generic_no,
                                    ),
                                )
                            }
                            item {
                                WearDetailItem(
                                    label = stringResource(R.string.credential_label_hmac_secret),
                                    value = stringResource(
                                        if (item.credential.hasHmacSecret) R.string.generic_yes
                                        else R.string.generic_no,
                                    ),
                                )
                            }
                            item {
                                WearDetailItem(
                                    label = stringResource(R.string.credential_label_fingerprint),
                                    value = item.credential.keyHandle.sha256().copyOfRange(0, 8).groupedHex(),
                                )
                            }
                            item {
                                WearDetailItem(
                                    label = stringResource(R.string.credential_label_rp_id_hash),
                                    value = item.credential.rpIdHash.groupedHex(),
                                )
                            }
                            item {
                                WearButton(
                                    label = stringResource(R.string.button_delete),
                                    onClick = { showDeleteConfirmation = true },
                                )
                            }
                        }
                        DeleteConfirmation(
                            visible = showDeleteConfirmation,
                            item = item,
                            onConfirm = {
                                AuthenticatorContext.deleteResidentCredential(
                                    this@ResidentCredentialsList,
                                    item.credential,
                                )
                                showDeleteConfirmation = false
                                selected = null
                                refreshCredentials()
                            },
                            onDismiss = { showDeleteConfirmation = false },
                        )
                    }
                }
            }
        }
    }

    private fun refreshCredentials() {
        groups = AuthenticatorContext.getAllResidentCredentials(this).mapNotNull { (rpId, credentials) ->
            val items = credentials.mapIndexed { index, credential ->
                // Decryption needs a recent screen lock confirmation and throws once the user info
                // key's validity window has lapsed. Failing to reveal a name must degrade to the
                // placeholder rendering, never take down the management screen.
                try {
                    credential.unlockUserInfoIfNecessary()
                } catch (error: Exception) {
                    w(error) { "Failed to unlock user info for a resident credential:" }
                }
                val info = credential.getTwoLineInfo(index + 1)
                CredentialItem(
                    rpId = rpId,
                    credential = credential,
                    title = info.first.toString(),
                    summary = info.second?.toString(),
                )
            }
            if (items.isEmpty()) null else CredentialGroup(rpId, items)
        }
    }
}

private data class CredentialGroup(val rpId: String, val items: List<CredentialItem>)

private data class CredentialItem(
    val rpId: String,
    val credential: WebAuthnCredential,
    val title: String,
    val summary: String?,
)

private fun ByteArray.groupedHex(maxBytes: Int = 32): String {
    val visibleBytes = if (size > maxBytes) copyOf(maxBytes) else this
    val grouped = Hex.bytesToStringUppercase(visibleBytes).chunked(4).joinToString(" ")
    return if (size > maxBytes) "$grouped … ($size bytes)" else grouped
}

@Composable
private fun CredentialList(groups: List<CredentialGroup>, onSelect: (CredentialItem) -> Unit) {
    WearScreen(
        title = stringResource(
            if (groups.isEmpty()) R.string.credential_management_title_no_credentials
            else R.string.credential_management_title,
        ),
    ) {
        groups.forEach { group ->
            item { WearSection(group.rpId) }
            group.items.forEach { item ->
                item(key = item.credential.hashCode()) {
                    WearButton(
                        label = item.title,
                        secondaryLabel = item.summary,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeleteConfirmation(
    visible: Boolean,
    item: CredentialItem,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        confirmButton = { AlertDialogDefaults.ConfirmButton(onClick = onConfirm) },
        dismissButton = { AlertDialogDefaults.DismissButton(onClick = onDismiss) },
        title = { Text(item.rpId) },
        text = {
            Text(stringResource(R.string.prompt_delete_resident_credential_message, item.title))
        },
    )
}
