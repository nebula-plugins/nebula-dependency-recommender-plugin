/*
 * Copyright 2026 the original author or authors.
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

/**
 * Verifies effective Maven import policy using resolved artifacts and native resolution reasons.
 * Foo distinguishes import order, bar checks an explicit top-level override, and baz comes from
 * a nested import. Each JAR identifies its repository owner as well as its artifact and version.
 */
class ImportedBomOwnershipTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest(name = "Maven imports: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `imports retain owner context and track edits below an unchanged top level BOM`(version: String, isolated: Boolean) {
        listOf(false, true).forEach { reverse ->
            val paths = fixture(reverse)
            val tasks = tasks(paths).let { if (reverse) it.reversed() else it }
            val top = file("right-repo/sample/bom/1.0/bom-1.0.pom").readBytes()
            assertArrayEquals(top, file("left-repo/sample/bom/1.0/bom-1.0.pom").readBytes())

            fun check(inputs: List<String>, selected: List<List<String>>, owners: List<String> = listOf("left", "right")) {
                compare(version, isolated, paths, tasks, inputs, selected, owners)
            }
            check(inputs("1.0", "2.0"), listOf(policy("1.0"), policy("2.0")))
            val unchangedInputs = inputs("2.0", "1.0")
            check(unchangedInputs, listOf(policy("2.0"), policy("1.0")))

            // Have Gradle observe deletion before replacing repository bytes outside the build.
            // The requested BOM, its coordinates, and the interpolation inputs remain unchanged.
            run(version, isolated, listOf("purgePolicy"), unchangedInputs)
            importedPolicy("right", "3.0")
            assertArrayEquals(top, file("right-repo/sample/bom/1.0/bom-1.0.pom").readBytes())
            check(unchangedInputs, listOf(policy("2.0"), listOf("3.0", "3.0", "1.0")))

            val importedBytes = file("right-repo/sample/policy/1.0/policy-1.0.pom").readBytes()
            run(version, isolated, listOf("purgeNested"), unchangedInputs)
            nestedPolicy("right", "3.0")
            assertArrayEquals(top, file("right-repo/sample/bom/1.0/bom-1.0.pom").readBytes())
            assertArrayEquals(importedBytes, file("right-repo/sample/policy/1.0/policy-1.0.pom").readBytes())
            check(unchangedInputs, listOf(policy("2.0"), policy("3.0")))

            val build = file("left/build.gradle")
            build.writeText(build.readText().replace(repo("left"), repo("right")))
            check(unchangedInputs, listOf(policy("3.0"), policy("3.0")), listOf("right", "right"))
        }
    }

    @ParameterizedTest(name = "Maven import inputs: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `system interpolation and changed import order preserve effective model precedence`(version: String, isolated: Boolean) {
        val paths = fixture(false)
        val tasks = tasks(paths)
        val gradleInputs = inputs("1.0", "2.0")
        compare(version, isolated, paths, tasks, gradleInputs, listOf(policy("1.0"), policy("2.0")), listOf("left", "right"))
        val systemInputs = gradleInputs + listOf("-Dleft.version=2.0", "-Dright.version=1.0")
        compare(version, isolated, paths, tasks, systemInputs, listOf(policy("2.0"), policy("1.0")), listOf("left", "right"))

        // The first imported definition wins, while the top-level bar override wins over both.
        listOf("left", "right").forEach { topBom(it, fallbackFirst = true) }
        compare(version, isolated, paths, tasks, systemInputs, listOf(policy("3.0"), policy("3.0")), listOf("left", "right"))
    }

    @ParameterizedTest(name = "Maven import manual timing: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `both manual adapters retain late customization of an imported BOM graph`(version: String, isolated: Boolean) {
        listOf("util", "plugin").forEach { adapter ->
            val paths = fixture(false)
            listOf("left", "right").forEach { owner ->
                nestedPolicy(owner, "\${$owner.version}", version = "2.0")
                importedPolicy(owner, "\${$owner.version}", version = "2.0")
                topBom(owner, version = "2.0")
                val call = if (adapter == "util") """
                    netflix.nebula.dependency.recommender.util.BomResolutionUtil.eagerlyResolveBoms(
                        project, dependencyRecommendations, 'nebulaRecommenderBom')
                """ else """
                    plugins.getPlugin(netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin)
                        .eagerlyResolveBoms(project, dependencyRecommendations)
                """
                file("$owner/build.gradle").appendText("\n" + """
                    dependencyRecommendations.eagerlyResolve = false
                    afterEvaluate {
                        assert configurations.nebulaRecommenderBom.state.toString() == 'UNRESOLVED'
                        configurations.nebulaRecommenderBom.dependencies.clear()
                        dependencyRecommendations.mavenBom module: 'sample:bom:2.0'
                        $call
                    }
                """.trimIndent())
            }
            compare(version, isolated, paths, tasks(paths), inputs("1.0", "2.0"),
                listOf(policy("1.0"), policy("2.0")), listOf("left", "right"), bomVersion = "2.0")
        }
    }

    @ParameterizedTest(name = "Maven import rollout: Gradle {0}")
    @MethodSource("versions")
    fun `single owner import semantics survive rollout and rollback in one daemon`(version: String) {
        fixture(false, heap = 523)
        write("settings.gradle", """
            rootProject.name = 'imported-bom-rollout'
            include ':a'
            project(':a').projectDir = file('left')
        """)
        val properties = file("gradle.properties")
        properties.writeText(properties.readText().replace("nebula.dependency-recommender.isolatedProjectsSupport=true", ""))
        file("left/build.gradle").appendText("\n" + """
            tasks.register('daemonIdentity') {
                doLast { println 'importDaemon=' + ProcessHandle.current().pid() }
            }
        """.trimIndent())
        val paths = listOf(":a")
        val tasks = tasks(paths) + ":a:daemonIdentity"
        var daemon: String? = null
        fun check(result: BuildResult) {
            verify(result, paths, listOf(policy("2.0")), listOf("left"), "1.0")
            val current = Regex("importDaemon=([0-9]+)").find(result.output)?.groupValues?.get(1)
            assertNotNull(current, result.output)
            if (daemon == null) daemon = current else assertEquals(daemon, current, result.output)
        }
        listOf<String?>(null, "false", "true", "false", "true", null).forEachIndexed { index, flag ->
            val inputs = listOf("-Pleft.version=2.0") +
                (flag?.let { listOf("-Pnebula.dependency-recommender.isolatedProjectsSupport=$it") } ?: emptyList())
            check(run(version, false, tasks, inputs, cache = false))
            val cold = run(version, false, tasks, inputs)
            if (index < 3) assertStored(cold)
            check(cold)
            val warm = run(version, false, tasks, inputs)
            assertReused(warm)
            check(warm)
            if (flag == "true") {
                val isolated = run(version, true, tasks, inputs)
                if (index == 2) assertStored(isolated)
                check(isolated)
                val reused = run(version, true, tasks, inputs)
                assertReused(reused)
                check(reused)
            }
        }
    }

    private fun fixture(reverse: Boolean, heap: Int = 512): List<String> {
        val paths = if (reverse) listOf(":z", ":parent:a") else listOf(":a", ":parent:z")
        write("settings.gradle", """
            rootProject.name = 'imported-bom-ownership'
            include '${paths[0]}', '${paths[1]}'
            project('${paths[0]}').projectDir = file('left')
            project('${paths[1]}').projectDir = file('right')
        """)
        write("build.gradle", """
            tasks.register('purgePolicy', Delete) {
                delete layout.projectDirectory.file('right-repo/sample/policy/1.0/policy-1.0.pom')
            }
            tasks.register('purgeNested', Delete) {
                delete layout.projectDirectory.file('right-repo/sample/nested/1.0/nested-1.0.pom')
            }
        """)
        write("parent/build.gradle", "")
        write("gradle.properties", """
            nebula.dependency-recommender.useBuildService=true
            nebula.dependency-recommender.isolatedProjectsSupport=true
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.jvmargs=-Xmx${heap}m -Dnebula.features.coreBomSupport=false
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """)
        listOf("left", "right").forEach { owner ->
            nestedPolicy(owner, "\${$owner.version}")
            importedPolicy(owner, "\${$owner.version}")
            pom(owner, "fallback", "1.0", management(
                dependency("foo", "3.0"), dependency("bar", "2.0"), dependency("baz", "3.0")))
            topBom(owner)
            listOf("foo", "bar", "baz").forEach { artifact ->
                listOf("1.0", "2.0", "3.0").forEach { selected ->
                    pom(owner, artifact, selected, "", packaging = "jar")
                    JarOutputStream(file("$owner-repo/sample/$artifact/$selected/$artifact-$selected.jar").outputStream()).use {
                        it.putNextEntry(JarEntry("policy.txt"))
                        it.write("$owner:$artifact:$selected".toByteArray())
                        it.closeEntry()
                    }
                }
            }
            write("$owner/build.gradle", """
                plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
                repositories { maven { url = uri('${repo(owner)}') } }
                dependencyRecommendations { mavenBom module: 'sample:bom:1.0' }
                dependencies {
                    implementation 'sample:foo'
                    implementation 'sample:bar'
                    implementation 'sample:baz'
                }
                tasks.register('cleanResolved', Delete) { delete layout.buildDirectory.dir('resolved') }
                tasks.register('resolveRecommendations', Sync) {
                    dependsOn 'cleanResolved'
                    from configurations.runtimeClasspath
                    into layout.buildDirectory.dir('resolved')
                }
                tasks.named('dependencyInsight') {
                    setConfiguration('runtimeClasspath')
                    setDependencySpec('sample')
                }
            """)
        }
        return paths
    }

    private fun topBom(owner: String, version: String = "1.0", fallbackFirst: Boolean = false) {
        val imports = listOf(dependency("policy", version, imported = true), dependency("fallback", "1.0", imported = true))
            .let { if (fallbackFirst) it.reversed() else it }
        pom(owner, "bom", version, """
            <properties><managed.version>3.0</managed.version></properties>
            ${management(*(imports + dependency("bar", "3.0")).toTypedArray())}
        """)
    }

    private fun importedPolicy(owner: String, selected: String, version: String = "1.0") = pom(owner, "policy", version, """
        <properties><managed.version>$selected</managed.version></properties>
        ${management(dependency("nested", version, imported = true), dependency("foo", "\${managed.version}"), dependency("bar", "\${managed.version}"))}
    """)

    private fun nestedPolicy(owner: String, selected: String, version: String = "1.0") =
        pom(owner, "nested", version, management(dependency("baz", selected)))

    private fun dependency(artifact: String, version: String, imported: Boolean = false) = """
        <dependency><groupId>sample</groupId><artifactId>$artifact</artifactId><version>$version</version>
            ${if (imported) "<type>pom</type><scope>import</scope>" else ""}
        </dependency>
    """

    private fun management(vararg dependencies: String) =
        "<dependencyManagement><dependencies>${dependencies.joinToString("\n")}</dependencies></dependencyManagement>"

    private fun pom(owner: String, artifact: String, version: String, body: String, packaging: String = "pom") {
        write("$owner-repo/sample/$artifact/$version/$artifact-$version.pom", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>sample</groupId><artifactId>$artifact</artifactId><version>$version</version><packaging>$packaging</packaging>
                $body
            </project>
        """)
    }

    private fun compare(version: String, isolated: Boolean, paths: List<String>, tasks: List<String>, inputs: List<String>,
                        selected: List<List<String>>, owners: List<String>, bomVersion: String = "1.0") {
        verify(run(version, false, tasks, inputs, cache = false), paths, selected, owners, bomVersion)
        val cold = run(version, isolated, tasks, inputs)
        assertStored(cold)
        verify(cold, paths, selected, owners, bomVersion)
        val warm = run(version, isolated, tasks, inputs)
        assertReused(warm)
        verify(warm, paths, selected, owners, bomVersion)
    }

    private fun verify(result: BuildResult, paths: List<String>, selected: List<List<String>>, owners: List<String>, bomVersion: String) {
        paths.forEachIndexed { index, path ->
            assertEquals(TaskOutcome.SUCCESS, result.task("$path:resolveRecommendations")?.outcome, result.output)
            val resolved = file("${if (index == 0) "left" else "right"}/build/resolved")
            val artifacts = listOf("foo", "bar", "baz").zip(selected[index])
            assertEquals(artifacts.map { (artifact, version) -> "$artifact-$version.jar" }.toSet(), resolved.list()?.toSet(), result.output)
            val insight = result.output.substringAfter("> Task $path:dependencyInsight").substringBefore("\n> Task ")
            artifacts.forEach { (artifact, selectedVersion) ->
                JarFile(File(resolved, "$artifact-$selectedVersion.jar")).use { jar ->
                    assertEquals("${owners[index]}:$artifact:$selectedVersion", jar.getInputStream(jar.getJarEntry("policy.txt")).bufferedReader().readText())
                }
                assertTrue(insight.contains("Recommending version $selectedVersion for dependency sample:$artifact via conflict resolution recommendation"), insight)
            }
            val reasons = Regex("nebula.dependency-recommender uses mavenBom: ([^\\s,]+)")
                .findAll(insight).map { it.groupValues[1] }.toSet()
            assertEquals(setOf("sample:bom:pom:$bomVersion"), reasons, insight)
        }
    }

    private fun run(version: String, isolated: Boolean, tasks: List<String>, extra: List<String>, cache: Boolean = true): BuildResult {
        val flag = if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
            if (isolated) "--isolated-projects" else "--no-isolated-projects"
        } else "-Dorg.gradle.unsafe.isolated-projects=$isolated"
        return GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + extra + listOf(flag, if (cache) "--configuration-cache" else "--no-configuration-cache",
                "--configuration-cache-problems=fail", "--console=plain", "--stacktrace")).build()
    }

    private fun tasks(paths: List<String>) = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
    private fun inputs(left: String, right: String) = listOf("-Pleft.version=$left", "-Pright.version=$right")
    private fun policy(selected: String) = listOf(selected, "3.0", selected)
    private fun repo(owner: String) = file("$owner-repo").toURI().toString()
    private fun file(path: String) = File(projectDir, path)
    private fun assertStored(result: BuildResult) {
        assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
        assertFalse(result.output.contains("Reusing configuration cache"), result.output)
    }
    private fun assertReused(result: BuildResult) {
        assertTrue(result.output.contains("Reusing configuration cache"), result.output)
        assertTrue(result.output.contains("Configuration cache entry reused"), result.output)
    }
    private fun write(path: String, contents: String) {
        file(path).apply { parentFile.mkdirs(); writeText(contents.trimIndent()) }
    }

    companion object {
        @JvmStatic
        fun versions(): Stream<String> = listOf("9.1.0", GradleVersion.current().version).distinct().stream()
        @JvmStatic
        fun modes(): Stream<Arguments> = versions().flatMap { version ->
            Stream.of(Arguments.of(version, false), Arguments.of(version, true))
        }
    }
}
