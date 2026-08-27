package com.mykerd.panic

import android.app.*
import android.content.*
import android.hardware.Camera
import android.media.MediaRecorder
import android.os.*
import android.util.Log
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
        private var instance: PanicService? = null
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
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "PanicWifiLock")
            wifiLock?.acquire()
        } catch (e: Exception) {
            Log.e(TAG, "Error while activating wifilock: ${e.message}")
        }
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    super.onAvailable(network)
                    connectivityManager.bindProcessToNetwork(network)
                    Log.d(TAG, "🌐 PROCESS BOUND TO NETWORK: $network")
                }
                override fun onLost(network: Network) {
                    super.onLost(network)
                    connectivityManager.bindProcessToNetwork(null)
                    Log.e(TAG, "🌐 Network lost, unbinding...")
                    if (isRunning) handler.postDelayed({ connectivityManager.requestNetwork(request, networkCallback!!) }, 5000L)
                }
            }
            connectivityManager.requestNetwork(request, networkCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error in the network request: ${e.message}")
        }
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
            activeIp = prefs.getString("server_ip", "192.168.1.220") ?: "192.168.1.220"
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
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "zM:SOS_Wakelock")
        wakeLock?.acquire(12 * 60 * 60 * 1000L) /* Timeout 12h */
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
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        var attempts = 0
        val maxAttempts = 5
        while (attempts < maxAttempts) {
            if (keyguardManager.isKeyguardLocked) {
                Log.w(TAG, "Blocked screen...")
                try { Thread.sleep(500) } catch (e: Exception) {}
                continue
            }
            try {
                camera = Camera.open(camId)
                break
            } catch (e: Exception) {
                attempts++
                Log.w(TAG, "Camera hardware busy, attempt $attempts/$maxAttempts... Waiting 250ms")
                if (attempts >= maxAttempts) {
                    Log.e(TAG, "Final Camera Init Error: ${e.message}")
                    return false
                }
                try { Thread.sleep(250) } catch (sleepEx: Exception) {}
            }
        }
        return try {
            if (activeHiddenMode) camera?.enableShutterSound(false)
            camera?.setDisplayOrientation(90)
            camera?.parameters?.let { params ->
                if (params.supportedFocusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    params.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                    camera?.parameters = params
                    Log.d(TAG, "🔥 Continuous video autofocus activated!")
                }
            }
            dummySurfaceTexture = android.graphics.SurfaceTexture(10)
            if (visibleTexture != null) {
                camera?.setPreviewTexture(visibleTexture)
            } else {
                camera?.setPreviewTexture(dummySurfaceTexture)
            }
            camera?.startPreview()
            camera?.unlock()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera Configuration Error: ${e.message}")
            false
        }
    }
    fun updatePreviewTarget() {
        handler.post {
            try {
                camera?.let { cam ->
                    if (visibleTexture != null) {
                        cam.setPreviewTexture(visibleTexture)
                    } else {
                        cam.setPreviewTexture(dummySurfaceTexture)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error changing preview target dynamically: ${e.message}")
            }
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
            }
            try { camera?.unlock() } catch (e: Exception) { /* Ignored */ }
            mediaRecorder?.apply {
                setCamera(camera)
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isNetEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                if (isGpsEnabled || isNetEnabled) {
                    val loc = lastLocation
                        ?: locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    loc?.let {
                        val finalLat = it.latitude.toFloat()
                        val finalLon = it.longitude.toFloat()
                        setLocation(finalLat, finalLon)
                        Log.d(TAG, "Injected for Google Maps: $finalLat, $finalLon")
                    }
                }
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoSize(640, 480)
                setVideoFrameRate(15)
                val videoBitRate = 500_000
                val audioBitRate = 128_000
                setVideoEncodingBitRate(videoBitRate)
                setAudioEncodingBitRate(audioBitRate)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                val bytesPerSecond = (videoBitRate + audioBitRate) / 8
                val maxFileSizeBytes = bytesPerSecond * activeRotation.toLong()
                setMaxFileSize(maxFileSizeBytes)
                setOrientationHint(if (activeUseFront) 270 else 90)
                val recordingSurface = android.view.Surface(dummySurfaceTexture)
                setPreviewDisplay(recordingSurface)
                setOnInfoListener { _, what, _ ->
                    if (what == 803) {
                        handleSeamlessRotation()
                    }
                    else if (what == 802) {
                        if (pendingNextFile == null) queueNextOutputFile()
                    }
                }
                if (activeHiddenMode) {
                    setOnErrorListener { _, _, _ -> }
                }
                prepare()
                start()
            }
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_recording_to, file.name)) else Log.d(TAG, "Recording to: ${file.name}")
            queueNextOutputFile()
        } catch (e: Exception) {
            Log.e(TAG, "Recorder Error", e)
            if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_recorder_error, e.message ?: "")) else Log.e(TAG, "Recorder Error: ${e.message}")
        }
    }
    private fun queueNextOutputFile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val panicFolder = File(baseDir, "zMPanicRec")
            val nextFile = File(panicFolder, "SOS_${System.currentTimeMillis()}.mp4")
            pendingNextFile = nextFile
            try {
                mediaRecorder?.setNextOutputFile(nextFile)
                Log.d(TAG, "Next gapless file queued: ${nextFile.name}")
            } catch (e: Exception) {
                Log.e(TAG, "Unable to queue next file: ${e.message}")
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
    }
    private fun syncFiles() {
        Thread {
            if (activeIp.isEmpty()) {
                Log.e(TAG, "Sync aborted: IP is empty")
                return@Thread
            }
            val targetServers = mutableListOf<String>()
            val ipChunks = activeIp.split(";")
            for (chunk in ipChunks) {
                val clIp = chunk.trim()
                if (clIp.isNotEmpty()) {
                    var finalUrl = if (clIp.startsWith("http://") || clIp.startsWith("https://")) clIp else "http://$clIp"
                    if (finalUrl.count { it == ':' } == 1) {
                        finalUrl = "$finalUrl:$activePort"
                    }
                    if (!finalUrl.endsWith("/upload")) {
                        finalUrl = if (finalUrl.endsWith("/")) "${finalUrl}upload" else "$finalUrl/upload"
                    }
                    targetServers.add(finalUrl)
                }
            }
            if (targetServers.isEmpty()) return@Thread
            val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
            val panicFolder = File(baseDir, "zMPanicRec")
            val getFilesToSync = {
                panicFolder.listFiles { file ->
                    file.extension == "mp4" &&
                            !file.name.endsWith(".synced.mp4") &&
                            file.absolutePath != currentFile?.absolutePath &&
                            file.length() > 5000
                }?.sortedBy { it.lastModified() } ?: emptyList()
            }
            var filesToSync = getFilesToSync()
            while (filesToSync.isNotEmpty() || isRunning) {
                if (!isRunning) break
                for (file in filesToSync) {
                    if (!isRunning) break
                    var success = false
                    for (serverUrl in targetServers) {
                        if (!isRunning) break
                        try {
                            val latStr = lastLocation?.latitude?.toString() ?: "0.0"
                            val lonStr = lastLocation?.longitude?.toString() ?: "0.0"
                            val urlObj = java.net.URL(serverUrl)
                            val connection = urlObj.openConnection() as java.net.HttpURLConnection
                            if (connection is javax.net.ssl.HttpsURLConnection) {
                                connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
                            }
                            connection.connectTimeout = 5000
                            connection.readTimeout = 5000
                            connection.requestMethod = "POST"
                            connection.doOutput = true
                            connection.setRequestProperty("Content-Type", "application/octet-stream")
                            connection.setRequestProperty("File-Name", file.name)
                            connection.setRequestProperty("GPS-Latitude", latStr)
                            connection.setRequestProperty("GPS-Longitude", lonStr)
                            connection.outputStream.use { os ->
                                file.inputStream().use { fis ->
                                    fis.copyTo(os)
                                }
                            }
                            val responseCode = connection.responseCode
                            if (responseCode in 200..299) {
                                success = true
                                if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_upload_success, file.name)) else Log.d(TAG, "Upload success: ${file.name}")
                                connection.disconnect()
                                break
                            } else {
                                Log.e(TAG, "Server Error on $serverUrl: $responseCode")
                            }
                            connection.disconnect()
                        } catch (e: Exception) {
                            Log.e(TAG, "Network Error on $serverUrl: Unreachable")
                        }
                    }
                    if (success) {
                        val syncedFile = File(file.parent, file.name.replace(".mp4", ".synced.mp4"))
                        file.renameTo(syncedFile)
                    }
                }
                try { Thread.sleep(5000) } catch (e: Exception) {}
                filesToSync = getFilesToSync()
            }
        }.start()
    }
    private fun stopAll() {
        if (!activeHiddenMode) showVerboseToast(getString(R.string.toast_releasing_resources)) else Log.d(TAG, "Releasing resources")
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        try { camera?.lock(); camera?.stopPreview(); camera?.release() } catch (e: Exception) {}
        camera = null
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            wakeLock = null
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
        } catch (e: Exception) {
            Log.e(TAG, "Error in the silencer: ${e.message}")
        }
    }
    private fun findCameraId(facing: Int): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == facing) return i
        }
        return 0
    }
    private fun setupForeground() {
        val chanId = if (activeHiddenMode) "sys_sync_chan" else "panic_chan"
        val chanName = if (activeHiddenMode) getString(R.string.notification_channel_hidden) else getString(R.string.notification_channel_normal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, chanName, NotificationManager.IMPORTANCE_LOW)
            chan.lockscreenVisibility = if (activeHiddenMode) Notification.VISIBILITY_SECRET else Notification.VISIBILITY_PUBLIC
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
        val iconaSocial = if (activeHiddenMode) {
            android.R.drawable.stat_notify_sync
        } else {
            android.R.drawable.ic_menu_camera
        }
        val n = NotificationCompat.Builder(this, chanId)
            .setContentTitle(if (activeHiddenMode) getString(R.string.notification_title_hidden) else getString(R.string.notification_title_normal))
            .setContentText(if (activeHiddenMode) getString(R.string.notification_text_hidden) else getString(R.string.notification_text_normal))
            .setSmallIcon(iconaSocial)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(1, n)
        }
    }
    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            if (locationManager.getProvider(LocationManager.GPS_PROVIDER) != null &&
                locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    5000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.getProvider(LocationManager.NETWORK_PROVIDER) != null &&
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    0f,
                    locationListener,
                    Looper.getMainLooper()
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Loc error: Unknown (${e.message})")
        }
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
    private fun showToast(msg: String) {
        handler.post { Toast.makeText(applicationContext, "zM: $msg", Toast.LENGTH_SHORT).show() }
    }
    private fun showVerboseToast(msg: String) {
        handler.post { Toast.makeText(applicationContext, "zM [LOG]: $msg", Toast.LENGTH_SHORT).show() }
    }
    private fun vibrate(ms: Long) {
        if (activeHiddenMode) return
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else { @Suppress("DEPRECATION") v.vibrate(ms) }
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!activeHiddenMode) super.onTaskRemoved(rootIntent)
    }
    override fun onDestroy() {
        isRunning = false
        serviceRef?.clear()
        instance = null
        silenceDevice(false)
        locationManager.removeUpdates(locationListener)
        handler.removeCallbacksAndMessages(null)
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            connectivityManager.bindProcessToNetwork(null)
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {}
        if (wifiLock?.isHeld == true) wifiLock?.release()
        stopAll()
        super.onDestroy()
    }
    override fun onBind(i: Intent?) = null
}