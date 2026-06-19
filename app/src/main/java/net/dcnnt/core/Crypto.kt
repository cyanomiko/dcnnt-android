package net.dcnnt.core

import java.lang.Exception
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Default hash function for raw data
 * @param data - data to calculate hash from
 * @return safe hash as bytes
 */
fun hashFun(data: ByteArray): ByteArray {
    return MessageDigest.getInstance("SHA-256").digest(data)
}

/**
 * Create binary encryption key from text
 * @param text - initial text value for encryption key
 * @return bytes of encryption key
 */
fun deriveKeyFromString(text: String): ByteArray {
    return hashFun(text.toByteArray())
}

/**
 * Derive decryption key for DC transport to receive data from remote device
 * @param localUin - UIN of our local device/app
 * @param remoteUin - UIN of remote device
 * @param localPassword - text password of our local device/app
 * @param remotePassword - text password of remote device
 * @return receive decryption key as byte array
 */
fun deriveRecvKey(localUin: Uin, remoteUin: Uin, localPassword: String, remotePassword: String): ByteArray {
    return deriveKeyFromString(listOf(localUin, remoteUin, localPassword, remotePassword).joinToString(""))
}

/**
 * Derive encryption key for DC transport to sent data to remote device
 * @param localUin - UIN of our local device/app
 * @param remoteUin - UIN of remote device
 * @param localPassword - text password of our local device/app
 * @param remotePassword - text password of remote device
 * @return sent encryption key as byte array
 */
fun deriveSendKey(localUin: Uin, remoteUin: Uin, localPassword: String, remotePassword: String): ByteArray {
    return deriveKeyFromString(listOf(remoteUin, localUin, remotePassword, localPassword).joinToString(""))
}

/**
 * Derive encryption/decryption key for pairing routine
 * @param pairingCode - pairing code (random or from user)
 * @param uin - UIN of local or remote device
 * @return sent encryption key as byte array
 */
fun derivePairingKey(pairingCode: String, uin: Uin): ByteArray {
    return deriveKeyFromString("$pairingCode$uin")
}

/**
 * Any error on decryption
 * @param message - short error message for logging, debug and (sometimes) UI
 */
open class DCDecryptionError(message: String): DCException(message)

/**
 * Encryption method code is unknown
 */
open class DCUnknownEncryptionMethod(): DCException("Unknown encryption method")

/**
 * Encryption/decryption utility for DC connection
 */
interface EncryptionMethod {
    val key: ByteArray

    /**
     * Encrypt some raw data using key and return decryptable bytes array
     * @param data - bytes to encrypt
     * @return encrypted bytes
     */
    fun encrypt(data: ByteArray): ByteArray

    /**
     * Decrypt some raw data using key and return plain byte array
     * @param data - bytes to decrypt
     * @return decrypted bytes
     * @throws DCDecryptionError
     */
    fun decrypt(data: ByteArray): ByteArray

    companion object {
        /**
         * Create encryption method object from method code and key
         * @param key - encryption/decryption key
         * @param methodCode - encryption/decryption method code'
         * @return ready encryption method object
         * @throws DCUnknownEncryptionMethod
         */
        fun new(key: ByteArray, methodCode: Int = 0): EncryptionMethod = when (methodCode) {
            DefaultEncryption.CODE -> DefaultEncryption(key)
            ZeroEncryption.CODE -> ZeroEncryption(key)
            else -> throw DCUnknownEncryptionMethod()
        }

        /**
         * Get length of encrypted plugin mark by encryption method code
         * @param methodCode - encryption/decryption method code'
         * @return integer value
         * @throws DCUnknownEncryptionMethod
         */
        fun getPlgFieldSize(methodCode: Int = 0): Int = when (methodCode) {
            DefaultEncryption.CODE -> DefaultEncryption.ENCRYPTED_PLG_LEN
            ZeroEncryption.CODE -> ZeroEncryption.ENCRYPTED_PLG_LEN
            else -> throw DCUnknownEncryptionMethod()
        }
    }
}

/**
 * Default encryption method - AES/GCM
 */
class DefaultEncryption(override val key: ByteArray): EncryptionMethod {
    companion object {
        const val CODE = 0
        const val ENCRYPTED_PLG_LEN = 36
    }

    override fun encrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey =  SecretKeySpec(key, 0, key.size, "AES")
        val modeConf = GCMParameterSpec(128, ByteArray(cipher.blockSize))
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, modeConf)
        val encrypted: ByteArray = cipher.doFinal(data)
        return cipher.iv + encrypted
    }

    override fun decrypt(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val secretKey =  SecretKeySpec(key, 0, key.size, "AES")
        val modeConf = GCMParameterSpec(128, data.slice(0 until 16).toByteArray())
        cipher.init(Cipher.DECRYPT_MODE, secretKey, modeConf)
        try {
            return cipher.doFinal(data.slice(16 .. data.lastIndex).toByteArray())
        } catch (e: Exception) {
            throw DCDecryptionError("$e")
        }
    }
}

/**
 * No encryption - for debug or protected connections
 */
class ZeroEncryption(override val key: ByteArray): EncryptionMethod {
    companion object {
        const val CODE = 0xFF
        const val ENCRYPTED_PLG_LEN = 4
    }

    override fun encrypt(data: ByteArray): ByteArray = data
    override fun decrypt(data: ByteArray): ByteArray = data
}

/**
 * Get length of encrypted 4-bytes string by method code
 * @param methodCode - encryption/encoding method code
 * @return length of plugin code field in handshake
 */
fun getEncryptedPlgLength(methodCode: Int = 0): Int = EncryptionMethod.getPlgFieldSize(methodCode)

/**
 * Helper function to encrypt data
 * @param data - raw data to encrypt
 * @param key - encryption key
 * @param methodCode - encryption method code
 * @return encrypted bytes
 */
fun encrypt(data: ByteArray, key: ByteArray, methodCode: Int = 0): ByteArray =
    EncryptionMethod.new(key, methodCode).encrypt(data)

/**
 * Helper function to decrypt data
 * @param data - encrypted data to decrypt
 * @param key - encryption key
 * @param methodCode - encryption method code
 * @return plain bytes
 */
fun decrypt(data: ByteArray, key: ByteArray, methodCode: Int = 0): ByteArray =
    EncryptionMethod.new(key, methodCode).decrypt(data)
