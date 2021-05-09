package net.dcnnt.core

import java.io.File
import java.lang.Exception


class DCCrashHandler(val app: App, private val defaultHandler: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler {
    val SUFFIX = ".crash.txt"
    private val MAX_FILES = 10
    val path = "${app.directory}/log"

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        val directory = File(path)
        if (!directory.exists()) directory.mkdirs()
        val oldCrushLogs = directory.listFiles()?.filter {
            it.isFile and it.absolutePath.endsWith(SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: listOf()
        if (oldCrushLogs.size >= MAX_FILES) {
            oldCrushLogs.lastOrNull()?.delete()
        }
        val file = File("${app.directory}/log/${nowString()}$SUFFIX")
        val writer = file.printWriter()
        exception.printStackTrace(writer)
        writer.flush()
        writer.close()
        try {
            app.dumpLogs()
        } catch (e: Exception) {}
        defaultHandler!!.uncaughtException(thread, exception)
    }
}