package netflix.nebula.dependency.recommender.provider;

public abstract class AbstractRecommendationProvider implements RecommendationProvider {
    protected String name;

    static int providersWithoutNames = 0;

    @Override
    public String getName() {
        return name == null ? "recommender-" + (++providersWithoutNames) : name;
    }

    @Override
    public void setName(String name) { this.name = name; }

    @Override
    public String getVersion(String projectName, String org, String name) throws Exception {
        // default version ignores project name
        return getVersion(org, name);
    }
}
