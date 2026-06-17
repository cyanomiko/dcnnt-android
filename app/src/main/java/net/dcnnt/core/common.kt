package net.dcnnt.core

import android.util.Log
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.Exception

lateinit var APP: App
const val UIN_MIN = 0x0000000F
const val UIN_MAX = 0x0FFFFFFF
const val PORT = 5040
const val WRITE_REQUEST_CODE: Int = 43
val ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
val EVENT_AVAILABLE_DEVICES_UPDATED = "net.dcnnt:EVENT_AVAILABLE_DEVICES_UPDATED"

typealias Uin = Int

/**
 * Common parent class for any exception in all DC app
 * @param message - short error message for logging, debug and (sometimes) UI
 */
open class DCException(message: String): Exception(message)

typealias ProgressCallback = (cur: Long, total: Long, part: Long) -> Unit

fun uIntFromBytes(bytes: ByteArray): Int {
    return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN).int
}

fun uIntToBytes(num: Int): ByteArray {
    return ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(num).array()
}

fun patchDict(orig: JSONObject, update: JSONObject) {
    update.keys().forEach { key ->
        val updateValue = update.get(key)
        val origValue = orig.opt(key)
        if ((origValue is JSONObject) and (updateValue is JSONObject)) {
            patchDict(origValue as JSONObject, updateValue as JSONObject)
        } else {
            orig.put(key, updateValue)
        }
    }
}