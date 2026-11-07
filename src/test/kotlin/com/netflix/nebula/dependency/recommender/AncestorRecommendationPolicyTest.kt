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

class AncestorRecommendationPolicyTest {
    @TempDir
    lateinit var projectDir: File

    private val consumers = listOf(":parent:a", ":parent:b", ":sibling")
    private val tasks get() = consumers.flatMap { path ->
        listOf("$path:resolveRecommendations", "$path:fooInsight", "$path:bazInsight")
    }

    @ParameterizedTest(name = "ancestor equivalence: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `local layers match legacy precedence artifacts reasons and input changes`(version: String, isolated: Boolean) {
        fixture()
        fun check(extra: List<String> = emptyList(), parent: String = "2.0", root: String = "1.0", bar: String = "2.0",
                  foo: String? = null, recommended: Boolean = true) {
            val args = listOf("-Pparent.version=$parent", "-Proot.bom=$root") + extra
            val orderedTasks = if (parent == "3.0") tasks.reversed() else tasks
            listOf(false, true).forEach { enabled ->
                val control = run(version, false, enabled, orderedTasks, args, cache = false)
                verify(control, parent, root, bar, foo, recommended)
                val cold = run(version, isolated && enabled, enabled, orderedTasks, args)
                assertStored(cold)
                verify(cold, parent, root, bar, foo, recommended)
                val warm = run(version, isolated && enabled, enabled, orderedTasks, args)
                assertReused(warm)
                verify(warm, parent, root, bar, foo, recommended)
            }
        }
        check()
        check(parent = "3.0")
        check(parent = "3.0", root = "2.0")
        run(version, false, false, listOf(":removeParentPolicy"), cache = false)
        write("parent-policy.properties", "sample:bar=1.0\n")
        check(parent = "3.0", root = "2.0", bar = "1.0")
        check(listOf("-Pforce.foo=1.0"), "3.0", "2.0", "1.0", foo = "1.0", recommended = false)
        check(listOf("-Pexplicit.foo=1.0"), "3.0", "2.0", "1.0", foo = "1.0", recommended = false)
    }

    @ParameterizedTest(name = "ancestor manual timing: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `both manual adapters resolve inherited BOMs after late replacement`(version: String, isolated: Boolean) {
        fixture()
        val manual = """
            dependencyRecommendations.eagerlyResolve = false
            afterEvaluate {
                def scopes = [dependencyRecommendations] + dependencyRecommendations.inheritedPolicies
                scopes.each { scope ->
                    def configuration = configurations.getByName(scope.bomConfigurationName)
                    assert configuration.state.toString() == 'UNRESOLVED'
                    if (!configuration.dependencies.empty) {
                        configuration.dependencies.clear()
                        scope.mavenBom module: 'sample:bom:2.0'
                    }
                }
                if (providers.gradleProperty('manual.adapter').get() == 'util') {
                    netflix.nebula.dependency.recommender.util.BomResolutionUtil.eagerlyResolveBoms(
                        project, dependencyRecommendations, 'nebulaRecommenderBom')
                } else {
                    plugins.getPlugin(netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin)
                        .eagerlyResolveBoms(project, dependencyRecommendations)
                }
            }
        """.trimIndent()
        (listOf("", "parent") + consumers.map(::directory)).forEach { path ->
            File(projectDir, "$path/build.gradle".trimStart('/')).appendText("\n$manual\n")
        }
        listOf("util", "plugin").forEach { adapter ->
            listOf(false, true).forEach { enabled ->
                val args = listOf("-Pmanual.adapter=$adapter")
                verify(run(version, false, enabled, tasks, args, cache = false), root = "2.0")
                val cold = run(version, isolated && enabled, enabled, tasks, args)
                assertStored(cold)
                verify(cold, root = "2.0")
                val warm = run(version, isolated && enabled, enabled, tasks, args)
                assertReused(warm)
                verify(warm, root = "2.0")
            }
        }
    }

