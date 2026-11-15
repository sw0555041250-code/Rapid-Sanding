package com.chdv.quickreceive

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import java.net.InetAddress

/** A device that is ready to receive. */
data class Peer(val serviceName: String, val endpointId: String, val name: String, val type: Int, val host: InetAddress, val port: Int)

/**
 * Finds nearby Quick Share receivers on the Wi-Fi network (mDNS), and sends the Bluetooth
 * "someone nearby is sharing" signal that makes phones announce themselves.
 */
class Discovery(private val ctx: Context, private val ownEndpointId: String, private val onChange: (List<Peer>, Int) -> Unit) {
    private val nsd = ctx.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val peers = LinkedHashMap<String, Peer>()
    private val hidden = HashSet<String>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null
    private var advertiseCallback: AdvertiseCallback? = null
    var bleError = 0
        private set
    var bleState: String = "off"   // "on", "off" (Bluetooth is off), "unsupported", "failed"
        private set

    private var multicast: android.net.wifi.WifiManager.MulticastLock? = null

    fun start() {
        multicast = runCatching {
            ctx.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
                .createMulticastLock("quickshare:discovery").apply { setReferenceCounted(false); acquire() }
        }.getOrNull()
        Live.log("מחפש מכשירים (multicast ${if (multicast?.isHeld == true) "פעיל" else "לא פעיל"})")
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { Live.log("חיפוש ברשת נכשל, קוד $errorCode") }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                val id = endpointIdOf(info.serviceName)
                Live.log("נמצא ברשת: ${info.serviceName}${if (id == null) " (שם לא תקין)" else if (id == ownEndpointId) " (אני)" else ""}")
                if (id == null) return
                if (id == ownEndpointId) return
                main.post { queue.add(info); next() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (peers.remove(info.serviceName) != null || hidden.remove(info.serviceName)) publish()
                }
            }
        }
        listener = l
        runCatching { nsd.discoverServices(ReceiverService.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
        startBeacon()
    }

    fun stop() {
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        stopBeacon()
        runCatching { multicast?.let { if (it.isHeld) it.release() } }
        multicast = null
        main.removeCallbacksAndMessages(null)
    }

    /** Resolve one at a time (older Android can only do one). */
    private fun next() {
        if (resolving || listener == null) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ queue.add(info); next() }, 300) else next()
                    }
                }
                override fun onServiceResolved(si: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        add(si)
                        next()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    private fun add(si: NsdServiceInfo) {
        val id = endpointIdOf(si.serviceName) ?: return
        val host = si.host ?: return
        val raw = si.attributes["n"] ?: run { Live.log("${si.serviceName}: חסר מידע על המכשיר"); return }
        val info = runCatching { Base64.decode(String(raw, Charsets.US_ASCII), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull() ?: return
        if (info.size < 18) return
        val visibleName = info[0].toInt() and 0x10 == 0
        if (!visibleName) {
            Live.log("${si.serviceName}: גלוי רק לאנשי קשר")
            // shown only to the owner's contacts: we can't see its name or connect
            hidden.add(si.serviceName); publish(); return
        }
        val len = info[17].toInt() and 0xFF
        if (info.size < 18 + len) return
        val name = String(info, 18, len, Charsets.UTF_8)
        peers[si.serviceName] = Peer(si.serviceName, id, name, (info[0].toInt() shr 1) and 7, host, si.port)
        Live.log("מכשיר זמין: $name (${host.hostAddress}:${si.port})")
        publish()
    }

    private fun publish() = onChange(peers.values.toList(), hidden.size)

    // ---------- Bluetooth wake-up signal ----------

    @SuppressLint("MissingPermission")
    private fun startBeacon() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) { bleState = "unsupported"; Live.log("אין Bluetooth"); return }
        if (!adapter.isEnabled) { bleState = "off"; Live.log("Bluetooth כבוי"); return }
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null || !adapter.isMultipleAdvertisementSupported) { bleState = "unsupported"; Live.log("המכשיר לא תומך בשידור Bluetooth LE"); return }
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceData(SHARING_UUID, BEACON_PREFIX + Crypto.randomBytes(10))
            .build()
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false)
            .setTimeout(0)
            .build()
        val cb = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { bleState = "on"; Live.log("משדר אות Bluetooth לטלפונים"); publish() }
            override fun onStartFailure(errorCode: Int) { bleState = "failed"; bleError = errorCode; Live.log("שידור Bluetooth נכשל, קוד $errorCode"); publish() }
        }
        advertiseCallback = cb
        runCatching { adv.startAdvertising(settings, data, cb) }.onFailure { bleState = "failed" }
    }

    @SuppressLint("MissingPermission")
    private fun stopBeacon() {
        val cb = advertiseCallback ?: return
        advertiseCallback = null
        runCatching { BluetoothAdapter.getDefaultAdapter()?.bluetoothLeAdvertiser?.stopAdvertising(cb) }
    }

    companion object {
        private const val TAG = "QuickShareDiscovery"
        val SHARING_UUID: ParcelUuid = ParcelUuid.fromString("0000fe2c-0000-1000-8000-00805f9b34fb")
        /** "a device nearby is sharing" – phones answer by announcing themselves on the network */
        private val BEACON_PREFIX = byteArrayOf(0xFC.toByte(), 0x12, 0x8E.toByte(), 0x01, 0x42, 0, 0, 0, 0, 0, 0, 0, 0, 0)

        /** The 4-character endpoint id inside a service name, or null if it isn't a Quick Share service. */
        fun endpointIdOf(serviceName: String): String? {
            val b = runCatching { Base64.decode(serviceName, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP) }.getOrNull() ?: return null
            if (b.size < 10 || b[0] != 0x23.toByte() || b[5] != 0xFC.toByte() || b[6] != 0x9F.toByte() || b[7] != 0x5E.toByte()) return null
            return String(b, 1, 4, Charsets.US_ASCII)
        }
    }
}
