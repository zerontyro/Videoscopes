# Videoscopes: Real-Time Video Scopes Dashboard (Desktop & Android)

A professional, high-performance, real-time video analysis suite containing three crucial videography scopes: **Vectorscope (YCrCb / HSV)**, **Luminance Waveform**, and **RGB Parade**. 

This repository includes two separate versions of the tool:
1. **Desktop Version (Python + OpenCV + NumPy)**: Featuring camera capture, video file playback, custom screen cropping (ROI), and advanced post-processing color grading recommendations.
2. **Android Version (Kotlin + Jetpack Compose + CameraX)**: A lightweight, zero-dependency mobile app utilizing native graphics APIs for ultra-fast frame downsampling and real-time visualization.

---

## Download Binaries

Get the pre-compiled, standalone applications ready to use:

* 🖥️ **Windows Executable**: [desktop/dist/VideoScopes.exe](desktop/dist/VideoScopes.exe) (69.0 MB)
* 📱 **Android Package (APK)**: [android/dist/VideoScopes.apk](android/dist/VideoScopes.apk) (13.8 MB)

---

## Features Matrix

| Feature | Desktop Version (Python) | Android Version (Kotlin) |
| :--- | :---: | :---: |
| **Vectorscope** | YCrCb & HSV modes | YCrCb & HSV modes |
| **Luminance Waveform** | Rec. 709 Grayscale (0–100%) | Rec. 709 Grayscale (0–100%) |
| **RGB Parade** | Red, Green, Blue side-by-side | Red, Green, Blue side-by-side |
| **Frame Source** | Webcam, Video Files, Screen Crop | Back Device Camera |
| **Color Harmony Guides** | Complementary, Triadic, Analogous, Split | Complementary, Triadic, Analogous, Split |
| **Dominant Color ID** | Real-time Hex & HSV detector | Real-time Hex & HSV detector |
| **UI Customizations** | Gain, Saturation, Gamma, Colormaps | Gain, Saturation, Gamma, Colormaps |
| **Architecture** | Threaded IO + Vectorized NumPy | CameraX Analyzer + Kotlin CPU loops |
| **Performance** | ~5ms/frame (60+ FPS) | ~2ms/frame (60+ FPS) |

---

## Desktop Version (`/desktop`)

Built with OpenCV and NumPy, the desktop application runs in a multi-threaded loop to isolate frame grabbing from UI updates, ensuring a latency-free, fluid interface.

### Dashboard Layout & Controls
The interface compiles a 2x2 dashboard of size **1024x808**:
* **Top-Left**: Live Frame Feed (Webcam, selected file, or screen crop) with status overlay (FPS, Source, Latency).
* **Top-Right**: Vectorscope with target boxes, I-Line skin tone guide, and Color Grading Suggestion cards on the left/right margins.
* **Bottom-Left**: Luminance Waveform showing scene brightness.
* **Bottom-Right**: RGB Parade showing color balance.
* **Trackbar Controls**: Adjust Gain, Saturation, Scope Intensity, Active Colormap, and Harmony Mode dynamically.

### How to Run on Desktop

#### Option A: Running the Standalone Executable
Simply run the compiled executable:
```powershell
.\desktop\dist\VideoScopes.exe
```

#### Option B: Running from Python Script
1. Navigate to the `desktop` directory:
   ```bash
   cd desktop
   ```
2. Set up a virtual environment and install dependencies:
   ```bash
   python -m venv .venv
   .venv\Scripts\activate
   pip install opencv-python numpy pywin32
   ```
3. Run the script:
   ```bash
   python video_scopes.py
   ```

### Hotkey Shortcuts
While the desktop window is focused:
* `[F]`: Open Windows File Dialog to select a video file.
* `[W]`: Switch source back to the default webcam.
* `[S]`: Select a screen crop region (ROI) to analyze any part of the desktop at 30 FPS.
* `[V]`: Toggle between YCrCb and HSV vectorscope mode.
* `[C]`: Cycle through phosphor colormaps (Green, Cyan, Fire, Rainbow).
* `[P]`: Pause / Resume video playback.
* `[R]`: Rewind video file to the beginning.
* `[Q]` or `[ESC]`: Quit.

---

## Android Version (`/android`)

Designed to be lightweight and zero-dependency, this version does not package the massive OpenCV Android SDK (saving 100MB+ of overhead). Instead, it runs optimized Kotlin algorithms directly on the CPU by downsampling frames via native graphics memory.

### Architecture & Optimizations
```
Device Camera ──> CameraX ImageAnalysis ──> YUV to Bitmap ──> Downsample (128x128) ──> ScopeProcessor ──> Canvas Render
```
* **Performance**: Downsampling frames to $128 \times 128$ for the vectorscope and $128 \times 320$ for waveforms allows calculation loops to execute in under **2ms**, rendering buttery-smooth 60 FPS graphics.
* **Jetpack Compose UI**: Features a native 2x2 Compose layout using custom-drawing Canvas overlays.
* **Interactive Controls**: A bottom sheets card container exposes sliders for real-time changes to **Gain**, **Saturation**, **Intensity**, **Scope Mode**, **Colormaps**, and **Harmony Overlay Guides**.

### How to Build and Run on Android

#### Option A: Install APK
Download [VideoScopes.apk](android/dist/VideoScopes.apk) and install it on your device. Ensure you grant camera permission.

#### Option B: Compile from Source
1. Open the project in **Android Studio** or navigate to the `android/` directory.
2. Build the project using Gradle Wrapper:
   ```powershell
   .\gradlew assembleDebug
   ```
3. The output debug APK will be generated at:
   `android/app/build/outputs/apk/debug/app-debug.apk`

---

## Color Grading & Harmony Guide

The vectorscope in both versions supports **Color Harmony Guides** (based on classical color theory) to aid colorists and designers in creating balanced visual layouts:

1. **Complementary**: Draws an orange/teal polar guide lines. Used to achieve classic cinematic color contrast.
2. **Triadic**: Draws three guide lines at $120^\circ$ increments. Used for vibrant, balanced compositions.
3. **Analogous**: Highlights colors in adjacent ranges ($30^\circ$ apart). Used for calming, low-contrast scenes.
4. **Split-Complementary**: Highlights a base hue and two colors adjacent to its complement.

Both apps automatically detect the **Dominant Color** of your frame (ignoring grey/neutral values) and recommend the corresponding grading companion swatch to help you grade your scenes on the fly.