    @ParameterizedTest(name = "ancestor source laziness: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `unused inherited sources remain lazy for help and nearer policy hits`(version: String, isolated: Boolean) {
        fixture()
        val build = File(projectDir, "parent/b/build.gradle")
        build.writeText(build.readText().replace("implementation 'sample:bar'", "").replace("implementation 'sample:baz'", ""))
        File(projectDir, "policy.gradle").appendText("\n" + """
            dependencyRecommendations.eagerlyResolve = false
            def scopes = [dependencyRecommendations] + dependencyRecommendations.inheritedPolicies
            scopes.each { scope ->
                def configuration = configurations.getByName(scope.bomConfigurationName)
                if (!configuration.dependencies.empty) {
                    configuration.dependencies.clear()
                    scope.mavenBom module: 'unavailable:bom:missing'
                }
                if (scope.reasons.contains('nebula.dependency-recommender uses a provided map for recommendations')) {
                    def lower = scope.addProvider { group, name ->
                        throw new AssertionError('Unused lower-precedence provider was queried')
                    }
                    scope.addFirst(lower)
                }
            }
        """.trimIndent())
        listOf(false, true).forEach { enabled ->
            val help = listOf(":parent:b:help")
            assertStored(run(version, isolated && enabled, enabled, help))
            assertReused(run(version, isolated && enabled, enabled, help))
            val resolve = listOf(":parent:b:resolveRecommendations", ":parent:b:fooInsight")
            val control = run(version, false, enabled, resolve, cache = false)
            verifyFiles(":parent:b", mapOf("foo" to "2.0"))
            assertFalse(control.output.contains("Unused lower-precedence provider was queried"), control.output)
            val cold = run(version, isolated && enabled, enabled, resolve)
            assertStored(cold)
            verifyFiles(":parent:b", mapOf("foo" to "2.0"))
            val warm = run(version, isolated && enabled, enabled, resolve)
            assertReused(warm)
            verifyFiles(":parent:b", mapOf("foo" to "2.0"))
        }
    }

    @ParameterizedTest(name = "ancestor misses and adoption: Gradle {0}, isolated={1}")
    @MethodSource("modes")
    fun `complete misses preserve strict errors and unadopted inheritance is explicit`(version: String, isolated: Boolean) {
        fixture()
        File(projectDir, "parent/b/build.gradle").appendText("\n" + """
            dependencyRecommendations.strictMode = true
            dependencies { implementation 'sample:absent' }
        """.trimIndent())
        val resolve = listOf(":parent:b:resolveRecommendations")
        listOf(false, true).forEach { enabled ->
            val result = run(version, isolated && enabled, enabled, resolve, fail = true)
            assertTrue(result.output.contains("sample:absent omitted version with no recommended version"), result.output)
            assertFalse(result.output.contains("requires local inheritedPolicy"), result.output)
        }
        val convention = File(projectDir, "policy.gradle")
        convention.writeText(convention.readText().replace("while (ancestor != null) {", "while (ancestor != null && ancestor != ':') {"))
        val partial = run(version, isolated, true, resolve, fail = true)
        assertTrue(partial.output.contains("requires local inheritedPolicy(':') sources"), partial.output)
        assertFalse(partial.output.contains("cannot access 'Project.extensions'"), partial.output)
    }

