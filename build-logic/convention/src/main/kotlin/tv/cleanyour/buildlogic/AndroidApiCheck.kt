package tv.cleanyour.buildlogic

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

// The Android API check for the plain JVM modules (core, adb). Their classes ship in the APK and run
// on the phone's ART, but Android lint's NewApi never sees them: they compile against JDK 21, so a
// Java API that Android only added after minSdk compiles, passes every JVM test and then throws
// NoSuchFieldError or NoSuchMethodError on an older phone (BigInteger.TWO, API 33, broke connect on
// Android 9 to 12). This reads the compiled classes' constant pools and checks every class, field
// and method they use from the platform (java, javax and every other package Android ships) against
// the SDK's own api-versions.xml, the same database lint uses.

/** A field or method another class uses, as the constant pool names it. */
data class MemberRef(val owner: String, val name: String, val desc: String, val isField: Boolean)

/** What the check needs from one class file. Fields are keyed by name, methods by name and descriptor. */
class ClassInfo(
    val name: String,
    val superName: String?,
    val interfaces: List<String>,
    val fields: Set<String>,
    val methods: Set<String>,
    val classRefs: Set<String>,
    val memberRefs: List<MemberRef>,
)

object ClassFileReader {
    fun read(bytes: ByteArray): ClassInfo {
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        require(inp.readInt() == 0xCAFEBABE.toInt()) { "not a class file" }
        inp.readUnsignedShort(); inp.readUnsignedShort()
        val count = inp.readUnsignedShort()
        val tags = IntArray(count)
        val utf = arrayOfNulls<String>(count)
        val a = IntArray(count)
        val b = IntArray(count)
        var i = 1
        while (i < count) {
            val tag = inp.readUnsignedByte()
            tags[i] = tag
            when (tag) {
                1 -> utf[i] = inp.readUTF()
                3, 4 -> inp.readInt()
                5, 6 -> { inp.readLong(); i++ }
                7, 8, 16, 19, 20 -> a[i] = inp.readUnsignedShort()
                9, 10, 11, 12, 17, 18 -> { a[i] = inp.readUnsignedShort(); b[i] = inp.readUnsignedShort() }
                15 -> { inp.readUnsignedByte(); a[i] = inp.readUnsignedShort() }
                else -> error("constant pool tag $tag")
            }
            i++
        }
        fun className(idx: Int): String? = if (idx == 0) null else utf[a[idx]]
        inp.readUnsignedShort()
        val self = className(inp.readUnsignedShort())!!
        val superName = className(inp.readUnsignedShort())
        val interfaces = List(inp.readUnsignedShort()) { className(inp.readUnsignedShort())!! }
        fun members(keyOf: (String, String) -> String): Set<String> {
            val out = HashSet<String>()
            repeat(inp.readUnsignedShort()) {
                inp.readUnsignedShort()
                val name = utf[inp.readUnsignedShort()]!!
                val desc = utf[inp.readUnsignedShort()]!!
                out += keyOf(name, desc)
                repeat(inp.readUnsignedShort()) {
                    inp.readUnsignedShort()
                    inp.skipNBytesCompat(inp.readInt().toLong() and 0xFFFFFFFFL)
                }
            }
            return out
        }
        val fields = members { n, _ -> n }
        val methods = members { n, d -> n + d }
        val classRefs = HashSet<String>()
        val memberRefs = ArrayList<MemberRef>()
        for (k in 1 until count) {
            when (tags[k]) {
                7 -> classRefs += utf[a[k]]!!
                9, 10, 11 -> {
                    val nat = b[k]
                    memberRefs += MemberRef(className(a[k])!!, utf[a[nat]]!!, utf[b[nat]]!!, tags[k] == 9)
                }
            }
        }
        return ClassInfo(self, superName, interfaces, fields, methods, classRefs, memberRefs)
    }

    private fun DataInputStream.skipNBytesCompat(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { readByte(); left-- } else left -= s
        }
    }
}

class ApiMember(val since: Int, val removed: Int?)

class ApiClass(
    val name: String,
    val since: Int,
    val removed: Int?,
    val supers: List<String>,
    val fields: Map<String, ApiMember>,
    val methods: Map<String, ApiMember>,
)

