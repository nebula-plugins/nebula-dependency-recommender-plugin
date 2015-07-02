package netflix.nebula.dependency.recommender

import org.gradle.api.IllegalDependencyNotation
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier
import org.gradle.util.GUtil

class ModuleNotationParser {
    static ModuleVersionIdentifier parse(String dependencyNotation) {
        String[] moduleNotationParts = dependencyNotation.split(":");
        if (moduleNotationParts.length < 2 || moduleNotationParts.length > 4) {
            throw new IllegalDependencyNotation("Supplied String module notation '" + dependencyNotation +
                    "' is invalid. Example notations: 'org.gradle:gradle-core:2.2', 'org.mockito:mockito-core:1.9.5:javadoc'.");
        }

        def (group, name) = [ GUtil.elvis(moduleNotationParts[0], null), moduleNotationParts[1] ]

        return [
                getGroup: { group },
                getName: { name },
                getVersion: { moduleNotationParts.length == 2 ? null : moduleNotationParts[2] },
                getModule: [ getGroup: group, getName: name ] as ModuleIdentifier
        ] as ModuleVersionIdentifier
    }
}
