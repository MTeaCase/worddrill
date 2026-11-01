package com.worddrill.app.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.R
import com.worddrill.app.util.AboutConfig
import com.worddrill.app.util.AboutInfo
import com.worddrill.app.util.AboutRow

/** 「关于」对话框：内容全部来自 assets/about.txt，代码里只负责排版。 */
object AboutDialog {

    fun show(activity: AppCompatActivity) {
        val info = AboutConfig.load(activity, apkVersion(activity))
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_about, null)
        bind(activity, view, info)
        MaterialAlertDialogBuilder(activity)
            .setView(view)
            .setPositiveButton("知道了", null)
            .show()
    }

    fun bind(context: Context, root: View, info: AboutInfo) {
        root.findViewById<TextView>(R.id.tvAboutName).text = info.name
        root.findViewById<TextView>(R.id.tvAboutVersion).text =
            context.getString(R.string.about_version, info.version)
        setOptional(root, R.id.tvAboutSummary, info.summary)
        setOptional(root, R.id.tvAboutNote, info.note)

        val rows = root.findViewById<LinearLayout>(R.id.rowsContainer)
        rows.removeAllViews()
        val allRows = buildList {
            if (info.developer.isNotBlank()) {
                add(AboutRow(context.getString(R.string.about_developer_label), info.developer))
            }
            addAll(info.rows)
        }
        val inflater = LayoutInflater.from(context)
        allRows.forEach { row ->
            val item = inflater.inflate(R.layout.item_about_row, rows, false)
            val label = item.findViewById<TextView>(R.id.tvRowLabel)
            label.text = row.label
            label.isVisible = row.label.isNotBlank()
            item.findViewById<TextView>(R.id.tvRowValue).text = row.value
            rows.addView(item)
        }
        rows.isVisible = allRows.isNotEmpty()
        root.findViewById<View>(R.id.dividerRows).isVisible = allRows.isNotEmpty()
    }

    private fun setOptional(root: View, id: Int, text: String) {
        val view = root.findViewById<TextView>(id)
        view.text = text
        view.isVisible = text.isNotBlank()
    }

    private fun apkVersion(context: Context): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "1.0.0"
}
