package tw.umaya.tracker.peak

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A summit from OpenStreetMap (natural=peak/volcano). */
data class Peak(val lat: Double, val lon: Double, val ele: Double, val zh: String?, val en: String?)

/**
 * 🏔️ 山峰辨識 data: OSM peaks and a terrain model (AWS Terrarium DEM tiles) around the hiker,
 * downloaded once per area and cached under files/peakfinder so it works offline afterwards.
 */
class PeakData(context: Context) {
    private val root = File(context.filesDir, "peakfinder").apply { mkdirs() }
    private val tiles = HashMap<Long, ShortArray>() // (x shl 20 or y) → 256×256 metres, zoom DEM_Z

    /**
     * Makes sure DEM tiles and peaks within [RADIUS_KM] of (lat, lon) are on disk (downloading what's
     * missing) and loaded. [progress] gets short status texts. Returns the peaks in range.
     */
    fun prepare(lat: Double, lon: Double, progress: (String) -> Unit): List<Peak> {
        val (x0, y0) = tileXY(lat + RADIUS_KM / 111.0, lon - RADIUS_KM / (111.0 * cos(lat.rad)))
        val (x1, y1) = tileXY(lat - RADIUS_KM / 111.0, lon + RADIUS_KM / (111.0 * cos(lat.rad)))
        val need = (x0.toInt()..x1.toInt()).flatMap { x -> (y0.toInt()..y1.toInt()).map { y -> x to y } }
        need.forEachIndexed { i, (x, y) ->
            val f = File(root, "dem/$DEM_Z/$x/$y.png")
            if (!f.exists()) {
                progress("下載地形 ${i + 1}/${need.size}")
                runCatching {
                    f.parentFile?.mkdirs()
                    val bytes = get("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/$DEM_Z/$x/$y.png")
                    f.writeBytes(bytes)
                }
            }
            val key = (x.toLong() shl 20) or y.toLong()
            if (key !in tiles && f.exists()) runCatching { decode(f.readBytes()) }.getOrNull()?.let { tiles[key] = it }
        }
        return peaksAround(lat, lon, progress)
    }

