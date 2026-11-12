package com.worddrill.app.ui.adapter

import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
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
        val b = holder.binding
        b.tvWord.text = item.word
        b.tvMeaning.text = item.meaning
        // 「你填的」标红、「正确答案」标绿：两个都是红的等于没区分
        val context = b.root.context
        val secondary = ContextCompat.getColor(context, R.color.text_secondary)
        val wrong = ContextCompat.getColor(context, R.color.wrong)
        val correct = ContextCompat.getColor(context, R.color.correct)
        val text = SpannableStringBuilder().apply {
            appendColored("你的答案 ", secondary)
            appendColored(item.userAnswer.ifBlank { "（空白）" }, wrong, bold = true)
            appendColored("　正确答案 ", secondary)
            appendColored(item.answer, correct, bold = true)
        }
        b.tvAnswer.text = text
    }

    private fun SpannableStringBuilder.appendColored(
        value: String,
        color: Int,
        bold: Boolean = false,
    ) {
        val start = length
        append(value)
        setSpan(ForegroundColorSpan(color), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (bold) {
            setSpan(StyleSpan(Typeface.BOLD), start, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
