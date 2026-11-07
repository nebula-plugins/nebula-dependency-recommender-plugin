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
import netflix.nebula.dependency.recommender.util.IsolatedProjectsSupport;
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
        this.bomResolverService = IsolatedProjectsSupport.registerBomResolver(project);
    }

    /**
     * Retrieves cached BOM recommendations using the service selected for this project.
     * 
     * <p>This method delegates to the build service to get cached BOM recommendations,
     * avoiding direct configuration resolution that could cause lock conflicts in
     * parallel builds. The build service ensures that all BOM resolution happens
     * during the configuration phase when exclusive locks are available.</p>
     *
     * <p>{@link IsolatedProjectsSupport#registerBomResolver(Project)} selects the
     * legacy shared service by default, or the project-owned service when opted in.
     * Manual eager-resolution adapters use that same selection so they populate the
     * cache this provider reads. The flag does not change when this provider requests
     * recommendations or when users may customize BOM declarations before resolution.</p>
     * 
     * @param reasons a mutable set that will be populated with reasons explaining
     *                why specific recommendations were applied
     * @return a map of dependency coordinates (groupId:artifactId) to recommended versions
     * @throws RuntimeException if the build service is unavailable or the BOM was not cached eagerly
     */
    protected Map<String, String> getBomRecommendations(Set<String> reasons) {
        return bomResolverService.get().getRecommendations(project, configName, reasons);
    }
}
