package netflix.nebula.dependency.recommender;

import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolveDetails;

import java.util.ArrayList;
import java.util.List;

public class OnlyReccomendIfFirstOrderWithoutVersionExists extends RecommendationStrategy {

    private List<String> firstOrderDepsWithoutVersions = new ArrayList<>();

    @Override
    public void inspectDependency(Dependency dependency) {
        if (dependency.getVersion() == null || dependency.getVersion().isEmpty()) {
            firstOrderDepsWithoutVersions.add(dependency.getGroup() + ":" + dependency.getName());
        }
    }

    @Override
    public void recommendVersion(DependencyResolveDetails details, String version) {
        if (version != null && firstOrderDepsWithoutVersions.contains(getCoord(details))) {
            details.useVersion(version);
        }
    }
}
