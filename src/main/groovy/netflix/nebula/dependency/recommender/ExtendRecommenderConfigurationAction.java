package netflix.nebula.dependency.recommender;

import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import org.gradle.api.Action;
import org.gradle.api.DomainObjectSet;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyConstraint;
import org.gradle.api.artifacts.ExcludeRule;
import org.gradle.api.internal.artifacts.DefaultExcludeRule;
import org.gradle.api.internal.artifacts.configurations.DefaultConfiguration;
import org.gradle.api.internal.artifacts.dependencies.DependencyConstraintInternal;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class ExtendRecommenderConfigurationAction implements Action<Configuration> {

    private Logger logger = Logging.getLogger(ExtendRecommenderConfigurationAction.class);

    private final Configuration bom;
    private final Project project;
    private final RecommendationProviderContainer container;

    private final AtomicInteger copyCount = new AtomicInteger();

    public ExtendRecommenderConfigurationAction(Configuration bom, Project project, RecommendationProviderContainer container) {
        this.bom = bom;
        this.project = project;
        this.container = container;
    }

    @Override
    public void execute(Configuration configuration) {
        if (!isClasspathConfiguration(configuration) || container.getExcludedConfigurations().contains(configuration.getName()) || isCopyOfBomConfiguration(configuration)) {
            return;
        }

        if (configuration.getState() == Configuration.State.UNRESOLVED) {
            Configuration toExtend = bom;
            if (!project.getRootProject().equals(project)) {
                toExtend = createCopy(bom.getDependencies(), bom.getDependencyConstraints());
                project.getConfigurations().add(toExtend);
            }
            configuration.extendsFrom(toExtend);
        } else {
            logger.info("Configuration '" + configuration.getName() + "' has already been resolved and cannot be included for recommendation");
        }
    }

    //

    /**
     * This creates a copy with proper visibility and resolve configuration
     *
     * From Gradle:
     * Instead of copying a configuration's roles outright, we allow copied configurations
     * to assume any role. However, any roles which were previously disabled will become
     * deprecated in the copied configuration. In 9.0, we will update this to copy
     * roles and deprecations without modification. Or, better yet, we will remove support
     * for copying configurations altogether.
     *
     * This means the copy created is <strong>NOT</strong> a strictly identical copy of the original, as the role
     * will be not only a different instance, but also may return different deprecation values.
     */
    private DefaultConfiguration createCopy(Set<Dependency> dependencies, Set<DependencyConstraint> dependencyConstraints) {
        DefaultConfiguration copiedConfiguration = (DefaultConfiguration) project.getConfigurations().findByName(getNameWithCopySuffix());
        if (copiedConfiguration == null) {
            copiedConfiguration = (DefaultConfiguration) project.getConfigurations().create(getNameWithCopySuffix());
            copiedConfiguration.setVisible(false);
            copiedConfiguration.setCanBeResolved(false);
            copiedConfiguration.setCanBeConsumed(bom.isCanBeConsumed());
            copiedConfiguration.setTransitive(bom.isTransitive());
            copiedConfiguration.setDescription(bom.getDescription());
        }
        copiedConfiguration.getArtifacts().addAll(bom.getAllArtifacts());
        for (ExcludeRule excludeRule : bom.getExcludeRules()) {
            copiedConfiguration.getExcludeRules().add(new DefaultExcludeRule(excludeRule.getGroup(), excludeRule.getModule()));
        }
        DomainObjectSet<Dependency> copiedDependencies = copiedConfiguration.getDependencies();
        for (Dependency dependency : dependencies) {
            copiedDependencies.add(dependency.copy());
        }
        DomainObjectSet<DependencyConstraint> copiedDependencyConstraints = copiedConfiguration.getDependencyConstraints();
        for (DependencyConstraint dependencyConstraint : dependencyConstraints) {
            copiedDependencyConstraints.add(((DependencyConstraintInternal) dependencyConstraint).copy());
        }
        return copiedConfiguration;
    }

    private String getNameWithCopySuffix() {
        int count = this.copyCount.incrementAndGet();
        String copyName = bom.getName() + "Copy";
        return count == 1 ? copyName : copyName + count;
    }

    //we want to apply recommendation only into final resolvable configurations like `compileClasspath` or `runtimeClasspath` across all source sets.
    private boolean isClasspathConfiguration(Configuration configuration) {
        return configuration.getName().endsWith("Classpath") || configuration.getName().toLowerCase().endsWith("annotationprocessor");
    }

    //this action creates clones of bom configuration from root and gradle will also apply the action to them which would
    //lead to another copy of copy and so on creating infinite loop. We won't apply the action when configuration is copy from bom configuration.
    private boolean isCopyOfBomConfiguration(Configuration configuration) {
        return configuration.getName().startsWith(bom.getName());
    }
}
