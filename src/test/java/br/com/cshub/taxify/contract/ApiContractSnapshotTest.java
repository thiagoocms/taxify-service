package br.com.cshub.taxify.contract;

import br.com.cshub.taxify.dto.ErrorDetailsDTO;
import br.com.cshub.taxify.dto.assessment.TaxAssessmentDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.cloud.openfeign.FeignClient;
import br.com.cshub.taxify.integration.acessorias.AcessoriasCompanyDTO;
import br.com.cshub.taxify.integration.acessorias.AcessoriasUserDTO;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.introspect.AnnotatedClass;
import tools.jackson.databind.introspect.BeanPropertyDefinition;
import tools.jackson.databind.introspect.ClassIntrospector;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard for the English-naming refactor (wave 1): the HTTP contract must not change while the Java
 * names do. It describes every endpoint by route, by the names of its query/path/header params and by
 * the <b>shape</b> of its request and response JSON (property names and serialized enum values, never
 * class names), and compares that with the committed baseline {@code contract/api-contract-baseline.json},
 * generated from the code before the refactor. The same goes for the JSON saved in
 * {@code fiscal_period_closings.snapshot} ({@link TaxAssessmentDTO}) and for the error body.
 *
 * <p>Regenerate the baseline only on purpose (a deliberate contract change):
 * {@code -Dcontract.write=true}.
 */
class ApiContractSnapshotTest {

    private static final Path BASELINE = Path.of("src/test/resources/contract/api-contract-baseline.json");
    private static final String ROOT_PACKAGE = "br.com.cshub.taxify";

