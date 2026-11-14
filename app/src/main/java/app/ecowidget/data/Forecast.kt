package app.ecowidget.data

import androidx.annotation.DrawableRes
import app.ecowidget.R
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

data class HourPoint(
    val tSec: Long,
    val temp: Double?,
    val code: Int?,
    val isDay: Boolean,
    val popPct: Double?,
)

/** Pluie prévue sur une plage : `startMin` minutes après maintenant, pendant `minutes`, à `mmPerHour`. */
data class RainStep(val startMin: Int, val minutes: Int, val mmPerHour: Double)

/** Prévisions Open-Meteo : valeurs de l'en-tête + accès générique aux autres variables (voir [OmCatalog]). */
data class Forecast(
    val code: Int?,
    val isDay: Boolean?,
    val dayMax: Double?,
    val dayMin: Double?,
    val sunriseSec: Long?,
    val sunsetSec: Long?,
    /** Minutes avant la prochaine pluie (0 = en cours) ; null = rien sur l'horizon analysé. */
    val nextRainMin: Int?,
    /** Si de la pluie est en cours dans le modèle : minutes avant la fin ; null = pas de fin dans l'horizon. */
    val rainEndMin: Int?,
    /** false si aucune donnée par pas de 15 min n'est disponible. */
    val hasNowcast: Boolean,
    /** Durée réellement analysée (minutes). */
    val nowcastHorizonMin: Int,
    /** « AROME » (endpoint Météo-France) ou « modèle » (repli sur l'endpoint générique). */
    val nowcastSource: String,
    /** Intensité prévue à court terme, découpée en plages (sert aux barres de pluie du widget). */
    val rainSteps: List<RainStep>,
    val hours: List<HourPoint>,
    val raw: RawData?,
    val air: RawData?,
    /** Correction d'altitude des températures ; null si l'altitude n'est pas renseignée. */
    val altitude: AltitudeInfo? = null,
    /** Cumuls (mm) des prochains créneaux de 15 min selon chaque source : sert au diagnostic des réglages. */
    val rainDebug: String = "",
)

/** Correspondance des codes météo WMO utilisés par Open-Meteo. */
object WeatherCodes {
    fun label(code: Int?, isDay: Boolean = true): String = when (code) {
        0 -> if (isDay) "Dégagé" else "Ciel clair"
        1 -> "Plutôt dégagé"
        2 -> "Éclaircies"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        51, 53, 55 -> "Bruine"
        56, 57 -> "Bruine verglaçante"
        61 -> "Pluie faible"
        63 -> "Pluie"
        65 -> "Forte pluie"
        66, 67 -> "Pluie verglaçante"
        71 -> "Neige faible"
        73 -> "Neige"
        75 -> "Forte neige"
        77 -> "Grésil"
        80, 81 -> "Averses"
        82 -> "Fortes averses"
        85, 86 -> "Averses de neige"
        95 -> "Orage"
        96, 99 -> "Orage et grêle"
        else -> "—"
    }

    @DrawableRes
    fun icon(code: Int?, isDay: Boolean = true): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_sun else R.drawable.ic_wx_moon
        2 -> R.drawable.ic_wx_partly
        3 -> R.drawable.ic_wx_cloud
        45, 48 -> R.drawable.ic_wx_fog
        51, 53, 55, 56, 57 -> R.drawable.ic_wx_drizzle
        61, 63, 65, 66, 67, 80, 81, 82 -> R.drawable.ic_cloud_rain
        71, 73, 75, 77, 85, 86 -> R.drawable.ic_wx_snow
        95, 96, 99 -> R.drawable.ic_wx_storm
        else -> R.drawable.ic_wx_cloud
    }
}

internal fun JSONArray.dbl(i: Int): Double? =
    if (i < 0 || i >= length() || isNull(i)) null else optDouble(i).takeIf { !it.isNaN() }

/** Résultat de l'analyse de la pluie par pas de 15 min. */
private data class RainScan(val nextMin: Int?, val endMin: Int?, val horizonMin: Int, val steps: List<RainStep>)

/** Lit la réponse de /v1/forecast (timeformat=unixtime, timezone=auto). */
object ForecastParser {

    private const val WET_MM = 0.1

