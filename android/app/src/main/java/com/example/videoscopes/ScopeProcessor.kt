package com.example.videoscopes

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

class ScopeProcessor {

    // Output Bitmaps
    var vectorscopeBitmap: Bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
    var waveformBitmap: Bitmap = Bitmap.createBitmap(320, 256, Bitmap.Config.ARGB_8888)
    var paradeBitmap: Bitmap = Bitmap.createBitmap(360, 256, Bitmap.Config.ARGB_8888)

    // Temp buffers
    private val vecPixels = IntArray(128 * 128)
    private val wfPixels = IntArray(128 * 320)
    private val paradePixels = IntArray(128 * 360)

    private val vecOutPixels = IntArray(256 * 256)
    private val wfOutPixels = IntArray(320 * 256)
    private val paradeOutPixels = IntArray(360 * 256)

    // Scope settings
    var vectorscopeMode = 0 // 0: YCrCb, 1: HSV
    var colormapIdx = 0     // 0: Green, 1: Cyan, 2: Fire, 3: Rainbow
    var harmonyMode = 0     // 0: None, 1: Comp, 2: Triadic, 3: Analogous, 4: Split-Comp
    var scopeIntensity = 0.5f
    var gain = 1.0f
    var satBoost = 1.0f

    // Color Theory Outputs
    var dominantName = "Neutral"
    var dominantColor = Color.GRAY
    var suggestName = "N/A"
    var suggestColor = Color.DKGRAY
    var dominantHue = 0f
    var dominantTheta: Double? = null

    fun processFrame(frameBitmap: Bitmap) {
        // Enhance brightness/saturation if needed
        val enhanced = enhanceBitmap(frameBitmap)

        // Generate Scopes
        generateVectorscope(enhanced)
        generateWaveform(enhanced)
        generateRGBParade(enhanced)
    }

