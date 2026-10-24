package com.bookscan.ai

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 보정 옵션 */
data class ScanOptions(
    val autoCrop: Boolean = true,          // 불러온 사진에서 페이지 영역 자동 검출
    val splitSpread: Boolean = false,      // 양면(두 쪽) 펼침을 좌/우 페이지로 분리
    val dewarp: Boolean = true,            // 휨·구부림 보정 (글줄 곡선 펴기)
    val flatten: Boolean = true,           // 구김·그림자·책 가운데 어두운 부분 제거
    val removeColorInk: Boolean = true,    // 컬러 펜·형광펜 필기 제거
    val removeUnderline: Boolean = true,   // 밑줄 제거
    val removeMarginNotes: Boolean = false,// 본문 밖 여백 메모 제거
    val outputMode: Int = MODE_GRAY
) {
    companion object {
        const val MODE_COLOR = 0
        const val MODE_GRAY = 1
        const val MODE_BW = 2
    }
}

/**
 * 책 페이지 보정 엔진 (OpenCV).
 * 모든 컬러 Mat 은 RGB 3채널(CV_8UC3) 기준입니다.
 */
object BookProcessor {

    // ---------------------------------------------------------------- 변환
    fun bitmapToRgb(bmp: Bitmap): Mat {
        val src = if (bmp.config == Bitmap.Config.ARGB_8888) bmp else bmp.copy(Bitmap.Config.ARGB_8888, false)
        val rgba = Mat()
        Utils.bitmapToMat(src, rgba)
        val rgb = Mat()
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB)
        rgba.release()
        return rgb
    }

    fun matToBitmap(m: Mat): Bitmap {
        val rgba = Mat()
        if (m.channels() == 1) Imgproc.cvtColor(m, rgba, Imgproc.COLOR_GRAY2RGBA)
        else Imgproc.cvtColor(m, rgba, Imgproc.COLOR_RGB2RGBA)
        val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bmp)
        rgba.release()
        return bmp
    }

    private fun resizeTo(src: Mat, maxDim: Double): Pair<Mat, Double> {
        val scale = min(1.0, maxDim / max(src.rows(), src.cols()).toDouble())
        val dst = Mat()
        Imgproc.resize(
            src, dst,
            Size((src.cols() * scale).roundToInt().toDouble(), (src.rows() * scale).roundToInt().toDouble()),
            0.0, 0.0, Imgproc.INTER_AREA
        )
        return Pair(dst, scale)
    }

    private fun rect(w: Int, h: Int): Mat =
        Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(w.toDouble(), h.toDouble()))

    // ---------------------------------------------------------------- 1. 페이지 영역 검출 + 원근 보정
    fun autoCropPage(rgb: Mat): Mat {
        val (small, scale) = resizeTo(rgb, 800.0)
        val gray = Mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val edges = Mat()
        Imgproc.Canny(gray, edges, 50.0, 150.0)
        Imgproc.dilate(edges, edges, rect(5, 5))

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

        val minArea = 0.25 * small.rows() * small.cols()
        var best: Array<Point>? = null
        var bestArea = 0.0
        for (c in contours) {
            val c2f = MatOfPoint2f(*c.toArray())
            val peri = Imgproc.arcLength(c2f, true)
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(c2f, approx, 0.02 * peri, true)
            if (approx.total() == 4L) {
                val area = abs(Imgproc.contourArea(approx))
                if (area > minArea && area > bestArea) {
                    bestArea = area
                    best = approx.toArray()
                }
            }
        }
        small.release(); gray.release(); edges.release(); hierarchy.release()
        val quad = best ?: return rgb.clone()

        val pts = quad.map { Point(it.x / scale, it.y / scale) }
        val tl = pts.minBy { it.x + it.y }
        val br = pts.maxBy { it.x + it.y }
        val tr = pts.minBy { it.y - it.x }
        val bl = pts.maxBy { it.y - it.x }
        val w = max(dist(tl, tr), dist(bl, br)).roundToInt().coerceAtLeast(10)
        val h = max(dist(tl, bl), dist(tr, br)).roundToInt().coerceAtLeast(10)

        val src = MatOfPoint2f(tl, tr, br, bl)
        val dst = MatOfPoint2f(
            Point(0.0, 0.0), Point(w - 1.0, 0.0),
            Point(w - 1.0, h - 1.0), Point(0.0, h - 1.0)
        )
        val m = Imgproc.getPerspectiveTransform(src, dst)
        val out = Mat()
        Imgproc.warpPerspective(rgb, out, m, Size(w.toDouble(), h.toDouble()), Imgproc.INTER_CUBIC)
        m.release()
        return out
    }

    private fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    // ---------------------------------------------------------------- 2. 양면 분리 (책 가운데 골 찾기)
    fun splitSpread(rgb: Mat): List<Mat> {
        val w = rgb.cols()
        val h = rgb.rows()
        if (w < h) return listOf(rgb.clone()) // 세로로 긴 이미지는 이미 한 쪽
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val prof = Mat()
        Core.reduce(gray, prof, 0, Core.REDUCE_AVG, CvType.CV_32F)
        val arr = FloatArray(w)
        prof.get(0, 0, arr)
        gray.release(); prof.release()

        val win = max(3, w / 80)
        val a = (w * 0.35).toInt()
        val b = (w * 0.65).toInt()
        var bestX = w / 2
        var bestV = Float.MAX_VALUE
        for (x in a until b) {
            var s = 0f
            var n = 0
            for (k in max(0, x - win)..min(w - 1, x + win)) { s += arr[k]; n++ }
            val v = s / n
            if (v < bestV) { bestV = v; bestX = x }
        }
        val left = Mat(rgb, Rect(0, 0, bestX, h)).clone()
        val right = Mat(rgb, Rect(bestX, 0, w - bestX, h)).clone()
        return listOf(left, right)
    }

    // ---------------------------------------------------------------- 3. 조명 평탄화 (구김·그림자 제거)
    fun flatten(rgb: Mat): Mat {
        val (small, _) = resizeTo(rgb, 800.0)
        val bg = Mat()
        Imgproc.morphologyEx(small, bg, Imgproc.MORPH_CLOSE, rect(15, 15)) // 글자 지우기 → 종이 배경
        Imgproc.medianBlur(bg, bg, 21)
        val bgFull = Mat()
        Imgproc.resize(bg, bgFull, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Core.max(bgFull, Scalar(1.0, 1.0, 1.0), bgFull)
        val out = Mat()
        Core.divide(rgb, bgFull, out, 255.0) // 픽셀 / 배경 → 하얀 종이
        small.release(); bg.release(); bgFull.release()
        return out
    }

    // ---------------------------------------------------------------- 4. 컬러 필기·형광펜 제거
    fun removeColorInk(rgb: Mat) {
        val hsv = Mat()
        Imgproc.cvtColor(rgb, hsv, Imgproc.COLOR_RGB2HSV)
        val mask = Mat()
        Core.inRange(hsv, Scalar(0.0, 70.0, 50.0), Scalar(180.0, 255.0, 255.0), mask)
        Imgproc.dilate(mask, mask, rect(3, 3))
        rgb.setTo(Scalar(255.0, 255.0, 255.0), mask)
        hsv.release(); mask.release()
    }

    private fun textMask(gray: Mat): Mat {
        val bin = Mat()
        var block = max(31, gray.cols() / 40)
        if (block % 2 == 0) block++
        Imgproc.adaptiveThreshold(
            gray, bin, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C,
            Imgproc.THRESH_BINARY_INV, block, 15.0
        )
        return bin
    }

    // ---------------------------------------------------------------- 글줄 검출 (휨 보정·여백 메모 공용)
    private class TextLine(val x: Int, val y: Int, val w: Int, val h: Int, val coef: DoubleArray)

    /** 작은 이미지(sw×sh)에서 글줄을 찾아 각 글줄의 중심선을 3차 다항식으로 근사 */
    private fun findTextLines(smallRgb: Mat): List<TextLine> {
        val sw = smallRgb.cols()
        val sh = smallRgb.rows()
        val gray = Mat()
        Imgproc.cvtColor(smallRgb, gray, Imgproc.COLOR_RGB2GRAY)
        val bin = textMask(gray)
        val lines = Mat()
        Imgproc.morphologyEx(bin, lines, Imgproc.MORPH_CLOSE, rect(max(9, sw / 35), 1)) // 단어 → 줄
        Imgproc.morphologyEx(lines, lines, Imgproc.MORPH_OPEN, rect(3, 1))

        val labels = Mat()
        val stats = Mat()
        val cents = Mat()
        val n = Imgproc.connectedComponentsWithStats(lines, labels, stats, cents, 8, CvType.CV_32S)
        val lab = IntArray(sw * sh)
        labels.get(0, 0, lab)
        val st = IntArray(n * 5)
        stats.get(0, 0, st)
        gray.release(); bin.release(); lines.release(); labels.release(); stats.release(); cents.release()

        val result = ArrayList<TextLine>()
        for (i in 1 until n) {
            val x = st[i * 5 + Imgproc.CC_STAT_LEFT]
            val y = st[i * 5 + Imgproc.CC_STAT_TOP]
            val w = st[i * 5 + Imgproc.CC_STAT_WIDTH]
            val h = st[i * 5 + Imgproc.CC_STAT_HEIGHT]
            if (w < sw * 0.3 || h > sh * 0.06 || h < 3) continue
            val px = ArrayList<Double>()
            val py = ArrayList<Double>()
            val step = max(2, w / 40)
            var cx = x
            while (cx < x + w) {
                var first = -1
                var last = -1
                for (yy in y until y + h) {
                    if (lab[yy * sw + cx] == i) { if (first < 0) first = yy; last = yy }
                }
                if (first >= 0) { px.add(cx.toDouble() / sw); py.add((first + last) / 2.0) }
                cx += step
            }
            if (px.size < 8) continue
            val coef = polyfit(px, py, 3) ?: continue
            // 잘못 합쳐진 줄(대각선 등) 걸러내기
            var err = 0.0
            for (k in px.indices) { val d = polyval(coef, px[k]) - py[k]; err += d * d }
            if (sqrt(err / px.size) > max(2.5, h * 0.35)) continue
            result.add(TextLine(x, y, w, h, coef))
        }
        return result
    }

    // ---------------------------------------------------------------- 5. 휨·구부림 보정
    fun dewarp(rgb: Mat): Mat {
        val (small, scale) = resizeTo(rgb, 1000.0)
        val sw = small.cols()
        val sh = small.rows()
        val tl = findTextLines(small)
        small.release()
        if (tl.size < 3) return rgb.clone()

        val g = 64
        val gx = DoubleArray(g) { it * (sw - 1.0) / (g - 1) }

        // 각 글줄: 목표(직선) y = 줄 가운데에서의 y,  곡선 y(gx)
        data class Curve(val target: Double, val ys: DoubleArray)
        val curves = ArrayList<Curve>()
        for (l in tl) {
            val ys = DoubleArray(g) { polyval(l.coef, gx[it] / sw) }
            val target = polyval(l.coef, (l.x + l.w / 2.0) / sw)
            var maxD = 0.0
            for (v in ys) maxD = max(maxD, abs(v - target))
            if (maxD > sh * 0.15) continue
            curves.add(Curve(target, ys))
        }
        if (curves.size < 3) return rgb.clone()
        curves.sortBy { it.target }
        val tg = DoubleArray(curves.size) { curves[it].target }

        // 세로 변위 맵 (rows R × cols G)
        val r = 96
        val gy = DoubleArray(r) { it * (sh - 1.0) / (r - 1) }
        val mapY = Array(r) { DoubleArray(g) }
        for (j in 0 until g) {
            val disp = DoubleArray(curves.size) { curves[it].ys[j] - tg[it] }
            for (i in 0 until r) mapY[i][j] = gy[i] + interp(gy[i], tg, disp)
        }

        // 가로 방향: 곡면 길이(호 길이)만큼 펴기 → 책 안쪽 눌린 글자 복원
        val mean = DoubleArray(g) { j -> curves.sumOf { it.ys[j] } / curves.size }
        val seg = DoubleArray(g) { j ->
            val a = max(0, j - 1)
            val b = min(g - 1, j + 1)
            val slope = (mean[b] - mean[a]) / (gx[b] - gx[a])
            sqrt(1 + slope * slope)
        }
        val s = DoubleArray(g)
        for (j in 1 until g) s[j] = s[j - 1] + (seg[j] + seg[j - 1]) / 2 * (gx[j] - gx[j - 1])
        val total = s[g - 1]
        for (j in 0 until g) s[j] = s[j] / total * (sw - 1)
        val srcX = DoubleArray(g) { interp(gx[it], s, gx) }

        val fx = FloatArray(r * g)
        val fy = FloatArray(r * g)
        for (i in 0 until r) {
            for (j in 0 until g) {
                fx[i * g + j] = (srcX[j] / scale).toFloat()
                fy[i * g + j] = (interp(srcX[j], gx, mapY[i]) / scale).toFloat()
            }
        }
        val mxC = Mat(r, g, CvType.CV_32F); mxC.put(0, 0, fx)
        val myC = Mat(r, g, CvType.CV_32F); myC.put(0, 0, fy)
        val mx = Mat(); val my = Mat()
        Imgproc.resize(mxC, mx, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Imgproc.resize(myC, my, rgb.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val out = Mat()
        Imgproc.remap(rgb, out, mx, my, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar(255.0, 255.0, 255.0))
        mxC.release(); myC.release(); mx.release(); my.release()
        return out
    }

    // ---------------------------------------------------------------- 6. 밑줄 제거
    fun removeUnderlines(rgb: Mat) {
        val gray = Mat()
        Imgproc.cvtColor(rgb, gray, Imgproc.COLOR_RGB2GRAY)
        val bin = textMask(gray)
        val hl = Mat()
        Imgproc.morphologyEx(bin, hl, Imgproc.MORPH_OPEN, rect(max(20, rgb.cols() / 12), 1))
        Imgproc.dilate(hl, hl, rect(5, 3))
        rgb.setTo(Scalar(255.0, 255.0, 255.0), hl)
        gray.release(); bin.release(); hl.release()
    }

    // ---------------------------------------------------------------- 7. 여백 메모 제거 (본문 영역 밖 지우기)
    fun removeMarginNotes(rgb: Mat): Mat {
        val (small, scale) = resizeTo(rgb, 1000.0)
        val lines = findTextLines(small)
        val sw = small.cols(); val sh = small.rows()
        small.release()
        if (lines.size < 3) return rgb.clone()
        var x0 = sw; var y0 = sh; var x1 = 0; var y1 = 0
        for (l in lines) {
            x0 = min(x0, l.x); y0 = min(y0, l.y)
            x1 = max(x1, l.x + l.w); y1 = max(y1, l.y + l.h)
        }
        val pad = (max(sw, sh) * 0.02).roundToInt()
        val rx0 = ((x0 - pad).coerceAtLeast(0) / scale).roundToInt()
        val ry0 = ((y0 - pad).coerceAtLeast(0) / scale).roundToInt()
        val rx1 = ((x1 + pad).coerceAtMost(sw) / scale).roundToInt().coerceAtMost(rgb.cols())
        val ry1 = ((y1 + pad).coerceAtMost(sh) / scale).roundToInt().coerceAtMost(rgb.rows())
        if (rx1 - rx0 < 10 || ry1 - ry0 < 10) return rgb.clone()
        val body = Rect(rx0, ry0, rx1 - rx0, ry1 - ry0)
        val out = Mat(rgb.size(), rgb.type(), Scalar(255.0, 255.0, 255.0))
        rgb.submat(body).copyTo(out.submat(body))
        return out
    }

    // ---------------------------------------------------------------- 전체 파이프라인
    /** 가져오기 단계: 페이지 검출(옵션) */
    fun prepareImported(rgb: Mat, opt: ScanOptions): Mat =
        if (opt.autoCrop) autoCropPage(rgb) else rgb.clone()

    /** 한 페이지 보정. 입력 Mat 은 변경하지 않습니다. */
    fun processPage(input: Mat, opt: ScanOptions): Mat {
        var cur = input.clone()
        fun swap(next: Mat) { if (next !== cur) { cur.release(); cur = next } }

        if (opt.flatten) swap(flatten(cur))
        if (opt.removeColorInk) removeColorInk(cur)
        if (opt.dewarp) swap(dewarp(cur))
        if (opt.removeUnderline) removeUnderlines(cur)
        if (opt.removeMarginNotes) swap(removeMarginNotes(cur))

        when (opt.outputMode) {
            ScanOptions.MODE_GRAY -> {
                val g = Mat(); Imgproc.cvtColor(cur, g, Imgproc.COLOR_RGB2GRAY); swap(g)
            }
            ScanOptions.MODE_BW -> {
                val g = Mat(); Imgproc.cvtColor(cur, g, Imgproc.COLOR_RGB2GRAY)
                val bw = Mat()
                if (opt.flatten) {
                    Imgproc.threshold(g, bw, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
                } else {
                    Imgproc.adaptiveThreshold(
                        g, bw, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                        Imgproc.THRESH_BINARY, 41, 15.0
                    )
                }
                g.release(); swap(bw)
            }
        }
        return cur
    }

    // ---------------------------------------------------------------- 수학 도우미
    /** 최소제곱 다항식 근사 (계수: c0 + c1 x + c2 x² + ...) */
    private fun polyfit(xs: List<Double>, ys: List<Double>, deg: Int): DoubleArray? {
        val m = deg + 1
        val a = Array(m) { DoubleArray(m + 1) }
        for (k in xs.indices) {
            val p = DoubleArray(2 * deg + 1)
            p[0] = 1.0
            for (t in 1 until p.size) p[t] = p[t - 1] * xs[k]
            for (i in 0 until m) {
                for (j in 0 until m) a[i][j] += p[i + j]
                a[i][m] += p[i] * ys[k]
            }
        }
        // 가우스 소거
        for (c in 0 until m) {
            var piv = c
            for (rr in c + 1 until m) if (abs(a[rr][c]) > abs(a[piv][c])) piv = rr
            if (abs(a[piv][c]) < 1e-12) return null
            val tmp = a[c]; a[c] = a[piv]; a[piv] = tmp
            for (rr in 0 until m) {
                if (rr == c) continue
                val f = a[rr][c] / a[c][c]
                for (cc in c..m) a[rr][cc] -= f * a[c][cc]
            }
        }
        return DoubleArray(m) { a[it][m] / a[it][it] }
    }

    private fun polyval(c: DoubleArray, x: Double): Double {
        var v = 0.0
        for (i in c.indices.reversed()) v = v * x + c[i]
        return v
    }

    /** numpy.interp 와 동일 (xs 오름차순, 범위 밖은 끝값 유지) */
    private fun interp(x: Double, xs: DoubleArray, ys: DoubleArray): Double {
        val n = xs.size
        if (x <= xs[0]) return ys[0]
        if (x >= xs[n - 1]) return ys[n - 1]
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xs[mid] <= x) lo = mid else hi = mid
        }
        val dx = xs[hi] - xs[lo]
        if (dx <= 1e-9) return ys[lo]
        return ys[lo] + (ys[hi] - ys[lo]) * (x - xs[lo]) / dx
    }
}
