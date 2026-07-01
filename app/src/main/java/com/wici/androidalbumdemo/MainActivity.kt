package com.wici.androidalbumdemo

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.Manifest
import android.app.Activity
import android.content.res.ColorStateList
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.text.TextUtils
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private var glView: SplatGlView? = null
    private lateinit var renderStatus: TextView
    private lateinit var overlay: ImageView
    private lateinit var generateButton: LinearLayout
    private lateinit var generateLabel: TextView
    private lateinit var generateSpinner: ProgressBar
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private val previewBakeExecutor = Executors.newFixedThreadPool(PREVIEW_BAKE_CONCURRENCY)
    private val previewBakeInFlight = ConcurrentHashMap.newKeySet<String>()
    private var assetName: String = "brush-clean-noshr-10k.ply"
    private var latestCapture: ReleaseCapture? = null
    private var fluxDataUrl: String? = null
    private var finalBitmap: Bitmap? = null
    private var currentViewerPhoto: AlbumPhoto? = null
    private var displayMode = DisplayMode.RAW
    private var difixBusy = false
    private var fluxBusy = false
    private var viewerRoot: FrameLayout? = null
    private var generateErrorOverlay: View? = null
    private var releaseCaptureEnabled = true
    private var postPassEnabled = true
    private var streamDensityOverride: String? = null
    private var footprintScaleOverride: Float? = null
    private var splatCacheMaxBytesOverride: Long? = null
    private var viewerVisible = false
    private var previewRequestSerial = 0
    private var previewEnabled = false
    private var albumGrid: GridView? = null
    private var albumAdapter: AlbumGridAdapter? = null
    private var albumStatus: TextView? = null
    private var albumActionButton: TextView? = null
    private var galleryRequestSerial = 0
    private var cascadeRunSerial = 0
    private val previewHandler = Handler(Looper.getMainLooper())
    private val thumbnailCache = mutableMapOf<String, Bitmap>()
    private var albumPhotos: List<AlbumPhoto> = emptyList()
    private val importedPhotos = mutableListOf<AlbumPhoto>()
    private val removedCuratedPhotoIds = mutableSetOf<String>()
    private val localSourceDataUrlCache = mutableMapOf<String, String>()
    private val localSourceJpegCache = mutableMapOf<String, ByteArray>()
    private var albumScrollState: AlbumScrollState? = null
    private var albumScrollParcelable: Parcelable? = null
    private var nextImportedOrdinal = 1
    private var albumEditMode = false
    private val backendHandler = Handler(Looper.getMainLooper())
    private var discoveredBackendBaseUrl: String? = null
    private var backendDiscoveryInProgress = false
    private var backendDiscoveryCompleted = false
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdResolveInFlight = false
    private val interBase: Typeface by lazy { resources.getFont(R.font.inter_variable) }
    private val spaceGroteskBase: Typeface by lazy { resources.getFont(R.font.space_grotesk_variable) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(COLOR_CANVAS))
        window.statusBarColor = COLOR_CANVAS
        window.navigationBarColor = COLOR_CANVAS
        SupabaseAuth.init(this)
        removedCuratedPhotoIds.addAll(loadRemovedCuratedPhotoIds())

        releaseCaptureEnabled = !intent.getBooleanExtra("disableReleaseCapture", false)
        postPassEnabled = !intent.getBooleanExtra("disablePostPass", false)
        streamDensityOverride = intent.getStringExtra("streamDensity")
            ?.takeIf { it.isNotBlank() }
        footprintScaleOverride = if (intent.hasExtra("footprintScale")) {
            intent.getFloatExtra("footprintScale", Float.NaN)
                .takeIf { it.isFinite() && it > 0f }
        } else {
            null
        }
        splatCacheMaxBytesOverride = if (intent.hasExtra("splatCacheMaxBytes")) {
            intent.getLongExtra("splatCacheMaxBytes", 0L).takeIf { it > 0L }
        } else {
            null
        }
        startBackendDiscoveryIfNeeded()
        val requestedAsset = intent.getStringExtra("asset")
        if (requestedAsset != null) {
            val requestedPhotoId = intent.getStringExtra("photoId")
                ?.takeIf { it.isNotBlank() }
                ?: photoIdForAsset(requestedAsset)
            val dims = sourceDimsFromIntent() ?: legacySourceDims(requestedPhotoId)
            val photo = albumPhotos.firstOrNull { it.splatAsset == requestedAsset }
                ?: AlbumPhoto(
                    photoId = requestedPhotoId,
                    thumbnailUrl = absoluteGalleryUrl("/thumbnail?photoId=${Uri.encode(requestedPhotoId)}"),
                    hasOrbit = true,
                    hasSplat = true,
                    sourceWidth = dims?.first,
                    sourceHeight = dims?.second,
                    camFx = intentPositiveFloat("camFx"),
                    camFy = intentPositiveFloat("camFy"),
                    camCx = intentPositiveFloat("camCx"),
                    camCy = intentPositiveFloat("camCy"),
                    sourceUrl = absoluteGalleryUrl("/source?photoId=${Uri.encode(requestedPhotoId)}"),
                    splatStreamUrl = absoluteGalleryUrl("/splat_stream?photoId=${Uri.encode(requestedPhotoId)}")
                )
            showViewer(photo)
        } else {
            showAlbum()
        }
    }

    private fun showAlbum() {
        viewerVisible = false
        previewEnabled = true
        glView?.shutdown()
        glView?.onPause()
        glView = null
        viewerRoot = null
        generateErrorOverlay = null
        previewRequestSerial++
        cascadeRunSerial++
        previewHandler.removeCallbacksAndMessages(null)
        currentViewerPhoto = null
        albumEditMode = false
        albumActionButton = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COLOR_CANVAS)
            setPadding(dp(12), dp(22), dp(12), 0)
            setOnClickListener {
                if (albumEditMode) exitAlbumEditMode()
            }
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(18))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(
            TextView(this).apply {
                text = "Spatial Album"
                setTextColor(COLOR_INK)
                textSize = 32f
                typeface = spaceGrotesk(700)
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                rightMargin = dp(12)
            }
        )
        val controlCluster = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val previewToggle = TextView(this).apply {
            text = "3D"
            textSize = 13f
            typeface = inter(800)
            includeFontPadding = false
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            contentDescription = "3D Preview"
            setOnClickListener {
                updateOrbitPreviews(!previewEnabled)
                stylePreviewToggle(this, previewEnabled)
            }
        }
        stylePreviewToggle(previewToggle, true)
        albumActionButton = previewToggle
        controlCluster.addView(
            previewToggle,
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                rightMargin = dp(8)
            }
        )
        val settingsButton = GearButton(this).apply {
            contentDescription = "Settings"
            isClickable = true
            isFocusable = true
            background = rounded(COLOR_SURFACE, dp(22).toFloat(), dpFloat(1f), COLOR_HAIRLINE)
            applySoftShadow(this, 2)
            setOnClickListener { showBackendSettingsDialog() }
        }
        controlCluster.addView(settingsButton, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleRow.addView(controlCluster, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(44)))
        header.addView(titleRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, 0)
        }
        albumStatus = TextView(this).apply {
            text = if (albumPhotos.isEmpty()) "LOADING GALLERY" else momentCountText()
            setTextColor(COLOR_INK_SOFT)
            textSize = 11f
            typeface = inter(600)
            includeFontPadding = false
        }
        metaRow.addView(albumStatus, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        header.addView(metaRow, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        root.addView(header, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val adapter = AlbumGridAdapter()
        albumAdapter = adapter
        val grid = GridView(this).apply {
            numColumns = 3
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(10)
            verticalSpacing = dp(12)
            clipToPadding = false
            setPadding(0, 0, 0, dp(28))
            selector = ColorDrawable(Color.TRANSPARENT)
            this.adapter = adapter
            setRecyclerListener { view -> stopCellPreview(view) }
            setOnItemLongClickListener { _, _, position, _ ->
                val photo = adapter.photoAt(position)
                if (photo != null) {
                    enterAlbumEditMode()
                    true
                } else {
                    false
                }
            }
            setOnTouchListener { _, event ->
                if (
                    albumEditMode &&
                    event.action == MotionEvent.ACTION_UP &&
                    !isTouchInsideGridChild(this, event.x, event.y)
                ) {
                    exitAlbumEditMode()
                    true
                } else {
                    false
                }
            }
            setOnScrollListener(object : AbsListView.OnScrollListener {
                override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) {
                    if (scrollState == AbsListView.OnScrollListener.SCROLL_STATE_IDLE) {
                        refreshVisibleOrbitPreviews()
                    }
                }

                override fun onScroll(
                    view: AbsListView?,
                    firstVisibleItem: Int,
                    visibleItemCount: Int,
                    totalItemCount: Int
                ) = Unit
            })
        }
        albumGrid = grid
        root.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        adapter.previewEnabled = previewEnabled
        adapter.setPhotos(albumPhotos)
        applyFullscreen(root)
        setContentView(root)
        restoreAlbumScrollPosition(grid, "showAlbum")
        if (albumPhotos.isEmpty()) {
            loadDeviceAlbum()
        } else {
            grid.post { refreshVisibleOrbitPreviews() }
        }
    }

    private fun saveAlbumScrollPosition(reason: String) {
        val grid = albumGrid ?: return
        val anchorChild = (0 until grid.childCount)
            .firstOrNull { ((grid.getChildAt(it)?.top) ?: Int.MIN_VALUE) >= grid.paddingTop }
            ?: 0
        val first = (grid.firstVisiblePosition + anchorChild).coerceAtLeast(0)
        val top = grid.getChildAt(anchorChild)?.top ?: grid.paddingTop
        albumScrollState = AlbumScrollState(first, top)
        albumScrollParcelable = grid.onSaveInstanceState()
        Log.i(TAG, "Album scroll saved reason=$reason anchorChild=$anchorChild first=$first offset=$top")
    }

    private fun restoreAlbumScrollPosition(grid: GridView, reason: String) {
        val state = albumScrollState ?: return
        val listState = albumScrollParcelable
        fun applyRestore(pass: String) {
            val count = grid.adapter?.count ?: 0
            if (count <= 0) return
            if (listState != null) {
                grid.onRestoreInstanceState(listState)
            } else {
                val position = state.firstVisiblePosition.coerceIn(0, count - 1)
                grid.smoothScrollToPositionFromTop(position, state.topOffsetPx, 0)
                grid.setSelectionFromTop(position, state.topOffsetPx)
            }
            refreshVisibleOrbitPreviews()
            Log.i(
                TAG,
                "Album scroll restored reason=$reason pass=$pass first=${state.firstVisiblePosition} " +
                    "offset=${state.topOffsetPx} listState=${listState != null}"
            )
        }

        grid.post { applyRestore("post") }
        grid.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                grid.post { applyRestore("layout") }
            }
        })
        grid.postDelayed({
            applyRestore("delayed")
        }, 120L)
        grid.postDelayed({
            applyRestore("settled")
        }, 320L)
    }

    private fun sourceAspect(photo: AlbumPhoto): Float? {
        val width = photo.sourceWidth?.takeIf { it > 0 } ?: return null
        val height = photo.sourceHeight?.takeIf { it > 0 } ?: return null
        return width.toFloat() / height.toFloat()
    }

    private fun launchPhotoPicker() {
        val maxItems = try {
            MediaStore.getPickImagesMaxLimit().coerceAtMost(PICK_IMAGES_MAX)
        } catch (_: Exception) {
            PICK_IMAGES_MAX
        }
        val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
            type = "image/*"
            putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, maxItems)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGES)
        } catch (exc: Exception) {
            Log.w(TAG, "Platform photo picker unavailable, falling back", exc)
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(Intent.createChooser(fallback, "Add photos"), REQUEST_PICK_IMAGES)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK_IMAGES || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { clip ->
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i)?.uri?.let { uris += it }
            }
        }
        data.data?.let { uris += it }
        addImportedPhotos(uris.distinct())
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_READ_PHOTOS) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            loadDeviceAlbum()
        } else {
            albumStatus?.text = "GALLERY UNAVAILABLE"
            albumAdapter?.setPhotos(importedPhotos)
            albumPhotos = importedPhotos
        }
    }

    private fun addImportedPhotos(uris: List<Uri>) {
        if (uris.isEmpty()) return
        if (albumEditMode) exitAlbumEditMode()
        val added = uris.map { uri ->
            val id = "local-${SystemClock.elapsedRealtime()}-${nextImportedOrdinal++}"
            AlbumPhoto(
                photoId = id,
                thumbnailUrl = uri.toString(),
                hasOrbit = false,
                hasSplat = false,
                sourceWidth = null,
                sourceHeight = null,
                camFx = null,
                camFy = null,
                camCx = null,
                camCy = null,
                localUri = uri,
                imported = true
            )
        }
        importedPhotos.addAll(0, added)
        val importedIds = importedPhotos.map { it.photoId }.toSet()
        albumPhotos = importedPhotos + albumPhotos.filterNot { it.photoId in importedIds }
        albumAdapter?.setPhotos(albumPhotos)
        albumStatus?.text = momentCountText()
        Log.i(TAG, "Imported local photos count=${added.size} totalImported=${importedPhotos.size}")
    }

    private fun loadDeviceAlbum() {
        val requestSerial = ++galleryRequestSerial
        if (!hasPhotoLibraryPermission()) {
            albumStatus?.text = "GALLERY UNAVAILABLE"
            requestPermissions(arrayOf(photoLibraryPermission()), REQUEST_READ_PHOTOS)
            return
        }
        albumStatus?.text = "LOADING GALLERY"
        networkExecutor.execute {
            try {
                val parsed = queryDevicePhotos()
                runOnUiThread {
                    if (requestSerial != galleryRequestSerial || viewerVisible) return@runOnUiThread
                    val importedUris = importedPhotos.mapNotNull { it.localUri?.toString() }.toSet()
                    albumPhotos = importedPhotos + parsed.filterNot { it.localUri?.toString() in importedUris }
                    albumAdapter?.setPhotos(albumPhotos)
                    albumStatus?.text = momentCountText()
                    albumGrid?.let { restoreAlbumScrollPosition(it, "loadDeviceAlbum") }
                    if (previewEnabled) albumGrid?.post { refreshVisibleOrbitPreviews() }
                    Log.i(TAG, "Device photos loaded count=${parsed.size} imported=${importedPhotos.size}")
                }
            } catch (exc: Exception) {
                Log.w(TAG, "Device photo load failed", exc)
                runOnUiThread {
                    if (requestSerial != galleryRequestSerial || viewerVisible) return@runOnUiThread
                    albumStatus?.text = "GALLERY UNAVAILABLE"
                }
            }
        }
    }

    private fun queryDevicePhotos(): List<AlbumPhoto> {
        val photos = mutableListOf<AlbumPhoto>()
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection += MediaStore.Images.Media.RELATIVE_PATH
        }
        val sort = "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.Images.Media.DATE_ADDED} DESC"
        contentResolver.query(collection, projection.toTypedArray(), null, null, sort)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val widthCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val relativeCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            } else {
                -1
            }
            while (cursor.moveToNext() && photos.size < DEVICE_PHOTO_LIMIT) {
                val id = cursor.getLong(idCol)
                val displayName = cursor.getString(nameCol).orEmpty()
                val mime = cursor.getString(mimeCol).orEmpty()
                val bucket = cursor.getString(bucketCol).orEmpty()
                val relativePath = if (relativeCol >= 0) cursor.getString(relativeCol).orEmpty() else ""
                val width = cursor.getInt(widthCol).takeIf { it > 0 }
                val height = cursor.getInt(heightCol).takeIf { it > 0 }
                if (isFilteredDeviceImage(bucket, relativePath, displayName, mime, width, height)) continue
                val photoId = "device-$id"
                val uri = Uri.withAppendedPath(collection, id.toString())
                val cachedPreview = orbitPreviewCacheFile(photoId).takeIf { it.length() > 0L }
                photos += AlbumPhoto(
                    photoId = photoId,
                    thumbnailUrl = uri.toString(),
                    hasOrbit = cachedPreview != null,
                    hasSplat = false,
                    sourceWidth = width,
                    sourceHeight = height,
                    camFx = null,
                    camFy = null,
                    camCx = null,
                    camCy = null,
                    orbitWebpUrl = cachedPreview?.toURI()?.toString(),
                    localUri = uri,
                    imported = true
                )
            }
        }
        return photos
    }

    private fun isFilteredDeviceImage(
        bucket: String,
        relativePath: String,
        displayName: String,
        mime: String,
        width: Int?,
        height: Int?
    ): Boolean {
        if (!mime.startsWith("image/", ignoreCase = true)) return true
        if (mime.contains("gif", ignoreCase = true) || mime.contains("svg", ignoreCase = true)) return true
        val bucketLower = bucket.lowercase()
        val pathLower = relativePath.lowercase()
        val nameLower = displayName.lowercase()
        if (bucketLower == "screenshots" || bucketLower == "screenshot") return true
        if (bucketLower == "download" || bucketLower == "downloads") return true
        if ("screenshots" in pathLower || "screenshot" in pathLower) return true
        if ("screenrecord" in pathLower || "screen_record" in pathLower || "captures" in pathLower) return true
        if (pathLower.startsWith("download") || "/download" in pathLower) return true
        if (nameLower.startsWith("screenshot") || nameLower.startsWith("screen_shot")) return true
        if ("screenrecord" in nameLower || "screen_record" in nameLower) return true
        val rootPng = mime.equals("image/png", ignoreCase = true) &&
            (bucketLower.isBlank() || bucketLower == "null") &&
            (pathLower.isBlank() || pathLower == "/" || pathLower == "null")
        if (rootPng) return true
        if (mime.equals("image/png", ignoreCase = true) && looksLikePhoneScreenshot(width, height)) return true
        return false
    }

    private fun looksLikePhoneScreenshot(width: Int?, height: Int?): Boolean {
        if (width == null || height == null) return false
        val shortSide = min(width, height)
        val longSide = maxOf(width, height)
        val aspect = longSide.toFloat() / shortSide.toFloat()
        return shortSide in 1000..1450 && longSide in 1900..2600 && aspect > 1.55f
    }

    private fun parseGallery(array: JSONArray): List<AlbumPhoto> {
        val photos = mutableListOf<AlbumPhoto>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val photoId = item.optString("photoId").trim()
            if (photoId.isEmpty()) continue
            if (removedCuratedPhotoIds.contains(photoId)) continue
            val fallbackDims = legacySourceDims(photoId)
            val sourceWidth = firstOptionalInt(item, "sourceWidth", "source_width", "width", "imageWidth")
                ?: fallbackDims?.first
            val sourceHeight = firstOptionalInt(item, "sourceHeight", "source_height", "height", "imageHeight")
                ?: fallbackDims?.second
            if (item.optBoolean("hasSplat", false) && (sourceWidth == null || sourceHeight == null)) {
                Log.w(TAG, "Gallery item missing source dimensions photoId=$photoId; using generic camera")
            }
            photos += AlbumPhoto(
                photoId = photoId,
                thumbnailUrl = absoluteGalleryUrl(item.optString("thumbnailUrl")),
                hasOrbit = item.optBoolean("hasOrbit", false),
                hasSplat = item.optBoolean("hasSplat", false),
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                camFx = firstOptionalFloat(item, "camFx", "cam_fx", "fx"),
                camFy = firstOptionalFloat(item, "camFy", "cam_fy", "fy"),
                camCx = firstOptionalFloat(item, "camCx", "cam_cx", "cx"),
                camCy = firstOptionalFloat(item, "camCy", "cam_cy", "cy"),
                sourceUrl = firstOptionalUrl(item, "sourceUrl", "source_url"),
                orbitWebpUrl = firstOptionalUrl(item, "orbitWebpUrl", "orbit_webp_url"),
                orbitPreviewUrl = firstOptionalUrl(item, "orbitPreviewUrl", "orbit_preview_url"),
                splatStreamUrl = firstOptionalUrl(item, "splatStreamUrl", "splat_stream_url"),
                splatUrl = firstOptionalUrl(item, "splatUrl", "splat_url")
            )
        }
        return photos
    }

    private fun updateOrbitPreviews(enabled: Boolean) {
        if (albumEditMode && enabled) return
        previewEnabled = enabled
        previewRequestSerial++
        cascadeRunSerial++
        previewHandler.removeCallbacksAndMessages(null)
        albumAdapter?.previewEnabled = enabled
        val grid = albumGrid ?: return
        if (!enabled) {
            Log.i(PREVIEW_TAG, "Orbit preview off")
            for (i in 0 until grid.childCount) {
                stopCellPreview(grid.getChildAt(i), animate = true, reason = "toggle-off")
            }
        } else {
            refreshVisibleOrbitPreviews()
        }
    }

    private fun refreshVisibleOrbitPreviews() {
        val grid = albumGrid ?: return
        val adapter = albumAdapter ?: return
        if (!previewEnabled || albumEditMode) return
        previewHandler.removeCallbacksAndMessages(null)
        val runSerial = ++cascadeRunSerial
        var cascadeOrdinal = 0
        for (i in 0 until grid.childCount) {
            val position = grid.firstVisiblePosition + i
            val view = grid.getChildAt(i) as? AlbumCellView ?: continue
            val photo = adapter.photoAt(position) ?: continue
            if (!canPreviewPhoto(photo)) {
                stopCellPreview(view, reason = "no-orbit")
                continue
            }
            val ordinal = cascadeOrdinal++
            val delayMs = ordinal * PREVIEW_CASCADE_STAGGER_MS
            val startsAt = SystemClock.uptimeMillis() + delayMs
            view.cascadeOrdinal = ordinal
            view.cascadeScheduledAtMs = startsAt
            Log.i(
                PREVIEW_TAG,
                "Cascade schedule photoId=${photo.photoId} ordinal=$ordinal delayMs=$delayMs"
            )
            previewHandler.postDelayed({
                if (
                    runSerial == cascadeRunSerial &&
                    previewEnabled &&
                    view.boundPhotoId == photo.photoId &&
                    isCellStillVisible(view)
                ) {
                    adapter.updatePreviewForCell(view, photo)
                }
            }, delayMs)
        }
    }

    private fun startOrbitPreview(cell: AlbumCellView, photo: AlbumPhoto, url: String, requestSerial: Int) {
        if (cell.loadingPreviewPhotoId == photo.photoId || cell.playingPreviewPhotoId == photo.photoId) return
        cell.previewGeneration++
        val generation = cell.previewGeneration
        cell.loadingPreviewPhotoId = photo.photoId
        cell.playingPreviewPhotoId = null
        Log.i(PREVIEW_TAG, "Orbit preview loading photoId=${photo.photoId} url=$url")
        cell.preview.animate().cancel()
        cell.thumbnail.animate().cancel()
        stopPreviewDrawable(cell)
        cell.preview.setImageDrawable(null)
        cell.preview.visibility = View.GONE
        cell.preview.alpha = 0f
        cell.preview.scaleX = PREVIEW_REVEAL_START_SCALE
        cell.preview.scaleY = PREVIEW_REVEAL_START_SCALE
        cell.thumbnail.visibility = View.VISIBLE
        cell.thumbnail.alpha = 1f
        cell.thumbnail.bringToFront()
        networkExecutor.execute {
            try {
                val file = cachedOrbitPreview(photo.photoId, url)
                val drawable = decodeOrbitPreview(file)
                runOnUiThread {
                    if (
                        requestSerial == previewRequestSerial &&
                        previewEnabled &&
                        cell.boundPhotoId == photo.photoId &&
                        cell.loadingPreviewPhotoId == photo.photoId &&
                        cell.previewGeneration == generation
                    ) {
                        playOrbitPreview(cell, photo, drawable, requestSerial, generation)
                    } else if (cell.boundPhotoId == photo.photoId) {
                        cell.loadingPreviewPhotoId = null
                    }
                }
            } catch (exc: Exception) {
                Log.w(PREVIEW_TAG, "Orbit preview unavailable photoId=${photo.photoId}: ${exc.message}")
                runOnUiThread {
                    if (cell.boundPhotoId == photo.photoId) {
                        stopCellPreview(cell)
                    }
                }
            }
        }
    }

    private fun canPreviewPhoto(photo: AlbumPhoto): Boolean =
        photo.hasOrbit || (photo.imported && photo.localUri != null)

    private fun orbitPreviewCacheFile(photoId: String): File {
        val safeId = photoId.replace(Regex("[^A-Za-z0-9_.-]+"), "-")
        return File(cacheDir, "orbit-preview-$safeId-$ORBIT_PREVIEW_CACHE_VERSION.webp")
    }

    private fun cachedOrbitPreview(photoId: String, url: String): File {
        val file = orbitPreviewCacheFile(photoId)
        if (file.length() > 0L) {
            file.setLastModified(System.currentTimeMillis())
            Log.i(
                PREVIEW_TAG,
                "Orbit preview cache hit photoId=$photoId version=$ORBIT_PREVIEW_CACHE_VERSION bytes=${file.length()}"
            )
            return file
        }
        val started = System.nanoTime()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4_000
            readTimeout = 12_000
            requestMethod = "GET"
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.outputStream().use { out ->
                conn.inputStream.use { input -> input.copyTo(out) }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } finally {
            conn.disconnect()
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        Log.i(PREVIEW_TAG, "Orbit preview cached photoId=$photoId bytes=${file.length()} elapsedMs=$elapsedMs")
        return file
    }

    private fun enqueueDeviceOrbitPreviewBake(cell: AlbumCellView, photo: AlbumPhoto) {
        val imageUri = photo.localUri ?: return
        val cached = orbitPreviewCacheFile(photo.photoId)
        if (cached.length() > 0L) {
            val updated = photo.copy(hasOrbit = true, orbitWebpUrl = cached.toURI().toString())
            updateAlbumPhoto(updated)
            val url = loadOrbitPreview(updated) ?: return
            startOrbitPreview(cell, updated, url, previewRequestSerial)
            return
        }
        if (!previewBakeInFlight.add(photo.photoId)) {
            Log.i(PREVIEW_TAG, "Orbit preview bake already in flight photoId=${photo.photoId}")
            return
        }
        val requestSerial = previewRequestSerial
        val runSerial = cascadeRunSerial
        Log.i(
            PREVIEW_TAG,
            "Orbit preview bake enqueue photoId=${photo.photoId} ordinal=${cell.cascadeOrdinal} " +
                "visibleOnly=true inFlight=${previewBakeInFlight.size}"
        )
        previewBakeExecutor.execute {
            try {
                val started = SystemClock.elapsedRealtime()
                val file = postIngestPreviewWebp(photo, imageUri, cached)
                pruneOrbitPreviewCache()
                val elapsed = SystemClock.elapsedRealtime() - started
                runOnUiThread {
                    val updated = photo.copy(hasOrbit = true, orbitWebpUrl = file.toURI().toString())
                    updateAlbumPhoto(updated)
                    Log.i(
                        PREVIEW_TAG,
                        "Orbit preview bake done photoId=${photo.photoId} bytes=${file.length()} " +
                            "elapsedMs=$elapsed inFlight=${previewBakeInFlight.size}"
                    )
                    if (
                        requestSerial == previewRequestSerial &&
                        runSerial == cascadeRunSerial &&
                        previewEnabled &&
                        cell.boundPhotoId == updated.photoId &&
                        isCellStillVisible(cell)
                    ) {
                        val url = loadOrbitPreview(updated)
                        if (url != null) startOrbitPreview(cell, updated, url, requestSerial)
                    } else if (previewEnabled) {
                        refreshVisibleOrbitPreviews()
                    }
                }
            } catch (exc: Exception) {
                Log.w(PREVIEW_TAG, "Orbit preview bake failed photoId=${photo.photoId}: ${exc.message}", exc)
            } finally {
                previewBakeInFlight.remove(photo.photoId)
            }
        }
    }

    private fun updateAlbumPhoto(updated: AlbumPhoto) {
        albumPhotos = albumPhotos.map { if (it.photoId == updated.photoId) updated else it }
        albumAdapter?.rememberUpdatedPhoto(updated)
        val importedIndex = importedPhotos.indexOfFirst { it.photoId == updated.photoId }
        if (importedIndex >= 0) importedPhotos[importedIndex] = updated
    }

    private fun postIngestPreviewWebp(photo: AlbumPhoto, imageUri: Uri, outFile: File): File {
        outFile.parentFile?.mkdirs()
        val boundary = "----WiciAndroidPreview${SystemClock.elapsedRealtimeNanos()}"
        val mime = contentResolver.getType(imageUri) ?: "image/jpeg"
        val previewUploadJpeg = buildPreviewUploadJpeg(photo.photoId, imageUri)
        val uploadMime = if (previewUploadJpeg != null) "image/jpeg" else mime
        val filename = if (previewUploadJpeg != null) {
            "android-preview-${System.currentTimeMillis()}.jpg"
        } else {
            "android-preview-${System.currentTimeMillis()}.${extensionForMime(mime)}"
        }
        val tmp = File(outFile.parentFile, "${outFile.name}.tmp-${SystemClock.elapsedRealtimeNanos()}")
        val conn = (URL("${galleryUrl()}/ingest_preview").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "image/webp")
        }
        try {
            conn.outputStream.use { out ->
                writeMultipartText(out, boundary, "photoId", photo.photoId)
                writeMultipartText(out, boundary, "title", photo.photoId)
                out.writeUtf8("--$boundary\r\n")
                out.writeUtf8("Content-Disposition: form-data; name=\"image\"; filename=\"$filename\"\r\n")
                out.writeUtf8("Content-Type: $uploadMime\r\n\r\n")
                if (previewUploadJpeg != null) {
                    out.write(previewUploadJpeg)
                } else {
                    contentResolver.openInputStream(imageUri).use { input ->
                        requireNotNull(input) { "openInputStream returned null" }
                        input.copyTo(out)
                    }
                }
                out.writeUtf8("\r\n--$boundary--\r\n")
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            if (conn.responseCode !in 200..299) {
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw RuntimeException("HTTP ${conn.responseCode}: ${text.take(220)}")
            }
            val contentType = conn.contentType.orEmpty()
            if (!contentType.contains("image/webp", ignoreCase = true)) {
                Log.w(PREVIEW_TAG, "ingest_preview contentType=$contentType for photoId=${photo.photoId}")
            }
            tmp.outputStream().use { out ->
                requireNotNull(stream) { "ingest_preview response body missing" }.use { input -> input.copyTo(out) }
            }
            if (tmp.length() <= 0L) throw RuntimeException("empty ingest_preview response")
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
            Log.i(
                PREVIEW_TAG,
                "Orbit preview bake cached photoId=${photo.photoId} bytes=${outFile.length()} " +
                    "serverTiming=${conn.headerString("X-Orbit-Timing-Json")?.take(180)}"
            )
            return outFile
        } finally {
            if (tmp.exists()) tmp.delete()
            conn.disconnect()
        }
    }

    private fun buildPreviewUploadJpeg(photoId: String, imageUri: Uri): ByteArray? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = decodeLocalBitmap(imageUri, PREVIEW_UPLOAD_MAX_SIDE)
            val bytes = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_UPLOAD_JPEG_QUALITY, bytes)
            if (!ok) throw RuntimeException("JPEG compress returned false")
            val payload = bytes.toByteArray()
            Log.i(
                PREVIEW_TAG,
                "Orbit preview upload downscaled photoId=$photoId " +
                    "bitmap=${bitmap.width}x${bitmap.height} bytes=${payload.size}"
            )
            payload
        } catch (exc: Exception) {
            Log.w(
                PREVIEW_TAG,
                "Orbit preview upload downscale failed photoId=$photoId; falling back to raw upload: ${exc.message}",
                exc
            )
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun buildFullReframeUploadJpeg(photoId: String, imageUri: Uri): ByteArray? {
        var bitmap: Bitmap? = null
        return try {
            bitmap = decodeLocalBitmap(imageUri, PREVIEW_UPLOAD_MAX_SIDE)
            val bytes = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, PREVIEW_UPLOAD_JPEG_QUALITY, bytes)
            if (!ok) throw RuntimeException("JPEG compress returned false")
            val payload = bytes.toByteArray()
            Log.i(
                TAG,
                "Ingest upload downscaled photoId=$photoId " +
                    "bitmap=${bitmap.width}x${bitmap.height} bytes=${payload.size}"
            )
            payload
        } catch (exc: Exception) {
            Log.w(
                TAG,
                "Ingest upload downscale failed photoId=$photoId; falling back to raw upload: ${exc.message}",
                exc
            )
            null
        } finally {
            bitmap?.recycle()
        }
    }

    private fun pruneOrbitPreviewCache() {
        val files = cacheDir.listFiles { file ->
            file.isFile && file.name.startsWith("orbit-preview-") && file.name.endsWith(".webp")
        }?.toList().orEmpty()
        var total = files.sumOf { it.length() }
        if (total <= ORBIT_PREVIEW_CACHE_MAX_BYTES) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= ORBIT_PREVIEW_CACHE_MAX_BYTES) break
            val size = file.length()
            if (file.delete()) {
                total -= size
                Log.i(PREVIEW_TAG, "Orbit preview cache evict file=${file.name} bytes=$size total=$total")
            }
        }
    }

    private fun decodeOrbitPreview(file: File): Drawable =
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(file))

    private fun playOrbitPreview(
        cell: AlbumCellView,
        photo: AlbumPhoto,
        drawable: Drawable,
        requestSerial: Int,
        generation: Int
    ) {
        if (
            requestSerial != previewRequestSerial ||
            !previewEnabled ||
            cell.boundPhotoId != photo.photoId ||
            cell.previewGeneration != generation ||
            cell.loadingPreviewPhotoId != photo.photoId ||
            !isCellStillVisible(cell)
        ) {
            cell.loadingPreviewPhotoId = null
            return
        }
        cell.preview.setImageDrawable(drawable)
        cell.preview.visibility = View.VISIBLE
        cell.preview.alpha = 0f
        cell.preview.scaleX = PREVIEW_REVEAL_START_SCALE
        cell.preview.scaleY = PREVIEW_REVEAL_START_SCALE
        cell.preview.bringToFront()
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
        cell.loadingPreviewPhotoId = null
        cell.playingPreviewPhotoId = photo.photoId
        val startedAtMs = SystemClock.uptimeMillis()
        val scheduledAtMs = cell.cascadeScheduledAtMs.takeIf { it > 0L } ?: startedAtMs
        Log.i(
            PREVIEW_TAG,
            "Cascade start photoId=${photo.photoId} ordinal=${cell.cascadeOrdinal} " +
                "tMs=$startedAtMs scheduledDeltaMs=${startedAtMs - scheduledAtMs} " +
                "active=${visibleAnimatedPreviewCount()} animated=${drawable is AnimatedImageDrawable}"
        )
        cell.preview.animate().cancel()
        cell.preview.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(PREVIEW_REVEAL_MS)
            .withEndAction {
                if (
                    cell.previewGeneration == generation &&
                    cell.playingPreviewPhotoId == photo.photoId
                ) {
                    cell.thumbnail.visibility = View.GONE
                }
            }
            .start()
        Log.i(PREVIEW_TAG, "Orbit preview playing photoId=${photo.photoId} format=webp animated=${drawable is AnimatedImageDrawable}")
    }

    private fun loadOrbitPreview(photo: AlbumPhoto): String? {
        val url = photo.orbitWebpUrl
            ?: photo.orbitPreviewUrl
            ?: if (photo.hasOrbit && !photo.imported) {
                "${orbitPreviewUrl()}?photoId=${Uri.encode(photo.photoId)}&format=webp"
            } else {
                null
            }
            ?: return null
        val absolute = if (url.startsWith("file:")) url else absoluteGalleryUrl(url)
        val separator = if (absolute.contains("?")) "&" else "?"
        return "$absolute${separator}v=$ORBIT_PREVIEW_CACHE_VERSION"
    }

    private fun enterAlbumEditMode() {
        if (albumEditMode || viewerVisible) return
        albumEditMode = true
        updateOrbitPreviews(false)
        styleAlbumActionButton()
        albumAdapter?.notifyDataSetChanged()
        refreshVisibleEditMode()
        Log.i(TAG, "Album edit mode entered")
    }

    private fun exitAlbumEditMode() {
        if (!albumEditMode) return
        albumEditMode = false
        styleAlbumActionButton()
        albumGrid?.let { grid ->
            for (i in 0 until grid.childCount) {
                (grid.getChildAt(i) as? AlbumCellView)?.let { stopCellJiggle(it) }
            }
        }
        albumAdapter?.notifyDataSetChanged()
        Log.i(TAG, "Album edit mode exited")
    }

    private fun styleAlbumActionButton() {
        val button = albumActionButton ?: return
        if (albumEditMode) {
            button.text = "Done"
            button.setOnClickListener { exitAlbumEditMode() }
            button.setTextColor(COLOR_INK)
            button.background = rounded(COLOR_SURFACE, dp(19).toFloat(), dpFloat(1f), COLOR_HAIRLINE)
            applySoftShadow(button, 2)
        } else {
            button.text = "3D"
            button.setOnClickListener {
                updateOrbitPreviews(!previewEnabled)
                stylePreviewToggle(button, previewEnabled)
            }
            stylePreviewToggle(button, previewEnabled)
        }
    }

    private fun refreshVisibleEditMode() {
        val grid = albumGrid ?: return
        for (i in 0 until grid.childCount) {
            val position = grid.firstVisiblePosition + i
            val cell = grid.getChildAt(i) as? AlbumCellView ?: continue
            val photo = albumAdapter?.photoAt(position)
            applyEditModeToCell(cell, photo)
        }
    }

    private fun removePhotoFromAlbum(photo: AlbumPhoto) {
        if (photo.imported) {
            importedPhotos.removeAll { it.photoId == photo.photoId }
        } else {
            removedCuratedPhotoIds.add(photo.photoId)
            saveRemovedCuratedPhotoIds()
        }
        thumbnailCache.remove(photo.photoId)
        albumPhotos = albumPhotos.filterNot { it.photoId == photo.photoId }
        albumAdapter?.setPhotos(albumPhotos)
        albumStatus?.text = momentCountText()
        refreshVisibleEditMode()
        Log.i(TAG, "Album photo removed locally photoId=${photo.photoId} imported=${photo.imported}")
    }

    private fun applyEditModeToCell(cell: AlbumCellView, photo: AlbumPhoto?) {
        val isPhoto = photo != null
        cell.deleteBadge.visibility = if (albumEditMode && isPhoto) View.VISIBLE else View.GONE
        if (albumEditMode && isPhoto) {
            applySoftShadow(cell.deleteBadge, 3)
            cell.deleteBadge.bringToFront()
            startCellJiggle(cell)
        } else {
            stopCellJiggle(cell)
        }
    }

    private fun startCellJiggle(cell: AlbumCellView) {
        if (cell.jiggleAnimator?.isStarted == true) return
        cell.rotation = -EDIT_JIGGLE_DEGREES
        cell.jiggleAnimator = ObjectAnimator.ofFloat(cell, View.ROTATION, -EDIT_JIGGLE_DEGREES, EDIT_JIGGLE_DEGREES).apply {
            duration = EDIT_JIGGLE_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = LinearInterpolator()
            startDelay = ((cell.boundPhotoId?.hashCode() ?: 0).let { kotlin.math.abs(it) } % 80).toLong()
            start()
        }
    }

    private fun stopCellJiggle(cell: AlbumCellView) {
        cell.jiggleAnimator?.cancel()
        cell.jiggleAnimator = null
        cell.rotation = 0f
    }

    private fun handleAlbumCellTouch(cell: AlbumCellView, photo: AlbumPhoto, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cell.pendingLongPress?.let { previewHandler.removeCallbacks(it) }
                cell.touchDownX = event.x
                cell.touchDownY = event.y
                val runnable = Runnable {
                    if (cell.boundPhotoId == photo.photoId && !albumEditMode && !viewerVisible) {
                        cell.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        enterAlbumEditMode()
                    }
                }
                cell.pendingLongPress = runnable
                previewHandler.postDelayed(runnable, EDIT_LONG_PRESS_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = kotlin.math.abs(event.x - cell.touchDownX)
                val dy = kotlin.math.abs(event.y - cell.touchDownY)
                if (dx > dpFloat(10f) || dy > dpFloat(10f)) {
                    cell.pendingLongPress?.let { previewHandler.removeCallbacks(it) }
                    cell.pendingLongPress = null
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cell.pendingLongPress?.let { previewHandler.removeCallbacks(it) }
                cell.pendingLongPress = null
            }
        }
        return false
    }

    private fun isTouchInsideGridChild(grid: GridView, x: Float, y: Float): Boolean {
        for (i in 0 until grid.childCount) {
            val child = grid.getChildAt(i)
            if (x >= child.left && x <= child.right && y >= child.top && y <= child.bottom) {
                return true
            }
        }
        return false
    }

    private inner class AlbumGridAdapter : BaseAdapter() {
        private var photos: List<AlbumPhoto> = emptyList()
        var previewEnabled: Boolean = false

        fun setPhotos(next: List<AlbumPhoto>) {
            photos = next
            notifyDataSetChanged()
        }

        fun rememberUpdatedPhoto(updated: AlbumPhoto) {
            photos = photos.map { if (it.photoId == updated.photoId) updated else it }
        }

        fun photoAt(position: Int): AlbumPhoto? =
            photos.getOrNull(position - 1)

        override fun getCount(): Int = photos.size + 1

        override fun getItem(position: Int): Any =
            photoAt(position) ?: ADD_TILE_ID

        override fun getItemId(position: Int): Long =
            if (position == 0) ADD_TILE_ID.hashCode().toLong() else photos[position - 1].photoId.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? AlbumCellView) ?: AlbumCellView(this@MainActivity)
            val photo = photoAt(position)
            if (photo == null) {
                bindAddCell(cell)
            } else {
                bindCell(cell, photo)
            }
            return cell
        }

        private fun bindAddCell(cell: AlbumCellView) {
            if (cell.boundPhotoId != ADD_TILE_ID) {
                stopCellPreview(cell)
            }
            cell.boundPhotoId = ADD_TILE_ID
            cell.alpha = 1f
            cell.contentDescription = "Add photo"
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener {
                if (albumEditMode) exitAlbumEditMode() else launchPhotoPicker()
            }
            cell.background = rounded(COLOR_SURFACE, dp(14).toFloat(), dpFloat(1.5f), COLOR_HAIRLINE)
            cell.clipToOutline = true
            applySoftShadow(cell)
            cell.thumbnail.visibility = View.GONE
            cell.preview.visibility = View.GONE
            cell.addStack.visibility = View.VISIBLE
            cell.addPlus.typeface = inter(300)
            cell.addLabel.typeface = inter(600)
            cell.addStack.bringToFront()
            cell.setOnLongClickListener(null)
            cell.isLongClickable = false
            cell.setOnTouchListener(null)
            cell.pendingLongPress?.let { previewHandler.removeCallbacks(it) }
            cell.pendingLongPress = null
            cell.deleteBadge.setOnClickListener(null)
            applyEditModeToCell(cell, null)
        }

        private fun bindCell(cell: AlbumCellView, photo: AlbumPhoto) {
            if (cell.boundPhotoId != photo.photoId) {
                stopCellPreview(cell)
            }
            cell.boundPhotoId = photo.photoId
            cell.background = rounded(COLOR_SURFACE, dp(14).toFloat())
            cell.clipToOutline = true
            applySoftShadow(cell)
            cell.alpha = 1f
            cell.contentDescription = photo.photoId
            cell.isClickable = true
            cell.isFocusable = true
            cell.setOnClickListener {
                if (!albumEditMode) beginReframing(photo)
            }
            cell.setOnLongClickListener {
                enterAlbumEditMode()
                true
            }
            cell.setOnTouchListener { _, event -> handleAlbumCellTouch(cell, photo, event) }
            cell.deleteBadge.setOnClickListener {
                removePhotoFromAlbum(photo)
            }
            cell.addStack.visibility = View.GONE
            cell.thumbnail.visibility = View.VISIBLE
            cell.thumbnail.alpha = 1f
            cell.thumbnail.bringToFront()
            cell.thumbnail.setBackgroundColor(COLOR_SURFACE)
            loadThumbnail(photo, cell)
            applyEditModeToCell(cell, photo)
        }

        fun updatePreviewForCell(cell: AlbumCellView, photo: AlbumPhoto) {
            if (!previewEnabled || !canPreviewPhoto(photo)) {
                stopCellPreview(cell)
                return
            }
            val url = loadOrbitPreview(photo)
            if (url == null) {
                enqueueDeviceOrbitPreviewBake(cell, photo)
                return
            }
            startOrbitPreview(cell, photo, url, previewRequestSerial)
        }
    }

    private class AlbumCellView(context: Context) : SquareFrameLayout(context) {
        val preview: ImageView = ImageView(context).apply {
            visibility = View.GONE
            isClickable = false
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        val thumbnail: ImageView = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = false
        }
        val addPlus: TextView = TextView(context).apply {
            text = "+"
            textSize = 28f
            setTextColor(COLOR_INK)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        val addLabel: TextView = TextView(context).apply {
            text = "Add"
            textSize = 12f
            setTextColor(COLOR_INK_SOFT)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        val addStack: LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            isClickable = false
            addView(addPlus, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(addLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val deleteBadge: DeleteBadgeView = DeleteBadgeView(context).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            contentDescription = "Remove photo"
        }
        var boundPhotoId: String? = null
        var loadingPreviewPhotoId: String? = null
        var playingPreviewPhotoId: String? = null
        var previewGeneration: Int = 0
        var cascadeOrdinal: Int = -1
        var cascadeScheduledAtMs: Long = 0L
        var jiggleAnimator: ObjectAnimator? = null
        var pendingLongPress: Runnable? = null
        var touchDownX: Float = 0f
        var touchDownY: Float = 0f

        init {
            addView(preview, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(thumbnail, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(addStack, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            val density = context.resources.displayMetrics.density
            val badgeSize = (30f * density + 0.5f).toInt()
            val badgeInset = (5f * density + 0.5f).toInt()
            addView(
                deleteBadge,
                FrameLayout.LayoutParams(badgeSize, badgeSize, Gravity.TOP or Gravity.END).apply {
                    topMargin = badgeInset
                    rightMargin = badgeInset
                }
            )
        }
    }

    private fun stopCellPreview(view: View?, animate: Boolean = false, reason: String = "stop") {
        val cell = view as? AlbumCellView ?: return
        val photoId = cell.boundPhotoId ?: cell.loadingPreviewPhotoId ?: cell.playingPreviewPhotoId
        cell.previewGeneration++
        val generation = cell.previewGeneration
        cell.loadingPreviewPhotoId = null
        cell.playingPreviewPhotoId = null
        cell.cascadeOrdinal = -1
        cell.cascadeScheduledAtMs = 0L
        cell.preview.animate().cancel()
        cell.thumbnail.animate().cancel()
        cell.thumbnail.visibility = View.VISIBLE
        cell.thumbnail.alpha = 1f
        cell.thumbnail.bringToFront()
        if (cell.preview.visibility == View.VISIBLE) {
            Log.i(PREVIEW_TAG, "Orbit preview stop photoId=$photoId reason=$reason animate=$animate")
        }
        if (animate && cell.preview.visibility == View.VISIBLE) {
            cell.preview.animate()
                .alpha(0f)
                .scaleX(PREVIEW_REVEAL_START_SCALE)
                .scaleY(PREVIEW_REVEAL_START_SCALE)
                .setDuration(PREVIEW_REVEAL_MS)
                .withEndAction {
                    if (cell.previewGeneration == generation) {
                        stopPreviewDrawable(cell)
                        cell.preview.setImageDrawable(null)
                        cell.preview.visibility = View.GONE
                    }
                }
                .start()
        } else {
            stopPreviewDrawable(cell)
            cell.preview.setImageDrawable(null)
            cell.preview.visibility = View.GONE
            cell.preview.alpha = 0f
            cell.preview.scaleX = PREVIEW_REVEAL_START_SCALE
            cell.preview.scaleY = PREVIEW_REVEAL_START_SCALE
        }
    }

    private fun stopPreviewDrawable(cell: AlbumCellView) {
        (cell.preview.drawable as? AnimatedImageDrawable)?.stop()
    }

    private fun isCellStillVisible(cell: AlbumCellView): Boolean {
        val grid = albumGrid ?: return false
        if (cell.parent !== grid || cell.windowToken == null) return false
        for (i in 0 until grid.childCount) {
            if (grid.getChildAt(i) === cell) return true
        }
        return false
    }

    private fun visibleAnimatedPreviewCount(): Int {
        val grid = albumGrid ?: return 0
        var count = 0
        for (i in 0 until grid.childCount) {
            val cell = grid.getChildAt(i) as? AlbumCellView ?: continue
            if (cell.playingPreviewPhotoId != null && cell.preview.visibility == View.VISIBLE) count++
        }
        return count
    }

    private fun loadThumbnail(photo: AlbumPhoto, cell: AlbumCellView) {
        synchronized(thumbnailCache) { thumbnailCache[photo.photoId] }?.let {
            if (cell.boundPhotoId == photo.photoId) {
                cell.thumbnail.setImageBitmap(it)
            }
            return
        }
        cell.thumbnail.setImageBitmap(null)
        networkExecutor.execute {
            try {
                val bitmap = photo.localUri
                    ?.let { decodeLocalBitmap(it, THUMBNAIL_MAX_SIDE) }
                    ?: fetchBitmap(photo.thumbnailUrl)
                synchronized(thumbnailCache) {
                    thumbnailCache[photo.photoId] = bitmap
                }
                runOnUiThread {
                    if (cell.boundPhotoId == photo.photoId) {
                        cell.thumbnail.setImageBitmap(bitmap)
                        Log.i(TAG, "Thumbnail loaded photoId=${photo.photoId}")
                    }
                }
            } catch (exc: Exception) {
                Log.w(TAG, "Thumbnail failed photoId=${photo.photoId}: ${exc.message}")
            }
        }
    }

    private fun loadDisplayBitmap(photo: AlbumPhoto): Bitmap =
        photo.localUri
            ?.let { decodeLocalBitmap(it, DISPLAY_IMAGE_MAX_SIDE) }
            ?: fetchBitmap(photo.sourceUrl ?: absoluteGalleryUrl("/source?photoId=${Uri.encode(photo.photoId)}"))

    private fun showPhotoScreen(photo: AlbumPhoto) {
        viewerVisible = true
        previewEnabled = false
        glView?.shutdown()
        glView?.onPause()
        glView = null
        viewerRoot = null
        generateErrorOverlay = null
        latestCapture = null
        fluxDataUrl = null
        finalBitmap = null
        previewRequestSerial++
        cascadeRunSerial++
        previewHandler.removeCallbacksAndMessages(null)
        albumGrid?.let { grid ->
            for (i in 0 until grid.childCount) {
                stopCellPreview(grid.getChildAt(i), reason = "open-photo")
            }
        }

        val imagePlate = FrameLayout(this).apply {
            background = rounded(COLOR_SURFACE, dp(16).toFloat())
            clipToOutline = true
            applySoftShadow(this)
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(COLOR_SURFACE)
            contentDescription = "flat-photo"
        }
        imagePlate.addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        val status = TextView(this).apply {
            setTextColor(COLOR_INK_SOFT)
            textSize = 13f
            typeface = inter(500)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        setInlineStatus(status, if (photo.hasSplat || photo.localUri != null) "" else "3D view unavailable")
        val back = studioBackButton()
        val reframe = TextView(this).apply {
            text = "Reframe"
            contentDescription = "reframing"
            isEnabled = photo.hasSplat || photo.localUri != null
            setTextColor(if (isEnabled) COLOR_INK else COLOR_INK_SOFT)
            textSize = 15f
            typeface = inter(700)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minHeight = dp(48)
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = roundedState(COLOR_SURFACE, 0xFFF6F7FA.toInt(), dp(24).toFloat(), dpFloat(1f), COLOR_HAIRLINE)
            applySoftShadow(this, 4)
            setOnClickListener { beginReframing(photo) }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            addView(
                imagePlate,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                ).apply {
                    leftMargin = dp(18)
                    rightMargin = dp(18)
                    topMargin = dp(82)
                    bottomMargin = dp(112)
                }
            )
            addView(
                back,
                FrameLayout.LayoutParams(
                    dp(44),
                    dp(44),
                    Gravity.START or Gravity.TOP
                ).apply {
                    topMargin = dp(26)
                    leftMargin = dp(18)
                }
            )
            addView(
                status,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(96)
                }
            )
            addView(
                reframe,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                ).apply {
                    bottomMargin = dp(30)
                }
            )
        }
        applyFullscreen(root)
        setContentView(root)

        networkExecutor.execute {
            try {
                val bitmap = loadDisplayBitmap(photo)
                runOnUiThread {
                    fitPlateToBitmap(imagePlate, bitmap.width, bitmap.height, dp(18), dp(82), dp(112))
                    image.setImageBitmap(bitmap)
                    setInlineStatus(status, if (photo.hasSplat || photo.localUri != null) "" else "3D view unavailable")
                    Log.i(TAG, "Flat photo loaded photoId=${photo.photoId} size=${bitmap.width}x${bitmap.height}")
                }
            } catch (exc: Exception) {
                Log.w(TAG, "Flat photo failed photoId=${photo.photoId}: ${exc.message}", exc)
                runOnUiThread { setInlineStatus(status, "Photo unavailable: ${exc.message}") }
            }
        }
    }

    private fun beginReframing(photo: AlbumPhoto) {
        saveAlbumScrollPosition("beginReframing")
        if (!photo.imported && photo.hasSplat) {
            if (hasCachedSplat(photo.photoId)) {
                showViewer(photo)
            } else {
                beginBackendCheckedViewer(photo, "curated-stream")
            }
            return
        }
        val localUri = photo.localUri
        if (photo.imported && localUri != null) {
            val cacheKey = SplatCache.keyFor(photo.photoId, DEFAULT_SPLAT_DENSITY)
            if (SplatCache.lookup(this, cacheKey, SPLAT_ROW_BYTES) != null) {
                Log.i(TAG, "Imported Reframing cache hit photoId=${photo.photoId} key=$cacheKey")
                showViewer(photo.copy(hasSplat = true))
                return
            }
            Log.i(TAG, "Imported Reframing cache miss photoId=${photo.photoId} key=$cacheKey; streaming ingest into viewer")
        }
        if (localUri == null) {
            showStaticPhoto(photo)
            return
        }
        Log.i(TAG, "Ingest progressive start localPhotoId=${photo.photoId} uri=$localUri")
        beginBackendCheckedViewer(photo.copy(hasSplat = true, imported = true, splatStreamUrl = null, splatUrl = null), "ingest")
    }

    private fun hasCachedSplat(photoId: String): Boolean {
        val density = streamDensityOverride
            ?.takeIf { it.isNotBlank() && it != "default" }
            ?: DEFAULT_SPLAT_DENSITY
        val cacheKey = SplatCache.keyFor(photoId, density)
        return SplatCache.lookup(this, cacheKey, SPLAT_ROW_BYTES) != null
    }

    private fun beginBackendCheckedViewer(photo: AlbumPhoto, reason: String) {
        val status = showReframingLoading(photo)
        val base = backendBaseUrl()
        if (requiresCloudLogin(base) && !SupabaseAuth.isLoggedIn()) {
            status.text = "Sign in to use WiCi Cloud..."
            promptCloudLogin("Reframe", onFailed = {
                status.text = "Sign in with Google to use WiCi Cloud."
            }) {
                beginBackendCheckedViewer(photo, reason)
            }
            return
        }
        status.text = "Checking 3D server..."
        Log.i(TAG, "Backend health precheck start photoId=${photo.photoId} reason=$reason base=$base source=${backendSourceLabel()}")
        networkExecutor.execute {
            val health = checkOrbitHealth(base)
            runOnUiThread {
                if (health.ok) {
                    Log.i(TAG, "Backend health precheck ok photoId=${photo.photoId} reason=$reason base=$base elapsedMs=${health.elapsedMs}")
                    showViewer(photo)
                } else {
                    Log.w(TAG, "Backend health precheck failed photoId=${photo.photoId} reason=$reason base=$base elapsedMs=${health.elapsedMs} error=${health.message}")
                    showBackendUnavailable(photo, base, "health", health.message)
                }
            }
        }
    }

    private fun showReframingLoading(photo: AlbumPhoto): TextView {
        saveAlbumScrollPosition("showReframingLoading")
        viewerVisible = true
        previewEnabled = false
        glView?.shutdown()
        glView?.onPause()
        glView = null
        viewerRoot = null
        generateErrorOverlay = null
        latestCapture = null
        fluxDataUrl = null
        finalBitmap = null
        previewRequestSerial++
        cascadeRunSerial++
        previewHandler.removeCallbacksAndMessages(null)
        albumGrid?.let { grid ->
            for (i in 0 until grid.childCount) {
                stopCellPreview(grid.getChildAt(i), reason = "reframing")
            }
        }

        val status = TextView(this).apply {
            setTextColor(COLOR_INK_SOFT)
            text = "Reframing..."
            textSize = 15f
            typeface = inter(600)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val spinner = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(COLOR_INK_SOFT)
        }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(spinner, LinearLayout.LayoutParams(dp(34), dp(34)).apply { bottomMargin = dp(14) })
            addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 0
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            addView(
                studioBackButton(),
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.TOP).apply {
                    topMargin = dp(26)
                    leftMargin = dp(18)
                }
            )
            addView(
                stack,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
            )
        }
        applyFullscreen(root)
        setContentView(root)
        Log.i(TAG, "Reframing screen photoId=${photo.photoId}")
        return status
    }

    private data class BackendHealthResult(
        val ok: Boolean,
        val message: String,
        val elapsedMs: Long
    )

    private data class GenerateHealthResult(
        val ok: Boolean,
        val service: String?,
        val message: String,
        val elapsedMs: Long
    )

    private fun checkBackendServiceHealth(baseUrl: String, service: String): BackendHealthResult {
        val started = SystemClock.elapsedRealtimeNanos()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("$baseUrl/$service/health").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = BACKEND_HEALTH_TIMEOUT_MS
                readTimeout = BACKEND_HEALTH_TIMEOUT_MS
            }
            val code = conn.responseCode
            if (code in 200..299) {
                BackendHealthResult(true, "HTTP $code", elapsedMs(started))
            } else {
                BackendHealthResult(false, "HTTP $code", elapsedMs(started))
            }
        } catch (exc: Exception) {
            BackendHealthResult(false, exc.message ?: exc.javaClass.simpleName, elapsedMs(started))
        } finally {
            conn?.disconnect()
        }
    }

    private fun checkOrbitHealth(baseUrl: String): BackendHealthResult =
        checkBackendServiceHealth(baseUrl, "orbit")

    private fun checkGenerateHealth(baseUrl: String): GenerateHealthResult {
        val started = SystemClock.elapsedRealtimeNanos()
        val difix = checkBackendServiceHealth(baseUrl, "difix")
        if (!difix.ok) {
            return GenerateHealthResult(false, "Difix", difix.message, elapsedMs(started))
        }
        val flux = checkBackendServiceHealth(baseUrl, "flux")
        if (!flux.ok) {
            return GenerateHealthResult(false, "FLUX", flux.message, elapsedMs(started))
        }
        return GenerateHealthResult(true, null, "ok", elapsedMs(started))
    }

    private fun shouldFallbackDiscoveredLocal(baseUrl: String): Boolean {
        val discovered = discoveredBackendBaseUrl ?: return false
        if (manualBackendBaseUrl() != null) return false
        return normalizeBackendBaseUrl(baseUrl) == discovered && discovered != DEFAULT_BACKEND_BASE_URL
    }

    private fun switchDiscoveredLocalToCloud(reason: String) {
        if (discoveredBackendBaseUrl == null) return
        Log.w(TAG, "Backend local fallback to cloud reason=$reason old=$discoveredBackendBaseUrl cloud=$DEFAULT_BACKEND_BASE_URL")
        stopBackendDiscovery()
        discoveredBackendBaseUrl = null
        backendDiscoveryInProgress = false
        backendDiscoveryCompleted = true
    }

    private fun showBackendUnavailable(photo: AlbumPhoto, failedBaseUrl: String, stage: String, detail: String) {
        val fellBackToCloud = shouldFallbackDiscoveredLocal(failedBaseUrl)
        if (fellBackToCloud) switchDiscoveredLocalToCloud(stage)

        viewerVisible = true
        previewEnabled = false
        glView?.shutdown()
        glView?.onPause()
        glView = null
        latestCapture = null
        fluxDataUrl = null
        finalBitmap = null
        currentViewerPhoto = photo

        val title = TextView(this).apply {
            text = "Can't reach the 3D server"
            setTextColor(COLOR_INK)
            textSize = 22f
            typeface = spaceGrotesk(700)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val hint = TextView(this).apply {
            text = if (fellBackToCloud) {
                "Local server is down. Retry will use WiCi Cloud."
            } else {
                "Check the server address or connection, then retry."
            }
            setTextColor(COLOR_INK_SOFT)
            textSize = 14f
            typeface = inter(500)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLineSpacing(dpFloat(2f), 1f)
        }
        val retry = TextView(this).apply {
            text = "Retry"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = inter(700)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(132)
            setPadding(dp(26), 0, dp(26), 0)
            isClickable = true
            isFocusable = true
            background = roundedState(COLOR_ACCENT, COLOR_ACCENT_PRESS, dp(24).toFloat())
            applySoftShadow(this, 0)
            setOnClickListener { beginReframing(photo) }
        }
        val detailLine = TextView(this).apply {
            text = backendAddressLabel(backendBaseUrl()).orEmpty()
            setTextColor(COLOR_INK_SOFT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            includeFontPadding = false
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setSingleLine(true)
        }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(30), dp(30), dp(30))
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(detailLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(retry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                topMargin = dp(22)
            })
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            addView(
                studioBackButton(),
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.TOP).apply {
                    topMargin = dp(26)
                    leftMargin = dp(18)
                }
            )
            addView(
                stack,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    leftMargin = dp(18)
                    rightMargin = dp(18)
                }
            )
        }
        applyFullscreen(root)
        setContentView(root)
        Log.w(
            TAG,
            "Backend unavailable displayed photoId=${photo.photoId} stage=$stage failedBase=$failedBaseUrl " +
                "fallbackCloud=$fellBackToCloud detail=${detail.take(180)} effective=${backendBaseUrl()}"
        )
    }

    private fun clearGenerateErrorOverlay() {
        val overlayView = generateErrorOverlay ?: return
        (overlayView.parent as? ViewGroup)?.removeView(overlayView)
        generateErrorOverlay = null
    }

    private fun showGenerateUnavailable(photo: AlbumPhoto, failedBaseUrl: String, stage: String, detail: String) {
        val root = viewerRoot
        if (root == null) {
            showBackendUnavailable(photo, failedBaseUrl, stage, detail)
            return
        }
        val fellBackToCloud = shouldFallbackDiscoveredLocal(failedBaseUrl)
        if (fellBackToCloud) switchDiscoveredLocalToCloud(stage)
        fluxBusy = false
        difixBusy = false
        clearGenerateErrorOverlay()
        updateViewerControls()

        val title = TextView(this).apply {
            text = "Generate isn't available"
            setTextColor(COLOR_INK)
            textSize = 22f
            typeface = spaceGrotesk(700)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        val hint = TextView(this).apply {
            text = if (fellBackToCloud) {
                "Local server can't finish generating. Retry will use WiCi Cloud."
            } else {
                "This server can't finish generating right now. Try again, or switch to the cloud server in Settings."
            }
            setTextColor(COLOR_INK_SOFT)
            textSize = 14f
            typeface = inter(500)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setLineSpacing(dpFloat(2f), 1f)
        }
        val detailLine = TextView(this).apply {
            text = backendAddressLabel(backendBaseUrl()).orEmpty()
            setTextColor(COLOR_INK_SOFT)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            includeFontPadding = false
            ellipsize = TextUtils.TruncateAt.MIDDLE
            setSingleLine(true)
        }
        val retry = TextView(this).apply {
            text = "Retry"
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = inter(700)
            gravity = Gravity.CENTER
            includeFontPadding = false
            minWidth = dp(132)
            setPadding(dp(26), 0, dp(26), 0)
            isClickable = true
            isFocusable = true
            background = roundedState(COLOR_ACCENT, COLOR_ACCENT_PRESS, dp(24).toFloat())
            applySoftShadow(this, 0)
            setOnClickListener {
                clearGenerateErrorOverlay()
                if (latestCapture != null) {
                    displayMode = DisplayMode.RAW
                    updateViewerControls()
                    generateFlux()
                } else {
                    beginReframing(photo)
                }
            }
        }
        val stack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(30), dp(30), dp(30), dp(30))
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(detailLine, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(12)
            })
            addView(retry, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                topMargin = dp(22)
            })
        }
        val errorView = FrameLayout(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            addView(
                studioBackButton(),
                FrameLayout.LayoutParams(dp(44), dp(44), Gravity.START or Gravity.TOP).apply {
                    topMargin = dp(26)
                    leftMargin = dp(18)
                }
            )
            addView(
                stack,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    leftMargin = dp(18)
                    rightMargin = dp(18)
                }
            )
        }
        generateErrorOverlay = errorView
        root.addView(errorView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        updateViewerControls()
        Log.w(
            TAG,
            "Generate unavailable displayed photoId=${photo.photoId} stage=$stage failedBase=$failedBaseUrl " +
                "fallbackCloud=$fellBackToCloud detail=${detail.take(180)} effective=${backendBaseUrl()}"
        )
    }

    private fun showViewer(photo: AlbumPhoto) {
        if (!photo.hasSplat) {
            if (photo.localUri != null) beginReframing(photo) else showStaticPhoto(photo)
            return
        }
        saveAlbumScrollPosition("showViewer")
        viewerVisible = true
        previewEnabled = false
        previewRequestSerial++
        cascadeRunSerial++
        previewHandler.removeCallbacksAndMessages(null)
        albumGrid?.let { grid ->
            for (i in 0 until grid.childCount) {
                stopCellPreview(grid.getChildAt(i), reason = "open-viewer")
            }
        }
        assetName = photo.splatAsset
        currentViewerPhoto = photo
        latestCapture = null
        fluxDataUrl = null
        finalBitmap = null
        displayMode = DisplayMode.RAW
        difixBusy = false
        fluxBusy = false

        renderStatus = TextView(this).apply {
            setTextColor(COLOR_INK_SOFT)
            textSize = 11f
            typeface = inter(500)
            includeFontPadding = false
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(260)
            background = rounded(0xEFFFFFFF.toInt(), dp(16).toFloat())
            setPadding(dp(12), dp(8), dp(12), dp(8))
            text = "Loading $assetName"
        }
        overlay = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(COLOR_CANVAS)
            visibility = View.GONE
        }
        generateButton = primaryGenerateButton()
        val viewerBackendBase = backendBaseUrl()
        val viewerGalleryUrl = "$viewerBackendBase/orbit"

        val viewer = SplatGlView(
            this,
            assetName,
            photo.photoId,
            { message -> runOnUiThread { renderStatus.text = message } },
            { capture -> handleReleaseCapture(capture) },
            { error ->
                runOnUiThread {
                    if (viewerVisible && currentViewerPhoto?.photoId == photo.photoId) {
                        showBackendUnavailable(photo, viewerBackendBase, "stream", error)
                    }
                }
            },
            { beginLiveOrbit() },
            releaseCaptureEnabled,
            postPassEnabled,
            streamDensityOverride,
            footprintScaleOverride,
            photo.sourceWidth,
            photo.sourceHeight,
            photo.camFx,
            photo.camFy,
            photo.camCx,
            photo.camCy,
            photo.splatStreamUrl,
            "$viewerGalleryUrl/splat_stream",
            "$viewerGalleryUrl/ingest",
            photo.localUri?.takeIf { photo.imported }?.toString(),
            splatCacheMaxBytesOverride,
            networkStreamEnabled = !photo.imported || photo.localUri != null
        )
        glView = viewer

        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_CANVAS)
        }
        viewerRoot = root
        generateErrorOverlay = null
        val viewerFrame = AspectFrameLayout(this, sourceAspect(photo)).apply {
            setBackgroundColor(COLOR_CANVAS)
        }
        viewerFrame.addView(viewer, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        viewerFrame.addView(overlay, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(
            viewerFrame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        )
        root.addView(
            studioBackButton(),
            FrameLayout.LayoutParams(
                dp(44),
                dp(44),
                Gravity.START or Gravity.TOP
            ).apply {
                topMargin = dp(26)
                leftMargin = dp(18)
            }
        )
        if (SHOW_RENDER_DEBUG) {
            root.addView(
                renderStatus,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                ).apply {
                    topMargin = dp(28)
                    rightMargin = dp(18)
                }
            )
        }
        root.addView(
            generateButton,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                dp(52),
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                bottomMargin = dp(32)
            }
        )
        updateViewerControls()
        applyFullscreen(root)
        setContentView(root)
    }

    private fun showStaticPhoto(photo: AlbumPhoto) {
        saveAlbumScrollPosition("showStaticPhoto")
        viewerVisible = true
        previewEnabled = false
        glView?.shutdown()
        glView?.onPause()
        glView = null
        viewerRoot = null
        generateErrorOverlay = null
        latestCapture = null
        fluxDataUrl = null
        finalBitmap = null
        previewRequestSerial++
        cascadeRunSerial++
        previewHandler.removeCallbacksAndMessages(null)
        albumGrid?.let { grid ->
            for (i in 0 until grid.childCount) {
                stopCellPreview(grid.getChildAt(i), reason = "open-static")
            }
        }

        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(COLOR_SURFACE)
        }
        val status = TextView(this).apply {
            setTextColor(COLOR_INK_SOFT)
            background = rounded(0xEFFFFFFF.toInt(), dp(18).toFloat())
            textSize = 13f
            typeface = inter(500)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            text = "3D view unavailable"
        }
        val back = studioBackButton()
        val root = FrameLayout(this).apply {
            setBackgroundColor(COLOR_CANVAS)
            addView(image, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            addView(
                status,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.START or Gravity.TOP
                ).apply {
                    topMargin = dp(24)
                    leftMargin = dp(16)
                }
            )
            addView(
                back,
                FrameLayout.LayoutParams(
                    dp(44),
                    dp(44),
                    Gravity.START or Gravity.TOP
                ).apply {
                    topMargin = dp(26)
                    leftMargin = dp(18)
                }
            )
        }
        synchronized(thumbnailCache) { thumbnailCache[photo.photoId] }?.let { image.setImageBitmap(it) }
        networkExecutor.execute {
            try {
                val bitmap = fetchBitmap(photo.thumbnailUrl)
                synchronized(thumbnailCache) { thumbnailCache[photo.photoId] = bitmap }
                runOnUiThread { image.setImageBitmap(bitmap) }
            } catch (exc: Exception) {
                Log.w(TAG, "Static photo failed photoId=${photo.photoId}: ${exc.message}")
            }
        }
        applyFullscreen(root)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
    }

    override fun onPause() {
        glView?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        previewHandler.removeCallbacksAndMessages(null)
        backendHandler.removeCallbacksAndMessages(null)
        stopBackendDiscovery()
        glView?.shutdown()
        networkExecutor.shutdownNow()
        previewBakeExecutor.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (albumEditMode) {
            exitAlbumEditMode()
        } else if (viewerVisible) {
            showAlbum()
        } else {
            super.onBackPressed()
        }
    }

    private fun handleReleaseCapture(capture: ReleaseCapture) {
        if (difixBusy || fluxBusy) return
        latestCapture = capture
        fluxDataUrl = null
        finalBitmap = null
        displayMode = DisplayMode.RAW
        runOnUiThread {
            overlay.visibility = View.GONE
            updateViewerControls()
            setPipelineStatus("Captured ${capture.width}x${capture.height}; ready to Generate")
        }
    }

    private fun beginLiveOrbit() {
        if (displayMode == DisplayMode.RAW && overlay.visibility != View.VISIBLE) return
        Log.i(TAG, "Live orbit requested; hiding result overlay from mode=$displayMode")
        displayMode = DisplayMode.RAW
        overlay.visibility = View.GONE
        updateViewerControls()
        setPipelineStatus("Showing raw splat")
    }

    private fun buildDifixMultipartBody(capture: ReleaseCapture): DifixMultipartBody {
        val photo = currentViewerPhoto
        val photoId = photo?.photoId ?: photoIdForAsset(capture.assetName)
        val metrics = captureMetrics(capture)
        val seed = dataUrlPart(capture.seedDataUrl)
        val preview = dataUrlPart(capture.previewDataUrl)
        val mask = dataUrlPart(capture.refineMaskDataUrl)
        val boundary = "----WiciAndroidDifix${SystemClock.elapsedRealtimeNanos()}"
        val out = ByteArrayOutputStream()
        writeMultipartText(out, boundary, "ply", capture.assetName)
        writeMultipartText(out, boundary, "box", "android-album-demo")
        writeMultipartText(out, boundary, "photoId", photoId)
        writeMultipartText(out, boundary, "prompt", "remove degradation")
        writeMultipartText(out, boundary, "metrics", metrics.toString())
        writeMultipartBytes(out, boundary, "seed", "seed.jpg", seed.mime, seed.bytes)
        writeMultipartBytes(out, boundary, "preview", "preview.jpg", preview.mime, preview.bytes)
        writeMultipartBytes(out, boundary, "refineMask", "refine-mask.png", mask.mime, mask.bytes)
        var sourceBytes = 0
        var sourceMime = ""
        if (photo?.imported == true && photo.localUri != null) {
            val source = localSourceJpegBytes(photo)
            sourceBytes = source.size
            sourceMime = "image/jpeg"
            writeMultipartText(out, boundary, "sourceId", photoId)
            writeMultipartBytes(out, boundary, "source", "source.jpg", sourceMime, source)
            metrics.put("transportMode", "android-local-source-multipart+jpeg")
        } else {
            writeMultipartText(out, boundary, "sourceId", "$photoId-original")
            sourcePathForAsset(capture.assetName)?.let { writeMultipartText(out, boundary, "source", it) }
        }
        out.writeUtf8("--$boundary--\r\n")
        val requestBytes = out.size()
        metrics.put(
            "difixTransport",
            JSONObject()
                .put("mode", "multipart-binary")
                .put("seedBytes", seed.bytes.size)
                .put("seedMime", seed.mime)
                .put("previewBytes", preview.bytes.size)
                .put("previewMime", preview.mime)
                .put("refineMaskBytes", mask.bytes.size)
                .put("refineMaskMime", mask.mime)
                .put("sourceBytes", sourceBytes)
                .put("sourceMime", sourceMime)
                .put("requestBytes", requestBytes)
                .put("seedPreviewSame", seed.bytes.contentEquals(preview.bytes))
        )
        Log.i(
            TAG,
            "Difix binary request payload photoId=$photoId capture=${capture.width}x${capture.height} " +
                "seed=${seed.bytes.size}B/${seed.mime} preview=${preview.bytes.size}B/${preview.mime} " +
                "mask=${mask.bytes.size}B/${mask.mime} source=${sourceBytes}B/${sourceMime.ifBlank { "-" }} " +
                "requestBytes=$requestBytes seedPreviewSame=${seed.bytes.contentEquals(preview.bytes)}"
        )
        return DifixMultipartBody(boundary, out.toByteArray())
    }

    private fun captureMetrics(capture: ReleaseCapture): JSONObject =
        JSONObject()
            .put("client", "android-album-demo")
            .put("seedMode", "black-hole-render")
            .put("previewMode", "gpu-push-pull-filled-render")
            .put("transportMode", "android-source-path+jpeg")
            .put("releaseMaxSide", capture.releaseMaxSide)
            .put("captureSize", JSONArray().put(capture.width).put(capture.height))
            .put("renderSize", JSONArray().put(capture.renderWidth).put(capture.renderHeight))
            .put("alphaHoleThreshold", capture.alphaThreshold)
            .put("gapPx", capture.gapPx)
            .put("peripheralPx", capture.peripheralPx)
            .put("interiorPx", capture.interiorPx)
            .put("coveredPx", capture.coveredPx)

    private fun generateFlux() {
        val capture = latestCapture ?: return
        if (difixBusy || fluxBusy) return
        val generateBackendBase = backendBaseUrl()
        if (requiresCloudLogin(generateBackendBase) && !SupabaseAuth.isLoggedIn()) {
            setPipelineStatus("Sign in to use WiCi Cloud Generate")
            promptCloudLogin("Generate", onFailed = {
                setPipelineStatus("Sign in with Google to use WiCi Cloud Generate")
            }) {
                generateFlux()
            }
            return
        }
        fluxDataUrl = null
        finalBitmap = null
        displayMode = DisplayMode.RAW
        difixBusy = true
        fluxBusy = false
        clearGenerateErrorOverlay()
        runOnUiThread {
            overlay.visibility = View.GONE
            updateViewerControls()
            setPipelineStatus("Checking Generate services...")
        }
        networkExecutor.execute {
            var failureStage = "difix"
            try {
                val health = checkGenerateHealth(generateBackendBase)
                if (!health.ok) {
                    Log.w(
                        TAG,
                        "Generate health failed base=$generateBackendBase service=${health.service ?: "-"} " +
                            "elapsedMs=${health.elapsedMs} error=${health.message}"
                    )
                    difixBusy = false
                    fluxBusy = false
                    val photo = currentViewerPhoto
                    runOnUiThread {
                        updateViewerControls()
                        if (photo != null) {
                            showGenerateUnavailable(
                                photo,
                                generateBackendBase,
                                "generate-health-${health.service?.lowercase() ?: "unknown"}",
                                "${health.service ?: "Generate"} health: ${health.message}"
                            )
                        }
                    }
                    return@execute
                }
                val started = System.nanoTime()
                val photo = currentViewerPhoto
                val photoId = photo?.photoId ?: photoIdForAsset(capture.assetName)
                runOnUiThread { setPipelineStatus("Running Difix...") }
                val bodyStartNs = SystemClock.elapsedRealtimeNanos()
                val difixBody = buildDifixMultipartBody(capture)
                val bodyBuildMs = elapsedMs(bodyStartNs)
                val difixPostResult = postDifixMultipart("$generateBackendBase/difix/refine", difixBody, 240_000)
                val difixData = "data:${difixPostResult.contentType};base64," +
                    Base64.encodeToString(difixPostResult.imageBytes, Base64.NO_WRAP)
                Log.i(
                    TAG,
                    "Difix binary profile capture=${capture.width}x${capture.height} " +
                        "bodyBuildMs=$bodyBuildMs connectMs=${difixPostResult.connectMs} " +
                        "uploadMs=${difixPostResult.uploadMs} " +
                        "serverWaitFirstByteMs=${difixPostResult.serverWaitFirstByteMs} " +
                        "serverWaitHeadersMs=${difixPostResult.serverWaitHeadersMs} " +
                        "responseDownloadMs=${difixPostResult.responseDownloadMs} " +
                        "requestBytes=${difixBody.bytes.size} responseBytes=${difixPostResult.imageBytes.size} " +
                        "httpStatus=${difixPostResult.httpStatus} " +
                        "serverTiming=${difixPostResult.serverTimingJson?.take(220) ?: "-"}"
                )
                failureStage = "flux"
                difixBusy = false
                fluxBusy = true
                runOnUiThread {
                    updateViewerControls()
                    setPipelineStatus("Generating final image...")
                }
                val metrics = captureMetrics(capture)
                val body = JSONObject()
                    .put("photoId", photoId)
                    .put("ply", capture.assetName)
                    .put("image", difixData)
                    .put("mask", capture.peripheralMaskDataUrl)
                    .put("metrics", metrics)
                if (photo?.imported == true && photo.localUri != null) {
                    body.put("sourceId", photoId)
                    body.put("source", localSourceDataUrl(photo))
                    metrics.put("transportMode", "android-local-source-data-url+jpeg")
                }
                val response = postJson("$generateBackendBase/flux/fill", body, 300_000)
                val image = response.getString("image")
                val bitmap = decodeDataUrl(image)
                fluxDataUrl = image
                finalBitmap = bitmap
                displayMode = DisplayMode.FLUX
                val elapsed = (System.nanoTime() - started) / 1_000_000
                difixBusy = false
                fluxBusy = false
                runOnUiThread {
                    overlay.setImageBitmap(bitmap)
                    overlay.visibility = View.VISIBLE
                    updateViewerControls()
                    setPipelineStatus("Generate done in ${elapsed}ms (${bitmap.width}x${bitmap.height})")
                }
            } catch (exc: Exception) {
                difixBusy = false
                fluxBusy = false
                val photo = currentViewerPhoto
                runOnUiThread {
                    updateViewerControls()
                    val stageLabel = if (failureStage == "difix") "Difix" else "FLUX"
                    setPipelineStatus("$stageLabel failed: ${exc.message}")
                    if (photo != null) {
                        showGenerateUnavailable(photo, generateBackendBase, failureStage, exc.message ?: exc.javaClass.simpleName)
                    }
                }
            }
        }
    }

    private fun saveFinalImage() {
        val bitmap = finalBitmap ?: return
        val name = "wici-spatial-${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = contentResolver
        var uri: Uri? = null
        try {
            uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            requireNotNull(uri) { "MediaStore insert returned null" }
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { "openOutputStream returned null" }
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            setPipelineStatus("Saved to Downloads/$name")
        } catch (exc: Exception) {
            uri?.let { resolver.delete(it, null, null) }
            setPipelineStatus("Save failed: ${exc.message}")
        }
    }

    private fun setPipelineStatus(message: String) {
        Log.i(TAG, message)
    }

    private fun postJson(url: String, body: JSONObject, timeoutMs: Int): JSONObject {
        val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Length", bytes.size.toString())
        }
        conn.outputStream.use { it.write(bytes) }
        val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use {
            BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
        }.orEmpty()
        if (conn.responseCode !in 200..299) {
            throw RuntimeException("HTTP ${conn.responseCode}: ${text.take(220)}")
        }
        return JSONObject(text)
    }

    private fun postDifixMultipart(url: String, body: DifixMultipartBody, timeoutMs: Int): DifixBinaryResult {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = timeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=${body.boundary}")
            setRequestProperty("Accept", "image/jpeg")
            setRequestProperty("Content-Length", body.bytes.size.toString())
        }
        val connectStartNs = SystemClock.elapsedRealtimeNanos()
        conn.connect()
        val connectMs = elapsedMs(connectStartNs)
        val uploadStartNs = SystemClock.elapsedRealtimeNanos()
        conn.outputStream.use { it.write(body.bytes) }
        val uploadMs = elapsedMs(uploadStartNs)
        val waitStartNs = SystemClock.elapsedRealtimeNanos()
        val code = conn.responseCode
        val serverWaitHeadersMs = elapsedMs(waitStartNs)
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val responseBytes = ByteArrayOutputStream()
        var serverWaitFirstByteMs = serverWaitHeadersMs
        var responseDownloadMs = 0L
        stream?.use { input ->
            val buffer = ByteArray(64 * 1024)
            val firstCount = input.read(buffer)
            serverWaitFirstByteMs = elapsedMs(waitStartNs)
            val downloadStartNs = SystemClock.elapsedRealtimeNanos()
            if (firstCount > 0) responseBytes.write(buffer, 0, firstCount)
            if (firstCount >= 0) {
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) responseBytes.write(buffer, 0, count)
                }
            }
            responseDownloadMs = elapsedMs(downloadStartNs)
        }
        if (code !in 200..299) {
            val text = responseBytes.toString(StandardCharsets.UTF_8.name())
            throw RuntimeException("HTTP $code: ${text.take(220)}")
        }
        Log.i(
            TAG,
            "Difix binary HTTP profile requestBytes=${body.bytes.size} responseBytes=${responseBytes.size()} " +
                "connectMs=$connectMs uploadMs=$uploadMs serverWaitHeadersMs=$serverWaitHeadersMs " +
                "serverWaitFirstByteMs=$serverWaitFirstByteMs responseDownloadMs=$responseDownloadMs status=$code " +
                "serverTiming=${conn.headerString("X-Difix-Timing-Json")?.take(220) ?: "-"}"
        )
        return DifixBinaryResult(
            imageBytes = responseBytes.toByteArray(),
            contentType = conn.contentType?.substringBefore(";")?.ifBlank { null } ?: "image/jpeg",
            connectMs = connectMs,
            uploadMs = uploadMs,
            serverWaitHeadersMs = serverWaitHeadersMs,
            serverWaitFirstByteMs = serverWaitFirstByteMs,
            responseDownloadMs = responseDownloadMs,
            httpStatus = code,
            serverTimingJson = conn.headerString("X-Difix-Timing-Json")
        )
    }

    private data class DifixMultipartBody(
        val boundary: String,
        val bytes: ByteArray
    )

    private data class DifixBinaryResult(
        val imageBytes: ByteArray,
        val contentType: String,
        val connectMs: Long,
        val uploadMs: Long,
        val serverWaitHeadersMs: Long,
        val serverWaitFirstByteMs: Long,
        val responseDownloadMs: Long,
        val httpStatus: Int,
        val serverTimingJson: String?
    )

    private data class IngestSplatResult(
        val photo: AlbumPhoto,
        val store: SplatCache.StoreResult,
        val records: Int,
        val sizeBytes: Long
    )

    private fun postIngestSplat(photo: AlbumPhoto): IngestSplatResult {
        val imageUri = photo.localUri ?: throw IllegalArgumentException("imported photo has no local URI")
        val boundary = "----WiciAndroidAlbum${SystemClock.elapsedRealtimeNanos()}"
        val mime = contentResolver.getType(imageUri) ?: "image/jpeg"
        val requestedPhotoId = photo.photoId
        val ingestUploadJpeg = buildFullReframeUploadJpeg(requestedPhotoId, imageUri)
        val uploadMime = if (ingestUploadJpeg != null) "image/jpeg" else mime
        val filename = if (ingestUploadJpeg != null) {
            "android-import-${System.currentTimeMillis()}.jpg"
        } else {
            "android-import-${System.currentTimeMillis()}.${extensionForMime(mime)}"
        }
        val initialKey = SplatCache.keyFor(requestedPhotoId, DEFAULT_SPLAT_DENSITY)
        var tmp = SplatCache.tempFile(this, initialKey)
        val requestStartNs = SystemClock.elapsedRealtimeNanos()
        var uploadImageBytes = ingestUploadJpeg?.size?.toLong() ?: -1L
        var uploadWriteMs = -1L
        var responseWaitMs = -1L
        var downloadMs = -1L
        var commitMs = -1L
        var serverTimingJson: String? = null
        var serverSharpMs: Double? = null
        val conn = (URL("${galleryUrl()}/ingest").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/octet-stream")
            setRequestProperty(INGEST_SPLAT_ACCEPT_HEADER, "$INGEST_SPLAT_FORMAT_FP16_V1,gzip")
        }
        try {
            val uploadStartNs = SystemClock.elapsedRealtimeNanos()
            conn.outputStream.use { out ->
                writeMultipartText(out, boundary, "photoId", requestedPhotoId)
                writeMultipartText(out, boundary, "title", requestedPhotoId)
                out.writeUtf8("--$boundary\r\n")
                out.writeUtf8("Content-Disposition: form-data; name=\"image\"; filename=\"$filename\"\r\n")
                out.writeUtf8("Content-Type: $uploadMime\r\n\r\n")
                if (ingestUploadJpeg != null) {
                    out.write(ingestUploadJpeg)
                } else {
                    contentResolver.openInputStream(imageUri).use { input ->
                        requireNotNull(input) { "openInputStream returned null" }
                        uploadImageBytes = input.copyTo(out)
                    }
                }
                out.writeUtf8("\r\n--$boundary--\r\n")
            }
            uploadWriteMs = elapsedMs(uploadStartNs)

            val responseWaitStartNs = SystemClock.elapsedRealtimeNanos()
            val code = conn.responseCode
            responseWaitMs = elapsedMs(responseWaitStartNs)
            serverTimingJson = conn.headerString("X-Ingest-Timing-Json") ?: conn.headerString("X-Orbit-Timing-Json")
            serverSharpMs = parseIngestSharpMs(serverTimingJson)
            val responseFormat = conn.headerString("X-Splat-Format") ?: SplatCache.FORMAT_SPLAT
            val responseEncoding = conn.headerString("X-Splat-Encoding") ?: "identity"
            val wireBytes = conn.headerLong("X-Splat-Size-Bytes") ?: conn.headerLong("Content-Length") ?: -1L
            val payloadHeaderBytes = conn.headerLong("X-Splat-Payload-Size-Bytes")
            val fp32EquivalentBytes = conn.headerLong("X-Splat-Fp32-Size-Bytes")
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw RuntimeException("HTTP $code: ${text.take(220)}")
            }

            val downloadStartNs = SystemClock.elapsedRealtimeNanos()
            tmp.outputStream().use { out ->
                requireNotNull(stream) { "ingest response body missing" }.use { input ->
                    val body = if (responseEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(input) else input
                    body.use { decoded -> decoded.copyTo(out) }
                }
            }
            downloadMs = elapsedMs(downloadStartNs)
            val bytes = tmp.length()
            if (bytes <= 0L) throw RuntimeException("empty ingest splat response")
            val payloadInfo = SplatCache.inspect(tmp, SPLAT_ROW_BYTES)
                ?: throw RuntimeException("invalid ingest splat payload bytes=$bytes format=$responseFormat encoding=$responseEncoding")

            val returnedPhotoId = conn.headerString("X-Ingest-Photo-Id")?.takeIf { it.isNotBlank() } ?: requestedPhotoId
            val cacheKey = SplatCache.keyFor(returnedPhotoId, DEFAULT_SPLAT_DENSITY)
            val records = conn.headerInt("X-Splat-Num-Gaussians") ?: payloadInfo.records
            if (records != payloadInfo.records) {
                Log.w(TAG, "Ingest record header mismatch photoId=$returnedPhotoId header=$records payload=${payloadInfo.records}")
            }
            if (payloadHeaderBytes != null && payloadHeaderBytes != bytes) {
                Log.w(TAG, "Ingest payload size header mismatch photoId=$returnedPhotoId header=$payloadHeaderBytes actual=$bytes")
            }
            val commitStartNs = SystemClock.elapsedRealtimeNanos()
            val store = SplatCache.commit(this, cacheKey, tmp, splatCacheMaxBytesOverride ?: SplatCache.DEFAULT_MAX_BYTES)
            commitMs = elapsedMs(commitStartNs)
            tmp = File("")
            if (!store.stored) {
                throw RuntimeException("ingest splat was not cached: ${store.reason ?: "unknown"}")
            }
            val baked = photo.copy(
                photoId = returnedPhotoId,
                hasSplat = true,
                sourceWidth = conn.headerInt("X-Src-Width") ?: photo.sourceWidth,
                sourceHeight = conn.headerInt("X-Src-Height") ?: photo.sourceHeight,
                camFx = conn.headerFloat("X-Cam-Fx") ?: photo.camFx,
                camFy = conn.headerFloat("X-Cam-Fy") ?: photo.camFy,
                camCx = conn.headerFloat("X-Cam-Cx") ?: photo.camCx,
                camCy = conn.headerFloat("X-Cam-Cy") ?: photo.camCy,
                splatStreamUrl = null,
                splatUrl = null,
                imported = true
            )
            Log.i(
                TAG,
                "Ingest splat received photoId=$returnedPhotoId records=$records bytes=$bytes " +
                    "camera=${baked.sourceWidth}x${baked.sourceHeight} fx=${baked.camFx} fy=${baked.camFy} " +
                    "stored=${store.stored} totalBytes=${store.totalBytes} evicted=${store.evictedCount}"
            )
            val totalMs = elapsedMs(requestStartNs)
            val downloadWireBytes = wireBytes.takeIf { it > 0L } ?: bytes
            val downloadMb = downloadWireBytes.toDouble() / (1024.0 * 1024.0)
            val downloadMbps = if (downloadMs > 0L) downloadMb / (downloadMs.toDouble() / 1000.0) else 0.0
            Log.i(
                TAG,
                "Ingest profile photoId=$returnedPhotoId uploadMode=${if (ingestUploadJpeg != null) "jpeg1536" else "raw"} " +
                    "uploadImageBytes=$uploadImageBytes uploadWriteMs=$uploadWriteMs " +
                    "responseWaitMs=$responseWaitMs serverSharpMs=${serverSharpMs?.let { "%.1f".format(it) } ?: "-"} " +
                    "splatFormat=${payloadInfo.format} responseFormat=$responseFormat responseEncoding=$responseEncoding " +
                    "wireBytes=$wireBytes payloadBytes=$bytes fp32Bytes=${fp32EquivalentBytes ?: "-"} " +
                    "downloadMs=$downloadMs downloadWireMB=${"%.2f".format(downloadMb)} " +
                    "downloadWireMBps=${"%.2f".format(downloadMbps)} commitMs=$commitMs totalMs=$totalMs " +
                    "serverTiming=${serverTimingJson?.take(240) ?: "-"}"
            )
            return IngestSplatResult(baked, store, records, bytes)
        } finally {
            if (tmp.path.isNotEmpty() && tmp.exists()) tmp.delete()
            conn.disconnect()
        }
    }

    private fun postIngest(imageUri: Uri): JSONObject {
        val boundary = "----WiciAndroidAlbum${SystemClock.elapsedRealtimeNanos()}"
        val mime = contentResolver.getType(imageUri) ?: "image/jpeg"
        val filename = "android-import-${System.currentTimeMillis()}.${extensionForMime(mime)}"
        val conn = (URL("${galleryUrl()}/ingest").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Accept", "application/json")
        }
        try {
            conn.outputStream.use { out ->
                out.writeUtf8("--$boundary\r\n")
                out.writeUtf8("Content-Disposition: form-data; name=\"image\"; filename=\"$filename\"\r\n")
                out.writeUtf8("Content-Type: $mime\r\n\r\n")
                contentResolver.openInputStream(imageUri).use { input ->
                    requireNotNull(input) { "openInputStream returned null" }
                    input.copyTo(out)
                }
                out.writeUtf8("\r\n--$boundary--\r\n")
            }
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${conn.responseCode}: ${text.take(220)}")
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun writeMultipartText(out: OutputStream, boundary: String, name: String, value: String) {
        out.writeUtf8("--$boundary\r\n")
        out.writeUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeUtf8(value)
        out.writeUtf8("\r\n")
    }

    private fun writeMultipartBytes(
        out: OutputStream,
        boundary: String,
        name: String,
        filename: String,
        mime: String,
        bytes: ByteArray
    ) {
        out.writeUtf8("--$boundary\r\n")
        out.writeUtf8("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
        out.writeUtf8("Content-Type: $mime\r\n\r\n")
        out.write(bytes)
        out.writeUtf8("\r\n")
    }

    private fun OutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun extensionForMime(mime: String): String =
        when {
            mime.contains("png", ignoreCase = true) -> "png"
            mime.contains("webp", ignoreCase = true) -> "webp"
            else -> "jpg"
        }

    private fun photoFromIngestResponse(item: JSONObject, previous: AlbumPhoto): AlbumPhoto {
        val photoId = item.optString("photoId").trim().ifEmpty { previous.photoId }
        val fallbackDims = legacySourceDims(photoId)
        return AlbumPhoto(
            photoId = photoId,
            thumbnailUrl = firstOptionalUrl(item, "thumbnailUrl", "thumbnail_url") ?: previous.thumbnailUrl,
            hasOrbit = item.optBoolean("hasOrbit", previous.hasOrbit),
            hasSplat = item.optBoolean("hasSplat", true),
            sourceWidth = firstOptionalInt(item, "sourceWidth", "source_width", "width", "imageWidth")
                ?: previous.sourceWidth
                ?: fallbackDims?.first,
            sourceHeight = firstOptionalInt(item, "sourceHeight", "source_height", "height", "imageHeight")
                ?: previous.sourceHeight
                ?: fallbackDims?.second,
            camFx = firstOptionalFloat(item, "camFx", "cam_fx", "fx") ?: previous.camFx,
            camFy = firstOptionalFloat(item, "camFy", "cam_fy", "fy") ?: previous.camFy,
            camCx = firstOptionalFloat(item, "camCx", "cam_cx", "cx") ?: previous.camCx,
            camCy = firstOptionalFloat(item, "camCy", "cam_cy", "cy") ?: previous.camCy,
            sourceUrl = firstOptionalUrl(item, "sourceUrl", "source_url") ?: previous.sourceUrl,
            orbitWebpUrl = firstOptionalUrl(item, "orbitWebpUrl", "orbit_webp_url") ?: previous.orbitWebpUrl,
            orbitPreviewUrl = firstOptionalUrl(item, "orbitPreviewUrl", "orbit_preview_url") ?: previous.orbitPreviewUrl,
            splatStreamUrl = firstOptionalUrl(item, "splatStreamUrl", "splat_stream_url") ?: previous.splatStreamUrl,
            splatUrl = firstOptionalUrl(item, "splatUrl", "splat_url") ?: previous.splatUrl,
            localUri = previous.localUri,
            imported = true
        )
    }

    private fun replaceImportedPhoto(oldPhotoId: String, next: AlbumPhoto) {
        val index = importedPhotos.indexOfFirst { it.photoId == oldPhotoId }
        if (index >= 0) {
            importedPhotos[index] = next
        } else {
            importedPhotos.add(0, next)
        }
        val importedIds = importedPhotos.map { it.photoId }.toSet()
        albumPhotos = importedPhotos + albumPhotos.filterNot {
            it.photoId == oldPhotoId || it.photoId == next.photoId || it.photoId in importedIds
        }
        thumbnailCache.remove(oldPhotoId)
        albumAdapter?.setPhotos(albumPhotos)
        albumStatus?.text = momentCountText()
    }

    private fun getText(url: String, timeoutMs: Int): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = timeoutMs
            setRequestProperty("Accept", "application/json")
        }
        try {
            val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.use {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).readText()
            }.orEmpty()
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${conn.responseCode}: ${text.take(220)}")
            }
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchBitmap(url: String): Bitmap {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 4_000
            readTimeout = 12_000
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw RuntimeException("HTTP ${conn.responseCode}")
            }
            return conn.inputStream.use { input ->
                BitmapFactory.decodeStream(input)
            } ?: throw RuntimeException("decode failed")
        } finally {
            conn.disconnect()
        }
    }

    private fun decodeLocalBitmap(uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val width = info.size.width.coerceAtLeast(1)
            val height = info.size.height.coerceAtLeast(1)
            val scale = min(1f, maxSide.toFloat() / maxOf(width, height).toFloat())
            if (scale < 1f) {
                decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    private fun decodeDataUrl(value: String): Bitmap {
        val encoded = value.substringAfter(",", value)
        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw IllegalArgumentException("failed to decode image")
    }

    private data class DataUrlStats(val mime: String, val bytes: Int)
    private data class DataUrlPart(val mime: String, val bytes: ByteArray)

    private fun dataUrlPayload(value: String): String =
        value.substringAfter(",", value)

    private fun dataUrlPart(value: String): DataUrlPart {
        val header = value.substringBefore(",", "")
        val mime = header.removePrefix("data:").substringBefore(";").ifBlank { "application/octet-stream" }
        val bytes = Base64.decode(dataUrlPayload(value), Base64.DEFAULT)
        return DataUrlPart(mime, bytes)
    }

    private fun dataUrlStats(value: String): DataUrlStats {
        val header = value.substringBefore(",", "")
        val mime = header.removePrefix("data:").substringBefore(";").ifBlank { "unknown" }
        val bytes = Base64.decode(dataUrlPayload(value), Base64.DEFAULT).size
        return DataUrlStats(mime, bytes)
    }

    private fun localSourceDataUrl(photo: AlbumPhoto): String {
        synchronized(localSourceDataUrlCache) {
            localSourceDataUrlCache[photo.photoId]?.let { return it }
        }
        val bytes = localSourceJpegBytes(photo)
        val dataUrl = "data:image/jpeg;base64,${Base64.encodeToString(bytes, Base64.NO_WRAP)}"
        synchronized(localSourceDataUrlCache) {
            localSourceDataUrlCache[photo.photoId] = dataUrl
        }
        return dataUrl
    }

    private fun localSourceJpegBytes(photo: AlbumPhoto): ByteArray {
        synchronized(localSourceJpegCache) {
            localSourceJpegCache[photo.photoId]?.let { return it }
        }
        val uri = photo.localUri ?: throw IllegalArgumentException("imported photo has no local URI")
        val bitmap = decodeLocalBitmap(uri, DIFIX_SOURCE_UPLOAD_MAX_SIDE)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, DIFIX_SOURCE_UPLOAD_JPEG_QUALITY, out)
        val bytes = out.toByteArray()
        bitmap.recycle()
        synchronized(localSourceJpegCache) {
            localSourceJpegCache[photo.photoId] = bytes
        }
        Log.i(
            TAG,
            "Local source encoded photoId=${photo.photoId} bytes=${bytes.size} " +
                "maxSide=$DIFIX_SOURCE_UPLOAD_MAX_SIDE quality=$DIFIX_SOURCE_UPLOAD_JPEG_QUALITY"
        )
        return bytes
    }

    private fun loadRemovedCuratedPhotoIds(): Set<String> =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getStringSet(PREF_REMOVED_CURATED_IDS, emptySet())
            ?.toSet()
            .orEmpty()

    private fun saveRemovedCuratedPhotoIds() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putStringSet(PREF_REMOVED_CURATED_IDS, removedCuratedPhotoIds.toSet())
            .apply()
    }

    private fun loadAssetBitmap(assetName: String): Bitmap? =
        try {
            assets.open(assetName).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }

    private fun inter(weight: Int = 400): Typeface =
        Typeface.create(interBase, weight, false)

    private fun spaceGrotesk(weight: Int = 700): Typeface =
        Typeface.create(spaceGroteskBase, weight, false)

    private fun momentCountText(): String =
        "${albumPhotos.size} MOMENTS"

    private fun stylePreviewToggle(view: TextView, enabled: Boolean) {
        view.setTextColor(if (enabled) Color.WHITE else COLOR_INK_SOFT)
        view.background = if (enabled) {
            roundedState(COLOR_ACCENT, COLOR_ACCENT_PRESS, dp(22).toFloat())
        } else {
            rounded(COLOR_SURFACE, dp(22).toFloat(), dpFloat(1f), COLOR_HAIRLINE)
        }
        applySoftShadow(view, if (enabled) 3 else 2)
    }

    private fun serverSegmentButton(label: String): TextView =
        TextView(this).apply {
            text = label
            textSize = 14f
            typeface = inter(600)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(8), 0, dp(8), 0)
            isClickable = true
            isFocusable = true
        }

    private fun styleServerSegment(segment: TextView, selected: Boolean) {
        segment.setTextColor(if (selected) COLOR_INK else COLOR_INK_SOFT)
        segment.typeface = inter(if (selected) 650 else 500)
        segment.background = if (selected) {
            rounded(COLOR_SURFACE, dp(10).toFloat(), dpFloat(0.8f), COLOR_HAIRLINE)
        } else {
            null
        }
        applySoftShadow(segment, 0)
    }

    private fun sheetCancelAction(label: String): TextView =
        TextView(this).apply {
            text = label
            setTextColor(COLOR_INK_SOFT)
            textSize = 15f
            typeface = inter(600)
            includeFontPadding = false
            gravity = Gravity.CENTER
            minWidth = dp(72)
            setPadding(dp(12), 0, dp(12), 0)
            isClickable = true
            isFocusable = true
            background = roundedState(Color.TRANSPARENT, 0x0F000000, dp(20).toFloat())
        }

    private fun sheetSaveAction(label: String): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = inter(650)
            includeFontPadding = false
            gravity = Gravity.CENTER
            minWidth = dp(92)
            setPadding(dp(22), 0, dp(22), 0)
            isClickable = true
            isFocusable = true
            background = roundedState(COLOR_ACCENT, COLOR_ACCENT_PRESS, dp(20).toFloat())
            applySoftShadow(this, 0)
        }

    private data class ServerStatusModel(
        val statusLine: String,
        val addressLine: String
    )

    private fun serverStatusModel(manualMode: Boolean, manualText: String): ServerStatusModel =
        when {
            manualMode -> ServerStatusModel(
                statusLine = "Using a custom server",
                addressLine = backendAddressLabel(
                    normalizeBackendBaseUrl(manualText) ?: manualText.trim().takeIf { it.isNotBlank() }
                ) ?: "Enter a server address"
            )
            discoveredBackendBaseUrl != null -> ServerStatusModel(
                statusLine = "Connected to your WiCi box",
                addressLine = backendAddressLabel(discoveredBackendBaseUrl)
                    ?: backendAddressLabel(backendBaseUrl()).orEmpty()
            )
            backendDiscoveryInProgress -> ServerStatusModel(
                statusLine = "Looking for your WiCi box",
                addressLine = backendAddressLabel(DEFAULT_BACKEND_BASE_URL).orEmpty()
            )
            else -> ServerStatusModel(
                statusLine = "Using WiCi Cloud",
                addressLine = backendAddressLabel(DEFAULT_BACKEND_BASE_URL).orEmpty()
            )
        }

    private fun backendAddressLabel(url: String?): String? {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val normalized = normalizeBackendBaseUrl(value) ?: value
        return try {
            val uri = Uri.parse(normalized)
            val host = uri.host.orEmpty()
            val port = uri.port
            if (host.isBlank() || port <= 0) normalized.removePrefix("http://").removePrefix("https://") else "$host:$port"
        } catch (_: Exception) {
            normalized.removePrefix("http://").removePrefix("https://")
        }
    }

    private fun googleAccountEmail(): String? =
        SupabaseAuth.email()?.takeIf { it.isNotBlank() }

    private fun clearGoogleAccount() {
        SupabaseAuth.signOut()
        Toast.makeText(this, "Signed out", Toast.LENGTH_SHORT).show()
    }

    private fun beginGoogleSignIn(onComplete: ((Boolean) -> Unit)? = null) {
        Toast.makeText(this, "Opening Google sign-in...", Toast.LENGTH_SHORT).show()
        SupabaseAuth.signInWithGoogle(this) { success, message ->
            if (success) {
                val email = googleAccountEmail()
                Toast.makeText(this, if (email == null) "Signed in" else "Signed in as $email", Toast.LENGTH_SHORT).show()
                onComplete?.invoke(true)
            } else {
                val detail = message ?: "Sign-in failed"
                Log.w(TAG, "Google sign-in failed: $detail")
                Toast.makeText(this, detail, Toast.LENGTH_LONG).show()
                onComplete?.invoke(false)
            }
        }
    }

    private fun promptCloudLogin(
        operation: String,
        onFailed: (() -> Unit)? = null,
        onSignedIn: (() -> Unit)? = null
    ) {
        if (SupabaseAuth.isLoggedIn()) {
            onSignedIn?.invoke()
            return
        }
        Log.i(TAG, "Cloud login required operation=$operation base=${backendBaseUrl()}")
        Toast.makeText(this, "Sign in to use WiCi Cloud for $operation", Toast.LENGTH_LONG).show()
        beginGoogleSignIn { success ->
            if (success) onSignedIn?.invoke() else onFailed?.invoke()
        }
    }

    private fun showBackendSettingsDialog() {
        var manualMode = manualBackendBaseUrl() != null
        lateinit var automaticSegment: TextView
        lateinit var manualSegment: TextView
        lateinit var contextZone: LinearLayout
        lateinit var accountTitle: TextView
        lateinit var accountDetail: TextView
        lateinit var signOutAction: TextView
        val quietGreen = 0xFF2FB574.toInt()
        val input = EditText(this).apply {
            setSingleLine(true)
            setText(manualBackendBaseUrl() ?: backendBaseUrl())
            setSelectAllOnFocus(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            textSize = 14f
            typeface = inter(500)
            setTextColor(COLOR_INK)
            setHintTextColor(COLOR_INK_SOFT)
            hint = "http://192.168.1.50:54228"
            background = rounded(COLOR_SURFACE, dp(10).toFloat(), dpFloat(1f), COLOR_HAIRLINE)
            setPadding(dp(14), 0, dp(14), 0)
        }

        fun updateModeUi() {
            styleServerSegment(automaticSegment, selected = !manualMode)
            styleServerSegment(manualSegment, selected = manualMode)
            input.isEnabled = manualMode
            contextZone.removeAllViews()
            if (manualMode) {
                contextZone.addView(
                    TextView(this@MainActivity).apply {
                        text = "Server address"
                        setTextColor(COLOR_INK)
                        textSize = 13f
                        typeface = inter(600)
                        includeFontPadding = false
                    },
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                contextZone.addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply {
                    topMargin = dp(8)
                })
            } else {
                val status = serverStatusModel(false, "")
                val dotColor = if (discoveredBackendBaseUrl != null) quietGreen else COLOR_ACCENT
                contextZone.addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.TOP
                        addView(
                            View(this@MainActivity).apply {
                                background = rounded(dotColor, dp(4).toFloat())
                            },
                            LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                                topMargin = dp(5)
                                rightMargin = dp(10)
                            }
                        )
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = status.statusLine
                                        setTextColor(COLOR_INK)
                                        textSize = 14f
                                        typeface = inter(500)
                                        includeFontPadding = false
                                    },
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                                addView(
                                    TextView(this@MainActivity).apply {
                                        text = status.addressLine
                                        setTextColor(COLOR_INK_SOFT)
                                        textSize = 12f
                                        typeface = Typeface.MONOSPACE
                                        includeFontPadding = false
                                        ellipsize = TextUtils.TruncateAt.MIDDLE
                                        setSingleLine(true)
                                        setPadding(0, dp(5), 0, 0)
                                    },
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                )
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        )
                    },
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
        }

        fun updateAccountUi() {
            val email = googleAccountEmail()
            if (email == null) {
                accountTitle.text = "Sign in with Google"
                accountDetail.text = "Needed for cloud"
                signOutAction.visibility = View.GONE
            } else {
                accountTitle.text = "Signed in as $email"
                accountDetail.text = "Google account"
                signOutAction.visibility = View.VISIBLE
            }
        }

        var settingsPopup: android.widget.PopupWindow? = null
        lateinit var sheetScroll: ScrollView
        fun dismissSettingsSheet() {
            val popup = settingsPopup ?: return
            if (!popup.isShowing) return
            sheetScroll.animate().cancel()
            sheetScroll.animate()
                .translationY(sheetScroll.height.toFloat())
                .setDuration(150L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .withEndAction { popup.dismiss() }
                .start()
        }

        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(20))
            background = roundedTop(COLOR_SURFACE, dp(22).toFloat())
            applySoftShadow(this, 0)
            addView(
                View(this@MainActivity).apply {
                    background = rounded(Color.argb(51, 107, 110, 118), dp(2).toFloat())
                },
                LinearLayout.LayoutParams(dp(36), dp(4)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dp(14)
                }
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Server"
                    setTextColor(COLOR_INK)
                    textSize = 24f
                    typeface = spaceGrotesk(700)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(dp(3), dp(3), dp(3), dp(3))
                    background = rounded(COLOR_CANVAS, dp(13).toFloat())
                    automaticSegment = serverSegmentButton("Automatic").apply {
                        setOnClickListener {
                            manualMode = false
                            updateModeUi()
                        }
                    }
                    manualSegment = serverSegmentButton("Manual").apply {
                        setOnClickListener {
                            manualMode = true
                            input.setText(manualBackendBaseUrl() ?: backendBaseUrl())
                            updateModeUi()
                        }
                    }
                    addView(automaticSegment, LinearLayout.LayoutParams(0, dp(38), 1f))
                    addView(manualSegment, LinearLayout.LayoutParams(0, dp(38), 1f))
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply {
                    topMargin = dp(16)
                }
            )
            contextZone = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(contextZone, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(16)
            })
            addView(
                View(this@MainActivity).apply {
                    setBackgroundColor(COLOR_HAIRLINE)
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                    topMargin = dp(20)
                    bottomMargin = dp(18)
                }
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Account"
                    setTextColor(COLOR_INK_SOFT)
                    textSize = 12f
                    typeface = inter(600)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(11), 0, dp(10))
                    isClickable = true
                    isFocusable = true
                    background = roundedState(Color.TRANSPARENT, 0x08000000, dp(8).toFloat())
                    addView(
                        ImageView(this@MainActivity).apply {
                            setImageResource(R.drawable.ic_google_g)
                            contentDescription = "Google"
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setPadding(dp(2), dp(2), dp(2), dp(2))
                        },
                        LinearLayout.LayoutParams(dp(24), dp(24)).apply {
                            rightMargin = dp(10)
                        }
                    )
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.VERTICAL
                            accountTitle = TextView(this@MainActivity).apply {
                                setTextColor(COLOR_INK)
                                textSize = 15f
                                typeface = inter(600)
                                includeFontPadding = false
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            }
                            accountDetail = TextView(this@MainActivity).apply {
                                setTextColor(COLOR_INK_SOFT)
                                textSize = 12f
                                typeface = inter(400)
                                includeFontPadding = false
                                setPadding(0, dp(3), 0, 0)
                            }
                            addView(accountTitle, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            addView(accountDetail, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        },
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    signOutAction = TextView(this@MainActivity).apply {
                        text = "Sign out"
                        setTextColor(COLOR_ACCENT)
                        textSize = 13f
                        typeface = inter(650)
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                        visibility = View.GONE
                        isClickable = true
                        isFocusable = true
                        background = roundedState(Color.TRANSPARENT, 0x0F000000, dp(16).toFloat())
                        setOnClickListener {
                            clearGoogleAccount()
                            updateAccountUi()
                        }
                    }
                    addView(signOutAction, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)).apply {
                        leftMargin = dp(8)
                    })
                    setOnClickListener {
                        if (googleAccountEmail() == null) beginGoogleSignIn { updateAccountUi() }
                    }
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    addView(
                        sheetCancelAction("Cancel").apply {
                            setOnClickListener { dismissSettingsSheet() }
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40))
                    )
                    addView(
                        sheetSaveAction("Save").apply {
                            setOnClickListener {
                                if (manualMode) {
                                    val normalized = normalizeBackendBaseUrl(input.text?.toString().orEmpty())
                                    if (normalized == null) {
                                        input.error = "Enter http(s)://host:port"
                                        return@setOnClickListener
                                    }
                                    saveBackendBaseUrl(normalized)
                                    Log.i(TAG, "Backend base URL updated base=$normalized source=${backendSourceLabel()} gallery=${galleryUrl()}")
                                } else {
                                    clearBackendBaseUrlOverride()
                                    Log.i(TAG, "Backend manual override cleared effective=${backendBaseUrl()} source=${backendSourceLabel()}")
                                }
                                dismissSettingsSheet()
                            }
                        },
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)).apply {
                            leftMargin = dp(12)
                        }
                    )
                },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(16)
                }
            )
        }

        sheetScroll = ScrollView(this).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isClickable = true
            visibility = View.INVISIBLE
            addView(sheet, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val popupRoot = FrameLayout(this).apply {
            setBackgroundColor(0x5C000000)
            isClickable = true
            setOnClickListener { dismissSettingsSheet() }
            addView(
                sheetScroll,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            )
        }
        updateModeUi()
        updateAccountUi()
        settingsPopup = android.widget.PopupWindow(
            popupRoot,
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            true
        ).apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            isOutsideTouchable = true
            setOnDismissListener { sheetScroll.animate().cancel() }
        }
        settingsPopup.showAtLocation(window.decorView, Gravity.NO_GRAVITY, 0, 0)
        sheetScroll.post {
            sheetScroll.translationY = sheetScroll.height.toFloat()
            sheetScroll.visibility = View.VISIBLE
            sheetScroll.animate()
                .translationY(0f)
                .setDuration(220L)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun manualBackendBaseUrl(): String? {
        val saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BACKEND_BASE_URL, null)
        return normalizeBackendBaseUrl(saved.orEmpty())
    }

    private fun backendBaseUrl(): String {
        manualBackendBaseUrl()?.let { return it }
        discoveredBackendBaseUrl?.let { return it }
        return DEFAULT_BACKEND_BASE_URL
    }

    private fun requiresCloudLogin(baseUrl: String = backendBaseUrl()): Boolean =
        runCatching {
            val host = Uri.parse(normalizeBackendBaseUrl(baseUrl) ?: baseUrl).host.orEmpty().lowercase()
            host == "wici.ai" || host.endsWith(".wici.ai")
        }.getOrDefault(false)

    private fun saveBackendBaseUrl(value: String) {
        stopBackendDiscovery()
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_BACKEND_BASE_URL, value)
            .apply()
    }

    private fun clearBackendBaseUrlOverride() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(PREF_BACKEND_BASE_URL)
            .apply()
        startBackendDiscoveryIfNeeded(force = true)
    }

    private fun backendSourceLabel(): String =
        when {
            manualBackendBaseUrl() != null -> "manual"
            discoveredBackendBaseUrl != null -> "discovered local"
            backendDiscoveryInProgress -> "cloud default while local discovery runs"
            else -> "cloud default"
        }

    private fun backendSettingsSummary(): String {
        val source = when {
            manualBackendBaseUrl() != null -> "Manual override"
            discoveredBackendBaseUrl != null -> "Discovered local backend"
            backendDiscoveryInProgress -> "Cloud default while local discovery runs"
            else -> "Cloud default"
        }
        return "Current: ${backendBaseUrl()}\nSource: $source"
    }

    private fun normalizeBackendBaseUrl(value: String): String? {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.isBlank()) return null
        val uri = try {
            Uri.parse(trimmed)
        } catch (_: Exception) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        val port = uri.port
        val hasOnlyRootPath = uri.path.isNullOrBlank() || uri.path == "/"
        if (scheme !in setOf("http", "https")) return null
        if (host.isNullOrBlank() || port !in 1..65535) return null
        if (!hasOnlyRootPath || !uri.query.isNullOrBlank() || !uri.fragment.isNullOrBlank()) return null
        return "$scheme://$host:$port"
    }

    private fun galleryUrl(): String = "${backendBaseUrl()}/orbit"

    private fun difixUrl(): String = "${backendBaseUrl()}/difix"

    private fun fluxUrl(): String = "${backendBaseUrl()}/flux"

    private fun orbitPreviewUrl(): String = "${galleryUrl()}/orbit_preview"

    private fun startBackendDiscoveryIfNeeded(force: Boolean = false) {
        if (manualBackendBaseUrl() != null) {
            stopBackendDiscovery()
            return
        }
        if (!force && (backendDiscoveryInProgress || backendDiscoveryCompleted || discoveredBackendBaseUrl != null)) return
        stopBackendDiscovery()
        if (force) discoveredBackendBaseUrl = null
        backendDiscoveryInProgress = true
        backendDiscoveryCompleted = false
        nsdResolveInFlight = false

        val nsdManager = getSystemService(NSD_SERVICE) as? NsdManager
        if (nsdManager == null) {
            backendDiscoveryInProgress = false
            backendDiscoveryCompleted = true
            Log.w(TAG, "Backend NSD unavailable; using cloud default base=$DEFAULT_BACKEND_BASE_URL")
            return
        }

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.i(TAG, "Backend NSD discovery started type=$regType fallback=$DEFAULT_BACKEND_BASE_URL")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val type = serviceInfo.serviceType.orEmpty()
                if (!type.trimEnd('.').equals(NSD_BACKEND_SERVICE_TYPE.trimEnd('.'), ignoreCase = true)) return
                if (nsdResolveInFlight || manualBackendBaseUrl() != null) return
                nsdResolveInFlight = true
                Log.i(TAG, "Backend NSD service found name=${serviceInfo.serviceName} type=$type")
                try {
                    nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(service: NsdServiceInfo, errorCode: Int) {
                            nsdResolveInFlight = false
                            Log.w(TAG, "Backend NSD resolve failed name=${service.serviceName} error=$errorCode")
                        }

                        override fun onServiceResolved(service: NsdServiceInfo) {
                            val addresses = nsdHostAddresses(service)
                            val directHost = chooseBackendHostAddress(addresses)
                            val fallbackHost = if (directHost == null || directHost.contains(":")) {
                                fallbackBackendHostAddress(service)
                            } else {
                                null
                            }
                            val host = listOfNotNull(directHost, fallbackHost)
                                .firstOrNull { !it.contains(":") }
                                ?: directHost
                                ?: fallbackHost
                            val port = service.port
                            val base = host?.let { normalizeBackendBaseUrl(httpBackendUrl(it, port)) }
                            backendHandler.post {
                                if (manualBackendBaseUrl() != null) return@post
                                nsdResolveInFlight = false
                                if (base != null) {
                                    discoveredBackendBaseUrl = base
                                    Log.i(
                                        TAG,
                                        "Backend NSD discovered local name=${service.serviceName} " +
                                            "base=$base gallery=${galleryUrl()}"
                                    )
                                } else {
                                    Log.w(
                                        TAG,
                                        "Backend NSD resolved no routable address name=${service.serviceName} " +
                                            "port=$port addresses=${addresses.joinToString { it.hostAddress.orEmpty() }}"
                                    )
                                }
                                finishBackendDiscovery("resolved")
                            }
                        }
                    })
                } catch (exc: Exception) {
                    nsdResolveInFlight = false
                    Log.w(TAG, "Backend NSD resolve threw", exc)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "Backend NSD service lost name=${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(TAG, "Backend NSD discovery stopped type=$serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Backend NSD start failed type=$serviceType error=$errorCode")
                backendHandler.post { finishBackendDiscovery("start-failed") }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "Backend NSD stop failed type=$serviceType error=$errorCode")
            }
        }
        nsdDiscoveryListener = listener
        try {
            nsdManager.discoverServices(NSD_BACKEND_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            backendHandler.postDelayed({
                if (backendDiscoveryInProgress) {
                    Log.i(TAG, "Backend NSD discovery timeout fallback=$DEFAULT_BACKEND_BASE_URL")
                    finishBackendDiscovery("timeout")
                }
            }, NSD_DISCOVERY_TIMEOUT_MS)
        } catch (exc: Exception) {
            Log.w(TAG, "Backend NSD discovery threw", exc)
            finishBackendDiscovery("exception")
        }
    }

    private fun finishBackendDiscovery(reason: String) {
        backendDiscoveryInProgress = false
        backendDiscoveryCompleted = true
        backendHandler.removeCallbacksAndMessages(null)
        stopBackendDiscovery()
        Log.i(TAG, "Backend NSD discovery finished reason=$reason effective=${backendBaseUrl()} source=${backendSourceLabel()}")
    }

    private fun stopBackendDiscovery() {
        val listener = nsdDiscoveryListener ?: return
        nsdDiscoveryListener = null
        try {
            (getSystemService(NSD_SERVICE) as? NsdManager)?.stopServiceDiscovery(listener)
        } catch (_: Exception) {
        }
    }

    private fun httpBackendUrl(host: String, port: Int): String {
        val withoutZone = host.substringBefore('%')
        val hostPart = if (withoutZone.contains(":") && !withoutZone.startsWith("[")) "[$withoutZone]" else withoutZone
        return "http://$hostPart:$port"
    }

    private fun nsdHostAddresses(service: NsdServiceInfo): List<InetAddress> {
        val addresses = mutableListOf<InetAddress>()
        try {
            val method = service.javaClass.methods.firstOrNull {
                it.name == "getHostAddresses" && it.parameterTypes.isEmpty()
            }
            val values = method?.invoke(service) as? Iterable<*>
            values?.forEach { value ->
                if (value is InetAddress) addresses += value
            }
        } catch (exc: Exception) {
            Log.d(TAG, "Backend NSD multi-address unavailable: ${exc.message}")
        }
        service.host?.let { addresses += it }
        return addresses.distinctBy { it.hostAddress.orEmpty() }
    }

    private fun chooseBackendHostAddress(addresses: List<InetAddress>): String? {
        val routable = addresses.filterNot {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress
        }
        val ipv4 = routable.firstOrNull { !it.hostAddress.orEmpty().contains(":") }
        return (ipv4 ?: routable.firstOrNull())?.hostAddress?.substringBefore('%')
    }

    private fun fallbackBackendHostAddress(service: NsdServiceInfo): String? {
        val serviceName = service.serviceName.orEmpty()
        val hostLabel = serviceName.substringAfter(" on ", missingDelimiterValue = "")
            .trim()
            .trimEnd('.')
            .takeIf { it.isNotBlank() && it.all { ch -> ch.isLetterOrDigit() || ch == '-' || ch == '.' } }
            ?: return null
        val localHost = if (hostLabel.endsWith(".local", ignoreCase = true)) hostLabel else "$hostLabel.local"
        return try {
            val resolved = InetAddress.getAllByName(localHost).toList()
            val chosen = chooseBackendHostAddress(resolved)
            Log.i(
                TAG,
                "Backend NSD hostname fallback host=$localHost " +
                    "addresses=${resolved.joinToString { it.hostAddress.orEmpty() }} chosen=$chosen"
            )
            chosen
        } catch (exc: Exception) {
            Log.w(TAG, "Backend NSD hostname fallback failed host=$localHost: ${exc.message}")
            null
        }
    }

    private fun studioBackButton(): View =
        ChevronButton(this).apply {
            contentDescription = "back"
            isClickable = true
            isFocusable = true
            background = rounded(COLOR_SURFACE, dp(22).toFloat())
            applySoftShadow(this, 3)
            setOnClickListener { showAlbum() }
        }

    private fun primaryGenerateButton(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumWidth = dp(178)
            minimumHeight = dp(52)
            setPadding(dp(24), 0, dp(24), 0)
            isClickable = true
            isFocusable = true
            applySoftShadow(this, 4)
            setOnClickListener { handleStageAction() }
            generateSpinner = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleSmall).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(COLOR_INK_SOFT)
                visibility = View.GONE
            }
            generateLabel = TextView(this@MainActivity).apply {
                textSize = 15f
                typeface = inter(700)
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                compoundDrawablePadding = dp(7)
            }
            addView(
                generateSpinner,
                LinearLayout.LayoutParams(dp(20), dp(20)).apply {
                    rightMargin = dp(8)
                }
            )
            addView(
                generateLabel,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

    private fun handleStageAction() {
        when (displayMode) {
            DisplayMode.RAW -> generateFlux()
            DisplayMode.FLUX -> saveFinalImage()
        }
    }

    private fun updateViewerControls() {
        if (!::generateButton.isInitialized) return
        if (generateErrorOverlay != null) {
            generateButton.visibility = View.GONE
            return
        }
        if (displayMode == DisplayMode.FLUX && fluxDataUrl == null) {
            displayMode = DisplayMode.RAW
        }
        val hasCapture = latestCapture != null
        val hasFlux = fluxDataUrl != null
        when {
            difixBusy || fluxBusy -> {
                generateButton.visibility = View.VISIBLE
                styleStageButton("Generating...", enabled = false, busy = true)
            }
            hasFlux && displayMode == DisplayMode.FLUX -> {
                generateButton.visibility = View.VISIBLE
                styleStageButton("Download", enabled = true, busy = false)
            }
            hasCapture -> {
                generateButton.visibility = View.VISIBLE
                styleStageButton("Generate", enabled = true, busy = false)
            }
            else -> {
                generateButton.visibility = View.GONE
                styleStageButton("Generate", enabled = false, busy = false)
            }
        }
    }

    private fun styleStageButton(label: String, enabled: Boolean, busy: Boolean) {
        generateButton.isEnabled = enabled
        generateButton.isClickable = enabled
        generateButton.alpha = 1f
        generateButton.background = when {
            enabled -> roundedState(COLOR_SURFACE, 0xFFF6F7FA.toInt(), dp(26).toFloat(), dpFloat(1.2f), COLOR_HAIRLINE)
            busy -> rounded(COLOR_SURFACE, dp(26).toFloat(), dpFloat(1.2f), COLOR_HAIRLINE)
            else -> rounded(0xFFE6E8EE.toInt(), dp(26).toFloat(), dpFloat(1f), COLOR_HAIRLINE)
        }
        generateSpinner.visibility = if (busy) View.VISIBLE else View.GONE
        generateLabel.text = label
        generateLabel.setTextColor(if (enabled || busy) COLOR_INK else COLOR_INK_SOFT)
        generateLabel.setCompoundDrawables(null, null, null, null)
    }

    private fun setInlineStatus(view: TextView, message: String) {
        view.text = message
        view.visibility = if (message.isBlank()) View.GONE else View.VISIBLE
        if (message.isNotBlank()) {
            view.background = rounded(0xEFFFFFFF.toInt(), dp(18).toFloat())
            applySoftShadow(view, 2)
        }
    }

    private fun fitPlateToBitmap(plate: View, bitmapWidth: Int, bitmapHeight: Int, horizontalMargin: Int, topMargin: Int, bottomMargin: Int) {
        plate.post {
            val parent = plate.parent as? View ?: return@post
            val maxW = (parent.width - horizontalMargin * 2).coerceAtLeast(1)
            val maxH = (parent.height - topMargin - bottomMargin).coerceAtLeast(1)
            val scale = min(maxW.toFloat() / bitmapWidth.coerceAtLeast(1), maxH.toFloat() / bitmapHeight.coerceAtLeast(1))
            val lp = (plate.layoutParams as? FrameLayout.LayoutParams) ?: return@post
            lp.width = (bitmapWidth * scale).roundToInt().coerceAtLeast(1)
            lp.height = (bitmapHeight * scale).roundToInt().coerceAtLeast(1)
            lp.gravity = Gravity.CENTER
            lp.leftMargin = horizontalMargin
            lp.rightMargin = horizontalMargin
            lp.topMargin = topMargin
            lp.bottomMargin = bottomMargin
            plate.layoutParams = lp
        }
    }

    private fun applySoftShadow(view: View, elevationDp: Int = 3) {
        view.elevation = dp(elevationDp).toFloat()
        view.translationZ = 0f
        view.stateListAnimator = null
    }

    private fun rounded(
        color: Int,
        radius: Float,
        strokeWidth: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT
    ): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius
            if (strokeWidth > 0f) {
                setStroke(strokeWidth.roundToInt().coerceAtLeast(1), strokeColor)
            }
        }

    private fun roundedTop(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadii = floatArrayOf(radius, radius, radius, radius, 0f, 0f, 0f, 0f)
        }

    private fun roundedState(
        normalColor: Int,
        pressedColor: Int,
        radius: Float,
        strokeWidth: Float = 0f,
        strokeColor: Int = Color.TRANSPARENT
    ): StateListDrawable =
        StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), rounded(pressedColor, radius, strokeWidth, strokeColor))
            addState(intArrayOf(-android.R.attr.state_enabled), rounded(COLOR_HAIRLINE, radius, strokeWidth, strokeColor))
            addState(intArrayOf(), rounded(normalColor, radius, strokeWidth, strokeColor))
        }

    private fun absoluteGalleryUrl(pathOrUrl: String): String =
        when {
            pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
            pathOrUrl.startsWith("/") -> "${galleryUrl()}$pathOrUrl"
            else -> "${galleryUrl()}/$pathOrUrl"
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun dpFloat(value: Float): Float =
        value * resources.displayMetrics.density

    private fun applyFullscreen(root: View) {
        root.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun photoIdForAsset(name: String): String =
        when {
            name.contains("phone-photo-man", ignoreCase = true) -> "phone-photo-man"
            else -> name.substringBeforeLast(".")
        }

    private fun optionalInt(item: JSONObject, key: String): Int? {
        if (!item.has(key) || item.isNull(key)) return null
        return item.optInt(key).takeIf { it > 0 }
    }

    private fun firstOptionalInt(item: JSONObject, vararg keys: String): Int? {
        for (key in keys) {
            optionalInt(item, key)?.let { return it }
        }
        return null
    }

    private fun optionalFloat(item: JSONObject, key: String): Float? {
        if (!item.has(key) || item.isNull(key)) return null
        val value = item.optDouble(key, Double.NaN).toFloat()
        return value.takeIf { it.isFinite() && it > 0f }
    }

    private fun firstOptionalFloat(item: JSONObject, vararg keys: String): Float? {
        for (key in keys) {
            optionalFloat(item, key)?.let { return it }
        }
        return null
    }

    private fun firstOptionalUrl(item: JSONObject, vararg keys: String): String? {
        for (key in keys) {
            if (!item.has(key) || item.isNull(key)) continue
            val value = item.optString(key).trim()
            if (value.isNotEmpty()) return absoluteGalleryUrl(value)
        }
        return null
    }

    private fun HttpURLConnection.headerString(name: String): String? =
        getHeaderField(name)?.trim()?.takeIf { it.isNotEmpty() }

    private fun HttpURLConnection.headerInt(name: String): Int? =
        headerString(name)?.toIntOrNull()?.takeIf { it > 0 }

    private fun HttpURLConnection.headerLong(name: String): Long? =
        headerString(name)?.toLongOrNull()?.takeIf { it > 0L }

    private fun HttpURLConnection.headerFloat(name: String): Float? =
        headerString(name)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }

    private fun elapsedMs(startNs: Long): Long =
        (SystemClock.elapsedRealtimeNanos() - startNs) / 1_000_000L

    private fun parseIngestSharpMs(timingJson: String?): Double? {
        if (timingJson.isNullOrBlank()) return null
        return try {
            val json = JSONObject(timingJson)
            val sharpServerSeconds = json.optJSONObject("sharpServer")?.optDouble("seconds", Double.NaN)
            val seconds = listOf(
                sharpServerSeconds,
                json.optDouble("sharpInprocSeconds", Double.NaN),
                json.optDouble("sharpHttpSeconds", Double.NaN),
                json.optDouble("sharpServerSeconds", Double.NaN)
            ).firstOrNull { it != null && it.isFinite() && it >= 0.0 }
            seconds?.times(1000.0)
        } catch (exc: Exception) {
            Log.w(TAG, "Failed to parse ingest timing header: ${exc.message}")
            null
        }
    }

    private fun intentPositiveFloat(key: String): Float? =
        if (intent.hasExtra(key)) {
            intent.getFloatExtra(key, Float.NaN).takeIf { it.isFinite() && it > 0f }
        } else {
            null
        }

    private fun photoLibraryPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    private fun hasPhotoLibraryPermission(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            true
        } else {
            checkSelfPermission(photoLibraryPermission()) == PackageManager.PERMISSION_GRANTED
        }

    private fun sourceDimsFromIntent(): Pair<Int, Int>? {
        val width = intent.getIntExtra("sourceWidth", 0).takeIf { it > 0 }
            ?: intent.getIntExtra("width", 0).takeIf { it > 0 }
        val height = intent.getIntExtra("sourceHeight", 0).takeIf { it > 0 }
            ?: intent.getIntExtra("height", 0).takeIf { it > 0 }
        return if (width != null && height != null) width to height else null
    }

    private fun legacySourceDims(photoId: String): Pair<Int, Int>? =
        when (photoId) {
            "phone-photo-man" -> 1920 to 1280
            "cafe-couple-bench" -> 1920 to 2886
            "01-woman-portrait" -> 1600 to 2400
            "02-man-stairs" -> 1600 to 2400
            "03-man-window" -> 1600 to 2400
            "04-family-street" -> 1600 to 2400
            "05-street-crowd" -> 1600 to 2400
            "06-woman-street" -> 1600 to 2400
            "07-forest-path" -> 1600 to 2415
            "08-autumn-trail" -> 1600 to 2400
            "09-dog-grass" -> 1600 to 1067
            "10-poodle-park" -> 1600 to 2843
            "wm-people-01-pilgrim-valletta" -> 1205 to 1800
            "wm-people-02-fishmonger-market" -> 1800 to 1198
            "wm-people-03-red-yukata-gion" -> 1800 to 1200
            "wm-people-04-shepherd-rajasthan" -> 1800 to 1200
            "wm-landscape-01-hallasan-steps" -> 1800 to 1216
            "wm-landscape-02-blue-lake-cook" -> 1800 to 1245
            "wm-landscape-03-lake-gosaikunda" -> 1800 to 1201
            "wm-landscape-04-chola-valley" -> 1800 to 1200
            "wm-street-01-madhya-pradesh-04" -> 1800 to 1200
            "wm-street-02-madhya-pradesh-06" -> 1800 to 1200
            "wm-street-03-madhya-pradesh-13" -> 1800 to 1200
            else -> null
        }

    private fun sourcePathForAsset(name: String): String? =
        if (name.contains("phone-photo-man", ignoreCase = true)) {
            "/home/wici/spatial-reframing/spatial-viewer/plys/phone-photo-man-original.jpg"
        } else {
            null
        }

    private enum class DisplayMode {
        RAW,
        FLUX
    }

    private class GearButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK_SOFT
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val gearPath = Path()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val outer = 13.0f * density
            val root = 10.0f * density
            val hole = 4.4f * density
            gearPath.reset()
            for (i in 0 until 8) {
                val base = Math.toRadians((i * 45f - 22.5f).toDouble())
                val points = doubleArrayOf(
                    base,
                    base + Math.toRadians(9.5),
                    base + Math.toRadians(35.5),
                    base + Math.toRadians(45.0)
                )
                for ((j, angle) in points.withIndex()) {
                    val radius = if (j == 1 || j == 2) outer else root
                    val x = cx + (Math.cos(angle) * radius).toFloat()
                    val y = cy + (Math.sin(angle) * radius).toFloat()
                    if (i == 0 && j == 0) gearPath.moveTo(x, y) else gearPath.lineTo(x, y)
                }
            }
            gearPath.close()
            paint.style = Paint.Style.FILL
            paint.color = COLOR_INK_SOFT
            canvas.drawPath(gearPath, paint)
            paint.style = Paint.Style.FILL
            paint.color = COLOR_SURFACE
            canvas.drawCircle(cx, cy, hole, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.4f * density
            paint.color = COLOR_INK_SOFT
            canvas.drawPath(gearPath, paint)
            canvas.drawCircle(cx, cy, hole, paint)
        }
    }

    private class ChevronButton(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_INK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 2.2f * context.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawLine(w * 0.58f, h * 0.32f, w * 0.42f, h * 0.5f, paint)
            canvas.drawLine(w * 0.42f, h * 0.5f, w * 0.58f, h * 0.68f, paint)
        }
    }

    private class DeleteBadgeView(context: Context) : View(context) {
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = COLOR_DELETE
            style = Paint.Style.FILL
        }
        private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x22FFFFFF
            style = Paint.Style.STROKE
            strokeWidth = 1f * context.resources.displayMetrics.density
        }
        private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 1.9f * context.resources.displayMetrics.density
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val radius = min(width, height) * 0.5f
            val cx = width * 0.5f
            val cy = height * 0.5f
            canvas.drawCircle(cx, cy, radius - strokePaint.strokeWidth, fillPaint)
            canvas.drawCircle(cx, cy, radius - strokePaint.strokeWidth, strokePaint)
            val inset = radius * 0.42f
            canvas.drawLine(cx - inset, cy - inset, cx + inset, cy + inset, xPaint)
            canvas.drawLine(cx + inset, cy - inset, cx - inset, cy + inset, xPaint)
        }
    }

    private data class AlbumPhoto(
        val photoId: String,
        val thumbnailUrl: String,
        val hasOrbit: Boolean,
        val hasSplat: Boolean,
        val sourceWidth: Int?,
        val sourceHeight: Int?,
        val camFx: Float?,
        val camFy: Float?,
        val camCx: Float?,
        val camCy: Float?,
        val sourceUrl: String? = null,
        val orbitWebpUrl: String? = null,
        val orbitPreviewUrl: String? = null,
        val splatStreamUrl: String? = null,
        val splatUrl: String? = null,
        val localUri: Uri? = null,
        val imported: Boolean = false
    ) {
        val splatAsset: String get() = "$photoId.splat"
    }

    private data class AlbumScrollState(
        val firstVisiblePosition: Int,
        val topOffsetPx: Int
    )

    private class AspectFrameLayout(
        context: Context,
        private val aspect: Float?
    ) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val targetAspect = aspect?.takeIf { it.isFinite() && it > 0f }
            if (targetAspect == null) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val parentW = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
            val parentH = MeasureSpec.getSize(heightMeasureSpec).coerceAtLeast(1)
            var measuredW = parentW
            var measuredH = (measuredW / targetAspect).roundToInt().coerceAtLeast(1)
            if (measuredH > parentH) {
                measuredH = parentH
                measuredW = (measuredH * targetAspect).roundToInt().coerceAtLeast(1)
            }
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(measuredW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(measuredH, MeasureSpec.EXACTLY)
            )
        }
    }

    private open class SquareFrameLayout(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val square = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            super.onMeasure(widthMeasureSpec, square)
        }
    }

    companion object {
        private const val TAG = "AlbumPipeline"
        private const val PREVIEW_TAG = "AlbumPreview"
        private const val PREFS_NAME = "android-album-demo"
        private const val PREF_REMOVED_CURATED_IDS = "removed_curated_photo_ids"
        private const val PREF_BACKEND_BASE_URL = "backend_base_url"
        private const val DEFAULT_BACKEND_BASE_URL = "http://app.wici.ai:54228"
        private const val NSD_BACKEND_SERVICE_TYPE = "_wici-backend._tcp."
        private const val NSD_DISCOVERY_TIMEOUT_MS = 3_000L
        private const val BACKEND_HEALTH_TIMEOUT_MS = 2_500
        private const val ADD_TILE_ID = "__add_photo__"
        private const val SHOW_RENDER_DEBUG = false
        private const val EDIT_JIGGLE_DEGREES = 1.5f
        private const val EDIT_JIGGLE_DURATION_MS = 150L
        private const val EDIT_LONG_PRESS_MS = 520L
        private val COLOR_CANVAS = 0xFFF2F3F5.toInt()
        private val COLOR_SURFACE = 0xFFFFFFFF.toInt()
        private val COLOR_INK = 0xFF16171A.toInt()
        private val COLOR_INK_SOFT = 0xFF6B6E76.toInt()
        private val COLOR_HAIRLINE = 0xFFE2E4E8.toInt()
        private val COLOR_ACCENT = 0xFF5B5BFF.toInt()
        private val COLOR_ACCENT_PRESS = 0xFF4A47E0.toInt()
        private val COLOR_DELETE = 0xFFFF3B30.toInt()
        private const val ORBIT_PREVIEW_CACHE_VERSION = "webp360-v1"
        private const val ORBIT_PREVIEW_CACHE_MAX_BYTES = 96L * 1024L * 1024L
        private const val PREVIEW_BAKE_CONCURRENCY = 3
        private const val DEFAULT_SPLAT_DENSITY = "full"
        private const val SPLAT_ROW_BYTES = 32
        private const val INGEST_SPLAT_ACCEPT_HEADER = "X-Wici-Splat-Accept"
        private const val INGEST_SPLAT_FORMAT_FP16_V1 = "fp16v1"
        private const val PREVIEW_CASCADE_STAGGER_MS = 120L
        private const val PREVIEW_REVEAL_MS = 300L
        private const val PREVIEW_REVEAL_START_SCALE = 0.96f
        private const val REQUEST_PICK_IMAGES = 4201
        private const val REQUEST_READ_PHOTOS = 4202
        private const val PICK_IMAGES_MAX = 100
        private const val DEVICE_PHOTO_LIMIT = 600
        private const val THUMBNAIL_MAX_SIDE = 720
        private const val DISPLAY_IMAGE_MAX_SIDE = 3200
        private const val PREVIEW_UPLOAD_MAX_SIDE = 1536
        private const val PREVIEW_UPLOAD_JPEG_QUALITY = 90
        private const val DIFIX_SOURCE_UPLOAD_MAX_SIDE = 960
        private const val DIFIX_SOURCE_UPLOAD_JPEG_QUALITY = PREVIEW_UPLOAD_JPEG_QUALITY
    }
}
