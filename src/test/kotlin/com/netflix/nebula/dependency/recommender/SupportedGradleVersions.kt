package com.netflix.nebula.dependency.recommender

import nebula.test.dsl.Gradle

enum class SupportedGradleVersion(val version: Gradle) {
    GRADLE_9_1(Gradle.ofVersion("9.1.0")),
    CURRENT(Gradle.current())
}
