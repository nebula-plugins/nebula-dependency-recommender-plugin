package netflix.nebula.dependency.recommender.provider

import spock.lang.Specification

class CustomRecommendationProviderSpec extends Specification {
    def 'can provide a closure that is wholly responsible for determining versions'() {
        when:
        def recommender = new CustomRecommendationProvider({ org, name -> '1.0' })

        then:
        recommender.getVersion('commons-logging', 'commons-logging') == '1.0'
    }
}
