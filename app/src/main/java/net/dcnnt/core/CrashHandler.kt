package net.dcnnt.core

import java.io.File


class DCCrashHandler(val app: App, private val defaultHandler: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler {
    private val SUFFIX = ".crash.txt"
    private val MAX_FILES = 10

    override fun uncaughtException(thread: Thread, exception: Throwable) {
        val directory = File("${app.directory}/log")
        if (!directory.exists()) directory.mkdirs()
        val oldCrushLogs = directory.listFiles()?.filter {
            it.isFile and it.absolutePath.endsWith(SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: listOf()
        if (oldCrushLogs.size > MAX_FILES) {
            oldCrushLogs[0].delete()
        }
        val file = File("${app.directory}/log/${System.currentTimeMillis()}$SUFFIX")
        val writer = file.printWriter()
        exception.printStackTrace(writer)
        writer.flush()
        writer.close()
        defaultHandler!!.uncaughtException(thread, exception)
    }
}