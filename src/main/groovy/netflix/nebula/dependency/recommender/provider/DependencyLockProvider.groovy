package netflix.nebula.dependency.recommender.provider

import groovy.json.JsonSlurper
import org.gradle.api.Project

class DependencyLockProvider extends FileBasedRecommendationProvider {
    Map<String, String> recommendations

    DependencyLockProvider() {}

    DependencyLockProvider(Project project) {
        super(project)
    }

    @Override
    String getVersion(String org, String name) throws Exception {
        if (!recommendations) {
            input.withCloseable {
                final Map<String, Map<String, String>> locks = (Map<String, Map<String, String>>) new JsonSlurper().parse(it)
                final boolean isDependencyLock4Format = locks.every {
                    it.value.every {
                        it.value instanceof Map
                    }
                }

                recommendations = (isDependencyLock4Format ? locks.collectEntries { it.value } : locks).collectEntries {
                    [(it.key): it.value["locked"]]
                } as Map<String, String>
            }
        }
        recommendations[org + ':' + name]
    }
}
