package com.example.alarmcheckinlauncher

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.alarmcheckinlauncher.databinding.ActivityGithubSettingsBinding

/**
 * GitHub 上传配置表单：Token / owner / repo / branch / path。
 *
 * - 所有字段保存在 [GithubSettings]（应用私有目录，不进仓库）
 * - 提供「填入当前仓库」按钮，用包名反推默认 owner/repo（用户可改）
 * - Token 字段使用 password inputType 隐藏
 */
class GithubSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGithubSettingsBinding
    private val settings by lazy { GithubSettings.get(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGithubSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = getString(R.string.title_github_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // 回填；若 owner/repo 为空（首次打开），自动预填当前仓库默认值，避免空配置
        binding.editToken.setText(settings.token)
        if (settings.owner.isEmpty() || settings.repo.isEmpty()) {
            binding.editOwner.setText("sicauyanglei")
            binding.editRepo.setText("AlarmCheckInLauncher")
            binding.editBranch.setText("main")
            binding.editPath.setText("logs/alarm_checkin.log")
        } else {
            binding.editOwner.setText(settings.owner)
            binding.editRepo.setText(settings.repo)
            binding.editBranch.setText(settings.branch)
            binding.editPath.setText(settings.path)
        }

        binding.btnSave.setOnClickListener {
            val token = binding.editToken.text?.toString().orEmpty().trim()
            val owner = binding.editOwner.text?.toString().orEmpty().trim()
            val repo = binding.editRepo.text?.toString().orEmpty().trim()
            val branch = binding.editBranch.text?.toString().orEmpty().trim().ifEmpty { "main" }
            val path = binding.editPath.text?.toString().orEmpty().trim().ifEmpty { "logs/alarm_checkin.log" }
            if (token.isEmpty() || owner.isEmpty() || repo.isEmpty()) {
                Toast.makeText(this, R.string.toast_github_required, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            settings.token = token
            settings.owner = owner
            settings.repo = repo
            settings.branch = branch
            settings.path = path
            FileLogger.i("GitHub 配置已保存 owner=$owner repo=$repo branch=$branch path=$path")
            Toast.makeText(this, R.string.toast_github_saved, Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.btnFillCurrentRepo.setOnClickListener {
            // 用本应用仓库做默认值（便于直接提交到本项目仓库的 logs 目录）
            binding.editOwner.setText("sicauyanglei")
            binding.editRepo.setText("AlarmCheckInLauncher")
            binding.editBranch.setText("main")
            binding.editPath.setText("logs/alarm_checkin.log")
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }
}
