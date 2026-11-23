package app.rasidi.backup

import android.content.Context
import app.rasidi.core.backup.drive.DriveRestClient
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.TimeUnit

/**
 * تفويض Google Identity Services (AuthorizationClient) لنطاق drive.appdata فقط.
 * لا تسجيل دخول ولا قراءة لملفات المستخدم الأخرى: التطبيق يرى خزنة appDataFolder الخاصة به.
 */
object DriveAuth {
    fun request(): AuthorizationRequest = AuthorizationRequest.builder()
        .setRequestedScopes(listOf(Scope(DriveRestClient.SCOPE)))
        .build()

    fun playServicesAvailable(ctx: Context) =
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(ctx) == ConnectionResult.SUCCESS

    sealed interface Silent {
        data class Token(val value: String) : Silent
        /** يلزم تفاعل المستخدم: «أعد ربط Google». */
        data object NeedsUser : Silent
        data class Unavailable(val reason: String) : Silent
    }

    /** رمز وصول دون واجهة (للعامل في الخلفية). يُستدعى خارج الخيط الرئيسي. */
    fun silentToken(ctx: Context): Silent {
        if (!playServicesAvailable(ctx)) return Silent.Unavailable("خدمات Google Play غير متوفرة على هذا الجهاز")
        return try {
            val r: AuthorizationResult = Tasks.await(Identity.getAuthorizationClient(ctx).authorize(request()), 30, TimeUnit.SECONDS)
            if (r.hasResolution()) Silent.NeedsUser else r.accessToken?.let { Silent.Token(it) } ?: Silent.NeedsUser
        } catch (e: Exception) {
            Silent.Unavailable(e.cause?.message ?: e.message ?: "تعذّر التفويض")
        }
    }

    /** يمسح رمزاً مرفوضاً من ذاكرة Google حتى يُطلب رمز جديد. */
    fun clearToken(ctx: Context, token: String) { runCatching { GoogleAuthUtil.clearToken(ctx, token) } }
}
