package com.fastnode.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.widget.Toast
import hev.htproxy.TProxyService
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns one VPN session. Start and stop work is serialized so a fast double tap
 * cannot create overlapping Xray/HEV instances or leak a TUN descriptor.
 */
class FastVpnService : VpnService() {
    private var pfd: ParcelFileDescriptor? = null
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "V2Shizzi-VpnWorker").apply { isDaemon = true }
    }
    private val stopping = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val notifyHandler = Handler(Looper.getMainLooper())

    private val refreshNotification = object : Runnable {
        override fun run() {
            if (!AppState.vpn) return
            runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            }
            notifyHandler.postDelayed(this, NOTIFICATION_REFRESH_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())
        when (intent?.action) {
            ACTION_STOP -> {
                stopping.set(true)
                worker.execute { stopAll() }
            }
            ACTION_START, null -> {
                if (!started.get() && !stopping.get()) {
                    worker.execute {
                        if (!started.get() && !stopping.get()) startAll()
                    }
                }
            }
            else -> EventLog.add(this, "Service", "تم تجاهل أمر خدمة غير معروف")
        }
        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "V2Shizzi VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 11,
            Intent(this, FastVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("V2Shizzi VPN")
            .setContentText(SessionStats.notificationLine(this))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(openIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(AppState.vpn)
            .addAction(android.R.drawable.ic_menu_view, "فتح التطبيق", openIntent)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف VPN", stopIntent)
            .build()
    }

    private fun toast(message: String) {
        notifyHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
        }
    }

    private fun startAll() {
        if (started.get() || stopping.get()) return
        var xrayStarted = false
        var tunnelStarted = false
        var serverLabel = "غير معروف"
        try {
            if (!AppIntegrity.releaseIsIntact(this)) {
                throw SecurityException("فشل فحص سلامة التطبيق أو شهادة التوقيع")
            }
            val server = LinkParser.parse(Prefs(this).active)
                ?: throw IllegalArgumentException("الكود غير صالح أو غير مدعوم")
            serverLabel = server.name
            EventLog.add(
                this, "اتصال الكود",
                "بدء اتصال ${server.name}؛ البروتوكول=${server.outbound.optString("protocol")}"
            )

            XrayCore.start(this, ConfigBuilder.build(server))
            xrayStarted = true
            if (stopping.get()) throw InterruptedException("تم إلغاء بدء الاتصال")

            val descriptor = Builder()
                .setSession("V2Shizzi VPN")
                .setMtu(DEFAULT_MTU)
                .addAddress("26.26.26.1", 30)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("1.1.1.1")
                .addDnsServer("8.8.8.8")
                .addDisallowedApplication(packageName)
                .establish() ?: throw IllegalStateException("تعذّر إنشاء النفق؛ تحقق من إذن VPN")
            pfd = descriptor
            if (stopping.get()) throw InterruptedException("تم إلغاء بدء الاتصال")

            val configFile = File(filesDir, HEV_CONFIG_FILE)
            configFile.writeText(hevConfiguration())
            if (!TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd)) {
                throw IllegalStateException("تعذّر تشغيل محرك النفق (HEV)")
            }
            tunnelStarted = true
            if (stopping.get()) throw InterruptedException("تم إلغاء بدء الاتصال")

            SessionStats.begin(this)
            started.set(true)
            AppState.vpn = true
            EventLog.add(this, "اتصال الكود", "بدأ نفق VPN بنجاح للكود $serverLabel")
            AppState.notifyChange()
            notifyHandler.removeCallbacks(refreshNotification)
            notifyHandler.post(refreshNotification)
        } catch (error: Throwable) {
            EventLog.add(
                this, "فشل اتصال الكود",
                "فشل اتصال $serverLabel: ${error.message ?: error.javaClass.simpleName}"
            )
            if (error !is InterruptedException) {
                toast("تعذّر تشغيل VPN: ${error.message ?: "خطأ غير معروف"}")
            }
            // Roll back resources acquired by this start attempt.
            if (tunnelStarted) runCatching { TProxyService.TProxyStopService() }
            if (xrayStarted) runCatching { XrayCore.stop() }
            runCatching { pfd?.close() }
            pfd = null
            SessionStats.end(this)
            started.set(false)
            AppState.vpn = false
            AppState.sharing = false
            AppState.notifyChange()
            // A failed start must not leave an empty foreground VPN service alive.
            stopping.set(true)
            finishService()
        }
    }

    private fun hevConfiguration(): String = """
        tunnel:
          mtu: $DEFAULT_MTU
        socks5:
          port: ${ConfigBuilder.SOCKS_PORT}
          address: 127.0.0.1
          udp: 'udp'
        misc:
          task-stack-size: 81920
          tcp-buffer-size: 65536
          connect-timeout: 5000
          read-write-timeout: 60000
          log-level: warn
    """.trimIndent()

    private fun stopAll() {
        EventLog.add(this, "VPN", "إيقاف خدمة VPN ومشاركة الشبكة")
        notifyHandler.removeCallbacks(refreshNotification)
        SessionStats.end(this)
        runCatching { HotspotSharing.disable() }
            .onFailure {
                EventLog.add(this, "خطأ تنظيف المشاركة", it.message ?: it.javaClass.simpleName)
            }
        runCatching { TProxyService.TProxyStopService() }
        runCatching { XrayCore.stop() }
        runCatching { pfd?.close() }
        pfd = null

        started.set(false)
        stopping.set(false)
        AppState.vpn = false
        AppState.sharing = false
        AppState.notifyChange()
        finishService()
    }

    private fun finishService() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopping.set(true)
        worker.execute { stopAll() }
        super.onRevoke()
    }

    override fun onDestroy() {
        stopping.set(true)
        notifyHandler.removeCallbacksAndMessages(null)
        runCatching { worker.shutdown() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "com.fastnode.app.action.START_VPN"
        const val ACTION_STOP = "com.fastnode.app.action.STOP_VPN"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_REFRESH_MS = 5_000L
        private const val DEFAULT_MTU = 1500
        private const val HEV_CONFIG_FILE = "hev.yml"
    }
}
