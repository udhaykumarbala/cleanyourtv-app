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
 * Derived from dadb 2.0.0 (Constants.kt, AdbMessage.kt, AdbReader.kt, AdbWriter.kt); see NOTICE.
 * Changes: every incoming length is bounded per command (SECURITY N7), the checksum is verified,
 * the magic word is verified, unknown commands are refused, and the reader works on the
 * suspending AdbSocket instead of an okio source.
 */

package tv.cleanyour.adb.wire

import tv.cleanyour.core.model.AdbError
import tv.cleanyour.core.model.AdbException

// The ADB wire protocol constants (AOSP packages/modules/adb/adb.h).
object AdbProtocol {
    const val A_SYNC = 0x434e5953
    const val A_CNXN = 0x4e584e43
    const val A_AUTH = 0x48545541
    const val A_OPEN = 0x4e45504f
    const val A_OKAY = 0x59414b4f
    const val A_CLSE = 0x45534c43
    const val A_WRTE = 0x45545257
    const val A_STLS = 0x534c5453

    const val AUTH_TOKEN = 1
    const val AUTH_SIGNATURE = 2
    const val AUTH_RSAPUBLICKEY = 3

    // We speak 0x01000000 (dadb's choice, proven on the Mi TV in spike S2): adbd then computes and
    // verifies checksums both ways, and so do we.
    const val VERSION = 0x01000000
    const val STLS_VERSION = 0x01000000

    const val HEADER_BYTES = 24

    // What we advertise in CNXN and the most we ever read for one WRTE frame (ARCHITECTURE 3.7).
    const val MAX_PAYLOAD = 1024 * 1024

    // SECURITY N7: control messages are bounded at 256 KiB (an OPEN carries at most a 131000
    // character shell line), data frames at MAX_PAYLOAD, and OKAY, CLSE, STLS carry nothing.
    const val MAX_CONTROL_PAYLOAD = 256 * 1024

    // A peer that claims a smaller window than this is not an adbd we know.
    const val MIN_PEER_PAYLOAD = 4096

    const val TOKEN_BYTES = 20
    const val RSA_2048_SIGNATURE_BYTES = 256

    val CONNECT_PAYLOAD: ByteArray = "host::\u0000".toByteArray(Charsets.US_ASCII)

    fun maxPayloadFor(command: Int): Int = when (command) {
        A_WRTE -> MAX_PAYLOAD
        A_CNXN, A_AUTH, A_OPEN -> MAX_CONTROL_PAYLOAD
        A_OKAY, A_CLSE, A_STLS, A_SYNC -> 0
        else -> -1
    }

    fun name(command: Int): String = when (command) {
        A_SYNC -> "SYNC"; A_CNXN -> "CNXN"; A_AUTH -> "AUTH"; A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"; A_CLSE -> "CLSE"; A_WRTE -> "WRTE"; A_STLS -> "STLS"
        else -> "0x%08x".format(command)
    }
}

// One decoded frame. The payload array is exactly payloadLength long.
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray) {
    override fun toString(): String = "${AdbProtocol.name(command)}[0x%x, 0x%x] payload[${payload.size}]".format(arg0, arg1)
}

// The 24-byte header, parsed and bounds-checked before any payload byte is read.
class AdbHeader(val command: Int, val arg0: Int, val arg1: Int, val payloadLength: Int, val checksum: Int)

fun protocolError(detail: String): AdbException = AdbException(AdbError.Transport.Protocol(detail))

// Issues #325, #620: the phone's ADB key could not answer the TV's AUTH token (a Keystore error, say).
// The detail is this fixed text; the signer's own exception is the cause, never part of the text.
const val SIGNER_FAILED = "the phone's key could not sign"

fun signerFailed(cause: Throwable): AdbException = AdbException(AdbError.Transport.Protocol(SIGNER_FAILED)).also { it.initCause(cause) }

