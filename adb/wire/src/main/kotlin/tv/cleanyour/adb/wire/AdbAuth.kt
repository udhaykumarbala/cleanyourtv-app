/*
 * Copyright (c) 2021 mobile.dev inc.
 * Copyright (c) 2026 the Clean Your TV authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * The public key struct encoder is derived from dadb 2.0.0 AdbKeyPair.kt (itself after AdbLib's
 * AdbCrypto and adb_host_auth.c); see NOTICE. Changes: a decoder for the fake adbd, the D20
 * comment, and signing through the JCA NONEwithRSA over the SHA-1 DigestInfo and the token
 * instead of a raw RSA cipher over a block the caller supplies (no general signing oracle).
 */

package tv.cleanyour.adb.wire

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

// adbd's RSAPublicKey struct: len (words), n0inv, n[64], rr[64], exponent; all little-endian
// 32-bit words. adbd stores it base64 followed by a space and a comment in adb_keys and shows its
// MD5 as the fingerprint in the Allow dialog.
object AdbPublicKeyCodec {
    const val COMMENT = "cleanyourtv"          // D20: no identifier in the TV's adb_keys
    const val KEY_BITS = 2048
    private const val WORDS = KEY_BITS / 32
    const val STRUCT_BYTES = 4 + 4 + WORDS * 4 * 2 + 4   // 524

    fun encode(key: RSAPublicKey): ByteArray {
        require(key.modulus.bitLength() == KEY_BITS) { "adb keys are RSA-2048" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        var n = key.modulus
        val r = BigInteger.ONE.shiftLeft(WORDS * 32)
        var rr = r.modPow(BigInteger.valueOf(2), n)
        val n0inv = n.mod(r32).modInverse(r32)
        val myN = IntArray(WORDS)
        val myRr = IntArray(WORDS)
        for (i in 0 until WORDS) {
            var res = rr.divideAndRemainder(r32)
            rr = res[0]
            myRr[i] = res[1].toInt()
            res = n.divideAndRemainder(r32)
            n = res[0]
            myN[i] = res[1].toInt()
        }
        val buf = ByteBuffer.allocate(STRUCT_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(WORDS)
        buf.putInt(n0inv.negate().toInt())
        for (w in myN) buf.putInt(w)
        for (w in myRr) buf.putInt(w)
        buf.putInt(key.publicExponent.toInt())
        return buf.array()
    }

    // Rebuilds the JCA key from the struct; the redundant fields (n0inv, rr) are checked.
    fun decode(struct: ByteArray): RSAPublicKey {
        require(struct.size == STRUCT_BYTES) { "adb public key struct is $STRUCT_BYTES bytes, got ${struct.size}" }
        val buf = ByteBuffer.wrap(struct).order(ByteOrder.LITTLE_ENDIAN)
        require(buf.getInt() == WORDS) { "adb public key is not 2048 bits" }
        val n0inv = buf.getInt()
        var n = BigInteger.ZERO
        for (i in 0 until WORDS) n = n.or(BigInteger.valueOf(buf.getInt().toLong() and 0xffffffffL).shiftLeft(32 * i))
        var rr = BigInteger.ZERO
        for (i in 0 until WORDS) rr = rr.or(BigInteger.valueOf(buf.getInt().toLong() and 0xffffffffL).shiftLeft(32 * i))
        val e = buf.getInt()
        require(n.bitLength() == KEY_BITS && n.testBit(0)) { "adb public key modulus is malformed" }
        require(e == 3 || e == 65537) { "adb public key exponent is malformed" }
        val r32 = BigInteger.ONE.shiftLeft(32)
        require(n.mod(r32).modInverse(r32).negate().toInt() == n0inv) { "adb public key n0inv does not match" }
        require(BigInteger.ONE.shiftLeft(WORDS * 32).modPow(BigInteger.valueOf(2), n) == rr) { "adb public key rr does not match" }
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, BigInteger.valueOf(e.toLong()))) as RSAPublicKey
    }

    fun line(key: RSAPublicKey, comment: String = COMMENT): String =
        Base64.getEncoder().encodeToString(encode(key)) + " " + comment

    // The base64 part is everything before the first space (or the whole line).
    fun parseLine(line: String): RSAPublicKey {
        val b64 = line.trimEnd('\u0000', '\n', '\r').substringBefore(' ')
        require(b64.length <= 1024) { "adb public key line too long" }
        return decode(Base64.getDecoder().decode(b64))
    }

    fun fingerprint(key: RSAPublicKey): String =
        MessageDigest.getInstance("MD5").digest(encode(key)).joinToString(":") { "%02X".format(it) }
}

