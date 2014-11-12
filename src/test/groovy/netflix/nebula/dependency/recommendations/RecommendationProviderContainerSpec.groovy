package netflix.nebula.dependency.recommendations

import netflix.nebula.dependency.recommendations.provider.PropertyFileRecommendationProvider
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification

class RecommendationProviderContainerSpec extends Specification {
    def 'properties file provider can be added'() {
        when:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: 'nebula-dependency-recommendations'

        project.recommendationProvider {
            propertiesFile name: 'test', file: new File('test.properties')
        }

        then:
        project.recommendationProvider[0].class == PropertyFileRecommendationProvider
    }
}
