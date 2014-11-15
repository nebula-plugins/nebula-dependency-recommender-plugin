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
    @Shared def recommender = [:] as FileBasedRecommendationProvider

    @Unroll
    def '#name results in an input stream'(String name, Closure setInput, Closure fromFile) {
        setup:
        def goodFile = projectDir.newFile(); goodFile << 'test'
        def badFile = new File('doesnotexist')

        when:
        setInput(fromFile.call(goodFile))

        then:
        recommender.input.text == 'test'

        when:
        setInput(fromFile.call(badFile))
        recommender.input.text

        then:
        Exception e = thrown()
        e instanceof InvalidUserDataException || e instanceof FileNotFoundException

        where:
        name                |   setInput                    |   fromFile
        'setFile'           |   recommender.&setFile        |   { File f -> f }
        'setUrl(URL)'       |   recommender.&setUrl         |   { File f -> f.toURI().toURL() }
        'setUrl(String)'    |   recommender.&setUrl         |   { File f -> f.toURI().toURL().toString() }
        'setUri(URI)'       |   recommender.&setUri         |   { File f -> f.toURI() }
        'setUri(String)'    |   recommender.&setUri         |   { File f -> f.toURI().toString() }
        'setInputStream'    |   recommender.&setInputStream |   { File f -> new FileInputStream(f) }
    }

    def 'module definition results in an input stream'() {
        setup:
        def project = ProjectBuilder.builder().build();
        project.apply plugin: 'java'
        project.apply plugin: 'nebula-dependency-recommendations'

        def repo = projectDir.newFolder('repo')

        def sample = new File(repo, 'sample/recommendations/1.0')
        sample.mkdirs()

        def sampleFile = new File(sample, 'recommendations-1.0.txt')
        sampleFile << 'test'

        project.repositories { maven { url repo } }
        recommender.project = project

        when:
        recommender.setModule('sample:recommendations:1.0@txt')

        then:
        recommender.input.text == 'test'
    }
}
