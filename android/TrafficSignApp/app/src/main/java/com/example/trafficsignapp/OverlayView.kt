package com.example.trafficsignapp

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

class OverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(
    context,
    attrs
) {

    // Danh sách detection hiện tại.
    private var detections:
            List<Detection> =
        emptyList()

    // Kích thước frame camera đã chạy YOLO.
    private var sourceWidth = 0
    private var sourceHeight = 0

    // Paint dùng để vẽ bounding box.
    private val boxPaint =
        Paint().apply {

            style =
                Paint.Style.STROKE

            strokeWidth = 5f

            color =
                Color.GREEN

            isAntiAlias = true
        }

    // Paint dùng để vẽ chữ.
    private val textPaint =
        Paint().apply {

            style =
                Paint.Style.FILL

            color =
                Color.WHITE

            textSize = 34f

            isAntiAlias = true
        }

    // Background phía sau label.
    private val labelBackgroundPaint =
        Paint().apply {

            style =
                Paint.Style.FILL

            color =
                Color.argb(
                    190,
                    0,
                    0,
                    0
                )
        }

    // MainActivity gọi hàm này sau mỗi inference.
    fun setResults(
        newDetections: List<Detection>,
        newSourceWidth: Int,
        newSourceHeight: Int
    ) {

        detections =
            newDetections

        sourceWidth =
            newSourceWidth

        sourceHeight =
            newSourceHeight

        // Yêu cầu Android vẽ lại OverlayView.
        invalidate()
    }

    override fun onDraw(
        canvas: Canvas
    ) {

        super.onDraw(canvas)

        if (
            sourceWidth <= 0 ||
            sourceHeight <= 0
        ) {
            return
        }

        /*
         * PreviewView dùng FILL_CENTER.
         *
         * Vì vậy ảnh camera được scale sao cho
         * phủ kín toàn bộ PreviewView.
         *
         * Phần dư có thể bị crop ở hai cạnh.
         */
        val scale =
            max(
                width.toFloat() /
                        sourceWidth,
                height.toFloat() /
                        sourceHeight
            )

        val scaledSourceWidth =
            sourceWidth *
                    scale

        val scaledSourceHeight =
            sourceHeight *
                    scale

        // Offset do FILL_CENTER căn ảnh vào giữa.
        val offsetX =
            (width -
                    scaledSourceWidth) /
                    2f

        val offsetY =
            (height -
                    scaledSourceHeight) /
                    2f

        for (
        detection in detections
        ) {

            // Đổi box camera → tọa độ OverlayView.
            val left =
                detection.left *
                        scale +
                        offsetX

            val top =
                detection.top *
                        scale +
                        offsetY

            val right =
                detection.right *
                        scale +
                        offsetX

            val bottom =
                detection.bottom *
                        scale +
                        offsetY

            val rect =
                RectF(
                    left,
                    top,
                    right,
                    bottom
                )

            // Vẽ bounding box.
            canvas.drawRect(
                rect,
                boxPaint
            )

            // Nội dung label.
            val labelText =
                "${detection.label} " +
                        "%.2f".format(
                            detection.confidence
                        )

            val textWidth =
                textPaint.measureText(
                    labelText
                )

            val textHeight =
                textPaint.textSize

            // Đặt label gần cạnh trên của box.
            val labelTop =
                max(
                    0f,
                    top - textHeight - 12f
                )

            val labelRect =
                RectF(
                    left,
                    labelTop,
                    left +
                            textWidth +
                            20f,
                    labelTop +
                            textHeight +
                            12f
                )

            // Vẽ nền label.
            canvas.drawRect(
                labelRect,
                labelBackgroundPaint
            )

            // Vẽ text.
            canvas.drawText(
                labelText,
                left + 10f,
                labelTop +
                        textHeight,
                textPaint
            )
        }
    }
}