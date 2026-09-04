package com.mykerd.panic

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.KeyguardManager
import android.app.NotificationManager
import android.util.Log
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : Activity() {
    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var prefs: SharedPreferences
    private val tapTimestamps = mutableListOf<Long>()
    private val tapLimit = 5
    private val tapWindow = 2500L
    private val ACTION_START_STEALTH_COUNTDOWN = "com.mykerd.panic.ACTION_START_STEALTH_COUNTDOWN"
    private val ACTION_CANCEL_STEALTH_ACTIVATION = "com.mykerd.panic.ACTION_CANCEL_STEALTH_ACTIVATION"
    private enum class StealthState {
        NORMAL,
        PENDING_ACTIVATION,
        CONFIRMING_ACTIVATION,
        ACTIVE
    }
    private val securePrefs: SharedPreferences by lazy {
        SecureConfig.getPrefs(this)
    }
    private val secureStealthPrefs: SharedPreferences by lazy {
        SecureConfig.getStealthPrefs(this)
    }
    @Volatile
    private var _stealthState: StealthState = StealthState.NORMAL
    private var stealthState: StealthState
        get() = _stealthState
        set(value) {
            _stealthState = value
            Thread {
                try {
                    secureStealthPrefs.edit().putString("stealth_state", value.name).apply()
                } catch (e: Exception) {
                    Log.e("zMPanic", "Error saving stealth state: ${e.message}")
                }
            }.start()
        }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            try {
                SecureConfig.getPrefs(this)
                val sPrefs = SecureConfig.getStealthPrefs(this)
                val stateStr = sPrefs.getString("stealth_state", StealthState.NORMAL.name)
                _stealthState = try { StealthState.valueOf(stateStr ?: StealthState.NORMAL.name) } catch (e: Exception) { StealthState.NORMAL }
                runOnUiThread { updateStealthUI() }
            } catch (e: Exception) {
                Log.e("zMPanic", "SecureConfig pre-warm failed: ${e.message}")
            }
        }.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
        setupUI()
        checkEulaAndProceed()
        updateStealthUI()
    }
    private fun checkEulaAndProceed() {
        val isEulaAccepted = prefs.getBoolean("eula_accepted", false)
        if (!isEulaAccepted) {
            showEulaDialog()
        } else {
            requestPermissionsAndStart()
        }
    }
    private fun showEulaDialog() {
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.dialog_eula_title))
            .setMessage(getString(R.string.dialog_eula_message))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.dialog_eula_positive)) { _, _ ->
                prefs.edit().putBoolean("eula_accepted", true).apply()
                requestPermissionsAndStart()
            }
            .setNegativeButton(getString(R.string.dialog_eula_negative)) { _, _ ->
                finish()
            }
            .create()
        dialog.show()
        dialog.window?.decorView?.findViewById<View>(android.R.id.content)?.parent?.let { parentView ->
            (parentView as? View)?.setBackgroundColor(Color.parseColor("#0A0000"))
        }
        val titleId = resources.getIdentifier("alertTitle", "id", "android")
        if (titleId > 0) {
            dialog.findViewById<TextView>(titleId)?.apply {
                setTextColor(Color.parseColor("#FF0033"))
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
            }
        }
        dialog.findViewById<TextView>(android.R.id.message)?.apply {
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 14f
        }
        val colorRed = Color.parseColor("#FF0033")
        val colorDarkGray = Color.parseColor("#444444")
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(colorRed)
            setTypeface(null, Typeface.BOLD)
            textSize = 14f
            setPadding(30, 10, 30, 10)
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(colorDarkGray)
            textSize = 14f
            setPadding(30, 10, 30, 10)
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    private fun setupUI() {
        val btnBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            colors = intArrayOf(Color.parseColor("#FF0033"), Color.parseColor("#80001A"))
            setStroke(5, Color.argb(128, 255, 255, 255))
        }
        findViewById<LinearLayout>(R.id.btnStopSos).background = btnBg
        val glowBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(38, 255, 0, 51))
        }
        val glowEffect = findViewById<View>(R.id.glowEffect)
        glowEffect.background = glowBg
        val settingsBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 40f
            setColor(Color.argb(51, 255, 77, 109))
            setStroke(2, Color.argb(76, 255, 0, 51))
        }
        ObjectAnimator.ofPropertyValuesHolder(
            glowEffect,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.2f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.2f)
        ).apply {
            duration = 1000
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
        val btnStop = findViewById<LinearLayout>(R.id.btnStopSos)
        btnStop.setOnLongClickListener { stopPanicService(); true }
        btnStop.setOnClickListener { handleTap() }
        val btnDownload = findViewById<Button>(R.id.btnDownloadServer)
        btnDownload.setOnClickListener {
            saveServerScript()
        }
        val editIp = findViewById<EditText>(R.id.editIp)
        val editPort = findViewById<EditText>(R.id.editPort)
        val editPassword = findViewById<EditText>(R.id.editPassword)
        val editSecs = findViewById<EditText>(R.id.editSecs)
        val switchFront = findViewById<Switch>(R.id.switchFront)
        val hiddenOverlay = findViewById<View>(R.id.hiddenOverlay)
        (editIp.parent as? ViewGroup)?.background = settingsBg
        editIp.setText(prefs.getString("server_ip", "192.168.1.220"))
        editPort.setText(prefs.getString("server_port", "9999"))
        Thread {
            val pwd = securePrefs.getString("server_password", "")
            runOnUiThread { editPassword.setText(pwd) }
        }.start()
        editSecs.setText(prefs.getInt("rotation_seconds", 20).toString())
        switchFront.isChecked = prefs.getBoolean("use_front_cam", false)
        val isHidden = prefs.getBoolean("hidden_mode", false)
        hiddenOverlay.visibility = if (isHidden) View.VISIBLE else View.GONE
        findViewById<FrameLayout>(R.id.cameraContainer).layoutParams.height = if (isHidden) 1 else 400
        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                prefs.edit().apply {
                    putString("server_ip", editIp.text.toString())
                    putString("server_port", editPort.text.toString())
                    putInt("rotation_seconds", editSecs.text.toString().toIntOrNull() ?: 20)
                    apply()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        editIp.addTextChangedListener(textWatcher)
        editPort.addTextChangedListener(textWatcher)
        editSecs.addTextChangedListener(textWatcher)
        editPassword.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pass = s.toString()
                Thread {
                    try {
                        securePrefs.edit().putString("server_password", pass).apply()
                    } catch (e: Exception) {
                        Log.e("zMPanic", "Error saving password: ${e.message}")
                    }
                }.start()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        switchFront.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_front_cam", isChecked).apply()
            restartService()
        }
        findViewById<TextureView>(R.id.textureView).surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                PanicService.setVisibleSurfaceTexture(st)
                if (!prefs.getBoolean("eula_accepted", false)) return
                if (hasRequiredPermissions()) checkPasswordAndStart()
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                PanicService.setVisibleSurfaceTexture(null)
                return false
            }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
    }
    private fun restartService() {
        val intent = Intent(this, PanicService::class.java)
        stopService(intent)
        if (hasRequiredPermissions()) checkPasswordAndStart()
    }
    private fun requestPermissionsAndStart() {
        if (!prefs.getBoolean("eula_accepted", false)) return
        requestIgnoreBatteryOptimizations()
        checkDndPermission()
        checkExactAlarmPermission()
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val needed = permissions.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            checkBackgroundLocationPermission()
        }
    }
    private fun checkBackgroundLocationPermission() {
        if (!prefs.getBoolean("eula_accepted", false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.toast_select_always_allow), Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 1002)
        } else {
            checkPasswordAndStart()
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!prefs.getBoolean("eula_accepted", false)) return
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                checkBackgroundLocationPermission()
            } else {
                Toast.makeText(this, getString(R.string.toast_perm_denied), Toast.LENGTH_SHORT).show()
                checkBackgroundLocationPermission()
            }
        } else if (requestCode == 1002) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPasswordAndStart()
            } else {
                Toast.makeText(this, getString(R.string.toast_bg_perm_denied), Toast.LENGTH_SHORT).show()
                checkPasswordAndStart()
            }
        }
    }
    private fun checkPasswordAndStart() {
        Thread {
            val pwd = securePrefs.getString("server_password", null)
            runOnUiThread {
                if (pwd.isNullOrEmpty()) {
                    showPasswordDialog()
                } else {
                    startPanicService()
                }
            }
        }.start()
    }
    private fun showPasswordDialog() {
        val container = FrameLayout(this)
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.setMargins(50, 20, 50, 20)
        val input = EditText(this).apply {
            hint = getString(R.string.hint_server_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(512))
            maxLines = 1
            layoutParams = params
        }
        container.addView(input)
        val dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(getString(R.string.dialog_password_title))
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("SAVE") { _, _ ->
                val pass = input.text.toString()
                if (pass.isNotEmpty()) {
                    Thread {
                        try {
                            securePrefs.edit().putString("server_password", pass).apply()
                            runOnUiThread { startPanicService() }
                        } catch (e: Exception) {
                            Log.e("zMPanic", "Error saving password: ${e.message}")
                        }
                    }.start()
                } else {
                    showPasswordDialog()
                }
            }
            .create()
        dialog.show()
    }
    private fun requestIgnoreBatteryOptimizations() {
        if (!prefs.getBoolean("eula_accepted", false)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                try {
                    startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (ex: Exception) {
                    Log.e("zMPanic", "Battery settings unreachable")
                }
            }
        }
    }
    override fun onStart() {
        super.onStart()
        if (prefs.getBoolean("hidden_mode", false)) {
            Handler(Looper.getMainLooper()).postDelayed({ moveTaskToBack(true) }, 250)
        }
    }
    private fun startPanicService() {
        val intent = Intent(this, PanicService::class.java).apply {
            action = "com.mykerd.panic.ACTION_START_SERVICE"
            putExtra("EXTRA_IP", prefs.getString("server_ip", "192.168.1.220"))
            putExtra("EXTRA_PORT", prefs.getString("server_port", "9999"))
            putExtra("EXTRA_ROTATION", prefs.getInt("rotation_seconds", 20))
            putExtra("EXTRA_FRONT", prefs.getBoolean("use_front_cam", false))
            putExtra("EXTRA_HIDDEN", prefs.getBoolean("hidden_mode", false))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }
    private fun stopPanicService() {
        stopService(Intent(this, PanicService::class.java))
        Toast.makeText(this, getString(R.string.toast_service_stopped), Toast.LENGTH_SHORT).show()
    }
    private fun saveServerScript() {
        Thread {
            try {
                val inputStream = assets.open("zmpanicsrcsrv.zip")
                val outputFile = File(getExternalFilesDir(null), "zmpanicsrcsrv.zip")
                inputStream.use { input ->
                    outputFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.toast_script_saved, outputFile.absolutePath), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                val baseErrorText = getString(R.string.toast_script_error).replace("%s", "").trim()
                val errorMsg = e.message ?: "Unknown error"
                runOnUiThread {
                    Toast.makeText(this, "$baseErrorText $errorMsg", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }
    private fun handleAlias(active: Boolean) {
        Thread {
            val pkg = packageManager
            val realApp = ComponentName(this, MainActivity::class.java)
            val fakeApp = ComponentName(this, "com.mykerd.panic.MainActivityAlias")
            try {
                if (active) {
                    pkg.setComponentEnabledSetting(fakeApp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    pkg.setComponentEnabledSetting(realApp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                } else {
                    pkg.setComponentEnabledSetting(realApp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    pkg.setComponentEnabledSetting(fakeApp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
                Log.d("zMPanic", "Alias toggled: $active")
            } catch (e: Exception) {
                Log.e("zMPanic", "Error toggling alias", e)
            }
        }.start()
    }
    private fun hasRequiredPermissions(): Boolean {
        if (!prefs.getBoolean("eula_accepted", false)) return false
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
            return false
        }
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        
        val missingBasePerms = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingBasePerms.isNotEmpty()) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        return true
    }
    private fun checkDndPermission() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !notificationManager.isNotificationPolicyAccessGranted) {
            Toast.makeText(this, getString(R.string.toast_dnd_permission), Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            startActivity(intent)
        }
    }
    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }
    private fun handleTap() {
        val now = System.currentTimeMillis()
        tapTimestamps.add(now)
        tapTimestamps.removeAll { it < now - tapWindow }
        if (tapTimestamps.size >= tapLimit) {
            tapTimestamps.clear()
            if (stealthState == StealthState.NORMAL) {
                showAntiStalkingDialog()
            }
        }
    }
    private fun showAntiStalkingDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.dialog_anti_stalking_title)
            .setMessage(R.string.dialog_anti_stalking_message)
            .setCancelable(false)
            .setPositiveButton(R.string.btn_cancel_activation) { _, _ ->
                cancelStealthActivation()
            }
            .setNegativeButton(android.R.string.ok) { _, _ ->
                startStealthCountdown()
            }
            .show()
    }
    private fun startStealthCountdown() {
        stealthState = StealthState.PENDING_ACTIVATION
        Thread {
            try {
                secureStealthPrefs.edit().putLong("activation_start_time", System.currentTimeMillis()).apply()
            } catch (e: Exception) {
                Log.e("zMPanic", "Error saving activation time: ${e.message}")
            }
        }.start()
        val intent = Intent(this, PanicService::class.java).apply {
            action = ACTION_START_STEALTH_COUNTDOWN
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStealthUI()
    }
    private fun cancelStealthActivation() {
        stealthState = StealthState.NORMAL
        Thread {
            try {
                secureStealthPrefs.edit().putLong("activation_start_time", 0L).apply()
            } catch (e: Exception) {
                Log.e("zMPanic", "Error clearing activation time: ${e.message}")
            }
        }.start()
        val intent = Intent(this, PanicService::class.java).apply {
            action = ACTION_CANCEL_STEALTH_ACTIVATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStealthUI()
    }
    override fun onResume() {
        super.onResume()
        updateStealthUI()
    }
    private fun updateStealthUI() {
        Thread {
            val currentState = stealthState
            runOnUiThread {
                val btnSosContainer = findViewById<View>(R.id.btnSosContainer) ?: return@runOnUiThread
                val container = btnSosContainer.parent as? LinearLayout ?: return@runOnUiThread
                val existingBtn = container.findViewWithTag<Button>("btnCancelStealth")

                if (currentState == StealthState.PENDING_ACTIVATION || currentState == StealthState.CONFIRMING_ACTIVATION) {
                    if (existingBtn == null) {
                        val btnCancel = Button(this).apply {
                            tag = "btnCancelStealth"
                            text = getString(R.string.btn_cancel_activation)
                            setOnClickListener {
                                cancelStealthActivation()
                            }
                        }
                        container.addView(btnCancel, container.indexOfChild(btnSosContainer) + 1)
                    }
                } else {
                    existingBtn?.let { container.removeView(it) }
                }
            }
        }.start()
    }
}