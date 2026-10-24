package com.bookscan.ai

import android.content.Context
import java.io.File
import java.util.UUID

/** 한 페이지: 원본(src), 보정본(proc), 썸네일. half = 양면 분리로 생긴 페이지 */
data class Page(
    val id: String,
    var src: String,
    var proc: String?,
    var thumb: String,
    var half: Boolean
)

/** 페이지 목록을 앱 내부 저장소에 보관 (앱을 닫아도 유지) */
class PageStore(private val ctx: Context) {
    val dir: File = File(ctx.filesDir, "pages").apply { mkdirs() }
    private val index = File(ctx.filesDir, "pages.tsv")
    val pages = ArrayList<Page>()

    init { load() }

    fun newId(): String = UUID.randomUUID().toString().substring(0, 12)
    fun file(name: String) = File(dir, name)

    private fun load() {
        pages.clear()
        if (!index.exists()) return
        index.readLines().forEach { line ->
            val p = line.split('\t')
            if (p.size >= 5 && File(p[1]).exists()) {
                pages.add(Page(p[0], p[1], p[2].ifEmpty { null }?.takeIf { File(it).exists() }, p[3], p[4] == "1"))
            }
        }
    }

    @Synchronized
    fun save() {
        index.writeText(pages.joinToString("\n") {
            listOf(it.id, it.src, it.proc ?: "", it.thumb, if (it.half) "1" else "0").joinToString("\t")
        })
    }

    fun deleteFiles(p: Page) {
        File(p.src).delete()
        p.proc?.let { File(it).delete() }
        File(p.thumb).delete()
    }

    fun clear() {
        pages.forEach { deleteFiles(it) }
        pages.clear()
        save()
    }
}
