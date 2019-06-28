/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.core.script

import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.idea.core.util.*
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.scripting.resolve.ScriptCompilationConfigurationWrapper
import java.io.*
import kotlin.script.experimental.api.ScriptCompilationConfiguration
import kotlin.script.experimental.dependencies.ScriptDependencies

var KtFile.scriptDependencies: ScriptDependencies?
    get() = this.virtualFile.scriptDependencies
    set(value) {
        this.virtualFile.scriptDependencies = value
    }

private var VirtualFile.scriptDependencies: ScriptDependencies? by cachedFileAttribute(
    name = "kotlin-script-dependencies",
    version = 3,
    read = {
        ScriptDependencies(
            classpath = readFileList(),
            imports = readStringList(),
            javaHome = readNullable(DataInput::readFile),
            scripts = readFileList(),
            sources = readFileList()
        )
    },
    write = {
        with(it) {
            writeFileList(classpath)
            writeStringList(imports)
            writeNullable(javaHome, DataOutput::writeFile)
            writeFileList(scripts)
            writeFileList(sources)
        }
    }
)

var KtFile.scriptCompilationConfiguration: ScriptCompilationConfiguration?
    get() = this.virtualFile.scriptCompilationConfiguration
    set(value) {
        this.virtualFile.scriptCompilationConfiguration = value
    }

private var VirtualFile.scriptCompilationConfiguration: ScriptCompilationConfiguration? by cachedFileAttribute(
    name = "kotlin-script-compilation-configuration",
    version = 1,
    read = {
        val size = readInt()
        val bytes = ByteArray(size)
        read(bytes, 0, size)
        val bis = ByteArrayInputStream(bytes)
        ObjectInputStream(bis).use { ois ->
            ois.readObject() as ScriptCompilationConfiguration
        }
    },
    write = {
        val os = ByteArrayOutputStream()
        ObjectOutputStream(os).use { oos ->
            oos.writeObject((it as? ScriptCompilationConfigurationWrapper.FromCompilationConfiguration)?.configuration)
        }
        val bytes = os.toByteArray()
        writeInt(bytes.size)
        write(bytes)
    }
)
