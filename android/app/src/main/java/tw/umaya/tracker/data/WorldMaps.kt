package tw.umaya.tracker.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * 世界各地離線地圖: the Mapsforge v5 map server (download.mapsforge.org), browsed continent →
 * country (→ region for big countries). Each entry is one .map file the app renders with the
 * 魯地圖 theme like any imported map.
 */
object WorldMaps {
    const val BASE = "https://download.mapsforge.org/maps/v5/"

    /** [path] relative to [BASE] ("asia/", "asia/japan/kanto.map"); [bytes] approximate (from the listing). */
    data class Entry(val path: String, val name: String, val isDir: Boolean, val sizeText: String, val bytes: Long) {
        val label: String get() = label(name)
    }

    private val row = Regex("""<a href="([^"?/][^"]*)">[^<]*</a>\s*</td><td align="right">[^<]*</td><td align="right">\s*([^<]*?)\s*</td>""")

    suspend fun list(path: String): List<Entry> = withContext(Dispatchers.IO) {
        val html = (URL(BASE + path).openConnection() as HttpURLConnection).run {
            connectTimeout = 15_000; readTimeout = 30_000
            if (responseCode !in 200..299) error("HTTP $responseCode")
            inputStream.bufferedReader().use { it.readText() }
        }
        row.findAll(html).mapNotNull { m ->
            val href = m.groupValues[1]; val size = m.groupValues[2]
            val isDir = href.endsWith("/")
            if (!isDir && !href.endsWith(".map")) return@mapNotNull null
            val name = href.removeSuffix("/").removeSuffix(".map")
            Entry(path + href, name, isDir, if (isDir) "" else size, parseSize(size))
        }.filter { it.name != "world" || path.isNotEmpty() }.toList()
    }

    private fun parseSize(s: String): Long {
        val n = s.dropLast(1).toDoubleOrNull() ?: return 0
        return (n * when (s.lastOrNull()) { 'K' -> 1e3; 'M' -> 1e6; 'G' -> 1e9; else -> 1.0 }).toLong()
    }

