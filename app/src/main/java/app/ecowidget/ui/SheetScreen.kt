package app.ecowidget.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ecowidget.R
import app.ecowidget.data.Block
import app.ecowidget.data.Card
import app.ecowidget.data.Cell
import app.ecowidget.data.CellsB
import app.ecowidget.data.ChartB
import app.ecowidget.data.DayRows
import app.ecowidget.data.Drawing
import app.ecowidget.data.Gfx
import app.ecowidget.data.Hero
import app.ecowidget.data.Hue
import app.ecowidget.data.IconHero
import app.ecowidget.data.InfoChip
import app.ecowidget.data.Legend
import app.ecowidget.data.LegendB
import app.ecowidget.data.LegendKind
import app.ecowidget.data.LineRow
import app.ecowidget.data.Metric2
import app.ecowidget.data.MiniCards
import app.ecowidget.data.NoteB
import app.ecowidget.data.PillB
import app.ecowidget.data.PollenGrid
import app.ecowidget.data.ScaleB
import app.ecowidget.data.Seg
import app.ecowidget.data.Segments
import app.ecowidget.data.Sheet
import app.ecowidget.data.SheetCharts
import app.ecowidget.data.StatGrid
import app.ecowidget.data.TextPair
import app.ecowidget.data.Tile

/*
 * Rendu des fiches : mêmes dimensions, couleurs et textes que les maquettes « H ».
 * Les valeurs (dp / sp) sont celles des feuilles de style des maquettes.
 */

private val Dim = Color(0xFF06090C)
private val SheetBg = Color(0xFF111A23)

private fun Hue.col(): Color = Color(c)

private fun Hue.alpha(a: Float): Color = Color(c).copy(alpha = a)

// ───────────────────────────── Écran ─────────────────────────────

@Composable
fun SheetScreen(sheet: Sheet, onTile: (Tile) -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Dim).statusBarsPadding(),
    ) {
        Spacer(Modifier.fillMaxWidth().height(16.dp).clickable(onClick = onClose))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(SheetBg)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(top = 10.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFF3A4D61)))
            }
            Header(sheet, onClose)
            ChipNav(sheet, onTile)
            Column(
                modifier = Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                sheet.blocks.forEach { BlockView(it) }
            }
        }
    }
}

@Composable
private fun Header(sheet: Sheet, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(sheet.hue.alpha(0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(sheet.icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(sheet.hue.col()))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sheet.title, color = Hue.TXT.col(), fontSize = 19.sp, fontWeight = FontWeight.Medium, lineHeight = 24.sp)
            Text(sheet.subtitle, color = Hue.MUTED.col(), fontSize = 12.sp, lineHeight = 16.sp)
        }
        Box(
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(Hue.CARD.col()).clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Image(painterResource(R.drawable.mk_close), "Fermer la fiche", Modifier.size(18.dp), colorFilter = ColorFilter.tint(Hue.TXT.col()))
        }
    }
}

/** Bandeau des autres fiches : la fiche précédente, la fiche ouverte, puis les suivantes (comme dans les maquettes). */
@Composable
private fun ChipNav(sheet: Sheet, onTile: (Tile) -> Unit) {
    val all = Tile.entries
    val at = all.indexOf(sheet.tile)
    val start = maxOf(0, at - 1)
    val seq = all.drop(start) + all.take(start)
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        seq.forEach { t ->
            val on = t == sheet.tile
            Box(
                modifier = Modifier
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) sheet.hue.alpha(0.18f) else Hue.CARD.col())
                    .border(1.dp, if (on) sheet.hue.alpha(0.6f) else Hue.CARD.col(), RoundedCornerShape(18.dp))
                    .clickable { onTile(t) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label, color = if (on) Hue.TXT.col() else Hue.MUTED.col(), fontSize = 13.sp,
                    fontWeight = FontWeight.Medium, lineHeight = 16.sp, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

// ───────────────────────────── Briques ─────────────────────────────

@Composable
private fun BlockView(b: Block) {
    when (b) {
        is Hero -> HeroView(b)
        is IconHero -> IconHeroView(b)
        is PillB -> PillView(b)
        is StatGrid -> StatGridView(b)
        is MiniCards -> MiniCardsView(b)
        is Card -> CardView(b)
        is ChartB -> ChartView(b)
        is LegendB -> LegendView(b)
        is NoteB -> Text(b.text, color = Hue.MUTED.col(), fontSize = 11.sp, lineHeight = 16.sp)
        is CellsB -> CellsView(b)
        is InfoChip -> InfoChipView(b)
        is LineRow -> LineRowView(b)
        is DayRows -> DayRowsView(b)
        is ScaleB -> ScaleView(b)
        is Segments -> SegmentsView(b)
        is PollenGrid -> PollenView(b)
        is TextPair -> TextPairView(b)
    }
}

@Composable
private fun HeroView(h: Hero) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f, fill = false), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(h.value, color = h.hue.col(), fontSize = 54.sp, fontWeight = FontWeight.Light, lineHeight = 60.sp, maxLines = 1, softWrap = false)
                if (h.unit.isNotEmpty()) {
                    Text(h.unit, color = Hue.MUTED.col(), fontSize = 18.sp, lineHeight = 24.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                h.lines.forEach { Text(it, color = Hue.MUTED.col(), fontSize = 13.sp, lineHeight = 18.sp) }
            }
        }
        h.compassFrom?.let { Box(Modifier.padding(start = 12.dp)) { DrawingView(SheetCharts.compass(it), 76f) } }
    }
}

