package com.seanghay.studio.gles.shader.filter

import com.seanghay.studio.gles.graphics.FrameBuffer
import com.seanghay.studio.gles.graphics.texture.Texture
import com.seanghay.studio.gles.shader.TextureShader
import com.seanghay.studio.gles.transition.TransitionalTextureShader

class CompositeShader(private val shaders: ArrayList<TextureShader> = arrayListOf()) {

    var width: Int = -1

    var height: Int = -1

    val frameBuffers = arrayListOf<FrameBuffer>()

    private fun invalidate() {
        frameBuffers.forEach { it.release() }
        frameBuffers.clear()

        for (shader in this.shaders) {

            shader.trySetup()

            if (width != -1 && height != -1)
                shader.setResolution(width.toFloat(), height.toFloat())

            val frameBuffer = FrameBuffer()
            frameBuffer.setup(width, height)
            frameBuffers.add(frameBuffer)
        }
    }

    fun add(vararg shaders: TextureShader) {
        this.shaders.addAll(shaders)
        invalidate()
    }

    fun setup(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    fun draw(texture: Texture) {
        for ((index, shader) in shaders.withIndex()) {
            if (index == 0) {
                frameBuffers[0].use {
                    shader.draw(texture)
                }
                continue
            }

            if (index == shaders.size - 1) {
                shader.isFlipVertical = true
                shader.draw(frameBuffers[index - 1].toTexture())
                continue
            }

            frameBuffers[index].use {
                shader.draw(frameBuffers[index - 1].toTexture())
            }
        }
    }



    fun draw(texture: Texture, tex2: Texture) {
        for ((index, shader) in shaders.withIndex()) {
            if (index == 0) {
                frameBuffers[0].use {
                    if (shader is TransitionalTextureShader)
                    shader.draw(texture, tex2)
                    else shader.draw(texture)
                }
                continue
            }

            if (index == shaders.size - 1) {
                shader.isFlipVertical = true
                shader.draw(frameBuffers[index - 1].toTexture())
                continue
            }

            frameBuffers[index].use {
                shader.draw(frameBuffers[index - 1].toTexture())
            }
        }
    }
}