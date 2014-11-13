package netflix.nebula.dependency.recommendations;

import netflix.nebula.dependency.recommendations.provider.RecommendationProvider;
import netflix.nebula.dependency.recommendations.provider.RecommendationProviderContainer;
import org.gradle.api.Action;
import org.gradle.api.plugins.DeferredConfigurable;

@DeferredConfigurable
public class DependencyRecommendationsExtension {
    private final RecommendationProviderContainer providers;
    private RecommendationProvider defaultProvider;

    public DependencyRecommendationsExtension() {
        this.providers = new RecommendationProviderContainer();
    }

    public RecommendationProviderContainer getProviders() { return providers; }

    public void providers(Action<? super RecommendationProviderContainer> configure) {
        configure.execute(providers);
    }

    public void setDefaultProvider(RecommendationProvider defaultProvider) {
        this.defaultProvider = defaultProvider;
    }
}
