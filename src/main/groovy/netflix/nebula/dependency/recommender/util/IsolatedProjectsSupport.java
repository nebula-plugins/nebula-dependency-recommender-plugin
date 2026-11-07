/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package netflix.nebula.dependency.recommender.util;

import netflix.nebula.dependency.recommender.service.BomResolverService;
import netflix.nebula.dependency.recommender.service.ProjectOwnedBomResolverService;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;

/** Internal rollout gate for experimental Isolated Projects resolution changes. */
public final class IsolatedProjectsSupport {
    private static final String PROPERTY = "nebula.dependency-recommender.isolatedProjectsSupport";

    private IsolatedProjectsSupport() {}

    public static boolean isEnabled(Project project) {
        return project.getProviders().gradleProperty(PROPERTY).map(Boolean::parseBoolean).getOrElse(false);
    }

    public static Provider<BomResolverService> registerBomResolver(Project project) {
        if (isEnabled(project)) {
            return project.getGradle().getSharedServices().registerIfAbsent(
                    "bomResolver" + project.getPath(), ProjectOwnedBomResolverService.class, spec -> {}
            ).map(service -> service);
        }
        return project.getGradle().getSharedServices().registerIfAbsent(
                "bomResolver", BomResolverService.class, spec -> {}
        );
    }
}
