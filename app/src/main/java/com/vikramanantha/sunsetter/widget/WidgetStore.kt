package com.vikramanantha.sunsetter.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.vikramanantha.sunsetter.ble.Protocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * What the widget shows. The lamp can't push state to a widget, so this is the last state anyone
 * (the app or a widget tap) saw, persisted so it survives the process dying.
 */
object WidgetStore {
    data class State(
        /** null until the lamp has been reached once. */
        val on: Boolean? = null,
        val brightness: Int = Protocol.BRIGHTNESS_MAX,
        val busy: Boolean = false,
        val failed: Boolean = false,
    )

    private var flow: MutableStateFlow<State>? = null

    fun state(context: Context): StateFlow<State> = flow(context)

    suspend fun update(context: Context, transform: (State) -> State) {
        val old = flow(context).getAndUpdate(transform)
        val new = flow(context).value
        if (new == old) return
        prefs(context).edit()
            .putInt(KEY_ON, when (new.on) { null -> -1; true -> 1; false -> 0 })
            .putInt(KEY_BRIGHTNESS, new.brightness)
            .apply()
        SunsetterWidget().updateAll(context)
    }

    @Synchronized
    private fun flow(context: Context): MutableStateFlow<State> = flow ?: MutableStateFlow(load(context)).also { flow = it }

    private fun load(context: Context): State {
        val p = prefs(context)
        return State(
            on = when (p.getInt(KEY_ON, -1)) { 1 -> true; 0 -> false; else -> null },
            brightness = p.getInt(KEY_BRIGHTNESS, Protocol.BRIGHTNESS_MAX),
        )
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("widget", Context.MODE_PRIVATE)

    private const val KEY_ON = "on"
    private const val KEY_BRIGHTNESS = "brightness"
}