    /** 中文名 for continents and many countries (search matches both); otherwise the server's name, prettified. */
    fun label(name: String): String {
        val en = name.split('-', '_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
        return ZH[name]?.let { "$it $en" } ?: en
    }

    private val ZH = mapOf(
        "africa" to "非洲", "asia" to "亞洲", "australia-oceania" to "大洋洲", "central-america" to "中美洲", "europe" to "歐洲",
        "north-america" to "北美洲", "russia" to "俄羅斯", "south-america" to "南美洲",
        "taiwan" to "台灣", "japan" to "日本", "china" to "中國", "south-korea" to "南韓", "north-korea" to "北韓", "mongolia" to "蒙古",
        "nepal" to "尼泊爾", "bhutan" to "不丹", "india" to "印度", "pakistan" to "巴基斯坦", "bangladesh" to "孟加拉", "sri-lanka" to "斯里蘭卡",
        "myanmar" to "緬甸", "thailand" to "泰國", "laos" to "寮國", "cambodia" to "柬埔寨", "vietnam" to "越南", "malaysia" to "馬來西亞",
        "singapore" to "新加坡", "indonesia" to "印尼", "philippines" to "菲律賓", "kazakhstan" to "哈薩克", "kyrgyzstan" to "吉爾吉斯",
        "tajikistan" to "塔吉克", "uzbekistan" to "烏茲別克", "turkmenistan" to "土庫曼", "afghanistan" to "阿富汗", "iran" to "伊朗",
        "iraq" to "伊拉克", "israel-and-palestine" to "以色列與巴勒斯坦", "jordan" to "約旦", "lebanon" to "黎巴嫩", "syria" to "敘利亞",
        "yemen" to "葉門", "armenia" to "亞美尼亞", "azerbaijan" to "亞塞拜然", "georgia" to "喬治亞", "turkey" to "土耳其",
        "gcc-states" to "波斯灣國家", "east-timor" to "東帝汶", "maldives" to "馬爾地夫",
        "australia" to "澳洲", "new-zealand" to "紐西蘭", "fiji" to "斐濟", "papua-new-guinea" to "巴布亞紐幾內亞", "new-caledonia" to "新喀里多尼亞",
        "france" to "法國", "germany" to "德國", "italy" to "義大利", "spain" to "西班牙", "portugal" to "葡萄牙", "switzerland" to "瑞士",
        "austria" to "奧地利", "netherlands" to "荷蘭", "belgium" to "比利時", "luxembourg" to "盧森堡", "great-britain" to "英國",
        "ireland-and-northern-ireland" to "愛爾蘭", "iceland" to "冰島", "norway" to "挪威", "sweden" to "瑞典", "finland" to "芬蘭",
        "denmark" to "丹麥", "poland" to "波蘭", "czech-republic" to "捷克", "slovakia" to "斯洛伐克", "hungary" to "匈牙利",
        "slovenia" to "斯洛維尼亞", "croatia" to "克羅埃西亞", "bosnia-herzegovina" to "波士尼亞", "serbia" to "塞爾維亞",
        "montenegro" to "蒙特內哥羅", "albania" to "阿爾巴尼亞", "macedonia" to "北馬其頓", "greece" to "希臘", "bulgaria" to "保加利亞",
        "romania" to "羅馬尼亞", "moldova" to "摩爾多瓦", "ukraine" to "烏克蘭", "belarus" to "白俄羅斯", "lithuania" to "立陶宛",
        "latvia" to "拉脫維亞", "estonia" to "愛沙尼亞", "cyprus" to "賽普勒斯", "malta" to "馬爾他", "andorra" to "安道爾",
        "liechtenstein" to "列支敦斯登", "monaco" to "摩納哥", "kosovo" to "科索沃", "faroe-islands" to "法羅群島",
        "us" to "美國", "canada" to "加拿大", "mexico" to "墨西哥", "greenland" to "格陵蘭", "alaska" to "阿拉斯加",
        "guatemala" to "瓜地馬拉", "belize" to "貝里斯", "honduras" to "宏都拉斯", "nicaragua" to "尼加拉瓜", "costa-rica" to "哥斯大黎加",
        "panama" to "巴拿馬", "cuba" to "古巴", "jamaica" to "牙買加", "haiti-and-domrep" to "海地與多明尼加", "el-salvador" to "薩爾瓦多",
        "argentina" to "阿根廷", "chile" to "智利", "peru" to "秘魯", "bolivia" to "玻利維亞", "ecuador" to "厄瓜多", "colombia" to "哥倫比亞",
        "venezuela" to "委內瑞拉", "brazil" to "巴西", "paraguay" to "巴拉圭", "uruguay" to "烏拉圭", "guyana" to "蓋亞那", "suriname" to "蘇利南",
        "morocco" to "摩洛哥", "algeria" to "阿爾及利亞", "tunisia" to "突尼西亞", "egypt" to "埃及", "ethiopia" to "衣索比亞",
        "kenya" to "肯亞", "tanzania" to "坦尚尼亞", "uganda" to "烏干達", "rwanda" to "盧安達", "south-africa" to "南非",
        "namibia" to "納米比亞", "botswana" to "波札那", "zimbabwe" to "辛巴威", "zambia" to "尚比亞", "madagascar" to "馬達加斯加",
        "malawi" to "馬拉威", "mozambique" to "莫三比克", "lesotho" to "賴索托", "nigeria" to "奈及利亞", "ghana" to "迦納",
        "cameroon" to "喀麥隆", "senegal-and-gambia" to "塞內加爾與甘比亞", "mali" to "馬利", "niger" to "尼日", "sudan" to "蘇丹",
        "canary-islands" to "加那利群島", "cape-verde" to "維德角",
    )
}
