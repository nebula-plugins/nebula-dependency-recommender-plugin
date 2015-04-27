package netflix.nebula.dependency.recommender.provider;

import org.gradle.api.Project;
import org.gradle.api.artifacts.repositories.ArtifactRepository;
import org.gradle.api.artifacts.repositories.MavenArtifactRepository;
import org.gradle.api.publish.maven.MavenArtifact;
import org.gradle.mvn3.org.apache.maven.model.Dependency;
import org.gradle.mvn3.org.apache.maven.model.Model;
import org.gradle.mvn3.org.apache.maven.model.Repository;
import org.gradle.mvn3.org.apache.maven.model.building.*;
import org.gradle.mvn3.org.apache.maven.model.interpolation.StringSearchModelInterpolator;
import org.gradle.mvn3.org.apache.maven.model.path.DefaultPathTranslator;
import org.gradle.mvn3.org.apache.maven.model.path.DefaultUrlNormalizer;
import org.gradle.mvn3.org.apache.maven.model.resolution.InvalidRepositoryException;
import org.gradle.mvn3.org.apache.maven.model.resolution.ModelResolver;
import org.gradle.mvn3.org.apache.maven.model.resolution.UnresolvableModelException;
import org.gradle.mvn3.org.codehaus.plexus.interpolation.MapBasedValueSource;
import org.gradle.mvn3.org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.gradle.mvn3.org.codehaus.plexus.interpolation.ValueSource;
import org.gradle.mvn3.org.sonatype.aether.util.DefaultRepositorySystemSession;
import org.gradle.mvn3.org.sonatype.aether.util.artifact.DefaultArtifactType;
import org.gradle.mvn3.org.sonatype.aether.util.artifact.DefaultArtifactTypeRegistry;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MavenBomRecommendationProvider extends FileBasedRecommendationProvider {
    private Map<String, String> recommendations;

    public MavenBomRecommendationProvider(Project project) {
        super(project);
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

            DefaultModelBuildingRequest request = new DefaultModelBuildingRequest();

            request.setModelResolver(new ModelResolver() {
                @Override
                public ModelSource resolveModel(String groupId, String artifactId, String version) throws UnresolvableModelException {
                    String relativeUrl = "";
                    for(String groupIdPart : groupId.split("\\."))
                        relativeUrl += groupIdPart + "/";
                    relativeUrl += artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
                    try {
                        // try to find the parent pom in each maven repository specified in the gradle file
                        for(ArtifactRepository repo : project.getRepositories()) {
                            if(!(repo instanceof MavenArtifactRepository))
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

            request.setModelSource(new SimpleModelSource(getInput()));

            DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
            modelBuilder.setModelInterpolator(new ProjectPropertiesModelInterpolator(project));

            ModelBuildingResult result = modelBuilder.build(request);
            for (Dependency d : result.getEffectiveModel().getDependencyManagement().getDependencies()) {
                recommendations.put(d.getGroupId() + ":" + d.getArtifactId(), d.getVersion());
            }
        }
        return recommendations.get(org + ":" + name);
    }

    @SuppressWarnings("unchecked")
    @Override
    public InputStreamProvider setModule(Object dependencyNotation) {
        if(dependencyNotation instanceof String && !((String) dependencyNotation).endsWith("@pom"))
            dependencyNotation = dependencyNotation + "@pom";
        if(dependencyNotation != null && Map.class.isAssignableFrom(dependencyNotation.getClass()))
            ((Map) dependencyNotation).put("ext", "pom");
        return super.setModule(dependencyNotation);
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
