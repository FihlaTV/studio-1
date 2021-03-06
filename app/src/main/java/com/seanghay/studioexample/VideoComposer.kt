package com.seanghay.studioexample

import android.content.Context
import android.graphics.*
import android.opengl.GLES20.*
import android.opengl.GLUtils
import android.opengl.Matrix
import android.util.Size
import android.util.SparseArray
import android.view.TextureView
import android.view.animation.AnticipateOvershootInterpolator
import androidx.core.util.contains
import androidx.core.util.set
import androidx.lifecycle.MutableLiveData
import com.seanghay.studio.core.StudioDrawable
import com.seanghay.studio.core.StudioRenderThread
import com.seanghay.studio.gles.annotation.GlContext
import com.seanghay.studio.gles.egl.glScope
import com.seanghay.studio.gles.graphics.FrameBuffer
import com.seanghay.studio.gles.graphics.Matrix4f
import com.seanghay.studio.gles.graphics.mat4
import com.seanghay.studio.gles.graphics.texture.Texture2d
import com.seanghay.studio.gles.kenburns.Kenburns
import com.seanghay.studio.gles.kenburns.SimpleKenburns
import com.seanghay.studio.gles.shader.AlphaOverlayTextureShader
import com.seanghay.studio.gles.shader.TextureShader
import com.seanghay.studio.gles.shader.filter.PackFilterShader
import com.seanghay.studio.gles.shader.filter.pack.PackFilter
import com.seanghay.studio.gles.shader.filter.tonecurve.ToneCurve
import com.seanghay.studio.gles.shader.filter.tonecurve.ToneCurveFilterShader
import com.seanghay.studio.gles.transition.TransitionStore
import com.seanghay.studio.gles.transition.TransitionalTextureShader
import com.seanghay.studio.utils.BitmapDiskCache
import com.seanghay.studio.utils.BitmapProcessor
import com.seanghay.studio.utils.clamp
import com.seanghay.studio.utils.smoothStep
import com.seanghay.studioexample.dao.md5
import io.reactivex.Single
import java.util.*
import kotlin.math.abs

class VideoComposer(private val context: Context) : StudioDrawable {


    var videoSize: Size = Size(1920, 1080)

    private val bitmapCache: BitmapDiskCache = BitmapDiskCache(context)

    private var studioRenderThread: StudioRenderThread? = null

    var width: Int = -1
    var height: Int = -1

    private var isReleased = false
    private var isSetup = false

    // Transitions
    private val transitions = TransitionStore.getAllTransitions()

    private val scenes = mutableListOf<Scene>()

    // Threading & Handlers
    private val preDrawRunnables: Queue<Runnable> = LinkedList()
    private val postDrawRunnables: Queue<Runnable> = LinkedList()

    // Texture
    private val textureShaders = transitions.associate { it.name to TransitionalTextureShader(it) }
    private val blankTexture = Texture2d()
    private var durations = longArrayOf()

    var watermarkBitmap = watermarkBitmap()

    private val watermarkShader = TextureShader()
    private val watermarkTexture = Texture2d()

    // Quotes
    private val quoteShader = AlphaOverlayTextureShader()
    private val quoteTexture = Texture2d()

    // Slide Quotes
    private val slideQuoteShader = AlphaOverlayTextureShader()
    private val slideQuotesTextures = SparseArray<Texture2d>()

    // Kenburns Effect
    private var mvpMatrix = mat4()
    private val kenburnsMatrix = mat4()

    private val newMatrix = mat4()
    private val kenburns: Kenburns = SimpleKenburns(0f, .1f)

    // Progression
    var progress: Float = 0f
    var totalDuration = 0L
        set(value) {
            field = value
            duration.postValue(value)
        }


    var duration: MutableLiveData<Long> = MutableLiveData()


    // Filters
    private val filterShader = PackFilterShader()
    private var defaultFilterPack = PackFilter()

    // Tone Curve Filter
    private val toneCurveFilterShader =
        ToneCurveFilterShader()

    // FrameBuffers
    private val filterFrameBuffer: FrameBuffer = FrameBuffer()
    private val frameBufferMvpMatrix = mat4()

    private val fromFrameBuffer: FrameBuffer = FrameBuffer()
    private val toFrameBuffer: FrameBuffer = FrameBuffer()
    private val toneCurveFrameBuffer: FrameBuffer = FrameBuffer()


    override fun renderAtProgress(progress: Float) {
        this.progress = progress
    }

    fun renderThread(): StudioRenderThread? = studioRenderThread

    fun getTransitions() = transitions

    fun getScenes(): List<Scene> = scenes

