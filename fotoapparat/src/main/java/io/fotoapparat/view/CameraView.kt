package io.fotoapparat.view

import android.content.Context
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.util.AttributeSet
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.fotoapparat.exception.camera.UnavailableSurfaceException
import io.fotoapparat.parameter.Resolution
import io.fotoapparat.parameter.ScaleType
import java.util.concurrent.CountDownLatch


/**
 * Uses [android.view.TextureView] as an output for camera.
 */
class CameraView
@JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr), CameraRenderer {

    private val textureLatch = CountDownLatch(1)
    val textureView = TextureView(context)

    private lateinit var previewResolution: Resolution
    private lateinit var scaleType: ScaleType

    var surfaceTexture: SurfaceTexture? = textureView.tryInitialize()

    init {
        addView(textureView)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        textureLatch.countDown()
    }

    override fun setScaleType(scaleType: ScaleType) {
        this.scaleType = scaleType
    }

    override fun setPreviewResolution(resolution: Resolution) {
        post {
            previewResolution = resolution
            requestLayout()
        }
    }

    override fun getPreview(): Preview {
        return surfaceTexture?.toPreview() ?: getPreviewAfterLatch()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (isInEditMode || !::previewResolution.isInitialized || !::scaleType.isInitialized) {
            super.onLayout(changed, left, top, right, bottom)
        } else {
            layoutTextureView(previewResolution, scaleType)
        }
    }

    private fun getPreviewAfterLatch(): Preview.Texture {
        textureLatch.await()
        return surfaceTexture?.toPreview() ?: throw UnavailableSurfaceException()
    }

    private fun TextureView.tryInitialize() = surfaceTexture ?: null.also {
        /*surfaceTextureListener = TextureAvailabilityListener {

        }*/
        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {
                mSurfaceTextureListener?.onSurfaceTextureSizeChanged(surface, width, height)
            }

            override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
                mSurfaceTextureListener?.onSurfaceTextureUpdated(surface)
            }

            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
                return mSurfaceTextureListener?.onSurfaceTextureDestroyed(surface)!!
            }

            override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
                this@CameraView.surfaceTexture = surface
                textureLatch.countDown()
                mSurfaceTextureListener?.onSurfaceTextureAvailable(surface, width, height)
            }
        }


    }

    public var mSurfaceTextureListener: SurfaceTextureListener? = null

    interface SurfaceTextureListener {
        fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int)
        fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int)
        fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean
        fun onSurfaceTextureUpdated(surface: SurfaceTexture?)
    }
}

private fun ViewGroup.layoutTextureView(
        previewResolution: Resolution?,
        scaleType: ScaleType?
) = when (scaleType) {
    ScaleType.CenterInside -> previewResolution?.centerInside(this)
    ScaleType.CenterCrop -> previewResolution?.centerCrop(this)
    else -> null
}

private fun Resolution.centerInside(view: ViewGroup) {
    val scale = Math.min(
            view.measuredWidth / width.toFloat(),
            view.measuredHeight / height.toFloat()
    )

    val width = (width * scale).toInt()
    val height = (height * scale).toInt()

    val extraX = Math.max(0, view.measuredWidth - width)
    val extraY = Math.max(0, view.measuredHeight - height)

    val rect = Rect(
            extraX / 2,
            extraY / 2,
            width + extraX / 2,
            height + extraY / 2
    )

    view.layoutChildrenAt(rect)
}

private fun Resolution.centerCrop(view: ViewGroup) {
    val scale = Math.max(
            view.measuredWidth / width.toFloat(),
            view.measuredHeight / height.toFloat()
    )

    val width = (width * scale).toInt()
    val height = (height * scale).toInt()

    val extraX = Math.max(0, width - view.measuredWidth)
    val extraY = Math.max(0, height - view.measuredHeight)

    val rect = Rect(
            -extraX / 2,
            -extraY / 2,
            width - extraX / 2,
            height - extraY / 2
    )

    view.layoutChildrenAt(rect)
}

private fun ViewGroup.layoutChildrenAt(rect: Rect) {
    (0 until childCount).forEach {
        getChildAt(it).layout(
                rect.left,
                rect.top,
                rect.right,
                rect.bottom
        )
    }
}
