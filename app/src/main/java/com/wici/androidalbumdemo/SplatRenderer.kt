package com.wici.androidalbumdemo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.net.Uri
import android.os.SystemClock
import android.util.Half
import android.util.Log
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

class SplatRenderer(
    private val context: Context,
    private val assetName: String,
    private val photoId: String,
    private val status: (String) -> Unit,
    private val releaseCapture: (ReleaseCapture) -> Unit = {},
    private val streamErrorCallback: (String) -> Unit = {},
    private val postPassEnabled: Boolean = true,
    streamDensity: String? = null,
    footprintScale: Float? = null,
    sourceWidth: Int? = null,
    sourceHeight: Int? = null,
    camFx: Float? = null,
    camFy: Float? = null,
    camCx: Float? = null,
    camCy: Float? = null,
    private val splatStreamUrl: String? = null,
    private val splatStreamEndpointUrl: String? = null,
    private val ingestEndpointUrl: String? = null,
    private val ingestImageUri: String? = null,
    splatCacheMaxBytes: Long? = null,
    private val networkStreamEnabled: Boolean = true
) : GLSurfaceView.Renderer {
    private var model: SplatModel? = null
    private val streamPhotoId = photoId.takeIf { it.isNotBlank() }
    private val requestedStreamDensity = when {
        streamDensity == null || streamDensity.isBlank() -> DEFAULT_STREAM_DENSITY
        streamDensity == "default" -> null
        else -> streamDensity
    }
    private val renderFootprintScale = footprintScale?.takeIf { it.isFinite() && it > 0f } ?: FOOTPRINT_SCALE
    private val splatCacheMaxBytes = splatCacheMaxBytes?.takeIf { it > 0L } ?: SplatCache.DEFAULT_MAX_BYTES
    private var sourceCamera =
        sourceCameraFromExact(sourceWidth, sourceHeight, camFx, camFy, camCx, camCy, "gallery")
            ?: sourceCameraFromDims(sourceWidth, sourceHeight)
            ?: sourceCameraForAsset(assetName)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private var viewportW = 1
    private var viewportH = 1
    private var yaw = if (sourceCamera != null) 0f else 0.35f
    private var pitch = if (sourceCamera != null) 0f else -0.15f
    private var zoom = if (sourceCamera != null) SOURCE_REST_ZOOM else 1f
    private var panX = 0f
    private var panY = 0f
    private var contentMinX = 0f
    private var contentMinY = 0f
    private var contentMaxX = 1f
    private var contentMaxY = 1f
    private var sortDirty = true
    private var interactionActive = false
    private var frameCount = 0
    private var lastStatusMs = 0L
    private var statsFrames = 0
    private var statsSortCount = 0
    private var statsSortNs = 0L
    private var statsDrawNs = 0L
    private var releaseCaptureRequested = false
    private var releaseCaptureReason = "unknown"
    private var lastReleaseCaptureDeferredLogMs = 0L
    private val captureRunning = AtomicBoolean(false)

    private var splatProgram = 0
    private var blitProgram = 0
    private var rawBlitProgram = 0
    private var downsampleProgram = 0
    private var seedFillProgram = 0
    private var pushFillProgram = 0
    private var vao = 0
    private var quadVbo = 0
    private var instanceVbo = 0
    private var instanceCapacityBytes = 0
    private var fbo = 0
    private var fboTex = 0
    private val mipTex = IntArray(PYRAMID_LEVELS)
    private val mipFbo = IntArray(PYRAMID_LEVELS)
    private val fillTex = IntArray(PYRAMID_LEVELS)
    private val fillFbo = IntArray(PYRAMID_LEVELS)
    private val mipW = IntArray(PYRAMID_LEVELS)
    private val mipH = IntArray(PYRAMID_LEVELS)
    private val ellipseScratch = FloatArray(7)
    private val sortExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AlbumGLES-sort").apply { isDaemon = true }
    }
    private val captureExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AlbumGLES-capture").apply { isDaemon = true }
    }
    private val streamExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "AlbumGLES-stream").apply { isDaemon = true }
    }
    private val sortRunning = AtomicBoolean(false)
    private val streamCancelled = AtomicBoolean(false)
    private val pendingSortLock = Any()
    private var pendingSort: SortResult? = null
    private val instanceBuildBufferLock = Any()
    private var instanceBuildBuffer: ByteBuffer? = null
    private var instanceBuildBufferCapacityBytes = 0
    private val pendingStreamLock = Any()
    private val pendingStreamBatches = ArrayDeque<SplatBatch>()
    private var hasUploadedInstances = false
    private var uploadedInstanceCount = 0
    private var lastSubmittedSortYaw = Float.NaN
    private var lastSubmittedSortPitch = Float.NaN
    private var lastUploadedSortYaw = Float.NaN
    private var lastUploadedSortPitch = Float.NaN
    private val statsLock = Any()
    @Volatile private var streamExpectedCount = 0
    @Volatile private var streamComplete = false
    @Volatile private var streamError: String? = null
    @Volatile private var streamConnection: HttpURLConnection? = null
    @Volatile private var pendingSourceCamera: SourceCamera? = null
    private var streamStartMs = 0L
    private var firstStreamFrameLogged = false
    private var autoFitActive = false
    private var lastOrbitClampLogMs = 0L
    private var cameraSafetyEvaluatedCount = 0
    private val pointScratch = FloatArray(4)
    private val viewPointScratch = FloatArray(4)
    private val clipPointScratch = FloatArray(4)

    private data class SourceCamera(
        val width: Int,
        val height: Int,
        val fx: Float,
        val fy: Float,
        val cx: Float,
        val cy: Float,
        val exact: Boolean,
        val source: String
    )

    fun orbit(dx: Float, dy: Float) {
        val radiansPerPixel = (TWO_PI * WEB_ROTATE_SPEED) / viewportH.coerceAtLeast(1).toFloat()
        val nextYaw = yaw + dx * radiansPerPixel
        val nextPitch = pitch + dy * radiansPerPixel
        yaw = nextYaw.coerceIn(-WEB_AZIMUTH_LIMIT_RAD, WEB_AZIMUTH_LIMIT_RAD)
        pitch = nextPitch.coerceIn(-WEB_POLAR_LIMIT_RAD, WEB_POLAR_LIMIT_RAD)
        if (abs(yaw - nextYaw) > 1e-5f || abs(pitch - nextPitch) > 1e-5f) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastOrbitClampLogMs > 250L) {
                lastOrbitClampLogMs = now
                Log.i(
                    TAG,
                    "orbitClamp photoId=$photoId yawDeg=%.1f pitchDeg=%.1f limitsDeg=±%.0f/±%.0f"
                        .format(
                            Math.toDegrees(yaw.toDouble()),
                            Math.toDegrees(pitch.toDouble()),
                            WEB_AZIMUTH_LIMIT_DEG,
                            WEB_POLAR_LIMIT_DEG
                        )
                )
            }
        }
        sortDirty = true
    }

    fun dolly(scale: Float) {
        if (!scale.isFinite() || scale <= 0f) return
        zoom = (zoom / scale.pow(WEB_ZOOM_SPEED)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        sortDirty = true
    }

    fun pan(dxPx: Float, dyPx: Float) {
        if (!dxPx.isFinite() || !dyPx.isFinite() || viewportW <= 1 || viewportH <= 1) return
        val focalPx = (abs(proj[5]) * viewportH.toFloat() * 0.5f).takeIf { it.isFinite() && it > 1f }
            ?: viewportH.toFloat()
        val unitsPerPx = currentCameraDistance() / focalPx
        panX -= dxPx * unitsPerPx
        panY += dyPx * unitsPerPx
        sortDirty = true
    }

    fun resetView() {
        yaw = if (sourceCamera != null || autoFitActive) 0f else 0.35f
        pitch = if (sourceCamera != null || autoFitActive) 0f else -0.15f
        zoom = if (sourceCamera != null || autoFitActive) SOURCE_REST_ZOOM else 1f
        panX = 0f
        panY = 0f
        resetSortForViewChange()
        Log.i(TAG, "viewReset photoId=$photoId")
    }

    fun setInteractionActive(active: Boolean) {
        if (interactionActive == active) return
        interactionActive = active
        if (!active) sortDirty = true
        Log.i(TAG, "interactionActive=$active")
    }

    fun requestReleaseCapture(reason: String = "unknown") {
        releaseCaptureReason = reason
        releaseCaptureRequested = true
        Log.i(TAG, "releaseCaptureRequested reason=$reason")
    }

    fun shutdown() {
        streamCancelled.set(true)
        streamConnection?.disconnect()
        synchronized(pendingStreamLock) {
            pendingStreamBatches.clear()
        }
        synchronized(pendingSortLock) {
            pendingSort = null
        }
        sortExecutor.shutdownNow()
        captureExecutor.shutdownNow()
        streamExecutor.shutdownNow()
        model = null
        uploadedInstanceCount = 0
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val retainedModel = model
        resetGlResourceHandlesForContext()
        resetSortUploadState()

        GLES30.glClearColor(0.949f, 0.953f, 0.961f, 1f)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)

        splatProgram = buildProgram(SPLAT_VS, SPLAT_FS)
        blitProgram = buildProgram(BLIT_VS, BLIT_FS)
        rawBlitProgram = buildProgram(BLIT_VS, RAW_BLIT_FS)
        downsampleProgram = buildProgram(BLIT_VS, DOWNSAMPLE_FS)
        seedFillProgram = buildProgram(BLIT_VS, SEED_FILL_FS)
        pushFillProgram = buildProgram(BLIT_VS, PUSH_FILL_FS)
        createGeometry()

        lastStatusMs = SystemClock.elapsedRealtime()
        val streamId = streamPhotoId
        if (streamId != null) {
            if (retainedModel != null && retainedModel.count > 0) {
                model = retainedModel
                sortDirty = true
                status("Restored $streamId${experimentLabel()} ${retainedModel.count}/${streamExpectedCount.takeIf { it > 0 } ?: "?"}")
                Log.i(
                    TAG,
                    "surfaceCreatedRetained photoId=$streamId count=${retainedModel.count} " +
                        "streamComplete=$streamComplete expected=$streamExpectedCount"
                )
            } else {
                model = emptyModel(assetName)
                sortDirty = true
                streamComplete = false
                firstStreamFrameLogged = false
                streamError = null
                streamStartMs = SystemClock.elapsedRealtime()
                status("Streaming $streamId${experimentLabel()}...")
                Log.i(TAG, "surfaceCreatedStartStream photoId=$streamId retainedCount=${retainedModel?.count ?: 0}")
                startSplatStream(streamId)
            }
        } else {
            if (retainedModel != null && retainedModel.count > 0) {
                model = retainedModel
                sortDirty = true
                status("Restored ${retainedModel.assetName}: ${retainedModel.count} splats")
                Log.i(TAG, "surfaceCreatedRetained asset=$assetName count=${retainedModel.count}")
            } else {
                val start = SystemClock.elapsedRealtime()
                model = PlyLoader.loadAsset(context, assetName)
                val elapsed = SystemClock.elapsedRealtime() - start
                val m = requireNotNull(model)
                sortDirty = true
                status("Loaded ${m.assetName}: ${m.count} splats, invalid=${m.invalidValueCount}, ${elapsed}ms")
            }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = width.coerceAtLeast(1)
        viewportH = height.coerceAtLeast(1)
        cameraSafetyEvaluatedCount = 0
        val camera = sourceCamera
        if (camera != null && !autoFitActive) {
            setSourceProjection(camera)
            Log.i(
                TAG,
                "sourceCamera asset=$assetName fx=${camera.fx} fy=${camera.fy} " +
                    "cx=${camera.cx} cy=${camera.cy} source=${camera.width}x${camera.height} " +
                    "cameraSource=${camera.source} exact=${camera.exact} viewport=${viewportW}x${viewportH} " +
                    interactionProjectionLog()
            )
        } else {
            setAutoFitProjection()
            Log.i(TAG, "autoFitProjection asset=$assetName viewport=${viewportW}x${viewportH} ${interactionProjectionLog()}")
        }
        createFbo(viewportW, viewportH)
        createPostFbos(viewportW, viewportH)
        sortDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        applyPendingSourceCamera()
        applyPendingStreamBatches()
        val m = model ?: return
        updateView(m)

        uploadPendingSort()
        if (m.count > 0 && !hasUploadedInstances) {
            val sortStartNs = System.nanoTime()
            val result = buildInstanceBuffer(m, view.copyOf(), yaw, pitch)
            uploadInstanceBuffer(result.buffer, result.byteCount)
            lastSubmittedSortYaw = yaw
            lastSubmittedSortPitch = pitch
            lastUploadedSortYaw = yaw
            lastUploadedSortPitch = pitch
            hasUploadedInstances = true
            recordSortTime(System.nanoTime() - sortStartNs)
            sortDirty = false
        } else if (m.count > 0 && sortDirty && !interactionActive) {
            maybeScheduleSort(m)
        }

        val drawStartNs = System.nanoTime()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        GLES30.glClearColor(0.025f, 0.028f, 0.032f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_BLEND)
        drawSplats(uploadedInstanceCount)

        val usePostPass = postPassEnabled && !isProgressiveLoadInFlight() && !isSourceRestPose()
        if (usePostPass) {
            runPushPullFill()
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        GLES30.glClearColor(0.949f, 0.953f, 0.961f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        if (usePostPass) {
            drawComposite()
        } else {
            drawRawBlit()
        }
        maybeCaptureRelease(m)
        val drawNs = System.nanoTime() - drawStartNs

        frameCount++
        statsFrames++
        statsDrawNs += drawNs
        val now = SystemClock.elapsedRealtime()
        if (now - lastStatusMs > 1000L) {
            val elapsedMs = (now - lastStatusMs).coerceAtLeast(1L)
            val fps = statsFrames * 1000f / elapsedMs.toFloat()
            val avgDrawMs = statsDrawNs / 1_000_000.0 / statsFrames.coerceAtLeast(1)
            val sortStats = synchronized(statsLock) {
                val count = statsSortCount
                val ns = statsSortNs
                statsSortCount = 0
                statsSortNs = 0L
                count to ns
            }
            val avgSortMs = if (sortStats.first > 0) sortStats.second / 1_000_000.0 / sortStats.first else 0.0
            val sortLabel = if (sortStats.first > 0) "%.1f".format(avgSortMs) else "-"
            val loadedLabel = streamLabel(m)
            val message = "${m.assetName}: $loadedLabel | fps %.1f | sort $sortLabel ms x${sortStats.first} | draw %.1f ms | ${viewportW}x${viewportH} FBO"
                .format(fps, avgDrawMs)
            status(message)
            Log.i(TAG, message)
            lastStatusMs = now
            statsFrames = 0
            statsDrawNs = 0L
        }
    }

    private fun updateView(m: SplatModel) {
        if (autoFitActive) {
            updateAutoFitView(m)
            return
        }
        if (sourceCamera != null) {
            updateSourceView()
            maybeApplyCameraSafety(m)
            if (autoFitActive) {
                updateAutoFitView(m)
            }
            return
        }
        val distance = m.radius * 2.6f * zoom
        val cp = cos(pitch)
        val eyeX = m.centerX + distance * sin(yaw) * cp
        val eyeY = m.centerY + distance * sin(pitch)
        val eyeZ = m.centerZ + distance * cos(yaw) * cp
        setLookAtWithPan(eyeX, eyeY, eyeZ, m.centerX, m.centerY, m.centerZ, 0f, 1f, 0f)
    }

    private fun updateSourceView() {
        val targetZ = SOURCE_LOOK_AT_Z
        val distance = targetZ * zoom
        val cp = cos(pitch)
        val eyeX = distance * sin(yaw) * cp
        val eyeY = distance * sin(pitch)
        val eyeZ = targetZ - distance * cos(yaw) * cp
        setLookAtWithPan(eyeX, eyeY, eyeZ, 0f, 0f, targetZ, 0f, -1f, 0f)
    }

    private fun updateAutoFitView(m: SplatModel) {
        val distance = autoFitDistance(m) * zoom
        val cp = cos(pitch)
        val eyeX = m.centerX + distance * sin(yaw) * cp
        val eyeY = m.centerY + distance * sin(pitch)
        val eyeZ = m.centerZ - distance * cos(yaw) * cp
        setLookAtWithPan(eyeX, eyeY, eyeZ, m.centerX, m.centerY, m.centerZ, 0f, -1f, 0f)
    }

    private fun setLookAtWithPan(
        eyeX: Float,
        eyeY: Float,
        eyeZ: Float,
        targetX: Float,
        targetY: Float,
        targetZ: Float,
        upX: Float,
        upY: Float,
        upZ: Float
    ) {
        val fx = targetX - eyeX
        val fy = targetY - eyeY
        val fz = targetZ - eyeZ
        val fLen = sqrt(fx * fx + fy * fy + fz * fz).takeIf { it.isFinite() && it > 1e-5f } ?: 1f
        val fnx = fx / fLen
        val fny = fy / fLen
        val fnz = fz / fLen
        var rx = fny * upZ - fnz * upY
        var ry = fnz * upX - fnx * upZ
        var rz = fnx * upY - fny * upX
        val rLen = sqrt(rx * rx + ry * ry + rz * rz)
        if (!rLen.isFinite() || rLen <= 1e-5f) {
            rx = 1f
            ry = 0f
            rz = 0f
        } else {
            rx /= rLen
            ry /= rLen
            rz /= rLen
        }
        val ux = ry * fnz - rz * fny
        val uy = rz * fnx - rx * fnz
        val uz = rx * fny - ry * fnx
        val ox = rx * panX + ux * panY
        val oy = ry * panX + uy * panY
        val oz = rz * panX + uz * panY
        Matrix.setLookAtM(
            view,
            0,
            eyeX + ox,
            eyeY + oy,
            eyeZ + oz,
            targetX + ox,
            targetY + oy,
            targetZ + oz,
            ux,
            uy,
            uz
        )
    }

    private fun currentCameraDistance(): Float {
        val m = model
        return when {
            autoFitActive && m != null -> autoFitDistance(m) * zoom
            sourceCamera != null -> SOURCE_LOOK_AT_Z * zoom
            m != null -> m.radius * 2.6f * zoom
            else -> SOURCE_LOOK_AT_Z * zoom
        }.coerceAtLeast(0.001f)
    }

    private fun isSourceRestPose(): Boolean =
        !autoFitActive &&
            sourceCamera != null &&
            abs(yaw) < SOURCE_REST_EPSILON &&
            abs(pitch) < SOURCE_REST_EPSILON &&
            abs(zoom - SOURCE_REST_ZOOM) < SOURCE_REST_EPSILON &&
            abs(panX) < SOURCE_REST_EPSILON &&
            abs(panY) < SOURCE_REST_EPSILON

    private fun setSourceProjection(camera: SourceCamera) {
        val scale = min(
            viewportW.toFloat() / camera.width.toFloat(),
            viewportH.toFloat() / camera.height.toFloat()
        )
        val drawW = camera.width * scale
        val drawH = camera.height * scale
        val offsetX = (viewportW - drawW) * 0.5f
        val offsetY = (viewportH - drawH) * 0.5f
        val fx = camera.fx * scale
        val fy = camera.fy * scale
        val cx = camera.cx * scale + offsetX
        val cy = camera.cy * scale + offsetY
        contentMinX = (offsetX / viewportW.toFloat()).coerceIn(0f, 1f)
        contentMinY = (offsetY / viewportH.toFloat()).coerceIn(0f, 1f)
        contentMaxX = ((offsetX + drawW) / viewportW.toFloat()).coerceIn(0f, 1f)
        contentMaxY = ((offsetY + drawH) / viewportH.toFloat()).coerceIn(0f, 1f)

        for (i in proj.indices) proj[i] = 0f
        proj[0] = 2f * fx / viewportW.toFloat()
        proj[5] = 2f * fy / viewportH.toFloat()
        proj[8] = 1f - 2f * cx / viewportW.toFloat()
        proj[9] = 2f * cy / viewportH.toFloat() - 1f
        proj[10] = -(FAR_PLANE + NEAR_PLANE) / (FAR_PLANE - NEAR_PLANE)
        proj[11] = -1f
        proj[14] = -(2f * FAR_PLANE * NEAR_PLANE) / (FAR_PLANE - NEAR_PLANE)
    }

    private fun setFullContentRect() {
        contentMinX = 0f
        contentMinY = 0f
        contentMaxX = 1f
        contentMaxY = 1f
    }

    private fun setAutoFitProjection() {
        setFullContentRect()
        val focal = sourceLikeFocalPx()
        for (i in proj.indices) proj[i] = 0f
        proj[0] = 2f * focal / viewportW.toFloat()
        proj[5] = 2f * focal / viewportH.toFloat()
        proj[10] = -(FAR_PLANE + NEAR_PLANE) / (FAR_PLANE - NEAR_PLANE)
        proj[11] = -1f
        proj[14] = -(2f * FAR_PLANE * NEAR_PLANE) / (FAR_PLANE - NEAR_PLANE)
    }

    private fun sourceLikeFocalPx(): Float =
        (SOURCE_FOCAL_PER_MIN_SIDE * min(viewportW, viewportH).toFloat()).coerceAtLeast(1f)

    private fun interactionProjectionLog(): String {
        val focalPx = abs(proj[5]) * viewportH.toFloat() * 0.5f
        val hFov = Math.toDegrees((2f * atan(viewportW.toFloat() / (2f * focalPx))).toDouble())
        val vFov = Math.toDegrees((2f * atan(viewportH.toFloat() / (2f * focalPx))).toDouble())
        val mode = when {
            autoFitActive -> "autoFit"
            sourceCamera != null -> "source"
            else -> "bare"
        }
        return "mode=$mode focalPx=%.1f hFov=%.1f vFov=%.1f".format(focalPx, hFov, vFov)
    }

    private fun maybeApplyCameraSafety(m: SplatModel) {
        if (m.count < CAMERA_SAFETY_MIN_SPLATS || viewportW <= 1 || viewportH <= 1) return
        val shouldCheck = when {
            cameraSafetyEvaluatedCount == 0 -> true
            streamComplete && cameraSafetyEvaluatedCount != m.count -> true
            m.count >= cameraSafetyEvaluatedCount * 2 -> true
            else -> false
        }
        if (!shouldCheck) return

        cameraSafetyEvaluatedCount = m.count
        val check = checkRestProjection(m)
        if (check.needsAutoFit) {
            autoFitActive = true
            yaw = 0f
            pitch = 0f
            zoom = SOURCE_REST_ZOOM
            panX = 0f
            panY = 0f
            setAutoFitProjection()
            resetSortUploadState()
            Log.w(
                TAG,
                "cameraSafety photoId=$photoId mode=auto-fit reason=${check.reason} count=${m.count} " +
                    "radius=${m.radius} distance=${autoFitDistance(m)} " +
                    "validCorners=${check.validCorners} ndc=${check.minNdcX},${check.minNdcY}.." +
                    "${check.maxNdcX},${check.maxNdcY} visiblePx=${check.visiblePx.toInt()} " +
                    "bbox=${m.minX},${m.minY},${m.minZ}..${m.maxX},${m.maxY},${m.maxZ}"
            )
        } else {
            Log.i(
                TAG,
                "cameraSafety photoId=$photoId mode=rest count=${m.count} " +
                    "radius=${m.radius} " +
                    "validCorners=${check.validCorners} ndc=${check.minNdcX},${check.minNdcY}.." +
                    "${check.maxNdcX},${check.maxNdcY} visiblePx=${check.visiblePx.toInt()}"
            )
        }
    }

    private fun resetSortUploadState() {
        synchronized(pendingSortLock) {
            pendingSort = null
        }
        hasUploadedInstances = false
        uploadedInstanceCount = 0
        instanceCapacityBytes = 0
        lastSubmittedSortYaw = Float.NaN
        lastSubmittedSortPitch = Float.NaN
        lastUploadedSortYaw = Float.NaN
        lastUploadedSortPitch = Float.NaN
        sortDirty = true
    }

    private fun resetSortForViewChange() {
        synchronized(pendingSortLock) {
            pendingSort = null
        }
        lastSubmittedSortYaw = Float.NaN
        lastSubmittedSortPitch = Float.NaN
        sortDirty = true
    }

    private fun resetGlResourceHandlesForContext() {
        splatProgram = 0
        blitProgram = 0
        rawBlitProgram = 0
        downsampleProgram = 0
        seedFillProgram = 0
        pushFillProgram = 0
        vao = 0
        quadVbo = 0
        instanceVbo = 0
        fbo = 0
        fboTex = 0
        instanceCapacityBytes = 0
        java.util.Arrays.fill(mipTex, 0)
        java.util.Arrays.fill(mipFbo, 0)
        java.util.Arrays.fill(fillTex, 0)
        java.util.Arrays.fill(fillFbo, 0)
    }

    private fun checkRestProjection(m: SplatModel): ProjectionCheck {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var validCorners = 0
        forEachBoundsCorner(m) { x, y, z ->
            pointScratch[0] = x
            pointScratch[1] = y
            pointScratch[2] = z
            pointScratch[3] = 1f
            Matrix.multiplyMV(viewPointScratch, 0, view, 0, pointScratch, 0)
            Matrix.multiplyMV(clipPointScratch, 0, proj, 0, viewPointScratch, 0)
            val w = clipPointScratch[3]
            if (w > 1e-5f) {
                val ndcX = clipPointScratch[0] / w
                val ndcY = clipPointScratch[1] / w
                if (ndcX.isFinite() && ndcY.isFinite()) {
                    minX = min(minX, ndcX)
                    minY = min(minY, ndcY)
                    maxX = max(maxX, ndcX)
                    maxY = max(maxY, ndcY)
                    validCorners++
                }
            }
        }
        if (validCorners == 0) {
            return ProjectionCheck(true, "no-front-facing-bbox-corners", 0f, 0f, 0f, 0f, 0f, 0)
        }

        val clipMinX = max(minX, -1f)
        val clipMinY = max(minY, -1f)
        val clipMaxX = min(maxX, 1f)
        val clipMaxY = min(maxY, 1f)
        val intersectsViewport = clipMaxX > clipMinX && clipMaxY > clipMinY
        val visiblePx = if (intersectsViewport) {
            max(
                (clipMaxX - clipMinX) * 0.5f * viewportW.toFloat(),
                (clipMaxY - clipMinY) * 0.5f * viewportH.toFloat()
            )
        } else {
            0f
        }
        val needsAutoFit = !intersectsViewport || visiblePx < CAMERA_SAFETY_MIN_VISIBLE_PX
        val reason = when {
            !intersectsViewport -> "bbox-off-screen"
            visiblePx < CAMERA_SAFETY_MIN_VISIBLE_PX -> "bbox-too-small"
            else -> "visible"
        }
        return ProjectionCheck(needsAutoFit, reason, minX, minY, maxX, maxY, visiblePx, validCorners)
    }

    private fun forEachBoundsCorner(m: SplatModel, block: (Float, Float, Float) -> Unit) {
        block(m.minX, m.minY, m.minZ)
        block(m.minX, m.minY, m.maxZ)
        block(m.minX, m.maxY, m.minZ)
        block(m.minX, m.maxY, m.maxZ)
        block(m.maxX, m.minY, m.minZ)
        block(m.maxX, m.minY, m.maxZ)
        block(m.maxX, m.maxY, m.minZ)
        block(m.maxX, m.maxY, m.maxZ)
    }

    private fun autoFitDistance(m: SplatModel): Float {
        val halfW = max(0.01f, (m.maxX - m.minX) * 0.5f)
        val halfH = max(0.01f, (m.maxY - m.minY) * 0.5f)
        val halfD = max(0.01f, (m.maxZ - m.minZ) * 0.5f)
        val focal = sourceLikeFocalPx()
        val vFov = 2f * atan(viewportH.toFloat() / (2f * focal))
        val hFov = 2f * atan(viewportW.toFloat() / (2f * focal))
        val distanceForH = halfW / tan(hFov * 0.5f)
        val distanceForV = halfH / tan(vFov * 0.5f)
        return (max(distanceForH, distanceForV) + halfD) * AUTO_FIT_MARGIN
    }

    private fun createGeometry() {
        val vaos = IntArray(1)
        val buffers = IntArray(2)
        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(2, buffers, 0)
        vao = vaos[0]
        quadVbo = buffers[0]
        instanceVbo = buffers[1]

        val quad = floatArrayOf(
            -1f, -1f,
            1f, -1f,
            -1f, 1f,
            1f, 1f
        )
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, quadVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, quad.size * 4, quad.toFloatBuffer(), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * 4, 0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        val stride = INSTANCE_FLOATS * 4
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glVertexAttribDivisor(1, 1)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 4, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glVertexAttribDivisor(2, 1)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 2, GLES30.GL_FLOAT, false, stride, 7 * 4)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 2, GLES30.GL_FLOAT, false, stride, 9 * 4)
        GLES30.glVertexAttribDivisor(4, 1)
        GLES30.glEnableVertexAttribArray(5)
        GLES30.glVertexAttribPointer(5, 3, GLES30.GL_FLOAT, false, stride, 11 * 4)
        GLES30.glVertexAttribDivisor(5, 1)
        GLES30.glBindVertexArray(0)
    }

    private fun createFbo(width: Int, height: Int) {
        if (fboTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(fboTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        }
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        fboTex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )

        GLES30.glGenFramebuffers(1, ids, 0)
        fbo = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            fboTex,
            0
        )
        val statusCode = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(statusCode == GLES30.GL_FRAMEBUFFER_COMPLETE) { "FBO incomplete: 0x${statusCode.toString(16)}" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createPostFbos(width: Int, height: Int) {
        if (mipTex[0] != 0) {
            GLES30.glDeleteTextures(PYRAMID_LEVELS, mipTex, 0)
            GLES30.glDeleteFramebuffers(PYRAMID_LEVELS, mipFbo, 0)
            GLES30.glDeleteTextures(PYRAMID_LEVELS, fillTex, 0)
            GLES30.glDeleteFramebuffers(PYRAMID_LEVELS, fillFbo, 0)
            java.util.Arrays.fill(mipTex, 0)
            java.util.Arrays.fill(mipFbo, 0)
            java.util.Arrays.fill(fillTex, 0)
            java.util.Arrays.fill(fillFbo, 0)
        }

        var w = width
        var h = height
        for (level in 0 until PYRAMID_LEVELS) {
            w = max(1, w / 2)
            h = max(1, h / 2)
            mipW[level] = w
            mipH[level] = h
            mipTex[level] = createRgbaTexture(w, h)
            mipFbo[level] = createFramebuffer(mipTex[level])
            fillTex[level] = createRgbaTexture(w, h)
            fillFbo[level] = createFramebuffer(fillTex[level])
        }
    }

    private fun createRgbaTexture(width: Int, height: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        val tex = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_RGBA,
            width,
            height,
            0,
            GLES30.GL_RGBA,
            GLES30.GL_UNSIGNED_BYTE,
            null
        )
        return tex
    }

    private fun createFramebuffer(tex: Int): Int {
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        val framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex,
            0
        )
        val statusCode = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        check(statusCode == GLES30.GL_FRAMEBUFFER_COMPLETE) { "post FBO incomplete: 0x${statusCode.toString(16)}" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        return framebuffer
    }

    private data class SortResult(
        val buffer: ByteBuffer,
        val byteCount: Int,
        val count: Int,
        val yaw: Float,
        val pitch: Float
    )

    private data class SplatBatch(
        val centers: FloatArray,
        val colors: FloatArray,
        val covariances: FloatArray,
        val count: Int,
        val invalidValueCount: Int,
        val receivedCount: Int,
        val expectedCount: Int,
        val complete: Boolean
    )

    private data class Bounds(
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val maxX: Float,
        val maxY: Float,
        val maxZ: Float,
        val centerX: Float,
        val centerY: Float,
        val centerZ: Float,
        val radius: Float
    )

    private data class ProjectionCheck(
        val needsAutoFit: Boolean,
        val reason: String,
        val minNdcX: Float,
        val minNdcY: Float,
        val maxNdcX: Float,
        val maxNdcY: Float,
        val visiblePx: Float,
        val validCorners: Int
    )

    private data class CaptureRect(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    private fun applyPendingSourceCamera() {
        val camera = pendingSourceCamera ?: return
        pendingSourceCamera = null
        if (sourceCamera?.exact == true) {
            Log.i(TAG, "sourceCameraHeaderIgnored photoId=$photoId existingSource=${sourceCamera?.source}")
            return
        }
        sourceCamera = camera
        autoFitActive = false
        yaw = 0f
        pitch = 0f
        zoom = SOURCE_REST_ZOOM
        panX = 0f
        panY = 0f
        cameraSafetyEvaluatedCount = 0
        if (viewportW > 1 && viewportH > 1) {
            setSourceProjection(camera)
        }
        resetSortUploadState()
        Log.i(
            TAG,
            "sourceCameraApplied photoId=$photoId source=${camera.source} exact=${camera.exact} " +
                "fx=${camera.fx} fy=${camera.fy} cx=${camera.cx} cy=${camera.cy} " +
                "size=${camera.width}x${camera.height}"
        )
    }

    private fun applyPendingStreamBatches() {
        if (streamPhotoId == null) return
        val batches = ArrayList<SplatBatch>()
        synchronized(pendingStreamLock) {
            while (!pendingStreamBatches.isEmpty()) {
                batches += pendingStreamBatches.removeFirst()
            }
        }
        if (batches.isEmpty()) return

        var appended = 0
        var invalid = 0
        var lastReceived = 0
        var lastExpected = streamExpectedCount
        for (batch in batches) {
            if (batch.count > 0) {
                appendSplatBatch(batch)
                appended += batch.count
                invalid += batch.invalidValueCount
            }
            lastReceived = max(lastReceived, batch.receivedCount)
            if (batch.expectedCount > 0) lastExpected = batch.expectedCount
            if (batch.complete) streamComplete = true
        }
        if (appended > 0) {
            sortDirty = true
            val m = model
            status("Streaming $photoId${experimentLabel()} ${m?.count ?: lastReceived}/${lastExpected.takeIf { it > 0 } ?: "?"}")
            Log.i(TAG, "streamAppend photoId=$photoId appended=$appended loaded=${m?.count ?: 0} expected=$lastExpected invalid=$invalid")
        }
        if (streamComplete && !firstStreamFrameLogged) {
            firstStreamFrameLogged = true
            val elapsed = SystemClock.elapsedRealtime() - streamStartMs
            Log.i(TAG, "streamCompleteApplied photoId=$photoId loaded=${model?.count ?: 0} elapsedMs=$elapsed")
        }
    }

    private fun appendSplatBatch(batch: SplatBatch) {
        val current = model ?: emptyModel(assetName)
        val newCount = current.count + batch.count
        val targetCount = streamExpectedCount.takeIf { it >= newCount } ?: newCount
        val centers = ensureFloatCapacity(current.centers, targetCount * 3, current.count * 3)
        val colors = ensureFloatCapacity(current.colors, targetCount * 4, current.count * 4)
        val covariances = ensureFloatCapacity(current.covariances, targetCount * 6, current.count * 6)
        batch.centers.copyInto(centers, current.count * 3)
        batch.colors.copyInto(colors, current.count * 4)
        batch.covariances.copyInto(covariances, current.count * 6)

        val bounds = appendBounds(current, batch)
        model = SplatModel(
            assetName = current.assetName,
            count = newCount,
            centers = centers,
            colors = colors,
            covariances = covariances,
            minX = bounds.minX,
            minY = bounds.minY,
            minZ = bounds.minZ,
            maxX = bounds.maxX,
            maxY = bounds.maxY,
            maxZ = bounds.maxZ,
            centerX = bounds.centerX,
            centerY = bounds.centerY,
            centerZ = bounds.centerZ,
            radius = bounds.radius,
            invalidValueCount = current.invalidValueCount + batch.invalidValueCount
        )
    }

    private fun ensureFloatCapacity(existing: FloatArray, requiredSize: Int, copySize: Int): FloatArray {
        if (existing.size >= requiredSize) return existing
        val next = FloatArray(requiredSize)
        if (copySize > 0) existing.copyInto(next, endIndex = copySize)
        return next
    }

    private fun appendBounds(current: SplatModel, batch: SplatBatch): Bounds {
        if (batch.count <= 0) {
            return Bounds(
                current.minX,
                current.minY,
                current.minZ,
                current.maxX,
                current.maxY,
                current.maxZ,
                current.centerX,
                current.centerY,
                current.centerZ,
                current.radius
            )
        }
        var minX = if (current.count > 0) current.minX else Float.POSITIVE_INFINITY
        var minY = if (current.count > 0) current.minY else Float.POSITIVE_INFINITY
        var minZ = if (current.count > 0) current.minZ else Float.POSITIVE_INFINITY
        var maxX = if (current.count > 0) current.maxX else Float.NEGATIVE_INFINITY
        var maxY = if (current.count > 0) current.maxY else Float.NEGATIVE_INFINITY
        var maxZ = if (current.count > 0) current.maxZ else Float.NEGATIVE_INFINITY
        for (i in 0 until batch.count) {
            val p = i * 3
            val x = batch.centers[p]
            val y = batch.centers[p + 1]
            val z = batch.centers[p + 2]
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        return Bounds(minX, minY, minZ, maxX, maxY, maxZ, cx, cy, cz, max(0.5f, sqrt(dx * dx + dy * dy + dz * dz) * 0.5f))
    }

    private fun uploadPendingSort() {
        val result = synchronized(pendingSortLock) {
            val value = pendingSort
            pendingSort = null
            value
        } ?: return
        uploadInstanceBuffer(result.buffer, result.byteCount)
        lastUploadedSortYaw = result.yaw
        lastUploadedSortPitch = result.pitch
        val countChanged = (model?.count ?: result.count) > result.count
        sortDirty = countChanged ||
            angleDeltaSq(yaw, pitch, lastUploadedSortYaw, lastUploadedSortPitch) > SORT_THRESHOLD_RAD * SORT_THRESHOLD_RAD
    }

    private fun maybeScheduleSort(m: SplatModel) {
        val referenceYaw = if (lastSubmittedSortYaw.isFinite()) lastSubmittedSortYaw else lastUploadedSortYaw
        val referencePitch = if (lastSubmittedSortPitch.isFinite()) lastSubmittedSortPitch else lastUploadedSortPitch
        val needsCountUpload = m.count != uploadedInstanceCount
        if (!needsCountUpload && angleDeltaSq(yaw, pitch, referenceYaw, referencePitch) < SORT_THRESHOLD_RAD * SORT_THRESHOLD_RAD) {
            sortDirty = false
            return
        }
        if (!sortRunning.compareAndSet(false, true)) {
            return
        }
        if (synchronized(pendingSortLock) { pendingSort != null }) {
            sortRunning.set(false)
            return
        }

        val viewSnapshot = view.copyOf()
        val targetYaw = yaw
        val targetPitch = pitch
        lastSubmittedSortYaw = targetYaw
        lastSubmittedSortPitch = targetPitch
        sortDirty = false

        sortExecutor.execute {
            val startNs = System.nanoTime()
            val result = buildInstanceBuffer(m, viewSnapshot, targetYaw, targetPitch)
            recordSortTime(System.nanoTime() - startNs)
            synchronized(pendingSortLock) {
                pendingSort = result
            }
            sortRunning.set(false)
        }
    }

    private fun angleDeltaSq(yawA: Float, pitchA: Float, yawB: Float, pitchB: Float): Float {
        if (!yawB.isFinite() || !pitchB.isFinite()) return Float.POSITIVE_INFINITY
        val dy = yawA - yawB
        val dp = pitchA - pitchB
        return dy * dy + dp * dp
    }

    private fun recordSortTime(ns: Long) {
        synchronized(statsLock) {
            statsSortCount++
            statsSortNs += ns
        }
    }

    private fun startSplatStream(streamId: String) {
        streamExecutor.execute {
            val densityParam = requestedStreamDensity
                ?.let { "&density=${URLEncoder.encode(it, "UTF-8")}" }
                .orEmpty()
            val url = splatStreamUrl
                ?.takeIf { it.isNotBlank() }
                ?.let { appendDensityIfNeeded(it, requestedStreamDensity) }
                ?: "${splatStreamEndpointUrl ?: SPLAT_STREAM_URL}?photoId=${URLEncoder.encode(streamId, "UTF-8")}$densityParam"
            val cacheKey = SplatCache.keyFor(streamId, requestedStreamDensity ?: "backend-default")
            SplatCache.lookup(context, cacheKey, SPLAT_ROW_BYTES)?.let { entry ->
                loadCachedSplat(streamId, entry)
                return@execute
            }
            Log.i(
                TAG,
                "splatCacheMiss photoId=$streamId key=$cacheKey capBytes=$splatCacheMaxBytes " +
                    "density=${requestedStreamDensity ?: "default"}"
            )
            val started = SystemClock.elapsedRealtime()
            if (!networkStreamEnabled) {
                streamError = "cached splat missing"
                status("Cached splat missing for $streamId")
                streamErrorCallback("cached splat missing")
                Log.w(TAG, "splatCacheRequiredMiss photoId=$streamId key=$cacheKey networkStreamEnabled=false")
                return@execute
            }
            val localIngestUri = ingestImageUri
            if (localIngestUri != null) {
                streamIngestSplat(streamId, cacheKey, localIngestUri, started)
                return@execute
            }
            Log.i(TAG, "streamStart photoId=$streamId density=${requestedStreamDensity ?: "default"} footprint=$renderFootprintScale url=$url")
            var conn: HttpURLConnection? = null
            var tmpCacheFile: File? = null
            var cacheComplete = false
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 30_000
                }
                streamConnection = conn
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw RuntimeException("HTTP $code")
                }
                val rowBytes = conn.getHeaderField("X-Splat-Stream-Row-Bytes")?.toIntOrNull() ?: SPLAT_ROW_BYTES
                require(rowBytes == SPLAT_ROW_BYTES) { "unexpected row bytes $rowBytes" }
                val expected = conn.getHeaderField("X-Splat-Stream-Num-Gaussians")?.toIntOrNull() ?: 0
                streamExpectedCount = expected
                sourceCameraFromHeaders(conn)?.let { headerCamera ->
                    if (sourceCamera?.exact == true) {
                        Log.i(TAG, "streamCameraHeaderPresent photoId=$streamId ignored existingSource=${sourceCamera?.source}")
                    } else {
                        pendingSourceCamera = headerCamera
                        Log.i(
                            TAG,
                            "streamCameraHeader photoId=$streamId source=${headerCamera.source} " +
                                "fx=${headerCamera.fx} fy=${headerCamera.fy} cx=${headerCamera.cx} " +
                                "cy=${headerCamera.cy} size=${headerCamera.width}x${headerCamera.height}"
                        )
                    }
                }
                status("Streaming $streamId${experimentLabel()} 0/${expected.takeIf { it > 0 } ?: "?"}")

                val tmp = SplatCache.tempFile(context, cacheKey)
                tmpCacheFile = tmp
                val receivedRecords = tmp.outputStream().use { cacheOut ->
                    conn.inputStream.use { input ->
                        consumeSplatInput(input, expected) { bytes, count ->
                            cacheOut.write(bytes, 0, count)
                        }
                    }
                }
                if (!streamCancelled.get()) {
                    enqueueStreamBatch(SplatBatch(FloatArray(0), FloatArray(0), FloatArray(0), 0, 0, receivedRecords, expected, complete = true))
                    val elapsed = SystemClock.elapsedRealtime() - started
                    status("Streamed $streamId${experimentLabel()} $receivedRecords/${expected.takeIf { it > 0 } ?: "?"} in ${elapsed}ms")
                    Log.i(TAG, "streamFinished photoId=$streamId density=${requestedStreamDensity ?: "default"} received=$receivedRecords expected=$expected elapsedMs=$elapsed")
                    val store = SplatCache.commit(context, cacheKey, tmp, splatCacheMaxBytes)
                    cacheComplete = store.stored
                    Log.i(
                        TAG,
                        "splatCacheStored photoId=$streamId key=$cacheKey stored=${store.stored} " +
                            "bytes=${store.bytes} totalBytes=${store.totalBytes} " +
                            "evicted=${store.evictedCount}/${store.evictedBytes} reason=${store.reason ?: "-"}"
                    )
                }
            } catch (exc: Exception) {
                if (!streamCancelled.get()) {
                    streamError = exc.message ?: exc.javaClass.simpleName
                    Log.e(TAG, "streamFailed photoId=$streamId", exc)
                    status("Stream failed: ${streamError}")
                    streamErrorCallback(streamError ?: "stream failed")
                }
            } finally {
                if (!cacheComplete) tmpCacheFile?.delete()
                conn?.disconnect()
                if (streamConnection === conn) streamConnection = null
            }
        }
    }

    private fun streamIngestSplat(streamId: String, cacheKey: String, imageUriString: String, started: Long) {
        Log.i(TAG, "ingestStreamStart photoId=$streamId key=$cacheKey uri=$imageUriString footprint=$renderFootprintScale")
        status("Reframing $streamId${experimentLabel()}...")
        var conn: HttpURLConnection? = null
        var tmpCacheFile: File? = null
        var cacheComplete = false
        var uploadBytes = -1L
        var responseWaitMs = -1L
        var downloadMs = -1L
        try {
            val upload = buildIngestUploadPayload(streamId, imageUriString)
            uploadBytes = upload.bytes.size.toLong()
            val boundary = "----WiciAndroidRenderer${SystemClock.elapsedRealtimeNanos()}"
            conn = (URL(ingestEndpointUrl ?: INGEST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8_000
                readTimeout = 300_000
                doOutput = true
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                setRequestProperty("Accept", "application/octet-stream")
                setRequestProperty(INGEST_SPLAT_ACCEPT_HEADER, "$INGEST_SPLAT_FORMAT_FP16_V1,gzip")
                setRequestProperty(INGEST_SPLAT_STREAM_HEADER, "chunked")
            }
            streamConnection = conn
            conn.outputStream.use { out ->
                writeMultipartText(out, boundary, "photoId", streamId)
                writeMultipartText(out, boundary, "title", streamId)
                out.writeUtf8("--$boundary\r\n")
                out.writeUtf8("Content-Disposition: form-data; name=\"image\"; filename=\"${upload.filename}\"\r\n")
                out.writeUtf8("Content-Type: ${upload.mime}\r\n\r\n")
                out.write(upload.bytes)
                out.writeUtf8("\r\n--$boundary--\r\n")
            }
            val waitStart = SystemClock.elapsedRealtime()
            val code = conn.responseCode
            responseWaitMs = SystemClock.elapsedRealtime() - waitStart
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
                throw RuntimeException("HTTP $code: ${text.take(220)}")
            }

            val responseFormat = conn.getHeaderField("X-Splat-Format") ?: SplatCache.FORMAT_SPLAT
            val responseEncoding = conn.getHeaderField("X-Splat-Encoding") ?: "identity"
            val rowBytes = conn.getHeaderField("X-Splat-Row-Bytes")?.toIntOrNull()
                ?: if (responseFormat == SplatCache.FORMAT_FP16_V1) SplatCache.FP16_ROW_BYTES else SPLAT_ROW_BYTES
            val expected = conn.getHeaderField("X-Splat-Num-Gaussians")?.toIntOrNull() ?: 0
            val wireBytes = conn.getHeaderField("X-Splat-Size-Bytes")?.toLongOrNull()
                ?: conn.getHeaderField("Content-Length")?.toLongOrNull()
                ?: -1L
            val payloadBytes = conn.getHeaderField("X-Splat-Payload-Size-Bytes")?.toLongOrNull() ?: -1L
            val fp32Bytes = conn.getHeaderField("X-Splat-Fp32-Size-Bytes")?.toLongOrNull() ?: -1L
            streamExpectedCount = expected
            sourceCameraFromHeaders(conn)?.let { headerCamera ->
                pendingSourceCamera = headerCamera
                Log.i(
                    TAG,
                    "ingestStreamCameraHeader photoId=$streamId source=${headerCamera.source} " +
                        "fx=${headerCamera.fx} fy=${headerCamera.fy} cx=${headerCamera.cx} cy=${headerCamera.cy} " +
                        "size=${headerCamera.width}x${headerCamera.height}"
                )
            }
            status("Streaming $streamId${experimentLabel()} 0/${expected.takeIf { it > 0 } ?: "?"}")

            val tmp = SplatCache.tempFile(context, cacheKey)
            tmpCacheFile = tmp
            val downloadStart = SystemClock.elapsedRealtime()
            val receivedRecords = tmp.outputStream().use { cacheOut ->
                requireNotNull(stream) { "ingest response body missing" }.use { rawInput ->
                    val body = if (responseEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(rawInput) else rawInput
                    body.use { decoded ->
                        if (responseFormat == SplatCache.FORMAT_FP16_V1) {
                            val header = readFully(decoded, SplatCache.FP16_HEADER_BYTES)
                            val compactRecords = validateCompactSplatHeader(header)
                            if (expected > 0 && compactRecords != expected) {
                                Log.w(TAG, "ingestStreamRecordHeaderMismatch photoId=$streamId expected=$expected compact=$compactRecords")
                            }
                            cacheOut.write(header)
                            consumeSplatInput(decoded, compactRecords, rowBytes = SplatCache.FP16_ROW_BYTES, format = SplatCache.FORMAT_FP16_V1) { bytes, count ->
                                cacheOut.write(bytes, 0, count)
                            }
                        } else {
                            require(rowBytes == SPLAT_ROW_BYTES) { "unexpected ingest row bytes $rowBytes for format=$responseFormat" }
                            consumeSplatInput(decoded, expected, rowBytes = SPLAT_ROW_BYTES, format = SplatCache.FORMAT_SPLAT) { bytes, count ->
                                cacheOut.write(bytes, 0, count)
                            }
                        }
                    }
                }
            }
            downloadMs = SystemClock.elapsedRealtime() - downloadStart
            if (!streamCancelled.get()) {
                enqueueStreamBatch(SplatBatch(FloatArray(0), FloatArray(0), FloatArray(0), 0, 0, receivedRecords, expected.takeIf { it > 0 } ?: receivedRecords, complete = true))
                val elapsed = SystemClock.elapsedRealtime() - started
                status("Streamed $streamId${experimentLabel()} $receivedRecords/${expected.takeIf { it > 0 } ?: "?"} in ${elapsed}ms")
                Log.i(
                    TAG,
                    "ingestStreamFinished photoId=$streamId responseFormat=$responseFormat responseEncoding=$responseEncoding " +
                        "received=$receivedRecords expected=$expected wireBytes=$wireBytes payloadBytes=$payloadBytes " +
                        "fp32Bytes=$fp32Bytes uploadBytes=$uploadBytes responseWaitMs=$responseWaitMs " +
                        "downloadMs=$downloadMs elapsedMs=$elapsed"
                )
                val store = SplatCache.commit(context, cacheKey, tmp, splatCacheMaxBytes)
                cacheComplete = store.stored
                Log.i(
                    TAG,
                    "splatCacheStored photoId=$streamId key=$cacheKey stored=${store.stored} " +
                        "bytes=${store.bytes} totalBytes=${store.totalBytes} " +
                        "evicted=${store.evictedCount}/${store.evictedBytes} reason=${store.reason ?: "-"}"
                )
            }
        } catch (exc: Exception) {
            if (!streamCancelled.get()) {
                streamError = exc.message ?: exc.javaClass.simpleName
                Log.e(TAG, "ingestStreamFailed photoId=$streamId", exc)
                status("Reframe failed: ${streamError}")
                streamErrorCallback(streamError ?: "reframe failed")
            }
        } finally {
            if (!cacheComplete) tmpCacheFile?.delete()
            conn?.disconnect()
            if (streamConnection === conn) streamConnection = null
        }
    }

    private data class IngestUploadPayload(
        val bytes: ByteArray,
        val mime: String,
        val filename: String
    )

    private fun buildIngestUploadPayload(streamId: String, imageUriString: String): IngestUploadPayload {
        val imageUri = Uri.parse(imageUriString)
        val sourceMime = context.contentResolver.getType(imageUri) ?: "image/jpeg"
        var bitmap: Bitmap? = null
        try {
            val source = ImageDecoder.createSource(context.contentResolver, imageUri)
            bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = false
                val w = info.size.width
                val h = info.size.height
                val maxSide = max(w, h)
                if (maxSide > INGEST_UPLOAD_MAX_SIDE) {
                    val scale = INGEST_UPLOAD_MAX_SIDE.toFloat() / maxSide.toFloat()
                    decoder.setTargetSize(max(1, (w * scale).toInt()), max(1, (h * scale).toInt()))
                }
            }
            val bytes = ByteArrayOutputStream()
            val ok = bitmap.compress(Bitmap.CompressFormat.JPEG, INGEST_UPLOAD_JPEG_QUALITY, bytes)
            if (!ok) throw RuntimeException("JPEG compress returned false")
            val payload = bytes.toByteArray()
            Log.i(
                TAG,
                "ingestStreamUploadDownscaled photoId=$streamId " +
                    "bitmap=${bitmap.width}x${bitmap.height} bytes=${payload.size}"
            )
            return IngestUploadPayload(
                bytes = payload,
                mime = "image/jpeg",
                filename = "android-import-${System.currentTimeMillis()}.jpg"
            )
        } catch (exc: Exception) {
            Log.w(
                TAG,
                "ingestStreamUploadDownscaleFailed photoId=$streamId; falling back to raw upload: ${exc.message}",
                exc
            )
            val payload = context.contentResolver.openInputStream(imageUri).use { input ->
                requireNotNull(input) { "openInputStream returned null" }
                input.readBytes()
            }
            return IngestUploadPayload(
                bytes = payload,
                mime = sourceMime,
                filename = "android-import-${System.currentTimeMillis()}.${extensionForMime(sourceMime)}"
            )
        } finally {
            bitmap?.recycle()
        }
    }

    private fun writeMultipartText(out: OutputStream, boundary: String, name: String, value: String) {
        out.writeUtf8("--$boundary\r\n")
        out.writeUtf8("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeUtf8(value)
        out.writeUtf8("\r\n")
    }

    private fun OutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun readFully(input: InputStream, byteCount: Int): ByteArray {
        val out = ByteArray(byteCount)
        var offset = 0
        while (offset < byteCount) {
            val n = input.read(out, offset, byteCount - offset)
            if (n < 0) throw RuntimeException("unexpected EOF while reading $byteCount-byte compact splat header")
            offset += n
        }
        return out
    }

    private fun validateCompactSplatHeader(header: ByteArray): Int {
        require(header.size == SplatCache.FP16_HEADER_BYTES) { "bad compact splat header size ${header.size}" }
        require(header.copyOfRange(0, FP16_MAGIC.size).contentEquals(FP16_MAGIC)) { "bad compact splat magic" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(FP16_MAGIC.size)
        val version = buffer.int
        val rowBytes = buffer.int
        val records = buffer.int
        buffer.int // flags
        require(version == 1) { "unsupported compact splat version $version" }
        require(rowBytes == SplatCache.FP16_ROW_BYTES) { "unexpected compact splat row bytes $rowBytes" }
        require(records > 0) { "empty compact splat payload" }
        return records
    }

    private fun loadCachedSplat(streamId: String, entry: SplatCache.Entry) {
        val started = SystemClock.elapsedRealtime()
        streamExpectedCount = entry.records
        status("Loading cached $streamId${experimentLabel()} 0/${entry.records}")
        Log.i(
            TAG,
            "splatCacheHit photoId=$streamId key=${entry.key} bytes=${entry.bytes} " +
                "records=${entry.records} format=${entry.format} rowBytes=${entry.rowBytes} " +
                "capBytes=$splatCacheMaxBytes"
        )
        try {
            val receivedRecords = entry.file.inputStream().use { input ->
                skipFully(input, entry.headerBytes.toLong())
                consumeSplatInput(input, entry.records, rowBytes = entry.rowBytes, format = entry.format)
            }
            if (!streamCancelled.get()) {
                enqueueStreamBatch(SplatBatch(FloatArray(0), FloatArray(0), FloatArray(0), 0, 0, receivedRecords, entry.records, complete = true))
                val elapsed = SystemClock.elapsedRealtime() - started
                status("Loaded cached $streamId${experimentLabel()} $receivedRecords/${entry.records} in ${elapsed}ms")
                Log.i(
                    TAG,
                    "splatCacheLoadFinished photoId=$streamId key=${entry.key} " +
                        "received=$receivedRecords expected=${entry.records} elapsedMs=$elapsed"
                )
            }
        } catch (exc: Exception) {
            if (!streamCancelled.get()) {
                streamError = exc.message ?: exc.javaClass.simpleName
                Log.e(TAG, "splatCacheLoadFailed photoId=$streamId key=${entry.key}", exc)
                status("Cache load failed: ${streamError}")
            }
        }
    }

    private fun consumeSplatInput(
        input: InputStream,
        expected: Int,
        rowBytes: Int = SPLAT_ROW_BYTES,
        format: String = SplatCache.FORMAT_SPLAT,
        onChunk: ((ByteArray, Int) -> Unit)? = null
    ): Int {
        val batchBytes = STREAM_BATCH_RECORDS * rowBytes
        val batchBuffer = ByteArray(batchBytes)
        val readBuffer = ByteArray(STREAM_READ_BYTES)
        var batchByteCount = 0
        var receivedRecords = 0
        while (!streamCancelled.get()) {
            val n = input.read(readBuffer)
            if (n < 0) break
            onChunk?.invoke(readBuffer, n)
            var offset = 0
            while (offset < n) {
                val take = min(n - offset, batchBytes - batchByteCount)
                System.arraycopy(readBuffer, offset, batchBuffer, batchByteCount, take)
                batchByteCount += take
                offset += take
                if (batchByteCount == batchBytes) {
                    val batch = parseSplatBatch(batchBuffer, batchByteCount, receivedRecords + STREAM_BATCH_RECORDS, expected, complete = false, rowBytes = rowBytes, format = format)
                    receivedRecords += batch.count
                    enqueueStreamBatch(batch)
                    batchByteCount = 0
                }
            }
        }
        if (!streamCancelled.get() && batchByteCount > 0) {
            if (batchByteCount % rowBytes != 0) {
                throw RuntimeException("partial trailing splat bytes: $batchByteCount rowBytes=$rowBytes")
            }
            val records = batchByteCount / rowBytes
            val batch = parseSplatBatch(batchBuffer, batchByteCount, receivedRecords + records, expected, complete = false, rowBytes = rowBytes, format = format)
            receivedRecords += batch.count
            enqueueStreamBatch(batch)
        }
        return receivedRecords
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
            } else if (input.read() >= 0) {
                remaining--
            } else {
                throw RuntimeException("unexpected EOF while skipping $bytes-byte splat header")
            }
        }
    }

    private fun enqueueStreamBatch(batch: SplatBatch) {
        synchronized(pendingStreamLock) {
            pendingStreamBatches.add(batch)
        }
    }

    private fun parseSplatBatch(
        bytes: ByteArray,
        byteCount: Int,
        receivedCount: Int,
        expectedCount: Int,
        complete: Boolean,
        rowBytes: Int = SPLAT_ROW_BYTES,
        format: String = SplatCache.FORMAT_SPLAT
    ): SplatBatch {
        val count = byteCount / rowBytes
        val centers = FloatArray(count * 3)
        val colors = FloatArray(count * 4)
        val covariances = FloatArray(count * 6)
        val buffer = ByteBuffer.wrap(bytes, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN)
        var invalid = 0
        var out = 0
        for (i in 0 until count) {
            val compact = format == SplatCache.FORMAT_FP16_V1
            val rawX: Float
            val rawY: Float
            val rawZ: Float
            val rawSx: Float
            val rawSy: Float
            val rawSz: Float
            if (compact) {
                rawX = Half.toFloat(buffer.short)
                rawY = Half.toFloat(buffer.short)
                rawZ = Half.toFloat(buffer.short)
                rawSx = Half.toFloat(buffer.short)
                rawSy = Half.toFloat(buffer.short)
                rawSz = Half.toFloat(buffer.short)
            } else {
                rawX = buffer.float
                rawY = buffer.float
                rawZ = buffer.float
                rawSx = buffer.float
                rawSy = buffer.float
                rawSz = buffer.float
            }
            if (!rawX.isFinite()) invalid++
            if (!rawY.isFinite()) invalid++
            if (!rawZ.isFinite()) invalid++
            if (!rawSx.isFinite()) invalid++
            if (!rawSy.isFinite()) invalid++
            if (!rawSz.isFinite()) invalid++
            val x = finiteOr(rawX, 0f)
            val y = finiteOr(rawY, 0f)
            val z = finiteOr(rawZ, 0f)
            val sx = finiteOr(rawSx, DEFAULT_DIRECT_SCALE).coerceAtLeast(MIN_DIRECT_SCALE)
            val sy = finiteOr(rawSy, DEFAULT_DIRECT_SCALE).coerceAtLeast(MIN_DIRECT_SCALE)
            val sz = finiteOr(rawSz, DEFAULT_DIRECT_SCALE).coerceAtLeast(MIN_DIRECT_SCALE)
            val r = buffer.get().toInt() and 0xff
            val g = buffer.get().toInt() and 0xff
            val b = buffer.get().toInt() and 0xff
            val a = buffer.get().toInt() and 0xff
            val qw = ((buffer.get().toInt() and 0xff) - 128) / 128f
            val qx = ((buffer.get().toInt() and 0xff) - 128) / 128f
            val qy = ((buffer.get().toInt() and 0xff) - 128) / 128f
            val qz = ((buffer.get().toInt() and 0xff) - 128) / 128f

            // Match the web GaussianSplats3D splatAlphaRemovalThreshold (=5): drop near-transparent
            // splats. The hand-rolled renderer otherwise floors every alpha to 0.02 and draws even
            // a=0 splats, so the faint peripheral splats accumulate into a low-quality smear under
            // orbit. Dropping them leaves that region empty so it reads as a hole -> peripheral mask
            // -> flux regenerates it cleanly, matching the web.
            if (a < SPLAT_ALPHA_REMOVAL_THRESHOLD) continue

            val p = out * 3
            centers[p] = x
            centers[p + 1] = y
            centers[p + 2] = z
            val c = out * 4
            colors[c] = r / 255f
            colors[c + 1] = g / 255f
            colors[c + 2] = b / 255f
            colors[c + 3] = (a / 255f).coerceIn(0.02f, 0.98f)
            writeCovarianceFromDirectScales(covariances, out * 6, sx, sy, sz, qw, qx, qy, qz)
            out++
        }
        return if (out == count) {
            SplatBatch(centers, colors, covariances, count, invalid, receivedCount, expectedCount, complete)
        } else {
            SplatBatch(
                centers.copyOf(out * 3),
                colors.copyOf(out * 4),
                covariances.copyOf(out * 6),
                out,
                invalid,
                receivedCount,
                expectedCount,
                complete
            )
        }
    }

    private fun finiteOr(value: Float, fallback: Float): Float =
        if (value.isFinite()) value else fallback

    private fun emptyModel(name: String): SplatModel =
        SplatModel(
            assetName = name,
            count = 0,
            centers = FloatArray(0),
            colors = FloatArray(0),
            covariances = FloatArray(0),
            minX = 0f,
            minY = 0f,
            minZ = SOURCE_LOOK_AT_Z,
            maxX = 0f,
            maxY = 0f,
            maxZ = SOURCE_LOOK_AT_Z,
            centerX = 0f,
            centerY = 0f,
            centerZ = SOURCE_LOOK_AT_Z,
            radius = 1f,
            invalidValueCount = 0
        )

    private fun computeBounds(centers: FloatArray, count: Int): Bounds {
        if (count <= 0) {
            return Bounds(
                0f,
                0f,
                SOURCE_LOOK_AT_Z,
                0f,
                0f,
                SOURCE_LOOK_AT_Z,
                0f,
                0f,
                SOURCE_LOOK_AT_Z,
                1f
            )
        }
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (i in 0 until count) {
            val p = i * 3
            val x = centers[p]
            val y = centers[p + 1]
            val z = centers[p + 2]
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)
        }
        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        val radius = max(0.5f, sqrt(dx * dx + dy * dy + dz * dz) * 0.5f)
        return Bounds(minX, minY, minZ, maxX, maxY, maxZ, cx, cy, cz, radius)
    }

    private fun streamLabel(m: SplatModel): String {
        val error = streamError
        if (error != null) return "${m.count} | stream failed: $error"
        val expected = streamExpectedCount
        return if (streamPhotoId != null && expected > 0 && !streamComplete) {
            "${m.count}/$expected streaming"
        } else {
            "${m.count}"
        }
    }

    private fun experimentLabel(): String {
        val density = requestedStreamDensity
        val footprint = renderFootprintScale
        return if (density == null && footprint == FOOTPRINT_SCALE) {
            ""
        } else {
            " density=${density ?: "default"} fp=%.2f".format(footprint)
        }
    }

    private fun isProgressiveLoadInFlight(): Boolean =
        streamPhotoId != null && !streamComplete

    private fun uploadInstanceBuffer(buffer: ByteBuffer, byteCount: Int) {
        buffer.position(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        if (byteCount > instanceCapacityBytes) {
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, byteCount, buffer, GLES30.GL_DYNAMIC_DRAW)
            instanceCapacityBytes = byteCount
        } else {
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, byteCount, buffer)
        }
        uploadedInstanceCount = byteCount / (INSTANCE_FLOATS * 4)
    }

    private fun reusableInstanceBuildBuffer(byteCount: Int): ByteBuffer {
        val expectedCount = streamExpectedCount.takeIf { it > 0 } ?: model?.count ?: 0
        val targetBytes = max(byteCount, expectedCount * INSTANCE_FLOATS * 4)
        synchronized(instanceBuildBufferLock) {
            val existing = instanceBuildBuffer
            if (existing == null || instanceBuildBufferCapacityBytes < targetBytes) {
                instanceBuildBuffer = ByteBuffer.allocateDirect(targetBytes).order(ByteOrder.nativeOrder())
                instanceBuildBufferCapacityBytes = targetBytes
                Log.i(TAG, "instanceBuildBufferAllocated capacityBytes=$targetBytes")
            }
            return requireNotNull(instanceBuildBuffer)
        }
    }

    private fun buildInstanceBuffer(m: SplatModel, sortView: FloatArray, sortYaw: Float, sortPitch: Float): SortResult {
        val order = IntArray(m.count) { it }
        val depth = FloatArray(m.count)
        for (i in 0 until m.count) {
            val p = i * 3
            depth[i] = sortView[2] * m.centers[p] + sortView[6] * m.centers[p + 1] + sortView[10] * m.centers[p + 2] + sortView[14]
        }
        quickSort(order, depth, 0, m.count - 1)

        val byteCount = m.count * INSTANCE_FLOATS * 4
        val buffer = reusableInstanceBuildBuffer(byteCount)
        buffer.clear()
        val fx = proj[0] * viewportW * 0.5f
        val fy = proj[5] * viewportH * 0.5f

        val vr00 = sortView[0]
        val vr01 = sortView[4]
        val vr02 = sortView[8]
        val vr10 = sortView[1]
        val vr11 = sortView[5]
        val vr12 = sortView[9]
        val vr20 = sortView[2]
        val vr21 = sortView[6]
        val vr22 = sortView[10]
        val footprintScale = renderFootprintScale

        for (rank in 0 until m.count) {
            val i = order[rank]
            val p = i * 3
            val c = i * 4
            val cov = i * 6
            val cx = m.centers[p]
            val cy = m.centers[p + 1]
            val cz = m.centers[p + 2]
            val tx = sortView[0] * cx + sortView[4] * cy + sortView[8] * cz + sortView[12]
            val ty = sortView[1] * cx + sortView[5] * cy + sortView[9] * cz + sortView[13]
            val tz = sortView[2] * cx + sortView[6] * cy + sortView[10] * cz + sortView[14]
            projectEllipse(ellipseScratch, m, cov, tx, ty, tz, fx, fy, footprintScale, vr00, vr01, vr02, vr10, vr11, vr12, vr20, vr21, vr22)
            buffer.putFloat(m.centers[p])
            buffer.putFloat(m.centers[p + 1])
            buffer.putFloat(m.centers[p + 2])
            buffer.putFloat(m.colors[c])
            buffer.putFloat(m.colors[c + 1])
            buffer.putFloat(m.colors[c + 2])
            buffer.putFloat(m.colors[c + 3])
            buffer.putFloat(ellipseScratch[0])
            buffer.putFloat(ellipseScratch[1])
            buffer.putFloat(ellipseScratch[2])
            buffer.putFloat(ellipseScratch[3])
            buffer.putFloat(ellipseScratch[4])
            buffer.putFloat(ellipseScratch[5])
            buffer.putFloat(ellipseScratch[6])
        }
        buffer.flip()
        return SortResult(buffer, byteCount, m.count, sortYaw, sortPitch)
    }

    private fun drawSplats(count: Int) {
        GLES30.glUseProgram(splatProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(splatProgram, "uView"), 1, false, view, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(splatProgram, "uProj"), 1, false, proj, 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(splatProgram, "uViewport"), viewportW.toFloat(), viewportH.toFloat())
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, count)
        GLES30.glBindVertexArray(0)
    }

    private fun projectEllipse(
        out: FloatArray,
        m: SplatModel,
        cov: Int,
        tx: Float,
        ty: Float,
        tz: Float,
        fx: Float,
        fy: Float,
        footprintScale: Float,
        r00: Float,
        r01: Float,
        r02: Float,
        r10: Float,
        r11: Float,
        r12: Float,
        r20: Float,
        r21: Float,
        r22: Float
    ) {
        if (tz > -0.02f) {
            out[0] = 0f
            out[1] = 0f
            out[2] = 0f
            out[3] = 0f
            out[4] = 1f
            out[5] = 0f
            out[6] = 1f
            return
        }

        val cxx = m.covariances[cov]
        val cxy = m.covariances[cov + 1]
        val cxz = m.covariances[cov + 2]
        val cyy = m.covariances[cov + 3]
        val cyz = m.covariances[cov + 4]
        val czz = m.covariances[cov + 5]

        fun covRow(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float): Float {
            val cbx = cxx * bx + cxy * by + cxz * bz
            val cby = cxy * bx + cyy * by + cyz * bz
            val cbz = cxz * bx + cyz * by + czz * bz
            return ax * cbx + ay * cby + az * cbz
        }

        val v00 = covRow(r00, r01, r02, r00, r01, r02)
        val v01 = covRow(r00, r01, r02, r10, r11, r12)
        val v02 = covRow(r00, r01, r02, r20, r21, r22)
        val v11 = covRow(r10, r11, r12, r10, r11, r12)
        val v12 = covRow(r10, r11, r12, r20, r21, r22)
        val v22 = covRow(r20, r21, r22, r20, r21, r22)

        val z = max(0.02f, -tz)
        val invZ = 1f / z
        val invZ2 = invZ * invZ
        val j00 = fx * invZ
        val j02 = fx * tx * invZ2
        val j11 = fy * invZ
        val j12 = fy * ty * invZ2

        val footprintScale2 = footprintScale * footprintScale
        var a = (j00 * j00 * v00 + 2f * j00 * j02 * v02 + j02 * j02 * v22) * footprintScale2
        var b = (j00 * j11 * v01 + j00 * j12 * v02 + j02 * j11 * v12 + j02 * j12 * v22) * footprintScale2
        var c = (j11 * j11 * v11 + 2f * j11 * j12 * v12 + j12 * j12 * v22) * footprintScale2

        a += LOW_PASS_VARIANCE
        c += LOW_PASS_VARIANCE

        var det = a * c - b * b
        if (!det.isFinite() || det < 1e-6f || !a.isFinite() || !b.isFinite() || !c.isFinite()) {
            a = 4f
            b = 0f
            c = 4f
            det = 16f
        }

        val conicA = c / det
        val conicB = -b / det
        val conicC = a / det

        val mid = 0.5f * (a + c)
        val diff = 0.5f * (a - c)
        val root = sqrt(max(0f, diff * diff + b * b))
        val lambda0 = max(MIN_VARIANCE, mid + root)
        val lambda1 = max(MIN_VARIANCE, mid - root)

        var vx: Float
        var vy: Float
        if (abs(b) > 1e-5f) {
            vx = b
            vy = lambda0 - a
        } else if (a >= c) {
            vx = 1f
            vy = 0f
        } else {
            vx = 0f
            vy = 1f
        }
        val len = sqrt(max(1e-10f, vx * vx + vy * vy))
        vx /= len
        vy /= len

        val radius0 = min(MAX_PIXEL_RADIUS, SIGMA_EXTENT * sqrt(lambda0))
        val radius1 = min(MAX_PIXEL_RADIUS, SIGMA_EXTENT * sqrt(lambda1))
        out[0] = vx * radius0
        out[1] = vy * radius0
        out[2] = -vy * radius1
        out[3] = vx * radius1
        out[4] = conicA
        out[5] = conicB
        out[6] = conicC
    }

    private fun runPushPullFill() {
        GLES30.glDisable(GLES30.GL_BLEND)

        var sourceTex = fboTex
        var sourceW = viewportW
        var sourceH = viewportH
        for (level in 0 until PYRAMID_LEVELS) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, mipFbo[level])
            GLES30.glViewport(0, 0, mipW[level], mipH[level])
            GLES30.glUseProgram(downsampleProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(downsampleProgram, "uTex"), 0)
            GLES30.glUniform2f(
                GLES30.glGetUniformLocation(downsampleProgram, "uTexel"),
                1f / sourceW.toFloat(),
                1f / sourceH.toFloat()
            )
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            sourceTex = mipTex[level]
            sourceW = mipW[level]
            sourceH = mipH[level]
        }

        val last = PYRAMID_LEVELS - 1
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fillFbo[last])
        GLES30.glViewport(0, 0, mipW[last], mipH[last])
        GLES30.glUseProgram(seedFillProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mipTex[last])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(seedFillProgram, "uTex"), 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        for (level in last - 1 downTo 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fillFbo[level])
            GLES30.glViewport(0, 0, mipW[level], mipH[level])
            GLES30.glUseProgram(pushFillProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, mipTex[level])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(pushFillProgram, "uFine"), 0)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fillTex[level + 1])
            GLES30.glUniform1i(GLES30.glGetUniformLocation(pushFillProgram, "uCoarse"), 1)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
    }

    private fun drawComposite() {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(blitProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fillTex[0])
        GLES30.glUniform1i(GLES30.glGetUniformLocation(blitProgram, "uFill"), 1)
        GLES30.glUniform2f(
            GLES30.glGetUniformLocation(blitProgram, "uFullTexel"),
            1f / viewportW.toFloat(),
            1f / viewportH.toFloat()
        )
        GLES30.glUniform1f(GLES30.glGetUniformLocation(blitProgram, "uDilationPx"), FINAL_DILATION_PX)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uContentMin"), contentMinX, contentMinY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(blitProgram, "uContentMax"), contentMaxX, contentMaxY)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun drawRawBlit() {
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(rawBlitProgram)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTex)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(rawBlitProgram, "uTex"), 0)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(rawBlitProgram, "uContentMin"), contentMinX, contentMinY)
        GLES30.glUniform2f(GLES30.glGetUniformLocation(rawBlitProgram, "uContentMax"), contentMaxX, contentMaxY)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glEnable(GLES30.GL_BLEND)
    }

    private fun maybeCaptureRelease(m: SplatModel) {
        if (!releaseCaptureRequested) return
        val reason = releaseCaptureReason
        if (streamError != null) {
            releaseCaptureRequested = false
            Log.w(TAG, "releaseCaptureCancelled reason=$reason streamError=$streamError")
            status("Release capture skipped: splat failed to load")
            return
        }
        val pendingSortExists = synchronized(pendingSortLock) { pendingSort != null }
        val readyForCapture =
            !isProgressiveLoadInFlight() &&
                !interactionActive &&
                hasUploadedInstances &&
                uploadedInstanceCount >= m.count &&
                !sortDirty &&
                !sortRunning.get() &&
                !pendingSortExists
        if (!readyForCapture) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastReleaseCaptureDeferredLogMs > 250L) {
                lastReleaseCaptureDeferredLogMs = now
                Log.i(
                    TAG,
                    "releaseCaptureDeferred reason=$reason loaded=${m.count} expected=$streamExpectedCount " +
                        "streamComplete=$streamComplete interactionActive=$interactionActive " +
                        "uploaded=$uploadedInstanceCount hasUploaded=$hasUploadedInstances sortDirty=$sortDirty " +
                        "sortRunning=${sortRunning.get()} pendingSort=$pendingSortExists"
                )
            }
            return
        }
        releaseCaptureRequested = false
        if (!captureRunning.compareAndSet(false, true)) {
            Log.i(TAG, "releaseCaptureSkipped reason=$reason alreadyRunning=true")
            return
        }
        val readStartNs = System.nanoTime()
        try {
            val fboStartNs = System.nanoTime()
            val fboPixels = readPixels(fbo)
            val fboReadNs = System.nanoTime() - fboStartNs
            val previewStartNs = System.nanoTime()
            val previewPixels = readPixels(0)
            val previewReadNs = System.nanoTime() - previewStartNs
            val renderW = viewportW
            val renderH = viewportH
            val captureRect = currentCaptureRect()
            val readNs = System.nanoTime() - readStartNs
            Log.i(
                TAG,
                "releaseCaptureRead reason=$reason render=${renderW}x${renderH} " +
                    "capture=${captureRect.x},${captureRect.y} ${captureRect.width}x${captureRect.height} " +
                    "timingsMs fboRead=%.1f previewRead=%.1f totalRead=%.1f"
                        .format(
                            fboReadNs / 1_000_000.0,
                            previewReadNs / 1_000_000.0,
                            readNs / 1_000_000.0
                        )
            )
            captureExecutor.execute {
                val buildStartNs = System.nanoTime()
                try {
                    val capture = buildReleaseCapture(m, fboPixels, previewPixels, renderW, renderH, captureRect)
                    val buildNs = System.nanoTime() - buildStartNs
                    Log.i(
                        TAG,
                        "releaseCaptureBuilt reason=$reason size=${capture.width}x${capture.height} " +
                            "render=${capture.renderWidth}x${capture.renderHeight} gap=${capture.gapPx} " +
                            "peripheral=${capture.peripheralPx} timingsMs build=%.1f total=%.1f"
                                .format(
                                    buildNs / 1_000_000.0,
                                    (readNs + buildNs) / 1_000_000.0
                                )
                    )
                    releaseCapture(capture)
                } catch (exc: Exception) {
                    Log.e(TAG, "release capture build failed", exc)
                    status("Release capture failed: ${exc.message}")
                } finally {
                    captureRunning.set(false)
                }
            }
        } catch (exc: Exception) {
            captureRunning.set(false)
            Log.e(TAG, "release capture failed", exc)
            status("Release capture failed: ${exc.message}")
        }
    }

    private fun currentCaptureRect(): CaptureRect {
        val x0 = (contentMinX * viewportW).toInt().coerceIn(0, viewportW - 1)
        val y0 = (contentMinY * viewportH).toInt().coerceIn(0, viewportH - 1)
        val x1 = kotlin.math.ceil(contentMaxX * viewportW).toInt().coerceIn(x0 + 1, viewportW)
        val y1 = kotlin.math.ceil(contentMaxY * viewportH).toInt().coerceIn(y0 + 1, viewportH)
        return CaptureRect(x0, y0, x1 - x0, y1 - y0)
    }

    private fun readPixels(framebuffer: Int): ByteArray {
        val raw = ByteArray(viewportW * viewportH * 4)
        val buffer = ByteBuffer.allocateDirect(raw.size).order(ByteOrder.nativeOrder())
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glReadPixels(0, 0, viewportW, viewportH, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        buffer.position(0)
        buffer.get(raw)
        return raw
    }

    private fun buildReleaseCapture(
        m: SplatModel,
        fboPixels: ByteArray,
        previewPixels: ByteArray,
        renderW: Int,
        renderH: Int,
        captureRect: CaptureRect
    ): ReleaseCapture {
        val captureW = captureRect.width
        val captureH = captureRect.height
        val total = captureW * captureH
        val seedArgb = IntArray(total)
        val previewArgb = IntArray(total)
        val gap = ByteArray(total)
        var gapPx = 0
        var coveredPx = 0
        for (y in 0 until captureH) {
            val screenY = captureRect.y + y
            val srcY = renderH - 1 - screenY
            for (x in 0 until captureW) {
                val screenX = captureRect.x + x
                val dst = y * captureW + x
                val src = (srcY * renderW + screenX) * 4
                val sr = fboPixels[src].toInt() and 0xff
                val sg = fboPixels[src + 1].toInt() and 0xff
                val sb = fboPixels[src + 2].toInt() and 0xff
                val sa = fboPixels[src + 3].toInt() and 0xff
                seedArgb[dst] = -0x1000000 or (sr shl 16) or (sg shl 8) or sb
                // Hole = the splat coverage lets the background show through by more than the
                // tolerance, i.e. coverage < ~97%. This mirrors the web reference exactly: the web's
                // black-vs-magenta render diff equals (255 - alpha), so testing (255 - sa) here gives
                // the same gap without a second render. The old sa<=8 test only caught the fully
                // empty core and missed the sparse splat-thinning disocclusion boundary, making the
                // phone's peripheral mask smaller than the web's (difix then refined that low-quality
                // fringe instead of letting flux regenerate it).
                if (255 - sa > MASK_TOLERANCE) {
                    gap[dst] = 1
                    gapPx++
                } else {
                    coveredPx++
                }

                val pr = previewPixels[src].toInt() and 0xff
                val pg = previewPixels[src + 1].toInt() and 0xff
                val pb = previewPixels[src + 2].toInt() and 0xff
                previewArgb[dst] = -0x1000000 or (pr shl 16) or (pg shl 8) or pb
            }
        }

        // Align mask construction with the web reference: clean the raw coverage gap with the same
        // morphological pipeline (removeSmall -> dilate -> erode -> removeSmall) before classifying.
        // The phone previously skipped this, so its peripheral/refine masks were raw and jagged and
        // diverged from the web's for the same view. Parameters scale with capture resolution.
        val cleanedGap = cleanGapMask(gap, captureW, captureH)
        gapPx = 0
        for (i in 0 until total) if (cleanedGap[i].toInt() != 0) gapPx++
        coveredPx = total - gapPx
        val peripheral = floodBorder(cleanedGap, captureW, captureH)
        val refine = ByteArray(total)
        var peripheralPx = 0
        var interiorPx = 0
        for (i in 0 until total) {
            if (peripheral[i].toInt() != 0) {
                peripheralPx++
                refine[i] = 0
            } else {
                refine[i] = 1
                if (cleanedGap[i].toInt() != 0) interiorPx++
            }
        }

        val scale = min(1f, RELEASE_MAX_SIDE.toFloat() / max(captureW, captureH).toFloat())
        val outW = max(1, (captureW * scale).toInt())
        val outH = max(1, (captureH * scale).toInt())
        val seedFull = Bitmap.createBitmap(seedArgb, captureW, captureH, Bitmap.Config.ARGB_8888)
        val previewFull = Bitmap.createBitmap(previewArgb, captureW, captureH, Bitmap.Config.ARGB_8888)
        val refineFull = maskBitmap(refine, captureW, captureH)
        val peripheralFull = maskBitmap(peripheral, captureW, captureH)
        val seed = Bitmap.createScaledBitmap(seedFull, outW, outH, true)
        val preview = Bitmap.createScaledBitmap(previewFull, outW, outH, true)
        val refineMask = Bitmap.createScaledBitmap(refineFull, outW, outH, false)
        val peripheralMask = Bitmap.createScaledBitmap(peripheralFull, outW, outH, false)
        seedFull.recycle()
        previewFull.recycle()
        refineFull.recycle()
        peripheralFull.recycle()

        return ReleaseCapture(
            assetName = m.assetName,
            renderWidth = captureW,
            renderHeight = captureH,
            width = outW,
            height = outH,
            seedDataUrl = bitmapDataUrl(seed, Bitmap.CompressFormat.JPEG, DIFIX_CAPTURE_JPEG_QUALITY),
            previewDataUrl = bitmapDataUrl(preview, Bitmap.CompressFormat.JPEG, DIFIX_CAPTURE_JPEG_QUALITY),
            refineMaskDataUrl = bitmapDataUrl(refineMask, Bitmap.CompressFormat.PNG, 100),
            peripheralMaskDataUrl = bitmapDataUrl(peripheralMask, Bitmap.CompressFormat.PNG, 100),
            gapPx = gapPx,
            peripheralPx = peripheralPx,
            interiorPx = interiorPx,
            coveredPx = coveredPx,
            alphaThreshold = MASK_TOLERANCE,
            releaseMaxSide = RELEASE_MAX_SIDE
        )
    }

    private fun floodBorder(mask: ByteArray, w: Int, h: Int): ByteArray {
        val n = w * h
        val out = ByteArray(n)
        val queue = IntArray(n)
        var head = 0
        var tail = 0

        fun add(idx: Int) {
            if (idx in 0 until n && mask[idx].toInt() != 0 && out[idx].toInt() == 0) {
                out[idx] = 1
                queue[tail++] = idx
            }
        }

        for (x in 0 until w) {
            add(x)
            add((h - 1) * w + x)
        }
        for (y in 0 until h) {
            add(y * w)
            add(y * w + w - 1)
        }
        while (head < tail) {
            val idx = queue[head++]
            val x = idx % w
            add(idx - w)
            add(idx + w)
            if (x > 0) add(idx - 1)
            if (x < w - 1) add(idx + 1)
        }
        return out
    }

    private fun maskBitmap(mask: ByteArray, w: Int, h: Int): Bitmap {
        val pixels = IntArray(w * h)
        for (i in pixels.indices) {
            pixels[i] = if (mask[i].toInt() != 0) -0x1 else -0x1000000
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    // Port of the web reference gap cleanup (buildGapMask). The web tunes removeSmall(80) ->
    // dilate(2) -> erode(1) -> removeSmall(120) for a 960-px longest side; scale those to this
    // capture's native resolution so the physical morphology matches. Linear ops scale by
    // refScale, area thresholds by refScale^2.
    private fun cleanGapMask(raw: ByteArray, w: Int, h: Int): ByteArray {
        val refScale = max(w, h).toFloat() / RELEASE_MAX_SIDE.toFloat()
        val area1 = Math.round(80f * refScale * refScale)
        val dilateIt = max(1, Math.round(2f * refScale))
        val erodeIt = max(1, Math.round(1f * refScale))
        val area2 = Math.round(120f * refScale * refScale)
        var g = removeSmallComponents(raw, w, h, area1)
        g = dilate4(g, w, h, dilateIt)
        g = erode4(g, w, h, erodeIt)
        return removeSmallComponents(g, w, h, area2)
    }

    private fun dilate4(mask: ByteArray, w: Int, h: Int, iterations: Int): ByteArray {
        var cur = mask
        val n = w * h
        repeat(iterations) {
            val out = cur.copyOf()
            for (i in 0 until n) {
                if (cur[i].toInt() == 0) continue
                val x = i % w
                if (i >= w) out[i - w] = 1
                if (i < n - w) out[i + w] = 1
                if (x > 0) out[i - 1] = 1
                if (x < w - 1) out[i + 1] = 1
            }
            cur = out
        }
        return cur
    }

    private fun erode4(mask: ByteArray, w: Int, h: Int, iterations: Int): ByteArray {
        val inv = ByteArray(mask.size)
        for (i in mask.indices) inv[i] = if (mask[i].toInt() != 0) 0.toByte() else 1.toByte()
        val grown = dilate4(inv, w, h, iterations)
        val out = ByteArray(mask.size)
        for (i in mask.indices) out[i] = if (grown[i].toInt() != 0) 0.toByte() else 1.toByte()
        return out
    }

    private fun removeSmallComponents(mask: ByteArray, w: Int, h: Int, minArea: Int): ByteArray {
        if (minArea <= 1) return mask.copyOf()
        val n = w * h
        val seen = ByteArray(n)
        val out = ByteArray(n)
        val queue = IntArray(n)
        val comp = IntArray(n)
        for (start in 0 until n) {
            if (mask[start].toInt() == 0 || seen[start].toInt() != 0) continue
            var head = 0
            var tail = 0
            var compLen = 0
            queue[tail++] = start
            seen[start] = 1
            while (head < tail) {
                val idx = queue[head++]
                comp[compLen++] = idx
                val x = idx % w
                val up = idx - w
                val down = idx + w
                if (up >= 0 && mask[up].toInt() != 0 && seen[up].toInt() == 0) { seen[up] = 1; queue[tail++] = up }
                if (down < n && mask[down].toInt() != 0 && seen[down].toInt() == 0) { seen[down] = 1; queue[tail++] = down }
                if (x > 0 && mask[idx - 1].toInt() != 0 && seen[idx - 1].toInt() == 0) { seen[idx - 1] = 1; queue[tail++] = idx - 1 }
                if (x < w - 1 && mask[idx + 1].toInt() != 0 && seen[idx + 1].toInt() == 0) { seen[idx + 1] = 1; queue[tail++] = idx + 1 }
            }
            if (compLen >= minArea) {
                for (i in 0 until compLen) out[comp[i]] = 1
            }
        }
        return out
    }

    private fun bitmapDataUrl(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(format, quality, out)
        val mime = if (format == Bitmap.CompressFormat.PNG) "image/png" else "image/jpeg"
        val encoded = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        bitmap.recycle()
        return "data:$mime;base64,$encoded"
    }

    private fun quickSort(order: IntArray, depth: FloatArray, left: Int, right: Int) {
        var i = left
        var j = right
        val pivot = depth[order[(left + right) ushr 1]]
        while (i <= j) {
            while (depth[order[i]] < pivot) i++
            while (depth[order[j]] > pivot) j--
            if (i <= j) {
                val tmp = order[i]
                order[i] = order[j]
                order[j] = tmp
                i++
                j--
            }
        }
        if (left < j) quickSort(order, depth, left, j)
        if (i < right) quickSort(order, depth, i, right)
    }

    companion object {
        private const val TAG = "AlbumGLESPerf"
        private const val SPLAT_STREAM_URL = "http://app.wici.ai:54228/orbit/splat_stream"
        private const val INGEST_URL = "http://app.wici.ai:54228/orbit/ingest"
        private const val INGEST_SPLAT_ACCEPT_HEADER = "X-Wici-Splat-Accept"
        private const val INGEST_SPLAT_STREAM_HEADER = "X-Wici-Splat-Stream"
        private const val INGEST_SPLAT_FORMAT_FP16_V1 = "fp16v1"
        private const val INGEST_UPLOAD_MAX_SIDE = 1536
        private const val INGEST_UPLOAD_JPEG_QUALITY = 90
        private const val SPLAT_ROW_BYTES = 32
        // Mirror the web viewer's GaussianSplats3D splatAlphaRemovalThreshold (SPLAT_ALPHA_THRESHOLD=5):
        // splats with stored alpha below this are dropped at parse time.
        private const val SPLAT_ALPHA_REMOVAL_THRESHOLD = 5
        private const val STREAM_BATCH_RECORDS = 8_192
        private const val STREAM_READ_BYTES = 128 * 1024
        private const val INSTANCE_FLOATS = 14
        private const val LOW_PASS_VARIANCE = 0.35f
        private const val MIN_VARIANCE = 0.05f
        private const val DEFAULT_STREAM_DENSITY = "full"
        private const val FOOTPRINT_SCALE = 1.35f
        private const val MIN_DIRECT_SCALE = 0.00001f
        private const val DEFAULT_DIRECT_SCALE = 0.01f
        private const val SIGMA_EXTENT = 3.0f
        private const val MAX_PIXEL_RADIUS = 220.0f
        private const val SORT_THRESHOLD_RAD = 0.06f
        private const val PYRAMID_LEVELS = 11
        private const val FINAL_DILATION_PX = 5.0f
        private const val MASK_TOLERANCE = 8
        private const val RELEASE_MAX_SIDE = 960
        private const val DIFIX_CAPTURE_JPEG_QUALITY = 90
        private const val NEAR_PLANE = 0.02f
        private const val FAR_PLANE = 500f
        private const val TWO_PI = (Math.PI * 2.0).toFloat()
        private const val WEB_ROTATE_SPEED = 0.42f
        private const val WEB_ZOOM_SPEED = 0.55f
        private const val WEB_AZIMUTH_LIMIT_DEG = 20f
        private const val WEB_POLAR_LIMIT_DEG = 12f
        private val WEB_AZIMUTH_LIMIT_RAD = Math.toRadians(WEB_AZIMUTH_LIMIT_DEG.toDouble()).toFloat()
        private val WEB_POLAR_LIMIT_RAD = Math.toRadians(WEB_POLAR_LIMIT_DEG.toDouble()).toFloat()
        private const val SOURCE_LOOK_AT_Z = 1f
        private const val SOURCE_FOCAL_35MM = 30f
        private const val SOURCE_SENSOR_DIAGONAL = 43.266617f
        private const val SOURCE_FOCAL_PER_MIN_SIDE = 1.25f
        private const val SOURCE_REST_ZOOM = 1f
        private const val SOURCE_REST_EPSILON = 0.001f
        private const val MIN_ZOOM = 0.42f
        private const val MAX_ZOOM = 1.85f
        private const val AUTO_FIT_MARGIN = 1.18f
        private const val CAMERA_SAFETY_MIN_SPLATS = 4_096
        private const val CAMERA_SAFETY_MIN_VISIBLE_PX = 64f
        private val FP16_MAGIC = byteArrayOf(
            'W'.code.toByte(),
            'I'.code.toByte(),
            'C'.code.toByte(),
            'I'.code.toByte(),
            'S'.code.toByte(),
            'F'.code.toByte(),
            '1'.code.toByte(),
            '6'.code.toByte()
        )

        private fun writeCovarianceFromDirectScales(
            out: FloatArray,
            base: Int,
            sx: Float,
            sy: Float,
            sz: Float,
            qwIn: Float,
            qxIn: Float,
            qyIn: Float,
            qzIn: Float
        ) {
            val norm = sqrt(qwIn * qwIn + qxIn * qxIn + qyIn * qyIn + qzIn * qzIn).coerceAtLeast(1e-8f)
            val w = qwIn / norm
            val x = qxIn / norm
            val y = qyIn / norm
            val z = qzIn / norm

            val xx = x * x
            val yy = y * y
            val zz = z * z
            val xy = x * y
            val xz = x * z
            val yz = y * z
            val wx = w * x
            val wy = w * y
            val wz = w * z

            val r00 = 1f - 2f * (yy + zz)
            val r01 = 2f * (xy - wz)
            val r02 = 2f * (xz + wy)
            val r10 = 2f * (xy + wz)
            val r11 = 1f - 2f * (xx + zz)
            val r12 = 2f * (yz - wx)
            val r20 = 2f * (xz - wy)
            val r21 = 2f * (yz + wx)
            val r22 = 1f - 2f * (xx + yy)

            val sx2 = sx * sx
            val sy2 = sy * sy
            val sz2 = sz * sz

            out[base] = r00 * r00 * sx2 + r01 * r01 * sy2 + r02 * r02 * sz2
            out[base + 1] = r00 * r10 * sx2 + r01 * r11 * sy2 + r02 * r12 * sz2
            out[base + 2] = r00 * r20 * sx2 + r01 * r21 * sy2 + r02 * r22 * sz2
            out[base + 3] = r10 * r10 * sx2 + r11 * r11 * sy2 + r12 * r12 * sz2
            out[base + 4] = r10 * r20 * sx2 + r11 * r21 * sy2 + r12 * r22 * sz2
            out[base + 5] = r20 * r20 * sx2 + r21 * r21 * sy2 + r22 * r22 * sz2
        }

        private fun sourceCameraForAsset(assetName: String): SourceCamera? {
            if (!assetName.contains("phone-photo-man")) return null
            return SourceCamera(
                width = 1920,
                height = 1280,
                fx = 1600f,
                fy = 1600f,
                cx = 960f,
                cy = 640f,
                exact = true,
                source = "legacy-phone-photo-man"
            )
        }

        private fun sourceCameraFromDims(width: Int?, height: Int?): SourceCamera? {
            val w = width?.takeIf { it > 0 } ?: return null
            val h = height?.takeIf { it > 0 } ?: return null
            val f = sourceFocalPx(w, h)
            return SourceCamera(
                width = w,
                height = h,
                fx = f,
                fy = f,
                cx = w * 0.5f,
                cy = h * 0.5f,
                exact = false,
                source = "derived-dims"
            )
        }

        private fun sourceCameraFromExact(
            width: Int?,
            height: Int?,
            fx: Float?,
            fy: Float?,
            cx: Float?,
            cy: Float?,
            source: String
        ): SourceCamera? {
            val w = width?.takeIf { it > 0 } ?: return null
            val h = height?.takeIf { it > 0 } ?: return null
            val safeFx = fx?.takeIf { it.isFinite() && it > 0f } ?: return null
            val safeFy = fy?.takeIf { it.isFinite() && it > 0f } ?: return null
            val safeCx = cx?.takeIf { it.isFinite() && it >= 0f } ?: return null
            val safeCy = cy?.takeIf { it.isFinite() && it >= 0f } ?: return null
            return SourceCamera(
                width = w,
                height = h,
                fx = safeFx,
                fy = safeFy,
                cx = safeCx,
                cy = safeCy,
                exact = true,
                source = source
            )
        }

        private fun sourceCameraFromHeaders(conn: HttpURLConnection): SourceCamera? =
            sourceCameraFromExact(
                headerInt(conn, "X-Src-Width", "X-Source-Width", "X-Image-Width", "X-Width"),
                headerInt(conn, "X-Src-Height", "X-Source-Height", "X-Image-Height", "X-Height"),
                headerFloat(conn, "X-Cam-Fx", "X-Cam-FX", "X-Camera-Fx", "X-Fx"),
                headerFloat(conn, "X-Cam-Fy", "X-Cam-FY", "X-Camera-Fy", "X-Fy"),
                headerFloat(conn, "X-Cam-Cx", "X-Cam-CX", "X-Camera-Cx", "X-Cx"),
                headerFloat(conn, "X-Cam-Cy", "X-Cam-CY", "X-Camera-Cy", "X-Cy"),
                "stream-header"
            )

        private fun headerInt(conn: HttpURLConnection, vararg names: String): Int? {
            for (name in names) {
                conn.getHeaderField(name)?.toIntOrNull()?.takeIf { it > 0 }?.let { return it }
            }
            return null
        }

        private fun headerFloat(conn: HttpURLConnection, vararg names: String): Float? {
            for (name in names) {
                conn.getHeaderField(name)?.toFloatOrNull()?.takeIf { it.isFinite() && it > 0f }?.let { return it }
            }
            return null
        }

        private fun appendDensityIfNeeded(url: String, density: String?): String {
            if (density.isNullOrBlank() || url.contains("density=")) return url
            val separator = if (url.contains("?")) "&" else "?"
            return "$url${separator}density=${URLEncoder.encode(density, "UTF-8")}"
        }

        private fun extensionForMime(mime: String): String =
            when {
                mime.contains("png", ignoreCase = true) -> "png"
                mime.contains("webp", ignoreCase = true) -> "webp"
                mime.contains("heic", ignoreCase = true) || mime.contains("heif", ignoreCase = true) -> "heic"
                else -> "jpg"
            }

        private fun sourceFocalPx(width: Int, height: Int): Float {
            val diagonal = sqrt(width.toFloat() * width.toFloat() + height.toFloat() * height.toFloat())
            return SOURCE_FOCAL_35MM * diagonal / SOURCE_SENSOR_DIAGONAL
        }

        private const val SPLAT_VS = """
            #version 300 es
            precision highp float;
            layout(location = 0) in vec2 aCorner;
            layout(location = 1) in vec3 iCenter;
            layout(location = 2) in vec4 iColor;
            layout(location = 3) in vec2 iAxis0;
            layout(location = 4) in vec2 iAxis1;
            layout(location = 5) in vec3 iConic;
            uniform mat4 uView;
            uniform mat4 uProj;
            uniform vec2 uViewport;
            out vec2 vDeltaPx;
            out vec3 vConic;
            out vec4 vColor;
            void main() {
                vec4 viewPos = uView * vec4(iCenter, 1.0);
                vec4 clip = uProj * viewPos;
                vec2 deltaPx = iAxis0 * aCorner.x + iAxis1 * aCorner.y;
                vec2 ndcOffset = deltaPx * 2.0 / uViewport;
                gl_Position = clip + vec4(ndcOffset * clip.w, 0.0, 0.0);
                vDeltaPx = deltaPx;
                vConic = iConic;
                vColor = iColor;
            }
        """

        private const val SPLAT_FS = """
            #version 300 es
            precision highp float;
            in vec2 vDeltaPx;
            in vec3 vConic;
            in vec4 vColor;
            out vec4 outColor;
            void main() {
                float power = vConic.x * vDeltaPx.x * vDeltaPx.x
                    + 2.0 * vConic.y * vDeltaPx.x * vDeltaPx.y
                    + vConic.z * vDeltaPx.y * vDeltaPx.y;
                if (power > 9.0) {
                    discard;
                }
                float a = min(0.995, vColor.a * exp(-0.5 * power));
                if (a < 0.003) {
                    discard;
                }
                outColor = vec4(vColor.rgb * a, a);
            }
        """

        private const val BLIT_VS = """
            #version 300 es
            precision highp float;
            out vec2 vUv;
            void main() {
                vec2 p;
                if (gl_VertexID == 0) {
                    p = vec2(-1.0, -1.0);
                    vUv = vec2(0.0, 0.0);
                } else if (gl_VertexID == 1) {
                    p = vec2(3.0, -1.0);
                    vUv = vec2(2.0, 0.0);
                } else {
                    p = vec2(-1.0, 3.0);
                    vUv = vec2(0.0, 2.0);
                }
                gl_Position = vec4(p, 0.0, 1.0);
            }
        """

        private const val BLIT_FS = """
            #version 300 es
            precision highp float;
            uniform sampler2D uTex;
            uniform sampler2D uFill;
            uniform vec2 uFullTexel;
            uniform float uDilationPx;
            uniform vec2 uContentMin;
            uniform vec2 uContentMax;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                if (vUv.x < uContentMin.x || vUv.x > uContentMax.x ||
                    vUv.y < uContentMin.y || vUv.y > uContentMax.y) {
                    outColor = vec4(0.949, 0.953, 0.961, 1.0);
                    return;
                }
                vec4 splat = texture(uTex, vUv);
                vec3 fill = texture(uFill, vUv).rgb;

                vec2 d = uFullTexel * uDilationPx;
                float maxA = splat.a;
                maxA = max(maxA, texture(uTex, vUv + vec2( d.x, 0.0)).a);
                maxA = max(maxA, texture(uTex, vUv + vec2(-d.x, 0.0)).a);
                maxA = max(maxA, texture(uTex, vUv + vec2(0.0,  d.y)).a);
                maxA = max(maxA, texture(uTex, vUv + vec2(0.0, -d.y)).a);
                maxA = max(maxA, texture(uTex, vUv + vec2( d.x,  d.y)).a);
                maxA = max(maxA, texture(uTex, vUv + vec2(-d.x,  d.y)).a);
                maxA = max(maxA, texture(uTex, vUv + vec2( d.x, -d.y)).a);
                maxA = max(maxA, texture(uTex, vUv + vec2(-d.x, -d.y)).a);

                float a = clamp(splat.a, 0.0, 1.0);
                float feather = smoothstep(0.0, 0.18, maxA);
                vec3 colorOverFill = splat.rgb + fill * (1.0 - a);
                vec3 color = mix(fill, colorOverFill, feather);
                outColor = vec4(color, 1.0);
            }
        """

        private const val RAW_BLIT_FS = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            uniform vec2 uContentMin;
            uniform vec2 uContentMax;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                if (vUv.x < uContentMin.x || vUv.x > uContentMax.x ||
                    vUv.y < uContentMin.y || vUv.y > uContentMax.y) {
                    outColor = vec4(0.949, 0.953, 0.961, 1.0);
                    return;
                }
                vec4 splat = texture(uTex, vUv);
                outColor = vec4(splat.rgb, 1.0);
            }
        """

        private const val DOWNSAMPLE_FS = """
            #version 300 es
            precision highp float;
            uniform sampler2D uTex;
            uniform vec2 uTexel;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                vec2 o = 0.5 * uTexel;
                vec4 s = texture(uTex, vUv + vec2(-o.x, -o.y))
                    + texture(uTex, vUv + vec2( o.x, -o.y))
                    + texture(uTex, vUv + vec2(-o.x,  o.y))
                    + texture(uTex, vUv + vec2( o.x,  o.y));
                outColor = s * 0.25;
            }
        """

        private const val SEED_FILL_FS = """
            #version 300 es
            precision highp float;
            uniform sampler2D uTex;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                vec4 p = texture(uTex, vUv);
                vec3 color = p.a > 0.0005 ? p.rgb / p.a : vec3(0.28, 0.34, 0.17);
                outColor = vec4(color, p.a);
            }
        """

        private const val PUSH_FILL_FS = """
            #version 300 es
            precision highp float;
            uniform sampler2D uFine;
            uniform sampler2D uCoarse;
            in vec2 vUv;
            out vec4 outColor;
            void main() {
                vec4 fine = texture(uFine, vUv);
                vec4 coarse = texture(uCoarse, vUv);
                vec3 fineColor = fine.a > 0.02 ? fine.rgb / fine.a : coarse.rgb;
                float keepFine = smoothstep(0.06, 0.28, fine.a);
                vec3 color = mix(coarse.rgb, fineColor, keepFine);
                float confidence = max(fine.a, coarse.a * 0.985);
                outColor = vec4(color, confidence);
            }
        """
    }
}

private fun FloatArray.toFloatBuffer(): FloatBuffer =
    ByteBuffer.allocateDirect(size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .also {
            it.put(this)
            it.position(0)
        }

private fun buildProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES30.glCreateProgram()
    GLES30.glAttachShader(program, vertex)
    GLES30.glAttachShader(program, fragment)
    GLES30.glLinkProgram(program)
    val linked = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
    if (linked[0] == 0) {
        val log = GLES30.glGetProgramInfoLog(program)
        GLES30.glDeleteProgram(program)
        error("Program link failed: $log")
    }
    GLES30.glDeleteShader(vertex)
    GLES30.glDeleteShader(fragment)
    return program
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    GLES30.glShaderSource(shader, source.trimIndent())
    GLES30.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
        val log = GLES30.glGetShaderInfoLog(shader)
        GLES30.glDeleteShader(shader)
        error("Shader compile failed: $log")
    }
    return shader
}
