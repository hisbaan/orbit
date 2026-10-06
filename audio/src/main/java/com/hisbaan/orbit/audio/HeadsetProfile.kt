package com.hisbaan.orbit.audio

import com.hisbaan.orbit.diagnostics.EventLog
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Holds the HFP (BluetoothHeadset) profile proxy. Binding the proxy is asynchronous and
 * takes tens of milliseconds, so it is fetched once and cached; on a headset trigger we
 * want `startVoiceRecognition` to go out as fast as possible.
 */
class HeadsetProfile(context: Context) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    @Volatile
    private var proxy: BluetoothHeadset? = null
    private var pending: CompletableDeferred<BluetoothHeadset?>? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile) {
            proxy = p as BluetoothHeadset
            EventLog.log("hfp", "Headset profile proxy connected")
            pending?.complete(proxy)
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
            EventLog.log("hfp", "Headset profile proxy disconnected")
        }
    }

    /** Returns the proxy, binding it if needed. Null if Bluetooth is off or unavailable. */
    suspend fun get(): BluetoothHeadset? {
        proxy?.let { return it }
        val adapter = adapter ?: return null
        val deferred = synchronized(this) {
            pending?.takeIf { it.isActive } ?: CompletableDeferred<BluetoothHeadset?>().also {
                pending = it
                if (!adapter.getProfileProxy(appContext, listener, BluetoothProfile.HEADSET)) {
                    EventLog.log("hfp", "getProfileProxy(HEADSET) returned false (Bluetooth off?)")
                    it.complete(null)
                }
            }
        }
        return withTimeoutOrNull(PROXY_TIMEOUT_MS) { deferred.await() }
    }

    /** The proxy if already bound, without waiting. */
    fun cached(): BluetoothHeadset? = proxy

    @SuppressLint("MissingPermission")
    fun connectedDevices(): List<BluetoothDevice> = try {
        proxy?.connectedDevices.orEmpty()
    } catch (e: SecurityException) {
        EventLog.log("hfp", "connectedDevices: missing BLUETOOTH_CONNECT")
        emptyList()
    }

    @SuppressLint("MissingPermission")
    fun isAudioConnected(device: BluetoothDevice): Boolean = try {
        proxy?.isAudioConnected(device) == true
    } catch (_: SecurityException) {
        false
    }

    fun close() {
        proxy?.let { adapter?.closeProfileProxy(BluetoothProfile.HEADSET, it) }
        proxy = null
    }

    private companion object {
        const val PROXY_TIMEOUT_MS = 2000L
    }
}