@Composable
private fun IconHeroView(h: IconHero) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Image(painterResource(h.icon), null, Modifier.size(48.dp), colorFilter = ColorFilter.tint(h.hue.col()))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(h.title, color = Hue.TXT.col(), fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp)
            Text(h.line, color = Hue.MUTED.col(), fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun PillView(p: PillB) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(p.hue.alpha(0.16f))
            .padding(start = 10.dp, top = 6.dp, end = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(painterResource(p.icon), null, Modifier.size(15.dp), colorFilter = ColorFilter.tint(p.hue.col()))
        Text(p.text, color = p.hue.col(), fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun MetricView(m: Metric2, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(m.label, color = Hue.MUTED.col(), fontSize = 10.sp, fontWeight = FontWeight.Medium, lineHeight = 14.sp, letterSpacing = 0.5.sp, maxLines = 1, overflow = TextOverflow.Clip)
        Row {
            Text(
                m.value, color = m.hue.col(), fontSize = m.size.sp, fontWeight = FontWeight.Medium, lineHeight = (m.size + 4).sp,
                maxLines = 1, softWrap = false, modifier = Modifier.alignByBaseline(),
            )
            if (m.unit.isNotEmpty()) {
                Text(m.unit, color = Hue.MUTED.col(), fontSize = 12.sp, modifier = Modifier.padding(start = 3.dp).alignByBaseline(), maxLines = 1, softWrap = false)
            }
        }
        m.sub?.let { Text(it, color = Hue.MUTED.col(), fontSize = 11.sp, lineHeight = 14.sp) }
    }
}

@Composable
private fun StatGridView(g: StatGrid) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(g.gap.dp)) {
        g.items.chunked(g.cols).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(g.gap.dp)) {
                row.forEach { MetricView(it, Modifier.weight(1f)) }
                repeat(g.cols - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MiniCardsView(g: MiniCards) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        g.items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach {
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Hue.CARD.col()).padding(horizontal = 14.dp, vertical = 12.dp),
                    ) { MetricView(it, Modifier.fillMaxWidth()) }
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun CardView(c: Card) {
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Hue.CARD.col()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(c.gap.dp),
    ) {
        if (c.title != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(c.title, color = Hue.TXT.col(), fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp, modifier = Modifier.weight(1f).alignByBaseline())
                c.right?.let {
                    Text(it, color = Hue.MUTED.col(), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.End, modifier = Modifier.alignByBaseline())
                }
            }
        }
        c.content.forEach { BlockView(it) }
    }
}

// ───────────────────────────── Dessins ─────────────────────────────

/** Affiche un dessin exprimé en dp : le canvas est mis à l'échelle de l'écran. */
@Composable
private fun DrawingView(d: Drawing, widthDp: Float) {
    Canvas(Modifier.size(widthDp.dp, d.heightDp.dp)) {
        val k = density
        drawIntoCanvas { c ->
            val nc = c.nativeCanvas
            nc.save()
            nc.scale(k, k)
            runCatching { d.draw(nc) }
            nc.restore()
        }
    }
}

@Composable
private fun ChartView(b: ChartB) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val w = maxWidth.value
        val d = remember(b, w) { runCatching { b.maker.make(w) }.getOrNull() }
        if (d != null) DrawingView(d, w)
    }
}

@Composable
private fun Swatch(kind: LegendKind, hue: Hue) {
    val narrow = kind == LegendKind.LINE || kind == LegendKind.DASH || kind == LegendKind.BAND
    val w = if (narrow) 18f else 12f
    val h = if (narrow) 10f else 12f
    DrawingView(Drawing(h) { c -> Gfx.swatch(c, kind, hue) }, w)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LegendView(l: LegendB) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        l.items.forEach { LegendItem(it) }
    }
}

@Composable
private fun LegendItem(it: Legend) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Swatch(it.kind, it.hue)
        Text(it.text, color = Hue.MUTED.col(), fontSize = 11.sp, lineHeight = 14.sp)
    }
}

// ───────────────────────────── Cellules d'heures, lignes ─────────────────────────────

@Composable
private fun CellsView(b: CellsB) {
    val gap = if (b.big) 4.dp else 2.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
        b.cells.forEach { CellView(it, b.big, Modifier.weight(1f)) }
    }
}

