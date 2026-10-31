package com.registry.collector.switchlog

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

/** A raw usage event as reported by the platform. Only [type] values in [SwitchType] are kept. */
data class RawEvent(val ts: Long, val pkg: String, val type: Int)

/** The only seam to Android's UsageStatsManager. Tests supply fakes. */
interface UsageEventSource {
    fun events(fromMs: Long, toMs: Long): List<RawEvent>
}

/** Persistent cursor and run bookkeeping. Implementations must make [setCursor] durable before returning. */
interface CursorStore {
    fun getCursor(): Long?
    fun setCursor(ms: Long)
    fun recordRun(ms: Long, events: Int)
    fun getBackfillEarliest(): Long?
    fun setBackfillEarliest(ms: Long)
}

/**
 * Packages that must never be written to the switch log. Empty by default: no list is guessed here.
 * Matching events are dropped before they reach disk. The PC-side ingest step is where sensitive
 * packages get hashed or category-mapped; that is out of scope for the on-device writer.
 */
val SWITCH_LOG_DENYLIST: Set<String> = emptySet()

enum class SwitchType(val code: Int, val tag: String, val hasPackage: Boolean) {
    FG(1, "FG", true),
    BG(2, "BG", true),
    SCREEN_ON(15, "SCREEN_ON", false),
    SCREEN_OFF(16, "SCREEN_OFF", false),
    LOCK(17, "LOCK", false),
    UNLOCK(18, "UNLOCK", false);

    companion object {
        // 1 and 2 equal MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND; 15-18 exist from API 28 and never appear below it.
        fun fromCode(code: Int): SwitchType? = values().firstOrNull { it.code == code }
    }
}

/** One stored line. Exactly the keys ts, pkg, t. */
data class LogLine(val ts: Long, val pkg: String, val t: String)

object SwitchLogFormat {
    const val DIR_NAME = "switch-log"
    private val LINE = Regex("""^\{"ts":(\d+),"pkg":"((?:[^"\\]|\\.)*)","t":"([A-Z_]+)"\}$""")

    fun fileName(ts: Long): String {
        val d = Instant.ofEpochMilli(ts).atZone(ZoneOffset.UTC)
        return "switches-%04d-%02d.jsonl".format(d.year, d.monthValue)
    }

    fun encode(line: LogLine): String =
        "{\"ts\":${line.ts},\"pkg\":\"${escape(line.pkg)}\",\"t\":\"${line.t}\"}"

    fun decode(s: String): LogLine? {
        val m = LINE.matchEntire(s) ?: return null
        return LogLine(m.groupValues[1].toLong(), unescape(m.groupValues[2]), m.groupValues[3])
    }

    private fun escape(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) {
                sb.append(c)
                i++
            } else if (s[i + 1] == 'u' && i + 6 <= s.length) {
                sb.append(s.substring(i + 2, i + 6).toInt(16).toChar())
                i += 6
            } else {
                sb.append(s[i + 1])
                i += 2
            }
        }
        return sb.toString()
    }

    fun logFiles(rootDir: File): List<File> =
        rootDir.listFiles { f -> f.isFile && f.name.startsWith("switches-") && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.name }.orEmpty()
}

sealed class RunResult {
    data class Ok(val written: Int) : RunResult()
    object Failed : RunResult()
}

/**
 * All switch-log logic. No Android imports: the source, cursor store and clock are injected.
 * Never logs package names; [log] receives fixed messages only.
 */
