package tw.umaya.tracker.ui

data class LoraDevicePoint(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val lastHeardMillis: Long? = null,
    val batteryPercent: Int? = null,
    val rssi: Int? = null,
    val snr: Float? = null,
    val isSelf: Boolean = false,
)

fun LoraDevicePoint.titleText(): String =
    if (isSelf) "$name（本機）" else name

fun LoraDevicePoint.detailText(nowMillis: Long = System.currentTimeMillis()): String {
    val parts = mutableListOf<String>()
    lastHeardMillis?.let { last ->
        val ageMinutes = ((nowMillis - last).coerceAtLeast(0L) / 60_000L).toInt()
        parts.add("最後收到 ${ageMinutes} 分鐘前")
    }
    batteryPercent?.let { parts.add("電量 $it%") }
    rssi?.let { parts.add("RSSI $it") }
    snr?.let { parts.add("SNR %.1f".format(it)) }
    return parts.joinToString(" / ")
}
