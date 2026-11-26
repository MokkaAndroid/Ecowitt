package app.ecowidget.data

import org.json.JSONObject
import kotlin.math.abs

/**
 * Diagnostic : quel modèle de prévision colle le mieux aux mesures de la station ?
 * Les prévisions passées de chaque modèle (API « Previous Runs » d'Open-Meteo : dernier run et prévu la veille)
 * sont comparées à l'historique Ecowitt, heure par heure : pluie (cumul, détection, fausses alertes) et température.
 */
object ModelCheck {

    private class Model(val id: String, val label: String)

    private val MODELS = listOf(
        Model("meteofrance_arome_france_hd", "AROME HD"),
        Model("icon_d2", "ICON-D2"),
        Model("meteoswiss_icon_ch1", "ICON-CH1"),
        Model("icon_eu", "ICON-EU"),
        Model("best_match", "Auto Open-Meteo"),
    )

    /** Pluie : une heure est « mouillée » à partir de 0,2 mm. */
    internal const val WET = 0.2
    internal const val HOUR = 3600L

    internal class Station(val temp: Map<Long, Double>, val rain: Map<Long, Double>?, val days: Double)

    private class Result(val label: String, val lead: String, val n: Int, val line: String, val csi: Double?, val mae: Double?)

    /** Profondeurs proposées (jours). */
    val DEPTHS = listOf(7, 14, 30, 60)

    fun run(c: Credentials, loc: Location, days: Int = 7): List<String> {
        val now = System.currentTimeMillis() / 1000
        val st = try {
            fetchStation(c, now, days)
        } catch (e: Exception) {
            return listOf("Historique station indisponible : " + EcowittClient.describe(e))
        }
        val pastDays = kotlin.math.ceil(st.days).toInt().coerceIn(2, 90)
        val out = ArrayList<String>()
        val rainTotal = st.rain?.values?.sum()
        val rainHours = st.rain?.values?.count { it >= WET }
        out.add(
            "Période : " + fmt(st.days, 1) + " j" + (if (st.days < days - 1.0) " (demandé : $days j, l'historique Ecowitt s'arrête avant)" else "") + " · station : " +
                (if (rainTotal != null) fmt(rainTotal, 1) + " mm, " + rainHours + " h de pluie" else "pluie indisponible") +
                " · « veille » = prévu environ 24 h avant",
        )
        val results = ArrayList<Result>()
        for (m in MODELS) {
            try {
                val data = OpenMeteoClient.fetchPreviousRuns(loc, m.id, pastDays)
                val h = data.optJSONObject("hourly")
                if (h == null) {
                    out.add(m.label + " : pas de données")
                    continue
                }
                results.add(score(m.label, "dernier run", h, "temperature_2m", "precipitation", st, now))
                results.add(score(m.label, "veille", h, "temperature_2m_previous_day1", "precipitation_previous_day1", st, now))
            } catch (e: Exception) {
                out.add(m.label + " : " + OpenMeteoClient.describe(e))
            }
        }
        for (r in results) out.add(r.line)

        // Conclusion : meilleur score pluie et meilleur écart de température, sur les prévisions de la veille.
        val veille = results.filter { it.lead == "veille" && it.n > 0 }
        val bestRain = veille.filter { it.csi != null }.maxByOrNull { it.csi!! }
        val bestTemp = veille.filter { it.mae != null }.minByOrNull { it.mae!! }
        if (bestRain != null) out.add("Pluie (veille) : meilleur score = " + bestRain.label + " (" + pct(bestRain.csi!!) + ")")
        if (bestTemp != null) out.add("Température (veille) : plus proche = " + bestTemp.label + " (écart moyen " + fmt(bestTemp.mae!!, 1) + "°)")
        return out
    }

    /** L'API Ecowitt limite la plage d'une requête (30 min : ~7 jours) : on découpe en tranches de 6 jours qui se recouvrent d'1 h. */
    private const val CHUNK_SEC = 6 * 86400L
    private const val PAUSE_MS = 1_100L

    internal fun fetchStation(c: Credentials, now: Long, days: Int): Station {
        val limitStart = now - days * 86400L
        val temp = HashMap<Long, Double>()
        val rain = HashMap<Long, Double>()
        var groups: String? = null
        var end = now + 3600
        var k = 0
        while (end > limitStart + 3600) {
            val start = maxOf(limitStart, end - CHUNK_SEC - 3600)
            try {
                val part: Station
                if (groups == null) {
                    var found: Station? = null
                    var last: Exception? = null
                    for (g in listOf("outdoor,rainfall,rainfall_piezo", "outdoor,rainfall", "outdoor,rainfall_piezo", "outdoor")) {
                        try {
                            found = parseStation(EcowittClient.fetchHistoryRange(c, start, end, g))
                            groups = g
                            break
                        } catch (e: Exception) {
                            last = e
                        }
                    }
                    part = found ?: throw (last ?: EcowittClient.ApiException("Historique indisponible"))
                } else {
                    Thread.sleep(PAUSE_MS)
                    part = parseStation(EcowittClient.fetchHistoryRange(c, start, end, groups))
                }
                temp.putAll(part.temp)
                part.rain?.let { rain.putAll(it) }
            } catch (e: Exception) {
                // Première tranche en échec : rien à comparer. Sinon on garde ce qu'on a (l'historique s'arrête là).
                if (k == 0) throw e
                break
            }
            end = start + 3600
            k++
        }
        if (temp.isEmpty()) throw EcowittClient.ApiException("Aucune température dans l'historique")
        val spanDays = (now - temp.keys.min()) / 86400.0
        return Station(temp, rain.takeIf { it.isNotEmpty() }, minOf(spanDays, days.toDouble()))
    }

