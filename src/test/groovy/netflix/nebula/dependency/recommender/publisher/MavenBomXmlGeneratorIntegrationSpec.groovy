/*
 * Copyright 2017 Netflix, Inc.
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
package netflix.nebula.dependency.recommender.publisher

import groovy.xml.XmlSlurper
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import nebula.test.dsl.GroovyTestProjectBuilder
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir

import static org.assertj.core.api.Assertions.assertThat

class MavenBomXmlGeneratorIntegrationSpec {
    @TempDir
    File projectDir

    @Test
    void 'pom created'() {
        def graph = new DependencyGraphBuilder().addModule('test0:test0:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/mytestrepo")
        generator.generateTestMavenRepo()
        def runner = GroovyTestProjectBuilder.testProject(projectDir) {
            properties {
                buildCache(true)
            }
            rootProject {
                plugins {
                    id("com.netflix.nebula.dependency-recommender")
                    id 'nebula.maven-publish' version 'latest.release'
                }
                rawBuildScript("""\
            group = 'test.nebula'
            version = '0.1.0'
            
            repositories {
                ${generator.mavenRepositoryBlock}
            }
            
            configurations {
                recommendation
            }
            
            dependencies {
                recommendation 'test0:test0:1.0.0'
            }
            
            publishing {
                publications {
                    recommender(MavenPublication) {
                        project.nebulaDependencyManagement.fromConfigurations {
                            project.configurations.recommendation
                        }
                    }
                }
            }
            """)
            }
        }

        def results = runner.run('generatePomFileForRecommenderPublication', "--stacktrace")

        def xml = new File(projectDir, 'build/publications/recommender/pom-default.xml')
        def reader = new XmlSlurper().parse(xml)
        assertThat(reader.dependencyManagement.dependencies.dependency.size()).isEqualTo(1)
        assertThat(reader.dependencyManagement.dependencies.dependency.groupId.text()).isEqualTo('test0')
        assertThat(reader.dependencyManagement.dependencies.dependency.artifactId.text()).isEqualTo('test0')
        assertThat(reader.dependencyManagement.dependencies.dependency.version.text()).isEqualTo('1.0.0')
    }

    @Test
    void 'pom created conflict resolves'() {
        def graph = new DependencyGraphBuilder()
                .addModule('test0:test0:1.0.0')
                .addModule('test0:test0:1.1.0')
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/mytestrepo")
        generator.generateTestMavenRepo()
        def runner = GroovyTestProjectBuilder.testProject(projectDir) {
            properties {
                buildCache(true)
            }
            rootProject {
                plugins {
                    id("com.netflix.nebula.dependency-recommender")
                    id 'nebula.maven-publish' version 'latest.release'
                }
                rawBuildScript("""\
            group = 'test.nebula'
            version = '0.1.0'

            repositories {
                ${generator.mavenRepositoryBlock}
            }

            configurations {
                recommendation
            }

            dependencies {
                recommendation 'test0:test0:1.1.0'
                recommendation 'test0:test0:1.0.0'
            }

            publishing {
                publications {
                    recommender(MavenPublication) {
                        project.nebulaDependencyManagement.fromConfigurations {
                            project.configurations.recommendation
                        }
                    }
                }
            }
            """)
            }
        }

        def results = runner.run('generatePomFileForRecommenderPublication')

        def xml = new File(projectDir, 'build/publications/recommender/pom-default.xml')
        def reader = new XmlSlurper().parse(xml)
        assertThat(reader.dependencyManagement.dependencies.dependency.size()).isEqualTo(1)
        assertThat(reader.dependencyManagement.dependencies.dependency.groupId.text()).isEqualTo('test0')
        assertThat(reader.dependencyManagement.dependencies.dependency.artifactId.text()).isEqualTo('test0')
        assertThat(reader.dependencyManagement.dependencies.dependency.version.text()).isEqualTo('1.1.0')
    }

    @Test
    @Timeout(30)
    void 'pom generates with circular dependency in graph'() {
        def graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test0:test0:0.1.0').addDependency('test1:test1:1.0.0').build())
                .addModule(new ModuleBuilder('test1:test1:1.0.0').addDependency('test0:test0:0.1.0').build())
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/mytestrepo")
        generator.generateTestMavenRepo()
        def runner = GroovyTestProjectBuilder.testProject(projectDir) {
            properties {
                buildCache(true)
            }
            rootProject {
                plugins {
                    id("com.netflix.nebula.dependency-recommender")
                    id 'nebula.maven-publish' version 'latest.release'
                }
                rawBuildScript("""\
            group = 'test.nebula'
            version = '0.1.0'

            repositories {
                ${generator.mavenRepositoryBlock}
            }

            configurations {
                recommendation
            }

            dependencies {
                recommendation 'test0:test0:0.1.0'
                recommendation 'test1:test1:1.0.0'
            }

            publishing {
                publications {
                    recommender(MavenPublication) {
                        project.nebulaDependencyManagement.fromConfigurations {
                            project.configurations.recommendation
                        }
                    }
                }
            }
            """)
            }
        }

        def results = runner.run('generatePomFileForRecommenderPublication')

        def xml = new File(projectDir, 'build/publications/recommender/pom-default.xml')
        def reader = new XmlSlurper().parse(xml)
        assertThat(reader.dependencyManagement.dependencies.dependency.size()).isEqualTo(2)
        reader.dependencyManagement.dependencies.dependency.each { dep ->
            if (dep.groupId.text() == 'test0') {
                assertThat(dep.artifactId.text()).isEqualTo('test0')
                assertThat(dep.version.text()).isEqualTo('0.1.0')
            } else if (dep.groupId.text() == 'test1') {
                assertThat(dep.artifactId.text()).isEqualTo('test1')
                assertThat(dep.version.text()).isEqualTo('1.0.0')
            } else {
                Assertions.fail('dependency has unexpected group')
            }
        }
    }
}
