package com.magneto.spotyy.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.magneto.spotyy.review.ReviewNudgeService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MyProjectActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        withContext(Dispatchers.IO) {
            ReviewNudgeService.maybeShowReviewNudge(project)
        }
    }
}