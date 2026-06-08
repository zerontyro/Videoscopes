#!/usr/bin/env python3
"""
Real-time Video Scopes Dashboard
Author: Antigravity AI
Description: A high-performance, real-time video and color analysis tool
             using OpenCV and NumPy. Features a threaded frame grabber,
             YCrCb/HSV Vectorscope, Luminance Waveform, and RGB Parade.
"""

import os
import sys
import time
import queue
import threading
import numpy as np
import cv2

# Optional Tkinter and PIL for file/screen capture dialogs
try:
    import tkinter as tk
    from tkinter import filedialog
    TK_AVAILABLE = True
except ImportError:
    TK_AVAILABLE = False

try:
    from PIL import ImageGrab
    Pillow_AVAILABLE = True
except ImportError:
    Pillow_AVAILABLE = False


class VideoSource:
    """
    Threaded video reader to capture frames from a webcam, video file,
    or a cropped region of the screen without blocking the main GUI loop.
    """
    def __init__(self, source=0):
        self.source = source
        self.lock = threading.Lock()
        self.running = False
        self.frame = None
        self.thread = None
        self.cap = None
        self.screen_bbox = None
        self.fps = 30.0
        self.frame_delay = 1.0 / self.fps
        
        # Determine initial capture mode
        if self.is_webcam(source):
            self.capture_mode = "camera"
            self.cap = cv2.VideoCapture(int(source) if isinstance(source, str) else source)
        else:
            self.capture_mode = "file"
            self.cap = cv2.VideoCapture(source)
            file_fps = self.cap.get(cv2.CAP_PROP_FPS)
            if file_fps > 0:
                self.fps = file_fps
            self.frame_delay = 1.0 / self.fps

    @staticmethod
    def is_webcam(source):
        """Check if source is a webcam index."""
        if isinstance(source, int):
            return True
        if isinstance(source, str) and source.isdigit():
            return True
        return False

    def start(self):
        """Start the background frame reading thread."""
        if self.running:
            return
        self.running = True
        self.thread = threading.Thread(target=self._update, daemon=True)
        self.thread.start()

    def _update(self):
        """Background thread execution loop."""
        while self.running:
            start_time = time.perf_counter()
            
            if self.capture_mode == "screen":
                if Pillow_AVAILABLE and self.screen_bbox is not None:
                    try:
                        img = ImageGrab.grab(bbox=self.screen_bbox)
                        frame = cv2.cvtColor(np.array(img), cv2.COLOR_RGB2BGR)
                        with self.lock:
                            self.frame = frame
                    except Exception as e:
                        time.sleep(0.1)
                        continue
                else:
                    time.sleep(0.1)
                    continue
                
                # Cap screen grab speed at 30 FPS to conserve CPU
                elapsed = time.perf_counter() - start_time
                sleep_time = self.frame_delay - elapsed
                if sleep_time > 0:
                    time.sleep(sleep_time)
                else:
                    time.sleep(0.005)
            else:
                if self.cap is None or not self.cap.isOpened():
                    time.sleep(0.1)
                    continue
                    
                ret, frame = self.cap.read()
                
                if not ret:
                    # Loop video files if they reach the end
                    if self.capture_mode == "file":
                        self.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                        continue
                    else:
                        # Webcam error or disconnected
                        time.sleep(0.1)
                        continue

                with self.lock:
                    self.frame = frame

                # Cap frame rate for video files to match natural playback speed
                if self.capture_mode == "file":
                    elapsed = time.perf_counter() - start_time
                    sleep_time = self.frame_delay - elapsed
                    if sleep_time > 0:
                        time.sleep(sleep_time)
                else:
                    # Webcam capture throttling (to avoid 100% CPU on fast feeds)
                    time.sleep(0.005)

    def get_frame(self):
        """Retrieve the latest frame in a thread-safe manner."""
        with self.lock:
            if self.frame is None:
                return None
            return self.frame.copy()

    def stop(self):
        """Stop reading frames and release video capture resources."""
        self.running = False
        if self.thread is not None:
            self.thread.join(timeout=1.0)
            self.thread = None
        if self.cap is not None:
            self.cap.release()
            self.cap = None

    def change_source(self, new_source):
        """Switch input source dynamically (Camera or File)."""
        self.stop()
        self.source = new_source
        self.screen_bbox = None
        
        # Handle string integer input (webcam indices)
        if isinstance(new_source, str) and new_source.isdigit():
            new_source = int(new_source)
            
        if self.is_webcam(new_source):
            self.capture_mode = "camera"
            self.cap = cv2.VideoCapture(new_source)
            self.fps = 30.0
        else:
            self.capture_mode = "file"
            self.cap = cv2.VideoCapture(new_source)
            self.fps = 30.0
            file_fps = self.cap.get(cv2.CAP_PROP_FPS)
            if file_fps > 0:
                self.fps = file_fps
                
        self.frame_delay = 1.0 / self.fps
        self.frame = None
        self.start()

    def change_to_screen_capture(self, bbox):
        """Switch input source dynamically to screen capture (Cropped ROI)."""
        self.stop()
        self.source = "Screen ROI"
        self.capture_mode = "screen"
        self.screen_bbox = bbox
        self.fps = 30.0
        self.frame_delay = 1.0 / self.fps
        self.frame = None
        self.start()


