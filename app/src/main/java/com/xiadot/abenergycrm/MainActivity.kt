package com.xiadot.abenergycrm

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val liveCrmUrl = "https://xiadot.com/AB_Energy_Solutions/"
    private val PERMISSION_REQUEST_CODE = 1001

    private val CHANNEL_ID = "ab_energy_crm_notifications"
    private val CHANNEL_NAME = "AB Energy CRM Notifications"

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (filePathCallback != null) {
            val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize Android system notification channel (Android 8.0+)
        createNotificationChannel()

        // Request runtime permissions (Telephony, Notifications, Camera, Location, Storage)
        requestAppPermissions()

        webView = findViewById(R.id.crmWebView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setGeolocationEnabled(true)

        // Enable cookies
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        // Bridge to allow web app to trigger native Android notifications, register phone, or dial
        webView.addJavascriptInterface(AndroidCallTrackerBridge(this), "AndroidCallTracker")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // Click-to-Call: launches phone dialer
                if (url.startsWith("tel:")) {
                    val dialIntent = Intent(Intent.ACTION_DIAL, url.toUri())
                    startActivity(dialIntent)
                    return true
                }

                // WhatsApp Messaging
                if (url.startsWith("https://wa.me/") || url.startsWith("whatsapp://")) {
                    val waIntent = Intent(Intent.ACTION_VIEW, url.toUri())
                    startActivity(waIntent)
                    return true
                }

                // Proposal PDF View / External Browser intent
                if ((url.contains("/api/quotations/") && url.contains("/pdf")) || url.endsWith(".pdf", ignoreCase = true)) {
                    try {
                        val pdfIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(pdfIntent)
                        return true
                    } catch (e: Exception) {
                        // Fallback to in-app webview
                    }
                }

                return false
            }
        }

        // Native Download Listener for PDF and Document downloads
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val resolvedMime = if (mimetype.isNullOrEmpty() || mimetype == "application/octet-stream") "application/pdf" else mimetype
                val guessedFileName = URLUtil.guessFileName(url, contentDisposition, resolvedMime)
                val safeFileName = if (!guessedFileName.endsWith(".pdf", ignoreCase = true) && (url.contains("/pdf") || resolvedMime.contains("pdf"))) {
                    if (guessedFileName.contains('.')) guessedFileName else "$guessedFileName.pdf"
                } else guessedFileName

                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType(resolvedMime)
                    addRequestHeader("User-Agent", userAgent)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies != null) {
                        addRequestHeader("Cookie", cookies)
                    }
                    setDescription("Downloading AB Energy document...")
                    setTitle(safeFileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading PDF...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Download error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // Grant geolocation for site survey photo geotagging
                callback?.invoke(origin, true, false)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                runOnUiThread {
                    if (ContextCompat.checkSelfPermission(this@MainActivity, android.Manifest.permission.CAMERA)
                        == PackageManager.PERMISSION_GRANTED) {
                        request?.grant(request.resources)
                    } else {
                        // Request camera permission if not yet granted
                        ActivityCompat.requestPermissions(
                            this@MainActivity,
                            arrayOf(android.Manifest.permission.CAMERA),
                            PERMISSION_REQUEST_CODE
                        )
                        // Also grant if requested
                        request?.grant(request.resources)
                    }
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                }
                filePickerLauncher.launch(intent)
                return true
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        webView.loadUrl(liveCrmUrl)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Real-time alerts for lead assignments, quotations, payments, and site surveys"
                enableLights(true)
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun triggerSystemNotification(title: String, message: String) {
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 100000).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            )

            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setVibrate(longArrayOf(0, 250, 150, 250))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setContentIntent(pendingIntent)

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify((System.currentTimeMillis() % 100000).toInt(), builder.build())
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Failed to trigger system notification: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) {
            webView.onResume()
            // Dispatch focus event to WebView so in-app call listeners react immediately
            webView.evaluateJavascript("if (typeof window !== 'undefined') { window.dispatchEvent(new Event('focus')); }", null)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::webView.isInitialized) {
            webView.onPause()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.CAMERA,
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    inner class AndroidCallTrackerBridge(private val context: Context) {
        @JavascriptInterface
        fun showNotification(title: String, message: String) {
            runOnUiThread {
                triggerSystemNotification(title, message)
            }
        }

        @JavascriptInterface
        fun setEmployeePhone(phone: String) {
            val cleanPhone = phone.replace("+91", "").replace(" ", "").replace("-", "").trim()
            val prefs = context.getSharedPreferences("ab_energy_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("employee_phone", cleanPhone).apply()
            android.util.Log.i("MainActivity", "Registered active employee phone on device: $cleanPhone")
        }

        @JavascriptInterface
        fun dialNumber(phoneNumber: String, customerName: String) {
            val dialIntent = Intent(Intent.ACTION_DIAL, "tel:$phoneNumber".toUri())
            dialIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(dialIntent)
        }

        @JavascriptInterface
        fun syncNow() {
            // Compliant no-op for Google Play: dialing uses standard ACTION_DIAL intent
        }

        @JavascriptInterface
        fun downloadFile(url: String, filename: String) {
            try {
                val safeFileName = if (filename.endsWith(".pdf", ignoreCase = true)) filename else "$filename.pdf"
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setMimeType("application/pdf")
                    addRequestHeader("User-Agent", webView.settings.userAgentString)
                    val cookies = CookieManager.getInstance().getCookie(url)
                    if (cookies != null) {
                        addRequestHeader("Cookie", cookies)
                    }
                    setDescription("Downloading quotation...")
                    setTitle(safeFileName)
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName)
                }
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                (context as? AppCompatActivity)?.runOnUiThread {
                    Toast.makeText(context, "Downloading PDF...", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    (context as? AppCompatActivity)?.runOnUiThread {
                        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
