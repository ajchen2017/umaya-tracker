package tw.umaya.tracker.data

import android.content.Context
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
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
const val TAIWAN_PACK_ID = "taiwan"

class MapsforgeDownloader(context: Context) {
    val baseDir: File = File(context.getExternalFilesDir(null), "mapsforge")

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val themeFile: File get() = File(baseDir, "theme/MOI_OSM.xml")
    private val mapsDir: File get() = File(baseDir, "maps")

    @Volatile private var cancelled = false
    fun cancel() { cancelled = true }

    data class PackageStatus(val fileName: String, val url: String, val bytes: Long)

    data class Package(val url: String, val destDir: String)

    /** One independently installable offline map. Catalog packs download from rudymap.tw's mirror;
     *  imported packs are a .map the hiker copied in themselves (no DEM, no download URLs). */
    data class OfflinePack(
        val id: String,
        val name: String,
        val mapFileName: String,
        val demDirName: String?,
        val downloads: List<Package> = emptyList(),
        val sizeHint: String? = null,
    ) {
        val isImported: Boolean get() = downloads.isEmpty()
    }

    private val themePackage = Package("https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy_hs_style.zip", "theme")

    // Taiwan keeps the original maps/ + dem/ layout so existing installs don't re-download ~1GB.
    val catalog = listOf(
        OfflinePack(
            TAIWAN_PACK_ID, "魯地圖（台灣）", "MOI_OSM_Taiwan_TOPO_Rudy.map", "dem",
            listOf(
                Package("https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy.map.zip", "maps"),
                Package("https://moi.kcwu.csie.org/MOI_OSM_Taiwan_TOPO_Rudy.poi.zip", "maps"),
                Package("https://moi.kcwu.csie.org/hgtmix.zip", "dem"),
            ),
            "約 400MB（解壓後約 1GB）",
        ),
        OfflinePack(
            "annapurna", "安娜普納（尼泊爾）", "AW3D30_OSM_Annapurna_TOPO_Rudy.map", "dem-annapurna",
            listOf(
                Package("https://moi.kcwu.csie.org/AW3D30_OSM_Annapurna_TOPO_Rudy.map.zip", "maps"),
                Package("https://moi.kcwu.csie.org/AW3D30_OSM_Annapurna_TOPO_Rudy.poi.zip", "maps"),
                Package("https://moi.kcwu.csie.org/annapurna_hgtmix.zip", "dem-annapurna"),
            ),
            "約 190MB",
        ),
    )

    /** Catalog packs (installed or not) followed by any .map the hiker imported themselves. */
    fun allPacks(): List<OfflinePack> {
        val catalogFiles = catalog.map { it.mapFileName }.toSet()
        val imported = mapsDir.listFiles { f -> f.extension == "map" && f.name !in catalogFiles }
            .orEmpty().sortedBy { it.name }
            .map { OfflinePack("import:${it.name}", it.nameWithoutExtension, it.name, null) }
        return catalog + imported
    }

    fun mapFile(pack: OfflinePack): File = File(mapsDir, pack.mapFileName)
    fun demDir(pack: OfflinePack): File? = pack.demDirName?.let { File(baseDir, it) }
    fun isInstalled(pack: OfflinePack): Boolean = mapFile(pack).exists() && themeFile.exists()
    fun installedBytes(pack: OfflinePack): Long =
        packFiles(pack).sumOf { f -> if (f.isDirectory) f.walk().sumOf { it.length() } else f.length() }

    /** The theme is shared by every pack, so deleting a pack never touches it. */
    fun delete(pack: OfflinePack) {
        packFiles(pack).forEach { it.deleteRecursively() }
    }

    private fun packFiles(pack: OfflinePack): List<File> = listOfNotNull(
        mapFile(pack),
        File(mapsDir, pack.mapFileName.removeSuffix(".map") + ".poi"),
        demDir(pack),
    ).filter { it.exists() }

    /** Copies a hiker-chosen .map (or a .zip containing one) into maps/; returns the .map names
     *  added. Fails without leaving a partial file behind if the source has no .map in it. */
    suspend fun importMap(input: InputStream, displayName: String): List<String> = withContext(Dispatchers.IO) {
        mapsDir.mkdirs()
        if (displayName.endsWith(".zip", ignoreCase = true)) {
            val added = mutableListOf<String>()
            ZipInputStream(BufferedInputStream(input)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = File(entry.name).name
                    if (!entry.isDirectory && name.endsWith(".map", ignoreCase = true)) {
                        copyAtomically(zis, File(mapsDir, name))
                        added += name
                    }
                    entry = zis.nextEntry
                }
            }
            if (added.isEmpty()) throw IOException("壓縮檔裡沒有 .map 地圖檔")
            added
        } else if (displayName.endsWith(".map", ignoreCase = true)) {
            input.use { copyAtomically(it, File(mapsDir, File(displayName).name)) }
            listOf(File(displayName).name)
        } else {
            throw IOException("只支援 .map 或含 .map 的 .zip 檔")
        }
    }

    private fun copyAtomically(input: InputStream, dest: File) {
        val tmp = File(dest.path + ".part")
        FileOutputStream(tmp).use { input.copyTo(it) }
        if (!tmp.renameTo(dest)) throw IOException("無法寫入 ${dest.name}")
    }

    private fun packagesFor(pack: OfflinePack): List<Package> =
        if (themeFile.exists()) pack.downloads else pack.downloads + themePackage

    suspend fun checkPackages(pack: OfflinePack): List<PackageStatus> = withContext(Dispatchers.IO) {
        (pack.downloads + themePackage).map { pkg ->
            PackageStatus(
                fileName = File(pkg.url).name,
                url = pkg.url,
                bytes = fetchContentLength(pkg.url),
            )
        }
    }

    /**
     * Downloads and extracts every package, reporting combined progress across all of them (one
     * hiker-facing progress bar, not one per file). Safe to call again after a partial failure or
     * [cancel] — a zip already downloaded-and-extracted from a prior run is skipped only if the
     * final marker file for it is present; otherwise its .zip.part resumes via Range.
     */
    suspend fun download(
        pack: OfflinePack,
        onProgress: (downloadedBytes: Long, totalBytes: Long, currentFile: String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        cancelled = false
        val sizes = packagesFor(pack).map { it to fetchContentLength(it.url) }
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
