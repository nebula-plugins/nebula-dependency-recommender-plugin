package netflix.nebula.dependency.recommender;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.ModuleVersionSelector;

import java.util.ArrayList;
import java.util.List;

/**
 * Applies policy to transitive requests while retaining explicitly versioned declarations.
 *
 * <p>Inspection records {@code group:name} labels in this strategy instance. A versioned
 * selector matching one of those labels keeps its declared version; a versionless selector
 * remains eligible for a recommendation. The plugin creates one strategy per resolved
 * configuration and checks forces before invoking it.</p>
 *
 * <p>With experimental Isolated Projects support enabled, the built-in strategy receives
 * producer coordinates through local metadata inspection. Otherwise the plugin retains
 * recursive declaration inspection. Both paths use the same recommendation predicate.</p>
 */
public class RecommendationsOverrideTransitivesStrategy extends RecommendationStrategy {

    private List<String> firstOrderDepsWithVersions = new ArrayList<>();

    @Override
    public void inspectDependency(Dependency dependency) {
        if (dependency.getVersion() != null && !dependency.getVersion().isEmpty()) {
            firstOrderDepsWithVersions.add(dependency.getGroup() + ":" + dependency.getName());
        }
    }

    /**
     * Adds explicitly versioned producer declarations transported as immutable coordinates.
     *
     * <p>This internal adapter populates the same per-configuration state as
     * {@link #inspectDependency(Dependency)} without retaining a foreign project's mutable
     * dependency model. Coordinates use the existing {@code group:name} comparison. A
     * versionless selector can still receive a recommendation even when another declaration
     * of its coordinate has an explicit version; {@link #canRecommendVersion(ModuleVersionSelector)}
     * retains that distinction.</p>
     *
     * @param coordinates explicitly versioned external modules in inspected producer scopes
     */
    void inspectCoordinates(Iterable<String> coordinates) {
        for (String coordinate : coordinates) {
            firstOrderDepsWithVersions.add(coordinate);
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
