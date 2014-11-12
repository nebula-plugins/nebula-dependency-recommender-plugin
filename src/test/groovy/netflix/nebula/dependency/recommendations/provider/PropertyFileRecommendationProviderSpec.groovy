package netflix.nebula.dependency.recommendations.provider

import org.gradle.api.InvalidUserDataException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class PropertyFileRecommendationProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir

    PropertyFileRecommendationProvider provider
    File propFile

    def setup() {
        propFile = projectDir.newFile('recommendations.properties')
        provider = new PropertyFileRecommendationProvider()
    }

    def 'attempting to load from a file that does not exist throws exception'() {
        when:
        new PropertyFileRecommendationProvider(file: new File('doesnotexist.properties'))

        then:
        thrown(InvalidUserDataException)
    }

    def 'attempting to access a recommendation when no property file was provided throws exception'() {
        when:
        provider.getVersion('test', 'test')

        then:
        thrown(InvalidUserDataException)
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
