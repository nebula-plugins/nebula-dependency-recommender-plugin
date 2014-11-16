package netflix.nebula.dependency.recommender

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class DependencyRecommendationsPluginSpec extends Specification {
    @Rule TemporaryFolder projectDir

    def 'applies recommendations to dependencies with no version'() {
        when:
        def project = ProjectBuilder.builder().build();
        project.apply plugin: 'java'
        project.apply plugin: 'nebula-dependency-recommender'

        def recommendations = projectDir.newFile()
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
}
