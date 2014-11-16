package netflix.nebula.dependency.recommendations.provider

import spock.lang.Shared
import spock.lang.Specification

class FuzzyVersionResolverSpec extends Specification {
    @Shared Map recommendations

    def resolver = [
        'propertyNames': { recommendations.keySet() },
        'propertyValue': { name -> recommendations[name] }
    ] as FuzzyVersionResolver

    def 'resolve versions recursively'() {
        when:
        recommendations = [
            'GUAVA_VERSION': '18.0',
            'com.google.guava:guava' : '$GUAVA_VERSION',
            'some:other': '$com.google.guava:guava'
        ]

        then:
        resolver.versionOf('com.google.guava:guava') == '18.0'
        resolver.versionOf('some:other') == '18.0'
    }

    def 'resolve globbed versions'() {
        when:
        recommendations = ['com.sun.jersey:*': '1.23']

        then:
        resolver.versionOf('com.sun.jersey:jersey-core') == '1.23'
    }
}
