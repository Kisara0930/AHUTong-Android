/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package androidx.compose.material.icons.rounded

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Rounded.Lightbulb: ImageVector
    get() {
        if (_lightbulb != null) {
            return _lightbulb!!
        }
        _lightbulb = materialIcon(name = "Rounded.Lightbulb") {
            materialPath {
                moveTo(12.0f, 22.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                horizontalLineToRelative(-4.0f)
                curveTo(10.0f, 21.1f, 10.9f, 22.0f, 12.0f, 22.0f)
                close()
            }
            materialPath {
                moveTo(9.0f, 19.0f)
                horizontalLineToRelative(6.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, -0.45f, 1.0f, -1.0f)
                verticalLineToRelative(0.0f)
                curveToRelative(0.0f, -0.55f, -0.45f, -1.0f, -1.0f, -1.0f)
                horizontalLineTo(9.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, 0.45f, -1.0f, 1.0f)
                verticalLineToRelative(0.0f)
                curveTo(8.0f, 18.55f, 8.45f, 19.0f, 9.0f, 19.0f)
                close()
            }
            materialPath {
                moveTo(12.0f, 2.0f)
                curveTo(7.86f, 2.0f, 4.5f, 5.36f, 4.5f, 9.5f)
                curveToRelative(0.0f, 3.82f, 2.66f, 5.86f, 3.77f, 6.5f)
                horizontalLineToRelative(7.46f)
                curveToRelative(1.11f, -0.64f, 3.77f, -2.68f, 3.77f, -6.5f)
                curveTo(19.5f, 5.36f, 16.14f, 2.0f, 12.0f, 2.0f)
                close()
            }
        }
        return _lightbulb!!
    }

private var _lightbulb: ImageVector? = null
