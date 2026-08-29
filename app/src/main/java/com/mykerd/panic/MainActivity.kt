package com.mykerd.panic

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.KeyguardManager
import android.util.Log
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

class MainActivity : Activity() {
    private val PERMISSION_REQUEST_CODE = 1001
    private lateinit var prefs: SharedPreferences
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
            Log.e("zMPanic", "SecurePrefs Init Failure, falling back to standard", e)
            getSharedPreferences("secure_zmpanic_fallback", MODE_PRIVATE)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            km.requestDismissKeyguard(this, null)
        }
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)
        setupUI()
        checkEulaAndProceed()
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
            .setMessage(getString(R.string.dialog_eula_message)) // <-- Cambiato qui!
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
        val btnDownload = findViewById<Button>(R.id.btnDownloadServer)
        btnDownload.setOnClickListener {
            saveServerScript()
        }
        val editIp = findViewById<EditText>(R.id.editIp)
        val editPort = findViewById<EditText>(R.id.editPort)
        val editPassword = findViewById<EditText>(R.id.editPassword)
        val editSecs = findViewById<EditText>(R.id.editSecs)
        val switchFront = findViewById<Switch>(R.id.switchFront)
        val switchHidden = findViewById<Switch>(R.id.switchHidden)
        val hiddenOverlay = findViewById<View>(R.id.hiddenOverlay)
        (editIp.parent as? ViewGroup)?.background = settingsBg
        editIp.setText(prefs.getString("server_ip", "192.168.1.220"))
        editPort.setText(prefs.getString("server_port", "9999"))
        editPassword.setText(securePrefs.getString("server_password", ""))
        editSecs.setText(prefs.getInt("rotation_seconds", 20).toString())
        switchFront.isChecked = prefs.getBoolean("use_front_cam", false)
        val isHidden = prefs.getBoolean("hidden_mode", false)
        switchHidden.isChecked = isHidden
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
                securePrefs.edit().putString("server_password", s.toString()).apply()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        switchFront.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_front_cam", isChecked).apply()
            restartService()
        }
        switchHidden.setOnCheckedChangeListener { _, active ->
            prefs.edit().putBoolean("hidden_mode", active).apply()
            hiddenOverlay.visibility = if (active) View.VISIBLE else View.GONE
            findViewById<FrameLayout>(R.id.cameraContainer).layoutParams.height = if (active) 1 else 400
            restartService()
            handleAlias(active)
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
    private fun handleAlias(active: Boolean) {
        val pkg = packageManager
        val realApp = ComponentName(this, MainActivity::class.java)
        val fakeApp = ComponentName(this, "com.mykerd.panic.MainActivityAlias")
        if (active) {
            Toast.makeText(this, getString(R.string.toast_alias_active), Toast.LENGTH_LONG).show()
            Handler(Looper.getMainLooper()).postDelayed({
                pkg.setComponentEnabledSetting(fakeApp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                pkg.setComponentEnabledSetting(realApp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }, 2000)
        } else {
            pkg.setComponentEnabledSetting(realApp, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            pkg.setComponentEnabledSetting(fakeApp, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            Toast.makeText(this, getString(R.string.toast_alias_inactive), Toast.LENGTH_SHORT).show()
        }
    }
    private fun restartService() {
        stopService(Intent(this, PanicService::class.java))
        if (hasRequiredPermissions()) checkPasswordAndStart()
    }
    private fun requestPermissionsAndStart() {
        if (!prefs.getBoolean("eula_accepted", false)) return
        requestIgnoreBatteryOptimizations()
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
        val pwd = securePrefs.getString("server_password", null)
        if (pwd.isNullOrEmpty()) {
            showPasswordDialog()
        } else {
            startPanicService()
        }
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
                    securePrefs.edit().putString("server_password", pass).apply()
                    startPanicService()
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
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
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
            putExtra("EXTRA_IP", prefs.getString("server_ip", "192.168.1.220"))
            putExtra("EXTRA_PORT", prefs.getString("server_port", "9999"))
            putExtra("EXTRA_ROTATION", prefs.getInt("rotation_seconds", 20))
            putExtra("EXTRA_FRONT", prefs.getBoolean("use_front_cam", false))
            putExtra("EXTRA_HIDDEN", prefs.getBoolean("hidden_mode", false))
        }
        ContextCompat.startForegroundService(this, intent)
    }
    private fun stopPanicService() {
        stopService(Intent(this, PanicService::class.java))
        Toast.makeText(this, getString(R.string.toast_service_stopped), Toast.LENGTH_SHORT).show()
    }
    private fun saveServerScript() {
        try {
            val inputStream = assets.open("zmpanicsrcsrv.zip")
            val outputFile = File(getExternalFilesDir(null), "zmpanicsrcsrv.zip")
            inputStream.use { input ->
                outputFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(this, getString(R.string.toast_script_saved, outputFile.absolutePath), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            val baseErrorText = getString(R.string.toast_script_error).replace("%s", "").trim()
            val errorMsg = e.message ?: "Unknown error"
            Toast.makeText(this, "$baseErrorText $errorMsg", Toast.LENGTH_SHORT).show()
        }
    }
    private fun hasRequiredPermissions(): Boolean {
        if (!prefs.getBoolean("eula_accepted", false)) return false
        val perms = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val missingBasePerms = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingBasePerms.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingBasePerms.toTypedArray(), 100)
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, getString(R.string.toast_select_always_allow), Toast.LENGTH_LONG).show()
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 100)
            return false
        }
        return true
    }
}