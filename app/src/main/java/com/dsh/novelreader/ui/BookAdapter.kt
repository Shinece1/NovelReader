package com.dsh.novelreader.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.dsh.novelreader.R
import com.dsh.novelreader.data.Book
import com.dsh.novelreader.data.BookType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BookAdapter(
    private var items: List<Book>,
    private val onClick: (Book) -> Unit,
    private val onLongClick: (Book) -> Unit,
) : RecyclerView.Adapter<BookAdapter.Holder>() {

    fun submit(list: List<Book>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_book, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(items[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val title: TextView = itemView.findViewById(R.id.bookTitle)
        private val meta: TextView = itemView.findViewById(R.id.bookMeta)

        fun bind(book: Book) {
            title.text = book.title
            val type = if (book.type == BookType.PDF) "PDF" else "TXT"
            val percent = (book.lastPercent * 100).toInt()
            val whenText = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                .format(Date(if (book.updatedAt == 0L) System.currentTimeMillis() else book.updatedAt))
            meta.text = "$type · 已读 $percent% · 上次 $whenText"
            itemView.setOnClickListener { onClick(book) }
            itemView.setOnLongClickListener {
                onLongClick(book)
                true
            }
        }
    }
}