    private fun peaksAround(lat: Double, lon: Double, progress: (String) -> Unit): List<Peak> {
        // Cached per 0.5° cell of the request centre; a request covers ±1° so neighbours overlap.
        val cell = "${(lat * 2).roundToInt()}_${(lon * 2).roundToInt()}"
        val f = File(root, "peaks_$cell.json")
        if (!f.exists()) {
            progress("下載山峰名稱…")
            val c = (lat * 2).roundToInt() / 2.0 to (lon * 2).roundToInt() / 2.0
            val q = "[out:json][timeout:60];node[\"natural\"~\"^(peak|volcano)$\"][\"name\"](${c.first - 1},${c.second - 1.2},${c.first + 1},${c.second + 1.2});out body;"
            runCatching {
                val body = post("https://overpass-api.de/api/interpreter", "data=" + URLEncoder.encode(q, "UTF-8"))
                f.writeBytes(body)
            }.onFailure { progress("山峰名稱下載失敗：${it.message}") }
        }
        if (!f.exists()) return emptyList()
        val els = JSONObject(f.readText()).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<Peak>(els.length())
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i); val t = e.optJSONObject("tags") ?: continue
            val name = t.optString("name").ifBlank { null }
            val zh = listOf("name:zh-Hant", "name:zh-TW", "name:zh").firstNotNullOfOrNull { t.optString(it).ifBlank { null } }
                ?: name?.takeIf { it.any { ch -> ch.code in 0x4E00..0x9FFF } }
            val en = t.optString("name:en").ifBlank { null } ?: name?.takeIf { it.none { ch -> ch.code in 0x4E00..0x9FFF } }
            val pLat = e.optDouble("lat"); val pLon = e.optDouble("lon")
            val ele = t.optString("ele").replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: elevation(pLat, pLon) ?: continue
            out += Peak(pLat, pLon, ele, zh, if (en == zh) null else en)
        }
        return out
    }

    /** Ground elevation (m) from the loaded DEM, bilinear; null where no tile is loaded. */
    fun elevation(lat: Double, lon: Double): Double? {
        val (fx, fy) = tileXY(lat, lon)
        val px = (fx - floor(fx)) * 256 - 0.5; val py = (fy - floor(fy)) * 256 - 0.5
        val tile = tiles[(fx.toLong() shl 20) or fy.toLong()] ?: return null
        val ix = px.toInt().coerceIn(0, 254); val iy = py.toInt().coerceIn(0, 254)
        val tx = (px - ix).coerceIn(0.0, 1.0); val ty = (py - iy).coerceIn(0.0, 1.0)
        fun v(x: Int, y: Int) = tile[y * 256 + x].toDouble()
        return v(ix, iy) * (1 - tx) * (1 - ty) + v(ix + 1, iy) * tx * (1 - ty) + v(ix, iy + 1) * (1 - tx) * ty + v(ix + 1, iy + 1) * tx * ty
    }

    /**
     * The skyline seen from (lat, lon, eyeEle): for each azimuth step the highest apparent elevation
     * angle (degrees) of the terrain out to [RADIUS_KM], with earth curvature and refraction.
     */
    fun skyline(lat: Double, lon: Double, eyeEle: Double): FloatArray {
        val n = (360 / AZ_STEP).toInt()
        val out = FloatArray(n) { -90f }
        val cosLat = cos(lat.rad)
        for (a in 0 until n) {
            val az = (a * AZ_STEP).rad; val dN = cos(az) / 111_320.0; val dE = sin(az) / (111_320.0 * cosLat)
            var best = -90.0; var d = 60.0
            while (d < RADIUS_KM * 1000) {
                val h = elevation(lat + dN * d, lon + dE * d)
                if (h != null) {
                    val drop = d * d / (2 * EARTH_R) * (1 - REFRACTION)
                    val ang = atan((h - eyeEle - drop) / d)
                    if (ang > best) best = ang
                }
                d += (d * 0.006).coerceAtLeast(30.0)
            }
            out[a] = Math.toDegrees(best).toFloat()
        }
        return out
    }

    companion object {
        const val DEM_Z = 10           // ~150 m per pixel — enough for skylines tens of km away
        const val RADIUS_KM = 100.0
        const val AZ_STEP = 0.25        // degrees per skyline sample
        const val EARTH_R = 6_371_000.0
        const val REFRACTION = 0.13

        /** Apparent elevation angle (deg) and distance (m) of a point seen from the eye. */
        fun angleTo(lat: Double, lon: Double, eyeEle: Double, pLat: Double, pLon: Double, pEle: Double): Pair<Double, Double> {
            val d = distance(lat, lon, pLat, pLon)
            val drop = d * d / (2 * EARTH_R) * (1 - REFRACTION)
            return Math.toDegrees(atan((pEle - eyeEle - drop) / d.coerceAtLeast(1.0))) to d
        }

        fun bearing(lat: Double, lon: Double, pLat: Double, pLon: Double): Double {
            val y = sin((pLon - lon).rad) * cos(pLat.rad)
            val x = cos(lat.rad) * sin(pLat.rad) - sin(lat.rad) * cos(pLat.rad) * cos((pLon - lon).rad)
            return (Math.toDegrees(atan2(y, x)) + 360) % 360
        }

        fun distance(lat: Double, lon: Double, pLat: Double, pLon: Double): Double {
            val dLat = (pLat - lat).rad; val dLon = (pLon - lon).rad
            val h = sin(dLat / 2).let { it * it } + cos(lat.rad) * cos(pLat.rad) * sin(dLon / 2).let { it * it }
            return 2 * EARTH_R * atan2(sqrt(h), sqrt(1 - h))
        }

        private val Double.rad get() = this * PI / 180

        private fun tileXY(lat: Double, lon: Double): Pair<Double, Double> {
            val n = (1 shl DEM_Z).toDouble()
            val x = (lon + 180) / 360 * n
            val y = (1 - ln(tan(lat.rad) + 1 / cos(lat.rad)) / PI) / 2 * n
            return x to y
        }

        private fun decode(png: ByteArray): ShortArray {
            val bmp = BitmapFactory.decodeByteArray(png, 0, png.size) ?: error("bad tile")
            val px = IntArray(256 * 256); bmp.getPixels(px, 0, 256, 0, 0, 256, 256); bmp.recycle()
            return ShortArray(px.size) { i ->
                val c = px[i]
                (((c shr 16) and 0xFF) * 256 + ((c shr 8) and 0xFF) + (c and 0xFF) / 256.0 - 32768).toInt().coerceIn(-500, 9000).toShort()
            }
        }

        private fun get(url: String): ByteArray = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "umaya-tracker/peakfinder")
            if (responseCode !in 200..299) error("HTTP $responseCode")
            inputStream.use { it.readBytes() }
        }

        private fun post(url: String, form: String): ByteArray = (URL(url).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 90_000; requestMethod = "POST"; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", "umaya-tracker/peakfinder")
            outputStream.use { it.write(form.toByteArray()) }
            if (responseCode !in 200..299) error("HTTP $responseCode")
            inputStream.use { it.readBytes() }
        }
    }
}