    private fun parseStation(data: JSONObject): Station {
        val temp = HistoryParser.series(data, "outdoor", "temperature")
        if (temp.isEmpty()) throw EcowittClient.ApiException("Aucune température dans l'historique")
        // Pluie : cumul du jour (remis à zéro à minuit), la série qui a le plus plu est la bonne sonde.
        val rainSeries = listOf("rainfall", "rainfall_piezo")
            .map { HistoryParser.series(data, it, "daily") }
            .filter { it.size >= 4 }
            .maxByOrNull { s -> s.maxOf { it.v } }
        val tempAt = nearestMap(temp, 900)
        val hours = ((temp.first().tSec / HOUR) + 1)..(temp.last().tSec / HOUR)
        val tMap = HashMap<Long, Double>()
        for (hr in hours) tempAt(hr * HOUR)?.let { tMap[hr * HOUR] = it }
        var rMap: HashMap<Long, Double>? = null
        if (rainSeries != null) {
            val at = nearestMap(rainSeries, 600)
            rMap = HashMap()
            for (hr in hours) {
                val a = at(hr * HOUR) ?: continue
                val b = at((hr - 1) * HOUR) ?: continue
                val d = a - b
                // Valeur plus petite qu'une heure avant : remise à zéro de minuit, la pluie de l'heure est le cumul actuel.
                rMap[hr * HOUR] = if (d < -0.05) a else d.coerceAtLeast(0.0)
            }
        }
        return Station(tMap, rMap, 0.0)
    }

    /** Valeur la plus proche de `t` à moins de `tolSec`, ou null. */
    private fun nearestMap(series: List<SeriesPoint>, tolSec: Long): (Long) -> Double? = { t ->
        var best: SeriesPoint? = null
        var bd = Long.MAX_VALUE
        for (p in series) {
            val d = abs(p.tSec - t)
            if (d < bd) {
                bd = d
                best = p
            }
        }
        if (bd <= tolSec) best?.v else null
    }

    private fun score(
        label: String, lead: String, h: JSONObject, tempKey: String, rainKey: String, st: Station, now: Long,
    ): Result {
        val times = h.optJSONArray("time")
        val temps = h.optJSONArray(tempKey)
        val rains = h.optJSONArray(rainKey)
        val name = "$label · $lead"
        if (times == null) return Result(label, lead, 0, "$name : pas de données", null, null)

        var nT = 0
        var sumErr = 0.0
        var sumAbs = 0.0
        var nR = 0
        var hits = 0
        var miss = 0
        var fa = 0
        var totModel = 0.0
        var totStation = 0.0
        // Pluie : les heures où la station a une mesure ; une prévision absente compte comme « pas de pluie » seulement
        // si le modèle a des valeurs ailleurs (sinon la variable n'existe pas pour ce modèle).
        val rainKnown = rains != null && (0 until rains.length()).any { !rains.isNull(it) }
        for (i in 0 until times.length()) {
            val t = times.optLong(i)
            if (t > now - HOUR) continue
            val sT = st.temp[t]
            if (sT != null && temps != null && !temps.isNull(i)) {
                val e = temps.optDouble(i) - sT
                sumErr += e
                sumAbs += abs(e)
                nT++
            }
            val sR = st.rain?.get(t)
            if (sR != null && rainKnown && rains != null && !rains.isNull(i)) {
                val m = rains.optDouble(i)
                nR++
                totModel += m
                totStation += sR
                val mw = m >= WET
                val sw = sR >= WET
                if (mw && sw) hits++ else if (sw) miss++ else if (mw) fa++
            }
        }
        if (nT == 0 && nR == 0) return Result(label, lead, 0, "$name : indisponible pour ce modèle", null, null)

        val parts = ArrayList<String>()
        var csi: Double? = null
        if (nR > 0) {
            val wet = hits + miss
            val denom = hits + miss + fa
            csi = if (denom > 0) hits.toDouble() / denom else null
            val pod = if (wet > 0) pct(hits.toDouble() / wet) else "—"
            val far = if (hits + fa > 0) pct(fa.toDouble() / (hits + fa)) else "—"
            parts.add(
                "pluie " + fmt(totModel, 1) + " mm (station " + fmt(totStation, 1) + ") · détecte " + pod +
                    " · fausses alertes " + far + (if (csi != null) " · score " + pct(csi) else ""),
            )
        } else {
            parts.add("pluie indisponible")
        }
        var mae: Double? = null
        if (nT > 0) {
            mae = sumAbs / nT
            val bias = sumErr / nT
            parts.add("temp. " + (if (bias >= 0) "+" else "") + fmt(bias, 1) + "° · écart moyen " + fmt(mae, 1) + "°")
        }
        return Result(label, lead, nR.coerceAtLeast(nT), name + " : " + parts.joinToString(" · "), csi, mae)
    }

    private fun fmt(v: Double, d: Int): String = Metric.fmt(v, d)

    private fun pct(f: Double): String = Metric.fmt(f * 100.0, 0) + " %"
}
