package com.worddrill.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.worddrill.app.R
import com.worddrill.app.data.Word
import com.worddrill.app.databinding.ItemWordBinding

class WordAdapter(
    private var items: List<Word>,
    private val extraProvider: (Word) -> String? = { null },
    private val onStar: (Word) -> Unit,
    private val showIndex: Boolean = true,
    private val onClick: ((Word) -> Unit)? = null,
    private val onLongClick: ((Word) -> Unit)? = null,
    /** 传入后每行右侧会多一个「移出」按钮（错题本用）。 */
    private val onRemove: ((Word) -> Unit)? = null,
) : RecyclerView.Adapter<WordAdapter.VH>() {

    class VH(val binding: ItemWordBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<Word>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemWordBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val word = items[position]
        val b = holder.binding
        b.tvIndex.visibility = if (showIndex) View.VISIBLE else View.GONE
        b.tvIndex.text = word.ord.toString()
        b.tvWord.text = word.text
        b.tvMeaning.text = word.meaningWithPos
        val extra = extraProvider(word)
        if (extra.isNullOrBlank()) {
            b.tvExtra.visibility = View.GONE
        } else {
            b.tvExtra.visibility = View.VISIBLE
            b.tvExtra.text = extra
        }
        b.btnStar.setImageResource(if (word.starred) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        b.btnStar.setOnClickListener { onStar(word) }
        if (onRemove == null) {
            b.btnRemove.visibility = View.GONE
        } else {
            b.btnRemove.visibility = View.VISIBLE
            b.btnRemove.setOnClickListener { onRemove.invoke(word) }
        }
        b.root.setOnClickListener(onClick?.let { callback -> View.OnClickListener { callback(word) } })
        b.root.setOnLongClickListener(
            onLongClick?.let { callback ->
                View.OnLongClickListener {
                    callback(word)
                    true
                }
            },
        )
    }
}
