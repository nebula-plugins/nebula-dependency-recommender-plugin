package netflix.nebula.dependency.recommendations.provider

import org.gradle.api.InvalidUserDataException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Unroll

class FileBasedRecommendationProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir
    @Shared FileBasedRecommendationProvider recommender = [:] as FileBasedRecommendationProvider
    File goodFile
    File badFile = new File('doesnotexist')

    def setup() {
        def project = ProjectBuilder.builder().build();
        project.apply plugin: 'java'
        project.apply plugin: 'nebula-dependency-recommendations'

        goodFile = projectDir.newFile()
        goodFile << 'test'
    }

    @Unroll
    def '#name results in an input stream'(String name, Closure setInput, Closure fromFile) {
        when:
        setInput(fromFile.call(goodFile))

        then:
        recommender.input.text == 'test'

        when:
        setInput(fromFile.call(badFile))
        recommender.input.text

        then:
        thrown(InvalidUserDataException)

        where:
        name                |   setInput               |   fromFile
        'setFile'           |   recommender.&setFile   |   { File f -> f }
        'setUrl(URL)'       |   recommender.&setUrl    |   { File f -> f.toURI().toURL() }
        'setUrl(String)'    |   recommender.&setUrl    |   { File f -> f.toURI().toURL().toString() }
        'setUri(URI)'       |   recommender.&setUri    |   { File f -> f.toURI() }
        'setUri(String)'    |   recommender.&setUri    |   { File f -> f.toURI().toString() }
    }
}
