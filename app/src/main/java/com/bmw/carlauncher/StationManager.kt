package com.bmw.carlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class RadioStation(
    val name: String,
    val genre: String,
    val url: String,
    val logoUrl: String
)

object StationManager {
    private const val PREFS_NAME = "agama_radio_prefs"
    private const val KEY_STATIONS = "stations_json"
    private const val KEY_LAST_INDEX = "last_station_index"

    private val defaultStations = listOf(
        RadioStation(
            "Rock FM",
            "Classic Rock",
            "https://live.rockfm.ro/rockfm.aacp",
            "https://upload.wikimedia.org/wikipedia/ro/6/6a/Logo_Rock_FM_Rom%C3%A2nia.png"
        ),
        RadioStation(
            "Magic FM",
            "Soft Music",
            "https://live.magicfm.ro/magicfm.aacp",
            "https://upload.wikimedia.org/wikipedia/commons/9/94/MagicFM-LogoNou.png"
        ),
        RadioStation(
            "Europa FM",
            "Stiri si Muzica",
            "https://astreaming.edi.ro:8443/EuropaFM_aac",
            "https://upload.wikimedia.org/wikipedia/commons/6/6c/Logo_Europa_FM_2018.png"
        ),
        RadioStation(
            "Kiss FM",
            "Hit Radio",
            "https://live.kissfm.ro/kissfm.aacp",
            "https://www.kissfm.ro/apple-touch-icon.png"
        ),
        RadioStation(
            "Radio Liberty Oldies",
            "Retro",
            "https://sonicssl.namehost.ro/7790/stream",
            "https://www.radioliberty.ro/images/stations/1744356753.webp"
        )
    )

    private fun upgradeLegacyLogoUrl(name: String, logoUrl: String): String {
        return when {
            name.equals("Europa FM", ignoreCase = true) &&
                (logoUrl.isBlank() || logoUrl.contains("themes/europafm/images/logo.png")) ->
                "https://upload.wikimedia.org/wikipedia/commons/6/6c/Logo_Europa_FM_2018.png"
            name.equals("Rock FM", ignoreCase = true) &&
                (logoUrl.isBlank() || logoUrl.endsWith("favicon.ico")) ->
                "https://upload.wikimedia.org/wikipedia/ro/6/6a/Logo_Rock_FM_Rom%C3%A2nia.png"
            name.equals("Magic FM", ignoreCase = true) &&
                (logoUrl.isBlank() || logoUrl.endsWith("favicon.ico")) ->
                "https://upload.wikimedia.org/wikipedia/commons/9/94/MagicFM-LogoNou.png"
            name.equals("Kiss FM", ignoreCase = true) &&
                (logoUrl.isBlank() || logoUrl.endsWith("favicon.ico")) ->
                "https://www.kissfm.ro/apple-touch-icon.png"
            else -> logoUrl
        }
    }

    fun getStations(context: Context): MutableList<RadioStation> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_STATIONS, null) ?: return defaultStations.toMutableList()
        val list = mutableListOf<RadioStation>()
        var migrated = false
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val name = obj.getString("name")
                val rawLogo = obj.optString("logoUrl", "")
                val upgradedLogo = upgradeLegacyLogoUrl(name, rawLogo)
                if (upgradedLogo != rawLogo) migrated = true
                list.add(
                    RadioStation(
                        name,
                        obj.optString("genre", "Live Radio"),
                        obj.getString("url"),
                        upgradedLogo
                    )
                )
            }
            if (list.none { it.name.equals("Radio Liberty Oldies", ignoreCase = true) }) {
                list.add(defaultStations.last())
                migrated = true
            }
            if (migrated) {
                saveStations(context, list)
            }
        } catch (e: Exception) {
            return defaultStations.toMutableList()
        }
        return if (list.isEmpty()) defaultStations.toMutableList() else list
    }

    fun saveStations(context: Context, stations: List<RadioStation>) {
        val arr = JSONArray()
        for (s in stations) {
            val obj = JSONObject()
            obj.put("name", s.name)
            obj.put("genre", s.genre)
            obj.put("url", s.url)
            obj.put("logoUrl", s.logoUrl)
            arr.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATIONS, arr.toString())
            .apply()
    }

    fun getLastIndex(context: Context): Int {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LAST_INDEX, 0)
    }

    fun saveLastIndex(context: Context, index: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LAST_INDEX, index)
            .apply()
    }
}