    private fun enhanceBitmap(src: Bitmap): Bitmap {
        if (gain == 1.0f && satBoost == 1.0f) return src
        
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        
        val hsv = FloatArray(3)
        for (i in pixels.indices) {
            val color = pixels[i]
            var r = (color shr 16) and 0xFF
            var g = (color shr 8) and 0xFF
            var b = color and 0xFF

            // Apply gain
            if (gain != 1.0f) {
                r = (r * gain).toInt().coerceIn(0, 255)
                g = (g * gain).toInt().coerceIn(0, 255)
                b = (b * gain).toInt().coerceIn(0, 255)
            }

            // Apply saturation boost
            if (satBoost != 1.0f) {
                Color.RGBToHSV(r, g, b, hsv)
                hsv[1] = (hsv[1] * satBoost).coerceIn(0f, 1f)
                pixels[i] = Color.HSVToColor((color shr 24) and 0xFF, hsv)
            } else {
                pixels[i] = (color and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
        }
        
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun colorize(intensity: Float): Int {
        val u = (intensity * 255).toInt().coerceIn(0, 255)
        return when (colormapIdx) {
            0 -> { // Green Phosphor
                Color.rgb((u * 0.10f).toInt(), u, (u * 0.15f).toInt())
            }
            1 -> { // Cyan Phosphor
                Color.rgb((u * 0.20f).toInt(), (u * 0.85f).toInt(), u)
            }
            2 -> { // Fire
                val r = (u * 1.5f).toInt().coerceIn(0, 255)
                val g = (u * 0.8f).toInt().coerceIn(0, 255)
                val b = (u * 0.2f).toInt().coerceIn(0, 255)
                Color.rgb(r, g, b)
            }
            else -> { // Rainbow (approximation)
                val h = (1.0f - intensity) * 240f
                Color.HSVToColor(floatArrayOf(h, 1f, 1f))
            }
        }
    }

    private fun getHueColor(h: Float): Int {
        return Color.HSVToColor(floatArrayOf(h, 0.9f, 0.8f))
    }

    private fun getHueName(h: Float): String {
        val angle = h % 360
        return when {
            angle >= 345 || angle < 15 -> "Red"
            angle < 45 -> "Orange"
            angle < 75 -> "Yellow"
            angle < 105 -> "Yellow-Green"
            angle < 135 -> "Green"
            angle < 165 -> "Teal/Green-Cyan"
            angle < 195 -> "Cyan"
            angle < 225 -> "Light Blue"
            angle < 255 -> "Blue"
            angle < 285 -> "Indigo/Violet"
            angle < 315 -> "Magenta"
            else -> "Pink"
        }
    }

    private fun generateVectorscope(src: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(src, 128, 128, true)
        scaled.getPixels(vecPixels, 0, 128, 0, 0, 128, 128)

        val hist = FloatArray(256 * 256)
        val hsv = FloatArray(3)

        for (color in vecPixels) {
            val r = (color shr 16) and 0xFF
            val g = (color shr 8) and 0xFF
            val b = color and 0xFF

            if (vectorscopeMode == 0) { // YCrCb
                val cr = (128 + 0.5f * r - 0.4187f * g - 0.0813f * b).toInt().coerceIn(0, 255)
                val cb = (128 - 0.1687f * r - 0.3313f * g + 0.5f * b).toInt().coerceIn(0, 255)
                val x = cb
                val y = 255 - cr
                hist[y * 256 + x] += 1f
            } else { // HSV Polar
                Color.RGBToHSV(r, g, b, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val rad = Math.toRadians(hue.toDouble())
                val radius = sat * 120.0
                val x = (128 + radius * cos(rad)).toInt().coerceIn(0, 255)
                val y = (128 - radius * sin(rad)).toInt().coerceIn(0, 255)
                hist[y * 256 + x] += 1f
            }
        }

        // Color Theory Peak Detection (exclude center neutral noise)
        var peakX = 128
        var peakY = 128
        var peakVal = 0f
        for (y in 0..255) {
            for (x in 0..255) {
                val dx = x - 128
                val dy = y - 128
                val dist = sqrt((dx * dx + dy * dy).toDouble())
                if (dist in 20.0..120.0) {
                    val v = hist[y * 256 + x]
                    if (v > peakVal) {
                        peakVal = v
                        peakX = x
                        peakY = y
                    }
                }
            }
        }

        if (peakVal > 0) {
            val dx = peakX - 128
            val dy = 128 - peakY
            val theta = atan2(dy.toDouble(), dx.toDouble())
            dominantTheta = theta

            if (vectorscopeMode == 0) { // YCrCb mode peak conversion
                val cr = 255 - peakY
                val cb = peakX
                // Back conversion YCrCb -> RGB -> HSV
                val r = (128 + 1.402f * (cr - 128)).toInt().coerceIn(0, 255)
                val g = (128 - 0.344136f * (cb - 128) - 0.714136f * (cr - 128)).toInt().coerceIn(0, 255)
                val b = (128 + 1.772f * (cb - 128)).toInt().coerceIn(0, 255)
                Color.RGBToHSV(r, g, b, hsv)
                dominantHue = hsv[0]
            } else { // HSV Mode
                dominantHue = (Math.toDegrees(theta).toFloat() + 360f) % 360f
            }

            dominantColor = getHueColor(dominantHue)
            dominantName = getHueName(dominantHue)

            val suggestHue = (dominantHue + 180f) % 360f
            suggestColor = getHueColor(suggestHue)
            suggestName = getHueName(suggestHue)
        } else {
            dominantName = "Neutral"
            dominantColor = Color.GRAY
            suggestName = "N/A"
            suggestColor = Color.DKGRAY
            dominantTheta = null
        }

        // Normalize and draw pixels
        var maxHist = 0f
        for (v in hist) {
            if (v > maxHist) maxHist = v
        }

        for (i in hist.indices) {
            val hVal = hist[i]
            if (hVal > 0 && maxHist > 0) {
                // Apply Intensity gamma curve
                val normalized = (hVal / maxHist)
                val curve = normalized.pow(scopeIntensity)
                vecOutPixels[i] = colorize(curve)
            } else {
                vecOutPixels[i] = 0x00000000 // transparent
            }
        }

        vectorscopeBitmap.setPixels(vecOutPixels, 0, 256, 0, 0, 256, 256)
    }

    private fun generateWaveform(src: Bitmap) {
        val scaled = Bitmap.createScaledBitmap(src, 320, 128, true)
        scaled.getPixels(wfPixels, 0, 320, 0, 0, 320, 128)

        val hist = FloatArray(320 * 256)

        for (c in 0..319) {
            for (r in 0..127) {
                val color = wfPixels[r * 320 + c]
                val red = (color shr 16) and 0xFF
                val green = (color shr 8) and 0xFF
                val blue = color and 0xFF

                // Rec 709 Luminance formula
                val y = (0.2126f * red + 0.7152f * green + 0.0722f * blue).toInt().coerceIn(0, 255)
                hist[(255 - y) * 320 + c] += 1f
            }
        }

        var maxHist = 0f
        for (v in hist) {
            if (v > maxHist) maxHist = v
        }

        for (i in hist.indices) {
            val hVal = hist[i]
            if (hVal > 0 && maxHist > 0) {
                val curve = (hVal / maxHist).pow(scopeIntensity)
                wfOutPixels[i] = colorize(curve)
            } else {
                wfOutPixels[i] = 0x00000000
            }
        }

        waveformBitmap.setPixels(wfOutPixels, 0, 320, 0, 0, 320, 256)
    }

    private fun generateRGBParade(src: Bitmap) {
        // Total width: 360 (Red: 120, Green: 120, Blue: 120)
        val scaled = Bitmap.createScaledBitmap(src, 360, 128, true)
        scaled.getPixels(paradePixels, 0, 360, 0, 0, 360, 128)

        val hist = FloatArray(360 * 256)

        // Process columns
        for (c in 0..359) {
            val channel = c / 120 // 0: Red, 1: Green, 2: Blue
            for (r in 0..127) {
                val color = paradePixels[r * 360 + c]
                val value = when (channel) {
                    0 -> (color shr 16) and 0xFF // Red
                    1 -> (color shr 8) and 0xFF  // Green
                    else -> color and 0xFF       // Blue
                }
                hist[(255 - value) * 360 + c] += 1f
            }
        }

        var maxHist = 0f
        for (v in hist) {
            if (v > maxHist) maxHist = v
        }

        for (c in 0..359) {
            val channel = c / 120
            for (y in 0..255) {
                val idx = y * 360 + c
                val hVal = hist[idx]
                if (hVal > 0 && maxHist > 0) {
                    val curve = (hVal / maxHist).pow(scopeIntensity)
                    val u = (curve * 255).toInt().coerceIn(0, 255)
                    paradeOutPixels[idx] = when (channel) {
                        0 -> Color.rgb(u, (u * 0.1f).toInt(), (u * 0.1f).toInt()) // Red channel
                        1 -> Color.rgb((u * 0.1f).toInt(), u, (u * 0.1f).toInt()) // Green channel
                        else -> Color.rgb((u * 0.1f).toInt(), (u * 0.1f).toInt(), u) // Blue channel
                    }
                } else {
                    paradeOutPixels[idx] = 0x00000000
                }
            }
        }

        paradeBitmap.setPixels(paradeOutPixels, 0, 360, 0, 0, 360, 256)
    }
}
