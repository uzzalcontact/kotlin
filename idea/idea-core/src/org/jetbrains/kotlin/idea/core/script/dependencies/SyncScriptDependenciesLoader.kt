/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.dependencies

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.idea.core.script.ScriptsCompilationConfigurationUpdater
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.KtFileScriptSource
import org.jetbrains.kotlin.scripting.resolve.refineScriptCompilationConfiguration

class SyncScriptDependenciesLoader(project: Project) : ScriptDependenciesLoader(project) {
    override fun isApplicable(file: KtFile): Boolean {
        val scriptDefinition = file.findScriptDefinition() ?: return false
        return !ScriptsCompilationConfigurationUpdater.getInstance(project).isAsyncDependencyResolver(scriptDefinition)
    }

    override fun loadDependencies(file: KtFile) {
        val scriptDef = file.findScriptDefinition() ?: return
        debug(file) { "start sync dependencies loading" }
        val result = refineScriptCompilationConfiguration(KtFileScriptSource(file), scriptDef, project)
        debug(file) { "finish sync dependencies loading" }
        processRefinedConfiguration(result, file)
    }

    override fun shouldShowNotification(): Boolean = false
}