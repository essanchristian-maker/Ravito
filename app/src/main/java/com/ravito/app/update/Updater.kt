package com.ravito.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.ravito.app.BuildConfig
import com.ravito.app.license.Release
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Mise à jour depuis l'application : télécharge la nouvelle version puis ouvre l'écran
 * « Installer » d'Android (une seule confirmation, les données sont conservées).
 */
class Updater(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Downloading(val percent: Int) : State
        data class Error(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun isNewer(r: Release?): Boolean = r != null && r.versionCode > BuildConfig.VERSION_CODE

    /** Android 8+ : Ravito doit être autorisé une fois à installer des mises à jour. */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT >= 26) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    suspend fun downloadAndInstall(release: Release) {
        if (_state.value is State.Downloading) return
        _state.value = State.Downloading(0)
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val out = File(dir, "Ravito-${release.versionCode}.apk")
                var url = URL(release.url)
                var conn: HttpURLConnection
                var hops = 0
                while (true) { // GitHub redirige vers son stockage de fichiers
                    conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000; readTimeout = 60_000; instanceFollowRedirects = false
                    }
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) {
                        url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue
                    }
                    if (code != 200) throw IllegalStateException("HTTP $code")
                    break
                }
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.size
                conn.inputStream.use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            done += n
                            if (total > 0) _state.value = State.Downloading((done * 100 / total).toInt())
                        }
                    }
                }
                conn.disconnect()
                // Vérification : c'est bien Ravito, et plus récent.
                val info = context.packageManager.getPackageArchiveInfo(out.path, 0)
                @Suppress("DEPRECATION")
                val v = info?.let { if (Build.VERSION.SDK_INT >= 28) it.longVersionCode.toInt() else it.versionCode } ?: -1
                if (info?.packageName != context.packageName || v <= BuildConfig.VERSION_CODE) {
                    out.delete(); throw IllegalStateException("fichier invalide")
                }
                out
            } catch (e: Exception) {
                null
            }
        }
        if (file == null) {
            _state.value = State.Error("Téléchargement impossible. Vérifiez votre connexion et réessayez.")
            return
        }
        _state.value = State.Idle
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
