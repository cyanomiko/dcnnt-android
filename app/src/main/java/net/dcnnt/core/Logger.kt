package net.dcnnt.core

import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.lang.Exception

/**
 * Simple custom logger for human-readable logs
 */
class DCLogger(val app: App, val name: String, val suffix: String,
               val maxFiles: Int, val maxSize: Long) {
    private val bufSize = 4096
    val path = "${app.directory}/log"
    val file = File("$path/$name.0$suffix")
    private var writer: PrintWriter = getWriter()

    /**
     * Create PrintWriter with buffering, open file in append mode
     */
    private fun getWriter(): PrintWriter {
        val directory = File(path)
        if (!directory.exists()) directory.mkdirs()
        return PrintWriter(BufferedWriter(FileOutputStream(file, true).writer(), bufSize))
    }

    /**
     * Shift all log files
     */
    private fun rotateFiles() {
        writer.close()
        Log.d("DC/Log", "Rotate files")
        for (i in maxFiles - 1 downTo 0) {
            val fileCur = File("$path/$name.${i - 1}$suffix")
            if (fileCur.exists()) {
                fileCur.renameTo(File("$path/$name.${i - 0}$suffix"))
            }
        }
        writer = getWriter()
    }

    /**
     * Dump logging data to persistent storage
     */
    fun dump() {
        Log.d("DC/Log", "Flush writer to storage")
        writer.flush()
    }

    /**
     * Add string to log
     */
    fun log(line: String, tag: String = "DC/Log") {
        writer.println("${nowString()}: $line")
        Log.i(tag, line)
        if (file.length() > maxSize - bufSize) rotateFiles()
    }

    /**
     * Add exception data to log
     */
    fun log(e: Exception, tag: String = "DC/Log") {
        writer.println("")
        writer.println("${nowString()}: EXCEPTION OCCURRED")
        e.printStackTrace(writer)
        Log.e(tag, e.toString())
        if (file.length() > maxSize - bufSize) rotateFiles()
    }
}
