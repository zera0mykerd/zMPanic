package com.mykerd.panic

import android.app.*
import android.content.*
import android.hardware.Camera
import android.location.Location
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.os.*
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class PanicService : Service() {
    private var mediaRecorder: MediaRecorder? = null
    private var camera: Camera? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var lastLocation: String = "0.0,0.0"
    private var lastFinishedFile: File? = null

    // OkHttp Client configuration
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
        setupForeground()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        startRecordingLoop()
        return START_STICKY
    }

    private fun startRecordingLoop() {
        handler.removeCallbacksAndMessages(null)
        val runnable = object : Runnable {
            override fun run() {
                updateLocation()
                saveAndRestart()
                vibrate(60)
                handler.postDelayed(this, 20000) // Loop every 20 seconds
            }
        }
        handler.post(runnable)
    }

    private fun saveAndRestart() {
        // 1. Capture the file that just finished recording
        val fileToSend = lastFinishedFile

        // 2. Safely try to stop the recorder
        try {
            mediaRecorder?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Stop failed (video probably too short): ${e.message}")
        }

        // 3. Resource cleanup
        try {
            mediaRecorder?.release()
            camera?.stopPreview()
            camera?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Cleanup error: ${e.message}")
        } finally {
            mediaRecorder = null
            camera = null
        }

        // 4. UPLOAD: If file exists and has content, send to server
        if (fileToSend != null && fileToSend.exists() && fileToSend.length() > 0) {
            val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
            val ip = prefs.getString("server_ip", "")?.trim()
            val port = prefs.getString("server_port", "")?.trim()

            if (!ip.isNullOrEmpty()) {
                val url = "http://$ip:$port/upload"
                uploadFile(fileToSend, url)
            }
        }

        // 5. START NEW RECORDING SEGMENT
        prepareNewRecording()
    }

    private fun prepareNewRecording() {
        val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
        val publicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val panicFolder = File(publicDir, "zMPanicRec")
        if (!panicFolder.exists()) panicFolder.mkdirs()

        val newFile = File(panicFolder, "REC_${System.currentTimeMillis()}.mp4")

        try {
            val isFront = prefs.getBoolean("use_front_cam", false)
            val camId = if (isFront) Camera.CameraInfo.CAMERA_FACING_FRONT else Camera.CameraInfo.CAMERA_FACING_BACK
            camera = Camera.open(camId)

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
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(640, 480)
                setVideoFrameRate(30)
                setVideoEncodingBitRate(2000000)
                setOutputFile(newFile.absolutePath)
                previewHolder?.let { setPreviewDisplay(it.surface) }
                prepare()
                start()
            }
            lastFinishedFile = newFile
        } catch (e: Exception) {
            showToast("Restart Error: ${e.message}")
        }
    }

    private fun uploadFile(file: File, url: String) {
        if (!file.exists() || file.length() == 0L) return

        showToast("📤 Sending: ${file.name}")

        val requestBody = file.asRequestBody("application/octet-stream".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/octet-stream")
            .header("Connection", "close")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                showToast("❌ ERROR: ${e.message}")
                Log.e(TAG, "Upload failed: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    showToast("✅ RECEIVED BY PC")
                } else {
                    showToast("❌ SERVER ERROR: ${response.code}")
                }
                response.close()
            }
        })
    }

    private fun showToast(message: String) {
        handler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }


    private fun updateLocation() {
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc: Location? ->
                loc?.let { lastLocation = "${it.latitude},${it.longitude}" }
            }
        } catch (e: SecurityException) {}
    }

    private fun writeLog(msg: String) {
        try {
            val logFile = File(getExternalFilesDir(null), "zmpanic_log.txt")
            FileOutputStream(logFile, true).use {
                val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                it.write("$fmt - $msg\n".toByteArray())
            }
        } catch (e: Exception) {}
        Log.d(TAG, msg)
    }

    private fun setupForeground() {
        val chan = NotificationChannel("panic", "zMPanic", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(chan)
        val n = NotificationCompat.Builder(this, "panic")
            .setContentTitle("SOS Protection Active")
            .setContentText("Emergency recording and streaming in progress...")
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    private fun vibrate(ms: Long) {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(ms)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            camera?.release()
        } catch (e: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null
}