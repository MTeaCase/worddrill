package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.worddrill.app.databinding.ActivityPrivacyBinding

/**
 * 隐私政策页：内容来自 assets/privacy.md（与仓库根目录的 PRIVACY.md 保持一致）。
 * 这里做最简的 Markdown 去标记，正文用普通文本展示，不做富文本渲染。
 */
class PrivacyActivity : BaseActivity() {

    private lateinit var binding: ActivityPrivacyBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvPrivacy.text = plainText(loadMarkdown())
    }

    private fun loadMarkdown(): String = runCatching {
        assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse { "隐私政策加载失败：${it.message}" }

    /** 去掉 Markdown 标记，保留可读的纯文本。 */
    private fun plainText(markdown: String): String = markdown.lineSequence()
        .joinToString("\n") { raw ->
            var line = raw.trimEnd()
            when {
                line.startsWith("#### ") -> line.removePrefix("#### ")
                line.startsWith("### ") -> line.removePrefix("### ")
                line.startsWith("## ") -> line.removePrefix("## ")
                line.startsWith("# ") -> line.removePrefix("# ")
                else -> line
            }.replace("**", "").replace("`", "")
        }

    companion object {
        private const val ASSET = "privacy.md"

        fun intent(context: Context): Intent = Intent(context, PrivacyActivity::class.java)
    }
}
