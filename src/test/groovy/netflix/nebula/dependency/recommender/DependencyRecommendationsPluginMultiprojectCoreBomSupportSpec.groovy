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


import nebula.test.IntegrationTestKitSpec
import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.maven.ArtifactType
import nebula.test.dependencies.maven.Pom
import nebula.test.dependencies.repositories.MavenRepo

class DependencyRecommendationsPluginMultiprojectCoreBomSupportSpec extends IntegrationTestKitSpec {
    def repo
    def generator

    def setup() {
        new File("${projectDir}/gradle.properties") << """
            systemProp.nebula.features.coreBomSupport=true
            org.gradle.configuration-cache=true
            """.stripIndent()
        definePluginOutsideOfPluginBlock = true

        repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.0.0')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .build()
        generator = new GradleDependencyGenerator(graph)
        generator.generateTestMavenRepo()
    }

    def cleanup() {
        System.clearProperty('systemProp.nebula.features.coreBomSupport')
    }

    def 'can use recommender across a multiproject'() {
        def a = addSubproject('a', '''\
                dependencies {
                    implementation 'test.nebula:foo'
                }
            '''.stripIndent())
        writeHelloWorld('a', a)
        def b = addSubproject('b', '''\
                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())
        writeHelloWorld('b', b)
        buildFile << """\
            apply plugin: 'com.netflix.nebula.dependency-recommender'
            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:testbom:latest.release'
            }
            
            allprojects {
                apply plugin: 'java'

                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }
            """.stripIndent()
        when:
        def results = runTasks(':a:dependencies', '--configuration', 'compileClasspath')

        then:
        results.output.contains("+--- test.nebula:foo -> 1.0.0")
    }
}
