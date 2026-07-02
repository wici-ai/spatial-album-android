package com.wici.androidalbumdemo

import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.sqrt

class SplatGlView(
    context: Context,
    assetName: String,
    photoId: String,
    status: (String) -> Unit,
    releaseCapture: (ReleaseCapture) -> Unit,
    streamError: (String) -> Unit = {},
    private val interactionStarted: () -> Unit = {},
    private val releaseCaptureEnabled: Boolean = true,
    postPassEnabled: Boolean = true,
    streamDensity: String? = null,
    footprintScale: Float? = null,
    sourceWidth: Int? = null,
    sourceHeight: Int? = null,
    camFx: Float? = null,
    camFy: Float? = null,
    camCx: Float? = null,
    camCy: Float? = null,
    splatStreamUrl: String? = null,
    splatStreamEndpointUrl: String? = null,
    ingestEndpointUrl: String? = null,
    ingestImageUri: String? = null,
    splatCacheMaxBytes: Long? = null,
    networkStreamEnabled: Boolean = true
) : GLSurfaceView(context) {
    private val splatRenderer = SplatRenderer(
        context.applicationContext,
        assetName,
        photoId,
        status,
        releaseCapture,
        streamError,
        postPassEnabled,
        streamDensity,
        footprintScale,
        sourceWidth,
        sourceHeight,
        camFx,
        camFy,
        camCx,
        camCy,
        splatStreamUrl,
        splatStreamEndpointUrl,
        ingestEndpointUrl,
        ingestImageUri,
        splatCacheMaxBytes,
        networkStreamEnabled
    )
    private var lastX = 0f
    private var lastY = 0f
    private var lastPinchDistance = 0f
    private var lastPinchCenterX = 0f
    private var lastPinchCenterY = 0f
    private var pinching = false

    init {
        setEGLContextClientVersion(3)
        setPreserveEGLContextOnPause(true)
        setRenderer(splatRenderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_MOVE) {
            Log.i(
                TOUCH_TAG,
                "event=${actionName(event.actionMasked)} pointers=${event.pointerCount} " +
                    "x=${event.x.toInt()} y=${event.y.toInt()} downTime=${event.downTime} eventTime=${event.eventTime}"
            )
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interactionStarted()
                queueEvent { splatRenderer.setInteractionActive(true) }
                pinching = false
                lastPinchDistance = 0f
                lastX = event.x
                lastY = event.y
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                interactionStarted()
                queueEvent { splatRenderer.setInteractionActive(true) }
                if (event.pointerCount >= 2) {
                    pinching = true
                    lastPinchDistance = pinchDistance(event)
                    lastPinchCenterX = pinchCenterX(event)
                    lastPinchCenterY = pinchCenterY(event)
                    Log.i(TOUCH_TAG, "pinchStart distance=${lastPinchDistance.toInt()}")
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (event.pointerCount >= 2) {
                    val distance = pinchDistance(event)
                    val previous = lastPinchDistance
                    val centerX = pinchCenterX(event)
                    val centerY = pinchCenterY(event)
                    val centerDx = centerX - lastPinchCenterX
                    val centerDy = centerY - lastPinchCenterY
                    if (pinching && previous > 1f && distance > 1f) {
                        val distanceDelta = distance - previous
                        val centerMove = sqrt(centerDx * centerDx + centerDy * centerDy)
                        val zoomDominant = abs(distanceDelta) > centerMove * 0.5f && abs(distanceDelta) > 0.75f
                        val panDominant = centerMove > 0.75f && centerMove >= abs(distanceDelta) * 0.35f
                        if (zoomDominant) {
                            val scale = distance / previous
                            queueEvent { splatRenderer.dolly(scale) }
                        }
                        if (panDominant) {
                            queueEvent { splatRenderer.pan(centerDx, centerDy) }
                        }
                    }
                    pinching = true
                    lastPinchDistance = distance
                    lastPinchCenterX = centerX
                    lastPinchCenterY = centerY
                    return true
                }
                val x = event.x
                val y = event.y
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                queueEvent { splatRenderer.orbit(dx, dy) }
                return true
            }
            MotionEvent.ACTION_UP -> {
                pinching = false
                lastPinchDistance = 0f
                val reason = actionName(event.actionMasked)
                Log.i(TOUCH_TAG, "releaseTrigger reason=$reason enabled=$releaseCaptureEnabled")
                queueEvent {
                    splatRenderer.setInteractionActive(false)
                    if (releaseCaptureEnabled) {
                        splatRenderer.requestReleaseCapture(reason)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 <= 1) {
                    val remaining = if (event.actionIndex == 0) 1 else 0
                    if (remaining < event.pointerCount) {
                        lastX = event.getX(remaining)
                        lastY = event.getY(remaining)
                    }
                    pinching = false
                    lastPinchDistance = 0f
                    lastPinchCenterX = 0f
                    lastPinchCenterY = 0f
                    Log.i(TOUCH_TAG, "pinchEnd")
                } else if (event.pointerCount >= 3) {
                    lastPinchDistance = pinchDistance(event)
                    lastPinchCenterX = pinchCenterX(event)
                    lastPinchCenterY = pinchCenterY(event)
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pinching = false
                lastPinchDistance = 0f
                lastPinchCenterX = 0f
                lastPinchCenterY = 0f
                queueEvent { splatRenderer.setInteractionActive(false) }
                Log.i(TOUCH_TAG, "releaseIgnored reason=ACTION_CANCEL")
                return true
            }
        }
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (
            event.action == MotionEvent.ACTION_SCROLL &&
            (event.source and InputDevice.SOURCE_CLASS_POINTER) != 0
        ) {
            val scroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (abs(scroll) > 0.001f) {
                val step = 1f + abs(scroll) * 0.12f
                val scale = if (scroll > 0f) step else 1f / step
                queueEvent { splatRenderer.dolly(scale) }
                Log.i(TOUCH_TAG, "scrollZoom axis=$scroll scale=$scale")
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    fun shutdown() {
        splatRenderer.shutdown()
    }

    fun resetView() {
        interactionStarted()
        queueEvent { splatRenderer.resetView() }
    }

    private fun pinchDistance(event: MotionEvent): Float {
        if (event.pointerCount < 2) return 0f
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        return sqrt(dx * dx + dy * dy)
    }

    private fun pinchCenterX(event: MotionEvent): Float =
        if (event.pointerCount >= 2) (event.getX(0) + event.getX(1)) * 0.5f else event.x

    private fun pinchCenterY(event: MotionEvent): Float =
        if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) * 0.5f else event.y

    private fun actionName(action: Int): String =
        when (action) {
            MotionEvent.ACTION_DOWN -> "ACTION_DOWN"
            MotionEvent.ACTION_MOVE -> "ACTION_MOVE"
            MotionEvent.ACTION_UP -> "ACTION_UP"
            MotionEvent.ACTION_CANCEL -> "ACTION_CANCEL"
            MotionEvent.ACTION_POINTER_DOWN -> "ACTION_POINTER_DOWN"
            MotionEvent.ACTION_POINTER_UP -> "ACTION_POINTER_UP"
            else -> "ACTION_$action"
        }

    private companion object {
        private const val TOUCH_TAG = "AlbumTouch"
    }
}
