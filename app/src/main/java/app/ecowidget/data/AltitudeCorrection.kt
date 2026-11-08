package app.ecowidget.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * Correction d'altitude des températures prévues.
 * `modelM` : altitude du point de prévision (modèle numérique de terrain d'Open-Meteo) ;
 * `userM` : altitude de l'utilisateur ; `offsetC` : décalage appliqué (négatif si l'on habite plus haut) ;
 * `applied` : true quand les températures affichées sont les valeurs corrigées.
 */
data class AltitudeInfo(
    val modelM: Double,
    val userM: Double,
    val offsetC: Double,
    val applied: Boolean,
)

object AltitudeCorrection {

    /** Gradient standard : −0,65 °C par 100 m. */
    const val LAPSE_C_PER_M = 0.0065

    /** Posée sur la réponse quand elle a été demandée SANS altitude : valeurs brutes du point de prévision. */
    const val RAW_KEY = "_raw_elevation"

    private val CURRENT_AND_HOURLY = listOf("temperature_2m", "apparent_temperature", "dew_point_2m", "wet_bulb_temperature_2m")
    private val DAILY = listOf(
        "temperature_2m_max", "temperature_2m_min", "temperature_2m_mean",
        "apparent_temperature_max", "apparent_temperature_min", "apparent_temperature_mean",
    )

    /** Décalage de température (°C) pour passer du point de prévision à l'altitude de l'utilisateur. */
    fun offset(modelM: Double, userM: Double): Double = -(userM - modelM) * LAPSE_C_PER_M

    /**
     * Prépare la réponse Open-Meteo : si `corrected`, décale les températures sur place.
     * Retourne null quand la correction n'est pas possible (altitude non renseignée, ancienne réponse).
     */
    fun apply(json: JSONObject, userM: Double?, corrected: Boolean): AltitudeInfo? {
        if (userM == null || !json.optBoolean(RAW_KEY, false)) return null
        val modelM = json.optDouble("elevation", Double.NaN)
        if (modelM.isNaN()) return null
        val d = offset(modelM, userM)
        if (corrected) {
            shift(json.optJSONObject("current"), CURRENT_AND_HOURLY, d)
            shift(json.optJSONObject("hourly"), CURRENT_AND_HOURLY, d)
            shift(json.optJSONObject("daily"), DAILY, d)
        }
        return AltitudeInfo(modelM = modelM, userM = userM, offsetC = d, applied = corrected)
    }

    private fun shift(section: JSONObject?, keys: List<String>, d: Double) {
        if (section == null) return
        for (k in keys) {
            when (val v = section.opt(k)) {
                is JSONArray -> for (i in 0 until v.length()) {
                    val x = v.optDouble(i, Double.NaN)
                    if (!x.isNaN()) v.put(i, x + d)
                }
                is Number -> section.put(k, v.toDouble() + d)
                else -> {}
            }
        }
    }
}
