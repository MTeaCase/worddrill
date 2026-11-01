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
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivityImportBinding
import com.worddrill.app.parser.VocabEntry
import com.worddrill.app.parser.VocabMarkdownParser
import com.worddrill.app.util.Bg
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
