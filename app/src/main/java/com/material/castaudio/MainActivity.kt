package com.material.castaudio

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.os.Build
import android.provider.Settings
import android.net.Uri
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.material.castaudio.ui.theme.SoundDriftTheme
import kotlinx.coroutines.*

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    private lateinit var audioStreamer: AudioStreamer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    )
                }
            }
        }

        audioStreamer = AudioStreamer(this)

        setContent {
            SoundDriftTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    MainScreen(
                        modifier = Modifier.padding(innerPadding),
                        audioStreamer = audioStreamer
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Try to rebind to service if it's running
        audioStreamer.tryRebindToService()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Only unbind, don't stop the service - streaming continues in background
        audioStreamer.unbindFromService()
    }
}

public val Volume_up: ImageVector
    get() {
        if (_Volume_up != null) {
            return _Volume_up!!
        }
        _Volume_up = ImageVector.Builder(
            name = "Volume_up",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 1.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 1.0f,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(560f, 829f)
                verticalLineToRelative(-82f)
                quadToRelative(90f, -26f, 145f, -100f)
                reflectiveQuadToRelative(55f, -168f)
                reflectiveQuadToRelative(-55f, -168f)
                reflectiveQuadToRelative(-145f, -100f)
                verticalLineToRelative(-82f)
                quadToRelative(124f, 28f, 202f, 125.5f)
                reflectiveQuadTo(840f, 479f)
                reflectiveQuadToRelative(-78f, 224.5f)
                reflectiveQuadTo(560f, 829f)
                moveTo(120f, 600f)
                verticalLineToRelative(-240f)
                horizontalLineToRelative(160f)
                lineToRelative(200f, -200f)
                verticalLineToRelative(640f)
                lineTo(280f, 600f)
                close()
                moveToRelative(440f, 40f)
                verticalLineToRelative(-322f)
                quadToRelative(47f, 22f, 73.5f, 66f)
                reflectiveQuadToRelative(26.5f, 96f)
                quadToRelative(0f, 51f, -26.5f, 94.5f)
                reflectiveQuadTo(560f, 640f)
                moveTo(400f, 354f)
                lineToRelative(-86f, 86f)
                horizontalLineTo(200f)
                verticalLineToRelative(80f)
                horizontalLineToRelative(114f)
                lineToRelative(86f, 86f)
                close()
                moveTo(300f, 480f)
            }
        }.build()
        return _Volume_up!!
    }

private var _Volume_up: ImageVector? = null


public val Volume_mute: ImageVector
    get() {
        if (_Volume_mute != null) {
            return _Volume_mute!!
        }
        _Volume_mute = ImageVector.Builder(
            name = "Volume_mute",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 960f,
            viewportHeight = 960f
        ).apply {
            path(
                fill = SolidColor(Color.Black),
                fillAlpha = 1.0f,
                stroke = null,
                strokeAlpha = 1.0f,
                strokeLineWidth = 1.0f,
                strokeLineCap = StrokeCap.Butt,
                strokeLineJoin = StrokeJoin.Miter,
                strokeLineMiter = 1.0f,
                pathFillType = PathFillType.NonZero
            ) {
                moveTo(280f, 600f)
                verticalLineToRelative(-240f)
                horizontalLineToRelative(160f)
                lineToRelative(200f, -200f)
                verticalLineToRelative(640f)
                lineTo(440f, 600f)
                close()
                moveToRelative(80f, -80f)
                horizontalLineToRelative(114f)
                lineToRelative(86f, 86f)
                verticalLineToRelative(-252f)
                lineToRelative(-86f, 86f)
                horizontalLineTo(360f)
                close()
                moveToRelative(100f, -40f)
            }
        }.build()
        return _Volume_mute!!
    }

private var _Volume_mute: ImageVector? = null


class AudioStreamer(private val activity: ComponentActivity) {
    private val preferences = activity.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
    private var mediaProjectionService: MediaProjectionService? = null
    private var _connectionStatus = mutableStateOf("")
    val connectionStatus = _connectionStatus as State<String>
    private var updateJob: Job? = null

