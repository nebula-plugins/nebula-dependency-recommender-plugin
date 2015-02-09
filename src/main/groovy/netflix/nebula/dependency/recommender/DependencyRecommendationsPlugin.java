package netflix.nebula.dependency.recommender;

import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.plugins.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public class DependencyRecommendationsPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getExtensions().create("dependencyRecommendations", RecommendationProviderContainer.class, project);
        
        project.getPlugins().withType(JavaPlugin.class, new Action<JavaPlugin>() {
            @Override
            public void execute(JavaPlugin javaPlugin) {
                applyRecommendations();
            }

            public void applyRecommendations() {
                project.getConfigurations().all(new Action<Configuration>() {
                    @Override
                    public void execute(final Configuration conf) {
                        final List<String> firstOrderDepsWithVersions = new ArrayList<>();

                        conf.getIncoming().beforeResolve(new Action<ResolvableDependencies>() {
                            @Override
                            public void execute(ResolvableDependencies resolvableDependencies) {
                                for (Dependency dependency : resolvableDependencies.getDependencies()) {
                                    if(dependency.getVersion() != null && !dependency.getVersion().isEmpty())
                                        firstOrderDepsWithVersions.add(dependency.getGroup() + ":" + dependency.getName());
                                }
                            }
                        });

                        conf.getResolutionStrategy().eachDependency(new Action<DependencyResolveDetails>() {
                            @Override
                            public void execute(DependencyResolveDetails details) {
                                ModuleVersionSelector requested = details.getRequested();
                                String coord = requested.getGroup() + ":" + requested.getName();

                                // don't interfere with the way forces trump everything
                                for (ModuleVersionSelector force : conf.getResolutionStrategy().getForcedModules())
                                    if(requested.getGroup().equals(force.getGroup()) && requested.getName().equals(force.getName())) {
                                        return;
                                    }

                                String version = getRecommendedVersionRecursive(project, requested);
                                if (version != null && !firstOrderDepsWithVersions.contains(coord)) {
                                    details.useVersion(version);
                                }
                                else if (requested.getVersion() != null && !requested.getVersion().isEmpty()) {
                                    project.getLogger().error("Unable to provide a recommended version for " + coord);
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    /**
     * Look for recommended versions in a project and each of its ancestors in order until one is found or the root is reached
     * @return the recommended version or <code>null</code>
     */
    protected String getRecommendedVersionRecursive(Project project, ModuleVersionSelector mvSelector) {
        String version = project.getExtensions()
                .getByType(RecommendationProviderContainer.class)
                .getRecommendedVersion(mvSelector.getGroup(), mvSelector.getName());
        if (version != null)
            return version;
        if (project.getParent() != null)
            return getRecommendedVersionRecursive(project.getParent(), mvSelector);
        return null;
    }
}
