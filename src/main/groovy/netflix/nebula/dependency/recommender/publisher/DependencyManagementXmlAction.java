/*
 * Copyright 2026 Netflix, Inc.
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
package netflix.nebula.dependency.recommender.publisher;

import groovy.util.Node;
import netflix.nebula.dependency.recommender.ModuleNotationParser;
import org.gradle.api.Action;
import org.gradle.api.GradleException;
import org.gradle.api.Transformer;
import org.gradle.api.XmlProvider;
import org.gradle.api.artifacts.ModuleVersionIdentifier;
import org.gradle.api.artifacts.component.ComponentIdentifier;
import org.gradle.api.artifacts.result.DependencyResult;
import org.gradle.api.artifacts.result.ResolvedComponentResult;
import org.gradle.api.artifacts.result.ResolvedDependencyResult;
import org.gradle.api.artifacts.result.UnresolvedDependencyResult;
import org.gradle.api.provider.Provider;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Appends resolved dependency coordinates to a publication's Maven dependency-management section.
 *
 * <p>POM XML actions execute after publication configuration and must survive configuration-cache
 * storage and reuse. This action retains Gradle-managed providers mapped to plain coordinate values,
 * rather than configurations, resolution-result objects, or Groovy closures with project/script
 * delegates. The action and its nested transformers have no enclosing DSL instance to capture.
 *
 * <p>Contributions are appended in their supplied order. Deduplication happens within each resolved
 * graph, not across contributions or other XML actions registered on the publication.
 */
final class DependencyManagementXmlAction implements Action<XmlProvider> {
    private final List<Provider<List<Coordinate>>> contributions;

    DependencyManagementXmlAction(List<Provider<List<Coordinate>>> contributions) {
        this.contributions = new ArrayList<>(contributions);
    }

    /**
     * Queries the coordinate providers and appends their entries when Gradle generates the POM.
     *
     * @param xml the publication's mutable POM XML
     */
    @Override
    public void execute(XmlProvider xml) {
        Node dependencies = dependenciesNode(xml);
        for (Provider<List<Coordinate>> contribution : contributions) {
            for (Coordinate coordinate : contribution.get()) {
                coordinate.appendTo(dependencies);
            }
        }
    }

    /**
     * Reuses the first dependency-management section, or creates one for the first contribution.
     * This lets repeated DSL calls share a section while leaving earlier XML contributions intact.
     *
     * @param xml the publication's mutable POM XML
     * @return the dependencies node to which this action should append entries
     */
    private static Node dependenciesNode(XmlProvider xml) {
        Node root = xml.asNode();
        List<?> management = (List<?>) root.get("dependencyManagement");
        if (management.isEmpty()) {
            return root.appendNode("dependencyManagement").appendNode("dependencies");
        }
        return (Node) ((List<?>) ((Node) management.get(0)).get("dependencies")).get(0);
    }

    /**
     * Immutable group, artifact, and version snapshot suitable for configuration-cache storage.
     * Copying the strings avoids retaining the Gradle identifier or its backing resolution model.
     */
    static final class Coordinate implements Serializable {
        private final String group;
        private final String name;
        private final String version;

        Coordinate(ModuleVersionIdentifier id) {
            group = id.getGroup();
            name = id.getName();
            version = id.getVersion();
        }

        void appendTo(Node dependencies) {
            Node dependency = dependencies.appendNode("dependency");
            dependency.appendNode("groupId").setValue(group);
            dependency.appendNode("artifactId").setValue(name);
            dependency.appendNode("version").setValue(version);
        }
    }

    /**
     * Maps a resolution-root provider to immutable coordinates before the provider is retained by
     * the XML action. Traversal uses Gradle's selected components, so conflict resolution and
     * transitive dependencies are reflected in the published versions without resolving artifacts.
     */
    static final class ResolvedCoordinates implements Transformer<List<Coordinate>, ResolvedComponentResult> {
        /**
         * Collects dependencies reachable from the root; the configuration's root is not itself
         * added as an entry unless it is reached through a dependency edge.
         *
         * @param root the root component of a configuration's resolution result
         * @return an immutable list with each encountered module version included once
         * @throws GradleException if a traversed dependency could not be resolved
         */
        @Override
        public List<Coordinate> transform(ResolvedComponentResult root) {
            List<Coordinate> coordinates = new ArrayList<>();
            collect(root, new HashSet<>(), new HashSet<>(), coordinates);
            return Collections.unmodifiableList(coordinates);
        }

        /**
         * Traverses non-constraint dependency edges depth first. Constraints affect selected
         * versions but do not independently contribute BOM entries. Components without module
         * coordinates are traversed without adding an entry, so their dependencies remain reachable.
         *
         * <p>Component identities prevent cycles and repeated traversal; module-version identities
         * separately prevent duplicate coordinates. Unresolved edges fail publication rather than
         * silently producing an incomplete BOM.
         *
         * @param component the component whose outgoing dependencies should be visited
         * @param visited component identities already traversed in this graph
         * @param modules module versions already emitted in this graph
         * @param coordinates accumulated coordinates in traversal order
         */
        private static void collect(ResolvedComponentResult component, Set<ComponentIdentifier> visited,
                                    Set<ModuleVersionIdentifier> modules, List<Coordinate> coordinates) {
            if (!visited.add(component.getId())) {
                return;
            }
            for (DependencyResult dependency : component.getDependencies()) {
                if (dependency instanceof UnresolvedDependencyResult) {
                    throw new GradleException("Could not resolve dependencies for BOM publication.",
                            ((UnresolvedDependencyResult) dependency).getFailure());
                }
                ResolvedDependencyResult resolved = (ResolvedDependencyResult) dependency;
                if (resolved.isConstraint()) {
                    continue;
                }
                ResolvedComponentResult selected = resolved.getSelected();
                ModuleVersionIdentifier id = selected.getModuleVersion();
                if (id != null && modules.add(id)) {
                    coordinates.add(new Coordinate(id));
                }
                collect(selected, visited, modules, coordinates);
            }
        }
    }

    /**
     * Appends explicit dependency notations without resolving a dependency graph or transitives.
     * Only a defensive snapshot of the notation strings is retained, so the supplying DSL closure
     * and its delegates are not part of the deferred action. Parsing remains deferred until POM
     * generation, preserving the existing timing and validation errors for invalid notations.
     */
    static final class ExplicitDependencies implements Action<XmlProvider> {
        private final List<String> notations;

        ExplicitDependencies(List<String> notations) {
            this.notations = Collections.unmodifiableList(new ArrayList<>(notations));
        }

        @Override
        public void execute(XmlProvider xml) {
            Node dependencies = dependenciesNode(xml);
            // Keep notation parsing deferred, including its existing validation errors.
            for (String notation : notations) {
                new Coordinate(ModuleNotationParser.parse(notation)).appendTo(dependencies);
            }
        }
    }
}
