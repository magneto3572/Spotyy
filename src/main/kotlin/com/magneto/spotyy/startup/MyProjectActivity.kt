package com.magneto.spotyy.startup

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) {
            // Nothing to do here for now - removed sample code warning
            // Any future initialization should happen here
        }
    }
}