package app.ecowidget.data

import app.ecowidget.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Construit le contenu des tuiles et des fiches détaillées à partir des mesures de la station et des prévisions.
 * Le résultat est une description pure (valeurs + graphiques) : le widget Glance et la fiche plein écran la dessinent chacun à leur façon.
 */
object DetailBuilder {

    private val HOUR_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("H'h'")
    private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", Locale.FRENCH)
    private const val H = 3600L
    private const val DASH = "—"

    // ───────────────────────────── Tuiles (maquette « H ») ─────────────────────────────

    /** Première pluie des 24 h à venir, et premier moment où elle tombe en neige. */
    private class Episode(val first: Long?, val firstSnow: Long?, val firstIsSnow: Boolean)

    private fun episode(i: DetailInputs): Episode? {
        val prec = hourly(i, "precipitation", i.nowSec + 1, i.nowSec + 24 * H)
        if (prec.isEmpty()) return null
        val temps = hourlyMap(i, "temperature_2m")
        val snowH = hourlyMap(i, "snowfall_height")
        val frz = hourlyMap(i, "freezing_level_height")
        var first: Long? = null
        var firstSnow: Long? = null
        var firstTone = Tone.RAIN
        for ((t, mm) in prec) {
            if (mm < 0.1) continue
            val tone = precipTone(i.altM, t, temps, snowH, frz)
            if (first == null) {
                first = t - H
                firstTone = tone
            }
            if (tone == Tone.SNOW && firstSnow == null) firstSnow = t - H
        }
        return Episode(first, firstSnow, firstTone == Tone.SNOW)
    }

