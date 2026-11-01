package com.bleural.croissant.backup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import com.bleural.croissant.BuildConfig
import com.bleural.croissant.Prefs
import com.bleural.croissant.ask.KeysStore
import com.bleural.croissant.ask.KeystoreSealer
import com.bleural.croissant.data.Dates
import java.io.File
import java.io.IOException
import java.time.OffsetDateTime

/** This C1's serial cannot be read, so an archive cannot say whose it is. */
class SerialUnreadable(message: String) : IOException(message)

/**
 * What ties backup and restore to this C1: its device-protected files, its serial, its
 * settings file and its keystore. The archive's logic itself (ArchiveMaker, RestoreApplier)
 * takes plain files and runs on the JVM in the tests.
 */
object BackupDevice {

    val app: AppVersion get() = AppVersion(BuildConfig.APPLICATION_ID, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    /** Device-protected, like the prefs: the app starts before the user's storage is unlocked. */
    fun files(ctx: Context): File = ctx.createDeviceProtectedStorageContext().filesDir

    fun restoreDir(ctx: Context) = File(files(ctx), BackupConfig.RESTORE_DIR)
    fun outcomeFile(ctx: Context) = File(files(ctx), BackupConfig.OUTCOME_FILE)
    fun uploadFile(ctx: Context) = File(ctx.createDeviceProtectedStorageContext().cacheDir, BackupConfig.UPLOAD_FILE)
    fun outFile(ctx: Context, stem: String) = File(File(files(ctx), BackupConfig.OUT_DIR), stem + BackupConfig.ZIP)

    /**
     * The serial, as the manifest records it and a restore compares it. `Build.getSerial()`
     * needs READ_PHONE_STATE and Device Owner (Kiosk.apply grants the first as the second);
     * without them it throws, and that is said, never replaced by a made-up serial.
     */
    @SuppressLint("HardwareIds", "MissingPermission")
    fun serial(): String {
        val s = try {
            Build.getSerial()
        } catch (e: SecurityException) {
            throw SerialUnreadable("this C1's serial cannot be read (SecurityException: ${e.message}): " +
                "the app reads it as Device Owner with READ_PHONE_STATE")
        }
        if (s.isNullOrBlank() || s == Build.UNKNOWN) throw SerialUnreadable("this C1's serial reads as \"$s\"")
        return s
    }

    /** This C1's archive into [out]. Throws [SerialUnreadable], or [IOException] naming what failed. */
    fun make(ctx: Context, out: File): Made {
        val files = files(ctx)
        val origin = Origin(app, serial(), Dates.stamp(OffsetDateTime.now()))
        return ArchiveMaker(files).make(out, Prefs.values(ctx), KeysStore(File(files, BackupConfig.KEYS), KeystoreSealer()), origin)
    }

    /** The restore waiting in restore/, applied (CroissantApp.kt); null when there was none. */
    fun applyRestore(ctx: Context): RestoreOutcome? =
        RestoreApplier(files(ctx), KeystoreSealer(), prefsOf(ctx), app, ::serial).run()

    private fun prefsOf(ctx: Context) = object : PrefsAccess {
        override fun read(): Map<String, Any?> = Prefs.values(ctx)
        override fun replace(values: Map<String, Any>) {
            if (!Prefs.replaceValues(ctx, values)) throw IOException("the settings file could not be written")
        }
    }
}
