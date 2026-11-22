package app.ecowidget.widget

import android.content.Context

/**
 * Lisse le changement de scénario : on passe tout de suite à une situation urgente (orage, pluie, hors ligne),
 * mais on attend 10 min avant de basculer vers une situation calme, et 30 min après la pluie ou l'orage
 * (le bilan de pluie reste affiché), pour que la mise en page ne saute pas à chaque rafraîchissement.
 */
internal object BentoState {
    private const val FILE = "ecowidget_bento"
    private const val HOLD_CALM_MIN = 10L
    private const val HOLD_AFTER_RAIN_MIN = 30L

    fun resolve(ctx: Context, wanted: Scn, nowMs: Long): Scn {
        return try {
            val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val cur = p.getString("scn", null)?.let { n -> Scn.entries.firstOrNull { it.name == n } }
            if (cur == null || cur == wanted) {
                val e = p.edit()
                if (cur != wanted) e.putString("scn", wanted.name)
                e.remove("cand").apply()
                return wanted
            }
            val immediate = wanted == Scn.STORM || wanted == Scn.OFFLINE || wanted == Scn.RAIN || cur == Scn.OFFLINE
            if (immediate) {
                p.edit().putString("scn", wanted.name).remove("cand").apply()
                return wanted
            }
            val cand = p.getString("cand", null)
            val since = p.getLong("since", 0L)
            if (cand != wanted.name) {
                p.edit().putString("cand", wanted.name).putLong("since", nowMs).apply()
                return cur
            }
            val hold = if (cur == Scn.RAIN || cur == Scn.STORM) HOLD_AFTER_RAIN_MIN else HOLD_CALM_MIN
            if (nowMs - since >= hold * 60_000L) {
                p.edit().putString("scn", wanted.name).remove("cand").apply()
                wanted
            } else {
                cur
            }
        } catch (e: Exception) {
            wanted
        }
    }
}

/** Une tuile placée sur la grille (colonne, rangée, étendue). `half` : 0 entière, 1 moitié haute, 2 moitié basse (duo humidité / pression). */
internal class Item(val spec: Spec, val c: Int, val r: Int, val sc: Int, val sr: Int, val half: Int = 0)

/**
 * Choisit et place les tuiles selon le scénario : chaque scénario donne une liste de souhaits par ordre de priorité,
 * posés au premier emplacement libre (de haut en bas, de gauche à droite), dans la plus grande forme qui tient.
 * Humidité et pression restent toujours visibles : en dernier recours elles se partagent une case (duo).
 */
internal object BentoPlanner {

    private class Wish(val kind: BKind, val shapes: List<Shape>, val bottom: Boolean = false, val duo: Boolean = false)

    private val ONE = Shape(1, 1)

