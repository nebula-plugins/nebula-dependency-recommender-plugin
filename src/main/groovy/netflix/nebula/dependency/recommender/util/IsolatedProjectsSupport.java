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
package netflix.nebula.dependency.recommender.util;

import netflix.nebula.dependency.recommender.service.BomResolverService;
import netflix.nebula.dependency.recommender.service.ProjectOwnedBomResolverService;
import org.gradle.api.Project;
import org.gradle.api.provider.Provider;

/**
 * Internal rollout gate for experimental dependency-recommender resolution changes.
 *
 * <p>Set the build-wide Gradle property
 * {@code nebula.dependency-recommender.isolatedProjectsSupport=true} in
 * {@code gradle.properties}, or pass it with {@code -P}, before applying the plugin.
 * The default is {@code false}. Gradle's Isolated Projects mode and this opt-in
 * are independent: neither enables the other.</p>
 *
 * <p>Future experimental resolution paths should use this gate during configuration
 * and retain their legacy path when disabled. Reading through Gradle's provider API
 * makes the property a configuration-cache input and avoids a static decision leaking
 * between builds in the same daemon. Project extra properties and changes after plugin
 * application are not supported ways to select the mode.</p>
 *
 * <p>The gate currently selects project-owned BOM caching and the ConflictResolved
 * project-inspection shortcut, plus producer-owned metadata inspection for the built-in
 * OverrideTransitives strategy. Every inspected producer must apply the plugin locally;
 * metadata preserves the declared target-configuration/default scope and explicit-version
 * semantics without changing ordinary classpath variant selection. Gradle 9+ build-service
 * selection also skips redundant project-property lookup, retaining the version default
 * without reading ancestor extras.
 * Ancestor fallback uses explicitly configured local policy
 * layers rather than importing parent extension state. Core-BOM mode wires only local
 * classpaths to a live local BOM; every participating consumer must apply the plugin and
 * equivalent policy locally. Disabling this gate restores legacy descendant distribution.
 * The separate core-mode switch and eager-resolution timing are unchanged.</p>
 *
 * <p>This does not establish full Isolated Projects support: automatic parent policy import,
 * automatic cross-project core-BOM distribution, composite builds, and custom-strategy
 * inspection retain their limitations. BOM publication fixes remain active
 * independently of this gate.</p>
 */
public final class IsolatedProjectsSupport {
    private static final String PROPERTY = "nebula.dependency-recommender.isolatedProjectsSupport";

    private IsolatedProjectsSupport() {}

    /**
     * Reads the rollout property from the current project's Gradle provider factory.
     *
     * <p>Call during configuration and capture the decision before wiring resolution
     * callbacks. An absent property or any value other than {@code true}, ignoring
     * case, selects the legacy path. This does not consult another project's model.</p>
     *
     * @param project the project being configured
     * @return whether experimental Isolated Projects resolution changes are enabled
     */
    public static boolean isEnabled(Project project) {
        return project.getProviders().gradleProperty(PROPERTY).map(Boolean::parseBoolean).getOrElse(false);
    }

    /**
     * Registers, or retrieves, the BOM service selected by the rollout property.
     *
     * <p>Disabled builds retain the legacy {@code bomResolver} registration and its
     * coordinate-based cache. Enabled builds use {@link ProjectOwnedBomResolverService}
     * under {@code bomResolver} plus the owning project path. These registrations have
     * separate cache state, so experimental results cannot populate the legacy cache.</p>
     *
     * <p>Normal providers and manual eager-resolution adapters must use this same
     * registration path. Registration does not resolve any BOM; the returned provider
     * retains lazy service creation and the caller's existing eager-resolution timing.</p>
     *
     * @param project the project whose BOM policy the service will resolve
     * @return a provider for the selected service, without realizing it
     */
    public static Provider<BomResolverService> registerBomResolver(Project project) {
        if (isEnabled(project)) {
            return project.getGradle().getSharedServices().registerIfAbsent(
                    "bomResolver" + project.getPath(), ProjectOwnedBomResolverService.class, spec -> {}
            ).map(service -> service);
        }
        return project.getGradle().getSharedServices().registerIfAbsent(
                "bomResolver", BomResolverService.class, spec -> {}
        );
    }
}
