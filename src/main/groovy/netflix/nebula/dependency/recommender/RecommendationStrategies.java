package netflix.nebula.dependency.recommender;

public enum RecommendationStrategies {
    ConflictResolved(RecommendationsConflictResolvedStrategy.class),
    OverrideTransitives(RecommendationsOverrideTransitivesStrategy.class);

    private Class<? extends RecommendationStrategy> strategyClass;

    RecommendationStrategies(Class<? extends RecommendationStrategy> strategyClass) {
        this.strategyClass = strategyClass;
    }

    public Class<? extends RecommendationStrategy> getStrategyClass() {
        return strategyClass;
    }
}
