package com.mykerd.panic

import android.app.*
import android.content.*
import android.hardware.Camera
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import android.location.LocationManager
import android.location.Location
import java.io.File
import android.annotation.SuppressLint
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import java.lang.ref.WeakReference
import android.content.Intent
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.SurfaceTexture
import android.location.LocationListener
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class PanicService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var camera: Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var locationManager: LocationManager
    private var currentFile: File? = null
    private var pendingNextFile: File? = null
    private var isRunning = false
    private var activeIp: String = ""
    private var activePort: String = ""
    private var activeRotation: Int = 20
    private var activeUseFront: Boolean = false
    private var lastLocation: Location? = null
    private var activeHiddenMode: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var dummySurfaceTexture: SurfaceTexture? = null
    private lateinit var audioManager: AudioManager
    private var originalSystemVolume: Int = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var sessionStartTime = 0L
    private val SESSION_CAP_MS = 12 * 60 * 60 * 1000L
    private val STEALTH_COUNTDOWN_MS = 30 * 60 * 1000L
    private var chosenWidth = 640
    private var chosenHeight = 480
    private var clockTamperReceiver: BroadcastReceiver? = null
    private enum class StealthState {
        NORMAL, PENDING_ACTIVATION, CONFIRMING_ACTIVATION, ACTIVE
    }
    private val secureStealthPrefs: SharedPreferences by lazy {
        SecureConfig.getStealthPrefs(applicationContext)
    }
    @Volatile
    private var _stealthState: StealthState = StealthState.NORMAL
    private var stealthState: StealthState
        get() = _stealthState
        set(value) {
            _stealthState = value
            backgroundHandler.post {
                try {
                    secureStealthPrefs.edit().putString("stealth_state", value.name).apply()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving stealth state: ${e.message}")
                }
            }
        }
    private val ACTION_START_STEALTH_COUNTDOWN = "com.mykerd.panic.ACTION_START_STEALTH_COUNTDOWN"
    private val ACTION_CANCEL_STEALTH_ACTIVATION = "com.mykerd.panic.ACTION_CANCEL_STEALTH_ACTIVATION"
    private val ACTION_ALARM_TRIGGER = "com.mykerd.panic.ACTION_ALARM_TRIGGER"
    private val ACTION_CONFIRM_STEALTH = "com.mykerd.panic.ACTION_CONFIRM_STEALTH"
    private var alarmRingtone: Ringtone? = null
    private var originalAlarmVolume: Int = 0
    private var originalRingerMode: Int = AudioManager.RINGER_MODE_NORMAL
    private var isGaplessSupported = true
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        Log.w(TAG, "WATCHDOG: Gapless rotation failed. Forcing fallback.")
        isGaplessSupported = false
        rotateProcess()
    }
    private val securePrefs: SharedPreferences by lazy {
        SecureConfig.getPrefs(this)
    }
    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastLocation = location
            Log.d(TAG, "New Location: ${location.latitude}, ${location.longitude}")
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }
    private val TAG = "zMPanicCore"
    companion object {
        private var previewHolder: SurfaceHolder? = null
        private var visibleTexture: SurfaceTexture? = null
        private var serviceRef: WeakReference<PanicService>? = null
        fun setPreviewHolder(holder: SurfaceHolder?) { previewHolder = holder }
        fun setVisibleSurfaceTexture(st: SurfaceTexture?) {
            visibleTexture = st
            serviceRef?.get()?.updatePreviewTarget()
        }
    }
    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        backgroundThread = HandlerThread("PanicServiceBackground").apply { start() }
        backgroundHandler = Handler(backgroundThread.looper)
        backgroundHandler.post {
            try {
                SecureConfig.getPrefs(applicationContext)
                val sPrefs = SecureConfig.getStealthPrefs(applicationContext)
                val stateStr = sPrefs.getString("stealth_state", StealthState.NORMAL.name)
                _stealthState = try { StealthState.valueOf(stateStr ?: StealthState.NORMAL.name) } catch (e: Exception) { StealthState.NORMAL }
                val appPrefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
                activeHiddenMode = appPrefs.getBoolean("hidden_mode", false)
                Log.d(TAG, "SecureConfig pre-warmed, state: $_stealthState, hidden: $activeHiddenMode")
                reconcileStealthState()
            } catch (e: Exception) {
                Log.e(TAG, "SecureConfig pre-warm or reconcile failed: ${e.message}")
            }
        }
        try {
            val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zMPanic:WifiLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
        } catch (e: Exception) { Log.e(TAG, "WifiLock failed: ${e.message}") }
        try {
            val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    try { connectivityManager.bindProcessToNetwork(network) } catch (e: Exception) {}
                    Log.d(TAG, "Network bound: $network")
                }
                override fun onLost(network: Network) {
                    super.onLost(network)
                    try { connectivityManager.bindProcessToNetwork(null) } catch (e: Exception) {}
                    if (isRunning) handler.postDelayed({ 
                        try { connectivityManager.requestNetwork(request, this) } catch (e: Exception) {} 
                    }, 5000L)
                }
            }
            connectivityManager.requestNetwork(request, networkCallback!!)
        } catch (e: Exception) { Log.e(TAG, "Network Callback Error: ${e.message}") }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            if (it.hasExtra("EXTRA_HIDDEN")) {
                activeHiddenMode = it.getBooleanExtra("EXTRA_HIDDEN", activeHiddenMode)
            }
        }
        val isPossiblyBackgroundStart = intent == null || intent.action == null || intent.action == ACTION_ALARM_TRIGGER
        setupForeground(!isPossiblyBackgroundStart)
        backgroundHandler.post {
            handleStartCommand(intent)
        }
        return START_STICKY
    }
    private fun handleStartCommand(intent: Intent?) {
        val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
        if (intent == null || !intent.hasExtra("EXTRA_HIDDEN")) {
            activeHiddenMode = prefs.getBoolean("hidden_mode", activeHiddenMode)
        }
        when (intent?.action) {
            ACTION_START_STEALTH_COUNTDOWN -> {
                startStealthCountdown()
                return
            }
            ACTION_CANCEL_STEALTH_ACTIVATION -> {
                cancelStealthActivation()
                return
            }
            ACTION_ALARM_TRIGGER -> {
                triggerAudibleAlarm()
                return
            }
            ACTION_CONFIRM_STEALTH -> {
                if (stealthState == StealthState.CONFIRMING_ACTIVATION) {
                    activateStealthMode()
                }
                return
            }
        }
        intent?.let {
            activeIp = it.getStringExtra("EXTRA_IP") ?: ""
            activePort = it.getStringExtra("EXTRA_PORT") ?: "9999"
            activeRotation = it.getIntExtra("EXTRA_ROTATION", 20)
            activeUseFront = it.getBooleanExtra("EXTRA_FRONT", false)
            silenceDevice(true)
        }
        if (activeIp.isEmpty()) {
            activeIp = prefs.getString("server_ip", "") ?: ""
            activePort = prefs.getString("server_port", "9999") ?: "9999"
            activeRotation = prefs.getInt("rotation_seconds", 20)
            activeUseFront = prefs.getBoolean("use_front_cam", false)
        }
        if (isRunning) {
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_service_running)) else Log.d(TAG, "Service already running")
            updatePreviewTarget()
            checkSessionCap()
            return
        }
        isRunning = true
        sessionStartTime = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            isGaplessSupported = false
        }
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zMPanic:SOS_Wakelock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(12 * 60 * 60 * 1000L)
        startLocationUpdates()
        startRecordingFlow()
        if (activeHiddenMode) monitorCredentials()
        if (stealthState == StealthState.PENDING_ACTIVATION) {
            resumeStealthCountdown()
        }
    }
    @SuppressLint("ScheduleExactAlarm")
    private fun startStealthCountdown() {
        stealthState = StealthState.PENDING_ACTIVATION
        val elapsedStart = SystemClock.elapsedRealtime()
        val wallStart = System.currentTimeMillis()
        secureStealthPrefs.edit()
            .putLong("activation_start_time", wallStart)
            .putLong("activation_start_elapsed", elapsedStart)
            .apply()
        registerClockTamperReceiver()
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, PanicService::class.java).apply { action = ACTION_ALARM_TRIGGER }
        val pi = PendingIntent.getService(this, 100, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val triggerAt = elapsedStart + STEALTH_COUNTDOWN_MS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in startStealthCountdown", e)
            am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }
    @SuppressLint("ScheduleExactAlarm")
    private fun resumeStealthCountdown() {
        val wallStart = secureStealthPrefs.getLong("activation_start_time", 0L)
        val elapsedStart = secureStealthPrefs.getLong("activation_start_elapsed", 0L)
        if (wallStart == 0L) return
        if (System.currentTimeMillis() < wallStart) {
            Log.w(TAG, "Clock moved backwards! Tampering detected.")
            cancelStealthActivation()
            return
        }
        val elapsedNow = SystemClock.elapsedRealtime()
        val remaining = if (elapsedNow >= elapsedStart && elapsedStart > 0L) {
            (elapsedStart + STEALTH_COUNTDOWN_MS) - elapsedNow
        } else {
            (wallStart + STEALTH_COUNTDOWN_MS) - System.currentTimeMillis()
        }
        if (remaining <= 0) {
            triggerAudibleAlarm()
        } else {
            registerClockTamperReceiver()
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, PanicService::class.java).apply { action = ACTION_ALARM_TRIGGER }
            val pi = PendingIntent.getService(this, 100, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val triggerAt = elapsedNow + remaining

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in resumeStealthCountdown", e)
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }
    }
    private fun registerClockTamperReceiver() {
        if (clockTamperReceiver != null) return
        try {
            val tamperFilter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            }
            clockTamperReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (stealthState == StealthState.PENDING_ACTIVATION) {
                        Log.w(TAG, "Clock Tampering Detected! Activation Block!")
                        cancelStealthActivation()
                    }
                }
            }
            registerReceiver(clockTamperReceiver, tamperFilter)
        } catch (e: Exception) {
            Log.e(TAG, "Tamper receiver registration error: ${e.message}")
        }
    }
    private fun unregisterClockTamperReceiver() {
        try {
            clockTamperReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {}
        clockTamperReceiver = null
    }
    private fun cancelStealthActivation() {
        stealthState = StealthState.NORMAL
        unregisterClockTamperReceiver()
        secureStealthPrefs.edit()
            .putLong("activation_start_time", 0L)
            .putLong("activation_start_elapsed", 0L)
            .apply()
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val triggerIntent = Intent(this, PanicService::class.java).apply { action = ACTION_ALARM_TRIGGER }
        val triggerPI = PendingIntent.getService(this, 100, triggerIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(triggerPI)
        val confirmIntent = Intent(this, PanicService::class.java).apply { action = ACTION_CONFIRM_STEALTH }
        val confirmPI = PendingIntent.getService(this, 101, confirmIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.cancel(confirmPI)
        stopAudibleAlarm()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1002)
        Log.i(TAG, getString(R.string.log_stealth_cancelled))
    }
    private fun triggerAudibleAlarm() {
        unregisterClockTamperReceiver()
        stealthState = StealthState.CONFIRMING_ACTIVATION
        try {
            originalAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            originalRingerMode = audioManager.ringerMode
            
            if (originalRingerMode != AudioManager.RINGER_MODE_NORMAL) {
                try { audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (e: Exception) {}
            }
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error adjusting alarm volume", e)
        }
        handler.post {
            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            alarmRingtone = RingtoneManager.getRingtone(this, alarmUri)
            alarmRingtone?.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            alarmRingtone?.play()
            handler.postDelayed({ backgroundHandler.post { stopAudibleAlarm() } }, 15000)
            showConfirmationNotification()
            val confirmIntent = Intent(this, PanicService::class.java).apply { action = ACTION_CONFIRM_STEALTH }
            val pi = PendingIntent.getService(this, 101, confirmIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val triggerAt = SystemClock.elapsedRealtime() + 30 * 1000L
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                } else {
                    am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException in triggerAudibleAlarm", e)
                am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 30 * 1000L, pi)
            }
        }
    }
    private fun stopAudibleAlarm() {
        try {
            alarmRingtone?.let {
                if (it.isPlaying) it.stop()
            }
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalAlarmVolume, 0)
            if (audioManager.ringerMode != originalRingerMode) {
                try { audioManager.ringerMode = originalRingerMode } catch (e: Exception) {}
            }
        } catch (e: Exception) { Log.e(TAG, "Error in stopAudibleAlarm", e) }
    }
    private fun showConfirmationNotification() {
        val chanId = getString(R.string.notification_channel_id_confirmation)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, getString(R.string.notification_channel_confirmation_name), NotificationManager.IMPORTANCE_HIGH).apply {
                description = getString(R.string.notification_channel_confirmation_desc)
                setBypassDnd(true)
                enableVibration(true)
                enableLights(true)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
        val cancelIntent = Intent(this, PanicService::class.java).apply { action = ACTION_CANCEL_STEALTH_ACTIVATION }
        val cancelPI = PendingIntent.getService(this, 102, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val fullScreenIntent = Intent(this, MainActivity::class.java)
        val fullScreenPI = PendingIntent.getActivity(this, 0, fullScreenIntent, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, chanId)
            .setContentTitle(getString(R.string.notification_stealth_warning_title))
            .setContentText(getString(R.string.notification_stealth_warning_text))
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fullScreenPI, true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.btn_cancel_hidden_mode), cancelPI)
            .build()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1002, n)
    }
    private fun activateStealthMode() {
        stealthState = StealthState.ACTIVE
        val appPrefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
        appPrefs.edit().putBoolean("hidden_mode", true).apply()
        activeHiddenMode = true
        handleAliasInternal(true)
        activeIp = appPrefs.getString("server_ip", activeIp) ?: ""
        activePort = appPrefs.getString("server_port", activePort) ?: "9999"
        activeRotation = appPrefs.getInt("rotation_seconds", activeRotation)
        activeUseFront = appPrefs.getBoolean("use_front_cam", activeUseFront)
        handler.post {
            setupForeground(true)
            if (isRunning) {
                stopAllRecorderResources()
                startRecordingFlow()
            }
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(1002)
        Log.i(TAG, "Stealth mode activated successfully without service restart")
    }
    private fun deactivateStealthMode() {
        stealthState = StealthState.NORMAL
        val appPrefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
        appPrefs.edit().putBoolean("hidden_mode", false).apply()
        activeHiddenMode = false
        handleAliasInternal(false)
        handler.post {
            setupForeground(true)
            if (isRunning) {
                stopAllRecorderResources()
                startRecordingFlow()
            }
        }
        Log.i(TAG, "Stealth mode deactivated successfully without service restart")
    }
    private fun handleAliasInternal(active: Boolean) {
        val appContext = applicationContext
        val pkg = appContext.packageManager
        val realApp = ComponentName(appContext, MainActivity::class.java)
        val fakeApp = ComponentName(appContext, "com.mykerd.panic.MainActivityAlias")
        try {
            if (active) {
                pkg.setComponentEnabledSetting(fakeApp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                pkg.setComponentEnabledSetting(realApp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            } else {
                pkg.setComponentEnabledSetting(realApp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                pkg.setComponentEnabledSetting(fakeApp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
            Log.d(TAG, "Alias handled successfully: active=$active")
        } catch (e: Exception) {
            Log.e(TAG, "Error in handleAliasInternal", e)
        }
    }
    private fun reconcileStealthState() {
        val currentState = stealthState
        val appContext = applicationContext
        val pkg = appContext.packageManager
        val realApp = ComponentName(appContext, MainActivity::class.java)
        val fakeApp = ComponentName(appContext, "com.mykerd.panic.MainActivityAlias")
        val isStealthActive = (currentState == StealthState.ACTIVE)
        try {
            val realState = pkg.getComponentEnabledSetting(realApp)
            val fakeState = pkg.getComponentEnabledSetting(fakeApp)
            val expectedReal = if (isStealthActive) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            val expectedFake = if (isStealthActive) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (realState != expectedReal || fakeState != expectedFake) {
                Log.w(TAG, "Reconciling stealth state: fixing components to match $currentState")
                pkg.setComponentEnabledSetting(fakeApp, expectedFake, PackageManager.DONT_KILL_APP)
                pkg.setComponentEnabledSetting(realApp, expectedReal, PackageManager.DONT_KILL_APP)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reconcile stealth state", e)
        }
    }
    private fun monitorCredentials() {
        val runnable = object : Runnable {
            override fun run() {
                if (!isRunning || !activeHiddenMode) return
                if (hasCredentialsChanged()) {
                    Log.i(TAG, getString(R.string.log_credential_change_deactivation))
                    deactivateStealthMode()
                } else {
                    backgroundHandler.postDelayed(this, 5000)
                }
            }
        }
        backgroundHandler.postDelayed(runnable, 5000)
    }
    private fun hasCredentialsChanged(): Boolean {
        return false
    }
    private fun startRecordingFlow() {
        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_init_camera)) else Log.d(TAG, "Initializing camera")
        handler.post { setupForeground(true) }
        if (initCamera()) {
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_start_recorder)) else Log.d(TAG, "Starting media recorder")
            startMediaRecorder()
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_start_sync)) else Log.d(TAG, "Starting sync worker")
            syncFiles()
            if (!activeHiddenMode) showToast(getString(R.string.toast_sos_active)) else Log.d(TAG, "SOS active and recording")
        } else {
            if (!activeHiddenMode) showToast(getString(R.string.toast_camera_error)) else Log.e(TAG, "Camera error")
        }
    }
    private fun initCamera(): Boolean {
        val camId = findCameraId(if (activeUseFront) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK)
        try {
            camera = Camera.open(camId)
        } catch (e: Exception) {
            Log.e(TAG, "Camera busy or unavailable: ${e.message}")
            return false
        }
        return try {
            camera?.let { cam ->
                if (activeHiddenMode) cam.enableShutterSound(false)
                cam.setDisplayOrientation(90)
                val p = cam.parameters
                val commonSize = getBestCommonSize(p)
                chosenWidth = commonSize.width
                chosenHeight = commonSize.height
                p.setPreviewSize(chosenWidth, chosenHeight)
                val focusModes = p.supportedFocusModes
                if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    p.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                } else if (focusModes != null && focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO
                }
                cam.parameters = p
                dummySurfaceTexture = SurfaceTexture(0)
                if (visibleTexture != null) {
                    cam.setPreviewTexture(visibleTexture)
                } else {
                    cam.setPreviewTexture(dummySurfaceTexture)
                }
                cam.startPreview()
                cam.unlock() 
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Camera parameters config failure: ${e.message}")
            try { camera?.release(); camera = null } catch (ex: Exception) {}
            false
        }
    }
    fun updatePreviewTarget() {
        handler.post {
            try {
                camera?.let { cam ->
                    if (visibleTexture != null) cam.setPreviewTexture(visibleTexture)
                    else cam.setPreviewTexture(dummySurfaceTexture)
                }
            } catch (e: Exception) { Log.e(TAG, "Preview update error: ${e.message}") }
        }
    }
    @SuppressLint("MissingPermission")
    private fun startMediaRecorder() {
        if (activeHiddenMode && checkSessionCap()) return
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val panicFolder = File(baseDir, "zMPanicRec")
        if (!panicFolder.exists()) panicFolder.mkdirs()
        val file = File(panicFolder, "SOS_${System.currentTimeMillis()}.mp4")
        currentFile = file
        try {
            if (mediaRecorder == null) {
                mediaRecorder = MediaRecorder()
            } else {
                mediaRecorder?.reset()
            }
            try { camera?.unlock() } catch (e: Exception) {}
            mediaRecorder?.apply {
                setCamera(camera)
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                val loc = lastLocation ?: try { 
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: 
                    locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                } catch (e: Exception) { null }
                loc?.let { setLocation(it.latitude.toFloat(), it.longitude.toFloat()) }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoSize(chosenWidth, chosenHeight)
                try { setVideoFrameRate(30) } catch (e: Exception) { Log.w(TAG, "30 FPS rejected") }
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoEncodingBitRate(1_000_000)
                setAudioEncodingBitRate(128_000)
                setOutputFile(file.absolutePath)
                if (isGaplessSupported) {
                    val bytesPerSecond = (1_000_000 + 128_000) / 8
                    setMaxFileSize(bytesPerSecond * activeRotation.toLong())
                }
                setOrientationHint(if (activeUseFront) 270 else 90)
                if (visibleTexture != null) {
                    setPreviewDisplay(Surface(visibleTexture))
                }
                setOnInfoListener { _, what, _ ->
                    if (what == 803) { 
                        resetWatchdog()
                        handleSeamlessRotation()
                    } else if (what == 802) { 
                        if (pendingNextFile == null) queueNextOutputFile()
                    }
                }
                if (activeHiddenMode) setOnErrorListener { _, _, _ -> }
                prepare()
                start()
            }
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_recording_to, file.name)) else Log.d(TAG, "Recording to: ${file.name}")

            if (isGaplessSupported) {
                resetWatchdog()
                queueNextOutputFile()
            } else {
                scheduleLegacyRotation()
            }
        } catch (e: Exception) {
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_recorder_error, e.message ?: "")) else Log.e(TAG, "Recorder Error: ${e.message}")
            handleCriticalRecorderFailure()
        }
    }
    private fun getBestCommonSize(params: Camera.Parameters): Camera.Size {
        val pSizes = params.supportedPreviewSizes ?: emptyList()
        val vSizes = params.supportedVideoSizes ?: emptyList()
        val validVSizes = if (vSizes.isEmpty()) pSizes else vSizes
        val common = pSizes.filter { p -> validVSizes.any { v -> v.width == p.width && v.height == p.height } }
        for (s in common) if (s.width == 640 && s.height == 480) return s
        for (s in common) if (s.width == 1280 && s.height == 720) return s
        return if (common.isNotEmpty()) common[0] else (if (pSizes.isNotEmpty()) pSizes[0] else params.previewSize)
    }
    private fun handleCriticalRecorderFailure() {
        isGaplessSupported = false
        stopAllRecorderResources()
        handler.postDelayed({ if (initCamera()) startMediaRecorder() }, 1000)
    }
    private fun stopAllRecorderResources() {
        try { mediaRecorder?.stop() } catch (e: Exception) {}
        try { mediaRecorder?.reset() } catch (e: Exception) {}
        try { mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        try { camera?.lock() } catch (e: Exception) {}
        try { camera?.stopPreview() } catch (e: Exception) {}
        try { camera?.release() } catch (e: Exception) {}
        camera = null
    }
    private fun queueNextOutputFile() {
        if (!isGaplessSupported) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val panicFolder = File(baseDir, "zMPanicRec")
            val nextFile = File(panicFolder, "SOS_${System.currentTimeMillis()}.mp4")
            pendingNextFile = nextFile
            try {
                mediaRecorder?.setNextOutputFile(nextFile)
                Log.d(TAG, "Gapless next file queued: ${nextFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Gapless unsupported on this HAL: ${e.message}")
                isGaplessSupported = false
                scheduleLegacyRotation()
            }
        }
    }
    private fun handleSeamlessRotation() {
        val completedFile = currentFile
        currentFile = pendingNextFile
        pendingNextFile = null
        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_chunk_saved, completedFile?.name ?: "")) else Log.d(TAG, "Chunk saved: ${completedFile?.name}")
        updateLocation()
        vibrate(40)
        queueNextOutputFile()
        Log.d(TAG, "Seamless transition to: ${currentFile?.name}")
    }
    private fun scheduleLegacyRotation() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (isRunning && !isGaplessSupported) rotateProcess()
        }, activeRotation * 1000L)
    }
    private fun resetWatchdog() {
        watchdogHandler.removeCallbacks(watchdogRunnable)
        watchdogHandler.postDelayed(watchdogRunnable, (activeRotation + 5) * 1000L)
    }
    private fun rotateProcess() {
        if (!isRunning) return
        val completedFile = currentFile
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
        } catch (e: Exception) { Log.e(TAG, "Mode B Stop Error: ${e.message}") }
        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_chunk_saved, completedFile?.name ?: "")) else Log.d(TAG, "Chunk saved: ${completedFile?.name}")
        updateLocation()
        vibrate(40)
        startMediaRecorder()
    }
    private fun syncFiles() {
        Thread {
            if (activeIp.isEmpty()) return@Thread
            val targetServers = mutableListOf<String>()
            val ipChunks = activeIp.split(";")
            for (chunk in ipChunks) {
                val clIp = chunk.trim()
                if (clIp.isNotEmpty()) {
                    var finalUrl = if (clIp.startsWith("http://") || clIp.startsWith("https://")) clIp else "https://$clIp"
                    if (finalUrl.count { it == ':' } == 1) finalUrl = "$finalUrl:$activePort"
                    if (!finalUrl.endsWith("/upload")) finalUrl = if (finalUrl.endsWith("/")) "${finalUrl}upload" else "$finalUrl/upload"
                    targetServers.add(finalUrl)
                }
            }
            val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val panicFolder = File(baseDir, "zMPanicRec")
            while (isRunning) {
                val files = panicFolder.listFiles { file ->
                    file.extension == "mp4" && !file.name.endsWith(".synced.mp4") &&
                    file.absolutePath != currentFile?.absolutePath && file.length() > 2000
                }?.sortedBy { it.lastModified() } ?: emptyList()
                for (file in files) {
                    if (!isRunning) break
                    var success = false
                    for (serverUrl in targetServers) {
                        try {
                            val conn = URL(serverUrl).openConnection() as HttpURLConnection
                            if (conn is HttpsURLConnection) {
                                val sslContext = SSLContext.getInstance("TLS")
                                sslContext.init(null, arrayOf(object : X509TrustManager {
                                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                                        if (chain.isEmpty()) throw java.security.cert.CertificateException("Certificate SSL missing")
                                        val digest = java.security.MessageDigest.getInstance("SHA-256")
                                        val currentPin = digest.digest(chain[0].encoded).joinToString("") { "%02x".format(it) }
                                        val serverHost = URL(serverUrl).host
                                        val expectedPin = securePrefs.getString("pinned_cert_$serverHost", "") ?: ""
                                        if (expectedPin.isEmpty()) {
                                            securePrefs.edit().putString("pinned_cert_$serverHost", currentPin).apply()
                                            Log.i(TAG, "TOFU: Certificate for $serverHost saved for the first time: $currentPin")
                                        } else if (!currentPin.equals(expectedPin, ignoreCase = true)) {
                                            Log.e(TAG, "MITM ALERT: Server certificate mismatch!")
                                            throw java.security.cert.CertificateException("Man-in-the-Middle Attack Detected!")
                                        }
                                    }
                                }), SecureRandom())
                                conn.sslSocketFactory = sslContext.socketFactory
                                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
                            }
                            conn.connectTimeout = 10000
                            conn.readTimeout = 10000
                            conn.requestMethod = "POST"
                            conn.doOutput = true
                            conn.setRequestProperty("Content-Type", "application/octet-stream")
                            conn.setRequestProperty("File-Name", file.name)
                            conn.setRequestProperty("GPS-Latitude", lastLocation?.latitude?.toString() ?: "0.0")
                            conn.setRequestProperty("GPS-Longitude", lastLocation?.longitude?.toString() ?: "0.0")
                            conn.setRequestProperty("Authorization", securePrefs.getString("server_password", "") ?: "")
                            conn.outputStream.use { file.inputStream().use { fis -> fis.copyTo(it) } }
                            if (conn.responseCode in 200..299) {
                                success = true
                                if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_upload_success, file.name)) else Log.d(TAG, "Upload success: ${file.name}")
                                break
                            }
                        } catch (e: Exception) { Log.e(TAG, "Sync failed for $serverUrl: ${e.message}") }
                    }
                    if (success) file.renameTo(File(file.parent, file.name.replace(".mp4", ".synced.mp4")))
                }
                try { Thread.sleep(5000) } catch (e: Exception) {}
            }
        }.start()
    }
    private fun setupForeground(includeSensitiveTypes: Boolean = true) {
        val chanId = if (activeHiddenMode) getString(R.string.notification_channel_id_hidden) else getString(R.string.notification_channel_id_normal)
        val chanName = if (activeHiddenMode) getString(R.string.notification_channel_hidden) else getString(R.string.notification_channel_normal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, chanName, NotificationManager.IMPORTANCE_HIGH)
            chan.lockscreenVisibility = if (activeHiddenMode) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC
            chan.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
        val icon = if (activeHiddenMode) android.R.drawable.stat_notify_sync else android.R.drawable.ic_menu_camera
        val title = if (activeHiddenMode) getString(R.string.notification_title_hidden) else getString(R.string.notification_title_normal)
        val text = if (activeHiddenMode) getString(R.string.notification_text_hidden) else getString(R.string.notification_text_normal)
        val n = NotificationCompat.Builder(this, chanId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(if (activeHiddenMode) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            if (Build.VERSION.SDK_INT >= 34) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            }
            if (includeSensitiveTypes) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            try {
                startForeground(1, n, types)
            } catch (e: Exception) {
                Log.e(TAG, "Foreground start failed with types $types: ${e.message}")
                try {
                    startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
                    if (includeSensitiveTypes) {
                        notifyRecordingFailedToStart()
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Critical foreground fallback failed", ex)
                }
            }
        } else {
            startForeground(1, n)
        }
    }
    private fun notifyRecordingFailedToStart() {
        try {
            val n = NotificationCompat.Builder(this, getString(R.string.notification_channel_id_normal))
                .setContentTitle(getString(R.string.notification_title_normal))
                .setContentText("NoAutoRecordBlockErrCustom.")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)?.notify(9999, n)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post recording-failed notification", e)
        }
    }
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) 
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 5000L, 0f, locationListener, Looper.getMainLooper())
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) 
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 5000L, 0f, locationListener, Looper.getMainLooper())
        } catch (e: Exception) {}
    }
    private fun updateLocation() {
        val loc = lastLocation
        if (activeHiddenMode) return
        handler.post {
            val text = if (loc != null) {
                val baseLocText = getString(R.string.toast_loc_found).replace("%s", "").trim()
                val formattedCoords = "${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}"
                "$baseLocText $formattedCoords"
            } else {
                getString(R.string.toast_loc_searching)
            }
            val t = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT)
            t.show()
            handler.postDelayed({ t.cancel() }, 1000)
        }
    }
    private fun silenceDevice(silent: Boolean) {
        if (!activeHiddenMode) return
        try {
            if (silent) {
                originalSystemVolume = audioManager.getStreamVolume(AudioManager.STREAM_SYSTEM)
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, 0, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
                audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, 0, 0)
                audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
            } else {
                audioManager.setStreamVolume(AudioManager.STREAM_SYSTEM, originalSystemVolume, 0)
                audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: Exception) { Log.e(TAG, "Error in silenceDevice", e) }
    }
    private fun showToast(msg: String) = handler.post { Toast.makeText(applicationContext, "zM: $msg", Toast.LENGTH_SHORT).show() }
    private fun showVerboseToast(msg: String) = handler.post { Toast.makeText(applicationContext, "zM [LOG]: $msg", Toast.LENGTH_SHORT).show() }
    private fun vibrate(ms: Long) {
        if (activeHiddenMode) return
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        else v.vibrate(ms)
    }
    private fun findCameraId(facing: Int): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == facing) return i
        }
        return 0
    }
    private fun checkSessionCap(): Boolean {
        if (activeHiddenMode && System.currentTimeMillis() - sessionStartTime >= SESSION_CAP_MS) {
            Log.w(TAG, "Session recording cap reached (12h). Stopping recording.")
            stopAllRecorderResources()
            return true
        }
        return false
    }
    private fun stopAll() {
        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_releasing_resources)) else Log.d(TAG, "Releasing resources")
        watchdogHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        backgroundHandler.removeCallbacksAndMessages(null)
        stopAllRecorderResources()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }
    override fun onDestroy() {
        isRunning = false
        serviceRef?.clear()
        backgroundHandler.post { silenceDevice(false) }
        locationManager.removeUpdates(locationListener)
        try {
            val cm = connectivityManager
            cm.bindProcessToNetwork(null)
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
        stopAll()
        backgroundThread.quitSafely()
        unregisterClockTamperReceiver()
        super.onDestroy()
    }
    private val connectivityManager get() = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
    override fun onBind(i: Intent?) = null
}