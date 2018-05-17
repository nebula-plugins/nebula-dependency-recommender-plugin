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
import nebula.test.dependencies.ModuleBuilder
import nebula.test.dependencies.maven.ArtifactType
import nebula.test.dependencies.maven.Pom
import nebula.test.dependencies.repositories.MavenRepo

class DependencyRecommendationsPluginMultiprojectSpec extends IntegrationSpec {
    def 'can use recommender across a multiproject'() {
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
        when:
        def results = runTasksSuccessfully(':a:dependencies', ':b:dependencies', 'build', '--debug')
        def debugOutputPrefix = /^\d{2}:\d{2}:\d{2}.\d{3} \[[^\]]+\] \[[^\]]+\] /
        def normalizedOutput = results.standardOutput.normalize().readLines().collect { it.replaceAll(debugOutputPrefix, '') }.join('\n')

        then:
        noExceptionThrown()
        normalizedOutput.contains 'Recommending version 1.0.0 for dependency example:foo\n'
        normalizedOutput.contains '\\--- project :a\n' +
                '     \\--- example:foo -> 1.0.0'

    }

    def 'can use recommender with dependencyInsightEnhanced across a multiproject'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'multiprojectbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('example', 'foo', '1.0.0')
        pom.addManagementDependency('example', 'bar', '1.0.0')
        repo.poms.add(pom)
        repo.generate()
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
                apply plugin: 'nebula.dependency-recommender'
            }
            subprojects {
                apply plugin: 'java'

                repositories {
                    maven { url '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }

                dependencies {
                    nebulaRecommenderBom 'test.nebula.bom:multiprojectbom:1.0.0@pom'
                }
            }
            """.stripIndent()
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--configuration', 'compile', '--dependency', 'foo')

        then:
        results.standardOutput.contains 'example:foo:1.0.0 (recommend 1.0.0 via conflict resolution recommendation)'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'produce usable error on a multiproject when a subproject depends on another that uses recommendations'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'multiprojectbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('example', 'foo', '1.0.0')
        repo.poms.add(pom)
        repo.generate()
        def depGraph = new DependencyGraphBuilder()
                .addModule('example:foo:1.0.0')
                .addModule(new ModuleBuilder('example:bar:1.0.0').addDependency('example:foo:1.0.0').build())
                .build()
        def generator = new GradleDependencyGenerator(depGraph)
        generator.generateTestMavenRepo()

        def a = addSubproject('a', '''\
                dependencies {
                    nebulaRecommenderBom 'test.nebula.bom:multiprojectbom:1.0.0@pom'
                    compile 'example:foo'
                }
            '''.stripIndent())
        writeHelloWorld('a', a)
        def b = addSubproject('b', '''\
                dependencies {
                    compile project(':a')
                    compile 'example:bar:1.0.0'
                }
            '''.stripIndent())
        writeHelloWorld('b', b)
        buildFile << """\
            allprojects {
                apply plugin: 'nebula.dependency-recommender'
            }
            subprojects {
                apply plugin: 'java'
                
                dependencyRecommendations {
                    strictMode = true
                }

                repositories {
                    maven { url '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }
            """.stripIndent()
        when:
        def results = runTasks(':b:dependencyInsight', '--configuration', 'compile', '--dependency', 'foo', '--info', 'build')

        then:
        def expectedMessage = 'Dependency example:foo omitted version with no recommended version'
        //output where message is printed is different between Gradle 4.7 and 4.8 while we are testing Gradle 4.8 we need to check both
        results.standardError.contains(expectedMessage) || results.standardOutput.contains(expectedMessage)
    }
}
