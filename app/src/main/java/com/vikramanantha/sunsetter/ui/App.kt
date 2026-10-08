package com.vikramanantha.sunsetter.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vikramanantha.sunsetter.R
import com.vikramanantha.sunsetter.ble.LampController
import com.vikramanantha.sunsetter.ble.LampController.Connection
import com.vikramanantha.sunsetter.ble.Protocol
import kotlin.math.roundToInt

private val Swatches = listOf(
    Color(0xFFFF0000), Color(0xFFFF6A00), Color(0xFFFFC800), Color(0xFF00FF40), Color(0xFF00E5FF),
    Color(0xFF0040FF), Color(0xFF8000FF), Color(0xFFFF00A0), Color(0xFFFFFFFF),
)

private val Rainbow = Brush.horizontalGradient((0..6).map { Color.hsv(it * 60f, 1f, 1f) })

private val Protocol.Scene.displayColor
    get() = when (this) {
        Protocol.Scene.Sunset -> Color(0xFFFFA040)
        Protocol.Scene.Sunrise -> Color(0xFFFF8A65)
        Protocol.Scene.SummerSun -> Color(0xFFFFD54F)
    }

private val Mode.displayColor
    get() = when (this) {
        is Mode.Rgb -> color
        is Mode.Scene -> scene.displayColor
    }

private val Mode.label
    get() = when (this) {
        is Mode.Rgb -> "Color"
        is Mode.Scene -> scene.label
    }

private fun hueOf(c: Color) = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }[0]

@Composable
fun App(vm: LampViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.retry() else denied = true
    }
    val askPermission = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!denied) {
                permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                // Android stops showing the prompt after a denial, so send them to the app's settings.
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                )
            }
        }
    }
    LaunchedEffect(Unit) { if (!vm.lamp.hasPermission()) askPermission() }

    val enabled = ui.connection == Connection.Connected
    Scaffold(containerColor = MaterialTheme.colorScheme.surface) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(ui.connection, onReconnect = vm::retry)
            ui.error?.let { error ->
                ErrorBanner(error, actionLabel = if (error == LampController.PERMISSION_NEEDED) "Allow" else null, onAction = askPermission)
            }
            PowerCard(ui, enabled, onToggle = vm::togglePower)
            Section("Brightness", enabled, trailing = "${ui.brightness}%") {
                Slider(
                    value = ui.brightness.toFloat(),
                    onValueChange = { vm.setBrightness(it.roundToInt()) },
                    valueRange = Protocol.BRIGHTNESS_MIN.toFloat()..Protocol.BRIGHTNESS_MAX.toFloat(),
                    enabled = enabled,
                )
            }
            Section("Color", enabled) {
                ColorPicker(ui.mode, enabled, onColor = vm::setColor)
            }
            Section("Scenes", enabled) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Protocol.Scene.entries.forEach { scene ->
                        SceneTile(
                            scene,
                            selected = (ui.mode as? Mode.Scene)?.scene == scene,
                            enabled = enabled,
                            onClick = { vm.setScene(scene) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Header(connection: Connection, onReconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Sunsetter", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        val (label, dot) = when (connection) {
            Connection.Connected -> "Connected" to MaterialTheme.colorScheme.primary
            Connection.Connecting -> "Connecting…" to MaterialTheme.colorScheme.tertiary
            Connection.Off -> "Disconnected" to MaterialTheme.colorScheme.outline
        }
        Surface(
            onClick = onReconnect,
            enabled = connection == Connection.Off,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(dot, CircleShape))
                Spacer(Modifier.size(8.dp))
                Text(label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, actionLabel: String?, onAction: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (actionLabel != null) TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun PowerCard(ui: LampUi, enabled: Boolean, onToggle: () -> Unit) {
    val lampColor = ui.mode.displayColor
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val container by animateColorAsState(if (ui.on) lampColor.copy(alpha = 0.28f).compositeOver(base) else base, label = "container")
    val bulb by animateColorAsState(if (ui.on) lampColor else MaterialTheme.colorScheme.onSurfaceVariant, label = "bulb")
    val glow by animateFloatAsState(if (ui.on) 1f else 0f, label = "glow")

    Card(
        onClick = onToggle,
        enabled = enabled,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = container, disabledContainerColor = container),
        modifier = Modifier.fillMaxWidth().height(240.dp),
    ) {
        Box(Modifier.fillMaxSize().alpha(if (enabled) 1f else 0.6f), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(220.dp)
                    .alpha(glow)
                    .background(Brush.radialGradient(listOf(lampColor.copy(alpha = 0.55f), Color.Transparent)), CircleShape)
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(painterResource(R.drawable.ic_bulb), contentDescription = null, tint = bulb, modifier = Modifier.size(84.dp))
                Spacer(Modifier.height(12.dp))
                Text(if (ui.on) "On" else "Off", style = MaterialTheme.typography.displayMedium)
                Text(
                    if (ui.on) "${ui.mode.label} · ${ui.brightness}%" else "Tap to turn on",
                    style = MaterialTheme.typography.bodyLarge.merge(Numbers),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, enabled: Boolean, trailing: String? = null, content: @Composable () -> Unit) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp).alpha(if (enabled) 1f else 0.5f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (trailing != null) Text(trailing, style = MaterialTheme.typography.titleMedium.merge(Numbers))
            }
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ColorPicker(mode: Mode, enabled: Boolean, onColor: (Color) -> Unit) {
    val current = (mode as? Mode.Rgb)?.color
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Swatches.forEach { c -> Swatch(c, selected = c == current, enabled = enabled) { onColor(c) } }
    }
    val hue = current?.let(::hueOf) ?: 30f
    Slider(
        value = hue,
        onValueChange = { onColor(Color.hsv(it, 1f, 1f)) },
        valueRange = 0f..360f,
        enabled = enabled,
        thumb = {
            Box(
                Modifier
                    .size(28.dp)
                    .shadow(3.dp, CircleShape)
                    .background(Color.White, CircleShape)
                    .padding(4.dp)
                    .background(Color.hsv(hue, 1f, 1f), CircleShape)
            )
        },
        track = {
            Box(Modifier.fillMaxWidth().height(14.dp).clip(CircleShape).background(Rainbow))
        },
    )
}

@Composable
private fun Swatch(color: Color, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = color,
        modifier = Modifier
            .size(44.dp)
            .then(
                if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                else Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            ),
    ) {
        if (selected) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SceneTile(
    scene: Protocol.Scene,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    ) {
        Column(Modifier.padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(32.dp)
                    .background(
                        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f), scene.displayColor)),
                        CircleShape,
                    )
            )
            Spacer(Modifier.height(8.dp))
            Text(scene.label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
