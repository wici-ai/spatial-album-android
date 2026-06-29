package com.wici.androidalbumdemo

data class ReleaseCapture(
    val assetName: String,
    val renderWidth: Int,
    val renderHeight: Int,
    val width: Int,
    val height: Int,
    val seedDataUrl: String,
    val previewDataUrl: String,
    val refineMaskDataUrl: String,
    val peripheralMaskDataUrl: String,
    val gapPx: Int,
    val peripheralPx: Int,
    val interiorPx: Int,
    val coveredPx: Int,
    val alphaThreshold: Int,
    val releaseMaxSide: Int
)
