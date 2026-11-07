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
import java.util.jar.JarOutputStream
import java.util.jar.JarFile
import java.util.stream.Stream

/**
 * Regression coverage and assessment of the remaining isolation limitations. Known
 * failures are asserted explicitly; they are not proof of compatibility.
 * Every project applies its own plugins. No allprojects/subprojects setup masks the cause.
 */
class DependencyRecommendationsPluginIsolatedProjectsTest {
    @TempDir
    lateinit var projectDir: File

    @ParameterizedTest(name = "single BOM interpolation: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `single project BOM tracks Gradle interpolation input`(version: String, isolated: Boolean) {
        fixture()
        write("build.gradle", localPlugins + repository + """
            dependencyRecommendations { mavenBom module: 'sample:interpolated-bom:1.0' }
            dependencies { implementation 'sample:foo' }
        """ + resolveTask)

        verifyReuseAndInvalidation(version, isolated, listOf(":resolveRecommendations"), "1.0", "2.0") {
            listOf("-Psample.version=$it")
        }
    }

    @ParameterizedTest(name = "single properties file: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `single project properties file content invalidates recommendations`(version: String, isolated: Boolean) {
        fixture()
        write("recommendations.properties", "sample:foo=1.0\n")
        write("build.gradle", localPlugins + repository + """
            dependencyRecommendations { propertiesFile file: file('recommendations.properties') }
            dependencies { implementation 'sample:foo' }
        """ + resolveTask)

        verifyReuseAndInvalidation(version, isolated, listOf(":resolveRecommendations"), "1.0", "2.0") {
            write("recommendations.properties", "sample:foo=$it\n")
            emptyList()
        }
    }

    @ParameterizedTest(name = "single core BOM: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `single project core BOM tracks selected BOM`(version: String, isolated: Boolean) {
        fixture(core = true)
        write("build.gradle", localPlugins + repository + """
            dependencyRecommendations {
                mavenBom module: "sample:bom:" + providers.gradleProperty('bomVersion').get()
            }
            dependencies { implementation 'sample:foo' }
        """ + resolveTask)

        verifyReuseAndInvalidation(version, isolated, listOf(":resolveRecommendations"), "1.0", "2.0") {
            listOf("-PbomVersion=$it")
        }
    }

    @ParameterizedTest(name = "independent projects: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `independent projects with local recommendations store reuse and invalidate`(version: String, isolated: Boolean) {
        fixture("include 'a', 'b'")
        write("build.gradle", "")
        listOf("a", "b").forEach { name ->
            write("$name/build.gradle", localPlugins + repository + """
                dependencyRecommendations {
                    map name: 'local', recommendations: ['sample:foo': providers.gradleProperty('sample.version').get()]
                }
                dependencies { implementation 'sample:foo' }
            """ + resolveTask)
        }

        verifyReuseAndInvalidation(version, isolated, listOf(":a:resolveRecommendations", ":b:resolveRecommendations"), "1.0", "2.0") {
            listOf("-Psample.version=$it")
        }
    }

    @ParameterizedTest(name = "nested inheritance characterization: Gradle {0}")
    @MethodSource("versions")
    fun `characterization - nearest parent recommendations violate isolation`(version: String) {
        fixture("include 'parent:leaf'")
        write("build.gradle", localPlugins + """
            dependencyRecommendations { map name: 'root', recommendations: ['sample:foo': '1.0'] }
        """)
        write("parent/build.gradle", localPlugins + """
            dependencyRecommendations { map name: 'parent', recommendations: ['sample:foo': '2.0'] }
        """)
        write("parent/leaf/build.gradle", localPlugins + repository + """
            dependencies { implementation 'sample:foo' }
        """ + resolveTask)
        val tasks = listOf(":parent:leaf:resolveRecommendations")

        assertStored(run(version, false, tasks))
        assertResolved("parent/leaf", "2.0")
        assertReused(run(version, false, tasks))
        write("parent/build.gradle", File(projectDir, "parent/build.gradle").readText().replace("'2.0'", "'1.0'"))
        assertStored(run(version, false, tasks))
        assertResolved("parent/leaf", "1.0")
        assertReused(run(version, false, tasks))

        assertIsolationFailure(version, tasks, "extensions", ":parent:leaf", ":parent")
    }

