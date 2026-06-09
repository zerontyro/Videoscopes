package com.example.videoscopes.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.videoscopes.ScopeProcessor
import com.example.videoscopes.data.DefaultDataRepository
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin

fun loadAndScaleBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            
            val maxDim = 640
            var scale = 1
            if (options.outHeight > maxDim || options.outWidth > maxDim) {
                scale = maxOf(options.outHeight / maxDim, options.outWidth / maxDim)
            }
            
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = scale
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel { MainScreenViewModel(DefaultDataRepository()) },
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            hasCameraPermission = granted
        }
    )

    LaunchedEffect(key1 = true) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(25, 15, 11) // BGR (25,15,11) -> Dark Slate Blue (#0B0F19)
    ) {
        if (hasCameraPermission) {
            VideoScopesDashboard()
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Camera permission is required",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(40, 200, 255))
                    ) {
                        Text("Grant Permission", color = Color.Black)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoScopesDashboard() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scopeProcessor = remember { ScopeProcessor() }
    
    // UI parameters state
    var vectorscopeMode by remember { mutableStateOf(0) } // YCrCb / HSV
    var colormapIdx by remember { mutableStateOf(0) }     // Green/Cyan/Fire/Rainbow
    var harmonyMode by remember { mutableStateOf(0) }     // None/Comp/Triadic/Analogous/Split
    var scopeIntensity by remember { mutableStateOf(0.5f) }
    var gain by remember { mutableStateOf(1.0f) }
    var satBoost by remember { mutableStateOf(1.0f) }

    // Latency metrics
    var fps by remember { mutableStateOf(0.0) }
    var latencyMs by remember { mutableStateOf(0.0) }

    // Recomposition trigger
    var scopeUpdateTrigger by remember { mutableStateOf(0L) }

    // Source photo state
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val scaled = loadAndScaleBitmap(context, it)
            if (scaled != null) {
                sourceBitmap = scaled
            }
        }
    }

    // CameraX executor
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    // Sync settings to processor and re-run if photo selected
    LaunchedEffect(sourceBitmap, vectorscopeMode, colormapIdx, harmonyMode, scopeIntensity, gain, satBoost) {
        scopeProcessor.vectorscopeMode = vectorscopeMode
        scopeProcessor.colormapIdx = colormapIdx
        scopeProcessor.harmonyMode = harmonyMode
        scopeProcessor.scopeIntensity = scopeIntensity
        scopeProcessor.gain = gain
        scopeProcessor.satBoost = satBoost

        if (sourceBitmap != null) {
            val start = System.nanoTime()
            scopeProcessor.processFrame(sourceBitmap!!)
            val end = System.nanoTime()
            latencyMs = (end - start) / 1_000_000.0
            fps = 0.0
            scopeUpdateTrigger = System.currentTimeMillis()
        }
    }

    Scaffold(
        bottomBar = {
            DashboardControls(
                vectorscopeMode = vectorscopeMode,
                onModeChange = { vectorscopeMode = it },
                colormapIdx = colormapIdx,
                onColormapChange = { colormapIdx = it },
                harmonyMode = harmonyMode,
                onHarmonyChange = { harmonyMode = it },
                scopeIntensity = scopeIntensity,
                onIntensityChange = { scopeIntensity = it },
                gain = gain,
                onGainChange = { gain = it },
                satBoost = satBoost,
                onSatChange = { satBoost = it }
            )
        },
        containerColor = Color(25, 15, 11)
    ) { paddingValues ->
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLandscape) {
                // Landscape: 60% Left for prioritized Vectorscope, 40% Right for secondary items Column
                Row(modifier = Modifier.weight(1f)) {
                    // Left Pane: Large prioritized Vectorscope
                    Box(
                        modifier = Modifier
                            .weight(1.6f)
                            .fillMaxHeight()
                            .border(1.dp, Color(59, 41, 30)),
                        contentAlignment = Alignment.Center
                    ) {
                        VectorscopeView(scopeProcessor, scopeUpdateTrigger)
                        
                        Text(
                            text = if (vectorscopeMode == 0) "VECTORSCOPE: YCrCb" else "VECTORSCOPE: HSV",
                            color = Color(240, 232, 226),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        )
                    }

                    // Right Pane: Column of 3 smaller panels
                    Column(
                        modifier = Modifier
                            .weight(1.0f)
                            .fillMaxHeight()
                    ) {
                        // 1. Source (Camera Preview / Photo Display)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .border(1.dp, Color(59, 41, 30)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                CameraPreviewView(
                                    executor = cameraExecutor,
                                    scopeProcessor = scopeProcessor,
                                    isPhotoSelected = sourceBitmap != null,
                                    onProcessed = { currentFps, currentLatency ->
                                        if (sourceBitmap == null) {
                                            fps = currentFps
                                            latencyMs = currentLatency
                                            scopeUpdateTrigger = System.currentTimeMillis()
                                        }
                                    }
                                )
                                if (sourceBitmap != null) {
                                    Image(
                                        bitmap = sourceBitmap!!.asImageBitmap(),
                                        contentDescription = "Imported Photo",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(25, 15, 11)),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                            
                            // Text overlays on preview
                            Column(
                                modifier = Modifier
                                    .padding(8.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Text(
                                    text = if (sourceBitmap == null) "Source: Device Camera" else "Source: Imported Photo",
                                    color = Color(0, 255, 255),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(String.format("FPS: %.1f", fps), color = Color(0, 255, 255), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Text(String.format("Latency: %.1fms", latencyMs), color = Color(0, 255, 255), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                            }
                            
                            // Import Toggle Button
                            Button(
                                onClick = {
                                    if (sourceBitmap == null) {
                                        photoPickerLauncher.launch("image/*")
                                    } else {
                                        sourceBitmap = null
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xCC3B291E),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .height(24.dp)
                            ) {
                                Text(
                                    text = if (sourceBitmap == null) "IMPORT PHOTO" else "USE CAMERA",
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 2. Luminance Waveform
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .border(1.dp, Color(59, 41, 30)),
                            contentAlignment = Alignment.Center
                        ) {
                            WaveformView(scopeProcessor, scopeUpdateTrigger)
                            
                            Text(
                                text = "LUMINANCE WAVEFORM",
                                color = Color(240, 232, 226),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                            )
                        }

                        // 3. RGB Parade
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .border(1.dp, Color(59, 41, 30)),
                            contentAlignment = Alignment.Center
                        ) {
                            ParadeView(scopeProcessor, scopeUpdateTrigger)
                            
                            Text(
                                text = "RGB PARADE",
                                color = Color(240, 232, 226),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                            )
                        }
                    }
                }
            } else {
                // Portrait: 55% Top for prioritized Vectorscope, 45% Bottom for secondary items Row
                Column(modifier = Modifier.weight(1f)) {
                    // Top Pane: Large prioritized Vectorscope
                    Box(
                        modifier = Modifier
                            .weight(1.5f)
                            .fillMaxWidth()
                            .border(1.dp, Color(59, 41, 30)),
                        contentAlignment = Alignment.Center
                    ) {
                        VectorscopeView(scopeProcessor, scopeUpdateTrigger)
                        
                        Text(
                            text = if (vectorscopeMode == 0) "VECTORSCOPE: YCrCb" else "VECTORSCOPE: HSV",
                            color = Color(240, 232, 226),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        )
                    }

                    // Bottom Pane: Horizontal Row of 3 smaller panels
                    Row(
                        modifier = Modifier
                            .weight(1.0f)
                            .fillMaxWidth()
                    ) {
                        // 1. Source (Camera Preview / Photo Display)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(1.dp, Color(59, 41, 30)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                CameraPreviewView(
                                    executor = cameraExecutor,
                                    scopeProcessor = scopeProcessor,
                                    isPhotoSelected = sourceBitmap != null,
                                    onProcessed = { currentFps, currentLatency ->
                                        if (sourceBitmap == null) {
                                            fps = currentFps
                                            latencyMs = currentLatency
                                            scopeUpdateTrigger = System.currentTimeMillis()
                                        }
                                    }
                                )
                                if (sourceBitmap != null) {
                                    Image(
                                        bitmap = sourceBitmap!!.asImageBitmap(),
                                        contentDescription = "Imported Photo",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color(25, 15, 11)),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                            }
                            
                            // Text overlays on preview
                            Column(
                                modifier = Modifier
                                    .padding(4.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Text(
                                    text = if (sourceBitmap == null) "Source" else "Photo",
                                    color = Color(0, 255, 255),
                                    fontSize = 8.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(String.format("%.1f FPS", fps), color = Color(0, 255, 255), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                                Text(String.format("%.1fms", latencyMs), color = Color(0, 255, 255), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                            }
                            
                            // Import Toggle Button
                            Button(
                                onClick = {
                                    if (sourceBitmap == null) {
                                        photoPickerLauncher.launch("image/*")
                                    } else {
                                        sourceBitmap = null
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xCC3B291E),
                                    contentColor = Color.White
                                ),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(4.dp)
                                    .height(20.dp)
                            ) {
                                Text(
                                    text = if (sourceBitmap == null) "IMPORT" else "CAMERA",
                                    fontSize = 7.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        // 2. Luminance Waveform
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(1.dp, Color(59, 41, 30)),
                            contentAlignment = Alignment.Center
                        ) {
                            WaveformView(scopeProcessor, scopeUpdateTrigger)
                            
                            Text(
                                text = "WAVEFORM",
                                color = Color(240, 232, 226),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                            )
                        }

                        // 3. RGB Parade
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .border(1.dp, Color(59, 41, 30)),
                            contentAlignment = Alignment.Center
                        ) {
                            ParadeView(scopeProcessor, scopeUpdateTrigger)
                            
                            Text(
                                text = "RGB PARADE",
                                color = Color(240, 232, 226),
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun CameraPreviewView(
    executor: ExecutorService,
    scopeProcessor: ScopeProcessor,
    isPhotoSelected: Boolean,
    onProcessed: (Double, Double) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    
    val currentIsPhotoSelected by rememberUpdatedState(isPhotoSelected)
    
    var lastFrameTime = remember { System.nanoTime() }
    var fpsAccumulator = remember { 30.0 }

    LaunchedEffect(previewView) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            
            // Preview
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Frame Analyzer
            val imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                if (currentIsPhotoSelected) {
                    imageProxy.close()
                    return@setAnalyzer
                }
                val startTime = System.nanoTime()
                
                // Convert ImageProxy to Bitmap using CameraX native utility
                val bitmap = imageProxy.toBitmap()
                if (bitmap != null) {
                    scopeProcessor.processFrame(bitmap)
                }
                
                val endTime = System.nanoTime()
                val latency = (endTime - startTime) / 1_000_000.0
                
                val now = System.nanoTime()
                val elapsed = (now - lastFrameTime) / 1_000_000_000.0
                lastFrameTime = now
                val currentFps = if (elapsed > 0) 1.0 / elapsed else 30.0
                fpsAccumulator = 0.9 * fpsAccumulator + 0.1 * currentFps

                // Notify Main UI thread
                ContextCompat.getMainExecutor(context).execute {
                    onProcessed(fpsAccumulator, latency)
                }

                imageProxy.close()
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (exc: Exception) {
                // Handle binding failure
            }
        }, ContextCompat.getMainExecutor(context))
    }

    AndroidView(
        factory = { previewView },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun VectorscopeView(processor: ScopeProcessor, trigger: Long) {
    val gridColor = Color(80, 60, 45) // Dim warm grid
    val textColor = Color(240, 232, 226)

    val hsvColors = remember {
        listOf(
            Color.Red,         // 0 deg
            Color(0xFFE600FF), // Magenta (300 deg)
            Color.Blue,        // 240 deg
            Color.Cyan,        // 180 deg
            Color.Green,       // 120 deg
            Color.Yellow,      // 60 deg
            Color.Red          // 360 deg
        )
    }
    val ycrcbColors = remember {
        listOf(
            Color(0xFF0088FF), // 0 deg (Blue-Cyan)
            Color.Cyan,        // ~81 deg
            Color.Green,       // ~117 deg
            Color.Yellow,      // ~171 deg
            Color.Red,         // ~261 deg
            Color(0xFFE600FF), // ~297 deg (Magenta)
            Color.Blue,        // ~351 deg
            Color(0xFF0088FF)  // 360 deg
        )
    }

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // Force dependency recomposition on trigger tick
        val _tick = trigger

        val w = size.width
        val h = size.height
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(cx, cy) - 10f

        // Draw margins/Color theory info if dominant hue is available
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 9.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
        }

        // Draw Swatches on Left Margin (if space allows)
        if (w > 320f) {
            // Dominant color swatch
            drawContext.canvas.nativeCanvas.drawText("DOMINANT", 40f, cy - 50f, textPaint)
            drawRect(
                color = Color(processor.dominantColor),
                topLeft = Offset(10f, cy - 40f),
                size = androidx.compose.ui.geometry.Size(60f, 40f)
            )
            drawRect(
                color = Color(59, 41, 30),
                topLeft = Offset(10f, cy - 40f),
                size = androidx.compose.ui.geometry.Size(60f, 40f),
                style = Stroke(1.dp.toPx())
            )
            drawContext.canvas.nativeCanvas.drawText(processor.dominantName, 40f, cy + 15f, textPaint)

            // Suggestion color swatch
            val suggestionLabels = listOf("SUGGEST", "COMPLEMENT", "TRIADIC HARM", "ANALOGOUS", "SPLIT-COMP")
            val harmLabel = suggestionLabels[processor.harmonyMode]
            drawContext.canvas.nativeCanvas.drawText(harmLabel, w - 40f, cy - 50f, textPaint)

            if (processor.harmonyMode > 0 && processor.dominantName != "Neutral") {
                drawRect(
                    color = Color(processor.suggestColor),
                    topLeft = Offset(w - 70f, cy - 40f),
                    size = androidx.compose.ui.geometry.Size(60f, 40f)
                )
                drawRect(
                    color = Color(59, 41, 30),
                    topLeft = Offset(w - 70f, cy - 40f),
                    size = androidx.compose.ui.geometry.Size(60f, 40f),
                    style = Stroke(1.dp.toPx())
                )
                drawContext.canvas.nativeCanvas.drawText(processor.suggestName, w - 40f, cy + 15f, textPaint)
            } else {
                // Placeholder
                drawRect(
                    color = Color(32, 22, 18),
                    topLeft = Offset(w - 70f, cy - 40f),
                    size = androidx.compose.ui.geometry.Size(60f, 40f)
                )
                drawRect(
                    color = Color(59, 41, 30),
                    topLeft = Offset(w - 70f, cy - 40f),
                    size = androidx.compose.ui.geometry.Size(60f, 40f),
                    style = Stroke(1.dp.toPx())
                )
                drawContext.canvas.nativeCanvas.drawText("Inactive", w - 40f, cy + 15f, textPaint)
            }
        }

        // Draw Scope Bitmap Texture (Centered)
        val srcRect = android.graphics.Rect(0, 0, 256, 256)
        val destRect = android.graphics.Rect(
            (cx - radius).toInt(),
            (cy - radius).toInt(),
            (cx + radius).toInt(),
            (cy + radius).toInt()
        )
        drawContext.canvas.nativeCanvas.drawBitmap(
            processor.vectorscopeBitmap,
            srcRect,
            destRect,
            null
        )

        // Draw Reticle Lines on top
        drawCircle(
            color = gridColor,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(1.dp.toPx())
        )

        // Draw Color Wheel Rim
        val hsvBrush = Brush.sweepGradient(hsvColors, center = Offset(cx, cy))
        val ycrcbBrush = Brush.sweepGradient(ycrcbColors, center = Offset(cx, cy))
        val rimWidth = 4.dp.toPx()
        drawCircle(
            brush = if (processor.vectorscopeMode == 0) ycrcbBrush else hsvBrush,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = rimWidth)
        )
        // Frame the gradient rim with inner/outer thin black concentric borders
        drawCircle(
            color = Color.Black,
            radius = radius + rimWidth / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = 0.5.dp.toPx())
        )
        drawCircle(
            color = Color.Black,
            radius = radius - rimWidth / 2f,
            center = Offset(cx, cy),
            style = Stroke(width = 0.5.dp.toPx())
        )
        drawLine(
            color = gridColor,
            start = Offset(cx - radius, cy),
            end = Offset(cx + radius, cy),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = gridColor,
            start = Offset(cx, cy - radius),
            end = Offset(cx, cy + radius),
            strokeWidth = 1.dp.toPx()
        )

        // Draw Color Target boxes
        val boxRadius = 8f
        val colorTargets = if (processor.vectorscopeMode == 0) {
            // YCrCb Target coordinates scaled to circular radius
            listOf(
                Pair(Offset(cx - radius * 0.14f, cy - radius * 0.88f), "R"),
                Pair(Offset(cx + radius * 0.33f, cy - radius * 0.65f), "Mg"),
                Pair(Offset(cx + radius * 0.88f, cy - radius * 0.14f), "B"),
                Pair(Offset(cx + radius * 0.14f, cy + radius * 0.88f), "Cy"),
                Pair(Offset(cx - radius * 0.33f, cy + radius * 0.65f), "G"),
                Pair(Offset(cx - radius * 0.88f, cy + radius * 0.14f), "Yl")
            )
        } else {
            // HSV Targets at standard angles (0, 60, 120, 180, 240, 300)
            listOf(0f, 60f, 120f, 180f, 240f, 300f).mapIndexed { idx, deg ->
                val rad = Math.toRadians(deg.toDouble())
                val tx = cx + radius * 0.9f * cos(rad).toFloat()
                val ty = cy - radius * 0.9f * sin(rad).toFloat()
                val labels = listOf("R", "Yl", "G", "Cy", "B", "Mg")
                Pair(Offset(tx, ty), labels[idx])
            }
        }

        for (target in colorTargets) {
            drawRect(
                color = gridColor,
                topLeft = Offset(target.first.x - boxRadius, target.first.y - boxRadius),
                size = androidx.compose.ui.geometry.Size(boxRadius * 2, boxRadius * 2),
                style = Stroke(1.dp.toPx())
            )
            drawContext.canvas.nativeCanvas.drawText(
                target.second,
                target.first.x,
                target.first.y + 3f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.GRAY
                    textSize = 8.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }

        // Draw Skin tone line (I-Line) at 123 degrees
        if (processor.vectorscopeMode == 0) {
            val skinRad = Math.toRadians(123.0)
            val sx = cx + radius * cos(skinRad).toFloat()
            val sy = cy - radius * sin(skinRad).toFloat()
            drawLine(
                color = Color(230, 150, 40),
                start = Offset(cx, cy),
                end = Offset(sx, sy),
                strokeWidth = 1.dp.toPx()
            )
            drawContext.canvas.nativeCanvas.drawText(
                "SKIN",
                sx + 15f,
                sy,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#E69628")
                    textSize = 7.sp.toPx()
                    textAlign = android.graphics.Paint.Align.CENTER
                }
            )
        }

        // Draw Dotted Harmony Lines
        val theta = processor.dominantTheta
        if (theta != null && processor.harmonyMode > 0) {
            val rLen = radius
            val angles = when (processor.harmonyMode) {
                1 -> listOf(Pair(theta, Color(40, 200, 255)), Pair(theta + Math.PI, Color(240, 100, 240)))
                2 -> listOf(
                    Pair(theta, Color(40, 200, 255)),
                    Pair(theta + 2 * Math.PI / 3, Color(240, 100, 240)),
                    Pair(theta - 2 * Math.PI / 3, Color(240, 100, 240))
                )
                3 -> listOf(
                    Pair(theta, Color(40, 200, 255)),
                    Pair(theta + Math.PI / 6, Color(240, 100, 240)),
                    Pair(theta - Math.PI / 6, Color(240, 100, 240))
                )
                else -> listOf(
                    Pair(theta, Color(40, 200, 255)),
                    Pair(theta + 5 * Math.PI / 6, Color(240, 100, 240)),
                    Pair(theta - 5 * Math.PI / 6, Color(240, 100, 240))
                )
            }

            for (pair in angles) {
                val angle = pair.first
                val color = pair.second
                val ex = cx + rLen * cos(angle).toFloat()
                val ey = cy - rLen * sin(angle).toFloat()

                // Draw simple dotted line using Canvas segments
                val step = 10f
                val length = rLen
                var currentLen = 0f
                while (currentLen < length) {
                    val x1 = cx + currentLen * cos(angle).toFloat()
                    val y1 = cy - currentLen * sin(angle).toFloat()
                    val x2 = cx + (currentLen + 5f).coerceAtMost(length) * cos(angle).toFloat()
                    val y2 = cy - (currentLen + 5f).coerceAtMost(length) * sin(angle).toFloat()
                    drawLine(color = color, start = Offset(x1, y1), end = Offset(x2, y2), strokeWidth = 1.dp.toPx())
                    currentLen += step
                }
            }
        }
    }
}

@Composable
fun WaveformView(processor: ScopeProcessor, trigger: Long) {
    val gridColor = Color(80, 60, 45)
    val textColor = Color(240, 232, 226)

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val _tick = trigger

        val w = size.width
        val h = size.height
        val leftPadding = 50f
        val scopeW = w - leftPadding
        val scopeH = h

        // Draw Graticule Scale Labels
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 8.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        val scales = listOf(
            Pair("100%", 0f),
            Pair("80%", scopeH * 0.2f),
            Pair("60%", scopeH * 0.4f),
            Pair("40%", scopeH * 0.6f),
            Pair("20%", scopeH * 0.8f),
            Pair("0%", scopeH - 1f)
        )

        for (scale in scales) {
            val y = scale.second
            // Tick
            drawLine(color = textColor, start = Offset(leftPadding - 10f, y), end = Offset(leftPadding, y), strokeWidth = 1.dp.toPx())
            // Grid lines
            if (y > 0f && y < scopeH - 5f) {
                drawLine(color = gridColor, start = Offset(leftPadding, y), end = Offset(w, y), strokeWidth = 0.5.dp.toPx())
            } else {
                drawLine(color = Color(59, 41, 30), start = Offset(leftPadding, y), end = Offset(w, y), strokeWidth = 1.dp.toPx())
            }
            // Label
            drawContext.canvas.nativeCanvas.drawText(scale.first, leftPadding - 15f, y + 4f, textPaint)
        }

        // Draw Waveform Texture
        val srcRect = android.graphics.Rect(0, 0, 320, 256)
        val destRect = android.graphics.Rect(
            leftPadding.toInt(),
            0,
            w.toInt(),
            scopeH.toInt()
        )
        drawContext.canvas.nativeCanvas.drawBitmap(
            processor.waveformBitmap,
            srcRect,
            destRect,
            null
        )
    }
}

@Composable
fun ParadeView(processor: ScopeProcessor, trigger: Long) {
    val gridColor = Color(80, 60, 45)
    val textColor = Color(240, 232, 226)

    Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        val _tick = trigger

        val w = size.width
        val h = size.height
        val leftPadding = 50f
        val scopeW = w - leftPadding
        val scopeH = h

        // Draw Graticule Scale Labels
        val textPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            textSize = 8.sp.toPx()
            textAlign = android.graphics.Paint.Align.RIGHT
        }

        val scales = listOf(
            Pair("100%", 0f),
            Pair("80%", scopeH * 0.2f),
            Pair("60%", scopeH * 0.4f),
            Pair("40%", scopeH * 0.6f),
            Pair("20%", scopeH * 0.8f),
            Pair("0%", scopeH - 1f)
        )

        for (scale in scales) {
            val y = scale.second
            drawLine(color = textColor, start = Offset(leftPadding - 10f, y), end = Offset(leftPadding, y), strokeWidth = 1.dp.toPx())
            if (y > 0f && y < scopeH - 5f) {
                drawLine(color = gridColor, start = Offset(leftPadding, y), end = Offset(w, y), strokeWidth = 0.5.dp.toPx())
            } else {
                drawLine(color = Color(59, 41, 30), start = Offset(leftPadding, y), end = Offset(w, y), strokeWidth = 1.dp.toPx())
            }
            drawContext.canvas.nativeCanvas.drawText(scale.first, leftPadding - 15f, y + 4f, textPaint)
        }

        // Draw RGB Parade Texture
        val srcRect = android.graphics.Rect(0, 0, 360, 256)
        val destRect = android.graphics.Rect(
            leftPadding.toInt(),
            0,
            w.toInt(),
            scopeH.toInt()
        )
        drawContext.canvas.nativeCanvas.drawBitmap(
            processor.paradeBitmap,
            srcRect,
            destRect,
            null
        )

        // Draw segment separators
        val channelWidth = scopeW / 3f
        val separatorPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#3B291E")
            strokeWidth = 1.dp.toPx()
        }
        drawContext.canvas.nativeCanvas.drawLine(leftPadding + channelWidth, 0f, leftPadding + channelWidth, scopeH, separatorPaint)
        drawContext.canvas.nativeCanvas.drawLine(leftPadding + channelWidth * 2, 0f, leftPadding + channelWidth * 2, scopeH, separatorPaint)

        // Draw Channel Headers
        val headerPaint = android.graphics.Paint().apply {
            textSize = 8.sp.toPx()
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
        
        headerPaint.color = android.graphics.Color.RED
        drawContext.canvas.nativeCanvas.drawText("RED", leftPadding + channelWidth * 0.5f, 20f, headerPaint)
        headerPaint.color = android.graphics.Color.GREEN
        drawContext.canvas.nativeCanvas.drawText("GREEN", leftPadding + channelWidth * 1.5f, 20f, headerPaint)
        headerPaint.color = android.graphics.Color.BLUE
        drawContext.canvas.nativeCanvas.drawText("BLUE", leftPadding + channelWidth * 2.5f, 20f, headerPaint)
    }
}

@Composable
fun DashboardControls(
    vectorscopeMode: Int,
    onModeChange: (Int) -> Unit,
    colormapIdx: Int,
    onColormapChange: (Int) -> Unit,
    harmonyMode: Int,
    onHarmonyChange: (Int) -> Unit,
    scopeIntensity: Float,
    onIntensityChange: (Float) -> Unit,
    gain: Float,
    onGainChange: (Float) -> Unit,
    satBoost: Float,
    onSatChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(18, 12, 8)) // #120C08 Dark Footer
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Slider 1: Gain
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Gain: ${String.format("%.2fx", gain)}", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(90.dp))
                Slider(
                    value = gain,
                    onValueChange = onGainChange,
                    valueRange = 0.1f..3.0f,
                    modifier = Modifier.weight(1f)
                )
            }

            // Slider 2: Saturation
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sat: ${String.format("%.2fx", satBoost)}", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(90.dp))
                Slider(
                    value = satBoost,
                    onValueChange = onSatChange,
                    valueRange = 0.0f..3.0f,
                    modifier = Modifier.weight(1f)
                )
            }

            // Slider 3: Scope Intensity
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Intensity: ${(scopeIntensity * 100).toInt()}%", color = Color.White, fontSize = 11.sp, modifier = Modifier.width(90.dp))
                Slider(
                    value = scopeIntensity,
                    onValueChange = onIntensityChange,
                    valueRange = 0.1f..1.0f,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Selectors Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Scope Mode
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Scope Mode", color = Color.Gray, fontSize = 10.sp)
                    Row {
                        Button(
                            onClick = { onModeChange(0) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (vectorscopeMode == 0) Color(40, 200, 255) else Color.DarkGray
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("YCrCb", fontSize = 10.sp, color = Color.Black)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = { onModeChange(1) },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (vectorscopeMode == 1) Color(40, 200, 255) else Color.DarkGray
                            ),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("HSV", fontSize = 10.sp, color = Color.Black)
                        }
                    }
                }

                // Colormap Selection
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Colormap", color = Color.Gray, fontSize = 10.sp)
                    val colormaps = listOf("Green", "Cyan", "Fire", "Spectral")
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    Box {
                        Button(
                            onClick = { dropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(colormaps[colormapIdx], fontSize = 10.sp, color = Color.White)
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            colormaps.forEachIndexed { idx, name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onColormapChange(idx)
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Color Harmony Selection
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Harmony", color = Color.Gray, fontSize = 10.sp)
                    val harmonies = listOf("None", "Complementary", "Triadic", "Analogous", "Split-Comp")
                    var dropdownExpanded by remember { mutableStateOf(false) }
                    Box {
                        Button(
                            onClick = { dropdownExpanded = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text(harmonies[harmonyMode], fontSize = 10.sp, color = Color.White)
                        }
                        DropdownMenu(
                            expanded = dropdownExpanded,
                            onDismissRequest = { dropdownExpanded = false }
                        ) {
                            harmonies.forEachIndexed { idx, name ->
                                DropdownMenuItem(
                                    text = { Text(name) },
                                    onClick = {
                                        onHarmonyChange(idx)
                                        dropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
