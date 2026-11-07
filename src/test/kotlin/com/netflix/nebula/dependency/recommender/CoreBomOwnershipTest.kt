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
import javax.xml.parsers.DocumentBuilderFactory

class CoreBomOwnershipTest {
    @TempDir
    lateinit var projectDir: File

    private val consumers = listOf(":sibling", ":parent:leaf")
    private val mainClasspaths = setOf("compileClasspath", "runtimeClasspath", "testCompileClasspath", "testRuntimeClasspath")
    private val ownClasspaths = setOf("smokeCompileClasspath", "smokeRuntimeClasspath", "lateClasspath", "skippedProbeClasspath")
    private val processors = setOf("annotationProcessor", "testAnnotationProcessor")
    private val configurations = mainClasspaths + ownClasspaths + processors +
        listOf("excludedClasspath", "excludedAnnotationProcessor", "unrelated")
    private val tasks = consumers.flatMap { path ->
        configurations.map { "$path:copy$it" } + listOf("$path:fooInsight",
            "$path:generatePomFileForMavenPublication", "$path:generateMetadataFileForMavenPublication")
    }

    @ParameterizedTest(name = "core policy equivalence: Gradle {0}, isolated={1}, enforced={2}")
    @MethodSource("platformModes")
    fun adoptedPolicyMatchesLegacyArtifactsReasonsAndPublication(version: String, isolated: Boolean, enforced: Boolean) {
        fixture()
        val states = listOf(
            listOf("-PbomVersion=1.0"), listOf("-PbomVersion=2.0"),
            listOf("-PbomVersion=2.0", "-Pexplicit=3.0")
        ) + if (!enforced) listOf(listOf("-PbomVersion=2.0", "-Pforce=1.0")) else emptyList()
        states.forEachIndexed { index, state ->
            val bom = if (index == 0) "1.0" else "2.0"
            val selected = when {
                state.contains("-Pforce=1.0") -> "1.0"
                state.contains("-Pexplicit=3.0") && !enforced -> "3.0"
                else -> bom
            }
            val args = state + "-Penforced=$enforced"
            val legacy = run(version, false, false, tasks, args, cache = false)
            verify(legacy, bom, selected)
            val baselineReasons = reasons(legacy)
            assertTrue(baselineReasons.values.all { it.isNotEmpty() }, legacy.output)
            if (enforced) assertTrue(baselineReasons.values.all { set -> set.any { it.contains("forced", true) } }, legacy.output)
            val control = run(version, false, true, tasks.reversed(), args, cache = false)
            verify(control, bom, selected)
            assertEquals(baselineReasons, reasons(control), control.output)
            listOf(false, true).forEach { enabled ->
                val cold = run(version, isolated && enabled, enabled, tasks, args)
                assertStored(cold)
                verify(cold, bom, selected)
                assertEquals(baselineReasons, reasons(cold), cold.output)
                val warm = run(version, isolated && enabled, enabled, tasks, args)
                assertReused(warm)
                verify(warm, bom, selected)
                assertEquals(baselineReasons, reasons(warm), warm.output)
            }
        }
    }

    @ParameterizedTest(name = "core manual timing: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun localBomRemainsLiveUntilLateManualCustomization(version: String, isolated: Boolean) {
        listOf("util", "plugin").forEach { adapter ->
            fixture(eager = false, adapter = adapter)
            val args = listOf("-PbomVersion=1.0", "-Penforced=true")
            verify(run(version, false, true, tasks, args, cache = false), "2.0", "2.0")
            val cold = run(version, isolated, true, tasks, args)
            assertStored(cold)
            verify(cold, "2.0", "2.0")
            val warm = run(version, isolated, true, tasks, args)
            assertReused(warm)
            verify(warm, "2.0", "2.0")
            // A distinct task key avoids reusing the first adapter's fixture cache.
            if (adapter == "util") projectDir = File(projectDir, "plugin-adapter").apply { mkdirs() }
        }
    }

