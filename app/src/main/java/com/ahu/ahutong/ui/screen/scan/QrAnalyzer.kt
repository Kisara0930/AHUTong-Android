package com.ahu.ahutong.ui.screen.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 逐帧解二维码亮度平面（只解 QR，取 Y 平面逐像素拷贝成紧凑数组）。
 *
 * - 命中一次即回调并停止（[emitted]），避免同一张码连发；
 * - 不持有相机帧（`analyze` 结束前 close）；
 * - 原文只在内存里过一手，**不落库、不打日志**（学习通的 `enc` 属于凭据）。
 */
class QrAnalyzer(private val onDecoded: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            )
        )
    }

    private val emitted = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (emitted.get()) {
            image.close()
            return
        }
        try {
            val plane = image.planes[0]
            val buffer = plane.buffer
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return
            val rowStride = plane.rowStride
            val pixelStride = plane.pixelStride
            val base = buffer.position()
            val data = ByteArray(width * height)
            for (y in 0 until height) {
                val rowStart = base + y * rowStride
                for (x in 0 until width) {
                    data[y * width + x] = buffer.get(rowStart + x * pixelStride)
                }
            }
            val text = decode(data, width, height)
            if (text != null && emitted.compareAndSet(false, true)) {
                onDecoded(text)
            }
        } catch (_: Throwable) {
            // 帧不可用或越界，跳过本帧
        } finally {
            image.close()
        }
    }

    private fun decode(data: ByteArray, width: Int, height: Int): String? {
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        for (luminance in listOf(source, source.invert())) {
            try {
                return reader.decodeWithState(BinaryBitmap(HybridBinarizer(luminance))).text
            } catch (_: NotFoundException) {
                // 本帧没有码，试反相/下一帧
            } catch (_: Throwable) {
            } finally {
                reader.reset()
            }
        }
        return null
    }
}
