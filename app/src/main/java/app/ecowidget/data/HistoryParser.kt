package app.ecowidget.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Lit l'objet `data` de GET /api/v3/device/history.
 * Forme attendue : data.outdoor.temperature.list = { "1731000000": "2.8", ... } (clé = timestamp Unix).
 */
object HistoryParser {

    const val WINDOW_SEC = 24 * 3600L

    fun series(data: JSONObject, group: String, key: String): List<SeriesPoint> {
        val list = data.optJSONObject(group)?.optJSONObject(key)?.optJSONObject("list") ?: return emptyList()
        val out = ArrayList<SeriesPoint>(list.length())
        val keys = list.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val t = k.toLongOrNull() ?: continue
            val raw = list.opt(k)
            val v = when (raw) {
                is Number -> raw.toDouble()
                is String -> raw.trim().toDoubleOrNull()
                else -> null
            } ?: continue
            out.add(SeriesPoint(t, v))
        }
        out.sortBy { it.tSec }
        return out
    }

    fun parse(data: JSONObject, snap: WeatherSnapshot?, nowSec: Long, zone: ZoneId): Derived {
        val temp = series(data, "outdoor", "temperature")
            .filter { it.tSec >= nowSec - WINDOW_SEC && it.tSec <= nowSec + 600 }
            .toMutableList()
        // On termine la courbe sur la mesure en direct.
        val liveT = snap?.outdoorTemp
        if (liveT != null) {
            val liveAt = snap?.observedAtSec ?: nowSec
            if (temp.isEmpty() || liveAt > temp.last().tSec) temp.add(SeriesPoint(liveAt, liveT))
        }

        val press = series(data, "pressure", "relative").toMutableList()
        val liveP = snap?.pressure
        if (liveP != null) {
            val liveAt = snap?.observedAtSec ?: nowSec
            if (press.isEmpty() || liveAt > press.last().tSec) press.add(SeriesPoint(liveAt, liveP))
        }

        val tempNow = liveT ?: temp.lastOrNull()?.v
        val temp1h = valueAt(temp, nowSec - 3600)
        val tempTrend = if (tempNow != null && temp1h != null) tempNow - temp1h else null

        val pressNow = liveP ?: press.lastOrNull()?.v
        val press3h = valueAt(press, nowSec - 3 * 3600)
        val pressTrend = if (pressNow != null && press3h != null) pressNow - press3h else null

        val midnight = Instant.ofEpochSecond(nowSec).atZone(zone).toLocalDate().atStartOfDay(zone).toEpochSecond()
        val today = temp.filter { it.tSec >= midnight }
        return Derived(
            tempSeries = temp,
            tempTrend1h = tempTrend,
            pressTrend3h = pressTrend,
            dayMin = today.minOfOrNull { it.v },
            dayMax = today.maxOfOrNull { it.v },
            pressSeries = press,
            windSeries = series(data, "wind", "wind_speed"),
            gustSeries = series(data, "wind", "wind_gust"),
            humSeries = series(data, "outdoor", "humidity"),
            dewSeries = series(data, "outdoor", "dew_point"),
            rainDaySeries = series(data, "rainfall", "daily"),
        )
    }

    /** Interpolation linéaire ; null si la série ne couvre pas cet instant (tolérance 40 min). */
    fun valueAt(series: List<SeriesPoint>, tSec: Long): Double? {
        if (series.isEmpty()) return null
        val tol = 40 * 60L
        if (tSec < series.first().tSec - tol || tSec > series.last().tSec + tol) return null
        if (tSec <= series.first().tSec) return series.first().v
        if (tSec >= series.last().tSec) return series.last().v
        for (i in 0 until series.size - 1) {
            val a = series[i]
            val b = series[i + 1]
            if (tSec in a.tSec..b.tSec) {
                if (b.tSec == a.tSec) return a.v
                val f = (tSec - a.tSec).toDouble() / (b.tSec - a.tSec)
                return a.v + (b.v - a.v) * f
            }
        }
        return null
    }
}
