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
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipFile

class MultiThreadDownloader(
    private val context: Context,
    private val threadCount: Int = 6,
    private val mirror: String = "auto"
) {
    interface Listener {
        fun onProgress(downloadedBytes: Long, totalBytes: Long, speedBytesPerSec: Long, mirrorHost: String)
        fun onPaused(downloadedBytes: Long, totalBytes: Long, mirrorHost: String)
        fun onVerifying()
        fun onCompleted(fileName: String, sha256: String, mirrorHost: String)
        fun onError(error: String)
    }

    private data class ChunkState(
        val start: Long,
        val end: Long,
        val current: AtomicLong
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isCancelled = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    fun pause() {
        isPaused.set(true)
    }

    fun cancel() {
        isCancelled.set(true)
    }

    fun download(downloadUrl: String, fileName: String, listener: Listener) {
        val managerExecutor = Executors.newSingleThreadExecutor()
        managerExecutor.execute {
            val tempFile = File(context.cacheDir, "$fileName.part")
            val metaFile = File(context.cacheDir, "$fileName.part.meta")
            var mirrorHost = ""
            var totalBytes = -1L
            val chunks = mutableListOf<ChunkState>()

            try {
                val targetUrl = applyMirrorParam(downloadUrl, mirror)
                val probe = resolveDirectUrl(targetUrl)
                val directUrl = probe.finalUrl
                mirrorHost = extractHost(directUrl)
                totalBytes = probe.contentLength
                val supportsRanges = probe.supportsRanges

                val loadedChunks = if (supportsRanges && totalBytes > 0 && tempFile.exists() && metaFile.exists()) {
                    loadMeta(metaFile, totalBytes)
                } else {
                    null
                }

                if (loadedChunks != null && loadedChunks.isNotEmpty()) {
                    chunks.addAll(loadedChunks)
                } else {
                    if (tempFile.exists()) tempFile.delete()
                    if (metaFile.exists()) metaFile.delete()

                    val effectiveThreads = if (supportsRanges && totalBytes > 1024 * 1024) threadCount else 1
                    if (totalBytes > 0) {
                        RandomAccessFile(tempFile, "rw").use { it.setLength(totalBytes) }
                    }

                    if (effectiveThreads == 1) {
                        val end = if (totalBytes > 0) totalBytes - 1 else -1L
                        chunks.add(ChunkState(0L, end, AtomicLong(0L)))
                    } else {
                        val chunkSize = totalBytes / effectiveThreads
                        for (i in 0 until effectiveThreads) {
                            val start = i * chunkSize
                            val end = if (i == effectiveThreads - 1) totalBytes - 1 else (start + chunkSize - 1)
                            chunks.add(ChunkState(start, end, AtomicLong(start)))
                        }
                    }
                    if (supportsRanges && totalBytes > 0) {
                        saveMeta(metaFile, totalBytes, chunks)
                    }
                }

                fun calcDownloaded(): Long = chunks.sumOf { (it.current.get() - it.start).coerceAtLeast(0L) }

                var lastBytes = calcDownloaded()
                var lastTime = System.currentTimeMillis()

                val reportTask = Runnable {
                    if (supportsRanges && totalBytes > 0) {
                        saveMeta(metaFile, totalBytes, chunks)
                    }
                    val now = System.currentTimeMillis()
                    val current = calcDownloaded()
                    val elapsedMs = (now - lastTime).coerceAtLeast(1L)
                    val speed = ((current - lastBytes) * 1000L) / elapsedMs
                    lastBytes = current
                    lastTime = now

                    mainHandler.post {
                        listener.onProgress(current, totalBytes, speed, mirrorHost)
                    }
                }

                val activeChunks = chunks.filter { it.end < 0L || it.current.get() <= it.end }
                if (activeChunks.isNotEmpty()) {
                    val workerPool = Executors.newFixedThreadPool(activeChunks.size)
                    val tasks = activeChunks.map { chunk ->
                        Callable {
                            downloadChunk(directUrl, tempFile, chunk)
                        }
                    }

                    val progressThread = Thread {
                        while (!Thread.currentThread().isInterrupted && !isCancelled.get() && !isPaused.get()) {
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
                }

                if (isCancelled.get()) {
                    tempFile.delete()
                    metaFile.delete()
                    return@execute
                }

                if (isPaused.get()) {
                    if (supportsRanges && totalBytes > 0) {
                        saveMeta(metaFile, totalBytes, chunks)
                    }
                    val current = calcDownloaded()
                    mainHandler.post {
                        listener.onPaused(current, totalBytes, mirrorHost)
                    }
                    return@execute
                }

                mainHandler.post { listener.onVerifying() }

                if (totalBytes > 0 && tempFile.length() != totalBytes) {
                    throw IllegalStateException("Size mismatch: ${tempFile.length()} != $totalBytes")
                }

                verifyZipStructure(tempFile)
                val sha256 = computeFileSha256(tempFile)

                if (isCancelled.get()) {
                    tempFile.delete()
                    metaFile.delete()
                    return@execute
                }

                moveToDownloads(tempFile, fileName)
                metaFile.delete()

                mainHandler.post {
                    listener.onCompleted(fileName, sha256, mirrorHost)
                }
            } catch (e: Exception) {
                if (isPaused.get()) {
                    val current = chunks.sumOf { (it.current.get() - it.start).coerceAtLeast(0L) }
                    mainHandler.post {
                        listener.onPaused(current, totalBytes, mirrorHost)
                    }
                } else {
                    tempFile.delete()
                    metaFile.delete()
                    if (!isCancelled.get()) {
                        mainHandler.post {
                            listener.onError(e.localizedMessage ?: e.javaClass.simpleName)
                        }
                    }
                }
            } finally {
                managerExecutor.shutdown()
            }
        }
    }

    private fun saveMeta(metaFile: File, totalBytes: Long, chunks: List<ChunkState>) {
        try {
            val sb = StringBuilder()
            sb.append(totalBytes).append('\n')
            for (c in chunks) {
                sb.append(c.start).append(':').append(c.current.get()).append(':').append(c.end).append('\n')
            }
            metaFile.writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    private fun loadMeta(metaFile: File, expectedTotalBytes: Long): List<ChunkState>? {
        return try {
            val lines = metaFile.readLines().filter { it.isNotBlank() }
            if (lines.size < 2) return null
            val savedTotal = lines[0].trim().toLongOrNull() ?: return null
            if (savedTotal != expectedTotalBytes) return null

            val list = mutableListOf<ChunkState>()
            for (i in 1 until lines.size) {
                val parts = lines[i].split(':')
                if (parts.size != 3) return null
                val start = parts[0].toLong()
                val current = parts[1].toLong()
                val end = parts[2].toLong()
                if (current < start || current > end + 1) return null
                list.add(ChunkState(start, end, AtomicLong(current)))
            }
            list
        } catch (_: Exception) {
            null
        }
    }

    private fun verifyZipStructure(file: File) {
        try {
            ZipFile(file).use { zip ->
                val entries = zip.entries()
                if (!entries.hasMoreElements()) {
                    throw IllegalStateException("ZIP archive is empty")
                }
                while (entries.hasMoreElements()) {
                    entries.nextElement()
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Corrupted ZIP: ${e.message}")
        }
    }

    private fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                if (isCancelled.get()) {
                    throw InterruptedException("Cancelled during SHA-256 calculation")
                }
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun applyMirrorParam(rawUrl: String, selectedMirror: String): String {
        val cleanMirror = selectedMirror.trim()
        if (cleanMirror.isEmpty() || cleanMirror.equals("auto", ignoreCase = true)) {
            return rawUrl
        }
        if (!rawUrl.contains("sourceforge.net", ignoreCase = true)) {
            return rawUrl
        }
        if (rawUrl.contains("use_mirror=", ignoreCase = true)) {
            return rawUrl
        }
        val separator = if (rawUrl.contains("?")) "&" else "?"
        return "$rawUrl${separator}use_mirror=$cleanMirror"
    }

    private fun extractHost(urlStr: String): String {
        return try {
            URI(urlStr).host ?: ""
        } catch (_: Exception) {
            ""
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
        chunk: ChunkState
    ) {
        val startOffset = chunk.current.get()
        val endByte = chunk.end
        if (endByte >= 0L && startOffset > endByte) return

        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 30000
            setRequestProperty("User-Agent", "Wget/1.21.4")
            if (endByte >= startOffset) {
                setRequestProperty("Range", "bytes=$startOffset-$endByte")
            }
        }

        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            throw IllegalStateException("HTTP $code on chunk $startOffset-$endByte")
        }

        if (startOffset > 0L && code != HttpURLConnection.HTTP_PARTIAL) {
            throw IllegalStateException("Mirror does not support Range resume (HTTP $code)")
        }

        conn.inputStream.use { input ->
            RandomAccessFile(targetFile, "rw").use { raf ->
                raf.seek(startOffset)
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (isCancelled.get() || isPaused.get()) return
                    raf.write(buffer, 0, read)
                    chunk.current.addAndGet(read.toLong())
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
