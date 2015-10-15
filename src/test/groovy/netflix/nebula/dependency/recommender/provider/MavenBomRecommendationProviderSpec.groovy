package netflix.nebula.dependency.recommender.provider

import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class MavenBomRecommendationProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir

    def 'recommendations are loaded from the dependencyManagement section of a BOM'() {
        setup:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        def repo = projectDir.newFolder('repo')
        def sample = new File(repo, 'sample/recommender/1.0')
        sample.mkdirs()
        def sampleFile = new File(sample, 'recommender-1.0.pom')
        sampleFile << '''
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
                    <version>${commons.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
        '''

        project.repositories { maven { url repo } }

        // demonstrates maven property interpolation from gradle project properties
        project.getExtensions().add("commons.version", "1.1.2")

        when:
        def recommendations = new MavenBomRecommendationProvider(project)
        recommendations.setModule('sample:recommender:1.0')

        then:
        recommendations.getVersion('commons-logging', 'commons-logging') == '1.1.1'
        recommendations.getVersion('commons-configuration', 'commons-configuration') == '1.1.2'
    }

    def 'bom files that specify a non-relative parent pom are resolvable'() {
        setup:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        def repo = projectDir.newFolder('repo')
        def sample = new File(repo, 'sample/recommender/1.1.1')
        sample.mkdirs()
        def sampleFile = new File(sample, 'recommender-1.1.1.pom')
        sampleFile << '''
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <artifactId>oss-parent</artifactId>
                <groupId>org.sonatype.oss</groupId>
                <version>7</version>
              </parent>
              <groupId>sample</groupId>
              <artifactId>recommender</artifactId>
              <version>1.1.1</version>

              <dependencyManagement>
                <dependencies>
                  <dependency>
                    <groupId>commons-logging</groupId>
                    <artifactId>commons-logging</artifactId>
                    <version>${project.version}</version>
                  </dependency>
                </dependencies>
              </dependencyManagement>
            </project>
        '''

        project.repositories {
            maven { url repo }
            mavenCentral()
        }

        when:
        def recommendations = new MavenBomRecommendationProvider(project)
        recommendations.setModule('sample:recommender:1.1.1')

        then:
        recommendations.getVersion('commons-logging', 'commons-logging') == '1.1.1'
    }
}
