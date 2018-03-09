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

import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.netflix.nebula.dependencybase.DependencyManagement;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

public class MavenBomRecommendationProvider extends ClasspathBasedRecommendationProvider {
    private Supplier<Map<String, String>> recommendations = Suppliers.memoize(new Supplier<Map<String, String>>() {
        @Override
        public Map<String, String> get() {
            try {
                return getMavenRecommendations();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    });
    private DependencyManagement insight;

    public MavenBomRecommendationProvider(Project project, String configName) {
        super(project, configName);
        this.insight = new DependencyManagement();
    }

    public MavenBomRecommendationProvider(Project project, String configName, DependencyManagement insight) {
        super(project, configName);
        this.insight = insight;
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
        return recommendations.get();
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
            insight.addPluginMessage("nebula.dependency-recommender uses mavenBom: " + result.getEffectiveModel().getId());
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
}
