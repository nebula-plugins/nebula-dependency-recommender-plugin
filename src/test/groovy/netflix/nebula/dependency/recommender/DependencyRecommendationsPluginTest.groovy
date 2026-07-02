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
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.BuildResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import static nebula.test.dsl.TestKitAssertions.assertThat

class DependencyRecommendationsPluginTest {
    @TempDir
    File projectDir
    @TempDir
    File repoDir

    @Test
    void 'applies recommendations to dependencies with no version'() {
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        def recommendations = projectDir.toPath().resolve('recommendations.properties').toFile()
        recommendations.createNewFile()
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

        assertThat(resolved).as("the first one is :test:unspecified").hasSize(2)
        assertThat(resolved[1].name).isEqualTo('guava')
        assertThat(resolved[1].version).isEqualTo('18.0')
    }

    @Test
    void 'provide recommendation via configuration'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.0.0')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(graph, repoDir.absolutePath)
        generator.generateTestMavenRepo()
        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            properties {
                buildCache(true)
                configurationCache(true)
            }
            rootProject{
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}

dependencies {
    nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
    implementation 'test.nebula:foo'
}
""")
            }
        }

        BuildResult result = runner.run('dependencies')//, '--configuration', 'compile')

        assertThat(result.output).contains 'test.nebula:foo -> 1.0.0'
    }

    @Test
    void 'provide recommendation via configuration - no zinc'() {
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
        def generator = new GradleDependencyGenerator(graph, repoDir.absolutePath)
        generator.generateTestMavenRepo()

        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            properties {
                buildCache(true)
                configurationCache(true)
            }
            rootProject{
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
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
""")
            }
        }

        BuildResult resultZinc = runner.run('dependencies', '--configuration', 'zinc')

        then:
       assertThat(resultZinc.output).contains '\\--- test.nebula:foo:1.0.0'

        when:
        BuildResult resultCompileClasspath = runner.run('dependencies')

        then:
        assertThat(resultCompileClasspath.output).contains 'test.nebula:foo -> 2.0.0'
    }

    @Test
    void 'dependencyInsight from recommendation via configuration'() {
        def repo = new MavenRepo()
        repo.root = new File(projectDir, 'build/bomrepo')
        def pom = new Pom('test.nebula.bom', 'testbom', '1.0.0', ArtifactType.POM)
        pom.addManagementDependency('test.nebula', 'foo', '1.0.0')
        repo.poms.add(pom)
        repo.generate()
        def graph = new DependencyGraphBuilder()
                .addModule('test.nebula:foo:1.0.0')
                .build()
        def generator = new GradleDependencyGenerator(graph, repoDir.absolutePath)
        generator.generateTestMavenRepo()

        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            properties {
                buildCache(true)
                configurationCache(true)
            }
            rootProject{
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}

dependencies {
    nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
    implementation 'test.nebula:foo'
}
""")
            }
        }

        BuildResult result = runner.run('dependencyInsight', '--configuration', 'compileClasspath', '--dependency', 'foo')

        then:
        assertThat(result.output).contains 'Recommending version 1.0.0 for dependency test.nebula:foo'
        assertThat(result.output).contains 'nebula.dependency-recommender uses mavenBom: test.nebula.bom:testbom:pom:1.0.0'
    }

    @Test
    void 'conflict resolved respects higher transitive'() {
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
        def generator = new GradleDependencyGenerator(graph, repoDir.absolutePath)
        generator.generateTestMavenRepo()

        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            properties {
                buildCache(true)
                configurationCache(true)
            }
            rootProject{
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}

dependencies {
    nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
    implementation 'test.nebula:foo'
    implementation 'test.nebula:bar:1.0.0'
}
""")
            }
        }

        BuildResult result = runner.run('dependencies')

        then:
        assertThat(result.output).contains 'test.nebula:foo -> 1.1.0'
    }

    @Test
    void 'conflict resolved respects higher recommendation'() {
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
        def generator = new GradleDependencyGenerator(graph, repoDir.absolutePath)
        generator.generateTestMavenRepo()

        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            properties {
                buildCache(true)
                configurationCache(true)
            }
            rootProject{
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}

dependencies {
    nebulaRecommenderBom 'test.nebula.bom:testbom:1.0.0@pom'
    implementation 'test.nebula:foo'
    implementation 'test.nebula:bar:1.0.0'
}
""")
            }
        }

        when:
        BuildResult result = runner.run('dependencies')//, '--configuration', 'compile')

        then:
        assertThat(result.output).contains 'test.nebula:foo -> 1.1.0'
        assertThat(result.output).contains '\\--- test.nebula:foo:1.0.0 -> 1.1.0'
    }

    @Test
    void 'can lock boms'() {
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
        def generator = new GradleDependencyGenerator(graph, repoDir.absolutePath)
        generator.generateTestMavenRepo()

        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            properties {
                buildCache(true)
                configurationCache(true)
            }
            rootProject{
                plugins {
                    java()
                    id("com.netflix.nebula.dependency-recommender")
                    id("com.netflix.nebula.dependency-lock").version("13.+")
                }
                rawBuildScript("""
repositories {
    maven { url = '${repo.root.absoluteFile.toURI()}' }
    ${generator.mavenRepositoryBlock}
}

dependencies {
    nebulaRecommenderBom 'test.nebula.bom:testbom:latest.release@pom'
    implementation 'test.nebula:foo'
}
""")
            }
        }

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


        BuildResult result = runner.run('dependencies', '-Dorg.gradle.configuration-cache=false')//, '--configuration', 'compile')

        assertThat(result.output).contains 'test.nebula:foo -> 1.0.0'
    }

    @Test
    void 'configures the maven-publish plugin to publish a BOM'() {
        final var runner = GroovyTestProjectBuilder.testProject(projectDir){
            properties {
                buildCache(true)
                configurationCache(false)
            }
            rootProject {
                plugins {
                    id("maven-publish")
                    id("com.netflix.nebula.dependency-recommender")
                }
                group("netflix")
                version("1")
                repositories {
                    mavenCentral()
                }
                rawBuildScript("""
configurations { implementation }
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
        maven { url = "${projectDir.toPath().resolve("build/repo").toFile().absolutePath}" }
    }
}
""")
            }
        }

        runner.run('publish')

        def pomText = new File(projectDir, "build/repo/netflix/module-parent/1/module-parent-1.pom").text

        // assert on overall shape
        assertThat(pomText).contains('''<project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0''')
        assertThat(pomText).contains('''
  <modelVersion>4.0.0</modelVersion>
  <groupId>netflix</groupId>
  <artifactId>module-parent</artifactId>
  <version>1</version>
  <packaging>pom</packaging>
  <dependencyManagement>
    <dependencies>
''')

        assertThat(pomText).contains('''
    </dependencies>
  </dependencyManagement>
  <description>A demonstration of maven POM customization</description>
</project>
''')

        // assert on dependency blocks, that are sometimes re-ordered
        assertThat(pomText.findAll('<dependency>')).hasSize(8)

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
                '''<dependency><groupId>commons-configuration</groupId><artifactId>commons-configuration</artifactId><version>1.6</version></dependency>''',
                '''<dependency><groupId>manual</groupId><artifactId>dep</artifactId><version>1</version></dependency>'''
        ]

        expectedDependencyBlocks.each {
            assertThat(pomText).containsIgnoringWhitespaces(it)
        }
    }
}
