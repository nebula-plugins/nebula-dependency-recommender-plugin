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

/** Exercises Maven POM inheritance, independently of Gradle project recommendation inheritance. */
class ParentBomOwnershipTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest(name = "Maven parents: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `parent policies retain owner context and invalidate without changing the child BOM`(version: String, isolated: Boolean) {
        listOf(false, true).forEach { reverse ->
            fixture(reverse)
            val paths = if (reverse) listOf(":z", ":parent:a") else listOf(":a", ":parent:z")
            val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
                .let { if (reverse) it.reversed() else it }
            val childBytes = File(projectDir, "right-repo/sample/bom/1.0/bom-1.0.pom").readBytes()
            assertArrayEquals(childBytes, File(projectDir, "left-repo/sample/bom/1.0/bom-1.0.pom").readBytes())

            fun check(left: String, right: String, selected: List<String> = listOf(left, right), owners: List<String> = listOf("left", "right")) {
                val inputs = listOf("-Pleft.version=$left", "-Pright.version=$right")
                verify(run(version, false, tasks, inputs, cache = false), paths, selected, owners)
                val cold = run(version, isolated, tasks, inputs)
                assertStored(cold)
                verify(cold, paths, selected, owners)
                val warm = run(version, isolated, tasks, inputs)
                assertReused(warm)
                verify(warm, paths, selected, owners)
            }

            check("1.0", "2.0")
            check("2.0", "1.0")

            // Notify Gradle of deletion before replacing the file so daemon filesystem snapshots
            // cannot hide a test-side repository mutation. Neither child BOM nor build script changes.
            run(version, isolated, listOf("purgeParent"), emptyList())
            parent("right", "3.0")
            assertArrayEquals(childBytes, File(projectDir, "right-repo/sample/bom/1.0/bom-1.0.pom").readBytes())
            check("2.0", "1.0", listOf("2.0", "3.0"))

            // A repository switch must resolve the same child and parent coordinates in its new context.
            val build = File(projectDir, "left/build.gradle")
            build.writeText(build.readText().replace(repo("left"), repo("right")))
            check("2.0", "1.0", listOf("3.0", "3.0"), listOf("right", "right"))
        }
    }

    @ParameterizedTest(name = "Maven hierarchy: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `multiple parent levels preserve Maven property precedence and tracked system interpolation`(version: String, isolated: Boolean) {
        fixture(false)
        listOf("left", "right").forEach { owner ->
            pom(owner, "base", "1.0", "pom", """
                <properties><managed.version>3.0</managed.version></properties>
                ${management("\${managed.version}")}
            """)
            pom(owner, "policy", "1.0", "pom", """
                ${parentReference("base")}
                <properties><managed.version>${'$'}{$owner.version}</managed.version></properties>
            """)
            // Inherit group and version too, so the effective-model reason must remain the child BOM.
            write("$owner-repo/sample/bom/1.0/bom-1.0.pom", """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                    ${parentReference("policy")}
                    <artifactId>bom</artifactId><packaging>pom</packaging>
                </project>
            """)
        }
        val paths = listOf(":a", ":parent:z")
        val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
        val gradleInputs = listOf("-Pleft.version=1.0", "-Pright.version=2.0")
        listOf(emptyList(), listOf("-Dleft.version=2.0", "-Dright.version=1.0")).forEach { systemInputs ->
            val selected = if (systemInputs.isEmpty()) listOf("1.0", "2.0") else listOf("2.0", "1.0")
            val inputs = gradleInputs + systemInputs
            verify(run(version, false, tasks, inputs, cache = false), paths, selected, listOf("left", "right"))
            val cold = run(version, isolated, tasks, inputs)
            assertStored(cold)
            verify(cold, paths, selected, listOf("left", "right"))
            val warm = run(version, isolated, tasks, inputs)
            assertReused(warm)
            verify(warm, paths, selected, listOf("left", "right"))
        }
    }

    @ParameterizedTest(name = "Parent manual timing: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `both manual adapters honor late child BOM replacement with Maven parents`(version: String, isolated: Boolean) {
        listOf("util", "plugin").forEach { adapter ->
            fixture(false)
            listOf("left", "right").forEach { owner ->
                pom(owner, "policy", "2.0", "pom", management("\${$owner.version}"))
                pom(owner, "bom", "2.0", "pom", parentReference("policy", "2.0"))
                val call = if (adapter == "util") """
                    netflix.nebula.dependency.recommender.util.BomResolutionUtil.eagerlyResolveBoms(
                        project, dependencyRecommendations, 'nebulaRecommenderBom')
                """ else """
                    plugins.getPlugin(netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin)
                        .eagerlyResolveBoms(project, dependencyRecommendations)
                """
                File(projectDir, "$owner/build.gradle").appendText("\n" + """
                    dependencyRecommendations.eagerlyResolve = false
                    afterEvaluate {
                        configurations.nebulaRecommenderBom.dependencies.clear()
                        dependencyRecommendations.mavenBom module: 'sample:bom:2.0'
                        $call
                    }
                """.trimIndent())
            }
            val paths = listOf(":a", ":parent:z")
            val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
            val inputs = listOf("-Pleft.version=1.0", "-Pright.version=2.0")
            verify(run(version, false, tasks, inputs, cache = false), paths, listOf("1.0", "2.0"), listOf("left", "right"), "2.0")
            val cold = run(version, isolated, tasks, inputs)
            assertStored(cold)
            verify(cold, paths, listOf("1.0", "2.0"), listOf("left", "right"), "2.0")
            val warm = run(version, isolated, tasks, inputs)
            assertReused(warm)
            verify(warm, paths, listOf("1.0", "2.0"), listOf("left", "right"), "2.0")
        }
    }

    @ParameterizedTest(name = "Parent rollout: Gradle {0}")
    @MethodSource("versions")
    fun `single owner Maven inheritance stays compatible across rollout and rollback`(version: String) {
        fixture(false)
        write("settings.gradle", """
            rootProject.name = 'parent-bom-rollout'
            include ':a'
            project(':a').projectDir = file('left')
        """)
        val paths = listOf(":a")
        val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
        val properties = File(projectDir, "gradle.properties")
        properties.writeText(properties.readText().replace("nebula.dependency-recommender.isolatedProjectsSupport=true", ""))
        listOf<String?>(null, "false", "true", "false", "true", null).forEachIndexed { index, flag ->
            val inputs = listOf("-Pleft.version=2.0") +
                (flag?.let { listOf("-Pnebula.dependency-recommender.isolatedProjectsSupport=$it") } ?: emptyList())
            verify(run(version, false, tasks, inputs, cache = false), paths, listOf("2.0"), listOf("left"))
            val cold = run(version, false, tasks, inputs)
            // Returning to a previous property value may restore its existing cache entry.
            if (index < 3) assertStored(cold)
            verify(cold, paths, listOf("2.0"), listOf("left"))
            val warm = run(version, false, tasks, inputs)
            assertReused(warm)
            verify(warm, paths, listOf("2.0"), listOf("left"))
        }
    }

    private fun fixture(reverse: Boolean) {
        write("settings.gradle", """
            rootProject.name = 'parent-bom-ownership'
            include '${if (reverse) ":z" else ":a"}', '${if (reverse) ":parent:a" else ":parent:z"}'
            project('${if (reverse) ":z" else ":a"}').projectDir = file('left')
            project('${if (reverse) ":parent:a" else ":parent:z"}').projectDir = file('right')
        """)
        write("build.gradle", """
            tasks.register('purgeParent', Delete) {
                delete layout.projectDirectory.file('right-repo/sample/policy/1.0/policy-1.0.pom')
            }
        """)
        write("parent/build.gradle", "")
        write("gradle.properties", """
            nebula.dependency-recommender.useBuildService=true
            nebula.dependency-recommender.isolatedProjectsSupport=true
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.jvmargs=-Xmx512m -Dnebula.features.coreBomSupport=false
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """)
        listOf("left", "right").forEach { owner ->
            parent(owner, "\${$owner.version}")
            pom(owner, "bom", "1.0", "pom", parentReference("policy"))
            listOf("1.0", "2.0", "3.0").forEach { selected ->
                pom(owner, "foo", selected, "jar", "")
                JarOutputStream(File(projectDir, "$owner-repo/sample/foo/$selected/foo-$selected.jar").outputStream()).use {
                    it.putNextEntry(JarEntry("policy.txt"))
                    it.write("$owner:$selected".toByteArray())
                    it.closeEntry()
                }
            }
            write("$owner/build.gradle", """
                plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
                repositories { maven { url = uri('${repo(owner)}') } }
                dependencyRecommendations { mavenBom module: 'sample:bom:1.0' }
                dependencies { implementation 'sample:foo' }
                tasks.register('cleanResolved', Delete) { delete layout.buildDirectory.dir('resolved') }
                tasks.register('resolveRecommendations', Sync) {
                    dependsOn 'cleanResolved'
                    from configurations.runtimeClasspath
                    into layout.buildDirectory.dir('resolved')
                }
                tasks.named('dependencyInsight') {
                    setConfiguration('runtimeClasspath')
                    setDependencySpec('sample:foo')
                }
            """)
        }
    }

    private fun parentReference(artifact: String, version: String = "1.0") = """
        <parent><groupId>sample</groupId><artifactId>$artifact</artifactId><version>$version</version><relativePath/></parent>
    """

    private fun parent(owner: String, selected: String) = pom(owner, "policy", "1.0", "pom", management(selected))

    private fun management(selected: String) = """
        <dependencyManagement><dependencies><dependency>
            <groupId>sample</groupId><artifactId>foo</artifactId><version>$selected</version>
        </dependency></dependencies></dependencyManagement>
    """

    private fun pom(owner: String, artifact: String, version: String, packaging: String, body: String) {
        write("$owner-repo/sample/$artifact/$version/$artifact-$version.pom", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                $body
                <groupId>sample</groupId><artifactId>$artifact</artifactId><version>$version</version><packaging>$packaging</packaging>
            </project>
        """)
    }

    private fun verify(result: BuildResult, paths: List<String>, selected: List<String>, owners: List<String>, bomVersion: String = "1.0") {
        paths.forEachIndexed { index, path ->
            assertEquals(TaskOutcome.SUCCESS, result.task("$path:resolveRecommendations")?.outcome, result.output)
            val resolved = File(projectDir, "${if (index == 0) "left" else "right"}/build/resolved")
            assertEquals(setOf("foo-${selected[index]}.jar"), resolved.list()?.toSet(), result.output)
            JarFile(File(resolved, "foo-${selected[index]}.jar")).use { jar ->
                assertEquals("${owners[index]}:${selected[index]}", jar.getInputStream(jar.getJarEntry("policy.txt")).bufferedReader().readText())
            }
            val insight = result.output.substringAfter("> Task $path:dependencyInsight").substringBefore("\n> Task ")
            assertTrue(insight.contains("Recommending version ${selected[index]} for dependency sample:foo via conflict resolution recommendation"), insight)
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

    private fun repo(owner: String) = File(projectDir, "$owner-repo").toURI().toString()

    private fun assertStored(result: BuildResult) {
        assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
        assertFalse(result.output.contains("Reusing configuration cache"), result.output)
    }

    private fun assertReused(result: BuildResult) {
        assertTrue(result.output.contains("Reusing configuration cache"), result.output)
        assertTrue(result.output.contains("Configuration cache entry reused"), result.output)
    }

    private fun write(path: String, contents: String) {
        File(projectDir, path).apply { parentFile.mkdirs(); writeText(contents.trimIndent()) }
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
