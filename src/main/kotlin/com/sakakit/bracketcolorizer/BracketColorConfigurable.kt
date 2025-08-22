package com.sakakit.bracketcolorizer

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.ColorPanel
import com.intellij.ui.JBColor
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.components.JBCheckBox
import javax.swing.JComponent

/**
 * 設定画面（Settings/Preferences）にブラケットカラーの調整 UI を提供する Configurable。
 *
 * - 各ネストレベルごとに ColorPanel を配置します。
 * - 括弧タイプ（(), [], {}, <>）ごとの色付け有効/無効をチェックボックスで切り替えられます。
 * - 適用時にカラースキームへ反映し、ハイライトのデーモンを再起動します。
 *   また、Apply 押下で設定ダイアログを開いたままでも即座にエディタへ反映されます。
 */
class BracketColorConfigurable : SearchableConfigurable, Configurable.NoScroll, DumbAware {
    /**
     * 永続化されたブラケット色設定へのアクセスを提供するサービス。
     */
    private val settings = BracketColorSettings.getInstance()

    /**
     * 各ネストレベルの色を選択するための ColorPanel 配列。
     * UI 生成時に LEVEL_COUNT の数だけ行として並べます。
     */
    private val panels = Array(BracketColorSettings.LEVEL_COUNT) { ColorPanel() }

    /**
     * dsl builder で構築される設定 UI のルートパネル。
     * createComponent で初期化し、再利用します。
     */
    private var root: DialogPanel? = null

    // 括弧タイプごとの有効/無効チェックボックス
    private val cbRound = JBCheckBox("()")
    private val cbSquare = JBCheckBox("[]")
    private val cbCurly = JBCheckBox("{}")
    private val cbAngle = JBCheckBox("<>")

    /**
     * この設定ページの一意な ID を返します。
     * @return 設定ページ ID（検索可能設定用）
     */
    override fun getId(): String = "com.sakakit.brackets.settings"

    /**
     * 設定ページに表示する名称を返します。
     * @return 表示名
     */
    override fun getDisplayName(): String = "Bracket Colorizer"

    /**
     * 設定 UI のルートコンポーネントを生成します。
     * @return 設定 UI のルートコンポーネント
     */
    override fun createComponent(): JComponent {
        if (root == null) {
            root = panel {
                group("Enable coloring for bracket types") {
                    row {
                        cell(cbRound)
                        cell(cbSquare)
                        cell(cbCurly)
                        cell(cbAngle)
                    }
                }
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
        if (cbRound.isSelected != settings.isRoundEnabled()) return true
        if (cbSquare.isSelected != settings.isSquareEnabled()) return true
        if (cbCurly.isSelected != settings.isCurlyEnabled()) return true
        if (cbAngle.isSelected != settings.isAngleEnabled()) return true
        return false
    }

    /**
     * 選択された色を保存し、カラースキームに適用して、ハイライトを再起動します。
     */
    override fun apply() {
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            settings.setColor(i, panels[i].selectedColor ?: JBColor.BLACK)
        }
        // Save bracket-type flags
        settings.setRoundEnabled(cbRound.isSelected)
        settings.setSquareEnabled(cbSquare.isSelected)
        settings.setCurlyEnabled(cbCurly.isSelected)
        settings.setAngleEnabled(cbAngle.isSelected)

        settings.applyColorsToScheme()
        // 既存のアノテーション系再起動（念のため）
        // Restart for all open projects instead of null to ensure immediate refresh
        com.intellij.openapi.project.ProjectManager.getInstance().openProjects.forEach {
            DaemonCodeAnalyzer.getInstance(it).restart()
        }
        // 括弧タイプの有効/無効変更を即時反映（モーダル中でも動作）
        BracketColorRefresher.refreshAllOpenEditors()
    }

    /**
     * 設定画面の色選択を保存済みの値に戻します。
     */
    override fun reset() {
        val colors = settings.getColors()
        for (i in 0 until BracketColorSettings.LEVEL_COUNT) {
            panels[i].selectedColor = colors[i]
        }
        cbRound.isSelected = settings.isRoundEnabled()
        cbSquare.isSelected = settings.isSquareEnabled()
        cbCurly.isSelected = settings.isCurlyEnabled()
        cbAngle.isSelected = settings.isAngleEnabled()
    }
}
