/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.dependencies

import com.intellij.openapi.project.Project
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.idea.core.script.ScriptDependenciesManager
import org.jetbrains.kotlin.idea.highlighter.OutsidersPsiFileSupportUtils
import org.jetbrains.kotlin.idea.highlighter.OutsidersPsiFileSupportWrapper
import org.jetbrains.kotlin.psi.KtFile

class OutsiderFileDependenciesLoader(project: Project) : ScriptDependenciesLoader(project) {
    override fun isApplicable(file: KtFile): Boolean {
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile ?: return false
        return OutsidersPsiFileSupportWrapper.isOutsiderFile(virtualFile)
    }

    override fun loadDependencies(file: KtFile) {
        val virtualFile = file.virtualFile ?: file.originalFile.virtualFile ?: return
        val fileOrigin = OutsidersPsiFileSupportUtils.getOutsiderFileOrigin(project, virtualFile) ?: return
        val psiFileOrigin = PsiManager.getInstance(project).findFile(fileOrigin) as? KtFile ?: return
        val compilationConfiguration =
            ScriptDependenciesManager.getInstance(project).getRefinedCompilationConfiguration(psiFileOrigin) ?: return
        saveToCache(file, compilationConfiguration)
    }

    override fun shouldShowNotification(): Boolean = false
}