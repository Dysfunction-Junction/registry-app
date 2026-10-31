package com.registry.collector.switchlog

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSource(var events: List<RawEvent> = emptyList()) : UsageEventSource {
    val calls = ArrayList<Pair<Long, Long>>()
    override fun events(fromMs: Long, toMs: Long): List<RawEvent> {
        calls.add(fromMs to toMs)
        return events.filter { it.ts in fromMs..toMs }
    }
}

private class FakeCursor : CursorStore {
    var saved: Long? = null
    var lastRun: Pair<Long, Int>? = null
    var earliest: Long? = null
    override fun getCursor() = saved
    override fun setCursor(ms: Long) { saved = ms }
    override fun recordRun(ms: Long, events: Int) { lastRun = ms to events }
    override fun getBackfillEarliest() = earliest
    override fun setBackfillEarliest(ms: Long) { earliest = ms }
}

class SwitchLogRunnerTest {
    private lateinit var root: File
    private val source = FakeSource()
    private val cursor = FakeCursor()
    private var now = 1_800_000_000_000L // 2027-01-15 UTC
    private val logs = ArrayList<String>()

    private fun runner(dir: File = root) = SwitchLogRunner(source, dir, { now }, cursor) { logs.add(it) }
    private fun fg(ts: Long, pkg: String = "a.b") = RawEvent(ts, pkg, 1)

    @Before fun setUp() { root = Files.createTempDirectory("swlog").toFile().resolve("switch-log") }
    @After fun tearDown() { root.parentFile.deleteRecursively() }

    @Test fun mapsKnownTypesAndDropsOthers() {
        source.events = listOf(
            RawEvent(now - 10_000_000, "p.one", 1), RawEvent(now - 9_000_000, "p.one", 2),
            RawEvent(now - 8_000_000, "ignored.pkg", 15), RawEvent(now - 7_000_000, "ignored.pkg", 16),
            RawEvent(now - 6_000_000, "ignored.pkg", 17), RawEvent(now - 5_000_000, "ignored.pkg", 18),
            RawEvent(now - 4_000_000, "p.two", 5), RawEvent(now - 3_000_000, "p.two", 7),
            RawEvent(now - 2_000_000, "p.two", 12), RawEvent(now - 1_000_000, "p.two", 23),
        )
        assertEquals(RunResult.Ok(6), runner().run())
        val got = SwitchLogReader.readDeduped(root).map { it.pkg to it.t }
        assertEquals(
            listOf("p.one" to "FG", "p.one" to "BG", "" to "SCREEN_ON", "" to "SCREEN_OFF", "" to "LOCK", "" to "UNLOCK"),
            got,
        )
    }

    @Test fun outputOrderedByTsAndStableForTies() {
        val t = now - 1_000_000
        source.events = listOf(fg(t + 5, "z"), fg(t, "first"), fg(t, "second"), fg(t - 5, "early"))
        runner().run()
        assertEquals(listOf("early", "first", "second", "z"), SwitchLogReader.readDeduped(root).map { it.pkg })
    }

    @Test fun failedWriteLeavesCursorAndRetryLosesAndDuplicatesNothing() {
        val blocker = File(root.parentFile, "blocked").also { it.writeText("x") } // a file where the dir must be
        source.events = listOf(fg(now - 3_000_000, "a"), fg(now - 2_000_000, "b"))
        assertEquals(RunResult.Failed, runner(blocker).run())
        assertNull(cursor.saved)
        assertEquals(RunResult.Ok(2), runner().run())
        assertEquals(listOf("a", "b"), SwitchLogReader.readDeduped(root).map { it.pkg })
    }

    @Test fun partialWriteThenRetryDedupes() {
        // Two months of events; the second month's file is blocked by a directory, so month one is written, then failure.
        val dec = 1_798_000_000_000L // 2026-12-23
        now = 1_800_000_000_000L // 2027-01
        val janTs = now - 2_000_000
        source.events = listOf(fg(dec, "a"), fg(janTs, "b"))
        root.mkdirs()
        val block = File(root, SwitchLogFormat.fileName(janTs)).also { it.mkdir() }
        assertEquals(RunResult.Failed, runner().run())
        assertNull(cursor.saved)
        block.delete()
        assertEquals(RunResult.Ok(2), runner().run()) // "a" is now on disk twice
        val raw = SwitchLogFormat.logFiles(root).sumOf { f -> f.readLines().size }
        assertEquals(3, raw)
        assertEquals(listOf("a", "b"), SwitchLogReader.readDeduped(root).map { it.pkg })
    }

