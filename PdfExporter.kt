package com.bookscan.ai

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import kotlin.math.roundToInt

object PdfExporter {
    /**
     * 페이지 이미지들을 하나의 PDF로 저장.
     * 페이지 폭은 A4 폭(595pt) 기준, 높이는 이미지 비율에 맞춤. 이미지 해상도는 그대로 유지됩니다.
     */
    fun export(ctx: Context, pages: List<Page>, out: Uri, maxDim: Int, onProgress: (Int) -> Unit): Boolean {
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        try {
            pages.forEachIndexed { i, p ->
                val bmp = ImageIO.loadFile(p.proc ?: p.src, maxDim) ?: return@forEachIndexed
                val pw = 595
                val ph = (595f * bmp.height / bmp.width).roundToInt()
                val info = PdfDocument.PageInfo.Builder(pw, ph, i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(bmp, null, Rect(0, 0, pw, ph), paint)
                doc.finishPage(page)
                onProgress(i + 1)
            }
            val os = ctx.contentResolver.openOutputStream(out) ?: return false
            os.use { doc.writeTo(it) }
            return true
        } finally {
            doc.close()
        }
    }
}
