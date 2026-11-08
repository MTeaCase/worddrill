package com.worddrill.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.worddrill.app.data.Book
import com.worddrill.app.databinding.ItemBookCardBinding

/** 词库 tab 的卡片：显示进度、当前选中的打勾，点卡片切换，点「词表」进词表。 */
class LibraryBookAdapter(
    private var items: List<LibraryBook>,
    private val onSelect: (LibraryBook) -> Unit,
    private val onWords: (LibraryBook) -> Unit,
    private val onDelete: (Book) -> Unit,
) : RecyclerView.Adapter<LibraryBookAdapter.VH>() {

    data class LibraryBook(val book: Book, val practiced: Int, val selected: Boolean)

    class VH(val binding: ItemBookCardBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<LibraryBook>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBookCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        val book = item.book
        val b = holder.binding
        b.tvName.text = book.name
        b.tvBadge.text = if (book.builtin) "内置" else "自组"
        b.ivSelected.visibility = if (item.selected) View.VISIBLE else View.GONE
        b.tvMeta.text = "${book.wordCount} 词 · 已练 ${item.practiced}"
        b.root.strokeColor = if (item.selected) {
            b.root.context.getColor(com.worddrill.app.R.color.brand)
        } else {
            b.root.context.getColor(com.worddrill.app.R.color.divider)
        }
        b.root.strokeWidth = if (item.selected) 2 else 1
        b.root.setOnClickListener { onSelect(item) }
        b.btnWords.setOnClickListener { onWords(item) }
        b.root.setOnLongClickListener {
            if (!book.builtin) onDelete(book)
            true
        }
    }
}
