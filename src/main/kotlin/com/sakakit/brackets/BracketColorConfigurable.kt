package com.sakakit.brackets

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.dsl.builder.panel
import java.awt.Color
import javax.swing.JComponent

class BracketColorConfigurable : SearchableConfigurable, Configurable.NoScroll, DumbAware {
    private val settings = BracketColorSettings.getInstance()

    private val panels = Array(BracketColorSettings.LEVEL_COUNT) { ColorPanel() }
    private var root: DialogPanel? = null

    override fun getId(): String = "com.sakakit.brackets.settings"
    override fun getDisplayName(): String = "Bracket Colorizer"

    override fun createComponent(): JComponent {
        if (root == null) {
            root = panel {
                for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
                    row("Level ${i + 1}") {
                        cell(panels[i])
                    }
                }
            }
        }
        reset()
        return root as DialogPanel
    }

    override fun isModified(): Boolean {
        val colors = settings.getColors()
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            val c = panels[i].selectedColor ?: Color.BLACK
            if (c.rgb != colors[i].rgb) return true
        }
        return false
    }

    override fun apply() {
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            settings.setColor(i, panels[i].selectedColor ?: Color.BLACK)
        }
        settings.applyColorsToScheme()
        // restart daemon highlighting
        com.intellij.codeInsight.daemon.DaemonCodeAnalyzer.getInstance(null).restart()
    }

    override fun reset() {
        val colors = settings.getColors()
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            panels[i].selectedColor = colors[i]
        }
    }
}
