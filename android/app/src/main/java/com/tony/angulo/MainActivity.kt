package com.tony.angulo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.*
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewFeature

/* Envuelve la app web (carpeta assets/www) en una app de Android.
   Se sirve desde https://appassets.androidplatform.net para que Chrome permita los sensores. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private var geoOrigin: String? = null
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var micRequest: PermissionRequest? = null
    private val FULL = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val pre = "https://appassets.androidplatform.net/"
        fun intercept(req: WebResourceRequest): WebResourceResponse? {
            val u = req.url
            if (u.host != "appassets.androidplatform.net") return null
            // las rutas de la web van dentro de assets/www
            val path = u.path?.trimStart('/') ?: ""
            return loader.shouldInterceptRequest(android.net.Uri.parse(pre + "www/" + (if (path.isEmpty() || path.endsWith("/")) path + "index.html" else path)))
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) {
            ServiceWorkerControllerCompat.getInstance().setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = intercept(request)
            })
        }
        web = WebView(this)
        setContentView(web)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setGeolocationEnabled(true)
            allowFileAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.addJavascriptInterface(Puente(), "AndroidApp")
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = intercept(request)
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, true)
                } else {
                    geoOrigin = origin; geoCallback = callback
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 7)
                }
            }
            // micrófono para el silbido: pide el permiso de Android y luego se lo da a la web
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val wantsMic = request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                    if (!wantsMic || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        micRequest = request
                        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 8)
                    }
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)
        if (savedInstanceState == null) web.loadUrl(pre + "index.html") else web.restoreState(savedInstanceState)
        if (FULL) inmersivo()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == 8) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            micRequest?.let { if (ok) it.grant(it.resources) else it.deny() }; micRequest = null
        }
        if (requestCode == 7) {
            val ok = grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
            geoCallback?.invoke(geoOrigin, ok, ok); geoCallback = null
        }
    }

    private fun inmersivo() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) { super.onWindowFocusChanged(hasFocus); if (hasFocus && FULL) inmersivo() }
    override fun onSaveInstanceState(outState: Bundle) { super.onSaveInstanceState(outState); web.saveState(outState) }
    override fun onPause() { super.onPause(); web.onPause() }
    override fun onResume() { super.onResume(); web.onResume() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else super.onBackPressed() }

    // Los botones del mando Bluetooth llegan a la web como Gamepad; no dejamos que «atrás» cierre la app por error
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_B) return web.dispatchKeyEvent(event)
        return super.dispatchKeyEvent(event)
    }

    inner class Puente {
        @JavascriptInterface
        fun compartir(texto: String) {
            runOnUiThread {
                val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, texto) }
                startActivity(Intent.createChooser(i, "Compartir"))
            }
        }
    }
}
