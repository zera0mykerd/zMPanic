# 🛡️ zM SOS GUARD - Advanced Emergency Surveillance System

![Android 16 Compatible](https://img.shields.io/badge/Android-16%20Ready-brightgreen)
![Status](https://img.shields.io/badge/Status-Active-red)
![License](https://img.shields.io/badge/License-Private-orange)

**zM SOS GUARD** is a high-performance emergency recording and streaming tool designed for critical situations where every second counts. It transforms an Android device into a persistent "Black Box" that captures audio/video and streams it in real-time to a secure remote server.

---

## 🚀 Key Features

* **⚡ Electric SOS Interface:** High-contrast, neon-red UI designed for immediate visibility in high-stress environments.
* **🎥 Continuous Stealth Loop:** Records video in 20-second segments to ensure data is uploaded progressively, preventing loss if the device is destroyed or stolen.
* **🔒 Background Resilience:** Engineered to bypass modern OS restrictions. It records and streams even when the **screen is locked** or the app is in the background.
* **🌐 Real-Time Remote Upload:** Uses a high-speed OkHttp implementation to push encrypted binary data to a dedicated server via HTTP POST.
* **📍 Location Tracking:** Embeds GPS coordinates within the metadata loop for precise emergency tracking.
* **🖥️ Live Monitor:** Provides a low-latency preview of the camera feed directly within the app.

---

## 🛠️ How it Works (The Technical Core)

The application is built on a dual-layer architecture:

### 1. The Foreground Guardian (PanicService)
The heart of the app is a **Foreground Service** that runs with high priority. 
* It utilizes the `MediaRecorder` API for low-level hardware access.
* Implements a recursive `Handler` loop that manages the lifecycle of each video segment.
* Handles resource cleanup (Camera/Mic) instantly to prevent "Resource Busy" errors.

### 2. The Network Engine
Each 20-second clip is treated as an independent packet.
* **Protocol:** HTTP/1.1 POST.
* **Payload:** Binary MPEG-4 stream.
* **Retry Logic:** Designed to handle network switching (Wi-Fi to 5G) without crashing the recording session.

---

## 📂 Project Structure

```text
zMPanic/
├── app/src/main/java/.../
│   ├── MainActivity.kt      # Neon SOS UI & Permission Handler
│   └── PanicService.kt      # Core Engine (Recording & Upload)
├── server/
│   └── server.py            # Python Flask/FastAPI Backend (Receiver)
└── build.gradle             # High-speed networking dependencies
