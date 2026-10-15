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

package androidx.compose.material.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.Drafts: ImageVector
    get() {
        if (_drafts != null) {
            return _drafts!!
        }
        _drafts = materialIcon(name = "Outlined.Drafts") {
            materialPath {
                moveTo(21.99f, 8.0f)
                curveToRelative(0.0f, -0.72f, -0.37f, -1.35f, -0.94f, -1.7f)
                lineTo(12.0f, 1.0f)
                lineTo(2.95f, 6.3f)
                curveTo(2.38f, 6.65f, 2.0f, 7.28f, 2.0f, 8.0f)
                verticalLineToRelative(10.0f)
                curveToRelative(0.0f, 1.1f, 0.9f, 2.0f, 2.0f, 2.0f)
                horizontalLineToRelative(16.0f)
                curveToRelative(1.1f, 0.0f, 2.0f, -0.9f, 2.0f, -2.0f)
                lineToRelative(-0.01f, -10.0f)
                close()
                moveTo(19.99f, 8.0f)
                verticalLineToRelative(0.01f)
                lineTo(12.0f, 13.0f)
                lineTo(4.0f, 8.0f)
                lineToRelative(8.0f, -4.68f)
                lineTo(19.99f, 8.0f)
                close()
                moveTo(4.0f, 18.0f)
                verticalLineToRelative(-7.66f)
                lineToRelative(8.0f, 5.02f)
                lineToRelative(7.99f, -4.99f)
                lineTo(20.0f, 18.0f)
                lineTo(4.0f, 18.0f)
                close()
            }
        }
        return _drafts!!
    }

private var _drafts: ImageVector? = null
