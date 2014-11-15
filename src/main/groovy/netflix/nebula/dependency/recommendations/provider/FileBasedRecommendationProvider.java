package netflix.nebula.dependency.recommendations.provider;

import netflix.nebula.dependency.recommendations.parser.DependencyMapNotationParser;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ExternalDependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency;
import org.gradle.api.internal.notations.DependencyStringNotationParser;
import org.gradle.internal.reflect.DirectInstantiator;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.typeconversion.NotationConvertResult;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.UUID;

public abstract class FileBasedRecommendationProvider extends AbstractRecommendationProvider {
    private final Instantiator instantiator = new DirectInstantiator();

    private final DependencyStringNotationParser<DefaultExternalModuleDependency> moduleStringParser =
            new DependencyStringNotationParser<>(instantiator, DefaultExternalModuleDependency.class);
    private final DependencyMapNotationParser<DefaultExternalModuleDependency> moduleMapParser =
            new DependencyMapNotationParser<>(instantiator, DefaultExternalModuleDependency.class);

    private Project project;

    /**
     * We only want to open input streams if a version is actually asked for
     */
    public static interface InputStreamProvider {
        InputStream getInputStream() throws Exception;
    }

    protected InputStreamProvider inputProvider = new InputStreamProvider() {
        @Override
        public InputStream getInputStream() throws Exception {
            throw new InvalidUserDataException("No recommendations input source has been defined");
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
            throw new InvalidUserDataException("Unable to open recommendations input source", e);
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

    public InputStreamProvider setModule(String moduleIdentifier) {
        ProviderNotationConvertResult result = new ProviderNotationConvertResult();
        moduleStringParser.convert(moduleIdentifier, result);
        if(!result.hasResult())
            throw new InvalidUserDataException("Cannot construct a dependency from string '" + moduleIdentifier + "'");
        return streamFromExternalDependency(result.dependency);
    }

    public InputStreamProvider setModule(Map moduleIdentifier) {
        return streamFromExternalDependency(moduleMapParser.parseNotation(moduleIdentifier));
    }

    private InputStreamProvider streamFromExternalDependency(final ExternalDependency dep) {
        inputProvider = new InputStreamProvider() {
            @Override
            public InputStream getInputStream() throws Exception {
                Configuration conf = project.getConfigurations().create("recommendations" + UUID.randomUUID());
                conf.getDependencies().add(dep);
                if(!conf.getArtifacts().isEmpty())
                    return new FileInputStream(conf.getArtifacts().getFiles().getSingleFile());
                throw new InvalidUserDataException("Unable to resolve " + dep);
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
