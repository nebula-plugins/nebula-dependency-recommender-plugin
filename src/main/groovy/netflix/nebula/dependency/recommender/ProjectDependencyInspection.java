/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package netflix.nebula.dependency.recommender;

import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.ExternalModuleDependency;
import org.gradle.api.artifacts.ProjectDependency;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Internal, artifact-free transport of legacy project-dependency inspection data.
 *
 * <p>Each participating producer exports a metadata configuration for each local source
 * configuration. Its dependencies record explicitly versioned external module coordinates
 * and nested project edges as internal markers, following each declaration's target
 * configuration or {@code default}, exactly as the
 * legacy scan does. Dependency attributes and capabilities do not change that legacy
 * inspection scope; ordinary classpath variant selection is left to the original graph.</p>
 *
 * <p>The consumer resolves a separate graph containing only these project metadata edges.
 * It reads Gradle-owned result values rather than another project's configurations,
 * extensions, or strategy instances. No external modules or files are resolved, and no
 * generated artifact or task must run before recommendation rules can be installed.
 * Each graph selects only one producer scope, so Gradle module conflict resolution cannot
 * collapse different producers that happen to share published coordinates. A local visited
 * set terminates project cycles without reentering the consumer's classpath graph.</p>
 *
 * <p>Registration does not select a strategy, evaluate declaration providers, or resolve
 * a graph. This transport is used only behind the experimental rollout gate. All producer
 * projects reached by inspection must apply the plugin locally with that gate enabled.</p>
 *
 * <p>Projections and marker configurations have no attributes. Adding attributed variants
 * would disable Gradle's implicit {@code default} fallback for plain producer projects.
 * Named project edges select these internal configurations directly; unique capabilities
 * keep them separate from ordinary variants. Marker edges carry a versioned, Base64-encoded
 * coordinate set in their dependency reason. Gradle exposes those immutable strings in the
 * selected component's reasons. Each payload identifies its owning project and source
 * configuration, so reasons aggregated for other scopes cannot contaminate inspection. These
 * reasons never enter the user's classpath graph or its dependency-insight output.</p>
 *
 * <p>Only consumable explicit targets and the conventional {@code default} scope export
 * declarations. This avoids realizing deferred dependencies from every unused declarable
 * configuration while Gradle prepares producer metadata. A reached non-consumable explicit
 * target is represented by a diagnostic marker and rejected by the consumer, so declarations
 * are never silently omitted. Normal classpath resolution also requires consumable targets.</p>
 */
final class ProjectDependencyInspection {
    private static final String PREFIX = "nebulaRecommenderProjectInspection_";
    private static final String FORMAT = "nebula-recommender-inspection-v1:";
    private static final String UNAVAILABLE = "nebula-recommender-inspection-unavailable-v1:";
    private static final String SINK = PREFIX + "module_marker";

    private ProjectDependencyInspection() {}

    /**
     * Registers lazy producer-owned projections, including configurations added later.
     * No source configuration roles, attributes, dependencies, or artifacts are changed.
     */
    static void register(Project project) {
        project.getConfigurations().register(SINK, marker -> {
            marker.setCanBeResolved(false);
            marker.setCanBeConsumed(true);
            marker.getOutgoing().capability(capability(project.getPath(), SINK));
        });
        project.getConfigurations().all(source -> {
            if (isInternal(source.getName())) {
                return;
            }
            project.getConfigurations().register(metadataName(source.getName()), metadata -> {
                metadata.setCanBeResolved(false);
                metadata.setCanBeConsumed(true);
                metadata.setDescription("Internal dependency inspection for " + source.getName());
                metadata.getOutgoing().capability(capability(project.getPath(), source.getName()));
                metadata.withDependencies(dependencies -> {
                    if (!source.isCanBeConsumed() && !Dependency.DEFAULT_CONFIGURATION.equals(source.getName())) {
                        // Gradle prepares every consumable variant's dependency metadata. Do
                        // not turn an unused declarable/resolvable source into an eager input.
                        Dependency marker = marker(project);
                        marker.because(UNAVAILABLE + scopeKey(project.getPath(), source.getName()));
                        dependencies.add(marker);
                        return;
                    }
                    Set<String> records = new LinkedHashSet<>();
                    for (Dependency dependency : source.getAllDependencies()) {
                        if (dependency instanceof ProjectDependency) {
                            ProjectDependency edge = (ProjectDependency) dependency;
                            records.add("p:" + scopeKey(edge.getPath(), target(edge)));
                        } else if (dependency instanceof ExternalModuleDependency
                                && dependency.getVersion() != null && !dependency.getVersion().isEmpty()) {
                            records.add("m:" + encode(dependency.getGroup() + ":" + dependency.getName()));
                        }
                    }
                    Dependency marker = marker(project);
                    marker.because(FORMAT + scopeKey(project.getPath(), source.getName()) + ":" + String.join(",", records));
                    dependencies.add(marker);
                });
            });
        });
    }

    static boolean isInternal(String name) {
        return name.startsWith(PREFIX);
    }

