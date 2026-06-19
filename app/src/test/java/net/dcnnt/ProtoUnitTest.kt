package net.dcnnt

import net.dcnnt.core.DCHandshakeError
import net.dcnnt.core.DCUnknownEncryptionMethod
import net.dcnnt.core.Handshake
import net.dcnnt.core.Uin
import net.dcnnt.core.ZeroEncryption
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProtoUnitTest {
    @Test
    fun handshakeSerializeDeserialize() {
        val plg = "test".toByteArray()
        val h = Handshake(0u, ZeroEncryption.CODE.toULong(), 1, 2, plg)
        val a = h.toByteArray()
        assertArrayEquals(a, Handshake.fromByteArray(a).toByteArray())
    }

    @Test
    fun handshakeIncorrectEnc() {
        val plg = "test".toByteArray()
        val exception = assertThrows(DCUnknownEncryptionMethod::class.java) {
            Handshake(0u, 12345u, 1, 2, plg)
        }
        assert(exception.message?.isNotEmpty() ?: false)
    }

    @Test
    fun handshakeDeserializeShort() {
        val exception = assertThrows(DCHandshakeError::class.java) {
            Handshake.fromByteArray(byteArrayOf(0, 0, 0))
        }
        assert(exception.message?.isNotEmpty() ?: false)
    }

    @Test
    fun handshakeDeserializePlgShort() {
        val plg = "test".toByteArray()
        val h = Handshake(0u, ZeroEncryption.CODE.toULong(), 1, 2, plg)
        val a = h.toByteArray()
        val exception = assertThrows(DCHandshakeError::class.java) {
            Handshake.fromByteArray(a.sliceArray(0 .. (a.size - 2)))
        }
        assert(exception.message?.isNotEmpty() ?: false)
    }
}
