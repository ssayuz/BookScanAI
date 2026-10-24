package com.bookscan.ai

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import org.opencv.core.Mat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var store: PageStore
    private lateinit var adapter: PageAdapter
    private lateinit var list: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var txtStatus: TextView
    private lateinit var txtCount: TextView
    private lateinit var txtEmpty: TextView
    private lateinit var buttons: List<Button>
    private var busy = false
    private var openCvReady = false

    // ML Kit AI 문서 스캐너 결과
    private val scanLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            if (res.resultCode == RESULT_OK) {
                val result = GmsDocumentScanningResult.fromActivityResultIntent(res.data)
                val uris = result?.pages?.map { it.imageUri } ?: emptyList()
                if (uris.isNotEmpty()) importUris(uris, fromScanner = true)
            }
        }

    // 갤러리 사진 여러 장 선택
    private val pickLauncher =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
            if (uris.isNotEmpty()) importUris(uris, fromScanner = false)
        }

    // PDF 저장 위치 선택
    private val pdfLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) exportPdf(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        openCvReady = try { OpenCVLoader.initLocal() } catch (e: Throwable) { false }
        if (!openCvReady) toast("OpenCV 초기화 실패 – 보정 기능을 사용할 수 없습니다")

        store = PageStore(this)
        list = findViewById(R.id.list)
        progress = findViewById(R.id.progress)
        txtStatus = findViewById(R.id.txtStatus)
        txtCount = findViewById(R.id.txtCount)
        txtEmpty = findViewById(R.id.txtEmpty)

        adapter = PageAdapter(store.pages) { showPreview(it) }
        list.layoutManager = GridLayoutManager(this, 3)
        list.adapter = adapter
        setupDragReorder()

        val btnScan = findViewById<Button>(R.id.btnScan)
        val btnImport = findViewById<Button>(R.id.btnImport)
        val btnProcess = findViewById<Button>(R.id.btnProcess)
        val btnPdf = findViewById<Button>(R.id.btnPdf)
        val btnSettings = findViewById<Button>(R.id.btnSettings)
        val btnClear = findViewById<Button>(R.id.btnClear)
        buttons = listOf(btnScan, btnImport, btnProcess, btnPdf, btnSettings, btnClear)

        btnScan.setOnClickListener { startScanner() }
        btnImport.setOnClickListener {
            pickLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        btnProcess.setOnClickListener { runProcess() }
        btnPdf.setOnClickListener {
            if (store.pages.isEmpty()) { toast("저장할 페이지가 없습니다"); return@setOnClickListener }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.KOREA).format(Date())
            pdfLauncher.launch("책스캔_$stamp.pdf")
        }
        btnSettings.setOnClickListener { showSettings() }
        btnClear.setOnClickListener {
            if (store.pages.isEmpty()) return@setOnClickListener
            MaterialAlertDialogBuilder(this)
                .setTitle("전체 삭제")
                .setMessage("모든 페이지(${store.pages.size}장)를 지울까요?")
                .setPositiveButton("삭제") { _, _ -> store.clear(); refresh() }
                .setNegativeButton("취소", null)
                .show()
        }
        refresh()
    }

    // ------------------------------------------------------------ 스캔 / 불러오기
    private fun startScanner() {
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setPageLimit(100)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_JPEG)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(options).getStartScanIntent(this)
            .addOnSuccessListener { sender ->
                scanLauncher.launch(IntentSenderRequest.Builder(sender).build())
            }
            .addOnFailureListener { e ->
                toast("스캐너를 열 수 없습니다 (Google Play 서비스 필요): ${e.message}\n'사진 불러오기'를 이용하세요")
            }
    }

    private fun importUris(uris: List<Uri>, fromScanner: Boolean) {
        val opt = loadOptions()
        lifecycleScope.launch {
            setBusy(true, "불러오는 중…", uris.size)
            val added = withContext(Dispatchers.Default) {
                val out = ArrayList<Page>()
                uris.forEachIndexed { i, uri ->
                    try {
                        val bmp = ImageIO.loadUri(this@MainActivity, uri, 3000)
                        if (bmp != null) {
                            val id = store.newId()
                            val f = store.file("src_$id.jpg")
                            if (!fromScanner && opt.autoCrop && openCvReady) {
                                val rgb = BookProcessor.bitmapToRgb(bmp)
                                val cropped = BookProcessor.prepareImported(rgb, opt)
                                ImageIO.saveJpeg(BookProcessor.matToBitmap(cropped), f)
                                rgb.release(); cropped.release()
                            } else {
                                ImageIO.saveJpeg(bmp, f)
                            }
                            val t = store.file("thumb_$id.jpg")
                            ImageIO.makeThumb(f.path, t)
                            out.add(Page(id, f.path, null, t.path, false))
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "import failed", e)
                    }
                    withContext(Dispatchers.Main) { progress.progress = i + 1 }
                }
                out
            }
            store.pages.addAll(added)
            store.save()
            refresh()
            setBusy(false, "${added.size}장 추가됨. ✨ AI 보정 실행을 누르세요.")
        }
    }

    // ------------------------------------------------------------ AI 보정
    private fun runProcess() {
        if (store.pages.isEmpty()) { toast("먼저 페이지를 추가하세요"); return }
        if (!openCvReady) { toast("OpenCV를 사용할 수 없습니다"); return }
        val opt = loadOptions()
        val snapshot = ArrayList(store.pages)
        lifecycleScope.launch {
            setBusy(true, "AI 보정 중… (휨 펴기 · 그림자/구김 제거 · 필기 제거)", snapshot.size)
            var failed = 0
            val result = withContext(Dispatchers.Default) {
                val out = ArrayList<Page>()
                snapshot.forEachIndexed { i, p ->
                    try {
                        out.addAll(processOne(p, opt))
                    } catch (e: Throwable) {
                        Log.e(TAG, "process failed", e)
                        failed++
                        out.add(p)
                    }
                    withContext(Dispatchers.Main) {
                        progress.progress = i + 1
                        txtStatus.text = "AI 보정 중… ${i + 1} / ${snapshot.size}"
                    }
                }
                out
            }
            store.pages.clear()
            store.pages.addAll(result)
            store.save()
            refresh()
            val msg = if (failed == 0) "보정 완료! 📄 PDF 저장을 누르세요." else "보정 완료 (실패 ${failed}장)"
            setBusy(false, msg)
        }
    }

    /** 한 장 처리. 양면 분리 시 2장을 돌려줍니다. */
    private fun processOne(p: Page, opt: ScanOptions): List<Page> {
        val bmp = ImageIO.loadFile(p.src, 3000) ?: return listOf(p)
        val rgb = BookProcessor.bitmapToRgb(bmp)

        val work = ArrayList<Pair<Page, Mat>>()
        if (opt.splitSpread && !p.half) {
            val halves = BookProcessor.splitSpread(rgb)
            if (halves.size == 2) {
                for (m in halves) {
                    val id = store.newId()
                    val f = store.file("src_$id.jpg")
                    ImageIO.saveJpeg(BookProcessor.matToBitmap(m), f)
                    work.add(Page(id, f.path, null, store.file("thumb_$id.jpg").path, true) to m)
                }
                store.deleteFiles(p)
            } else {
                work.add(p to halves[0])
            }
        } else {
            work.add(p to rgb.clone())
        }
        rgb.release()

        val out = ArrayList<Page>()
        for ((page, m) in work) {
            val res = BookProcessor.processPage(m, opt)
            m.release()
            val pf = store.file("proc_${page.id}.jpg")
            ImageIO.saveJpeg(BookProcessor.matToBitmap(res), pf, 90)
            res.release()
            page.proc = pf.path
            ImageIO.makeThumb(pf.path, File(page.thumb))
            out.add(page)
        }
        return out
    }

    // ------------------------------------------------------------ PDF
    private fun exportPdf(uri: Uri) {
        val pages = ArrayList(store.pages)
        lifecycleScope.launch {
            setBusy(true, "PDF 만드는 중…", pages.size)
            val ok = withContext(Dispatchers.IO) {
                try {
                    PdfExporter.export(this@MainActivity, pages, uri, 2400) { n ->
                        progress.post { progress.progress = n }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "pdf failed", e)
                    false
                }
            }
            setBusy(false, if (ok) "PDF 저장 완료 (${pages.size}쪽) ✅" else "PDF 저장 실패")
            toast(if (ok) "PDF 저장 완료" else "PDF 저장 실패")
        }
    }

    // ------------------------------------------------------------ 미리보기 / 설정
    private fun showPreview(pos: Int) {
        if (busy || pos !in store.pages.indices) return
        val p = store.pages[pos]
        val v = layoutInflater.inflate(R.layout.dialog_preview, null)
        val img = v.findViewById<ImageView>(R.id.imgPreview)
        val btnOrig = v.findViewById<Button>(R.id.btnOrig)
        val btnProc = v.findViewById<Button>(R.id.btnProc)
        fun show(path: String?) { if (path != null) img.setImageBitmap(ImageIO.loadFile(path, 1800)) }
        btnOrig.setOnClickListener { show(p.src) }
        btnProc.setOnClickListener { if (p.proc != null) show(p.proc) else toast("아직 보정 전입니다") }
        show(p.proc ?: p.src)

        MaterialAlertDialogBuilder(this)
            .setTitle("${pos + 1} 페이지")
            .setView(v)
            .setPositiveButton("닫기", null)
            .setNegativeButton("이 페이지 삭제") { _, _ ->
                store.deleteFiles(p)
                store.pages.remove(p)
                store.save()
                refresh()
            }
            .show()
    }

    private fun showSettings() {
        val o = loadOptions()
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val cbDewarp = v.findViewById<CheckBox>(R.id.cbDewarp).apply { isChecked = o.dewarp }
        val cbFlatten = v.findViewById<CheckBox>(R.id.cbFlatten).apply { isChecked = o.flatten }
        val cbInk = v.findViewById<CheckBox>(R.id.cbInk).apply { isChecked = o.removeColorInk }
        val cbUnder = v.findViewById<CheckBox>(R.id.cbUnderline).apply { isChecked = o.removeUnderline }
        val cbMargin = v.findViewById<CheckBox>(R.id.cbMargin).apply { isChecked = o.removeMarginNotes }
        val cbSplit = v.findViewById<CheckBox>(R.id.cbSplit).apply { isChecked = o.splitSpread }
        val cbCrop = v.findViewById<CheckBox>(R.id.cbCrop).apply { isChecked = o.autoCrop }
        val rg = v.findViewById<RadioGroup>(R.id.rgMode)
        rg.check(
            when (o.outputMode) {
                ScanOptions.MODE_COLOR -> R.id.rbColor
                ScanOptions.MODE_BW -> R.id.rbBw
                else -> R.id.rbGray
            }
        )
        MaterialAlertDialogBuilder(this)
            .setTitle("보정 설정")
            .setView(v)
            .setPositiveButton("저장") { _, _ ->
                val mode = when (rg.checkedRadioButtonId) {
                    R.id.rbColor -> ScanOptions.MODE_COLOR
                    R.id.rbBw -> ScanOptions.MODE_BW
                    else -> ScanOptions.MODE_GRAY
                }
                saveOptions(
                    ScanOptions(
                        autoCrop = cbCrop.isChecked,
                        splitSpread = cbSplit.isChecked,
                        dewarp = cbDewarp.isChecked,
                        flatten = cbFlatten.isChecked,
                        removeColorInk = cbInk.isChecked,
                        removeUnderline = cbUnder.isChecked,
                        removeMarginNotes = cbMargin.isChecked,
                        outputMode = mode
                    )
                )
                toast("설정 저장됨 – ✨ AI 보정 실행을 다시 누르면 적용됩니다")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun loadOptions(): ScanOptions {
        val sp = getSharedPreferences("opts", MODE_PRIVATE)
        val d = ScanOptions()
        return ScanOptions(
            autoCrop = sp.getBoolean("autoCrop", d.autoCrop),
            splitSpread = sp.getBoolean("split", d.splitSpread),
            dewarp = sp.getBoolean("dewarp", d.dewarp),
            flatten = sp.getBoolean("flatten", d.flatten),
            removeColorInk = sp.getBoolean("ink", d.removeColorInk),
            removeUnderline = sp.getBoolean("underline", d.removeUnderline),
            removeMarginNotes = sp.getBoolean("margin", d.removeMarginNotes),
            outputMode = sp.getInt("mode", d.outputMode)
        )
    }

    private fun saveOptions(o: ScanOptions) {
        getSharedPreferences("opts", MODE_PRIVATE).edit()
            .putBoolean("autoCrop", o.autoCrop)
            .putBoolean("split", o.splitSpread)
            .putBoolean("dewarp", o.dewarp)
            .putBoolean("flatten", o.flatten)
            .putBoolean("ink", o.removeColorInk)
            .putBoolean("underline", o.removeUnderline)
            .putBoolean("margin", o.removeMarginNotes)
            .putInt("mode", o.outputMode)
            .apply()
    }

    // ------------------------------------------------------------ UI 도우미
    private fun setupDragReorder() {
        val cb = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0
        ) {
            override fun onMove(
                rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
            ): Boolean {
                val from = vh.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from < 0 || to < 0) return false
                Collections.swap(store.pages, from, to)
                adapter.notifyItemMoved(from, to)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled() = !busy

            @SuppressLint("NotifyDataSetChanged")
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                store.save()
                rv.post { adapter.notifyDataSetChanged() } // 쪽 번호 갱신
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(list)
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refresh() {
        adapter.notifyDataSetChanged()
        val n = store.pages.size
        val done = store.pages.count { it.proc != null }
        txtCount.text = "${n} 페이지 · 보정 ${done}"
        txtEmpty.visibility = if (n == 0) View.VISIBLE else View.GONE
    }

    private fun setBusy(b: Boolean, msg: String, max: Int = 0) {
        busy = b
        buttons.forEach { it.isEnabled = !b }
        progress.visibility = if (b) View.VISIBLE else View.GONE
        if (b) { progress.max = max.coerceAtLeast(1); progress.progress = 0 }
        txtStatus.text = msg
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object { private const val TAG = "BookScan" }
}
