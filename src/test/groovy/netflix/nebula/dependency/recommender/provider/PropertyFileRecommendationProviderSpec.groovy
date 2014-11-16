package netflix.nebula.dependency.recommender.provider

import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class PropertyFileRecommendationProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir

    Project project
    PropertyFileRecommendationProvider provider
    File propFile

    def setup() {
        project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        propFile = projectDir.newFile('recommender.properties')
        provider = new PropertyFileRecommendationProvider(null)
    }

    def 'exact match recommendations are provided'() {
        when:
        propFile << 'com.google.guava:guava = 18.0'
        provider.setFile(propFile)

        then:
        provider.getVersion('com.google.guava', 'guava') == '18.0'
    }

    def 'value references are resolved'() {
        when:
        propFile << '''
            GUAVA_VERSION = 18.0
            com.google.guava:guava = $GUAVA_VERSION
            some:other = $com.google.guava:guava
        '''
        provider.setFile(propFile)

        then:
        provider.getVersion('com.google.guava', 'guava') == '18.0'
        provider.getVersion('some', 'other') == '18.0'
    }

    def 'recommendations can be provided via a globbed coordinate'() {
        when:
        propFile << 'com.sun.jersey:* = 1.23'
        provider.setFile(propFile)

        then:
        provider.getVersion('com.sun.jersey', 'jersey-core') == '1.23'
    }
}
