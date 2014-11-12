package netflix.nebula.dependency.recommendations;

import netflix.nebula.dependency.recommendations.provider.PropertyFileRecommendationProvider;
import netflix.nebula.dependency.recommendations.provider.RecommendationProvider;
import org.gradle.api.Action;
import org.gradle.api.Namer;
import org.gradle.api.internal.ConfigureByMapAction;
import org.gradle.api.internal.DefaultNamedDomainObjectList;

import java.util.HashMap;
import java.util.Map;

public class RecommendationProviderExtension extends DefaultNamedDomainObjectList<RecommendationProvider> {
    private final Action<? super RecommendationProvider> addLastAction = new Action<RecommendationProvider>() {
        public void execute(RecommendationProvider r) {
            RecommendationProviderExtension.super.add(r);
        }
    };

    public RecommendationProviderExtension() {
        super(RecommendationProvider.class, null, new RecommendationProviderNamer());
    }

    private static class RecommendationProviderNamer implements Namer<RecommendationProvider> {
        public String determineName(RecommendationProvider r) {
            return r.getName();
        }
    }

    public <T extends RecommendationProvider> T addProvider(T provider, Action<? super T> configureAction) {
        configureAction.execute(provider);
        assertCanAdd(provider.getName());
        addLastAction.execute(provider);
        return provider;
    }

    public PropertyFileRecommendationProvider propertiesFile(Map<String, ?> args) {
        Map<String, Object> modifiedArgs = new HashMap<String, Object>(args);
        return addProvider(new PropertyFileRecommendationProvider(), new ConfigureByMapAction<PropertyFileRecommendationProvider>(modifiedArgs));
    }
}
