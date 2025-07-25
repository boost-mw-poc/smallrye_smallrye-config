package io.smallrye.config.inject;

import static io.smallrye.config.Converters.newCollectionConverter;
import static io.smallrye.config.Converters.newMapConverter;
import static io.smallrye.config.Converters.newOptionalConverter;

import java.io.Serial;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.AnnotatedMember;
import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.InjectionPoint;
import jakarta.inject.Provider;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.config.spi.Converter;

import io.smallrye.config.ConfigValue;
import io.smallrye.config.SecretKeys;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.common.AbstractConverter;
import io.smallrye.config.common.AbstractDelegatingConverter;

/**
 * Actual implementations for producer method in CDI producer {@link ConfigProducer}.
 *
 * @author <a href="https://github.com/guhilling">Gunnar Hilling</a>
 */
public final class ConfigProducerUtil {

    private ConfigProducerUtil() {
        throw new UnsupportedOperationException();
    }

    /**
     * Retrieves a converted configuration value from {@link Config}.
     *
     * @param injectionPoint the {@link InjectionPoint} where the configuration value will be injected
     * @param config the current {@link Config} instance.
     *
     * @return the converted configuration value.
     */
    public static <T> T getValue(InjectionPoint injectionPoint, Config config) {
        return getValue(getName(injectionPoint), getType(injectionPoint), getDefaultValue(injectionPoint), config);
    }

    private static Type getType(InjectionPoint injectionPoint) {
        Type type = injectionPoint.getType();
        if (type instanceof ParameterizedType) {
            ParameterizedType parameterizedType = (ParameterizedType) type;
            if (parameterizedType.getRawType().equals(Provider.class)
                    || parameterizedType.getRawType().equals(Instance.class)) {
                return parameterizedType.getActualTypeArguments()[0];
            }
        }
        return type;
    }

    /**
     * Retrieves a converted configuration value from {@link Config}.
     *
     * @param name the name of the configuration property.
     * @param type the {@link Type} of the configuration value to convert.
     * @param defaultValue the default value to use if no configuration value is found.
     * @param config the current {@link Config} instance.
     *
     * @return the converted configuration value.
     */
    public static <T> T getValue(String name, Type type, String defaultValue, Config config) {
        if (name == null) {
            return null;
        }

        SmallRyeConfig smallRyeConfig = config.unwrap(SmallRyeConfig.class);
        ConfigValue configValue = getConfigValue(name, smallRyeConfig);
        ConfigValue configValueWithDefault = configValue.withValue(resolveDefault(configValue.getValue(), defaultValue));

        if (hasCollection(type)) {
            return convertValues(configValueWithDefault, type, smallRyeConfig);
        } else if (hasMap(type)) {
            return convertMapValues(configValueWithDefault, type, smallRyeConfig);
        }

        return smallRyeConfig.convertValue(configValueWithDefault, resolveConverter(type, smallRyeConfig));
    }

    private static <T> T convertValues(ConfigValue configValue, Type type, SmallRyeConfig config) {
        List<String> indexedProperties = config.getIndexedProperties(configValue.getName());
        if (indexedProperties.isEmpty()) {
            return config.convertValue(configValue, resolveConverter(type, config));
        }

        // Check ordinality of indexed
        int indexedOrdinality = Integer.MIN_VALUE;
        List<ConfigValue> indexedValues = new ArrayList<>(indexedProperties.size());
        for (String indexedProperty : indexedProperties) {
            ConfigValue indexedValue = getConfigValue(indexedProperty, config);
            if (indexedValue.getConfigSourceOrdinal() >= indexedOrdinality) {
                indexedOrdinality = indexedValue.getConfigSourceOrdinal();
            }
            indexedValues.add(indexedValue);
        }

        BiFunction<Converter<T>, IntFunction<Collection<T>>, Collection<T>> indexedConverter = (itemConverter,
                collectionFactory) -> {
            Collection<T> collection = collectionFactory.apply(indexedValues.size());
            for (ConfigValue indexedValue : indexedValues) {
                collection.add(config.convertValue(indexedValue, itemConverter));
            }
            return collection;
        };

        // Use indexed if comma separated empty or higher in ordinality
        if (configValue.getValue() == null || indexedOrdinality >= configValue.getConfigSourceOrdinal()) {
            return resolveConverterForIndexed(type, config, indexedConverter).convert(" ");
        } else {
            return config.convertValue(configValue, resolveConverter(type, config));
        }
    }

    private static <T> T convertMapValues(ConfigValue configValue, Type type, SmallRyeConfig config) {
        Map<String, String> mapKeys = config.getMapKeys(configValue.getName());
        if (configValue.getRawValue() != null || mapKeys.isEmpty()) {
            return config.convertValue(configValue, resolveConverter(type, config));
        }

        BiFunction<Converter<?>, Converter<?>, Map<?, ?>> mapConverter = (keyConverter, valueConverter) -> {
            Map<Object, Object> map = new HashMap<>(mapKeys.size());
            for (Map.Entry<String, String> entry : mapKeys.entrySet()) {
                map.put(keyConverter.convert(entry.getKey()),
                        config.convertValue(getConfigValue(entry.getValue(), config), valueConverter));
            }
            return map;
        };

        return ConfigProducerUtil.<T> resolveConverterForMap(type, config, mapConverter).convert(" ");
    }

