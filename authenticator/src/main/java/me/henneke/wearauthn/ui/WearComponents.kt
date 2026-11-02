package me.henneke.wearauthn.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnState
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonColors
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

/**
 * App level scaffold. [AppScaffold] provides the ScaffoldState and layers TimeText above everything
 * else, so it belongs at the top of an activity's composition and must enclose every screen that
 * activity can show - otherwise each screen swap tears down and rebuilds the app scaffold and
 * TimeText re-animates on every navigation.
 *
 * Activities that show more than one screen should call this once and use [WearScreen] per screen.
 * Single screen activities can use [WearListScreen], which pairs the two.
 */
@Composable
fun WearAppScaffold(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    // AppScaffold's content is scoped to BoxScope. That receiver is deliberately not passed on:
    // nothing here needs align/matchParentSize, and exposing it silently rebinds `this` at every
    // call site, so an activity that wrote `Intent(this, ...)` would hand over the BoxScope.
    AppScaffold(modifier = modifier) { content() }
}

/**
 * One scrollable screen. Must be composed inside a [WearAppScaffold].
 */
@Composable
fun WearScreen(
    title: String,
    modifier: Modifier = Modifier,
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    content: TransformingLazyColumnScope.() -> Unit,
) {
    ScreenScaffold(scrollState = state, modifier = modifier) { contentPadding ->
        TransformingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = state,
            contentPadding = contentPadding,
        ) {
            item {
                val spec = rememberTransformationSpec()
                ListHeader(
                    modifier = Modifier.transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) {
                    Text(title, textAlign = TextAlign.Center)
                }
            }
            content()
        }
    }
}

/**
 * Convenience pairing of [WearAppScaffold] and [WearScreen] for activities and dialogs that only
 * ever show a single screen.
 */
@Composable
fun WearListScreen(
    title: String,
    modifier: Modifier = Modifier,
    state: TransformingLazyColumnState = rememberTransformingLazyColumnState(),
    content: TransformingLazyColumnScope.() -> Unit,
) {
    WearAppScaffold {
        WearScreen(title = title, modifier = modifier, state = state, content = content)
    }
}

/**
 * These are extensions on [TransformingLazyColumnItemScope] rather than plain composables so that
 * each one can build the [SurfaceTransformation] the list needs. A TransformingLazyColumn exists to
 * morph its items as they approach the edge of a round screen; an item that passes no transformation
 * renders flat and the list looks like a plain LazyColumn wearing Wear components.
 */
@Composable
fun TransformingLazyColumnItemScope.WearButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondaryLabel: String? = null,
    @DrawableRes iconRes: Int? = null,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    val spec = rememberTransformationSpec()
    Button(
        onClick = onClick,
        // No horizontal padding here: ScreenScaffold already applies a screen-width-relative
        // content padding, and adding a fixed inset on top of it double pads every row.
        modifier = modifier.fillMaxWidth().transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        secondaryLabel = secondaryLabel?.let { text -> { Text(text) } },
        icon = iconRes?.let { drawable ->
            {
                Icon(
                    painter = painterResource(drawable),
                    contentDescription = null,
                )
            }
        },
        enabled = enabled,
        colors = colors,
        label = { Text(label) },
    )
}

@Composable
fun TransformingLazyColumnItemScope.WearSection(title: String) {
    val spec = rememberTransformationSpec()
    ListSubHeader(
        modifier = Modifier.transformedHeight(this, spec),
        transformation = SurfaceTransformation(spec),
        label = { Text(title) },
    )
}

@Composable
fun TransformingLazyColumnItemScope.WearBodyItem(
    text: String,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(vertical = 6.dp),
    color: Color = Color.Unspecified,
    // Short body copy is centred: on a round display, left aligned paragraphs lose their first and
    // last characters to the curve on the top and bottom lines. Long form prose (licences, the
    // privacy policy) reads better left aligned and should pass TextAlign.Start.
    textAlign: TextAlign = TextAlign.Center,
) {
    val spec = rememberTransformationSpec()
    val transformation = SurfaceTransformation(spec)
    Text(
        text = text,
        textAlign = textAlign,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        modifier = modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .transformedContent(transformation)
            .padding(contentPadding),
    )
}

@Composable
fun TransformingLazyColumnItemScope.WearDetailItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    val spec = rememberTransformationSpec()
    val transformation = SurfaceTransformation(spec)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .transformedHeight(this, spec)
            .transformedContent(transformation)
            .padding(vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Applies the scroll driven fade/scale that [SurfaceTransformation] would otherwise apply to a
 * component's own surface. Used for items that draw no surface of their own, so that plain text
 * fades in step with the buttons around it instead of staying stubbornly opaque.
 */
private fun Modifier.transformedContent(transformation: SurfaceTransformation): Modifier =
    graphicsLayer { with(transformation) { applyContentTransformation() } }
