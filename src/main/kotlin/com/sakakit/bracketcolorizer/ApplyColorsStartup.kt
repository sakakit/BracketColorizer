package com.sakakit.bracketcolorizer

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * プロジェクト起動時にブラケット色設定をエディタのカラースキームへ適用するアクティビティ。
 *
 * このクラスは IDE のプロジェクトが開かれた直後に実行され、
 * 保存済みの設定色を TextAttributes としてグローバルスキームへ反映します。
 */
class ApplyColorsStartup : ProjectActivity {
    /**
     * プロジェクト起動時に設定済みのブラケットカラーを適用します。
     * @param project 対象のプロジェクト
     */
    override suspend fun execute(project: Project) {
        // Ensure the scheme has our colors applied at startup
        BracketColorSettings.getInstance().applyColorsToScheme()
    }
}