    private fun fixture() {
        write("settings.gradle", "rootProject.name = 'local-ancestor-policy'\ninclude 'parent:a', 'parent:b', 'sibling'\n")
        write("gradle.properties", """
            nebula.dependency-recommender.useBuildService=true
            systemProp.nebula.features.coreBomSupport=false
            org.gradle.jvmargs=-Xmx512m -Dnebula.features.coreBomSupport=false
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """)
        listOf("1.0", "2.0", "3.0").forEach { version ->
            listOf("foo", "bar", "baz").forEach { artifact ->
                pom(artifact, version, "jar", "")
                JarOutputStream(File(projectDir, "repo/sample/$artifact/$version/$artifact-$version.jar").outputStream()).use { jar ->
                    jar.putNextEntry(JarEntry("version.txt"))
                    jar.write("$artifact:$version".toByteArray())
                    jar.closeEntry()
                }
            }
            pom("bom", version, "pom", "<dependencyManagement><dependencies>" +
                listOf("foo", "bar", "baz").joinToString("") {
                    "<dependency><groupId>sample</groupId><artifactId>$it</artifactId><version>$version</version></dependency>"
                } + "</dependencies></dependencyManagement>")
        }
        write("parent-policy.properties", "sample:bar=2.0\n")
        write("policy.gradle", """
            repositories { maven { url = new File(rootDir, 'repo').toURI() } }
            def configureScope = { scope, owner ->
                if (owner == ':') {
                    scope.mavenBom module: 'sample:bom:' + providers.gradleProperty('root.bom').orElse('1.0').get()
                } else if (owner == ':parent') {
                    scope.propertiesFile file: new File(rootDir, 'parent-policy.properties'), name: 'parentFile'
                    scope.map name: 'early', recommendations: ['sample:foo': '1.0']
                    scope.map name: 'late', recommendations: ['sample:f*': providers.gradleProperty('parent.version').orElse('2.0').get()]
                    def first = scope.map name: 'first', recommendations: ['sample:foo': '3.0']
                    scope.addFirst(first)
                } else if (owner == ':parent:a') {
                    scope.map name: 'consumer', recommendations: ['sample:foo': '3.0']
                }
            }
            configureScope(dependencyRecommendations, path)
            if (providers.gradleProperty('nebula.dependency-recommender.isolatedProjectsSupport').orElse('false').get().toBoolean()) {
                def ancestor = path == ':' ? null : (path.lastIndexOf(':') == 0 ? ':' : path.substring(0, path.lastIndexOf(':')))
                while (ancestor != null) {
                    def owner = ancestor
                    dependencyRecommendations.inheritedPolicy(owner) { scope -> configureScope(scope, owner) }
                    ancestor = ancestor == ':' ? null : (ancestor.lastIndexOf(':') == 0 ? ':' : ancestor.substring(0, ancestor.lastIndexOf(':')))
                }
            }
        """)
        val plugins = "plugins { id 'java-library'; id 'com.netflix.nebula.dependency-recommender' }\n" +
            "apply from: new File(rootDir, 'policy.gradle')\n"
        write("build.gradle", plugins + "tasks.register('removeParentPolicy', Delete) { delete layout.projectDirectory.file('parent-policy.properties') }\n")
        write("parent/build.gradle", plugins)
        consumers.forEach { path ->
            write("${directory(path)}/build.gradle", plugins + """
                dependencies {
                    implementation 'sample:foo' + providers.gradleProperty('explicit.foo').map { ':' + it }.orElse('').get()
                    implementation 'sample:bar'
                    implementation 'sample:baz'
                }
                if (providers.gradleProperty('force.foo').isPresent()) {
                    configurations.runtimeClasspath.resolutionStrategy.force 'sample:foo:' + providers.gradleProperty('force.foo').get()
                }
                tasks.register('cleanResolved', Delete) { delete layout.buildDirectory.dir('resolved') }
                tasks.register('resolveRecommendations', Sync) {
                    dependsOn 'cleanResolved'
                    from configurations.runtimeClasspath
                    into layout.buildDirectory.dir('resolved')
                }
                ['foo', 'baz'].each { artifact ->
                    tasks.register(artifact + 'Insight', org.gradle.api.tasks.diagnostics.DependencyInsightReportTask) {
                        showingAllVariants.set(false)
                        setConfiguration('runtimeClasspath')
                        setDependencySpec('sample:' + artifact)
                    }
                }
            """)
        }
    }

