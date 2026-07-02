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

import nebula.test.dependencies.DependencyGraphBuilder
import nebula.test.dependencies.GradleDependencyGenerator
import nebula.test.dependencies.ModuleBuilder
import nebula.test.dependencies.maven.ArtifactType
import nebula.test.dependencies.maven.Pom
import nebula.test.dependencies.repositories.MavenRepo
import nebula.test.dsl.GroovyTestProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static nebula.test.dsl.TestKitAssertions.assertThat

class DependencyRecommendationsPluginMultiprojectTest {
    @TempDir
    File projectDir
    @TempDir
    File repoDir

    @Test
    void 'can use recommender across a multiproject'() {
        def depGraph = new DependencyGraphBuilder()
                .addModule('example:foo:1.0.0')
                .addModule('example:bar:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(depGraph, repoDir.absolutePath)
        generator.generateTestMavenRepo()

        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            subProject("a") {
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    ${generator.mavenRepositoryBlock}
                }
                 dependencyRecommendations {
                    map recommendations: ['example:foo': '1.0.0']
                }""")
                dependencies("implementation 'example:foo'")
                src { main { java("A.java","public class A { }")}}
            }
            subProject("b") {
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    ${generator.mavenRepositoryBlock}
                }
                 dependencyRecommendations {
                    map recommendations: ['example:foo': '1.0.0']
                }""")
                dependencies("implementation project(':a')")
                src { main { java("B.java","public class B { }")}}
            }
        }

        BuildResult results = runner.run(':a:dependencies', ':b:dependencies', 'build', '--info')
        def debugOutputPrefix = /^\d{2}:\d{2}:\d{2}.\d{3} \[[^\]]+\] \[[^\]]+\] /
        def normalizedOutput = results.output.normalize().readLines().collect { it.replaceAll(debugOutputPrefix, '') }.join('\n')

        assertThat(normalizedOutput).contains 'Recommending version 1.0.0 for dependency example:foo\n'
        assertThat(normalizedOutput).contains '\\--- project \':a\'\n'
        assertThat(normalizedOutput).contains '\\--- example:foo -> 1.0.0'
    }

    @Test
    void 'can use recommender with dependencyInsight across a multiproject'() {
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
        def generator = new GradleDependencyGenerator(depGraph, repoDir.absolutePath)
        generator.generateTestMavenRepo()
        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            subProject("a"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
""")
                dependencies("implementation 'example:foo'", "nebulaRecommenderBom 'test.nebula.bom:multiprojectbom:1.0.0@pom'")
                src { main { java("A.java","public class A { }")}}
            }
            subProject("b"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    ${generator.mavenRepositoryBlock}
                }
""")
                dependencies("implementation project(':a')", "nebulaRecommenderBom 'test.nebula.bom:multiprojectbom:1.0.0@pom'")
                src { main { java("B.java","public class B { }")}}
            }
        }

        BuildResult results = runner.run(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath')

        assertThat(results.output).contains 'Recommending version 1.0.0 for dependency example:foo'
        assertThat(results.output).contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    @Test
    void 'produce usable error on a multiproject when a subproject depends on another that uses recommendations'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'multiprojectbom', '1.0.0', ArtifactType.POM)
        repo.poms.add(pom)
        repo.generate()
        def depGraph = new DependencyGraphBuilder()
                .addModule('example:foo:1.0.0')
                .addModule(new ModuleBuilder('example:bar:1.0.0').addDependency('example:foo:1.0.0').build())
                .build()
        def generator = new GradleDependencyGenerator(depGraph, repoDir.absolutePath)
        generator.generateTestMavenRepo()

        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            rootProject{
                plugins{
                    id("com.netflix.nebula.dependency-recommender")
                }
            }
            subProject("a"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}