@Composable
private fun CellView(c: Cell, big: Boolean, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(c.hr, color = Hue.MUTED.col(), fontSize = if (big) 11.sp else 10.sp, lineHeight = if (big) 14.sp else 13.sp, maxLines = 1, softWrap = false)
        Image(
            painterResource(c.icon), null,
            Modifier.size(if (big) 20.dp else 18.dp).rotate(c.rotate),
            colorFilter = ColorFilter.tint(c.iconHue.col()),
        )
        Text(
            c.main, color = Hue.TXT.col(), fontSize = if (big) 15.sp else 13.sp, fontWeight = FontWeight.Medium,
            lineHeight = if (big) 18.sp else 16.sp, maxLines = 1, softWrap = false,
        )
        Text(c.sub, color = c.subHue.col(), fontSize = if (big) 10.sp else 9.sp, lineHeight = if (big) 13.sp else 12.sp, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun InfoChipView(c: InfoChip) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, Hue.LINE.col(), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Image(painterResource(c.icon), null, Modifier.size(14.dp), colorFilter = ColorFilter.tint(c.hue.col()))
        Text(c.text, color = Hue.TXT.col(), fontSize = 12.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, maxLines = 1)
    }
}

@Composable
private fun LineRowView(r: LineRow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(painterResource(r.icon), null, Modifier.size(28.dp), colorFilter = ColorFilter.tint(r.iconHue.col()))
        Text(r.text, color = Hue.MUTED.col(), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        r.right?.let {
            if (r.rightMulti) {
                Text(it, color = Hue.MUTED.col(), fontSize = 12.sp, lineHeight = 16.sp, textAlign = TextAlign.End, softWrap = false)
            } else {
                Text(it, color = Hue.TXT.col(), fontSize = 17.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun DayRowsView(d: DayRows) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        d.rows.forEach { r ->
            Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(r.day, color = Hue.TXT.col(), fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(56.dp))
                Image(painterResource(r.icon), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(r.iconHue.col()))
                Text(r.mm, color = Hue.BLUE.col(), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(50.dp))
                Text(r.lo, color = Hue.MUTED.col(), fontSize = 13.sp, lineHeight = 16.sp, textAlign = TextAlign.End, maxLines = 1, softWrap = false, modifier = Modifier.width(30.dp))
                BoxWithConstraints(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Hue.LINE.col())) {
                    val total = maxWidth
                    Box(
                        Modifier
                            .offset(x = total * r.from)
                            .width(total * (r.to - r.from).coerceAtLeast(0.02f))
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Hue.TEAL.col()),
                    )
                }
                Text(r.hi, color = Hue.TXT.col(), fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 16.sp, maxLines = 1, softWrap = false, modifier = Modifier.width(30.dp))
            }
        }
    }
}

// ───────────────────────────── Échelles ─────────────────────────────

private val AIR_SCALE = listOf(Hue.GREEN, Hue.LIME, Hue.YEL, Hue.ORANGE, Hue.RED, Hue.PINK)

@Composable
private fun ScaleView(s: ScaleB) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                AIR_SCALE.forEachIndexed { i, h ->
                    val shape = when (i) {
                        0 -> RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)
                        5 -> RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)
                        else -> RoundedCornerShape(0.dp)
                    }
                    Box(Modifier.weight(1f).height(8.dp).clip(shape).background(h.col()))
                }
            }
            Box(
                Modifier
                    .offset(x = maxWidth * s.pos - 2.dp)
                    .size(width = 4.dp, height = 16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Hue.TXT.col()),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Bon", "Moyen", "Très mauvais").forEach { Text(it, color = Hue.MUTED.col(), fontSize = 10.sp, lineHeight = 13.sp) }
        }
    }
}

@Composable
private fun SegmentsView(s: Segments) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        s.items.forEach { SegView(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun SegView(s: Seg, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (s.on) Hue.PRES.col() else Hue.LINE.col()))
        Text(
            s.name, color = if (s.on) Hue.TXT.col() else Hue.MUTED.col(), fontSize = 11.sp, lineHeight = 14.sp,
            fontWeight = if (s.on) FontWeight.Medium else FontWeight.Normal,
        )
        Text(s.range, color = Hue.MUTED.col(), fontSize = 10.sp, lineHeight = 13.sp)
    }
}

@Composable
private fun PollenView(p: PollenGrid) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        p.items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (name, v) ->
                    Row(
                        Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(10.dp)).background(Hue.CARD2.col()).padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(name, color = Hue.MUTED.col(), fontSize = 12.sp, lineHeight = 16.sp)
                        Text(v, color = Hue.DIM.col(), fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TextPairView(t: TextPair) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(t.left, color = Hue.MUTED.col(), fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(12.dp))
        Text(t.right, color = Hue.MUTED.col(), fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f, fill = false))
    }
}
