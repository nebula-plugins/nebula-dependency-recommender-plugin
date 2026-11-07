/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
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
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.stream.Stream

class BuildServiceSelectionTest {
    @TempDir
    lateinit var projectDir: File

    private val consumers = listOf(":sibling", ":parent:leaf")
    private val tasks = consumers.flatMap { listOf(it + ":resolveRecommendations", it + ":recommendationInsight") }
    private val ancestors = Source("ancestors", root = "false", parent = "true")

    @ParameterizedTest(name = "build-service settings: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun supportedGradleDefaultPreservesArtifactsReasonsAndEagerTiming(version: String, isolated: Boolean) {
        val sources = listOf(
            Source("absent"),
            ancestors,
            Source("localBooleanFalse", root = "true", parent = "true", local = "false"),
            Source("localStringTrue", root = "false", parent = "false", local = "'TrUe'", gradle = "false"),
            Source("gradleFalse", gradle = "false"),
            Source("gradleTrue", gradle = "TrUe")
        )
        sources.forEach { source ->
            val dir = fixture(source.name, source)
            // Keep task order stable across input changes so cache invalidation tests the
            // policy input. A second source exercises the reverse consumer/task order.
            val orderedTasks = if (source.name == "gradleTrue") tasks.reversed() else tasks
            listOf("1.0", "2.0").forEach { selected ->
                val extra = listOf("-Psample.version=" + selected) +
                    if (selected == "2.0" && source.gradle != null) {
                        listOf("-P" + USE_SERVICE + "=" + !source.gradle.toBoolean())
                    } else emptyList()
                listOf(false, true).forEach { enabled ->
                    verify(dir, run(dir, version, false, enabled, orderedTasks, extra, cache = false), selected)
                    val cold = run(dir, version, isolated && enabled, enabled, orderedTasks, extra)
                    assertStored(cold)
                    verify(dir, cold, selected)
                    val warm = run(dir, version, isolated && enabled, enabled, orderedTasks, extra)
                    assertReused(warm)
                    verify(dir, warm, selected)
                }
            }
        }
    }

    @ParameterizedTest(name = "build-service manual adapters: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun disabledEagerResolutionStillAllowsLateBomReplacementAndBothAdapters(version: String, isolated: Boolean) {
        listOf("util", "plugin").forEach { adapter ->
            val dir = fixture("manual-" + adapter, ancestors, eager = false)
            val call = if (adapter == "util") """
                netflix.nebula.dependency.recommender.util.BomResolutionUtil.eagerlyResolveBoms(
                    project, dependencyRecommendations, 'nebulaRecommenderBom')
            """ else """
                plugins.getPlugin(netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin)
                    .eagerlyResolveBoms(project, dependencyRecommendations)
            """
            consumers.forEach { path ->
                File(dir, directory(path) + "/build.gradle").appendText("\n" + """
                    afterEvaluate {
                        assert configurations.nebulaRecommenderBom.state.toString() == 'UNRESOLVED'
                        configurations.nebulaRecommenderBom.dependencies.clear()
                        dependencyRecommendations.mavenBom module: 'sample:bom:2.0'
                """.trimIndent() + "\n" + call.trimIndent() + "\n" + """
                        def service = netflix.nebula.dependency.recommender.util.IsolatedProjectsSupport.registerBomResolver(project).get()
                        assert service.getRecommendations(project, 'nebulaRecommenderBom', new LinkedHashSet())['sample:foo'] ==
                            providers.gradleProperty('sample.version').get()
                    }
                """.trimIndent())
            }
            listOf("1.0", "2.0").forEach { selected ->
                val extra = listOf("-Psample.version=" + selected)
                listOf(false, true).forEach { enabled ->
                    verify(dir, run(dir, version, false, enabled, tasks, extra, cache = false), selected, "2.0")
                    val cold = run(dir, version, isolated && enabled, enabled, tasks, extra)
                    assertStored(cold)
                    verify(dir, cold, selected, "2.0")
                    val warm = run(dir, version, isolated && enabled, enabled, tasks, extra)
                    assertReused(warm)
                    verify(dir, warm, selected, "2.0")
                }
            }
        }
    }

