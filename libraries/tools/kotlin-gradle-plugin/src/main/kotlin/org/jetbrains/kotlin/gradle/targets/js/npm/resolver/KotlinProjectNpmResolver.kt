/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm.resolver

import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinSingleTargetExtension
import org.jetbrains.kotlin.gradle.dsl.kotlinExtensionOrNull
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import org.jetbrains.kotlin.gradle.plugin.KotlinTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJsCompilation
import org.jetbrains.kotlin.gradle.targets.js.RequiredKotlinJsDependency
import org.jetbrains.kotlin.gradle.targets.js.nodejs.nodeJs
import org.jetbrains.kotlin.gradle.targets.js.npm.RequiresNpmDependencies
import org.jetbrains.kotlin.gradle.targets.js.npm.npmProject
import org.jetbrains.kotlin.gradle.targets.js.npm.resolved.KotlinProjectNpmResolution
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinPackageJsonTask
import org.jetbrains.kotlin.gradle.tasks.createOrRegisterTask

internal class KotlinProjectNpmResolver(
    val project: Project,
    val resolver: KotlinRootNpmResolver
) {
    override fun toString(): String = "ProjectNpmResolver($project)"

    private val byCompilation = mutableMapOf<KotlinJsCompilation, KotlinCompilationNpmResolver>()

    operator fun get(compilation: KotlinJsCompilation): KotlinCompilationNpmResolver {
        check(compilation.target.project == project)
        return byCompilation[compilation] ?: error("$compilation was not registered in $this")
    }

    private var closed = false

    val compilationResolvers: Collection<KotlinCompilationNpmResolver>
        get() = byCompilation.values

    private val taskRequirements = mutableMapOf<RequiresNpmDependencies, Collection<RequiredKotlinJsDependency>>()
    val requiredFromTasksByCompilation = mutableMapOf<KotlinJsCompilation, MutableList<RequiresNpmDependencies>>()
    var hasNodeModulesDependentTasks = false
        private set

    init {
        addContainerListeners()
    }

    private fun addContainerListeners() {
        project.tasks.forEach { task ->
            if (task.enabled && task is RequiresNpmDependencies) {
                addTaskRequirements(task)
            }
        }

        val kotlin = project.kotlinExtensionOrNull
        when (kotlin) {
            is KotlinSingleTargetExtension -> addTargetListeners(kotlin.target)
            is KotlinMultiplatformExtension -> kotlin.targets.forEach {
                addTargetListeners(it)
            }
            else -> error("NodeJSPlugin can be applied only after Kotlin plugin")
        }

    }

    private fun addTargetListeners(target: KotlinTarget) {
        check(!closed) { resolver.alreadyResolvedMessage("add target $target") }

        if (target.platformType == KotlinPlatformType.js) {
            target.compilations.forEach { compilation ->
                if (compilation is KotlinJsCompilation) {
                    // compilation may be KotlinWithJavaTarget for old Kotlin2JsPlugin
                    addCompilation(compilation)
                }
            }
        }
    }

    @Synchronized
    private fun addCompilation(compilation: KotlinJsCompilation) {
        check(!closed) { resolver.alreadyResolvedMessage("add compilation $compilation") }

        byCompilation[compilation] = KotlinCompilationNpmResolver(this, compilation)
    }

    @Synchronized
    private fun addTaskRequirements(task: RequiresNpmDependencies) {
        check(!closed) { resolver.alreadyResolvedMessage("create task $task that requires npm dependencies") }

        if (!hasNodeModulesDependentTasks && task.nodeModulesRequired) {
            hasNodeModulesDependentTasks = true
        }

        val requirements = task.requiredNpmDependencies.toList()

        taskRequirements[task] = requirements

        requiredFromTasksByCompilation
            .getOrPut(task.compilation) { mutableListOf() }
            .add(task)
    }

    fun close(): KotlinProjectNpmResolution {
        check(!closed)
        closed = true

        return KotlinProjectNpmResolution(
            project,
            byCompilation.values.map { it.close() },
            taskRequirements
        )
    }
}