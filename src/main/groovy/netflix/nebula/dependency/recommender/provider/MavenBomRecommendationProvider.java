/*
 * Copyright 2014-2017 Netflix, Inc.
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
package netflix.nebula.dependency.recommender.provider;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.*;
import org.apache.maven.model.interpolation.StringSearchModelInterpolator;
import org.apache.maven.model.path.DefaultPathTranslator;
import org.apache.maven.model.path.DefaultUrlNormalizer;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.ValueSource;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.util.GradleVersion;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

public class MavenBomRecommendationProvider extends ClasspathBasedRecommendationProvider {
    private volatile Map<String, String> recommendations = null;
    private Set<String> reasons = new HashSet<>();
    private static final GradleVersion GRADLE_9_0 = GradleVersion.version("9.0.0");

    public MavenBomRecommendationProvider(Project project, String configName) {
        super(project, configName);
    }

    public MavenBomRecommendationProvider(Project project, String configName, Set<String> reasons) {
        super(project, configName);
        this.reasons = reasons;
    }

    private static class SimpleModelSource implements ModelSource2 {
        InputStream in;

        public SimpleModelSource(InputStream in) {
            this.in = in;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return in;
        }

        @Override
        public String getLocation() {
            return null;
        }

        @Override
        public ModelSource2 getRelatedSource(String relPath) {
            return null;
        }

        @Override
        public URI getLocationURI() {
            return null;
        }
    }

    @Override
    public String getVersion(String org, String name) throws Exception {
        return getRecommendations().get(org + ":" + name);
    }

    public Map<String, String> getRecommendations() {
        if (recommendations == null) {
            try {
                // Try to get cached recommendations from build service if using Gradle 9+ or flag is enabled
                if (shouldUseBuildService()) {
                    recommendations = getBomRecommendations(reasons);
                } else {
                    // Fallback to original implementation for older Gradle versions
                    recommendations = getMavenRecommendations();
                }
            } catch (Exception e) {
                // Fallback to original implementation if build service fails
                try {
                    recommendations = getMavenRecommendations();
                } catch (Exception fallbackException) {
                    // If both approaches fail, return empty map to avoid failures
                    recommendations = new HashMap<>();
                }
            }
        }
        return recommendations;
    }

    public Map<String, String> getMavenRecommendations() throws Exception {
        Map<String, String> recommendations = new HashMap<>();

        Set<File> recommendationFiles = getFilesOnConfiguration();
        for (File recommendation : recommendationFiles) {
            if (!recommendation.getName().endsWith("pom")) {
                break;
            }

            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();

            request.setModelResolver(new ModelResolver() {
                @Override
                public ModelSource2 resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
                    String notation = groupId + ":" + artifactId + ":" + version + "@pom";
                    org.gradle.api.artifacts.Dependency dependency = project.getDependencies().create(notation);
                    Configuration configuration = project.getConfigurations().detachedConfiguration(dependency);
                    try {
                        File file = configuration.getFiles().iterator().next();
                        return new SimpleModelSource(new FileInputStream(file));
                    } catch (Exception e) {
                        throw new UnresolvableModelException(e, groupId, artifactId, version);
                    }
                }

                @Override
                public ModelSource2 resolveModel(Dependency dependency) throws UnresolvableModelException {
                    return resolveModel(dependency.getGroupId(), dependency.getArtifactId(), dependency.getVersion());
                }

                @Override
                public ModelSource2 resolveModel(Parent parent) throws UnresolvableModelException {
                    return resolveModel(parent.getGroupId(), parent.getArtifactId(), parent.getVersion());
                }

                @Override
                public void addRepository(Repository repository) throws InvalidRepositoryException {
                    // do nothing
                }

                @Override
                public void addRepository(Repository repository, boolean bool) throws InvalidRepositoryException {
                    // do nothing
                }

                @Override
                public ModelResolver newCopy() {
                    return this; // do nothing
                }
            });
            request.setModelSource(new SimpleModelSource(new FileInputStream(recommendation)));
            request.setSystemProperties(System.getProperties());

            DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
            modelBuilder.setModelInterpolator(new ProjectPropertiesModelInterpolator(project));

            ModelBuildingResult result = modelBuilder.build(request);
            reasons.add("nebula.dependency-recommender uses mavenBom: " + result.getEffectiveModel().getId());

            Model model = result.getEffectiveModel();
            if (model == null) {
                break;
            }
            org.apache.maven.model.DependencyManagement dependencyManagement = model.getDependencyManagement();
            if (dependencyManagement == null) {
                break;
            }
            for (Dependency d : dependencyManagement.getDependencies()) {
                recommendations.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
            }
        }
        return recommendations;
    }

    private static class ProjectPropertiesModelInterpolator extends StringSearchModelInterpolator {
        private final Project project;

        ProjectPropertiesModelInterpolator(Project project) {
            this.project = project;
            setUrlNormalizer(new DefaultUrlNormalizer());
            setPathTranslator(new DefaultPathTranslator());
        }

        public List<ValueSource> createValueSources(Model model, File projectDir, ModelBuildingRequest request, ModelProblemCollector collector) {
            List<ValueSource> sources = new ArrayList<>();
            sources.addAll(super.createValueSources(model, projectDir, request, collector));
            sources.add(new PropertiesBasedValueSource(System.getProperties()));
            sources.add(new MapBasedValueSource(project.getProperties()));
            return sources;
        }
    }
    
    /**
     * Determines whether to use the BomResolverService (build service) approach.
     * 
     * <p>The build service is used when:</p>
     * <ul>
     *   <li>Gradle version is 9.0 or higher, OR</li>
     *   <li>The gradle property 'nebula.dependency-recommender.useBuildService' is set to true</li>
     * </ul>
     * 
     * @return true if build service should be used, false otherwise
     */
    private boolean shouldUseBuildService() {
        // Check if explicitly enabled via gradle property
        if (project.hasProperty("nebula.dependency-recommender.useBuildService")) {
            Object property = project.property("nebula.dependency-recommender.useBuildService");
            if (Boolean.parseBoolean(property.toString())) {
                return true;
            }
        }
        
        // Default behavior: use build service for Gradle 9+
        GradleVersion currentVersion = GradleVersion.current();
        return currentVersion.compareTo(GRADLE_9_0) >= 0;
    }
}
