package com.worddrill.app.ui.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.worddrill.app.R
import com.worddrill.app.data.AnswerRecord
import com.worddrill.app.databinding.ItemResultBinding

class ResultAdapter(private val items: List<AnswerRecord>) :
    RecyclerView.Adapter<ResultAdapter.VH>() {

    class VH(val binding: ItemResultBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemResultBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvWord.text = item.word
        holder.binding.tvMeaning.text = item.meaning
        holder.binding.tvAnswer.text = "你的答案：${item.userAnswer.ifBlank { "（空白）" }}　正确答案：${item.answer}"
    }
}
