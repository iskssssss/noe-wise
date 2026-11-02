package com.skillforge.runtime.adapter.jdbc.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** JDBC adapter 内部共享的带类型、可反向校验规范二进制文本编码。 */
public final class CanonicalBinaryCodec {

    private static final int MAX_COLLECTION_SIZE = 1_000_000;

    private static final byte NULL = 0;
    private static final byte STRING = 1;
    private static final byte LONG = 2;
    private static final byte INTEGER = 3;
    private static final byte BOOLEAN = 4;
    private static final byte INSTANT = 5;
    private static final byte DURATION = 6;
    private static final byte ENUM = 7;
    private static final byte OPTIONAL = 8;
    private static final byte LIST = 9;
    private static final byte SET = 10;
    private static final byte RECORD = 11;

    private final byte[] magic;
    private final String failureCode;

    public CanonicalBinaryCodec(byte[] magic, String failureCode) {
        this.magic = Objects.requireNonNull(magic, "magic").clone();
        if (this.magic.length == 0) {
            throw new IllegalArgumentException("codec magic is empty");
        }
        this.failureCode = Objects.requireNonNull(failureCode, "failureCode");
        if (failureCode.isBlank()) {
            throw new IllegalArgumentException("codec failure code is blank");
        }
    }

    public String encode(Object value) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(magic);
                writeValue(output, value);
            }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException | ReflectiveOperationException failure) {
            throw codecFailure(failure);
        }
    }

    public <T> T decode(String encoded, Class<T> expectedType) {
        if (encoded == null || encoded.isBlank()) {
            throw codecFailure(new IllegalArgumentException("encoded value is blank"));
        }
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            Object value;
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                for (byte expected : magic) {
                    if (input.readByte() != expected) {
                        throw new IOException("invalid codec magic");
                    }
                }
                value = readValue(input);
                if (input.read() != -1) {
                    throw new IOException("trailing codec data");
                }
            }
            if (!expectedType.isInstance(value)) {
                throw new IOException("unexpected codec root type");
            }
            T decoded = expectedType.cast(value);
            if (!encode(decoded).equals(encoded)) {
                throw new IOException("non-canonical codec value");
            }
            return decoded;
        } catch (IOException | ReflectiveOperationException | RuntimeException failure) {
            if (failure instanceof IllegalStateException illegal
                    && failureCode.equals(illegal.getMessage())) {
                throw illegal;
            }
            throw codecFailure(failure);
        }
    }

    private void writeValue(DataOutputStream output, Object value)
            throws IOException, ReflectiveOperationException {
        if (value == null) {
            output.writeByte(NULL);
        } else if (value instanceof String text) {
            output.writeByte(STRING);
            writeText(output, text);
        } else if (value instanceof Long number) {
            output.writeByte(LONG);
            output.writeLong(number);
        } else if (value instanceof Integer number) {
            output.writeByte(INTEGER);
            output.writeInt(number);
        } else if (value instanceof Boolean flag) {
            output.writeByte(BOOLEAN);
            output.writeBoolean(flag);
        } else if (value instanceof Instant instant) {
            output.writeByte(INSTANT);
            output.writeLong(instant.getEpochSecond());
            output.writeInt(instant.getNano());
        } else if (value instanceof Duration duration) {
            output.writeByte(DURATION);
            output.writeLong(duration.getSeconds());
            output.writeInt(duration.getNano());
        } else if (value instanceof Enum<?> enumValue) {
            output.writeByte(ENUM);
            requireApiType(enumValue.getDeclaringClass());
            writeText(output, enumValue.getDeclaringClass().getName());
            writeText(output, enumValue.name());
        } else if (value instanceof Optional<?> optional) {
            output.writeByte(OPTIONAL);
            output.writeBoolean(optional.isPresent());
            if (optional.isPresent()) {
                writeValue(output, optional.orElseThrow());
            }
        } else if (value instanceof List<?> list) {
            output.writeByte(LIST);
            writeCount(output, list.size());
            for (Object item : list) {
                writeValue(output, item);
            }
        } else if (value instanceof Set<?> set) {
            output.writeByte(SET);
            List<byte[]> items = new ArrayList<>(set.size());
            for (Object item : set) {
                ByteArrayOutputStream itemBytes = new ByteArrayOutputStream();
                try (DataOutputStream itemOutput = new DataOutputStream(itemBytes)) {
                    writeValue(itemOutput, item);
                }
                items.add(itemBytes.toByteArray());
            }
            items.sort(CanonicalBinaryCodec::compareUnsigned);
            writeCount(output, items.size());
            for (byte[] item : items) {
                output.writeInt(item.length);
                output.write(item);
            }
        } else if (value.getClass().isRecord()) {
            output.writeByte(RECORD);
            Class<?> type = value.getClass();
            requireApiType(type);
            writeText(output, type.getName());
            RecordComponent[] components = type.getRecordComponents();
            writeCount(output, components.length);
            for (RecordComponent component : components) {
                writeText(output, component.getName());
                writeValue(output, component.getAccessor().invoke(value));
            }
        } else {
            throw new IOException("unsupported codec value type");
        }
    }

    private Object readValue(DataInputStream input)
            throws IOException, ReflectiveOperationException {
        return switch (input.readByte()) {
            case NULL -> null;
            case STRING -> readText(input);
            case LONG -> input.readLong();
            case INTEGER -> input.readInt();
            case BOOLEAN -> input.readBoolean();
            case INSTANT -> Instant.ofEpochSecond(input.readLong(), readNano(input));
            case DURATION -> Duration.ofSeconds(input.readLong(), readNano(input));
            case ENUM -> readEnum(input);
            case OPTIONAL -> input.readBoolean()
                    ? Optional.ofNullable(readValue(input)) : Optional.empty();
            case LIST -> readList(input);
            case SET -> readSet(input);
            case RECORD -> readRecord(input);
            default -> throw new IOException("unknown codec tag");
        };
    }

    private Object readEnum(DataInputStream input)
            throws IOException, ClassNotFoundException {
        Class<?> type = loadApiType(readText(input));
        if (!type.isEnum()) {
            throw new IOException("codec enum type is not an enum");
        }
        String name = readText(input);
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object value = Enum.valueOf((Class<? extends Enum>) type, name);
        return value;
    }

    private List<Object> readList(DataInputStream input)
            throws IOException, ReflectiveOperationException {
        int count = readCount(input);
        List<Object> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readValue(input));
        }
        return List.copyOf(values);
    }

    private Set<Object> readSet(DataInputStream input)
            throws IOException, ReflectiveOperationException {
        int count = readCount(input);
        Set<Object> values = new LinkedHashSet<>(count);
        byte[] previous = null;
        for (int index = 0; index < count; index++) {
            int length = readLength(input);
            byte[] encoded = input.readNBytes(length);
            if (encoded.length != length) {
                throw new EOFException("truncated set item");
            }
            if (previous != null && compareUnsigned(previous, encoded) >= 0) {
                throw new IOException("set items are not canonical");
            }
            previous = encoded;
            try (DataInputStream itemInput = new DataInputStream(
                    new ByteArrayInputStream(encoded))) {
                Object value = readValue(itemInput);
                if (itemInput.read() != -1 || !values.add(value)) {
                    throw new IOException("invalid set item");
                }
            }
        }
        return Set.copyOf(values);
    }

    private Object readRecord(DataInputStream input)
            throws IOException, ReflectiveOperationException {
        Class<?> type = loadApiType(readText(input));
        if (!type.isRecord()) {
            throw new IOException("codec record type is not a record");
        }
        RecordComponent[] components = type.getRecordComponents();
        if (readCount(input) != components.length) {
            throw new IOException("record component count changed");
        }
        Object[] arguments = new Object[components.length];
        Class<?>[] parameterTypes = new Class<?>[components.length];
        for (int index = 0; index < components.length; index++) {
            if (!components[index].getName().equals(readText(input))) {
                throw new IOException("record component identity changed");
            }
            arguments[index] = readValue(input);
            parameterTypes[index] = components[index].getType();
        }
        Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw failure;
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = readLength(input);
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("truncated codec text");
        }
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
            throw new IOException("invalid UTF-8 codec text");
        }
        return value;
    }

    private static void writeCount(DataOutputStream output, int count) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("codec collection exceeds limit");
        }
        output.writeInt(count);
    }

    private static int readCount(DataInputStream input) throws IOException {
        int count = input.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE) {
            throw new IOException("invalid codec collection size");
        }
        return count;
    }

    private static int readLength(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > input.available()) {
            throw new IOException("invalid codec length");
        }
        return length;
    }

    private static int readNano(DataInputStream input) throws IOException {
        int nano = input.readInt();
        if (nano < 0 || nano > 999_999_999) {
            throw new IOException("invalid nanosecond value");
        }
        return nano;
    }

    private static Class<?> loadApiType(String className)
            throws IOException, ClassNotFoundException {
        Class<?> type = Class.forName(
                className, false, CanonicalBinaryCodec.class.getClassLoader());
        requireApiType(type);
        return type;
    }

    private static void requireApiType(Class<?> type) throws IOException {
        String typeName = type.getName();
        if (!typeName.startsWith("com.skillforge.runtime.api.")
                && !typeName.startsWith("com.skillforge.runtime.session.spi.")) {
            throw new IOException(
                    "codec type is outside the frozen Runtime API and Session SPI");
        }
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        return java.util.Arrays.compareUnsigned(left, right);
    }

    private IllegalStateException codecFailure(Throwable cause) {
        return new IllegalStateException(failureCode, cause);
    }
}
