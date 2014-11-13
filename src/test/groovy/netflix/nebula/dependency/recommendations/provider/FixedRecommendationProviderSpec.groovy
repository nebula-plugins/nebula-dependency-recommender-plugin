package netflix.nebula.dependency.recommendations.provider

import org.gradle.api.InvalidUserDataException
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class FixedRecommendationProviderSpec extends Specification {
    FixedRecommendationProvider provider

    def setup() {
        provider = new FixedRecommendationProvider()
    }

    def 'attempting to access a recommendation when no map was provided throws exception'() {
        when:
        provider.getVersion('test', 'test')

        then:
        thrown(InvalidUserDataException)
    }

    def 'exact match recommendations are provided'() {
        when:
        provider.setMap('com.google.guava:guava': '18.0')

        then:
        provider.getVersion('com.google.guava', 'guava') == '18.0'
    }

    def 'value references are resolved'() {
        when:
        provider.setMap(
            'GUAVA_VERSION': '18.0',
            'com.google.guava:guava': '$GUAVA_VERSION',
            'some:other': '$com.google.guava:guava'
        )

        then:
        provider.getVersion('com.google.guava', 'guava') == '18.0'
        provider.getVersion('some', 'other') == '18.0'
    }

    def 'recommendations can be provided via a globbed coordinate'() {
        when:
        provider.setMap('com.sun.jersey:*': '1.23')

        then:
        provider.getVersion('com.sun.jersey', 'jersey-core') == '1.23'
    }
}
