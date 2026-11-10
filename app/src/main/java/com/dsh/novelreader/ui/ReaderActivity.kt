package com.dsh.novelreader.ui

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dsh.novelreader.BuildConfig
import com.dsh.novelreader.data.Book
import com.dsh.novelreader.data.BookStore
import com.dsh.novelreader.data.BookType
import com.dsh.novelreader.data.LibraryStore
import com.dsh.novelreader.data.PageImageBlock
import com.dsh.novelreader.data.PageTurnMode
import com.dsh.novelreader.data.ReaderSettings
import com.dsh.novelreader.data.ReaderTheme
import com.dsh.novelreader.data.Section
import com.dsh.novelreader.data.SectionState
import com.dsh.novelreader.data.TextBlock
import com.dsh.novelreader.data.TocItem
import com.dsh.novelreader.data.notes.AnnotationEntity
import com.dsh.novelreader.data.notes.BookmarkEntity
import com.dsh.novelreader.data.notes.NotesRepository
import com.dsh.novelreader.data.notes.PageNoteEntity
import com.dsh.novelreader.ocr.OcrQueue
import com.dsh.novelreader.pdf.Importer
import com.dsh.novelreader.pdf.PageImageCache
import com.dsh.novelreader.pdf.PdfOutline
import com.dsh.novelreader.pdf.PdfPageRenderer
import com.dsh.novelreader.reader.DisplayPage
import com.dsh.novelreader.reader.PageBuilder
import com.dsh.novelreader.reader.ReaderView
import com.dsh.novelreader.util.TextUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reading screen: styled text pages, image pages, OCR progress, bookmarks,
 * annotations and page notes. Positions are always anchored to
 * (original page, block id, character offset) so re-flow never loses them.
 */