    /** « 17 h », « 17 h 45 ». */
    private fun hm(sec: Long, zone: ZoneId): String {
        val z = Instant.ofEpochSecond(sec).atZone(zone)
        return if (z.minute == 0) "${z.hour} h" else "${z.hour} h " + String.format(Locale.ROOT, "%02d", z.minute)
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochSecond(a).atZone(zone).toLocalDate() == Instant.ofEpochSecond(b).atZone(zone).toLocalDate()

    /** Pastille de l'en-tête : « Pluie 17 h 45 · neige 5 h », « Sec jusqu'à demain », « Il pleut ». Null sans prévisions. */
    fun alert(i: DetailInputs): Alert? {
        val f = i.forecast
        if (i.snap?.isRaining == true) {
            val end = f?.rainEndMin
            return Alert(if (end != null) "Il pleut · fin dans " + OmFormat.delay(end) else "Il pleut", true)
        }
        val ep = episode(i) ?: return null
        val first = ep.first ?: return Alert("Sec sur 24 h", false)
        val nextMin = f?.nextRainMin
        // Quand la pluie est proche, l'heure vient du modèle à 15 min (AROME) ; sinon de la prévision horaire.
        val start = if (nextMin != null && nextMin in 1..120) i.nowSec + nextMin * 60L else first
        if (!sameDay(start, i.nowSec, i.zone)) return Alert("Sec jusqu'à demain", false)
        val verb = if (ep.firstIsSnow) "Neige" else "Pluie"
        if (start <= i.nowSec + 300) return Alert("$verb imminente", true)
        var text = "$verb ${hm(start, i.zone)}"
        val sn = ep.firstSnow
        if (!ep.firstIsSnow && sn != null) {
            text += " · neige " + (if (sameDay(sn, i.nowSec, i.zone)) "" else "demain ") + hm(sn, i.zone)
        }
        return Alert(text, true)
    }

    /** Les tuiles, dans l'ordre des maquettes : Extérieur (large), Prévision, Pluie / Vent, Humidité, Pression, Limite neige / Demain, Coucher, Air, Intérieur. */
    fun summaries(i: DetailInputs): List<TileSummary> {
        val s = i.snap
        val f = i.forecast
        val d = i.derived
        val now = i.nowSec
        val alt = i.altM
        val ep = episode(i)
        val first = ep?.first
        val raining = s?.isRaining == true
        val wetToday = first != null && sameDay(first, now, i.zone)

        val out = ArrayList<TileSummary>()

        // Extérieur
        out.add(
            TileSummary(
                tile = Tile.TEMP, label = "Extérieur", short = "Temp.",
                value = s?.outdoorTemp?.let { Metric.fmt(it, 1) } ?: DASH, unit = "°",
                icon = Tile.TEMP.icon, iconTone = Tone.ACCENT,
                colorTemp = s?.outdoorTemp,
                trend = Metric.trendOf(d?.tempTrend1h, 0.2),
                side = listOf(
                    "max" to deg(d?.dayMax),
                    "min" to deg(d?.dayMin),
                    "ress." to (s?.feelsLike?.let { Metric.fmt(it, 0) + "°" } ?: DASH),
                ),
                wide = true,
            ),
        )

        // Prévision
        val hi = f?.dayMax
        val lo = f?.dayMin
        out.add(
            TileSummary(
                tile = Tile.FORECAST, label = "Prévision", short = "Prévis.",
                value = if (hi != null && lo != null) Metric.fmt(hi, 0) + "°/" + Metric.fmt(lo, 0) + "°" else DASH,
                sub = when {
                    raining -> "il pleut"
                    wetToday && first != null -> "pluie " + hm(maxOf(first, now), i.zone)
                    f != null -> WeatherCodes.label(f.code, f.isDay ?: true).lowercase(Locale.FRENCH)
                    else -> ""
                },
                icon = if (raining || wetToday) R.drawable.ic_cloud_rain else if (f != null) WeatherCodes.icon(f.code, f.isDay ?: true) else Tile.FORECAST.icon,
                iconTone = if (raining || wetToday) Tone.RAIN else Tone.ACCENT,
            ),
        )

        // Pluie
        val today = RainOutlook.today(f?.raw, i.zone)
        val rainDaily = s?.rainDaily
        out.add(
            TileSummary(
                tile = Tile.RAIN, label = "Pluie", short = "Pluie",
                value = rainDaily?.let { Metric.fmt(it, 1) } ?: DASH, unit = "mm",
                sub = when {
                    raining -> s?.rainRate?.let { Metric.fmt(it, 1) + " mm/h" } ?: "en cours"
                    today != null && today.expected -> "+" + Metric.fmt(today.totalMm, 1) + " prévus"
                    today != null -> "rien de prévu"
                    else -> ""
                },
                icon = Tile.RAIN.icon, iconTone = Tone.RAIN,
                valueTone = if (raining) Tone.RAIN else if (rainDaily == null || rainDaily < 0.05) Tone.DIM else Tone.NORMAL,
            ),
        )

        // Vent
        out.add(
            TileSummary(
                tile = Tile.WIND, label = "Vent", short = "Vent" + (s?.windDir?.let { " " + Metric.cardinal(it) } ?: ""),
                value = s?.windSpeed?.let { Metric.fmt(it, 0) } ?: DASH, unit = "km/h",
                sub = listOfNotNull(s?.windGust?.let { "raf. " + Metric.fmt(it, 0) }, s?.windDir?.let { Metric.cardinal(it) }).joinToString(" · "),
                icon = Tile.WIND.icon, iconTone = Tone.WIND,
            ),
        )

        // Humidité
        out.add(
            TileSummary(
                tile = Tile.HUM, label = "Humidité", short = "Humidité",
                value = s?.humidity?.let { Metric.fmt(it, 0) } ?: DASH, unit = "%",
                sub = s?.dewPoint?.let { "rosée " + Metric.fmt(it, 1) + "°" } ?: "",
                icon = Tile.HUM.icon, iconTone = Tone.HUMID,
            ),
        )

        // Pression
        val pTrend = Metric.trendOf(d?.pressTrend3h, 1.0)
        out.add(
            TileSummary(
                tile = Tile.PRESS, label = "Pression", short = "hPa",
                value = s?.pressure?.let { Metric.fmt(it, 0) } ?: DASH,
                sub = d?.pressTrend3h?.let { signed(it, 1, " / 3 h") } ?: "",
                icon = Tile.PRESS.icon, iconTone = Tone.PRESSURE,
                trend = if (pTrend == 1 || pTrend == -1) pTrend else null,
            ),
        )

        // Limite neige
        val lim = limitSeries(i, 24)
        val limNow = lim.firstOrNull()?.second
        val lowNow = limNow != null && alt != null && limNow <= alt
        val cross = if (alt != null) lim.firstOrNull { it.second <= alt && it.first > now } else null
        out.add(
            TileSummary(
                tile = Tile.SNOW, label = "Lim. neige", short = "Neige",
                value = limNow?.let { hundreds(it).toString() } ?: DASH, unit = if (limNow != null) "m" else "",
                sub = when {
                    limNow == null -> ""
                    lowNow -> "chez toi"
                    cross != null -> "chez toi " + hm(cross.first, i.zone)
                    else -> "au-dessus de toi"
                },
                icon = Tile.SNOW.icon, iconTone = Tone.SNOW,
                valueTone = if (limNow != null) Tone.SNOW else Tone.DIM,
                subTone = if (lowNow || cross != null) Tone.SNOW else Tone.DIM,
                tinted = lowNow || cross != null,
            ),
        )

        // Demain
        val tmx = daily(i, "temperature_2m_max")
        val tmn = daily(i, "temperature_2m_min")
        val maxTom = dayAt(tmx, i, 1)
        val minTom = dayAt(tmn, i, 1)
        val snowTom = dayAt(daily(i, "snowfall_sum"), i, 1)
        val rainTom = dayAt(daily(i, "precipitation_sum"), i, 1)
        out.add(
            TileSummary(
                tile = Tile.TEMP, tab = 2, label = "Demain", short = "Demain",
                value = if (maxTom != null && minTom != null) Metric.fmt(maxTom, 0) + "°/" + Metric.fmt(minTom, 0) + "°" else DASH,
                sub = when {
                    snowTom != null && snowTom >= 0.5 -> "neige " + Metric.fmt(snowTom, 0) + " cm"
                    rainTom != null && rainTom >= 1.0 -> "pluie " + Metric.fmt(rainTom, 0) + " mm"
                    rainTom != null -> "sec"
                    else -> ""
                },
                icon = R.drawable.ic_calendar, iconTone = Tone.ACCENT,
            ),
        )

        // Coucher (ou lever demain une fois le soleil couché)
        val sunset = f?.sunsetSec
        val sunriseTom = dayAt(daily(i, "sunrise"), i, 1)?.toLong()
        val afterSunset = sunset != null && now > sunset
        val uvMax = dayAt(daily(i, "uv_index_max"), i, 0)
        out.add(
            TileSummary(
                tile = Tile.SUN, label = if (afterSunset) "Lever" else "Coucher", short = "Soleil",
                value = (if (afterSunset) sunriseTom else sunset)?.let { OmFormat.hhmm(it) } ?: DASH,
                sub = uvMax?.let { "UV max " + Metric.fmt(it, 1) } ?: "",
                icon = R.drawable.ic_sunset, iconTone = Tone.SUN,
            ),
        )

        // Air
        val aqi = f?.let { OmCatalog.byId("air.european_aqi")?.read(it, s) }
        val pollen = f?.let { OmCatalog.byId(OmCatalog.POLLEN_MAX)?.read(it, s) }
        val aqiOk = aqi != null && aqi.value != Metric.MISSING.value
        val band = aqi?.unit?.removePrefix("·")?.trim()?.lowercase(Locale.FRENCH).orEmpty()
        val pollenText = pollen?.takeIf { it.value != Metric.MISSING.value }?.let { "pollens " + it.value }
        out.add(
            TileSummary(
                tile = Tile.AIR, label = "Air", short = "Air",
                value = if (aqiOk && aqi != null) aqi.value else DASH,
                sub = listOfNotNull(band.takeIf { it.isNotEmpty() && aqiOk }, pollenText).joinToString(" · "),
                icon = R.drawable.ic_leaf, iconTone = Tone.GOOD,
                valueTone = if (!aqiOk) Tone.DIM else if (aqi?.highlight == true) Tone.WARN else Tone.GOOD,
            ),
        )

        // Intérieur
        out.add(
            TileSummary(
                tile = Tile.INDOOR, label = "Intérieur", short = "Intérieur",
                value = s?.indoorTemp?.let { Metric.fmt(it, 1) } ?: DASH, unit = "°",
                sub = s?.indoorHumidity?.let { Metric.fmt(it, 0) + " %" } ?: "",
                icon = R.drawable.ic_home, iconTone = Tone.DIM,
            ),
        )
        return out
    }

    fun fiche(tile: Tile, i: DetailInputs): Fiche = when (tile) {
        Tile.TEMP -> tempFiche(i)
        Tile.RAIN -> rainFiche(i)
        Tile.SNOW -> snowFiche(i)
        Tile.WIND -> windFiche(i)
        Tile.HUM -> humFiche(i)
        Tile.PRESS -> pressFiche(i)
        Tile.FORECAST -> forecastFiche(i)
        Tile.SUN -> sunFiche(i)
        Tile.AIR -> airFiche(i)
        Tile.INDOOR -> indoorFiche(i)
    }

    // ───────────────────────────── Soleil, air, intérieur ─────────────────────────────

    private fun sunFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val f = i.forecast
        val s = i.snap
        val uv = hourly(i, "uv_index", now - 3599, now + 24 * H)
        val uvMax = dayAt(daily(i, "uv_index_max"), i, 0)
        val page = DetailPage(
            "UV",
            listOf(
                Stat("Lever", f?.sunriseSec?.let { OmFormat.hhmm(it) } ?: DASH, Tone.SUN),
                Stat("Coucher", f?.sunsetSec?.let { OmFormat.hhmm(it) } ?: DASH, Tone.SUN),
                Stat("UV max du jour", num(uvMax, 1, "")),
                Stat("Ensoleillement", num(s?.solar, 0, " W/m²")),
            ),
            if (uv.size < 2) null else LineChart(
                listOf(LineSeries(uv, Tone.SUN, fill = true)), uv.first().first, uv.last().first,
                hourTicks(uv.first().first, uv.last().first, 6, i.zone), nowSec = now, minSpan = 3.0,
            ),
            if (uv.size < 2) "Indice UV indisponible." else "Indice UV prévu sur 24 h.",
        )
        return Fiche(Tile.SUN, listOf(page))
    }