/** The platform API as the SDK's data/api-versions.xml lists it: every class and member with the API it arrived in. */
class ApiDatabase(val classes: Map<String, ApiClass>) {
    val packages: Set<String> = classes.keys.mapTo(HashSet()) { it.substringBeforeLast('/', "") }

    companion object {
        fun parse(input: InputStream): ApiDatabase {
            val factory = XMLInputFactory.newInstance()
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
            val r = factory.createXMLStreamReader(input)
            val out = HashMap<String, ApiClass>()
            var name: String? = null
            var since = 1
            var removed: Int? = null
            var supers = ArrayList<String>()
            var fields = HashMap<String, ApiMember>()
            var methods = HashMap<String, ApiMember>()
            fun level(attr: String?): Int? = attr?.substringBefore('.')?.toIntOrNull()
            while (r.hasNext()) {
                when (r.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (r.localName) {
                        "class" -> {
                            name = r.getAttributeValue(null, "name")
                            since = level(r.getAttributeValue(null, "since")) ?: 1
                            removed = level(r.getAttributeValue(null, "removed"))
                            supers = ArrayList(); fields = HashMap(); methods = HashMap()
                        }
                        "extends", "implements" -> supers += r.getAttributeValue(null, "name")
                        "field", "method" -> if (name != null) {
                            val m = ApiMember(
                                level(r.getAttributeValue(null, "since")) ?: since,
                                level(r.getAttributeValue(null, "removed")),
                            )
                            val key = r.getAttributeValue(null, "name")
                            if (r.localName == "field") fields[key] = m else methods[key] = m
                        }
                    }
                    XMLStreamConstants.END_ELEMENT -> if (r.localName == "class" && name != null) {
                        out[name!!] = ApiClass(name!!, since, removed, supers, fields, methods)
                        name = null
                    }
                }
            }
            r.close()
            return ApiDatabase(out)
        }
    }
}

/**
 * Checks classes against the platform API at [minSdk]. [lookup] finds a non-platform class (the
 * module itself, its dependencies) so a member inherited from a platform class through our own
 * subclass is checked too.
 */
