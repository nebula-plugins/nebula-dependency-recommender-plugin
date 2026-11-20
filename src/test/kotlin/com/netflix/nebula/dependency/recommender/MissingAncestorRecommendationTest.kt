/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.nebula.dependency.recommender

import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream

class MissingAncestorRecommendationTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest(name = "later lock rule: Gradle {0}, duplicate={1}")
    @CsvSource("9.7.1,false", "9.7.1,true", "9.9.1,false", "9.9.1,true")
    fun laterLockRuleSuppliesVersionAcrossMissingAncestors(version: String, duplicate: Boolean) {
        fixture(consumer = """
            ${if (duplicate) "dependencies { implementation 'sample:foo:1.0' }" else ""}
            configurations.runtimeClasspath.incoming.beforeResolve {
                configurations.runtimeClasspath.resolutionStrategy.eachDependency { details ->
                    if (details.requested.group == 'sample' && details.requested.name == 'foo') {
                        details.useVersion('3.0')
                        details.because('locked to 3.0')
                    }
                }
            }
            configurations.runtimeClasspath.incoming.afterResolve {
                // The reason lookup must also tolerate a chain with no providers.
                assert plugins.getPlugin('com.netflix.nebula.dependency-recommender')
                    .getReasonsRecursive(project).empty
            }
        """)

        val result = run(version)

        verifyArtifact(result, "3.0")
        assertTrue(result.output.contains("locked to 3.0"), result.output)
    }

    @ParameterizedTest(name = "ancestor recommendation precedence: Gradle {0}")
    @ValueSource(strings = ["9.7.1", "9.9.1"])
    fun rootRecommendationsAndReasonsCrossMissingAncestorsWhileLocalPolicyWins(version: String) {
        fixture(root = """
            plugins { id 'com.netflix.nebula.dependency-recommender' }
            dependencyRecommendations.map recommendations: ['sample:foo': '2.0']
        """)

        val inherited = run(version)
        verifyArtifact(inherited, "2.0")
        assertTrue(inherited.output.contains("Recommending version 2.0"), inherited.output)
        assertTrue(inherited.output.contains("uses a provided map for recommendations"), inherited.output)

        File(projectDir, "shell/middle/consumer/build.gradle").appendText("""
            dependencyRecommendations.map recommendations: ['sample:foo': '3.0']
        """.trimIndent())
        verifyArtifact(run(version), "3.0")
    }

    @ParameterizedTest(name = "strict missing recommendation: Gradle {0}")
    @ValueSource(strings = ["9.7.1", "9.9.1"])
    fun strictModeStillRejectsMissingRecommendations(version: String) {
        fixture(consumer = "dependencyRecommendations.strictMode = true")

        val result = run(version, fail = true)

        assertEquals(TaskOutcome.FAILED, result.task(RESOLVE_TASK)?.outcome, result.output)
        assertTrue(result.output.contains("Dependency sample:foo omitted version with no recommended version"), result.output)
        assertFalse(result.output.contains("Extension of type 'RecommendationProviderContainer' does not exist"), result.output)
    }

    @ParameterizedTest(name = "local inherited policy under Isolated Projects: Gradle {0}")
    @ValueSource(strings = ["9.7.1", "9.9.1"])
    fun isolatedProjectsKeepConsumerOwnedPolicyAndReuseConfigurationCache(version: String) {
        fixture(root = """
            plugins { id 'com.netflix.nebula.dependency-recommender' }
            dependencyRecommendations.map recommendations: ['sample:foo': '2.0']
        """, consumer = """
            dependencyRecommendations.inheritedPolicy(':shell:middle') { }
            dependencyRecommendations.inheritedPolicy(':shell') { }
            dependencyRecommendations.inheritedPolicy(':') { scope ->
                scope.map recommendations: ['sample:foo': '3.0']
            }
        """)
        val args = listOf("-Pnebula.dependency-recommender.isolatedProjectsSupport=true",
            "--isolated-projects", "--configuration-cache", "--configuration-cache-problems=fail")

        val cold = run(version, args)
        verifyArtifact(cold, "3.0")
        assertTrue(cold.output.contains("Configuration cache entry stored"), cold.output)

        val warm = run(version, args)
        verifyArtifact(warm, "3.0", TaskOutcome.UP_TO_DATE)
        assertTrue(warm.output.contains("Reusing configuration cache"), warm.output)
    }

    private fun fixture(root: String = "", consumer: String = "") {
        write("settings.gradle", """
            rootProject.name = 'missing-ancestor-recommendations'
            include ':shell:middle:consumer'
        """)
        write("gradle.properties", """
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.jvmargs=-Xmx512m -Dnebula.features.coreBomSupport=false
            org.gradle.workers.max=2
        """)
        write("build.gradle", root)
        write("shell/build.gradle", "")
        write("shell/middle/build.gradle", "")
        listOf("1.0", "2.0", "3.0").forEach { version ->
            write("repo/sample/foo/$version/foo-$version.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId>
                <artifactId>foo</artifactId><version>$version</version></project>
            """)
            JarOutputStream(File(projectDir, "repo/sample/foo/$version/foo-$version.jar").outputStream()).use {
                it.putNextEntry(JarEntry("version.txt"))
                it.write(version.toByteArray())
                it.closeEntry()
            }
        }
        write("shell/middle/consumer/build.gradle", """
            plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
            repositories { maven { url = new File(rootDir, 'repo').toURI() } }
            $consumer
            dependencies { implementation 'sample:foo' }
            tasks.register('resolveRecommendations', Sync) {
                from configurations.runtimeClasspath
                into layout.buildDirectory.dir('resolved')
            }
            tasks.register('recommendationInsight', org.gradle.api.tasks.diagnostics.DependencyInsightReportTask) {
                showingAllVariants.set(false)
                setConfiguration('runtimeClasspath')
                setDependencySpec('sample:foo')
            }
        """)
    }

    private fun run(version: String, extra: List<String> = emptyList(), fail: Boolean = false): BuildResult {
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(listOf(RESOLVE_TASK, ":shell:middle:consumer:recommendationInsight", "--console=plain", "--stacktrace") + extra)
        return if (fail) runner.buildAndFail() else runner.build()
    }

    private fun verifyArtifact(result: BuildResult, version: String, outcome: TaskOutcome = TaskOutcome.SUCCESS) {
        assertEquals(outcome, result.task(RESOLVE_TASK)?.outcome, result.output)
        val output = File(projectDir, "shell/middle/consumer/build/resolved")
        assertEquals(setOf("foo-$version.jar"), output.list()?.toSet(), result.output)
        JarFile(File(output, "foo-$version.jar")).use {
            assertEquals(version, it.getInputStream(it.getJarEntry("version.txt")).bufferedReader().readText())
        }
    }

    private fun write(path: String, text: String) {
        File(projectDir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent() + "\n") }
    }

    companion object {
        private const val RESOLVE_TASK = ":shell:middle:consumer:resolveRecommendations"
    }
}
