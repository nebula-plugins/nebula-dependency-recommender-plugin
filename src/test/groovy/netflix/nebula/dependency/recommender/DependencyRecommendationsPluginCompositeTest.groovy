/*
 * Copyright 2016-2026 Netflix, Inc.
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
package netflix.nebula.dependency.recommender

import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dsl.GroovyTestProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import static nebula.test.dsl.TestKitAssertions.assertThat

class DependencyRecommendationsPluginCompositeTest {
    @TempDir
    File projectDir
    @TempDir
    File compositeDir
    @TempDir
    File repoDir

    @Test
    void 'can use recommender in a composite'() {
        def depGraph = new DependencyGraphBuilder()
                .addModule('example:foo:1.0.0')
                .addModule('example:bar:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(depGraph, repoDir.absolutePath)
        generator.generateTestMavenRepo()
        GroovyTestProjectBuilder.testProject(compositeDir) {
            rootProject {
                rawBuildScript("""
allprojects {
    group = 'example'
}
subprojects {
    repositories {
        ${generator.mavenRepositoryBlock}
    }
}
""")
            }
            subProject("a") {
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                dependencies("""implementation 'example:foo'""")
                rawBuildScript("""
dependencyRecommendations {
    map recommendations: ['example:foo': '1.0.0']
}
""")
            }
            subProject("b") {
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                dependencies("""implementation project(':a')""")
                rawBuildScript("""
dependencyRecommendations {
    map recommendations: ['example:foo': '1.0.0']
}
""")
                src {
                    main {
                        java("Main.java", "class Main {}")
                    }
                }
            }
        }

        // Composite
        final var composite = GroovyTestProjectBuilder.testProject(projectDir) {
            properties {
                buildCache(true)
                configurationCache(true)
            }
            settings {
                rawSettingsScript("""
rootProject.name = 'composite'
includeBuild '$compositeDir'
""")
            }
            rootProject {
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
            dependencyRecommendations {
                map recommendations: ['example:foo': '1.0.0']
            }

            repositories {
                ${generator.mavenRepositoryBlock}
            }
""")
                dependencies("implementation 'example:b:1.0.0'")
                src {
                    main {
                        java("Main.java", "class Main {}")
                    }
                }
            }
        }


        def results = composite.run(':dependencies', 'build', '--info')
        assertThat(results.output).contains 'Recommending version 1.0.0 for dependency example:foo'
        assertThat(results.output).contains "\\--- example:b:1.0.0 -> project ':${compositeDir.name}:b'"
        assertThat(results.output).contains "\\--- project ':${compositeDir.name}:a'"
        assertThat(results.output).contains '\\--- example:foo -> 1.0.0'
    }
}