    @ParameterizedTest(name = "core POM input: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun changingBomBytesInvalidatesNativePolicy(version: String, isolated: Boolean) {
        fixture()
        val args = listOf("-PbomVersion=2.0", "-Penforced=true")
        assertStored(run(version, isolated, true, tasks, args).also { verify(it, "2.0", "2.0") })
        assertReused(run(version, isolated, true, tasks, args).also { verify(it, "2.0", "2.0") })
        run(version, false, true, listOf(":removeBom"), args, cache = false)
        bom("2.0", "3.0")
        verify(run(version, false, true, tasks, args, cache = false), "3.0", "3.0")
        assertStored(run(version, isolated, true, tasks, args).also { verify(it, "3.0", "3.0") })
        assertReused(run(version, isolated, true, tasks, args).also { verify(it, "3.0", "3.0") })
    }

    @ParameterizedTest(name = "core repository input: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun repositorySwitchInvalidatesLocalPolicy(version: String, isolated: Boolean) {
        fixture()
        val alternate = File(projectDir, "alternateRepo")
        File(projectDir, "repo").copyRecursively(alternate)
        val alternateBom = File(alternate, "sample/bom/2.0/bom-2.0.pom")
        alternateBom.writeText(alternateBom.readText().replace(
            "<version>2.0</version></dependency>", "<version>3.0</version></dependency>"))
        listOf("repo" to "2.0", "alternateRepo" to "3.0").forEach { (repository, expected) ->
            val args = listOf("-PbomVersion=2.0", "-Penforced=true", "-Prepository=$repository")
            verify(run(version, false, true, tasks.reversed(), args, cache = false), expected, expected)
            assertStored(run(version, isolated, true, tasks, args).also { verify(it, expected, expected) })
            assertReused(run(version, isolated, true, tasks, args).also { verify(it, expected, expected) })
        }
    }

    @ParameterizedTest(name = "core independent policy: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun parallelConsumersKeepDifferentLocalPolicyInBothOrders(version: String, isolated: Boolean) {
        fixture()
        val args = listOf("-PbomVersion=1.0", "-PbomVersion_sibling=2.0",
            "-PbomVersion_parent_leaf=3.0", "-Penforced=true")
        fun check(result: BuildResult) {
            verifyConsumer(result, ":sibling", "2.0", "2.0")
            verifyConsumer(result, ":parent:leaf", "3.0", "3.0")
            assertTrue(reasons(result).values.all { it == setOf("By constraint", "Forced") }, result.output)
        }
        listOf(tasks, tasks.reversed()).forEach { order ->
            check(run(version, false, true, order, args, cache = false))
            assertStored(run(version, isolated, true, order, args).also(::check))
            assertReused(run(version, isolated, true, order, args).also(::check))
        }
    }

    @ParameterizedTest(name = "core legacy and adoption characterization: Gradle {0}")
    @MethodSource("versions")
    fun rootOnlyLegacyIsPreservedAndOptInRequiresLocalParticipation(version: String) {
        fixture()
        val args = listOf("-PbomVersion=1.0", "-Penforced=true")
        verify(run(version, false, false, tasks, args, cache = false), "1.0", "1.0")
        assertStored(run(version, false, false, tasks, args).also { verify(it, "1.0", "1.0") })
        assertReused(run(version, false, false, tasks, args).also { verify(it, "1.0", "1.0") })
        val legacy = run(version, true, false, tasks, args, fail = true)
        assertTrue(legacy.output.contains("cannot access"), legacy.output)
        assertTrue(legacy.output.contains("configurations"), legacy.output)
        assertTrue(legacy.output.contains("Configuration cache entry discarded"), legacy.output)
        val absent = run(version, true, true, listOf(":sibling:copyannotationProcessor"),
            args + "-Pnonparticipating=true", fail = true)
        assertTrue(absent.output.contains("sample:proc"), absent.output)
        assertFalse(absent.output.contains("cannot access"), absent.output)
    }

