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

class OverrideTransitivesInspectionTest {
    @TempDir lateinit var projectDir: File

    private val consumers = listOf(":app", ":nested:app")
    private val producers = listOf(":producer", ":parent:producer")
    private val tasks get() = consumers.flatMap { listOf("$it:resolveRecommendations", "$it:insight") }

    @ParameterizedTest(name = "inspection equivalence: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun adoptedInspectionMatchesLegacyAndTracksInputs(version: String, isolated: Boolean) {
        fixture()
        val states = listOf(
            State(emptyList(), "1.0", "2.0", "1.0"),
            State(listOf("-PproducerFoo=none"), "2.0", "2.0", "1.0"),
            State(listOf("-PproducerFoo=3.0"), "3.0", "2.0", "1.0"),
            State(listOf("-PrecommendedFoo=3.0", "-PrecommendedBar=3.0", "-PforceVersion=3.0"), "1.0", "3.0", "3.0")
        )
        states.forEach { state ->
            val legacy = run(version, false, false, cache = false, extra = state.args)
            verify(legacy, state.foo, state.bar, state.forced)
            val expectedReasons = reasons(legacy)
            assertTrue(expectedReasons.values.all { it.any { line -> line.contains("override transitive recommendation") } }, legacy.output)
            val enabledNoCache = run(version, false, true, cache = false, extra = state.args)
            verify(enabledNoCache, state.foo, state.bar, state.forced)
            assertEquals(expectedReasons, reasons(enabledNoCache), enabledNoCache.output)
            listOf(false, true).forEach { enabled ->
                val cold = run(version, isolated && enabled, enabled, extra = state.args)
                assertStored(cold)
                verify(cold, state.foo, state.bar, state.forced)
                assertEquals(expectedReasons, reasons(cold), cold.output)
                val warm = run(version, isolated && enabled, enabled, extra = state.args)
                assertReused(warm)
                verify(warm, state.foo, state.bar, state.forced)
                assertEquals(expectedReasons, reasons(warm), warm.output)
            }
        }
    }

    @ParameterizedTest(name = "inspection ownership: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun differentProducerPoliciesRemainLocalInBothOrders(version: String, isolated: Boolean) {
        fixture()
        val args = listOf("-PproducerFoo_producer=1.0", "-PproducerFoo_parent_producer=none",
            "-PrecommendedFoo_app=2.0", "-PrecommendedFoo_nested_app=3.0")
        fun check(result: BuildResult) {
            verifyConsumer(result, ":app", "1.0", "2.0", "1.0")
            verifyConsumer(result, ":nested:app", "3.0", "2.0", "1.0")
        }
        listOf(tasks, tasks.reversed()).forEachIndexed { index, order ->
            if (index == 1) write("settings.gradle", """
                rootProject.name = 'inspection-test'
                include 'nested:app', 'parent:producer', 'app', 'producer'
            """)
            check(run(version, false, true, cache = false, extra = args, selectedTasks = order))
            assertStored(run(version, isolated, true, extra = args, selectedTasks = order).also(::check))
            assertReused(run(version, isolated, true, extra = args, selectedTasks = order).also(::check))
        }
    }

    @ParameterizedTest(name = "inspection producer edits: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun editingProducerDeclarationsInvalidatesConsumerInspection(version: String, isolated: Boolean) {
        fixture()
        val args = listOf("-PproducerFoo=none")
        verify(run(version, false, false, cache = false, extra = args), "2.0", "2.0", "1.0")
        assertStored(run(version, isolated, true, extra = args).also { verify(it, "2.0", "2.0", "1.0") })
        assertReused(run(version, isolated, true, extra = args).also { verify(it, "2.0", "2.0", "1.0") })
        producers.forEach { path ->
            val source = File(projectDir, "${directory(path)}/build.gradle")
            source.writeText(source.readText().replace(
                "api 'sample:foo' + (declared == 'none' ? '' : ':' + declared)", "api 'sample:foo:1.0'"))
        }
        val legacy = run(version, false, false, cache = false, extra = args)
        verify(legacy, "1.0", "2.0", "1.0")
        val cold = run(version, isolated, true, extra = args)
        assertStored(cold)
        verify(cold, "1.0", "2.0", "1.0")
        assertEquals(reasons(legacy), reasons(cold), cold.output)
        assertReused(run(version, isolated, true, extra = args).also { verify(it, "1.0", "2.0", "1.0") })
    }

