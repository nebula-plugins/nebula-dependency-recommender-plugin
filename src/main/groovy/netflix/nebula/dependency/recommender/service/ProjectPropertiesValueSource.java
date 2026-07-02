package netflix.nebula.dependency.recommender.service;

import org.codehaus.plexus.interpolation.AbstractValueSource;
import org.gradle.api.provider.ProviderFactory;

public class ProjectPropertiesValueSource extends AbstractValueSource {
    public ProjectPropertiesValueSource(ProviderFactory providerFactory) {
        super(true);
        this.providerFactory = providerFactory;
    }

    private final ProviderFactory providerFactory;

    @Override
    public Object getValue(String expression) {
        return providerFactory.gradleProperty(expression).getOrNull();
    }
}
