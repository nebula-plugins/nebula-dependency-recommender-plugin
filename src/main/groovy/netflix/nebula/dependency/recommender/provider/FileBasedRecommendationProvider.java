package netflix.nebula.dependency.recommender.provider;

import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.internal.typeconversion.NotationConvertResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;

public abstract class FileBasedRecommendationProvider extends AbstractRecommendationProvider {
    protected Project project;

    /**
     * We only want to open input streams if a version is actually asked for
     */
    public static interface InputStreamProvider {
        InputStream getInputStream() throws Exception;
    }

    protected InputStreamProvider inputProvider = new InputStreamProvider() {
        @Override
        public InputStream getInputStream() throws Exception {
            throw new InvalidUserDataException("No recommender input source has been defined");
        }
    };

    protected FileBasedRecommendationProvider() { /* for mocks */ }

    public FileBasedRecommendationProvider(Project project) {
        this.project = project;
    }

    protected InputStream getInput() {
        try {
            return inputProvider.getInputStream();
        } catch (Exception e) {
            throw new InvalidUserDataException("Unable to open recommender input source", e);
        }
    }

    public InputStreamProvider setFile(final File f) {
        inputProvider = new InputStreamProvider() {
            @Override
            public InputStream getInputStream() throws Exception {
                return new FileInputStream(f);
            }
        };
        return inputProvider;
    }

    public InputStreamProvider setInputStream(final InputStream in) {
        inputProvider = new InputStreamProvider() {
            @Override
            public InputStream getInputStream() {
                return in;
            }
        };
        return inputProvider;
    }

    public InputStreamProvider setUri(final URI uri) {
        inputProvider = new InputStreamProvider() {
            @Override
            public InputStream getInputStream() throws Exception {
                return uri.toURL().openStream();
            }
        };
        return inputProvider;
    }

    public InputStreamProvider setUri(String uri) {
        return setUri(URI.create(uri));
    }

    public InputStreamProvider setUrl(final URL url) {
        inputProvider = new InputStreamProvider() {
            @Override
            public InputStream getInputStream() throws Exception {
                return url.openStream();
            }
        };
        return inputProvider;
    }

    public InputStreamProvider setUrl(final String url) {
        inputProvider = new InputStreamProvider() {
            @Override
            public InputStream getInputStream() throws Exception {
                return new URL(url).openStream();
            }
        };
        return inputProvider;
    }

    public InputStreamProvider setModule(final Object dependencyNotation) {
        inputProvider = new InputStreamProvider() {
            @Override
            public InputStream getInputStream() throws Exception {
                // create a temporary configuration to resolve the file
                Configuration conf = project.getConfigurations().detachedConfiguration(
                        project.getDependencies().create(dependencyNotation));
                return new FileInputStream(conf.resolve().iterator().next());
            }
        };
        return inputProvider;
    }

    private class ProviderNotationConvertResult implements NotationConvertResult<DefaultExternalModuleDependency> {
        ExternalModuleDependency dependency;

        @Override
        public boolean hasResult() {
            return dependency != null;
        }

        @Override
        public void converted(DefaultExternalModuleDependency result) {
            dependency = result;
        }
    }
}
