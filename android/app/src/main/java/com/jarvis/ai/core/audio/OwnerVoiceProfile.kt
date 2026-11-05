package com.jarvis.ai.core.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opt-in owner speech reference stored encrypted using Android Keystore AES-GCM.
 *
 * This is only an enrollment recording, NOT a verified speaker embedding or
 * security authenticator. A reliable voice verifier, spoof protection and
 * explicit approval for sensitive actions require separate implementation.
 * The raw recording is never written to disk or transmitted to an API.
 */
@Singleton
class OwnerVoiceProfile @Inject constructor(private val context: Context) {
    private val file: File get() = File(context.noBackupFilesDir, "lora_owner_voice.gcm")
    val isEnrolled: Boolean get() = file.isFile

    @SuppressLint("MissingPermission") // Permission is checked before creating AudioRecord.
    fun enroll(durationMs: Long = 3500): Result<Unit> = runCatching {
        check(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        ) { "أعطِ إذن الميكروفون أولاً" }

        val rate = 16000
        val minSize = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minSize > 0) { "تعذّر تهيئة الميكروفون" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            rate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minSize, 4096),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            "الميكروفون غير متاح؛ أوقف الاستماع أولاً"
        }
        val bytes = ByteArrayOutputStream()
        val chunk = ByteArray(2048)
        try {
            recorder.startRecording()
            val limit = (rate * 2L * durationMs / 1000L).toInt()
            while (bytes.size() < limit) {
                val n = recorder.read(chunk, 0, minOf(chunk.size, limit - bytes.size()))
                check(n > 0) { "فشل تسجيل العينة الصوتية" }
                bytes.write(chunk, 0, n)
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            recorder.release()
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(bytes.toByteArray())
        val output = File.createTempFile("lora_voice_", ".tmp", context.noBackupFilesDir)
        try {
            output.outputStream().use {
                it.write(cipher.iv.size)
                it.write(cipher.iv)
                it.write(encrypted)
            }
            check(output.renameTo(file)) { "تعذّر حفظ التسجيل المشفّر" }
        } finally {
            output.delete()
            bytes.reset()
            chunk.fill(0)
        }
    }

    fun delete() {
        if (file.exists()) check(file.delete()) { "تعذّر حذف التسجيل" }
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "lora_owner_voice_v1"
    }
}
