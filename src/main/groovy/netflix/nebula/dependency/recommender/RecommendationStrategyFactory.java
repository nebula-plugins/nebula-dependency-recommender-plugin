package netflix.nebula.dependency.recommender;

import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.Project;

import java.util.Objects;

/**
 * Creates RecommendationStrategy lazily on demand and caches it.
 * This is used to allow for scoped recommendationStrategies (e.g. per configuration as in DependencyRecommendationsPlugin)
 */
public class RecommendationStrategyFactory {
    private final Project project;
    private RecommendationStrategy recommendationStrategy;

    public RecommendationStrategyFactory(Project project) {
        this.project = project;
    }
    
    public RecommendationStrategy getRecommendationStrategy() {
        if(recommendationStrategy == null) {
            try {
                RecommendationProviderContainer recommendationProviderContainer = project.getExtensions().findByType(RecommendationProviderContainer.class);
                recommendationStrategy = Objects.requireNonNull(recommendationProviderContainer).getStrategy().getStrategyClass().newInstance();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return recommendationStrategy;
    }
}