    @ParameterizedTest(name = "core rollout and rollback: Gradle {0}")
    @MethodSource("versions")
    fun sharedConventionSupportsFlagRollbackInOneDaemon(version: String) {
        fixture(heap = 515)
        val args = listOf("-PbomVersion=2.0", "-Penforced=true")
        val selectedTasks = tasks + ":daemonIdentity"
        var daemon: String? = null
        fun check(result: BuildResult) {
            verify(result, "2.0", "2.0")
            val pid = Regex("testDaemon=([0-9]+)").find(result.output)?.groupValues?.get(1)
            assertNotNull(pid, result.output)
            if (daemon == null) daemon = pid else assertEquals(daemon, pid, result.output)
        }
        listOf<Boolean?>(null, false, true, false, true, null).forEachIndexed { index, enabled ->
            check(run(version, false, enabled, selectedTasks, args, cache = false))
            val cold = run(version, false, enabled, selectedTasks, args)
            if (index < 3) assertStored(cold)
            check(cold)
            assertReused(run(version, false, enabled, selectedTasks, args).also(::check))
            if (enabled == true) {
                val isolated = run(version, true, true, selectedTasks, args)
                if (index == 2) assertStored(isolated)
                check(isolated)
                assertReused(run(version, true, true, selectedTasks, args).also(::check))
            }
        }
    }

    private fun fixture(eager: Boolean = true, adapter: String? = null, heap: Int = 513) {
        write("settings.gradle", "rootProject.name = 'core-bom-ownership'\ninclude 'sibling', 'parent:leaf'\n")
        write("gradle.properties", """
            systemProp.nebula.features.coreBomSupport=true
            org.gradle.jvmargs=-Xmx${heap}m -Dnebula.features.coreBomSupport=true
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """)
        write("policy.gradle", """
            dependencyRecommendations {
                eagerlyResolve = $eager
                excludeConfigurations('excludedClasspath', 'excludedAnnotationProcessor')
                excludeConfigurationPrefixes('skipped')
                mavenBom module: 'sample:bom:' + providers.gradleProperty('bomVersion' + project.path.replace(':', '_'))
                    .orElse(providers.gradleProperty('bomVersion')).get(),
                    enforced: providers.gradleProperty('enforced').orElse('false').get().toBoolean()
            }
        """)
        val repository = "repositories { maven { url = new File(rootDir, providers.gradleProperty('repository').orElse('repo').get()).toURI() } }\n"
        val applyPolicy = "apply from: new File(rootDir, 'policy.gradle')\n"
        val manual = if (adapter == null) "" else """
            afterEvaluate {
                assert configurations.nebulaRecommenderBom.state.toString() == 'UNRESOLVED'
                configurations.nebulaRecommenderBom.dependencies.clear()
                dependencyRecommendations.mavenBom module: 'sample:bom:2.0', enforced: true
        """ + if (adapter == "util") """
                netflix.nebula.dependency.recommender.util.BomResolutionUtil.eagerlyResolveBoms(
                    project, dependencyRecommendations, 'nebulaRecommenderBom')
            }
        """ else """
                plugins.getPlugin(netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin)
                    .eagerlyResolveBoms(project, dependencyRecommendations)
            }
        """
        write("build.gradle", """
            plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
        """ + "\n" + repository + applyPolicy + manual + """
            tasks.register('removeBom', Delete) { delete layout.projectDirectory.file('repo/sample/bom/2.0/bom-2.0.pom') }
            tasks.register('daemonIdentity') { doLast { println('testDaemon=' + ProcessHandle.current().pid()) } }
        """)
        write("parent/build.gradle", "")
        consumers.forEach { path ->
            write(directory(path) + "/build.gradle", """
                plugins {
                    id 'java-library'
                    id 'maven-publish'
                    id 'com.netflix.nebula.dependency-recommender' apply false
                }
                group = 'sample.published'
                version = '1.0'
            """ + "\n" + repository + """
                def enabled = providers.gradleProperty('$SUPPORT').orElse('false').get().toBoolean()
                if (enabled && !providers.gradleProperty('nonparticipating').orElse('false').get().toBoolean()) {
                    apply plugin: 'com.netflix.nebula.dependency-recommender'
                    $applyPolicy
                    $manual
                }
                sourceSets.create('smoke')
                configurations {
                    excludedClasspath { canBeConsumed = false }
                    excludedAnnotationProcessor { canBeConsumed = false }
                    unrelated { canBeConsumed = false }
                    skippedProbeClasspath { canBeConsumed = false }
                }
                dependencies {
                    implementation 'sample:foo' + providers.gradleProperty('explicit').map { ':' + it }.orElse('').get()
                    implementation 'sample:bridge:1.0'
                    smokeImplementation 'sample:foo'
                    annotationProcessor 'sample:proc'
                    testAnnotationProcessor 'sample:proc'
                    excludedClasspath 'sample:foo:3.0'
                    excludedAnnotationProcessor 'sample:proc:3.0'
                    unrelated 'sample:foo:3.0'
                    skippedProbeClasspath 'sample:foo'
                }
                configurations.matching {
                    it.name in ['compileClasspath', 'runtimeClasspath', 'testCompileClasspath', 'testRuntimeClasspath']
                }.configureEach {
                    if (providers.gradleProperty('force').isPresent()) {
                        resolutionStrategy.force 'sample:foo:' + providers.gradleProperty('force').get()
                    }
                }
                afterEvaluate {
                    configurations.create('lateClasspath') { canBeConsumed = false }
                    dependencies.add('lateClasspath', 'sample:foo')
                }
                tasks.register('cleanResolved', Delete) { delete layout.buildDirectory.dir('resolved') }
            """ + configurations.joinToString("\n") { name -> """
                tasks.register('copy$name', Sync) {
                    dependsOn 'cleanResolved'
                    from configurations.named('$name')
                    into layout.buildDirectory.dir('resolved/$name')
                }
            """ } + """
                tasks.register('fooInsight', org.gradle.api.tasks.diagnostics.DependencyInsightReportTask) {
                    showingAllVariants.set(false)
                    setConfiguration('runtimeClasspath')
                    setDependencySpec('sample:foo')
                }
                publishing {
                    publications {
                        maven(MavenPublication) {
                            from components.java
                            versionMapping {
                                usage('java-api') { fromResolutionOf('runtimeClasspath') }
                                usage('java-runtime') { fromResolutionResult() }
                            }
                        }
                    }
                }
            """)
        }
        listOf("1.0", "2.0", "3.0").forEach { version ->
            listOf("foo", "proc").forEach { artifact ->
                pom(artifact, version, "jar", "")
                JarOutputStream(File(projectDir, "repo/sample/$artifact/$version/$artifact-$version.jar").outputStream()).use {
                    it.putNextEntry(JarEntry("version.txt")); it.write("$artifact:$version".toByteArray()); it.closeEntry()
                }
            }
            bom(version, version)
        }
        pom("bridge", "1.0", "jar", """
            <dependencies><dependency><groupId>sample</groupId><artifactId>foo</artifactId><version>1.0</version></dependency></dependencies>
        """)
        JarOutputStream(File(projectDir, "repo/sample/bridge/1.0/bridge-1.0.jar").outputStream()).use {
            it.putNextEntry(JarEntry("version.txt")); it.write("bridge:1.0".toByteArray()); it.closeEntry()
        }
    }

