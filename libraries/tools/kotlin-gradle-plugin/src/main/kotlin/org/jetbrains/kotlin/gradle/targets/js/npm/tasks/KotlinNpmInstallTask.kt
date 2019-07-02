/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.targets.js.npm.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction
import org.jetbrains.kotlin.gradle.targets.js.nodejs.nodeJs
import org.jetbrains.kotlin.gradle.targets.js.npm.npmProject
import org.jetbrains.kotlin.gradle.targets.js.npm.resolver.KotlinRootNpmResolver
import java.io.File

open class KotlinNpmInstallTask : DefaultTask() {
    private val resolver: KotlinRootNpmResolver
        get() = project.nodeJs.root.requireResolver()

    init {
        check(project == project.rootProject)
    }

    @get:InputFiles
    val packageJsonFiles: Collection<File>
        get() = project.nodeJs.root.resolutionState.compilations.map { it.npmProject.packageJsonFile }

    @TaskAction
    fun resolve() {
        project.nodeJs.root.resolveIfNeeded(project)
    }

    companion object {
        const val NAME = "kotlinNpmInstall"
    }
}