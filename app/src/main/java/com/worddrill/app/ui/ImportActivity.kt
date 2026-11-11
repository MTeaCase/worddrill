package com.worddrill.app.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityImportBinding
import com.worddrill.app.parser.VocabEntry
import com.worddrill.app.parser.VocabMarkdownParser
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings
import com.worddrill.app.util.SmartParser
import com.worddrill.app.util.TextFiles

class ImportActivity : BaseActivity() {

    private lateinit var binding: ActivityImportBinding
    private lateinit var repo: WordRepository

    private var appendBookId: Long? = null
    private var parsed: List<VocabEntry> = emptyList()

    private val pickFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) loadFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        appendBookId = intent.getLongExtra(EXTRA_BOOK_ID, -1L).takeIf { it > 0 }
        val existingName = intent.getStringExtra(EXTRA_BOOK_NAME).orEmpty()

        binding.toolbar.setNavigationOnClickListener { finish() }
        if (appendBookId != null) {
            binding.toolbar.title = "追加单词"
            binding.etName.setText(existingName)
            binding.etName.isEnabled = false
        }

        binding.btnPickFile.setOnClickListener { pickFile.launch(arrayOf("*/*")) }
        binding.btnPaste.setOnClickListener { pasteFromClipboard() }
        binding.btnPreview.setOnClickListener { preview() }
        binding.btnSave.setOnClickListener { save() }
        binding.btnAiParse.setOnClickListener { confirmSmartParse() }

        handleIntent(intent)
    }

    /** 被别的应用分享/打开时，Intent 里带过来的就是词表文本。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(incoming: Intent?) {
        if (incoming == null) return
        when (incoming.action) {
            Intent.ACTION_SEND -> {
                val text = incoming.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                val subject = incoming.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString()
                if (!text.isNullOrBlank()) fillSharedText(text, subject)
            }

            Intent.ACTION_VIEW -> incoming.data?.let { loadSharedFile(it) }
        }
    }

    private fun fillSharedText(text: String, name: String?) {
        binding.etMarkdown.setText(text)
        binding.tvFileName.visibility = View.GONE
        if (appendBookId == null && !name.isNullOrBlank() && binding.etName.text.isNullOrBlank()) {
            binding.etName.setText(name)
        }
        preview()
        if (parsed.isNotEmpty()) toast("从分享内容解析到 ${parsed.size} 条")
    }

    private fun loadSharedFile(uri: Uri) {
        Bg.run({ TextFiles.read(this, uri) }) { result ->
            result.onSuccess { fillSharedText(it, null) }
                .onFailure { toast("读取分享的文件失败：${it.message}") }
        }
    }

    private fun loadFile(uri: Uri) {
        binding.btnPickFile.isEnabled = false
        Bg.run({
            val text = TextFiles.read(this, uri)
            val name = TextFiles.displayName(this, uri)
            text to name
        }) { result ->
            binding.btnPickFile.isEnabled = true
            result.onSuccess { (text, name) ->
                binding.etMarkdown.setText(text)
                binding.tvFileName.visibility = View.VISIBLE
                binding.tvFileName.text = "已读取：$name"
                if (appendBookId == null && binding.etName.text.isNullOrBlank()) {
                    binding.etName.setText(name.substringBeforeLast('.').ifBlank { name })
                }
                preview()
            }.onFailure {
                toast("读取失败：${it.message}")
            }
        }
    }

    private fun pasteFromClipboard() {
        val manager = getSystemService<ClipboardManager>()
        val text = manager?.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板里没有文本")
            return
        }
        binding.etMarkdown.setText(text)
        binding.tvFileName.visibility = View.GONE
        preview()
    }

    private fun preview() {
        val text = binding.etMarkdown.text?.toString().orEmpty()
        parsed = VocabMarkdownParser.parse(text)
        binding.previewCard.visibility = View.VISIBLE
        if (parsed.isEmpty()) {
            binding.tvPreview.text = "没有解析到单词。请确认内容是 Markdown 表格（| 英文 | 词性 | 汉语 |）。"
            return
        }
        val sample = parsed.take(5).joinToString("\n") { entry ->
            "· ${entry.word}  ${entry.pos}  ${entry.meaning}"
        }
        val more = if (parsed.size > 5) "\n…" else ""
        binding.tvPreview.text = "解析到 ${parsed.size} 条（按出现顺序入库）：\n$sample$more"
    }

    /** AI 智能解析：把当前内容发给自建服务整理成表格，再走原来的导入流程。 */
    private fun confirmSmartParse() {
        val text = binding.etMarkdown.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) {
            toast("先把要整理的内容粘贴或读进来")
            return
        }
        val url = Settings.smartParseUrl(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_ai_parse)
            .setMessage(getString(R.string.import_ai_confirm, url))
            .setPositiveButton(R.string.import_ai_start) { _, _ -> runSmartParse(url, text) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun runSmartParse(url: String, text: String) {
        setBusy(true)
        binding.tvAiProgress.text = getString(R.string.import_ai_running)
        // 单独开线程：AI 推理可能要几十秒，不能占用数据库线程
        Thread({
            val result = runCatching {
                SmartParser.request(Settings.smartParseUrls(this), text) { done, total ->
                    if (total > 1) {
                        Bg.post {
                            if (!isFinishing && !isDestroyed) {
                                binding.tvAiProgress.text = getString(R.string.import_ai_progress, done, total)
                            }
                        }
                    }
                }
            }
            Bg.post {
                if (isFinishing || isDestroyed) return@post
                setBusy(false)
                binding.tvAiProgress.text = getString(R.string.import_ai_running)
                result.onSuccess { r ->
                    binding.etMarkdown.setText(r.markdown)
                    binding.tvFileName.visibility = View.GONE
                    preview()
                    toast(getString(R.string.import_ai_done, r.count))
                }.onFailure { error ->
                    toast("智能解析失败：${error.message ?: "未知错误"}")
                }
            }
        }, "worddrill-ai").apply { isDaemon = true }.start()
    }

    private fun setBusy(busy: Boolean) {
        binding.btnAiParse.isEnabled = !busy
        binding.btnPreview.isEnabled = !busy
        binding.btnPaste.isEnabled = !busy
        binding.btnPickFile.isEnabled = !busy
        binding.btnSave.isEnabled = !busy
        binding.aiProgress.visibility = if (busy) View.VISIBLE else View.GONE
    }

    private fun save() {
        val text = binding.etMarkdown.text?.toString().orEmpty()
        val entries = VocabMarkdownParser.parse(text)
        if (entries.isEmpty()) {
            toast("没有可导入的单词")
            return
        }
        val name = binding.etName.text?.toString()?.trim().orEmpty()
        if (appendBookId == null && name.isEmpty()) {
            toast("请填写词库名称")
            return
        }
        binding.btnSave.isEnabled = false
        Bg.run({ repo.importEntries(appendBookId, name, entries) }) { result ->
            binding.btnSave.isEnabled = true
            result.onSuccess { r ->
                val action = if (appendBookId == null) "已创建" else "已追加"
                toast("$action ${r.added} 条，跳过 ${r.skipped} 条重复")
                setResult(RESULT_OK)
                finish()
            }.onFailure {
                toast("导入失败：${it.message}")
            }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_BOOK_NAME = "book_name"

        fun intent(context: Context, bookId: Long? = null, bookName: String? = null): Intent =
            Intent(context, ImportActivity::class.java).apply {
                if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId)
                if (bookName != null) putExtra(EXTRA_BOOK_NAME, bookName)
            }
    }
}
