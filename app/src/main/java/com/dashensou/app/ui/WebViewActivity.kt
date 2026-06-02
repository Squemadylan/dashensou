package com.dashensou.app.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.dashensou.app.R
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.databinding.ActivityWebviewBinding
import com.dashensou.app.service.DownloadManager
import com.dashensou.app.util.NetDiskUtils

class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewActivity"
        const val EXTRA_GOTO_URL = "extra_goto_url"
        const val EXTRA_NET_DISK_TYPE = "extra_net_disk_type"
        const val EXTRA_PASSWORD = "extra_password"

        private val NET_DISK_DOMAINS = listOf(
            "pan.baidu.com",
            "yun.baidu.com",
            "pan.quark.cn",
            "drive.quark.cn",
            "pan.xunlei.com",
            "aliyundrive.com",
            "alipan.com",
            "123pan.com",
            "123684.com"
        )
    }

    private lateinit var binding: ActivityWebviewBinding
    private lateinit var netDiskType: NetDiskType
    private var password: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val gotoUrl = intent.getStringExtra(EXTRA_GOTO_URL)
        netDiskType = intent.getStringExtra(EXTRA_NET_DISK_TYPE)
            ?.let { runCatching { NetDiskType.valueOf(it) }.getOrNull() }
            ?: NetDiskType.OTHER
        password = intent.getStringExtra(EXTRA_PASSWORD)

        if (gotoUrl.isNullOrBlank()) {
            Toast.makeText(this, "跳转链接为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvTitle.text = getString(R.string.webview_title)
        binding.tvNetDiskType.text = getString(R.string.netdisk_type_label) + ": " + NetDiskUtils.getNetDiskTypeName(netDiskType)
        binding.tvPassword.text = if (password.isNullOrBlank()) {
            getString(R.string.no_password)
        } else {
            getString(R.string.password_label) + ": " + password
        }
        binding.tvPassword.visibility = if (password.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.btnCopyPassword.setOnClickListener { copyPassword() }
        binding.btnOpenApp.setOnClickListener { openNetDiskAppDirectly() }
        binding.btnClose.setOnClickListener { finish() }

        binding.webview.settings.javaScriptEnabled = true
        binding.webview.settings.domStorageEnabled = true
        binding.webview.settings.userAgentString = binding.webview.settings.userAgentString + " DaShenSou/1.0"
        binding.webview.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                Log.i(TAG, "shouldOverride: $url")
                if (isNetDiskUrl(url)) {
                    Log.i(TAG, "matched netdisk domain, launching app")
                    launchNetDiskApp(url)
                    return true
                }
                return false
            }
        }
        binding.webview.loadUrl(gotoUrl)
    }

    private fun isNetDiskUrl(url: String): Boolean {
        return NET_DISK_DOMAINS.any { url.contains(it, ignoreCase = true) }
    }

    private fun launchNetDiskApp(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, getString(R.string.netdisk_opened), Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "no app handles $url, falling back to chooser", e)
            try {
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                    getString(R.string.choose_netdisk_app)
                )
                chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(chooser)
                finish()
            } catch (e2: Exception) {
                Log.e(TAG, "no app at all", e2)
                Toast.makeText(this, getString(R.string.netdisk_not_installed), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openNetDiskAppDirectly() {
        val packageName = NetDiskUtils.getNetDiskPackageName(netDiskType)
        if (packageName != null) {
            try {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    Toast.makeText(this, getString(R.string.netdisk_opened), Toast.LENGTH_SHORT).show()
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "direct launch failed: $packageName", e)
            }
        }
        Toast.makeText(this, getString(R.string.netdisk_not_installed), Toast.LENGTH_SHORT).show()
    }

    private fun copyPassword() {
        if (password.isNullOrBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("netdisk_password", password))
        Toast.makeText(this, getString(R.string.password_copied), Toast.LENGTH_SHORT).show()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (binding.webview.canGoBack()) {
            binding.webview.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