    fun removeScene(id: String, done: () -> Unit) {
        val scene = scenes.find { it.id == id } ?: return
        postDraw {
            scene.release()
            scenes.remove(scene)
            evaluateDuration()
            done()
        }
    }

    fun updateSceneCropType(
        id: String,
        cropType: BitmapProcessor.CropType
    ): Single<Bitmap> {
        return Single.create {

            val scene = scenes.find { it.id == id }

            if (scene == null) {
                it.onError(IllegalStateException())
                return@create
            }

            if (scene.cropType == cropType) {
                it.onSuccess(scene.bitmap)
                return@create
            }

            val hash = scene.id
            val scaledBitmap: Bitmap = if (bitmapCache.contains(hash + cropType.key())) {
                bitmapCache.get(hash + cropType.key())!!
            } else {
                val original = BitmapProcessor.loadSync(scene.originalPath)
                val bitmapProcessor = BitmapProcessor(original)
                bitmapProcessor.crop(videoSize.width, videoSize.height)
                bitmapProcessor.cropType(cropType)
                val bmp = bitmapProcessor.proceedSync()
                bitmapCache.set(hash + cropType.key(), bmp)
                original.recycle()
                bmp
            }

            scene.bitmap.recycle()
            scene.bitmap = scaledBitmap
            scene.cropType = cropType
            it.onSuccess(scaledBitmap)

            preDraw {
                scene.setup()
            }
        }
    }

    fun setScenes(bitmaps: List<Pair<String, Bitmap>>): Single<Boolean> {
        return Single.create<Boolean> {

            val cropType = BitmapProcessor.CropType.FIT_CENTER

            for ((path, bitmap) in bitmaps) {
                val hash = path.md5()

                // Skip if it's already added
                if (scenes.any { item -> item.id == hash }) continue

                val scaledBitmap: Bitmap = if (bitmapCache.contains(hash + cropType.key())) {
                    bitmapCache.get(hash + cropType.key())!!
                } else {
                    val bitmapProcessor = BitmapProcessor(bitmap)
                    bitmapProcessor.crop(videoSize.width, videoSize.height)
                    bitmapProcessor.cropType(cropType)
                    val bmp = bitmapProcessor.proceedSync()
                    bitmapCache.set(hash + cropType.key(), bmp)
                    bmp
                }

                val scene = Scene(hash, scaledBitmap, path)
                scenes.add(scene)
                preDraw { scene.setup() }
            }

            evaluateDuration()
            it.onSuccess(true)
        }
    }

    fun evaluateDuration() {
        totalDuration = 0L
        scenes.forEach {
            totalDuration += it.duration
        }

        var last = 0L
        durations = scenes.map {
            last += it.duration
            last
        }.toLongArray()

    }

    override fun onSetup() {
        if (isSetup) return
        textureShaders.forEach { it.value.setup() }
        setupToneCurve()
        setupBlackTexture()
        setupWatermarkShader()
        setupQuoteShader()
        setupFilters()
        setupFrameBuffer()

        preDraw {
            if (width != -1 && height != -1) {
                setMatrices()
                setFrameBufferMatrices()
                glScope("SetViewport") {
                    glViewport(0, 0, width, height)
                }
            }
        }

        val curveFile = context.assets.open("curves/Mark-Galer-Grading.acv")
        toneCurveFilterShader.applyToneCurve(ToneCurve.fromInputStream(curveFile))
        isSetup = true
    }

    private fun setupToneCurve() {
        toneCurveFilterShader.setup()
    }


    private fun setFrameBufferMatrices() {
        // Reset Matrix
        Matrix.setIdentityM(frameBufferMvpMatrix.elements, 0)

        // FrameBuffer texture is upside down, so we have to flip it back to normal
        // That's kinda sad right?
        Matrix.scaleM(frameBufferMvpMatrix.elements, 0, 1f, -1f, 1f)
        Matrix.multiplyMM(
            frameBufferMvpMatrix.elements,
            0,
            mvpMatrix.elements,
            0,
            frameBufferMvpMatrix.elements,
            0
        )
    }

    private fun setupFrameBuffer() {
        filterFrameBuffer.setup(width, height)
        fromFrameBuffer.setup(width, height)
        toFrameBuffer.setup(width, height)
        toneCurveFrameBuffer.setup(width, height)
    }


    private fun setupFilters() {
        filterShader.setup()
        filterShader.applyPackFilter(defaultFilterPack)
    }

    fun applyFilterPack(filter: PackFilter, refresh: Boolean = true) {
        defaultFilterPack = filter
        filterShader.applyPackFilter(defaultFilterPack, refresh)
    }

    fun getCurrentFilterPack(): PackFilter {
        return defaultFilterPack
    }

