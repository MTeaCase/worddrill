package com.worddrill.app.ui.adapter

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.worddrill.app.R
import com.worddrill.app.data.AchievementDef
import com.worddrill.app.databinding.ItemAchievementBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 成就列表：每个徽章一张卡片，未解锁的整行压灰，解锁的显示获得日期。
 */
class AchievementAdapter : RecyclerView.Adapter<AchievementAdapter.VH>() {

    private val rows = mutableListOf<Pair<AchievementDef, Long>>()

    class VH(val binding: ItemAchievementBinding) : RecyclerView.ViewHolder(binding.root)

    fun submit(list: List<Pair<AchievementDef, Long>>) {
        rows.clear()
        rows.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemAchievementBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val (def, at) = rows[position]
        val b = holder.binding
        val context = b.root.context
        val unlocked = at > 0L

        b.ivIcon.setImageResource(iconOf(def.key))
        val iconTint = ContextCompat.getColor(context, if (unlocked) R.color.brand else R.color.text_hint)
        b.ivIcon.imageTintList = ColorStateList.valueOf(iconTint)
        b.iconBox.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(context, if (unlocked) R.color.brand_soft else R.color.surface_variant),
        )

        b.tvTitle.text = def.title
        b.tvTitle.setTextColor(
            ContextCompat.getColor(context, if (unlocked) R.color.text_primary else R.color.text_secondary),
        )
        b.tvTitle.setTypeface(null, if (unlocked) Typeface.BOLD else Typeface.NORMAL)

        b.tvDetail.text = def.detail
        b.tvDetail.setTextColor(
            ContextCompat.getColor(context, if (unlocked) R.color.text_secondary else R.color.text_hint),
        )

        b.tvWhen.isVisible = true
        b.tvWhen.text = if (unlocked) {
            context.getString(R.string.ach_unlocked_at, formatDate(at))
        } else {
            context.getString(R.string.ach_locked_when)
        }

        b.tvState.text = context.getString(if (unlocked) R.string.ach_unlocked else R.string.ach_locked)
        b.tvState.setTextColor(
            ContextCompat.getColor(context, if (unlocked) R.color.correct else R.color.text_hint),
        )
    }

    private fun formatDate(at: Long): String =
        SimpleDateFormat("MM-dd", Locale.US).format(Date(at))

    /** 每个成就一个手绘图形，按 key 对到 drawable。 */
    private fun iconOf(key: String): Int = when (key) {
        "first_word" -> R.drawable.ic_ach_first
        "words_100" -> R.drawable.ic_ach_words100
        "words_500" -> R.drawable.ic_ach_words500
        "streak_3" -> R.drawable.ic_ach_streak3
        "streak_7" -> R.drawable.ic_ach_streak7
        "streak_30" -> R.drawable.ic_ach_streak30
        "mastered_50" -> R.drawable.ic_ach_mastered
        else -> R.drawable.ic_ach_fast
    }
}