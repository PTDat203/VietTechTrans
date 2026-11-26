package vn.viettechtrans.app.ocr

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Dịch trên ảnh: draws the translation of each block over the picture, where the original text was, like the photo
 * mode of a camera translator. For each block: cover the original letters with the colour of their background, then
 * write the translation in the block's box (turned with the block when the photo is tilted), in a colour read from
 * the original letters, about as large as they were.
 */
object ImageOverlay {
    /** Without line boxes: font size per line pitch of the block. */
    private const val SIZE_PER_PITCH = 0.75f

    /** A longer translation may shrink down to this share of the original letter size, then the box grows down. */
    private const val MIN_SHRINK = 0.55f

    /** At most this many pixels are read per block to pick its colours. */
    private const val MAX_SAMPLES = 6_000

    /** A pixel this much darker or lighter than the background is ink (letters the recognizer's box left out). */
    private const val INK_DIFF = 60

    fun render(page: Bitmap, blocks: List<PlacedBlock>, texts: List<String>): Bitmap {
        val out = page.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        // Everything is measured on the clean picture, before any block is painted over (boxes may overlap).
        val plans = blocks.indices.mapNotNull { i ->
            if (i < texts.size && texts[i].isNotBlank()) plan(page, blocks[i], texts[i]) else null
        }
        for (p in plans) draw(canvas, p)
        return out
    }

    /** One block to draw, in its own frame: origin at its top-left corner, x along its text, y down its lines. */
    private class Plan(
        val origin: Pt,
        val angle: Float,
        val width: Float,
        val height: Float,
        /** The area to cover, beyond the box on each side, where ink of the original letters was found. */
        val coverLeft: Float,
        val coverTop: Float,
        val coverRight: Float,
        val coverBottom: Float,
        val background: Int,
        val layout: StaticLayout,
        /** Where the text starts: at the first ink left of the box (the box may begin inside the first letter). */
        val textLeft: Float,
        val textTop: Float,
        val feather: Float,
    )

    private fun plan(page: Bitmap, block: PlacedBlock, text: String): Plan? {
        val c = block.corners
        if (c.size != 4) return null
        val (p0, p1, _, p3) = c
        val width = hypot(p1.x - p0.x, p1.y - p0.y)
        val height = hypot(p3.x - p0.x, p3.y - p0.y)
        if (width < 4f || height < 4f) return null
        val ux = (p1.x - p0.x) / width
        val uy = (p1.y - p0.y) / width
        val vx = (p3.x - p0.x) / height
        val vy = (p3.y - p0.y) / height
        val lines = max(1, block.lineCount)
        val pitch = height / lines
        val boxes = block.lineHeights.filter { it > 0f }.sorted()
        val lineBox = if (boxes.isEmpty()) pitch else min(boxes[boxes.size / 2], pitch)

        val (background, ink) = sampleColors(page, c)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            typeface = Typeface.DEFAULT
        }
        // The recognizer's box is tight and may leave out a final full stop or the edge of a letter: grow the cover
        // while a strip just outside still has ink (a gap wider than a thin space stops it).
        val bgLuma = OverlayColors.luma(background)
        fun inkAt(x: Float, y: Float): Boolean {
            val px = (p0.x + ux * x + vx * y).roundToInt()
            val py = (p0.y + uy * x + vy * y).roundToInt()
            if (px < 0 || py < 0 || px >= page.width || py >= page.height) return false
            return abs(OverlayColors.luma(page.getPixel(px, py)) - bgLuma) >= INK_DIFF
        }
        fun grow(limit: Float, gap: Float, strip: (Float) -> Boolean): Float {
            var reach = 0f
            var clean = 0f
            var d = 1f
            while (d <= limit) {
                if (strip(d)) {
                    reach = d
                    clean = 0f
                } else {
                    clean += 1f
                    if (clean > gap) break
                }
                d += 1f
            }
            return reach
        }
        val step = max(1f, lineBox / 12f)
        fun column(x: Float): Boolean {
            var y = -lineBox * 0.3f
            while (y <= height + lineBox * 0.3f) {
                if (inkAt(x, y)) return true
                y += step
            }
            return false
        }
        fun row(y: Float): Boolean {
            var x = 0f
            while (x <= width) {
                if (inkAt(x, y)) return true
                x += step
            }
            return false
        }
        // Along the line a final full stop sits a little apart from its word; across lines the gap is kept small.
        val left = grow(lineBox * 1.2f, max(2f, lineBox * 0.3f)) { column(-it) }
        val right = grow(lineBox * 1.2f, max(2f, lineBox * 0.3f)) { column(width + it) }
        val top = grow(lineBox * 0.6f, max(2f, lineBox * 0.12f)) { row(-it) }
        val bottom = grow(lineBox * 0.45f, max(2f, lineBox * 0.12f)) { row(height + it) }
        val margin = max(2f, lineBox * 0.08f)

