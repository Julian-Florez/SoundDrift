package com.material.castaudio

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToLong

class MediaProjectionService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var deviceAudioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var connectionJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val binder = LocalBinder()
    private var isMicEnabled = AtomicBoolean(false)
    private var isDeviceAudioEnabled = AtomicBoolean(false)
    private var audioSocket: DatagramSocket? = null
    @Volatile private var pcSocket: Socket? = null
    @Volatile private var pcAddress: java.net.InetAddress? = null
    @Volatile private var isPcConnected = false
    private val metadataLock = Any()
    private var streamingWakeLock: PowerManager.WakeLock? = null
    private var tcpWriter: PrintWriter? = null
    private var micVolume = 1f
    private var deviceVolume = 1f
    private var connectedClientIP: String = ""
    private var connectedClientDeviceName: String = ""
    private var lastPacketTime = AtomicLong(0L)
    private val pcHost = "nixos.tail1e673a.ts.net"
    private val pcTcpPort = 55557
    private val pcUdpPort = 55555

    private val latencyMeasurements = ConcurrentLinkedQueue<Long>()
    private var maxLatency = 0L

    private val CHANNEL_ID = "SoundDriftServiceChannel"
    private val NOTIFICATION_ID = 1
    private val ACTION_STOP = "com.material.castaudio.STOP_STREAMING"
    private val sampleRate = 44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        channelConfig,
        AudioFormat.ENCODING_PCM_16BIT
    )

    fun getConnectedClientIP(): String = connectedClientIP
    fun getConnectedClientDeviceName(): String = connectedClientDeviceName
    fun getIsMicEnabled(): Boolean = isMicEnabled.get()
    fun getIsDeviceAudioEnabled(): Boolean = isDeviceAudioEnabled.get()
    fun setMicEnabled(enabled: Boolean) {
        isMicEnabled.set(enabled)
        updateAudioRecording()
        sendMetadata()
    }

    fun setDeviceAudioEnabled(enabled: Boolean) {
        isDeviceAudioEnabled.set(enabled)
        updateAudioRecording()
        sendMetadata()
    }

    fun setMicVolume(volume: Float) {
        micVolume = volume
        sendMetadata()
    }

    fun setDeviceVolume(volume: Float) {
        deviceVolume = volume
        sendMetadata()
    }

    private fun sendMetadata() {
        try {
            val metadata = JSONObject().apply {
                put("deviceName", Build.MODEL)
                put("averageLatency", getAverageLatency())
                put("maxLatency", maxLatency)
                put("bufferMs", (bufferSize * 1000 / (sampleRate * 4))) // 4 bytes per sample in stereo
                put("micVolume", micVolume)
                put("deviceVolume", deviceVolume)
                put("isMicEnabled", isMicEnabled.get())
                put("isDeviceAudioEnabled", isDeviceAudioEnabled.get())
            }
            synchronized(metadataLock) {
                tcpWriter?.println(metadata.toString())
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getAverageLatency(): Long {
        return if (latencyMeasurements.isNotEmpty()) {
            latencyMeasurements.average().roundToLong()
        } else 0L
    }

    inner class LocalBinder : Binder() {
        fun getService(): MediaProjectionService = this@MediaProjectionService
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startPcConnectionLoop()
    }

    private fun startPcConnectionLoop() {
        if (connectionJob?.isActive == true) return
        connectionJob = serviceScope.launch {
            while (isActive) {
                val socket = Socket()
                try {
                    val pcIpv4 = InetAddress.getAllByName(pcHost)
                        .firstOrNull { it is Inet4Address }
                        ?: throw IllegalStateException("$pcHost no tiene una dirección IPv4 de Tailscale")
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.connect(InetSocketAddress(pcIpv4, pcTcpPort), 5000)

                    pcSocket = socket
                    pcAddress = socket.inetAddress
                    audioSocket = DatagramSocket()
                    tcpWriter = PrintWriter(socket.getOutputStream(), true)
                    connectedClientIP = socket.inetAddress.hostAddress ?: pcHost
                    connectedClientDeviceName = pcHost
                    isPcConnected = true
                    println("Connected to $pcHost:$pcTcpPort")
                    sendMetadata()

                    val reader = socket.getInputStream().bufferedReader()
                    while (isActive && !socket.isClosed) {
                        if (reader.readLine() == null) break
                    }
                    println("PC connection closed")
                } catch (e: Exception) {
                    if (isActive) println("PC connection failed: ${e.message}; retrying")
                } finally {
                    isPcConnected = false
                    connectedClientIP = ""
                    connectedClientDeviceName = ""
                    pcAddress = null
                    synchronized(metadataLock) {
                        tcpWriter?.close()
                        tcpWriter = null
                    }
                    audioSocket?.close()
                    audioSocket = null
                    if (pcSocket === socket) pcSocket = null
                    runCatching { socket.close() }
                }
                delay(2000)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action from notification
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("CastAudio", MODE_PRIVATE)
                .edit()
                .putBoolean("resume_after_boot", false)
                .apply()
            stopSelf()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra("resultCode", 0) ?: 0
        val data = intent?.getParcelableExtra<Intent>("data")

        if (resultCode != 0 && data != null) {
            startForegroundService()
            acquireStreamingWakeLock()
            startMediaProjection(resultCode, data)
        } else if (intent?.action != ACTION_STOP) {
            stopSelf()
        }
        return START_STICKY
    }

    private fun acquireStreamingWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (streamingWakeLock?.isHeld == true) return
        streamingWakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "$packageName:AudioStreaming"
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startForegroundService() {
        // Intent to open the app when notification is tapped
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Intent to stop streaming from notification
        val stopIntent = Intent(this, MediaProjectionService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Enviar Audio")
            .setContentText("Transmitiendo; reconectando a $pcHost")
            .setSmallIcon(R.drawable.ic_stat_name)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(ContextCompat.getColor(this, R.color.BlueGrey))
            .setContentIntent(openAppPendingIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Streaming", stopPendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startMediaProjection(resultCode: Int, data: Intent) {
        val mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "SoundDrift Service Channel",
            NotificationManager.IMPORTANCE_DEFAULT
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun updateAudioRecording() {
        stopAudioRecording()
        if (isMicEnabled.get() || isDeviceAudioEnabled.get()) {
            if (isMicEnabled.get()) {
                startMicRecording()
            }
            if (isDeviceAudioEnabled.get() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startDeviceAudioCapture()
            }
            startAudioStreaming()
        }
        sendMetadata()
    }

    private fun startAudioStreaming() {
        if (!isMicEnabled.get() && !isDeviceAudioEnabled.get()) return

        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufferSize)
            val mixBuffer = ByteArray(bufferSize)
            var packetCount = 0L

            try {
                while (isActive) {
                    val startTime = System.nanoTime()
                    var totalSize = 0

                    if (isMicEnabled.get() && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val micSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (micSize > 0) {
                            if (isDeviceAudioEnabled.get()) {
                                // If both sources are enabled, copy to mix buffer for later mixing
                                System.arraycopy(buffer, 0, mixBuffer, 0, micSize)
                                totalSize = micSize
                            } else {
                                // If only mic is enabled, apply volume directly
                                applyStereoVolume(buffer, buffer, micSize, micVolume)
                                System.arraycopy(buffer, 0, mixBuffer, 0, micSize)
                                totalSize = micSize
                            }
                        }
                    }

                    if (isDeviceAudioEnabled.get() && deviceAudioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        val deviceSize = deviceAudioRecord?.read(buffer, 0, bufferSize) ?: 0
                        if (deviceSize > 0) {
                            if (totalSize > 0) {
                                // Both sources enabled, mix them
                                mixStereoAudio(mixBuffer, buffer, deviceSize)
                            } else {
                                // Only device audio enabled, apply volume directly
                                applyStereoVolume(buffer, mixBuffer, deviceSize, deviceVolume)
                                totalSize = deviceSize
                            }
                        }
                    }

                    if (totalSize > 0 && isPcConnected) {
                        try {
                            val targetAddress = pcAddress
                            val socket = audioSocket
                            if (isPcConnected && targetAddress != null && socket != null) {
                                socket.send(DatagramPacket(mixBuffer, totalSize, targetAddress, pcUdpPort))
                                lastPacketTime.set(System.currentTimeMillis())
                            }

                            // Calculate and update latency
                            val endTime = System.nanoTime()
                            val latency = (endTime - startTime) / 1_000_000 // Convert to ms
                            updateLatencyMetrics(latency)

                            packetCount++

                            if (packetCount % 100 == 0L) {
                                sendMetadata() // Update client with latest metrics periodically
                            }
                        } catch (e: Exception) {
                            println("Error sending audio packet: ${e.message}")
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                connectedClientIP = ""
            }
        }
    }

    private fun applyStereoVolume(input: ByteArray, output: ByteArray, size: Int, volume: Float) {
        for (i in 0 until size step 4) {
            if (i + 3 >= size) break

            // Left channel
            val leftSample = (input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)
            val scaledLeft = (leftSample.toFloat() / 32768.0f * volume * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Right channel
            val rightSample = (input[i + 3].toInt() shl 8) or (input[i + 2].toInt() and 0xFF)
            val scaledRight = (rightSample.toFloat() / 32768.0f * volume * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Write scaled samples
            output[i] = (scaledLeft and 0xFF).toByte()
            output[i + 1] = ((scaledLeft shr 8) and 0xFF).toByte()
            output[i + 2] = (scaledRight and 0xFF).toByte()
            output[i + 3] = ((scaledRight shr 8) and 0xFF).toByte()
        }
    }

    private fun updateLatencyMetrics(latency: Long) {
        latencyMeasurements.offer(latency)
        if (latencyMeasurements.size > 100) {
            latencyMeasurements.poll()
        }
        maxLatency = maxOf(maxLatency, latency)
    }

    private fun mixStereoAudio(output: ByteArray, input: ByteArray, size: Int) {
        for (i in 0 until size step 4) {
            if (i + 3 >= size) break

            // Left channel
            val leftSample1 = (output[i + 1].toInt() shl 8) or (output[i].toInt() and 0xFF)
            val leftSample2 = (input[i + 1].toInt() shl 8) or (input[i].toInt() and 0xFF)

            // Convert to float for better precision in mixing
            val leftFloat1 = leftSample1.toFloat() / 32768.0f * micVolume
            val leftFloat2 = leftSample2.toFloat() / 32768.0f * deviceVolume
            val mixedLeft = ((leftFloat1 + leftFloat2) * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Right channel
            val rightSample1 = (output[i + 3].toInt() shl 8) or (output[i + 2].toInt() and 0xFF)
            val rightSample2 = (input[i + 3].toInt() shl 8) or (input[i + 2].toInt() and 0xFF)

            val rightFloat1 = rightSample1.toFloat() / 32768.0f * micVolume
            val rightFloat2 = rightSample2.toFloat() / 32768.0f * deviceVolume
            val mixedRight = ((rightFloat1 + rightFloat2) * 32768.0f).toInt().coerceIn(-32768, 32767)

            // Write mixed samples
            output[i] = (mixedLeft and 0xFF).toByte()
            output[i + 1] = ((mixedLeft shr 8) and 0xFF).toByte()
            output[i + 2] = (mixedRight and 0xFF).toByte()
            output[i + 3] = ((mixedRight shr 8) and 0xFF).toByte()
        }
    }

    private fun startMicRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            ).apply {
                if (state == AudioRecord.STATE_INITIALIZED) {
                    startRecording()
                } else {
                    release()
                    audioRecord = null
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            audioRecord?.release()
            audioRecord = null
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun startDeviceAudioCapture() {
        if (mediaProjection == null) return

        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()

            val audioFormat = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(channelConfig)
                .build()

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            deviceAudioRecord = AudioRecord.Builder()
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build().apply {
                    if (state == AudioRecord.STATE_INITIALIZED) {
                        startRecording()
                    } else {
                        release()
                        deviceAudioRecord = null
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            deviceAudioRecord?.release()
            deviceAudioRecord = null
        }
    }

    private fun stopAudioRecording() {
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null

        deviceAudioRecord?.apply {
            stop()
            release()
        }
        deviceAudioRecord = null

        recordingJob?.cancel()
        recordingJob = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }

    override fun onDestroy() {
        connectionJob?.cancel()
        serviceScope.cancel()
        isMicEnabled.set(false)
        isDeviceAudioEnabled.set(false)
        stopAudioRecording()
        mediaProjection?.stop()
        mediaProjection = null
        isPcConnected = false
        pcSocket?.close()
        pcSocket = null
        audioSocket?.close()
        audioSocket = null
        synchronized(metadataLock) {
            tcpWriter?.close()
            tcpWriter = null
        }
        pcAddress = null
        streamingWakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        streamingWakeLock = null
        latencyMeasurements.clear()
        super.onDestroy()
    }
}
