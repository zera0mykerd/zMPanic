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
        if (isRunning) return START_STICKY

        isRunning = true
        setupForeground()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        startRecordingFlow()
        return START_STICKY
    }

    private fun startRecordingFlow() {
        if (initCamera()) {
            startMediaRecorder()
            // Immediately start syncing existing files from previous sessions
            syncFiles()
            scheduleRotation()
            showToast("🚀 SOS ACTIVE & RECORDING")
        } else {
            showToast("❌ CAMERA ERROR: Could not initialize")
        }
    }

    private fun initCamera(): Boolean {
        return try {
            val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
            val useFront = prefs.getBoolean("use_front_cam", false)
            val camId = findCameraId(if (useFront) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK)

            camera = Camera.open(camId)
            camera?.setDisplayOrientation(90)
            previewHolder?.let {
                camera?.setPreviewDisplay(it)
                camera?.startPreview()
            }
            camera?.unlock()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Camera Init Exception", e)
            false
        }
    }

    private fun startMediaRecorder() {
        val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
        val panicFolder = File(baseDir, "zMPanicRec")

        if (!panicFolder.exists()) {
            panicFolder.mkdirs()
        }

        val file = File(panicFolder, "SOS_${System.currentTimeMillis()}.mp4")
        currentFile = file

        try {
            val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
            val useFront = prefs.getBoolean("use_front_cam", false)

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
                setOrientationHint(if (useFront) 270 else 90)
                previewHolder?.let { setPreviewDisplay(it.surface) }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recorder Start Error", e)
            showToast("⚠️ RECORDER ERROR: ${e.localizedMessage}")
        }
    }

    private fun scheduleRotation() {
        handler.removeCallbacksAndMessages(null)
        val secs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE).getInt("rotation_seconds", 20)

        handler.postDelayed({
            rotateProcess()
            updateLocation()
            vibrate(40)
            scheduleRotation()
        }, (secs * 1000).toLong())
    }

    private fun rotateProcess() {
        val oldFile = currentFile
        Log.d(TAG, "Rotating video file...")

        try {
            mediaRecorder?.let {
                try {
                    it.stop()
                } catch (e: RuntimeException) {
                    oldFile?.delete()
                }
                it.reset()
                it.release()
            }
            mediaRecorder = null

            camera?.apply {
                lock()
                stopPreview()
                startPreview()
                unlock()
            }

            startMediaRecorder()
            syncFiles()

        } catch (e: Exception) {
            Log.e(TAG, "Rotation Error", e)
            showToast("🔄 ROTATION ERROR: Restarting flow...")
            stopAll()
            startRecordingFlow()
        }
    }

    private fun syncFiles() {
        Thread {
            val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
            val ip = prefs.getString("server_ip", "") ?: ""
            val port = prefs.getString("server_port", "9999") ?: "9999"
            val url = "http://$ip:$port/upload"

            if (ip.isEmpty()) {
                showToast("🚫 SYNC ABORTED: No IP set")
                return@Thread
            }

            val baseDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            val panicFolder = File(baseDir, "zMPanicRec")
            if (!panicFolder.exists()) return@Thread

            val getFilesToSync = {
                panicFolder.listFiles { file ->
                    file.extension == "mp4" &&
                            !file.name.endsWith(".synced.mp4") &&
                            file.absolutePath != currentFile?.absolutePath &&
                            file.length() > 5000
                }?.sortedBy { it.lastModified() } ?: emptyList()
            }

            var filesToSync = getFilesToSync()

            if (filesToSync.isNotEmpty()) {
                showToast("📡 SYNC: Found ${filesToSync.size} file(s) to upload")
            }

            while (filesToSync.isNotEmpty()) {
                if (!isRunning) break

                for (file in filesToSync) {
                    if (!isRunning) break

                    showToast("📤 UPLOADING: ${file.name}")
                    var success = false
                    try {
                        val body = file.asRequestBody("application/octet-stream".toMediaType())
                        val request = Request.Builder()
                            .url(url)
                            .header("File-Name", file.name)
                            .post(body)
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                success = true
                            } else {
                                Log.e(TAG, "Server Error: ${response.code}")
                                showToast("❌ SERVER ERROR ${response.code} for ${file.name}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Network Error", e)
                        showToast("📡 CONNECTION LOST: Retrying in 5s... ⏳")
                        break
                    }

                    if (success) {
                        val syncedFile = File(file.parent, file.name.replace(".mp4", ".synced.mp4"))
                        if (file.renameTo(syncedFile)) {
                            Log.d(TAG, "Sync Success: ${file.name}")
                            showToast("✅ SUCCESS: ${file.name} synced!")
                        }
                    }
                }

                try { Thread.sleep(5000) } catch (e: Exception) {}
                filesToSync = getFilesToSync()
            }

            if (isRunning) {
                Log.d(TAG, "All files are synchronized.")
                showToast("🏁 SYNC COMPLETE: All files uploaded!")
            }
        }.start()
    }

    private fun stopAll() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
        } catch (e: Exception) {}
        mediaRecorder = null
        try {
            camera?.lock()
            camera?.stopPreview()
            camera?.release()
        } catch (e: Exception) {}
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
            .setContentText("Recording and monitoring in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun updateLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) Log.d(TAG, "Location updated: ${loc.latitude}, ${loc.longitude}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing")
        }
    }

    private fun showToast(msg: String) {
        handler.post { Toast.makeText(applicationContext, "zM: $msg", Toast.LENGTH_SHORT).show() }
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else { @Suppress("DEPRECATION") v.vibrate(ms) }
    }

    override fun onDestroy() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        stopAll()
        showToast("🛑 SERVICE SHUTDOWN COMPLETE")
        super.onDestroy()
    }

    override fun onBind(i: Intent?) = null
}