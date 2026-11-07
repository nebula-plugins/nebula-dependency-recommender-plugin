/*
 * Copyright 2014-2017 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package netflix.nebula.dependency.recommender;

import com.netflix.nebula.interop.ConfigurationsKt;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import netflix.nebula.dependency.recommender.provider.RecommendationProviderContainer;
import netflix.nebula.dependency.recommender.provider.RecommendationResolver;
import netflix.nebula.dependency.recommender.publisher.MavenBomXmlGenerator;
import netflix.nebula.dependency.recommender.util.BomResolutionUtil;
import netflix.nebula.dependency.recommender.util.IsolatedProjectsSupport;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.runtime.MethodClosure;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.*;
import org.gradle.api.attributes.Category;
import org.gradle.api.attributes.LibraryElements;
import org.gradle.api.attributes.Usage;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.util.GradleVersion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public class DependencyRecommendationsPlugin implements Plugin<Project> {
    public static final String NEBULA_RECOMMENDER_BOM = "nebulaRecommenderBom";
    public static final boolean CORE_BOM_SUPPORT_ENABLED = Boolean.getBoolean("nebula.features.coreBomSupport");
    private static final GradleVersion GRADLE_9_0 = GradleVersion.version("9.0");
    private static final AtomicInteger COPY_COUNT = new AtomicInteger();
    private final Logger logger = Logging.getLogger(DependencyRecommendationsPlugin.class);
    private RecommendationProviderContainer recommendationProviderContainer;
    private boolean isolatedProjectsSupport;
    //TODO: remove this exclusion once https://github.com/gradle/gradle/issues/6750 is resolved
    private final String SCALA_ANALYSIS_CONFIGURATION_PREFIX = "incrementalScalaAnalysis";

    @Override
    public void apply(final Project project) {
        isolatedProjectsSupport = IsolatedProjectsSupport.isEnabled(project);
        Configuration bomConfiguration = project.getConfigurations().create(NEBULA_RECOMMENDER_BOM, c -> {
            c.getAttributes().attribute(
                    Usage.USAGE_ATTRIBUTE,
                    project.getObjects().named(Usage.class, Usage.JAVA_API));
            c.getAttributes().attribute(
                    LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    project.getObjects().named(LibraryElements.class, LibraryElements.JAR));
            c.getAttributes().attribute(
                    Category.CATEGORY_ATTRIBUTE,
                    project.getObjects().named(Category.class, Category.REGULAR_PLATFORM));
            c.setCanBeConsumed(false);
        });
        recommendationProviderContainer = project.getExtensions().create("dependencyRecommendations", RecommendationProviderContainer.class, project);

        if (CORE_BOM_SUPPORT_ENABLED) {
            logger.info(project.getName() + ":coreBomSupport feature enabled");
            recommendationProviderContainer.excludeConfigurations("archives", NEBULA_RECOMMENDER_BOM, "provided",
                    "versionManagement", "resolutionRules", "bootArchives", "webapp", "checkstyle", "jacocoAgent", "jacocoAnt", "pmd", "cobertura", "zinc");
            recommendationProviderContainer.excludeConfigurationPrefixes(SCALA_ANALYSIS_CONFIGURATION_PREFIX, "spotbugs", "findbugs");
            applyRecommendationsDirectly(project, bomConfiguration);
        } else {
            recommendationProviderContainer.excludeConfigurations(NEBULA_RECOMMENDER_BOM, "zinc", "checkstyle", "jacocoAgent", "jacocoAnt");
            recommendationProviderContainer.excludeConfigurationPrefixes(SCALA_ANALYSIS_CONFIGURATION_PREFIX, "spotbugs", "findbugs");
            applyRecommendations(project);
            enhanceDependenciesWithRecommender(project);
        }
        enhancePublicationsWithBomProducer(project);
    }

    /**
     * Attaches native BOM policy after the applying project's evaluation.
     *
     * <p>With experimental Isolated Projects support enabled, the configuration and
     * container belong to this project and only its own classpaths are wired. Each
     * consumer must apply the plugin and equivalent BOM policy locally, typically
     * through a shared convention. Root declarations are not imported automatically.</p>
     *
     * <p>The disabled path retains legacy descendant distribution. Automatic eager
     * resolution and the local wiring retain their evaluation timing; opting in does
     * not enable core mode or change platform versus enforced-platform semantics.</p>
     *
     * @param project the project applying the plugin
     * @param bomConfiguration its local BOM dependency configuration
     */
    private void applyRecommendationsDirectly(final Project project, final Configuration bomConfiguration) {
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project p) {
                // Eagerly resolve and cache all BOMs if using build service approach
                if (shouldUseBuildService(p) && BomResolutionUtil.shouldEagerlyResolveBoms(p, recommendationProviderContainer)) {
                    BomResolutionUtil.eagerlyResolveBoms(p, recommendationProviderContainer, NEBULA_RECOMMENDER_BOM);
                }

                p.getConfigurations().all(new ExtendRecommenderConfigurationAction(bomConfiguration, p, recommendationProviderContainer, COPY_COUNT));
                if (isolatedProjectsSupport) {
                    return;
                }
                p.subprojects(new Action<Project>() {
                    @Override
                    public void execute(Project sub) {
                        // Also eagerly resolve BOMs for subprojects if using build service
                        if (shouldUseBuildService(sub) && BomResolutionUtil.shouldEagerlyResolveBoms(sub, recommendationProviderContainer)) {
                            BomResolutionUtil.eagerlyResolveBoms(sub, recommendationProviderContainer, NEBULA_RECOMMENDER_BOM);
                        }
                        sub.getConfigurations().all(new ExtendRecommenderConfigurationAction(bomConfiguration, sub, recommendationProviderContainer, COPY_COUNT));
                    }
                });
            }
        });
    }


    private void applyRecommendations(final Project project) {
        // Add eager BOM resolution for regular (non-core) BOM support
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project p) {
                if (shouldUseBuildService(p) && BomResolutionUtil.shouldEagerlyResolveBoms(p, recommendationProviderContainer)) {
                    // Eagerly resolve and cache all BOMs after project evaluation
                    BomResolutionUtil.eagerlyResolveBoms(p, recommendationProviderContainer, NEBULA_RECOMMENDER_BOM);
                }
            }
        });

        project.getConfigurations().all(new Action<Configuration>() {
            @Override
            public void execute(final Configuration conf) {
                final RecommendationStrategyFactory rsFactory = new RecommendationStrategyFactory(project);
                if (conf.getState() == Configuration.State.UNRESOLVED) {
                    ConfigurationsKt.onResolve(conf, new Function1<ResolvableDependencies, Unit>() {
                        @Override
                        public Unit invoke(ResolvableDependencies resolvableDependencies) {
                            boolean isExcluded = isExcludedConfiguration(conf.getName());
                            if (isExcluded) {
                                return Unit.INSTANCE;
                            }

                            for (Dependency dependency : resolvableDependencies.getDependencies()) {
                                applyRecommendationToDependency(rsFactory, dependency, new ArrayList<ProjectDependency>(), project);
                            }

                            conf.getResolutionStrategy().eachDependency(new Action<DependencyResolveDetails>() {
                                @Override
                                public void execute(DependencyResolveDetails details) {
                                    ModuleVersionSelector requested = details.getTarget();

                                    // don't interfere with the way forces trump everything
                                    for (ModuleVersionSelector force : conf.getResolutionStrategy().getForcedModules()) {
                                        if (requested.getGroup().equals(force.getGroup()) && requested.getName().equals(force.getName())) {
                                            details.because("Would have recommended a version for " + requested.getGroup() + ":" + requested.getName() + ", but a force is in place");
                                            return;
                                        }
                                    }
                                    RecommendationStrategy strategy = rsFactory.getRecommendationStrategy();
                                    if (strategy.canRecommendVersion(requested)) {
                                        String version = getRecommendedVersionRecursive(project, requested);
                                        if (strategy.recommendVersion(details, version)) {
                                            String coordinate = requested.getGroup() + ":" + requested.getName();
                                            String strategyText = whichStrategy(strategy);
                                            logger.info("Recommending version " + version + " for dependency " + coordinate);
                                            details.because("Recommending version " + version + " for dependency " + coordinate + " via " + strategyText + "\n" +
                                                            "\twith reasons: " + StringUtils.join(getReasonsRecursive(project), ", "));
                                        } else {
                                            if (recommendationProviderContainer.getStrictMode().get()) {
                                                String errorMessage = "Dependency " + details.getRequested().getGroup() + ":" + details.getRequested().getName() + " omitted version with no recommended version. General causes include a dependency being removed from the recommendation source or not applying a recommendation source to a project that depends on another project using a recommender.";
                                                project.getLogger().error(errorMessage);
                                                throw new GradleException(errorMessage);
                                            }
                                        }
                                    }
                                }
                            });
                            return Unit.INSTANCE;
                        }
                    });
                }
            }
        });
    }

    private boolean isExcludedConfiguration(String confName) {
        if (recommendationProviderContainer.getExcludedConfigurations().get().contains(confName)) {
            return true;
        }

        for (String prefix : recommendationProviderContainer.getExcludedConfigurationPrefixes().get()) {
            if (confName.startsWith(prefix)) {
                return true;
            }
        }

        return false;
    }

    private void applyRecommendationToDependency(final RecommendationStrategyFactory factory, Dependency dependency, List<ProjectDependency> visited, Project rootProject) {
        if (dependency instanceof ExternalModuleDependency) {
            factory.getRecommendationStrategy().inspectDependency(dependency);
        } else if (dependency instanceof ProjectDependency projectDependency) {
            // ConflictResolved has no dependency inspection state. Avoid accessing another
            // project's configurations for its no-op hook; other strategies still need the scan.
            if (isolatedProjectsSupport && factory.getRecommendationStrategy().getClass() == RecommendationsConflictResolvedStrategy.class) {
                return;
            }
            if (!visited.contains(projectDependency)) {
                visited.add(projectDependency);
                String targetConfiguration = projectDependency.getTargetConfiguration() == null ? Dependency.DEFAULT_CONFIGURATION : projectDependency.getTargetConfiguration();
                Project dependencyProject = rootProject.findProject(projectDependency.getPath());
                if (dependencyProject != null) {
                    Configuration configuration = dependencyProject.getConfigurations().getByName(targetConfiguration);
                    DependencySet dependencies = configuration.getAllDependencies();
                    for (Dependency dep : dependencies) {
                        applyRecommendationToDependency(factory, dep, visited, rootProject);
                    }
                }
            }
        }
    }

    protected String whichStrategy(RecommendationStrategy strategy) {
        if (strategy instanceof RecommendationsConflictResolvedStrategy) {
            return "conflict resolution recommendation";
        } else if (strategy instanceof RecommendationsOverrideTransitivesStrategy) {
            return "override transitive recommendation";
        } else {
            return "nebula.dependency-recommender";
        }
    }

    protected void enhanceDependenciesWithRecommender(Project project) {
        RecommendationResolver resolver = new RecommendationResolver(project);
        project.getExtensions().getByType(ExtraPropertiesExtension.class).set("recommend",
                new MethodClosure(resolver, "recommend"));
    }

    protected void enhancePublicationsWithBomProducer(Project project) {
        project.getExtensions().create("nebulaDependencyManagement", MavenBomXmlGenerator.class);
    }

    /**
     * Looks for a version in the consumer and then in its nearest ancestor policy scopes.
     *
     * <p>With the rollout flag disabled, this retains recursive ancestor extension lookup.
     * With the flag enabled, it queries only the supplied project's local
     * {@link RecommendationProviderContainer#inheritedPolicy(String, Action)} layers.
     * Those layers must be supplied by a local convention; the flag does not import
     * arbitrary parent build-script state. Each scope preserves provider LIFO order.</p>
     *
     * <p>Invoke in the owning project's configuration context. Calling this method with
     * a foreign project does not make that project's mutable extension state accessible
     * under Isolated Projects.</p>
     *
     * @param project    the gradle <code>Project</code>
     * @param mvSelector the module to lookup
     * @return the recommended version or <code>null</code>
     */
    public String getRecommendedVersionRecursive(Project project, ModuleVersionSelector mvSelector) {
        RecommendationProviderContainer container = project.getExtensions().getByType(RecommendationProviderContainer.class);
        String version = container.getRecommendedVersion(mvSelector.getGroup(), mvSelector.getName());
        if (version != null)
            return version;
        if (IsolatedProjectsSupport.isEnabled(project))
            return container.getInheritedRecommendedVersion(mvSelector.getGroup(), mvSelector.getName());
        if (project.getParent() != null)
            return getRecommendedVersionRecursive(project.getParent(), mvSelector);
        return null;
    }

    /**
     * Returns the nearest nonempty reason set, independently of the scope providing a version.
     *
     * <p>The rollout path uses consumer-owned inherited policy layers; the disabled path
     * retains recursive ancestor extension lookup. Neither path polls new providers here.
     * A nearer scope's reasons can therefore be returned even when a more distant scope
     * supplied the recommended version, preserving existing reason-selection behavior.</p>
     *
     * @param project the gradle <code>Project</code>
     * @return the nearest nonempty reason set, or an empty set after a complete chain
     */
    public Set<String> getReasonsRecursive(Project project) {
        RecommendationProviderContainer container = project.getExtensions().getByType(RecommendationProviderContainer.class);
        Set<String> reasons = container.getReasons();
        if (!reasons.isEmpty())
            return reasons;
        if (IsolatedProjectsSupport.isEnabled(project))
            return container.getInheritedReasons();
        if (project.getParent() != null)
            return getReasonsRecursive(project.getParent());
        return Collections.emptySet();
    }

    /**
     * Determines whether to use the BomResolverService (build service) approach.
     *
     * <p>The build service is used when:</p>
     * <ul>
     *   <li>Gradle version is 9.0 or higher, OR</li>
     *   <li>The gradle property 'nebula.dependency-recommender.useBuildService' is set to true</li>
     * </ul>
     *
     * <p>The property is additive: {@code false} does not disable the Gradle 9+
     * default. With experimental Isolated Projects support enabled, that default is
     * selected before consulting project properties. This avoids an unnecessary dynamic
     * lookup that can inspect an ancestor project, including when the property is absent
     * locally. Local, inherited, and build-wide property values cannot change the result
     * on Gradle 9+, so no replacement property declaration is needed for this shortcut.</p>
     *
     * <p>The disabled rollout path retains the existing lookup and its timing. This
     * method only selects the automatic eager-resolution mechanism; it does not change
     * the container's {@code eagerlyResolve} setting or the public manual adapters.
     * It remains called from the same {@code afterEvaluate} callbacks; disabling eager
     * resolution still permits late BOM customization before a manual call. The shortcut does not fix
     * automatic cross-project core-BOM distribution; enabled core mode instead requires
     * local policy in every participating consumer.</p>
     *
     * @param project the Gradle project to check
     * @return true if build service should be used, false otherwise
     */
    private boolean shouldUseBuildService(Project project) {
        // The explicit setting only opts in; it cannot override the Gradle 9+ default.
        // Avoid dynamic ancestor-property lookup when that setting cannot affect selection.
        if (isolatedProjectsSupport && GradleVersion.current().compareTo(GRADLE_9_0) >= 0) {
            return true;
        }

        // Check if explicitly enabled via gradle property
        if (project.hasProperty("nebula.dependency-recommender.useBuildService")) {
            Object property = project.property("nebula.dependency-recommender.useBuildService");
            if (Boolean.parseBoolean(property.toString())) {
                return true;
            }
        }

        // Default behavior: use build service for Gradle 9+
        GradleVersion currentVersion = GradleVersion.current();
        return currentVersion.compareTo(GRADLE_9_0) >= 0;
    }

    /**
     * Eagerly resolves BOM configurations during the configuration phase to prevent
     * configuration resolution lock conflicts in parallel builds.
     *
     * <p>This method delegates to {@link BomResolutionUtil#eagerlyResolveBoms} and is
     * provided for backward compatibility and convenience for external plugins.</p>
     *
     * <p><strong>External Plugin Usage:</strong></p>
     * <pre>{@code
     * // Get the plugin instance and container
     * DependencyRecommendationsPlugin plugin = project.plugins.getPlugin(DependencyRecommendationsPlugin)
     * RecommendationProviderContainer container = project.extensions.getByType(RecommendationProviderContainer)
     *
     * // Disable automatic resolution and add BOMs
     * container.setEagerlyResolve(false)
     * container.mavenBom(module: 'com.example:custom-bom:1.0.0')
     *
     * // Manually trigger resolution
     * plugin.eagerlyResolveBoms(project, container)
     * }</pre>
     *
     * @param project   the Gradle project whose BOM configurations should be resolved
     * @param container the recommendation provider container to check for additional BOM providers
     * @see BomResolutionUtil#eagerlyResolveBoms(Project, RecommendationProviderContainer, String)
     * @since 12.7.0
     */
    public void eagerlyResolveBoms(Project project, RecommendationProviderContainer container) {
        BomResolutionUtil.eagerlyResolveBoms(project, container, NEBULA_RECOMMENDER_BOM);
    }
}
