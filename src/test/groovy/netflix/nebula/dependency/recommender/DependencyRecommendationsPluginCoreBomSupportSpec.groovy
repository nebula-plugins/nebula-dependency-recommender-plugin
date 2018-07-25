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
import nebula.test.dependencies.maven.ArtifactType
import nebula.test.dependencies.maven.Pom
import nebula.test.dependencies.repositories.MavenRepo
import spock.lang.Unroll

class DependencyRecommendationsPluginCoreBomSupportSpec extends IntegrationSpec {
    def repo
    def generator

    def setup() {
        fork = true
        new File("${projectDir}/gradle.properties").text = "systemProp.nebula.features.coreBomSupport=true"

        settingsFile << """\
            enableFeaturePreview('IMPROVED_POM_SUPPORT')
            """.stripIndent()

        repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.0.0')
        pom.addManagementDependency('test.nebula', 'bar', '2.0.0')
        pom.addManagementDependency('test.nebula', 'baz', '2.5.0')
        pom.addManagementDependency('test.nebula', 'lib', '3.9.9')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .addModule('test.nebula:bar:2.0.0')
                .addModule('test.nebula:baz:2.5.0')
                .addModule('test.nebula:lib:3.9.9')
                .build()
        generator = new GradleDependencyGenerator(graph)
        generator.generateTestMavenRepo()
    }

    def 'add given bom to configs'() {
        buildFile << """\
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'
            apply plugin: 'war'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:testbom:latest.release'
            }

            dependencies {
                annotationProcessor 'test.nebula:bar'
                compile 'test.nebula:foo'
                providedCompile 'test.nebula:lib'
                runtimeOnly 'test.nebula:baz'
            }
            """.stripIndent()

        when:
        def result = runTasksSuccessfully('dependencies')

        then:
        result.standardOutput.contains("+--- test.nebula:foo -> 1.0.0")
        result.standardOutput.contains("+--- test.nebula:bar -> 2.0.0")
        result.standardOutput.contains("\\--- test.nebula:baz -> 2.5.0")
        result.standardOutput.contains("\\--- test.nebula:lib -> 3.9.9")
    }

    @Unroll
    def 'error when #type(#argType) used'() {
        given:
        buildFile << """\
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'

            dependencyRecommendations {
                $type $arg
            }
            """.stripIndent()

        when:
        def result = runTasksWithFailure('dependencies', '--configuration', 'compileClasspath')

        then:
        result.standardOutput.contains("> dependencyRecommender.$type is not available with 'systemProp.nebula.features.coreBomSupport=true'")

        where:
        type             | argType   | arg
        'map'            | '[:]'     | "recommendations: ['test.nebula:foo': '1.0.0']"
        'map'            | '{}'      | "{ [recommendations: ['test.nebula:foo': '1.0.0']] }"
        'dependencyLock' | '[:]'     | "module: 'sample:dependencies:1.0'"
        'dependencyLock' | '{}'      | "{ [module: 'sample:dependencies:1.0'] }"
        'ivyXml'         | '[:]'     | "module: 'sample:sample:1.0'"
        'ivyXml'         | '{}'      | "{ [module: 'sample:sample:1.0'] }"
        'propertiesFile' | '[:]'     | "file: 'recommendations.props'"
        'propertiesFile' | '{}'      | "{ [file: 'recommendations.props'] }"
        'addProvider'    | '{}'      | "{ org, name -> 'latest.release' }"
    }
}
