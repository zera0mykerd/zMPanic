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
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class PanicService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var camera: Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentFile: File? = null
    private var isRunning = false

    private var activeIp: String = ""
    private var activePort: String = ""
    private var activeRotation: Int = 20
    private var activeUseFront: Boolean = false

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val TAG = "zMPanicCore"

    companion object {
        private var previewHolder: SurfaceHolder? = null
        fun setPreviewHolder(holder: SurfaceHolder?) { previewHolder = holder }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            activeIp = it.getStringExtra("EXTRA_IP") ?: ""
            activePort = it.getStringExtra("EXTRA_PORT") ?: "9999"
            activeRotation = it.getIntExtra("EXTRA_ROTATION", 20)
            activeUseFront = it.getBooleanExtra("EXTRA_FRONT", false)
        }

        if (activeIp.isEmpty()) {
            val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
            activeIp = prefs.getString("server_ip", "192.168.1.220") ?: "192.168.1.220"
            activePort = prefs.getString("server_port", "9999") ?: "9999"
            activeRotation = prefs.getInt("rotation_seconds", 20)
            activeUseFront = prefs.getBoolean("use_front_cam", false)
        }

        if (isRunning) {
            showVerboseToast("ℹ️ SERVICE ALREADY RUNNING")
            return START_STICKY
        }

        isRunning = true
        showVerboseToast("🛠️ SETTING UP FOREGROUND MODE")
        setupForeground()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startRecordingFlow()
        return START_STICKY
    }

    private fun startRecordingFlow() {
        showVerboseToast("📷 INITIALIZING CAMERA...")
        if (initCamera()) {
            showVerboseToast("📼 STARTING MEDIA RECORDER...")
            startMediaRecorder()
            showVerboseToast("🔄 STARTING SYNC WORKER...")
            syncFiles()
            scheduleRotation()
            showToast("🚀 SOS ACTIVE & RECORDING")
        } else {
            showToast("❌ CAMERA ERROR: COULD NOT INITIALIZE")
        }
    }

    private fun initCamera(): Boolean {
        return try {
            val camId = findCameraId(if (activeUseFront) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK)
            camera = Camera.open(camId)
            camera?.setDisplayOrientation(90)
            previewHolder?.let {
                camera?.setPreviewDisplay(it)
                camera?.startPreview()
            }
            camera?.unlock()
            showVerboseToast("✅ CAMERA $camId OPENED & UNLOCKED")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera Init Exception", e)
            showVerboseToast("⚠️ CAMERA INIT FAILED: ${e.message}")
            false
        }
    }

    private fun startMediaRecorder() {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: filesDir
        val panicFolder = File(baseDir, "zMPanicRec")

        if (!panicFolder.exists()) {
            panicFolder.mkdirs()
            showVerboseToast("📁 CREATED RECORDING FOLDER")
        }

        val file = File(panicFolder, "SOS_${System.currentTimeMillis()}.mp4")
        currentFile = file

        try {
            mediaRecorder = MediaRecorder().apply {
                setCamera(camera)
                setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoSize(640, 480)
                setVideoFrameRate(15)
                setVideoEncodingBitRate(500000)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                setOrientationHint(if (activeUseFront) 270 else 90)
                previewHolder?.let { setPreviewDisplay(it.surface) }
                prepare()
                start()
            }
            showVerboseToast("🔴 RECORDING TO: ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Recorder Start Error", e)
            showToast("⚠️ RECORDER ERROR")
            showVerboseToast("❌ RECORDER EXCEPTION: ${e.message}")
        }
    }

    private fun scheduleRotation() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            showVerboseToast("⏳ ROTATION TRIGGERED (${activeRotation}s)")
            rotateProcess()
            updateLocation()
            vibrate(40)
            scheduleRotation()
        }, (activeRotation * 1000).toLong())
    }

    private fun rotateProcess() {
        val oldFile = currentFile
        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                    showVerboseToast("💾 SAVED: ${oldFile?.name}")
                } catch (e: Exception) {
                    oldFile?.delete()
                    showVerboseToast("🗑️ DELETED INVALID FILE")
                }
                it.reset()
                it.release()
            }
            mediaRecorder = null
            camera?.apply { lock(); stopPreview(); startPreview(); unlock() }
            startMediaRecorder()
            syncFiles()
        } catch (e: Exception) {
            Log.e(TAG, "Rotation Error", e)
            showVerboseToast("🧨 ROTATION CRASHED: RECOVERING...")
            stopAll()
            startRecordingFlow()
        }
    }

    private fun syncFiles() {
        Thread {
            if (activeIp.isEmpty()) {
                Log.e(TAG, "Sync aborted: IP is empty")
                showToast("🚫 SYNC ABORTED: NO IP")
                return@Thread
            }

            val url = "http://$activeIp:$activePort/upload"
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
            if (filesToSync.isNotEmpty()) showVerboseToast("📤 SYNC: ${filesToSync.size} PENDING FILES")

            while (filesToSync.isNotEmpty()) {
                if (!isRunning) break
                for (file in filesToSync) {
                    if (!isRunning) break
                    var success = false
                    try {
                        showVerboseToast("☁️ UPLOADING: ${file.name}")
                        val body = file.asRequestBody("application/octet-stream".toMediaType())
                        val request = Request.Builder().url(url).header("File-Name", file.name).post(body).build()
                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                success = true
                                showVerboseToast("✅ UPLOAD SUCCESS: ${file.name}")
                            } else {
                                showVerboseToast("❌ SERVER ERROR: ${response.code}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Network Error")
                        showVerboseToast("🌐 NETWORK ERROR: UNREACHABLE")
                        break
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
        showVerboseToast("🛑 RELEASING RESOURCES...")
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (e: Exception) {}
        mediaRecorder = null
        try { camera?.lock(); camera?.stopPreview(); camera?.release() } catch (e: Exception) {}
        camera = null
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
        val chanId = "panic_chan"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(chanId, "zM SOS Service", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(chan)
        }
        val n = NotificationCompat.Builder(this, chanId)
            .setContentTitle("zM SOS Guard Active")
            .setContentText("Protecting and Recording...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun updateLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    Log.d(TAG, "Loc: ${loc.latitude}, ${loc.longitude}")
                    showVerboseToast("📍 LOC: ${"%.4f".format(loc.latitude)}, ${"%.4f".format(loc.longitude)}")
                }
            }
        } catch (e: SecurityException) {
            showVerboseToast("📍 LOC ERROR: PERMISSION DENIED")
        }
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(applicationContext, "zM: $msg", Toast.LENGTH_SHORT).show() }
    }

    private fun showVerboseToast(msg: String) {
        handler.post { Toast.makeText(applicationContext, "zM [LOG]: $msg", Toast.LENGTH_SHORT).show() }
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else { @Suppress("DEPRECATION") v.vibrate(ms) }
    }

    override fun onDestroy() {
        showVerboseToast("💀 SERVICE DESTROYING...")
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopAll()
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null
}