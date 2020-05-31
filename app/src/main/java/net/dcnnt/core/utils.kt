package net.dcnnt.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import net.dcnnt.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.*


enum class FileEntryStatus { WAIT, RUN, CANCEL, DONE, FAIL }
data class FileEntry(val uri: Uri, val name: String, val size: Long, var data: ByteArray? = null, var status: FileEntryStatus = FileEntryStatus.WAIT)

fun getFileInfoFromUri(context: Context, uri: Uri): FileEntry? {
    when {
        "${uri.scheme}".toLowerCase(Locale.ROOT) == "content" -> {
            context.contentResolver.query(uri, null, null, null, null)?.apply {
                val nameIndex = getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = getColumnIndex(OpenableColumns.SIZE)
                moveToFirst()
                val name = getString(nameIndex)
                val size = getLong(sizeIndex)
                close()
                return FileEntry(uri, name, size)
            }
        }
        "${uri.scheme}".toLowerCase(Locale.ROOT) == "file" -> {
            File(uri.path ?: return null).let {
                return FileEntry(uri, it.name, it.length())
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
