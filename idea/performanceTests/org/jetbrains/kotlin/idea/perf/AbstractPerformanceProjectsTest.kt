/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzerSettings
import com.intellij.codeInsight.daemon.impl.DaemonCodeAnalyzerImpl
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.ide.highlighter.ModuleFileType
import com.intellij.ide.startup.impl.StartupManagerImpl
import com.intellij.idea.IdeaTestApplication
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeId
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.startup.StartupManager
import com.intellij.openapi.util.Computable
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.RunAll
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.EditorTestFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.util.ThrowableRunnable
import com.intellij.util.io.exists
import com.intellij.util.ui.UIUtil
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.framework.KotlinSdkType
import org.jetbrains.kotlin.idea.test.ConfigLibraryUtil
import org.jetbrains.kotlin.idea.test.invalidateLibraryCache
import java.io.File
import java.nio.file.Paths

abstract class AbstractPerformanceProjectsTest : UsefulTestCase() {

    // myProject is not required for all potential perf test cases
    protected var myProject: Project? = null
    private lateinit var jdk18: Sdk
    private lateinit var myApplication: IdeaTestApplication

    override fun setUp() {
        super.setUp()

        myApplication = IdeaTestApplication.getInstance()
        ApplicationManager.getApplication().runWriteAction {
            val jdkTableImpl = JavaAwareProjectJdkTableImpl.getInstanceEx()
            val homePath = if (jdkTableImpl.internalJdk.homeDirectory!!.name == "jre") {
                jdkTableImpl.internalJdk.homeDirectory!!.parent.path
            } else {
                jdkTableImpl.internalJdk.homePath!!
            }

            val javaSdk = JavaSdk.getInstance()
            jdk18 = javaSdk.createJdk("1.8", homePath)
            val internal = javaSdk.createJdk("IDEA jdk", homePath)

            val jdkTable = ProjectJdkTable.getInstance()
            jdkTable.addJdk(jdk18, testRootDisposable)
            jdkTable.addJdk(internal, testRootDisposable)
            KotlinSdkType.setUpIfNeeded()
        }
        InspectionProfileImpl.INIT_INSPECTIONS = true
    }

    override fun tearDown() {
        RunAll(
            ThrowableRunnable { super.tearDown() },
            ThrowableRunnable {
                if (myProject != null) {
                    DaemonCodeAnalyzerSettings.getInstance().isImportHintEnabled = true // return default value to avoid unnecessary save
                    (StartupManager.getInstance(myProject!!) as StartupManagerImpl).checkCleared()
                    (DaemonCodeAnalyzer.getInstance(myProject!!) as DaemonCodeAnalyzerImpl).cleanupAfterTest()
                    closeProject(myProject!!)
                    myProject = null
                }
            }).run()
    }

    private fun simpleFilename(fileName: String): String {
        val lastIndexOf = fileName.lastIndexOf('/')
        return if (lastIndexOf >= 0) fileName.substring(lastIndexOf + 1) else fileName
    }

    protected fun perfOpenProject(name: String, stats: Stats, path: String = "idea/testData/perfTest") {
        myProject = innerPerfOpenProject(name, stats, path = path, note = "")
    }

    protected fun innerPerfOpenProject(
        name: String,
        stats: Stats,
        note: String,
        path: String = "idea/testData/perfTest"
    ): Project {
        val projectPath = "$path/$name"

        val warmUpIterations = 1
        val iterations = 3
        val projectManagerEx = ProjectManagerEx.getInstanceEx()

        var lastProject: Project? = null
        var counter = 0

        stats.perfTest<Project, Project>(
            warmUpIterations = warmUpIterations,
            iterations = iterations,
            testName = "open project${if (note.isNotEmpty()) " $note" else ""}",
            test = {

                val project = projectManagerEx.loadAndOpenProject(projectPath)!!
                if (!Paths.get(projectPath, ".idea").exists()) {
                    initKotlinProject(project, projectPath, name)
                }

                projectManagerEx.openTestProject(project)

                (StartupManager.getInstance(project) as StartupManagerImpl).runPostStartupActivities()
                val changeListManagerImpl = ChangeListManager.getInstance(project) as ChangeListManagerImpl
                changeListManagerImpl.waitUntilRefreshed()

                project
            },
            tearDown = { project ->
                lastProject = project
                val prj = project!!

                VirtualFileManager.getInstance().syncRefresh()

                invalidateLibraryCache(project)

                CodeInsightTestFixtureImpl.ensureIndexesUpToDate(project)

                UIUtil.dispatchAllInvocationEvents()

                // close all project but last - we're going to return and use it further
                if (counter < warmUpIterations + iterations - 1) {
                    closeProject(prj)
                }
                counter++
            }
        )

        return lastProject!!
    }

