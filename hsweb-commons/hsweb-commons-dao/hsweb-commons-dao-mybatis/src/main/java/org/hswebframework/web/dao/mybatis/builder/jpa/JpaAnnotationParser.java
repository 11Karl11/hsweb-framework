package org.hswebframework.web.dao.mybatis.builder.jpa;


import lombok.extern.slf4j.Slf4j;
import org.apache.commons.beanutils.BeanUtilsBean;
import org.hswebframework.ezorm.core.ValueConverter;
import org.hswebframework.ezorm.rdb.meta.RDBColumnMetaData;
import org.hswebframework.ezorm.rdb.meta.RDBTableMetaData;
import org.hswebframework.ezorm.rdb.meta.converter.BooleanValueConverter;
import org.hswebframework.ezorm.rdb.meta.converter.DateTimeConverter;
import org.hswebframework.ezorm.rdb.meta.converter.NumberValueConverter;
import org.hswebframework.utils.ClassUtils;
import org.hswebframework.web.dao.mybatis.builder.TypeUtils;
import org.hswebframework.web.dict.EnumDict;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.util.StringUtils;

import javax.persistence.*;
import java.beans.PropertyDescriptor;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.JDBCType;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * jpa 注解解析器
 *
 * @author zhouhao
 * @since 3.0
 */
@Slf4j
public class JpaAnnotationParser {

    private static final Map<Class, JDBCType> jdbcTypeMapping = new HashMap<>();

    private static final List<BiFunction<Class, PropertyDescriptor, JDBCType>> jdbcTypeConvert = new ArrayList<>();

    static {
        jdbcTypeMapping.put(String.class, JDBCType.VARCHAR);

        jdbcTypeMapping.put(Byte.class, JDBCType.TINYINT);
        jdbcTypeMapping.put(byte.class, JDBCType.TINYINT);

        jdbcTypeMapping.put(Short.class, JDBCType.INTEGER);
        jdbcTypeMapping.put(short.class, JDBCType.INTEGER);

        jdbcTypeMapping.put(Integer.class, JDBCType.INTEGER);
        jdbcTypeMapping.put(int.class, JDBCType.INTEGER);

        jdbcTypeMapping.put(Character.class, JDBCType.CHAR);
        jdbcTypeMapping.put(char.class, JDBCType.CHAR);

        jdbcTypeMapping.put(Long.class, JDBCType.BIGINT);
        jdbcTypeMapping.put(long.class, JDBCType.BIGINT);

        jdbcTypeMapping.put(Double.class, JDBCType.DECIMAL);
        jdbcTypeMapping.put(double.class, JDBCType.DECIMAL);

        jdbcTypeMapping.put(Float.class, JDBCType.DECIMAL);
        jdbcTypeMapping.put(float.class, JDBCType.DECIMAL);

        jdbcTypeMapping.put(Boolean.class, JDBCType.BIT);
        jdbcTypeMapping.put(boolean.class, JDBCType.BIT);

        jdbcTypeMapping.put(byte[].class, JDBCType.BLOB);

        jdbcTypeMapping.put(BigDecimal.class, JDBCType.DECIMAL);
        jdbcTypeMapping.put(BigInteger.class, JDBCType.INTEGER);

        jdbcTypeMapping.put(Date.class, JDBCType.TIMESTAMP);
        jdbcTypeMapping.put(java.sql.Date.class, JDBCType.TIMESTAMP);
        jdbcTypeMapping.put(java.sql.Timestamp.class, JDBCType.TIMESTAMP);

        jdbcTypeMapping.put(Object.class, JDBCType.VARCHAR);

        jdbcTypeConvert.add((type, property) -> {
            Enumerated enumerated = getAnnotation(type, property, Enumerated.class);
            return enumerated != null ? JDBCType.VARCHAR : null;
        });
        jdbcTypeConvert.add((type, property) -> {
            Lob enumerated = getAnnotation(type, property, Lob.class);
            return enumerated != null ? JDBCType.CLOB : null;
        });

        jdbcTypeConvert.add((type, property) -> {
            Class<?> propertyType = property.getPropertyType();
            boolean isArray = propertyType.isArray();
            if (isArray) {
                propertyType = propertyType.getComponentType();
            }
            if (propertyType.isEnum() && EnumDict.class.isAssignableFrom(propertyType)) {
                if (isArray) {
                    return JDBCType.BIGINT;
                }
                Class<?> genType = ((EnumDict<?>) propertyType.getEnumConstants()[0]).getValue().getClass();
                return jdbcTypeMapping.getOrDefault(genType, JDBCType.VARCHAR);
            }
            return null;
        });
    }


