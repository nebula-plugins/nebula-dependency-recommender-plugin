package netflix.nebula.dependency.recommender

import org.gradle.api.IllegalDependencyNotation
import org.gradle.api.artifacts.ModuleIdentifier
import org.gradle.api.artifacts.ModuleVersionIdentifier

import javax.annotation.Nullable

class ModuleNotationParser {
    static ModuleVersionIdentifier parse(String dependencyNotation) {
        String[] moduleNotationParts = dependencyNotation.split(":");
        if (moduleNotationParts.length < 2 || moduleNotationParts.length > 4) {
            throw new IllegalDependencyNotation("Supplied String module notation '" + dependencyNotation +
                    "' is invalid. Example notations: 'org.gradle:gradle-core:2.2', 'org.mockito:mockito-core:1.9.5:javadoc'.");
        }

        def (group, name) = [ elvis(moduleNotationParts[0], null), moduleNotationParts[1] ]

        return [
                getGroup: { group },
                getName: { name },
                getVersion: { moduleNotationParts.length == 2 ? null : moduleNotationParts[2] },
                getModule: [ getGroup: group, getName: name ] as ModuleIdentifier
        ] as ModuleVersionIdentifier
    }


    static <T> T elvis(@Nullable T object, @Nullable T defaultValue) {
        return isTrue(object) ? object : defaultValue;
    }

    static boolean isTrue(@Nullable Object object) {
        if (object == null) {
            return false
        } else if (object instanceof Collection) {
            return ((Collection)object).size() > 0
        } else if (object instanceof String) {
            return ((String)object).length() > 0
        } else {
            return true;
        }
    }
}
