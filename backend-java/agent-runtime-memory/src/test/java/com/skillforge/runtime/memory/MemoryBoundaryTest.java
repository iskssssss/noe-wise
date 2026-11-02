package com.skillforge.runtime.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 冻结 Memory 公共组件闭包、sealed 联合与不可伪造回执边界。 */
class MemoryBoundaryTest {
    private static final Set<String> PUBLIC_COMPONENTS = Set.of(
            "MemoryAccessPolicy",
            "MemoryObservation",
            "MemoryRuntime",
            "MemorySourcePolicy",
            "MemoryTypeRegistry");

    @Test
    void publicSurfaceContainsOnlyJdkApiAndMemoryTypes() throws Exception {
        Path classes = Path.of(MemoryRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        int count = 0;
        try (var files = Files.walk(classes)) {
            for (Path path : files.filter(file -> file.toString().endsWith(".class")).toList()) {
                String name = classes.relativize(path).toString()
                        .replace('\\', '.')
                        .replace('/', '.')
                        .replaceAll("\\.class$", "");
                Class<?> type = Class.forName(name, false, MemoryRuntime.class.getClassLoader());
                assertEquals("com.skillforge.runtime.memory", type.getPackageName(), type.getName());
                if (type.getEnclosingClass() == null) {
                    assertEquals(PUBLIC_COMPONENTS.contains(type.getSimpleName()),
                            Modifier.isPublic(type.getModifiers()), type.getName());
                }
                if (accessible(type)) {
                    checkPublicSurface(type);
                }
                count++;
            }
        }
        assertTrue(count >= 4, "Memory main output must not be empty");
    }

    @Test
    void sealedResultsHaveOnlyTheReviewedVariants() {
        assertPermits(MemoryAccessPolicy.Decision.class,
                MemoryAccessPolicy.Decision.Allowed.class,
                MemoryAccessPolicy.Decision.Denied.class);
        assertPermits(MemorySourcePolicy.Decision.class,
                MemorySourcePolicy.Decision.Allowed.class,
                MemorySourcePolicy.Decision.Denied.class);
        assertPermits(MemoryTypeRegistry.Validation.class,
                MemoryTypeRegistry.Validation.Allowed.class,
                MemoryTypeRegistry.Validation.Rejected.class);
        assertPermits(MemoryRuntime.Mutation.class,
                MemoryRuntime.Mutation.Create.class,
                MemoryRuntime.Mutation.Supersede.class,
                MemoryRuntime.Mutation.Transition.class,
                MemoryRuntime.Mutation.AcceptRevision.class);
        assertPermits(MemoryRuntime.RetrievalResult.class,
                MemoryRuntime.RetrievalResult.Recorded.class,
                MemoryRuntime.RetrievalResult.Pending.class,
                MemoryRuntime.RetrievalResult.Stopped.class);
        assertPermits(MemoryRuntime.MutationResult.class,
                MemoryRuntime.MutationResult.Recorded.class,
                MemoryRuntime.MutationResult.Rejected.class,
                MemoryRuntime.MutationResult.Conflict.class,
                MemoryRuntime.MutationResult.Pending.class,
                MemoryRuntime.MutationResult.Uncertain.class,
                MemoryRuntime.MutationResult.Stopped.class);
    }

    @Test
    void receiptsHaveNoPublicConstructionOrFactoryPath() {
        for (Class<?> type : receiptTypes()) {
            assertTrue(Modifier.isPublic(type.getModifiers()), type.getName());
            assertTrue(Modifier.isFinal(type.getModifiers()), type.getName());
            assertEquals(0, type.getConstructors().length, type.getName());
            for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()), type.getName());
            }
            Arrays.stream(type.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> Modifier.isStatic(method.getModifiers()))
                    .forEach(method -> assertFalse(method.getReturnType() == type,
                            "public receipt factory: " + type.getName() + "#" + method.getName()));
        }
    }

    @Test
    void receiptToStringNeverReadsOrExposesPrivateBodies() throws Exception {
        String[] expected = {
                "MemoryRetrievalReceipt[REDACTED]",
                "PendingMemoryRetrieval[REDACTED]",
                "PendingMemorySubmission[REDACTED]",
                "PendingMemoryMutation[REDACTED]"
        };
        Class<?>[] types = receiptTypes();
        for (int index = 0; index < types.length; index++) {
            Class<?> type = types[index];
            assertEquals(type, type.getDeclaredMethod("toString").getDeclaringClass(), type.getName());
            Constructor<?> constructor = type.getDeclaredConstructors()[0];
            assertTrue(constructor.trySetAccessible(), type.getName());
            Object receipt = constructor.newInstance(defaultArguments(constructor.getParameterTypes()));
            assertEquals(expected[index], receipt.toString());
            assertFalse(receipt.toString().contains("private-body"));
        }
    }

    private static Class<?>[] receiptTypes() {
        return new Class<?>[] {
                MemoryRuntime.RetrievalReceipt.class,
                MemoryRuntime.PendingRetrieval.class,
                MemoryRuntime.PendingSubmission.class,
                MemoryRuntime.PendingMutation.class
        };
    }

    private static void assertPermits(Class<?> sealedType, Class<?>... expected) {
        assertTrue(sealedType.isSealed(), sealedType.getName());
        assertEquals(Set.of(expected), Set.of(sealedType.getPermittedSubclasses()), sealedType.getName());
    }

    private static void checkPublicSurface(Class<?> type) {
        HashSet<Type> seen = new HashSet<>();
        if (type.getGenericSuperclass() != null) {
            checkType(type.getGenericSuperclass(), seen);
        }
        for (Type contract : type.getGenericInterfaces()) {
            checkType(contract, seen);
        }
        for (TypeVariable<?> variable : type.getTypeParameters()) {
            checkType(variable, seen);
        }
        if (type.isSealed()) {
            for (Class<?> permit : type.getPermittedSubclasses()) {
                checkType(permit, seen);
            }
        }
        for (var field : type.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers())) {
                checkType(field.getGenericType(), seen);
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!Modifier.isPublic(constructor.getModifiers())) {
                continue;
            }
            for (Type parameter : constructor.getGenericParameterTypes()) {
                checkType(parameter, seen);
            }
            for (Type exception : constructor.getGenericExceptionTypes()) {
                checkType(exception, seen);
            }
        }
        for (var method : type.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers())) {
                continue;
            }
            checkType(method.getGenericReturnType(), seen);
            for (Type parameter : method.getGenericParameterTypes()) {
                checkType(parameter, seen);
            }
            for (Type exception : method.getGenericExceptionTypes()) {
                checkType(exception, seen);
            }
            for (TypeVariable<?> variable : method.getTypeParameters()) {
                checkType(variable, seen);
            }
        }
    }

    private static void checkType(Type type, Set<Type> seen) {
        if (!seen.add(type)) {
            return;
        }
        if (type instanceof ParameterizedType parameterized) {
            checkType(parameterized.getRawType(), seen);
            if (parameterized.getOwnerType() != null) {
                checkType(parameterized.getOwnerType(), seen);
            }
            for (Type argument : parameterized.getActualTypeArguments()) {
                checkType(argument, seen);
            }
        } else if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                checkType(bound, seen);
            }
            for (Type bound : wildcard.getLowerBounds()) {
                checkType(bound, seen);
            }
        } else if (type instanceof GenericArrayType array) {
            checkType(array.getGenericComponentType(), seen);
        } else if (type instanceof TypeVariable<?> variable) {
            for (Type bound : variable.getBounds()) {
                checkType(bound, seen);
            }
        } else if (type instanceof Class<?> raw) {
            if (raw.isArray()) {
                checkType(raw.getComponentType(), seen);
                return;
            }
            String name = raw.getName();
            assertFalse(java.util.Map.class.isAssignableFrom(raw), "Map exposed by " + name);
            assertFalse(name.contains("springframework") || name.contains("mybatis"),
                    "framework type exposed by " + name);
            assertFalse(raw.getSimpleName().endsWith("DO")
                            || raw.getSimpleName().endsWith("Mapper")
                            || raw.getSimpleName().endsWith("Repository"),
                    "host persistence type exposed by " + name);
            if (name.startsWith("com.skillforge.")) {
                assertTrue(name.startsWith("com.skillforge.runtime.api.")
                                || name.startsWith("com.skillforge.runtime.memory."),
                        "host type exposed by " + name);
            }
            if (name.startsWith("com.skillforge.runtime.memory.")) {
                assertTrue(accessible(raw), "inaccessible Memory type exposed by " + name);
            }
        } else {
            throw new AssertionError("Unreviewed signature type: " + type);
        }
    }

    private static boolean accessible(Class<?> type) {
        return Modifier.isPublic(type.getModifiers())
                && (type.getEnclosingClass() == null || accessible(type.getEnclosingClass()));
    }

    private static Object[] defaultArguments(Class<?>[] parameterTypes) {
        Object[] values = new Object[parameterTypes.length];
        for (int index = 0; index < parameterTypes.length; index++) {
            Class<?> type = parameterTypes[index];
            if (type == boolean.class) {
                values[index] = false;
            } else if (type == byte.class) {
                values[index] = (byte) 0;
            } else if (type == short.class) {
                values[index] = (short) 0;
            } else if (type == int.class) {
                values[index] = 0;
            } else if (type == long.class) {
                values[index] = 0L;
            } else if (type == float.class) {
                values[index] = 0F;
            } else if (type == double.class) {
                values[index] = 0D;
            } else if (type == char.class) {
                values[index] = '\0';
            }
        }
        return values;
    }
}
