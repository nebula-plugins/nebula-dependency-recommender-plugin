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
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Issue

class DependencyRecommendationsPluginSpec extends IntegrationSpec  {
    def 'applies recommendations to dependencies with no version'() {
        when:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        def recommendations = createFile('recommendations.properties')
        recommendations << 'com.google.guava:guava = 18.0'

        project.dependencyRecommendations {
            propertiesFile name: 'props', file: recommendations
        }

        project.repositories {
            mavenCentral()
        }

        project.dependencies {
            implementation 'com.google.guava:guava'
        }

        def resolved = project.configurations.compileClasspath.incoming.resolutionResult
                .allComponents.collect { it.moduleVersion }

        then:
        resolved.size() == 2 // the first one is :test:unspecified
        resolved[1].name == 'guava'
        resolved[1].version == '18.0'
    }

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
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
                implementation 'test.nebula:foo'
            }
            """.stripIndent()

        when:
        def result = runTasksSuccessfully('dependencies')//, '--configuration', 'compile')

        then:
        result.standardOutput.contains 'test.nebula:foo -> 1.0.0'
    }

    def 'provide recommendation via configuration - no zinc'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '2.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '2.0.0')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .addModule('test.nebula:foo:2.0.0')
                .build()
        def generator = new GradleDependencyGenerator(graph)
        generator.generateTestMavenRepo()

        buildFile << """\
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            configurations {
                zinc
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:2.0.0@pom'
                zinc 'test.nebula:foo:1.0.0'
                implementation 'test.nebula:foo'
            }
            """.stripIndent()

        when:
        def resultZinc = runTasksSuccessfully('dependencies', '--configuration', 'zinc')

        then:
        resultZinc.standardOutput.contains '\\--- test.nebula:foo:1.0.0'

        when:
        def resultCompileClasspath = runTasksSuccessfully('dependencies')

        then:
        resultCompileClasspath.standardOutput.contains 'test.nebula:foo -> 2.0.0'
    }

    def 'dependencyInsight from recommendation via configuration'() {
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
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
                implementation 'test.nebula:foo'
            }
            """.stripIndent()

        when:
        def result = runTasksSuccessfully('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'foo')

        then:
        result.standardOutput.contains 'Recommending version 1.0.0 for dependency test.nebula:foo'
        result.standardOutput.contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:testbom:pom:1.0.0'
    }

    def 'conflict resolved respects higher transitive'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.0.0')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .addModule('test.nebula:foo:1.1.0')
                .addModule(new ModuleBuilder('test.nebula:bar:1.0.0').addDependency('test.nebula:foo:1.1.0').build())
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/build/testrepo")
        generator.generateTestMavenRepo()

        buildFile << """\
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
                implementation 'test.nebula:foo'
                implementation 'test.nebula:bar:1.0.0'
            }
            """.stripIndent()

        when:
        def result = runTasksSuccessfully('dependencies')//, '--configuration', 'compile')

        then:
        result.standardOutput.contains 'test.nebula:foo -> 1.1.0'
    }

    def 'conflict resolved respects higher recommendation'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.1.0')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .addModule('test.nebula:foo:1.1.0')
                .addModule(new ModuleBuilder('test.nebula:bar:1.0.0').addDependency('test.nebula:foo:1.0.0').build())
                .build()
        def generator = new GradleDependencyGenerator(graph, "$projectDir/build/testrepo")
        generator.generateTestMavenRepo()

        buildFile << """\
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
                implementation 'test.nebula:foo'
                implementation 'test.nebula:bar:1.0.0'
            }
            """.stripIndent()

        when:
        def result = runTasksSuccessfully('dependencies')//, '--configuration', 'compile')

        then:
        result.standardOutput.contains 'test.nebula:foo -> 1.1.0'
        result.standardOutput.contains '\\--- test.nebula:foo:1.0.0 -> 1.1.0'
    }

    def 'can lock boms'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.0.0')
        repo.poms.add(pom)
        def newPom = new Pom('test.nebula.bom', 'testbom', '1.1.0', ArtifactType.POM)
        newPom.addManagementDependency('test.nebula', 'foo', '1.0.1')
        repo.poms.add(newPom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .addModule('test.nebula:foo:1.0.1')
                .build()
        def generator = new GradleDependencyGenerator(graph)
        generator.generateTestMavenRepo()

        buildFile << """\
            plugins {
                id 'nebula.dependency-lock' version '4.3.2'
            }
            apply plugin: 'nebula.dependency-recommender'
            apply plugin: 'java'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${generator.mavenRepositoryBlock}
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:latest.release@pom'
                implementation 'test.nebula:foo'
            }
            """.stripIndent()

        new File(projectDir, 'dependencies.lock').text = '''\
            {
                "nebulaRecommenderBom": {
                    "test.nebula.bom:testbom": {
                        "locked": "1.0.0",
                        "requested": "latest.release"
                    }
                }
            }
            '''.stripIndent()

        when:
        def result = runTasksSuccessfully('dependencies')//, '--configuration', 'compile')

        then:
        result.standardOutput.contains 'test.nebula:foo -> 1.0.0'
    }

    def 'configures the maven-publish plugin to publish a BOM'() {
        when:
        buildFile << '''
            apply plugin: 'maven-publish'
            apply plugin: 'nebula.dependency-recommender'

            group = 'netflix'
            version = '1'

            configurations { implementation }

            repositories { mavenCentral() }

            dependencies {
                implementation 'commons-configuration:commons-configuration:1.6'
            }

            publishing {
                publications {
                    parent(MavenPublication) {
                        nebulaDependencyManagement.fromConfigurations { configurations.implementation }
                        nebulaDependencyManagement.withDependencies { 'manual:dep:1' }

                        artifactId = 'module-parent'
                        pom.withXml { asNode().appendNode('description', 'A demonstration of maven POM customization') }
                    }
                }
                repositories {
                    maven { url "$buildDir/repo" }
                }
            }
        '''

        runTasksSuccessfully('publish')

        def pomText = new File(projectDir, "build/repo/netflix/module-parent/1/module-parent-1.pom").text

        then:
        // assert on overall shape
        pomText.contains('''
<project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
  <modelVersion>4.0.0</modelVersion>
  <groupId>netflix</groupId>
  <artifactId>module-parent</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <dependencyManagement>
    <dependencies>
''')

        pomText.contains('''
    </dependencies>
  </dependencyManagement>
  <description>A demonstration of maven POM customization</description>
</project>
''')

        // assert on dependency blocks, that are sometimes re-ordered
        pomText.findAll('<dependency>').size() == 8

        def expectedDependencyBlocks = [
                '''
      <dependency>
        <groupId>commons-digester</groupId>
        <artifactId>commons-digester</artifactId>
        <version>1.8</version>
      </dependency>
      ''',
                '''
      <dependency>
        <groupId>commons-collections</groupId>
        <artifactId>commons-collections</artifactId>
        <version>3.2.1</version>
      </dependency>
      ''',
                '''
      <dependency>
        <groupId>commons-beanutils</groupId>
        <artifactId>commons-beanutils-core</artifactId>
        <version>1.8.0</version>
      </dependency>
      ''',
                '''
      <dependency>
        <groupId>commons-lang</groupId>
        <artifactId>commons-lang</artifactId>
        <version>2.4</version>
      </dependency>
      ''',
                '''
      <dependency>
        <groupId>commons-beanutils</groupId>
        <artifactId>commons-beanutils</artifactId>
        <version>1.7.0</version>
      </dependency>
      ''',
                '''
      <dependency>
        <groupId>commons-logging</groupId>
        <artifactId>commons-logging</artifactId>
        <version>1.1.1</version>
      </dependency>
      ''',
                '''
      <dependency>
        <groupId>commons-configuration</groupId>
        <artifactId>commons-configuration</artifactId>
        <version>1.6</version>
      </dependency>
      ''',
                '''
      <dependency>
        <groupId>manual</groupId>
        <artifactId>dep</artifactId>
        <version>1</version>
      </dependency>
      '''
        ]

        expectedDependencyBlocks.each {
            pomText.contains(it)
        }
    }

    @Issue('#49')
    def 'substituted dependencies do not have recommendations applied'() {
        when:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        def recommendations = createFile('recommendations.properties')
        recommendations << 'com.google.collections:google-collections = 1.0'

        project.dependencyRecommendations {
            propertiesFile name: 'props', file: recommendations
        }

        project.repositories {
            mavenCentral()
        }

        project.configurations.all {
            resolutionStrategy.dependencySubstitution {
                substitute module('com.google.collections:google-collections') with module ('com.google.guava:guava:12.0')
            }
        }

        project.dependencies {
            implementation 'com.google.collections:google-collections'
        }

        def resolutionResult = project.configurations.compileClasspath.incoming.resolutionResult
        def guava = resolutionResult.allDependencies.first()

        then:
        guava instanceof ResolvedDependencyResult
        def moduleVersion = (guava as ResolvedDependencyResult).selected.moduleVersion
        moduleVersion.group == 'com.google.guava'
        moduleVersion.name == 'guava'
        moduleVersion.version == '12.0'
    }

    def 'check that a circular dependency on ourself does not break recommender'() {
        given:
        def graph = new DependencyGraphBuilder()
                .addModule(new ModuleBuilder('test.nebula:foo:1.0.0').addDependency('example.nebula:self:1.0.0').build())
                .build()
        def dependencies = new GradleDependencyGenerator(graph, "${projectDir}/repo")
        dependencies.generateTestMavenRepo()

        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
                .addManagementDependency('test.nebula', 'foo', '1.0.0')
                .addManagementDependency('example.nebula', 'self', '1.0.0')
        repo.poms.add(pom)
        repo.generate()

        settingsFile << '''\
            rootProject.name = 'self'
            '''.stripIndent()

        buildFile << """\
            apply plugin: 'java'
            apply plugin: 'nebula.dependency-recommender'

            group = 'example.nebula'
            version = '1.0.0'

            repositories {
                maven { url '${repo.root.absoluteFile.toURI()}' }
                ${dependencies.mavenRepositoryBlock}
            }

            dependencies {
                nebulaRecommenderBom 'test.nebula.bom:testbom:latest.release@pom'
                implementation 'test.nebula:foo'
            }
            """.stripIndent()

        when:
        runTasks('dependencies')

        then:
        noExceptionThrown()
    }
}