    private fun setupBlackTexture() {
        blankTexture.initialize()
        blankTexture.configure(GL_TEXTURE_2D)
    }

    private fun setupWatermarkShader() {
        watermarkShader.setup()
        watermarkTexture.initialize()
        watermarkTexture.use(GL_TEXTURE_2D) {
            watermarkTexture.configure(GL_TEXTURE_2D)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, watermarkBitmap, 0)
        }
    }

    private fun setupQuoteShader() {
        val quoteBitmap = TextBitmap.quoteBitmap(
            context,
            ""
        )

        quoteShader.setup()
        quoteTexture.initialize()
        quoteTexture.use(GL_TEXTURE_2D) {
            quoteTexture.configure(GL_TEXTURE_2D)
            GLUtils.texImage2D(GL_TEXTURE_2D, 0, quoteBitmap, 0)
        }
        slideQuoteShader.setup()
    }


    private fun setMatrices() {
        val ratio = (width.toFloat() / height.toFloat())
        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val outputMatrix = FloatArray(16)
        val scaleMatrix = FloatArray(16)

        Matrix.setIdentityM(scaleMatrix, 0)
        Matrix.frustumM(projectionMatrix, 0, -ratio, ratio, -1f, 1f, 3f, 7f)
        Matrix.setLookAtM(viewMatrix, 0, 0f, 0f, -3f, 0f, 0f, 0f, 0f, -1.0f, 0.0f)
        Matrix.multiplyMM(outputMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // Scale it here, so we don't have to change our vertices
        Matrix.scaleM(scaleMatrix, 0, ratio, 1f, 1f)
        Matrix.multiplyMM(mvpMatrix.elements, 0, outputMatrix, 0, scaleMatrix, 0)
    }

    @GlContext
    private inline fun preDraw(crossinline run: () -> Unit) {
        preDrawRunnables.add(Runnable { run() })
    }

    @GlContext
    private inline fun postDraw(crossinline run: () -> Unit) {
        postDrawRunnables.add(Runnable { run() })
    }


    override fun onSizeChanged(size: Size) {
        this@VideoComposer.width = size.width
        this@VideoComposer.height = size.height
    }


    val surfaceTextureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(
            surfaceTexture: SurfaceTexture?,
            width: Int,
            height: Int
        ) {
            this@VideoComposer.width = width
            this@VideoComposer.height = height

            studioRenderThread?.let {
                it.height = height
                it.width = width
            }
        }

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture?) {

        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture?): Boolean {
            release()
            return false
        }

        override fun onSurfaceTextureAvailable(
            surfaceTexture: SurfaceTexture?,
            width: Int,
            height: Int
        ) {
            if (surfaceTexture == null) return

            this@VideoComposer.width = width
            this@VideoComposer.height = height

            studioRenderThread = StudioRenderThread(surfaceTexture).also {
                it.height = height
                it.width = width
                it.drawable = this@VideoComposer
                it.start()
            }
        }
    }

    @GlContext
    private fun clearColor() {
        glScope("ClearColor") {
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glClearColor(0f, 0f, 0f, 1f)
        }
    }

    private val defaultPack = PackFilter()
    private val interpolator = AnticipateOvershootInterpolator()

    override fun onDraw(): Boolean {
        run(preDrawRunnables)

        glScope("EnableBlending") {
            glEnable(GL_BLEND)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)
        }

        clearColor()

        val seekAt = totalDuration * progress
        val seekIndex = calculateIndexFromDuration(seekAt) ?: return false
        val offset = calculateSeekOffset(seekIndex, seekAt)
        val currentScene = scenes[seekIndex]


        val currentTexture = currentScene.texture
        val nextScene = scenes.getOrNull(seekIndex + 1)
        val nextTexture = nextScene?.texture ?: blankTexture


        val textureShader = textureShaders[currentScene.transition.name] ?: return false
        val interpolatedOffset = interpolateOffset(currentScene, offset).smoothStep(0f, 1f)

        filterShader.mvpMatrix = frameBufferMvpMatrix

        textureShader.mvpMatrix = calculateMvpMatrix(offset, seekIndex)
        textureShader.progress = interpolatedOffset

        clearColor()
        filterShader.applyPackFilter(currentScene.filter)
        fromFrameBuffer.use {
            filterShader.draw(currentTexture)
        }

        clearColor()
        filterShader.applyPackFilter(nextScene?.filter ?: defaultPack)

        toFrameBuffer.use {
            filterShader.draw(nextTexture)
        }

        filterShader.applyPackFilter(nextScene?.filter ?: defaultPack)
        filterFrameBuffer.use {
            textureShader.draw(fromFrameBuffer.asTexture(), toFrameBuffer.asTexture())
        }

        applyFilterPack(defaultFilterPack, false)
        filterShader.draw(filterFrameBuffer.asTexture())

        quoteShader.mvpMatrix = mvpMatrix
        quoteShader.draw(quoteTexture)

        val slideStartOffset = calculateStartOffset(currentScene, offset)

        slideQuoteShader.alpha = slideStartOffset
        slideQuoteShader.translateY = kenburns.getValue(offset)
        slideQuoteShader.translateX = -interpolator.getInterpolation(kenburns.getValue(offset))
        slideQuoteShader.setMatrix(mvpMatrix)

        if (slideQuotesTextures.contains(seekIndex)) {
            val tex = slideQuotesTextures[seekIndex]
            slideQuoteShader.draw(tex)
        }

        watermarkShader.mvpMatrix = mvpMatrix
        watermarkShader.draw(watermarkTexture)

