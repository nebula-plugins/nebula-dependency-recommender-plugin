package netflix.nebula.dependency.recommendations

import netflix.nebula.dependency.recommendations.provider.PropertyFileRecommendationProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class RecommendationProviderContainerSpec extends Specification {
    @Rule TemporaryFolder projectDir

    def 'properties file provider can be added'() {
        when:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: 'nebula-dependency-recommendations'

        def provider
        project.recommendationProvider {
            provider = propertiesFile name: 'test', file: projectDir.newFile()
        }

        then:
        provider.class == PropertyFileRecommendationProvider
    }
}