    @ParameterizedTest(name = "build-service rollout in one daemon: Gradle {0}")
    @MethodSource("versions")
    fun rolloutAndRollbackKeepDefaultSelectionInTheSameDaemon(version: String) {
        // Distinct JVM arguments reserve a daemon for this sequence instead of sharing
        // busy TestKit daemons with concurrently executing matrix cases.
        val dir = fixture("rollout", ancestors, heap = 514)
        val taskNames = tasks + ":daemonIdentity"
        val extra = listOf("-Psample.version=1.0")
        var daemon: String? = null
        fun check(result: BuildResult) {
            verify(dir, result, "1.0")
            val current = Regex("testDaemon=([0-9]+)").find(result.output)?.groupValues?.get(1)
            assertNotNull(current, result.output)
            if (daemon == null) daemon = current else assertEquals(daemon, current, result.output)
        }
        listOf<Boolean?>(null, false, true, false, true, null).forEachIndexed { index, enabled ->
            check(run(dir, version, false, enabled, taskNames, extra, cache = false))
            val cold = run(dir, version, false, enabled, taskNames, extra)
            if (index < 3) assertStored(cold)
            check(cold)
            val warm = run(dir, version, false, enabled, taskNames, extra)
            assertReused(warm)
            check(warm)
            if (enabled == true) {
                val isolated = run(dir, version, true, true, taskNames, extra)
                if (index == 2) assertStored(isolated)
                check(isolated)
                val reused = run(dir, version, true, true, taskNames, extra)
                assertReused(reused)
                check(reused)
            }
        }
    }

    @ParameterizedTest(name = "legacy parent lookup characterization: Gradle {0}")
    @MethodSource("versions")
    fun legacyFlagOffParentLookupRemainsAnExplicitIsolationLimitation(version: String) {
        val dir = fixture("legacy", ancestors)
        val extra = listOf("-Psample.version=1.0")
        if (GradleVersion.version(version) < GradleVersion.version("9.6")) {
            repeat(2) {
                val failure = run(dir, version, true, false, tasks, extra, fail = true)
                assertTrue(failure.output.contains("cannot dynamically look up a property in the parent project"), failure.output)
                assertTrue(failure.output.contains(":parent:leaf") || failure.output.contains(":sibling"), failure.output)
                assertTrue(failure.output.contains("Configuration cache entry discarded"), failure.output)
                assertFalse(failure.output.contains("Reusing configuration cache"), failure.output)
            }
        } else {
            // Gradle 9.6+ itself disables implicit parent lookup under isolation. This
            // flag-off control establishes the Gradle 9 default, not inherited extras.
            val cold = run(dir, version, true, false, tasks, extra)
            assertStored(cold)
            verify(dir, cold, "1.0")
            val warm = run(dir, version, true, false, tasks, extra)
            assertReused(warm)
            verify(dir, warm, "1.0")
        }
    }

    private data class Source(
        val name: String, val root: String? = null, val parent: String? = null,
        val local: String? = null, val gradle: String? = null
    )

    private fun fixture(name: String, source: Source, eager: Boolean = true, heap: Int = 512): File {
        val dir = File(projectDir, name)
        write(dir, "settings.gradle", "rootProject.name = 'service-selection'\ninclude 'sibling', 'parent:leaf'\n")
        write(dir, "gradle.properties", """
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.jvmargs=-Xmx${heap}m -Dnebula.features.coreBomSupport=false
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """ + (source.gradle?.let { "\n" + USE_SERVICE + "=" + it + "\n" } ?: ""))
        write(dir, "build.gradle", extra(source.root) + """
            tasks.register('daemonIdentity') {
                doLast { println('testDaemon=' + ProcessHandle.current().pid()) }
            }
        """)
        write(dir, "parent/build.gradle", extra(source.parent))
        consumers.forEach { path ->
            write(dir, directory(path) + "/build.gradle", """
                plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
                repositories { maven { url = new File(rootDir, 'repo').toURI() } }
            """ + "\n" + extra(source.local) + """
                dependencyRecommendations { mavenBom module: 'sample:bom:1.0' }
                dependencyRecommendations.eagerlyResolve = ${eager}
                dependencies { implementation 'sample:foo' }
                tasks.register('cleanResolved', Delete) { delete layout.buildDirectory.dir('resolved') }
                tasks.register('resolveRecommendations', Sync) {
                    dependsOn 'cleanResolved'
                    from configurations.runtimeClasspath
                    into layout.buildDirectory.dir('resolved')
                }
                tasks.register('recommendationInsight', org.gradle.api.tasks.diagnostics.DependencyInsightReportTask) {
                    showingAllVariants.set(false)
                    setConfiguration('runtimeClasspath')
                    setDependencySpec('sample:foo')
                }
            """ + if (eager) """
                afterEvaluate {
                    // A legacy cache hit may leave this configuration unresolved. Verify
                    // the eager policy snapshot rather than requiring duplicate resolution.
                    def service = netflix.nebula.dependency.recommender.util.IsolatedProjectsSupport.registerBomResolver(project).get()
                    assert service.getRecommendations(project, 'nebulaRecommenderBom', new LinkedHashSet())['sample:foo'] ==
                        providers.gradleProperty('sample.version').get()
                }
            """ else "")
        }
        listOf("1.0", "2.0").forEach { version ->
            pom(dir, "foo", version, "jar", "")
            JarOutputStream(File(dir, "repo/sample/foo/" + version + "/foo-" + version + ".jar").outputStream()).use { jar ->
                jar.putNextEntry(JarEntry("version.txt"))
                jar.write(version.toByteArray())
                jar.closeEntry()
            }
            pom(dir, "bom", version, "pom", """
                <dependencyManagement><dependencies><dependency>
                  <groupId>sample</groupId><artifactId>foo</artifactId><version>
            """ + "$" + "{sample.version}" + """
                  </version>
                </dependency></dependencies></dependencyManagement>
            """)
        }
        return dir
    }