class AndroidApiChecker(
    private val db: ApiDatabase,
    private val minSdk: Int,
    private val lookup: (String) -> ClassInfo?,
) {
    fun check(cls: ClassInfo): List<String> {
        val out = LinkedHashSet<String>()
        val who = cls.name.replace('/', '.')
        val used = cls.classRefs + listOfNotNull(cls.superName) + cls.interfaces
        for (raw in used) {
            val c = elementClass(raw) ?: continue
            if (c in DESUGARED || !isPlatform(c)) continue
            val api = db.classes[c]
            when {
                api == null -> out += "$who uses ${dotted(c)}, which Android does not have"
                api.since > minSdk -> out += "$who uses ${dotted(c)}, which Android has only from API ${api.since}"
                api.removed != null -> out += "$who uses ${dotted(c)}, which Android removed in API ${api.removed}"
            }
        }
        for (ref in cls.memberRefs) {
            if (ref.owner.startsWith("[") || ref.owner in DESUGARED) continue
            if (ref.owner == "java/lang/invoke/MethodHandle" || ref.owner == "java/lang/invoke/VarHandle") continue
            val what = "${dotted(ref.owner)}.${ref.name}" + if (ref.isField) "" else ref.desc
            when (val found = resolve(ref.owner, ref, HashSet())) {
                is Found -> when {
                    found.member.since > minSdk ->
                        out += "$who uses $what, which Android has only from API ${found.member.since}"
                    found.member.removed != null ->
                        out += "$who uses $what, which Android removed in API ${found.member.removed}"
                }
                Missing -> out += "$who uses $what, which Android does not have"
                Ours -> Unit
            }
        }
        return out.toList()
    }

    private sealed interface Resolution
    private class Found(val member: ApiMember) : Resolution
    private object Ours : Resolution
    private object Missing : Resolution

    private fun resolve(owner: String, ref: MemberRef, seen: MutableSet<String>): Resolution {
        if (!seen.add(owner)) return Missing
        val key = if (ref.isField) ref.name else ref.name + ref.desc
        val api = db.classes[owner]
        if (api != null) {
            (if (ref.isField) api.fields[key] else api.methods[key])?.let { return Found(it) }
            for (s in api.supers + OBJECT) {
                val r = resolve(s, ref, seen)
                if (r !is Missing) return r
            }
            return Missing
        }
        if (isPlatform(owner)) return Missing
        val ours = lookup(owner) ?: return Ours
        if (key in (if (ref.isField) ours.fields else ours.methods)) return Ours
        var reachedPlatform = false
        for (s in listOfNotNull(ours.superName) + ours.interfaces) {
            val r = resolve(s, ref, seen)
            if (r !is Missing) return r
            reachedPlatform = true
        }
        // The chain ended at the platform without the member: Android does not have it (our own
        // PrintStream subclass calling a JDK-only PrintStream method). Declared nowhere we can see
        // otherwise (a synthetic or a dependency we cannot read): not ours to judge.
        return if (reachedPlatform) Missing else Ours
    }

    // A platform class: one the database lists, or one in a JDK or Android package (java, javax,
    // jdk, sun, com.sun, org.ietf, org.omg, org.w3c, org.xml, or any package the database has) that no
    // dependency on the classpath provides (javax.inject from a library is not the platform).
    private fun isPlatform(c: String): Boolean {
        if (c in db.classes) return true
        val named = PLATFORM_PREFIXES.any { c.startsWith(it) } || c.substringBeforeLast('/', "") in db.packages
        return named && lookup(c) == null
    }

    private fun elementClass(raw: String): String? {
        if (!raw.startsWith("[")) return raw
        val e = raw.trimStart('[')
        return if (e.startsWith("L")) e.substring(1, e.length - 1) else null
    }

    private fun dotted(c: String) = c.replace('/', '.')

    companion object {
        private const val OBJECT = "java/lang/Object"
        private val PLATFORM_PREFIXES = listOf("java/", "javax/", "jdk/", "sun/", "com/sun/", "org/ietf/", "org/omg/", "org/w3c/", "org/xml/")

        // invokedynamic bootstraps the dexer (D8) rewrites at build time: lambdas, string
        // concatenation, record and switch helpers. They never run on the phone.
        val DESUGARED = setOf(
            "java/lang/invoke/LambdaMetafactory",
            "java/lang/invoke/StringConcatFactory",
            "java/lang/runtime/ObjectMethods",
            "java/lang/runtime/SwitchBootstraps",
        )

        /** Every class file in [roots] (directories or jars), by internal name. */
        fun classIndex(roots: Iterable<File>): Map<String, () -> ByteArray> {
            val out = HashMap<String, () -> ByteArray>()
            for (root in roots) {
                if (root.isDirectory) {
                    root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.forEach { f ->
                        val n = f.relativeTo(root).invariantSeparatorsPath.removeSuffix(".class")
                        out.putIfAbsent(n) { f.readBytes() }
                    }
                } else if (root.isFile && root.name.endsWith(".jar")) {
                    ZipFile(root).use { z ->
                        for (e in z.entries()) {
                            if (e.isDirectory || !e.name.endsWith(".class") || e.name.startsWith("META-INF/")) continue
                            val n = e.name.removeSuffix(".class")
                            out.putIfAbsent(n) { ZipFile(root).use { zz -> zz.getInputStream(zz.getEntry(e.name)).readBytes() } }
                        }
                    }
                }
            }
            return out
        }

        /** The newest api-versions.xml under [sdkDir]/platforms, preferring [compileSdk]'s platform. */
        fun apiVersionsIn(sdkDir: File, compileSdk: Int): File? {
            val platforms = File(sdkDir, "platforms")
            for (n in listOf("android-$compileSdk", "android-$compileSdk.0")) {
                File(platforms, "$n/data/api-versions.xml").takeIf { it.isFile }?.let { return it }
            }
            return platforms.listFiles().orEmpty()
                .filter { File(it, "data/api-versions.xml").isFile }
                .maxByOrNull { it.name.removePrefix("android-").substringBefore('.').toIntOrNull() ?: 0 }
                ?.let { File(it, "data/api-versions.xml") }
        }
    }
}
