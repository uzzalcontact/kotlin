/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf

import com.intellij.codeInsight.daemon.DaemonAnalyzerTestCase
import com.intellij.codeInsight.daemon.ImplicitUsageProvider
import com.intellij.codeInsight.daemon.ProblemHighlightFilter
import com.intellij.lang.ExternalAnnotatorsFilter
import com.intellij.lang.LanguageAnnotators
import com.intellij.lang.StdLanguages
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.impl.search.IndexPatternBuilder
import com.intellij.psi.xml.XmlFileNSInfoProvider
import com.intellij.xml.XmlSchemaProvider
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionsManager
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.Ignore

class PerformanceProjectsTest : AbstractPerformanceProjectsTest() {

    companion object {

        @JvmStatic
        var warmedUp: Boolean = false

        @JvmStatic
        val hwStats: Stats = Stats("helloWorld project")

        @BeforeClass
        @JvmStatic
        fun setup() {
            // things to execute once and keep around for the class
        }

        @AfterClass
        @JvmStatic
        fun teardown() {
            hwStats.close()
        }
    }

    override fun setUp() {
        super.setUp()
        // warm up: open simple small project
        if (!warmedUp) {
            val project = innerPerfOpenProject("helloKotlin", hwStats, "warm-up")
            val perfHighlightFile = perfHighlightFile(project, "src/HelloMain.kt", hwStats, "warm-up")
            assertTrue("kotlin project has been not imported properly", perfHighlightFile.isNotEmpty())
            closeProject(project)

            warmedUp = true
        }
    }

    fun testHelloWorldProject() {
        tcSuite("Hello world project") {
            perfOpenProject("helloKotlin", hwStats)

            // highlight
            perfHighlightFile("src/HelloMain.kt", hwStats)
            perfHighlightFile("src/HelloMain2.kt", hwStats)
        }
    }

    fun testKotlinProject() {
        tcSuite("Kotlin project") {
            val stats = Stats("kotlin project")
            stats.use {
                perfOpenProject("perfTestProject", stats = it, path = "..")

                perfHighlightFile("compiler/psi/src/org/jetbrains/kotlin/psi/KtFile.kt", stats = it)

                perfHighlightFile("compiler/psi/src/org/jetbrains/kotlin/psi/KtElement.kt", stats = it)
            }
        }
    }

    @Ignore("WIP: perfTestProject has to be imported via gradle etc")
    fun testKotlinProjectHighlightBuildGradle() {
        tcSuite("Kotlin project highlight build gradle") {
            val stats = Stats("kotlin project highlight build gradle")
            stats.use {
                perfOpenProject("perfTestProject", stats = it, path = "..")

                InjectedLanguageManager.getInstance(myProject) // zillion of Dom Sem classes
                LanguageAnnotators.INSTANCE.allForLanguage(JavaLanguage.INSTANCE) // pile of annotator classes loads
                LanguageAnnotators.INSTANCE.allForLanguage(StdLanguages.XML)
                DaemonAnalyzerTestCase.assertTrue(
                    "side effect: to load extensions",
                    ProblemHighlightFilter.EP_NAME.extensions.toMutableList()
                        .plus(ImplicitUsageProvider.EP_NAME.extensions)
                        .plus(XmlSchemaProvider.EP_NAME.extensions)
                        .plus(XmlFileNSInfoProvider.EP_NAME.extensions)
                        .plus(ExternalAnnotatorsFilter.EXTENSION_POINT_NAME.extensions)
                        .plus(IndexPatternBuilder.EP_NAME.extensions).isNotEmpty()
                )
                // side effect: to load script definitions"
                ScriptDefinitionsManager.getInstance(myProject!!).getAllDefinitions()

                perfHighlightFile("build.gradle.kts", stats = it)
                perfHighlightFile("idea/build.gradle.kts", stats = it)
                perfHighlightFile("gradle/jps.gradle.kts", stats = it)
                perfHighlightFile("gradle/versions.gradle.kts", stats = it)
            }
        }
    }

}