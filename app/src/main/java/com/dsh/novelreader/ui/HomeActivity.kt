package com.dsh.novelreader.ui

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsh.novelreader.BuildConfig
import com.dsh.novelreader.R
import com.dsh.novelreader.data.Book
import com.dsh.novelreader.data.BookStore
import com.dsh.novelreader.data.BookType
import com.dsh.novelreader.data.LibraryStore
import com.dsh.novelreader.data.ReaderSettings
import com.dsh.novelreader.data.ReaderTheme
import com.dsh.novelreader.pdf.Importer
import com.dsh.novelreader.util.TextUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Home screen: recent books, an import button, and an empty state.
 * Files arrive through the Storage Access Framework, so the app never asks for
 * a storage permission — it only ever reads the stream the user picked.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var library: LibraryStore
    private lateinit var store: BookStore
    private lateinit var settings: ReaderSettings
    private lateinit var adapter: BookAdapter
    private lateinit var recycler: RecyclerView
    private lateinit var emptyState: View

    private var importJob: Job? = null
    private var dialog: AlertDialog? = null
    private var dialogLabel: TextView? = null
    private var dialogBar: ProgressBar? = null

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        library = LibraryStore(this)
        store = BookStore(this)
        settings = ReaderSettings(this)

        recycler = findViewById(R.id.bookList)
        emptyState = findViewById(R.id.emptyState)
        findViewById<TextView>(R.id.versionText).text = "v${BuildConfig.VERSION_NAME}"
        adapter = BookAdapter(
            items = emptyList(),
            onClick = { book -> openReader(book) },
            onLongClick = { book -> confirmDelete(book) },
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.addItemDecoration(object : RecyclerView.ItemDecoration() {
            private val paint = Paint().apply { strokeWidth = 1f; isAntiAlias = true }

            override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
                paint.color = withAlpha(settings.theme.secondary, 70)
                for (index in 0 until parent.childCount) {
                    val child = parent.getChildAt(index)
                    val y = child.bottom.toFloat()
                    c.drawLine(child.left + dp(20f), y, child.right - dp(20f), y, paint)
                }
            }
        })
        recycler.adapter = adapter
        applyTheme()

        findViewById<Button>(R.id.importButton).setOnClickListener {
            picker.launch(
                arrayOf(
                    "application/pdf",
                    "text/plain",
                    "text/*",
                    "application/octet-stream",
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        // The reader can change the theme; re-apply it when coming back.
        applyTheme()
    }

    private fun applyTheme() {
        val theme = settings.theme
        window.statusBarColor = theme.bg
        window.navigationBarColor = theme.bg
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            if (theme != ReaderTheme.NIGHT) View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else 0

        findViewById<View>(R.id.homeRoot).setBackgroundColor(theme.bg)
        findViewById<TextView>(R.id.headerTitle).setTextColor(theme.fg)
        findViewById<TextView>(R.id.versionText).setTextColor(theme.secondary)
        findViewById<TextView>(R.id.emptyTitle).setTextColor(theme.secondary)
        findViewById<TextView>(R.id.emptyHint).setTextColor(theme.secondary)

        val button = findViewById<Button>(R.id.importButton)
        button.backgroundTintList = ColorStateList.valueOf(theme.accent)
        button.setTextColor(Color.WHITE)

        adapter.titleColor = theme.fg
        adapter.metaColor = theme.secondary
        adapter.notifyDataSetChanged()
        recycler.invalidateItemDecorations()
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private fun refresh() {
        val books = library.books()
        adapter.submit(books)
        emptyState.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
        recycler.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun openReader(book: Book) {
        startActivity(
            Intent(this, ReaderActivity::class.java)
                .putExtra(ReaderActivity.EXTRA_BOOK_ID, book.id),
        )
    }

    private fun confirmDelete(book: Book) {
        AlertDialog.Builder(this)
            .setTitle("删除《${book.title}》？")
            .setMessage("会同时删除导入的文件和 OCR 缓存。")
            .setPositiveButton("删除") { _, _ ->
                library.delete(book.id)
                store.deleteBook(book.id)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importPicked(uri: Uri) {
        val displayName = queryDisplayName(uri)
        val title = TextUtil.displayTitle(displayName)
        val type = if (displayName.endsWith(".txt", ignoreCase = true) ||
            contentResolver.getType(uri)?.startsWith("text/") == true
        ) {
            BookType.TXT
        } else {
            BookType.PDF
        }
        val id = UUID.randomUUID().toString()
        showProgress("正在导入《$title》…")

        importJob = lifecycleScope.launch {
            try {
                val importer = Importer(this@HomeActivity, store)
                val book = withContext(Dispatchers.IO) {
                    if (type == BookType.TXT) {
                        importer.importTxt(uri, id, title)
                    } else {
                        importer.importPdf(uri, id, title) { done, total ->
                            runOnUiThread { updateProgress("正在逐页检测文字层 $done/$total", done, total) }
                        }
                    }
                }
                library.upsert(book)
                dismissProgress()
                refresh()
                openReader(book)
            } catch (cancelled: CancellationException) {
                store.deleteBook(id)
                dismissProgress()
                Toast.makeText(this@HomeActivity, "已取消导入", Toast.LENGTH_SHORT).show()
            } catch (error: Throwable) {
                store.deleteBook(id)
                dismissProgress()
                AlertDialog.Builder(this@HomeActivity)
                    .setTitle("导入失败")
                    .setMessage(error.message ?: "无法读取所选文件")
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val name = cursor.getString(0)
                        if (!name.isNullOrBlank()) return name
                    }
                }
        }
        return uri.lastPathSegment ?: "未命名"
    }

    private fun showProgress(message: String) {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
        }
        dialogLabel = TextView(this).apply { text = message }
        dialogBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        container.addView(dialogLabel)
        container.addView(dialogBar)
        dialog = AlertDialog.Builder(this)
            .setTitle("导入")
            .setView(container)
            .setNegativeButton("取消") { _, _ -> importJob?.cancel() }
            .setCancelable(false)
            .show()
    }

    private fun updateProgress(message: String, done: Int, total: Int) {
        dialogLabel?.text = message
        val bar = dialogBar ?: return
        if (total <= 0) {
            bar.isIndeterminate = true
            return
        }
        bar.isIndeterminate = false
        bar.max = total
        bar.progress = done
    }

    private fun dismissProgress() {
        dialog?.dismiss()
        dialog = null
        dialogLabel = null
        dialogBar = null
        importJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dialog?.dismiss()
    }
}