    private fun run(dir: File, version: String, isolated: Boolean, enabled: Boolean?, taskNames: List<String>,
                    extra: List<String>, cache: Boolean = true, fail: Boolean = false): BuildResult {
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
            if (isolated) "--isolated-projects" else "--no-isolated-projects"
        } else "-Dorg.gradle.unsafe.isolated-projects=" + isolated
        val flag = enabled?.let { listOf("-P" + SUPPORT + "=" + it) } ?: emptyList()
        val runner = GradleRunner.create().withProjectDir(dir).withPluginClasspath().withGradleVersion(version)
            .withArguments(taskNames + extra + flag + listOf(isolation, "--parallel",
                if (cache) "--configuration-cache" else "--no-configuration-cache",
                "--configuration-cache-problems=fail", "--console=plain", "--stacktrace"))
        return if (fail) runner.buildAndFail() else runner.build()
    }

    private fun verify(dir: File, result: BuildResult, selected: String, bom: String = "1.0") {
        consumers.forEach { path ->
            assertEquals(TaskOutcome.SUCCESS, result.task(path + ":resolveRecommendations")?.outcome, result.output)
            val output = File(dir, directory(path) + "/build/resolved")
            assertEquals(setOf("foo-" + selected + ".jar"), output.list()?.toSet(), output.toString())
            JarFile(File(output, "foo-" + selected + ".jar")).use { jar ->
                assertEquals(selected, jar.getInputStream(jar.getJarEntry("version.txt")).bufferedReader().readText())
            }
            val insight = result.output.substringAfter("> Task " + path + ":recommendationInsight").substringBefore("\n> Task ")
            assertTrue(insight.contains("sample:foo:" + selected), insight)
            val reasons = insight.lineSequence().firstOrNull { it.contains("with reasons:") }?.substringAfter("with reasons:")?.trim()
            assertEquals("nebula.dependency-recommender uses mavenBom: sample:bom:pom:" + bom, reasons, insight)
        }
    }

    private fun assertStored(result: BuildResult) {
        assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
        assertFalse(result.output.contains("Reusing configuration cache"), result.output)
    }

    private fun assertReused(result: BuildResult) {
        assertTrue(result.output.contains("Reusing configuration cache"), result.output)
        assertTrue(result.output.contains("Configuration cache entry reused"), result.output)
    }

    private fun extra(value: String?) = value?.let { "ext.set('" + USE_SERVICE + "', " + it + ")\n" } ?: ""
    private fun directory(path: String) = path.trimStart(':').replace(':', '/')
    private fun write(dir: File, path: String, text: String) {
        File(dir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
    }
    private fun pom(dir: File, artifact: String, version: String, packaging: String, body: String) =
        write(dir, "repo/sample/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom",
            "<project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>" + artifact +
                "</artifactId><version>" + version + "</version><packaging>" + packaging + "</packaging>" + body + "</project>")

    companion object {
        private const val USE_SERVICE = "nebula.dependency-recommender.useBuildService"
        private const val SUPPORT = "nebula.dependency-recommender.isolatedProjectsSupport"
        @JvmStatic
        fun versions(): Stream<String> = listOf("9.1.0", GradleVersion.current().version).distinct().stream()
        @JvmStatic
        fun modes(): Stream<Arguments> = versions().flatMap { Stream.of(Arguments.of(it, false), Arguments.of(it, true)) }
    }
}
