package org.jetbrains.kotlin.gradle.targets.js.nodejs

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.BasePlugin
import org.gradle.api.tasks.Delete
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsRootExtension.Companion.EXTENSION_NAME
import org.jetbrains.kotlin.gradle.targets.js.npm.tasks.KotlinNpmInstallTask

open class NodeJsRootPlugin : Plugin<Project> {
    override fun apply(project: Project): Unit = project.run {
        check(project == project.rootProject) {
            "NodeJsRootPlugin can be applied only to root project"
        }

        this.extensions.create(EXTENSION_NAME, NodeJsRootExtension::class.java, this)

        val setupTask = tasks.create(NodeJsSetupTask.NAME, NodeJsSetupTask::class.java) {
            it.group = TASKS_GROUP_NAME
            it.description = "Download and install a local node/npm version"
        }

        tasks.create(KotlinNpmInstallTask.NAME, KotlinNpmInstallTask::class.java) {
            it.dependsOn(setupTask)
            it.outputs.upToDateWhen { false }
            it.group = TASKS_GROUP_NAME
            it.description = "Find, download and link NPM dependencies and projects"
        }

        setupCleanNodeModulesTask(project)
    }

    private fun setupCleanNodeModulesTask(project: Project) {
        val cleanKotlinNodeModules = project.tasks.create("cleanKotlinNodeModules", Delete::class.java) {
            it.description = "Deletes nodeJs projects created during build"
            it.group = BasePlugin.BUILD_GROUP
            it.delete.add(project.nodeJs.root.rootPackageDir)
        }

        project.plugins.apply(BasePlugin::class.java)
        project.tasks.getByName("clean").dependsOn(cleanKotlinNodeModules)

        project.tasks.create("cleanKotlinGradleNodeModules", Delete::class.java) {
            it.description = "Deletes node modules imported from gradle external modules"
            it.group = BasePlugin.BUILD_GROUP
            it.delete.add(project.nodeJs.root.nodeModulesGradleCacheDir)
        }
    }

    companion object {
        const val TASKS_GROUP_NAME: String = "nodeJs"

        fun apply(project: Project): NodeJsExtension {
            val rootProject = project.rootProject
            rootProject.plugins.apply(NodeJsRootPlugin::class.java)
            return rootProject.extensions.getByName(EXTENSION_NAME) as NodeJsExtension
        }
    }
}
