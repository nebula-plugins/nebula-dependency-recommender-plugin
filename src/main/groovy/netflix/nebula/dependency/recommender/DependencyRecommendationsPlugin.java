package netflix.nebula.dependency.recommender;

import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import netflix.nebula.dependency.recommender.provider.RecommendationResolver;
import netflix.nebula.dependency.recommender.publisher.MavenBomXmlGenerator;
import org.codehaus.groovy.runtime.MethodClosure;
import org.gradle.api.Action;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.ExtraPropertiesExtension;

public class DependencyRecommendationsPlugin implements Plugin<Project> {
    private Logger logger = Logging.getLogger(DependencyRecommendationsPlugin.class);

    @Override
    public void apply(final Project project) {
        project.getExtensions().create("dependencyRecommendations", RecommendationProviderContainer.class, project);
        applyRecommendations(project);
        enhanceDependenciesWithRecommender(project);
        enhancePublicationsWithBomProducer(project);
    }

    private void applyRecommendations(final Project project) {
        project.getConfigurations().all(new Action<Configuration>() {
            @Override
            public void execute(final Configuration conf) {
                final RecommendationStrategyFactory rsFactory = new RecommendationStrategyFactory(project);
                conf.getIncoming().beforeResolve(new Action<ResolvableDependencies>() {
                    @Override
                    public void execute(ResolvableDependencies resolvableDependencies) {
                        for (Dependency dependency : resolvableDependencies.getDependencies()) {
                            rsFactory.getRecommendationStrategy().inspectDependency(dependency);
                        }

                        conf.getResolutionStrategy().eachDependency(new Action<DependencyResolveDetails>() {
                            @Override
                            public void execute(DependencyResolveDetails details) {
                                ModuleVersionSelector requested = details.getRequested();

                                // don't interfere with the way forces trump everything
                                for (ModuleVersionSelector force : conf.getResolutionStrategy().getForcedModules()) {
                                    if (requested.getGroup().equals(force.getGroup()) && requested.getName().equals(force.getName())) {
                                        return;
                                    }
                                }
                                String version = getRecommendedVersionRecursive(project, requested);
                                if(rsFactory.getRecommendationStrategy().recommendVersion(details, version)) {
                                    logger.info("Recommending version " + version + " for dependency " + requested.getGroup() + ":" + requested.getName());
                                }
                            }
                        });
                    }
                });
            }
        });
    }

    protected void enhanceDependenciesWithRecommender(Project project) {
        RecommendationResolver resolver = new RecommendationResolver(project);
        project.getExtensions().getByType(ExtraPropertiesExtension.class).set("recommend",
                new MethodClosure(resolver, "recommend"));
    }

    protected void enhancePublicationsWithBomProducer(Project project) {
        project.getExtensions().create("nebulaDependencyManagement", MavenBomXmlGenerator.class, project);
    }

    /**
     * Look for recommended versions in a project and each of its ancestors in order until one is found or the root is reached
     * @return the recommended version or <code>null</code>
     */
    protected String getRecommendedVersionRecursive(Project project, ModuleVersionSelector mvSelector) {
        String version = project.getExtensions().getByType(RecommendationProviderContainer.class)
                .getRecommendedVersion(mvSelector.getGroup(), mvSelector.getName());
        if (version != null)
            return version;
        if (project.getParent() != null)
            return getRecommendedVersionRecursive(project.getParent(), mvSelector);
        return null;
    }
}
