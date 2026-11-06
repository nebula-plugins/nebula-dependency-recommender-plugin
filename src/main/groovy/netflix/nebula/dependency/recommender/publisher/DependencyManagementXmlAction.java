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

/** Execution state for the built-in publishing DSL; deliberately contains no project model. */
final class DependencyManagementXmlAction implements Action<XmlProvider> {
    private final List<Provider<List<Coordinate>>> contributions;

    DependencyManagementXmlAction(List<Provider<List<Coordinate>>> contributions) {
        this.contributions = new ArrayList<>(contributions);
    }

    @Override
    public void execute(XmlProvider xml) {
        Node dependencies = dependenciesNode(xml);
        for (Provider<List<Coordinate>> contribution : contributions) {
            for (Coordinate coordinate : contribution.get()) {
                coordinate.appendTo(dependencies);
            }
        }
    }

    private static Node dependenciesNode(XmlProvider xml) {
        Node root = xml.asNode();
        List<?> management = (List<?>) root.get("dependencyManagement");
        if (management.isEmpty()) {
            return root.appendNode("dependencyManagement").appendNode("dependencies");
        }
        return (Node) ((List<?>) ((Node) management.get(0)).get("dependencies")).get(0);
    }

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

    /** Map before retaining the provider: neither resolution results nor model objects escape. */
    static final class ResolvedCoordinates implements Transformer<List<Coordinate>, ResolvedComponentResult> {
        @Override
        public List<Coordinate> transform(ResolvedComponentResult root) {
            List<Coordinate> coordinates = new ArrayList<>();
            collect(root, new HashSet<>(), new HashSet<>(), coordinates);
            return Collections.unmodifiableList(coordinates);
        }

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