    private fun airFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val f = i.forecast
        val s = i.snap
        val aqi = f?.let { OmCatalog.byId("air.european_aqi")?.read(it, s) }
        val pollen = f?.let { OmCatalog.byId(OmCatalog.POLLEN_MAX)?.read(it, s) }
        val series = f?.air?.hourlySeries("european_aqi").orEmpty()
            .mapNotNull { p -> p.second?.let { p.first to it } }
            .filter { it.first in (now - 3599)..(now + 24 * H) }
        val ok = aqi != null && aqi.value != Metric.MISSING.value
        val page = DetailPage(
            "Air",
            listOf(
                Stat("Indice européen", if (ok && aqi != null) aqi.value else DASH, if (ok) Tone.GOOD else Tone.DIM),
                Stat("Niveau", aqi?.unit?.removePrefix("·")?.trim()?.ifEmpty { DASH } ?: DASH),
                Stat("Pollens", pollen?.takeIf { it.value != Metric.MISSING.value }?.value ?: DASH),
                Stat("Maxi 24 h", num(series.maxOfOrNull { it.second }, 0, "")),
            ),
            if (series.size < 2) null else LineChart(
                listOf(LineSeries(series, Tone.GOOD, fill = true)), series.first().first, series.last().first,
                hourTicks(series.first().first, series.last().first, 6, i.zone), nowSec = now, minSpan = 20.0,
            ),
            if (series.size < 2) "Qualité de l'air indisponible pour le moment." else "Indice européen : 0–20 bon, 20–40 correct, 40–60 moyen, au-delà médiocre à mauvais.",
        )
        return Fiche(Tile.AIR, listOf(page))
    }

    private fun indoorFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val s = i.snap
        val ind = i.derived?.indoorTempSeries.orEmpty().filter { it.tSec >= now - 24 * H }.map { it.tSec to it.v }
        val gap = if (s?.indoorTemp != null && s.outdoorTemp != null) s.indoorTemp - s.outdoorTemp else null
        val page = DetailPage(
            "Intérieur",
            listOf(
                Stat("Température", deg(s?.indoorTemp), Tone.ACCENT),
                Stat("Humidité", num(s?.indoorHumidity, 0, " %")),
                Stat("Écart extérieur", signed(gap, 1, "°")),
                Stat("Maxi 24 h", deg(ind.maxOfOrNull { it.second })),
            ),
            if (ind.size < 2) null else LineChart(
                listOf(LineSeries(ind, Tone.WARM, fill = true)), now - 24 * H, now + 1800,
                hourTicks(now - 24 * H, now + 1800, 6, i.zone), nowSec = now, minSpan = 2.0,
            ),
            if (ind.size < 2) "Pas encore d'historique de la température intérieure." else null,
        )
        return Fiche(Tile.INDOOR, listOf(page))
    }

    // ───────────────────────────── Température ─────────────────────────────

    private fun tempFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val mid = midnight(i)

        // Jour : 24 h mesurées par la station, prolongées par la prévision.
        val station = d?.tempSeries.orEmpty().filter { it.tSec >= now - 24 * H }.map { it.tSec to it.v }
        val ahead = hourly(i, "temperature_2m", now + 1, now + 12 * H)
        val jourSeries = ArrayList<LineSeries>()
        if (station.isNotEmpty()) jourSeries.add(LineSeries(station, Tone.ACCENT, fill = true))
        if (ahead.isNotEmpty()) {
            jourSeries.add(LineSeries(listOfNotNull(station.lastOrNull()) + ahead, Tone.ACCENT, dashed = true))
        }
        val jourMax = if (ahead.isNotEmpty()) now + 12 * H else now + 1800
        val jour = DetailPage(
            "Jour",
            listOf(
                Stat("Maintenant", deg(s?.outdoorTemp), Tone.ACCENT),
                Stat("Mini du jour", deg(d?.dayMin)),
                Stat("Maxi du jour", deg(d?.dayMax)),
                Stat("Sur 1 h", signed(d?.tempTrend1h, 1, "°")),
            ),
            if (jourSeries.isEmpty()) null else LineChart(
                jourSeries, now - 24 * H, jourMax, hourTicks(now - 24 * H, jourMax, 6, i.zone), nowSec = now, zeroLine = true,
            ),
            if (station.isEmpty()) "Pas encore d'historique de la station." else null,
        )

        // Heures : 24 h à venir, avec le ressenti en pointillés.
        val next = hourly(i, "temperature_2m", now - 3599, now + 24 * H)
        val feels = hourly(i, "apparent_temperature", now - 3599, now + 24 * H)
        val heuresSeries = ArrayList<LineSeries>()
        if (next.isNotEmpty()) heuresSeries.add(LineSeries(next, Tone.ACCENT, fill = true))
        if (feels.isNotEmpty()) heuresSeries.add(LineSeries(feels, Tone.WARM, dashed = true))
        val heures = DetailPage(
            "Heures",
            listOf(
                Stat("Mini 24 h", deg(next.minOfOrNull { it.second })),
                Stat("Maxi 24 h", deg(next.maxOfOrNull { it.second })),
                Stat("Ressenti", deg(s?.feelsLike)),
                Stat("Point de rosée", deg(s?.dewPoint)),
            ),
            if (next.isEmpty()) null else LineChart(
                heuresSeries, next.first().first, next.last().first, hourTicks(next.first().first, next.last().first, 6, i.zone),
                nowSec = now, zeroLine = true,
            ),
            if (next.isEmpty()) "Prévisions indisponibles." else "Pointillés : température ressentie.",
        )

        // Demain
        val tmx = daily(i, "temperature_2m_max")
        val tmn = daily(i, "temperature_2m_min")
        val maxTom = dayAt(tmx, i, 1)
        val minTom = dayAt(tmn, i, 1)
        val maxToday = dayAt(tmx, i, 0)
        val tom = hourly(i, "temperature_2m", mid + 24 * H, mid + 47 * H)
        val demain = DetailPage(
            "Demain",
            listOf(
                Stat("Mini", deg(minTom), Tone.COLD),
                Stat("Maxi", deg(maxTom), Tone.WARM),
                Stat("Écart maxi", signed(if (maxTom != null && maxToday != null) maxTom - maxToday else null, 1, "°")),
                Stat("Aujourd'hui", deg(maxToday)),
            ),
            if (tom.size < 2) null else LineChart(
                listOf(LineSeries(tom, Tone.ACCENT, fill = true)), mid + 24 * H, mid + 48 * H,
                hourTicks(mid + 24 * H, mid + 48 * H, 6, i.zone), zeroLine = true,
            ),
            if (tom.size < 2) "Prévisions de demain indisponibles." else null,
        )
        return Fiche(Tile.TEMP, listOf(jour, heures, demain))
    }

    // ───────────────────────────── Pluie ─────────────────────────────

    private fun rainFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val s = i.snap
        val f = i.forecast
        val d = i.derived
        val mid = midnight(i)
        val today = RainOutlook.today(f?.raw, i.zone)

        // Cumuls : pluie mesurée aujourd'hui, prévue d'ici minuit, demain.
        val dayPrec = daily(i, "precipitation_sum")
        val rainSeries = d?.rainDaySeries.orEmpty().filter { it.tSec >= mid }.map { it.tSec to it.v }
        val cumuls = DetailPage(
            "Cumuls",
            listOf(
                Stat("Aujourd'hui", num(s?.rainDaily, 1, " mm"), Tone.RAIN),
                Stat("Prévu d'ici minuit", if (today != null) num(today.totalMm, 1, " mm") else DASH),
                Stat("Demain", num(dayAt(dayPrec, i, 1), 1, " mm")),
                Stat("Intensité", num(s?.rainRate, 1, " mm/h")),
            ),
            if (rainSeries.size >= 2) LineChart(
                listOf(LineSeries(rainSeries, Tone.RAIN, fill = true)), mid, mid + 24 * H,
                hourTicks(mid, mid + 24 * H, 6, i.zone), nowSec = now, yDecimals = 1, minSpan = 2.0,
            ) else null,
            if (rainSeries.size >= 2) null else "La courbe du cumul apparaît quand l'historique de pluie de la station est reçu.",
        )

        // 60 min : pluie prévue par AROME, par pas de 5 min.
        val bins = rainBins(f, s?.rainRate)
        val next = f?.nextRainMin
        val end = f?.rainEndMin
        val msg = if (f == null || !f.hasNowcast) {
            "Indisponible"
        } else if (next == null) {
            "Rien sur " + OmFormat.horizon(f.nowcastHorizonMin)
        } else if (next == 0) {
            if (end != null) "Fin dans " + OmFormat.delay(end) else "En cours"
        } else {
            "Dans " + OmFormat.delay(next)
        }
        val soixante = DetailPage(
            "60 min",
            listOf(
                Stat("Prochaine pluie", msg, if (next != null) Tone.RAIN else Tone.NORMAL),
                Stat("Pic", num(bins.maxOrNull(), 1, " mm/h")),
                Stat("Source", f?.nowcastSource ?: DASH),
            ),
            BarChart(bins.map { Bar(it, Tone.RAIN) }, listOf(0 to "maint.", 6 to "+30 min", 11 to "+1 h"), minMax = 2.0, sqrt = true),
            null,
        )

        // Heures : pluie prévue heure par heure, colorée selon pluie / neige.
        val prec = hourly(i, "precipitation", now + 1, now + 24 * H)
        val temps = hourlyMap(i, "temperature_2m")
        val snowH = hourlyMap(i, "snowfall_height")
        val frz = hourlyMap(i, "freezing_level_height")
        val bars = prec.map { (t, mm) -> Bar(mm, precipTone(i.altM, t, temps, snowH, frz)) }
        val ticks = prec.mapIndexedNotNull { idx, p ->
            val z = Instant.ofEpochSecond(p.first - H).atZone(i.zone)
            if (z.hour % 6 == 0) idx to HOUR_FMT.format(z) else null
        }
        val probs = hourly(i, "precipitation_probability", now + 1, now + 24 * H)
        val first = prec.firstOrNull { it.second >= 0.1 }
        val snowCm = hourly(i, "snowfall", now + 1, now + 24 * H).sumOf { it.second }
        val heures = DetailPage(
            "Heures",
            listOf(
                Stat("Total 24 h", num(prec.sumOf { it.second }, 1, " mm"), Tone.RAIN),
                Stat("Risque max", num(probs.maxOfOrNull { it.second }, 0, " %")),
                Stat("Dès", if (first != null) HOUR_FMT.format(Instant.ofEpochSecond(first.first - H).atZone(i.zone)) else "Rien"),
                Stat("Neige 24 h", num(snowCm, 1, " cm"), if (snowCm >= 0.1) Tone.SNOW else Tone.NORMAL),
            ),
            if (bars.isEmpty()) null else BarChart(bars, ticks, minMax = 1.0, yDecimals = 1),
            if (bars.isEmpty()) "Prévisions indisponibles." else "Bleu : pluie · clair : neige · hachuré : mélange.",
        )

        // 7 j : cumul prévu par jour.
        val snowSum = daily(i, "snowfall_sum")
        val todayIdx = dayPrec.indexOfLast { it.first <= now }
        val days = if (todayIdx >= 0) dayPrec.drop(todayIdx).take(7) else emptyList()
        val dayBars = days.mapIndexed { idx, p ->
            val mm = p.second ?: 0.0
            val cm = snowSum.getOrNull(todayIdx + idx)?.second ?: 0.0
            Bar(mm, if (mm > 0.0 && cm / 10.0 >= 0.5 * mm) Tone.SNOW else Tone.RAIN)
        }
        val dayTicks = days.mapIndexed { idx, p -> idx to DAY_FMT.format(Instant.ofEpochSecond(p.first + 12 * H).atZone(i.zone)) }
        val wettest = days.withIndex().maxByOrNull { it.value.second ?: 0.0 }
        val totalMm = days.sumOf { it.second ?: 0.0 }
        val totalSnow = (0 until days.size).sumOf { snowSum.getOrNull(todayIdx + it)?.second ?: 0.0 }
        val sept = DetailPage(
            "7 j",
            listOf(
                Stat("Total 7 j", num(totalMm, 1, " mm"), Tone.RAIN),
                Stat(
                    "Plus arrosé",
                    if (wettest != null && (wettest.value.second ?: 0.0) > 0.0) {
                        DAY_FMT.format(Instant.ofEpochSecond(wettest.value.first + 12 * H).atZone(i.zone)) + " · " + num(wettest.value.second, 1, " mm")
                    } else {
                        "Aucun"
                    },
                ),
                Stat("Jours de pluie", days.count { (it.second ?: 0.0) >= 1.0 }.toString()),
                Stat("Neige 7 j", num(totalSnow, 0, " cm"), if (totalSnow >= 1.0) Tone.SNOW else Tone.NORMAL),
            ),
            if (dayBars.isEmpty()) null else BarChart(dayBars, dayTicks, minMax = 5.0, yDecimals = 0),
            if (dayBars.isEmpty()) "Prévisions sur 7 jours indisponibles." else null,
        )
        return Fiche(Tile.RAIN, listOf(cumuls, soixante, heures, sept))
    }

    private fun rainBins(f: Forecast?, stationRate: Double?): List<Double> = List(12) { idx ->
        val mid = idx * 5 + 2.5
        val model = f?.rainSteps?.firstOrNull { mid >= it.startMin && mid < it.startMin + it.minutes }?.mmPerHour ?: 0.0
        // Les premières minutes suivent la station quand elle détecte de la pluie.
        if (idx == 0 && stationRate != null && stationRate > 0.0) maxOf(model, stationRate) else model
    }

    /** Neige si la limite est sous l'altitude de l'utilisateur et qu'il gèle presque ; mélange si elle est à moins de 300 m au-dessus. */
    private fun precipTone(altM: Double?, t: Long, temps: Map<Long, Double>, snowH: Map<Long, Double>, frz: Map<Long, Double>): Tone {
        if (altM == null) return Tone.RAIN
        val limit = snowH[t] ?: frz[t]?.let { it - 300.0 } ?: return Tone.RAIN
        val temp = temps[t]
        return when {
            limit <= altM && (temp == null || temp <= 1.0) -> Tone.SNOW
            limit <= altM + 300.0 -> Tone.ICE
            else -> Tone.RAIN
        }
    }

    // ───────────────────────────── Limite neige ─────────────────────────────

    private fun snowFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val alt = i.altM
        val refLabel = alt?.let { "chez toi " + hundredsOrExact(it) + " m" }

        val lim = limitSeries(i, 24)
        val snowCm = hourly(i, "snowfall", now + 1, now + 24 * H).sumOf { it.second }
        val h24 = DetailPage(
            "24 h",
            listOf(
                Stat("Maintenant", lim.firstOrNull()?.let { hundreds(it.second).toString() + " m" } ?: DASH, Tone.SNOW),
                Stat("Mini 24 h", lim.minOfOrNull { it.second }?.let { hundreds(it).toString() + " m" } ?: DASH),
                Stat("Maxi 24 h", lim.maxOfOrNull { it.second }?.let { hundreds(it).toString() + " m" } ?: DASH),
                Stat("Neige 24 h", num(snowCm, 1, " cm"), if (snowCm >= 0.1) Tone.SNOW else Tone.NORMAL),
            ),
            if (lim.size < 2) null else LineChart(
                listOf(LineSeries(lim, Tone.SNOW, fill = true)), lim.first().first, lim.last().first,
                hourTicks(lim.first().first, lim.last().first, 6, i.zone), nowSec = now, refY = alt, refLabel = refLabel, minSpan = 400.0,
            ),
            if (lim.size < 2) "Limite pluie / neige indisponible à ta position." else "Altitude à partir de laquelle il neige (modèles DWD / MétéoSuisse).",
        )

        val frz = hourly(i, "freezing_level_height", now - 3599, now + 24 * H)
        val iso = DetailPage(
            "Iso 0 °C",
            listOf(
                Stat("Maintenant", frz.firstOrNull()?.let { hundreds(it.second).toString() + " m" } ?: DASH, Tone.ACCENT),
                Stat("Mini 24 h", frz.minOfOrNull { it.second }?.let { hundreds(it).toString() + " m" } ?: DASH),
                Stat("Maxi 24 h", frz.maxOfOrNull { it.second }?.let { hundreds(it).toString() + " m" } ?: DASH),
                Stat(
                    "Par rapport à toi",
                    if (alt != null && frz.isNotEmpty()) signed(frz.first().second - alt, 0, " m") else DASH,
                ),
            ),
            if (frz.size < 2) null else LineChart(
                listOf(LineSeries(frz, Tone.ACCENT, fill = true)), frz.first().first, frz.last().first,
                hourTicks(frz.first().first, frz.last().first, 6, i.zone), nowSec = now, refY = alt, refLabel = refLabel, minSpan = 400.0,
            ),
            if (frz.size < 2) "Isotherme 0 °C indisponible." else "Altitude où la température passe sous 0 °C.",
        )
        return Fiche(Tile.SNOW, listOf(h24, iso))
    }

    /** Limite pluie / neige horaire : `snowfall_height`, à défaut l'isotherme 0 °C moins 300 m. */
    private fun limitSeries(i: DetailInputs, hours: Int): List<Pair<Long, Double>> {
        val from = i.nowSec - 3599
        val to = i.nowSec + hours * H
        val snow = hourly(i, "snowfall_height", from, to)
        if (snow.isNotEmpty()) return snow.map { it.first to max(0.0, it.second) }
        return hourly(i, "freezing_level_height", from, to).map { it.first to max(0.0, it.second - 300.0) }
    }

    // ───────────────────────────── Vent ─────────────────────────────

    private fun windFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val ws = d?.windSeries.orEmpty().filter { it.tSec >= now - 24 * H }.map { it.tSec to it.v }
        val gs = d?.gustSeries.orEmpty().filter { it.tSec >= now - 24 * H }.map { it.tSec to it.v }
        val series = ArrayList<LineSeries>()
        if (ws.size >= 2) series.add(LineSeries(ws, Tone.ACCENT, fill = true))
        if (gs.size >= 2) series.add(LineSeries(gs, Tone.WARN, dashed = true))
        val jour = DetailPage(
            "Jour",
            listOf(
                Stat("Vent", num(s?.windSpeed, 0, " km/h"), Tone.ACCENT),
                Stat("Rafales", num(s?.windGust, 0, " km/h")),
                Stat("Direction", s?.windDir?.let { Metric.cardinal(it) + " · " + Metric.fmt(it, 0) + "°" } ?: DASH),
                Stat("Rafale max 24 h", num(gs.maxOfOrNull { it.second }, 0, " km/h"), Tone.WARN),
            ),
            if (series.isEmpty()) null else LineChart(
                series, now - 24 * H, now + 1800, hourTicks(now - 24 * H, now + 1800, 6, i.zone), nowSec = now, minSpan = 10.0,
            ),
            if (series.isEmpty()) "Pas encore d'historique de vent de la station." else "Pointillés : rafales.",
        )

        val wf = hourly(i, "wind_speed_10m", now - 3599, now + 24 * H)
        val gf = hourly(i, "wind_gusts_10m", now - 3599, now + 24 * H)
        val dir = hourly(i, "wind_direction_10m", now - 3599, now + 24 * H).firstOrNull()?.second
        val peak = gf.maxByOrNull { it.second } ?: wf.maxByOrNull { it.second }
        val fseries = ArrayList<LineSeries>()
        if (wf.isNotEmpty()) fseries.add(LineSeries(wf, Tone.ACCENT, fill = true))
        if (gf.isNotEmpty()) fseries.add(LineSeries(gf, Tone.WARN, dashed = true))
        val heures = DetailPage(
            "Heures",
            listOf(
                Stat("Vent max", num(wf.maxOfOrNull { it.second }, 0, " km/h"), Tone.ACCENT),
                Stat("Rafales max", num(gf.maxOfOrNull { it.second }, 0, " km/h"), Tone.WARN),
                Stat("Direction", dir?.let { Metric.cardinal(it) } ?: DASH),
                Stat("Pic vers", peak?.let { HOUR_FMT.format(Instant.ofEpochSecond(it.first).atZone(i.zone)) } ?: DASH),
            ),
            if (wf.size < 2) null else LineChart(
                fseries, wf.first().first, wf.last().first, hourTicks(wf.first().first, wf.last().first, 6, i.zone), nowSec = now, minSpan = 10.0,
            ),
            if (wf.size < 2) "Prévisions de vent indisponibles." else "Pointillés : rafales prévues.",
        )
        return Fiche(Tile.WIND, listOf(jour, heures))
    }

    // ───────────────────────────── Humidité ─────────────────────────────

    private fun humFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val hs = d?.humSeries.orEmpty().filter { it.tSec >= now - 24 * H }.map { it.tSec to it.v }
        val fromStation = hs.size >= 2
        val hf = hourly(i, "relative_humidity_2m", now - 3599, now + 24 * H)
        val line = if (fromStation) hs else hf
        val xMin = if (fromStation) now - 24 * H else hf.firstOrNull()?.first ?: now
        val xMax = if (fromStation) now + 1800 else hf.lastOrNull()?.first ?: now
        val jour = DetailPage(
            "Jour",
            listOf(
                Stat("Humidité", num(s?.humidity, 0, " %"), Tone.ACCENT),
                Stat("Point de rosée", deg(s?.dewPoint)),
                Stat("Mini", num(line.minOfOrNull { it.second }, 0, " %")),
                Stat("Maxi", num(line.maxOfOrNull { it.second }, 0, " %")),
            ),
            if (line.size < 2) null else LineChart(
                listOf(LineSeries(line, Tone.ACCENT, fill = true)), xMin, xMax, hourTicks(xMin, xMax, 6, i.zone),
                nowSec = now, minSpan = 20.0,
            ),
            when {
                line.size < 2 -> "Humidité indisponible."
                !fromStation -> "Prévision : pas encore d'historique de la station."
                else -> null
            },
        )

        val t = hourly(i, "temperature_2m", now - 3599, now + 24 * H)
        val td = hourly(i, "dew_point_2m", now - 3599, now + 24 * H)
        val gap = if (s?.outdoorTemp != null && s.dewPoint != null) s.outdoorTemp - s.dewPoint else null
        val rseries = ArrayList<LineSeries>()
        if (t.isNotEmpty()) rseries.add(LineSeries(t, Tone.WARM))
        if (td.isNotEmpty()) rseries.add(LineSeries(td, Tone.COLD, fill = true))
        val rosee = DetailPage(
            "Rosée",
            listOf(
                Stat("Rosée", deg(s?.dewPoint), Tone.COLD),
                Stat("Écart T − rosée", deg(gap), if (gap != null && gap < 2.0) Tone.WARN else Tone.NORMAL),
                Stat("Rosée mini 24 h", deg(td.minOfOrNull { it.second })),
                Stat("Rosée maxi 24 h", deg(td.maxOfOrNull { it.second })),
            ),
            if (t.size < 2 || td.size < 2) null else LineChart(
                rseries, t.first().first, t.last().first, hourTicks(t.first().first, t.last().first, 6, i.zone), nowSec = now, zeroLine = true,
            ),
            when {
                gap != null && gap in 0.0..2.0 -> "Air presque saturé : brouillard ou givre possibles."
                t.size < 2 || td.size < 2 -> "Prévisions indisponibles."
                else -> "Température (orange) et point de rosée (bleu)."
            },
        )
        return Fiche(Tile.HUM, listOf(jour, rosee))
    }

    // ───────────────────────────── Pression ─────────────────────────────

    private fun pressFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val s = i.snap
        val d = i.derived
        val ps = d?.pressSeries.orEmpty().filter { it.tSec >= now - 48 * H }.map { it.tSec to it.v }
        val ahead = hourly(i, "pressure_msl", now + 1, now + 12 * H)
        val series = ArrayList<LineSeries>()
        if (ps.size >= 2) series.add(LineSeries(ps, Tone.ACCENT, fill = true))
        if (ahead.isNotEmpty()) series.add(LineSeries(listOfNotNull(ps.lastOrNull()) + ahead, Tone.ACCENT, dashed = true))
        val xMax = if (ahead.isNotEmpty()) now + 12 * H else now + 1800
        val xMin = ps.firstOrNull()?.first ?: (now - 24 * H)
        val h48 = DetailPage(
            "48 h",
            listOf(
                Stat("Pression", num(s?.pressure, 0, " hPa"), Tone.ACCENT),
                Stat("Sur 3 h", signed(d?.pressTrend3h, 1, " hPa")),
                Stat("Mini", num(ps.minOfOrNull { it.second }, 0, " hPa")),
                Stat("Maxi", num(ps.maxOfOrNull { it.second }, 0, " hPa")),
            ),
            if (series.isEmpty()) null else LineChart(series, xMin, xMax, hourTicks(xMin, xMax, 12, i.zone), nowSec = now, minSpan = 6.0),
            if (ps.size < 2) "Pas encore d'historique de pression." else "Pointillés : prévision.",
        )

        val pf = hourly(i, "pressure_msl", now - 3599, now + 36 * H)
        val base = s?.pressure ?: pf.firstOrNull()?.second
        fun at(hours: Int): Double? {
            val target = now + hours * H
            val p = pf.minByOrNull { abs(it.first - target) } ?: return null
            return if (abs(p.first - target) <= H) p.second else null
        }
        val delta12 = if (base != null) at(12)?.let { it - base } else null
        val tendance = DetailPage(
            "Tendance",
            listOf(
                Stat("Dans 6 h", num(at(6), 0, " hPa")),
                Stat("Dans 12 h", num(at(12), 0, " hPa")),
                Stat("Dans 24 h", num(at(24), 0, " hPa")),
                Stat("Évolution 12 h", signed(delta12, 1, " hPa"), if (delta12 != null && delta12 < -2.0) Tone.WARN else Tone.NORMAL),
            ),
            if (pf.size < 2) null else LineChart(
                listOf(LineSeries(pf, Tone.ACCENT, fill = true)), pf.first().first, pf.last().first,
                hourTicks(pf.first().first, pf.last().first, 12, i.zone), nowSec = now, minSpan = 6.0,
            ),
            when {
                delta12 == null -> "Prévisions de pression indisponibles."
                delta12 < -2.0 -> "Baisse nette : dégradation probable."
                delta12 > 2.0 -> "Hausse nette : amélioration probable."
                else -> "Pression stable sur 12 h."
            },
        )
        return Fiche(Tile.PRESS, listOf(h48, tendance))
    }

    // ───────────────────────────── Prévisions ─────────────────────────────

    private fun forecastFiche(i: DetailInputs): Fiche {
        val now = i.nowSec
        val f = i.forecast
        val next = hourly(i, "temperature_2m", now - 3599, now + 24 * H)
        val probs = hourly(i, "precipitation_probability", now + 1, now + 24 * H)
        val sun = if (f?.sunriseSec != null && f.sunsetSec != null) {
            OmFormat.hhmm(f.sunriseSec) + " / " + OmFormat.hhmm(f.sunsetSec)
        } else {
            DASH
        }
        val heures = DetailPage(
            "Heures",
            listOf(
                Stat("Ciel", if (f != null) WeatherCodes.label(f.code, f.isDay ?: true) else DASH, Tone.ACCENT),
                Stat("Mini · Maxi", if (f?.dayMin != null && f.dayMax != null) deg(f.dayMin, 0) + " · " + deg(f.dayMax, 0) else DASH),
                Stat("Risque de pluie", num(probs.maxOfOrNull { it.second }, 0, " %"), Tone.RAIN),
                Stat("Lever / coucher", sun),
            ),
            if (next.size < 2) null else LineChart(
                listOf(LineSeries(next, Tone.ACCENT, fill = true)), next.first().first, next.last().first,
                hourTicks(next.first().first, next.last().first, 6, i.zone), nowSec = now, zeroLine = true,
            ),
            if (next.size < 2) "Prévisions indisponibles." else null,
        )

        val tmx = daily(i, "temperature_2m_max")
        val tmn = daily(i, "temperature_2m_min")
        val todayIdx = tmx.indexOfLast { it.first <= now }
        val dayCount = if (todayIdx >= 0) minOf(7, tmx.size - todayIdx) else 0
        val hiPts = ArrayList<Pair<Long, Double>>()
        val loPts = ArrayList<Pair<Long, Double>>()
        for (k in 0 until dayCount) {
            val t = tmx[todayIdx + k].first + 12 * H
            tmx[todayIdx + k].second?.let { hiPts.add(t to it) }
            tmn.getOrNull(todayIdx + k)?.second?.let { loPts.add(t to it) }
        }
        fun dayStat(offset: Int): Stat {
            val t = tmx.getOrNull(todayIdx + offset)?.first ?: return Stat("—", DASH)
            val name = DAY_FMT.format(Instant.ofEpochSecond(t + 12 * H).atZone(i.zone))
            val hi = tmx.getOrNull(todayIdx + offset)?.second
            val lo = tmn.getOrNull(todayIdx + offset)?.second
            return Stat(name, if (hi != null && lo != null) deg(hi, 0) + " / " + deg(lo, 0) else DASH)
        }
        val sept = DetailPage(
            "7 j",
            listOf(dayStat(1), dayStat(2), dayStat(3), dayStat(4)),
            if (hiPts.size < 2) null else LineChart(
                listOfNotNull(LineSeries(hiPts, Tone.WARM), loPts.takeIf { it.size >= 2 }?.let { LineSeries(it, Tone.COLD) }),
                hiPts.first().first, hiPts.last().first,
                hiPts.map { it.first to DAY_FMT.format(Instant.ofEpochSecond(it.first).atZone(i.zone)) },
                zeroLine = true,
            ),
            if (hiPts.size < 2) "Prévisions sur 7 jours indisponibles." else "Orange : maxi · bleu : mini.",
        )
        return Fiche(Tile.FORECAST, listOf(heures, sept))
    }

    // ───────────────────────────── Outils ─────────────────────────────

    private fun midnight(i: DetailInputs): Long =
        Instant.ofEpochSecond(i.nowSec).atZone(i.zone).toLocalDate().atStartOfDay(i.zone).toEpochSecond()

    /** Valeurs horaires d'une variable entre deux instants (inclus), sans les trous. */
    private fun hourly(i: DetailInputs, name: String, fromSec: Long, toSec: Long): List<Pair<Long, Double>> =
        i.forecast?.raw?.hourlySeries(name).orEmpty().mapNotNull { p ->
            val v = p.second
            if (v != null && p.first in fromSec..toSec) p.first to v else null
        }

    private fun hourlyMap(i: DetailInputs, name: String): Map<Long, Double> =
        i.forecast?.raw?.hourlySeries(name).orEmpty().mapNotNull { p -> p.second?.let { p.first to it } }.toMap()

    private fun daily(i: DetailInputs, name: String): List<Pair<Long, Double?>> =
        i.forecast?.raw?.dailySeries(name).orEmpty()

    /** Valeur du jour courant + `offset` jours. */
    private fun dayAt(series: List<Pair<Long, Double?>>, i: DetailInputs, offset: Int): Double? {
        val today = series.indexOfLast { it.first <= i.nowSec }
        if (today < 0) return null
        return series.getOrNull(today + offset)?.second
    }

    /** Repères horaires alignés sur les heures locales multiples de `step`. */
    private fun hourTicks(xMin: Long, xMax: Long, step: Int, zone: ZoneId): List<Pair<Long, String>> {
        var z = Instant.ofEpochSecond(xMin).atZone(zone).truncatedTo(ChronoUnit.HOURS)
        var guard = 0
        while ((z.toEpochSecond() < xMin || z.hour % step != 0) && guard++ < 60) z = z.plusHours(1)
        val out = ArrayList<Pair<Long, String>>()
        while (z.toEpochSecond() <= xMax && out.size < 24) {
            out.add(z.toEpochSecond() to HOUR_FMT.format(z))
            z = z.plusHours(step.toLong())
        }
        return out
    }

    private fun hundreds(x: Double): Int = (Math.round(x / 100.0) * 100L).toInt()

    private fun hundredsOrExact(x: Double): String = Math.round(x).toString()

    private fun deg(v: Double?, decimals: Int = 1): String = if (v == null) DASH else Metric.fmt(v, decimals) + "°"

    private fun num(v: Double?, decimals: Int, unit: String): String = if (v == null) DASH else Metric.fmt(v, decimals) + unit

    private fun signed(v: Double?, decimals: Int, unit: String): String {
        if (v == null) return DASH
        val t = Metric.fmt(v, decimals)
        val plus = v > 0 && t.trim('0', '.', ',').isNotEmpty()
        return (if (plus) "+" else "") + t + unit
    }
}