class SwitchLogRunner(
    private val source: UsageEventSource,
    private val rootDir: File,
    private val clock: () -> Long,
    private val cursor: CursorStore,
    private val log: (String) -> Unit,
) {
    fun run(): RunResult {
        val now = clock()
        val cur = cursor.getCursor()
        val from = cur ?: (now - FIRST_RUN_LOOKBACK_MS)
        val to = now - SETTLE_MS
        val raw = try {
            if (from > to) emptyList() else source.events(from, to)
        } catch (e: Exception) {
            log("query failed: ${e.javaClass.simpleName}")
            return RunResult.Failed
        }
        if (cur == null && raw.isNotEmpty() && cursor.getBackfillEarliest() == null) {
            cursor.setBackfillEarliest(raw.minOf { it.ts })
        }
        val mapped = map(raw)
        if (mapped.isEmpty()) {
            cursor.recordRun(now, 0)
            return RunResult.Ok(0)
        }
        val fresh = if (cur == null) {
            mapped
        } else {
            val existing = keysAt(cur)
            mapped.filterNot { it.ts == cur && it in existing }
        }
        try {
            write(fresh)
            // Written and fsynced; only now may the cursor move.
            cursor.setCursor(mapped.last().ts)
        } catch (e: Exception) {
            log("write failed: ${e.javaClass.simpleName}")
            return RunResult.Failed
        }
        cursor.recordRun(now, fresh.size)
        return RunResult.Ok(fresh.size)
    }

    private fun map(raw: List<RawEvent>): List<LogLine> =
        raw.mapNotNull { e ->
            val type = SwitchType.fromCode(e.type) ?: return@mapNotNull null
            val pkg = if (type.hasPackage) e.pkg else ""
            if (type.hasPackage && (pkg.isEmpty() || pkg in SWITCH_LOG_DENYLIST)) return@mapNotNull null
            LogLine(e.ts, pkg, type.tag)
        }.sortedBy { it.ts } // sortedBy is stable: ties keep platform order

    private fun keysAt(ts: Long): Set<LogLine> {
        val f = File(rootDir, SwitchLogFormat.fileName(ts))
        if (!f.isFile) return emptySet()
        val out = HashSet<LogLine>()
        f.forEachLine { s -> SwitchLogFormat.decode(s)?.let { if (it.ts == ts) out.add(it) } }
        return out
    }

    private fun write(lines: List<LogLine>) {
        if (lines.isEmpty()) return
        if (!rootDir.isDirectory && !rootDir.mkdirs()) throw IOException("cannot create log dir")
        for ((name, group) in lines.groupBy { SwitchLogFormat.fileName(it.ts) }.toSortedMap()) {
            FileOutputStream(File(rootDir, name), true).use { out ->
                out.write(group.joinToString("") { SwitchLogFormat.encode(it) + "\n" }.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
        }
    }

    companion object {
        const val FIRST_RUN_LOOKBACK_MS = 14L * 24 * 60 * 60 * 1000
        const val SETTLE_MS = 60_000L
    }
}

/** Read side. Writers may duplicate lines after a crash; readers collapse them. */
object SwitchLogReader {
    /** All lines across files, malformed lines skipped, duplicates on (ts, pkg, t) removed, ordered by ts. */
    fun readDeduped(rootDir: File): List<LogLine> {
        val seen = LinkedHashSet<LogLine>()
        for (f in SwitchLogFormat.logFiles(rootDir)) f.forEachLine { s -> SwitchLogFormat.decode(s)?.let { seen.add(it) } }
        return seen.sortedBy { it.ts }
    }

    /** Foreground sequence with repeated same-package FG (per-activity and split-screen resumes) collapsed. */
    fun foregroundSequence(lines: List<LogLine>): List<LogLine> {
        val out = ArrayList<LogLine>()
        for (l in lines) {
            if (l.t != SwitchType.FG.tag) continue
            if (out.lastOrNull()?.pkg == l.pkg) continue
            out.add(l)
        }
        return out
    }
}

data class SwitchLogStatus(val fileCount: Int, val lineCount: Int, val firstTs: Long?, val lastTs: Long?)

object SwitchLogStatusReader {
    fun read(rootDir: File): SwitchLogStatus {
        val files = SwitchLogFormat.logFiles(rootDir)
        var n = 0
        var first: Long? = null
        var last: Long? = null
        for (f in files) {
            f.forEachLine { s ->
                val l = SwitchLogFormat.decode(s) ?: return@forEachLine
                n++
                val a = first
                val b = last
                if (a == null || l.ts < a) first = l.ts
                if (b == null || l.ts > b) last = l.ts
            }
        }
        return SwitchLogStatus(files.size, n, first, last)
    }
}
