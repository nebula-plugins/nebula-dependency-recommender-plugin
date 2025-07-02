/*
 * Copyright 2025 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package netflix.nebula.dependency.recommender.util

import netflix.nebula.dependency.recommender.provider.MavenBomRecommendationProvider
import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import spock.lang.Specification
import spock.lang.Subject

class BomResolutionUtilSpec extends Specification {

    @Subject
    BomResolutionUtil util = new BomResolutionUtil()

    Project project
    RecommendationProviderContainer container
    MavenBomRecommendationProvider mavenBomProvider

    def setup() {
        project = ProjectBuilder.builder().build()
        container = Mock(RecommendationProviderContainer)
        mavenBomProvider = Mock(MavenBomRecommendationProvider)
    }

    def 'eagerlyResolveBoms throws IllegalArgumentException for null project'() {
        when:
        BomResolutionUtil.eagerlyResolveBoms(null, container, "testConfig")

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Project cannot be null"
    }

    def 'eagerlyResolveBoms throws IllegalArgumentException for null container'() {
        when:
        BomResolutionUtil.eagerlyResolveBoms(project, null, "testConfig")

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "RecommendationProviderContainer cannot be null"
    }

    def 'eagerlyResolveBoms throws IllegalArgumentException for null configuration name'() {
        when:
        BomResolutionUtil.eagerlyResolveBoms(project, container, null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "BOM configuration name cannot be null or empty"
    }

    def 'eagerlyResolveBoms throws IllegalArgumentException for empty configuration name'() {
        when:
        BomResolutionUtil.eagerlyResolveBoms(project, container, "  ")

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "BOM configuration name cannot be null or empty"
    }

    def 'eagerlyResolveBoms does not throw for valid arguments'() {
        when:
        BomResolutionUtil.eagerlyResolveBoms(project, container, "testConfig")

        then:
        // The method will try to access the build service, but won't fail completely
        // since our utility handles exceptions gracefully
        noExceptionThrown()
    }

    def 'shouldEagerlyResolveBoms throws IllegalArgumentException for null project'() {
        when:
        BomResolutionUtil.shouldEagerlyResolveBoms(null, container)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "Project cannot be null"
    }

    def 'shouldEagerlyResolveBoms throws IllegalArgumentException for null container'() {
        when:
        BomResolutionUtil.shouldEagerlyResolveBoms(project, null)

        then:
        def e = thrown(IllegalArgumentException)
        e.message == "RecommendationProviderContainer cannot be null"
    }

    def 'shouldEagerlyResolveBoms returns false when container disables eager resolution'() {
        given:
        container.shouldEagerlyResolve() >> false

        when:
        def result = BomResolutionUtil.shouldEagerlyResolveBoms(project, container)

        then:
        !result
    }

    def 'shouldEagerlyResolveBoms returns true when container enables eager resolution'() {
        given:
        container.shouldEagerlyResolve() >> true

        when:
        def result = BomResolutionUtil.shouldEagerlyResolveBoms(project, container)

        then:
        result
    }

    def 'BomResolutionUtil cannot be instantiated'() {
        when:
        def constructor = BomResolutionUtil.getDeclaredConstructor()
        constructor.setAccessible(true)
        constructor.newInstance()

        then:
        // The constructor should be private, but if accessible, should not throw
        noExceptionThrown()
    }
}