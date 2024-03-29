package net.dcnnt.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCaller
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import net.dcnnt.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.TypeVariable
import java.text.SimpleDateFormat
import java.util.*
import kotlin.collections.ArrayList
import kotlin.reflect.KClass

enum class FileStatus { WAIT, RUN, CANCEL, DONE, FAIL }
enum class EntryType { FILE, LINK }
data class FileEntry(
    val name: String,
    var size: Long,
    var status: FileStatus = FileStatus.WAIT,
    var data: ByteArray? = null,
    var localUri: Uri? = null,
    val remoteIndex: Long? = null,
    val remoteChildren: List<FileEntry>? = null,
    val entryType: EntryType = EntryType.FILE
) {
    val isDir: Boolean = ((remoteIndex == null) and (remoteChildren != null))
    val isLocal: Boolean = ((remoteIndex == null) and (remoteChildren == null))
    val isRemote: Boolean = !isLocal
    val idStr: String = "$name+$size+$localUri+$remoteIndex"
}

fun getFileInfoFromUri(context: Context, uri: Uri): FileEntry? {
    when {
        "${uri.scheme}".lowercase() == "content" -> {
            context.contentResolver.also { cr ->
                val mimeType = cr.getType(uri).toString()
                val fallbackExtension = if (mimeType.contains('/')) mimeType.split('/')[1] else "bin"
                cr.query(uri, null, null, null, null)?.apply {
                    val nameIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = getColumnIndex(OpenableColumns.SIZE)
                    moveToFirst()
                    var name = getString(nameIndex)
                    val size = getLong(sizeIndex)
                    if (!name.contains('.')) name = "$name.$fallbackExtension"
                    close()
                    return FileEntry(name, size, localUri = uri)
                }
            }
        }
        "${uri.scheme}".lowercase() == "file" -> {
            File(uri.path ?: return null).let {
                return FileEntry(it.name, it.length(), localUri = uri)
            }
        }
    }
    return null
}

fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) {
        if (drawable.bitmap != null) {
            return drawable.bitmap
        }
    }
    val bitmap: Bitmap = when((drawable.intrinsicWidth <= 0) || (drawable.intrinsicHeight <= 0)) {
        true -> Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        false -> Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
    }
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

fun bitmapToPNG(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().apply {
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()

fun drawableToPNG(drawable: Drawable) = bitmapToPNG(drawableToBitmap(drawable))

fun mimeTypeByPath(path: String): String {
    MimeTypeMap.getFileExtensionFromUrl(path)?.let {
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) ?: "*/*"
    }
    return "*/*"
}

fun fileIconByPath(path: String): Int {
    val mime = mimeTypeByPath(path)
    if (mime.startsWith("image")) return R.drawable.ic_image
    if (mime.startsWith("audio")) return R.drawable.ic_audiotrack
    if (mime.startsWith("video")) return R.drawable.ic_movie
    if (path.endsWith("zip", false)) return R.drawable.ic_archive
    if (path.endsWith("tar", false)) return R.drawable.ic_archive
    if (path.endsWith("gz", false)) return R.drawable.ic_archive
    if (path.endsWith("xz", false)) return R.drawable.ic_archive
    if (path.endsWith("bz2", false)) return R.drawable.ic_archive
    if (path.endsWith("7z", false)) return R.drawable.ic_archive
    if (path.endsWith("apk", false)) return R.drawable.ic_android
    return R.drawable.ic_file
}

fun fileIcon(name: String?, mime: String?): Int {
    if (mime?.startsWith("image") == true) return R.drawable.ic_image
    if (mime?.startsWith("audio") == true) return R.drawable.ic_audiotrack
    if (mime?.startsWith("video") == true) return R.drawable.ic_movie
    if (name?.endsWith("zip", false) == true) return R.drawable.ic_archive
    if (name?.endsWith("tar", false) == true) return R.drawable.ic_archive
    if (name?.endsWith("gz", false) == true) return R.drawable.ic_archive
    if (name?.endsWith("xz", false) == true) return R.drawable.ic_archive
    if (name?.endsWith("bz2", false) == true) return R.drawable.ic_archive
    if (name?.endsWith("7z", false) == true) return R.drawable.ic_archive
    if (name?.endsWith("apk", false) == true) return R.drawable.ic_android
    return R.drawable.ic_file
}

fun nowString(): String {
    return SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault()).format(Calendar.getInstance().time)
}

fun randId(): Int = (System.currentTimeMillis() and 0x0FFFFF).toInt() + (1673 .. 9574).random()

fun simplifyFilename(filename: String): String {
    return filename.take(21).trim(' ', '-', '.').replace(' ', '_')
        .filter { c -> c.isLetterOrDigit() or (c == '_') }
}

/* Type-safe Parcelable extraction with workaround for old SDK versions */

inline fun <reified T: Parcelable> getParcelable(bundle: Bundle?, key: String): T? {
    if (bundle == null) return null
    return if (Build.VERSION.SDK_INT >= 33) {
        bundle.getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        bundle.getParcelable<Parcelable>(key) as? T
    }
}

inline fun <reified T: Parcelable> getParcelableExtra(intent: Intent, key: String): T? {
    return if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableExtra(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra<Parcelable>(key) as? T
    }
}

inline fun <reified T: Parcelable> getParcelableArrayListExtra(intent: Intent, key: String): ArrayList<T>? {
    return if (Build.VERSION.SDK_INT >= 33) {
        intent.getParcelableArrayListExtra(key, T::class.java)
    } else {
         ArrayList<T>().apply {
             @Suppress("DEPRECATION")
             intent.getParcelableArrayListExtra<Parcelable>(key)?.forEach {
                 this.add((it as? T) ?: return null)
             }
        }
    }
}

fun newActivityCaller(caller: ActivityResultCaller, okResultOnly: Boolean = true,
                       handler: (resultCode: Int, intent: Intent?) -> Unit):
        ActivityResultLauncher<Intent> {
    val launcher = caller.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { result: ActivityResult ->
            if ((result.resultCode == Activity.RESULT_OK) or (!okResultOnly)) {
                handler(result.resultCode, result.data)
            }
    }
    return launcher
}