/*
 * Copyright 2016 Netflix, Inc.
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
package netflix.nebula.dependency.recommender.provider;

import netflix.nebula.dependency.recommender.service.BomResolverService;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.provider.Provider;

import java.io.File;
import java.util.Map;
import java.util.Set;

public abstract class ClasspathBasedRecommendationProvider extends AbstractRecommendationProvider {
    protected Project project;
    protected Configuration configuration;
    protected String configName;
    protected Provider<BomResolverService> bomResolverService;

    ClasspathBasedRecommendationProvider(Project project, String configName) {
        this.project = project;
        this.configName = configName;
        this.configuration = project.getConfigurations().getByName(configName);
        this.bomResolverService = project.getGradle().getSharedServices().registerIfAbsent(
            "bomResolver", BomResolverService.class, spec -> {}
        );
    }

    /**
     * Retrieves BOM recommendations using the shared {@link BomResolverService}.
     * 
     * <p>This method delegates to the build service to get cached BOM recommendations,
     * avoiding direct configuration resolution that could cause lock conflicts in
     * parallel builds. The build service ensures that all BOM resolution happens
     * during the configuration phase when exclusive locks are available.</p>
     * 
     * @param reasons a mutable set that will be populated with reasons explaining
     *                why specific recommendations were applied
     * @return a map of dependency coordinates (groupId:artifactId) to recommended versions
     * @throws RuntimeException if the build service is not available or BOM resolution fails
     */
    protected Map<String, String> getBomRecommendations(Set<String> reasons) {
        return bomResolverService.get().getRecommendations(project, configName, reasons);
    }
}
