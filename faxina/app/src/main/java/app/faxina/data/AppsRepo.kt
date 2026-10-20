package app.faxina.data

import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Process
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

class AppItem(
    val pkg: String,
    val label: String,
    /** código + dados + cache; null se o Android não deixou ler (falta acesso de uso). */
    val total: Long?,
    val cache: Long?,
    val lastUsed: Long?,
    val installedAt: Long,
)

object AppsRepo {
    suspend fun load(ctx: Context, canSeeStats: Boolean): List<AppItem> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val usage = if (canSeeStats) {
            try { usm.queryAndAggregateUsageStats(now - 400L * 24 * 3600 * 1000, now) } catch (_: Exception) { emptyMap() }
        } else {
            emptyMap()
        }
        val apps = pm.getInstalledApplications(0).filter { ai ->
            ai.packageName != ctx.packageName &&
                (ai.flags and ApplicationInfo.FLAG_SYSTEM == 0 || ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0) &&
                pm.getLaunchIntentForPackage(ai.packageName) != null
        }
        coroutineScope {
            apps.chunked(16).flatMap { chunk ->
                chunk.map { ai ->
                    async {
                        var total: Long? = null
                        var cache: Long? = null
                        if (canSeeStats) {
                            try {
                                val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                                total = st.appBytes + st.dataBytes
                                cache = st.cacheBytes
                            } catch (_: Exception) {
                            }
                        }
                        val installed = try {
                            pm.getPackageInfo(ai.packageName, 0).firstInstallTime
                        } catch (_: PackageManager.NameNotFoundException) { 0L }
                        AppItem(
                            pkg = ai.packageName,
                            label = pm.getApplicationLabel(ai).toString(),
                            total = total,
                            cache = cache,
                            lastUsed = usage[ai.packageName]?.lastTimeUsed?.takeIf { it > 0 },
                            installedAt = installed,
                        )
                    }
                }.awaitAll()
            }
        }
    }

    /** Soma o espaço de todos os apps instalados (para o anel do início). Null sem acesso de uso. */
    suspend fun totalAppsBytes(ctx: Context): Long? = withContext(Dispatchers.IO) {
        try {
            val pm = ctx.packageManager
            val ssm = ctx.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            var sum = 0L
            for (ai in pm.getInstalledApplications(0)) {
                try {
                    val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, ai.packageName, Process.myUserHandle())
                    sum += st.appBytes + st.dataBytes
                } catch (_: Exception) {
                }
            }
            sum
        } catch (_: Exception) {
            null
        }
    }
}
