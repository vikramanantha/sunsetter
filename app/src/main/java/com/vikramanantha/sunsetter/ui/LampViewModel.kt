package com.vikramanantha.sunsetter.ui

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vikramanantha.sunsetter.ble.LampController
import com.vikramanantha.sunsetter.ble.Protocol
import com.vikramanantha.sunsetter.widget.WidgetStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the lamp is showing. The lamp doesn't report this, so it's remembered locally. */
sealed interface Mode {
    data class Rgb(val color: Color) : Mode
    data class Scene(val scene: Protocol.Scene) : Mode
}

data class LampUi(
    val connection: LampController.Connection = LampController.Connection.Off,
    val error: String? = null,
    val on: Boolean = false,
    val brightness: Int = Protocol.BRIGHTNESS_MAX,
    val mode: Mode = Mode.Scene(Protocol.Scene.Sunset),
)

class LampViewModel(app: Application) : AndroidViewModel(app) {
    val lamp = LampController.get(app)
    private val prefs = app.getSharedPreferences("lamp", Context.MODE_PRIVATE)

    private val on = MutableStateFlow(false)
    private val brightness = MutableStateFlow(Protocol.BRIGHTNESS_MAX)
    private val mode = MutableStateFlow(loadMode())

    // Sliders fire on every pixel; these hold the latest value and the senders below drain them at ~15/s.
    private val brightnessOut = MutableStateFlow<Int?>(null)
    private val colorOut = MutableStateFlow<Color?>(null)

    val ui: StateFlow<LampUi> = combine(lamp.connection, lamp.error, on, brightness, mode) { c, e, o, b, m ->
        LampUi(c, e, o, b, m)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LampUi())

    init {
        viewModelScope.launch {
            lamp.status.filterNotNull().collect {
                on.value = it.on
                brightness.value = it.brightness
                WidgetStore.update(getApplication()) { w -> w.copy(on = it.on, brightness = it.brightness) }
            }
        }
        viewModelScope.launch {
            brightnessOut.filterNotNull().collect {
                lamp.send(Protocol.brightness(it))
                delay(SEND_INTERVAL_MS)
            }
        }
        viewModelScope.launch {
            colorOut.filterNotNull().collect {
                lamp.send(Protocol.color(it.red.to255(), it.green.to255(), it.blue.to255()))
                delay(SEND_INTERVAL_MS)
            }
        }
    }

    /** Hold the lamp connection while the app is on screen. */
    fun start() = lamp.acquire()
    fun stop() = lamp.release()
    fun retry() = lamp.retry()

    fun setPower(value: Boolean) {
        on.value = value
        lamp.send(Protocol.power(value))
    }

    fun togglePower() = setPower(!on.value)

    fun setBrightness(level: Int) {
        val clamped = level.coerceIn(Protocol.BRIGHTNESS_MIN, Protocol.BRIGHTNESS_MAX)
        brightness.value = clamped
        brightnessOut.value = clamped
    }

    fun setColor(color: Color) {
        ensureOn()
        setMode(Mode.Rgb(color))
        colorOut.value = color
    }

    fun setScene(scene: Protocol.Scene) {
        ensureOn()
        setMode(Mode.Scene(scene))
        colorOut.value = null // so picking the same color again after a scene still sends it
        lamp.send(Protocol.scene(scene.index))
    }

    private fun ensureOn() {
        if (!on.value) setPower(true)
    }

    private fun setMode(m: Mode) {
        mode.value = m
        prefs.edit().apply {
            when (m) {
                is Mode.Rgb -> putInt(KEY_COLOR, m.color.toArgb()).remove(KEY_SCENE)
                is Mode.Scene -> putInt(KEY_SCENE, m.scene.index)
            }
        }.apply()
    }

    private fun loadMode(): Mode {
        val scene = Protocol.Scene.entries.firstOrNull { it.index == prefs.getInt(KEY_SCENE, -1) }
        return when {
            scene != null -> Mode.Scene(scene)
            prefs.contains(KEY_COLOR) -> Mode.Rgb(Color(prefs.getInt(KEY_COLOR, 0)))
            else -> Mode.Scene(Protocol.Scene.Sunset)
        }
    }

    private fun Float.to255() = (this * 255).roundToInt()

    private companion object {
        const val SEND_INTERVAL_MS = 60L
        const val KEY_COLOR = "color"
        const val KEY_SCENE = "scene"
    }
}
