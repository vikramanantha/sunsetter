package com.vikramanantha.sunsetter.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.vikramanantha.sunsetter.R
import com.vikramanantha.sunsetter.ble.LampController
import com.vikramanantha.sunsetter.ble.LampController.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class SunsetterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = SunsetterWidget()
}

/** 2x1 widget: shows on/off and brightness, and tapping it toggles the lamp. */
class SunsetterWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val state by WidgetStore.state(context).collectAsState()
            GlanceTheme { Content(state) }
        }
    }

    @Composable
    private fun Content(state: WidgetStore.State) {
        val on = state.on == true
        val c = GlanceTheme.colors
        val (title, subtitle) = when {
            state.busy -> "…" to "Talking to lamp"
            state.failed -> "Offline" to "Tap to retry"
            state.on == null -> "Lamp" to "Tap to toggle"
            on -> "On" to "${state.brightness}%"
            else -> "Off" to "Tap to turn on"
        }
        Row(
            GlanceModifier
                .fillMaxSize()
                .cornerRadius(24.dp)
                .background(if (on) c.primaryContainer else c.surfaceVariant)
                .clickable(actionRunCallback<ToggleAction>())
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                GlanceModifier.size(40.dp).cornerRadius(20.dp).background(if (on) c.primary else c.surface),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    ImageProvider(R.drawable.ic_bulb),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(if (on) c.onPrimary else c.onSurfaceVariant),
                    modifier = GlanceModifier.size(24.dp),
                )
            }
            Spacer(GlanceModifier.width(10.dp))
            Column {
                val text = if (on) c.onPrimaryContainer else c.onSurfaceVariant
                Text(title, maxLines = 1, style = TextStyle(color = text, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Text(subtitle, maxLines = 1, style = TextStyle(color = text, fontSize = 12.sp))
            }
        }
    }
}

/**
 * Reads the lamp's real power state, then flips it, so the toggle is right even if MeRGBW or the
 * cable switch changed things since the widget last looked.
 */
class ToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        setLampPower(context, target = null)
    }
}
