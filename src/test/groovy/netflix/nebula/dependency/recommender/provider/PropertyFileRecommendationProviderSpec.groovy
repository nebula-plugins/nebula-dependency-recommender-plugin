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
        provider = new PropertyFileRecommendationProvider(project)
    }

    def 'exact match recommendations are provided'() {
        when:
        propFile << '''
            com.google.guava:guava = 18.0
            notProjectName@com.google.guava:guava = 11.0
            projectName@com.google.guava:guava = 99.0
        '''
        provider.setFile(propFile)

        then:
        provider.getVersion('', 'com.google.guava', 'guava') == '18.0'
    }

    def 'per-project exact match recommendations are provided'() {
        when:
        project = ProjectBuilder.builder().withName('projectName').build()
        provider = new PropertyFileRecommendationProvider(project)

        propFile << '''
            com.google.guava:guava = 18.0
            notProjectName@com.google.guava:guava = 11.0
            projectName@com.google.guava:guava = 99.0
        '''
        provider.setFile(propFile)

        then:
        provider.getVersion(project.name, 'com.google.guava', 'guava') == '99.0'
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
        provider.getVersion('', 'com.google.guava', 'guava') == '18.0'
        provider.getVersion('', 'some', 'other') == '18.0'
    }

    def 'per-project value references are resolved'() {
        when:
        project = ProjectBuilder.builder().withName('projectName').build()
        provider = new PropertyFileRecommendationProvider(project)

        propFile << '''
            GUAVA_VERSION = 18.0
            com.google.guava:guava = $GUAVA_VERSION
            some:other = $com.google.guava:guava
            notProjectName@GUAVA_VERSION = 11.0
            projectName@GUAVA_VERSION = 99.0
            projectName@com.google.guava:guava = $GUAVA_VERSION
            projectName@some:other = $com.google.guava:guava
        '''
        provider.setFile(propFile)

        then:
        provider.getVersion(project.name, 'com.google.guava', 'guava') == '99.0'
        provider.getVersion(project.name, 'some', 'other') == '99.0'
    }

    def 'recommendations can be provided via a globbed coordinate'() {
        when:
        propFile << '''
            com.sun.jersey:* = 1.23
            projectName@com.sun.jersey:* = 4.56
            notProjectName@com.sun.jersey:* = 9.99
        '''
        provider.setFile(propFile)

        then:
        provider.getVersion('', 'com.sun.jersey', 'jersey-core') == '1.23'
    }

    def 'per-project recommendations can be provided via a globbed coordinate'() {
        when:
        project = ProjectBuilder.builder().withName('projectName').build()
        provider = new PropertyFileRecommendationProvider(project)

        propFile << '''
            com.sun.jersey:* = 1.23
            projectName@com.sun.jersey:* = 4.56
            notProjectName@com.sun.jersey:* = 9.99
        '''
        provider.setFile(propFile)

        then:
        provider.getVersion(project.name, 'com.sun.jersey', 'jersey-core') == '4.56'
    }
}
