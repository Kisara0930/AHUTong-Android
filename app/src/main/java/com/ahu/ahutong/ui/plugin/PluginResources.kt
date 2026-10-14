@file:Suppress("DEPRECATION")

package com.ahu.ahutong.ui.plugin

import android.content.res.AssetFileDescriptor
import android.content.res.ColorStateList
import android.content.res.Resources
import android.content.res.Configuration
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.util.TypedValue
import java.io.InputStream

/**
 * Public API resource delegation for API 26+. Plugin resource packages use IDs above 0x7f;
 * shared Compose/design-system resources retain the host's 0x7f IDs.
 */
internal class PluginResources(
    private val own: Resources,
    private val host: Resources,
    private val dependencies: List<Resources>
) : Resources(own.assets, host.displayMetrics, host.configuration) {
    fun withConfiguration(configuration: Configuration): PluginResources = PluginResources(
        Resources(own.assets, own.displayMetrics, configuration),
        Resources(host.assets, host.displayMetrics, configuration),
        dependencies.map {
            if (it is PluginResources) it.withConfiguration(configuration)
            else Resources(it.assets, it.displayMetrics, configuration)
        }
    )
    private fun resource(id: Int): Resources {
        if ((id ushr 24) == 0x7f || (id ushr 24) == 0x01) return host
        return (listOf(own) + dependencies).firstOrNull {
            runCatching { it.getResourceName(id) }.isSuccess
        } ?: own
    }

    override fun getText(id: Int): CharSequence = resource(id).getText(id)
    override fun getText(id: Int, def: CharSequence?): CharSequence = resource(id).getText(id, def)
    override fun getString(id: Int): String = resource(id).getString(id)
    override fun getString(id: Int, vararg formatArgs: Any?): String = resource(id).getString(id, *formatArgs)
    override fun getQuantityText(id: Int, quantity: Int): CharSequence = resource(id).getQuantityText(id, quantity)
    override fun getQuantityString(id: Int, quantity: Int): String = resource(id).getQuantityString(id, quantity)
    override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
        resource(id).getQuantityString(id, quantity, *formatArgs)
    override fun getTextArray(id: Int): Array<CharSequence> = resource(id).getTextArray(id)
    override fun getStringArray(id: Int): Array<String> = resource(id).getStringArray(id)
    override fun getIntArray(id: Int): IntArray = resource(id).getIntArray(id)
    override fun getBoolean(id: Int): Boolean = resource(id).getBoolean(id)
    override fun getInteger(id: Int): Int = resource(id).getInteger(id)
    override fun getDimension(id: Int): Float = resource(id).getDimension(id)
    override fun getDimensionPixelSize(id: Int): Int = resource(id).getDimensionPixelSize(id)
    override fun getDimensionPixelOffset(id: Int): Int = resource(id).getDimensionPixelOffset(id)
    override fun getFraction(id: Int, base: Int, pbase: Int): Float = resource(id).getFraction(id, base, pbase)
    override fun getColor(id: Int): Int = resource(id).getColor(id)
    override fun getColor(id: Int, theme: Theme?): Int = resource(id).getColor(id, theme)
    override fun getColorStateList(id: Int): ColorStateList = resource(id).getColorStateList(id)
    override fun getColorStateList(id: Int, theme: Theme?): ColorStateList = resource(id).getColorStateList(id, theme)
    override fun getDrawable(id: Int): Drawable = resource(id).getDrawable(id)
    override fun getDrawable(id: Int, theme: Theme?): Drawable = resource(id).getDrawable(id, theme)
    override fun getDrawableForDensity(id: Int, density: Int): Drawable? = resource(id).getDrawableForDensity(id, density)
    override fun getDrawableForDensity(id: Int, density: Int, theme: Theme?): Drawable? =
        resource(id).getDrawableForDensity(id, density, theme)
    override fun getFont(id: Int): Typeface = resource(id).getFont(id)
    override fun getLayout(id: Int): XmlResourceParser = resource(id).getLayout(id)
    override fun getAnimation(id: Int): XmlResourceParser = resource(id).getAnimation(id)
    override fun getXml(id: Int): XmlResourceParser = resource(id).getXml(id)
    override fun openRawResource(id: Int): InputStream = resource(id).openRawResource(id)
    override fun openRawResource(id: Int, value: TypedValue): InputStream = resource(id).openRawResource(id, value)
    override fun openRawResourceFd(id: Int): AssetFileDescriptor? = resource(id).openRawResourceFd(id)
    override fun obtainTypedArray(id: Int): TypedArray = resource(id).obtainTypedArray(id)
    override fun getValue(id: Int, outValue: TypedValue, resolveRefs: Boolean) = resource(id).getValue(id, outValue, resolveRefs)
    override fun getValueForDensity(id: Int, density: Int, outValue: TypedValue, resolveRefs: Boolean) =
        resource(id).getValueForDensity(id, density, outValue, resolveRefs)
    override fun getValue(name: String, outValue: TypedValue, resolveRefs: Boolean) {
        val id = getIdentifier(name, null, null)
        getValue(id, outValue, resolveRefs)
    }
    override fun getIdentifier(name: String?, defType: String?, defPackage: String?): Int {
        for (source in listOf(own) + dependencies + host) {
            val id = source.getIdentifier(name, defType, defPackage)
            if (id != 0) return id
        }
        return 0
    }
    override fun getResourceName(id: Int): String = resource(id).getResourceName(id)
    override fun getResourcePackageName(id: Int): String = resource(id).getResourcePackageName(id)
    override fun getResourceTypeName(id: Int): String = resource(id).getResourceTypeName(id)
    override fun getResourceEntryName(id: Int): String = resource(id).getResourceEntryName(id)
}
