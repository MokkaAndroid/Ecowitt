package app.ecowidget.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.floor
import kotlin.math.min

/** Petite grille de valeurs (une bande) lue dans un GeoTIFF. */
class Raster(
    val width: Int,
    val height: Int,
    private val data: FloatArray,
    /** Longitude du bord gauche, latitude du bord haut, taille d'un pixel (degrés) ; null si non géoréférencé. */
    private val x0: Double?,
    private val y0: Double?,
    private val dx: Double?,
    private val dy: Double?,
    private val noData: Double?,
) {
    val georeferenced: Boolean
        get() = x0 != null && y0 != null && dx != null && dy != null && dx != 0.0 && dy != 0.0

    private fun isValid(v: Float): Boolean = !v.isNaN() && (noData == null || v.toDouble() != noData)

    /** Valeur du pixel qui contient (lat, lon) ; pixel central si le fichier n'est pas géoréférencé ; null hors grille / sans donnée. */
    fun valueAt(lat: Double, lon: Double): Float? {
        val col: Int
        val row: Int
        if (georeferenced) {
            col = floor((lon - x0!!) / dx!!).toInt()
            row = floor((y0!! - lat) / dy!!).toInt()
            if (col !in 0 until width || row !in 0 until height) return null
        } else {
            col = width / 2
            row = height / 2
        }
        val v = data[row * width + col]
        return if (isValid(v)) v else null
    }

    /** Plus petite et plus grande valeur valides de la grille. */
    fun range(): Pair<Float, Float>? {
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        for (v in data) {
            if (!isValid(v)) continue
            if (v < lo) lo = v
            if (v > hi) hi = v
        }
        return if (lo <= hi) lo to hi else null
    }
}

/**
 * Lecteur GeoTIFF minimal pour de petites extractions WCS : une bande, bandes (strips) ou tuiles,
 * sans compression ou Deflate, sans prédicteur. Toute autre variante lève une IllegalArgumentException
 * avec un message qui permet d'identifier ce qu'il faut ajouter.
 */
object GeoTiff {

    private class Entry(val type: Int, val count: Int, val offset: Int)

    private const val MAX_PIXELS = 4_000_000

    fun decode(bytes: ByteArray): Raster {
        require(bytes.size > 16) { "fichier trop court" }
        val order = when {
            bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
            bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
            else -> throw IllegalArgumentException("ce n'est pas un TIFF")
        }
        val bb = ByteBuffer.wrap(bytes).order(order)
        require(bb.getShort(2).toInt() == 42) { "TIFF non géré (BigTIFF ?)" }
        val ifd = bb.getInt(4)
        require(ifd in 8 until bytes.size - 2) { "en-tête TIFF invalide" }

        val n = bb.getShort(ifd).toInt() and 0xFFFF
        val entries = HashMap<Int, Entry>()
        for (i in 0 until n) {
            val p = ifd + 2 + i * 12
            require(p + 12 <= bytes.size) { "répertoire TIFF tronqué" }
            val tag = bb.getShort(p).toInt() and 0xFFFF
            val type = bb.getShort(p + 2).toInt() and 0xFFFF
            val count = bb.getInt(p + 4)
            val size = typeSize(type).toLong() * count.toLong()
            val off = if (size <= 4L) p + 8 else bb.getInt(p + 8)
            require(count >= 0 && off >= 0 && off.toLong() + size <= bytes.size) { "valeur TIFF hors fichier (tag $tag)" }
            entries[tag] = Entry(type, count, off)
        }

        fun values(tag: Int): DoubleArray? {
            val e = entries[tag] ?: return null
            return DoubleArray(e.count) { i ->
                when (e.type) {
                    1, 7 -> (bb.get(e.offset + i).toInt() and 0xFF).toDouble()
                    6 -> bb.get(e.offset + i).toDouble()
                    3 -> (bb.getShort(e.offset + 2 * i).toInt() and 0xFFFF).toDouble()
                    8 -> bb.getShort(e.offset + 2 * i).toDouble()
                    4 -> (bb.getInt(e.offset + 4 * i).toLong() and 0xFFFFFFFFL).toDouble()
                    9 -> bb.getInt(e.offset + 4 * i).toDouble()
                    11 -> bb.getFloat(e.offset + 4 * i).toDouble()
                    12 -> bb.getDouble(e.offset + 8 * i)
                    else -> throw IllegalArgumentException("type TIFF ${e.type} non géré (tag $tag)")
                }
            }
        }
        fun first(tag: Int): Int? = values(tag)?.firstOrNull()?.toInt()

        val width = first(256) ?: throw IllegalArgumentException("largeur absente")
        val height = first(257) ?: throw IllegalArgumentException("hauteur absente")
        require(width > 0 && height > 0 && width.toLong() * height <= MAX_PIXELS) { "taille d'image ${width}x$height non gérée" }

        val bps = first(258) ?: 1
        val spp = first(277) ?: 1
        val compression = first(259) ?: 1
        val format = first(339) ?: 1
        val predictor = first(317) ?: 1
        val planar = first(284) ?: 1
        require(bps == 8 || bps == 16 || bps == 32 || bps == 64) { "$bps bits par échantillon non géré" }
        require(compression == 1 || compression == 8 || compression == 32946) { "compression TIFF $compression non gérée" }
        require(predictor == 1) { "prédicteur TIFF $predictor non géré" }
        require(planar == 1 || spp == 1) { "bandes séparées non gérées" }
        val bytesPerSample = bps / 8
        val pixelBytes = spp * bytesPerSample

        fun sample(buf: ByteBuffer, at: Int): Float = when {
            format == 3 && bps == 32 -> buf.getFloat(at)
            format == 3 && bps == 64 -> buf.getDouble(at).toFloat()
            format == 3 -> throw IllegalArgumentException("flottant $bps bits non géré")
            format == 2 && bps == 8 -> buf.get(at).toFloat()
            format == 2 && bps == 16 -> buf.getShort(at).toFloat()
            format == 2 && bps == 32 -> buf.getInt(at).toFloat()
            bps == 8 -> (buf.get(at).toInt() and 0xFF).toFloat()
            bps == 16 -> (buf.getShort(at).toInt() and 0xFFFF).toFloat()
            bps == 32 -> (buf.getInt(at).toLong() and 0xFFFFFFFFL).toFloat()
            else -> throw IllegalArgumentException("entiers $bps bits non gérés")
        }

        fun segment(offset: Long, length: Long, expected: Int): ByteBuffer {
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "segment TIFF hors fichier" }
            val raw = bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
            val plain = if (compression == 1) raw else inflate(raw, expected)
            require(plain.size >= min(expected, 1)) { "segment vide" }
            return ByteBuffer.wrap(plain).order(order)
        }