    private fun wishes(scn: Scn, rows: Int, d: BData): List<Wish> {
        val out = ArrayList<Wish>()
        fun add(k: BKind, vararg sh: Shape, bottom: Boolean = false) {
            out.add(Wish(k, sh.toList(), bottom))
        }
        fun duo() {
            out.add(Wish(BKind.HUM_MINI, listOf(ONE), duo = true))
        }
        add(BKind.HERO, Shape(2, 2))
        val strongWind = (d.snap?.windSpeed ?: 0.0) >= 30.0 || (d.snap?.windGust ?: 0.0) >= 45.0

        when (scn) {
            Scn.DRY -> {
                add(BKind.WIND, ONE)
                add(BKind.UV, ONE)
                add(BKind.HUM, ONE)
                add(BKind.PRESS, ONE)
                add(BKind.AIR, ONE)
                if (rows >= 4) {
                    if (d.pollen() != null) add(BKind.POLLEN, Shape(2, 1)) else add(BKind.TOMORROW, Shape(2, 1))
                    add(BKind.HOME, ONE)
                }
                if (rows >= 5) add(BKind.WEEK, Shape(3, 1))
            }
            Scn.ANNOUNCED -> {
                add(BKind.RAIN12, Shape(3, 2), Shape(3, 1), bottom = true)
                add(BKind.WIND, ONE)
                add(BKind.HUM, ONE)
                add(BKind.PRESS, ONE)
                add(BKind.AIR, ONE)
                add(BKind.HOME, ONE)
            }
            Scn.RAIN -> {
                if (d.rainNow) {
                    add(BKind.RATE, Shape(1, 2))
                } else {
                    add(BKind.WIND, ONE)
                    add(BKind.HUM, ONE)
                }
                add(BKind.RAIN60, if (rows >= 4) Shape(3, 1) else Shape(2, 1))
                add(BKind.RAIN12, Shape(2, 2), Shape(2, 1))
                if (rows >= 5) {
                    add(BKind.HUM, ONE)
                    add(BKind.PRESS, ONE)
                } else {
                    duo()
                }
            }
            Scn.WINTER -> {
                add(BKind.WIND, ONE)
                if (d.snow24() != null) {
                    if (rows >= 5) {
                        add(BKind.HUM, ONE)
                        add(BKind.SNOW24, Shape(3, 1))
                        add(BKind.SNOW_ALT, Shape(1, 2), ONE)
                        add(BKind.FROST, Shape(2, 1))
                        add(BKind.PRESS, ONE)
                        add(BKind.HOME, ONE)
                    } else {
                        duo()
                        add(BKind.SNOW24, Shape(3, 1))
                        add(BKind.SNOW_ALT, ONE)
                        add(BKind.FROST, Shape(2, 1))
                    }
                } else {
                    add(BKind.HUM, ONE)
                    add(BKind.FROST, Shape(2, 1))
                    add(BKind.PRESS, ONE)
                    add(BKind.TOMORROW, Shape(2, 1))
                    add(BKind.HOME, ONE)
                    add(BKind.WEEK, Shape(3, 1))
                }
            }
            Scn.HEAT -> {
                if (strongWind) add(BKind.WIND, ONE)
                add(BKind.HUM, ONE)
                add(BKind.AIR, ONE)
                add(BKind.UV, Shape(3, 1))
                add(BKind.POLLEN, Shape(2, 1))
                add(BKind.PRESS, ONE)
                add(BKind.TOMORROW, Shape(2, 1))
                add(BKind.HOME, ONE)
            }
            Scn.OFFLINE -> {
                add(BKind.WIND, ONE)
                add(BKind.UV, ONE)
                add(BKind.OFFLINE, Shape(3, 1))
                add(BKind.HUM, ONE)
                add(BKind.PRESS, ONE)
                add(BKind.AIR, ONE)
                add(BKind.WEEK, Shape(3, 1))
            }
            Scn.STORM -> {
                add(BKind.STORM, Shape(1, 2))
                add(BKind.RAIN60, if (rows >= 4) Shape(3, 1) else Shape(2, 1))
                when {
                    rows >= 5 -> {
                        add(BKind.GUSTS, Shape(3, 1))
                        add(BKind.HUM, ONE)
                        add(BKind.PRESS, ONE)
                        add(BKind.WIND, ONE)
                    }
                    rows == 4 -> {
                        add(BKind.HUM, ONE)
                        add(BKind.PRESS, ONE)
                        add(BKind.WIND, ONE)
                    }
                    else -> duo()
                }
            }
        }
        return out
    }