    @ParameterizedTest(name = "ConflictResolved project dependencies: Gradle {0}, isolated={1}, nested={2}")
    @MethodSource("projectDependencyModes")
    fun `ConflictResolved project dependencies store reuse and invalidate`(version: String, isolated: Boolean, nested: Boolean) {
        val prefix = if (nested) "parent:" else ""
        val lib = "${prefix}lib"
        val app = "${prefix}app"
        val libDir = lib.replace(':', '/')
        val appDir = app.replace(':', '/')
        fixture("include '$lib', '$app'")
        write("build.gradle", "")
        if (nested) write("parent/build.gradle", "")
        // Complete local policy, including the project coordinate. No ancestor lookup is needed.
        val policy = """
            dependencyRecommendations {
                map name: 'local', recommendations: [
                    'sample:lib': '1.0', 'sample:foo': providers.gradleProperty('sample.version').get()
                ]
            }
        """
        write("$libDir/build.gradle", localPlugins + repository + policy + """
            group = 'sample'
            version = '1.0'
            dependencies { api 'sample:foo' }
        """)
        write("$libDir/src/main/resources/library.txt", "producer artifact")
        write("$appDir/build.gradle", localPlugins + repository + policy + """
            dependencies { implementation project(':$lib') }
        """ + resolveFreshTask)
        val tasks = listOf(":$app:resolveRecommendations", ":$app:dependencyInsight")

        fun check(selected: String, policyVersion: String, recommended: Boolean = true, forced: Boolean = false) {
            val args = listOf("--dependency", "sample:foo", "--configuration", "runtimeClasspath", "-Psample.version=$policyVersion")
            fun verify(result: BuildResult) {
                assertEquals(TaskOutcome.SUCCESS, result.task(":$app:resolveRecommendations")?.outcome, result.output)
                assertResolved(appDir, selected, setOf("lib-1.0.jar"))
                JarFile(File(projectDir, "$appDir/build/resolved/lib-1.0.jar")).use { jar ->
                    assertEquals("producer artifact", jar.getInputStream(jar.getJarEntry("library.txt")).bufferedReader().readText())
                }
                assertTrue(result.output.contains("sample:foo:$selected"), result.output)
                val reason = "Recommending version $selected for dependency sample:foo via conflict resolution recommendation"
                if (recommended) {
                    assertTrue(result.output.contains(reason), result.output)
                    assertTrue(result.output.contains("with reasons: nebula.dependency-recommender uses a provided map for recommendations"), result.output)
                } else {
                    assertFalse(result.output.contains("Recommending version"), result.output)
                }
                if (forced) assertTrue(result.output.contains("sample:foo:$selected (forced)"), result.output)
            }
            // Isolated Projects requires the cache. Use an ordinary no-cache build as the control.
            verify(run(version, false, tasks, args, cache = false))
            val cold = run(version, isolated, tasks, args)
            assertStored(cold)
            assertFalse(cold.output.contains("Reusing configuration cache"), cold.output)
            verify(cold)
            // The fixture deletes outputs through Gradle on every run, including warm reuse.
            val warm = run(version, isolated, tasks, args)
            assertReused(warm)
            verify(warm)
        }

        check("1.0", "1.0")
        check("2.0", "2.0")
        // A producer declaration change must invalidate the consumer's cached graph. Explicit
        // versions remain authoritative for ConflictResolved even when local policy differs.
        val producer = File(projectDir, "$libDir/build.gradle")
        producer.writeText(producer.readText().replace("api 'sample:foo'", "api 'sample:foo:1.0'"))
        check("1.0", "2.0", recommended = false)
        File(projectDir, "$appDir/build.gradle").appendText("\nconfigurations.runtimeClasspath.resolutionStrategy.force 'sample:foo:2.0'\n")
        check("2.0", "2.0", recommended = false, forced = true)
    }

