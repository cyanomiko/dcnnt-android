package net.dcnnt.core

import android.util.Log
import org.json.JSONObject
import java.io.File
import java.lang.Exception
import java.net.*
import java.util.concurrent.atomic.AtomicBoolean


class Device(val dm: DeviceManager, val uin: Int, var name: String, var description: String,
             var port: Int, val role: String, var password: String): JsonSerializable() {
    /*
    self.key_recv = derive_key(''.join(map(str, (app_uin, self.uin, app_password, self.password))))
    self.key_send = derive_key(''.join(map(str, (self.uin, app_uin, self.password, app_password))))
    */
    var uinApp: Uin = 0
    var passApp: String = ""
    var keyRecv: ByteArray = byteArrayOf()
    var keySend: ByteArray = byteArrayOf()
    var ip: String? = null
    var pairingData: ByteArray? = null

    init {
        updateKeys()
    }

    fun updateKeys() {
        uinApp = APP.conf.uin.value
        passApp = APP.conf.password.value
        keyRecv = deriveRecvKey(uinApp, uin, passApp, password)
        keySend = deriveSendKey(uinApp, uin, passApp, password)
    }

    fun processPairData(code: String): DCResult {
        pairingData?.also {
            pairingData = null
            try {
                password = String(decrypt(it, derivePairingKey(code, uinApp)))
            } catch (_: DCDecryptionError) {
                return DCResult(false, "Incorrect pair code")
            }
            updateKeys()
            return DCResult(true, "OK")
        }
        return DCResult(false, "No pairing data")
    }

    fun isNew(): Boolean {
        return password.isEmpty()
    }

    override fun toJson(): JSONObject {
        return JSONObject(mapOf(
            "uin" to uin,
            "name" to name,
            "role" to role,
            "description" to description,
            "password" to password,
            "port" to port
        ))
    }

    fun toJSON() = toJson()

    override fun toString() = name
}


class DeviceManager(val path: String) {
    val TAG = "DC/DeviceManager"
    val EXTENSION = ".device.json"
    val searching = AtomicBoolean(false)
    val directory = File(path)
    val devices = LinkedHashMap<Int, Device>()
    var lastSearchTimestamp: Long = 0L
    private val searchingNow = AtomicBoolean(false)
    val commonDevice: Device
            get() = Device(APP.dm, 0, "Common", "All devices", PORT, "server", "")
    var searchDone = false

    fun init() {
        if (!directory.exists()) directory.mkdirs()
    }

    fun loadItem(file: File) {
        try {
            val json = JSONObject(file.readText())
            val uin = json.getInt("uin")
            val name = json.getString("name")
            val description = json.getString("description")
            val role = json.getString("role")
            val password = json.optString("password")
            val port = json.optInt("port", PORT)
            if ((uin >= UIN_MAX) or (uin <= UIN_MIN)) {
                Log.w(TAG, "Field 'uin' not in range [$UIN_MIN .. $UIN_MAX]")
                return
            }
            if ((name !is String) or (description !is String) or (role !is String) or (password !is String)) {
                Log.w(TAG, "Field 'name', 'description', 'role' or 'password' is not String")
                return
            }
            Log.d(TAG, "Create device instance, uin = $uin")
            devices[uin] = Device(this, uin, name.toString(), description.toString(), port,
                role.toString(), password.toString())
        } catch (e: Exception) {
            Log.e(TAG, e.toString())
        }
    }

    fun load() {
        directory.listFiles()?.forEach {
            if (it.absolutePath.endsWith(EXTENSION)) {
                Log.v(TAG, "Load device info from file ${it.absolutePath}")
                loadItem(it)
            }
        }
    }

    fun dumpItem(device: Device) {
        File("${directory.absolutePath}/${device.uin}$EXTENSION").writeText(device.toJSON().toString())
    }

    fun dump() = devices.forEach { dumpItem(it.value) }

    fun remove(uin: Int) {
        devices.remove(uin)?.also {
            File("${directory.absolutePath}/$uin$EXTENSION").apply { if (exists()) delete() }
        }
    }

    fun onlineDevices() = devices.filterValues { it.ip != null }.values.toMutableList().toList()
    fun availableDevices() = devices.filterValues { !it.isNew() and (it.ip != null) }.values.toMutableList().toList()

