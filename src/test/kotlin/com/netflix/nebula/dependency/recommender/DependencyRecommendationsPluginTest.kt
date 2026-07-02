package com.netflix.nebula.dependency.recommender

import nebula.test.dsl.*
import nebula.test.dsl.TestKitAssertions.assertThat
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File

class DependencyRecommendationsPluginTest {

    @TempDir
    lateinit var projectDir: File

    @TempDir
    lateinit var repo: File

    @ParameterizedTest
    @EnumSource(SupportedGradleVersion::class)
    fun `test project property interpolation`(gradle: SupportedGradleVersion) {
        val sample = repo.resolve("sample/recommender/1.0")
        sample.mkdirs()
        val sampleFile = sample.resolve("recommender-1.0.pom")
        //language=xml
        sampleFile.writeText(
            """
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>sample</groupId>
  <artifactId>recommender</artifactId>
  <version>1.0</version>

  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>commons-logging</groupId>
        <artifactId>commons-logging</artifactId>
        <version>1.1.1</version>
      </dependency>
      <dependency>
        <groupId>commons-configuration</groupId>
        <artifactId>commons-configuration</artifactId>
        <version>${'$'}{commons.version}</version>
      </dependency>
    </dependencies>
  </dependencyManagement>
</project>
"""
        )

        val runner = testProject(projectDir, BuildscriptLanguage.GROOVY) {
            properties {
                buildCache(true)
                configurationCache(true)
            }
            rootProject {
                plugins {
                    id("java")
                    id("com.netflix.nebula.dependency-recommender")
                }
                repositories {
                    maven(repo.toURI().toURL().toExternalForm())
                    mavenCentral()
                }
                dependencies("""implementation("commons-configuration:commons-configuration")""")
                rawBuildScript(
                    """
dependencyRecommendations {
    mavenBom module: 'sample:recommender:1.0'
}
"""
                )
            }
        }
        val result = runner.run("dependencies", "-Pcommons.version=1.10") {
            forwardOutput()
            withGradle(gradle.version)
        }
        assertThat(result).hasNoDeprecationWarnings()
        assertThat(result.output)
            .contains("commons-configuration:commons-configuration -> 1.10")
    }
}