class VideoScopesApp:
    """
    Main application responsible for computing scopes, rendering the 2x2 dashboard,
    and handling interactive controls.
    """
    def __init__(self):
        self.window_name = "ClickPulse - Video Scopes Dashboard"
        self.video_source = VideoSource(0) # Default to webcam index 0
        self.paused = False
        
        # Default application parameters
        self.vectorscope_mode = 0  # 0: YCrCb, 1: HSV
        self.colormap_idx = 0      # 0: Green, 1: Cyan, 2: Fire, 3: Rainbow
        self.gain = 1.0            # Signal multiplier
        self.sat_boost = 1.0       # Saturation boost multiplier
        self.scope_intensity = 0.5 # Scope brightness gamma
        
        # Color Harmony settings
        self.harmony_mode = 0      # 0: None, 1: Complementary, 2: Triadic, 3: Analogous, 4: Split-Comp
        self.dominant_hue = 0.0
        self.dominant_bgr = (0, 0, 0)
        self.suggest_bgr = (0, 0, 0)
        self.dominant_name = "N/A"
        self.suggest_name = "N/A"
        
        # UI sizing
        self.tile_w = 512
        self.tile_h = 384
        
        # Performance monitoring
        self.fps_avg = 30.0
        self.latency_scopes = 0.0
        self.last_frame_time = time.perf_counter()
        
        # Palette Definitions (BGR)
        self.theme_bg = (25, 15, 11)        # #0B0F19 (Very dark slate blue)
        self.theme_border = (59, 41, 30)    # #1E293B (Slate gray)
        self.theme_text = (240, 232, 226)   # #E2E8F0 (Off-white)
        self.theme_grid = (80, 60, 45)      # Dim gray/blue grid lines
        
        # Precompute Vectorscope Reticles
        self.reticle_ycrcb = self._precompute_ycrcb_reticle()
        self.reticle_hsv = self._precompute_hsv_reticle()

    def run(self):
        """Initialize GUI window, register trackbars, and run the processing loop."""
        cv2.namedWindow(self.window_name, cv2.WINDOW_AUTOSIZE)
        
        # Create Control Trackbars
        cv2.createTrackbar("Gain x100", self.window_name, 100, 300, self._on_trackbar_gain)
        cv2.createTrackbar("Sat x100", self.window_name, 100, 300, self._on_trackbar_sat)
        cv2.createTrackbar("Intensity", self.window_name, 50, 100, self._on_trackbar_intensity)
        cv2.createTrackbar("Colormap", self.window_name, 0, 3, self._on_trackbar_colormap)
        cv2.createTrackbar("Scope Mode", self.window_name, 0, 1, self._on_trackbar_mode)
        cv2.createTrackbar("Harmony", self.window_name, 0, 4, self._on_trackbar_harmony)

        self.video_source.start()
        print("[INFO] Real-time Video Scopes Application started successfully.")
        print("[INFO] Press 'F' to choose a video file.")
        print("[INFO] Press 'W' to switch to webcam.")
        print("[INFO] Press 'S' to crop and analyze a region of the screen.")
        print("[INFO] Press 'V' to toggle Vectorscope mode (YCrCb / HSV).")
        print("[INFO] Press 'C' to cycle Colormaps.")
        print("[INFO] Press 'P' to pause/play.")
        print("[INFO] Press 'R' to rewind video (files only).")
        print("[INFO] Press 'ESC' or 'Q' to quit.")

        # Keep running until the window is closed or ESC/Q is pressed
        while True:
            t_start = time.perf_counter()
            
            # Fetch and process frame
            if not self.paused:
                frame = self.video_source.get_frame()
                if frame is not None:
                    # Perform image enhancements based on sliders
                    frame_boosted = self._enhance_frame(frame)
                    
                    # Compute Scopes
                    t_scope_start = time.perf_counter()
                    vectorscope = self._compute_vectorscope(frame_boosted)
                    waveform = self._compute_waveform(frame_boosted)
                    parade = self._compute_rgb_parade(frame_boosted)
                    self.latency_scopes = (time.perf_counter() - t_scope_start) * 1000.0
                    
                    # Generate Dashboard
                    dashboard = self._assemble_dashboard(frame, vectorscope, waveform, parade)
                    
                    # Display Dashboard
                    cv2.imshow(self.window_name, dashboard)
            
            # Handle user keys
            key = cv2.waitKey(1) & 0xFF
            if key == 27 or key == ord('q') or key == ord('Q'):  # ESC or Q
                break
            elif key == ord('p') or key == ord('P'):
                self.paused = not self.paused
                print(f"[INFO] Video {'PAUSED' if self.paused else 'RESUMED'}")
            elif key == ord('r') or key == ord('R'):
                if self.video_source.capture_mode == "file":
                    self.video_source.cap.set(cv2.CAP_PROP_POS_FRAMES, 0)
                    print("[INFO] Rewound video to beginning.")
            elif key == ord('v') or key == ord('V'):
                self.vectorscope_mode = 1 - self.vectorscope_mode
                cv2.setTrackbarPos("Scope Mode", self.window_name, self.vectorscope_mode)
            elif key == ord('c') or key == ord('C'):
                self.colormap_idx = (self.colormap_idx + 1) % 4
                cv2.setTrackbarPos("Colormap", self.window_name, self.colormap_idx)
            elif key == ord('f') or key == ord('F'):
                self._select_file_source()
            elif key == ord('s') or key == ord('S'):
                self._select_screen_crop()
            elif key == ord('w') or key == ord('W'):
                print("[INFO] Switching to default webcam...")
                self.video_source.change_source(0)

            # Frame rate performance overlay calculations
            t_end = time.perf_counter()
            frame_time = t_end - self.last_frame_time
            self.last_frame_time = t_end
            current_fps = 1.0 / frame_time if frame_time > 0 else 30.0
            self.fps_avg = 0.9 * self.fps_avg + 0.1 * current_fps

            # Check if GUI window was closed manually
            if cv2.getWindowProperty(self.window_name, cv2.WND_PROP_VISIBLE) < 1:
                break

        self.video_source.stop()
        cv2.destroyAllWindows()
        print("[INFO] Application exited cleanly.")

    # --- Slider Callback Handlers ---
    def _on_trackbar_gain(self, val):
        self.gain = max(0.01, val / 100.0)

    def _on_trackbar_sat(self, val):
        self.sat_boost = val / 100.0

    def _on_trackbar_intensity(self, val):
        self.scope_intensity = max(0.1, val / 100.0)

    def _on_trackbar_colormap(self, val):
        self.colormap_idx = val

    def _on_trackbar_mode(self, val):
        self.vectorscope_mode = val

    def _on_trackbar_harmony(self, val):
        self.harmony_mode = val

    def _select_screen_crop(self):
        """Capture screenshot and allow user to select a cropped region using cv2.selectROI."""
        if not Pillow_AVAILABLE:
            print("[WARNING] Pillow is not available. Cannot perform screen capture.")
            return

        print("[INFO] Capturing screen. Please select crop region in the overlay window...")
        
        # 1. Grab full screen screenshot
        try:
            screenshot = ImageGrab.grab()
            screen_bgr = cv2.cvtColor(np.array(screenshot), cv2.COLOR_RGB2BGR)
        except Exception as e:
            print(f"[ERROR] Screen capture failed: {e}")
            return
            
        # 2. Open normal window for ROI selection
        window_roi = "Select Crop Region (Drag Box & Press ENTER. Press ESC to Cancel)"
        cv2.namedWindow(window_roi, cv2.WINDOW_NORMAL)
        
        # Fit window to screen size nicely
        h_scr, w_scr = screen_bgr.shape[:2]
        cv2.resizeWindow(window_roi, min(1280, w_scr), min(720, h_scr))
        
        # 3. selectROI (blocks until Enter or ESC is pressed)
        r = cv2.selectROI(window_roi, screen_bgr, fromCenter=False, showCrosshair=True)
        cv2.destroyWindow(window_roi)
        
        x, y, w, h = r
        if w > 10 and h > 10:
            bbox = (x, y, x + w, y + h)
            print(f"[INFO] Cropped screen region selected: {bbox}")
            self.video_source.change_to_screen_capture(bbox)
        else:
            print("[INFO] Screen crop cancelled or region too small.")

    # --- Helper Operations ---
    def _enhance_frame(self, frame):
        """Apply gain and saturation modifications to the input frame."""
        # 1. Apply Gain (Brightness scaling)
        if self.gain != 1.0:
            frame = cv2.convertScaleAbs(frame, alpha=self.gain, beta=0)
            
        # 2. Apply Saturation Boost
        if self.sat_boost != 1.0:
            hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
            h, s, v = cv2.split(hsv)
            s = cv2.convertScaleAbs(s, alpha=self.sat_boost, beta=0)
            frame = cv2.cvtColor(cv2.merge([h, s, v]), cv2.COLOR_HSV2BGR)
            
        return frame

    def _colorise_scope(self, hist_norm):
        """Apply selected colormap/phosphor style to normalized histogram arrays."""
        if self.colormap_idx == 0:  # Green Phosphor
            g = hist_norm
            b = (hist_norm * 0.15).astype(np.uint8)
            r = (hist_norm * 0.10).astype(np.uint8)
            return cv2.merge([b, g, r])
            
        elif self.colormap_idx == 1:  # Cyan Phosphor (Cool)
            b = hist_norm
            g = (hist_norm * 0.85).astype(np.uint8)
            r = (hist_norm * 0.20).astype(np.uint8)
            return cv2.merge([b, g, r])
            
        elif self.colormap_idx == 2:  # Fire Glow
            return cv2.applyColorMap(hist_norm, cv2.COLORMAP_HOT)
            
        else:  # Rainbow (Spectral distribution)
            return cv2.applyColorMap(hist_norm, cv2.COLORMAP_JET)

    def _select_file_source(self):
        """Open a file dialog to choose a video file using Tkinter."""
        if not TK_AVAILABLE:
            print("[WARNING] Tkinter is not available. Cannot show file selector.")
            return

        def ask_file(q):
            root = tk.Tk()
            root.withdraw()
            # Bring file dialog to front
            root.attributes("-topmost", True)
            file_path = filedialog.askopenfilename(
                title="Select Video File",
                filetypes=[
                    ("Video Files", "*.mp4 *.avi *.mov *.mkv *.wmv"),
                    ("All Files", "*.*")
                ]
            )
            root.destroy()
            q.put(file_path)

        # Run file dialog in a separate short-lived thread to keep OpenCV GUI alive
        q = queue.Queue()
        dialog_thread = threading.Thread(target=ask_file, args=(q,), daemon=True)
        dialog_thread.start()
        
        print("[INFO] Waiting for file selection...")
        dialog_thread.join(timeout=15.0) # Wait up to 15 seconds
        
        if not q.empty():
            file_path = q.get()
            if file_path:
                print(f"[INFO] Opening video file: {file_path}")
                self.video_source.change_source(file_path)
            else:
                print("[INFO] File selection cancelled.")
        else:
            print("[INFO] File selection timed out.")

    # --- Precomputed Reticles ---
    def _precompute_ycrcb_reticle(self):
        """Precompute the vector coordinates and targets for YCrCb mode (256x256 BGR)."""
        reticle = np.zeros((256, 256, 3), dtype=np.uint8)
        cx, cy = 128, 128
        
        # 1. Main outer reference circle (radius 120)
        cv2.circle(reticle, (cx, cy), 120, self.theme_grid, 1, cv2.LINE_AA)
        
        # 2. Central crosshairs
        cv2.line(reticle, (cx - 120, cy), (cx + 120, cy), self.theme_grid, 1)
        cv2.line(reticle, (cx, cy - 120), (cx, cy + 120), self.theme_grid, 1)
        
        # 3. Target markings for standard 75% Color Bars
        # Colors: R, Mg, B, Cy, G, Yl
        targets = [
            ("R", (110, 15), (40, 40, 255)),
            ("Mg", (171, 45), (255, 40, 255)),
            ("B", (240, 145), (255, 40, 40)),
            ("Cy", (146, 239), (255, 255, 40)),
            ("G", (85, 209), (40, 255, 40)),
            ("Yl", (16, 109), (40, 255, 255))
        ]
        
        for name, pos, color in targets:
            tx, ty = pos
            # Draw a box around target
            cv2.rectangle(reticle, (tx - 5, ty - 5), (tx + 5, ty + 5), color, 1)
            # Write label next to target
            offset_x = 8 if tx < cx else -18
            offset_y = 12 if ty < cy else -5
            cv2.putText(reticle, name, (tx + offset_x, ty + offset_y),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.32, self.theme_text, 1, cv2.LINE_AA)

        # 4. Human Skin Tone line (I-Line)
        # Vector heading towards upper-left: 123 degrees from X-axis (Cb)
        angle_rad = 123.0 * np.pi / 180.0
        sk_x = int(cx + 120 * np.cos(angle_rad))
        sk_y = int(cy - 120 * np.sin(angle_rad)) # inverted y
        cv2.line(reticle, (cx, cy), (sk_x, sk_y), (40, 150, 230), 1, cv2.LINE_AA) # Orange-ish skin tone guide
        cv2.putText(reticle, "SKIN", (sk_x + 5, sk_y - 2),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.3, (40, 150, 230), 1, cv2.LINE_AA)
                    
        return reticle

    def _precompute_hsv_reticle(self):
        """Precompute the vector coordinates and targets for HSV mode (256x256 BGR)."""
        reticle = np.zeros((256, 256, 3), dtype=np.uint8)
        cx, cy = 128, 128
        
        # 1. Main outer reference circle
        cv2.circle(reticle, (cx, cy), 120, self.theme_grid, 1, cv2.LINE_AA)
        cv2.circle(reticle, (cx, cy), 60, self.theme_grid, 1, cv2.LINE_AA) # inner circle (50% sat)
        
        # 2. Crosshairs
        cv2.line(reticle, (cx - 120, cy), (cx + 120, cy), self.theme_grid, 1)
        cv2.line(reticle, (cx, cy - 120), (cx, cy + 120), self.theme_grid, 1)
        
        # 3. Targets at primary hue boundaries
        # Red: 0 deg, Yellow: 60, Green: 120, Cyan: 180, Blue: 240, Magenta: 300
        targets = [
            ("R", 0, (40, 40, 255)),
            ("Yl", 60, (40, 255, 255)),
            ("G", 120, (40, 255, 40)),
            ("Cy", 180, (255, 255, 40)),
            ("B", 240, (255, 40, 40)),
            ("Mg", 300, (255, 40, 255))
        ]
        
        for name, angle_deg, color in targets:
            rad = angle_deg * np.pi / 180.0
            tx = int(cx + 115 * np.cos(rad))
            ty = int(cy - 115 * np.sin(rad))
            
            cv2.rectangle(reticle, (tx - 5, ty - 5), (tx + 5, ty + 5), color, 1)
            
            offset_x = 8 if tx >= cx else -20
            offset_y = 5 if ty >= cy else -5
            cv2.putText(reticle, name, (tx + offset_x, ty + offset_y),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.32, self.theme_text, 1, cv2.LINE_AA)
                        
        return reticle

    # --- Color Theory Harmony Helpers ---
    def _hue_to_bgr(self, h):
        """Convert a standard Hue angle (0-359) back to BGR color."""
        hsv_pixel = np.array([[[int(h/2) % 180, 255, 200]]], dtype=np.uint8)
        return cv2.cvtColor(hsv_pixel, cv2.COLOR_HSV2BGR)[0, 0].tolist()

    def _get_color_name(self, h):
        """Map standard Hue angle (0-359) to a human-readable color name."""
        h = h % 360
        if h >= 345 or h < 15: return "Red"
        elif h >= 15 and h < 45: return "Orange"
        elif h >= 45 and h < 75: return "Yellow"
        elif h >= 75 and h < 105: return "Yellow-Green"
        elif h >= 105 and h < 135: return "Green"
        elif h >= 135 and h < 165: return "Teal/Green-Cyan"
        elif h >= 165 and h < 195: return "Cyan"
        elif h >= 195 and h < 225: return "Light Blue"
        elif h >= 225 and h < 255: return "Blue"
        elif h >= 255 and h < 285: return "Indigo/Violet"
        elif h >= 285 and h < 315: return "Magenta"
        else: return "Pink"

    def _draw_dotted_line(self, img, p1, p2, color, thickness=1, gap=6):
        """Draw a beautiful dotted line from p1 to p2 in OpenCV."""
        p1 = np.array(p1, dtype=np.float32)
        p2 = np.array(p2, dtype=np.float32)
        dist = np.linalg.norm(p1 - p2)
        if dist == 0: return
        num_pts = int(dist / gap)
        if num_pts < 2:
            cv2.line(img, tuple(p1.astype(int)), tuple(p2.astype(int)), color, thickness, cv2.LINE_AA)
            return
        pts = np.linspace(p1, p2, num_pts)
        for i in range(num_pts - 1):
            if i % 2 == 0:
                cv2.line(img, tuple(pts[i].astype(int)), tuple(pts[i+1].astype(int)), color, thickness, cv2.LINE_AA)

    def _bgr_to_hex(self, bgr):
        """Convert BGR tuple to HEX string."""
        return f"#{bgr[2]:02X}{bgr[1]:02X}{bgr[0]:02X}"

    def _analyze_color_theory(self, hist):
        """Analyze vectorscope histogram to extract dominant color and grading suggestions."""
        # 1. Mask out center pixels (radius < 20) and outer pixels (radius > 120) to filter neutral noise
        y_indices, x_indices = np.indices(hist.shape)
        dist = np.sqrt((x_indices - 128)**2 + (y_indices - 128)**2)
        mask = (dist >= 20) & (dist <= 120)
        masked_hist = hist * mask
        
        if masked_hist.max() > 0:
            # Find peak bin
            y_peak, x_peak = np.unravel_index(np.argmax(masked_hist), hist.shape)
            
            # Base angle in Cartesian coordinate space
            dx = x_peak - 128
            dy = 128 - y_peak # Inverted Y
            theta = np.arctan2(dy, dx)
            
            # Convert peak to standard BGR & Hue depending on active mode
            if self.vectorscope_mode == 0:  # YCrCb Mode
                # Peak coordinates represent Cb=x_peak, Cr=255-y_peak (since y = 255-Cr)
                cr_val = 255 - y_peak
                cb_val = x_peak
                # Convert single YCrCb pixel back to BGR
                ycrcb_pixel = np.array([[[128, cr_val, cb_val]]], dtype=np.uint8)
                bgr_raw = cv2.cvtColor(ycrcb_pixel, cv2.COLOR_YCrCb2BGR)[0, 0]
                hsv_raw = cv2.cvtColor(np.array([[bgr_raw]], dtype=np.uint8), cv2.COLOR_BGR2HSV)[0, 0]
                hue_deg = hsv_raw[0] * 2.0
            else:  # HSV Polar Mode
                # The angle in Cartesian space maps directly to Hue
                hue_deg = np.degrees(theta) % 360
                bgr_raw = np.array(self._hue_to_bgr(hue_deg), dtype=np.uint8)
            
            self.dominant_hue = hue_deg
            self.dominant_bgr = tuple(int(c) for c in bgr_raw)
            self.dominant_name = self._get_color_name(hue_deg)
            
            # Calculate grading suggestion (Complementary color)
            suggest_hue = (hue_deg + 180) % 360
            self.suggest_bgr = tuple(self._hue_to_bgr(suggest_hue))
            self.suggest_name = self._get_color_name(suggest_hue)
            
            return theta
        else:
            self.dominant_name = "Neutral"
            self.dominant_bgr = (100, 100, 100)
            self.suggest_name = "N/A"
            self.suggest_bgr = (25, 15, 11)
            return None

    def _draw_harmony_lines(self, scope_img, theta):
        """Draw selected color harmony guide lines on the Vectorscope image."""
        if theta is None or self.harmony_mode == 0:
            return
            
        cx, cy = 128, 128
        r = 120  # length of guide lines
        
        # Guide line color styles: BGR
        col_primary = (40, 200, 255)  # Orange/yellow for primary line
        col_harmony = (240, 100, 240)  # Magenta/pink for harmonic lines
        
        angles = []
        # harmony modes: 1: Complementary, 2: Triadic, 3: Analogous, 4: Split-Comp
        if self.harmony_mode == 1:
            # Complementary (0, 180)
            angles = [(theta, col_primary), (theta + np.pi, col_harmony)]
        elif self.harmony_mode == 2:
            # Triadic (0, +120, -120)
            angles = [
                (theta, col_primary), 
                (theta + 2 * np.pi / 3, col_harmony), 
                (theta - 2 * np.pi / 3, col_harmony)
            ]
        elif self.harmony_mode == 3:
            # Analogous (0, +30, -30)
            angles = [
                (theta, col_primary), 
                (theta + np.pi / 6, col_harmony), 
                (theta - np.pi / 6, col_harmony)
            ]
        elif self.harmony_mode == 4:
            # Split-Complementary (0, +150, -150)
            angles = [
                (theta, col_primary), 
                (theta + 5 * np.pi / 6, col_harmony), 
                (theta - 5 * np.pi / 6, col_harmony)
            ]
            
        for angle, color in angles:
            ex = int(cx + r * np.cos(angle))
            ey = int(cy - r * np.sin(angle)) # inverted y
            self._draw_dotted_line(scope_img, (cx, cy), (ex, ey), color, thickness=1, gap=6)

    # --- Scope Generators ---
    def _compute_vectorscope(self, frame):
        """
        Calculates Vectorscope distribution.
        Downsamples frame to 128x128, converts to HSV/YCrCb, and accumulates to 256x256.
        """
        frame_small = cv2.resize(frame, (128, 128), interpolation=cv2.INTER_AREA)
        
        if self.vectorscope_mode == 0:  # YCrCb Mode
            ycrcb = cv2.cvtColor(frame_small, cv2.COLOR_BGR2YCrCb)
            cr = ycrcb[:, :, 1].astype(np.float32)
            cb = ycrcb[:, :, 2].astype(np.float32)
            
            # Map values (horizontal: Cb, vertical: Cr inverted)
            x = cb
            y = 255 - cr
        else:  # HSV Mode
            hsv = cv2.cvtColor(frame_small, cv2.COLOR_BGR2HSV)
            h = hsv[:, :, 0].astype(np.float32) * (np.pi / 90.0) # 0-179 -> radians (0-2pi)
            s = hsv[:, :, 1].astype(np.float32)
            
            # Radius max is 120 pixels
            r = s * (120.0 / 255.0)
            x = 128.0 + r * np.cos(h)
            y = 128.0 - r * np.sin(h)
            
        # Fast 2D accumulation using bincount
        x = np.clip(x, 0, 255).astype(np.int32).flatten()
        y = np.clip(y, 0, 255).astype(np.int32).flatten()
        indices = y * 256 + x
        hist = np.bincount(indices, minlength=256 * 256).reshape((256, 256)).astype(np.float32)
        
        # Run dominant color theory analysis
        theta = self._analyze_color_theory(hist)
        
        # Gain/Intensity Gamma Scaling
        if hist.max() > 0:
            hist = np.power(hist, self.scope_intensity)
            cv2.normalize(hist, hist, 0, 255, cv2.NORM_MINMAX)
            
        hist_norm = hist.astype(np.uint8)
        scope_img = self._colorise_scope(hist_norm)
        
        # Draw dotted harmony guide lines on top of the scope glow
        self._draw_harmony_lines(scope_img, theta)
        
        # Apply Reticle Overlay (additive blend to preserve signal)
        reticle = self.reticle_ycrcb if self.vectorscope_mode == 0 else self.reticle_hsv
        scope_blended = cv2.addWeighted(scope_img, 1.0, reticle, 0.8, 0)
        
        return scope_blended

    def _compute_waveform(self, frame):
        """
        Calculates Luminance Waveform.
        Downsamples to 128x420, converts to Grayscale, accumulates column-wise, and adds scale markers.
        """
        target_w = 420
        target_h = 128
        frame_small = cv2.resize(frame, (target_w, target_h), interpolation=cv2.INTER_AREA)
        gray = cv2.cvtColor(frame_small, cv2.COLOR_BGR2GRAY)
        
        # Accumulate pixels into Y columns
        waveform = np.zeros((256, target_w), dtype=np.float32)
        for r in range(target_h):
            waveform[255 - gray[r], np.arange(target_w)] += 1
            
        # Non-linear intensity scaling
        if waveform.max() > 0:
            waveform = np.power(waveform, self.scope_intensity)
            cv2.normalize(waveform, waveform, 0, 255, cv2.NORM_MINMAX)
            
        waveform_norm = waveform.astype(np.uint8)
        colorized = self._colorise_scope(waveform_norm)
        
        # Build layout: 50px Left scale labels + 420px Waveform plot = 470px width
        padded_scope = np.full((256, target_w + 50, 3), self.theme_bg, dtype=np.uint8)
        padded_scope[:, 50:] = colorized
        
        # Overlay Grid lines and Scale texts (0%, 20%, 40%, 60%, 80%, 100%)
        # y positions for labels: 0% is at y=255, 100% is at y=0.
        scales = [
            ("100%", 0),
            ("80%", 51),
            ("60%", 102),
            ("40%", 153),
            ("20%", 204),
            ("0%", 255)
        ]
        
        for label, y in scales:
            # Draw tick mark on scale column
            cv2.line(padded_scope, (42, y), (50, y), self.theme_text, 1)
            # Draw dotted/dashed line across the scope area
            if y != 0 and y != 255:
                # Dotted line
                cv2.line(padded_scope, (50, y), (target_w + 50, y), self.theme_grid, 1, lineType=cv2.LINE_4)
            else:
                # Solid boundary line
                cv2.line(padded_scope, (50, y), (target_w + 50, y), self.theme_border, 1)
                
            # Scale text label
            cv2.putText(padded_scope, label, (8, y + 4 if y < 250 else y - 4),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.30, self.theme_text, 1, cv2.LINE_AA)
                        
        return padded_scope

    def _compute_rgb_parade(self, frame):
        """
        Calculates RGB Parade.
        Separates channels, resizes to 128x140 per channel, computes waveforms,
        colors them red/green/blue, stacks, and adds scale markers.
        """
        sec_w = 140
        target_h = 128
        
        # Resize BGR frame first, then split channels (faster than splitting first and resizing three times)
        frame_small = cv2.resize(frame, (sec_w, target_h), interpolation=cv2.INTER_AREA)
        b_small, g_small, r_small = cv2.split(frame_small)
        
        # Allocate waveforms
        w_b = np.zeros((256, sec_w), dtype=np.float32)
        w_g = np.zeros((256, sec_w), dtype=np.float32)
        w_r = np.zeros((256, sec_w), dtype=np.float32)
        
        # Fast column accumulation
        for row in range(target_h):
            w_b[255 - b_small[row], np.arange(sec_w)] += 1
            w_g[255 - g_small[row], np.arange(sec_w)] += 1
            w_r[255 - r_small[row], np.arange(sec_w)] += 1
            
        # Normalize individually
        for w in [w_b, w_g, w_r]:
            if w.max() > 0:
                cv2.normalize(np.power(w, self.scope_intensity), w, 0, 255, cv2.NORM_MINMAX)
                
        # Cast to uint8
        w_b_u8 = w_b.astype(np.uint8)
        w_g_u8 = w_g.astype(np.uint8)
        w_r_u8 = w_r.astype(np.uint8)
        
        # Colorize waveforms (Red segment -> R, Green segment -> G, Blue segment -> B)
        zeros = np.zeros_like(w_b_u8)
        
        # Parade layout standard: RED (left), GREEN (middle), BLUE (right)
        r_seg = cv2.merge([zeros, zeros, w_r_u8])
        g_seg = cv2.merge([zeros, w_g_u8, zeros])
        b_seg = cv2.merge([w_b_u8, zeros, zeros])
        
        # Stack channels horizontally (total width = 3 * 140 = 420px)
        parade_stack = np.hstack([r_seg, g_seg, b_seg])
        
        # Assemble complete parade panel: 50px Left scale labels + 420px stacked scope = 470px
        padded_parade = np.full((256, 3 * sec_w + 50, 3), self.theme_bg, dtype=np.uint8)
        padded_parade[:, 50:] = parade_stack
        
        # Draw channel division lines and text headers
        div_x1 = 50 + sec_w
        div_x2 = 50 + 2 * sec_w
        cv2.line(padded_parade, (div_x1, 0), (div_x1, 255), self.theme_border, 1, cv2.LINE_4)
        cv2.line(padded_parade, (div_x2, 0), (div_x2, 255), self.theme_border, 1, cv2.LINE_4)
        
        # Channel headers text labels
        cv2.putText(padded_parade, "RED", (50 + sec_w // 2 - 12, 18),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, (40, 40, 255), 1, cv2.LINE_AA)
        cv2.putText(padded_parade, "GREEN", (50 + sec_w + sec_w // 2 - 20, 18),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, (40, 255, 40), 1, cv2.LINE_AA)
        cv2.putText(padded_parade, "BLUE", (50 + 2 * sec_w + sec_w // 2 - 15, 18),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, (255, 40, 40), 1, cv2.LINE_AA)
                    
        # Overlay grid markers (identical vertical scaling to Waveform)
        scales = [
            ("100%", 0),
            ("80%", 51),
            ("60%", 102),
            ("40%", 153),
            ("20%", 204),
            ("0%", 255)
        ]
        
        for label, y in scales:
            cv2.line(padded_parade, (42, y), (50, y), self.theme_text, 1)
            if y != 0 and y != 255:
                cv2.line(padded_parade, (50, y), (3 * sec_w + 50, y), self.theme_grid, 1, lineType=cv2.LINE_4)
            else:
                cv2.line(padded_parade, (50, y), (3 * sec_w + 50, y), self.theme_border, 1)
                
            cv2.putText(padded_parade, label, (8, y + 4 if y < 250 else y - 4),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.30, self.theme_text, 1, cv2.LINE_AA)
                        
        return padded_parade

    # --- Dashboard Compositing ---
    def _center_image(self, img, target_w, target_h):
        """Helper to pad and center an image inside a larger dark-themed tile."""
        h, w = img.shape[:2]
        out = np.full((target_h, target_w, 3), self.theme_bg, dtype=np.uint8)
        dx = (target_w - w) // 2
        dy = (target_h - h) // 2
        out[dy:dy + h, dx:dx + w] = img
        return out

    def _assemble_dashboard(self, frame, vectorscope, waveform, parade):
        """Compose the 2x2 dashboard, metadata, guidelines, and control hints."""
        # 1. Tile 0,0: Video Frame
        video_resized = cv2.resize(frame, (self.tile_w, self.tile_h), interpolation=cv2.INTER_AREA)
        
        # Add beautiful panel frame around video feed
        cv2.rectangle(video_resized, (0, 0), (self.tile_w - 1, self.tile_h - 1), self.theme_border, 2)
        
        # Write source meta and FPS details onto the video feed
        if self.video_source.capture_mode == "camera":
            src_label = f"Source: Webcam {self.video_source.source}"
        elif self.video_source.capture_mode == "screen":
            # Format bounding box display nicely
            bbox = self.video_source.screen_bbox
            src_label = f"Source: Screen ROI ({bbox[0]},{bbox[1]} to {bbox[2]},{bbox[3]})"
        else:
            src_label = f"Source: File ({os.path.basename(str(self.video_source.source))})"
            
        cv2.putText(video_resized, src_label, (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1, cv2.LINE_AA)
        cv2.putText(video_resized, f"FPS: {self.fps_avg:.1f}", (15, 55),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1, cv2.LINE_AA)
        cv2.putText(video_resized, f"Latency: {self.latency_scopes:.1f}ms", (15, 80),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, (0, 255, 255), 1, cv2.LINE_AA)

        # 2. Tile 0,1: Vectorscope (Centered)
        vectorscope_centered = self._center_image(vectorscope, self.tile_w, self.tile_h)
        cv2.rectangle(vectorscope_centered, (0, 0), (self.tile_w - 1, self.tile_h - 1), self.theme_border, 2)
        
        mode_text = "VECTORSCOPE: YCrCb" if self.vectorscope_mode == 0 else "VECTORSCOPE: HSV"
        cm_names = ["Green Phosphor", "Cyan Phosphor", "Fire Glow", "Rainbow Spectral"]
        cm_text = f"Colorise: {cm_names[self.colormap_idx]}"
        
        cv2.putText(vectorscope_centered, mode_text, (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, self.theme_text, 1, cv2.LINE_AA)
        cv2.putText(vectorscope_centered, cm_text, (15, 55),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, self.theme_text, 1, cv2.LINE_AA)

        # Render Dominant Color Card (Left margin of Vectorscope)
        cv2.putText(vectorscope_centered, "DOMINANT HUE", (15, 110),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, self.theme_text, 1, cv2.LINE_AA)
        cv2.rectangle(vectorscope_centered, (15, 120), (95, 160), self.dominant_bgr, -1)
        cv2.rectangle(vectorscope_centered, (15, 120), (95, 160), self.theme_border, 1)
        
        cv2.putText(vectorscope_centered, self.dominant_name, (15, 180),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, self.theme_text, 1, cv2.LINE_AA)
        
        dom_hex = self._bgr_to_hex(self.dominant_bgr)
        cv2.putText(vectorscope_centered, dom_hex, (15, 198),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, (139, 116, 100), 1, cv2.LINE_AA)

        # Render Color grading harmony suggestions (Right margin of Vectorscope)
        harmony_labels = ["SUGGESTIONS", "COMPLEMENTARY", "TRIADIC HARM", "ANALOGOUS HARM", "SPLIT-COMP HARM"]
        harm_label = harmony_labels[self.harmony_mode]
        
        cv2.putText(vectorscope_centered, harm_label, (380, 110),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.35, self.theme_text, 1, cv2.LINE_AA)
        
        if self.harmony_mode > 0 and self.dominant_name != "Neutral":
            cv2.rectangle(vectorscope_centered, (400, 120), (480, 160), self.suggest_bgr, -1)
            cv2.rectangle(vectorscope_centered, (400, 120), (480, 160), self.theme_border, 1)
            
            cv2.putText(vectorscope_centered, self.suggest_name, (400, 180),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.35, self.theme_text, 1, cv2.LINE_AA)
            
            sug_hex = self._bgr_to_hex(self.suggest_bgr)
            cv2.putText(vectorscope_centered, sug_hex, (400, 198),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.35, (139, 116, 100), 1, cv2.LINE_AA)
        else:
            # Draw placeholder box indicating no harmony is active
            cv2.rectangle(vectorscope_centered, (400, 120), (480, 160), (32, 22, 18), -1)
            cv2.rectangle(vectorscope_centered, (400, 120), (480, 160), self.theme_border, 1)
            cv2.putText(vectorscope_centered, "Set", (426, 138),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.32, (100, 80, 70), 1, cv2.LINE_AA)
            cv2.putText(vectorscope_centered, "Harmony", (410, 152),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.32, (100, 80, 70), 1, cv2.LINE_AA)
            cv2.putText(vectorscope_centered, "Inactive", (400, 180),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.35, (100, 80, 70), 1, cv2.LINE_AA)

        # 3. Tile 1,0: Waveform (Centered)
        waveform_centered = self._center_image(waveform, self.tile_w, self.tile_h)
        cv2.rectangle(waveform_centered, (0, 0), (self.tile_w - 1, self.tile_h - 1), self.theme_border, 2)
        cv2.putText(waveform_centered, "LUMINANCE WAVEFORM", (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, self.theme_text, 1, cv2.LINE_AA)

        # 4. Tile 1,1: Parade (Centered)
        parade_centered = self._center_image(parade, self.tile_w, self.tile_h)
        cv2.rectangle(parade_centered, (0, 0), (self.tile_w - 1, self.tile_h - 1), self.theme_border, 2)
        cv2.putText(parade_centered, "RGB PARADE", (15, 30),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.45, self.theme_text, 1, cv2.LINE_AA)

        # Stitch Tiles into 2x2 grid (1024x768 total dashboard)
        top_row = np.hstack([video_resized, vectorscope_centered])
        bottom_row = np.hstack([waveform_centered, parade_centered])
        grid = np.vstack([top_row, bottom_row])
        
        # Append controls instruction strip at bottom (width 1024, height 40)
        footer = np.full((40, 2 * self.tile_w, 3), (18, 12, 8), dtype=np.uint8) # Darker navy footer
        cv2.rectangle(footer, (0, 0), (2 * self.tile_w - 1, 39), self.theme_border, 1)
        
        help_text = "[F] Load File   [W] Webcam   [S] Screen Crop   [V] Scope Mode   [C] Colormap   [P] Pause   [R] Rewind   [ESC] Exit"
        cv2.putText(footer, help_text, (20, 24),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.40, (180, 180, 180), 1, cv2.LINE_AA)
                    
        return np.vstack([grid, footer])


if __name__ == "__main__":
    app = VideoScopesApp()
    try:
        app.run()
    except KeyboardInterrupt:
        print("\n[INFO] Interrupted by keyboard.")
        sys.exit(0)
