package com.wici.androidalbumdemo

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class SplatModel(
    val assetName: String,
    val count: Int,
    val centers: FloatArray,
    val colors: FloatArray,
    val covariances: FloatArray,
    val minX: Float,
    val minY: Float,
    val minZ: Float,
    val maxX: Float,
    val maxY: Float,
    val maxZ: Float,
    val centerX: Float,
    val centerY: Float,
    val centerZ: Float,
    val radius: Float,
    val invalidValueCount: Int
)

object PlyLoader {
    private const val SH_C0 = 0.28209479177387814f

    fun loadAsset(context: Context, assetName: String): SplatModel {
        val bytes = context.assets.open(assetName).use { it.readBytes() }
        val headerEnd = findHeaderEnd(bytes)
        val header = String(bytes, 0, headerEnd, Charsets.US_ASCII)
        val lines = header.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        require(lines.firstOrNull() == "ply") { "Not a PLY file: $assetName" }
        require(lines.any { it == "format binary_little_endian 1.0" }) {
            "Only binary_little_endian PLY is supported"
        }

        var vertexCount = -1
        val properties = mutableListOf<String>()
        var inVertex = false
        for (line in lines) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size >= 3 && parts[0] == "element" && parts[1] == "vertex") {
                vertexCount = parts[2].toInt()
                inVertex = true
            } else if (parts.size >= 2 && parts[0] == "element") {
                inVertex = false
            } else if (inVertex && parts.size >= 3 && parts[0] == "property") {
                require(parts[1] == "float") { "Only float vertex properties are supported" }
                properties += parts.last()
            }
        }
        require(vertexCount > 0) { "No vertex element in $assetName" }

        val stride = properties.size * 4
        require(bytes.size >= headerEnd + vertexCount * stride) {
            "PLY body is shorter than the vertex declaration"
        }

        val index = properties.withIndex().associate { it.value to it.index }
        fun prop(name: String): Int = index[name] ?: -1

        val ix = prop("x")
        val iy = prop("y")
        val iz = prop("z")
        val ir = prop("f_dc_0")
        val ig = prop("f_dc_1")
        val ib = prop("f_dc_2")
        val ia = prop("opacity")
        val is0 = prop("scale_0")
        val is1 = prop("scale_1")
        val is2 = prop("scale_2")
        require(ix >= 0 && iy >= 0 && iz >= 0) { "PLY must include x/y/z" }

        val bb = ByteBuffer.wrap(bytes, headerEnd, bytes.size - headerEnd).order(ByteOrder.LITTLE_ENDIAN)
        val centers = FloatArray(vertexCount * 3)
        val colors = FloatArray(vertexCount * 4)
        val covariances = FloatArray(vertexCount * 6)
        val row = FloatArray(properties.size)
        val ir0 = prop("rot_0")
        val ir1 = prop("rot_1")
        val ir2 = prop("rot_2")
        val ir3 = prop("rot_3")

        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        var invalid = 0

        for (i in 0 until vertexCount) {
            for (p in properties.indices) {
                val value = bb.float
                if (value.isFinite()) {
                    row[p] = value
                } else {
                    row[p] = 0f
                    invalid++
                }
            }

            val x = row[ix]
            val y = row[iy]
            val z = row[iz]
            centers[i * 3] = x
            centers[i * 3 + 1] = y
            centers[i * 3 + 2] = z
            minX = min(minX, x)
            minY = min(minY, y)
            minZ = min(minZ, z)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
            maxZ = max(maxZ, z)

            colors[i * 4] = dcToRgb(if (ir >= 0) row[ir] else 0f)
            colors[i * 4 + 1] = dcToRgb(if (ig >= 0) row[ig] else 0f)
            colors[i * 4 + 2] = dcToRgb(if (ib >= 0) row[ib] else 0f)
            colors[i * 4 + 3] = sigmoid(if (ia >= 0) row[ia] else 2f).coerceIn(0.02f, 0.98f)

            val s0 = if (is0 >= 0) row[is0] else -4f
            val s1 = if (is1 >= 0) row[is1] else s0
            val s2 = if (is2 >= 0) row[is2] else s0
            val qw = if (ir0 >= 0) row[ir0] else 1f
            val qx = if (ir1 >= 0) row[ir1] else 0f
            val qy = if (ir2 >= 0) row[ir2] else 0f
            val qz = if (ir3 >= 0) row[ir3] else 0f
            writeCovariance(covariances, i * 6, s0, s1, s2, qw, qx, qy, qz)
        }

        val cx = (minX + maxX) * 0.5f
        val cy = (minY + maxY) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val dx = maxX - minX
        val dy = maxY - minY
        val dz = maxZ - minZ
        val boundsRadius = max(0.5f, kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f)

        return SplatModel(
            assetName,
            vertexCount,
            centers,
            colors,
            covariances,
            minX,
            minY,
            minZ,
            maxX,
            maxY,
            maxZ,
            cx,
            cy,
            cz,
            boundsRadius,
            invalid
        )
    }

    private fun writeCovariance(
        out: FloatArray,
        base: Int,
        logSx: Float,
        logSy: Float,
        logSz: Float,
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

        val sx2 = exp(logSx * 2f)
        val sy2 = exp(logSy * 2f)
        val sz2 = exp(logSz * 2f)

        out[base] = r00 * r00 * sx2 + r01 * r01 * sy2 + r02 * r02 * sz2
        out[base + 1] = r00 * r10 * sx2 + r01 * r11 * sy2 + r02 * r12 * sz2
        out[base + 2] = r00 * r20 * sx2 + r01 * r21 * sy2 + r02 * r22 * sz2
        out[base + 3] = r10 * r10 * sx2 + r11 * r11 * sy2 + r12 * r12 * sz2
        out[base + 4] = r10 * r20 * sx2 + r11 * r21 * sy2 + r12 * r22 * sz2
        out[base + 5] = r20 * r20 * sx2 + r21 * r21 * sy2 + r22 * r22 * sz2
    }

    private fun findHeaderEnd(bytes: ByteArray): Int {
        val marker = "end_header\n".toByteArray(Charsets.US_ASCII)
        outer@ for (i in 0..bytes.size - marker.size) {
            for (j in marker.indices) {
                if (bytes[i + j] != marker[j]) continue@outer
            }
            return i + marker.size
        }
        error("PLY header is missing end_header")
    }

    private fun dcToRgb(v: Float): Float = (0.5f + SH_C0 * v).coerceIn(0f, 1f)

    private fun sigmoid(v: Float): Float = 1f / (1f + kotlin.math.exp(-v))
}