    @ParameterizedTest(name = "inspection source ownership: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun sourceConfigurationsRemainLocalToEachConsumerGraph(version: String, isolated: Boolean) {
        fixture(producerExtra = """
            configurations.create('custom') { canBeResolved = false; canBeConsumed = true }
            afterEvaluate {
                dependencies {
                    custom 'sample:foo:3.0'
                    custom 'sample:bridge:1.0'
                    custom 'sample:forced'
                }
            }
        """)
        val nested = File(projectDir, "nested/app/build.gradle")
        nested.writeText(nested.readText().replace("implementation project(':parent:producer')",
            "implementation project(path: ':producer', configuration: 'custom')"))
        listOf(false, true).forEach { bothSources ->
            if (bothSources) File(projectDir, "app/build.gradle").appendText(
                "\ndependencies { implementation project(path: ':producer', configuration: 'custom') }\n")
            fun check(result: BuildResult) {
                verifyConsumer(result, ":app", if (bothSources) "3.0" else "1.0", "2.0", "1.0")
                verifyConsumer(result, ":nested:app", "3.0", "2.0", "1.0", projectJars = emptySet())
            }
            val legacy = run(version, false, false, cache = false).also(::check)
            listOf(tasks, tasks.reversed()).forEach { order ->
                val cold = run(version, isolated, true, selectedTasks = order)
                assertStored(cold)
                check(cold)
                assertEquals(reasons(legacy), reasons(cold), cold.output)
                assertReused(run(version, isolated, true, selectedTasks = order).also(::check))
            }
        }
    }

    @ParameterizedTest(name = "inspection targets and variants: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun explicitTargetsAndFixtureVariantsPreserveLegacyInspectionScope(version: String, isolated: Boolean) {
        fixture(producerExtra = """
            apply plugin: 'java-test-fixtures'
            configurations.create('custom') { canBeResolved = false; canBeConsumed = true }
            dependencies {
                custom 'sample:foo:3.0'
                custom 'sample:bar:1.0'
                custom 'sample:bridge:1.0'
                custom 'sample:forced'
                testFixturesApi 'sample:fixture-only:1.0'
            }
        """)
        consumers.zip(producers).forEach { (consumer, producer) ->
            val source = File(projectDir, "${directory(consumer)}/build.gradle")
            source.writeText(source.readText().replace("implementation project('$producer')",
                "implementation project(path: '$producer', configuration: 'custom')"))
        }
        fun custom(result: BuildResult) = verify(result, "3.0", "1.0", "1.0", projectJars = emptySet())
        val legacy = run(version, false, false, cache = false).also(::custom)
        assertStored(run(version, isolated, true).also { custom(it); assertEquals(reasons(legacy), reasons(it), it.output) })
        assertReused(run(version, isolated, true).also(::custom))
        consumers.zip(producers).forEach { (consumer, producer) ->
            val source = File(projectDir, "${directory(consumer)}/build.gradle")
            source.writeText(source.readText().replace(
                "implementation project(path: '$producer', configuration: 'custom')",
                "implementation testFixtures(project('$producer'))"))
        }
        fun fixtures(result: BuildResult) = verify(result, "1.0", "2.0", "1.0",
            extraModules = mapOf("fixture-only" to "2.0"),
            projectJars = setOf("producer-1.0.jar", "producer-1.0-test-fixtures.jar"))
        val fixtureLegacy = run(version, false, false, cache = false).also(::fixtures)
        assertStored(run(version, isolated, true).also { fixtures(it); assertEquals(reasons(fixtureLegacy), reasons(it), it.output) })
        assertReused(run(version, isolated, true).also(::fixtures))
    }

