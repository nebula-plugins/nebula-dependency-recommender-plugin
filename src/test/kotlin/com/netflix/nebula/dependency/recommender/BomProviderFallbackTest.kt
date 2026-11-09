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

/** Exercises the public BOM provider before an eager/manual adapter has populated its cache. */
class BomProviderFallbackTest {
    @TempDir lateinit var projectDir: File

    @ParameterizedTest(name = "Uncached BOM provider: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `uncached provider uses owner local interpolation and preserves artifact reasons`(version: String, isolated: Boolean) {
        fixture()
        compare(version, isolated, "1.0")
        compare(version, isolated, "2.0")
        compare(version, isolated, "1.0", listOf("-Ppolicy.version=2.0", "-Dpolicy.version=1.0"))
        compare(version, isolated, "2.0", listOf("-Ppolicy.version=2.0", "-Dpolicy.version=2.0"))
        compare(version, isolated, "1.0")
        val bom = file("repo/sample/bom/1.0/bom-1.0.pom")
        bom.writeText(bom.readText().replace("${'$'}{policy.version}", "2.0"))
        compare(version, isolated, "2.0", listOf("-Ppolicy.version=1.0"))
    }

    @ParameterizedTest(name = "Uncached owner contexts: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `parallel sibling and nested fallbacks keep repository and interpolation context`(version: String, isolated: Boolean) {
        listOf(false, true).forEach { reverse ->
            fixture()
            val paths = if (reverse) listOf(":z", ":parent:a") else listOf(":a", ":parent:z")
            write("settings.gradle", """
                rootProject.name = 'bom-provider-owners'
                include '${paths[0]}', '${paths[1]}'
                project('${paths[0]}').projectDir = file('left')
                project('${paths[1]}').projectDir = file('right')
            """)
            val script = file("build.gradle").readText()
            write("build.gradle", "")
            write("parent/build.gradle", "")
            listOf("left", "right").forEach { owner ->
                file("repo").copyRecursively(file("$owner-repo"), overwrite = true)
                val bom = file("$owner-repo/sample/bom/1.0/bom-1.0.pom")
                bom.writeText(bom.readText().replace("policy.version", "$owner.version"))
                listOf("1.0", "2.0").forEach { selected -> jar("$owner-repo", selected, "$owner:foo:$selected") }
                write("$owner/build.gradle", script.replace("uri('repo')", "uri('${file("$owner-repo").toURI()}')"))
            }
            val tasks = paths.flatMap { listOf("$it:resolveRecommendations", "$it:dependencyInsight") }
                .let { if (reverse) it.reversed() else it }
            listOf(listOf("1.0", "2.0"), listOf("2.0", "1.0")).forEach { selected ->
                val inputs = listOf("-Pleft.version=${selected[0]}", "-Pright.version=${selected[1]}")
                fun check(result: BuildResult) = paths.forEachIndexed { index, path ->
                    val owner = if (index == 0) "left" else "right"
                    verify(result, selected[index], path, owner, "$owner:foo:${selected[index]}")
                }
                check(run(version, false, inputs, tasks, cache = false))
                val cold = run(version, isolated, inputs, tasks)
                assertStored(cold)
                check(cold)
                val warm = run(version, isolated, inputs, tasks)
                assertReused(warm)
                check(warm)
            }
        }
    }

