package com.netflix.nebula.dependency.recommender

import netflix.nebula.dependency.recommender.service.BomResolverService
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.gradle.util.GradleVersion
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.stream.Stream

class BomCacheOwnershipTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest(name = "repository ownership: Gradle {0}, isolated={1}, dynamic={2}")
    @MethodSource("modes")
    fun `same requested BOM coordinates retain each projects repository and interpolation`(
        version: String, isolated: Boolean, dynamic: Boolean
    ) {
        // Reverse which policy is configured first in ordinary builds, and reverse requested
        // tasks. Isolated builds configure the independent owners in parallel.
        listOf(false, true).forEach { reverse ->
            fixture(dynamic)
            val paths = structure(reverse)
            val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
                .let { if (reverse) it.reversed() else it }
            val initialBoms = if (dynamic) listOf("1.0", "2.0") else listOf("1.0", "1.0")
            fun check(left: String, right: String, selected: List<String> = listOf(left, right), boms: List<String> = initialBoms) {
                val args = listOf("-Pleft.version=$left", "-Pright.version=$right")
                val control = run(version, false, tasks, args, cache = false)
                verify(control, paths, selected, boms)
                val cold = run(version, isolated, tasks, args)
                assertStored(cold)
                verify(cold, paths, selected, boms)
                val warm = run(version, isolated, tasks, args)
                assertTrue(warm.output.contains("Reusing configuration cache"), warm.output)
                assertTrue(warm.output.contains("Configuration cache entry reused"), warm.output)
                verify(warm, paths, selected, boms)
            }
            check("1.0", "2.0")
            check("2.0", "1.0")
            val build = File(projectDir, "left/build.gradle")
            build.writeText(build.readText().replace(File(projectDir, "left-repo").toURI().toString(),
                File(projectDir, "right-repo").toURI().toString()))
            val rightBom = initialBoms[1]
            check("2.0", "1.0", listOf("1.0", "1.0"), listOf(rightBom, rightBom))
            // Changing file-repository POM bytes at the same coordinates must invalidate the
            // parsed policy as well as a changed repository URL or interpolation property.
            pom("right-repo", "bom", rightBom, "pom", management("3.0"))
            check("2.0", "1.0", listOf("3.0", "3.0"), listOf(rightBom, rightBom))
        }
    }

    @ParameterizedTest(name = "manual eager adapters: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `disabled eager resolution permits late BOM changes and both manual adapters`(version: String, isolated: Boolean) {
        listOf("util", "plugin").forEach { adapter ->
            fixture(false)
            val paths = structure(false)
            listOf("left", "right").forEach { owner ->
                pom("$owner-repo", "bom", "2.0", "pom", management("\${$owner.version}"))
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
            val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
            listOf(listOf("1.0", "2.0"), listOf("2.0", "1.0")).forEach { selected ->
                val args = listOf("-Pleft.version=${selected[0]}", "-Pright.version=${selected[1]}")
                verify(run(version, false, tasks, args, cache = false), paths, selected, listOf("2.0", "2.0"))
                val cold = run(version, isolated, tasks, args)
                assertStored(cold)
                verify(cold, paths, selected, listOf("2.0", "2.0"))
                val warm = run(version, isolated, tasks, args)
                assertTrue(warm.output.contains("Reusing configuration cache"), warm.output)
                verify(warm, paths, selected, listOf("2.0", "2.0"))
            }
        }
    }

    @ParameterizedTest(name = "system interpolation: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `system interpolation inputs invalidate each owners policy`(version: String, isolated: Boolean) {
        fixture(false)
        val paths = structure(false)
        listOf("left", "right").forEach { owner ->
            pom("$owner-repo", "bom", "1.0", "pom", management("\${$owner.system.version}"))
        }
        val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
        listOf(listOf("1.0", "2.0"), listOf("2.0", "1.0")).forEach { selected ->
            val args = listOf("-Dleft.system.version=${selected[0]}", "-Dright.system.version=${selected[1]}")
            verify(run(version, false, tasks, args, cache = false), paths, selected, listOf("1.0", "1.0"))
            val cold = run(version, isolated, tasks, args)
            assertStored(cold)
            verify(cold, paths, selected, listOf("1.0", "1.0"))
            val warm = run(version, isolated, tasks, args)
            assertTrue(warm.output.contains("Reusing configuration cache"), warm.output)
            verify(warm, paths, selected, listOf("1.0", "1.0"))
        }
    }

    @Test
    fun `configuration copies with different resolved BOMs retain their own cached policy`() {
        val (project, service) = serviceFixture()
        val source = project.configurations.create("policy")
        project.dependencies.add(source.name, "sample:bom:latest.release@pom")
        val first = source.copy().apply { resolutionStrategy.force("sample:bom:1.0") }
        val second = source.copy().apply { resolutionStrategy.force("sample:bom:2.0") }
        val firstReasons = mutableSetOf<String>()
        val firstResult = service.getRecommendationsFromConfiguration(first, project, firstReasons)
        assertEquals(mapOf("sample:foo" to "1.0"), firstResult)
        assertEquals(setOf("bom-1.0.pom"), first.resolve().map { it.name }.toSet())
        val secondReasons = mutableSetOf<String>()
        assertEquals(mapOf("sample:foo" to "2.0"), service.getRecommendationsFromConfiguration(second, project, secondReasons))
        assertEquals(setOf("bom-2.0.pom"), second.resolve().map { it.name }.toSet())
        assertEquals(setOf("nebula.dependency-recommender uses mavenBom: sample:bom:pom:2.0"), secondReasons)

        val cachedReasons = mutableSetOf<String>()
        assertEquals(mapOf("sample:foo" to "1.0"), service.getCachedRecommendationsFromConfiguration(first, cachedReasons))
        assertEquals(setOf("nebula.dependency-recommender uses mavenBom: sample:bom:pom:1.0"), cachedReasons)
    }

    @Test
    fun `callers can modify returned recommendations without changing cached policy`() {
        val (project, service) = serviceFixture()
        val configuration = project.configurations.create("policy")
        project.dependencies.add(configuration.name, "sample:bom:1.0@pom")
        val reasons = mutableSetOf<String>()
        val recommendations = service.getRecommendationsFromConfiguration(configuration, project, reasons)
        recommendations["sample:foo"] = "3.0"
        reasons.add("consumer-only")
        val cachedReasons = mutableSetOf<String>()
        assertEquals(mapOf("sample:foo" to "1.0"), service.getCachedRecommendationsFromConfiguration(configuration, cachedReasons))
        assertEquals(setOf("nebula.dependency-recommender uses mavenBom: sample:bom:pom:1.0"), cachedReasons)
    }

    @Test
    fun `failed eager resolution does not poison another configurations matching declarations`() {
        val (project, service) = serviceFixture()
        val failed = project.configurations.create("failed")
        project.dependencies.add(failed.name, "sample:bom:latest.release@pom")
        failed.resolutionStrategy.force("sample:bom:missing")
        service.eagerlyResolveAndCacheBoms(project, failed.name)
        assertTrue(service.getCachedRecommendationsFromConfiguration(failed, mutableSetOf()).isEmpty())
        val valid = project.configurations.create("valid")
        project.dependencies.add(valid.name, "sample:bom:latest.release@pom")
        valid.resolutionStrategy.force("sample:bom:2.0")
        assertEquals(mapOf("sample:foo" to "2.0"), service.getRecommendationsFromConfiguration(valid, project, mutableSetOf()))
    }

    private fun serviceFixture(): Pair<Project, BomResolverService> {
        listOf("1.0", "2.0").forEach { selected ->
            pom("unit-repo", "bom", selected, "pom", management(selected))
        }
        val project = ProjectBuilder.builder().withProjectDir(projectDir).build()
        project.repositories.maven { it.setUrl(File(projectDir, "unit-repo").toURI()) }
        val service = project.gradle.sharedServices.registerIfAbsent("testBomResolver", BomResolverService::class.java) {}.get()
        return project to service
    }

    private fun fixture(dynamic: Boolean) {
        write("build.gradle", "")
        write("parent/build.gradle", "")
        write("gradle.properties", """
            nebula.dependency-recommender.useBuildService=true
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.jvmargs=-Xmx512m -Dnebula.features.coreBomSupport=false
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """)
        listOf("left", "right").forEachIndexed { index, owner ->
            listOf("1.0", "2.0", "3.0").forEach { selected ->
                pom("$owner-repo", "foo", selected, "jar", "")
                JarOutputStream(File(projectDir, "$owner-repo/sample/foo/$selected/foo-$selected.jar").outputStream()).use {
                    it.putNextEntry(JarEntry("version.txt"))
                    it.write(selected.toByteArray())
                    it.closeEntry()
                }
            }
            val bomVersion = if (dynamic) "${index + 1}.0" else "1.0"
            pom("$owner-repo", "bom", bomVersion, "pom", management("\${$owner.version}"))
            write("$owner-repo/sample/bom/maven-metadata.xml", """
                <metadata><groupId>sample</groupId><artifactId>bom</artifactId><versioning>
                  <latest>$bomVersion</latest><release>$bomVersion</release>
                  <versions><version>$bomVersion</version></versions><lastUpdated>20260101000000</lastUpdated>
                </versioning></metadata>
            """)
            write("$owner/build.gradle", """
                plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
                repositories { maven { url = uri('${File(projectDir, "$owner-repo").toURI()}') } }
                dependencyRecommendations { mavenBom module: 'sample:bom:${if (dynamic) "latest.release" else "1.0"}' }
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

    private fun structure(reverse: Boolean): List<String> {
        val left = if (reverse) ":z" else ":a"
        val right = if (reverse) ":parent:a" else ":parent:z"
        write("settings.gradle", """
            rootProject.name = 'bom-cache-ownership'
            include '$left', '$right'
            project('$left').projectDir = file('left')
            project('$right').projectDir = file('right')
        """)
        return listOf(left, right)
    }

    private fun verify(result: BuildResult, paths: List<String>, selected: List<String>, bomVersions: List<String>) {
        assertAll(paths.mapIndexed { index, path -> org.junit.jupiter.api.function.Executable {
            assertEquals(TaskOutcome.SUCCESS, result.task("$path:resolveRecommendations")?.outcome, result.output)
            val owner = if (index == 0) "left" else "right"
            val resolved = File(projectDir, "$owner/build/resolved")
            assertEquals(setOf("foo-${selected[index]}.jar"), resolved.list()?.toSet(), "$path\n${result.output}")
            JarFile(File(resolved, "foo-${selected[index]}.jar")).use { jar ->
                assertEquals(selected[index], jar.getInputStream(jar.getJarEntry("version.txt")).bufferedReader().readText())
            }
            val insight = result.output.substringAfter("> Task $path:dependencyInsight").substringBefore("\n> Task ")
            assertTrue(insight.contains("Recommending version ${selected[index]} for dependency sample:foo via conflict resolution recommendation"), insight)
            val reasons = Regex("nebula.dependency-recommender uses mavenBom: ([^\\s,]+)")
                .findAll(insight).map { it.groupValues[1] }.toSet()
            assertEquals(setOf("sample:bom:pom:${bomVersions[index]}"), reasons, insight)
        } })
    }

    private fun run(version: String, isolated: Boolean, tasks: List<String>, extra: List<String>, cache: Boolean = true): BuildResult {
        val flag = if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
            if (isolated) "--isolated-projects" else "--no-isolated-projects"
        } else "-Dorg.gradle.unsafe.isolated-projects=$isolated"
        return GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + extra + listOf(flag, if (cache) "--configuration-cache" else "--no-configuration-cache",
                "--configuration-cache-problems=fail", "--console=plain", "--stacktrace")).build()
    }

    private fun assertStored(result: BuildResult) {
        assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
        assertFalse(result.output.contains("Reusing configuration cache"), result.output)
        assertFalse(result.output.contains("Configuration cache problems found"), result.output)
    }

    private fun pom(repo: String, name: String, version: String, packaging: String, body: String) {
        write("$repo/sample/$name/$version/$name-$version.pom", """
            <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId><artifactId>$name</artifactId><version>$version</version><packaging>$packaging</packaging>
              $body
            </project>
        """)
    }

    private fun management(version: String) = """
        <dependencyManagement><dependencies><dependency>
          <groupId>sample</groupId><artifactId>foo</artifactId><version>$version</version>
        </dependency></dependencies></dependencyManagement>
    """

    private fun write(path: String, contents: String) {
        File(projectDir, path).apply { parentFile.mkdirs(); writeText(contents.trimIndent()) }
    }

    companion object {
        @JvmStatic
        fun cacheModes(): Stream<Arguments> = listOf("9.1.0", GradleVersion.current().version).distinct().stream().flatMap { version ->
            Stream.of(Arguments.of(version, false), Arguments.of(version, true))
        }

        @JvmStatic
        fun modes(): Stream<Arguments> = listOf("9.1.0", GradleVersion.current().version).distinct().stream().flatMap { version ->
            Stream.of(false, true).flatMap { isolated ->
                Stream.of(Arguments.of(version, isolated, false), Arguments.of(version, isolated, true))
            }
        }
    }
}
