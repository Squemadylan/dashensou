package com.dashensou.app.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.dashensou.app.R
import com.dashensou.app.data.model.NetDiskType
import com.dashensou.app.databinding.ActivityWebviewBinding
import com.dashensou.app.service.DownloadManager
import com.dashensou.app.util.DiskLabels
import com.dashensou.app.util.NetDiskUtils

class WebViewActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebViewActivity"
        private const val SLOW_PAGE_LOAD_MS = 10_000L
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
    private var gotoUrl: String = ""

    /**
     * Slow-page detection. We arm a delayed message in onPageStarted and
     * cancel it in onPageFinished. If the page never finishes, after
     * SLOW_PAGE_LOAD_MS the user gets a "switch to browser / copy link"
     * dialog instead of staring at a blank WebView (P0#6.5). The
     * sentinel-tag check makes sure we only show the dialog once and
     * never after a successful page load.
     */
    private val slowLoadHandler = Handler(Looper.getMainLooper())
    private var slowLoadArmed = false
    private val slowLoadRunnable = Runnable {
        if (slowLoadArmed && !isFinishing && !isDestroyed) {
            slowLoadArmed = false
            showSlowPageDialog()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWebviewBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupBackHandler()

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
        this.gotoUrl = gotoUrl

        // P1#12: chip + MaterialToolbar instead of the old hand-rolled
        // Toolbar and the two plain TextViews. The chip text follows the
        // existing string format so users still see "网盘类型: 百度网盘"
        // semantically; we just render it as a chip.
        binding.toolbar.title = getString(R.string.webview_title)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.chipNetdiskType.text =
            getString(R.string.netdisk_type_label) + ": " + DiskLabels.long(netDiskType)
        if (password.isNullOrBlank()) {
            binding.chipPassword.visibility = View.GONE
        } else {
            binding.chipPassword.visibility = View.VISIBLE
            binding.chipPassword.text = getString(R.string.password_label) + ": " + password
        }
        binding.btnCopyPassword.setOnClickListener { copyPassword() }
        binding.btnOpenApp.setOnClickListener { openNetDiskAppDirectly() }

        binding.webview.settings.javaScriptEnabled = true
        binding.webview.settings.domStorageEnabled = true
        binding.webview.settings.userAgentString = binding.webview.settings.userAgentString + " DaShenSou/1.0"
        binding.webview.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Arm the slow-page watchdog for every new navigation. A
                // single load can fire onPageStarted multiple times (302
                // chains, hash-only updates) so we cancel any pending
                // callback before re-arming.
                slowLoadArmed = true
                slowLoadHandler.removeCallbacks(slowLoadRunnable)
                slowLoadHandler.postDelayed(slowLoadRunnable, SLOW_PAGE_LOAD_MS)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                slowLoadArmed = false
                slowLoadHandler.removeCallbacks(slowLoadRunnable)
            }

            // P1#quality: prefer the modern WebResourceRequest signature
            // (added in API 23) which carries the failing URL on the
            // request object itself. The legacy 4-arg overload is kept
            // suppressed so old main-frame errors on lower API levels
            // still trip our watchdog cancellation.
            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: android.webkit.WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                slowLoadArmed = false
                slowLoadHandler.removeCallbacks(slowLoadRunnable)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onReceivedError(
                view: WebView?,
                errorCode: Int,
                description: String?,
                failingUrl: String?
            ) {
                super.onReceivedError(view, errorCode, description, failingUrl)
                slowLoadArmed = false
                slowLoadHandler.removeCallbacks(slowLoadRunnable)
            }

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

    /**
     * Shown when the goto page hasn't finished loading after
     * SLOW_PAGE_LOAD_MS. Most of the time the user is better off in the
     * system browser (the page is a net-disk landing page with a JS-driven
     * "open in app" button that we just emulate) or at minimum copying
     * the URL into a chat with a friend.
     */
    private fun showSlowPageDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.slow_page_title)
            .setMessage(R.string.slow_page_message)
            .setPositiveButton(R.string.open_in_browser) { _, _ ->
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(gotoUrl))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                    finish()
                } catch (e: Exception) {
                    Log.w(TAG, "openInBrowser failed: $gotoUrl", e)
                    Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.copy_link) { _, _ ->
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("netdisk_goto_url", gotoUrl))
                Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.cancel, null)
            .show()
    }

    private fun isNetDiskUrl(url: String): Boolean {
        return NET_DISK_DOMAINS.any { url.contains(it, ignoreCase = true) }
    }

    private fun launchNetDiskApp(url: String) {
        val finalUrl = NetDiskUtils.appendExtractionCode(url, netDiskType, password)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Toast.makeText(this, getString(R.string.netdisk_opened), Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "no app handles $url, falling back to chooser", e)
            try {
                val chooser = Intent.createChooser(
                    Intent(Intent.ACTION_VIEW, Uri.parse(finalUrl)),
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

    /**
     * P1#quality: register a back-press handler that pops the WebView
     * stack when possible, and otherwise lets the system handle it
     * (i.e. finish this activity). Replaces the deprecated
     * onBackPressed override and integrates with predictive-back /
     * OnBackPressedDispatcher as the framework expects.
     */
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.webview.canGoBack()) {
                    binding.webview.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * Tear down the WebView. WebView is one of the most common Android
     * memory leaks: if the host Activity is destroyed without calling
     * stopLoading + removeAllViews + destroy, the WebView keeps its
     * reference and pins the entire Activity. P1#15.
     */
    override fun onDestroy() {
        slowLoadArmed = false
        slowLoadHandler.removeCallbacks(slowLoadRunnable)
        binding.webview.stopLoading()
        binding.webview.loadUrl("about:blank")
        binding.webview.removeAllViews()
        binding.webview.destroy()
        super.onDestroy()
    }
}
