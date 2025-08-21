package com.sakakit.brackets

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ApplyColorsStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Ensure the scheme has our colors applied at startup
        BracketColorSettings.getInstance().applyColorsToScheme()
    }
}