//        toneCurveFilterShader.mvpMatrix = mvpMatrix
//        toneCurveFilterShader.draw(toneCurveFrameBuffer.asTexture())
        run(postDrawRunnables)
        return true
    }

    private fun calculateStartOffset(scene: Scene, offset: Float): Float {
        val animationDuration = 250L
        val sceneDuration = scene.duration
        val diff = animationDuration.toFloat() / sceneDuration.toFloat()
        return (offset / diff).clamp(0f, 1f) * (1f - interpolateOffset(scene, offset))
    }


    private fun calculateMvpMatrix(offset: Float, seekIndex: Int): Matrix4f {
        val f = seekIndex % 2

        val scale = kenburns.getValue(abs(f - offset))

        Matrix.setIdentityM(kenburnsMatrix.elements, 0)
        Matrix.setIdentityM(newMatrix.elements, 0)

        Matrix.scaleM(kenburnsMatrix.elements, 0, 1f + scale, 1f + scale, 1f)
        Matrix.multiplyMM(newMatrix.elements, 0, mvpMatrix.elements, 0, kenburnsMatrix.elements, 0)

        return newMatrix
    }

    @Synchronized
    fun release() {
        if (isReleased) return
        postDraw {
            scenes.forEach { it.release() }
            textureShaders.forEach { it.value.release() }
            studioRenderThread?.quit()
            isReleased = true
        }
    }

    private fun interpolateOffset(scene: Scene, offset: Float): Float {
        val slideDuration = scene.duration
        val transitionDuration = scene.transition.duration
        val diff = transitionDuration.toFloat() / slideDuration.toFloat()
        return ((offset - (1f - diff)) / diff).clamp(0f, 1f)
    }

    private fun run(runnables: Queue<Runnable>) {
        while (runnables.isNotEmpty()) runnables.poll()?.run()
    }

    private fun calculateIndexFromDuration(seekAt: Float): Int? {
        for ((index, value) in durations.withIndex()) {
            if (seekAt <= value) return index
        }
        return null
    }

    private fun calculateSeekOffset(index: Int, seekAt: Float): Float {
        val last = durations.getOrElse(index - 1) { 0L }
        return (seekAt - last) / scenes[index].duration.toFloat()
    }

    private fun watermarkBitmap(): Bitmap {
        val margin = 20
        val w = videoSize.width
        val h = videoSize.height

        val icon = BitmapFactory.decodeResource(context.resources, R.drawable.togness)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val iconRatio = icon.width.toFloat() / icon.height.toFloat()
        val dstWidth = (w.toFloat() / 5f).toInt()
        val dstHeight = (dstWidth / iconRatio).toInt()

        val left = w - dstWidth
        val top = h - dstHeight
        val dstRect = Rect(left - margin, top - margin, w - margin, h - margin)
        canvas.drawBitmap(icon, null, dstRect, null)
        return bitmap
    }

    fun applyQuoteBitmap(bitmap: Bitmap) {
        postDraw {
            quoteTexture.use(GL_TEXTURE_2D) {
                GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
            }
        }
    }


    fun setQuoteAt(at: Int, bitmap: Bitmap) {
        val texture = slideQuotesTextures.get(at)
        if (texture == null) {
            val texture2d = Texture2d()

            preDraw {
                texture2d.initialize()
                texture2d.use(GL_TEXTURE_2D) {
                    quoteTexture.configure(GL_TEXTURE_2D)
                    GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
                }

                slideQuotesTextures[at] = texture2d
            }

        } else {
            preDraw {
                texture.use(GL_TEXTURE_2D) {
                    quoteTexture.configure(GL_TEXTURE_2D)
                    GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)
                }
            }
        }
    }

}