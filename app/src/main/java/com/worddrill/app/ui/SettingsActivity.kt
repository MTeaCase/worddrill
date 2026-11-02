package com.worddrill.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import com.worddrill.app.BuildConfig
import com.worddrill.app.R
import com.worddrill.app.data.WordRepository
import com.worddrill.app.databinding.ActivitySettingsBinding
import com.worddrill.app.util.ApkInstaller
import com.worddrill.app.util.Bg
import com.worddrill.app.util.Settings
import com.worddrill.app.util.Tts
import com.worddrill.app.util.Updater
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 设置：单词朗读、备份与恢复、检查更新、关于。 */
class SettingsActivity : BaseActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var repo: WordRepository

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeBackup(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readBackup(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyInsets(binding.root)

        repo = WordRepository(this)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.swTts.isChecked = Settings.ttsEnabled(this)
        binding.swTts.setOnCheckedChangeListener { _, checked ->
            Settings.setTtsEnabled(this, checked)
            if (checked) Tts.speak(this, "hello")
        }

        binding.btnExport.setOnClickListener { startExport() }
        binding.btnImport.setOnClickListener { startImport() }
        binding.btnCheckUpdate.setOnClickListener { Updater.check(this, manual = true) }
        binding.btnAbout.setOnClickListener { AboutDialog.show(this) }

        binding.tvVersion.text = "WordDrill ${BuildConfig.VERSION_NAME}"
    }

    override fun onResume() {
        super.onResume()
        // 从「安装未知应用」设置页返回后，继续未完成的安装
        ApkInstaller.onActivityResume(this)
    }

    private fun startExport() {        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
        exportLauncher.launch("worddrill-backup-$stamp.json")
    }

    private fun writeBackup(uri: Uri) {
        Bg.run({
            val json = repo.exportBackupJson()
            contentResolver.openOutputStream(uri)?.use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: throw IllegalStateException("无法写入文件")
            json.length
        }) { result ->
            result.onSuccess { length ->
                toast("已导出备份（${length / 1024} KB）")
            }.onFailure { toast("导出失败：${it.message}") }
        }
    }

    private fun startImport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.settings_import)
            .setMessage("会把备份里的词库、错题、星标、打卡记录**合并**到本机，已有的单词不会被删除或覆盖。")
            .setPositiveButton("选择文件") { _, _ ->
                importLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun readBackup(uri: Uri) {
        Bg.run({
            val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: throw IllegalStateException("无法读取文件")
            repo.importBackupJson(text)
        }) { result ->
            result.onSuccess { summary ->
                AlertDialog.Builder(this)
                    .setTitle("导入完成")
                    .setMessage(summary)
                    .setPositiveButton("好", null)
                    .show()
            }.onFailure { toast("导入失败：${it.message}") }
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        fun intent(context: Context): Intent = Intent(context, SettingsActivity::class.java)
    }
}
