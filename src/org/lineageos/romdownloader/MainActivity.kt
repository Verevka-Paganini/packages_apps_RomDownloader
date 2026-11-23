package org.lineageos.romdownloader

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.util.Xml
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

sealed class ListItem {
    data class FolderHeader(
        val folderKey: String,
        val displayName: String,
        val count: Int,
        val isExpanded: Boolean
    ) : ListItem()

    data class BuildItem(val build: RomBuild) : ListItem()
}

data class RomBuild(
    val fileName: String,
    val subFolder: String,
    val downloadUrl: String,
    val pubDate: String,
    val timestamp: Long,
    val fileSizeFormatted: String
)

class MainActivity : Activity() {

    companion object {
        private const val PROJECT_NAME = "oneplus-pagani"
        private const val DEVICE_FOLDER = ""

        private const val PREFS_NAME = "rom_downloader_prefs"
        private const val KEY_THREAD_COUNT = "thread_count"
        private const val KEY_MIRROR = "selected_mirror"
        private val THREAD_OPTIONS = listOf(2, 4, 6, 8)
        private val MIRROR_IDS = listOf(
            "auto",
            "netactuate",
            "gigenet",
            "liquidtelecom",
            "cyfuture",
            "deac-ams",
            "nchc",
            "ixpeering"
        )
        private val MIRROR_LABELS = listOf(
            "Auto",
            "NetActuate (US/EU)",
            "Gigenet (US)",
            "Liquid Telecom",
            "CyFuture",
            "DEAC (Amsterdam)",
            "NCHC (Taiwan)",
            "IX Peering"
        )

        val RSS_URL: String
            get() = "https://sourceforge.net/projects/$PROJECT_NAME/rss?limit=100"
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView
    private lateinit var listView: ListView
    private lateinit var refreshButton: Button
    private lateinit var threadsButton: Button
    private lateinit var mirrorButton: Button

    private var selectedThreads: Int = DownloadService.DEFAULT_THREADS
    private var selectedMirror: String = DownloadService.DEFAULT_MIRROR

    private var groupedBuilds: Map<String, List<RomBuild>> = emptyMap()
    private var sortedFolderKeys: List<String> = emptyList()
    private val expandedFolders = mutableSetOf<String>()

    private lateinit var adapter: RomAdapter

    private val downloadStateListener: () -> Unit = {
        adapter.notifyDataSetChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getInt(KEY_THREAD_COUNT, DownloadService.DEFAULT_THREADS)
        selectedThreads = if (saved in THREAD_OPTIONS) saved else DownloadService.DEFAULT_THREADS
        val savedMirror = prefs.getString(KEY_MIRROR, DownloadService.DEFAULT_MIRROR) ?: DownloadService.DEFAULT_MIRROR
        selectedMirror = if (savedMirror in MIRROR_IDS) savedMirror else DownloadService.DEFAULT_MIRROR

        val deviceTitle = DEVICE_FOLDER.ifEmpty { PROJECT_NAME }
        title = getString(R.string.title_with_device, deviceTitle)

        requestNotificationPermissionIfNeeded()
        setupUi()
        DownloadStateHolder.addListener(downloadStateListener)
        loadRssFeed()
    }

    override fun onDestroy() {
        super.onDestroy()
        DownloadStateHolder.removeListener(downloadStateListener)
        executor.shutdownNow()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }
    }

    private fun cycleThreadCount() {
        val currentIndex = THREAD_OPTIONS.indexOf(selectedThreads)
        val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % THREAD_OPTIONS.size
        selectedThreads = THREAD_OPTIONS[nextIndex]

        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THREAD_COUNT, selectedThreads)
            .apply()

