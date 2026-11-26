package app.ecowidget.data

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Diagnostic par échéance : quel modèle prévoit le mieux la pluie (heure et quantité) et la température
 * à +1–2 h, +3–4 h, +5–6 h, +11–12 h puis à J+1 … J+5 ?
 *
 * - Échéances en heures : runs précis d'Open-Meteo (API « Single Runs », 4 runs par jour, 00 / 06 / 12 / 18 UTC).
 *   L'échéance est comptée depuis l'heure du run ; un run n'est disponible qu'environ 2 à 4 h plus tard.
 * - Échéances en jours : prévisions faites 1 à 5 jours avant (API « Previous Runs »).
 * Tout est comparé à l'historique Ecowitt, heure par heure.
 */
object ModelLeadCheck {

    private class Model(val id: String, val short: String, val label: String, val maxDay: Int)

    /** Modèles testés par heures (les plus fins) et par jours. */
    private val HOURLY_MODELS = listOf(
        Model("meteofrance_arome_france_hd", "AROME", "AROME HD", 0),
        Model("icon_d2", "ICON-D2", "ICON-D2", 0),
        Model("meteoswiss_icon_ch1", "ICON-CH1", "ICON-CH1", 0),
    )
    private val DAILY_MODELS = listOf(
        Model("meteofrance_arome_france_hd", "AROME", "AROME HD", 2),
        Model("icon_d2", "ICON-D2", "ICON-D2", 2),
        Model("icon_eu", "ICON-EU", "ICON-EU", 5),
        Model("meteofrance_arpege_europe", "ARPEGE", "ARPEGE Europe", 4),
        Model("ecmwf_ifs025", "IFS", "ECMWF IFS", 5),
        Model("best_match", "Auto", "Auto Open-Meteo", 5),
    )

    /** Tranches d'échéance en heures : clé, libellé, échéances (heures après le run). */
    private class Bucket(val key: String, val label: String, val hours: List<Int>)

    private val BUCKETS = listOf(
        Bucket("h1", "+1–2 h", listOf(1, 2)),
        Bucket("h3", "+3–4 h", listOf(3, 4)),
        Bucket("h5", "+5–6 h", listOf(5, 6)),
        Bucket("h11", "+11–12 h", listOf(11, 12)),
    )
    private const val MAX_LEAD_H = 12
    private val RUN_HOURS = listOf(0, 6, 12, 18)
    private const val HOUR = ModelCheck.HOUR
    private const val WET = ModelCheck.WET

    /** Cumul des écarts pour un couple (modèle, échéance). */
    private class Acc {
        var nT = 0
        var sumErr = 0.0
        var sumAbs = 0.0
        var nR = 0
        var wetS = 0
        var hit = 0
        var miss = 0
        var fa = 0
        var hitT = 0
        var missT = 0
        var faT = 0
        var totM = 0.0
        var totS = 0.0

        @Synchronized
        fun add(mT: Double?, sT: Double?, mR: Double?, mRn: Double?, sR: Double?, sRn: Double?) {
            if (mT != null && sT != null) {
                val e = mT - sT
                nT++
                sumErr += e
                sumAbs += abs(e)
            }
            if (mR != null && sR != null) {
                nR++
                totM += mR
                totS += sR
                val mw = mR >= WET
                val sw = sR >= WET
                if (sw) wetS++
                if (mw && sw) hit++ else if (sw) miss++ else if (mw) fa++
                // Tolérance ±1 h : une pluie prévue une heure trop tôt ou trop tard compte comme trouvée.
                val mwT = (mRn ?: mR) >= WET
                val swT = (sRn ?: sR) >= WET
                if (sw) {
                    if (mwT) hitT++ else missT++
                }
                if (mw && !swT) faT++
            }
        }

        fun csi(): Double? = (hit + miss + fa).takeIf { it > 0 }?.let { hit.toDouble() / it }
        fun csiTol(): Double? = (hitT + missT + faT).takeIf { it > 0 }?.let { hitT.toDouble() / it }
        fun ratio(): Double? = if (totS > 0.5) totM / totS else null
        fun bias(): Double? = if (nT > 0) sumErr / nT else null
        fun mae(): Double? = if (nT > 0) sumAbs / nT else null
    }

    private val acc = ConcurrentHashMap<String, Acc>()

    private fun accOf(model: String, lead: String): Acc = acc.getOrPut("$model|$lead") { Acc() }

