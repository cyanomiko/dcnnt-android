package net.dcnnt

import net.dcnnt.core.DCDecryptionError
import net.dcnnt.core.DefaultEncryption
import net.dcnnt.core.ZeroEncryption
import net.dcnnt.core.decrypt
import net.dcnnt.core.deriveKeyFromString
import net.dcnnt.core.encrypt

import org.junit.Test
import org.junit.Assert.*
import org.junit.Assert.assertArrayEquals


class CryptoUnitTest {
    @Test
    fun zeroEncryption() {
        val data = "Some test data".encodeToByteArray()
        val em = ZeroEncryption(byteArrayOf())
        assertEquals(data, em.decrypt(em.encrypt(data)))
    }

    @Test
    fun zeroEncryptionEmptyData() {
        val data = byteArrayOf()
        val em = ZeroEncryption(byteArrayOf())
        assertEquals(data, em.decrypt(em.encrypt(data)))
    }

    @Test
    fun defaultEncryption() {
        val data = "Some test data".encodeToByteArray()
        val em = DefaultEncryption(deriveKeyFromString("Test key"))
        assertArrayEquals(data, em.decrypt(em.encrypt(data)))
    }

    @Test
    fun defaultEncryptionEmptyData() {
        val data = byteArrayOf()
        val em = DefaultEncryption(deriveKeyFromString("Test key"))
        assertArrayEquals(data, em.decrypt(em.encrypt(data)))
    }

    @Test
    fun defaultEncryptionIncorrectKey() {
        val data = "Some test data".encodeToByteArray()
        val em0 = DefaultEncryption(deriveKeyFromString("Test key"))
        val em1 = DefaultEncryption(deriveKeyFromString("Incorrect key"))
        val exception = assertThrows(DCDecryptionError::class.java) {
            em1.decrypt(em0.encrypt(data))
        }
        assert(exception.message?.isNotEmpty() ?: false)
    }

    @Test
    fun helperCryptoFunctions() {
        val data = "Some test data".encodeToByteArray()
        val key = deriveKeyFromString("Test key")
        assertArrayEquals(data, decrypt(encrypt(data, key), key))
    }

    @Test
    fun helperCryptoFunctionsIncorrectKey() {
        val data = "Some test data".encodeToByteArray()
        val key = deriveKeyFromString("Test key")
        val incorrectKey = deriveKeyFromString("Incorrect key")
        val exception = assertThrows(DCDecryptionError::class.java) {
            decrypt(encrypt(data, key), incorrectKey)
        }
        assert(exception.message?.isNotEmpty() ?: false)
    }
}
