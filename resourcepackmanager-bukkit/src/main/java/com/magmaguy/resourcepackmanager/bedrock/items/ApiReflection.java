package com.magmaguy.resourcepackmanager.bedrock.items;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Small reflection helpers shared by the {@link CustomItemSource}s. */
final class ApiReflection {

    private ApiReflection() {
    }

    /**
     * Calls a no-argument or one-argument method of a plugin API class that may be a Java class
     * with static methods or a Kotlin {@code object} whose methods live on {@code INSTANCE}.
     */
    static Object callApi(Class<?> api, String name, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Method method = api.getMethod(name, parameterTypes);
        Object target = null;
        if (!Modifier.isStatic(method.getModifiers())) target = api.getField("INSTANCE").get(null);
        return invoke(method, target, arguments);
    }

    /** Calls a public no-argument method on an object, through the method's public declaration. */
    static Object call(Object target, String name) throws ReflectiveOperationException {
        return invoke(publicMethod(target.getClass(), name), target);
    }

    /**
     * Unwraps the plugin's own exception so the caller can tell it from a missing API, and
     * reports only the plugin's exception type and message.
     */
    static Object invoke(Method method, Object target, Object... arguments) throws ReflectiveOperationException {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException pluginFailure) {
            Throwable cause = pluginFailure.getCause() == null ? pluginFailure : pluginFailure.getCause();
            throw new PluginApiException(cause);
        }
    }

    /**
     * A public no-argument method by name, looked up on a public type that declares it, so a
     * call through a non-public implementation class (a Kotlin internal class, a lambda) works.
     */
    static Method publicMethod(Class<?> type, String name) throws NoSuchMethodException {
        Method method = type.getMethod(name);
        if (Modifier.isPublic(method.getDeclaringClass().getModifiers())) return method;
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            for (Class<?> declared : candidate.getInterfaces()) {
                Method viaInterface = publicInterfaceMethod(declared, name);
                if (viaInterface != null) return viaInterface;
            }
        }
        method.setAccessible(true);
        return method;
    }

    private static Method publicInterfaceMethod(Class<?> type, String name) {
        if (Modifier.isPublic(type.getModifiers())) {
            try {
                return type.getMethod(name);
            } catch (NoSuchMethodException ignored) {
                // Look further up.
            }
        }
        for (Class<?> parent : type.getInterfaces()) {
            Method method = publicInterfaceMethod(parent, name);
            if (method != null) return method;
        }
        return null;
    }

    /** Strings from a collection, an array, a map's keys or an optional. */
    static List<String> strings(Object values) {
        List<String> out = new ArrayList<>();
        if (values instanceof Map<?, ?> map) values = map.keySet();
        if (values instanceof Collection<?> collection) {
            for (Object value : collection) if (value != null) out.add(value.toString());
        } else if (values != null && values.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(values); i++) {
                Object value = Array.get(values, i);
                if (value != null) out.add(value.toString());
            }
        }
        return out;
    }

    /** The value inside an {@link Optional}, or the value itself. */
    static Object unwrap(Object value) {
        if (value instanceof Optional<?> optional) return optional.orElse(null);
        return value;
    }

    /** A plugin threw while RSPM called its API; not a missing API. */
    static final class PluginApiException extends ReflectiveOperationException {
        PluginApiException(Throwable cause) {
            super(cause.getClass().getSimpleName() + ": " + cause.getMessage(), cause);
        }
    }
}