    @ParameterizedTest(name = "inspection cycles: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun projectInspectionCyclesTerminateWithoutArtifactTaskCycles(version: String, isolated: Boolean) {
        fixture()
        producers.zip(producers.reversed()).forEach { (path, other) ->
            write("${directory(path)}/build.gradle", """
                plugins { id 'com.netflix.nebula.dependency-recommender' }
                group = 'sample.${directory(path).replace('/', '.')}'
                version = '1.0'
                configurations.create('default') { canBeConsumed = true; canBeResolved = false }
                dependencies {
                    add('default', project('$other'))
                    add('default', 'sample:foo:1.0')
                    add('default', 'sample:bridge:1.0')
                    add('default', 'sample:forced')
                }
            """)
        }
        val legacy = run(version, false, false, cache = false)
        verify(legacy, "1.0", "2.0", "1.0", projectJars = emptySet())
        val cold = run(version, isolated, true)
        assertStored(cold)
        verify(cold, "1.0", "2.0", "1.0", projectJars = emptySet())
        assertEquals(reasons(legacy), reasons(cold), cold.output)
        assertReused(run(version, isolated, true).also { verify(it, "1.0", "2.0", "1.0", projectJars = emptySet()) })
    }

    @ParameterizedTest(name = "inspection producer adoption diagnostic: Gradle {0}")
    @MethodSource("versions")
    fun missingProducerParticipationFailsWithAnActionableError(version: String) {
        fixture()
        producers.forEach { path ->
            write("${directory(path)}/build.gradle", """
                plugins { id 'java-library' }
                group = 'sample'
                version = '1.0'
                dependencies { api 'sample:foo:1.0'; api 'sample:bridge:1.0'; api 'sample:forced:1.0' }
            """)
        }
        verify(run(version, false, false, cache = false), "1.0", "2.0", "1.0")
        listOf(false, true).forEach { isolated ->
            val result = run(version, isolated, true, fail = true)
            assertTrue(result.output.contains("Apply the dependency-recommender plugin locally"), result.output)
            assertTrue(result.output.contains("producer"), result.output)
            assertFalse(result.output.contains("cannot access"), result.output)
        }
        // These failed builds establish the adoption error, not isolation support.
    }

    @ParameterizedTest(name = "inspection matching producer coordinates: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun matchingPublishedCoordinatesDoNotCollapseDeclaredInspectionScopes(version: String, isolated: Boolean) {
        fixture(producerExtra = """
            configurations.create('custom') { canBeConsumed = true; canBeResolved = false }
            configurations.create('inspectionOnly') { canBeConsumed = false; canBeResolved = false }
            configurations.named('default') { extendsFrom configurations.inspectionOnly }
        """)
        File(projectDir, "producer/build.gradle").appendText("""

            dependencies {
                custom 'sample:foo:3.0'
                inspectionOnly project(path: ':parent:producer', configuration: 'custom')
            }
        """)
        File(projectDir, "parent/producer/build.gradle").appendText("\ndependencies { custom 'sample:bar:1.0' }\n")
        // Both projects publish sample:producer:1.0, but only the nested custom scope
        // protects bar. The inspection-only edge is absent from actual runtime variants.
        fun check(result: BuildResult) {
            verifyConsumer(result, ":app", "1.0", "1.0", "1.0")
            verifyConsumer(result, ":nested:app", "1.0", "2.0", "1.0")
        }
        val legacy = run(version, false, false, cache = false).also(::check)
        run(version, false, true, cache = false).also { check(it); assertEquals(reasons(legacy), reasons(it), it.output) }
        listOf(tasks, tasks.reversed()).forEach { order ->
            val cold = run(version, isolated, true, selectedTasks = order)
            assertStored(cold)
            check(cold)
            assertEquals(reasons(legacy), reasons(cold), cold.output)
            assertReused(run(version, isolated, true, selectedTasks = order).also(::check))
        }
    }

