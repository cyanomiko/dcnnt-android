package net.dcnnt.core

import android.util.Log
import org.json.JSONObject
import java.lang.Exception
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

lateinit var APP: App
const val UIN_MIN = 0x0000000F
const val UIN_MAX = 0x0FFFFFFF
const val PORT = 5040
const val WRITE_REQUEST_CODE: Int = 43
val ACTION_NOTIFICATION_LISTENER_SETTINGS = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"
val ENABLED_NOTIFICATION_LISTENERS = "enabled_notification_listeners"
val EVENT_AVAILABLE_DEVICES_UPDATED = "net.dcnnt:EVENT_AVAILABLE_DEVICES_UPDATED"

fun hashFun(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(data)
}

fun deriveKey(password: String): ByteArray {
    return hashFun(password.toByteArray())
}

fun encrypt(data: ByteArray, key: ByteArray): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKey =  SecretKeySpec(key, 0, key.size, "AES")
    val modeConf = GCMParameterSpec(128, ByteArray(cipher.blockSize))
    cipher.init(Cipher.ENCRYPT_MODE, secretKey, modeConf)
    val encrypted: ByteArray = cipher.doFinal(data)
//    Log.d("DC/Crypto", key.joinToString(", ") { "%d".format(it) })
//    Log.d("DC/Crypto", data.joinToString(", ") { "%d".format(it) })
//    Log.d("DC/Crypto", encrypted.joinToString(", ") { "%d".format(it) })
//    Log.d("DC/Crypto", cipher.iv.joinToString(", ") { "%d".format(it) })
    return cipher.iv + encrypted
}

fun decrypt(data: ByteArray, key: ByteArray): ByteArray? {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val secretKey =  SecretKeySpec(key, 0, key.size, "AES")
    val modeConf = GCMParameterSpec(128, data.slice(0 until 16).toByteArray())
    // ToDo: Decryption
    cipher.init(Cipher.DECRYPT_MODE, secretKey, modeConf)
    try {
        return cipher.doFinal(data.slice(16 .. data.lastIndex).toByteArray())
    } catch (e: Exception) {
        Log.e("DC/Crypto", e.toString())
    }
    return null
}

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