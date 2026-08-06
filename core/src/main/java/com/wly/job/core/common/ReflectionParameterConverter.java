package com.wly.job.core.common;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 反射参数转换工具：将调度请求中的字符串参数（executeParam）转换为目标方法声明的参数类型，
 * 支持基本类型/包装类型、String 以及 List/Set/Map 等泛型集合与自定义泛型类（基于 fastjson2）。
 * <p>
 * 由 {@link com.wly.job.core.invocation.MethodInvocationJob} 在执行前调用；
 * 空串参数按目标类型默认值处理（基本类型返回 0/false 等，引用类型返回 null）。
 */
public class ReflectionParameterConverter {

    /**
     * 将字符串转换为指定类型
     */
    public static Object convertStringToType(String value, Type targetType) {
        if (value == null || value.trim().isEmpty()) {
            return getDefaultValue(targetType);
        }

        // 处理基本类型
        if (targetType instanceof Class) {
            return convertToSimpleType(value, (Class<?>) targetType);
        }

        // 处理泛型类型
        if (targetType instanceof ParameterizedType) {
            return convertToGenericType(value, (ParameterizedType) targetType);
        }

        throw new IllegalArgumentException("不支持的参数类型: " + targetType);
    }

    /**
     * 转换基本类型
     */
    private static Object convertToSimpleType(String value, Class<?> targetClass) {
        if (targetClass == String.class) {
            return value;
        } else if (targetClass == int.class || targetClass == Integer.class) {
            return Integer.parseInt(value);
        } else if (targetClass == long.class || targetClass == Long.class) {
            return Long.parseLong(value);
        } else if (targetClass == double.class || targetClass == Double.class) {
            return Double.parseDouble(value);
        } else if (targetClass == boolean.class || targetClass == Boolean.class) {
            return Boolean.parseBoolean(value);
        } else if (targetClass == float.class || targetClass == Float.class) {
            return Float.parseFloat(value);
        } else if (targetClass == short.class || targetClass == Short.class) {
            return Short.parseShort(value);
        } else if (targetClass == byte.class || targetClass == Byte.class) {
            return Byte.parseByte(value);
        } else if (targetClass == char.class || targetClass == Character.class) {
            if (value.length() != 1) {
                throw new IllegalArgumentException("Char 参数必须为单个字符: " + value);
            }
            return value.charAt(0);
        } else {
            // 对于其他对象类型，使用 JSON 转换
            return parseObject(value, targetClass);
        }
    }

    /**
     * 转换泛型类型
     */
    private static Object convertToGenericType(String value, ParameterizedType parameterizedType) {
        Type rawType = parameterizedType.getRawType();
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();

        if (rawType == List.class || rawType == Collection.class) {
            return convertToList(value, actualTypeArguments[0]);
        } else if (rawType == Set.class) {
            return convertToSet(value, actualTypeArguments[0]);
        } else if (rawType == Map.class) {
            return convertToMap(value, actualTypeArguments[0], actualTypeArguments[1]);
        } else {
            // 其他泛型类，如 Response<T>
            return convertToCustomGeneric(value, parameterizedType);
        }
    }

    /**
     * 转换 List 泛型 - 正确的方式
     */
    private static List<?> convertToList(String value, Type elementType) {
        if (value == null || value.trim().isEmpty()) {
            return new ArrayList<>();
        }

        // 创建自定义的 ParameterizedTypeImpl
        ParameterizedType listType = createParameterizedType(List.class, elementType);
        return JSON.parseObject(value, createTypeReference(listType));
    }

    /**
     * 转换 Set 泛型
     */
    private static Set<?> convertToSet(String value, Type elementType) {
        if (value == null || value.trim().isEmpty()) {
            return new HashSet<>();
        }

        ParameterizedType setType = createParameterizedType(Set.class, elementType);
        return JSON.parseObject(value, createTypeReference(setType));
    }

    /**
     * 转换 Map 泛型
     */
    private static Map<?, ?> convertToMap(String value, Type keyType, Type valueType) {
        if (value == null || value.trim().isEmpty()) {
            return new HashMap<>();
        }

        ParameterizedType mapType = createParameterizedType(Map.class, keyType, valueType);
        return JSON.parseObject(value, createTypeReference(mapType));
    }

    /**
     * 转换自定义泛型类
     */
    private static Object convertToCustomGeneric(String value, ParameterizedType parameterizedType) {
        return JSON.parseObject(value, createTypeReference(parameterizedType));
    }

    /**
     * 创建 ParameterizedType
     */
    private static ParameterizedType createParameterizedType(Type rawType, Type... typeArguments) {
        return new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return typeArguments;
            }

            @Override
            public Type getRawType() {
                return rawType;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
    }

    /**
     * 创建 TypeReference - 正确的方式
     * 通过构造方法传入 Type，而不是重写 getType()
     */
    private static <T> TypeReference<T> createTypeReference(Type type) {
        return new TypeReference<>(type) {
        };
    }

    // 续上代码

    /**
     * 使用 FastJSON 解析对象
     */
    private static Object parseObject(String json, Class<?> targetClass) {
        return JSON.parseObject(json, targetClass);
    }

    /**
     * 获取类型的默认值
     */
    private static Object getDefaultValue(Type type) {
        if (type instanceof Class<?> clazz) {
            if (clazz.isPrimitive()) {
                if (clazz == boolean.class) return false;
                if (clazz == byte.class) return (byte) 0;
                if (clazz == char.class) return '\0';
                if (clazz == short.class) return (short) 0;
                if (clazz == int.class) return 0;
                if (clazz == long.class) return 0L;
                if (clazz == float.class) return 0.0f;
                if (clazz == double.class) return 0.0d;
            }
        }
        return null;
    }
}