    @ParameterizedTest(name = "late strategy selection: Gradle {0}, strategy={1}")
    @MethodSource("inspectionStrategies")
    fun `late strategy selection preserves project inspection explicit versions and forces`(version: String, strategy: String) {
        fixture("include 'parent:lib', 'app'")
        // OverrideTransitives still consults ancestors on a miss (including project selectors).
        // Keep that existing ordinary-build behavior separate from the isolated default strategy.
        write("build.gradle", localPlugins)
        write("parent/build.gradle", "")
        listOf("bar", "direct", "forced", "bridge").forEach { name ->
            listOf("1.0", "2.0").forEach { selected ->
                pom(name, selected, "jar", if (name == "bridge") """
                    <dependencies><dependency><groupId>sample</groupId><artifactId>bar</artifactId>
                      <version>1.0</version></dependency></dependencies>
                """ else "")
                JarOutputStream(File(projectDir, "repo/sample/$name/$selected/$name-$selected.jar").outputStream()).use {
                    it.putNextEntry(JarEntry("version.txt"))
                    it.write(selected.toByteArray())
                    it.closeEntry()
                }
            }
        }
        val policy = """
            dependencyRecommendations {
                map name: 'local', recommendations: [
                    'sample:bridge': '1.0', 'sample:foo': '2.0',
                    'sample:bar': '2.0', 'sample:direct': '2.0', 'sample:forced': '2.0'
                ]
            }
        """
        write("parent/lib/build.gradle", localPlugins + repository + policy + """
            group = 'sample'
            version = '1.0'
            dependencies {
                api 'sample:foo:1.0'
                api 'sample:bridge:1.0'
                api 'sample:forced'
            }
        """)
        write("app/build.gradle", localPlugins + repository + policy + """
            // Strategy selection must remain lazy, including after plugin application/evaluation.
            afterEvaluate { dependencyRecommendations.strategy = dependencyRecommendations.$strategy }
            configurations.runtimeClasspath.resolutionStrategy.force 'sample:forced:1.0'
            dependencies {
                implementation project(':parent:lib')
                implementation 'sample:direct:1.0'
            }
        """ + resolveFreshTask)
        val tasks = listOf(":app:resolveRecommendations", ":app:dependencyInsight")
        val args = listOf("--dependency", "sample", "--configuration", "runtimeClasspath")
        fun verify(result: BuildResult) {
            assertEquals(TaskOutcome.SUCCESS, result.task(":app:resolveRecommendations")?.outcome, result.output)
            val bar = if (strategy == "OverrideTransitives") "2.0" else "1.0"
            val expected = mapOf("foo" to "1.0", "bridge" to "1.0", "bar" to bar, "direct" to "1.0", "forced" to "1.0")
            val resolved = File(projectDir, "app/build/resolved")
            assertEquals(expected.map { (name, selected) -> "$name-$selected.jar" }.toSet() + "lib-1.0.jar", resolved.list()?.toSet())
            expected.forEach { (name, selected) ->
                JarFile(File(resolved, "$name-$selected.jar")).use { jar ->
                    assertEquals(selected, jar.getInputStream(jar.getJarEntry("version.txt")).bufferedReader().readText())
                }
                assertTrue(result.output.contains("sample:$name:$selected"), result.output)
            }
            // Removing producer traversal would incorrectly override foo's explicit 1.0 with 2.0.
            assertFalse(result.output.contains("Recommending version 2.0 for dependency sample:foo"), result.output)
            assertFalse(result.output.contains("Recommending version 2.0 for dependency sample:direct"), result.output)
            assertTrue(result.output.contains("sample:forced:1.0 (forced)"), result.output)
            if (strategy == "OverrideTransitives") {
                assertTrue(result.output.contains("Recommending version 2.0 for dependency sample:bar via override transitive recommendation"), result.output)
                assertTrue(result.output.contains("with reasons: nebula.dependency-recommender uses a provided map for recommendations"), result.output)
            } else {
                assertFalse(result.output.contains("Recommending version 2.0 for dependency sample:bar"), result.output)
            }
        }
        verify(run(version, false, tasks, args, cache = false))
        val cold = run(version, false, tasks, args)
        assertStored(cold)
        verify(cold)
        val warm = run(version, false, tasks, args)
        assertReused(warm)
        verify(warm)
    }

    @ParameterizedTest(name = "root-only core BOM characterization: Gradle {0}")
    @MethodSource("versions")
    fun `characterization - root only core BOM mutates child configurations`(version: String) {
        fixture("include 'child'", core = true)
        write("build.gradle", localPlugins + repository + """
            dependencyRecommendations { mavenBom module: 'sample:bom:1.0' }
        """)
        write("child/build.gradle", "plugins { id 'java-library' }\n" + repository + """
            dependencies { implementation 'sample:foo' }
        """ + resolveTask)
        val tasks = listOf(":child:resolveRecommendations")

        assertStored(run(version, false, tasks))
        assertResolved("child", "1.0")
        assertReused(run(version, false, tasks))
        assertIsolationFailure(version, tasks, "configurations", ":child")
    }

