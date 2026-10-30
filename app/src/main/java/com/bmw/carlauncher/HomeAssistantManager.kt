package com.bmw.carlauncher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class EnergyData(
    val batterySoc: Int = 0,
    val batteryVoltage: Float = 0f,
    val batteryPower: Float = 0f,
    val solarPower: Float = 0f,
    val houseLoad: Float = 0f,
    val isSuccess: Boolean = false
)

object HomeAssistantManager {
    private const val PREFS_NAME = "ha_settings"
    private const val DEFAULT_URL = "http://eugenmarianneagu.home.ro:7642/"
    private const val DEFAULT_TOKEN = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiIyNmVjOGQ5YjQ1NGM0ZjAxYTc5ZGU2ZjM5NjBmOTBiNiIsImlhdCI6MTc5MjM0MTc1OCwiZXhwIjoyMTA3NzAxNzU4fQ.eClrcsU_M-84hHkXmTRxV5sgu6TXsxH-yGAH2R_Ui4s"

    // Entity IDs
    private const val ENTITY_BATTERY_SOC = "sensor.afara_bms_ble_proxy_baterie_state_of_charge"
    private const val ENTITY_BATTERY_VOLTAGE = "sensor.afara_bms_ble_proxy_baterie_total_voltage"
    private const val ENTITY_BATTERY_POWER = "sensor.afara_bms_ble_proxy_baterie_power"
    private const val ENTITY_SOLAR_POWER = "sensor.afara_datalogger_invertor_putere_incarcare_pv"
    private const val ENTITY_HOUSE_LOAD = "sensor.afara_datalogger_invertor_putere_activa_consumata"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var lastData: EnergyData = EnergyData()
        private set

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("ha_url", DEFAULT_URL) ?: DEFAULT_URL
    }

    fun getToken(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString("ha_token", DEFAULT_TOKEN) ?: DEFAULT_TOKEN
    }

    fun fetchEnergyData(context: Context, callback: (EnergyData) -> Unit) {
        val baseUrl = getBaseUrl(context).trim().trimEnd('/')
        val token = getToken(context).trim()

        executor.execute {
            try {
                var soc = lastData.batterySoc
                var voltage = lastData.batteryVoltage
                var batPower = lastData.batteryPower
                var solarPower = lastData.solarPower
                var houseLoad = lastData.houseLoad

                // Fetch states
                val socVal = fetchEntityState(baseUrl, token, ENTITY_BATTERY_SOC)
                if (socVal != null) soc = socVal.toFloatOrNull()?.toInt() ?: soc

                val voltVal = fetchEntityState(baseUrl, token, ENTITY_BATTERY_VOLTAGE)
                if (voltVal != null) voltage = voltVal.toFloatOrNull() ?: voltage

                val batPowVal = fetchEntityState(baseUrl, token, ENTITY_BATTERY_POWER)
                if (batPowVal != null) batPower = batPowVal.toFloatOrNull() ?: batPower

                val solPowVal = fetchEntityState(baseUrl, token, ENTITY_SOLAR_POWER)
                if (solPowVal != null) solarPower = solPowVal.toFloatOrNull() ?: solarPower

                val houseVal = fetchEntityState(baseUrl, token, ENTITY_HOUSE_LOAD)
                if (houseVal != null) houseLoad = houseVal.toFloatOrNull() ?: houseLoad

                val result = EnergyData(
                    batterySoc = soc,
                    batteryVoltage = voltage,
                    batteryPower = batPower,
                    solarPower = solarPower,
                    houseLoad = houseLoad,
                    isSuccess = true
                )
                lastData = result

                mainHandler.post { callback(result) }
            } catch (e: Exception) {
                mainHandler.post { callback(lastData.copy(isSuccess = false)) }
            }
        }
    }

    private fun fetchEntityState(baseUrl: String, token: String, entityId: String): String? {
        val endpoint = "$baseUrl/api/states/$entityId"
        val url = URL(endpoint)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Content-Type", "application/json")
        }

        if (conn.responseCode in 200..299) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()
            conn.disconnect()

            val json = JSONObject(sb.toString())
            return json.optString("state")
        } else {
            conn.disconnect()
            return null
        }
    }
}
