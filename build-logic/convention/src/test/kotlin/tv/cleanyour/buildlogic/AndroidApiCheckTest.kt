package tv.cleanyour.buildlogic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// The checkAndroidApi rules on real compiled classes (apifixture) against a small api-versions.xml
// in the SDK's own format. The real build runs the same checker on every shipped JVM module.
class AndroidApiCheckTest {

    private val xml = """
        <?xml version="1.0" encoding="utf-8"?>
        <api version="4">
            <class name="java/lang/Object" since="1">
                <method name="&lt;init>()V"/>
            </class>
            <class name="java/lang/Number" since="1">
                <extends name="java/lang/Object"/>
            </class>
            <class name="java/math/BigInteger" since="1">
                <extends name="java/lang/Number"/>
                <method name="valueOf(J)Ljava/math/BigInteger;"/>
                <method name="sqrt()Ljava/math/BigInteger;" since="33"/>
                <field name="ONE"/>
                <field name="TWO" since="33"/>
            </class>
            <class name="java/util/List" since="1">
                <extends name="java/lang/Object"/>
                <method name="of(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;" since="30"/>
            </class>
            <class name="java/io/OutputStream" since="1">
                <extends name="java/lang/Object"/>
                <method name="&lt;init>()V"/>
            </class>
            <class name="java/io/FilterOutputStream" since="1">
                <extends name="java/io/OutputStream"/>
                <method name="&lt;init>(Ljava/io/OutputStream;)V"/>
            </class>
            <class name="java/io/PrintStream" since="1">
                <extends name="java/io/FilterOutputStream"/>
                <method name="&lt;init>(Ljava/io/OutputStream;)V"/>
                <method name="write([BII)V"/>
            </class>
            <class name="java/io/InputStream" since="1">
                <extends name="java/lang/Object"/>
                <method name="&lt;init>()V"/>
                <method name="read()I"/>
                <method name="readAllBytes()[B" since="33"/>
            </class>
        </api>
    """.trimIndent()

    private val db = ApiDatabase.parse(xml.byteInputStream())

    private fun info(simple: String): ClassInfo? {
        val path = "tv/cleanyour/buildlogic/apifixture/$simple.class"
        return javaClass.classLoader.getResourceAsStream(path)?.use { ClassFileReader.read(it.readBytes()) }
    }

    private val checker = AndroidApiChecker(db, 28) { n -> info(n.substringAfterLast('/')) }

    private fun problems(simple: String) = checker.check(info(simple)!!)
        // The fixture classes also use Kotlin's runtime and these plain java.lang classes, which
        // the small database leaves out on purpose.
        .filterNot { "java.lang.String" in it || "java.lang.Integer" in it || "kotlin" in it.substringAfter(" uses ") }

    @Test
    fun `BigInteger TWO is API 33 and is reported`() {
        val p = problems("UsesTwo")
        assertEquals(1, p.size)
        assertTrue(p.single(), "java.math.BigInteger.TWO, which Android has only from API 33" in p.single())
    }

    @Test
    fun `BigInteger valueOf passes`() {
        assertEquals(emptyList<String>(), problems("UsesValueOf"))
    }

    @Test
    fun `List of is API 30 and is reported with its descriptor`() {
        val p = problems("UsesListOf")
        assertTrue(p.toString(), p.any { "java.util.List.of(Ljava/lang/Object;Ljava/lang/Object;)Ljava/util/List;, which Android has only from API 30" in it })
    }

    @Test
    fun `a java class Android does not have is reported`() {
        val p = problems("UsesHttpClient")
        assertTrue(p.toString(), p.any { "java.net.http.HttpClient, which Android does not have" in it })
    }

    @Test
    fun `a platform member reached through our own subclass is reported`() {
        val p = problems("OwnStream")
        assertTrue(p.toString(), p.any { "readAllBytes()[B, which Android has only from API 33" in it })
        // The caller names our class, whose own method is fine.
        assertEquals(emptyList<String>(), problems("UsesOwnStream"))
    }

    @Test
    fun `a javax class Android does not have is reported`() {
        val p = problems("UsesStax")
        assertTrue(p.toString(), p.any { "javax.xml.stream.XMLInputFactory, which Android does not have" in it })
        assertTrue(p.toString(), p.any { "javax.xml.stream.XMLInputFactory.newInstance()Ljavax/xml/stream/XMLInputFactory;, which Android does not have" in it })
    }

    @Test
    fun `a member the platform chain lacks, reached through our own subclass, is reported`() {
        val p = problems("OwnPrintStream")
        assertTrue(p.toString(), p.any { "OwnPrintStream.writeBytes([B)V, which Android does not have" in it })
        // The constructor the platform has passes.
        assertTrue(p.toString(), p.none { "<init>" in it })
    }

    @Test
    fun `a platform-named class a dependency provides is not the platform`() {
        val dep = ClassInfo("javax/inject/Provider", "java/lang/Object", emptyList(), emptySet(), setOf("get()Ljava/lang/Object;"), emptySet(), emptyList())
        val user = ClassInfo("tv/cleanyour/x/User", "java/lang/Object", emptyList(), emptySet(), emptySet(),
            setOf("javax/inject/Provider"), listOf(MemberRef("javax/inject/Provider", "get", "()Ljava/lang/Object;", false)))
        val withDep = AndroidApiChecker(db, 28) { n -> if (n == dep.name) dep else null }
        assertEquals(emptyList<String>(), withDep.check(user))
        val without = AndroidApiChecker(db, 28) { null }
        assertTrue(without.check(user).any { "javax.inject.Provider, which Android does not have" in it })
    }

    @Test
    fun `lambda and string concatenation bootstraps are left to the dexer`() {
        assertEquals(emptyList<String>(), problems("UsesLambdaAndConcat").filter { "java.lang.invoke" in it || "java.lang.runtime" in it })
    }

    @Test
    fun `a higher minSdk accepts what it has`() {
        val at33 = AndroidApiChecker(db, 33) { n -> info(n.substringAfterLast('/')) }
        assertTrue(at33.check(info("UsesTwo")!!).none { "BigInteger.TWO" in it })
    }

    @Test
    fun `api versions file is found under the compile SDK platform`() {
        val sdk = File(System.getProperty("java.io.tmpdir"), "cyt-api-sdk-${System.nanoTime()}")
        val f = File(sdk, "platforms/android-37.0/data/api-versions.xml")
        f.parentFile.mkdirs()
        f.writeText(xml)
        File(sdk, "platforms/android-35/data").mkdirs()
        assertEquals(f, AndroidApiChecker.apiVersionsIn(sdk, 37))
        sdk.deleteRecursively()
    }
}
