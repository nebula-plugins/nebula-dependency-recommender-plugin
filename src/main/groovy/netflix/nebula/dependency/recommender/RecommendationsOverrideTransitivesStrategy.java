package netflix.nebula.dependency.recommender;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.ModuleVersionSelector;

import java.util.ArrayList;
import java.util.List;

public class RecommendationsOverrideTransitivesStrategy extends RecommendationStrategy {

    private List<String> firstOrderDepsWithVersions = new ArrayList<>();

    @Override
    public void inspectDependency(Dependency dependency) {
        if (dependency.getVersion() != null && !dependency.getVersion().isEmpty()) {
            firstOrderDepsWithVersions.add(dependency.getGroup() + ":" + dependency.getName());
        }
    }

    @Override
    public boolean canRecommendVersion(ModuleVersionSelector selector) {
        String version = selector.getVersion();
        boolean versionMissing = version == null || version.isEmpty();
        return versionMissing || !firstOrderDepsWithVersions.contains(getCoord(selector));
    }

    @Override
    public boolean recommendVersion(DependencyResolveDetails details, String version) {
        if (version == null) {
            return false;
        }
        details.useVersion(version);
        return true;
    }
}
