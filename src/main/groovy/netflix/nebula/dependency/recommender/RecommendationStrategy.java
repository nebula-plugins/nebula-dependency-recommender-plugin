package netflix.nebula.dependency.recommender;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.ModuleVersionSelector;

/**
 * Defines in which cases recommendations are applied
 */
public abstract class RecommendationStrategy {
    
    /**
     * This hook is called for each dependency in a project. It collects the dependencies we are interested in for determining if a recommendation should be applied.
     * @param dependency the dependency to inspect.
     */
    public abstract void inspectDependency(Dependency dependency);

    /**
     * Puts the recommended version on details.useVersion depending on the strategy used.
     * @param details the details to recommend a version for
     * @param version the version to be potentially recommended for the requested artifact.
     */
    public abstract void recommendVersion(DependencyResolveDetails details, String version);

    /**
     * @param details the details to get coordinates from
     * @return the coordinates in the form of "<group>:<name>", taken from details.requested.
     */
    protected String getCoord(DependencyResolveDetails details) {
        ModuleVersionSelector requested = details.getRequested();
        return requested.getGroup() + ":" + requested.getName();
    }
}
