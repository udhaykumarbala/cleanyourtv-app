package tv.cleanyour.core.probe

import tv.cleanyour.core.model.Sha256
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

// The signer of an installed APK, as PinnedLauncher.SIGNER_SHA256 names one: the SHA-256 of the
// first signer's certificate (DER) in the APK Signing Block. The TV's package manager verified the
// signatures when it installed the APK, and `pm path` names that installed file, so reading the
// certificate is enough here, provided it is one the TV verified (RF-LAUNCHER-240 F2): a v2 block
// is required, since every Android the app supports verifies v2 when it is there, and a v3 block,
// which Android 8 ignores and v2 does not cover, must name the same first certificate. Anything else
// (v1 only, v3 only, v2 and v3 disagreeing, a malformed block) gives null, which the planner treats
// as "signer unknown". Our keys have no rotation, so our APKs read the same either way. The parser is bounded by the buffer: every
// length is checked before it is used, so a hostile file can only make it return null.
object ApkSigners {
    private const val EOCD_MAGIC = 0x06054b50
    private const val V2_BLOCK_ID = 0x7109871a
    private const val V3_BLOCK_ID = 0xf05368c0.toInt()
    private val SIG_BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    fun signerSha256(apk: ByteArray): Sha256? = try {
        certificate(apk)?.let { cert -> Sha256(MessageDigest.getInstance("SHA-256").digest(cert).joinToString("") { "%02x".format(it) }) }
    } catch (_: RuntimeException) {
        null
    }

    // The first signer's first certificate, DER.
    internal fun certificate(apk: ByteArray): ByteArray? {
        val b = ByteBuffer.wrap(apk).order(ByteOrder.LITTLE_ENDIAN)
        val eocd = findEocd(b) ?: return null
        val cdOffset = b.getInt(eocd + 16).toLong() and 0xffffffffL
        if (cdOffset < 32 || cdOffset > eocd) return null
        val magicAt = (cdOffset - 16).toInt()
        if (!apk.copyOfRange(magicAt, magicAt + 16).contentEquals(SIG_BLOCK_MAGIC)) return null
        val footerSize = b.getLong(magicAt - 8)
        if (footerSize < 24 || footerSize > cdOffset - 8) return null
        val start = (cdOffset - footerSize - 8).toInt()
        if (b.getLong(start) != footerSize) return null
        // ID-value pairs between the header size and the footer: uint64 length, uint32 id, value.
        val pairs = HashMap<Int, ByteBuffer>()
        var p = start + 8
        val end = magicAt - 8
        while (p < end) {
            if (end - p < 12) return null
            val len = b.getLong(p)
            if (len < 4 || len > end - p - 8) return null
            val id = b.getInt(p + 8)
            pairs.putIfAbsent(id, slice(b, p + 12, (len - 4).toInt()))
            p += 8 + len.toInt()
        }
        val v2 = firstCertificate(pairs[V2_BLOCK_ID] ?: return null)
        val v3 = pairs[V3_BLOCK_ID]?.let { firstCertificate(it) }
        if (v3 != null && !v3.contentEquals(v2)) return null
        return v2
    }

    // Both schemes: a length-prefixed sequence of length-prefixed signers; a signer starts with its
    // length-prefixed signed data, which holds the digests and then the certificates.
    private fun firstCertificate(block: ByteBuffer): ByteArray {
        val b = block.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        val signers = prefixed(b)
        val signer = prefixed(signers)
        val signedData = prefixed(signer)
        prefixed(signedData)                       // digests
        val certificates = prefixed(signedData)
        val cert = prefixed(certificates)
        return ByteArray(cert.remaining()).also { cert.get(it) }
    }

    private fun findEocd(b: ByteBuffer): Int? {
        val last = b.capacity() - 22
        if (last < 0) return null
        val first = maxOf(0, last - 0xffff)
        for (i in last downTo first) {
            if (b.getInt(i) == EOCD_MAGIC && (b.getShort(i + 20).toInt() and 0xffff) == last - i) return i
        }
        return null
    }

    private fun slice(b: ByteBuffer, at: Int, len: Int): ByteBuffer {
        require(at >= 0 && len >= 0 && at.toLong() + len <= b.capacity())
        val d = b.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        // Through Buffer: JDK 9's ByteBuffer.position(int) and limit(int) overloads are not in Android's API.
        (d as Buffer).position(at).limit(at + len)
        return d.slice().order(ByteOrder.LITTLE_ENDIAN)
    }

    // The next uint32-length-prefixed field of `b`, advancing b past it.
    private fun prefixed(b: ByteBuffer): ByteBuffer {
        require(b.remaining() >= 4)
        val len = b.int
        require(len >= 0 && len <= b.remaining())
        val out = slice(b, b.position(), len)
        (b as Buffer).position(b.position() + len)
        return out
    }
}
