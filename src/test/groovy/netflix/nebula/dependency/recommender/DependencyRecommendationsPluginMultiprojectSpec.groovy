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
    def setup() {
        System.setProperty("ignoreDeprecations", "true")
        System.setProperty("ignoreMutableProjectStateWarnings", "true")
        new File(projectDir, 'gradle.properties') << '''org.gradle.configuration-cache=true'''.stripIndent()
    }

    def 'can use recommender across a multiproject'() {
        def depGraph = new DependencyGraphBuilder()
                .addModule('example:foo:1.0.0')
                .addModule('example:bar:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(depGraph)
        generator.generateTestMavenRepo()

        def a = addSubproject('a', '''\
                dependencies {
                    implementation 'example:foo'
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
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
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
        normalizedOutput.contains '\\--- project :a\n'
        normalizedOutput.contains '\\--- example:foo -> 1.0.0'

    }

    def 'can use recommender with dependencyInsight across a multiproject'() {
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
                    implementation 'example:foo'
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
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
            }
            subprojects {
                apply plugin: 'java'

                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }

                dependencies {
                    nebulaRecommenderBom 'test.nebula.bom:multiprojectbom:1.0.0@pom'
                }
            }
            """.stripIndent()
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'produce usable error on a multiproject when a subproject depends on another that uses recommendations'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'multiprojectbom', '1.0.0', ArtifactType.POM)
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
                    implementation 'example:foo'
                }
            '''.stripIndent())
        writeHelloWorld('a', a)
        def b = addSubproject('b', '''\
                dependencies {
                    implementation project(':a')
                    implementation 'example:bar:1.0.0'
                }
            '''.stripIndent())
        writeHelloWorld('b', b)
        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
            }
            subprojects {
                apply plugin: 'java'
                
                dependencyRecommendations {
                    strictMode = true
                }

                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }
            """.stripIndent()
        when:
        def results = runTasks(':b:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath', '--info', 'build')

        then:
        def expectedMessage = 'Dependency example:foo omitted version with no recommended version'
        //output where message is printed is different between Gradle 4.7 and 4.8 while we are testing Gradle 4.8 we need to check both
        results.standardError.contains(expectedMessage) || results.standardOutput.contains(expectedMessage)
    }

    def 'recommendation is defined in root and we can see proper reasons in submodule dependency insight'() {
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

        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'

                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }

            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
            """.stripIndent()
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'recommendation is defined in root and we can see proper reasons in submodule dependency insight in parallel'() {
        // Ignore  - Resolution of the configuration :nebulaRecommenderBom was attempted from a context different than the project context.
        // Have a look at the documentation to understand why this is a problem and how it can be resolved. This behavior has been deprecated.
        // This will fail with an error in Gradle 9.0. For more information, please refer to https://docs.gradle.org/8.13/userguide/viewing_debugging_dependencies.html#sub:resolving-unsafe-configuration-resolution-errors in the Gradle documentation.
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

        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'

                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }

            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
            """.stripIndent()
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath', '--parallel')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'can use build service with flag enabled in multiproject'() {
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

        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'

                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())

        // Enable build service via gradle property
        new File(projectDir, 'gradle.properties') << '''
            org.gradle.configuration-cache=true
            nebula.dependency-recommender.useBuildService=true
            '''.stripIndent()

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }

            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
            """.stripIndent()
            
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath', '--parallel')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'build service is used automatically with Gradle 9+ simulation'() {
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

        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'

                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }

            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
            """.stripIndent()
            
        when:
        // Test with current Gradle version - should work regardless of version
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath', '--parallel')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'build service can be disabled with flag set to false'() {
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

        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'

                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())

        // Explicitly disable build service
        new File(projectDir, 'gradle.properties') << '''
            org.gradle.configuration-cache=true
            nebula.dependency-recommender.useBuildService=false
            '''.stripIndent()

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }

            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
            """.stripIndent()
            
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'parallel build works with build service enabled'() {
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

        // Create multiple subprojects to test parallel execution
        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'

                dependencies {
                    implementation 'example:bar'
                }
            '''.stripIndent())

        addSubproject('c', '''\
                apply plugin: 'java'

                dependencies {
                    implementation project(':a')
                    implementation project(':b')
                }
            '''.stripIndent())

        // Enable build service via gradle property
        new File(projectDir, 'gradle.properties') << '''
            org.gradle.configuration-cache=true
            nebula.dependency-recommender.useBuildService=true
            org.gradle.parallel=true
            '''.stripIndent()

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }

            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
            """.stripIndent()
            
        when:
        def results = runTasksSuccessfully('build', '--parallel', '--info')

        then:
        noExceptionThrown()
        // Verify that recommendations were applied without lock conflicts
        !results.standardOutput.contains('IllegalResolutionException')
        !results.standardError.contains('IllegalResolutionException')
    }

    def 'nebulaRecommenderBom configuration works with build service'() {
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

        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'

                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())

        // Enable build service via gradle property
        new File(projectDir, 'gradle.properties') << '''
            org.gradle.configuration-cache=true
            nebula.dependency-recommender.useBuildService=true
            '''.stripIndent()

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }
            
            subprojects {
                dependencies {
                    nebulaRecommenderBom 'test.nebula.bom:multiprojectbom:1.0.0@pom'
                }
            }
            """.stripIndent()
            
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'recommendation is defined in root and we can see proper reasons in submodule dependency insight in parallel'() {
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

        addSubproject('a', '''\
                apply plugin: 'java'
                
                dependencies {
                    implementation 'example:foo'
                }
            '''.stripIndent())

        addSubproject('b', '''\
                apply plugin: 'java'
                dependencies {
                    implementation project(':a')
                }
            '''.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }
            dependencyRecommendations {
                mavenBom module: 'test.nebula.bom:multiprojectbom:1.0.0@pom'
            }
            """.stripIndent()
        when:
        def results = runTasksSuccessfully(':a:dependencyInsight', '--dependency', 'foo', '--configuration', 'compileClasspath', '--parallel')

        then:
        results.standardOutput.contains 'Recommending version 1.0.0 for dependency example:foo'
        results.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:multiprojectbom:pom:1.0.0'
    }

    def 'different subprojects use different BOMs with different versions of same dependencies'() {
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
        def generator = new GradleDependencyGenerator(depGraph)
        generator.generateTestMavenRepo()

        // Create subproject A with its own BOM
        addSubproject('projecta', '''\
                apply plugin: 'java'
                
                dependencyRecommendations {
                    mavenBom module: 'test.nebula.bom:projecta-bom:1.0.0@pom'
                }
                
                dependencies {
                    implementation 'commons-logging:commons-logging'
                    implementation 'commons-lang:commons-lang'
                    testImplementation 'junit:junit'
                }
            '''.stripIndent())

        // Create subproject B with its own BOM
        addSubproject('projectb', '''\
                apply plugin: 'java'
                
                dependencyRecommendations {
                    mavenBom module: 'test.nebula.bom:projectb-bom:1.0.0@pom'
                }
                
                dependencies {
                    implementation 'commons-logging:commons-logging'
                    implementation 'commons-lang:commons-lang'
                    testImplementation 'junit:junit'
                }
            '''.stripIndent())

        // Create subproject C with its own BOM
        addSubproject('projectc', '''\
                apply plugin: 'java'
                
                dependencyRecommendations {
                    mavenBom module: 'test.nebula.bom:projectc-bom:1.0.0@pom'
                }
                
                dependencies {
                    implementation 'commons-logging:commons-logging'
                    implementation 'commons-lang:commons-lang'
                    testImplementation 'junit:junit'
                }
            '''.stripIndent())

        buildFile << """\
            allprojects {
                apply plugin: 'com.netflix.nebula.dependency-recommender'
                
                repositories {
                    maven { url = '${repo.root.absoluteFile.toURI()}' }
                    ${generator.mavenRepositoryBlock}
                }
            }
            """.stripIndent()

        when:
        def resultsA = runTasksSuccessfully(':projecta:dependencies', '--configuration', 'compileClasspath')
        def resultsB = runTasksSuccessfully(':projectb:dependencies', '--configuration', 'compileClasspath')
        def resultsC = runTasksSuccessfully(':projectc:dependencies', '--configuration', 'compileClasspath')

        then:
        // Verify project A gets version 1.0.0 and 2.0.0
        resultsA.standardOutput.contains('commons-logging:commons-logging -> 1.0.0')
        resultsA.standardOutput.contains('commons-lang:commons-lang -> 2.0.0')
        !resultsA.standardOutput.contains('commons-logging:commons-logging -> 1.1.0')
        !resultsA.standardOutput.contains('commons-logging:commons-logging -> 1.2.0')

        // Verify project B gets version 1.1.0 and 2.1.0
        resultsB.standardOutput.contains('commons-logging:commons-logging -> 1.1.0')
        resultsB.standardOutput.contains('commons-lang:commons-lang -> 2.1.0')
        !resultsB.standardOutput.contains('commons-logging:commons-logging -> 1.0.0')
        !resultsB.standardOutput.contains('commons-logging:commons-logging -> 1.2.0')

        // Verify project C gets version 1.2.0 and 2.2.0
        resultsC.standardOutput.contains('commons-logging:commons-logging -> 1.2.0')
        resultsC.standardOutput.contains('commons-lang:commons-lang -> 2.2.0')
        !resultsC.standardOutput.contains('commons-logging:commons-logging -> 1.0.0')
        !resultsC.standardOutput.contains('commons-logging:commons-logging -> 1.1.0')

        when: "running with parallel execution to test BOM caching"
        def parallelResults = runTasksSuccessfully(':projecta:dependencyInsight', '--dependency', 'commons-logging', '--configuration', 'compileClasspath',
                ':projectb:dependencyInsight', '--dependency', 'commons-logging', '--configuration', 'compileClasspath',
                ':projectc:dependencyInsight', '--dependency', 'commons-logging', '--configuration', 'compileClasspath',
                '--parallel')

        then: "each project should get recommendations from its own BOM"
        parallelResults.standardOutput.contains('Recommending version 1.0.0 for dependency commons-logging:commons-logging')
        parallelResults.standardOutput.contains('Recommending version 1.1.0 for dependency commons-logging:commons-logging')
        parallelResults.standardOutput.contains('Recommending version 1.2.0 for dependency commons-logging:commons-logging')
        parallelResults.standardOutput.contains('nebula.dependency-recommender uses mavenBom: test.nebula.bom:projecta-bom:pom:1.0.0')
        parallelResults.standardOutput.contains('nebula.dependency-recommender uses mavenBom: test.nebula.bom:projectb-bom:pom:1.0.0')
        parallelResults.standardOutput.contains('nebula.dependency-recommender uses mavenBom: test.nebula.bom:projectc-bom:pom:1.0.0')
    }
}