    @ParameterizedTest(name = "Uncached fallback rollout: Gradle {0}")
    @MethodSource("versions")
    fun `legacy extra properties and opted in tracked properties survive rollback in one daemon`(version: String) {
        fixture()
        val properties = file("gradle.properties")
        properties.writeText(properties.readText().replace("nebula.dependency-recommender.isolatedProjectsSupport=true", ""))
        val script = file("build.gradle")
        script.writeText(script.readText().replace("assert configurations.nebulaRecommenderBom.state", """
            extensions.extraProperties.set('policy.version', '2.0')
            assert configurations.nebulaRecommenderBom.state
        """.trimIndent()))
        script.appendText("\n" + """
            tasks.register('daemonIdentity') {
                doLast { println 'fallbackDaemon=' + ProcessHandle.current().pid() }
            }
        """.trimIndent())
        var daemon: String? = null
        fun check(result: BuildResult, selected: String) {
            verify(result, selected)
            val current = Regex("fallbackDaemon=([0-9]+)").find(result.output)?.groupValues?.get(1)
            assertNotNull(current, result.output)
            if (daemon == null) daemon = current else assertEquals(daemon, current, result.output)
        }
        val tasks = listOf("resolveRecommendations", "dependencyInsight", "daemonIdentity")
        listOf<String?>(null, "false", "true", "false", "true", null).forEachIndexed { index, support ->
            val selected = if (support == "true") "1.0" else "2.0"
            val inputs = listOf("-Ppolicy.version=1.0") +
                (support?.let { listOf("-Pnebula.dependency-recommender.isolatedProjectsSupport=$it") } ?: emptyList())
            check(run(version, false, inputs, tasks, cache = false), selected)
            val cold = run(version, false, inputs, tasks)
            if (index < 3) assertStored(cold)
            check(cold, selected)
            val warm = run(version, false, inputs, tasks)
            assertReused(warm)
            check(warm, selected)
            if (support == "true") {
                val isolated = run(version, true, inputs, tasks)
                if (index == 2) assertStored(isolated)
                check(isolated, selected)
                val reused = run(version, true, inputs, tasks)
                assertReused(reused)
                check(reused, selected)
            }
        }
    }

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'bom-provider-fallback'")
        write("gradle.properties", """
            nebula.dependency-recommender.isolatedProjectsSupport=true
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.jvmargs=-Xmx527m -Dnebula.features.coreBomSupport=false
            org.gradle.workers.max=2
            org.gradle.parallel=true
        """)
        write("repo/sample/bom/1.0/bom-1.0.pom", """
            <project><modelVersion>4.0.0</modelVersion>
                <groupId>sample</groupId><artifactId>bom</artifactId><version>1.0</version><packaging>pom</packaging>
                <dependencyManagement><dependencies><dependency>
                    <groupId>sample</groupId><artifactId>foo</artifactId><version>${'$'}{policy.version}</version>
                </dependency></dependencies></dependencyManagement>
            </project>
        """)
        listOf("1.0", "2.0").forEach { selected ->
            write("repo/sample/foo/$selected/foo-$selected.pom", """
                <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId>
                    <artifactId>foo</artifactId><version>$selected</version>
                </project>
            """)
            jar("repo", selected, "sample:foo:$selected")
        }
        write("build.gradle", """
            plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }
            repositories { maven { url = uri('repo') } }
            dependencyRecommendations {
                eagerlyResolve = false
                mavenBom module: 'sample:bom:1.0'
            }
            assert configurations.nebulaRecommenderBom.state.toString() == 'UNRESOLVED'
            assert dependencyRecommendations.mavenBomProvider.getVersion('sample', 'foo') != null
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

    private fun compare(version: String, isolated: Boolean, selected: String, inputs: List<String> = listOf("-Ppolicy.version=$selected")) {
        verify(run(version, false, inputs, cache = false), selected)
        val cold = run(version, isolated, inputs)
        assertStored(cold)
        verify(cold, selected)
        val warm = run(version, isolated, inputs)
        assertReused(warm)
        verify(warm, selected)
    }

    private fun verify(result: BuildResult, selected: String, path: String = "", directory: String = ".", contents: String = "sample:foo:$selected") {
        assertEquals(TaskOutcome.SUCCESS, result.task("$path:resolveRecommendations")?.outcome, result.output)
        val resolved = file("$directory/build/resolved")
        assertEquals(setOf("foo-$selected.jar"), resolved.list()?.toSet(), result.output)
        JarFile(File(resolved, "foo-$selected.jar")).use {
            assertEquals(contents, it.getInputStream(it.getJarEntry("policy.txt")).bufferedReader().readText())
        }
        val insight = result.output.substringAfter("> Task $path:dependencyInsight").substringBefore("\n> Task ")
        assertTrue(insight.contains("Recommending version $selected for dependency sample:foo via conflict resolution recommendation"), insight)
        val reasons = Regex("nebula.dependency-recommender uses mavenBom: ([^\\s,]+)")
            .findAll(insight).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("sample:bom:pom:1.0"), reasons, insight)
    }

    private fun run(version: String, isolated: Boolean, inputs: List<String>, tasks: List<String> = listOf("resolveRecommendations", "dependencyInsight"), cache: Boolean = true): BuildResult {
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
            if (isolated) "--isolated-projects" else "--no-isolated-projects"
        } else "-Dorg.gradle.unsafe.isolated-projects=$isolated"
        return GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + inputs + listOf(isolation,
                if (cache) "--configuration-cache" else "--no-configuration-cache",
                "--configuration-cache-problems=fail", "--console=plain", "--stacktrace")).build()
    }

    private fun jar(repo: String, selected: String, contents: String) {
        JarOutputStream(file("$repo/sample/foo/$selected/foo-$selected.jar").outputStream()).use {
            it.putNextEntry(JarEntry("policy.txt"))
            it.write(contents.toByteArray())
            it.closeEntry()
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

    private fun file(path: String) = File(projectDir, path)
    private fun write(path: String, contents: String) {
        file(path).apply { parentFile.mkdirs(); writeText(contents.trimIndent()) }
    }

    companion object {
        @JvmStatic fun versions(): Stream<String> = listOf("9.1.0", GradleVersion.current().version).distinct().stream()
        @JvmStatic fun modes(): Stream<Arguments> = versions()
            .flatMap { Stream.of(Arguments.of(it, false), Arguments.of(it, true)) }
    }
}
