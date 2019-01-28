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
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.runtime.MethodClosure;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencyResolveDetails;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.ResolvableDependencies;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.plugins.ExtraPropertiesExtension;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class DependencyRecommendationsPlugin implements Plugin<Project> {
    public static final String NEBULA_RECOMMENDER_BOM = "nebulaRecommenderBom";
    public static final boolean CORE_BOM_SUPPORT_ENABLED = Boolean.getBoolean("nebula.features.coreBomSupport");
    private Logger logger = Logging.getLogger(DependencyRecommendationsPlugin.class);
    private RecommendationProviderContainer recommendationProviderContainer;

    @Override
    public void apply(final Project project) {
        Configuration bomConfiguration = project.getConfigurations().create(NEBULA_RECOMMENDER_BOM);
        recommendationProviderContainer = project.getExtensions().create("dependencyRecommendations", RecommendationProviderContainer.class, project);

        if (CORE_BOM_SUPPORT_ENABLED) {
            logger.warn("coreBomSupport feature enabled");
            recommendationProviderContainer.excludeConfigurations("archives", NEBULA_RECOMMENDER_BOM, "provided",
                    "versionManagement", "resolutionRules", "bootArchives", "webapp", "checkstyle", "jacocoAgent", "jacocoAnt", "pmd", "findbugs", "spotbugs", "cobertura");
            applyRecommendationsDirectly(project, bomConfiguration);
        } else {
            applyRecommendations(project);
            enhanceDependenciesWithRecommender(project);
        }
        enhancePublicationsWithBomProducer(project);
    }

    private void applyRecommendationsDirectly(final Project project, final Configuration bomConfiguration) {
        project.afterEvaluate(new Action<Project>() {
            @Override
            public void execute(Project p) {
                p.getConfigurations().all(new ExtendRecommenderConfigurationAction(bomConfiguration, p, recommendationProviderContainer));
                p.subprojects(new Action<Project>() {
                    @Override
                    public void execute(Project sub) {
                        sub.getConfigurations().all(new ExtendRecommenderConfigurationAction(bomConfiguration, sub, recommendationProviderContainer));
                    }
                });
            }
        });
    }

    private void applyRecommendations(final Project project) {
        project.getConfigurations().all(new Action<Configuration>() {
            @Override
            public void execute(final Configuration conf) {
                final RecommendationStrategyFactory rsFactory = new RecommendationStrategyFactory(project);
                if (conf.getState() == Configuration.State.UNRESOLVED) {
                    ConfigurationsKt.onResolve(conf, new Function1<ResolvableDependencies, Unit>() {
                        @Override
                        public Unit invoke(ResolvableDependencies resolvableDependencies) {
                            if (recommendationProviderContainer.getExcludedConfigurations().contains(conf.getName())) {
                                return Unit.INSTANCE;
                            }

                            for (Dependency dependency : resolvableDependencies.getDependencies()) {
                                applyRecommendationToDependency(rsFactory, dependency, new ArrayList<ProjectDependency>());

                                // if project dependency, pull all first orders and apply recommendations if missing dependency versions
                                // dependency.getProjectConfiguration().allDependencies iterate and inspect them as well
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
                                            logger.debug("Recommending version " + version + " for dependency " + coordinate);
                                            details.because("Recommending version " + version + " for dependency " + coordinate + " via " + strategyText + "\n" +
                                                    "\twith reasons: " + StringUtils.join(recommendationProviderContainer.getReasons(), ", "));
                                        } else {
                                            if (recommendationProviderContainer.isStrictMode()) {
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

    private void applyRecommendationToDependency(final RecommendationStrategyFactory factory, Dependency dependency, List<ProjectDependency> visited) {
        if (dependency instanceof ExternalModuleDependency) {
            factory.getRecommendationStrategy().inspectDependency(dependency);
        } else if (dependency instanceof ProjectDependency) {
            ProjectDependency projectDependency = (ProjectDependency) dependency;
            if (!visited.contains(projectDependency)) {
                visited.add(projectDependency);
                Configuration configuration;
                try {
                    ProjectDependency.class.getMethod("getTargetConfiguration");
                    String targetConfiguration = projectDependency.getTargetConfiguration() == null ? Dependency.DEFAULT_CONFIGURATION : projectDependency.getTargetConfiguration();
                    configuration = projectDependency.getDependencyProject().getConfigurations().getByName(targetConfiguration);
                } catch (NoSuchMethodException ignore) {
                    try {
                        Method method = ProjectDependency.class.getMethod("getProjectConfiguration");
                        configuration = (Configuration) method.invoke(dependency);
                    } catch (Exception e) {
                        throw new RuntimeException("Unable to retrieve configuration for project dependency", e);
                    }
                }
                DependencySet dependencies = configuration.getAllDependencies();
                for (Dependency dep : dependencies) {
                    applyRecommendationToDependency(factory, dep, visited);
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
        project.getExtensions().create("nebulaDependencyManagement", MavenBomXmlGenerator.class, project);
    }

    /**
     * Look for recommended versions in a project and each of its ancestors in order until one is found or the root is reached
     *
     * @param project    the gradle <code>Project</code>
     * @param mvSelector the module to lookup
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
