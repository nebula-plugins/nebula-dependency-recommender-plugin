package netflix.nebula.dependency.recommender.provider;

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
import org.apache.commons.codec.binary.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.*;
import org.gradle.api.artifacts.repositories.PasswordCredentials;

public class MavenBomRecommendationProvider extends ClasspathBasedRecommendationProvider {
    private Map<String, String> recommendations;

    public MavenBomRecommendationProvider(Project project, String configName) {
        super(project, configName);
    }

    private static class SimpleModelSource implements ModelSource {
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
                        String relativeUrl = buildRelativeUrl(groupId, artifactId, version);
                        List<String> repositoryErrors = new LinkedList<>();
                        try {
                            // try to find the parent pom in each maven repository specified in the gradle file
                            for (ArtifactRepository repo : project.getRepositories()) {
                                if (!(repo instanceof MavenArtifactRepository)) {
                                    continue;
                                }
                                URL url = new URL(((MavenArtifactRepository) repo).getUrl().toString() + "/" + relativeUrl);
                                try {
                                    return new SimpleModelSource(askRepository(url, (MavenArtifactRepository) repo));
                                } catch (IOException e) {
                                    // record the error information - it will be used to build the exception, if the dependency
                                    // is not found in any of the Maven repositories.
                                    repositoryErrors.add(((MavenArtifactRepository) repo).getUrl().toString() + ": " + e.getMessage());
                                }
                            }
                        } catch (MalformedURLException e) {
                            throw new RuntimeException(e); // should never happen
                        }
                        throw buildError(groupId, artifactId, version, repositoryErrors);
                    }

                    @Override
                    public void addRepository(Repository repository) throws InvalidRepositoryException {
                        // do nothing
                    }

                    @Override
                    public ModelResolver newCopy() {
                        return this; // do nothing
                    }

                    private String buildRelativeUrl(String groupId, String artifactId, String version) {
                        String relativeUrl = "";
                        for (String groupIdPart : groupId.split("\\.")) {
                            relativeUrl += groupIdPart + "/";
                        }
                        return relativeUrl + artifactId + "/" + version + "/" + artifactId + "-" + version + ".pom";
                    }

                    private InputStream askRepository(URL url, MavenArtifactRepository repository) throws IOException {
                        URLConnection conn = url.openConnection();
                        PasswordCredentials credentials = repository.getCredentials();
                        if (null != credentials.getUsername() && !"".equals(credentials.getUsername())) {
                            String authString = credentials.getUsername() + ":" + credentials.getPassword();
                            conn.setRequestProperty("Authorization", "Basic " + new String(Base64.encodeBase64(authString.getBytes())));
                        }
                        return conn.getInputStream();
                    }

                    private UnresolvableModelException buildError(String groupId, String artifactId, String version, List<String> repositoryErrors) {
                        String relativeUrl = buildRelativeUrl(groupId, artifactId, version);
                        String combinedErrorMessages = "";
                        for (String errMsg: repositoryErrors) {
                            combinedErrorMessages += errMsg + ",\n";
                        }
                        return new UnresolvableModelException("Unable to locate the artifact '" + relativeUrl + "' in the following repositories:\n" + combinedErrorMessages, groupId, artifactId, version);
                    }
                });
                request.setModelSource(new SimpleModelSource(new FileInputStream(recommendation)));

                DefaultModelBuilder modelBuilder = new DefaultModelBuilderFactory().newInstance();
                modelBuilder.setModelInterpolator(new ProjectPropertiesModelInterpolator(project));

                ModelBuildingResult result = modelBuilder.build(request);
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
