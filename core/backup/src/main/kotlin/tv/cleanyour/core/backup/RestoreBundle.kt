package tv.cleanyour.core.backup

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import tv.cleanyour.core.model.ChangeRecord
import tv.cleanyour.core.model.Sha256
import tv.cleanyour.core.model.Snapshot
import tv.cleanyour.core.model.Tv
import tv.cleanyour.core.model.TvId
import tv.cleanyour.core.ops.OpRenderer
import tv.cleanyour.core.ops.UndoOrder
import tv.cleanyour.core.spec.ProfileSource
import tv.cleanyour.core.store.Clock
import tv.cleanyour.core.store.SnapshotCodec
import java.io.ByteArrayOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

// The restore zip (ARCHITECTURE 5.4), written through SAF by :feature:backup:
//
//   manifest.json          schema 1, app version, tvId, model, created, size and sha256 of every other entry
//   snapshot.json          the BASELINE snapshot through SnapshotCodec, with facts.serialLast4 blanked
//   changes.json           every change with its prior (ChangeJson)
//   commands.txt           the undo lines, for upstream tools/tv.py --batch
//   restore.sh, restore.ps1 laptop scripts with the same lines and a TV identity check
//   restore.txt            upstream debloat.txt shape (DefaultTvRestoreFiles)
//   launcher/config.json, launcher/wallpaper.jpg  when present
//
// Nothing in the zip names the TV's serial number, address, subnet or Wi-Fi: the TV is named by
// tvId (a hash), model and nickname. Entries are written in this order with a fixed timestamp, so
// the same inputs give the same bytes. The sink is flushed, not closed: the caller owns it.
object RestoreBundle {
    const val SCHEMA = 1
    const val KIND = "cleanyourtv-restore-bundle"
    const val MANIFEST = "manifest.json"
    const val SNAPSHOT = "snapshot.json"
    const val CHANGES = "changes.json"
    const val COMMANDS = "commands.txt"
    const val RESTORE_SH = "restore.sh"
    const val RESTORE_PS1 = "restore.ps1"
    const val RESTORE_TXT = "restore.txt"
    const val LAUNCHER_CONFIG = "launcher/config.json"
    const val LAUNCHER_WALLPAPER = "launcher/wallpaper.jpg"
    val ENTRIES = listOf(MANIFEST, SNAPSHOT, CHANGES, COMMANDS, RESTORE_SH, RESTORE_PS1, RESTORE_TXT, LAUNCHER_CONFIG, LAUNCHER_WALLPAPER)
    val REQUIRED = listOf(MANIFEST, SNAPSHOT, CHANGES, COMMANDS, RESTORE_SH, RESTORE_PS1, RESTORE_TXT)
    const val MAX_ENTRY_BYTES = 8L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024

    // The SAF file name suggested to the user: cleanyourtv-restore-<model>-<yyyymmdd>.zip
    fun fileName(tv: Tv, nowMs: Long, zone: ZoneId = ZoneOffset.UTC): String {
        val model = tv.facts.model.replace(Regex("[^A-Za-z0-9._-]+"), "-").trim('-', '.').take(40).ifEmpty { "tv" }
        return "cleanyourtv-restore-$model-${DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT).format(Instant.ofEpochMilli(nowMs).atZone(zone))}.zip"
    }

    // The baseline as it goes into the zip: the serial tail blanked and the hash recomputed.
    fun redact(baseline: Snapshot): Snapshot {
        val draft = baseline.copy(facts = baseline.facts.copy(serialLast4 = ""))
        return draft.copy(contentHash = SnapshotCodec.hash(draft))
    }

    internal fun sha256(bytes: ByteArray): Sha256 =
        Sha256(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) })
}