// Encoder and bounded decoder shared by the client (:adb:wire) and the fake adbd.
object FrameCodec {

    fun checksum(payload: ByteArray, off: Int = 0, len: Int = payload.size): Int {
        var sum = 0
        for (i in off until off + len) sum += payload[i].toInt() and 0xff
        return sum
    }

    fun encode(command: Int, arg0: Int, arg1: Int, payload: ByteArray = EMPTY, off: Int = 0, len: Int = payload.size): ByteArray {
        require(len >= 0 && off >= 0 && off + len <= payload.size)
        val frame = ByteArray(AdbProtocol.HEADER_BYTES + len)
        putIntLe(frame, 0, command)
        putIntLe(frame, 4, arg0)
        putIntLe(frame, 8, arg1)
        putIntLe(frame, 12, len)
        putIntLe(frame, 16, if (len == 0) 0 else checksum(payload, off, len))
        putIntLe(frame, 20, command xor -1)
        System.arraycopy(payload, off, frame, AdbProtocol.HEADER_BYTES, len)
        return frame
    }

    // Throws AdbException(Transport.Protocol) for a bad magic, an unknown command or a length over
    // the command's bound. Nothing is allocated for the payload before this passes.
    fun parseHeader(h: ByteArray, off: Int = 0): AdbHeader {
        require(h.size - off >= AdbProtocol.HEADER_BYTES)
        val command = getIntLe(h, off)
        val arg0 = getIntLe(h, off + 4)
        val arg1 = getIntLe(h, off + 8)
        val length = getIntLe(h, off + 12)
        val checksum = getIntLe(h, off + 16)
        val magic = getIntLe(h, off + 20)
        if (magic != (command xor -1)) throw protocolError("bad magic for ${AdbProtocol.name(command)}")
        val max = AdbProtocol.maxPayloadFor(command)
        if (max < 0) throw protocolError("unknown command ${AdbProtocol.name(command)}")
        if (length < 0 || length > max) throw protocolError("${AdbProtocol.name(command)} payload $length exceeds $max")
        return AdbHeader(command, arg0, arg1, length, checksum)
    }

    // Reads one frame. Returns null on a clean end of stream before the first header byte; a
    // truncated frame, a bad header or a checksum mismatch is a protocol error.
    suspend fun readMessage(socket: AdbSocket): AdbMessage? {
        val header = ByteArray(AdbProtocol.HEADER_BYTES)
        val got = readFully(socket, header, allowEmpty = true)
        if (got == 0) return null
        if (got < header.size) throw protocolError("truncated header")
        val h = parseHeader(header)
        val payload = if (h.payloadLength == 0) EMPTY else ByteArray(h.payloadLength)
        if (payload.isNotEmpty() && readFully(socket, payload, allowEmpty = false) < payload.size) throw protocolError("truncated payload")
        val sum = if (payload.isEmpty()) 0 else checksum(payload)
        if (sum != h.checksum) throw protocolError("checksum mismatch on ${AdbProtocol.name(h.command)}")
        return AdbMessage(h.command, h.arg0, h.arg1, payload)
    }

    // Fills buf or stops at end of stream; returns the number of bytes read.
    private suspend fun readFully(socket: AdbSocket, buf: ByteArray, allowEmpty: Boolean): Int {
        var pos = 0
        while (pos < buf.size) {
            val n = socket.read(buf, pos, buf.size - pos)
            if (n < 0) return pos
            if (n == 0) continue
            pos += n
        }
        return pos
    }

    val EMPTY = ByteArray(0)

    fun putIntLe(b: ByteArray, off: Int, v: Int) {
        b[off] = v.toByte(); b[off + 1] = (v ushr 8).toByte(); b[off + 2] = (v ushr 16).toByte(); b[off + 3] = (v ushr 24).toByte()
    }

    fun getIntLe(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or ((b[off + 3].toInt() and 0xff) shl 24)
}