    /**
     * Collects the same explicitly versioned coordinates as legacy inspection of supported scopes.
     * Fresh graphs and the visited set belong to the configuration being resolved; strategy
     * state is never shared across consumers. These graphs contain no consumable file artifacts.
     *
     * <p>Read only after strategy selection at the consumer's existing resolution callback.
     * Producer model evaluation stays under Gradle's ownership. The result is the union of
     * explicitly versioned module coordinates across the reachable legacy source scopes;
     * different consumer graphs cannot contribute their markers or policy to this result.</p>
     *
     * @param project the consumer owning this graph
     * @param consumer the original configuration about to resolve
     * @return detached coordinates for the consumer's strategy instance
     * @throws GradleException if a required producer projection cannot be resolved
     */
    static Set<String> inspect(Project project, Configuration consumer) {
        Deque<String> pending = new ArrayDeque<>();
        for (Dependency dependency : consumer.getAllDependencies()) {
            if (dependency instanceof ProjectDependency) {
                ProjectDependency edge = (ProjectDependency) dependency;
                pending.add(scopeKey(edge.getPath(), target(edge)));
            }
        }
        Set<String> visited = new LinkedHashSet<>();
        Set<String> coordinates = new LinkedHashSet<>();
        while (!pending.isEmpty()) {
            String scope = pending.removeFirst();
            if (visited.add(scope)) {
                String[] identity = scope.split(":", -1);
                String path = decode(identity[0]);
                String source = decode(identity[1]);
                String payload = inspectScope(project, consumer.getName(), visited.size(), path, source, scope);
                if (!payload.isEmpty()) {
                    for (String record : payload.split(",")) {
                        if (record.startsWith("m:")) {
                            coordinates.add(decode(record.substring(2)));
                        } else if (record.startsWith("p:")) {
                            pending.add(record.substring(2));
                        } else {
                            throw new GradleException("Unsupported dependency-recommender inspection record for " + path + ":" + source);
                        }
                    }
                }
            }
        }
        return coordinates;
    }

    /** Resolves one scope to prevent normal module conflict selection from discarding another producer. */
    private static String inspectScope(Project project, String consumer, int index, String path, String source, String scope) {
        Configuration inspection = project.getConfigurations().create(
                PREFIX + consumer + "_resolver_" + index, configuration -> {
                    configuration.setCanBeConsumed(false);
                    configuration.setCanBeResolved(true);
                    configuration.getResolutionStrategy().getUseGlobalDependencySubstitutionRules().set(false);
                    configuration.getDependencies().add(project.getDependencies().project(
                            Map.of("path", path, "configuration", metadataName(source))));
                });
        Set<String> payloads = new LinkedHashSet<>();
        String header = FORMAT + scope + ":";
        for (DependencyResult edge : inspection.getIncoming().getResolutionResult().getAllDependencies()) {
            if (edge instanceof UnresolvedDependencyResult) {
                throw new GradleException("Cannot inspect " + edge.getRequested().getDisplayName()
                        + " for OverrideTransitives. Apply the dependency-recommender plugin locally "
                        + "to every inspected producer with nebula.dependency-recommender.isolatedProjectsSupport=true. "
                        + "Its declared target configuration must exist.",
                        ((UnresolvedDependencyResult) edge).getFailure());
            }
            if (edge instanceof ResolvedDependencyResult) {
                ((ResolvedDependencyResult) edge).getSelected().getSelectionReason().getDescriptions().forEach(reason -> {
                    if (reason.getDescription().equals(UNAVAILABLE + scope)) {
                        throw new GradleException("Cannot inspect non-consumable producer configuration "
                                + path + ":" + source
                                + " for OverrideTransitives with experimental support enabled. "
                                + "Use a consumable explicit target configuration, or the legacy default inspection scope.");
                    }
                    if (reason.getDescription().startsWith(header)) {
                        payloads.add(reason.getDescription().substring(header.length()));
                    }
                });
            }
        }
        if (payloads.size() != 1) {
            throw new GradleException("Missing or ambiguous dependency-recommender inspection metadata for "
                    + path + ":" + source + ". Apply the same plugin version locally in every inspected producer.");
        }
        return payloads.iterator().next();
    }

    private static String target(ProjectDependency dependency) {
        // Legacy inspection follows targetConfiguration/default regardless of the actual
        // classpath variant's attributes, capabilities, excludes, or transitivity flag.
        String target = dependency.getTargetConfiguration();
        return target == null ? Dependency.DEFAULT_CONFIGURATION : target;
    }

    /** Encodes each identity field separately so delimiters cannot collide with project/configuration names. */
    private static String scopeKey(String path, String source) {
        return encode(path) + ":" + encode(source);
    }

    private static String encode(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static String capability(String path, String source) {
        // Length-prefix the path so (project path, source configuration) cannot collide.
        String identity = path.length() + ":" + path + source;
        return "netflix.nebula.dependency-recommender.inspection:"
                + Base64.getUrlEncoder().withoutPadding().encodeToString(identity.getBytes(StandardCharsets.UTF_8)) + ":1";
    }

    private static Dependency marker(Project project) {
        return project.getDependencies().project(Map.of("path", project.getPath(), "configuration", SINK));
    }

    private static String metadataName(String source) {
        // The suffix also keeps internal projections out of core-mode classpath name filters.
        return PREFIX + source + "_metadata";
    }
}