    /** Pluie de la station aux heures `t - 1 h`, `t`, `t + 1 h` : le maximum disponible. */
    private fun stationNear(st: ModelCheck.Station, t: Long): Double? {
        val r = st.rain ?: return null
        val vals = listOf(t - HOUR, t, t + HOUR).mapNotNull { r[it] }
        return vals.maxOrNull()
    }

    private fun column(h: JSONObject, name: String): DoubleArray? {
        val a = h.optJSONArray(name) ?: return null
        if ((0 until a.length()).none { !a.isNull(it) }) return null
        return DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.optDouble(it) }
    }

    private fun DoubleArray.at(i: Int): Double? = if (i in indices && !this[i].isNaN()) this[i] else null

    private fun neighbourMax(r: DoubleArray?, i: Int): Double? {
        if (r == null) return null
        return listOf(i - 1, i, i + 1).mapNotNull { r.at(it) }.maxOrNull()
    }

    /** Écarts d'un modèle pour un jeu de séries horaires, à l'échéance `lead`, aux heures `valid` (null = toutes). */
    private fun feed(
        st: ModelCheck.Station, model: String, lead: String, times: List<Long>, temp: DoubleArray?, rain: DoubleArray?,
        only: Set<Int>?, now: Long,
    ) {
        val a = accOf(model, lead)
        for (i in times.indices) {
            if (only != null && i !in only) continue
            val t = times[i]
            if (t > now - HOUR) continue
            val sT = st.temp[t]
            val sR = st.rain?.get(t)
            if (sT == null && sR == null) continue
            a.add(temp?.at(i), sT, rain?.at(i), neighbourMax(rain, i), sR, stationNear(st, t))
        }
    }

    private fun timesOf(h: JSONObject): List<Long> {
        val a = h.optJSONArray("time") ?: return emptyList()
        return List(a.length()) { a.optLong(it) }
    }

    /**
     * Lance le diagnostic. `days` : profondeur de l'historique (jours) ; les échéances en heures sont limitées à 30 jours
     * (4 requêtes par jour et par modèle). `progress` reçoit l'avancement.
     */
    fun run(c: Credentials, loc: Location, days: Int, progress: (String) -> Unit): List<String> {
        acc.clear()
        val now = System.currentTimeMillis() / 1000
        progress("Lecture de l'historique de la station…")
        val st = try {
            ModelCheck.fetchStation(c, now, days)
        } catch (e: Exception) {
            return listOf("Historique station indisponible : " + EcowittClient.describe(e))
        }
        val spanDays = kotlin.math.ceil(st.days).toInt().coerceIn(2, 90)
        val hourlyDays = minOf(spanDays, 30)

        // Les tâches : une par (modèle, jour) et une par (modèle, run).
        val tasks = ArrayList<() -> Unit>()
        val failures = ConcurrentHashMap<String, AtomicInteger>()
        fun fail(m: String) = failures.getOrPut(m) { AtomicInteger() }.incrementAndGet()

        for (m in DAILY_MODELS) {
            for (d in 1..m.maxDay) {
                tasks.add {
                    try {
                        val h = OpenMeteoClient.fetchPreviousDay(loc, m.id, d, spanDays).optJSONObject("hourly")
                        if (h != null) {
                            feed(st, m.short, "j$d", timesOf(h), column(h, "temperature_2m_previous_day$d"), column(h, "precipitation_previous_day$d"), null, now)
                        } else {
                            fail(m.short)
                        }
                    } catch (e: Exception) {
                        fail(m.short)
                    }
                }
            }
        }

        val dayStart = (now / 86400L) * 86400L
        for (m in HOURLY_MODELS) {
            for (back in 1..hourlyDays) {
                for (rh in RUN_HOURS) {
                    val run = dayStart - back * 86400L + rh * HOUR
                    if (run + (MAX_LEAD_H + 2) * HOUR > now) continue
                    tasks.add {
                        try {
                            val h = OpenMeteoClient.fetchSingleRun(loc, m.id, run).optJSONObject("hourly")
                            if (h == null) {
                                fail(m.short + "·run")
                            } else {
                                val times = timesOf(h)
                                val temp = column(h, "temperature_2m")
                                val rain = column(h, "precipitation")
                                for (b in BUCKETS) {
                                    val idx = b.hours.mapNotNull { lead -> times.indexOf(run + lead * HOUR).takeIf { it >= 0 } }.toSet()
                                    if (idx.isNotEmpty()) feed(st, m.short, b.key, times, temp, rain, idx, now)
                                }
                            }
                        } catch (e: Exception) {
                            fail(m.short + "·run")
                        }
                    }
                }
            }
        }

        val total = tasks.size
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(3)
        tasks.forEach { t ->
            pool.execute {
                t()
                val n = done.incrementAndGet()
                if (n % 5 == 0 || n == total) progress("Prévisions comparées : $n / $total requêtes")
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.MINUTES)

        return report(st, spanDays, hourlyDays, failures.mapValues { it.value.get() })
    }

    // ───────────────────────────── Rapport ─────────────────────────────

    private fun fmt(v: Double, d: Int): String = Metric.fmt(v, d)
    private fun pct(f: Double): String = Metric.fmt(f * 100.0, 0)

    private fun report(st: ModelCheck.Station, spanDays: Int, hourlyDays: Int, failures: Map<String, Int>): List<String> {
        val out = ArrayList<String>()
        val rainTotal = st.rain?.values?.sum()
        out.add(
            "Station sur " + fmt(st.days, 1) + " j : " +
                (if (rainTotal != null) fmt(rainTotal, 0) + " mm, " + (st.rain?.values?.count { it >= WET } ?: 0).toString() + " h de pluie" else "pluie indisponible") +
                ". Échéances en heures : " + minOf(hourlyDays, st.days.toInt().coerceAtLeast(1)) + " derniers jours, 4 runs par jour.",
        )
        out.add(
            "Lecture : pluie = score à ±1 h (score strict, même heure) · quantité prévue ÷ mesurée (1,0 = juste). " +
                "Température = écart moyen (biais). ★ = meilleur. Échéance comptée depuis l'heure du run, disponible 2 à 4 h après.",
        )

        data class Lead(val key: String, val label: String)
        val leads = BUCKETS.map { Lead(it.key, it.label) } + (1..5).map { Lead("j$it", "J+$it") }
        val models = (HOURLY_MODELS + DAILY_MODELS).map { it.short }.distinct()

        val bestRain = ArrayList<String>()
        val bestTemp = ArrayList<String>()
        val rainLines = ArrayList<String>()
        val tempLines = ArrayList<String>()
        for (l in leads) {
            val rows = models.mapNotNull { m -> acc["$m|${l.key}"]?.let { m to it } }
            // Pluie
            val rain = rows.filter { it.second.nR >= 40 && it.second.csiTol() != null }.sortedByDescending { it.second.csiTol() }
            if (rain.isNotEmpty()) {
                val wet = rain.maxOf { it.second.wetS }
                val txt = rain.mapIndexed { i, (m, a) ->
                    (if (i == 0) "★ " else "") + m + " " + pct(a.csiTol()!!) + " % (" + (a.csi()?.let { pct(it) } ?: "—") + ")" +
                        (a.ratio()?.let { " ×" + fmt(it, 1) } ?: "")
                }.joinToString(" · ")
                rainLines.add(l.label + " · pluie (" + wet + " h de pluie) : " + txt)
                bestRain.add(l.label + " " + rain.first().first)
            }
            // Température
            val temp = rows.filter { it.second.nT >= 40 && it.second.mae() != null }.sortedBy { it.second.mae() }
            if (temp.isNotEmpty()) {
                val txt = temp.mapIndexed { i, (m, a) ->
                    (if (i == 0) "★ " else "") + m + " " + fmt(a.mae()!!, 1) + "° (" + (if (a.bias()!! >= 0) "+" else "") + fmt(a.bias()!!, 1) + ")"
                }.joinToString(" · ")
                tempLines.add(l.label + " · température : " + txt)
                bestTemp.add(l.label + " " + temp.first().first)
            }
        }
        if (bestRain.isNotEmpty()) out.add("Pluie, meilleur modèle : " + bestRain.joinToString(" · "))
        if (bestTemp.isNotEmpty()) out.add("Température, meilleur modèle : " + bestTemp.joinToString(" · "))
        out.addAll(rainLines)
        out.addAll(tempLines)

        val fails = failures.filter { it.value > 0 }
        if (fails.isNotEmpty()) {
            out.add("Requêtes sans réponse : " + fails.entries.joinToString(" · ") { it.key + " " + it.value })
        }
        if (rainLines.isEmpty() && tempLines.isEmpty()) out.add("Aucun résultat exploitable (runs indisponibles ou historique vide).")
        return out
    }
}
