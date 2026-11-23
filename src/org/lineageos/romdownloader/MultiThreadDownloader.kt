package org.lineageos.romdownloader

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MultiThreadDownloader(
    private val context: Context,
    private val threadCount: Int = 6
) {
    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long)
        fun onCompleted(fileName: String)
        fun onError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    fun download(downloadUrl: String, fileName: String, listener: Listener) {
        val managerExecutor = Executors.newSingleThreadExecutor()
        managerExecutor.execute {
            try {
                val probe = resolveDirectUrl(downloadUrl)
                val directUrl = probe.finalUrl
                val totalBytes = probe.contentLength
                val supportsRanges = probe.supportsRanges

                val tempFile = File(context.cacheDir, "$fileName.part")
                if (tempFile.exists()) tempFile.delete()

                val effectiveThreads = if (supportsRanges && totalBytes > 1024 * 1024) threadCount else 1
                val downloadedBytes = AtomicLong(0L)

                if (totalBytes > 0) {
                    RandomAccessFile(tempFile, "rw").use { it.setLength(totalBytes) }
                }

                var lastBytes = 0L
                var lastTime = System.currentTimeMillis()

                val reportTask = Runnable {
                    val now = System.currentTimeMillis()
                    val current = downloadedBytes.get()
                    val elapsedMs = (now - lastTime).coerceAtLeast(1L)
                    val speed = ((current - lastBytes) * 1000L) / elapsedMs
                    lastBytes = current
                    lastTime = now

                    mainHandler.post {
                        listener.onProgress(current, totalBytes, speed)
                    }
                }

                val workerPool = Executors.newFixedThreadPool(effectiveThreads)
                val tasks = mutableListOf<Callable<Unit>>()

                if (effectiveThreads == 1) {
                    tasks.add(Callable {
                        downloadChunk(directUrl, tempFile, 0L, -1L, downloadedBytes)
                    })
                } else {
                    val chunkSize = totalBytes / effectiveThreads
                    for (i in 0 until effectiveThreads) {
                        val start = i * chunkSize
                        val end = if (i == effectiveThreads - 1) totalBytes - 1 else (start + chunkSize - 1)
                        tasks.add(Callable {
                            downloadChunk(directUrl, tempFile, start, end, downloadedBytes)
                        })
                    }
                }

                val progressThread = Thread {
                    while (!Thread.currentThread().isInterrupted && !isCancelled.get()) {
                        reportTask.run()
                        try {
                            Thread.sleep(500)
                        } catch (_: InterruptedException) {
                            break
                        }
                    }
                }.apply { start() }

                try {
                    val futures = workerPool.invokeAll(tasks)
                    for (future in futures) {
                        future.get()
                    }
                } finally {
                    progressThread.interrupt()
                    workerPool.shutdownNow()
                }

                if (isCancelled.get()) {
                    tempFile.delete()
                    return@execute
                }

                moveToDownloads(tempFile, fileName)

                mainHandler.post {
                    listener.onCompleted(fileName)
                }
            } catch (e: Exception) {
                if (!isCancelled.get()) {
                    mainHandler.post {
                        listener.onError(e.localizedMessage ?: e.javaClass.simpleName)
                    }
                }
            } finally {
                managerExecutor.shutdown()
            }
        }
    }

    private data class ProbeResult(
        val finalUrl: String,
        val contentLength: Long,
        val supportsRanges: Boolean
    )

    private fun resolveDirectUrl(initialUrl: String): ProbeResult {
        var currentUrl = initialUrl
        var redirects = 0

        while (redirects < 10) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", "Wget/1.21.4")
                setRequestProperty("Range", "bytes=0-0")
            }

            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                    ?: throw IllegalStateException("Redirect without Location")
                currentUrl = URL(URL(currentUrl), location).toString()
                conn.disconnect()
                redirects++
                continue
            }

            val supportsRanges = (code == HttpURLConnection.HTTP_PARTIAL)
            var totalSize = -1L

            if (supportsRanges) {
                val contentRange = conn.getHeaderField("Content-Range")
                totalSize = contentRange?.substringAfterLast("/")?.toLongOrNull() ?: -1L
            }
            if (totalSize <= 0L) {
                totalSize = conn.contentLengthLong
            }

            conn.disconnect()
            return ProbeResult(currentUrl, totalSize, supportsRanges)
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun downloadChunk(
        urlStr: String,
        targetFile: File,
        startByte: Long,
        endByte: Long,
        downloadedCounter: AtomicLong
    ) {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Wget/1.21.4")
            if (endByte >= startByte) {
                setRequestProperty("Range", "bytes=$startByte-$endByte")
            }
        }

        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            throw IllegalStateException("HTTP $code on chunk $startByte-$endByte")
        }

        conn.inputStream.use { input ->
            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.seek(startByte)
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (isCancelled.get()) return
                    raf.write(buffer, 0, read)
                    downloadedCounter.addAndGet(read.toLong())
                }
            }
        }
        conn.disconnect()
    }

    private fun moveToDownloads(sourceFile: File, fileName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Failed to create file in Downloads")

            resolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(out, bufferSize = 65536)
                }
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            sourceFile.delete()
        } else {
            @Suppress("DEPRECATION")
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)
            sourceFile.delete()
        }
    }
}
