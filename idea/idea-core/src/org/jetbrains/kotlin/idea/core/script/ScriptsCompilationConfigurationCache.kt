/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script

import com.intellij.ProjectTopics
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.OrderRootType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementFinder
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.NonClasspathDirectoriesScope
import com.intellij.util.containers.ConcurrentFactoryMap
import com.intellij.util.containers.ContainerUtil
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.kotlin.idea.core.util.EDT
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationResult
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty
import kotlin.reflect.KProperty0
import kotlin.reflect.jvm.isAccessible
import kotlin.script.experimental.api.valueOrNull

class ScriptsCompilationConfigurationCache(private val project: Project) {

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                clearCaches()
            }
        })
    }

    private val cacheLock = ReentrantReadWriteLock()

    private val scriptDependenciesCache = ContainerUtil.createConcurrentWeakMap<KtFile, ScriptCompilationConfigurationResult>()
    private val scriptsModificationStampsCache = ContainerUtil.createConcurrentWeakMap<KtFile, Long>()

    private val <T, P> MutableMap<T, P>.notNullEntries get() = this.entries.filter { it.value != null }

    operator fun get(file: KtFile): ScriptCompilationConfigurationResult? = scriptDependenciesCache[file]

    fun shouldRunDependenciesUpdate(file: KtFile): Boolean {
        if (scriptsModificationStampsCache[file] != file.modificationStamp) {
            scriptsModificationStampsCache[file] = file.modificationStamp
            return true
        }
        return false
    }

    private val scriptsDependenciesClasspathScopeCache: MutableMap<KtFile, GlobalSearchScope> = ConcurrentFactoryMap.createWeakMap {
        val compilationConfiguration = scriptDependenciesCache[it]?.valueOrNull()
            ?: return@createWeakMap GlobalSearchScope.EMPTY_SCOPE
        val roots = compilationConfiguration.dependenciesClassPath

        val sdk = ScriptDependenciesManager.getScriptSdk(compilationConfiguration)

        @Suppress("FoldInitializerAndIfToElvis")
        if (sdk == null) {
            return@createWeakMap NonClasspathDirectoriesScope.compose(ScriptDependenciesManager.toVfsRoots(roots))
        }

        return@createWeakMap NonClasspathDirectoriesScope.compose(
            sdk.rootProvider.getFiles(OrderRootType.CLASSES).toList() +
                    ScriptDependenciesManager.toVfsRoots(roots)
        )
    }

    fun scriptDependenciesClassFilesScope(file: VirtualFile): GlobalSearchScope {
        val ktFile = PsiManager.getInstance(project).findFile(file) as? KtFile ?: return GlobalSearchScope.EMPTY_SCOPE
        return scriptsDependenciesClasspathScopeCache[ktFile] ?: GlobalSearchScope.EMPTY_SCOPE
    }

    val allSdks by ClearableLazyValue(cacheLock) {
        scriptDependenciesCache.notNullEntries
            .mapNotNull { ScriptDependenciesManager.getInstance(project).getScriptSdk(it.key, project) }
            .distinct()
    }

    private val allNonIndexedSdks by ClearableLazyValue(cacheLock) {
        scriptDependenciesCache.notNullEntries
            .mapNotNull { ScriptDependenciesManager.getInstance(project).getScriptSdk(it.key, project) }
            .filterNonModuleSdk()
            .distinct()
    }

    val allDependenciesClassFiles by ClearableLazyValue(cacheLock) {
        val sdkFiles = allNonIndexedSdks
            .flatMap { it.rootProvider.getFiles(OrderRootType.CLASSES).toList() }

        val scriptDependenciesClasspath = scriptDependenciesCache.notNullEntries
            .flatMap { it.value.valueOrNull()?.dependenciesClassPath ?: emptyList() }.distinct()

        sdkFiles + ScriptDependenciesManager.toVfsRoots(scriptDependenciesClasspath)
    }

    val allDependenciesSources by ClearableLazyValue(cacheLock) {
        val sdkSources = allNonIndexedSdks
            .flatMap { it.rootProvider.getFiles(OrderRootType.SOURCES).toList() }

        val scriptDependenciesSources = scriptDependenciesCache.notNullEntries
            .flatMap { it.value.valueOrNull()?.dependenciesSources ?: emptyList() }.distinct()
        sdkSources + ScriptDependenciesManager.toVfsRoots(scriptDependenciesSources)
    }

    val allDependenciesClassFilesScope by ClearableLazyValue(cacheLock) {
        NonClasspathDirectoriesScope.compose(allDependenciesClassFiles)
    }

    val allDependenciesSourcesScope by ClearableLazyValue(cacheLock) {
        NonClasspathDirectoriesScope.compose(allDependenciesSources)
    }

    private fun List<Sdk>.filterNonModuleSdk(): List<Sdk> {
        val moduleSdks = ModuleManager.getInstance(project).modules.map { ModuleRootManager.getInstance(it).sdk }
        return filterNot { moduleSdks.contains(it) }
    }

    private fun onChange(files: List<KtFile>) {
        clearCaches()
        updateHighlighting(files)
    }

    private fun clearCaches() {
        this::allSdks.clearValue()
        this::allNonIndexedSdks.clearValue()

        this::allDependenciesClassFiles.clearValue()
        this::allDependenciesClassFilesScope.clearValue()

        this::allDependenciesSources.clearValue()
        this::allDependenciesSourcesScope.clearValue()

        scriptsDependenciesClasspathScopeCache.clear()

        val kotlinScriptDependenciesClassFinder =
            Extensions.getArea(project).getExtensionPoint(PsiElementFinder.EP_NAME).extensions
                .filterIsInstance<KotlinScriptDependenciesClassFinder>()
                .single()

        kotlinScriptDependenciesClassFinder.clearCache()
    }

    private fun updateHighlighting(files: List<KtFile>) {
        ScriptDependenciesModificationTracker.getInstance(project).incModificationCount()

        GlobalScope.launch(EDT(project)) {
            files.filter { it.isValid }.forEach {
                DaemonCodeAnalyzer.getInstance(project).restart(it)
            }
        }
    }

    fun hasNotCachedRoots(compilationConfiguration: ScriptCompilationConfigurationWrapper): Boolean {
        val scriptSdk =
            ScriptDependenciesManager.getScriptSdk(compilationConfiguration) ?: ScriptDependenciesManager.getScriptDefaultSdk(project)
        return (scriptSdk != null && !allSdks.contains(scriptSdk)) ||
                !allDependenciesClassFiles.containsAll(ScriptDependenciesManager.toVfsRoots(compilationConfiguration.dependenciesClassPath)) ||
                !allDependenciesSources.containsAll(ScriptDependenciesManager.toVfsRoots(compilationConfiguration.dependenciesSources))
    }

    fun clear() {
        val keys = scriptDependenciesCache.notNullEntries.map { it.key }.toList()

        scriptDependenciesCache.clear()
        scriptsModificationStampsCache.clear()

        updateHighlighting(keys)
    }

    fun save(file: KtFile, new: ScriptCompilationConfigurationResult): Boolean {
        val old = scriptDependenciesCache[file]
        if (old == null || old != new) {
            scriptDependenciesCache[file] = new

            onChange(listOf(file))
            return true
        }

        return false
    }

    fun delete(file: KtFile): Boolean {
        val changed = scriptDependenciesCache.remove(file)
        if (changed != null) {
            onChange(listOf(file))
            return true
        }
        return false
    }
}

private fun <R> KProperty0<R>.clearValue() {
    isAccessible = true
    (getDelegate() as ClearableLazyValue<*, *>).clear()
}

private class ClearableLazyValue<in R, out T : Any>(
    private val lock: ReentrantReadWriteLock,
    private val compute: () -> T
) : ReadOnlyProperty<R, T> {
    override fun getValue(thisRef: R, property: KProperty<*>): T {
        lock.write {
            if (value == null) {
                value = compute()
            }
            return value!!
        }
    }

    private var value: T? = null


    fun clear() {
        lock.write {
            value = null
        }
    }
}