    private static List<RDBColumnMetaData> parseColumnMeta(String prefix, String columnName, Class entityClass) {

        PropertyDescriptor[] descriptors = BeanUtilsBean.getInstance()
                .getPropertyUtils()
                .getPropertyDescriptors(entityClass);
        List<RDBColumnMetaData> columnMetaDataList = new ArrayList<>();

        for (PropertyDescriptor descriptor : descriptors) {
            Column columnAnn = getAnnotation(entityClass, descriptor, Column.class);
            CollectionTable collectionTable = getAnnotation(entityClass, descriptor, CollectionTable.class);

            if (columnAnn == null) {
                if (collectionTable != null) {
                    columnMetaDataList.addAll(parseColumnMeta(descriptor.getName(), collectionTable.name(), descriptor.getPropertyType()));
                    continue;
                }
                continue;
            }

            String realName = StringUtils.hasText(columnAnn.name()) ? columnAnn.name() : descriptor.getName();
            String realAlias = StringUtils.hasText(prefix) ? prefix.concat(".").concat(descriptor.getName()) : descriptor.getName();

            RDBColumnMetaData column = new RDBColumnMetaData();
            column.setName(StringUtils.hasText(columnName) ? columnName.concat(".").concat(realName) : realName);
            column.setAlias(realAlias);
            column.setLength(columnAnn.length());
            column.setPrecision(columnAnn.precision());
            column.setJavaType(descriptor.getPropertyType());
            if (!columnAnn.updatable()) {
                column.setProperty("read-only", true);
            }
            if (!columnAnn.nullable()) {
                column.setNotNull(true);
            }
            if (StringUtils.hasText(columnAnn.columnDefinition())) {
                column.setColumnDefinition(columnAnn.columnDefinition());
            }
            Class propertyType = descriptor.getPropertyType();

            JDBCType type = jdbcTypeMapping.get(propertyType);
            if (type == null) {
                type = jdbcTypeConvert.stream()
                        .map(func -> func.apply(entityClass, descriptor))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(JDBCType.OTHER);
            }
            column.setJdbcType(type);
            columnMetaDataList.add(column);
        }
        return columnMetaDataList;
    }

    public static RDBTableMetaData parseMetaDataFromEntity(Class entityClass) {
        Table table = AnnotationUtils.findAnnotation(entityClass, Table.class);
        if (table == null) {
            return null;
        }
        RDBTableMetaData tableMetaData = new RDBTableMetaData();
        tableMetaData.setName(table.name());
        parseColumnMeta(null, null, entityClass).forEach(tableMetaData::addColumn);
        return tableMetaData;
    }


    private static <T extends Annotation> T getAnnotation(Class entityClass, PropertyDescriptor descriptor, Class<T> type) {
        T ann = null;
        try {
            Field field = entityClass.getDeclaredField(descriptor.getName());
            ann = AnnotationUtils.findAnnotation(field, type);
        } catch (@SuppressWarnings("all") NoSuchFieldException ignore) {
            if (entityClass.getSuperclass() != Object.class) {
                return getAnnotation(entityClass.getSuperclass(), descriptor, type);
            }
        }
        Method read = descriptor.getReadMethod(),
                write = descriptor.getWriteMethod();
        if (null == ann && read != null) {
            ann = AnnotationUtils.findAnnotation(read, type);
        }
        if (null == ann && write != null) {
            ann = AnnotationUtils.findAnnotation(write, type);
        }
        return ann;
    }
}
