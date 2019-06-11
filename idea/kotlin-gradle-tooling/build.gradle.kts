
description = "Kotlin Gradle Tooling support"

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(kotlinStdlib())
    compile(project(":compiler:cli-common"))
    compile(project(":kotlin-native:kotlin-native-utils"))
    compile(intellijPluginDep("gradle"))
    compileOnly(intellijDep()) { includeJars("slf4j-api-1.7.25") }
}

sourceSets {
    "main" { projectDefault() }
    "test" {}
}

runtimeJar()
