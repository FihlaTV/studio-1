/**
 * Designed and developed by Seanghay Yath (@seanghay)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.seanghay.studio.utils

import android.opengl.GLES20.GL_CLAMP_TO_EDGE
import android.opengl.GLES20.GL_COMPILE_STATUS
import android.opengl.GLES20.GL_FRAGMENT_SHADER
import android.opengl.GLES20.GL_LINEAR
import android.opengl.GLES20.GL_LINK_STATUS
import android.opengl.GLES20.GL_TEXTURE_2D
import android.opengl.GLES20.GL_TEXTURE_MAG_FILTER
import android.opengl.GLES20.GL_TEXTURE_MIN_FILTER
import android.opengl.GLES20.GL_TEXTURE_WRAP_S
import android.opengl.GLES20.GL_TEXTURE_WRAP_T
import android.opengl.GLES20.GL_TRUE
import android.opengl.GLES20.GL_VERTEX_SHADER
import android.opengl.GLES20.glAttachShader
import android.opengl.GLES20.glBindTexture
import android.opengl.GLES20.glCompileShader
import android.opengl.GLES20.glCreateProgram
import android.opengl.GLES20.glCreateShader
import android.opengl.GLES20.glDeleteProgram
import android.opengl.GLES20.glDeleteShader
import android.opengl.GLES20.glGenTextures
import android.opengl.GLES20.glGetProgramInfoLog
import android.opengl.GLES20.glGetProgramiv
import android.opengl.GLES20.glGetShaderInfoLog
import android.opengl.GLES20.glGetShaderiv
import android.opengl.GLES20.glLinkProgram
import android.opengl.GLES20.glShaderSource
import android.opengl.GLES20.glTexParameteri
import android.opengl.GLES20.glValidateProgram
import android.util.Log
import com.seanghay.studio.gles.annotation.GlContext
import com.seanghay.studio.gles.egl.glScope

object GlUtils {

    data class ShaderData(
      var program: Int,
      var vertexShader: Int,
      var fragmentShader: Int
    )

    private const val TAG = "GlCommon"

    const val FLOAT_SIZE_BYTES = 4
    const val NO_TEXTURE = -1

    fun setupTextureSampler(target: Int, mag: Int, min: Int) {
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, mag)
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, min)
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    @GlContext
    fun createShader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        if (shader == 0) throw RuntimeException("Cannot create shader $type\n$source")

        glShaderSource(shader, source)
        glCompileShader(shader)

        val args = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, args, 0)

        if (args.first() == 0) {
            Log.e(TAG, "Failed to compile shader source")
            Log.e(TAG, glGetShaderInfoLog(shader))

            glDeleteShader(shader)
            throw RuntimeException("Could not compile shader \n$source\n$type")
        }
        return shader
    }

    @GlContext
    fun createProgramWithShaders(
      vertexShaderSource: String,
      fragmentShaderSource: String
    ): ShaderData {
        val vertexShader = createShader(GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader = createShader(GL_FRAGMENT_SHADER, fragmentShaderSource)

        return createProgram(vertexShader, fragmentShader).run {
            ShaderData(this, vertexShader, fragmentShader)
        }
    }

    @GlContext
    fun createProgram(vertexShaderSource: String, fragmentShaderSource: String): Int {
        val vertexShader = createShader(GL_VERTEX_SHADER, vertexShaderSource)
        val fragmentShader =
            createShader(GL_FRAGMENT_SHADER, fragmentShaderSource)
        return createProgram(vertexShader, fragmentShader)
    }

    @GlContext
    fun createProgram(vertexShader: Int, fragmentShader: Int): Int {
        val program = glCreateProgram()
        if (program == 0) throw RuntimeException("Cannot create program")

        glScope("Attach vertexSource shader to program") {
            glAttachShader(
                program,
                vertexShader
            )
        }
        glScope("Attach fragmentSource shader to program") {
            glAttachShader(
                program,
                fragmentShader
            )
        }

        glLinkProgram(program)
        val args = IntArray(1)
        glGetProgramiv(program, GL_LINK_STATUS, args, 0)

        if (args.first() != GL_TRUE) {
            val info = glGetProgramInfoLog(program)
            glDeleteProgram(program)
            throw RuntimeException("Cannot link program $program, Info: $info")
        }

        glValidateProgram(program)

        // Remove shaders from
        glDeleteShader(vertexShader)
        glDeleteShader(fragmentShader)

        return program
    }

    @GlContext
    fun genTexture(bindBlock: ((Int) -> Unit)? = null): Int {

        val textures = IntArray(1)
        glScope("glGenTextures") { glGenTextures(textures.size, textures, 0) }
        glScope("glBindTexture") { glBindTexture(GL_TEXTURE_2D, textures.first()) }
        glScope("glTexParameteri") {
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        }

        bindBlock?.invoke(textures.first())

        // Unbind
        glBindTexture(GL_TEXTURE_2D, 0)
        return textures.first()
    }

    internal fun fail(message: String): Nothing {
        throw RuntimeException(message)
    }
}
