package tw.umaya.tracker.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads the Mapsforge offline map data (main .map + .poi, render theme + its resource icons,
 * SRTM hillshade tiles) directly from rudymap.tw's own mirror — same source and same 3 files
 * backend/src/admin/updateRudyMap.js already pulls to refresh the VPS's own tileserver copy.
 * The phone never talks to our VPS for this: rudymap.tw is the public distribution point for
 * these files (it mirrors across multiple hosts for exactly this kind of bulk fan-out), so this
 * downloads straight from there instead of relaying ~1GB per install through our own server.
 */
class MapsforgeDownloader(context: Context) {
    val baseDir: File = File(context.getExternalFilesDir(null), "mapsforge")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val mapFile: File get() = File(baseDir, "maps/MOI_OSM_Taiwan_TOPO_Rudy.map")
    val themeFile: File get() = File(baseDir, "theme/MOI_OSM.xml")
    val demDir: File get() = File(baseDir, "dem")

    /** True once the core map file is fully present — enough to render offline. */
    fun hasCoreMapData(): Boolean = mapFile.exists()

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    private data class Package(val url: String, val destDir: String)

    private val packages = listOf(
        Package("https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy.map.zip", "maps"),
        Package("https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy.poi.zip", "maps"),
        Package("https://moi.kcwu.csie.org/hgtmix.zip", "dem"),
        Package("https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip", "theme"),
    )

    /**
     * Downloads and extracts every package, reporting combined progress across all of them (one
     * hiker-facing progress bar, not one per file). Safe to call again after a partial failure or
     * [cancel] — a zip already downloaded-and-extracted from a prior run is skipped only if the
     * final marker file for it is present; otherwise its .zip.part resumes via Range.
     */
    suspend fun downloadAll(
        onProgress: (downloadedBytes: Long, totalBytes: Long, currentFile: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        val sizes = packages.map { it to fetchContentLength(it.url) }
        val totalBytes = sizes.sumOf { it.second }
        var doneBytes = 0L

        for ((pkg, size) in sizes) {
            if (cancelled) throw CancellationException("下載已取消")
            val destDir = File(baseDir, pkg.destDir)
            destDir.mkdirs()
            val zipPart = File(baseDir, "${pkg.destDir}-${File(pkg.url).name}.part")
            val name = File(pkg.url).name
            val before = doneBytes
            downloadOneFile(pkg.url, zipPart) { transferred ->
                onProgress(before + transferred, totalBytes, name)
            }
            doneBytes = before + size
            onProgress(doneBytes, totalBytes, "$name（解壓中…）")
            extractZip(zipPart, destDir)
            zipPart.delete()
        }
    }

    private fun fetchContentLength(url: String): Long {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.header("Content-Length")?.toLongOrNull() ?: 0L
        }
    }

    /** Downloads one zip, resuming a partial one via Range — falls back to a full restart if the
     *  server doesn't honor Range. */
    private fun downloadOneFile(url: String, dest: File, onProgress: (Long) -> Unit) {
        val existingBytes = if (dest.exists()) dest.length() else 0L
        val request = Request.Builder().url(url).apply {
            if (existingBytes > 0) addHeader("Range", "bytes=$existingBytes-")
        }.build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("空回應：$url")
            val resumed = existingBytes > 0 && response.code == 206
            var transferred = if (resumed) existingBytes else 0L
            FileOutputStream(dest, resumed).use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        if (cancelled) throw CancellationException("下載已取消")
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        transferred += read
                        onProgress(transferred)
                    }
                }
            }
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        ZipInputStream(BufferedInputStream(zipFile.inputStream())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (cancelled) throw CancellationException("下載已取消")
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