    @ParameterizedTest(name = "dynamic parent property characterization: Gradle {0}")
    @MethodSource("versions")
    fun `characterization - implicit parent property lookup depends on Gradle version`(version: String) {
        fixture("include 'child'")
        val properties = File(projectDir, "gradle.properties")
        properties.writeText(properties.readText().replace("nebula.dependency-recommender.useBuildService=true", ""))
        write("build.gradle", "ext.set('nebula.dependency-recommender.useBuildService', 'true')")
        write("child/build.gradle", localPlugins + repository + """
            dependencyRecommendations { map name: 'local', recommendations: ['sample:foo': '1.0'] }
            dependencies { implementation 'sample:foo' }
        """ + resolveTask)
        val tasks = listOf(":child:resolveRecommendations")

        assertStored(run(version, false, tasks))
        assertResolved("child", "1.0")
        assertReused(run(version, false, tasks))
        if (GradleVersion.version(version) < GradleVersion.version("9.6")) {
            assertIsolationFailure(version, tasks, "dynamically look up a property", ":child")
        } else {
            // Gradle 9.6+ disables implicit ancestor lookup under isolation. This only proves
            // resolution on Gradle 9+, where build-service use is already the default.
            assertStored(run(version, true, tasks))
            assertResolved("child", "1.0")
            assertReused(run(version, true, tasks))
        }
    }

    @ParameterizedTest(name = "publication coordinates: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `publication coordinate callback stores reuses and invalidates`(version: String, isolated: Boolean) {
        fixture()
        write("build.gradle", """
            plugins {
                id 'maven-publish'
                id 'com.netflix.nebula.dependency-recommender'
            }
            group = 'sample'
            version = '1.0'
            publishing {
                publications {
                    recommendations(MavenPublication) {
                        nebulaDependencyManagement.withDependencies {
                            'sample:foo:' + providers.gradleProperty('publishedVersion').get()
                        }
                    }
                }
            }
        """)
        val tasks = listOf(":generatePomFileForRecommendationsPublication")
        listOf("1.0", "2.0").forEach { selected ->
            val args = listOf("-PpublishedVersion=$selected")
            assertStored(run(version, isolated, tasks, args))
            assertPublished(selected)
            assertReused(run(version, isolated, tasks, args))
            assertPublished(selected)
        }
    }

    @ParameterizedTest(name = "publication configuration callback: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `publication configuration callback stores reuses regenerates and invalidates`(version: String, isolated: Boolean) {
        fixture()
        write("build.gradle", """
            plugins {
                id 'maven-publish'
                id 'com.netflix.nebula.dependency-recommender'
            }
            group = 'sample'
            version = '1.0'
        """.trimIndent() + "\n" + repository + """
            configurations { recommendations }
            dependencies {
                recommendations 'sample:foo:' + providers.gradleProperty('publishedVersion').get()
            }
            publishing {
                publications {
                    recommendations(MavenPublication) {
                        nebulaDependencyManagement.fromConfigurations { configurations.recommendations }
                    }
                }
            }
        """)
        val tasks = listOf(":generatePomFileForRecommendationsPublication")
        listOf("1.0", "2.0").forEach { selected ->
            val args = listOf("-PpublishedVersion=$selected")
            run(version, false, tasks, args, cache = false)
            assertPublished(selected)
            val pom = File(projectDir, "build/publications/recommendations/pom-default.xml")
            val baseline = pom.readText()
            assertTrue(pom.delete())
            assertStored(run(version, isolated, tasks, args))
            assertPublished(selected)
            assertEquals(baseline, pom.readText())
            assertTrue(pom.delete())
            assertReused(run(version, isolated, tasks, args))
            assertPublished(selected)
            assertEquals(baseline, pom.readText())
        }
        pom("replacement", "1.0", "jar", "")
        val script = File(projectDir, "build.gradle")
        script.writeText(script.readText().replace("'sample:foo:'", "'sample:replacement:'"))
        val changedArgs = listOf("-PpublishedVersion=1.0")
        run(version, false, tasks, changedArgs, cache = false)
        val changedPom = File(projectDir, "build/publications/recommendations/pom-default.xml")
        val changedBaseline = changedPom.readText()
        assertStored(run(version, isolated, tasks, changedArgs))
        assertPublished("1.0", "replacement")
        assertTrue(changedPom.delete())
        assertReused(run(version, isolated, tasks, changedArgs))
        assertPublished("1.0", "replacement")
        assertEquals(changedBaseline, changedPom.readText())
    }

