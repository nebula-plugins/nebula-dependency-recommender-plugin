package netflix.nebula.dependency.recommender

import nebula.test.IntegrationSpec
import org.gradle.testfixtures.ProjectBuilder
import org.xmlunit.builder.DiffBuilder
import org.xmlunit.builder.Input

class DependencyRecommendationsPluginSpec extends IntegrationSpec  {
    def 'applies recommendations to dependencies with no version'() {
        when:
        def project = ProjectBuilder.builder().build();
        project.apply plugin: 'java'
        project.apply plugin: 'nebula.dependency-recommender'

        def recommendations = createFile('recommendations.properties')
        recommendations << 'com.google.guava:guava = 18.0'

        project.dependencyRecommendations {
            propertiesFile name: 'props', file: recommendations
        }

        project.repositories {
            mavenCentral()
        }

        project.dependencies {
            compile 'com.google.guava:guava'
        }

        def resolved = project.configurations.compile.incoming.resolutionResult
                .allComponents.collect { it.moduleVersion }

        then:
        resolved.size() == 2 // the first one is :test:unspecified
        resolved[1].name == 'guava'
        resolved[1].version == '18.0'
    }

    def 'configures the maven-publish plugin to publish a BOM'() {
        when:
        buildFile << '''
            apply plugin: 'maven-publish'
            apply plugin: 'nebula.dependency-recommender'

            group = 'netflix'
            version = '1'

            configurations { compile }

            repositories { jcenter() }

            dependencies {
                compile 'commons-configuration:commons-configuration:1.6'
            }

            publishing {
                publications {
                    parent(MavenPublication) {
                        dependencyManagement.fromConfigurations { configurations.compile }
                        dependencyManagement.withDependencies { 'manual:dep:1' }

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
        println pomText


        // Looks like the order of these dependencies differs from Java 7 to 8. We'll need to change this assertion when we switch to Java 8
        def diff = DiffBuilder
                .compare(Input.fromString(pomText))
                .withTest(Input.fromString('''\
                    <project xmlns="http://maven.apache.org/POM/4.0.0" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                      <modelVersion>4.0.0</modelVersion>
                      <groupId>netflix</groupId>
                      <artifactId>module-parent</artifactId>
                      <version>1</version>
                      <packaging>pom</packaging>
                      <dependencyManagement>
                        <dependencies>
                          <dependency>
                            <groupId>commons-digester</groupId>
                            <artifactId>commons-digester</artifactId>
                            <version>1.8</version>
                          </dependency>
                          <dependency>
                            <groupId>commons-logging</groupId>
                            <artifactId>commons-logging</artifactId>
                            <version>1.1.1</version>
                          </dependency>
                          <dependency>
                            <groupId>commons-lang</groupId>
                            <artifactId>commons-lang</artifactId>
                            <version>2.4</version>
                          </dependency>
                          <dependency>
                            <groupId>commons-configuration</groupId>
                            <artifactId>commons-configuration</artifactId>
                            <version>1.6</version>
                          </dependency>
                          <dependency>
                            <groupId>commons-beanutils</groupId>
                            <artifactId>commons-beanutils</artifactId>
                            <version>1.7.0</version>
                          </dependency>
                          <dependency>
                            <groupId>commons-collections</groupId>
                            <artifactId>commons-collections</artifactId>
                            <version>3.2.1</version>
                          </dependency>
                          <dependency>
                            <groupId>commons-beanutils</groupId>
                            <artifactId>commons-beanutils-core</artifactId>
                            <version>1.8.0</version>
                          </dependency>
                          <dependency>
                            <groupId>manual</groupId>
                            <artifactId>dep</artifactId>
                            <version>1</version>
                          </dependency>
                        </dependencies>
                      </dependencyManagement>
                      <description>A demonstration of maven POM customization</description>
                    </project>
                '''))
                .ignoreWhitespace()
                .build()

        then:
        !diff.hasDifferences()
    }
}
