package dev.kb896.aiusage.update

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kb896.aiusage.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class UpdateState(
    val release: GitHubRelease? = null,
    val check: Check = Check.IDLE,
    val download: Download = Download.IDLE,
) {
    enum class Check { IDLE, CHECKING, CURRENT, LIMITED, FAILED }
    enum class Download { IDLE, DOWNLOADING, READY, FAILED, BLOCKED_HOST, UNVERIFIED }
}

internal class UpdateModel(private val app: Application) : AndroidViewModel(app) {
    private val installed = ReleaseVersion.parse(BuildConfig.VERSION_NAME)
        ?.takeIf { enabled(BuildConfig.DEBUG, app.packageName) }
    private val checks = UpdateChecks(app.getSharedPreferences("github_updates", Context.MODE_PRIVATE))
    private val mutable = MutableStateFlow(UpdateState(release = installed?.let(checks::release)))
    val state = mutable.asStateFlow()
    var apk: File? = null
        private set
    private var installerPending = false

    fun check(manual: Boolean = false) {
        val version = installed ?: return
        val old = state.value
        if (old.check == UpdateState.Check.CHECKING || old.download == UpdateState.Download.DOWNLOADING ||
            (!manual && !checks.automaticDue())) return
        mutable.value = old.copy(check = UpdateState.Check.CHECKING)
        viewModelScope.launch {
            try {
                val checked = withContext(Dispatchers.IO) { checks.check(manual) }
                if (!checked) {
                    mutable.value = old
                    return@launch
                }
                val release = checks.release(version)
                val current = state.value
                if (release != current.release) {
                    apk = null
                    installerPending = false
                }
                mutable.value = current.copy(release = release,
                    check = if (manual && release == null) UpdateState.Check.CURRENT else UpdateState.Check.IDLE,
                    download = if (release == current.release) current.download else UpdateState.Download.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (_: GitHubUpdates.RateLimited) {
                mutable.value = if (manual) old.copy(check = UpdateState.Check.LIMITED) else old
            } catch (_: Exception) {
                mutable.value = if (manual) old.copy(check = UpdateState.Check.FAILED) else old
            }
        }
    }

    fun download() {
        if (installed == null) return
        val old = state.value
        if (old.check == UpdateState.Check.CHECKING) return
        val release = old.release ?: return
        if (old.download == UpdateState.Download.DOWNLOADING) return
        apk = null
        installerPending = false
        mutable.value = old.copy(download = UpdateState.Download.DOWNLOADING)
        viewModelScope.launch {
            val outcome = try {
                apk = withContext(Dispatchers.IO) {
                    UpdateApk.download(app, release)
                }
                installerPending = true
                UpdateState.Download.READY
            } catch (e: CancellationException) {
                throw e
            } catch (_: GitHubUpdates.AssetHostBlocked) {
                UpdateState.Download.BLOCKED_HOST
            } catch (_: UpdateApk.VerificationFailed) {
                UpdateState.Download.UNVERIFIED
            } catch (_: Exception) {
                UpdateState.Download.FAILED
            }
            mutable.value = state.value.copy(download = outcome)
        }
    }

    fun takePendingInstaller(): Boolean = installerPending.also { installerPending = false }

    companion object {
        fun enabled(debug: Boolean, packageName: String): Boolean = !debug && packageName == "dev.chicken.rear"
    }
}