    private fun verify(result: BuildResult, policy: String, selected: String) {
        consumers.forEach { path -> verifyConsumer(result, path, policy, selected) }
    }

    private fun verifyConsumer(result: BuildResult, path: String, policy: String, selected: String) {
        configurations.forEach { name ->
            val expected = when {
                name in mainClasspaths -> mapOf("foo" to selected, "bridge" to "1.0")
                name in ownClasspaths -> mapOf("foo" to policy)
                name in processors -> mapOf("proc" to policy)
                name == "excludedAnnotationProcessor" -> mapOf("proc" to "3.0")
                else -> mapOf("foo" to "3.0")
            }
            assertEquals(TaskOutcome.SUCCESS, result.task("$path:copy$name")?.outcome, result.output)
            val output = File(projectDir, "${directory(path)}/build/resolved/$name")
            assertEquals(expected.map { (a, v) -> "$a-$v.jar" }.toSet(), output.list()?.toSet(), "$path $name")
            expected.forEach { (a, v) ->
                JarFile(File(output, "$a-$v.jar")).use {
                    assertEquals("$a:$v", it.getInputStream(it.getJarEntry("version.txt")).bufferedReader().readText())
                }
            }
        }
        val published = File(projectDir, "${directory(path)}/build/publications/maven")
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(published, "pom-default.xml"))
        val deps = xml.getElementsByTagName("dependency")
        var found = false
        for (i in 0 until deps.length) {
            val element = deps.item(i) as org.w3c.dom.Element
            val artifact = element.getElementsByTagName("artifactId").item(0).textContent
            assertNotEquals("bom", artifact)
            if (artifact == "foo") {
                found = true
                assertEquals(selected, element.getElementsByTagName("version").item(0).textContent)
            }
        }
        assertTrue(found, File(published, "pom-default.xml").readText())
        val metadata = File(published, "module.json").readText()
        assertFalse(metadata.contains("\"module\": \"bom\""), metadata)
    }

    private fun reasons(result: BuildResult): Map<String, Set<String>> = consumers.associateWith { path ->
        val insight = result.output.substringAfter("> Task $path:fooInsight").substringBefore("\n> Task ")
        val block = insight.substringAfter("Selection reasons:", "").substringBefore("\n\n")
        val multiple = block.lineSequence().map { it.trim() }.filter { it.startsWith("- ") }
            .map { it.removePrefix("- ") }.toSet()
        // Gradle renders a single reason inline, e.g. "sample:foo:1.0 (by constraint)".
        val single = Regex("^sample:foo:[^\\n]+ \\(([^)]+)\\)", RegexOption.MULTILINE).find(insight)
            ?.groupValues?.get(1)?.replaceFirstChar { it.uppercase() }
        if (multiple.isNotEmpty()) multiple else single?.let { setOf(it) } ?: emptySet()
    }

    private fun run(version: String, isolated: Boolean, enabled: Boolean?, selectedTasks: List<String>, extra: List<String>,
                    cache: Boolean = true, fail: Boolean = false): BuildResult {
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
            if (isolated) "--isolated-projects" else "--no-isolated-projects"
        } else "-Dorg.gradle.unsafe.isolated-projects=$isolated"
        val flag = enabled?.let { listOf("-P$SUPPORT=$it") } ?: emptyList()
        val diagnostics = if (fail && GradleVersion.version(version) >= GradleVersion.version("9.7"))
            listOf("-Dorg.gradle.isolated-projects.diagnostics=true") else emptyList()
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(selectedTasks + extra + flag + diagnostics + listOf(isolation, "--parallel",
                if (cache) "--configuration-cache" else "--no-configuration-cache",
                "--configuration-cache-problems=fail", "--console=plain", "--stacktrace"))
        return if (fail) runner.buildAndFail() else runner.build()
    }

    private fun assertStored(result: BuildResult) {
        assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
        assertFalse(result.output.contains("Reusing configuration cache"), result.output)
    }
    private fun assertReused(result: BuildResult) {
        assertTrue(result.output.contains("Reusing configuration cache"), result.output)
        assertTrue(result.output.contains("Configuration cache entry reused"), result.output)
    }
    private fun directory(path: String) = path.trimStart(':').replace(':', '/')
    private fun write(path: String, text: String) { File(projectDir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) } }
    private fun pom(a: String, v: String, packaging: String, body: String) =
        write("repo/sample/$a/$v/$a-$v.pom", """
            <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>$a</artifactId>
            <version>$v</version><packaging>$packaging</packaging>$body</project>
        """)
    private fun bom(coordinate: String, selected: String) = pom("bom", coordinate, "pom", """
        <dependencyManagement><dependencies>
        <dependency><groupId>sample</groupId><artifactId>foo</artifactId><version>$selected</version></dependency>
        <dependency><groupId>sample</groupId><artifactId>proc</artifactId><version>$selected</version></dependency>
        </dependencies></dependencyManagement>
    """)
    companion object {
        private const val SUPPORT = "nebula.dependency-recommender.isolatedProjectsSupport"
        @JvmStatic fun versions(): Stream<String> = listOf("9.1.0", GradleVersion.current().version).distinct().stream()
        @JvmStatic fun cacheModes(): Stream<Arguments> = versions().flatMap { Stream.of(Arguments.of(it, false), Arguments.of(it, true)) }
        @JvmStatic fun platformModes(): Stream<Arguments> = versions().flatMap { version ->
            Stream.of(false, true).flatMap { isolated -> Stream.of(Arguments.of(version, isolated, false), Arguments.of(version, isolated, true)) }
        }
    }
}