class ZipRestoreBundleWriter(
    private val renderer: OpRenderer,
    private val clock: Clock,
    private val appVersion: String,
    private val profiles: ProfileSource? = null,
    private val zone: ZoneId = ZoneOffset.UTC,
) : RestoreBundleWriter {

    override suspend fun write(tv: Tv, baseline: Snapshot, changes: List<ChangeRecord>, overlay: ByteArray?, wallpaper: ByteArray?, sink: OutputStream) {
        require(baseline.tvId == tv.id) { "baseline is for another TV" }
        val now = clock.nowMs()
        val profile = tv.profileId?.let { id -> profiles?.all()?.firstOrNull { it.id == id } }
        val header = ScriptHeader(tv.id, tv.nickname, tv.facts.model, DateTimeFormatter.ISO_LOCAL_DATE.format(Instant.ofEpochMilli(now).atZone(zone)), appVersion)
        val steps = RestoreCommands(renderer).steps(changes)
        val ordered = changes.sortedWith(UndoOrder.CHRONOLOGICAL)

        val entries = LinkedHashMap<String, ByteArray>()
        entries[RestoreBundle.SNAPSHOT] = SnapshotCodec.canonical(RestoreBundle.redact(baseline))
        entries[RestoreBundle.CHANGES] = ChangeJson.encode(ordered)
        entries[RestoreBundle.COMMANDS] = RestoreScripts.commandsTxt(header, steps).encodeToByteArray()
        entries[RestoreBundle.RESTORE_SH] = RestoreScripts.sh(header, steps).encodeToByteArray()
        entries[RestoreBundle.RESTORE_PS1] = RestoreScripts.ps1(header, steps).encodeToByteArray()
        entries[RestoreBundle.RESTORE_TXT] = DefaultTvRestoreFiles(clock, zone).restoreTxt(tv, changes, profile)
        overlay?.let { entries[RestoreBundle.LAUNCHER_CONFIG] = it }
        wallpaper?.let { entries[RestoreBundle.LAUNCHER_WALLPAPER] = it }
        entries.forEach { (name, bytes) -> require(bytes.size <= RestoreBundle.MAX_ENTRY_BYTES) { "$name is too large for a restore zip" } }

        val manifest = buildJsonObject {
            put("schema", RestoreBundle.SCHEMA)
            put("kind", RestoreBundle.KIND)
            put("appVersion", TextSafety.ascii(appVersion, 40))
            put("tvId", tv.id.value)
            put("model", TextSafety.ascii(tv.facts.model, 64))
            put("createdAtMs", now)
            put("baselineTakenAtMs", baseline.takenAtMs)
            put("redacted", JsonArray(listOf(JsonPrimitive("snapshot.facts.serialLast4"))))
            put("entries", buildJsonArray {
                entries.forEach { (name, bytes) ->
                    add(buildJsonObject { put("name", name); put("size", bytes.size.toLong()); put("sha256", RestoreBundle.sha256(bytes).hex) })
                }
            })
        }.toString().encodeToByteArray()

        val zip = ZipOutputStream(object : FilterOutputStream(sink) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() { flush() }
        })
        zip.use { z ->
            (listOf(RestoreBundle.MANIFEST to manifest) + entries.toList()).forEach { (name, bytes) ->
                // the local time in the writer's zone, not the JVM default zone, so the bytes do not
                // depend on the device's time zone setting. ZipEntry.time stores the default zone's
                // local time, so hand it the instant whose default-zone time is the writer's local
                // time (ZipEntry.timeLocal says this directly but Android has it only from API 35).
                // One exception: a writer's local time inside a spring-forward gap of the device's
                // zone does not exist there, so it moves on by the gap (usually an hour) and those
                // bytes differ from another zone's.
                val local = LocalDateTime.ofInstant(Instant.ofEpochMilli(now), zone)
                z.putNextEntry(ZipEntry(name).apply { time = local.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() })
                z.write(bytes)
                z.closeEntry()
            }
        }
    }
}

// What a restore zip holds once its manifest and hashes check out. Imported shell text is data,
// never run by the app (ARCHITECTURE 5.4); entries keeps every file's bytes for the import step.
class ReadBundle(val tvId: TvId, val model: String, val appVersion: String, val createdAtMs: Long,
    val baseline: Snapshot, val changes: List<ChangeRecord>, val entries: Map<String, ByteArray>)