    /** Grille de 3 colonnes × `rows` rangées. */
    fun grid(d: BData, scn: Scn, rows: Int): List<Item> {
        val occ = Array(rows) { BooleanArray(3) }
        val placed = ArrayList<Item>()

        fun free(c: Int, r: Int, sc: Int, sr: Int): Boolean {
            if (c < 0 || r < 0 || c + sc > 3 || r + sr > rows) return false
            for (rr in r until r + sr) for (cc in c until c + sc) if (occ[rr][cc]) return false
            return true
        }
        fun mark(c: Int, r: Int, sc: Int, sr: Int, v: Boolean) {
            for (rr in r until r + sr) for (cc in c until c + sc) occ[rr][cc] = v
        }
        fun firstFit(sc: Int, sr: Int): Pair<Int, Int>? {
            for (r in 0..rows - sr) for (c in 0..3 - sc) if (free(c, r, sc, sr)) return c to r
            return null
        }
        fun bottomFit(sc: Int, sr: Int): Pair<Int, Int>? {
            val r = rows - sr
            for (c in 0..3 - sc) if (free(c, r, sc, sr)) return c to r
            return null
        }
        fun has(kind: BKind): Boolean = placed.any { it.spec.kind == kind }

        fun put(spec: Spec, pos: Pair<Int, Int>, sh: Shape) {
            placed.add(Item(spec, pos.first, pos.second, sh.c, sh.r))
            mark(pos.first, pos.second, sh.c, sh.r, true)
        }

        fun putDuo(pos: Pair<Int, Int>): Boolean {
            val hm = d.spec(BKind.HUM_MINI, ONE, scn)
            val pm = d.spec(BKind.PRESS_MINI, ONE, scn)
            if (hm == null || pm == null) return false
            placed.add(Item(hm, pos.first, pos.second, 1, 1, half = 1))
            placed.add(Item(pm, pos.first, pos.second, 1, 1, half = 2))
            mark(pos.first, pos.second, 1, 1, true)
            return true
        }

        fun place(w: Wish) {
            if (w.duo) {
                if (has(BKind.HUM_MINI) || has(BKind.HUM) || has(BKind.PRESS)) return
                firstFit(1, 1)?.let { putDuo(it) }
                return
            }
            if (has(w.kind) || (w.kind == BKind.SNOW_ALT && (has(BKind.SNOW_LIM) || has(BKind.SNOW_ALT)))) return
            for (sh in w.shapes) {
                val spec = d.spec(w.kind, sh, scn) ?: return
                val pos = (if (w.bottom) bottomFit(sh.c, sh.r) else firstFit(sh.c, sh.r)) ?: continue
                put(spec, pos, sh)
                return
            }
        }

        val all = wishes(scn, rows, d)
        // Le héros d'abord, puis les tuiles ancrées en bas (leur place est réservée), puis le reste par priorité.
        all.filter { it.kind == BKind.HERO }.forEach { place(it) }
        all.filter { it.bottom }.forEach { place(it) }
        all.filter { it.kind != BKind.HERO && !it.bottom }.forEach { place(it) }
        if (placed.none { it.spec.kind == BKind.HERO }) return emptyList()

        // Humidité et pression doivent rester visibles : à défaut de place pour les deux, elles se partagent une case.
        val hasBoth = (has(BKind.HUM) && has(BKind.PRESS)) || has(BKind.HUM_MINI)
        if (!hasBoth && d.hum() != null && d.press() != null) {
            placed.filter { it.spec.kind == BKind.HUM || it.spec.kind == BKind.PRESS }.toList().forEach {
                mark(it.c, it.r, it.sc, it.sr, false)
                placed.remove(it)
            }
            var pos = firstFit(1, 1)
            if (pos == null) {
                // Aucune case libre : on remplace la dernière petite tuile posée.
                val victim = placed.lastOrNull { it.sc == 1 && it.sr == 1 && it.spec.kind != BKind.HERO }
                if (victim != null) {
                    mark(victim.c, victim.r, 1, 1, false)
                    placed.remove(victim)
                    pos = victim.c to victim.r
                }
            }
            if (pos != null) putDuo(pos)
        }

        // Cases restées vides : on les comble avec les petites tuiles pas encore affichées.
        val fillers = listOf(BKind.HOME, BKind.AIR, BKind.WIND, BKind.UV)
        for (r in 0 until rows) for (c in 0 until 3) {
            if (occ[r][c]) continue
            for (k in fillers) {
                if (has(k)) continue
                val spec = d.spec(k, ONE, scn) ?: continue
                put(spec, c to r, ONE)
                break
            }
        }
        return placed
    }

    /** Bandeau : héros bas (3 colonnes) puis une rangée de petites tuiles ; `Item.r` vaut 1 pour cette rangée. */
    fun band(d: BData, scn: Scn): List<Item> {
        val out = ArrayList<Item>()
        val hero = d.spec(BKind.HERO, Shape(3, 1), scn) ?: return emptyList()
        out.add(Item(hero, 0, 0, 3, 1))
        var col = 0
        fun add(kind: BKind, span: Int): Boolean {
            if (col + span > 2) return false
            val spec = d.spec(kind, Shape(span, 1), scn) ?: return false
            out.add(Item(spec, col, 1, span, 1))
            col += span
            return true
        }
        fun small(vararg kinds: BKind) {
            for (k in kinds) if (add(k, 1)) return
        }
        when (scn) {
            Scn.DRY -> {
                small(BKind.WIND)
                small(BKind.UV, BKind.AIR, BKind.HOME)
            }
            Scn.ANNOUNCED -> if (!add(BKind.RAIN12, 2)) {
                small(BKind.WIND)
                small(BKind.AIR, BKind.HOME)
            }
            Scn.RAIN, Scn.STORM -> if (!add(BKind.RAIN60, 2)) {
                small(BKind.WIND)
                small(BKind.AIR, BKind.HOME)
            }
            Scn.WINTER -> if (!add(BKind.SNOW24, 2) && !add(BKind.FROST, 2)) {
                small(BKind.WIND)
                small(BKind.HOME, BKind.AIR)
            }
            Scn.HEAT -> if (!add(BKind.UV, 2)) {
                small(BKind.WIND)
                small(BKind.AIR, BKind.HOME)
            }
            Scn.OFFLINE -> if (!add(BKind.OFFLINE, 2)) {
                small(BKind.WIND)
                small(BKind.AIR, BKind.HOME)
            }
        }
        val hm = d.spec(BKind.HUM_MINI, ONE, scn)
        val pm = d.spec(BKind.PRESS_MINI, ONE, scn)
        if (hm != null && pm != null) {
            out.add(Item(hm, 2, 1, 1, 1, half = 1))
            out.add(Item(pm, 2, 1, 1, 1, half = 2))
        }
        return out
    }
}
