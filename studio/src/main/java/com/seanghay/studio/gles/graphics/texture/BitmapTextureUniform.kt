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
package com.seanghay.studio.gles.graphics.texture

import android.graphics.Bitmap
import android.opengl.GLUtils

class BitmapTextureUniform(
  name: String,
  private val bitmap: Bitmap,
  texture2d: Texture2d = Texture2d()
) : Texture2dUniform(name, texture2d) {

    override fun configure() {
        super.configure()
        GLUtils.texImage2D(textureTarget, 0, bitmap, 0)
    }
}
