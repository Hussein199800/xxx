package app.rasidi

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rasidi.ui.MainViewModel
import app.rasidi.ui.RasidiRoot
import app.rasidi.ui.screens.LockScreen
import app.rasidi.ui.theme.RasidiTheme

class MainActivity : FragmentActivity() {
    private val vm: MainViewModel by viewModels()
    private var unlocked by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        unlocked = !vm.device.appLock
        setContent {
            val s by vm.settings.collectAsStateWithLifecycle()
            RasidiTheme(s.theme, s.fontScale, s.hideAmounts) {
                if (unlocked) RasidiRoot(vm) else LockScreen(onUnlock = ::authenticate)
            }
        }
        if (!unlocked) authenticate()
    }

    /** قفل التطبيق ببصمة أو رمز الجهاز عبر BiometricPrompt. */
    fun authenticate() {
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { unlocked = true; return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { unlocked = true }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("افتح رصيدي").setSubtitle("بالبصمة أو رمز الجهاز").setAllowedAuthenticators(auth).build())
    }
}
