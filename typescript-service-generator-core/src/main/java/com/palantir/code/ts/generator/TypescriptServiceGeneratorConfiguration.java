/*
 * Copyright © 2016 Palantir Technologies Inc.
 */

package com.palantir.code.ts.generator;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.immutables.value.Value;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

import cz.habarta.typescript.generator.GenericsTypeProcessor;
import cz.habarta.typescript.generator.JsonLibrary;
import cz.habarta.typescript.generator.Settings;
import cz.habarta.typescript.generator.TsType;
import cz.habarta.typescript.generator.TypeProcessor;
import cz.habarta.typescript.generator.TypeScriptFileType;
import cz.habarta.typescript.generator.TypeScriptOutputKind;
import cz.habarta.typescript.generator.ext.EnumConstantsExtension;

@Value.Immutable
@Value.Style(visibility = Value.Style.ImplementationVisibility.PUBLIC)
@JsonDeserialize(as = ImmutableTypescriptServiceGeneratorConfiguration.class)
@JsonSerialize(as = ImmutableTypescriptServiceGeneratorConfiguration.class)
public abstract class TypescriptServiceGeneratorConfiguration {

    /**
     * A copyright header.
     */
    @Value.Default
    public String copyrightHeader() {
        return "";
    }

    /**
     * See {@link TypeProcessor}, Enables custom parsing of Java types into Typescript types.
     * A user might want some type that their api references to parse to Typescript in a special way.
     */
    @Value.Default
    public TypeProcessor customTypeProcessor() {
        return new TypeProcessor() {
            @Override
            public Result processType(Type javaType, Context context) {
                return null;
            }
        };
    }

    /**
     * Provides a strategy for resolving duplicate method names
     * @return
     */
    @Value.Default
    public DuplicateMethodNameResolver duplicateEndpointNameResolver() {
        return new DuplicateMethodNameResolver() {
            @Override
            public Map<Method, String> resolveDuplicateNames(List<Method> methodsWithSameName) {
                return Maps.newHashMap();
            }
        };
    }

    /**
     * If true, emit method signatures even when it would create duplicate java names (results in typescript that does not compile).
     * If false, emit a warning instead.
     */
    @Value.Default
    public boolean emitDuplicateJavaMethodNames() {
        return false;
    }

    /**
     * If true, emit typings that can be used with module loading systems (no Typescript module).
     * If false, emit typings that correspond to the Typescript module to prefix code under.
     */
    @Value.Default
    public boolean emitES6() {
    	return false;
    }

    /**
     * A Java format string, expected to have exactly one %s where a generic should be placed.
     * Specifies what return types should look like.
     * For example, suppose a Java endpoint returned a string, then for a value of "Foo&lt;Bar&lt;%s&gt;&gt;" for this property,
     * the generated Typescript endpoint would return type Foo&lt;string&gt;.
     */
    @Value.Parameter
    public abstract String genericEndpointReturnType();

    /**
     * A set of annotations that should be ignored on Java method declarations.
     * Ignored here means that the Typescript method is generated as if the annotated Java argument did not exist.
     */
    @Value.Default
    public Set<Class<?>> ignoredAnnotations() {
        return new HashSet<>();
    }

    /**
     * A list of annotations that will generate fields as optional in the typescript definitions.
     */
    @Value.Default
    public List<Class<? extends Annotation>> optionalAnnotations() {
        return new ArrayList<>();
    }

    /**
     * The Typescript module to prefix all generated code under, for example: "MyProject.GeneratedCode"
     */
    @Value.Parameter
    public abstract Optional<String> typescriptModule();

    /**
     * A message to be displayed at the top of every generated file, perhaps informing users that the file is autogenerated.
     */
    @Value.Default
    public String generatedMessage() {
        return "";
    }

    /**
     * A Set of classes that Typescript types should not be generated for.
     */
    @Value.Default
    public Set<Type> ignoredClasses() {
        return Sets.newHashSet();
    }

    /**
     * The pre-existing folder location that all generated files should be placed into.
     */
    @Value.Parameter
    public abstract File generatedFolderLocation();

    /**
     * The prefix to put before all generated interfaces.
     */
    @Value.Default
    public String generatedInterfacePrefix() {
        return "";
    }

    /**
     * A filter for whether or not a method should be parsed from a class.
     */
    @Value.Default
    public MethodFilter methodFilter() {
        return new MethodFilter() {
            @Override
            public boolean shouldGenerateMethod(Class<?> parentClass, Method method) {
                return true;
            }
        };
    }

    public TypeProcessor getOverridingTypeParser() {
        TypeProcessor defaultTypeProcessor = new TypeProcessor() {
            @Override
            public Result processType(Type javaType, Context context) {
                TsType ret = null;
                if (javaType instanceof ParameterizedType) {
                    ParameterizedType param = (ParameterizedType) javaType;
                    if (param.getRawType() == Optional.class) {
                        Type arg = param.getActualTypeArguments()[0];
                        Result contextResponse = context.processType(arg);
                        if (contextResponse != null) {
                            return new Result(contextResponse.getTsType().optional(), contextResponse.getDiscoveredClasses());
                        } else {
                            return null;
                        }
                    }
                } else if (javaType == URI.class) {
                    ret = TsType.String;
                } if (ret == null) {
                    return null;
                } else {
                    return new Result(ret, new ArrayList<Class<?>>());
                }
            }
        };
        return new TypeProcessor.Chain(Lists.newArrayList(customTypeProcessor(), defaultTypeProcessor));
    }

    public Settings getSettings() {
        Settings settings = new Settings();

        TypeProcessor genericTypeProcessor = new GenericsTypeProcessor();
        List<TypeProcessor> typeProcessors = new ArrayList<>();
        typeProcessors.add(customTypeProcessor());
        typeProcessors.add(getOverridingTypeParser());
        typeProcessors.add(genericTypeProcessor);
        settings.customTypeProcessor = new TypeProcessor.Chain(typeProcessors);
        settings.addTypeNamePrefix = generatedInterfacePrefix();
        settings.sortDeclarations = true;
        settings.noFileComment = true;
        settings.jsonLibrary = JsonLibrary.jackson2;
        settings.optionalAnnotations = optionalAnnotations();
        settings.outputKind = TypeScriptOutputKind.global;
        settings.outputFileType = TypeScriptFileType.implementationFile;
        settings.extensions = Lists.newArrayList(new EnumConstantsExtension());

        return settings;
    }

    public interface DuplicateMethodNameResolver {
        /**
         * Takes a list of methods that all have the same name, returns a map of resolved names, or
         * null if the names can't be resolved
         */
        Map<Method, String> resolveDuplicateNames(List<Method> methodsWithSameName);
    }

    public interface MethodFilter {
        /**
         * Return true if method should be generated, false otherwise
         */
        boolean shouldGenerateMethod(Class<?> parentClass, Method method);
    }
}
