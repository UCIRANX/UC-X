package com.multivpn.app

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection

/** متن کاملاً انگلیسی یا عددی: همیشه از چپ به راست. */
val LtrStyle = TextStyle(textDirection = TextDirection.Ltr)

/** متن ترکیبی: جهت از اولین حرف قوی متن تعیین می‌شود (فارسی یا انگلیسی). */
val ContentStyle = TextStyle(textDirection = TextDirection.Content)
