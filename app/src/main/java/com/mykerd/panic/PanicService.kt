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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
    private var dummySurfaceTexture: android.graphics.SurfaceTexture? = null
    private lateinit var audioManager: AudioManager
    private var originalSystemVolume: Int = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private var chosenWidth = 640
    private var chosenHeight = 480

    private var isGaplessSupported = true
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = Runnable {
        Log.w(TAG, "⚠️ WATCHDOG: Gapless rotation failed (803 timeout). Forcing Mode B Fallback.")
        isGaplessSupported = false
        rotateProcess()
    }

    private val securePrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                this,
                "secure_zmpanic_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "SecurePrefs corruption, using fallback", e)
            getSharedPreferences("secure_zmpanic_fallback", MODE_PRIVATE)
        }
    }

    private val locationListener = object : android.location.LocationListener {
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
        private var visibleTexture: android.graphics.SurfaceTexture? = null
        private var serviceRef: WeakReference<PanicService>? = null
        fun setPreviewHolder(holder: SurfaceHolder?) { previewHolder = holder }
        fun setVisibleSurfaceTexture(st: android.graphics.SurfaceTexture?) {
            visibleTexture = st
            serviceRef?.get()?.updatePreviewTarget()
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "zMPanic:WifiLock")
            wifiLock?.setReferenceCounted(false)
            wifiLock?.acquire()
        } catch (e: Exception) { Log.e(TAG, "WifiLock failed: ${e.message}") }

        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    try { connectivityManager.bindProcessToNetwork(network) } catch (e: Exception) {}
                    Log.d(TAG, "🌐 Network bound: $network")
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
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        intent?.let {
            activeIp = it.getStringExtra("EXTRA_IP") ?: ""
            activePort = it.getStringExtra("EXTRA_PORT") ?: "9999"
            activeRotation = it.getIntExtra("EXTRA_ROTATION", 20)
            activeUseFront = it.getBooleanExtra("EXTRA_FRONT", false)
            activeHiddenMode = it.getBooleanExtra("EXTRA_HIDDEN", false)
            silenceDevice(true)
        }
        
        if (activeIp.isEmpty()) {
            val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
            activeIp = prefs.getString("server_ip", "") ?: ""
            activePort = prefs.getString("server_port", "9999") ?: "9999"
            activeRotation = prefs.getInt("rotation_seconds", 20)
            activeUseFront = prefs.getBoolean("use_front_cam", false)
        }
        
        if (isRunning) {
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_service_running)) else Log.d(TAG, "Service already running")
            updatePreviewTarget()
            return START_STICKY
        }
        
        isRunning = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            isGaplessSupported = false
        }

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zMPanic:SOS_Wakelock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(12 * 60 * 60 * 1000L)

        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_setup_foreground)) else Log.d(TAG, "Setting up foreground mode")
        setupForeground()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startLocationUpdates()
        startRecordingFlow()
        
        return START_STICKY
    }

    private fun startRecordingFlow() {
        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_init_camera)) else Log.d(TAG, "Initializing camera")
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
                
                dummySurfaceTexture = android.graphics.SurfaceTexture(0)
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
                            val conn = java.net.URL(serverUrl).openConnection() as java.net.HttpURLConnection
                            if (conn is javax.net.ssl.HttpsURLConnection) {
                                val sslContext = javax.net.ssl.SSLContext.getInstance("TLS")
                                sslContext.init(null, arrayOf(object : javax.net.ssl.X509TrustManager {
                                    override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                                    override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                                    override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                                }), java.security.SecureRandom())
                                conn.sslSocketFactory = sslContext.socketFactory
                                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
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

    private fun setupForeground() {
        val chanId = if (activeHiddenMode) "sys_integrity" else "sos_guard"
        val chanName = if (activeHiddenMode) "System Integrity Service" else "zM SOS Service"
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, chanName, NotificationManager.IMPORTANCE_HIGH)
            chan.lockscreenVisibility = if (activeHiddenMode) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC
            chan.setShowBadge(false)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
        
        val icon = if (activeHiddenMode) android.R.drawable.stat_notify_sync else android.R.drawable.ic_menu_camera
        val title = if (activeHiddenMode) "System Update" else "zM SOS Guard Active"
        val text = if (activeHiddenMode) "Synchronizing..." else "Protecting and Recording..."
        
        val n = NotificationCompat.Builder(this, chanId)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(if (activeHiddenMode) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
            .build()
            
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(1, n)
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
        } catch (e: Exception) {}
    }

    private fun showToast(msg: String) = handler.post { Toast.makeText(applicationContext, "zM: $msg", Toast.LENGTH_SHORT).show() }
    private fun showVerboseToast(msg: String) = handler.post { Toast.makeText(applicationContext, "zM [LOG]: $msg", Toast.LENGTH_SHORT).show() }

    private fun vibrate(ms: Long) {
        if (activeHiddenMode) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
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

    private fun stopAll() {
        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_releasing_resources)) else Log.d(TAG, "Releasing resources")
        watchdogHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        stopAllRecorderResources()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    override fun onDestroy() {
        isRunning = false
        serviceRef?.clear()
        silenceDevice(false)
        locationManager.removeUpdates(locationListener)
        try {
            val cm = connectivityManager
            cm.bindProcessToNetwork(null)
            networkCallback?.let { cm.unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
        stopAll()
        super.onDestroy()
    }
    
    private val connectivityManager get() = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    override fun onBind(i: Intent?) = null
}
