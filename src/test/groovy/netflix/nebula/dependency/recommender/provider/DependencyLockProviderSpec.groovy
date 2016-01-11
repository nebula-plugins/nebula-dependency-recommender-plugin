package netflix.nebula.dependency.recommender.provider

import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class DependencyLockProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir

    def 'v3 dependency locks provide recommendations'() {
        setup:
        def recommender = new DependencyLockProvider()

        def file = projectDir.newFile()
        file << '''
        {
          "commons-logging:commons-logging": { "locked": "1.1.1", "requested": "1.1.+" },
          "commons-configuration:commons-configuration": { "locked": "1.1.2" }
        }
        '''

        when:
        recommender.setFile(file)

        then:
        recommender.getVersion('commons-logging', 'commons-logging') == '1.1.1'
        recommender.getVersion('commons-configuration', 'commons-configuration') == '1.1.2'
    }

    def 'v4 dependency locks provide recommendations'() {
        setup:
        def recommender = new DependencyLockProvider()

        def file = projectDir.newFile()
        file << '''
        {
          "compile": {
            "commons-logging:commons-logging": { "locked": "1.1.1", "requested": "1.1.+" },
            "commons-configuration:commons-configuration": { "locked": "1.1.2" }
          }
        }
        '''

        when:
        recommender.setFile(file)

        then:
        recommender.getVersion('commons-logging', 'commons-logging') == '1.1.1'
        recommender.getVersion('commons-configuration', 'commons-configuration') == '1.1.2'
    }
}
