package com.bluemalic.repair;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验每个实体的字段与数据库表的列一一对应。
 *
 * <p>为什么需要它：字段名写错（例如把 deleted 写成 delete）**不会是编译错误**，
 * 只在运行时变成 Unknown column 的 SQL 异常；更糟的是逻辑删除会静默失效。
 * 靠人记住不可靠，所以把这条约定变成测试——写歪一个字段名就红。
 *
 * <p>覆盖范围是自动的：从所有 {@code BaseMapper} 反推出实体类型，因此新增实体
 * （只要配了 Mapper）会自动进入校验，不需要维护一份清单。
 */
@SpringBootTest
class EntityTableMappingTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void entityFieldsMustMatchTableColumns() {
        Map<String, Set<String>> tableColumns = loadTableColumns();
        List<String> problems = new ArrayList<>();
        Set<String> checkedTables = new LinkedHashSet<>();

        String[] mapperNames = applicationContext.getBeanNamesForType(BaseMapper.class);
        assertThat(mapperNames)
                .as("一个 Mapper 都没扫描到，检查 MybatisPlusConfig 上的 @MapperScan")
                .isNotEmpty();

        for (String mapperName : mapperNames) {
            BaseMapper<?> mapper = (BaseMapper<?>) applicationContext.getBean(mapperName);
            Class<?> entityClass = resolveEntityType(mapper);
            String table = resolveTableName(entityClass);
            checkedTables.add(table);

            Set<String> columns = tableColumns.get(table);
            if (columns == null) {
                problems.add(table + "（" + entityClass.getSimpleName() + "）：数据库里没有这张表");
                continue;
            }

            Set<String> fields = entityColumnNames(entityClass);
            Set<String> onlyInEntity = new TreeSet<>(fields);
            onlyInEntity.removeAll(columns);
            Set<String> onlyInTable = new TreeSet<>(columns);
            onlyInTable.removeAll(fields);

            if (!onlyInEntity.isEmpty() || !onlyInTable.isEmpty()) {
                problems.add(String.format("%s（%s）：实体多出 %s，表里多出 %s",
                        table, entityClass.getSimpleName(), onlyInEntity, onlyInTable));
            }
        }

        // 反向检查：表建了却没有实体，同样是遗漏
        Set<String> tablesWithoutEntity = new TreeSet<>(tableColumns.keySet());
        tablesWithoutEntity.removeAll(checkedTables);
        if (!tablesWithoutEntity.isEmpty()) {
            problems.add("这些表还没有对应的实体：" + tablesWithoutEntity);
        }

        assertThat(problems).as("实体字段与表列不一致").isEmpty();
    }

    /** 一次查出当前库所有表的列，避免每张表查一次。 */
    private Map<String, Set<String>> loadTableColumns() {
        return jdbcTemplate.query(
                "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA = DATABASE()",
                rs -> {
                    Map<String, Set<String>> result = new LinkedHashMap<>();
                    while (rs.next()) {
                        result.computeIfAbsent(rs.getString(1), k -> new LinkedHashSet<>()).add(rs.getString(2));
                    }
                    return result;
                });
    }

    /** 从 Mapper 接口的 {@code BaseMapper<T>} 上取出 T。 */
    private Class<?> resolveEntityType(BaseMapper<?> mapper) {
        for (Class<?> mapperInterface : mapper.getClass().getInterfaces()) {
            for (Type generic : mapperInterface.getGenericInterfaces()) {
                if (generic instanceof ParameterizedType parameterized
                        && parameterized.getRawType() == BaseMapper.class) {
                    return (Class<?>) parameterized.getActualTypeArguments()[0];
                }
            }
        }
        throw new IllegalStateException("无法从 " + mapper.getClass().getName() + " 解析出实体类型");
    }

    private String resolveTableName(Class<?> entityClass) {
        TableName annotation = entityClass.getAnnotation(TableName.class);
        if (annotation != null && !annotation.value().isEmpty()) {
            return annotation.value();
        }
        return camelToSnake(entityClass.getSimpleName());
    }

    private Set<String> entityColumnNames(Class<?> entityClass) {
        Set<String> columns = new LinkedHashSet<>();
        for (Field field : entityClass.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            TableField tableField = field.getAnnotation(TableField.class);
            if (tableField != null && !tableField.exist()) {
                continue;
            }
            columns.add(camelToSnake(field.getName()));
        }
        return columns;
    }

    private String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
