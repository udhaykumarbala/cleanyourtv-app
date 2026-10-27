package tv.cleanyour.buildlogic.apifixture

import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.math.BigInteger

// Compiled classes the AndroidApiCheckTest feeds to the checker. Each uses one platform member.

object UsesTwo {
    fun two(): BigInteger = BigInteger.TWO
}

object UsesValueOf {
    fun two(): BigInteger = BigInteger.valueOf(2)
}

object UsesListOf {
    fun list(): List<Int> = java.util.List.of(1, 2)
}

object UsesHttpClient {
    fun client(): Any = java.net.http.HttpClient.newHttpClient()
}

// A member inherited from the platform through our own subclass.
class OwnStream : InputStream() {
    override fun read(): Int = -1
    fun everything(): ByteArray = readAllBytes()
}

object UsesOwnStream {
    fun everything(): ByteArray = OwnStream().everything()
}

object UsesLambdaAndConcat {
    fun run(n: Int): String {
        val f = { x: Int -> "n=$x" }
        return f(n) + " done $n"
    }
}

// javax.xml.stream (StAX) is in the JDK but not in Android.
object UsesStax {
    fun factory(): Any = javax.xml.stream.XMLInputFactory.newInstance()
}

// A PrintStream member Android lacks, reached through our own subclass.
class OwnPrintStream(out: OutputStream) : PrintStream(out) {
    fun raw(b: ByteArray) = writeBytes(b)
}
