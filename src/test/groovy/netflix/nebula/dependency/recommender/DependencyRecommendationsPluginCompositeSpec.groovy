/*
 * Copyright 2016-2017 Netflix, Inc.
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

import nebula.test.IntegrationSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator

class DependencyRecommendationsPluginCompositeSpec extends IntegrationSpec {
    def 'can use recommender in a composite'() {
        fork = false

        def depGraph = new DependencyGraphBuilder()
                .addModule('example:foo:1.0.0')
                .addModule('example:bar:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(depGraph)
        generator.generateTestMavenRepo()

        def a = addSubproject('a', '''\
                dependencies {
                    compile 'example:foo'
                }
            '''.stripIndent())
        writeHelloWorld('a', a)
        def b = addSubproject('b', '''\
                dependencies {
                    compile project(':a')
                }
            '''.stripIndent())
        writeHelloWorld('b', b)

        buildFile << """\
            allprojects {
                group 'example'
            
                apply plugin: 'nebula.dependency-recommender'
                dependencyRecommendations {
                    map recommendations: ['example:foo': '1.0.0']
                }
            }

            subprojects {
                apply plugin: 'java'

                repositories {
                    ${generator.mavenRepositoryBlock}
                }
            }
            """.stripIndent()

        def compositeDir = new File(projectDir.parentFile, "${projectDir.name}-composite")
        compositeDir.deleteDir()
        projectDir.renameTo(compositeDir)
        projectDir.mkdirs()

        // Composite
        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'nebula.dependency-recommender'

            dependencyRecommendations {
                map recommendations: ['example:foo': '1.0.0']
            }

            repositories {
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                compile 'example:b:1.0.0'
            }
        """.stripIndent()
        settingsFile << """\
            rootProject.name = 'composite'
            includeBuild '$compositeDir'
        """
        writeHelloWorld('c')

        when:
        def results = runTasksSuccessfully(':dependencies', 'build', '--debug')

        then:
        noExceptionThrown()
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains '\\--- example:b:1.0.0 -> project :can-use-recommender-in-a-composite:b'
        results.standardOutput.contains '\\--- project :can-use-recommender-in-a-composite:a'
        results.standardOutput.contains '\\--- example:foo -> 1.0.0'
    }
}
