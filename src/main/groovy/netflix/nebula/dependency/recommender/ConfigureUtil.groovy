package netflix.nebula.dependency.recommender

import groovy.transform.CompileDynamic;
import org.codehaus.groovy.runtime.GeneratedClosure;
import org.gradle.api.Action;
import org.gradle.internal.Actions;
import org.gradle.internal.metaobject.ConfigureDelegate;
import org.gradle.internal.metaobject.DynamicInvokeResult;
import org.gradle.internal.metaobject.DynamicObject;
import org.gradle.internal.metaobject.DynamicObjectUtil
import org.gradle.util.Configurable;
import org.gradle.util.internal.ClosureBackedAction;

import javax.annotation.Nullable;

@CompileDynamic
class ConfigureUtil {

    static class IncompleteInputException extends RuntimeException {
        private final Collection missingKeys;

        IncompleteInputException(String message, Collection missingKeys) {
            super(message);
            this.missingKeys = missingKeys;
        }

        Collection getMissingKeys() {
            return missingKeys;
        }
    }

    /**
     * <p>Configures {@code target} with {@code configureClosure}, via the {@link Configurable} interface if necessary.</p>
     *
     * <p>If {@code target} does not implement {@link Configurable} interface, it is set as the delegate of a clone of
     * {@code configureClosure} with a resolve strategy of {@code DELEGATE_FIRST}.</p>
     *
     * <p>If {@code target} does implement the {@link Configurable} interface, the {@code configureClosure} will be passed to
     * {@code delegate}'s {@link Configurable#configure(Closure)} method.</p>
     *
     * @param configureClosure The configuration closure
     * @param target The object to be configured
     * @return The delegate param
     */
    static <T> T configure(@Nullable Closure configureClosure, T target) {
        if (configureClosure == null) {
            return target;
        }

        if (target instanceof Configurable) {
            ((Configurable) target).configure(configureClosure);
        } else {
            configureTarget(configureClosure, target, new ConfigureDelegate(configureClosure, target));
        }

        return target;
    }

    /**
     * Creates an action that uses the given closure to configure objects of type T.
     */
    static <T> Action<T> configureUsing(@Nullable final Closure configureClosure) {
        if (configureClosure == null) {
            return Actions.doNothing();
        }

        return new WrappedConfigureAction<T>(configureClosure);
    }


    private static <T> void configureTarget(Closure configureClosure, T target, ConfigureDelegate closureDelegate) {
        if (!(configureClosure instanceof GeneratedClosure)) {
            new ClosureBackedAction<T>(configureClosure, Closure.DELEGATE_FIRST, false).execute(target);
            return;
        }

        // Hackery to make closure execution faster, by short-circuiting the expensive property and method lookup on Closure
        Closure withNewOwner = configureClosure.rehydrate(target, closureDelegate, configureClosure.getThisObject());
        new ClosureBackedAction<T>(withNewOwner, Closure.OWNER_ONLY, false).execute(target);
    }


    static class WrappedConfigureAction<T> implements Action<T> {
        private final Closure configureClosure;

        WrappedConfigureAction(Closure configureClosure) {
            this.configureClosure = configureClosure;
        }

        @Override
        void execute(T t) {
            configure(configureClosure, t);
        }

        Closure getConfigureClosure() {
            return configureClosure;
        }
    }
}