    @ParameterizedTest(name = "inspection non-consumable target diagnostic: Gradle {0}")
    @MethodSource("versions")
    fun hiddenNonConsumableInspectionTargetsFailExplicitly(version: String) {
        fixture(producerExtra = """
            configurations.create('privatePolicy') { canBeConsumed = false; canBeResolved = false }
            configurations.create('inspectionOnly') { canBeConsumed = false; canBeResolved = false }
            configurations.named('default') { extendsFrom configurations.inspectionOnly }
            dependencies { privatePolicy 'sample:foo:3.0' }
        """)
        File(projectDir, "producer/build.gradle").appendText("""

            dependencies { inspectionOnly project(path: ':parent:producer', configuration: 'privatePolicy') }
        """)
        // The ordinary runtime variant does not expose this default-only project edge.
        verify(run(version, false, false, cache = false), "1.0", "2.0", "1.0")
        listOf(false, true).forEach { isolated ->
            val result = run(version, isolated, true, fail = true)
            assertTrue(result.output.contains("Cannot inspect non-consumable producer configuration :parent:producer:privatePolicy"), result.output)
            assertFalse(result.output.contains("cannot access"), result.output)
        }
        // This is an explicit unsupported-scope diagnostic, not compatibility evidence.
    }

    @ParameterizedTest(name = "inspection rollout and rollback: Gradle {0}")
    @MethodSource("versions")
    fun rolloutAndRollbackStayInOneDaemon(version: String) {
        fixture()
        val properties = File(projectDir, "gradle.properties")
        properties.writeText(properties.readText().replace("-Xmx512m", "-Xmx518m"))
        File(projectDir, "build.gradle").appendText("\n" + """
            tasks.register('daemonIdentity') { doLast { println('testDaemon=' + ProcessHandle.current().pid()) } }
        """.trimIndent())
        val selected = tasks + ":daemonIdentity"
        var daemon: String? = null
        fun check(result: BuildResult) {
            verify(result, "1.0", "2.0", "1.0")
            val pid = Regex("testDaemon=([0-9]+)").find(result.output)?.groupValues?.get(1)
            assertNotNull(pid, result.output)
            if (daemon == null) daemon = pid else assertEquals(daemon, pid, result.output)
        }
        listOf<Boolean?>(null, false, true, false, true, null).forEachIndexed { index, enabled ->
            check(run(version, false, enabled, cache = false, selectedTasks = selected))
            val cold = run(version, false, enabled, selectedTasks = selected)
            if (index < 3) assertStored(cold)
            check(cold)
            assertReused(run(version, false, enabled, selectedTasks = selected).also(::check))
            if (enabled == true) {
                val coldIsolated = run(version, true, true, selectedTasks = selected)
                if (index == 2) assertStored(coldIsolated)
                check(coldIsolated)
                assertReused(run(version, true, true, selectedTasks = selected).also(::check))
            }
        }
    }

    @ParameterizedTest(name = "inspection source laziness: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun unselectedDeclarationsRemainLazy(version: String, isolated: Boolean) {
        fixture(producerExtra = """
            configurations.register('unselected') {
                canBeConsumed = false
                dependencies.addLater(providers.provider {
                    throw new GradleException('unselected dependency provider realized')
                })
                withDependencies { throw new GradleException('unselected declarations realized') }
            }
        """)
        verify(run(version, false, false, cache = false), "1.0", "2.0", "1.0")
        verify(run(version, false, true, cache = false), "1.0", "2.0", "1.0")
        val cold = run(version, isolated, true)
        assertStored(cold)
        verify(cold, "1.0", "2.0", "1.0")
        val warm = run(version, isolated, true)
        assertReused(warm)
        verify(warm, "1.0", "2.0", "1.0")
    }

