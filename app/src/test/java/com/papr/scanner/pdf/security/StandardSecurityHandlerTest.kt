package com.papr.scanner.pdf.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fixture values were produced by qpdf (via pikepdf) - a mature, independent
 * reference implementation - encrypting a blank PDF with owner password "",
 * user password "userpw", Revision 3 / 128-bit RC4, and the given /ID and
 * /P. This cross-checks our from-scratch implementation against real-world
 * PDF tooling rather than only against itself.
 */
class StandardSecurityHandlerTest {

    private val handler = StandardSecurityHandler()

    private val documentId = hexToBytes("157963eb48b5b2c97b4e640a561b9130")
    private val permissions = -4
    private val expectedOwnerValue = hexToBytes("f44711ab7bd5ba08b80e0548a35f7566ddc76e1dc10d6fd13b51c801baba5e89")

    // Only the first 16 bytes of a Revision-3 /U are spec-meaningful (see
    // StandardSecurityHandler.computeUserValue); the qpdf fixture's trailing
    // bytes are its own implementation-specific padding choice, not ours.
    private val expectedUserValuePrefix = hexToBytes("0c6848325a257c28682c78f27b5b3499")

    @Test
    fun `computes owner value matching qpdf reference`() {
        val ownerValue = handler.computeOwnerValue(ownerPassword = "", userPassword = "userpw")

        assertArrayEquals(expectedOwnerValue, ownerValue)
    }

    @Test
    fun `computes user value matching qpdf reference in its spec-meaningful bytes`() {
        val fileKey = handler.computeFileKey(
            userPassword = "userpw",
            ownerValue = expectedOwnerValue,
            permissions = permissions,
            documentId = documentId,
        )
        val userValue = handler.computeUserValue(fileKey, documentId)

        assertEquals(32, userValue.size)
        assertArrayEquals(expectedUserValuePrefix, userValue.copyOf(16))
    }

    @Test
    fun `object key is deterministic and varies by object number`() {
        val fileKey = ByteArray(16) { it.toByte() }

        val key1 = handler.computeObjectKey(fileKey, objectNumber = 4, generation = 0)
        val key1Again = handler.computeObjectKey(fileKey, objectNumber = 4, generation = 0)
        val key2 = handler.computeObjectKey(fileKey, objectNumber = 5, generation = 0)

        assertArrayEquals(key1, key1Again)
        assert(!key1.contentEquals(key2)) { "expected different object numbers to derive different keys" }
        assertEquals(16, key1.size)
    }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
