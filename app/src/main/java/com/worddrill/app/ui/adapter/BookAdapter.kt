package com.worddrill.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.worddrill.app.data.Book
import com.worddrill.app.databinding.ItemBookBinding

class BookAdapter(
    private var items: List<Book>,
    private val onOpen: (Book) -> Unit,
    private val onDelete: (Book) -> Unit,
) : RecyclerView.Adapter<BookAdapter.VH>() {

    class VH(val binding: ItemBookBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<Book>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemBookBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val book = items[position]
        val b = holder.binding
        b.tvName.text = book.name
        b.tvMeta.text = "${book.wordCount} 词 · ${if (book.builtin) "内置词库" else "自组词库"}"
        b.btnDelete.visibility = if (book.builtin) View.GONE else View.VISIBLE
        b.btnDelete.setOnClickListener { onDelete(book) }
        b.root.setOnClickListener { onOpen(book) }
    }
}
