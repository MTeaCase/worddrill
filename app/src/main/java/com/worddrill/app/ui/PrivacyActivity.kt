package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.worddrill.app.R
import com.worddrill.app.databinding.ActivityPrivacyBinding

/**
 * 纯文本页面：隐私政策、开放源代码许可都用它。
 * 内容来自 assets 里的 Markdown 文件，这里只做最简去标记，不做富文本渲染。
 */
class PrivacyActivity : BaseActivity() {

    private lateinit var binding: ActivityPrivacyBinding

    private val assetName: String get() = intent.getStringExtra(EXTRA_ASSET) ?: ASSET_PRIVACY

    private val titleText: String
        get() = intent.getStringExtra(EXTRA_TITLE) ?: getString(R.string.privacy_title)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPrivacyBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)
        binding.toolbar.title = titleText
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.tvPrivacy.text = plainText(loadMarkdown())
    }

    private fun loadMarkdown(): String = runCatching {
        assets.open(assetName).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrElse { "内容加载失败：${it.message}" }

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
        private const val ASSET_PRIVACY = "privacy.md"
        private const val ASSET_LICENSE = "license.md"
        private const val EXTRA_ASSET = "asset"
        private const val EXTRA_TITLE = "title"

        fun intent(context: Context): Intent = Intent(context, PrivacyActivity::class.java)

        fun licenseIntent(context: Context): Intent =
            Intent(context, PrivacyActivity::class.java).apply {
                putExtra(EXTRA_ASSET, ASSET_LICENSE)
                putExtra(EXTRA_TITLE, context.getString(R.string.settings_license))
            }
    }
}