    private fun assertPublished(version: String, artifact: String = "foo") {
        val pom = File(projectDir, "build/publications/recommendations/pom-default.xml")
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
        val dependencies = document.getElementsByTagName("dependency")
        assertEquals(1, dependencies.length)
        val dependency = dependencies.item(0) as org.w3c.dom.Element
        assertEquals("sample", dependency.getElementsByTagName("groupId").item(0).textContent)
        assertEquals(artifact, dependency.getElementsByTagName("artifactId").item(0).textContent)
        assertEquals(version, dependency.getElementsByTagName("version").item(0).textContent)
    }

    @ParameterizedTest(name = "publication contributions: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `local publications preserve contributions transitives and XML ordering`(version: String, isolated: Boolean) {
        fixture("include 'a', 'b', 'unselected', 'parent:leaf'")
        listOf("1.0", "2.0").forEach {
            pom("foo", it, "jar", """
                <dependencies><dependency><groupId>sample</groupId><artifactId>bar</artifactId>
                <version>1.0</version></dependency></dependencies>
            """)
        }
        // A cycle also verifies that graph traversal terminates and keeps selected versions.
        pom("bar", "1.0", "jar", """
            <dependencies><dependency><groupId>sample</groupId><artifactId>foo</artifactId>
            <version>1.0</version></dependency></dependencies>
        """)
        val publishing = """
            plugins {
                id 'maven-publish'
                id 'com.netflix.nebula.dependency-recommender'
            }
            group = 'sample'
            version = '1.0'
        """.trimIndent() + "\n" + repository + """
            configurations { create('first'); create('second'); create('unused') }
            dependencies {
                first 'sample:foo:1.0'
                first 'sample:foo:' + providers.gradleProperty('publishedVersion').get()
                second 'sample:bar:1.0'
                unused 'missing:must-not-resolve:1'
            }
            // Fail if either DSL registration or an unrelated publication triggers resolution.
            configurations.unused.incoming.beforeResolve { throw new GradleException('UNSELECTED RESOLUTION') }
            publishing {
                publications {
                    recommendations(MavenPublication) {
                        pom.withXml { xml ->
                            xml.asNode().appendNode('description', 'before')
                            def dep = xml.asNode().appendNode('dependencyManagement').appendNode('dependencies').appendNode('dependency')
                            dep.appendNode('groupId', 'user'); dep.appendNode('artifactId', 'before'); dep.appendNode('version', '0')
                        }
                        nebulaDependencyManagement.fromConfigurations { configurations.first }
                        pom.withXml { xml ->
                            assert xml.asNode().dependencyManagement.dependencies.dependency.size() == 3
                            xml.asNode().description[0].value = 'between'
                        }
                        nebulaDependencyManagement.fromConfigurations { [configurations.second, configurations.first] }
                        nebulaDependencyManagement.withDependencies { 'manual:single:1' }
                        nebulaDependencyManagement.withDependencies { new CoordinateNotations() }
                        pom.withXml { xml ->
                            assert xml.asNode().description.text() == 'between'
                            assert xml.asNode().dependencyManagement.size() == 1
                            assert xml.asNode().dependencyManagement.dependencies.dependency.size() == 12
                            xml.asNode().description[0].value = 'after'
                        }
                    }
                    unselected(MavenPublication) {
                        nebulaDependencyManagement.fromConfigurations { configurations.unused }
                    }
                }
            }
            class CoordinateNotations implements Iterable<String> {
                Iterator<String> iterator() {
                    ['manual:list:2', 'manual:classified:3:tests', 'manual:versionless', ':empty-group:4'].iterator()
                }
            }
        """
        listOf("", "a", "b", "parent/leaf").forEach { path ->
            write("$path/build.gradle".trimStart('/'), publishing)
        }
        write("unselected/build.gradle", publishing.replace("configurations.first }", "configurations.unused }"))
        val tasks = listOf(":generatePomFileForRecommendationsPublication",
            ":a:generatePomFileForRecommendationsPublication", ":b:generatePomFileForRecommendationsPublication",
            ":parent:leaf:generatePomFileForRecommendationsPublication")
        val poms = listOf("", "a/", "b/", "parent/leaf/").map {
            File(projectDir, "${it}build/publications/recommendations/pom-default.xml")
        }
        listOf("1.0", "2.0").forEach { selected ->
            val args = listOf("-PpublishedVersion=$selected")
            run(version, false, tasks, args, cache = false)
            val baseline = poms.map { it.readText() }
            repeat(2) { attempt ->
                poms.forEach { assertTrue(it.delete()) }
                val result = run(version, isolated, tasks, args)
                if (attempt == 0) assertStored(result) else assertReused(result)
                poms.forEachIndexed { index, pom ->
                    assertEquals(baseline[index], pom.readText())
                    val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom)
                    assertEquals("after", document.getElementsByTagName("description").item(0).textContent)
                    val dependencies = document.getElementsByTagName("dependency")
                    val coordinates = (0 until dependencies.length).map {
                        val dep = dependencies.item(it) as org.w3c.dom.Element
                        listOf("groupId", "artifactId", "version").joinToString(":") { name ->
                            dep.getElementsByTagName(name).item(0).textContent
                        }
                    }
                    assertEquals(listOf("user:before:0", "sample:foo:$selected", "sample:bar:1.0", "sample:bar:1.0", "sample:foo:1.0",
                        "sample:foo:$selected", "sample:bar:1.0", "manual:single:1", "manual:list:2",
                        "manual:classified:3", "manual:versionless:null", "null:empty-group:4"), coordinates)
                }
            }
        }
    }

    @ParameterizedTest(name = "unselected publications: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `unselected publication tasks do not resolve configurations`(version: String, isolated: Boolean) {
        fixture()
        write("build.gradle", """
            plugins { id 'maven-publish'; id 'com.netflix.nebula.dependency-recommender' }
            configurations { recommendations }
            configurations.recommendations.incoming.beforeResolve { throw new GradleException('EARLY RESOLUTION') }
            dependencies { recommendations 'missing:must-not-resolve:1' }
            publishing {
                publications {
                    recommendations(MavenPublication) {
                        nebulaDependencyManagement.fromConfigurations { configurations.recommendations }
                    }
                }
            }
        """)
        assertStored(run(version, isolated, listOf(":help")))
        assertReused(run(version, isolated, listOf(":help")))
    }

    @ParameterizedTest(name = "legacy publishing semantics: Gradle {0}")
    @MethodSource("versions")
    fun `publishing matches protected legacy helpers for platforms constraints and notations`(version: String) {
        fixture()
        write("build.gradle", """
            plugins { id 'maven-publish'; id 'com.netflix.nebula.dependency-recommender' }
            group = 'sample'
            version = '1.0'
        """.trimIndent() + "\n" + repository + """
            class LegacyBomXml extends netflix.nebula.dependency.recommender.publisher.MavenBomXmlGenerator {
                static void configure(org.gradle.api.publish.maven.MavenPublication publication,
                                      org.gradle.api.artifacts.Configuration configuration) {
                    generateDependencyManagementXml(publication, { getManagedDependencies(configuration) })
                    generateDependencyManagementXml(publication, {
                        ['manual:single:1', 'manual:classified:2:tests', 'manual:versionless', ':empty-group:3'].collect {
                            netflix.nebula.dependency.recommender.ModuleNotationParser.parse(it)
                        }
                    })
                }
            }
            configurations { recommendations }
            dependencies {
                recommendations platform('sample:bom:2.0')
                recommendations 'sample:foo:1.0'
                recommendations files('repo/sample/foo/1.0/foo-1.0.jar')
                constraints {
                    recommendations 'sample:foo:2.0'
                    recommendations 'sample:unused:1.0'
                }
            }
            publishing {
                publications {
                    modern(MavenPublication) {
                        nebulaDependencyManagement.fromConfigurations { configurations.recommendations }
                        nebulaDependencyManagement.withDependencies {
                            ['manual:single:1', 'manual:classified:2:tests', 'manual:versionless', ':empty-group:3']
                        }
                    }
                    legacy(MavenPublication) { LegacyBomXml.configure(delegate, configurations.recommendations) }
                }
            }
        """)
        run(version, false, listOf(":generatePomFileForModernPublication", ":generatePomFileForLegacyPublication"), cache = false)
        fun dependencyXml(publication: String): String = File(projectDir,
            "build/publications/$publication/pom-default.xml").readText()
            .substringAfter("<dependencyManagement>").substringBefore("</dependencyManagement>")
        assertEquals(dependencyXml("legacy"), dependencyXml("modern"))
    }

    @ParameterizedTest(name = "publication failures: Gradle {0}, isolated={1}")
    @MethodSource("cacheModes")
    fun `publishing still reports unresolved dependencies and invalid notations`(version: String, isolated: Boolean) {
        fixture()
        write("build.gradle", """
            plugins { id 'maven-publish'; id 'com.netflix.nebula.dependency-recommender' }
            configurations { recommendations }
            dependencies { recommendations 'missing:dependency:1' }
            publishing {
                publications {
                    unresolved(MavenPublication) {
                        nebulaDependencyManagement.fromConfigurations { configurations.recommendations }
                    }
                    invalid(MavenPublication) {
                        nebulaDependencyManagement.withDependencies { 'invalid' }
                    }
                }
            }
        """)
        val unresolved = run(version, isolated, listOf(":generatePomFileForUnresolvedPublication"), fail = true)
        assertTrue(unresolved.output.contains("missing:dependency:1"), unresolved.output)
        val invalid = run(version, isolated, listOf(":generatePomFileForInvalidPublication"), fail = true)
        assertTrue(invalid.output.contains("Supplied String module notation 'invalid' is invalid"), invalid.output)
        assertEquals(TaskOutcome.FAILED, invalid.task(":generatePomFileForInvalidPublication")?.outcome)
    }

    private fun fixture(includes: String = "", core: Boolean = false) {
        write("settings.gradle", "rootProject.name = 'isolation-assessment'\n$includes\n")
        // An explicit Gradle property keeps positive cases independent of dynamic ancestor lookup.
        write("gradle.properties", """
            nebula.dependency-recommender.useBuildService=true
            systemProp.nebula.features.coreBomSupport=$core
            org.gradle.jvmargs=-Xmx${if (core) 513 else 512}m -Dnebula.features.coreBomSupport=$core
            org.gradle.parallel=true
            org.gradle.workers.max=2
        """.trimIndent())
        listOf("1.0", "2.0").forEach { version ->
            pom("foo", version, "jar", "")
            val jar = File(projectDir, "repo/sample/foo/$version/foo-$version.jar")
            JarOutputStream(jar.outputStream()).use {
                it.putNextEntry(JarEntry("version.txt"))
                it.write(version.toByteArray())
                it.closeEntry()
            }
            pom("bom", version, "pom", management(version))
        }
        pom("interpolated-bom", "1.0", "pom", management("\${sample.version}"))
    }

    private fun pom(name: String, version: String, packaging: String, body: String) {
        write("repo/sample/$name/$version/$name-$version.pom", """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>sample</groupId>
              <artifactId>$name</artifactId><version>$version</version><packaging>$packaging</packaging>
              $body
            </project>
        """.trimIndent())
    }

    private fun management(version: String) = """
        <dependencyManagement><dependencies><dependency>
          <groupId>sample</groupId><artifactId>foo</artifactId><version>$version</version>
        </dependency></dependencies></dependencyManagement>
    """.trimIndent()

    private fun verifyReuseAndInvalidation(
        version: String, isolated: Boolean, tasks: List<String>, before: String, after: String,
        input: (String) -> List<String>
    ) {
        val initialArgs = input(before)
        assertStored(run(version, isolated, tasks, initialArgs))
        tasks.forEach { assertResolved(it.substringBeforeLast(':').replace(':', '/').trim('/'), before) }
        assertReused(run(version, isolated, tasks, initialArgs))
        tasks.forEach { assertResolved(it.substringBeforeLast(':').replace(':', '/').trim('/'), before) }
        val changedArgs = input(after)
        val changed = run(version, isolated, tasks, changedArgs)
        assertStored(changed)
        assertFalse(changed.output.contains("Reusing configuration cache"), changed.output)
        tasks.forEach { assertResolved(it.substringBeforeLast(':').replace(':', '/').trim('/'), after) }
        assertReused(run(version, isolated, tasks, changedArgs))
        tasks.forEach { assertResolved(it.substringBeforeLast(':').replace(':', '/').trim('/'), after) }
    }

    private fun assertResolved(project: String, version: String, additional: Set<String> = emptySet()) {
        val resolved = File(projectDir, "$project/build/resolved".trimStart('/'))
        assertEquals(setOf("foo-$version.jar") + additional, resolved.list()?.toSet(), resolved.toString())
        JarFile(File(resolved, "foo-$version.jar")).use { jar ->
            assertEquals(version, jar.getInputStream(jar.getJarEntry("version.txt")).bufferedReader().readText())
        }
    }

    private fun assertStored(result: BuildResult) {
        assertTrue(result.output.contains("Configuration cache entry stored"), result.output)
        assertFalse(result.output.contains("Configuration cache problems found"), result.output)
    }

    private fun assertReused(result: BuildResult) {
        assertTrue(result.output.contains("Reusing configuration cache"), result.output)
        assertTrue(result.output.contains("Configuration cache entry reused"), result.output)
    }

    private fun assertIsolationFailure(version: String, tasks: List<String>, vararg evidence: String) {
        repeat(2) {
            val result = run(version, true, tasks, fail = true)
            if (it == 0) {
                println("Gradle $version isolation blocker for $tasks:\n" + result.output.lineSequence()
                    .filter { line -> line.contains("cannot access") || line.contains("cannot dynamically look up") ||
                        line.contains("DependencyRecommendationsPlugin.java:") }
                    .distinct().joinToString("\n"))
            }
            assertTrue(result.output.contains("cannot access") || result.output.contains("cannot dynamically look up"), result.output)
            evidence.forEach { assertTrue(result.output.contains(it), result.output) }
            assertTrue(result.output.contains("Configuration cache entry discarded"), result.output)
            assertFalse(result.output.contains("Reusing configuration cache"), result.output)
        }
    }

    private fun run(
        version: String, isolated: Boolean, tasks: List<String>, extra: List<String> = emptyList(),
        fail: Boolean = false, cache: Boolean = true
    ): BuildResult {
        // The feature switch is a static final field in the plugin. A matching daemon JVM
        // argument AND distinct heap size above prevent classloader reuse from leaking core mode
        // between fixtures (Gradle can update arbitrary system properties in a reused daemon).
        val diagnostics = if (fail && GradleVersion.version(version) >= GradleVersion.version("9.7"))
            listOf("-Dorg.gradle.isolated-projects.diagnostics=true") else emptyList()
        val args = tasks + extra + diagnostics + listOf(
            if (cache) "--configuration-cache" else "--no-configuration-cache",
            "--configuration-cache-problems=fail", "--stacktrace", "--console=plain",
            isolationFlag(version, isolated)
        )
        val runner = GradleRunner.create().withProjectDir(projectDir).withPluginClasspath()
            .withGradleVersion(version).withArguments(args)
        val result = if (fail) runner.buildAndFail() else runner.build()
        if (!fail) tasks.forEach {
            assertTrue(result.task(it)?.outcome in setOf(TaskOutcome.SUCCESS, TaskOutcome.UP_TO_DATE), result.output)
        }
        return result
    }

    private fun write(path: String, contents: String) {
        File(projectDir, path).apply { parentFile.mkdirs(); writeText(contents.trimIndent()) }
    }

    private val localPlugins = """
        plugins {
            id 'java-library'
            id 'com.netflix.nebula.dependency-recommender'
        }
    """.trimIndent() + "\n"

    private val repository get() = "repositories { maven { url = uri('${File(projectDir, "repo").toURI()}') } }\n"

    // A real consumer of resolved files: missing recommendations fail the build, unlike dependencies reports.
    private val resolveTask = """
        tasks.register('resolveRecommendations', Sync) {
            from configurations.runtimeClasspath
            into layout.buildDirectory.dir('resolved')
        }
    """.trimIndent() + "\n"

    // Keep output regeneration inside Gradle's tracked filesystem operations. Deleting the
    // directory externally between fast TestKit runs can race filesystem watcher delivery.
    private val resolveFreshTask get() = resolveTask + """
        tasks.register('cleanResolved', Delete) {
            delete layout.buildDirectory.dir('resolved')
        }
        tasks.named('resolveRecommendations') { dependsOn 'cleanResolved' }
    """.trimIndent() + "\n"

    companion object {
        @JvmStatic
        fun versions(): Stream<String> = listOf("9.1.0", GradleVersion.current().version).distinct().stream()

        @JvmStatic
        fun cacheModes(): Stream<Arguments> = versions().flatMap { version ->
            Stream.of(Arguments.of(version, false), Arguments.of(version, true))
        }

        @JvmStatic
        fun projectDependencyModes(): Stream<Arguments> = versions().flatMap { version ->
            Stream.of(false, true).flatMap { isolated ->
                Stream.of(Arguments.of(version, isolated, false), Arguments.of(version, isolated, true))
            }
        }

        @JvmStatic
        fun inspectionStrategies(): Stream<Arguments> = versions().flatMap { version ->
            Stream.of(Arguments.of(version, "ConflictResolved"), Arguments.of(version, "OverrideTransitives"))
        }

        private fun isolationFlag(version: String, isolated: Boolean): String =
            if (GradleVersion.version(version) >= GradleVersion.version("9.7")) {
                if (isolated) "--isolated-projects" else "--no-isolated-projects"
            } else "-Dorg.gradle.unsafe.isolated-projects=$isolated"
    }
}