    private fun fixture(producerExtra: String = "", consumerExtra: String = "") {
        write("settings.gradle", "rootProject.name = 'inspection-test'\ninclude 'producer', 'parent:producer', 'app', 'nested:app'")
        write("build.gradle", plugins)
        write("parent/build.gradle", plugins)
        write("nested/build.gradle", plugins)
        write("gradle.properties", """
            org.gradle.jvmargs=-Xmx512m -Dnebula.features.coreBomSupport=false
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """)
        producers.forEach { path ->
            write("${directory(path)}/build.gradle", plugins + policy(path, false) + """
                def declared = providers.gradleProperty('producerFoo${path.replace(':', '_')}')
                    .orElse(providers.gradleProperty('producerFoo')).orElse('1.0').get()
                dependencies {
                    api 'sample:foo' + (declared == 'none' ? '' : ':' + declared)
                    api 'sample:bridge:1.0'
                    api 'sample:forced'
                }
            """ + producerExtra)
        }
        consumers.zip(producers).forEach { (path, producer) ->
            write("${directory(path)}/build.gradle", plugins + policy(path, true) + """
                group = 'sample'
                version = '1.0'
                dependencies {
                    implementation project('$producer')
                    implementation 'sample:direct:1.0'
                }
                configurations.runtimeClasspath.resolutionStrategy.force 'sample:forced:' +
                    providers.gradleProperty('forceVersion').orElse('1.0').get()
                tasks.register('cleanResolved', Delete) { delete layout.buildDirectory.dir('resolved') }
                tasks.register('resolveRecommendations', Sync) {
                    dependsOn 'cleanResolved'
                    from configurations.runtimeClasspath
                    into layout.buildDirectory.dir('resolved')
                }
                tasks.register('insight', org.gradle.api.tasks.diagnostics.DependencyInsightReportTask) {
                    showingAllVariants.set(false)
                    setConfiguration('runtimeClasspath')
                    setDependencySpec('sample')
                }
            """ + consumerExtra)
        }
        listOf("foo", "bar", "forced", "direct", "bridge", "fixture-only").forEach { module ->
            listOf("1.0", "2.0", "3.0").forEach { version -> artifact(module, version) }
        }
    }

    private fun policy(path: String, override: Boolean): String {
        val scope = path.substringBeforeLast(':')
        return """
            group = 'sample'
            version = '1.0'
            repositories { maven { url = new File(rootDir, 'repo').toURI() } }
            dependencyRecommendations {
                map name: 'local', recommendations: [
                    'sample:foo': providers.gradleProperty('recommendedFoo${path.replace(':', '_')}')
                        .orElse(providers.gradleProperty('recommendedFoo')).orElse('2.0').get(),
                    'sample:bar': providers.gradleProperty('recommendedBar').orElse('2.0').get(),
                    'sample:bridge': '1.0', 'sample:forced': '2.0', 'sample:direct': '2.0',
                    'sample:fixture-only': '2.0'
                ]
                if (providers.gradleProperty(SUPPORT).orElse('false').get().toBoolean()) {
                    ${if (scope.isNotEmpty()) "inheritedPolicy('$scope') {}" else ""}
                    inheritedPolicy(':') {}
                }
            }
            ${if (override) "afterEvaluate { dependencyRecommendations.strategy = dependencyRecommendations.OverrideTransitives }" else ""}
        """.replace("SUPPORT", "'$SUPPORT'")
    }

    private fun verify(result: BuildResult, foo: String, bar: String, forced: String,
                       extraModules: Map<String, String> = emptyMap(), projectJars: Set<String> = setOf("producer-1.0.jar")) {
        consumers.forEach { path -> verifyConsumer(result, path, foo, bar, forced, extraModules, projectJars) }
    }

