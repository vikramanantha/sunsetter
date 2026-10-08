package com.vikramanantha.sunsetter.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps one GATT connection to the lamp. Connects straight to [address] (no scan), enables FFF4
 * notifications, then writes frames to FFF3 one at a time, since Android GATT allows a single
 * operation in flight.
 *
 * The lamp accepts only one connection, so the app and the widget share one instance ([get]) and
 * the link stays up while anyone holds it ([acquire]/[release]). MeRGBW has to be closed meanwhile.
 * All public functions are main-thread only.
 */
@SuppressLint("MissingPermission") // open() checks hasPermission() before touching the GATT
class LampController private constructor(context: Context, private val address: String) {
    enum class Connection { Off, Connecting, Connected }

    private val app = context.applicationContext
    private val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _connection = MutableStateFlow(Connection.Off)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    /** Last power/brightness the lamp reported. */
    private val _status = MutableStateFlow<Protocol.Reply.Status?>(null)
    val status: StateFlow<Protocol.Reply.Status?> = _status.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Every reply the lamp sends on FFF4. */
    private val _replies = MutableSharedFlow<Protocol.Reply>(extraBufferCapacity = 16)
    val replies: SharedFlow<Protocol.Reply> = _replies.asSharedFlow()

    // Only touched on the main thread (GATT callbacks hop over via onMain).
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var users = 0
    private val wanted get() = users > 0
    private var sentPower: Boolean? = null
    private var failures = 0
    private var retryJob: Job? = null

    private val queue = Channel<ByteArray>(Channel.UNLIMITED)
    @Volatile private var pendingWrite: CompletableDeferred<Unit>? = null

    init {
        scope.launch { for (frame in queue) writeFrame(frame) }
    }

    fun hasPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** Starts holding the connection; it stays up until every [acquire] has a matching [release]. */
    fun acquire() {
        users++
        if (gatt == null) open()
    }

    fun release() {
        if (users == 0) return
        users--
        if (users == 0) {
            retryJob?.cancel()
            close()
            _connection.value = Connection.Off
        }
    }

    /** Tries again right away instead of waiting for the next retry, e.g. after the permission is granted. */
    fun retry() {
        if (wanted && gatt == null) open()
    }

    /** Queues [frame] for the lamp. Dropped when not connected, so taps don't replay on reconnect. */
    fun send(frame: ByteArray) {
        if (_connection.value != Connection.Connected) return
        if (frame[1].toInt() == Protocol.CMD_POWER) sentPower = frame[4].toInt() == 1
        queue.trySend(frame)
    }

    /** Asks the lamp for its power/brightness and waits for the answer. */
    suspend fun requestStatus(): Protocol.Reply.Status = coroutineScope {
        val reply = async(start = CoroutineStart.UNDISPATCHED) { replies.filterIsInstance<Protocol.Reply.Status>().first() }
        send(Protocol.status())
        reply.await()
    }

    /** Sets power and waits for the lamp to acknowledge it. */
    suspend fun setPowerAndWait(on: Boolean) = coroutineScope {
        val ack = async(start = CoroutineStart.UNDISPATCHED) {
            replies.first { it is Protocol.Reply.Ack && it.cmd == Protocol.CMD_POWER }
        }
        send(Protocol.power(on))
        ack.await()
    }

    private fun open() {
        val a = adapter ?: run { _error.value = "This phone has no Bluetooth"; return }
        if (!hasPermission()) { _error.value = PERMISSION_NEEDED; return }
        if (!a.isEnabled) { _error.value = "Bluetooth is off"; _connection.value = Connection.Off; scheduleRetry(); return }
        _connection.value = Connection.Connecting
        gatt = a.getRemoteDevice(address).connectGatt(app, false, callback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun close() {
        gatt?.run { disconnect(); close() }
        gatt = null
        writeChar = null
        pendingWrite?.complete(Unit)
    }

    private fun scheduleRetry() {
        if (!wanted) return
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(2000)
            if (wanted && gatt == null) open()
        }
    }

    private fun markReady() {
        failures = 0
        _error.value = null
        _connection.value = Connection.Connected
        send(Protocol.status())
    }

    private fun connectionFailed() {
        close()
        failures++
        if (failures >= 2) _error.value = "Can't reach the lamp. Is it plugged in, and is MeRGBW closed?"
        _connection.value = if (wanted) Connection.Connecting else Connection.Off
        scheduleRetry()
    }

    private suspend fun writeFrame(frame: ByteArray) {
        repeat(3) {
            val g = gatt ?: return
            val c = writeChar ?: return
            val done = CompletableDeferred<Unit>().also { pendingWrite = it }
            val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, frame, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                c.value = frame
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            if (started) {
                withTimeoutOrNull(1000) { done.await() }
                return
            }
            delay(30) // stack busy; try again
        }
    }

    private fun enableNotifications(g: BluetoothGatt, d: BluetoothGattDescriptor) {
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            @Suppress("DEPRECATION")
            d.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(d)
        }
    }

    private fun handle(value: ByteArray) {
        val reply = Protocol.parse(value) ?: return
        when {
            reply is Protocol.Reply.Status -> _status.value = reply
            // Power acks don't say on/off, but they confirm the last power command we sent.
            reply is Protocol.Reply.Ack && reply.cmd == Protocol.CMD_POWER && reply.ok -> sentPower?.let { on ->
                _status.value = (_status.value ?: Protocol.Reply.Status(on, Protocol.BRIGHTNESS_MAX)).copy(on = on)
            }
        }
        _replies.tryEmit(reply)
    }

    private fun onMain(block: () -> Unit) {
        scope.launch { block() }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) = onMain {
            if (g != gatt) { g.close(); return@onMain }
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                connectionFailed()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = onMain {
            if (g != gatt) return@onMain
            val service = g.getService(Protocol.SERVICE)
            val write = service?.getCharacteristic(Protocol.WRITE)
            val notify = service?.getCharacteristic(Protocol.NOTIFY)
            if (write == null || notify == null) {
                _error.value = "That device doesn't look like the Sunset lamp"
                close()
                _connection.value = Connection.Off
                return@onMain
            }
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(Protocol.CCCD)
            if (cccd == null) markReady() else enableNotifications(g, cccd)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = onMain {
            if (g == gatt) markReady()
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(Unit)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onMain { handle(value) }

        @Deprecated("Called instead of the 3-arg version below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) c.value?.copyOf()?.let { v -> onMain { handle(v) } }
        }
    }

    companion object {
        @Volatile private var instance: LampController? = null

        fun get(context: Context): LampController = instance ?: synchronized(this) {
            instance ?: LampController(context.applicationContext, Protocol.LAMP_ADDRESS).also { instance = it }
        }

        const val PERMISSION_NEEDED = "Sunsetter needs the Nearby devices permission to talk to the lamp"
    }
}