    @Test fun zeroEventQueryLeavesCursorAlone() {
        cursor.saved = now - 5_000_000
        val before = cursor.saved
        assertEquals(RunResult.Ok(0), runner().run())
        assertEquals(before, cursor.saved)
        assertEquals(now to 0, cursor.lastRun)
        assertFalse(root.exists())
    }

    @Test fun sameMillisecondAtCursorNotLostNorDuplicated() {
        val t = now - 5_000_000
        source.events = listOf(fg(t, "x"))
        runner().run()
        assertEquals(t, cursor.saved)
        // A second event in the same millisecond arrives late; the first is returned again.
        source.events = listOf(fg(t, "x"), fg(t, "y"), fg(t + 10, "z"))
        assertEquals(RunResult.Ok(2), runner().run())
        assertEquals(listOf("x", "y", "z"), SwitchLogReader.readDeduped(root).map { it.pkg })
        assertEquals(3, SwitchLogFormat.logFiles(root).sumOf { it.readLines().size })
    }

    @Test fun firstRunQueriesFourteenDaysBackLaterRunsStartAtCursor() {
        val t = now - 3_000_000
        source.events = listOf(fg(t))
        runner().run()
        assertEquals(now - 14L * 24 * 3600 * 1000, source.calls[0].first)
        assertEquals(now - 60_000, source.calls[0].second)
        assertEquals(t, cursor.earliest)
        now += 1_000_000
        runner().run()
        assertEquals(t, source.calls[1].first)
    }

    @Test fun monthRolloverWritesRightFiles() {
        val endOfDec = 1_798_761_599_000L // 2026-12-31T23:59:59Z
        now = endOfDec + 3_600_000
        source.events = listOf(fg(endOfDec, "dec"), fg(endOfDec + 1_000, "jan"))
        runner().run()
        assertEquals(listOf("switches-2026-12.jsonl", "switches-2027-01.jsonl"), SwitchLogFormat.logFiles(root).map { it.name })
        assertTrue(File(root, "switches-2026-12.jsonl").readText().contains("dec"))
        assertTrue(File(root, "switches-2027-01.jsonl").readText().contains("jan"))
    }

    @Test fun linesHaveExactlyTheThreeKeys() {
        source.events = listOf(fg(now - 2_000_000, "com.x\"y"), RawEvent(now - 1_000_000, "leak.pkg", 15))
        runner().run()
        val lines = SwitchLogFormat.logFiles(root).flatMap { it.readLines() }
        assertEquals(2, lines.size)
        for (l in lines) {
            assertTrue(l, Regex("""^\{"ts":\d+,"pkg":"(?:[^"\\]|\\.)*","t":"[A-Z_]+"\}$""").matches(l))
            assertEquals(listOf("ts", "pkg", "t"), Regex("\"(ts|pkg|t)\":").findAll(l.replace(Regex("\"pkg\":\"(?:[^\"\\\\]|\\\\.)*\""), "\"pkg\":\"\"")).map { it.groupValues[1] }.toList())
        }
        assertFalse(lines[1].contains("leak.pkg"))
        assertEquals("com.x\"y", SwitchLogFormat.decode(lines[0])!!.pkg)
        assertTrue(logs.none { it.contains("com.x") })
    }

    @Test fun foregroundSequenceCollapsesRepeatedPackage() {
        val l = listOf(LogLine(1, "a", "FG"), LogLine(2, "a", "FG"), LogLine(3, "a", "BG"), LogLine(4, "b", "FG"), LogLine(5, "a", "FG"))
        assertEquals(listOf("a", "b", "a"), SwitchLogReader.foregroundSequence(l).map { it.pkg })
    }

    @Test fun newCodeHasNoNetworkFirebaseLogOrJson() {
        val dir = listOf("src/main/kotlin/com/registry/collector/switchlog", "app/src/main/kotlin/com/registry/collector/switchlog")
            .map { File(it) }.first { it.isDirectory }
        val banned = listOf("okhttp", "firebase", "com.registry.collector.network", "IngestPayload", "IngestApiService",
            "IngestClient", "android.util.Log", "org.json", "SyncPreferences")
        val files = dir.listFiles { f -> f.extension == "kt" }!!
        assertTrue(files.isNotEmpty())
        for (f in files) {
            val imports = f.readLines().filter { it.startsWith("import ") }
            for (b in banned) assertTrue("${f.name} imports $b", imports.none { it.contains(b, ignoreCase = true) })
        }
        val core = File(dir, "SwitchLogCore.kt").readLines().filter { it.startsWith("import ") }
        assertTrue(core.none { it.startsWith("import android.") || it.startsWith("import androidx.") })
    }
}
