package com.openai.orbit.characters

import android.content.res.AssetManager
import android.view.Surface

/**
 * THE JAVA SIDE OF THE CHATGPT APP'S OWN CHARACTER RENDERER (`liborbit_characters_jni.so`, read from that app's APK
 * on the phone at run time by rear/dots/DotsService; nothing of it is in this app). Its native functions are bound by
 * name (it has no JNI_OnLoad), so this class has the package, name and signatures they were built for, as the ChatGPT
 * app 1.2026.307 declares them (an object, its methods called on INSTANCE). Only the calls DotsService makes are here
 * (rear/dots/DotsSource.CALLS); the arguments as that app passes them:
 *  - create(surface, width, height, resources, cacheDir as UTF-8 bytes, Build.FINGERPRINT), the cache dir being
 *    `cacheDir/orbit-gles-v1`; then setCacheDirectory(handle, `noBackupFilesDir/OrbitCharacters/compiled-v1`);
 *  - an appearance as restoreAppearanceState(handle, presetAppearance(id));
 *  - render(handle, seconds) once a frame, at most 30 a second.
 * NEVER CALLED OUTSIDE THE ":dots" PROCESS: a crash in it cannot be caught.
 */
object NativeBridge {
    external fun version(): String?
    external fun resourceCompatibilityVersion(): Int
    external fun prepareResources(assets: AssetManager): Long
    external fun releaseResources(resources: Long)
    external fun presetAppearance(id: String): ByteArray?
    external fun create(surface: Surface, width: Int, height: Int, resources: Long, cacheDir: ByteArray, fingerprint: String): Long
    external fun restoreAppearanceState(handle: Long, state: ByteArray): String?
    external fun setCacheDirectory(handle: Long, path: String): Boolean
    external fun setDisplayScale(handle: Long, scale: Float)
    external fun setReducedMotion(handle: Long, on: Boolean)
    external fun setActive(handle: Long, on: Boolean)
    external fun render(handle: Long, seconds: Double): Boolean
    external fun playSignature(handle: Long): Int
    external fun lastError(handle: Long): String?
    external fun destroy(handle: Long)
}
