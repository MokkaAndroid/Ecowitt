package app.ecowidget.data

import androidx.annotation.DrawableRes
import app.ecowidget.R
import java.time.ZoneId

/** Couleur sémantique d'une valeur ou d'une courbe ; chaque thème la traduit en vraie couleur. */
enum class Tone { NORMAL, ACCENT, RAIN, SNOW, ICE, WARM, COLD, WARN }

/** Les sujets du widget compact. L'ordre est celui de l'affichage des tuiles. */
enum class Tile(val id: String, val label: String, val short: String, @DrawableRes val icon: Int) {
    TEMP("temp", "Température", "Temp.", R.drawable.ic_thermometer),
    RAIN("rain", "Pluie", "Pluie", R.drawable.ic_cloud_rain),
    SNOW("snow", "Limite neige", "Neige", R.drawable.ic_wx_snow),
    WIND("wind", "Vent", "Vent", R.drawable.ic_wind),
    HUM("hum", "Humidité", "Humidité", R.drawable.ic_droplet),
    PRESS("press", "Pression", "Pression", R.drawable.ic_gauge),
    FORECAST("fc", "Prévisions", "Prévis.", R.drawable.ic_wx_partly);

    companion object {
        fun fromId(id: String?): Tile? = entries.firstOrNull { it.id == id }
    }
}

/** Une valeur chiffrée d'une fiche (libellé + valeur). */
data class Stat(val label: String, val value: String, val tone: Tone = Tone.NORMAL)

/** Une courbe : points (instant en secondes, valeur). */
data class LineSeries(
    val pts: List<Pair<Long, Double>>,
    val tone: Tone,
    val dashed: Boolean = false,
    val fill: Boolean = false,
)

data class Bar(val v: Double, val tone: Tone)

/** Description d'un graphique, indépendante de la façon de le dessiner (bitmap dans le widget et dans la fiche). */
sealed interface ChartSpec

data class LineChart(
    val series: List<LineSeries>,
    val xMin: Long,
    val xMax: Long,
    val xTicks: List<Pair<Long, String>>,
    /** Trait vertical « maintenant ». */
    val nowSec: Long? = null,
    /** Trait horizontal de référence (par exemple l'altitude de l'utilisateur). */
    val refY: Double? = null,
    val refLabel: String? = null,
    val yDecimals: Int = 0,
    /** Étendue verticale minimale, pour qu'une valeur quasi constante ne remplisse pas tout le cadre. */
    val minSpan: Double = 2.0,
    val zeroLine: Boolean = false,
) : ChartSpec

data class BarChart(
    val bars: List<Bar>,
    val xTicks: List<Pair<Int, String>>,
    /** Valeur maximale de l'échelle ; sinon la plus grande barre, au moins [minMax]. */
    val yMax: Double? = null,
    val minMax: Double = 1.0,
    /** Racine carrée : une pluie faible reste visible à côté d'une averse. */
    val sqrt: Boolean = false,
    val yDecimals: Int = 1,
) : ChartSpec

/** Un onglet d'une fiche : quelques valeurs, un graphique, une remarque. */
data class DetailPage(val tab: String, val stats: List<Stat>, val chart: ChartSpec?, val note: String? = null)

data class Fiche(val tile: Tile, val pages: List<DetailPage>)

/** Ce que la tuile affiche sur l'écran d'accueil. */
data class TileSummary(
    val tile: Tile,
    val value: String,
    val label: String,
    @DrawableRes val icon: Int,
    val tone: Tone = Tone.NORMAL,
    /** Température qui pilote la couleur de la valeur (tuile Température). */
    val colorTemp: Double? = null,
)

/** Tout ce dont les fiches ont besoin, lu une fois. */
class DetailInputs(
    val snap: WeatherSnapshot?,
    val derived: Derived?,
    val forecast: Forecast?,
    /** Altitude de l'utilisateur (m). */
    val altM: Double?,
    val nowSec: Long = System.currentTimeMillis() / 1000,
    val zone: ZoneId = ZoneId.systemDefault(),
)

object DetailData {
    /** Délai sans interaction après lequel le widget revient aux tuiles (mode 3). */
    const val AUTO_CLOSE_MS = 30_000L

    /** Variables Open-Meteo demandées en plus quand un mode à tuiles est actif. */
    val NEEDS = Needs(
        current = emptySet(),
        hourly = setOf(
            "apparent_temperature", "precipitation", "snowfall", "snowfall_height", "freezing_level_height",
            "wind_speed_10m", "wind_gusts_10m", "wind_direction_10m", "relative_humidity_2m", "dew_point_2m",
            "pressure_msl", "precipitation_probability",
        ),
        daily = setOf("precipitation_sum", "snowfall_sum", "precipitation_probability_max"),
        air = emptySet(),
        days = 8,
    )
}