    static ConfigValue getConfigValue(InjectionPoint injectionPoint, SmallRyeConfig config) {
        String name = getName(injectionPoint);
        if (name == null) {
            return null;
        }

        io.smallrye.config.ConfigValue configValue = config.getConfigValue(name);
        if (configValue.getRawValue() == null) {
            configValue = configValue.withValue(getDefaultValue(injectionPoint));
        }

        return configValue;
    }

    static ConfigValue getConfigValue(String name, SmallRyeConfig config) {
        return SecretKeys.doUnlocked(() -> config.getConfigValue(name));
    }

    private static String resolveDefault(String rawValue, String defaultValue) {
        return rawValue != null ? rawValue : defaultValue;
    }

    @SuppressWarnings("unchecked")
    private static <T> Converter<T> resolveConverter(final Type type, final SmallRyeConfig config) {
        Class<T> rawType = rawTypeOf(type);
        if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (rawType == List.class) {
                return (Converter<T>) newCollectionConverter(resolveConverter(typeArgs[0], config), ArrayList::new);
            } else if (rawType == Set.class) {
                return (Converter<T>) newCollectionConverter(resolveConverter(typeArgs[0], config), HashSet::new);
            } else if (rawType == Map.class) {
                return (Converter<T>) newMapConverter(resolveConverter(typeArgs[0], config),
                        resolveConverter(typeArgs[1], config), HashMap::new);
            } else if (rawType == Optional.class) {
                return (Converter<T>) newOptionalConverter(resolveConverter(typeArgs[0], config));
            } else if (rawType == Supplier.class) {
                return resolveConverter(typeArgs[0], config);
            }
        }
        // just try the raw type
        return config.getConverter(rawType).orElseThrow(() -> InjectionMessages.msg.noRegisteredConverter(rawType));
    }

    /**
     * We need to handle indexed properties in a special way, since a Collection may be wrapped in other converters.
     * The issue is that in the original code the value was retrieved by calling the first converter that will delegate
     * to all the wrapped types until it finally gets the result. For indexed properties, because it requires
     * additional key lookups, a special converter is added to perform the work. This is mostly a workaround, since
     * converters do not have the proper API, and probably should not have to handle this type of logic.
     *
     * @see IndexedCollectionConverter
     */
    @SuppressWarnings("unchecked")
    private static <T> Converter<T> resolveConverterForIndexed(
            final Type type,
            final SmallRyeConfig config,
            final BiFunction<Converter<T>, IntFunction<Collection<T>>, Collection<T>> indexedConverter) {

        Class<T> rawType = rawTypeOf(type);
        if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (rawType == List.class) {
                return (Converter<T>) new IndexedCollectionConverter<>(resolveConverter(typeArgs[0], config), ArrayList::new,
                        indexedConverter);
            } else if (rawType == Set.class) {
                return (Converter<T>) new IndexedCollectionConverter<>(resolveConverter(typeArgs[0], config), HashSet::new,
                        indexedConverter);
            } else if (rawType == Optional.class) {
                return (Converter<T>) newOptionalConverter(resolveConverterForIndexed(typeArgs[0], config, indexedConverter));
            } else if (rawType == Supplier.class) {
                return resolveConverterForIndexed(typeArgs[0], config, indexedConverter);
            }
        }

        throw new IllegalArgumentException();
    }

    @SuppressWarnings("unchecked")
    private static <T> Converter<T> resolveConverterForMap(
            final Type type,
            final SmallRyeConfig config,
            final BiFunction<Converter<?>, Converter<?>, Map<?, ?>> mapConverter) {

        Class<T> rawType = rawTypeOf(type);
        if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (rawType == Map.class) {
                return (Converter<T>) new MapKeyConverter<>(resolveConverter(typeArgs[0], config),
                        resolveConverter(typeArgs[1], config), mapConverter);
            } else if (rawType == Optional.class) {
                return (Converter<T>) newOptionalConverter(resolveConverterForMap(typeArgs[0], config, mapConverter));
            } else if (rawType == Supplier.class) {
                return resolveConverterForMap(typeArgs[0], config, mapConverter);
            }
        }

        throw new IllegalArgumentException();
    }

    @SuppressWarnings("unchecked")
    private static <T> Class<T> rawTypeOf(final Type type) {
        if (type instanceof Class<?>) {
            return (Class<T>) type;
        } else if (type instanceof ParameterizedType) {
            return rawTypeOf(((ParameterizedType) type).getRawType());
        } else if (type instanceof GenericArrayType) {
            return (Class<T>) Array.newInstance(rawTypeOf(((GenericArrayType) type).getGenericComponentType()), 0).getClass();
        } else {
            throw InjectionMessages.msg.noRawType(type);
        }
    }

    private static boolean hasMap(final Type type) {
        Class<?> rawType = rawTypeOf(type);
        if (rawType == Map.class) {
            return true;
        } else if (type instanceof ParameterizedType) {
            return hasMap(((ParameterizedType) type).getActualTypeArguments()[0]);
        }
        return false;
    }

    private static <T> boolean hasCollection(final Type type) {
        Class<T> rawType = rawTypeOf(type);
        if (type instanceof ParameterizedType) {
            ParameterizedType paramType = (ParameterizedType) type;
            Type[] typeArgs = paramType.getActualTypeArguments();
            if (rawType == List.class) {
                return true;
            } else if (rawType == Set.class) {
                return true;
            } else {
                return hasCollection(typeArgs[0]);
            }
        }
        return false;
    }

    private static String getName(InjectionPoint injectionPoint) {
        for (Annotation qualifier : injectionPoint.getQualifiers()) {
            if (qualifier.annotationType().equals(ConfigProperty.class)) {
                ConfigProperty configProperty = ((ConfigProperty) qualifier);
                return getConfigKey(injectionPoint, configProperty);
            }
        }
        return null;
    }

    private static String getDefaultValue(InjectionPoint injectionPoint) {
        for (Annotation qualifier : injectionPoint.getQualifiers()) {
            if (qualifier.annotationType().equals(ConfigProperty.class)) {
                String str = ((ConfigProperty) qualifier).defaultValue();
                if (!ConfigProperty.UNCONFIGURED_VALUE.equals(str)) {
                    return str;
                }
                Class<?> rawType = rawTypeOf(injectionPoint.getType());
                if (rawType.isPrimitive()) {
                    if (rawType == char.class) {
                        return null;
                    } else if (rawType == boolean.class) {
                        return "false";
                    } else {
                        return "0";
                    }
                }
                return null;
            }
        }
        return null;
    }

    static String getConfigKey(InjectionPoint ip, ConfigProperty configProperty) {
        String key = configProperty.name();
        if (!key.trim().isEmpty()) {
            return key;
        }
        if (ip.getAnnotated() instanceof AnnotatedMember) {
            AnnotatedMember<?> member = (AnnotatedMember<?>) ip.getAnnotated();
            AnnotatedType<?> declaringType = member.getDeclaringType();
            if (declaringType != null) {
                String[] parts = declaringType.getJavaClass().getCanonicalName().split("\\.");
                StringBuilder sb = new StringBuilder(parts[0]);
                for (int i = 1; i < parts.length; i++) {
                    sb.append(".").append(parts[i]);
                }
                sb.append(".").append(member.getJavaMember().getName());
                return sb.toString();
            }
        }
        throw InjectionMessages.msg.noConfigPropertyDefaultName(ip);
    }

    static final class IndexedCollectionConverter<T, C extends Collection<T>> extends AbstractDelegatingConverter<T, C> {
        @Serial
        private static final long serialVersionUID = 5186940408317652618L;

        private final IntFunction<Collection<T>> collectionFactory;
        private final BiFunction<Converter<T>, IntFunction<Collection<T>>, Collection<T>> indexedConverter;

        public IndexedCollectionConverter(
                final Converter<T> resolveConverter,
                final IntFunction<Collection<T>> collectionFactory,
                final BiFunction<Converter<T>, IntFunction<Collection<T>>, Collection<T>> indexedConverter) {
            super(resolveConverter);

            this.collectionFactory = collectionFactory;
            this.indexedConverter = indexedConverter;
        }

        @Override
        @SuppressWarnings("unchecked")
        public C convert(final String value) throws IllegalArgumentException, NullPointerException {
            return (C) indexedConverter.apply((Converter<T>) getDelegate(), collectionFactory);
        }
    }

    static final class MapKeyConverter<K, V> extends AbstractConverter<Map<? extends K, ? extends V>> {
        @Serial
        private static final long serialVersionUID = -2920578756435265533L;

        private final Converter<? extends K> keyConverter;
        private final Converter<? extends V> valueConverter;
        private final BiFunction<Converter<? extends K>, Converter<? extends V>, Map<? extends K, ? extends V>> mapConverter;

        public MapKeyConverter(
                final Converter<? extends K> keyConverter,
                final Converter<? extends V> valueConverter,
                final BiFunction<Converter<? extends K>, Converter<? extends V>, Map<? extends K, ? extends V>> mapConverter) {
            this.keyConverter = keyConverter;
            this.valueConverter = valueConverter;
            this.mapConverter = mapConverter;
        }

        @Override
        public Map<? extends K, ? extends V> convert(final String value) throws IllegalArgumentException, NullPointerException {
            return mapConverter.apply(keyConverter, valueConverter);
        }
    }
}