    private final ObjectMapper mapper = JsonMapper.builder().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS).build();
    private final JsonNodeFactory nodes = JsonNodeFactory.instance;

    @Test
    void theHttpContractAndTheSavedJsonDidNotChange() throws Exception {
        ObjectNode current = describeContract();
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(current);
        if (Boolean.getBoolean("contract.write")) {
            Files.createDirectories(BASELINE.getParent());
            Files.writeString(BASELINE, json + System.lineSeparator());
        }
        ObjectNode baseline = (ObjectNode) mapper.readTree(Files.readString(BASELINE));
        List<String> differences = new ArrayList<>();
        Set<String> keys = new TreeSet<>(baseline.propertyNames());
        keys.addAll(current.propertyNames());
        for (String key : keys) {
            if (!baseline.has(key)) {
                differences.add("new: " + key);
            } else if (!current.has(key)) {
                differences.add("missing: " + key);
            } else if (!baseline.get(key).equals(current.get(key))) {
                differences.add("changed: " + key + System.lineSeparator() + "  before: " + baseline.get(key)
                        + System.lineSeparator() + "  after:  " + current.get(key));
            }
        }
        assertThat(differences).as("contract differences against " + BASELINE).isEmpty();
    }

    private ObjectNode describeContract() throws ClassNotFoundException {
        TreeMap<String, ObjectNode> entries = new TreeMap<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition candidate : scanner.findCandidateComponents(ROOT_PACKAGE + ".rest")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            for (Method method : controller.getMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null || method.isBridge() || method.isSynthetic() || Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String key = routeKey(controller, mapping);
                String unique = key;
                for (int i = 2; entries.containsKey(unique); i++) {
                    unique = key + " #" + i;
                }
                entries.put(unique, describeEndpoint(controller, method, mapping));
            }
        }
        ClassPathScanningCandidateComponentProvider feignScanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(org.springframework.beans.factory.annotation.AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        feignScanner.addIncludeFilter(new AnnotationTypeFilter(FeignClient.class));
        for (BeanDefinition candidate : feignScanner.findCandidateComponents(ROOT_PACKAGE + ".integration")) {
            Class<?> client = Class.forName(candidate.getBeanClassName());
            String clientName = client.getAnnotation(FeignClient.class).name();
            for (Method method : client.getMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping != null) {
                    entries.put("~third-party feign:" + clientName + " " + routeKey(client, mapping), describeEndpoint(client, method, mapping));
                }
            }
        }
        // JSON of third-party APIs read by hand with an ObjectMapper (not through Feign).
        for (Class<?> thirdParty : List.of(AcessoriasCompanyDTO.class, AcessoriasUserDTO.class)) {
            entries.put("~third-party json:" + thirdParty.getSimpleName(), (ObjectNode) shape(mapper.constructType(thirdParty), false, new HashSet<>()));
        }
        ObjectNode root = nodes.objectNode();
        entries.forEach(root::set);
        root.set("~saved-json fiscal_period_closings.snapshot", shape(mapper.constructType(TaxAssessmentDTO.class), false, new HashSet<>()));
        root.set("~error-body", shape(mapper.constructType(ErrorDetailsDTO.class), true, new HashSet<>()));
        return root;
    }

    private String routeKey(Class<?> controller, RequestMapping mapping) {
        RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        String[] prefixes = base == null || base.path().length == 0 ? new String[]{""} : base.path();
        String[] paths = mapping.path().length == 0 ? new String[]{""} : mapping.path();
        List<String> full = new ArrayList<>();
        for (String prefix : prefixes) {
            for (String path : paths) {
                full.add(prefix + path);
            }
        }
        String verbs = mapping.method().length == 0 ? "ANY" : String.join(",", Arrays.stream(mapping.method()).map(Enum::name).sorted().toList());
        String extra = (mapping.params().length == 0 ? "" : " params=" + Arrays.toString(mapping.params()))
                + (mapping.headers().length == 0 ? "" : " headers=" + Arrays.toString(mapping.headers()))
                + (mapping.consumes().length == 0 ? "" : " consumes=" + Arrays.toString(mapping.consumes()))
                + (mapping.produces().length == 0 ? "" : " produces=" + Arrays.toString(mapping.produces()));
        return verbs + " " + String.join("|", full) + extra;
    }

    private ObjectNode describeEndpoint(Class<?> controller, Method method, RequestMapping mapping) {
        ObjectNode endpoint = nodes.objectNode();
        ObjectNode params = nodes.objectNode();
        for (int i = 0; i < method.getParameterCount(); i++) {
            MethodParameter parameter = new MethodParameter(method, i);
            parameter = parameter.withContainingClass(controller);
            ResolvableType type = ResolvableType.forMethodParameter(parameter);
            String javaName = method.getParameters()[i].getName();
            RequestParam requestParam = parameter.getParameterAnnotation(RequestParam.class);
            PathVariable pathVariable = parameter.getParameterAnnotation(PathVariable.class);
            RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
            RequestPart part = parameter.getParameterAnnotation(RequestPart.class);
            RequestBody body = parameter.getParameterAnnotation(RequestBody.class);
            if (requestParam != null) {
                params.set("query:" + name(requestParam.name(), javaName), simpleParam(type, requestParam.required(), requestParam.defaultValue()));
            } else if (pathVariable != null) {
                params.set("path:" + name(pathVariable.name(), javaName), simpleParam(type, pathVariable.required(), null));
            } else if (header != null) {
                params.set("header:" + name(header.name(), javaName), simpleParam(type, header.required(), header.defaultValue()));
            } else if (part != null) {
                params.set("part:" + name(part.name(), javaName), simpleParam(type, part.required(), null));
            } else if (body != null) {
                params.set("body", shape(toJavaType(type), false, new HashSet<>()));
            } else if (isOurs(type.resolve(Object.class)) && !type.resolve(Object.class).isEnum()) {
                params.set("query-object", boundObject(type.resolve(Object.class)));
            }
        }
        endpoint.set("params", params);
        endpoint.set("response", shape(toJavaType(ResolvableType.forMethodReturnType(method, controller)), true, new HashSet<>()));
        return endpoint;
    }

    private static String name(String annotated, String javaName) {
        return annotated == null || annotated.isEmpty() ? javaName : annotated;
    }

    private ObjectNode simpleParam(ResolvableType type, boolean required, String defaultValue) {
        ObjectNode node = nodes.objectNode();
        Class<?> raw = type.resolve(Object.class);
        if (Collection.class.isAssignableFrom(raw) || raw.isArray()) {
            ResolvableType element = raw.isArray() ? type.getComponentType() : type.asCollection().getGeneric(0);
            raw = element.resolve(Object.class);
            node.put("multi", true);
        }
        if (raw.isEnum()) {
            node.set("enum", enumNames(raw));
        } else {
            node.put("type", isOurs(raw) ? "object" : raw.getSimpleName());
        }
        node.put("required", required && (defaultValue == null || defaultValue.equals("\n\t\t\n\t\t\n\n\t\t\t\t\n")));
        if (defaultValue != null && !defaultValue.equals("\n\t\t\n\t\t\n\n\t\t\t\t\n")) {
            node.put("default", defaultValue);
        }
        return node;
    }

    /** Query params bound by Spring's data binder use the Java property names, not Jackson's. */
    private ObjectNode boundObject(Class<?> type) {
        ObjectNode node = nodes.objectNode();
        TreeMap<String, String> properties = new TreeMap<>();
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                properties.put(component.getName(), bindingType(component.getType()));
            }
        } else {
            try {
                for (PropertyDescriptor descriptor : Introspector.getBeanInfo(type, Object.class).getPropertyDescriptors()) {
                    if (descriptor.getWriteMethod() != null) {
                        properties.put(descriptor.getName(), bindingType(descriptor.getPropertyType()));
                    }
                }
            } catch (java.beans.IntrospectionException e) {
                throw new IllegalStateException(e);
            }
        }
        properties.forEach(node::put);
        return node;
    }

    private String bindingType(Class<?> type) {
        if (type.isEnum()) {
            return "enum" + enumNames(type);
        }
        return isOurs(type) ? "object" : type.getSimpleName();
    }

    private tools.jackson.databind.node.ArrayNode enumNames(Class<?> type) {
        tools.jackson.databind.node.ArrayNode values = nodes.arrayNode();
        for (Object constant : type.getEnumConstants()) {
            values.add(((Enum<?>) constant).name());
        }
        return values;
    }

    private JavaType toJavaType(ResolvableType type) {
        if (type.isArray()) {
            return mapper.getTypeFactory().constructArrayType(toJavaType(type.getComponentType()));
        }
        Class<?> raw = type.resolve(Object.class);
        ResolvableType[] generics = type.getGenerics();
        if (generics.length == 0 || raw.getTypeParameters().length != generics.length) {
            return mapper.constructType(raw);
        }
        JavaType[] params = new JavaType[generics.length];
        for (int i = 0; i < generics.length; i++) {
            params[i] = toJavaType(generics[i]);
        }
        return mapper.getTypeFactory().constructParametricType(raw, params);
    }

    /** JSON shape: property names (sorted) and nested shapes; enums by their serialized values. */
    private tools.jackson.databind.JsonNode shape(JavaType type, boolean serialization, Set<Class<?>> visiting) {
        Class<?> raw = type.getRawClass();
        if (raw == Optional.class || raw.getName().equals("org.springframework.http.ResponseEntity")
                || raw.getName().equals("org.springframework.http.HttpEntity")) {
            JavaType content = type.containedTypeCount() > 0 ? type.containedType(0) : mapper.constructType(Object.class);
            return shape(content, serialization, visiting);
        }
        if (type.isArrayType() && raw != byte[].class) {
            ObjectNode node = nodes.objectNode();
            node.set("array", shape(type.getContentType(), serialization, visiting));
            return node;
        }
        if (type.isCollectionLikeType()) {
            ObjectNode node = nodes.objectNode();
            node.set("array", shape(type.getContentType(), serialization, visiting));
            return node;
        }
        if (type.isMapLikeType()) {
            ObjectNode node = nodes.objectNode();
            node.set("mapKey", shape(type.getKeyType(), serialization, visiting));
            node.set("mapValue", shape(type.getContentType(), serialization, visiting));
            return node;
        }
        if (raw.isEnum()) {
            tools.jackson.databind.node.ArrayNode values = nodes.arrayNode();
            for (Object constant : raw.getEnumConstants()) {
                values.add(mapper.writeValueAsString(constant));
            }
            ObjectNode node = nodes.objectNode();
            node.set("enum", values);
            return node;
        }
        if (!isOurs(raw) && !isSpringData(raw)) {
            return nodes.textNode(scalarName(raw));
        }
        if (!visiting.add(raw)) {
            return nodes.textNode("<recursive>");
        }
        try {
            ObjectNode node = nodes.objectNode();
            TreeMap<String, tools.jackson.databind.JsonNode> properties = new TreeMap<>();
            for (BeanPropertyDefinition property : properties(type, serialization)) {
                JavaType propertyType = property.getPrimaryType();
                properties.put(property.getName(), propertyType == null ? nodes.textNode("?") : shape(propertyType, serialization, visiting));
            }
            properties.forEach(node::set);
            return node;
        } finally {
            visiting.remove(raw);
        }
    }

    private List<BeanPropertyDefinition> properties(JavaType type, boolean serialization) {
        ClassIntrospector introspector = serialization
                ? mapper.serializationConfig().classIntrospectorInstance()
                : mapper.deserializationConfig().classIntrospectorInstance();
        AnnotatedClass annotated = introspector.introspectClassAnnotations(type);
        BeanDescription description = serialization
                ? introspector.introspectForSerialization(type, annotated)
                : introspector.introspectForDeserialization(type, annotated);
        List<BeanPropertyDefinition> result = new ArrayList<>();
        for (BeanPropertyDefinition property : description.findProperties()) {
            if (serialization ? property.couldSerialize() : property.couldDeserialize()) {
                result.add(property);
            }
        }
        return result;
    }

    private static String scalarName(Class<?> raw) {
        if (raw.isPrimitive() || Number.class.isAssignableFrom(raw) || raw == Boolean.class || raw == String.class
                || raw == Character.class || Temporal.class.isAssignableFrom(raw) || raw == byte[].class) {
            return raw.getSimpleName();
        }
        return raw.getName();
    }

    private static boolean isOurs(Class<?> raw) {
        return raw.getName().startsWith(ROOT_PACKAGE + ".");
    }

    private static boolean isSpringData(Class<?> raw) {
        return raw.getName().startsWith("org.springframework.data.domain.");
    }

}
