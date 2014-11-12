package netflix.nebula.dependency.recommendations.provider;

abstract class AbstractRecommendationProvider implements RecommendationProvider {
    protected String name;

    @Override
    public String getName() { return name; }

    @Override
    public void setName(String name) { this.name = name; }
}
