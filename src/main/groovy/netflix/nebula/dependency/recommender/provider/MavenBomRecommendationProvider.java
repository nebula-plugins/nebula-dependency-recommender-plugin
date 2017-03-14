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

import com.netflix.nebula.dependencybase.DependencyManagement;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
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
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;

public class MavenBomRecommendationProvider extends ClasspathBasedRecommendationProvider {
    private Map<String, String> recommendations;
    private DependencyManagement insight;

    public MavenBomRecommendationProvider(Project project, String configName) {
        super(project, configName);
        this.insight = new DependencyManagement();
    }

    public MavenBomRecommendationProvider(Project project, String configName, DependencyManagement insight) {
        super(project, configName);
        this.insight = insight;
    }

    private class SimpleModelSource implements ModelSource {
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
    }

    @Override
    public String getVersion(String org, String name) throws Exception {
        if(recommendations == null) {
            recommendations = new HashMap<>();

            Set<File> recommendationFiles = getFilesOnConfiguration();
            for (File recommendation : recommendationFiles) {
                if (!recommendation.getName().endsWith("pom")) {
                    break;
                }

                DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();

                request.setModelResolver(new ModelResolver() {
                    @Override
                    public ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
                        String relativeUrl = "";
                        for (String groupIdPart : groupId.split("\\."))
                            relativeUrl += groupIdPart + "/";
                        relativeUrl += artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
                        try {
                            // try to find the parent pom in each maven repository specified in the gradle file
                            for (ArtifactRepository repo : project.getRepositories()) {
                                if (!(repo instanceof MavenArtifactRepository))
                                    continue;
                                URL url = new URL(((MavenArtifactRepository) repo).getUrl().toString() + "/" + relativeUrl);
                                try {
                                    return new SimpleModelSource(url.openStream());
                                } catch (IOException e) {
                                    // try the next repo
                                }
                            }
                        } catch (MalformedURLException e) {
                            throw new RuntimeException(e); // should never happen
                        }
                        return null;
                    }

                    @Override
                    public void addRepository(Repository repository) throws InvalidRepositoryException {
                        // do nothing
                    }

                    @Override
                    public ModelResolver newCopy() {
                        return this; // do nothing
                    }
                });

                request.setModelSource(new SimpleModelSource(new FileInputStream(recommendation)));

                DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
                modelBuilder.setModelInterpolator(new ProjectPropertiesModelInterpolator(project));

                ModelBuildingResult result = modelBuilder.build(request);
                insight.addPluginMessage("nebula.dependency-recommender uses mavenBom: " + result.getEffectiveModel().getId());
                for (Dependency d : result.getEffectiveModel().getDependencyManagement().getDependencies()) {
                    recommendations.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
                }
            }
        }
        return recommendations.get(org + ":" + name);
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