        val data = FloatArray(width * height) { Float.NaN }
        val tileW = first(322)
        val tileH = first(323)
        if (tileW != null && tileH != null) {
            val offsets = values(324) ?: throw IllegalArgumentException("tuiles sans positions")
            val counts = values(325) ?: throw IllegalArgumentException("tuiles sans tailles")
            val across = (width + tileW - 1) / tileW
            for (t in offsets.indices) {
                val expected = tileW * tileH * pixelBytes
                val buf = segment(offsets[t].toLong(), counts[t].toLong(), expected)
                val tx = (t % across) * tileW
                val ty = (t / across) * tileH
                for (r in 0 until tileH) for (c in 0 until tileW) {
                    val x = tx + c
                    val y = ty + r
                    val at = (r * tileW + c) * pixelBytes
                    if (x < width && y < height && at + bytesPerSample <= buf.limit()) data[y * width + x] = sample(buf, at)
                }
            }
        } else {
            val offsets = values(273) ?: throw IllegalArgumentException("bandes sans positions")
            val counts = values(279) ?: throw IllegalArgumentException("bandes sans tailles")
            val rps = (first(278) ?: height).coerceIn(1, height)
            for (s in offsets.indices) {
                val rowStart = s * rps
                if (rowStart >= height) break
                val rows = min(rps, height - rowStart)
                val buf = segment(offsets[s].toLong(), counts[s].toLong(), rows * width * pixelBytes)
                for (r in 0 until rows) for (c in 0 until width) {
                    val at = (r * width + c) * pixelBytes
                    if (at + bytesPerSample <= buf.limit()) data[(rowStart + r) * width + c] = sample(buf, at)
                }
            }
        }

        // Géoréférencement : échelle des pixels (33550) + point d'accroche (33922).
        val scale = values(33550)
        val tie = values(33922)
        var x0: Double? = null
        var y0: Double? = null
        var dx: Double? = null
        var dy: Double? = null
        if (scale != null && scale.size >= 2 && tie != null && tie.size >= 6) {
            dx = scale[0]
            dy = scale[1]
            x0 = tie[3] - tie[0] * dx
            y0 = tie[4] + tie[1] * dy
        }

        val noData = entries[42113]?.let { e ->
            String(bytes, e.offset, e.count, Charsets.US_ASCII).trim { it <= ' ' }.toDoubleOrNull()
        }
        return Raster(width, height, data, x0, y0, dx, dy, noData)
    }

    private fun typeSize(type: Int): Int = when (type) {
        1, 2, 6, 7 -> 1
        3, 8 -> 2
        4, 9, 11 -> 4
        5, 10, 12, 16, 17, 18 -> 8
        else -> 1
    }

    private fun inflate(src: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(src)
            val out = ByteArray(expected)
            var total = 0
            while (total < expected && !inflater.finished()) {
                val got = inflater.inflate(out, total, expected - total)
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                total += got
            }
            return out
        } finally {
            inflater.end()
        }
    }
}
