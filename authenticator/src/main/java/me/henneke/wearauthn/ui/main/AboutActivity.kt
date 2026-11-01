package me.henneke.wearauthn.ui.main

import android.os.Bundle
import android.text.Html
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.henneke.wearauthn.BuildConfig
import me.henneke.wearauthn.R
import me.henneke.wearauthn.fido.context.checkAllKeysInHardware
import me.henneke.wearauthn.ui.WearAppScaffold
import me.henneke.wearauthn.ui.WearBodyItem
import me.henneke.wearauthn.ui.WearButton
import me.henneke.wearauthn.ui.WearScreen
import me.henneke.wearauthn.ui.openUrlOnPhone
import me.henneke.wearauthn.ui.theme.WearAuthnTheme

class AboutActivity : ComponentActivity() {

    private var keyStorageMessage by mutableStateOf(0)
    private var textPage by mutableStateOf<TextPage?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearAuthnTheme {
                WearAppScaffold {
                    val page = textPage
                    if (page == null) {
                        WearScreen(title = stringResource(R.string.app_name)) {
                            item { WearBodyItem(text = BuildConfig.VERSION_NAME) }
                            item {
                                WearBodyItem(
                                    text = Html.fromHtml(
                                        getString(R.string.about_how_to_use),
                                        Html.FROM_HTML_MODE_LEGACY,
                                    ).toString(),
                                )
                            }
                            item {
                                WearButton(
                                    label = stringResource(R.string.message_continue_on_phone),
                                    iconRes = R.drawable.ic_open_on_phone,
                                    onClick = {
                                        openUrlOnPhone(this@AboutActivity, getString(R.string.url_usage))
                                    },
                                )
                            }
                            item {
                                WearBodyItem(
                                    text = stringResource(
                                        if (keyStorageMessage == 0) R.string.message_key_storage_type_unknown
                                        else keyStorageMessage,
                                    ),
                                )
                            }
                            item {
                                WearButton(
                                    label = stringResource(R.string.label_privacy),
                                    onClick = {
                                        textPage = TextPage(getString(R.string.label_privacy), privacyPolicy)
                                    },
                                )
                            }
                            item {
                                WearButton(
                                    label = stringResource(R.string.label_licenses),
                                    onClick = {
                                        textPage = TextPage(getString(R.string.label_licenses), licensesText)
                                    },
                                )
                            }
                        }
                    } else {
                        BackHandler { textPage = null }
                        // The licence text and privacy policy run to thousands of lines. Emitting
                        // them as a single item defeats the lazy column entirely: everything is
                        // measured and composed up front, and the transform maths then runs against
                        // one enormous item. Split on blank lines so the list can do its job.
                        val paragraphs = remember(page) {
                            page.text.split(PARAGRAPH_BREAK)
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                        }
                        WearScreen(title = page.title) {
                            paragraphs.forEachIndexed { index, paragraph ->
                                item(key = index) {
                                    WearBodyItem(text = paragraph, textAlign = TextAlign.Start)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        keyStorageMessage = 0
        lifecycleScope.launch(Dispatchers.IO) {
            val message = if (checkAllKeysInHardware()) {
                R.string.message_key_storage_type_hardware
            } else {
                R.string.message_key_storage_type_software
            }
            withContext(Dispatchers.Main) { keyStorageMessage = message }
        }
    }

    private val privacyPolicy by lazy {
        resources.openRawResource(R.raw.privacy_policy).bufferedReader().use { it.readText() }
    }

    private val licensesText by lazy {
        resources.openRawResource(R.raw.licenses_text).bufferedReader().use { it.readText() }
    }
}

private data class TextPage(val title: String, val text: String)

/** A blank line, i.e. a paragraph boundary, tolerating trailing whitespace and CRLF endings. */
private val PARAGRAPH_BREAK = Regex("\\R[ \\t]*\\R")
