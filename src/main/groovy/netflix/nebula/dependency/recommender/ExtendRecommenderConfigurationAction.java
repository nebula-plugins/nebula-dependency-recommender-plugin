package netflix.nebula.dependency.recommender;

import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import netflix.nebula.dependency.recommender.util.IsolatedProjectsSupport;
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

/**
 * Adds core-BOM policy to unresolved classpath and annotation-processor configurations.
 *
 * <p>With experimental Isolated Projects support enabled, the supplied BOM, project,
 * and provider container must all belong to the same project. Eligible configurations
 * extend that live local BOM, so later customization remains visible until resolution.
 * This action does not import policy from other projects or make foreign configurations
 * safe to access.</p>
 *
 * <p>With the rollout flag disabled, legacy root-driven distribution is retained:
 * root configurations extend the BOM directly and descendant configurations receive
 * local snapshot copies. Exact-name exclusions and configuration-name filtering are
 * unchanged; core mode does not apply provider configuration-prefix exclusions.</p>
 */
public class ExtendRecommenderConfigurationAction implements Action<Configuration> {

    private Logger logger = Logging.getLogger(ExtendRecommenderConfigurationAction.class);

    private final Configuration bom;
    private final Project project;
    private final RecommendationProviderContainer container;
    private final AtomicInteger copyCount;
    private final boolean isolatedProjectsSupport;

    /**
     * Creates an action and captures the rollout property during configuration.
     *
     * @param bom the policy configuration; owner-local when experimental support is enabled
     * @param project the project whose configurations will be extended
     * @param container the provider container supplying exact-name exclusions
     * @param copyCount the counter used to name legacy descendant snapshot configurations
     */
    public ExtendRecommenderConfigurationAction(Configuration bom, Project project, RecommendationProviderContainer container, AtomicInteger copyCount) {
        this.bom = bom;
        this.project = project;
        this.container = container;
        this.copyCount = copyCount;
        this.isolatedProjectsSupport = IsolatedProjectsSupport.isEnabled(project);
    }

    /**
     * Creates an action with its own counter for legacy snapshot names.
     *
     * @param bom the policy configuration; owner-local when experimental support is enabled
     * @param project the project whose configurations will be extended
     * @param container the provider container supplying exact-name exclusions
     */
    public ExtendRecommenderConfigurationAction(Configuration bom, Project project, RecommendationProviderContainer container) {
        this(bom, project, container, new AtomicInteger());
    }

    /**
     * Extends an eligible unresolved configuration without resolving either configuration.
     *
     * <p>Configurations created after this action is registered receive the same policy.
     * Already resolved configurations are left unchanged, as on the legacy path.</p>
     *
     * @param configuration a configuration owned by the supplied project
     */
    @Override
    public void execute(Configuration configuration) {
        if (!isClasspathConfiguration(configuration) || container.getExcludedConfigurations().get().contains(configuration.getName()) || isCopyOfBomConfiguration(configuration)) {
            return;
        }

        if (configuration.getState() == Configuration.State.UNRESOLVED) {
            Configuration toExtend = bom;
            if (!isolatedProjectsSupport && !project.getRootProject().equals(project)) {
                toExtend = createCopy(bom.getDependencies(), bom.getDependencyConstraints());
                // No need to add to configurations - createCopy already handles this
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
        // Include project path to ensure uniqueness across composite builds and subprojects
        // Replace colons with underscores to avoid configuration naming issues
        String projectPathSuffix = project.getPath().replace(":", "_");
        if (projectPathSuffix.isEmpty() || projectPathSuffix.equals("_")) {
            projectPathSuffix = "root";
        }
        String copyName = bom.getName() + "Copy_" + projectPathSuffix;
        return count == 1 ? copyName : copyName + "_" + count;
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