        // A translation longer than the original may run on into plain background on the right (up to 60 % more)
        // before its letters get smaller, like camera translators do.
        fun plain(x: Float): Boolean {
            var y = 0f
            while (y <= height) {
                val px = (p0.x + ux * x + vx * y).roundToInt()
                val py = (p0.y + uy * x + vy * y).roundToInt()
                if (px < 0 || py < 0 || px >= page.width || py >= page.height) return false
                if (abs(OverlayColors.luma(page.getPixel(px, py)) - bgLuma) >= 25) return false
                y += step
            }
            return true
        }
        var room = 0f
        while (room < width * 0.6f && plain(width + right + margin + room + 1f)) room += 1f
        // Keep a margin from whatever ends the plain background (a frame, the edge of a page).
        room = max(0f, room - lineBox * 0.5f)

        val textWidth = (left + width + right + room).roundToInt()
        // As many lines as the original, at the size of the original letters; smaller only if it does not fit.
        val start = letterSize(block) ?: (pitch * SIZE_PER_PITCH)
        var size = start
        var layout = layout(text, paint, size, textWidth, pitch, lines)
        while (layout.lineCount > lines && size > start * MIN_SHRINK) {
            size *= 0.92f
            layout = layout(text, paint, size, textWidth, pitch, lines)
        }
        return Plan(
            origin = p0,
            angle = Math.toDegrees(atan2(uy, ux).toDouble()).toFloat(),
            width = width,
            height = height,
            coverLeft = left + margin,
            coverTop = top + margin,
            coverRight = right + margin,
            coverBottom = bottom + margin,
            background = background,
            layout = layout,
            textLeft = -left,
            // Centred on the original lines (a line box is only as high as its letters, the layout has room for any).
            textTop = (height - layout.height) / 2f,
            feather = max(2f, lineBox * 0.15f),
        )
    }

    private fun draw(canvas: Canvas, p: Plan) {
        canvas.save()
        canvas.rotate(p.angle, p.origin.x, p.origin.y)
        canvas.translate(p.origin.x, p.origin.y)
        // The original letters (with the ink found around the box), and the new text wherever it runs, are covered.
        val l = -p.coverLeft
        val t = min(-p.coverTop, p.textTop)
        val r = max(p.width + p.coverRight, p.textLeft + lineWidth(p.layout) + p.feather)
        val b = max(p.height + p.coverBottom, p.textTop + p.layout.height)
        // A soft edge around a solid cover: the patch fades into the picture instead of showing a hard rectangle.
        val soft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = p.background
            maskFilter = BlurMaskFilter(p.feather, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRect(l - p.feather / 2, t - p.feather / 2, r + p.feather / 2, b + p.feather / 2, soft)
        val solid = Paint().apply { color = p.background }
        canvas.drawRect(l, t, r, b, solid)
        canvas.translate(p.textLeft, p.textTop)
        p.layout.draw(canvas)
        canvas.restore()
    }

    /** Width of the longest drawn line (the layout itself is as wide as the room it was given). */
    private fun lineWidth(layout: StaticLayout): Float = (0 until layout.lineCount).maxOfOrNull { layout.getLineRight(it) } ?: 0f

    /**
     * The size of the original letters: each line box is as high as the ink of its text, so the same text measured in
     * the drawing font at 100 px gives the scale (a line without descenders or accents has a lower box, and this
     * accounts for it). Median over the lines; null without line boxes.
     */
    private fun letterSize(block: PlacedBlock): Float? {
        val probe = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT
            textSize = 100f
        }
        val bounds = Rect()
        val sizes = block.lines.zip(block.lineHeights).mapNotNull { (line, h) ->
            if (h <= 0f || line.isBlank()) return@mapNotNull null
            probe.getTextBounds(line, 0, line.length, bounds)
            if (bounds.height() <= 0) null else 100f * h / bounds.height()
        }.sorted()
        return sizes.getOrNull(sizes.size / 2)
    }

    /** Lines of a block as far apart as in the original ([pitch]), when it had several. */
    private fun layout(text: String, paint: TextPaint, size: Float, width: Int, pitch: Float, lines: Int): StaticLayout {
        paint.textSize = size
        val natural = paint.fontMetrics.let { it.descent - it.ascent }
        val spacing = if (lines > 1 && natural > 0f) (pitch / natural).coerceIn(1f, 2f) else 1f
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, max(1, width))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, spacing)
            .build()
    }

    /** Background and text colour of the block, from the pixels of its bounding box (see [OverlayColors.pick]). */
    private fun sampleColors(page: Bitmap, corners: List<Pt>): Pair<Int, Int> {
        val left = floor(corners.minOf { it.x }).toInt().coerceIn(0, page.width - 1)
        val top = floor(corners.minOf { it.y }).toInt().coerceIn(0, page.height - 1)
        val right = ceil(corners.maxOf { it.x }).toInt().coerceIn(left + 1, page.width)
        val bottom = ceil(corners.maxOf { it.y }).toInt().coerceIn(top + 1, page.height)
        val w = right - left
        val h = bottom - top
        val all = IntArray(w * h)
        page.getPixels(all, 0, w, left, top, w, h)
        val step = max(1, ceil(all.size / MAX_SAMPLES.toDouble()).toInt())
        val samples = IntArray((all.size + step - 1) / step) { all[it * step] }
        return OverlayColors.pick(samples)
    }
}
