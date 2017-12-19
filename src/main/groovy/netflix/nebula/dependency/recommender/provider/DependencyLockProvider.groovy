package netflix.nebula.dependency.recommender.provider

import groovy.json.JsonSlurper
import org.gradle.api.Project

class DependencyLockProvider extends FileBasedRecommendationProvider {
    volatile Map<String, String> recommendations

    DependencyLockProvider() {}

    DependencyLockProvider(Project project) {
        super(project)
    }

    @Override
    String getVersion(String org, String name) throws Exception {
        
        Map<String, String> tmpResult = recommendations
        
        if (tmpResult == null) {
            synchronized (this) {
                tmpResult = recommendations
                if (tmpResult == null) {
                    input.withCloseable {
                        final locks = new JsonSlurper().parse(it)
                        final isDependencyLock4Format = locks.every {
                            it.value.every {
                                it.value instanceof Map
                            }
                        }
                        tmpResult = (isDependencyLock4Format ? locks.collectEntries { it.value } : locks).collectEntries {
                            [(it.key): it.value.locked]
                        }
                    }
                    recommendations = tmpResult
                }
            }
        }
        tmpResult[org + ':' + name]
    }
}
