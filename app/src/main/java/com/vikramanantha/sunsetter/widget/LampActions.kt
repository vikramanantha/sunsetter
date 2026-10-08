package com.vikramanantha.sunsetter.widget

import android.content.Context
import com.vikramanantha.sunsetter.ble.LampController
import com.vikramanantha.sunsetter.ble.LampController.Connection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Connects, sets the lamp's power, disconnects, and updates the widget.
 * [target]: true = on, false = off, null = toggle.
 * Returns the lamp's new state, or null if it couldn't be reached.
 */
suspend fun setLampPower(context: Context, target: Boolean?): Boolean? {
    if (WidgetStore.state(context).value.busy) return null
    WidgetStore.update(context) { it.copy(busy = true, failed = false) }

    val result = withContext(Dispatchers.Main) {
        val lamp = LampController.get(context)
        if (!lamp.hasPermission()) return@withContext null
        lamp.acquire()
        try {
            withTimeoutOrNull(12_000) {
                lamp.connection.first { it == Connection.Connected }
                val status = lamp.requestStatus()
                val on = target ?: !status.on
                lamp.setPowerAndWait(on)
                status.copy(on = on)
            }
        } finally {
            lamp.release()
        }
    }

    WidgetStore.update(context) {
        if (result == null) it.copy(busy = false, failed = true)
        else it.copy(on = result.on, brightness = result.brightness, busy = false, failed = false)
    }
    return result?.on
}
