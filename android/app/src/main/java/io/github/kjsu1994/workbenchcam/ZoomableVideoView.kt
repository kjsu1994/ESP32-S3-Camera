package io.github.kjsu1994.workbenchcam

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.max
import kotlin.math.min

internal class ZoomState(
    val minZoom: Float = 1f,
    val maxZoom: Float = 5f
) {
    var zoom: Float = 1f
        private set
    var panX: Float = 0f
        private set
    var panY: Float = 0f
        private set

    fun isZoomed(): Boolean = zoom > 1.01f

    fun reset() {
        zoom = 1f
        panX = 0f
        panY = 0f
    }

    fun applyZoom(
        factor: Float,
        focusX: Float,
        focusY: Float,
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        fillCrop: Boolean
    ) {
        if (!factor.isFinite() || factor <= 0f) return
        val oldZoom = zoom
        val nextZoom = (oldZoom * factor).coerceIn(minZoom, maxZoom)
        if (nextZoom <= 1.001f) {
            reset()
            return
        }
        val ratio = nextZoom / oldZoom
        val cx = viewWidth / 2f
        val cy = viewHeight / 2f
        // Keep the focal point stationary under the fingers/tap while zooming.
        panX = (panX + cx - focusX) * ratio - (cx - focusX)
        panY = (panY + cy - focusY) * ratio - (cy - focusY)
        zoom = nextZoom
        clamp(viewWidth, viewHeight, bitmapWidth, bitmapHeight, fillCrop)
    }

    fun toggleDoubleTap(
        focusX: Float,
        focusY: Float,
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        fillCrop: Boolean
    ) {
        if (isZoomed()) {
            reset()
        } else {
            applyZoom(2.5f, focusX, focusY, viewWidth, viewHeight, bitmapWidth, bitmapHeight, fillCrop)
        }
    }

    fun panBy(
        dx: Float,
        dy: Float,
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        fillCrop: Boolean
    ) {
        if (!isZoomed()) return
        panX += dx
        panY += dy
        clamp(viewWidth, viewHeight, bitmapWidth, bitmapHeight, fillCrop)
    }

    fun clamp(
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        fillCrop: Boolean
    ) {
        if (viewWidth <= 0f || viewHeight <= 0f || bitmapWidth <= 0f || bitmapHeight <= 0f) {
            panX = 0f
            panY = 0f
            return
        }
        val baseScale = baseScale(viewWidth, viewHeight, bitmapWidth, bitmapHeight, fillCrop)
        val scaledWidth = bitmapWidth * baseScale * zoom
        val scaledHeight = bitmapHeight * baseScale * zoom
        val maxPanX = max(0f, (scaledWidth - viewWidth) / 2f)
        val maxPanY = max(0f, (scaledHeight - viewHeight) / 2f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
    }

    fun baseScale(
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Float,
        bitmapHeight: Float,
        fillCrop: Boolean
    ): Float {
        if (viewWidth <= 0f || viewHeight <= 0f || bitmapWidth <= 0f || bitmapHeight <= 0f) return 1f
        val sx = viewWidth / bitmapWidth
        val sy = viewHeight / bitmapHeight
        return if (fillCrop) max(sx, sy) else min(sx, sy)
    }
}

internal class ZoomableVideoView(
    context: Context,
    private val onSingleTap: (() -> Unit)? = null,
    private val onZoomChanged: ((Float) -> Unit)? = null
) : ImageView(context) {

    private val zoomState = ZoomState()
    private val renderMatrix = Matrix()
    private var bitmapWidth = 0
    private var bitmapHeight = 0
    private var fillCrop = false
    private var showGrid = false

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66ffffff
        strokeWidth = context.resources.displayMetrics.density.coerceAtLeast(1f)
        style = Paint.Style.STROKE
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xaa9ae9ba.toInt()
        strokeWidth = (context.resources.displayMetrics.density * 1.4f).coerceAtLeast(1.5f)
        style = Paint.Style.STROKE
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (bitmapWidth <= 0 || bitmapHeight <= 0) return false
                zoomState.applyZoom(
                    detector.scaleFactor,
                    detector.focusX,
                    detector.focusY,
                    width.toFloat(),
                    height.toFloat(),
                    bitmapWidth.toFloat(),
                    bitmapHeight.toFloat(),
                    fillCrop
                )
                applyMatrix()
                onZoomChanged?.invoke(zoomState.zoom)
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke()
                return onSingleTap != null
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (bitmapWidth <= 0 || bitmapHeight <= 0) return false
                zoomState.toggleDoubleTap(
                    e.x,
                    e.y,
                    width.toFloat(),
                    height.toFloat(),
                    bitmapWidth.toFloat(),
                    bitmapHeight.toFloat(),
                    fillCrop
                )
                applyMatrix()
                onZoomChanged?.invoke(zoomState.zoom)
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!zoomState.isZoomed() || bitmapWidth <= 0 || bitmapHeight <= 0) return false
                zoomState.panBy(
                    -distanceX,
                    -distanceY,
                    width.toFloat(),
                    height.toFloat(),
                    bitmapWidth.toFloat(),
                    bitmapHeight.toFloat(),
                    fillCrop
                )
                applyMatrix()
                return true
            }
        }
    )

    init {
        scaleType = ScaleType.MATRIX
    }

    fun setFillCrop(enabled: Boolean) {
        if (fillCrop == enabled) return
        fillCrop = enabled
        zoomState.clamp(width.toFloat(), height.toFloat(), bitmapWidth.toFloat(), bitmapHeight.toFloat(), fillCrop)
        applyMatrix()
    }

    fun setShowGrid(enabled: Boolean) {
        if (showGrid == enabled) return
        showGrid = enabled
        invalidate()
    }

    fun resetZoom() {
        zoomState.reset()
        applyMatrix()
        onZoomChanged?.invoke(1f)
    }

    fun currentZoom(): Float = zoomState.zoom

    override fun setImageBitmap(bm: Bitmap?) {
        val nextW = bm?.width ?: 0
        val nextH = bm?.height ?: 0
        val dimensionsChanged = nextW != bitmapWidth || nextH != bitmapHeight
        bitmapWidth = nextW
        bitmapHeight = nextH
        super.setImageBitmap(bm)
        if (bm == null) {
            zoomState.reset()
            onZoomChanged?.invoke(1f)
        } else if (dimensionsChanged) {
            zoomState.clamp(width.toFloat(), height.toFloat(), bitmapWidth.toFloat(), bitmapHeight.toFloat(), fillCrop)
            applyMatrix()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        zoomState.clamp(w.toFloat(), h.toFloat(), bitmapWidth.toFloat(), bitmapHeight.toFloat(), fillCrop)
        applyMatrix()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount > 1 || zoomState.isZoomed()) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        val scaled = scaleDetector.onTouchEvent(event)
        val gestured = gestureDetector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return scaled || gestured || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!showGrid || width <= 0 || height <= 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        // Rule-of-thirds grid lines
        canvas.drawLine(w / 3f, 0f, w / 3f, h, gridPaint)
        canvas.drawLine(w * 2f / 3f, 0f, w * 2f / 3f, h, gridPaint)
        canvas.drawLine(0f, h / 3f, w, h / 3f, gridPaint)
        canvas.drawLine(0f, h * 2f / 3f, w, h * 2f / 3f, gridPaint)
        // Center alignment crosshair
        val cx = w / 2f
        val cy = h / 2f
        val arm = min(w, h) * 0.045f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, crosshairPaint)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, crosshairPaint)
    }

    private fun applyMatrix() {
        if (width <= 0 || height <= 0 || bitmapWidth <= 0 || bitmapHeight <= 0) return
        val base = zoomState.baseScale(width.toFloat(), height.toFloat(), bitmapWidth.toFloat(), bitmapHeight.toFloat(), fillCrop)
        val total = base * zoomState.zoom
        renderMatrix.reset()
        renderMatrix.postTranslate(-bitmapWidth / 2f, -bitmapHeight / 2f)
        renderMatrix.postScale(total, total)
        renderMatrix.postTranslate(width / 2f + zoomState.panX, height / 2f + zoomState.panY)
        imageMatrix = renderMatrix
    }
}
