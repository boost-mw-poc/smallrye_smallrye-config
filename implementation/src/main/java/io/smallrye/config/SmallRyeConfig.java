/*
 * Copyright 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.smallrye.config;

import static io.smallrye.config.ConfigMappingLoader.configMappingProperties;
import static io.smallrye.config.ConfigMappingLoader.getConfigMappingClass;
import static io.smallrye.config.ConfigSourceInterceptor.EMPTY;
import static io.smallrye.config.Converters.newCollectionConverter;
import static io.smallrye.config.Converters.newMapConverter;
import static io.smallrye.config.Converters.newOptionalConverter;
import static io.smallrye.config.ProfileConfigSourceInterceptor.activeName;
import static io.smallrye.config.common.utils.StringUtil.unindexed;
import static io.smallrye.config.common.utils.StringUtil.unquoted;
import static java.util.stream.Collectors.toList;

import java.io.ObjectStreamException;
import java.io.Serial;
import java.io.Serializable;
import java.lang.reflect.Array;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.config.inject.ConfigProperties;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;
import org.eclipse.microprofile.config.spi.Converter;

import io.smallrye.config.SmallRyeConfigBuilder.InterceptorWithPriority;
import io.smallrye.config._private.ConfigLogging;
import io.smallrye.config._private.ConfigMessages;
import io.smallrye.config.common.utils.StringUtil;

/**
 * {@link SmallRyeConfig} provides a way to retrieve configuration values from a configuration name.
 * <p>
 * A {@link SmallRyeConfig} instance is obtained via the {@link SmallRyeConfigBuilder#build()}, which details how
 * {@link SmallRyeConfig} will behave. Generally, a {@link SmallRyeConfig} instance is composed of:
 * <ul>
 * <li>{@linkplain ConfigSource Configuration Sources} to lookup the configuration values</li>
 * <li>{@linkplain Converter Converters} to convert values to specific types</li>
 * <li>{@linkplain ConfigSourceInterceptor Interceptors} to enhance the configuration lookup process</li>
 * <li>{@linkplain ConfigMapping} Config Mappings classes to group multiple configuration values in a common prefix</li>
 * </ul>
 *
 * @author Jeff Mesnil
 * @author David M. Lloyd
 * @author Roberto Cortez
 */
public class SmallRyeConfig implements Config, Serializable {
    /**
     * Configuration name to set the main Profiles to activate. The configuration supports multiple profiles separated
     * by a comma.
     */
    public static final String SMALLRYE_CONFIG_PROFILE = "smallrye.config.profile";
    /**
     * Configuration name to set the parent Profile to activate.
     */
    public static final String SMALLRYE_CONFIG_PROFILE_PARENT = "smallrye.config.profile.parent";
    /**
     * Configuration name to set additional config locations to be loaded with the Config. The configuration supports
     * multiple locations separated by a comma and each must represent a valid {@code java.net.URI}.
     */
    public static final String SMALLRYE_CONFIG_LOCATIONS = "smallrye.config.locations";
    /**
     * Configuration name to validate that a {@link ConfigMapping} maps every available configuration name contained
     * in the mapping prefix. The configuration value must be a {@code boolean}.
     */
    public static final String SMALLRYE_CONFIG_MAPPING_VALIDATE_UNKNOWN = "smallrye.config.mapping.validate-unknown";
    /**
     * Configuration name to set the names of the secret handlers to be loaded. A value of {@code all} loads all
     * available secret handlers and a value of none {@code skips} the load. The configuration supports multiple secret
     * handlers separated by a comma.
     */
    public static final String SMALLRYE_CONFIG_SECRET_HANDLERS = "smallrye.config.secret-handlers";
    /**
     * Configuration name to enable logging of configuration values lookup in {@code DEBUG} log level. The configuration
     * value must be a {@code boolean}.
     */
    public static final String SMALLRYE_CONFIG_LOG_VALUES = "smallrye.config.log.values";

    @Serial
    private static final long serialVersionUID = 8138651532357898263L;

    private final ConfigSources configSources;
    private final Map<Type, Converter<?>> converters;
    private final Map<Type, Converter<Optional<?>>> optionalConverters = new ConcurrentHashMap<>();

    private final ConfigValidator configValidator;
    private final Map<Class<?>, Map<String, Object>> mappings;

    SmallRyeConfig(SmallRyeConfigBuilder builder) {
        this.configSources = new ConfigSources(builder);
        this.converters = buildConverters(builder);
        this.configValidator = builder.getValidator();
        this.mappings = new ConcurrentHashMap<>(buildMappings(builder));
    }

    private Map<Type, Converter<?>> buildConverters(final SmallRyeConfigBuilder builder) {
        final Map<Type, SmallRyeConfigBuilder.ConverterWithPriority> convertersToBuild = new HashMap<>(builder.getConverters());

        if (builder.isAddDiscoveredConverters()) {
            for (Converter<?> converter : builder.discoverConverters()) {
                Type type = Converters.getConverterType(converter.getClass());
                if (type == null) {
                    throw ConfigMessages.msg.unableToAddConverter(converter);
                }
                SmallRyeConfigBuilder.addConverter(type, converter, convertersToBuild);
            }
        }

        final ConcurrentHashMap<Type, Converter<?>> converters = new ConcurrentHashMap<>(Converters.ALL_CONVERTERS);
        for (Entry<Type, SmallRyeConfigBuilder.ConverterWithPriority> entry : convertersToBuild.entrySet()) {
            converters.put(entry.getKey(), entry.getValue().getConverter());
        }
        converters.put(ConfigValue.class, Converters.CONFIG_VALUE_CONVERTER);

        return converters;
    }

    Map<Class<?>, Map<String, Object>> buildMappings(final SmallRyeConfigBuilder builder)
            throws ConfigValidationException {
        SmallRyeConfigBuilder.MappingBuilder mappingsBuilder = builder.getMappingsBuilder();
        if (mappingsBuilder.isEmpty()) {
            return Collections.emptyMap();
        }

        // Perform the config mapping
        ConfigMappingContext context = SecretKeys.doUnlocked(new Supplier<ConfigMappingContext>() {
            @Override
            public ConfigMappingContext get() {
                return new ConfigMappingContext(SmallRyeConfig.this, mappingsBuilder);
            }
        });

        if (getOptionalValue(SMALLRYE_CONFIG_MAPPING_VALIDATE_UNKNOWN, boolean.class).orElse(true)) {
            context.reportUnknown(mappingsBuilder.getIgnoredPaths());
        }

        List<ConfigValidationException.Problem> problems = context.getProblems();
        if (!problems.isEmpty()) {
            throw new ConfigValidationException(problems.toArray(ConfigValidationException.Problem.NO_PROBLEMS));
        }

        return context.getMappings();
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for indexed
     * properties. An indexed property uses the original property name with square brackets and an index in between, as
     * {@code my.property[0]}. All indexed properties are queried for their value, which represents a single
     * element in the returning {@code List} converted to their specified property type. The following
     * configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in a {@code List} with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in a {@code List} with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param propertyType The type into which the resolved property values are converted
     * @return the resolved property values as a {@code List} of instances of the property type
     * @param <T> the item type
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getValues(String, Converter, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     */
    @Override
    public <T> List<T> getValues(final String name, final Class<T> propertyType) {
        return getValues(name, propertyType, ArrayList::new);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for indexed
     * properties. An indexed property uses the original property name with square brackets and an index in between, as
     * {@code my.property[0]}. All indexed properties are queried for their value, which represents a single
     * element in the returning {@code Collection} converted to their specified property type. The following
     * configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in a {@code Collection} with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in a {@code Collection} with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param itemClass The type into which the resolved property values are converted
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Collection} of instances of the property type
     * @param <T> the item type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Class)
     * @see SmallRyeConfig#getValues(String, Converter, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     */
    public <T, C extends Collection<T>> C getValues(final String name, final Class<T> itemClass,
            final IntFunction<C> collectionFactory) {
        return getValues(name, requireConverter(itemClass), collectionFactory);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for indexed
     * properties. An indexed property uses the original property name with square brackets and an index in between, as
     * {@code my.property[0]}. All indexed properties are queried for their value, which represents a single
     * element in the returning {@code Collection} converted by the specified {@link Converter}. The following
     * configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in a {@code Collection} with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in a {@code Collection} with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the {@link Converter} to convert the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param converter The {@link Converter} to use to convert the resolved property values
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Collection} of instances of the property type
     * @param <T> the item type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Class)
     * @see SmallRyeConfig#getValues(String, Class IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     */
    public <T, C extends Collection<T>> C getValues(final String name, final Converter<T> converter,
            final IntFunction<C> collectionFactory) {
        List<String> indexedProperties = getIndexedProperties(name);
        if (indexedProperties.isEmpty()) {
            // Try legacy / MP comma separated values
            return getValue(name, newCollectionConverter(converter, collectionFactory));
        }

        // Check ordinality of indexed
        int indexedOrdinality = Integer.MIN_VALUE;
        C collection = collectionFactory.apply(indexedProperties.size());
        for (String indexedProperty : indexedProperties) {
            ConfigValue indexed = getConfigValue(indexedProperty);
            if (indexed.getConfigSourceOrdinal() >= indexedOrdinality) {
                indexedOrdinality = indexed.getConfigSourceOrdinal();
            }
            collection.add(convertValue(indexed, converter));
        }

        // Use indexed if comma separated empty or higher in ordinality
        ConfigValue commaSeparated = getConfigValue(name);
        if (commaSeparated.getValue() == null || indexedOrdinality >= commaSeparated.getConfigSourceOrdinal()) {
            return collection;
        } else {
            return getValue(name, newCollectionConverter(converter, collectionFactory));
        }
    }