class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        private const val ZONE_LEFT = 0.33f
        private const val ZONE_RIGHT = 0.67f
        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }

    private lateinit var library: LibraryStore
    private lateinit var store: BookStore
    private lateinit var settings: ReaderSettings
    private lateinit var book: Book
    private lateinit var imageCache: PageImageCache
    private lateinit var notes: NotesRepository

    private var bookmarks: List<BookmarkEntity> = emptyList()
    private var pageAnnotations: List<AnnotationEntity> = emptyList()
    private var currentPageNote: PageNoteEntity? = null

    private lateinit var root: FrameLayout
    private lateinit var readerView: ReaderView
    private lateinit var imageView: ZoomImageView
    private lateinit var imageStatus: TextView

    private var sectionIndex = 0
    private var pageIndex = 0
    private var pages: List<DisplayPage> = emptyList()
    private var currentSection: Section? = null
    private var pendingAnchor: Pair<String, Int>? = null
    private var imageJob: Job? = null

    private var menuLayer: FrameLayout? = null
    private var menuProgress: TextView? = null
    private var menuSeek: SeekBar? = null
    private var menuOcr: TextView? = null
    private var menuCancel: TextView? = null
    private var menuResume: TextView? = null
    private var menuRetry: TextView? = null
    private var menuFullscreen: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LibraryStore(this)
        store = BookStore(this)
        settings = ReaderSettings(this)
        imageCache = PageImageCache(store)
        notes = NotesRepository(this)

        val loaded = intent.getStringExtra(EXTRA_BOOK_ID)?.let { library.get(it) }
        if (loaded == null) {
            finish()
            return
        }
        book = loaded
        sectionIndex = book.lastSection.coerceIn(0, (book.totalSections - 1).coerceAtLeast(0))
        pageIndex = book.lastPage.coerceAtLeast(0)
        if (book.lastBlockId.isNotEmpty()) {
            pendingAnchor = book.lastBlockId to book.lastBlockOffset
        }

        buildUi()
        applyTheme()
        readerView.onMetricsChanged = {
            val anchor = pendingAnchor ?: currentAnchor()
            pendingAnchor = null
            reflow(anchor)
        }
        readerView.onTap = { fraction, offset -> handleTap(fraction, offset) }
        readerView.onLongPress = { offset -> startSelection(offset) }
        readerView.onSwipe = { forward -> swipePage(forward) }
        imageView.onSwipe = { forward -> swipePage(forward) }
        observeOcr()
        lifecycleScope.launch {
            notes.bookmarks(book.id).collect {
                bookmarks = it
                refreshChrome()
                updateMenuState()
            }
        }
    }

    // ---------------------------------------------------------------- UI

    private fun buildUi() {
        root = FrameLayout(this)
        readerView = ReaderView(this)
        root.addView(readerView, FrameLayout.LayoutParams(MATCH, MATCH))

        imageView = ZoomImageView(this).apply {
            visibility = View.GONE
            onTapFraction = { fraction -> handleTap(fraction, null) }
        }
        root.addView(imageView, FrameLayout.LayoutParams(MATCH, MATCH))

        imageStatus = TextView(this).apply {
            textSize = 14f
            visibility = View.GONE
        }
        root.addView(imageStatus, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER))
        setContentView(root)
    }

    private fun applyTheme() {
        val theme = settings.theme
        readerView.applyStyle(
            textSizeSp = settings.fontSizeSp.toFloat(),
            lineSpacingMultiplier = settings.lineSpacing,
            marginDp = settings.marginDp,
            bodyColor = theme.fg,
            secondary = theme.secondary,
            background = theme.bg,
        )
        root.setBackgroundColor(theme.bg)
        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (theme != ReaderTheme.NIGHT) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0
        imageStatus.setTextColor(theme.secondary)
    }

    // ----------------------------------------------------------- pagination

    private fun reflow(anchor: Pair<String, Int>?) {
        applyTheme()
        loadSection(anchor)
    }

    private fun loadSection(anchor: Pair<String, Int>?) {
        val section = store.readSection(book.id, sectionIndex)
        currentSection = section

        val built = if (section == null || (!section.isReadable && !hasPageImage(section))) {
            emptyList()
        } else {
            PageBuilder.build(
                section = section,
                paint = readerView.textPaint(),
                width = readerView.contentWidth,
                height = readerView.contentHeight,
                lineSpacing = settings.lineSpacing,
                imageMode = settings.showImagePages,
                fidelity = settings.fidelity,
            )
        }

        pages = if (built.isEmpty()) {
            listOf(
                DisplayPage.TextPage(
                    text = SpannableString(placeholderMessage(section)),
                    slices = emptyList(),
                    indexInUnit = 0,
                ),
            )
        } else {
            built
        }

        pageIndex = if (anchor != null) {
            PageBuilder.findPage(pages, anchor.first, anchor.second)
                .takeIf { it >= 0 }
                ?: pageIndex.coerceIn(0, pages.lastIndex)
        } else {
            pageIndex.coerceIn(0, pages.lastIndex)
        }
        renderCurrentPage()
        updateMenuState()
    }

    private fun hasPageImage(section: Section?): Boolean =
        section?.blocks?.any { it is PageImageBlock } == true

    private fun placeholderMessage(section: Section?): String = when {
        section == null -> "本页数据缺失，请重新导入该书。"
        section.state == SectionState.BLANK -> "（本页空白，可直接翻页）"
        section.state == SectionState.FAILED ->
            "本页识别失败\n${section.error ?: "未知错误"}\n\n菜单里可以重试失败的页。"
        OcrQueue.isRunning -> "本页正在识别…\n${ocrChipText()}"
        else -> "本页尚未识别\n打开菜单点“继续识别”即可接着做。"
    }

    private fun renderCurrentPage() {
        val page = pages.getOrNull(pageIndex)
        if (page is DisplayPage.ImagePage) {
            showImagePage(page)
            lifecycleScope.launch {
                currentPageNote = notes.pageNote(book.id, page.pageIndex)
                refreshChrome()
                updateMenuState()
            }
        } else {
            hideImage()
            val textPage = page as? DisplayPage.TextPage
            pageAnnotations = emptyList()
            currentPageNote = null
            readerView.setBody(textPage?.let { styledPageText(it) } ?: "")
            refreshChrome()
            if (textPage != null) loadAnnotations(textPage)
        }
        layoutImageBounds()
    }

    private fun styledPageText(page: DisplayPage.TextPage): CharSequence {
        if (pageAnnotations.isEmpty()) return page.text
        val builder = SpannableStringBuilder(page.text)
        for (slice in page.slices) {
            for (annotation in pageAnnotations) {
                if (annotation.blockId != slice.blockId) continue
                val from = maxOf(annotation.startOffset, slice.start)
                val to = minOf(annotation.endOffset, slice.end)
                if (to <= from) continue
                val startInPage = slice.pageStart + (from - slice.start)
                val endInPage = slice.pageStart + (to - slice.start)
                if (startInPage < 0 || endInPage > builder.length) continue
                builder.setSpan(
                    BackgroundColorSpan(annotation.color),
                    startInPage,
                    endInPage,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        return builder
    }

    /** Re-renders the current page's highlights without moving the reading position. */
    private fun refreshPageAnnotations() {
        val page = pages.getOrNull(pageIndex) as? DisplayPage.TextPage ?: return
        loadAnnotations(page)
    }

    private fun loadAnnotations(page: DisplayPage.TextPage) {
        val ids = page.slices.map { it.blockId }.distinct()
        lifecycleScope.launch {
            val loaded = notes.annotationsForBlocks(book.id, ids)
            if (pages.getOrNull(pageIndex) !== page) return@launch
            pageAnnotations = loaded
            readerView.setBody(styledPageText(page))
        }
    }

    private fun showImagePage(page: DisplayPage.ImagePage) {
        imageView.visibility = View.VISIBLE
        imageView.setBitmap(null)
        imageStatus.text = "渲染中…"
        imageStatus.visibility = View.VISIBLE
        readerView.setBody("")
        refreshChrome()
        layoutImageBounds()

        imageJob?.cancel()
        imageJob = lifecycleScope.launch {
            val bitmap = bitmapFor(page)
            if (bitmap == null) {
                imageStatus.text = "图片渲染失败"
                return@launch
            }
            if (!isActive) return@launch
            imageView.setBitmap(bitmap)
            imageStatus.visibility = View.GONE
        }
    }

    /** Whole-page scans use the page bitmap; a figure is cropped out with its bbox. */
    private suspend fun bitmapFor(page: DisplayPage.ImagePage): Bitmap? {
        val full = imageCache.load(book, page.pageIndex, readerView.width.coerceAtLeast(320))
            ?: return null
        val box = page.bbox
        if (page.wholePage || box == null) return full
        val left = (box.left * full.width).toInt().coerceIn(0, full.width - 1)
        val top = (box.top * full.height).toInt().coerceIn(0, full.height - 1)
        val right = (box.right * full.width).toInt().coerceIn(left + 1, full.width)
        val bottom = (box.bottom * full.height).toInt().coerceIn(top + 1, full.height)
        return runCatching { Bitmap.createBitmap(full, left, top, right - left, bottom - top) }
            .getOrNull()
    }

    private fun hideImage() {
        imageJob?.cancel()
        imageView.visibility = View.GONE
        imageView.setBitmap(null)
        imageStatus.visibility = View.GONE
    }

    private fun layoutImageBounds() {
        if (imageView.visibility != View.VISIBLE || readerView.width == 0) return
        val rect = readerView.contentRect()
        val params = imageView.layoutParams as FrameLayout.LayoutParams
        params.setMargins(
            rect.left,
            rect.top,
            readerView.width - rect.right,
            readerView.height - rect.bottom,
        )
        imageView.layoutParams = params
    }

    private fun refreshChrome() {
        val progress = OcrQueue.progress.value
        val ocr = if (progress != null && progress.bookId == book.id && progress.running) {
            " · OCR ${progress.done}/${progress.total}"
        } else {
            ""
        }
        val star = if (bookmarkedHere()) " ★" else ""
        val note = if (currentPageNote?.note?.isNotBlank() == true) " ✎" else ""
        readerView.setChrome(book.title, sectionLabel() + ocr + star + note, footerText())
    }

    private fun sectionLabel(): String =
        if (book.type == BookType.PDF) {
            "第 ${sectionIndex + 1} / ${book.totalSections} 页"
        } else {
            "第 ${sectionIndex + 1} / ${book.totalSections} 节"
        }

    private fun footerText(): String = "${pageIndex + 1}/${pages.size} · ${percentPercent()}%"

    private fun percentFloat(): Float {
        val total = book.totalSections.coerceAtLeast(1)
        val within = if (pages.isEmpty()) 0f else (pageIndex + 1).toFloat() / pages.size
        return ((sectionIndex + within) / total).coerceIn(0f, 1f)
    }

    private fun percentPercent(): Int = (percentFloat() * 100).toInt()

    private fun currentAnchor(): Pair<String, Int>? {
        val page = pages.getOrNull(pageIndex) ?: return null
        return when (page) {
            is DisplayPage.TextPage -> page.slices.firstOrNull()?.let { it.blockId to it.start }
            is DisplayPage.ImagePage -> page.blockId to 0
        }
    }

    private fun saveProgress() {
        val anchor = currentAnchor()
        library.updateProgress(
            id = book.id,
            section = sectionIndex,
            page = pageIndex,
            percent = percentFloat(),
            blockId = anchor?.first ?: "",
            blockOffset = anchor?.second ?: 0,
        )
    }

    // ------------------------------------------------------------ navigation

    private fun handleTap(fraction: Float, offset: Int?) {
        val textPage = pages.getOrNull(pageIndex) as? DisplayPage.TextPage
        annotationAt(textPage, offset)?.let { annotation ->
            showAnnotationDialog(annotation)
            return
        }
        // Swipe mode never turns pages on a tap, so the two styles stay exclusive.
        if (settings.pageTurnMode == PageTurnMode.SWIPE) {
            toggleMenu()
            return
        }
        when {
            fraction < ZONE_LEFT -> previousPage()
            fraction > ZONE_RIGHT -> nextPage()
            else -> toggleMenu()
        }
    }

    private fun swipePage(forward: Boolean) {
        if (settings.pageTurnMode != PageTurnMode.SWIPE) return
        if (forward) nextPage() else previousPage()
    }

    /** Short fade so the new page does not flash in. */
    private fun fadeInPage() {
        val view: View = if (imageView.visibility == View.VISIBLE) imageView else readerView
        view.animate().cancel()
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(180L).start()
    }

    private fun nextPage() {
        when {
            pageIndex < pages.lastIndex -> {
                pageIndex++
                renderCurrentPage()
                fadeInPage()
                saveProgress()
                updateMenuState()
            }
            sectionIndex < book.totalSections - 1 -> {
                sectionIndex++
                pageIndex = 0
                loadSection(null)
                fadeInPage()
                saveProgress()
            }
            else -> toast("已经是最后一页")
        }
    }

    private fun previousPage() {
        when {
            pageIndex > 0 -> {
                pageIndex--
                renderCurrentPage()
                fadeInPage()
                saveProgress()
                updateMenuState()
            }
            sectionIndex > 0 -> {
                sectionIndex--
                loadSection(null)
                pageIndex = pages.lastIndex.coerceAtLeast(0)
                renderCurrentPage()
                fadeInPage()
                saveProgress()
                updateMenuState()
            }
            else -> toast("已经是第一页")
        }
    }

    private fun jumpToSection(index: Int) {
        sectionIndex = index.coerceIn(0, (book.totalSections - 1).coerceAtLeast(0))
        pageIndex = 0
        loadSection(null)
        saveProgress()
    }

    private fun jumpToAnchor(blockId: String, offset: Int, pageIndex: Int) {
        sectionIndex = pageIndex.coerceIn(0, (book.totalSections - 1).coerceAtLeast(0))
        loadSection(blockId to offset)
    }

    // ----------------------------------------------------------------- menu

    private fun toggleMenu() {
        if (menuLayer == null) showMenu() else hideMenu()
    }

    private fun hideMenu() {
        menuLayer?.let { root.removeView(it) }
        menuLayer = null
        menuProgress = null
        menuSeek = null
        menuOcr = null
        menuCancel = null
        menuResume = null
        menuRetry = null
        menuFullscreen = null
    }

    private fun showMenu() {
        hideMenu()
        val theme = settings.theme
        val layer = FrameLayout(this)
        val scrim = View(this).apply {
            setBackgroundColor(0x33000000)
            setOnClickListener { hideMenu() }
        }
        layer.addView(scrim, FrameLayout.LayoutParams(MATCH, MATCH))

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(panelColor(theme))
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), withAlpha(theme.secondary, 90))
            }
        }

        panel.addView(TextView(this).apply {
            text = "阅读菜单 · v${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(theme.secondary)
        })
        menuProgress = TextView(this).apply {
            textSize = 13f
            setTextColor(theme.fg)
            setPadding(0, dp(4), 0, 0)
        }
        panel.addView(menuProgress)

        menuSeek = SeekBar(this).apply {
            max = (book.totalSections - 1).coerceAtLeast(0)
            progress = sectionIndex
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (fromUser) jumpToSection(value)
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        panel.addView(menuSeek)

        panel.addView(row("字号", { "${settings.fontSizeSp}" },
            { settings.fontSizeSp = settings.fontSizeSp - 1 },
            { settings.fontSizeSp = settings.fontSizeSp + 1 }))
        panel.addView(row("行距", { String.format(Locale.US, "%.1f", settings.lineSpacing) },
            { settings.lineSpacing = settings.lineSpacing - ReaderSettings.SPACING_STEP },
            { settings.lineSpacing = settings.lineSpacing + ReaderSettings.SPACING_STEP }))
        panel.addView(row("边距", { "${settings.marginDp}" },
            { settings.marginDp = settings.marginDp - ReaderSettings.MARGIN_STEP },
            { settings.marginDp = settings.marginDp + ReaderSettings.MARGIN_STEP }))

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        themeRow.addView(TextView(this).apply {
            text = "背景"
            textSize = 14f
            setTextColor(theme.secondary)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        for ((label, value) in listOf(
            "日间" to ReaderTheme.DAY,
            "护眼" to ReaderTheme.EYE,
            "米黄" to ReaderTheme.SEPIA,
            "深色" to ReaderTheme.NIGHT,
        )) {
            themeRow.addView(textButton(if (settings.theme == value) "●$label" else label) {
                settings.theme = value
                hideMenu()
                reflow(currentAnchor())
            })
        }
        panel.addView(themeRow)

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        modeRow.addView(TextView(this).apply {
            text = "图片页"
            textSize = 14f
            setTextColor(theme.secondary)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        modeRow.addView(compactButton(if (settings.showImagePages) "单独显示" else "仅文字") {
            settings.showImagePages = !settings.showImagePages
            hideMenu()
            reflow(currentAnchor())
        })
        panel.addView(modeRow)

        val layoutRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        layoutRow.addView(TextView(this).apply {
            text = "排版"
            textSize = 14f
            setTextColor(theme.secondary)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        layoutRow.addView(compactButton(if (settings.fidelity) "高度还原" else "普通重排") {
            settings.fidelity = !settings.fidelity
            hideMenu()
            reflow(currentAnchor())
        })
        panel.addView(layoutRow)

        val turnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        turnRow.addView(TextView(this).apply {
            text = "翻页"
            textSize = 14f
            setTextColor(theme.secondary)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        turnRow.addView(compactButton(if (settings.pageTurnMode == PageTurnMode.TAP) "点击" else "滑动") {
            settings.pageTurnMode = if (settings.pageTurnMode == PageTurnMode.TAP) {
                PageTurnMode.SWIPE
            } else {
                PageTurnMode.TAP
            }
            hideMenu()
            toast(if (settings.pageTurnMode == PageTurnMode.SWIPE) "已切换为滑动翻页" else "已切换为点击翻页")
        })
        panel.addView(turnRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, 0)
        }
        if (book.type == BookType.PDF) {
            actionRow.addView(compactButton("查看原页") { showOriginalPage() })
        }
        menuFullscreen = compactButton("全屏看图") { showFullscreen() }
        menuFullscreen?.let { actionRow.addView(it) }
        actionRow.addView(compactButton("重排版面") { rebuildLayout() })
        actionRow.addView(compactButton("目录") { showToc() })
        actionRow.addView(compactButton("回首页") { finish() })
        panel.addView(actionRow)

        val markRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        markRow.addView(compactButton(if (bookmarkedHere()) "★ 取消书签" else "☆ 加书签") { toggleBookmark() })
        markRow.addView(compactButton("书签列表") { showBookmarks() })
        markRow.addView(compactButton("批注列表") { showAnnotations() })
        markRow.addView(compactButton("整页笔记") { editPageNote() })
        markRow.addView(compactButton("导出") { exportNotes() })
        panel.addView(markRow)

        val ocrRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        menuOcr = TextView(this).apply {
            textSize = 12f
            setTextColor(theme.secondary)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        menuCancel = compactButton("取消识别") {
            OcrQueue.cancel()
            updateMenuState()
        }
        menuResume = compactButton("继续识别") {
            OcrQueue.start(this, book, store, focusSection = sectionIndex)
            updateMenuState()
        }
        menuRetry = compactButton("重试失败页") {
            OcrQueue.start(this, book, store, focusSection = sectionIndex, onlyFailed = true)
            updateMenuState()
        }
        ocrRow.addView(menuOcr)
        ocrRow.addView(compactButton("失败页") { showFailedPages() })
        ocrRow.addView(menuCancel)
        ocrRow.addView(menuResume)
        ocrRow.addView(menuRetry)
        panel.addView(ocrRow)

        layer.addView(
            panel,
            FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
                setMargins(dp(10), 0, dp(10), dp(10))
            },
        )
        root.addView(layer, FrameLayout.LayoutParams(MATCH, MATCH))
        menuLayer = layer
        updateMenuState()
    }

    private fun row(
        label: String,
        valueProvider: () -> String,
        onMinus: () -> Unit,
        onPlus: () -> Unit,
    ): LinearLayout {
        val theme = settings.theme
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val value = TextView(this).apply {
            text = valueProvider()
            textSize = 15f
            setTextColor(theme.fg)
            gravity = Gravity.CENTER
            minWidth = dp(52)
        }
        layout.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(theme.secondary)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        layout.addView(compactButton("−") {
            onMinus()
            value.text = valueProvider()
            reflow(currentAnchor())
        })
        layout.addView(value)
        layout.addView(compactButton("+") {
            onPlus()
            value.text = valueProvider()
            reflow(currentAnchor())
        })
        return layout
    }

    private fun compactButton(label: String, action: () -> Unit): TextView {
        val theme = settings.theme
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(theme.fg)
            setPadding(dp(5), dp(6), dp(5), dp(6))
            setOnClickListener { action() }
        }
    }

    private fun textButton(label: String, action: () -> Unit): TextView {
        val theme = settings.theme
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(theme.fg)
            setPadding(dp(10), dp(7), dp(10), dp(7))
            setOnClickListener { action() }
        }
    }

    private fun updateMenuState() {
        menuProgress?.text =
            "第 ${sectionIndex + 1} / ${book.totalSections} 个" +
                (if (book.type == BookType.PDF) "页" else "节") +
                " · 全书 ${percentPercent()}%"
        menuSeek?.progress = sectionIndex

        val progress = OcrQueue.progress.value
        val mine = progress != null && progress.bookId == book.id
        val remaining = if (mine) (progress!!.total - progress.done).coerceAtLeast(0) else 0
        menuOcr?.text = when {
            !mine -> ""
            progress!!.running -> "OCR ${progress.done}/${progress.total} 页"
            progress.cancelled && remaining > 0 -> "已取消：完成 ${progress.done}/${progress.total} 页"
            progress.failed > 0 -> "结束，${progress.failed} 页失败"
            else -> "完成 ${progress.done}/${progress.total} 页"
        }
        menuCancel?.visibility = if (mine && progress!!.running) View.VISIBLE else View.GONE
        menuResume?.visibility =
            if (mine && !progress!!.running && remaining > 0) View.VISIBLE else View.GONE
        menuRetry?.visibility =
            if (mine && !progress!!.running && progress.failed > 0) View.VISIBLE else View.GONE
        menuFullscreen?.visibility =
            if (pages.getOrNull(pageIndex) is DisplayPage.ImagePage) View.VISIBLE else View.GONE
    }

    // ------------------------------------------------------------- OCR glue

    private fun startOcrIfNeeded() {
        if (book.type != BookType.PDF) return
        if (OcrQueue.isRunning) return
        val pending = (0 until book.totalSections).count { index ->
            store.readSection(book.id, index)?.state == SectionState.PENDING
        }
        if (pending > 0) {
            OcrQueue.start(this, book, store, focusSection = sectionIndex)
        }
    }

    private fun observeOcr() {
        startOcrIfNeeded()
        lifecycleScope.launch {
            OcrQueue.progress.collect {
                refreshChrome()
                updateMenuState()
            }
        }
        lifecycleScope.launch {
            OcrQueue.sectionReady.collect { (bookId, index) ->
                if (bookId != book.id) return@collect
                if (index == sectionIndex) loadSection(currentAnchor())
                refreshChrome()
                updateMenuState()
            }
        }
    }

    private fun ocrChipText(): String {
        val progress = OcrQueue.progress.value
        if (progress == null || progress.bookId != book.id) return ""
        return when {
            progress.running -> "OCR 识别中 ${progress.done}/${progress.total} 页"
            progress.failed > 0 -> "OCR 结束：${progress.failed} 页失败（菜单可重试）"
            else -> "OCR 完成 ${progress.done}/${progress.total} 页"
        }
    }

    // ------------------------------------------------------ bookmarks & notes

    private fun annotationAt(page: DisplayPage.TextPage?, offset: Int?): AnnotationEntity? {
        if (page == null || offset == null) return null
        val slice = page.slices.firstOrNull { offset >= it.pageStart && offset < it.pageEnd } ?: return null
        val blockOffset = slice.start + (offset - slice.pageStart)
        return pageAnnotations.firstOrNull {
            it.blockId == slice.blockId && blockOffset >= it.startOffset && blockOffset < it.endOffset
        }
    }

    private fun bookmarkedHere(): Boolean {
        val anchor = currentAnchor() ?: return false
        return bookmarks.any { it.blockId == anchor.first && it.charOffset == anchor.second }
    }

    private fun previewText(): String {
        val page = pages.getOrNull(pageIndex)
        return when (page) {
            is DisplayPage.TextPage -> page.text.toString().replace('\n', ' ').trim().take(40)
            is DisplayPage.ImagePage -> "第 ${page.pageIndex + 1} 页（图片）"
            null -> ""
        }
    }

    private fun toggleBookmark() {
        val anchor = currentAnchor() ?: run {
            toast("当前页无法定位")
            return
        }
        hideMenu()
        lifecycleScope.launch {
            val existing = bookmarks.firstOrNull {
                it.blockId == anchor.first && it.charOffset == anchor.second
            }
            if (existing != null) {
                notes.removeBookmark(existing)
                toast("已取消书签")
            } else {
                notes.addBookmark(book.id, sectionIndex, anchor.first, anchor.second, previewText())
                toast("已添加书签")
            }
            refreshChrome()
        }
    }

    private fun startSelection(pageOffset: Int) {
        val page = pages.getOrNull(pageIndex) as? DisplayPage.TextPage ?: return
        val slice = page.slices.firstOrNull { pageOffset >= it.pageStart && pageOffset < it.pageEnd } ?: return
        val block = currentSection?.blocks?.firstOrNull { it.id == slice.blockId } as? TextBlock ?: return
        val text = block.text
        if (text.isEmpty()) return
        val blockOffset = (slice.start + (pageOffset - slice.pageStart)).coerceIn(0, text.length - 1)
        var start = blockOffset
        while (start > 0 && text[start - 1] !in "。！？!?\n") start--
        var end = blockOffset
        while (end < text.length && text[end] !in "。！？!?\n") end++
        if (end < text.length) end++
        if (end - start > 160) end = (start + 160).coerceAtMost(text.length)
        if (end <= start) return
        showSelectionDialog(slice.blockId, start, end, text.substring(start, end))
    }

    private fun showSelectionDialog(blockId: String, start: Int, end: Int, text: String) {
        hideMenu()
        AlertDialog.Builder(this)
            .setTitle("选中文字")
            .setMessage(text.take(300))
            .setPositiveButton("高亮") { _, _ -> saveAnnotation(blockId, start, end, text, "") }
            .setNeutralButton("高亮并写笔记") { _, _ ->
                editNote("添加笔记", "") { note -> saveAnnotation(blockId, start, end, text, note) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun saveAnnotation(blockId: String, start: Int, end: Int, text: String, note: String) {
        lifecycleScope.launch {
            notes.addAnnotation(book.id, sectionIndex, blockId, start, end, text, note, 0x66FFE082)
            toast(if (note.isBlank()) "已高亮" else "已保存批注")
            refreshPageAnnotations()
        }
    }

    private fun showAnnotationDialog(annotation: AnnotationEntity) {
        val colors = listOf(0x66FFE082, 0x66A5D6A7, 0x66F48FB1)
        AlertDialog.Builder(this)
            .setTitle("批注 · 第 ${annotation.pageIndex + 1} 页")
            .setMessage(
                "「${annotation.text}」" +
                    if (annotation.note.isBlank()) "" else "\n\n笔记：${annotation.note}",
            )
            .setPositiveButton("编辑笔记") { _, _ ->
                editNote("编辑笔记", annotation.note) { note ->
                    lifecycleScope.launch {
                        notes.updateAnnotation(annotation.copy(note = note))
                        refreshPageAnnotations()
                    }
                }
            }
            .setNeutralButton("改颜色") { _, _ ->
                lifecycleScope.launch {
                    val next = colors[(colors.indexOf(annotation.color).coerceAtLeast(0) + 1) % colors.size]
                    notes.updateAnnotation(annotation.copy(color = next))
                    refreshPageAnnotations()
                }
            }
            .setNegativeButton("删除") { _, _ ->
                lifecycleScope.launch {
                    notes.deleteAnnotation(annotation)
                    toast("已删除批注")
                    refreshPageAnnotations()
                }
            }
            .show()
    }

    private fun editNote(title: String, initial: String, onSave: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            minLines = 3
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("保存") { _, _ -> onSave(input.text.toString().trim()) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showBookmarks() {
        hideMenu()
        val list = bookmarks
        if (list.isEmpty()) {
            toast("还没有书签")
            return
        }
        val formatter = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        val items = list.map {
            "第 ${it.pageIndex + 1} 页 · ${it.preview} · ${formatter.format(Date(it.createdAt))}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("书签（${list.size}）")
            .setItems(items) { _, which ->
                val bookmark = list[which]
                jumpToAnchor(bookmark.blockId, bookmark.charOffset, bookmark.pageIndex)
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    /** Whole-book annotation list; tapping an entry jumps to its original location. */
    private fun showAnnotations() {
        hideMenu()
        lifecycleScope.launch {
            val list = notes.annotationsAll(book.id)
                .sortedWith(compareBy({ it.pageIndex }, { it.blockId }, { it.startOffset }))
            if (list.isEmpty()) {
                toast("还没有批注")
                return@launch
            }
            val items = list.map {
                "第 ${it.pageIndex + 1} 页 · 「${it.text.take(24)}」" +
                    if (it.note.isBlank()) "" else " · ${it.note.take(18)}"
            }.toTypedArray()
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("批注（整本书 ${list.size} 条）")
                .setItems(items) { _, which ->
                    val annotation = list[which]
                    jumpToAnchor(annotation.blockId, annotation.startOffset, annotation.pageIndex)
                }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    private fun editPageNote() {
        hideMenu()
        val page = sectionIndex
        lifecycleScope.launch {
            val existing = notes.pageNote(book.id, page)
            val input = EditText(this@ReaderActivity).apply {
                setText(existing?.note ?: "")
                minLines = 4
                setPadding(dp(16), dp(12), dp(16), dp(12))
            }
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("第 ${page + 1} 页笔记")
                .setView(input)
                .setPositiveButton("保存") { _, _ ->
                    lifecycleScope.launch {
                        notes.savePageNote(book.id, page, input.text.toString().trim())
                        currentPageNote = notes.pageNote(book.id, page)
                        toast("已保存整页笔记")
                        refreshChrome()
                        updateMenuState()
                    }
                }
                .setNeutralButton("删除") { _, _ ->
                    lifecycleScope.launch {
                        notes.savePageNote(book.id, page, "")
                        currentPageNote = null
                        refreshChrome()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun exportNotes() {
        hideMenu()
        lifecycleScope.launch {
            val (markdown, json) = notes.export(this@ReaderActivity, book)
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("已导出")
                .setMessage("Markdown：\n${markdown.absolutePath}\n\nJSON：\n${json.absolutePath}")
                .setPositiveButton("好", null)
                .show()
        }
    }

    /** Lists pages whose OCR failed, with the recorded reason, and jumps to them. */
    private fun showFailedPages() {
        hideMenu()
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) {
                (0 until book.totalSections).mapNotNull { index ->
                    val section = store.readSection(book.id, index) ?: return@mapNotNull null
                    if (section.state == SectionState.FAILED) {
                        index to (section.error ?: "识别失败")
                    } else {
                        null
                    }
                }
            }
            if (failed.isEmpty()) {
                toast("没有失败页")
                return@launch
            }
            val items = failed.map { (index, reason) -> "第 ${index + 1} 页 · $reason" }.toTypedArray()
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("失败页（${failed.size}）")
                .setItems(items) { _, which -> jumpToSection(failed[which].first) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** Re-analyses the stored PDF with the current layout rules, keeping OCR text. */
    private fun rebuildLayout() {
        hideMenu()
        if (book.type != BookType.PDF) {
            toast("TXT 不需要重新解析")
            return
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("重新解析版面")
            .setMessage("正在重新分析文字层、目录与插图…")
            .setCancelable(false)
            .setNegativeButton("取消", null)
            .show()
        lifecycleScope.launch {
            try {
                val importer = Importer(this@ReaderActivity, store)
                val total = importer.rebuildLayout(book) { done, all ->
                    runOnUiThread { dialog.setMessage("正在重新解析 $done/$all 页…") }
                }
                dialog.dismiss()
                loadSection(currentAnchor())
                toast("已重新解析 $total 页")
            } catch (t: Throwable) {
                dialog.dismiss()
                toast("重排失败：${t.message ?: "未知错误"}")
            }
        }
    }

    // ------------------------------------------------------------ dialogs

    private fun showOriginalPage() {
        if (book.type != BookType.PDF) {
            toast("TXT 没有原页可看")
            return
        }
        hideMenu()
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    PdfPageRenderer(store.sourceFile(book)).use { renderer ->
                        renderer.render(sectionIndex, 1200)
                    }
                }.getOrNull()
            }
            if (bitmap == null) {
                toast("原页渲染失败")
                return@launch
            }
            val image = ImageView(this@ReaderActivity).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val scroll = ScrollView(this@ReaderActivity).apply { addView(image) }
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("原页 · 第 ${sectionIndex + 1} 页")
                .setView(scroll)
                .setMessage("OCR 结果可能有个别错字；倾斜、双栏、竖排或低清晰度扫描会降低准确率。")
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    private fun showFullscreen() {
        val page = pages.getOrNull(pageIndex) as? DisplayPage.ImagePage ?: return
        hideMenu()
        lifecycleScope.launch {
            val bitmap = bitmapFor(page)
            if (bitmap == null) {
                toast("图片渲染失败")
                return@launch
            }
            val viewer = ZoomImageView(this@ReaderActivity).apply {
                setBitmap(bitmap)
                setBackgroundColor(Color.BLACK)
            }
            AlertDialog.Builder(this@ReaderActivity)
                .setView(viewer)
                .setPositiveButton("关闭", null)
                .show()
        }
    }

    /** Prefers the PDF's own bookmark tree; falls back to a per-page preview list. */
    private fun showToc() {
        hideMenu()
        lifecycleScope.launch {
            val outline = outlineFor()
            if (outline.isNotEmpty()) {
                val items = outline.map { item ->
                    "　".repeat(item.level.coerceIn(0, 4)) + item.title + "  ·  " + (item.pageIndex + 1)
                }.toTypedArray()
                AlertDialog.Builder(this@ReaderActivity)
                    .setTitle("目录（${outline.size} 条）")
                    .setItems(items) { _, which -> jumpToSection(outline[which].pageIndex) }
                    .setNegativeButton("关闭", null)
                    .show()
                return@launch
            }
            val items = withContext(Dispatchers.IO) { pagePreviewItems() }
            AlertDialog.Builder(this@ReaderActivity)
                .setTitle("目录（按页预览）")
                .setItems(items) { _, which -> jumpToSection(which) }
                .setNegativeButton("关闭", null)
                .show()
        }
    }

    /** Cached bookmark tree, extracted on demand for books imported before this feature. */
    private suspend fun outlineFor(): List<TocItem> = withContext(Dispatchers.IO) {
        val cached = store.readOutline(book.id)
        if (cached.isNotEmpty()) return@withContext cached
        if (book.type != BookType.PDF) return@withContext emptyList()
        val extracted = runCatching { PdfOutline.read(store.sourceFile(book)) }
            .getOrDefault(emptyList())
        if (extracted.isNotEmpty()) store.saveOutline(book.id, extracted)
        extracted
    }

    private fun pagePreviewItems(): Array<String> {
        val limit = minOf(book.totalSections, 800)
        return (0 until limit).map { index ->
            val section = store.readSection(book.id, index)
            when {
                section == null -> "（缺失）"
                section.isReadable -> "${index + 1}. ${TextUtil.sectionPreview(section.text)}"
                section.state == SectionState.BLANK -> "${index + 1}. （空白页）"
                section.blocks.any { it is PageImageBlock } -> "${index + 1}. （图片页 / 待识别）"
                else -> "${index + 1}. （待识别）"
            }
        }.toTypedArray()
    }

    // ------------------------------------------------------------- lifecycle

    override fun onPause() {
        super.onPause()
        saveProgress()
    }

    override fun onDestroy() {
        super.onDestroy()
        saveProgress()
        imageCache.clear()
        OcrQueue.dismiss(book.id)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun panelColor(theme: ReaderTheme): Int = when (theme) {
        ReaderTheme.NIGHT -> 0xFF1C1C1C.toInt()
        else -> 0xFFFFFFFF.toInt()
    }
}
