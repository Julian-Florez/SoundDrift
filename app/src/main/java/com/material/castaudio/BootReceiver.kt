package com.material.castaudio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        val preferences = context.getSharedPreferences(CastAudioSettings.PREFS_NAME, Context.MODE_PRIVATE)
        if (!preferences.getBoolean(CastAudioSettings.RESUME_AFTER_BOOT, false)) return

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationManager.createNotificationChannel(
                NotificationChannel(
                    CastAudioSettings.BOOT_NOTIFICATION_CHANNEL,
                    "Reanudar Enviar Audio",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            action = CastAudioSettings.ACTION_RESUME_AFTER_BOOT
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val resumePendingIntent = PendingIntent.getActivity(
            context,
            10,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(
            context,
            CastAudioSettings.BOOT_NOTIFICATION_CHANNEL
        )
            .setSmallIcon(R.drawable.ic_stat_name)
            .setContentTitle("Enviar Audio está listo para reanudarse")
            .setContentText("Toca para autorizar de nuevo la captura de audio")
            .setContentIntent(resumePendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Reanudar", resumePendingIntent)
            .build()

        notificationManager.notify(1002, notification)
    }
}
