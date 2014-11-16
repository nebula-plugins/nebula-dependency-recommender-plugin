package netflix.nebula.dependency.recommendations

import netflix.nebula.dependency.recommendations.provider.PropertyFileRecommendationProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class RecommendationProviderContainerSpec extends Specification {
    @Rule TemporaryFolder projectDir

    def 'version recommendations are given in the order that the providers are specified'() {
        setup:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: 'nebula-dependency-recommendations'

        project.repositories { mavenCentral() }

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
}