class RestoreBundleException(message: String) : Exception(message)

// Reads and checks a restore zip: known entry names only, each once, size caps against zip bombs,
// every entry listed in the manifest with the right size and sha256 and nothing unlisted, the
// snapshot's own contentHash (SnapshotCodec) and tvId, and the change records' grammars.
object RestoreBundleReader {
    private val json = Json

    fun read(input: InputStream): ReadBundle {
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.isDirectory) { if (e.name == "launcher/") continue else fail("unexpected folder ${e.name}") }
                if (e.name !in RestoreBundle.ENTRIES) fail("unexpected entry ${e.name.take(80)}")
                if (e.name in files) fail("entry ${e.name} appears twice")
                val bytes = readCapped(zin, RestoreBundle.MAX_ENTRY_BYTES) ?: fail("entry ${e.name} is too large")
                total += bytes.size
                if (total > RestoreBundle.MAX_TOTAL_BYTES) fail("the zip is too large")
                files[e.name] = bytes
            }
        }
        RestoreBundle.REQUIRED.forEach { if (it !in files) fail("$it is missing") }

        val manifest = try { json.parseToJsonElement(files.getValue(RestoreBundle.MANIFEST).decodeToString()).jsonObject } catch (e: Exception) { fail("manifest.json is not JSON") }
        val listed = try {
            if (manifest["schema"]?.jsonPrimitive?.int != RestoreBundle.SCHEMA) fail("manifest schema is not ${RestoreBundle.SCHEMA}")
            if (manifest["kind"]?.jsonPrimitive?.content != RestoreBundle.KIND) fail("not a Clean Your TV restore zip")
            val m = LinkedHashMap<String, Pair<Long, String>>()
            manifest.getValue("entries").jsonArray.forEach {
                val o = it.jsonObject
                val name = o.getValue("name").jsonPrimitive.content
                if (m.put(name, o.getValue("size").jsonPrimitive.long to o.getValue("sha256").jsonPrimitive.content) != null) {
                    fail("manifest.json lists ${name.take(80)} twice")
                }
            }
            m
        } catch (e: RestoreBundleException) { throw e } catch (e: Exception) { fail("manifest.json is malformed") }
        for ((name, bytes) in files) {
            if (name == RestoreBundle.MANIFEST) continue
            val (size, sha) = listed[name] ?: fail("$name is not in the manifest")
            if (size != bytes.size.toLong() || sha != RestoreBundle.sha256(bytes).hex) fail("$name does not match the manifest")
        }
        listed.keys.forEach { if (it !in files) fail("$it is in the manifest but not in the zip") }

        val tvId = try { TvId(manifest.getValue("tvId").jsonPrimitive.content) } catch (e: Exception) { fail("manifest tvId is malformed") }
        val baseline = try { SnapshotCodec.decode(files.getValue(RestoreBundle.SNAPSHOT)) } catch (e: Exception) { fail("snapshot.json does not load: ${e.message?.take(120)}") }
        if (baseline.tvId != tvId) fail("snapshot.json is for another TV")
        val changes = try { ChangeJson.decode(files.getValue(RestoreBundle.CHANGES)) } catch (e: Exception) { fail("changes.json does not load: ${e.message?.take(120)}") }
        return ReadBundle(
            tvId = tvId,
            model = manifest["model"]?.jsonPrimitive?.content.orEmpty(),
            appVersion = manifest["appVersion"]?.jsonPrimitive?.content.orEmpty(),
            createdAtMs = manifest["createdAtMs"]?.jsonPrimitive?.long ?: 0L,
            baseline = baseline, changes = changes, entries = files,
        )
    }

    private fun readCapped(input: InputStream, cap: Long): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return out.toByteArray()
            out.write(buf, 0, n)
            if (out.size() > cap) return null
        }
    }

    private fun fail(why: String): Nothing = throw RestoreBundleException(why)
}
