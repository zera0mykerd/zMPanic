package com.mykerd.panic

import android.app.*
import android.content.*
import android.hardware.Camera
import android.location.Location
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
import java.io.IOException
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class PanicService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var camera: Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentFile: File? = null
    private val isUploading = AtomicBoolean(false)

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
        if (camera != null) {
            showToast("🔄 SETTINGS UPDATED")
            rotateRecording()
        } else {
            showToast("🚀 SOS SERVICE STARTED")
            setupForeground()
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            prepareNewRecording()
            startRecordingLoop()
        }
        return START_STICKY
    }

    private fun startRecordingLoop() {
        handler.removeCallbacksAndMessages(null)
        val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)

        val runnable = object : Runnable {
            override fun run() {

                val seconds = prefs.getInt("rotation_seconds", 20)
                val intervalMs = (seconds * 1000).toLong()

                showToast("🔄 CYCLE ${seconds}s: Rotating...")
                updateLocation()
                rotateRecording()
                vibrate(60)

                handler.postDelayed(this, intervalMs)
            }
        }
        val initialSeconds = prefs.getInt("rotation_seconds", 20)
        handler.postDelayed(runnable, (initialSeconds * 1000).toLong())
    }

    private fun rotateRecording() {
        val fileToUpload = currentFile
        stopAndReleaseResources()

        if (fileToUpload != null && fileToUpload.exists() && fileToUpload.length() > 0) {
            checkAndUploadQueue(fileToUpload)
        }

        prepareNewRecording()
    }

    private fun checkAndUploadQueue(currentFile: File) {
        if (isUploading.get()) return

        Thread {
            isUploading.set(true)
            val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
            val ip = prefs.getString("server_ip", "")?.trim() ?: ""
            val port = prefs.getString("server_port", "9999")?.trim() ?: "9999"
            val url = "http://$ip:$port/upload"

            if (ip.isNotEmpty()) {
                val success = performUpload(currentFile, url)

                if (success) {
                    val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
                    val panicFolder = File(publicDir, "zMPanicRec")
                    val files = panicFolder.listFiles()?.filter {
                        it.name.endsWith(".mp4") && it.absolutePath != currentFile.absolutePath
                    }?.sortedBy { it.lastModified() }

                    files?.forEach { failedFile ->
                        Log.d(TAG, "Retrying failed file: ${failedFile.name}")
                        performUpload(failedFile, url)
                    }
                }
            }
            isUploading.set(false)
        }.start()
    }

    private fun performUpload(file: File, url: String): Boolean {
        return try {
            val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
            val request = Request.Builder().url(url).post(requestBody).build()
            val response = client.newCall(request).execute()

            val isOk = response.isSuccessful
            if (isOk) {
                showToast("✅ SENT: ${file.name.takeLast(8)}")
                // Optional: file.delete() if you delete after upload
            }
            response.close()
            isOk
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed for ${file.name}")
            false
        }
    }

    private fun stopAndReleaseResources() {
        try {
            mediaRecorder?.stop()
            mediaRecorder?.reset()
            mediaRecorder?.release()
        } catch (e: Exception) { }
        mediaRecorder = null
        try {
            camera?.stopPreview()
            camera?.setPreviewDisplay(null)
            camera?.release()
        } catch (e: Exception) { }
        camera = null
    }

    private fun prepareNewRecording() {
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val panicFolder = File(publicDir, "zMPanicRec")
        if (!panicFolder.exists()) panicFolder.mkdirs()

        val newFile = File(panicFolder, "SOS_${System.currentTimeMillis()}.mp4")
        currentFile = newFile

        try {
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
                setOutputFile(newFile.absolutePath)
                if (useFront) setOrientationHint(270) else setOrientationHint(90)
                previewHolder?.let { setPreviewDisplay(it.surface) }
                prepare()
                start()
            }
        } catch (e: Exception) {
            showToast("❌ CAMERA ERROR")
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
        val channelId = "panic_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, "zMPanic Service", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("zM SOS Active")
            .setContentText("Emergency monitoring running")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        startForeground(1, notification)
    }

    private fun updateLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { if (it != null) Log.d(TAG, "GPS OK") }
        } catch (e: SecurityException) {}
    }

    private fun showToast(message: String) {
        handler.post { Toast.makeText(applicationContext, "zM: $message", Toast.LENGTH_SHORT).show() }
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else { @Suppress("DEPRECATION") v.vibrate(ms) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        stopAndReleaseResources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}