    fun getBroadcastAddreses(): List<String> {
        val res = mutableListOf("255.255.255.255")
        NetworkInterface.getNetworkInterfaces().toList().forEach {
            if (it.isUp and (!it.isVirtual) and (!it.isLoopback)) {
                Log.d(TAG, "${it.name}, ${it.hardwareAddress}")
                it.interfaceAddresses.forEach { a ->
                    a.broadcast?.also { b ->
                        val ip = "${b.hostName}"
                        if (ip.count { c -> c == '.' } == 3) {
                            res.add(ip)
                        }
                    }
                }
            }
        }
        return res
    }

    private fun sendToAll(socket: DatagramSocket, buf: ByteArray, dst: List<String>) {
        dst.forEach {
            socket.send(DatagramPacket(buf, buf.size, InetSocketAddress(it, PORT)))
        }
    }

    fun search(appConf: AppConf, timeout: Int = 10, triesRead: Int = 100, triesSend: Int = 2,
               pairInfo: Pair<Uin, String>? = null,
               onAvailableDevice: ((Device) -> Unit)? = null): Boolean {
        searchDone = true
        lastSearchTimestamp = System.currentTimeMillis() / 1000L
        var responseCounter: Int = 0
        val request = LegacySearchMessage(appConf.uin.value, appConf.name.value, encryptedPairingData=pairInfo?.let {
            encrypt(appConf.password.value.toByteArray(), derivePairingKey(it.second, it.first))
        }).toByteArray()
        var availableDevicesCountPre = 0
        devices.forEach {
            if (!it.value.isNew() and (it.value.ip != null)) availableDevicesCountPre++
            it.value.ip = null
        }
        try {
            val broadcastAddresses = getBroadcastAddreses()
            val socket = DatagramSocket(PORT)
            val responseData = ByteArray(4096)
            val response = DatagramPacket(responseData, responseData.size)
            val found = mutableSetOf<Int>()
            socket.broadcast = true
            socket.reuseAddress = true
            socket.soTimeout = timeout
            sendToAll(socket, request, broadcastAddresses)
            APP.log("Search devices on port $PORT, $broadcastAddresses", TAG)
            for (i in 0..triesRead) {
                if (i % (triesRead / triesSend) == 0) sendToAll(socket, request, broadcastAddresses)
                try {
                    try {
                        socket.receive(response)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val ip = response.address.hostAddress ?: continue
                    val r = LegacySearchMessage.fromByteArray(response.data)
                    Log.d(TAG, "$r")
                    if (r.action != DeviceSearchAction.RESPONSE) {
                        continue
                    }
                    Log.i(TAG, "Process response")
                    responseCounter++
                    val uin: Int = r.uin
                    if (!devices.containsKey(r.uin)) {
                        APP.log("Add new '${r.role.name}' device $uin '${r.name}', IP: $ip", TAG)
                        devices[uin] = Device(this, uin, r.name, "", PORT, r.roleName, "")
                    }
                    devices[uin]?.also {
                        if (!found.contains(uin)) {
                            APP.log( "Update '${it.role}' device '${it.name}' IP: $ip", TAG)
                            it.ip = ip
                            it.name = r.name
                            found.add(uin)
                            it.pairingData = r.encryptedPairingData
                            onAvailableDevice?.invoke(it)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, e.toString())
                }
            }
            socket.close()
            var availableDevicesCountCur = 0
            devices.forEach { if (!it.value.isNew() and (it.value.ip != null)) availableDevicesCountCur++ }
            APP.log("${found.size} devices found")
            return (availableDevicesCountCur != availableDevicesCountPre)
        } catch (e: Exception) {
            APP.logError("Exception occurred while searching devices", TAG)
            APP.logException(e, TAG)
            return availableDevicesCountPre != 0
        }
    }

    fun exclusiveSearch(appConf: AppConf): Boolean {
        if (!searchingNow.getAndSet(true)) return false
        return search(appConf).also { searching.set(false) }
    }

    fun syncSearch(appConf: AppConf, timeout: Int = 10, triesRead: Int = 100, triesSend: Int = 4,
                   pairInfo: Pair<Int, String>? = null,
                   onAvailableDevice: ((Device) -> Unit)? = null): Boolean {
        synchronized(this) {
            return search(appConf, timeout, triesRead, triesSend, pairInfo, onAvailableDevice)
        }
    }
}