    private fun verify(result: BuildResult, parent: String = "2.0", root: String = "1.0", bar: String = "2.0",
                       foo: String? = null, recommended: Boolean = true) {
        consumers.forEach { path ->
            val isRootChild = path == ":sibling"
            val selected = mapOf("foo" to (foo ?: if (path == ":parent:a") "3.0" else if (isRootChild) root else parent),
                "bar" to if (isRootChild) root else bar, "baz" to root)
            assertEquals(TaskOutcome.SUCCESS, result.task("$path:resolveRecommendations")?.outcome, result.output)
            verifyFiles(path, selected)
            val expectedReasons = when (path) {
                ":parent:a" -> setOf("nebula.dependency-recommender uses a provided map for recommendations")
                ":parent:b" -> setOf("nebula.dependency-recommender uses a provided map for recommendations",
                    "nebula.dependency-recommender uses a properties file: ${File(projectDir, "parent-policy.properties").canonicalFile}")
                else -> setOf("nebula.dependency-recommender uses mavenBom: sample:bom:pom:$root")
            }
            listOf("foo", "baz").forEach { artifact ->
                val insight = result.output.substringAfter("> Task $path:${artifact}Insight").substringBefore("\n> Task ")
                val expected = if (artifact == "foo" && !recommended) emptySet() else expectedReasons
                val reasonText = insight.lineSequence().firstOrNull { it.contains("with reasons:") }?.substringAfter("with reasons: ")
                val actual = reasonText?.split(", ")?.toSet() ?: emptySet()
                assertEquals(expected, actual, "$path $artifact\n$insight")
                assertTrue(insight.contains("sample:$artifact:${selected[artifact]}"), insight)
            }
        }
    }

    private fun verifyFiles(path: String, versions: Map<String, String>) {
        val output = File(projectDir, "${directory(path)}/build/resolved")
        assertEquals(versions.map { (artifact, version) -> "$artifact-$version.jar" }.toSet(), output.list()?.toSet(), path)
        versions.forEach { (artifact, version) ->
            JarFile(File(output, "$artifact-$version.jar")).use { jar ->
                assertEquals("$artifact:$version", jar.getInputStream(jar.getJarEntry("version.txt")).bufferedReader().readText())
            }
        }
    }

    private fun run(version: String, isolated: Boolean, enabled: Boolean, tasks: List<String>, extra: List<String> = emptyList(),
                    cache: Boolean = true, fail: Boolean = false): BuildResult {
        val isolation = if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
            if (isolated) "--isolated-projects" else "--no-isolated-projects"
        } else "-Dorg.gradle.unsafe.isolated-projects=$isolated"
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath().withGradleVersion(version)
            .withArguments(tasks + extra + listOf("-Pnebula.dependency-recommender.isolatedProjectsSupport=$enabled", isolation,
                // The legacy properties provider publishes its mutable map before loading it.
                // Serialize shared-parent controls; adopted consumers own independent instances.
                if (enabled) "--parallel" else "--no-parallel",
                if (cache) "--configuration-cache" else "--no-configuration-cache", "--configuration-cache-problems=fail", "--console=plain", "--stacktrace"))
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

    private fun pom(artifact: String, version: String, packaging: String, extra: String) = write(
        "repo/sample/$artifact/$version/$artifact-$version.pom", """
            <project><modelVersion>4.0.0</modelVersion><groupId>sample</groupId><artifactId>$artifact</artifactId>
            <version>$version</version><packaging>$packaging</packaging>$extra</project>
        """
    )

    private fun directory(path: String) = path.trimStart(':').replace(':', '/')

    private fun write(path: String, text: String) {
        File(projectDir, path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
    }

    companion object {
        @JvmStatic
        fun modes(): Stream<Arguments> = listOf("9.1.0", GradleVersion.current().version).distinct().stream().flatMap {
            Stream.of(Arguments.of(it, false), Arguments.of(it, true))
        }
    }
}