    fun parse(json: JSONObject, nowSec: Long, nowcast: JSONObject? = null, air: JSONObject? = null): Forecast {
        val raw = RawData(json, nowSec)
        val hourly = json.optJSONObject("hourly")
        val hTimes = hourly?.optJSONArray("time")

        fun hAt(name: String, i: Int): Double? = hourly?.optJSONArray(name)?.dbl(i)

        val hours = ArrayList<HourPoint>()
        if (hTimes != null) {
            for (i in 0 until hTimes.length()) {
                val t = hTimes.optLong(i)
                if (t <= nowSec) continue
                if (hours.size >= 8) break
                hours.add(
                    HourPoint(
                        tSec = t,
                        temp = hAt("temperature_2m", i),
                        code = hAt("weather_code", i)?.roundToInt(),
                        isDay = (hAt("is_day", i) ?: 1.0) >= 0.5,
                        popPct = hAt("precipitation_probability", i),
                    ),
                )
            }
        }

        // Pluie à court terme : on croise l'endpoint Météo-France (AROME, 6 h) et la réponse générique (même
        // modèle que les barres « heure par heure »). Par créneau on garde le plus pluvieux des deux : une seule
        // source sèche ne doit pas annoncer « plus de pluie » quand l'autre en prévoit.
        val arome = quarterHours(nowcast)
        val generic = quarterHours(json)
        val aromeOk = arome.values.any { it != null }
        val genericOk = generic.values.any { it != null }
        val source = when {
            aromeOk && genericOk -> "AROME + modèle"
            aromeOk -> "AROME"
            else -> "modèle"
        }
        val scan = scanRain(arome, generic, nowSec, if (aromeOk) 24 else 12)

        return Forecast(
            code = raw.number(OmSection.CURRENT, "weather_code")?.roundToInt(),
            isDay = raw.number(OmSection.CURRENT, "is_day")?.let { it >= 0.5 },
            dayMax = raw.number(OmSection.DAILY, "temperature_2m_max"),
            dayMin = raw.number(OmSection.DAILY, "temperature_2m_min"),
            sunriseSec = raw.number(OmSection.DAILY, "sunrise")?.toLong(),
            sunsetSec = raw.number(OmSection.DAILY, "sunset")?.toLong(),
            nextRainMin = scan?.nextMin,
            rainEndMin = scan?.endMin,
            hasNowcast = scan != null,
            nowcastHorizonMin = scan?.horizonMin ?: 0,
            nowcastSource = source,
            rainSteps = scan?.steps ?: emptyList(),
            hours = hours,
            raw = raw,
            air = air?.let { RawData(it, nowSec) },
            rainDebug = debugLine(arome, generic, nowSec),
        )
    }

    /** Cumuls de `minutely_15.precipitation` : fin du créneau (s) → mm, `null` quand la valeur manque. */
    private fun quarterHours(json: JSONObject?): Map<Long, Double?> {
        val m = json?.optJSONObject("minutely_15") ?: return emptyMap()
        val times = m.optJSONArray("time") ?: return emptyMap()
        val precip = m.optJSONArray("precipitation") ?: return emptyMap()
        val out = LinkedHashMap<Long, Double?>()
        for (i in 0 until times.length()) out[times.optLong(i)] = precip.dbl(i)
        return out
    }

    private fun mm1(v: Double?): String = if (v == null) "—" else String.format(Locale.FRANCE, "%.1f", v)

    /**
     * Parcourt les créneaux de 15 min à venir. `minutely_15.precipitation` est le cumul des 15 min PRÉCÉDENTES :
     * l'horodatage est donc la fin du créneau. Une valeur absente (null) n'est PAS « sec » : le créneau est ignoré.
     */
    private fun scanRain(arome: Map<Long, Double?>, generic: Map<Long, Double?>, nowSec: Long, maxSlots: Int): RainScan? {
        var scanned = 0
        var next: Int? = null
        var end: Int? = null
        var ended = false
        val steps = ArrayList<RainStep>()
        for (slotEnd in (arome.keys + generic.keys).filter { it > nowSec }.sorted()) {
            val mm = listOfNotNull(arome[slotEnd], generic[slotEnd]).maxOrNull() ?: continue
            if (scanned >= maxSlots) break
            scanned++
            // Plage restante du créneau (le créneau en cours a déjà commencé).
            val effStart = maxOf(slotEnd - 900, nowSec)
            val minutes = ((slotEnd - effStart) / 60).toInt()
            if (minutes > 0) steps.add(RainStep(((effStart - nowSec) / 60).toInt(), minutes, mm * 4.0))
            if (ended) continue
            val wet = mm >= WET_MM
            val startsIn = ((slotEnd - 900 - nowSec) / 60).toInt().coerceAtLeast(0)
            if (next == null) {
                if (wet) next = startsIn
            } else if (!wet) {
                end = startsIn
                ended = true
            }
        }
        if (scanned == 0) return null
        return RainScan(next, end, scanned * 15, steps)
    }

    /** « AROME 0,3 0,3 0,0 · modèle 0,2 … » : les 8 prochains créneaux de 15 min, pour comprendre un écart entre sources. */
    private fun debugLine(arome: Map<Long, Double?>, generic: Map<Long, Double?>, nowSec: Long): String {
        val ends = (arome.keys + generic.keys).filter { it > nowSec }.sorted().take(8)
        if (ends.isEmpty()) return ""
        return "Pluie 15 min (mm) · AROME " + ends.joinToString(" ") { mm1(arome[it]) } +
            " · modèle " + ends.joinToString(" ") { mm1(generic[it]) }
    }
}
