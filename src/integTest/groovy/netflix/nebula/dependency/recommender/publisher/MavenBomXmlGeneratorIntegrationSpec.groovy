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

import nebula.test.IntegrationSpec
import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import spock.lang.Timeout

import static org.junit.Assert.*;

class MavenBomXmlGeneratorIntegrationSpec extends IntegrationSpec {
    def 'pom created'() {
        def graph = new DependencyGraphBuilder().addModule('test0:test0:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/mytestrepo")
        generator.generateTestMavenRepo()
        buildFile << """\
            plugins {
                id 'nebula.maven-publish' version '5.1.0'
            }
            
            apply plugin: 'com.netflix.nebula.dependency-recommender'
            
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
            """.stripIndent()

        when:
        def results = runTasks('generatePomFileForRecommenderPublication')

        then:
        def xml = new File(projectDir, 'build/publications/recommender/pom-default.xml')
        def reader = new XmlSlurper().parse(xml)
        reader.dependencyManagement.dependencies.dependency.size() == 1
        reader.dependencyManagement.dependencies.dependency.groupId.text() == 'test0'
        reader.dependencyManagement.dependencies.dependency.artifactId.text() == 'test0'
        reader.dependencyManagement.dependencies.dependency.version.text() == '1.0.0'
    }

    def 'pom created conflict resolves'() {
        def graph = new DependencyGraphBuilder()
                .addModule('test0:test0:1.0.0')
                .addModule('test0:test0:1.1.0')
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/mytestrepo")
        generator.generateTestMavenRepo()
        buildFile << """\
            plugins {
                id 'nebula.maven-publish' version '5.1.0'
            }
            
            apply plugin: 'com.netflix.nebula.dependency-recommender'
            
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
            """.stripIndent()

        when:
        def results = runTasks('generatePomFileForRecommenderPublication')

        then:
        def xml = new File(projectDir, 'build/publications/recommender/pom-default.xml')
        def reader = new XmlSlurper().parse(xml)
        reader.dependencyManagement.dependencies.dependency.size() == 1
        reader.dependencyManagement.dependencies.dependency.groupId.text() == 'test0'
        reader.dependencyManagement.dependencies.dependency.artifactId.text() == 'test0'
        reader.dependencyManagement.dependencies.dependency.version.text() == '1.1.0'
    }

    @Timeout(10)
    def 'pom generates with circular dependency in graph'() {
        def graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test0:test0:0.1.0').addDependency('test1:test1:1.0.0').build())
                .addModule(new ModuleBuilder('test1:test1:1.0.0').addDependency('test0:test0:0.1.0').build())
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/mytestrepo")
        generator.generateTestMavenRepo()
        buildFile << """\
            plugins {
                id 'nebula.maven-publish' version '5.1.0'
            }
            
            apply plugin: 'com.netflix.nebula.dependency-recommender'
            
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
            """.stripIndent()

        when:
        def results = runTasks('generatePomFileForRecommenderPublication')

        then:
        def xml = new File(projectDir, 'build/publications/recommender/pom-default.xml')
        def reader = new XmlSlurper().parse(xml)
        reader.dependencyManagement.dependencies.dependency.size() == 2
        reader.dependencyManagement.dependencies.dependency.each { dep ->
            if (dep.groupId.text() == 'test0') {
                dep.artifactId.text() == 'test0'
                dep.version.text() == '0.1.0'
            } else if (dep.groupId.text() == 'test1') {
                dep.artifactId.text() == 'test1'
                dep.version.text() == '1.0.0'
            } else {
                fail('dependency has unexpected group')
            }
        }
    }
}
