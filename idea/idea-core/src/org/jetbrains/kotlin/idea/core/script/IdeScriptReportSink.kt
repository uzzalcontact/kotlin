/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea.core.script

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.ui.EditorNotifications
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.UserDataProperty
import org.jetbrains.kotlin.scripting.resolve.ScriptReportSink
import kotlin.script.experimental.dependencies.ScriptReport

class IdeScriptReportSink(val project: Project) : ScriptReportSink {
    override fun attachReports(scriptFile: KtFile, reports: List<ScriptReport>) {
        if (getReports(scriptFile) == reports) return

        // TODO: persist errors between launches?
        scriptFile.scriptReports = reports

        ApplicationManager.getApplication().invokeLater {
            if (scriptFile.isValid && !project.isDisposed) {
                DaemonCodeAnalyzer.getInstance(project).restart(scriptFile)
                val virtualFile = scriptFile.virtualFile ?: return@invokeLater

                EditorNotifications.getInstance(project).updateNotifications(virtualFile)
            }
        }
    }

    companion object {
        fun getReports(file: KtFile): List<ScriptReport> {
            return file.scriptReports ?: emptyList()
        }

        private var KtFile.scriptReports: List<ScriptReport>? by UserDataProperty(Key.create("KOTLIN_SCRIPT_REPORTS"))
    }
}