    private var _isMicEnabled = mutableStateOf(preferences.getBoolean("mic_enabled", false))
    private var _isDeviceAudioEnabled = mutableStateOf(preferences.getBoolean("device_audio_enabled", true))
    private var _isStreaming = mutableStateOf(false)
    val isMicEnabled = _isMicEnabled as State<Boolean>
    val isDeviceAudioEnabled = _isDeviceAudioEnabled as State<Boolean>
    val isStreaming = _isStreaming as State<Boolean>

    private var _micVolume = mutableFloatStateOf(preferences.getFloat("mic_volume", 1f))
    private var _deviceVolume = mutableFloatStateOf(preferences.getFloat("device_volume", 1f))
    val micVolume = _micVolume as State<Float>
    val deviceVolume = _deviceVolume as State<Float>

    fun setMicVolume(volume: Float) {
        _micVolume.floatValue = volume
        activity.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat("mic_volume", volume).apply()
        mediaProjectionService?.setMicVolume(volume)
    }

    fun setDeviceVolume(volume: Float) {
        _deviceVolume.floatValue = volume
        activity.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putFloat("device_volume", volume).apply()
        mediaProjectionService?.setDeviceVolume(volume)
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as MediaProjectionService.LocalBinder
            mediaProjectionService = binder.getService()

            // Check if this is a rebind (service already has state) or fresh start
            val serviceHasActiveState = mediaProjectionService?.getIsMicEnabled() == true || 
                                        mediaProjectionService?.getIsDeviceAudioEnabled() == true
            
            if (serviceHasActiveState) {
                // Rebind case: sync state FROM the service
                mediaProjectionService?.apply {
                    _isMicEnabled.value = getIsMicEnabled()
                    _isDeviceAudioEnabled.value = getIsDeviceAudioEnabled()
                }
            } else {
                // Fresh start: push local state TO the service
                mediaProjectionService?.apply {
                    setMicEnabled(_isMicEnabled.value)
                    setDeviceAudioEnabled(_isDeviceAudioEnabled.value)
                    setMicVolume(_micVolume.floatValue)
                    setDeviceVolume(_deviceVolume.floatValue)
                }
            }

            _isStreaming.value = true
            startConnectionStatusUpdates()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            mediaProjectionService = null
            updateJob?.cancel()
            _connectionStatus.value = ""
            _isStreaming.value = false
        }
    }

    private fun startConnectionStatusUpdates() {
        updateJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                val clientIP = mediaProjectionService?.getConnectedClientIP() ?: ""
                val clientDeviceName = mediaProjectionService?.getConnectedClientDeviceName() ?: ""

                _connectionStatus.value = if (clientIP.isNotEmpty() &&clientDeviceName.isNotEmpty()) {
                    "Streaming active \n$clientDeviceName \n($clientIP connected)"
                } else {
                    "Streaming active \n(waiting for connection...)"
                }
                delay(1000) // Update every second
            }
        }
    }

    fun setMicEnabled(enabled: Boolean) {
        _isMicEnabled.value = enabled
        activity.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("mic_enabled", enabled).apply()
        mediaProjectionService?.setMicEnabled(enabled)
    }

    fun setDeviceAudioEnabled(enabled: Boolean) {
        _isDeviceAudioEnabled.value = enabled
        activity.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean("device_audio_enabled", enabled).apply()
        mediaProjectionService?.setDeviceAudioEnabled(enabled)
    }

    private var isBound = false

    fun startProjection(resultCode: Int, data: Intent) {
        activity.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(CastAudioSettings.RESUME_AFTER_BOOT, true)
            .putBoolean("mic_enabled", _isMicEnabled.value)
            .putBoolean("device_audio_enabled", _isDeviceAudioEnabled.value)
            .putFloat("mic_volume", _micVolume.floatValue)
            .putFloat("device_volume", _deviceVolume.floatValue)
            .apply()
        val serviceIntent = Intent(activity, MediaProjectionService::class.java).apply {
            putExtra("resultCode", resultCode)
            putExtra("data", data)
        }

        activity.startForegroundService(serviceIntent)
        activity.bindService(
            Intent(activity, MediaProjectionService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
        isBound = true
    }

    fun tryRebindToService() {
        // Only try to bind if service is already running (don't auto-create)
        if (!isBound && !_isStreaming.value) {
            try {
                // Use 0 instead of BIND_AUTO_CREATE to only bind if already running
                val bound = activity.bindService(
                    Intent(activity, MediaProjectionService::class.java),
                    serviceConnection,
                    0  // Don't create service, only bind if running
                )
                if (bound) {
                    isBound = true
                }
            } catch (e: Exception) {
                // Service not running, that's fine
            }
        }
    }

    fun unbindFromService() {
        if (isBound) {
            try {
                activity.unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false
            updateJob?.cancel()
        }
    }

    fun stopStreaming() {
        if (_isStreaming.value) {
            activity.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(CastAudioSettings.RESUME_AFTER_BOOT, false).apply()
            try {
                activity.unbindService(serviceConnection)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isBound = false

            activity.stopService(Intent(activity, MediaProjectionService::class.java))
            updateJob?.cancel()
            _isStreaming.value = false
            _connectionStatus.value = ""
        }
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    audioStreamer: AudioStreamer
) {
    val context = LocalContext.current
    val connectionStatus by audioStreamer.connectionStatus
    val isMicEnabled by audioStreamer.isMicEnabled
    val isDeviceAudioEnabled by audioStreamer.isDeviceAudioEnabled
    val isStreaming by audioStreamer.isStreaming

    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == ComponentActivity.RESULT_OK) {
            result.data?.let { data ->
                audioStreamer.startProjection(result.resultCode, data)
            }
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted && (isMicEnabled || isDeviceAudioEnabled)) {
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        }
    }

    val requestProjection = {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        requestProjection()
    }

    LaunchedEffect(Unit) {
        val activity = context as? MainActivity
        if (activity?.intent?.action == CastAudioSettings.ACTION_RESUME_AFTER_BOOT) {
            activity.intent = activity.intent.apply { action = null }
            requestProjection()
        }
    }

    var micSliderPosition by remember { mutableFloatStateOf(audioStreamer.micVolume.value) }
    var deviceSliderPosition by remember { mutableFloatStateOf(audioStreamer.deviceVolume.value) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {

        Text(
            text = "Enviar Audio",
            style = MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            ),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            textAlign = TextAlign.Start
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh 
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Microphone")
                    Switch(
                        checked = isMicEnabled,
                        onCheckedChange = { audioStreamer.setMicEnabled(it) },
                        enabled = !isStreaming
                    )
                }


                if (isMicEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Volume_mute,
                            contentDescription = "Microphone Volume",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = micSliderPosition,
                            onValueChange = {
                                micSliderPosition = it
                                audioStreamer.setMicVolume(it)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isStreaming
                        )
                        Icon(
                            imageVector = Volume_up,
                            contentDescription = "Microphone Volume Max",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Audio")
                    Switch(
                        checked = isDeviceAudioEnabled,
                        onCheckedChange = { audioStreamer.setDeviceAudioEnabled(it) },
                        enabled = !isStreaming
                    )
                }


                if (isDeviceAudioEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Volume_mute,
                            contentDescription = "Device Volume Min",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                        Slider(
                            value = deviceSliderPosition,
                            onValueChange = {
                                deviceSliderPosition = it
                                audioStreamer.setDeviceVolume(it)
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isStreaming
                        )
                        Icon(
                            imageVector = Volume_up,
                            contentDescription = "Device Volume Max",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Button(
                    onClick = {
                        if (!isStreaming) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) != PackageManager.PERMISSION_GRANTED
                            ) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                requestProjection()
                            }
                        } else {
                            audioStreamer.stopStreaming()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isMicEnabled || isDeviceAudioEnabled
                ) {
                    Text(if (isStreaming) "Stop Streaming" else "Start Streaming")
                }
            }
        }

        if (connectionStatus.isNotEmpty()) {

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        connectionStatus,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }


        }

        Spacer(modifier = Modifier.weight(1f))

        // Download desktop client link
        TextButton(
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://heymeowcat.is-a.dev/SoundDrift-site/#download"))
                context.startActivity(intent)
            }
        ) {
            Text(
                "Download Desktop Client",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
    SoundDriftTheme {
        MainScreen(audioStreamer = AudioStreamer(ComponentActivity()))
    }
}
