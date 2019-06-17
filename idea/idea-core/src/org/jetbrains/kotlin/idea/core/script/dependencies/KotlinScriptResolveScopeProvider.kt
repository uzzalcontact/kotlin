/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script.dependencies

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.psi.ResolveScopeProvider
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.script.ScriptDependenciesManager
import org.jetbrains.kotlin.idea.core.script.StandardIdeScriptDefinition
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.findScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.KotlinScriptDefinitionFromAnnotatedTemplate

class KotlinScriptResolveScopeProvider : ResolveScopeProvider() {
    companion object {
        // Used in LivePlugin
        val USE_NULL_RESOLVE_SCOPE = "USE_NULL_RESOLVE_SCOPE"
    }

    override fun getResolveScope(file: VirtualFile, project: Project): GlobalSearchScope? {
        if (file.fileType != KotlinFileType.INSTANCE) return null

        val ktFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return null
        val scriptDefinition = ktFile.findScriptDefinition()
        return when {
            scriptDefinition == null -> null
            // This is a workaround for completion in scripts and REPL to provide module dependencies
            scriptDefinition.baseClassType.fromClass == Any::class -> null
            scriptDefinition.asLegacyOrNull<StandardIdeScriptDefinition>() != null -> null
            scriptDefinition is ScriptDefinition.FromConfigurations || scriptDefinition.asLegacyOrNull<KotlinScriptDefinitionFromAnnotatedTemplate>() != null -> {
                GlobalSearchScope.fileScope(project, file)
                    .union(ScriptDependenciesManager.getInstance(project).getScriptDependenciesClassFilesScope(file))
            }
            else -> null
        }
    }
}