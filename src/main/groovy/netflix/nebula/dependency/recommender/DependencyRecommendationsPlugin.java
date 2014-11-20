package netflix.nebula.dependency.recommender;

import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.plugins.JavaPlugin;

public class DependencyRecommendationsPlugin implements Plugin<Project> {
    @Override
    public void apply(final Project project) {
        project.getPlugins().withType(JavaPlugin.class, new Action<JavaPlugin>() {
            @Override
            public void execute(JavaPlugin javaPlugin) {
                project.getExtensions().create("dependencyRecommendations", RecommendationProviderContainer.class, project);
                applyRecommendations();
            }

            public void applyRecommendations() {
                project.getConfigurations().all(new Action<Configuration>() {
                    @Override
                    public void execute(final Configuration conf) {
                        conf.getResolutionStrategy().eachDependency(new Action<DependencyResolveDetails>() {
                            @Override
                            public void execute(DependencyResolveDetails details) {
                                ModuleVersionSelector requested = details.getRequested();

                                // don't interfere with the way forces trump everything
                                for (ModuleVersionSelector force : conf.getResolutionStrategy().getForcedModules())
                                    if(requested.getGroup().equals(force.getGroup()) && requested.getName().equals(force.getName()))
                                        return;

                                String version = getRecommendedVersionRecursive(project, requested);
                                if (version != null) {
                                    details.useVersion(version);
                                }
                                else if (StringUtils.isBlank(requested.getVersion())) {
                                    project.getLogger().error("Unable to provide a recommended version for " + requested.getGroup() + ":" + requested.getName());
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
