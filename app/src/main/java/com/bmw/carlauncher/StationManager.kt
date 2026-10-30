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
            "https://www.rockfm.ro/favicon.ico"
        ),
        RadioStation(
            "Magic FM",
            "Soft Music",
            "https://live.magicfm.ro/magicfm.aacp",
            "https://www.magicfm.ro/favicon.ico"
        ),
        RadioStation(
            "Europa FM",
            "Stiri si Muzica",
            "https://astreaming.edi.ro:8443/EuropaFM_aac",
            "https://www.europafm.ro/wp-content/themes/europafm/images/logo.png"
        ),
        RadioStation(
            "Kiss FM",
            "Hit Radio",
            "https://live.kissfm.ro/kissfm.aacp",
            "https://www.kissfm.ro/favicon.ico"
        )
    )

    fun getStations(context: Context): MutableList<RadioStation> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_STATIONS, null) ?: return defaultStations.toMutableList()
        val list = mutableListOf<RadioStation>()
        try {
            val arr = JSONArray(jsonStr)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    RadioStation(
                        obj.getString("name"),
                        obj.optString("genre", "Live Radio"),
                        obj.getString("url"),
                        obj.optString("logoUrl", "")
                    )
                )
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
