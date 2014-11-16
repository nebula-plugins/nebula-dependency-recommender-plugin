package netflix.nebula.dependency.recommender

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class RecommendationProviderContainerSpec extends Specification {
    @Rule TemporaryFolder projectDir

    Project project

    def setup() {
        project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: 'nebula-dependency-recommender'

        project.repositories { mavenCentral() }
    }

    def 'version recommendations are given in the order that the providers are specified'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
            map recommendations: ['commons-logging:commons-logging': '1.2', 'com.google.guava:guava': '18.0']
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging'
            compile 'com.google.guava:guava'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.1', '18.0']
    }

    def 'transitive dependencies of providers are not calculated and therefore have no effect'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
            map recommendations: ['logkit:logkit': '2.0']
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging'
            compile 'logkit:logkit'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.1', '2.0']
    }

    def 'dependencies that already have versions are not overriden by providers'() {
        setup:
        project.dependencyRecommendations {
            map recommendations: ['commons-logging:commons-logging': '1.1']
        }

        when:
        project.dependencies {
            compile 'commons-logging:commons-logging:1.0'
        }

        then:
        project.configurations.compile.resolvedConfiguration.firstLevelModuleDependencies.collect { it.moduleVersion } == ['1.0']
    }
}