    private fun initKotlinProject(
        project: Project,
        projectPath: String,
        name: String
    ) {
        val modulePath = "$projectPath/$name${ModuleFileType.DOT_DEFAULT_EXTENSION}"
        val projectFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(File(projectPath))!!
        val srcFile = projectFile.findChild("src")!!
        val module = ApplicationManager.getApplication().runWriteAction(Computable<Module> {
            val projectRootManager = ProjectRootManager.getInstance(project)
            with(projectRootManager) {
                projectSdk = jdk18
            }
            val moduleManager = ModuleManager.getInstance(project)
            val module = moduleManager.newModule(modulePath, ModuleTypeId.JAVA_MODULE)
            PsiTestUtil.addSourceRoot(module, srcFile)
            module
        })
        ConfigLibraryUtil.configureKotlinRuntimeAndSdk(module, jdk18)
    }

    protected fun perfHighlightFile(name: String, stats: Stats): List<HighlightInfo> =
        perfHighlightFile(myProject!!, name, stats)


    protected fun perfHighlightFile(
        project: Project,
        fileName: String,
        stats: Stats,
        note: String = ""
    ): List<HighlightInfo> {
        var highlightInfos: List<HighlightInfo> = emptyList()
        stats.perfTest(
            testName = "highlighting ${if (note.isNotEmpty()) "$note " else ""}${simpleFilename(fileName)}",
            setUp = {
                val fileInEditor = openFileInEditor(project, fileName)
                fileInEditor
            },
            test = { file ->
                Result(file!!, highlightFile(project, file!!.psiFile))
            },
            tearDown = {
                highlightInfos = it!!.highlightInfos
                commitAllDocuments()
                FileEditorManager.getInstance(project).closeFile(it!!.editorFile.psiFile.virtualFile)
            }
        )
        println("-".repeat(40))
        println("$fileName ->\n${highlightInfos.joinToString("\n")}")
        println()

        return highlightInfos
    }

    private fun highlightFile(project: Project, psiFile: PsiFile): List<HighlightInfo> {
        val document = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile)!!
        val editor = EditorFactory.getInstance().getEditors(document).first()
        return CodeInsightTestFixtureImpl.instantiateAndRun(psiFile, editor, IntArray(0), false)
    }

    private fun openFileInEditor(project: Project, name: String): EditorFile {
        val fileDocumentManager = FileDocumentManager.getInstance()
        val fileEditorManager = FileEditorManager.getInstance(project)
        val psiDocumentManager = PsiDocumentManager.getInstance(project)

        val psiFile = projectFileByName(project, name)
        val vFile = psiFile.virtualFile

        runInEdtAndWait {
            fileEditorManager.openFile(vFile, true)
        }
        val document = fileDocumentManager.getDocument(vFile)!!
//        runInEdtAndWait {
//            fileDocumentManager.reloadFromDisk(document)
//        }

        assertNotNull(EditorFactory.getInstance().getEditors(document))
        assertTrue(document.text.isNotEmpty())

//        runInEdtAndWait {
//            psiDocumentManager.commitDocument(document)
//        }

        return EditorFile(psiFile = psiFile, document = document)
    }

    private fun projectFileByName(project: Project, name: String): PsiFile {
        val fileManager = VirtualFileManager.getInstance()
        val url = "file://${File("${project.basePath}/$name").absolutePath}"
        val virtualFile = fileManager.refreshAndFindFileByUrl(url)
        return virtualFile!!.toPsiFile(project)!!
    }

    private data class EditorFile(val psiFile: PsiFile, val document: Document)

    private data class Result(val editorFile: EditorFile, val highlightInfos: List<HighlightInfo>)

}