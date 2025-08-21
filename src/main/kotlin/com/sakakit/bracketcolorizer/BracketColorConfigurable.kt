package com.sakakit.bracketcolorizer

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent

/**
 * 設定画面（Settings/Preferences）にブラケットカラーの調整 UI を提供する Configurable。
 *
 * - 各ネストレベルごとに ColorPanel を配置します。
 * - 適用時にカラースキームへ反映し、ハイライトのデーモンを再起動します。
 */
class BracketColorConfigurable : SearchableConfigurable, Configurable.NoScroll, DumbAware {
    private val settings = BracketColorSettings.getInstance()

    private val panels = Array(BracketColorSettings.LEVEL_COUNT) { ColorPanel() }
    private var root: DialogPanel? = null

    override fun getId(): String = "com.sakakit.brackets.settings"
    override fun getDisplayName(): String = "Bracket Colorizer"

    /**
     * 設定 UI のルートコンポーネントを生成します。
     * @return 設定 UI のルートコンポーネント
     */
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

    /**
     * 画面上の選択色と保存済み設定が異なるかを判定します。
     * @return 差分がある場合は true、なければ false
     */
    override fun isModified(): Boolean {
        val colors = settings.getColors()
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            val c = panels[i].selectedColor ?: JBColor.BLACK
            if (c.rgb != colors[i].rgb) return true
        }
        return false
    }

    /**
     * 選択された色を保存し、カラースキームに適用して、ハイライトを再起動します。
     */
    override fun apply() {
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            settings.setColor(i, panels[i].selectedColor ?: JBColor.BLACK)
        }
        settings.applyColorsToScheme()
        // restart daemon highlighting
        DaemonCodeAnalyzer.getInstance(null).restart()
    }

    /**
     * 設定画面の色選択を保存済みの値に戻します。
     */
    override fun reset() {
        val colors = settings.getColors()
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            panels[i].selectedColor = colors[i]
        }
    }
}
