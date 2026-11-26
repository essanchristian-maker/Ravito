package com.ravito.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.ravito.app.ui.RavitoRoot
import com.ravito.app.ui.onboarding.hasSmsPermission
import com.ravito.app.ui.theme.RavitoTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {

    private val app get() = application as RavitoApp

    private var locked by mutableStateOf(true)
    private var openTab by mutableStateOf<String?>(null)
    private var backgroundSince = 0L

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            backgroundSince = SystemClock.elapsedRealtime()
        }

        override fun onStart(owner: LifecycleOwner) {
            if (backgroundSince == 0L) return
            val elapsed = (SystemClock.elapsedRealtime() - backgroundSince) / 1000
            if (app.pin.hasPin && elapsed >= app.settings.current.lockTimeoutSeconds) locked = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        // Par défaut, pas de capture ni de partage d'écran (données financières).
        // L'utilisateur peut l'autoriser dans Paramètres → Sécurité ; la version « debug » l'autorise toujours.
        lifecycleScope.launch {
            app.settings.state.collect { s ->
                if (BuildConfig.DEBUG || s.allowScreenCapture) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                else window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        locked = app.pin.hasPin && savedInstanceState?.getBoolean(KEY_UNLOCKED) != true
        openTab = intent?.getStringExtra(EXTRA_OPEN_TAB)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)

        setContent {
            RavitoTheme {
                RavitoRoot(
                    locked = locked,
                    biometricAvailable = biometricUsable(),
                    openTab = openTab,
                    onTabConsumed = { openTab = null },
                    onBiometric = ::showBiometricPrompt,
                    onUnlocked = { locked = false; syncNow() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_TAB)?.let { openTab = it }
    }

    override fun onResume() {
        super.onResume()
        com.ravito.app.telemetry.Telemetry.count(this, com.ravito.app.telemetry.Telemetry.OPENS)
        app.license.refresh()
        // Contact avec le serveur au plus toutes les 30 minutes (licence, version, compteurs).
        if (app.license.checkinDue(30)) lifecycleScope.launch { app.checkinNow() }
        if (!locked) syncNow()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_UNLOCKED, !locked)
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
    }

    /** Rattrapage à chaque ouverture : relit les SMS reçus pendant que l'application était fermée. */
    private fun syncNow() {
        if (!app.settings.current.onboardingDone || !hasSmsPermission()) return
        lifecycleScope.launch { app.repository.syncInbox() }
    }

    private fun biometricUsable(): Boolean =
        app.settings.current.biometricEnabled &&
            BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometricPrompt() {
        if (!biometricUsable()) return
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    locked = false
                    syncNow()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller Ravito")
            .setNegativeButtonText("Utiliser le code PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()
        prompt.authenticate(info)
    }

    companion object {
        const val EXTRA_OPEN_TAB = "open_tab"
        const val TAB_REPORT = "report"
        private const val KEY_UNLOCKED = "unlocked"
    }
}
