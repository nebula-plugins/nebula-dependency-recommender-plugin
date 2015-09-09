package netflix.nebula.dependency.recommender.provider

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

class IvyRecommendationProviderSpec extends Specification {
    @Rule TemporaryFolder projectDir

    def 'recommendations are loaded from the dependencies section of an ivy file'() {
        setup:
        def project = ProjectBuilder.builder().build()
        project.apply plugin: 'java'
        project.apply plugin: 'nebula.dependency-recommender'

        def repo = projectDir.newFolder('repo')
        def sample = new File(repo, 'sample/recommender/1.0')
        sample.mkdirs()
        def sampleFile = new File(sample, 'recommender-1.0.ivy')
        sampleFile << '''<?xml version="1.0" encoding="UTF-8"?>
            <ivy-module version="2.0" xmlns:e="http://ant.apache.org/ivy/extra" xmlns:n="http://netflix.com/build">
              <info organisation="netflix" module="platform" revision="2.1287.0" status="candidate" publication="20150318224045">
                <n:manifest>
                  Implementation-Vendor: Netflix, Inc.
                  Status-Minimum: candidate
                  X-Compile-Target-JDK: 1.7
                  X-Compile-Source-JDK: 1.7
                </n:manifest>
              </info>
              <configurations>
                <conf name="compile" visibility="public"/>
                <conf name="default" visibility="public" extends="runtime,master"/>
                <conf name="dependencyReport" visibility="public"/>
                <conf name="javadoc" visibility="public"/>
              </configurations>
              <publications>
                <artifact name="platform" type="jar" ext="jar" conf="runtime"/>
              </publications>
              <dependencies defaultconfmapping="%-&gt;default">
                <dependency org="netflix" name="platform-ipc" rev="2.1287.0" conf="runtime-&gt;default"/>
              </dependencies>
            </ivy-module>
        '''

        project.repositories { ivy { url repo } }

        when:
        def recommendations = new IvyRecommendationProvider(project)
        recommendations.setModule('sample:recommender:1.0')

        then:
        recommendations.getVersion('netflix', 'platform-ipc') == '2.1287.0'
    }
}
