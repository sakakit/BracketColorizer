package com.sakakit.brackets

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.util.NlsContexts
import com.intellij.ui.JBColor
import java.awt.Color

@State(name = "BracketColorSettings", storages = [Storage("BracketColorSettings.xml")])
class BracketColorSettings : PersistentStateComponent<BracketColorSettings.State> {
    class State {
        var colorsHex: MutableList<String> = defaultHexColors().toMutableList()
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
        applyColorsToScheme()
    }

    fun getColors(): List<Color> = myState.colorsHex.map { hex -> parseHex(hex) }

    fun setColor(index: Int, color: Color) {
        if (index in 0 until LEVEL_COUNT) {
            ensureSize()
            myState.colorsHex[index] = toHex(color)
        }
    }

    fun applyColorsToScheme() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val colors = getColors()
        for (i in 0 until LEVEL_COUNT) {
            val key = BracketKeys.LEVEL_KEYS[i]
            val attrs = TextAttributes(colors[i], null, null, null, 0)
            scheme.setAttributes(key, attrs)
        }
    }

    private fun ensureSize() {
        if (myState.colorsHex.size < LEVEL_COUNT) {
            val add = LEVEL_COUNT - myState.colorsHex.size
            repeat(add) { myState.colorsHex.add(defaultHexColors()[myState.colorsHex.size % LEVEL_COUNT]) }
        }
    }

    companion object {
        const val LEVEL_COUNT = 5

        fun defaultHexColors(): List<String> = listOf(
            "#CC7832", // orange
            "#9876AA", // purple
            "#6897BB", // blue
            "#6A8759", // green
            "#BBB529"  // yellow
        )

        fun parseHex(hex: String): Color = try {
            Color.decode(hex)
        } catch (_: Exception) {
            JBColor.GRAY
        }

        fun toHex(color: Color): String = "#%02X%02X%02X".format(color.red, color.green, color.blue)

        @JvmStatic
        fun getInstance(): BracketColorSettings = service()
    }
}

object BracketKeys {
    val LEVEL_KEYS = Array(BracketColorSettings.LEVEL_COUNT) { i ->
        com.intellij.openapi.editor.colors.TextAttributesKey.createTextAttributesKey(
            "BRACKET_COLOR_LEVEL_${i + 1}"
        )
    }
}
