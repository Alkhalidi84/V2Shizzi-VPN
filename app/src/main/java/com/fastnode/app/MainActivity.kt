package com.fastnode.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var logo: LogoView
    private lateinit var chip: TextView
    private lateinit var b1: android.widget.Button
    private lateinit var b2: android.widget.Button
    private lateinit var status: TextView
    private lateinit var sessionInfo: TextView

    private val listener: () -> Unit = {
        // The service publishes every completed start/stop attempt through AppState.
        vpnRequestPending.set(false)
        refresh()
    }
    private val statsHandler = Handler(Looper.getMainLooper())
    private val sharingWorker = Executors.newSingleThreadExecutor()
    private val vpnRequestPending = AtomicBoolean(false)
    private val sharingRequestPending = AtomicBoolean(false)
    @Volatile private var isResumed = false

    private val statsTicker = object : Runnable {
        override fun run() {
            updateSessionInfo()
            statsHandler.postDelayed(this, 1_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            setPadding(dp(20), dp(36), dp(20), dp(16))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        chip = label("", 14f, MUTED).apply {
            background = roundBg(Color.parseColor("#12101A"), dp(18), dp(1), Color.parseColor("#2A2733"))
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val spacer = View(this)
        val gear = label("⚙", 30f, Color.parseColor("#B7A4D4")).apply {
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }
        top.addView(chip)
        top.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(gear)
        root.addView(top, LinearLayout.LayoutParams(-1, -2))

        logo = LogoView(this)
        root.addView(logo, LinearLayout.LayoutParams(-1, 0, 1f))

        b1 = btn("", MAGENTA, Color.BLACK) { onVpnClick() }
        b2 = btn("", VIOLET, Color.WHITE) { onShareClick() }
        root.addView(b1, LinearLayout.LayoutParams(-1, dp(64)))
        root.addView(b2, LinearLayout.LayoutParams(-1, dp(64)).apply { topMargin = dp(14) })

        sessionInfo = label("", 11f, MUTED).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        }
        root.addView(sessionInfo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        status = label("", 12f, Color.parseColor("#4A4458"), true).apply {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            letterSpacing = 0.12f
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        isResumed = true
        AppState.listen(listener)
        refresh()
        statsHandler.removeCallbacks(statsTicker)
        statsHandler.post(statsTicker)

        UpdateChecker.maybeCheck(this) { update ->
            runOnUiThread {
                if (isResumed && !isFinishing && !isDestroyed) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("يتوفر تحديث ${update.versionName}")
                        .setMessage(update.notes)
                        .setNegativeButton("لاحقاً", null)
                        .setPositiveButton("عرض التحديث") { _, _ ->
                            startActivity(Intent(this, UpdateActivity::class.java))
                        }
                        .show()
                }
            }
        }
    }

    override fun onPause() {
        isResumed = false
        super.onPause()
        AppState.unlisten(listener)
        statsHandler.removeCallbacks(statsTicker)
    }

    override fun onDestroy() {
        statsHandler.removeCallbacksAndMessages(null)
        sharingWorker.shutdown()
        super.onDestroy()
    }

    private fun refresh() {
        val server = LinkParser.parse(prefs.active)
        chip.text = server?.name ?: "لا يوجد كود"
        b1.text = if (AppState.vpn) "إيقاف VPN" else "تشغيل VPN"
        b1.isEnabled = !vpnRequestPending.get()
        b2.text = when {
            sharingRequestPending.get() -> "جارٍ تنفيذ طلب المشاركة…"
            AppState.sharing -> "إيقاف مشاركة الشبكة"
            else -> "مشاركة الشبكة"
        }
        b2.isEnabled = !sharingRequestPending.get() && AppState.vpn
        logo.active = AppState.vpn
        val currentStatus = when {
            sharingRequestPending.get() -> "SHARING REQUEST"
            AppState.sharing -> "SHARING ACTIVE"
            vpnRequestPending.get() -> "VPN REQUEST"
            AppState.vpn -> "VPN ACTIVE"
            else -> "DISCONNECTED"
        }
        status.text = "V2SHIZZI  |  STATUS $currentStatus"
        updateSessionInfo()
    }

    private fun updateSessionInfo() {
        if (!::sessionInfo.isInitialized) return
        val summary = SessionStats.display(this)
        sessionInfo.text = summary
        sessionInfo.visibility = if (summary.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun toast(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun onVpnClick() {
        if (!vpnRequestPending.compareAndSet(false, true)) return

        if (AppState.vpn) {
            EventLog.add(this, "VPN", "طلب المستخدم إيقاف VPN")
            startService(Intent(this, FastVpnService::class.java).setAction(FastVpnService.ACTION_STOP))
            vpnRequestPending.set(false)
            refresh()
            return
        }

        if (LinkParser.parse(prefs.active) == null) {
            vpnRequestPending.set(false)
            EventLog.add(this, "VPN", "تعذر البدء: لا يوجد كود صالح مختار")
            toast("أضف كود V2Ray صالحاً من الإعدادات أولاً")
            startActivity(Intent(this, SettingsActivity::class.java))
            refresh()
            return
        }

        try {
            val permissionIntent = VpnService.prepare(this)
            if (permissionIntent != null) {
                startActivityForResult(permissionIntent, REQUEST_VPN_PERMISSION)
            } else {
                startVpn()
            }
        } catch (error: Throwable) {
            vpnRequestPending.set(false)
            EventLog.add(this, "VPN", "فشل طلب إذن VPN: ${error.message ?: error.javaClass.simpleName}")
            toast("تعذر طلب إذن VPN")
            refresh()
        }
    }

    private fun startVpn() {
        try {
            EventLog.add(this, "VPN", "إرسال طلب تشغيل خدمة VPN")
            val intent = Intent(this, FastVpnService::class.java).setAction(FastVpnService.ACTION_START)
            startForegroundService(intent)
        } catch (error: Throwable) {
            vpnRequestPending.set(false)
            EventLog.add(this, "VPN", "فشل تشغيل خدمة VPN: ${error.message ?: error.javaClass.simpleName}")
            toast("تعذر تشغيل خدمة VPN: ${error.message ?: "خطأ غير معروف"}")
            refresh()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_VPN_PERMISSION -> {
                if (resultCode == RESULT_OK) {
                    startVpn()
                } else {
                    vpnRequestPending.set(false)
                    EventLog.add(this, "VPN", "رفض المستخدم إذن VPN")
                    toast("لم يتم منح إذن VPN")
                    refresh()
                }
            }
            REQUEST_HOTSPOT_SETTINGS -> {
                if (AppState.vpn && !AppState.sharing) checkHotspotAndShare(openSettingsIfOff = false)
            }
        }
    }

    private fun onShareClick() {
        if (!AppState.vpn || !sharingRequestPending.compareAndSet(false, true)) return
        refresh()
        sharingWorker.execute {
            try {
                if (AppState.sharing) {
                    EventLog.add(this, "مشاركة الإنترنت", "طلب المستخدم إيقاف المشاركة")
                    HotspotSharing.disable()
                    AppState.sharing = false
                    EventLog.add(this, "مشاركة الإنترنت", "اكتمل طلب تنظيف قواعد المشاركة")
                } else {
                    EventLog.add(this, "مشاركة الإنترنت", "بدأ فحص واجهة الهوتسبوت والصلاحيات")
                    val (mode, interfaces) = HotspotSharing.detectForSharing(applicationContext)
                    if (mode == null) {
                        throw IllegalStateException("فعّل Root أو شغّل Shizuku وامنح التطبيق صلاحية shell")
                    }
                    if (interfaces.isEmpty()) {
                        runOnUiThread {
                            if (isResumed) openHotspotSettings()
                            else toast("شغّل نقطة الاتصال ثم أعد المحاولة")
                        }
                        return@execute
                    }

                    val error = HotspotSharing.enable(applicationContext)
                    if (error != null) throw IllegalStateException(error)
                    AppState.sharing = true
                    EventLog.add(
                        this, "مشاركة الإنترنت",
                        "بدأت المشاركة عبر $mode؛ الواجهات=${interfaces.joinToString()}"
                    )
                }
            } catch (error: Throwable) {
                EventLog.add(
                    this, "خطأ مشاركة",
                    error.message ?: error.javaClass.simpleName
                )
                AppState.sharing = false
                toast("تعذّر تنفيذ مشاركة الشبكة: ${error.message ?: "خطأ غير معروف"}")
            } finally {
                sharingRequestPending.set(false)
                AppState.notifyChange()
                runOnUiThread { refresh() }
            }
        }
    }

    private fun checkHotspotAndShare(openSettingsIfOff: Boolean) {
        if (!sharingRequestPending.compareAndSet(false, true)) return
        refresh()
        sharingWorker.execute {
            try {
                val (mode, interfaces) = HotspotSharing.detectForSharing(applicationContext)
                if (mode == null) throw IllegalStateException("لا توجد صلاحية Root أو Shizuku")
                if (interfaces.isEmpty()) {
                    runOnUiThread {
                        if (openSettingsIfOff && isResumed) openHotspotSettings()
                        else toast("لم يتم اكتشاف واجهة Hotspot نشطة")
                    }
                    return@execute
                }
                val error = HotspotSharing.enable(applicationContext)
                if (error != null) throw IllegalStateException(error)
                AppState.sharing = true
                EventLog.add(this, "مشاركة الإنترنت", "تم إعداد المشاركة عبر $mode")
            } catch (error: Throwable) {
                AppState.sharing = false
                EventLog.add(this, "خطأ مشاركة", error.message ?: error.javaClass.simpleName)
                toast("تعذّر تشغيل المشاركة: ${error.message ?: "خطأ غير معروف"}")
            } finally {
                sharingRequestPending.set(false)
                AppState.notifyChange()
                runOnUiThread { refresh() }
            }
        }
    }

    private fun openHotspotSettings() {
        toast("فعّل نقطة اتصال Wi‑Fi من إعدادات النظام ثم ارجع إلى التطبيق")
        val actions = listOf(
            "android.settings.TETHER_SETTINGS",
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_WIFI_SETTINGS
        )
        for (action in actions) {
            try {
                startActivityForResult(Intent(action), REQUEST_HOTSPOT_SETTINGS)
                return
            } catch (_: Exception) {
                // Try the next system settings entry point.
            }
        }
        toast("تعذر فتح إعدادات الهوتسبوت تلقائياً")
    }

    companion object {
        private const val REQUEST_NOTIFICATIONS = 5
        private const val REQUEST_VPN_PERMISSION = 1
        private const val REQUEST_HOTSPOT_SETTINGS = 2
    }
}
