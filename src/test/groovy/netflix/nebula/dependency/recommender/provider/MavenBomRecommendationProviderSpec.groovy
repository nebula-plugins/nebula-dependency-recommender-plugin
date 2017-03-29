package netflix.nebula.dependency.recommender.provider

import netflix.nebula.dependency.recommender.DependencyRecommendationsPlugin
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class MavenBomRecommendationProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir
    static def version = 1.0

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

        project.dependencyRecommendations {
            mavenBom module: module
        }

        when:
        def recommendations = new MavenBomRecommendationProvider(project, 'nebulaRecommenderBom')

        then:
        recommendations.getVersion('commons-logging', 'commons-logging') == '1.1.1'
        recommendations.getVersion('commons-configuration', 'commons-configuration') == '1.1.2'

        where:
        module << [
            'sample:recommender:1.0',
            "sample:recommender:$version", // verify GString doesn't cause issues
            'sample:recommender:1.0@pom',
            "sample:recommender:$version@pom"
        ]
    }

    def 'bom files that specify a non-relative parent pom are resolvable'() {
        setup:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        def repo = projectDir.newFolder('repo')

        def parent = new File(repo, 'sample/recommender-parent/1.0')
        parent.mkdirs()
        new File(parent, 'recommender-parent-1.0.pom') << '''
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <groupId>sample</groupId>
              <artifactId>recommender-parent</artifactId>
              <version>1.0</version>
              <packaging>pom</packaging>
            </project>
        '''

        def sample = new File(repo, 'sample/recommender/1.1.1')
        sample.mkdirs()
        def samplePom = new File(sample, 'recommender-1.1.1.pom')
        samplePom << '''
            <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                 xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
              <modelVersion>4.0.0</modelVersion>
              <parent>
                <artifactId>recommender-parent</artifactId>
                <groupId>sample</groupId>
                <version>1.0</version>
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
        }

        project.dependencies {
            nebulaRecommenderBom 'sample:recommender:1.1.1@pom'
        }

        when:
        def recommendations = new MavenBomRecommendationProvider(project, 'nebulaRecommenderBom')

        then:
        recommendations.getVersion('commons-logging', 'commons-logging') == '1.1.1'
    }

    def 'spring boot/cloud BOMs'() {
        setup:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: DependencyRecommendationsPlugin

        project.repositories {
            jcenter()
        }

        project.dependencies {
            nebulaRecommenderBom 'org.springframework.boot:spring-boot-starter-parent:1.5.2.RELEASE@pom'
            nebulaRecommenderBom 'org.springframework.cloud:spring-cloud-netflix:1.2.6.RELEASE@pom'

            compile 'org.springframework.boot:spring-boot-starter-websocket'
            compile 'org.springframework.cloud:spring-cloud-starter-feign'
        }

        project.configurations.compile.resolve()
    }
}
