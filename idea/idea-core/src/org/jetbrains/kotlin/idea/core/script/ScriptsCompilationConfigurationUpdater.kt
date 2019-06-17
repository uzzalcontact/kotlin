/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script

import com.intellij.openapi.application.Application
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.Alarm
import org.jetbrains.annotations.TestOnly
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.script.dependencies.AsyncScriptDependenciesLoader
import org.jetbrains.kotlin.idea.core.script.dependencies.FromFileAttributeScriptDependenciesLoader
import org.jetbrains.kotlin.idea.core.script.dependencies.OutsiderFileDependenciesLoader
import org.jetbrains.kotlin.idea.core.script.dependencies.SyncScriptDependenciesLoader
import org.jetbrains.kotlin.idea.util.ProjectRootsUtil
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.NotNullableUserDataProperty
import org.jetbrains.kotlin.scripting.definitions.KotlinScriptDefinition
import org.jetbrains.kotlin.scripting.definitions.ScriptDefinition
import org.jetbrains.kotlin.scripting.resolve.LegacyResolverWrapper
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationResult
import kotlin.script.experimental.dependencies.AsyncDependenciesResolver

class ScriptsCompilationConfigurationUpdater(
    private val project: Project,
    private val cache: ScriptsCompilationConfigurationCache
) {
    private val scriptsQueue = Alarm(Alarm.ThreadToUse.SWING_THREAD, project)
    private val scriptChangesListenerDelay = 1400

    private val loaders = arrayListOf(
        FromFileAttributeScriptDependenciesLoader(project),
        OutsiderFileDependenciesLoader(project),
        AsyncScriptDependenciesLoader(project),
        SyncScriptDependenciesLoader(project)
    )

    init {
        listenForChangesInScripts()
    }

    fun getCurrentCompilationConfiguration(file: KtFile): ScriptCompilationConfigurationResult? {
        cache[file]?.let { return it }

        updateDependencies(file)
        makeRootsChangeIfNeeded()

        return cache[file]
    }

    fun updateDependenciesIfNeeded(files: List<KtFile>): Boolean {
        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return false

        var wasDependenciesUpdateStarted = false
        for (file in files) {
            if (!areDependenciesCached(file)) {
                wasDependenciesUpdateStarted = true
                updateDependencies(file)
            }
        }

        if (wasDependenciesUpdateStarted) {
            makeRootsChangeIfNeeded()
        }

        return wasDependenciesUpdateStarted
    }

    private fun updateDependencies(file: KtFile) {
        if (!ScriptDefinitionsManager.getInstance(project).isReady()) return
        if (!cache.shouldRunDependenciesUpdate(file)) return

        loaders.filter { it.isApplicable(file) }.forEach { it.loadDependencies(file) }
    }

    private fun makeRootsChangeIfNeeded() {
        loaders.firstOrNull {
            it.notifyRootsChanged()
        }
    }

    private fun listenForChangesInScripts() {
        project.messageBus.connect().subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                runScriptDependenciesUpdateIfNeeded(file)
            }

            override fun selectionChanged(event: FileEditorManagerEvent) {
                event.newFile?.let { runScriptDependenciesUpdateIfNeeded(it) }
            }

            private fun runScriptDependenciesUpdateIfNeeded(file: VirtualFile) {
                val ktFile = getKtFileToStartConfigurationUpdate(file) ?: return

                updateDependencies(ktFile)
                makeRootsChangeIfNeeded()
            }
        })

        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                if (event.newFragment.isBlank() && event.oldFragment.isBlank()) return

                val document = event.document
                val file = FileDocumentManager.getInstance().getFile(document)?.takeIf { it.isInLocalFileSystem } ?: return
                val ktFile = getKtFileToStartConfigurationUpdate(file) ?: return

                if (!ktFile.isValid) {
                    cache.delete(ktFile)
                    return
                }


                // only update dependencies for scripts that were touched recently
                if (cache[ktFile] == null) {
                    return
                }

                scriptsQueue.cancelAllRequests()

                scriptsQueue.addRequest(
                    {
                        FileDocumentManager.getInstance().saveDocument(document)
                        updateDependencies(ktFile)
                        makeRootsChangeIfNeeded()
                    },
                    scriptChangesListenerDelay,
                    true
                )
            }
        }, project.messageBus.connect())
    }

    private fun getKtFileToStartConfigurationUpdate(file: VirtualFile): KtFile? {
        if (project.isDisposed || !file.isValid || file.fileType != KotlinFileType.INSTANCE) {
            return null
        }

        if (
            ApplicationManager.getApplication().isUnitTestMode &&
            ApplicationManager.getApplication().isScriptDependenciesUpdaterDisabled == true
        ) {
            return null
        }

        val ktFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return null
        if (ProjectRootsUtil.isInProjectSource(ktFile, includeScriptsOutsideSourceRoots = true)) {
            return ktFile
        }

        return null
    }

    private fun areDependenciesCached(file: KtFile): Boolean {
        return cache[file] != null || file.scriptDependencies != null
    }

    fun isAsyncDependencyResolver(scriptDef: ScriptDefinition): Boolean =
        scriptDef.asLegacyOrNull<KotlinScriptDefinition>()?.dependencyResolver?.let {
            it is AsyncDependenciesResolver || it is LegacyResolverWrapper
        } ?: false

    companion object {
        @JvmStatic
        fun getInstance(project: Project): ScriptsCompilationConfigurationUpdater =
            ServiceManager.getService(project, ScriptsCompilationConfigurationUpdater::class.java)

        fun areDependenciesCached(file: KtFile): Boolean {
            return getInstance(file.project).areDependenciesCached(file)
        }
    }
}

@set: TestOnly
var Application.isScriptDependenciesUpdaterDisabled by NotNullableUserDataProperty(
    Key.create("SCRIPT_DEPENDENCIES_UPDATER_DISABLED"),
    false
)