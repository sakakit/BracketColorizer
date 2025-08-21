package com.sakakit.bracketcolorizer

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.ui.JBColor
import java.awt.Color

/**
 * ブラケットの色設定を保持し、エディタのカラースキームへ適用するサービス。
 *
 * - PersistentStateComponent を実装し、ユーザーが選択した色を XML に永続化します。
 * - 各ネストレベルごとの TextAttributesKey に色を割り当てます。
 */
@State(name = "BracketColorSettings", storages = [Storage("BracketColorSettings.xml")])
class BracketColorSettings : PersistentStateComponent<BracketColorSettings.State> {
    /**
     * サービスの永続化対象となる内部状態。
     * colorsHex には各レベルの色を 16 進表記で保持します。
     */
    class State {
        var colorsHex: MutableList<String> = defaultHexColors().toMutableList()
    }

    /**
     * 現在のサービス状態。
     * PersistentStateComponent の getState()/loadState() で永続化・復元されます。
     * UI で選択された色は State.colorsHex に 16 進文字列として保持されます。
     */
    private var myState = State()

    /**
     * 現在の設定状態を返します。
     * @return 永続化される State オブジェクト
     */
    override fun getState(): State = myState

    /**
     * 永続化された状態を読み込み、エディタのカラースキームへ即時反映します。
     * @param state 保存済みの状態
     */
    override fun loadState(state: State) {
        myState = state
        applyColorsToScheme()
    }

    /**
     * 設定されている各レベルの色を Color リストで返します。
     * @return 各レベルの色を表す Color のリスト
     */
    fun getColors(): List<Color> = myState.colorsHex.map { hex -> parseHex(hex) }

    /**
     * 指定レベルの色を設定します。
     * @param index レベルインデックス（0 以上 LEVEL_COUNT 未満）
     * @param color 設定する色
     */
    fun setColor(index: Int, color: Color) {
        if (index in 0 until LEVEL_COUNT) {
            ensureSize()
            myState.colorsHex[index] = toHex(color)
        }
    }

    /**
     * 現在の設定色をエディタのグローバルカラースキームに適用します。
     * 各ネストレベルに対応する TextAttributesKey に前景色を設定します。
     */
    fun applyColorsToScheme() {
        val scheme = EditorColorsManager.getInstance().globalScheme
        val colors = getColors()
        for (i in 0 until LEVEL_COUNT) {
            val key = BracketKeys.LEVEL_KEYS[i]
            val attrs = TextAttributes(colors[i], null, null, null, 0)
            scheme.setAttributes(key, attrs)
        }
    }

    /**
     * colorsHex のサイズを LEVEL_COUNT 以上に拡張します。
     * 不足分はデフォルト色を循環して埋めます。
     */
    private fun ensureSize() {
        if (myState.colorsHex.size < LEVEL_COUNT) {
            val add = LEVEL_COUNT - myState.colorsHex.size
            repeat(add) { myState.colorsHex.add(defaultHexColors()[myState.colorsHex.size % LEVEL_COUNT]) }
        }
    }

    /**
     * 定数やユーティリティ関数を提供するコンパニオンオブジェクト。
     */
    companion object {
        /**
         * サポートするネストレベル数。
         */
        const val LEVEL_COUNT = 9

        /**
         * デフォルトの色（16進表記）リストを返します。
         * @return デフォルト色の 16 進表記（#RRGGBB）リスト
         */
        fun defaultHexColors(): List<String> = listOf(
            "#FF8C00", // Level1: DarkOrange
            "#EE82EE", // Level2: Violet
            "#9ACD32", // Level3: YellowGreen
            "#7B68EE", // Level4: MediumSlateBlue
            "#DAA520", // Level5: Goldenrod
            "#4169E1", // Level6: RoyalBlue
            "#FF00FF", // Level7: Fuchsia
            "#00CED1", // Level8: DarkTurquoise
            "#3CB371"  // Level9: MediumSeaGreen
        )

        /**
         * 16進表記の文字列から Color を生成します。失敗時は JBColor.GRAY を返します。
         * @param hex 例: "#CC7832"
         * @return 解析に成功した Color。失敗時は JBColor.GRAY
         */
        fun parseHex(hex: String): Color = try {
            Color.decode(hex)
        } catch (_: Exception) {
            JBColor.GRAY
        }

        /**
         * Color から 16進表記（#RRGGBB）へ変換します。
         * @param color 変換する対象の色（前景色）
         * @return 先頭に # を付けた RRGGBB 形式の 16 進文字列
         */
        fun toHex(color: Color): String = "#%02X%02X%02X".format(color.red, color.green, color.blue)

        /**
         * サービスインスタンスを取得します。
         * @return BracketColorSettings のシングルトンインスタンス
         */
        @JvmStatic
        fun getInstance(): BracketColorSettings = service()
    }
}

/**
 * ブラケットのネストレベルごとに TextAttributesKey を提供するユーティリティ。
 */
object BracketKeys {
    /**
     * ネストレベル 1..LEVEL_COUNT に対応する TextAttributesKey 配列。
     */
    val LEVEL_KEYS = Array(BracketColorSettings.LEVEL_COUNT) { i ->
        TextAttributesKey.createTextAttributesKey(
            "BRACKET_COLOR_LEVEL_${i + 1}"
        )
    }
}