dependencyRecommendations {
    strictMode = true
}
""")
                dependencies("implementation 'example:foo'", "nebulaRecommenderBom 'test.nebula.bom:multiprojectbom:1.0.0@pom'")
                src { main { java("A.java","public class A { }")}}
            }
            subProject("b"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
dependencyRecommendations {
    strictMode = true
}
""")
                dependencies(
                        "implementation project(':a')",
                        "implementation 'example:bar:1.0.0'")
                src { main { java("B.java","public class B { }")}}
            }
        }

        BuildResult results = runner.runAndFail(':b:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath', '--info', 'build', "--stacktrace")

        def expectedMessage = 'Dependency example:foo omitted version with no recommended version'
        assertThat(results.output).contains(expectedMessage)
    }

    @Test
    void 'recommendation is defined in root and we can see proper reasons in submodule dependency insight'() {
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
        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            subProject("a"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
""")
                dependencies("implementation 'example:foo'")
                src { main { java("A.java","public class A { }")}}
            }
            subProject("b"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
""")
                dependencies("implementation project(':a')")
                src { main { java("B.java","public class B { }")}}
            }
        }

        BuildResult results = runner.run(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath')

        assertThat(results.output).contains 'Recommending version 1.0.0 for dependency example:foo'
        assertThat(results.output).contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    @Test
    void 'recommendation is defined in root and we can see proper reasons in submodule dependency insight in parallel'() {
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
        def generator = new GradleDependencyGenerator(depGraph, repoDir.absolutePath)
        generator.generateTestMavenRepo()
        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            subProject("a"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
""")
                dependencies("implementation 'example:foo'")
                src { main { java("A.java","public class A { }")}}
            }
            subProject("b"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}
dependencyRecommendations {
    mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
}
""")
                dependencies("implementation project(':a')")
                src { main { java("B.java","public class B { }")}}
            }
        }

        BuildResult results = runner.run(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath', '--parallel')

        then:
        assertThat(results.output).contains 'Recommending version 1.0.0 for dependency example:foo'
        assertThat(results.output).contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    @Test
    void 'different subprojects use different BOMs with different versions of same dependencies'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        
        // Create BOM for project A with version 1.0.0 of common dependencies
        def bomA = new Pom('test.nebula.bom', 'projecta-bom', '1.0.0', ArtifactType.POM)
        bomA.addManagementDependency('commons-logging', 'commons-logging', '1.0.0')
        bomA.addManagementDependency('commons-lang', 'commons-lang', '2.0.0')
        bomA.addManagementDependency('junit', 'junit', '4.12')
        repo.poms.add(bomA)
        
        // Create BOM for project B with version 1.1.0 of common dependencies
        def bomB = new Pom('test.nebula.bom', 'projectb-bom', '1.0.0', ArtifactType.POM)
        bomB.addManagementDependency('commons-logging', 'commons-logging', '1.1.0')
        bomB.addManagementDependency('commons-lang', 'commons-lang', '2.1.0')
        bomB.addManagementDependency('junit', 'junit', '4.13')
        repo.poms.add(bomB)
        
        // Create BOM for project C with version 1.2.0 of common dependencies
        def bomC = new Pom('test.nebula.bom', 'projectc-bom', '1.0.0', ArtifactType.POM)
        bomC.addManagementDependency('commons-logging', 'commons-logging', '1.2.0')
        bomC.addManagementDependency('commons-lang', 'commons-lang', '2.2.0')
        bomC.addManagementDependency('junit', 'junit', '4.13.2')
        repo.poms.add(bomC)
        
        repo.generate()
        
        // Create dependency graph with all versions
        def depGraph = new DependencyGraphBuilder()
                .addModule('commons-logging:commons-logging:1.0.0')
                .addModule('commons-logging:commons-logging:1.1.0')
                .addModule('commons-logging:commons-logging:1.2.0')
                .addModule('commons-lang:commons-lang:2.0.0')
                .addModule('commons-lang:commons-lang:2.1.0')
                .addModule('commons-lang:commons-lang:2.2.0')
                .addModule('junit:junit:4.12')
                .addModule('junit:junit:4.13')
                .addModule('junit:junit:4.13.2')
                .build()
        def generator = new GradleDependencyGenerator(depGraph, repoDir.absolutePath)
        generator.generateTestMavenRepo()
        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            subProject("projecta"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                dependencies(
                        "implementation 'commons-logging:commons-logging'",
                        "implementation 'commons-lang:commons-lang'",
                        "testImplementation 'junit:junit'"
                )
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}
   dependencyRecommendations {
        mavenBom module: 'test.nebula.bom:projecta-bom:1.0.0@pom'
    }
    """)
            }
            subProject("projectb"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                dependencies(
                        "implementation 'commons-logging:commons-logging'",
                        "implementation 'commons-lang:commons-lang'",
                        "testImplementation 'junit:junit'"
                )
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}
dependencyRecommendations {
    mavenBom module: 'test.nebula.bom:projectb-bom:1.0.0@pom'
}
                """)
            }
            subProject("projectc"){
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                dependencies(
                        "implementation 'commons-logging:commons-logging'",
                        "implementation 'commons-lang:commons-lang'",
                        "testImplementation 'junit:junit'"
                )
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}
dependencyRecommendations {
    mavenBom module: 'test.nebula.bom:projectc-bom:1.0.0@pom'
}
""")
            }
        }

        def resultsA = runner.run(':projecta:dependencies', '--configuration', 'compileClasspath')
        def resultsB = runner.run(':projectb:dependencies', '--configuration', 'compileClasspath')
        def resultsC = runner.run(':projectc:dependencies', '--configuration', 'compileClasspath')

        then:
        // Verify project A gets version 1.0.0 and 2.0.0
        assertThat(resultsA.output).contains('commons-logging:commons-logging -> 1.0.0')
        assertThat(resultsA.output).contains('commons-lang:commons-lang -> 2.0.0')
        assertThat(resultsA.output).doesNotContain('commons-logging:commons-logging -> 1.1.0')
        assertThat(resultsA.output).doesNotContain('commons-logging:commons-logging -> 1.2.0')
        
        // Verify project B gets version 1.1.0 and 2.1.0
        assertThat(resultsB.output).contains('commons-logging:commons-logging -> 1.1.0')
        assertThat(resultsB.output).contains('commons-lang:commons-lang -> 2.1.0')
        assertThat(resultsB.output).doesNotContain('commons-logging:commons-logging -> 1.0.0')
        assertThat(resultsB.output).doesNotContain('commons-logging:commons-logging -> 1.2.0')
        
        // Verify project C gets version 1.2.0 and 2.2.0
        assertThat(resultsC.output).contains('commons-logging:commons-logging -> 1.2.0')
        assertThat(resultsC.output).contains('commons-lang:commons-lang -> 2.2.0')
        assertThat(resultsC.output).doesNotContain('commons-logging:commons-logging -> 1.0.0')
        assertThat(resultsC.output).doesNotContain('commons-logging:commons-logging -> 1.1.0')

        // running with parallel execution to test BOM caching
        def parallelResults = runner.run(':projecta:dependencyInsight', '--dependency', 'commons-logging', '--configuration', 'compileClasspath',
                                                  ':projectb:dependencyInsight', '--dependency', 'commons-logging', '--configuration', 'compileClasspath',
                                                  ':projectc:dependencyInsight', '--dependency', 'commons-logging', '--configuration', 'compileClasspath',
                                                  '--parallel')

        // each project should get recommendations from its own BOM
        assertThat(parallelResults.output).contains('Recommending version 1.0.0 for dependency commons-logging:commons-logging')
        assertThat(parallelResults.output).contains('Recommending version 1.1.0 for dependency commons-logging:commons-logging')
        assertThat(parallelResults.output).contains('Recommending version 1.2.0 for dependency commons-logging:commons-logging')
        assertThat(parallelResults.output).contains('nebula.dependency-recommender uses mavenBom: test.nebula.bom:projecta-bom:pom:1.0.0')
        assertThat(parallelResults.output).contains('nebula.dependency-recommender uses mavenBom: test.nebula.bom:projectb-bom:pom:1.0.0')
        assertThat(parallelResults.output).contains('nebula.dependency-recommender uses mavenBom: test.nebula.bom:projectc-bom:pom:1.0.0')
    }
}