        threadsButton.text = getString(R.string.threads_format, selectedThreads)
        Toast.makeText(
            this,
            getString(R.string.toast_threads_set, selectedThreads),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun getMirrorDisplayName(mirrorId: String): String {
        return if (mirrorId == "auto") getString(R.string.mirror_auto) else mirrorId
    }

    private fun showMirrorPickerDialog() {
        val displayItems = MIRROR_LABELS.mapIndexed { index, label ->
            if (index == 0) getString(R.string.mirror_auto) else label
        }.toTypedArray()
        val checkedIndex = MIRROR_IDS.indexOf(selectedMirror).coerceAtLeast(0)

        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_select_mirror)
            .setSingleChoiceItems(displayItems, checkedIndex) { dialog, which ->
                selectedMirror = MIRROR_IDS[which]
                getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_MIRROR, selectedMirror)
                    .apply()

                val shortLabel = getMirrorDisplayName(selectedMirror)
                mirrorButton.text = getString(R.string.mirror_btn_format, shortLabel)
                DownloadService.switchMirrorForActive(this, selectedMirror)
                Toast.makeText(
                    this,
                    getString(R.string.toast_mirror_set, shortLabel),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.btn_cancel, null)
            .show()
    }

    private fun setupUi() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(12)
            }
        }

        val headerText = TextView(this).apply {
            text = getString(R.string.available_builds)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        mirrorButton = Button(this).apply {
            text = getString(R.string.mirror_btn_format, getMirrorDisplayName(selectedMirror))
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { showMirrorPickerDialog() }
        }

        threadsButton = Button(this).apply {
            text = getString(R.string.threads_format, selectedThreads)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = dp(6)
            }
            setOnClickListener { cycleThreadCount() }
        }

        refreshButton = Button(this).apply {
            text = getString(R.string.btn_refresh)
            setOnClickListener { loadRssFeed() }
        }

        topBar.addView(headerText)
        topBar.addView(mirrorButton)
        topBar.addView(threadsButton)
        topBar.addView(refreshButton)
        rootLayout.addView(topBar)

        progressBar = ProgressBar(this).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(32)
            }
        }
        rootLayout.addView(progressBar)

        statusText = TextView(this).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(32)
            }
        }
        rootLayout.addView(statusText)

        adapter = RomAdapter()
        listView = ListView(this).apply {
            divider = null
            dividerHeight = dp(10)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            this.adapter = this@MainActivity.adapter
            setOnItemClickListener { _, _, position, _ ->
                val item = this@MainActivity.adapter.getItem(position)
                if (item is ListItem.FolderHeader) {
                    toggleFolder(item.folderKey)
                }
            }
        }
        rootLayout.addView(listView)

        setContentView(rootLayout)
    }

    private fun toggleFolder(folderKey: String) {
        if (expandedFolders.contains(folderKey)) {
            expandedFolders.remove(folderKey)
        } else {
            expandedFolders.add(folderKey)
        }
        refreshDisplayedList()
    }

    private fun refreshDisplayedList() {
        val visibleItems = mutableListOf<ListItem>()
        for (folderKey in sortedFolderKeys) {
            val buildsInFolder = groupedBuilds[folderKey].orEmpty()
            val isExpanded = expandedFolders.contains(folderKey)
            val displayName = folderKey.ifEmpty { getString(R.string.root_folder) }

            visibleItems.add(
                ListItem.FolderHeader(
                    folderKey = folderKey,
                    displayName = displayName,
                    count = buildsInFolder.size,
                    isExpanded = isExpanded
                )
            )

            if (isExpanded) {
                for (build in buildsInFolder) {
                    visibleItems.add(ListItem.BuildItem(build))
                }
            }
        }
        adapter.updateItems(visibleItems)
    }

    private fun loadRssFeed() {
        progressBar.visibility = View.VISIBLE
        statusText.visibility = View.GONE
        listView.visibility = View.GONE
        refreshButton.isEnabled = false

        executor.execute {
            try {
                val builds = fetchSourceForgeRss(RSS_URL)
                val grouped = builds.groupBy { it.subFolder }
                    .mapValues { (_, list) -> list.sortedByDescending { it.timestamp } }
                val sortedKeys = grouped.keys.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })

                mainHandler.post {
                    progressBar.visibility = View.GONE
                    refreshButton.isEnabled = true

                    groupedBuilds = grouped
                    sortedFolderKeys = sortedKeys
                    expandedFolders.retainAll(sortedKeys.toSet())

                    if (sortedKeys.isEmpty()) {
                        statusText.text = getString(R.string.no_builds_found)
                        statusText.visibility = View.VISIBLE
                    } else {
                        listView.visibility = View.VISIBLE
                        refreshDisplayedList()
                    }
                }
            } catch (e: Exception) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    refreshButton.isEnabled = true
                    statusText.text = getString(
                        R.string.error_loading,
                        e.javaClass.simpleName,
                        e.message ?: ""
                    )
                    statusText.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun startMultiThreadDownload(build: RomBuild) {
        DownloadService.startDownload(this, build.downloadUrl, build.fileName, selectedThreads, selectedMirror)
        Toast.makeText(
            this,
            getString(R.string.toast_download_started, selectedThreads, build.fileName),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun pauseMultiThreadDownload(build: RomBuild) {
        DownloadService.pauseDownload(this, build.fileName)
    }

    private fun cancelMultiThreadDownload(build: RomBuild) {
        DownloadService.cancelDownload(this, build.fileName)
        Toast.makeText(
            this,
            getString(R.string.toast_download_cancelled),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun fetchSourceForgeRss(rssUrl: String): List<RomBuild> {
        val result = mutableListOf<RomBuild>()
        val connection = (URL(rssUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Wget/1.21.4")
            setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml, */*")
        }

        val responseCode = connection.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(150).orEmpty()
            throw IllegalStateException("HTTP $responseCode: $errorBody")
        }

        val requiredPrefix = if (DEVICE_FOLDER.isNotEmpty()) {
            "/${DEVICE_FOLDER.trim('/')}/"
        } else {
            "/"
        }

        connection.inputStream.use { inputStream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)

            var eventType = parser.eventType
            var insideItem = false
            var title = ""
            var link = ""
            var pubDate = ""
            var fileSizeBytes = 0L

            while (eventType != XmlPullParser.END_DOCUMENT) {
                val tagName = parser.name
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        if (tagName.equals("item", ignoreCase = true)) {
                            insideItem = true
                            title = ""
                            link = ""
                            pubDate = ""
                            fileSizeBytes = 0L
                        } else if (insideItem) {
                            when {
                                tagName.equals("title", ignoreCase = true) -> title = parser.nextText()
                                tagName.equals("link", ignoreCase = true) -> link = parser.nextText()
                                tagName.equals("pubDate", ignoreCase = true) -> pubDate = parser.nextText()
                                tagName.equals("media:content", ignoreCase = true) -> {
                                    fileSizeBytes = parser.getAttributeValue(null, "filesize")?.toLongOrNull() ?: 0L
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (tagName.equals("item", ignoreCase = true) && insideItem) {
                            insideItem = false

                            val fullPath = if (title.startsWith("/")) title else "/$title"

                            if (fullPath.startsWith(requiredPrefix, ignoreCase = true) &&
                                fullPath.endsWith(".zip", ignoreCase = true)
                            ) {
                                val cleanName = fullPath.substringAfterLast("/")
                                val relativePath = fullPath.removePrefix(requiredPrefix)
                                val subFolder = if (relativePath.contains("/")) {
                                    relativePath.substringBeforeLast("/")
                                } else {
                                    ""
                                }

                                val timestamp = parseRssDateToMillis(pubDate)

                                result.add(
                                    RomBuild(
                                        fileName = cleanName,
                                        subFolder = subFolder,
                                        downloadUrl = link,
                                        pubDate = pubDate.substringBeforeLast(" +").substringBeforeLast(" GMT"),
                                        timestamp = timestamp,
                                        fileSizeFormatted = formatFileSize(fileSizeBytes)
                                    )
                                )
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        }
        return result
    }

    private fun parseRssDateToMillis(rawDate: String): Long {
        val trimmed = rawDate.trim()
        val formats = listOf(
            "EEE, dd MMM yyyy HH:mm:ss Z",
            "EEE, dd MMM yyyy HH:mm:ss z",
            "dd MMM yyyy HH:mm:ss Z",
            "yyyy-MM-dd HH:mm:ss"
        )
        for (pattern in formats) {
            try {
                val sdf = SimpleDateFormat(pattern, Locale.US)
                val parsed = sdf.parse(trimmed)
                if (parsed != null) return parsed.time
            } catch (_: Exception) {
            }
        }
        return 0L
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return getString(R.string.size_unknown)
        val units = resources.getStringArray(R.array.file_size_units)
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            .coerceIn(0, units.lastIndex)
        return DecimalFormat("#,##0.##").format(bytes / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private inner class RomAdapter : BaseAdapter() {

        private var items: List<ListItem> = emptyList()

        fun updateItems(newItems: List<ListItem>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): ListItem = items[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getViewTypeCount(): Int = 2

        override fun getItemViewType(position: Int): Int {
            return when (items[position]) {
                is ListItem.FolderHeader -> 0
                is ListItem.BuildItem -> 1
            }
        }

        override fun isEnabled(position: Int): Boolean {
            return items[position] is ListItem.FolderHeader
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            return when (val item = items[position]) {
                is ListItem.FolderHeader -> createFolderHeaderView(item)
                is ListItem.BuildItem -> createBuildCardView(item.build)
            }
        }

        private fun createFolderHeaderView(header: ListItem.FolderHeader): View {
            val headerBackground = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(Color.parseColor("#26888888"))
            }

            val headerLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = headerBackground
                setPadding(dp(14), dp(14), dp(14), dp(14))
            }

            val arrow = if (header.isExpanded) "▼" else "▶"
            val titleView = TextView(this@MainActivity).apply {
                text = "$arrow  📁 ${header.displayName} (${header.count})"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTypeface(null, Typeface.BOLD)
            }

            headerLayout.addView(titleView)
            return headerLayout
        }

        private fun createBuildCardView(build: RomBuild): View {
            val status = DownloadStateHolder.getStatus(build.fileName)

            val cardBackground = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#14888888"))
                setStroke(dp(1), Color.parseColor("#33888888"))
            }

            val cardLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBackground
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }

            val nameView = TextView(this@MainActivity).apply {
                text = build.fileName
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, Typeface.BOLD)
            }

            val detailsView = TextView(this@MainActivity).apply {
                text = getString(R.string.build_details, build.pubDate, build.fileSizeFormatted)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                alpha = 0.75f
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(8)
                }
            }

            cardLayout.addView(nameView)
            cardLayout.addView(detailsView)

            when (status) {
                is DownloadStatus.Preparing -> {
                    val prepBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = true
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(4) }
                    }
                    val prepText = TextView(this@MainActivity).apply {
                        text = getString(R.string.download_preparing)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                    }
                    cardLayout.addView(prepBar)
                    cardLayout.addView(prepText)
                }

                is DownloadStatus.Running -> {
                    val percent = if (status.totalBytes > 0) {
                        ((status.downloadedBytes * 100) / status.totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    val dlProgressBar = ProgressBar(
                        this@MainActivity,
                        null,
                        android.R.attr.progressBarStyleHorizontal
                    ).apply {
                        max = 100
                        progress = percent
                        isIndeterminate = status.totalBytes <= 0
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(4) }
                    }
                    val progressLine = getString(
                        R.string.download_progress,
                        percent,
                        formatFileSize(status.speedBytesPerSec),
                        formatFileSize(status.downloadedBytes),
                        formatFileSize(status.totalBytes)
                    )
                    val mirrorSuffix = if (status.mirrorHost.isNotEmpty()) {
                        "\n${getString(R.string.mirror_host_label, status.mirrorHost)}"
                    } else {
                        ""
                    }
                    val progressText = TextView(this@MainActivity).apply {
                        text = progressLine + mirrorSuffix
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                    }
                    cardLayout.addView(dlProgressBar)
                    cardLayout.addView(progressText)
                }

                is DownloadStatus.Paused -> {
                    val percent = if (status.totalBytes > 0) {
                        ((status.downloadedBytes * 100) / status.totalBytes).toInt().coerceIn(0, 100)
                    } else {
                        0
                    }
                    val pauseBar = ProgressBar(
                        this@MainActivity,
                        null,
                        android.R.attr.progressBarStyleHorizontal
                    ).apply {
                        max = 100
                        progress = percent
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(4) }
                    }
                    val pauseLine = getString(
                        R.string.download_paused,
                        percent,
                        formatFileSize(status.downloadedBytes),
                        formatFileSize(status.totalBytes)
                    )
                    val mirrorSuffix = if (status.mirrorHost.isNotEmpty()) {
                        "\n${getString(R.string.mirror_host_label, status.mirrorHost)}"
                    } else {
                        ""
                    }
                    val pauseText = TextView(this@MainActivity).apply {
                        text = pauseLine + mirrorSuffix
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(Color.parseColor("#FF9800"))
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                    }
                    cardLayout.addView(pauseBar)
                    cardLayout.addView(pauseText)
                }

                is DownloadStatus.Verifying -> {
                    val verifyBar = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                        isIndeterminate = true
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(4) }
                    }
                    val verifyText = TextView(this@MainActivity).apply {
                        text = getString(R.string.download_verifying)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                    }
                    cardLayout.addView(verifyBar)
                    cardLayout.addView(verifyText)
                }

                is DownloadStatus.Completed -> {
                    val shortHash = status.sha256.take(16)
                    val mirrorInfo = if (status.mirrorHost.isNotEmpty()) {
                        "\n${getString(R.string.mirror_host_label, status.mirrorHost)}"
                    } else {
                        ""
                    }
                    val doneText = TextView(this@MainActivity).apply {
                        text = "✅ ${getString(R.string.download_completed)}$mirrorInfo\n${getString(R.string.sha256_tap_copy, shortHash)}"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(Color.parseColor("#4CAF50"))
                        setTypeface(null, Typeface.BOLD)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                        setOnClickListener {
                            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("SHA-256", status.sha256))
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.toast_sha256_copied),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    cardLayout.addView(doneText)
                }

                is DownloadStatus.Failed -> {
                    val errText = TextView(this@MainActivity).apply {
                        text = "❌ ${status.error}"
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                        setTextColor(Color.parseColor("#F44336"))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(8) }
                    }
                    cardLayout.addView(errText)
                }

                null -> Unit
            }

            val btnRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            when (status) {
                is DownloadStatus.Preparing, is DownloadStatus.Running -> {
                    val pauseBtn = Button(this@MainActivity).apply {
                        text = getString(R.string.btn_pause)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(8) }
                        setOnClickListener { pauseMultiThreadDownload(build) }
                    }
                    val cancelBtn = Button(this@MainActivity).apply {
                        text = getString(R.string.btn_cancel)
                        setOnClickListener { cancelMultiThreadDownload(build) }
                    }
                    btnRow.addView(pauseBtn)
                    btnRow.addView(cancelBtn)
                }

                is DownloadStatus.Paused -> {
                    val resumeBtn = Button(this@MainActivity).apply {
                        text = getString(R.string.btn_resume)
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(8) }
                        setOnClickListener { startMultiThreadDownload(build) }
                    }
                    val cancelBtn = Button(this@MainActivity).apply {
                        text = getString(R.string.btn_cancel)
                        setOnClickListener { cancelMultiThreadDownload(build) }
                    }
                    btnRow.addView(resumeBtn)
                    btnRow.addView(cancelBtn)
                }

                is DownloadStatus.Verifying -> {
                    val cancelBtn = Button(this@MainActivity).apply {
                        text = getString(R.string.btn_cancel)
                        setOnClickListener { cancelMultiThreadDownload(build) }
                    }
                    btnRow.addView(cancelBtn)
                }

                else -> {
                    val downloadBtn = Button(this@MainActivity).apply {
                        text = getString(R.string.btn_download)
                        setOnClickListener { startMultiThreadDownload(build) }
                    }
                    btnRow.addView(downloadBtn)
                }
            }

            cardLayout.addView(btnRow)
            return cardLayout
        }
    }
}
