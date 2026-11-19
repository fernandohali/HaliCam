package com.halicam.diagnostic.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import java.util.Locale

/** Leituras rápidas de bateria, temperatura, armazenamento e memória (monitoramento durante a captura). */
object DeviceStatus {
    data class Snapshot(
        val batteryPct: Int,
        val charging: Boolean,
        val temperatureC: Float,
        val freeBytes: Long,
        val availMemMb: Long,
        val lowMemory: Boolean
    )

    fun read(context: Context): Snapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val temp = (battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val free = try { StatFs(Environment.getExternalStorageDirectory().path).availableBytes } catch (_: Exception) { 0L }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return Snapshot(
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            temperatureC = temp,
            freeBytes = free,
            availMemMb = mem.availMem / (1024 * 1024),
            lowMemory = mem.lowMemory
        )
    }

    fun formatBytes(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale("pt", "BR"), "%.1f GB", b / (1L shl 30).toDouble())
        else -> String.format(Locale("pt", "BR"), "%d MB", b / (1L shl 20))
    }

    /** Minutos de gravação restantes estimados para um bitrate total (bits/s). */
    fun minutesLeft(freeBytes: Long, bitsPerSecond: Long): Long =
        if (bitsPerSecond <= 0) 0 else freeBytes * 8 / bitsPerSecond / 60
}
