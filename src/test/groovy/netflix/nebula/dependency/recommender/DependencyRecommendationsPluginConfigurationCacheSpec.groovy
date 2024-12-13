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

class DependencyRecommendationsPluginConfigurationCacheSpec extends IntegrationTestKitSpec  {

    def 'provide recommendation via configuration'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.0.0')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(graph)
        generator.generateTestMavenRepo()

        buildFile << """\
            plugins {
                id 'com.netflix.nebula.dependency-recommender'
                id 'java'
            }

            repositories {
                maven { url = '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
                implementation 'test.nebula:foo'
            }
            """.stripIndent()

        expect:
        runTasks('--configuration-cache', 'dependencies')
    }

}