// adbd verifies RSA_verify(NID_sha1, token, 20, sig): PKCS#1 v1.5 over DigestInfo(SHA-1) || token.
object AdbSignature {
    val SHA1_DIGEST_INFO: ByteArray = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )
    const val SIGNED_INPUT_BYTES = 35

    fun input(token: AuthToken): ByteArray {
        require(token.bytes.size == AdbProtocol.TOKEN_BYTES) { "adb tokens are 20 bytes" }
        return SHA1_DIGEST_INFO + token.bytes
    }

    // JCA NONEwithRSA adds the PKCS#1 type 1 padding, exactly what RSA_sign does after the DigestInfo.
    fun sign(privateKey: PrivateKey, token: AuthToken, provider: String? = null): ByteArray {
        val sig = if (provider == null) Signature.getInstance("NONEwithRSA") else Signature.getInstance("NONEwithRSA", provider)
        sig.initSign(privateKey)
        sig.update(input(token))
        return sig.sign()
    }

    fun verify(publicKey: RSAPublicKey, token: ByteArray, signature: ByteArray): Boolean {
        if (token.size != AdbProtocol.TOKEN_BYTES) return false
        if (signature.size != AdbProtocol.RSA_2048_SIGNATURE_BYTES) return false
        return try {
            Signature.getInstance("NONEwithRSA").run {
                initVerify(publicKey)
                update(SHA1_DIGEST_INFO + token)
                verify(signature)
            }
        } catch (e: Exception) {
            false
        }
    }
}

sealed interface SignerSelfTest {
    data object Pass : SignerSelfTest
    data class Fail(val reason: String) : SignerSelfTest
}

// The self-test every signer must pass before its key is used (R2): a random token signed by the
// signer verifies under the key it advertises, the line carries the D20 comment and the struct
// decodes, the fingerprint matches, and the 20-byte rule holds at the type level.
suspend fun AdbSigner.selfTest(): SignerSelfTest {
    val publicKey = try {
        AdbPublicKeyCodec.parseLine(publicKeyLine)
    } catch (e: Exception) {
        return SignerSelfTest.Fail("public key line does not decode: ${e.message}")
    }
    if (!publicKeyLine.endsWith(" " + AdbPublicKeyCodec.COMMENT)) return SignerSelfTest.Fail("public key comment is not ${AdbPublicKeyCodec.COMMENT}")
    if (fingerprint != AdbPublicKeyCodec.fingerprint(publicKey)) return SignerSelfTest.Fail("fingerprint does not match the public key")
    val token = ByteArray(AdbProtocol.TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
    val signature = try {
        sign(AuthToken(token))
    } catch (e: Exception) {
        return SignerSelfTest.Fail("signing failed: ${e.javaClass.simpleName}")
    }
    if (signature.size != AdbProtocol.RSA_2048_SIGNATURE_BYTES) return SignerSelfTest.Fail("signature is ${signature.size} bytes")
    if (!AdbSignature.verify(publicKey, token, signature)) return SignerSelfTest.Fail("signature does not verify as PKCS#1 v1.5 over DigestInfo(SHA-1) and the token")
    val wrongSize = runCatching { AuthToken(ByteArray(21)) }.isSuccess || runCatching { AuthToken(ByteArray(19)) }.isSuccess
    if (wrongSize) return SignerSelfTest.Fail("AuthToken accepts a size other than 20")
    return SignerSelfTest.Pass
}

// A software RSA-2048 signer in the real signature format. The engine behind the wrapped
// software fallback in :android:platform and the signer tests use on the JVM.
class SoftwareRsaAdbSigner(
    keyPair: KeyPair,
    override val backing: KeyBacking = KeyBacking.WRAPPED_SOFTWARE,
    private val onReset: suspend (KeyPair) -> Unit = {},
) : AdbSigner {
    @Volatile var keyPair: KeyPair = keyPair
        private set

    private val publicKey: RSAPublicKey get() = keyPair.public as RSAPublicKey
    override val publicKeyLine: String get() = AdbPublicKeyCodec.line(publicKey)
    override val fingerprint: String get() = AdbPublicKeyCodec.fingerprint(publicKey)

    override suspend fun sign(token: AuthToken): ByteArray {
        require(token.bytes.size == AdbProtocol.TOKEN_BYTES) { "adb tokens are 20 bytes" }
        return AdbSignature.sign(keyPair.private, token)
    }

    override suspend fun reset() {
        keyPair = generateKeyPair()
        onReset(keyPair)
    }

    companion object {
        fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(AdbPublicKeyCodec.KEY_BITS) }.generateKeyPair()
    }
}