    @Deprecated(forRemoval = true)
    public <T, C extends Collection<T>> C getIndexedValues(String name, Converter<T> converter,
            IntFunction<C> collectionFactory) {
        List<String> indexedProperties = getIndexedProperties(name);
        if (indexedProperties.isEmpty()) {
            throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(name));
        }
        return getIndexedValues(indexedProperties, converter, collectionFactory);
    }

    @Deprecated(forRemoval = true)
    private <T, C extends Collection<T>> C getIndexedValues(List<String> indexedProperties, Converter<T> converter,
            IntFunction<C> collectionFactory) {
        C collection = collectionFactory.apply(indexedProperties.size());
        for (String indexedProperty : indexedProperties) {
            collection.add(getValue(indexedProperty, converter));
        }
        return collection;
    }

    public List<String> getIndexedProperties(final String property) {
        Map<Integer, String> indexedProperties = configSources.getPropertyNames().indexed().get(property);
        return indexedProperties == null ? Collections.emptyList() : indexedProperties.values().stream().toList();
    }

    public List<Integer> getIndexedPropertiesIndexes(final String property) {
        Map<Integer, String> indexedProperties = configSources.getPropertyNames().indexed().get(property);
        return indexedProperties == null ? Collections.emptyList() : indexedProperties.keySet().stream().toList();
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed
     * properties. A keyed property uses the original property name plus an additional dotted segment to represent
     * a {@code Map} key, as {@code my.property.key}, where {@code my.property} is the property name and {@code key}
     * is the {@code Map} key. All keyed properties are queried for their values, which represent a single entry in the
     * returning {@code Map} converting both the key and value to their specified types.
     * The following configuration:
     * <ul>
     * <li>server.reasons.200=OK</li>
     * <li>server.reasons.201=CREATED</li>
     * <li>server.reasons.404=NOT_FOUND</li>
     * </ul>
     * <p>
     * Results in a {@code Map} with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND},
     * considering the configuration name as {@code server.reasons}, the key type as {@code Integer} and the
     * property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} with the backslash ({@code \}) as
     * the escape character.
     * A configuration of {@code server.reasons=200=OK;201=CREATED;404=NOT_FOUND} results in a {@code Map}
     * with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND}, considering the configuration
     * name as {@code server.reasons}, the key type as {@code Integer} and the property type as a {@code String}.
     * <p>
     * The keyed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyClass The type into which the resolved property keys are converted
     * @param valueClass The type into which the resolved property values are converted
     * @return the resolved property values as a {@code Map} of keys of the property name and values of the property
     *         type
     * @param <K> the key type
     * @param <V> the value type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Converter, Converter)
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     */
    public <K, V> Map<K, V> getValues(final String name, final Class<K> keyClass, final Class<V> valueClass) {
        return getValues(name, requireConverter(keyClass), requireConverter(valueClass));
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed
     * properties. A keyed property uses the original property name plus an additional dotted segment to represent
     * a {@code Map} key, as {@code my.property.key}, where {@code my.property} is the property name and {@code key}
     * is the {@code Map} key. All keyed properties are queried for their values, which represent a single entry in the
     * returning {@code Map} converting both the key and value using the specified {@linkplain Converters Converters}.
     * The following configuration:
     * <ul>
     * <li>server.reasons.200=OK</li>
     * <li>server.reasons.201=CREATED</li>
     * <li>server.reasons.404=NOT_FOUND</li>
     * </ul>
     * <p>
     * Results in a {@code Map} with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND},
     * considering the configuration name as {@code server.reasons} and {@linkplain Converters Converters} to
     * convert the key type as an {@code Integer} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} with the backslash ({@code \}) as
     * the escape character.
     * A configuration of {@code server.reasons=200=OK;201=CREATED;404=NOT_FOUND} results in a {@code Map}
     * with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND}, considering the configuration
     * name as {@code server.reasons} and {@linkplain Converters Converters} to convert the key type as an
     * {@code Integer} and the property type as a {@code String}.
     * <p>
     * The keyed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyConverter The {@link Converter} to use to convert the resolved property keys
     * @param valueConverter The {@link Converter} to use to convert the resolved property values
     * @return the resolved property values as a {@code Map} of keys of the property name and values of the property
     *         type
     * @param <K> the key type
     * @param <V> the value type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Class, Class)
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     */
    public <K, V> Map<K, V> getValues(
            final String name,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter) {
        return getValues(name, keyConverter, valueConverter, HashMap::new);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed
     * properties. A keyed property uses the original property name plus an additional dotted segment to represent
     * a {@code Map} key, as {@code my.property.key}, where {@code my.property} is the property name and {@code key}
     * is the {@code Map} key. All keyed properties are queried for their values, which represent a single entry in the
     * returning {@code Map} converting both the key and value using the specified {@linkplain Converters Converters}.
     * The following configuration:
     * <ul>
     * <li>server.reasons.200=OK</li>
     * <li>server.reasons.201=CREATED</li>
     * <li>server.reasons.404=NOT_FOUND</li>
     * </ul>
     * <p>
     * Results in a {@code Map} with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND},
     * considering the configuration name as {@code server.reasons} and {@linkplain Converters Converters} to
     * convert the key type as an {@code Integer} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} with the backslash ({@code \}) as
     * the escape character.
     * A configuration of {@code server.reasons=200=OK;201=CREATED;404=NOT_FOUND} results in a {@code Map}
     * with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND}, considering the configuration
     * name as {@code server.reasons} and {@linkplain Converters Converters} to convert the key type as an
     * {@code Integer} and the property type as a {@code String}.
     * <p>
     * The keyed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyConverter The {@link Converter} to use to convert the resolved property keys
     * @param valueConverter The {@link Converter} to use to convert the resolved property values
     * @param mapFactory the resulting instance of a {@code Map} to return the property keys and values
     * @return the resolved property values as a {@code Map} of keys of the property name and values of the property
     *         type
     * @param <K> the key type
     * @param <V> the value type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Class, Class)
     * @see SmallRyeConfig#getValues(String, Converter, Converter)
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     */
    public <K, V> Map<K, V> getValues(
            final String name,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter,
            final IntFunction<Map<K, V>> mapFactory) {

        Map<String, String> keys = getMapKeys(name);
        if (keys.isEmpty()) {
            // Try legacy MapConverter
            return getValue(name, newMapConverter(keyConverter, valueConverter, mapFactory));
        }

        return getMapValues(keys, keyConverter, valueConverter, mapFactory);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed indexed
     * properties. A keyed indexed property uses the original property name plus an additional dotted segment to
     * represent a {@code Map} key followed by square brackets and an index in between, as {@code my.property.key[0]},
     * where {@code my.property} is the property name, {@code key} is the {@code Map} key and {code [0]} the index of
     * the {@code Collection} element. All keyed indexed properties are queried for their value, which represent
     * a single entry in the returning {@code Map}, and single element in the {@code Collection} value, converting both
     * the key and value to their specified types. The following configuration:
     * <ul>
     * <li>server.env.prod[0]=alpha</li>
     * <li>server.env.prod[1]=beta</li>
     * <li>server.env.dev[0]=local</li>
     * </ul>
     * <p>
     * Results in a {@code Map} with the entry key {@code prod} and entry value {@code Collection} with the values
     * {@code alpha} and {@code beta}, and the entry key {@code dev} and entry value {@code Collection} with the value
     * {@code local}, considering the configuration name as {@code server.env}, the key type as a {@code String}, and
     * the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} and value as a comma-separated
     * string ({@code ,}) can represent, and split into multiple elements with the backslash ({@code \}) as the
     * escape character. A configuration of {@code server.env=prod=alpha,beta;dev=local} results in a {@code Map} with
     * the entry key {@code prod} and entry value {@code Collection} with the values {@code alpha} and {@code beta},
     * and the entry key {@code dev} and entry value {@code Collection} with the value {@code local}, considering the
     * configuration name as {@code server.env}, the key type as a {@code String}, and the property type as a
     * {@code String}.
     * <p>
     * The keyed indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyClass The type into which the resolved property keys are converted
     * @param valueClass The type into which the resolved property values are converted
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Map} of keys of the property name and values as a
     *         {@code Collections} of instances of the property type
     * @param <K> the key type
     * @param <V> the value type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Class, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter, IntFunction, IntFunction)
     */
    public <K, V, C extends Collection<V>> Map<K, C> getValues(
            final String name,
            final Class<K> keyClass,
            final Class<V> valueClass,
            final IntFunction<C> collectionFactory) {
        return getValues(name, requireConverter(keyClass), requireConverter(valueClass), HashMap::new, collectionFactory);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed indexed
     * properties. A keyed indexed property uses the original property name plus an additional dotted segment to
     * represent a {@code Map} key followed by square brackets and an index in between, as {@code my.property.key[0]},
     * where {@code my.property} is the property name, {@code key} is the {@code Map} key and {code [0]} the index of
     * the {@code Collection} element. All keyed indexed properties are queried for their value, which represent
     * a single entry in the returning {@code Map}, and single element in the {@code Collection} value, converting both
     * the key and value using the specified {@linkplain Converters Converters}. The following configuration:
     * <ul>
     * <li>server.env.prod[0]=alpha</li>
     * <li>server.env.prod[1]=beta</li>
     * <li>server.env.dev[0]=local</li>
     * </ul>
     * <p>
     * Results in a {@code Map} with the entry key {@code prod} and entry value {@code Collection} with the values
     * {@code alpha} and {@code beta}, and the entry key {@code dev} and entry value {@code Collection} with the value
     * {@code local}, considering the configuration name as {@code server.env} and {@linkplain Converters Converters}
     * to convert the key type as a {@code String} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} and value as a comma-separated
     * string ({@code ,}) can represent, and split into multiple elements with the backslash ({@code \}) as the
     * escape character. A configuration of {@code server.env=prod=alpha,beta;dev=local} results in a {@code Map} with
     * the entry key {@code prod} and entry value {@code Collection} with the values {@code alpha} and {@code beta},
     * and the entry key {@code dev} and entry value {@code Collection} with the value {@code local}, considering the
     * configuration name as {@code server.env}, and {@linkplain Converters Converters} to convert the key type as a
     * {@code String} and the property type as a {@code String}.
     * <p>
     * The keyed indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyConverter The {@link Converter} to use to convert the resolved property keys
     * @param valueConverter The {@link Converter} to use to convert the resolved property values
     * @param mapFactory the resulting instance of a {@code Map} to return the property keys and values
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Map} of keys of the property name and values as a
     *         {@code Collections} of instances of the property type
     * @param <K> the key type
     * @param <V> the value type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Class, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter, IntFunction, IntFunction)
     */
    public <K, V, C extends Collection<V>> Map<K, C> getValues(
            final String name,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter,
            final IntFunction<Map<K, C>> mapFactory,
            final IntFunction<C> collectionFactory) {

        Map<String, String> keys = getMapIndexedKeys(name);
        if (keys.isEmpty()) {
            // Try legacy MapConverter
            return getValue(name,
                    newMapConverter(keyConverter, newCollectionConverter(valueConverter, collectionFactory), mapFactory));
        }

        return getMapIndexedValues(keys, keyConverter, valueConverter, mapFactory, collectionFactory);
    }

    public Map<String, String> getMapKeys(final String name) {
        Map<String, String> keys = new HashMap<>();
        for (String propertyName : getPropertyNames()) {
            if (propertyName.length() > name.length() + 1
                    && (name.isEmpty() || propertyName.charAt(name.length()) == '.')
                    && propertyName.startsWith(name)) {
                String key = unquoted(propertyName, name.isEmpty() ? 0 : name.length() + 1);
                keys.put(key, propertyName);
            }
        }
        return keys;
    }

    <K, V> Map<K, V> getMapValues(
            final Map<String, String> keys,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter,
            final IntFunction<Map<K, V>> mapFactory) {

        Map<K, V> map = mapFactory.apply(keys.size());
        for (Entry<String, String> entry : keys.entrySet()) {
            // Use ConfigValue when converting the key to have proper error messages with the key name
            K key = convertValue(ConfigValue.builder().withName(entry.getKey()).withValue(entry.getKey()).build(),
                    keyConverter);
            V value = getValue(entry.getValue(), valueConverter);
            map.put(key, value);
        }
        return map;
    }

    public Map<String, String> getMapIndexedKeys(final String name) {
        Map<String, String> keys = new HashMap<>();
        for (String propertyName : getPropertyNames()) {
            if (propertyName.length() > name.length() + 1
                    && (name.isEmpty() || propertyName.charAt(name.length()) == '.')
                    && propertyName.startsWith(name)) {
                String unindexedName = unindexed(propertyName);
                String key = unquoted(unindexedName, name.isEmpty() ? 0 : name.length() + 1);
                keys.put(key, unindexedName);
            }
        }
        return keys;
    }

    <K, V, C extends Collection<V>> Map<K, C> getMapIndexedValues(
            final Map<String, String> keys,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter,
            final IntFunction<Map<K, C>> mapFactory,
            final IntFunction<C> collectionFactory) {

        Map<K, C> map = mapFactory.apply(keys.size());
        for (Entry<String, String> entry : keys.entrySet()) {
            // Use ConfigValue when converting the key to have proper error messages with the key name
            K key = convertValue(ConfigValue.builder().withName(entry.getKey()).withValue(entry.getKey()).build(),
                    keyConverter);
            C value = getValues(entry.getValue(), valueConverter, collectionFactory);
            map.put(key, value);
        }
        return map;
    }

    /**
     * Returns the value for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * If the requested type is a type array, the lookup to the configuration will first query
     * {@link SmallRyeConfig#getPropertyNames()} for indexed properties. An indexed property uses the original property
     * name with square brackets and an index in between, as {@code my.property[0]}. All indexed properties are
     * queried for their value, which represents a single element in the returning type array converted to their
     * specified property type. The following configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in an array with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in an array with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param propertyType The type into which the resolved property value is converted
     * @return the resolved property value as an instance of the property type
     * @param <T> the property type
     *
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValue(String, Converter)
     * @see SmallRyeConfig#getOptionalValue(String, Class)
     * @see SmallRyeConfig#getOptionalValue(String, Converter)
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T getValue(final String name, final Class<T> propertyType) {
        if (propertyType.isArray()) {
            List<String> indexedProperties = getIndexedProperties(name);
            if (indexedProperties.isEmpty()) {
                // Try legacy / MP comma separated values
                return getValue(name, requireConverter(propertyType));
            }

            // Check ordinality of indexed
            int indexedOrdinality = Integer.MIN_VALUE;
            Object array = Array.newInstance(propertyType.getComponentType(), indexedProperties.size());
            for (int i = 0; i < indexedProperties.size(); i++) {
                final String indexedProperty = indexedProperties.get(i);
                ConfigValue indexed = getConfigValue(indexedProperty);
                if (indexed.getConfigSourceOrdinal() >= indexedOrdinality) {
                    indexedOrdinality = indexed.getConfigSourceOrdinal();
                }
                Array.set(array, i, convertValue(indexed, requireConverter(propertyType.getComponentType())));
            }

            // Use indexed if comma separated empty or higher in ordinality
            ConfigValue commaSeparated = getConfigValue(name);
            if (commaSeparated.getValue() == null || indexedOrdinality >= commaSeparated.getConfigSourceOrdinal()) {
                return (T) array;
            } else {
                return convertValue(commaSeparated, requireConverter(propertyType));
            }
        }
        return getValue(name, requireConverter(propertyType));
    }

    /**
     * Returns the value for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * If the requested type is a type array, the lookup to the configuration will first query
     * {@link SmallRyeConfig#getPropertyNames()} for indexed properties. An indexed property uses the original property
     * name with square brackets and an index in between, as {@code my.property[0]}. All indexed properties are
     * queried for their value, which represents a single element in the returning type array converted by the
     * specified {@link Converter}. The following configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in an array with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the {@link Converter} to convert the property
     * type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in an array with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the {@link Converter} to convert the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param converter The {@link Converter} to use to convert the resolved property value
     * @return the resolved property value as an instance of the property type
     * @param <T> the property type
     *
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getValue(String, Class)
     * @see SmallRyeConfig#getOptionalValue(String, Class)
     * @see SmallRyeConfig#getOptionalValue(String, Converter)
     */
    @SuppressWarnings("unchecked")
    public <T> T getValue(final String name, final Converter<T> converter) {
        ConfigValue configValue = getConfigValue(name);
        if (Converters.CONFIG_VALUE_CONVERTER.equals(converter)) {
            return (T) configValue.noProblems();
        }

        if (converter instanceof Converters.OptionalConverter<?>) {
            if (Converters.CONFIG_VALUE_CONVERTER.equals(
                    ((Converters.OptionalConverter<?>) converter).getDelegate())) {
                return (T) Optional.of(configValue.noProblems());
            }
        }

        return convertValue(configValue, converter);
    }

    public <T> T convertValue(ConfigValue configValue, Converter<T> converter) {
        if (configValue.hasProblems()) {
            if (Converters.isOptionalConverter(converter)) {
                configValue = configValue.noProblems();
            } else {
                ConfigValidationException.Problem problem = configValue.getProblems().get(0);
                Optional<RuntimeException> exception = problem.getException();
                if (exception.isPresent()) {
                    throw exception.get();
                }
            }
        }

        final T converted;

        if (configValue.getValue() != null) {
            try {
                converted = converter.convert(configValue.getValue());
            } catch (IllegalArgumentException e) {
                throw ConfigMessages.msg.converterException(e, configValue.getNameProfiled(), configValue.getValue(),
                        e.getLocalizedMessage());
            }
        } else {
            try {
                // See if the Converter is designed to handle a missing (null) value i.e. Optional Converters
                converted = converter.convert("");
            } catch (IllegalArgumentException ignored) {
                throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(configValue.getNameProfiled()));
            }
        }

        if (converted == null) {
            if (configValue.getValue() == null) {
                throw new NoSuchElementException(ConfigMessages.msg.propertyNotFound(configValue.getNameProfiled()));
            } else if (configValue.getValue().isEmpty()) {
                throw ConfigMessages.msg.propertyEmptyString(configValue.getNameProfiled(), converter.getClass().getTypeName());
            } else {
                throw ConfigMessages.msg.converterReturnedNull(configValue.getNameProfiled(), configValue.getValue(),
                        converter.getClass().getTypeName());
            }
        }

        return converted;
    }

    /**
     * Returns the {@link ConfigValue} for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup of the configuration is performed immediately, meaning that calls to {@link ConfigValue} will always
     * yield the same results.
     * <p>
     * A {@link ConfigValue} is always returned even if a property name cannot be found. In this case, every method in
     * {@link ConfigValue} returns {@code null}, or the default value for primitive types, except for
     * {@link ConfigValue#getName()}, which includes the original property name being looked up.
     *
     * @param name The configuration property name to look for in the configuration
     * @return the resolved property value as a {@link ConfigValue}
     */
    @Override
    public ConfigValue getConfigValue(final String name) {
        final ConfigValue configValue = configSources.getInterceptorChain().proceed(name);
        return configValue != null ? configValue : ConfigValue.builder().withName(name).build();
    }

    @Deprecated
    public String getRawValue(String name) {
        final ConfigValue configValue = getConfigValue(name);
        return configValue != null ? configValue.getValue() : null;
    }

    /**
     * Returns the value for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * If the requested type is a type array, the lookup to the configuration will first query
     * {@link SmallRyeConfig#getPropertyNames()} for indexed properties. An indexed property uses the original property
     * name with square brackets and an index in between, as {@code my.property[0]}. All indexed properties are
     * queried for their value, which represents a single element in the returning type array converted to their
     * specified property type. The following configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in an array with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in an array with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param propertyType The type into which the resolved property value is converted
     * @return the resolved property value as a {@code Optional} instance of the property type
     * @param <T> the property type
     *
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     *
     * @see SmallRyeConfig#getOptionalValue(String, Converter)
     * @see SmallRyeConfig#getValue(String, Class)
     * @see SmallRyeConfig#getValue(String, Converter)
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> Optional<T> getOptionalValue(String name, Class<T> propertyType) {
        if (propertyType.isArray()) {
            List<String> indexedProperties = getIndexedProperties(name);
            if (indexedProperties.isEmpty()) {
                // Try legacy / MP comma separated values
                return getValue(name, getOptionalConverter(propertyType));
            }

            // Check ordinality of indexed
            int indexedOrdinality = Integer.MIN_VALUE;
            Object array = Array.newInstance(propertyType.getComponentType(), indexedProperties.size());
            for (int i = 0; i < indexedProperties.size(); i++) {
                final String indexedProperty = indexedProperties.get(i);
                ConfigValue indexed = getConfigValue(indexedProperty);
                if (indexed.getConfigSourceOrdinal() >= indexedOrdinality) {
                    indexedOrdinality = indexed.getConfigSourceOrdinal();
                }
                Array.set(array, i, convertValue(indexed, requireConverter(propertyType.getComponentType())));
            }

            // Use indexed if comma separated empty or higher in ordinality
            ConfigValue commaSeparated = getConfigValue(name);
            if (commaSeparated.getValue() == null || indexedOrdinality >= commaSeparated.getConfigSourceOrdinal()) {
                return (Optional<T>) Optional.of(array);
            } else {
                return getValue(name, getOptionalConverter(propertyType));
            }
        }
        return getValue(name, getOptionalConverter(propertyType));
    }

    /**
     * Returns the value for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * If the requested type is a type array, the lookup to the configuration will first query
     * {@link SmallRyeConfig#getPropertyNames()} for indexed properties. An indexed property uses the original property
     * name with square brackets and an index in between, as {@code my.property[0]}. All indexed properties are
     * queried for their value, which represents a single element in the returning type array converted by the
     * specified {@link Converter}. The following configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in an array with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the {@link Converter} to convert the property
     * type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in an array with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the {@link Converter} to convert the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param converter The {@link Converter} to use to convert the resolved property value
     * @return the resolved property value as a {@code Optional} instance of the property type
     * @param <T> the property type
     *
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     *
     * @see SmallRyeConfig#getValue(String, Class)
     * @see SmallRyeConfig#getOptionalValue(String, Class)
     * @see SmallRyeConfig#getOptionalValue(String, Converter)
     */
    public <T> Optional<T> getOptionalValue(final String name, final Converter<T> converter) {
        return getValue(name, newOptionalConverter(converter));
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for indexed
     * properties. An indexed property uses the original property name with square brackets and an index in between, as
     * {@code my.property[0]}. All indexed properties are queried for their value, which represents a single
     * element in the returning {@code Optional<List>} converted to their specified property type. The following
     * configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in an {@code Optional<List>} with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in a {@code Optional<List>} with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param propertyType The type into which the resolved property values are converted
     * @return the resolved property values as a {@code Optional<List>} of instances of the property type
     * @param <T> the item type
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     *
     * @see SmallRyeConfig#getOptionalValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     * @see SmallRyeConfig#getValues(String, Class)
     * @see SmallRyeConfig#getValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getValues(String, Converter, IntFunction)
     */
    @Override
    public <T> Optional<List<T>> getOptionalValues(final String name, final Class<T> propertyType) {
        return getOptionalValues(name, propertyType, ArrayList::new);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for indexed
     * properties. An indexed property uses the original property name with square brackets and an index in between, as
     * {@code my.property[0]}. All indexed properties are queried for their value, which represents a single
     * element in the returning {@code Optional<Collection>} converted to their specified property type. The following
     * configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in a {@code Optional<Collection>} with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in a {@code Optional<Collection>} with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param itemClass The type into which the resolved property values are converted
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Optional<Collection>} of instances of the property type
     * @param <T> the item type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, IntFunction)
     * @see SmallRyeConfig#getValues(String, Class)
     * @see SmallRyeConfig#getValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getValues(String, Converter, IntFunction)
     */
    public <T, C extends Collection<T>> Optional<C> getOptionalValues(final String name, final Class<T> itemClass,
            final IntFunction<C> collectionFactory) {
        return getOptionalValues(name, requireConverter(itemClass), collectionFactory);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for indexed
     * properties. An indexed property uses the original property name with square brackets and an index in between, as
     * {@code my.property[0]}. All indexed properties are queried for their value, which represents a single
     * element in the returning {@code Optional<Collection>} converted by the specified {@link Converter}. The following
     * configuration:
     * <ul>
     * <li>my.property[0]=dog</li>
     * <li>my.property[1]=cat</li>
     * <li>my.property[2]=turtle</li>
     * </ul>
     * <p>
     * Results in a {@code Optional<Collection>} with the elements {@code dog}, {@code cat}, and {@code turtle},
     * considering the configuration name as {@code my.property} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element that a comma-separated string ({@code ,}) can represent,
     * and split into multiple elements with the backslash ({@code \}) as the escape character.
     * A configuration of {@code my.property=dog,cat,turtle} results in a {@code Optional<Collection>} with the elements
     * {@code dog}, {@code cat}, and {@code turtle}, considering the configuration name as
     * {@code my.property} and the property type as a {@code String}.
     * <p>
     * The indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param converter The {@link Converter} to use to convert the resolved property values
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Optional<Collection>} of instances of the property type
     * @param <T> the item type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property values cannot be converted to the specified type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getOptionalValues(String, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Class IntFunction)
     * @see SmallRyeConfig#getValues(String, Class)
     * @see SmallRyeConfig#getValues(String, Class, IntFunction)
     * @see SmallRyeConfig#getValues(String, Converter, IntFunction)
     */
    public <T, C extends Collection<T>> Optional<C> getOptionalValues(final String name, final Converter<T> converter,
            final IntFunction<C> collectionFactory) {
        List<String> indexedProperties = getIndexedProperties(name);
        if (indexedProperties.isEmpty()) {
            // Try legacy / MP comma separated values
            return getOptionalValue(name, newCollectionConverter(converter, collectionFactory));
        }

        // Check ordinality of indexed
        int indexedOrdinality = Integer.MIN_VALUE;
        C collection = collectionFactory.apply(indexedProperties.size());
        for (String indexedProperty : indexedProperties) {
            ConfigValue indexed = getConfigValue(indexedProperty);
            if (indexed.getValue() != null && indexed.getConfigSourceOrdinal() >= indexedOrdinality) {
                indexedOrdinality = indexed.getConfigSourceOrdinal();
            }
            convertValue(indexed, newOptionalConverter(converter)).ifPresent(collection::add);
        }

        // Use indexed if comma separated empty or higher in ordinality
        ConfigValue commaSeparated = getConfigValue(name);
        if (commaSeparated.getValue() == null || indexedOrdinality >= commaSeparated.getConfigSourceOrdinal()) {
            return collection.isEmpty() ? Optional.empty() : Optional.of(collection);
        } else {
            return getOptionalValue(name, newCollectionConverter(converter, collectionFactory));
        }
    }

    @Deprecated(forRemoval = true)
    public <T, C extends Collection<T>> Optional<C> getIndexedOptionalValues(String name, Converter<T> converter,
            IntFunction<C> collectionFactory) {
        List<String> indexedProperties = getIndexedProperties(name);
        if (indexedProperties.isEmpty()) {
            return Optional.empty();
        }

        C collection = collectionFactory.apply(indexedProperties.size());
        for (String indexedProperty : indexedProperties) {
            Optional<T> optionalValue = getOptionalValue(indexedProperty, converter);
            optionalValue.ifPresent(collection::add);
        }

        return collection.isEmpty() ? Optional.empty() : Optional.of(collection);
    }

    /**
     * Returns the values for the specified configuration name from the underlyingx
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed
     * properties. A keyed property uses the original property name plus an additional dotted segment to represent
     * a {@code Map} key, as {@code my.property.key}, where {@code my.property} is the property name and {@code key}
     * is the {@code Map} key. All keyed properties are queried for their values, which represent a single entry in the
     * returning {@code Optional<Map>} converting both the key and value to their specified types.
     * The following configuration:
     * <ul>
     * <li>server.reasons.200=OK</li>
     * <li>server.reasons.201=CREATED</li>
     * <li>server.reasons.404=NOT_FOUND</li>
     * </ul>
     * <p>
     * Results in a {@code Optional<Map>} with the entries {@code 200=OK}, {@code 201=CREATED}, and
     * {@code 404=NOT_FOUND}, considering the configuration name as {@code server.reasons}, the key type as
     * {@code Integer} and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} with the backslash ({@code \}) as
     * the escape character.
     * A configuration of {@code server.reasons=200=OK;201=CREATED;404=NOT_FOUND} results in a {@code Optional<Map>}
     * with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND}, considering the configuration
     * name as {@code server.reasons}, the key type as {@code Integer} and the property type as a {@code String}.
     * <p>
     * The keyed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyClass The type into which the resolved property keys are converted
     * @param valueClass The type into which the resolved property values are converted
     * @return the resolved property values as a {@code Optional<Map>} of keys of the property name and values of the
     *         property type
     * @param <K> the key type
     * @param <V> the value type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter, IntFunction)
     * @see SmallRyeConfig#getValues(String, Class, Class)
     * @see SmallRyeConfig#getValues(String, Converter, Converter)
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction)
     */
    public <K, V> Optional<Map<K, V>> getOptionalValues(
            final String name,
            final Class<K> keyClass,
            final Class<V> valueClass) {
        return getOptionalValues(name, requireConverter(keyClass), requireConverter(valueClass));
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed
     * properties. A keyed property uses the original property name plus an additional dotted segment to represent
     * a {@code Map} key, as {@code my.property.key}, where {@code my.property} is the property name and {@code key}
     * is the {@code Map} key. All keyed properties are queried for their values, which represent a single entry in the
     * returning {@code Optional<Map>} converting both the key and value using the specified
     * {@linkplain Converters Converters}. The following configuration:
     * <ul>
     * <li>server.reasons.200=OK</li>
     * <li>server.reasons.201=CREATED</li>
     * <li>server.reasons.404=NOT_FOUND</li>
     * </ul>
     * <p>
     * Results in a {@code Optional<Map>} with the entries {@code 200=OK}, {@code 201=CREATED}, and
     * {@code 404=NOT_FOUND}, considering the configuration name as {@code server.reasons} and
     * {@linkplain Converters Converters} to convert the key type as an {@code Integer} and the property type as a
     * {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} with the backslash ({@code \}) as
     * the escape character.
     * A configuration of {@code server.reasons=200=OK;201=CREATED;404=NOT_FOUND} results in a {@code Optional<Map>}
     * with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND}, considering the configuration
     * name as {@code server.reasons} and {@linkplain Converters Converters} to convert the key type as an
     * {@code Integer} and the property type as a {@code String}.
     * <p>
     * The keyed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyConverter The {@link Converter} to use to convert the resolved property keys
     * @param valueConverter The {@link Converter} to use to convert the resolved property values
     * @return the resolved property values as a {@code Optional<Map>} of keys of the property name and values of the
     *         property type
     * @param <K> the key type
     * @param <V> the value type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getOptionalValues(String, Class, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter, IntFunction)
     * @see SmallRyeConfig#getValues(String, Class, Class)
     * @see SmallRyeConfig#getValues(String, Converter, Converter)
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction)
     */
    public <K, V> Optional<Map<K, V>> getOptionalValues(
            final String name,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter) {
        return getOptionalValues(name, keyConverter, valueConverter, HashMap::new);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed
     * properties. A keyed property uses the original property name plus an additional dotted segment to represent
     * a {@code Map} key, as {@code my.property.key}, where {@code my.property} is the property name and {@code key}
     * is the {@code Map} key. All keyed properties are queried for their values, which represent a single entry in the
     * returning {@code Optional<Map>} converting both the key and value using the specified
     * {@linkplain Converters Converters}. The following configuration:
     * <ul>
     * <li>server.reasons.200=OK</li>
     * <li>server.reasons.201=CREATED</li>
     * <li>server.reasons.404=NOT_FOUND</li>
     * </ul>
     * <p>
     * Results in a {@code Optional<Map>} with the entries {@code 200=OK}, {@code 201=CREATED}, and
     * {@code 404=NOT_FOUND}, considering the configuration name as {@code server.reasons} and
     * {@linkplain Converters Converters} to convert the key type as an {@code Integer} and the property type as a
     * {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} with the backslash ({@code \}) as
     * the escape character.
     * A configuration of {@code server.reasons=200=OK;201=CREATED;404=NOT_FOUND} results in a {@code Optional<Map>}
     * with the entries {@code 200=OK}, {@code 201=CREATED}, and {@code 404=NOT_FOUND}, considering the configuration
     * name as {@code server.reasons} and {@linkplain Converters Converters} to convert the key type as an
     * {@code Integer} and the property type as a {@code String}.
     * <p>
     * The keyed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyConverter The {@link Converter} to use to convert the resolved property keys
     * @param valueConverter The {@link Converter} to use to convert the resolved property values
     * @param mapFactory the resulting instance of a {@code Map} to return the property keys and values
     * @return the resolved property values as a {@code Optional<Map>} of keys of the property name and values of the
     *         property type
     * @param <K> the key type
     * @param <V> the value type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     * @throws java.util.NoSuchElementException if the property isn't present in the configuration or is defined as
     *         an empty string, or the converter returns {@code null}
     *
     * @see SmallRyeConfig#getOptionalValues(String, Class, Class)
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter)
     * @see SmallRyeConfig#getValues(String, Class, Class)
     * @see SmallRyeConfig#getValues(String, Converter, Converter)
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction)
     */
    public <K, V> Optional<Map<K, V>> getOptionalValues(
            final String name,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter,
            final IntFunction<Map<K, V>> mapFactory) {

        Map<String, String> keys = getMapKeys(name);
        if (keys.isEmpty()) {
            // Try legacy MapConverter
            return getOptionalValue(name, newMapConverter(keyConverter, valueConverter, mapFactory));
        }

        return Optional.of(getMapValues(keys, keyConverter, valueConverter, mapFactory));
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed indexed
     * properties. A keyed indexed property uses the original property name plus an additional dotted segment to
     * represent a {@code Map} key followed by square brackets and an index in between, as {@code my.property.key[0]},
     * where {@code my.property} is the property name, {@code key} is the {@code Map} key and {code [0]} the index of
     * the {@code Collection} element. All keyed indexed properties are queried for their value, which represent
     * a single entry in the returning {@code Optional<Map>}, and single element in the {@code Collection} value,
     * converting both the key and value to their specified types. The following configuration:
     * <ul>
     * <li>server.env.prod[0]=alpha</li>
     * <li>server.env.prod[1]=beta</li>
     * <li>server.env.dev[0]=local</li>
     * </ul>
     * <p>
     * Results in a {@code Optional<Map>} with the entry key {@code prod} and entry value {@code Collection} with the
     * values {@code alpha} and {@code beta}, and the entry key {@code dev} and entry value {@code Collection} with the
     * value {@code local}, considering the configuration name as {@code server.env}, the key type as a {@code String},
     * and the property type as a {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} and value as a comma-separated
     * string ({@code ,}) can represent, and split into multiple elements with the backslash ({@code \}) as the
     * escape character. A configuration of {@code server.env=prod=alpha,beta;dev=local} results in a
     * {@code Optional<Map>} with the entry key {@code prod} and entry value {@code Collection} with the values
     * {@code alpha} and {@code beta}, and the entry key {@code dev} and entry value {@code Collection} with the value
     * {@code local}, considering the configuration name as {@code server.env}, the key type as a {@code String}, and
     * the property type as a {@code String}.
     * <p>
     * The keyed indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyClass The type into which the resolved property keys are converted
     * @param valueClass The type into which the resolved property values are converted
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Optional<Map>} of keys of the property name and values as a
     *         {@code Collections} of instances of the property type
     * @param <K> the key type
     * @param <V> the value type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     *
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter, IntFunction, IntFunction)
     * @see SmallRyeConfig#getValues(String, Class, Class, IntFunction)
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction, IntFunction)
     */
    public <K, V, C extends Collection<V>> Optional<Map<K, C>> getOptionalValues(
            final String name,
            final Class<K> keyClass,
            final Class<V> valueClass,
            final IntFunction<C> collectionFactory) {
        return getOptionalValues(name, requireConverter(keyClass), requireConverter(valueClass), HashMap::new,
                collectionFactory);
    }

    /**
     * Returns the values for the specified configuration name from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * The lookup to the configuration will first query {@link SmallRyeConfig#getPropertyNames()} for keyed indexed
     * properties. A keyed indexed property uses the original property name plus an additional dotted segment to
     * represent a {@code Map} key followed by square brackets and an index in between, as {@code my.property.key[0]},
     * where {@code my.property} is the property name, {@code key} is the {@code Map} key and {code [0]} the index of
     * the {@code Collection} element. All keyed indexed properties are queried for their value, which represent
     * a single entry in the returning {@code Optional<Map>}, and single element in the {@code Collection} value,
     * converting both the key and value using the specified {@linkplain Converters Converters}. The following
     * configuration:
     * <ul>
     * <li>server.env.prod[0]=alpha</li>
     * <li>server.env.prod[1]=beta</li>
     * <li>server.env.dev[0]=local</li>
     * </ul>
     * <p>
     * Results in a {@code Optional<Map>} with the entry key {@code prod} and entry value {@code Collection} with the
     * values {@code alpha} and {@code beta}, and the entry key {@code dev} and entry value {@code Collection} with the
     * value {@code local}, considering the configuration name as {@code server.env} and
     * {@linkplain Converters Converters} to convert the key type as a {@code String} and the property type as a
     * {@code String}.
     * <p>
     * Otherwise, the configuration value is a single element represented by key value pairs as
     * {@code <key1>=<value1>;<key2>=<value2>...} separated by a semicolon {@code ;} and value as a comma-separated
     * string ({@code ,}) can represent, and split into multiple elements with the backslash ({@code \}) as the
     * escape character. A configuration of {@code server.env=prod=alpha,beta;dev=local} results in a
     * {@code Optional<Map>} with the entry key {@code prod} and entry value {@code Collection} with the values
     * {@code alpha} and {@code beta}, and the entry key {@code dev} and entry value {@code Collection} with the value
     * {@code local}, considering the configuration name as {@code server.env}, and {@linkplain Converters Converters}
     * to convert the key type as a {@code String} and the property type as a {@code String}.
     * <p>
     * The keyed indexed property format has priority when both styles are found in the same configuration source. When
     * available in multiple sources, the higher ordinal source wins, like any other configuration lookup.
     *
     * @param name The configuration property name to look for in the configuration
     * @param keyConverter The {@link Converter} to use to convert the resolved property keys
     * @param valueConverter The {@link Converter} to use to convert the resolved property values
     * @param mapFactory the resulting instance of a {@code Map} to return the property keys and values
     * @param collectionFactory the resulting instance of a {@code Collection} to return the property values
     * @return the resolved property values as a {@code Optional<Map>} of keys of the property name and values as a
     *         {@code Collections} of instances of the property type
     * @param <K> the key type
     * @param <V> the value type
     * @param <C> the collection type
     * @throws java.lang.IllegalArgumentException if the property keys or values cannot be converted to the specified
     *         type
     *
     * @see SmallRyeConfig#getOptionalValues(String, Converter, Converter, IntFunction, IntFunction)
     * @see SmallRyeConfig#getValues(String, Class, Class, IntFunction)
     * @see SmallRyeConfig#getValues(String, Converter, Converter, IntFunction, IntFunction)
     */
    public <K, V, C extends Collection<V>> Optional<Map<K, C>> getOptionalValues(
            final String name,
            final Converter<K> keyConverter,
            final Converter<V> valueConverter,
            final IntFunction<Map<K, C>> mapFactory,
            final IntFunction<C> collectionFactory) {

        Map<String, String> keys = getMapIndexedKeys(name);
        if (keys.isEmpty()) {
            // Try legacy MapConverter
            return getOptionalValue(name,
                    newMapConverter(keyConverter, newCollectionConverter(valueConverter, collectionFactory), mapFactory));
        }

        return Optional.of(getMapIndexedValues(keys, keyConverter, valueConverter, mapFactory, collectionFactory));
    }

    ConfigValidator getConfigValidator() {
        return configValidator;
    }

    Map<Class<?>, Map<String, Object>> getMappings() {
        return mappings;
    }

    /**
     * Returns an instance of a {@link ConfigMapping} annotated type, mapping all the configuration names matching the
     * {@link ConfigMapping#prefix()} and the {@link ConfigMapping} members to values from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * {@linkplain ConfigMapping ConfigMapping} instances are cached. They are populated when the
     * {@link SmallRyeConfig} instance is initialized and their values are not updated on
     * {@linkplain ConfigSource configuration sources} changes.
     *
     * @param type an interface annotated with {@link ConfigMapping}
     * @return an instance of a {@link ConfigMapping} annotated type
     * @param <T> the type of the {@link ConfigMapping}
     * @throws ConfigValidationException if the mapping names or values cannot be converter to the specified types, if
     *         the properties values are not present, defined as an empty string, or the conversion returns {@code null}
     *
     * @see SmallRyeConfig#getConfigMapping(Class, String)
     */
    public <T> T getConfigMapping(final Class<T> type) {
        String prefix;
        if (type.isInterface()) {
            ConfigMapping configMapping = type.getAnnotation(ConfigMapping.class);
            prefix = configMapping != null ? configMapping.prefix() : "";
        } else {
            ConfigProperties configProperties = type.getAnnotation(ConfigProperties.class);
            prefix = configProperties != null ? configProperties.prefix() : "";
        }
        return getConfigMapping(type, prefix);
    }

    /**
     * Returns an instance of a {@link ConfigMapping} annotated type, mapping all the configuration names matching the
     * prefix and the {@link ConfigMapping} members to values from the underlying
     * {@linkplain ConfigSource configuration sources}.
     * <p>
     * {@linkplain ConfigMapping ConfigMapping} instances are cached. They are populated when the
     * {@link SmallRyeConfig} instance is initialized and their values are not updated on
     * {@linkplain ConfigSource configuration sources} changes.
     *
     * @param type an interface annotated with {@link ConfigMapping}
     * @param prefix the prefix to override {@link ConfigMapping#prefix()}
     * @return an instance of a {@link ConfigMapping} annotated type
     * @param <T> the type of the {@link ConfigMapping}
     * @throws ConfigValidationException if the mapping names or values cannot be converter to the specified types, if
     *         the properties values are not present, defined as an empty string, or the conversion returns {@code null}
     *
     * @see SmallRyeConfig#getConfigMapping(Class)
     */
    public <T> T getConfigMapping(final Class<T> type, final String prefix) {
        if (prefix == null) {
            return getConfigMapping(type);
        }

        Map<String, Object> mappingsForType = mappings.get(getConfigMappingClass(type));
        if (mappingsForType == null) {
            throw ConfigMessages.msg.mappingNotFound(type.getName());
        }

        Object configMappingObject = mappingsForType.get(prefix);
        if (configMappingObject == null) {
            throw ConfigMessages.msg.mappingPrefixNotFound(type.getName(), prefix);
        }

        return type.cast(configMappingObject);
    }

    /**
     * {@inheritDoc}
     *
     * This implementation caches the list of property names collected when {@link SmallRyeConfig} is built via
     * {@link SmallRyeConfigBuilder#build()}.
     *
     * @return the cached names of all configured keys of the underlying configuration
     * @see SmallRyeConfig#getLatestPropertyNames()
     */
    @Override
    public Iterable<String> getPropertyNames() {
        return configSources.getPropertyNames().get();
    }

    /**
     * Provides a way to retrieve an updated list of all property names. The updated list replaces the cached list
     * returned by {@link SmallRyeConfig#getPropertyNames()}.
     *
     * @return the names of all configured keys of the underlying configuration
     */
    public Iterable<String> getLatestPropertyNames() {
        return configSources.getPropertyNames().latest();
    }

    /**
     * Checks if a property is present in the {@link Config} instance.
     * <br>
     * Because {@link ConfigSource#getPropertyNames()} may not include all available properties, it is not possible to
     * reliably determine if the property is present in the properties list. The property needs to be retrieved to make
     * sure it exists. The lookup is done without expression expansion, because the expansion value may not be
     * available, and it is not relevant for the final check.
     *
     * @param name the property name.
     * @return true if the property is present or false otherwise.
     */
    public boolean isPropertyPresent(String name) {
        return Expressions.withoutExpansion(() -> {
            ConfigValue configValue = SmallRyeConfig.this.getConfigValue(name);
            return configValue.getValue() != null && !configValue.getValue().isEmpty();
        });
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Iterable<ConfigSource> getConfigSources() {
        return configSources.getSources();
    }

    /**
     * Return the currently registered {@linkplain ConfigSource configuration sources} in {@link SmallRyeConfig} that
     * match the specified type
     * <p>
     * The returned sources will be sorted by descending ordinal value and name, which can be iterated in a thread-safe
     * manner. The {@link Iterable} contains a fixed number of {@linkplain ConfigSource configuration
     * sources}, determined at configuration initialization, and the config sources themselves may be static or dynamic.
     *
     * @param type The type of the {@link ConfigSource} to look for in the configuration
     * @return an {@link Iterable} of {@linkplain ConfigSource configuration sources}
     */
    public Iterable<ConfigSource> getConfigSources(final Class<?> type) {
        final List<ConfigSource> configSourcesByType = new ArrayList<>();
        for (ConfigSource configSource : getConfigSources()) {
            if (type.isAssignableFrom(configSource.getClass())) {
                configSourcesByType.add(configSource);
            }
        }
        return configSourcesByType;
    }

    /**
     * Return the first registered {@linkplain ConfigSource configuration sources} in {@link SmallRyeConfig} that
     * match the specified name, sorted by descending ordinal value and name.
     * <p>
     *
     * @param name the {{@linkplain ConfigSource} name to look for in the configuration
     * @return an {@link Optional} of a {@linkplain ConfigSource}, or an empty {@link Optional} if no
     *         {@linkplain ConfigSource} matches the specified name.
     */
    public Optional<ConfigSource> getConfigSource(final String name) {
        for (ConfigSource configSource : getConfigSources()) {
            final String configSourceName = configSource.getName();
            if (configSourceName != null && configSourceName.equals(name)) {
                return Optional.of(configSource);
            }
        }
        return Optional.empty();
    }

    DefaultValuesConfigSource getDefaultValues() {
        return configSources.defaultValues;
    }

    @Deprecated
    public <T> T convert(String value, Class<T> asType) {
        return value != null ? requireConverter(asType).convert(value) : null;
    }

    private <T> Converter<Optional<T>> getOptionalConverter(Class<T> asType) {
        Converter<Optional<T>> converter = recast(optionalConverters.get(asType));
        if (converter == null) {
            converter = newOptionalConverter(requireConverter(asType));
            Converter<Optional<T>> appearing = recast(optionalConverters.putIfAbsent(asType, recast(converter)));
            if (appearing != null) {
                converter = appearing;
            }
        }
        return converter;
    }

    @SuppressWarnings("unchecked")
    private static <T> T recast(Object obj) {
        return (T) obj;
    }

    @Deprecated // binary-compatibility bridge method for Quarkus
    public <T> Converter<T> getConverter$$bridge(Class<T> asType) {
        return requireConverter(asType);
    }

    // @Override // in MP Config 2.0+
    /**
     * {@inheritDoc}
     */
    public <T> Optional<Converter<T>> getConverter(Class<T> asType) {
        return Optional.ofNullable(getConverterOrNull(asType));
    }

    /**
     * Return the {@link Converter} used by this instance to produce instances of the specified type from
     * {@code String} values.
     *
     * @param asType the type to be produced by the converter
     * @return an instance of the {@link Converter} the specified type
     * @param <T> the conversion type
     * @throws java.lang.IllegalArgumentException if no {@link Converter} is registered for the specified type
     */
    public <T> Converter<T> requireConverter(final Class<T> asType) {
        final Converter<T> conv = getConverterOrNull(asType);
        if (conv == null) {
            throw ConfigMessages.msg.noRegisteredConverter(asType);
        }
        return conv;
    }

    @SuppressWarnings("unchecked")
    <T> Converter<T> getConverterOrNull(Class<T> asType) {
        final Converter<?> exactConverter = converters.get(asType);
        if (exactConverter != null) {
            return (Converter<T>) exactConverter;
        }
        if (asType.isPrimitive()) {
            return (Converter<T>) getConverterOrNull(Converters.wrapPrimitiveType(asType));
        }
        if (asType.isArray()) {
            final Converter<?> conv = getConverterOrNull(asType.getComponentType());
            return conv == null ? null : Converters.newArrayConverter(conv, asType);
        }
        return (Converter<T>) converters.computeIfAbsent(asType, clazz -> Converters.Implicit.getConverter((Class<?>) clazz));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public <T> T unwrap(final Class<T> type) {
        if (Config.class.isAssignableFrom(type)) {
            return type.cast(this);
        }

        throw ConfigMessages.msg.getTypeNotSupportedForUnwrapping(type);
    }

    /**
     * Returns a {@code List} of the active profiles in {@link SmallRyeConfig}.
     * <p>
     * Profiles are sorted in reverse order according to how they were set in
     * {@link SmallRyeConfig#SMALLRYE_CONFIG_PROFILE}, as the last profile overrides the previous one until there are
     * no profiles left in the list.
     *
     * @return a {@code List} of the active profiles
     */
    public List<String> getProfiles() {
        return configSources.getProfiles();
    }

    private static class ConfigSources implements Serializable {
        @Serial
        private static final long serialVersionUID = 3483018375584151712L;

        private final List<String> profiles;
        private final List<ConfigSource> sources;
        private final DefaultValuesConfigSource defaultValues;
        private final ConfigSourceInterceptorContext interceptorChain;
        private final PropertyNames propertyNames;

        /**
         * Builds a representation of Config Sources, Interceptors and the Interceptor chain to be used in Config. Note
         * that this constructor must be used when the Config object is being initialized, because interceptors also
         * require initialization.
         */
        ConfigSources(final SmallRyeConfigBuilder builder) {
            // Add all sources except for ConfigurableConfigSource types. These are initialized later
            List<ConfigSource> sources = buildSources(builder);
            // Add the default values sources separately, so we can keep a reference to it and add mappings defaults
            DefaultValuesConfigSource defaultValues = new DefaultValuesConfigSource(builder.getDefaultValues());
            sources.add(defaultValues);

            // Add all interceptors
            List<ConfigSourceInterceptor> negativeInterceptors = new ArrayList<>();
            List<ConfigSourceInterceptor> positiveInterceptors = new ArrayList<>();
            SmallRyeConfigSources negativeSources = new SmallRyeConfigSources(mapSources(sources), true);
            SmallRyeConfigSources positiveSources = new SmallRyeConfigSources(mapSources(sources), false);
            List<InterceptorWithPriority> interceptorWithPriorities = buildInterceptors(builder);

            // Create the initial chain with initial sources and all interceptors
            SmallRyeConfigSourceInterceptorContext.InterceptorChain chain = new SmallRyeConfigSourceInterceptorContext.InterceptorChain();
            SmallRyeConfigSourceInterceptorContext current = new SmallRyeConfigSourceInterceptorContext(EMPTY, null, chain);

            current = new SmallRyeConfigSourceInterceptorContext(negativeSources, current, chain);
            for (InterceptorWithPriority interceptorWithPriority : interceptorWithPriorities) {
                if (interceptorWithPriority.getPriority() < 0) {
                    ConfigSourceInterceptor interceptor = interceptorWithPriority.getInterceptor(current);
                    negativeInterceptors.add(interceptor);
                    current = new SmallRyeConfigSourceInterceptorContext(interceptor, current, chain);
                }
            }
            current = new SmallRyeConfigSourceInterceptorContext(positiveSources, current, chain);
            for (InterceptorWithPriority interceptorWithPriority : interceptorWithPriorities) {
                if (interceptorWithPriority.getPriority() >= 0) {
                    ConfigSourceInterceptor interceptor = interceptorWithPriority.getInterceptor(current);
                    positiveInterceptors.add(interceptor);
                    current = new SmallRyeConfigSourceInterceptorContext(interceptor, current, chain);
                }
            }

            // Init all late sources
            List<String> profiles = getProfiles(positiveInterceptors);
            List<ConfigSourceWithPriority> sourcesWithPriorities = mapLateSources(sources, negativeInterceptors,
                    positiveInterceptors, current, profiles, builder);
            List<ConfigSource> configSources = getSources(sourcesWithPriorities);

            // Rebuild the chain with the late sources and new instances of the interceptors
            // The new instance will ensure that we get rid of references to factories and other stuff and keep only
            // the resolved final source or interceptor to use.
            current = new SmallRyeConfigSourceInterceptorContext(EMPTY, null, chain);
            current = new SmallRyeConfigSourceInterceptorContext(new SmallRyeConfigSources(sourcesWithPriorities, true),
                    current, chain);
            for (ConfigSourceInterceptor interceptor : negativeInterceptors) {
                current = new SmallRyeConfigSourceInterceptorContext(interceptor, current, chain);
            }
            current = new SmallRyeConfigSourceInterceptorContext(new SmallRyeConfigSources(sourcesWithPriorities, false),
                    current, chain);
            for (ConfigSourceInterceptor interceptor : positiveInterceptors) {
                current = new SmallRyeConfigSourceInterceptorContext(interceptor, current, chain);
            }

            // Adjust the EnvSources to look for names with dashes instead of dots
            List<Entry<String, Supplier<Iterator<String>>>> properties = new ArrayList<>(
                    builder.getMappingsBuilder().getMappings().size());

            // Match dotted properties from other sources with Env with the same semantic meaning
            properties.add(Map.entry("", new Supplier<>() {
                private final List<String> names = new ArrayList<>();
                {
                    // Filter out some sources that do not contribute to the matching
                    for (ConfigSource configSource : configSources) {
                        if (!(configSource instanceof EnvConfigSource) && configSource != defaultValues) {
                            Set<String> propertyNames = configSource.getPropertyNames();
                            if (propertyNames != null) {
                                names.addAll(propertyNames.stream().map(new Function<String, String>() {
                                    @Override
                                    public String apply(final String name) {
                                        return activeName(name, profiles);
                                    }
                                }).toList());
                            }
                        }
                    }
                }

                @Override
                public Iterator<String> get() {
                    return names.iterator();
                }
            }));
            // Match mappings properties with Env
            for (ConfigMappings.ConfigClass mapping : builder.getMappingsBuilder().getMappings()) {
                Class<?> type = getConfigMappingClass(mapping.getType());
                properties.add(Map.entry(mapping.getPrefix(), new Supplier<>() {
                    private final List<String> names = new ArrayList<>(configMappingProperties(type).keySet());
                    {
                        // Sort by most specific key search to avoid clashing with map keys
                        names.sort(new Comparator<String>() {
                            @Override
                            public int compare(String o1, String o2) {
                                if (PropertyName.equals(o1, o2)) {
                                    // A name containing a star is always smaller
                                    return Integer.compare(o1.length(), o2.length()) * -1;
                                } else {
                                    return o1.compareTo(o2);
                                }
                            }
                        });
                    }

                    @Override
                    public Iterator<String> get() {
                        return names.iterator();
                    }
                }));
            }
            for (ConfigSource source : sources) {
                if (source instanceof EnvConfigSource) {
                    ((EnvConfigSource) source).matchEnvWithProperties(properties, profiles);
                }
            }

            this.profiles = profiles;
            this.sources = configSources;
            this.defaultValues = defaultValues;
            this.interceptorChain = current;
            this.propertyNames = new PropertyNames(current);
        }

        private static List<ConfigSource> buildSources(final SmallRyeConfigBuilder builder) {
            List<ConfigSource> sourcesToBuild = new ArrayList<>(builder.getSources());
            for (ConfigSourceProvider sourceProvider : builder.getSourceProviders()) {
                for (ConfigSource configSource : sourceProvider.getConfigSources(builder.getClassLoader())) {
                    sourcesToBuild.add(configSource);
                }
            }

            if (builder.isAddDiscoveredSources()) {
                sourcesToBuild.addAll(builder.discoverSources());
            }
            if (builder.isAddDefaultSources()) {
                sourcesToBuild.addAll(builder.getDefaultSources());
            } else {
                if (builder.isAddSystemSources()) {
                    sourcesToBuild.addAll(builder.getSystemSources());
                }
                if (builder.isAddPropertiesSources()) {
                    sourcesToBuild.addAll(builder.getPropertiesSources());
                }
            }

            return sourcesToBuild;
        }

        private static List<InterceptorWithPriority> buildInterceptors(final SmallRyeConfigBuilder builder) {
            List<InterceptorWithPriority> interceptors = new ArrayList<>(builder.getInterceptors());
            if (builder.isAddDiscoveredInterceptors()) {
                interceptors.addAll(builder.discoverInterceptors());
            }
            if (builder.isAddDefaultInterceptors()) {
                interceptors.addAll(builder.getDefaultInterceptors());
            }

            interceptors.sort(null);
            return interceptors;
        }

        private static List<ConfigSourceWithPriority> mapSources(final List<ConfigSource> sources) {
            List<ConfigSourceWithPriority> sourcesWithPriority = new ArrayList<>();
            for (ConfigSource source : sources) {
                if (!(source instanceof ConfigurableConfigSource)) {
                    sourcesWithPriority.add(new ConfigSourceWithPriority(source));
                }
            }
            sourcesWithPriority.sort(null);
            Collections.reverse(sourcesWithPriority);
            return sourcesWithPriority;
        }

        private static List<String> getProfiles(final List<ConfigSourceInterceptor> interceptors) {
            for (ConfigSourceInterceptor interceptor : interceptors) {
                if (interceptor instanceof ProfileConfigSourceInterceptor) {
                    return ((ProfileConfigSourceInterceptor) interceptor).getProfiles();
                }
            }
            return Collections.emptyList();
        }

        private static List<ConfigSourceWithPriority> mapLateSources(
                final List<ConfigSource> sources,
                final List<ConfigSourceInterceptor> negativeInterceptors,
                final List<ConfigSourceInterceptor> positiveInterceptors,
                final ConfigSourceInterceptorContext current,
                final List<String> profiles,
                final SmallRyeConfigBuilder builder) {

            ConfigSourceWithPriority.resetLoadPriority();
            List<ConfigSourceWithPriority> currentSources = new ArrayList<>();

            // Init all profile sources first
            List<ConfigSource> profileSources = new ArrayList<>();
            ConfigSourceContext mainContext = new SmallRyeConfigSourceContext(current, profiles, sources);
            for (ConfigurableConfigSource profileSource : getConfigurableSources(sources)) {
                if (profileSource.getFactory() instanceof ProfileConfigSourceFactory) {
                    profileSources.addAll(profileSource.getConfigSources(mainContext));
                }
            }

            // Sort the profiles sources with the main sources
            currentSources.addAll(mapSources(profileSources));
            currentSources.addAll(mapSources(sources));
            currentSources.sort(null);
            Collections.reverse(currentSources);

            // Rebuild the chain with the profiles sources, so profiles values are also available in factories
            SmallRyeConfigSourceInterceptorContext.InterceptorChain chain = new SmallRyeConfigSourceInterceptorContext.InterceptorChain();
            ConfigSourceInterceptorContext context = new SmallRyeConfigSourceInterceptorContext(EMPTY, null, chain);
            context = new SmallRyeConfigSourceInterceptorContext(new SmallRyeConfigSources(currentSources, true), context,
                    chain);
            for (ConfigSourceInterceptor interceptor : negativeInterceptors) {
                context = new SmallRyeConfigSourceInterceptorContext(interceptor, context, chain);
            }
            context = new SmallRyeConfigSourceInterceptorContext(new SmallRyeConfigSources(currentSources, false), context,
                    chain);
            for (ConfigSourceInterceptor interceptor : positiveInterceptors) {
                context = new SmallRyeConfigSourceInterceptorContext(interceptor, context, chain);
            }

            // Init remaining sources, coming from SmallRyeConfig
            int countSourcesFromLocations = 0;
            List<ConfigSource> lateSources = new ArrayList<>();
            ConfigSourceContext profileContext = new SmallRyeConfigSourceContext(context, profiles,
                    currentSources.stream().map(ConfigSourceWithPriority::getSource).collect(toList()));
            for (ConfigurableConfigSource lateSource : getConfigurableSources(sources)) {
                if (!(lateSource.getFactory() instanceof ProfileConfigSourceFactory)) {
                    List<ConfigSource> configSources = lateSource.getConfigSources(profileContext);

                    if (lateSource.getFactory() instanceof AbstractLocationConfigSourceFactory) {
                        countSourcesFromLocations = countSourcesFromLocations + configSources.size();
                    }

                    lateSources.addAll(configSources);
                }
            }

            if (countSourcesFromLocations == 0 && builder.isAddDiscoveredSources()) {
                ConfigValue locations = profileContext.getValue(SMALLRYE_CONFIG_LOCATIONS);
                if (locations != null && locations.getValue() != null) {
                    ConfigLogging.log.configLocationsNotFound(SMALLRYE_CONFIG_LOCATIONS, locations.getValue());
                }
            }

            // Sort the final sources
            currentSources.clear();
            currentSources.addAll(mapSources(lateSources));
            currentSources.addAll(mapSources(profileSources));
            currentSources.addAll(mapSources(sources));
            currentSources.sort(null);
            Collections.reverse(currentSources);

            return currentSources;
        }

        private static List<ConfigSource> getSources(final List<ConfigSourceWithPriority> sourceWithPriorities) {
            List<ConfigSource> configSources = new ArrayList<>();
            for (ConfigSourceWithPriority configSourceWithPriority : sourceWithPriorities) {
                ConfigSource source = configSourceWithPriority.getSource();
                configSources.add(source);
                if (ConfigLogging.log.isDebugEnabled()) {
                    ConfigLogging.log.loadedConfigSource(source.getName(), source.getOrdinal());
                }
            }
            return Collections.unmodifiableList(configSources);
        }

        private static List<ConfigurableConfigSource> getConfigurableSources(final List<ConfigSource> sources) {
            List<ConfigurableConfigSource> configurableConfigSources = new ArrayList<>();
            for (ConfigSource source : sources) {
                if (source instanceof ConfigurableConfigSource) {
                    configurableConfigSources.add((ConfigurableConfigSource) source);
                }
            }
            configurableConfigSources.sort(Comparator.comparingInt(ConfigurableConfigSource::getOrdinal).reversed());
            return Collections.unmodifiableList(configurableConfigSources);
        }

        List<String> getProfiles() {
            return profiles;
        }

        List<ConfigSource> getSources() {
            return sources;
        }

        ConfigSourceInterceptorContext getInterceptorChain() {
            return interceptorChain;
        }

        PropertyNames getPropertyNames() {
            return propertyNames;
        }

        private static class PropertyNames implements Serializable {
            @Serial
            private static final long serialVersionUID = 4193517748286869745L;

            private final SmallRyeConfigSourceInterceptorContext interceptorChain;

            private final Set<String> names = new HashSet<>();
            private final Map<String, Map<Integer, String>> indexed = new HashMap<>();

            public PropertyNames(final SmallRyeConfigSourceInterceptorContext interceptorChain) {
                this.interceptorChain = interceptorChain;
            }

            Iterable<String> get() {
                if (names.isEmpty()) {
                    return latest();
                }
                return names;
            }

            public Map<String, Map<Integer, String>> indexed() {
                // ensure populated
                get();
                return indexed;
            }

            Iterable<String> latest() {
                names.clear();
                Iterator<String> namesIterator = interceptorChain.iterateNames();
                while (namesIterator.hasNext()) {
                    String name = namesIterator.next();
                    names.add(name);

                    for (int i = 0; i < name.length(); i++) {
                        if (name.charAt(i) == '[') {
                            int indexEnd = name.indexOf(']', i);
                            if (StringUtil.isNumeric(name, i + 1, indexEnd - 1 - i)) {
                                if (indexEnd == name.length() - 1
                                        || (name.charAt(indexEnd + 1) == '.' && indexEnd + 2 < name.length())) {
                                    Integer index = Integer.valueOf(name.substring(i + 1, indexEnd));
                                    String parentKey = name.substring(0, i);
                                    indexed.computeIfAbsent(parentKey, key -> new TreeMap<>())
                                            .compute(index, new BiFunction<Integer, String, String>() {
                                                @Override
                                                public String apply(final Integer key, final String value) {
                                                    if (value != null && indexEnd == value.length() - 1) {
                                                        return value;
                                                    }
                                                    return name;
                                                }
                                            });
                                }
                                i = indexEnd + 1;
                            }
                        }
                    }
                }
                names.remove(ConfigSource.CONFIG_ORDINAL);
                return Collections.unmodifiableSet(names);
            }
        }
    }

    static class ConfigSourceWithPriority implements Comparable<ConfigSourceWithPriority>, Serializable {
        @Serial
        private static final long serialVersionUID = 3709554647398262957L;

        private final ConfigSource source;
        private final int priority;
        private final int loadPriority = loadPrioritySequence++;

        ConfigSourceWithPriority(final ConfigSource source) {
            this.source = source;
            this.priority = source.getOrdinal();
        }

        ConfigSource getSource() {
            return source;
        }

        int priority() {
            return priority;
        }

        @Override
        public int compareTo(final ConfigSourceWithPriority other) {
            int res = Integer.compare(this.priority, other.priority);
            return res != 0 ? res : Integer.compare(other.loadPriority, this.loadPriority);
        }

        private static int loadPrioritySequence = 0;

        static void resetLoadPriority() {
            loadPrioritySequence = 0;
        }
    }

    private static class SmallRyeConfigSourceContext implements ConfigSourceContext {
        private final ConfigSourceInterceptorContext context;
        private final List<String> profiles;
        private final List<ConfigSource> sources;

        public SmallRyeConfigSourceContext(
                final ConfigSourceInterceptorContext context,
                final List<String> profiles,
                final List<ConfigSource> sources) {
            this.context = context;
            this.profiles = profiles;
            this.sources = sources;
        }

        @Override
        public ConfigValue getValue(final String name) {
            ConfigValue value = context.proceed(name);
            return value != null ? value : ConfigValue.builder().withName(name).build();
        }

        @Override
        public List<String> getProfiles() {
            return profiles;
        }

        @Override
        public List<ConfigSource> getConfigSources() {
            return sources;
        }

        @Override
        public Iterator<String> iterateNames() {
            return context.iterateNames();
        }
    }

    private static class SmallRyeConfigSourceInterceptorContext implements ConfigSourceInterceptorContext {
        @Serial
        private static final long serialVersionUID = 6654406739008729337L;

        private final ConfigSourceInterceptor interceptor;
        private final ConfigSourceInterceptorContext next;
        private final InterceptorChain chain;

        private static final ThreadLocal<RecursionCount> rcHolder = ThreadLocal.withInitial(RecursionCount::new);

        SmallRyeConfigSourceInterceptorContext(
                final ConfigSourceInterceptor interceptor,
                final ConfigSourceInterceptorContext next,
                final InterceptorChain chain) {
            this.interceptor = interceptor;
            this.next = next;
            this.chain = chain.setChain(this);
        }

        @Override
        public ConfigValue proceed(final String name) {
            return interceptor.getValue(next, name);
        }

        @Override
        public ConfigValue restart(final String name) {
            RecursionCount rc = rcHolder.get();
            rc.increment();
            try {
                return chain.get().proceed(name);
            } finally {
                if (rc.decrement()) {
                    // avoid leaking if the thread is cached
                    rcHolder.remove();
                }
            }
        }

        @Override
        public Iterator<String> iterateNames() {
            return interceptor.iterateNames(next);
        }

        static class InterceptorChain implements Supplier<ConfigSourceInterceptorContext>, Serializable {
            @Serial
            private static final long serialVersionUID = 7387475787257736307L;

            private ConfigSourceInterceptorContext chain;

            @Override
            public ConfigSourceInterceptorContext get() {
                return chain;
            }

            public InterceptorChain setChain(final ConfigSourceInterceptorContext chain) {
                this.chain = chain;
                return this;
            }
        }

        static final class RecursionCount {
            int count;

            void increment() {
                int old = count;
                if (old == 20) {
                    throw new IllegalStateException("Too many recursive interceptor actions");
                }
                count = old + 1;
            }

            boolean decrement() {
                return --count == 0;
            }
        }
    }

    @Serial
    private Object writeReplace() throws ObjectStreamException {
        return RegisteredConfig.instance;
    }

    /**
     * Serialization placeholder which deserializes to the current registered config
     */
    private static class RegisteredConfig implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;
        private static final RegisteredConfig instance = new RegisteredConfig();

        @Serial
        private Object readResolve() throws ObjectStreamException {
            return ConfigProvider.getConfig();
        }
    }
}
