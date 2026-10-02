package vn.viettechtrans.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Why did the app close last time? Records uncaught JVM exceptions to a file and
 * reads Android's exit reasons (API 30+: low memory kill, native crash, ANR …),
 * so a crash on a phone can be diagnosed without a USB cable.
 */
object Diagnostics {
    private const val CRASH_FILE = "diagnostics/last_crash.txt"

    data class LastExit(val whenText: String, val reason: String, val description: String?, val pssMb: Long, val rssMb: Long)

    fun install(context: Context) {
        val file = File(context.filesDir, CRASH_FILE)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.parentFile?.mkdirs()
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                file.writeText("${stamp(System.currentTimeMillis())} thread=${thread.name}\n$sw")
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun lastCrash(context: Context): String? =
        File(context.filesDir, CRASH_FILE).takeIf { it.isFile }?.readText()?.take(4_000)

    fun lastExit(context: Context): LastExit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val am = context.getSystemService(ActivityManager::class.java) ?: return null
        val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
        return LastExit(
            whenText = stamp(info.timestamp),
            reason = reasonText(info.reason),
            description = info.description,
            pssMb = info.pss / 1024,
            rssMb = info.rss / 1024,
        )
    }

    /** Current memory of this process and of the device (shown in Settings). */
    fun memorySummary(context: Context): String {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val dmi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return "App: ${dmi.totalPss / 1024} MB (PSS) · Máy: còn ${mi.availMem shr 20} / ${mi.totalMem shr 20} MB" +
            if (mi.lowMemory) " · máy đang thiếu RAM" else ""
    }

    private fun reasonText(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_LOW_MEMORY -> "Hệ thống đóng app vì thiếu RAM"
        ApplicationExitInfo.REASON_CRASH -> "Lỗi Java/Kotlin (crash)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "Lỗi native (thư viện C++)"
        ApplicationExitInfo.REASON_ANR -> "App không phản hồi (ANR)"
        ApplicationExitInfo.REASON_EXIT_SELF -> "App tự thoát"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "Người dùng đóng app"
        ApplicationExitInfo.REASON_USER_STOPPED -> "Người dùng buộc dừng app"
        ApplicationExitInfo.REASON_SIGNALED -> "Bị hệ thống dừng (signal)"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Dùng quá nhiều tài nguyên"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Quyền thay đổi"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Thành phần phụ thuộc bị dừng"
        ApplicationExitInfo.REASON_OTHER -> "Lý do khác"
        else -> "Mã $reason"
    }

    private fun stamp(ms: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date(ms))
}
