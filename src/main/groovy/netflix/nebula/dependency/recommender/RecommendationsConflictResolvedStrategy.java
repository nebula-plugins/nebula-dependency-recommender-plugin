package netflix.nebula.dependency.recommender;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.ModuleVersionSelector;

/**
 * Recommends versions for all dependencies that do not specify a version, allowing conflict resolution against transitive dependencies.
 */
public class RecommendationsConflictResolvedStrategy extends RecommendationStrategy {
    @Override
    public void inspectDependency(Dependency dependency) {
    }

    @Override
    public boolean canRecommendVersion(ModuleVersionSelector selector) {
        String version = selector.getVersion();
        return version == null || version.isEmpty();
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
