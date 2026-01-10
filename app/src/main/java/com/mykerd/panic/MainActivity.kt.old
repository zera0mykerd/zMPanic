package com.mykerd.panic

import android.Manifest
import android.content.*
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val prefs = getSharedPreferences("zmpanic_prefs", MODE_PRIVATE)

        // Default initialization
        if (!prefs.contains("server_ip")) {
            prefs.edit()
                .putString("server_ip", "192.168.1.220")
                .putString("server_port", "9999")
                .putInt("rotation_seconds", 20) // Default 20s
                .apply()
        }

        setContent {
            val bgDeep = Color(0xFF0A0000)
            val electricRed = Color(0xFFFF0033)
            val darkCrimson = Color(0xFF80001A)
            val softRed = Color(0xFFFF4D6D).copy(alpha = 0.2f)

            var ip by remember { mutableStateOf(prefs.getString("server_ip", "192.168.1.220") ?: "") }
            var port by remember { mutableStateOf(prefs.getString("server_port", "9999") ?: "") }
            var rotationSecs by remember { mutableStateOf(prefs.getInt("rotation_seconds", 20).toString()) }
            var useFrontCamera by remember { mutableStateOf(prefs.getBoolean("use_front_cam", false)) }

            val scrollState = rememberScrollState()

            val launcher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { results ->
                if (results.values.all { it }) startPanicService()
            }

            LaunchedEffect(Unit) {
                val permissions = mutableListOf(
                    Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                launcher.launch(permissions.toTypedArray())
            }

            // MODIFIER: Added verticalScroll to adapt to any screen size
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgDeep)
                    .verticalScroll(scrollState)
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("zM SOS GUARD", color = electricRed, fontWeight = FontWeight.Black, fontSize = 26.sp)

                Spacer(modifier = Modifier.height(10.dp))

                // --- 1. STOP BUTTON ---
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(150.dp).pointerInput(Unit) {
                        detectTapGestures(onLongPress = { stopPanicService() })
                    }
                ) {
                    val infiniteTransition = rememberInfiniteTransition(label = "")
                    val glowScale by infiniteTransition.animateFloat(
                        initialValue = 1f, targetValue = 1.2f,
                        animationSpec = infiniteRepeatable(animation = tween(1000), repeatMode = RepeatMode.Reverse), label = ""
                    )
                    Box(modifier = Modifier.size(100.dp * glowScale).background(electricRed.copy(alpha = 0.15f), CircleShape))
                    Surface(
                        shape = CircleShape,
                        color = electricRed,
                        modifier = Modifier.size(100.dp).border(2.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                        shadowElevation = 10.dp
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier.background(Brush.radialGradient(listOf(electricRed, darkCrimson)))
                        ) {
                            Text("STOP", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 18.sp)
                            Text("HOLD", fontSize = 9.sp, color = Color.White)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(15.dp))

                // --- 2. LIVE FEED ---
                Text("LIVE EMERGENCY FEED", color = electricRed.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Start))
                Box(
                    modifier = Modifier.fillMaxWidth().height(140.dp).padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(12.dp)).background(Color.Black).border(1.dp, electricRed, RoundedCornerShape(12.dp))
                ) {
                    AndroidView(factory = { context ->
                        SurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) {
                                    PanicService.setPreviewHolder(h)
                                    startPanicService()
                                }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hi: Int) {}
                                override fun surfaceDestroyed(h: SurfaceHolder) { PanicService.setPreviewHolder(null) }
                            })
                        }
                    }, modifier = Modifier.fillMaxSize())
                    Text("● REC", color = electricRed, modifier = Modifier.padding(8.dp).align(Alignment.TopEnd), fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }

                Spacer(modifier = Modifier.height(15.dp))

                // --- 3. CONFIGURATION PANEL ---
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = softRed,
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, electricRed.copy(alpha = 0.3f))
                ) {
                    Column(modifier = Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("SYSTEM SETTINGS", color = electricRed, fontWeight = FontWeight.Bold, fontSize = 12.sp)

                        OutlinedTextField(
                            value = ip,
                            onValueChange = { ip = it; prefs.edit().putString("server_ip", it).apply() },
                            label = { Text("Server IP", fontSize = 12.sp) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = electricRed)
                        )

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = port,
                                onValueChange = { port = it; prefs.edit().putString("server_port", it).apply() },
                                label = { Text("Port") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = electricRed)
                            )

                            // NEW: ROTATION SECONDS FIELD
                            OutlinedTextField(
                                value = rotationSecs,
                                onValueChange = {
                                    rotationSecs = it
                                    val s = it.toIntOrNull() ?: 20
                                    prefs.edit().putInt("rotation_seconds", s).apply()
                                    startPanicService() // Restart to apply timing
                                },
                                label = { Text("Secs") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.Cyan)
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Front Camera", color = Color.White, fontSize = 14.sp)
                            Switch(
                                checked = useFrontCamera,
                                onCheckedChange = {
                                    useFrontCamera = it
                                    prefs.edit().putBoolean("use_front_cam", it).apply()
                                    startPanicService()
                                },
                                colors = SwitchDefaults.colors(checkedThumbColor = electricRed)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("SYSTEM ACTIVE: Auto-sync enabled", color = Color.Gray, fontSize = 10.sp)
                Spacer(modifier = Modifier.height(20.dp)) // Extra space for scroll
            }
        }
    }

    private fun startPanicService() {
        val intent = Intent(this, PanicService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopPanicService() {
        stopService(Intent(this, PanicService::class.java))
        Toast.makeText(this, "🛑 SOS STOPPED", Toast.LENGTH_SHORT).show()
    }
}