    private fun verifyConsumer(result: BuildResult, path: String, foo: String, bar: String, forced: String,
                               extraModules: Map<String, String> = emptyMap(), projectJars: Set<String> = setOf("producer-1.0.jar")) {
            assertEquals(TaskOutcome.SUCCESS, result.task("$path:resolveRecommendations")?.outcome, result.output)
            val expected = mapOf("foo" to foo, "bar" to bar, "bridge" to "1.0", "direct" to "1.0", "forced" to forced) + extraModules
            val files = File(projectDir, "${directory(path)}/build/resolved")
            assertEquals(expected.map { (module, version) -> "$module-$version.jar" }.toSet() + projectJars,
                files.list()?.toSet(), result.output)
            expected.forEach { (module, version) ->
                JarFile(File(files, "$module-$version.jar")).use { jar ->
                    assertEquals("$module:$version", jar.getInputStream(jar.getJarEntry("version.txt")).bufferedReader().readText())
                }
            }
            assertTrue(result.output.contains("sample:foo:$foo"), result.output)
            assertTrue(result.output.contains("sample:bar:$bar"), result.output)
            assertTrue(result.output.contains("sample:forced:$forced (forced)"), result.output)
    }

    private fun reasons(result: BuildResult) = consumers.associateWith { path ->
        result.output.substringAfter("> Task $path:insight").substringBefore("\n> Task ")
            .lineSequence().map { it.trim() }.filter { it.startsWith("- ") || it.startsWith("with reasons:") }
            .toSet()
    }

    private fun run(version: String, isolated: Boolean, enabled: Boolean?, cache: Boolean = true,
                    extra: List<String> = emptyList(), selectedTasks: List<String> = tasks, fail: Boolean = false): BuildResult {
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
            if (isolated) "--isolated-projects" else "--no-isolated-projects"
        } else "-Dorg.gradle.unsafe.isolated-projects=$isolated"
        val flag = enabled?.let { listOf("-P$SUPPORT=$it") } ?: emptyList()
        val diagnostics = if (fail && GradleVersion.version(version) >= GradleVersion.version("9.7"))
            listOf("-Dorg.gradle.isolated-projects.diagnostics=true") else emptyList()
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(selectedTasks + flag + extra + diagnostics + listOf(isolation, "--parallel", "--console=plain", "--stacktrace",
                if (cache) "--configuration-cache" else "--no-configuration-cache", "--configuration-cache-problems=fail"))
        return if (fail) runner.buildAndFail() else runner.build()
    }

    private fun artifact(module: String, version: String) {
        val dependencies = if (module == "bridge") """
            <dependencies><dependency><groupId>sample</groupId><artifactId>bar</artifactId><version>1.0</version></dependency></dependencies>
        """ else ""
        write("repo/sample/$module/$version/$module-$version.pom", """
            <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>$module</artifactId>
            <version>$version</version>$dependencies</project>
        """)
        JarOutputStream(File(projectDir, "repo/sample/$module/$version/$module-$version.jar").outputStream()).use {
            it.putNextEntry(JarEntry("version.txt")); it.write("$module:$version".toByteArray()); it.closeEntry()
        }
    }

    private val plugins = """
        plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
    """.trimIndent() + "\n"
    private fun directory(path: String) = path.trimStart(':').replace(':', '/')
    private fun write(path: String, text: String) = File(projectDir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
    private fun assertStored(result: BuildResult) {
        assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
        assertFalse(result.output.contains("Reusing configuration cache"), result.output)
    }
    private fun assertReused(result: BuildResult) {
        assertTrue(result.output.contains("Reusing configuration cache"), result.output)
        assertTrue(result.output.contains("Configuration cache entry reused"), result.output)
    }
    private data class State(val args: List<String>, val foo: String, val bar: String, val forced: String)
    companion object {
        private const val SUPPORT = "nebula.dependency-recommender.isolatedProjectsSupport"
        @JvmStatic fun versions(): Stream<String> = listOf("9.1.0", GradleVersion.current().version).distinct().stream()
        @JvmStatic fun modes(): Stream<Arguments> = versions().flatMap { Stream.of(Arguments.of(it, false), Arguments.of(it, true)) }
    }
}
