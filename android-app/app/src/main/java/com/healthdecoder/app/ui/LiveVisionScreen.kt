package com.healthdecoder.app.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.healthdecoder.app.ai.MedicalEngine
import com.healthdecoder.app.ui.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveVisionScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> hasCameraPermission = granted }
    )
    LaunchedEffect(Unit) { if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }
    var identifying by remember { mutableStateOf(false) }
    var identifiedName by remember { mutableStateOf<String?>(null) }
    var statusMsg by remember { mutableStateOf<String?>(null) }

    // Hoisted: tr() is @Composable and can't run inside the capture callback below.
    val msgUnreadable = tr("Couldn't read a medicine name — hold steady and a bit closer, then try again.")
    val msgCaptureFailed = tr("Capture failed. Please try again.")

    fun captureAndIdentify() {
        val capture = imageCaptureRef.value ?: return
        if (identifying) return
        identifying = true; statusMsg = null
        capture.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                    image.close()
                    scope.launch {
                        val name = withContext(Dispatchers.IO) {
                            MedicalEngine.identifyMedicineFromImage(context, bytes, "image/jpeg")
                        }
                        identifying = false
                        if (name.isBlank()) statusMsg = msgUnreadable
                        else identifiedName = name
                    }
                }
                override fun onError(exc: ImageCaptureException) {
                    identifying = false
                    statusMsg = msgCaptureFailed
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TopBarLogo()
                        Text(tr("Smart Health Lens"), fontWeight = FontWeight.Bold)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = tr("Back")
                        )
                    }
                },
                actions = {
                    // LIVE Badge
                    Surface(
                        color = Color(0xFFE53935),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(end = 16.dp)
                    ) {
                        Text(text = tr("● LIVE"),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(3.dp)
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (hasCameraPermission) {
                // Live camera feed
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        val previewView = PreviewView(ctx)
                        val providerFuture = ProcessCameraProvider.getInstance(ctx)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()
                            imageCaptureRef.value = capture
                            try {
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                            } catch (e: Exception) { e.printStackTrace() }
                        }, ContextCompat.getMainExecutor(ctx))
                        previewView.tag = providerFuture
                        previewView
                    },
                    onRelease = onRelease@{ view ->
                        @Suppress("UNCHECKED_CAST")
                        val future = view.tag as? com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> ?: return@onRelease
                        runCatching { future.get().unbindAll() }
                    }
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color(0xFF1E293B)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(tr("Camera permission is needed to identify medicines."), color = Color.White)
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text(tr("Grant permission")) }
                    }
                }
            }

            // Bottom Actions Container
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = statusMsg ?: tr("Point camera at a medicine strip to instantly identify it, see usage instructions, and check for interactions."),
                    color = if (statusMsg != null) Color(0xFFFFB4A2) else Color.White,
                    fontSize = 16.sp,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { captureAndIdentify() },
                        enabled = !identifying && imageCaptureRef.value != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        if (identifying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Text(tr("📸 Identify"))
                        }
                    }
                    Button(
                        onClick = { captureAndIdentify() },
                        enabled = !identifying && imageCaptureRef.value != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text(tr("🎙️ Ask AI"))
                    }
                }
            }
        }
    }

    // The identified medicine's details in the existing bottom sheet.
    identifiedName?.let { name ->
        MedicineInfoSheet(medicineName = name, onDismiss = { identifiedName = null })
    }
}
