package tw.umaya.tracker.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The point of 補空檔合併: the phone missed stretches the watch recorded (2026-10-02), and the
 * hiker wants one track with the holes filled — without zig-zagging between two devices where
 * both recorded, and without losing anything from the originals.
 */
class GpxMergeTest {
    private fun pt(min: Int, sec: Int = 0, lat: Double = 23.0 + min * 0.0001, ext: String = "") =
        "<trkpt lat=\"$lat\" lon=\"121.0\"><ele>100.0</ele><time>2026-10-02T01:%02d:%02dZ</time>$ext</trkpt>".format(min, sec)

    private fun gpx(dir: File, name: String, points: List<String>, ns: String = "") =
        File(dir, name).apply {
            writeText("<?xml version=\"1.0\"?>\n<gpx version=\"1.1\" xmlns=\"http://www.topografix.com/GPX/1/1\"$ns>\n" +
                "<wpt lat=\"23\" lon=\"121\"><name>$name</name></wpt>\n<trk><trkseg>\n" + points.joinToString("\n") + "\n</trkseg></trk></gpx>\n")
        }

    private val tmp = Files.createTempDirectory("merge").toFile()

    // Phone: every minute 0–10 and 30–40 (a 20-minute hole). Watch: every 10 s, 0–40, with heart rate.
    private val phone = gpx(tmp, "phone.gpx", (0..10).map { pt(it) } + (30..40).map { pt(it) })
    private val hr = " xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\""
    private val watch = gpx(tmp, "watch.gpx",
        (0 until 40 * 6).map { i -> pt(i / 6, (i % 6) * 10, ext = "<extensions><gpxtpx:hr>120</gpxtpx:hr></extensions>") }, hr)

    private val originals = listOf(phone, watch).associateWith { it.readText() }

    @Test fun fillGaps_densestLeads_othersOnlyFillItsHoles_noZigZag() {
        val out = mergeGpxFiles(listOf(phone, watch), "合併", fillGaps = true)
        val points = Regex("<trkpt\\b").findAll(out).count()
        // The watch (240 points) leads everywhere it has data — the phone adds nothing where both recorded.
        assertEquals(240, points)
        assertEquals("one continuous track — the watch has no hole", 1, Regex("<trkseg>").findAll(out).count())
    }

    @Test fun fillGaps_phoneFillsWhereTheWatchStopped() {
        val watchShort = gpx(tmp, "watch_short.gpx", (0 until 20 * 6).map { i -> pt(i / 6, (i % 6) * 10) }) // 0–20 min only
        val out = mergeGpxFiles(listOf(phone, watchShort), "合併", fillGaps = true)
        // Watch 0–19:50 (120 pts); phone fills only 30–40 (11 pts) — its 0–10 points overlap the watch.
        assertEquals(131, Regex("<trkpt\\b").findAll(out).count())
        assertEquals("the 20→30 min hole splits the track instead of drawing a straight line", 2, Regex("<trkseg>").findAll(out).count())
    }

    @Test fun keepsExtensionsAndTheirNamespace_soTheFileStaysValid() {
        val out = mergeGpxFiles(listOf(phone, watch), "合併", fillGaps = true)
        assertTrue(out.contains("<gpxtpx:hr>120</gpxtpx:hr>"))
        assertTrue("prefix used in the points must be declared on <gpx>", out.contains("xmlns:gpxtpx=\"http://www.garmin.com/xmlschemas/TrackPointExtension/v1\""))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(out.byteInputStream()) // throws if not well-formed
    }

    @Test fun separateSegments_keepsEveryPointOfEveryFile() {
        val out = mergeGpxFiles(listOf(watch, phone), "合併", fillGaps = false)
        assertEquals(240 + 22, Regex("<trkpt\\b").findAll(out).count())
        assertEquals(2, Regex("<wpt\\b").findAll(out).count())
    }

    @Test fun originalsAreNeverModified() {
        mergeGpxFiles(listOf(phone, watch), "合併", fillGaps = true)
        mergeGpxFiles(listOf(phone, watch), "合併", fillGaps = false)
        originals.forEach { (f, text) -> assertEquals(text, f.readText()) }
    }
}
