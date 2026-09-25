package com.bluemalic.repair.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 白名单表的结构：一份给提示词用（"Schema 注入"），一份给安全网关用（判断某张表有没有 {@code deleted} 列）。
 *
 * <p><b>为什么从 {@code information_schema} 读，而不是在 Java 里写死一份表结构</b>：
 * 写死就一定会漂——加个字段忘了回来改，模型就不知道它存在，表现是"问某个新字段答不上来"，
 * 而这种漂移没有任何检测手段能发现。从库里读则永远跟实际表结构一致。
 *
 * <p><b>用只读连接读，而不是主连接</b>：这样"AI 看到的表"与"它被授权能读的表"在数据库层面
 * 就是同一件事（只读账号只在白名单表上有权限，所以它在 {@code information_schema} 里也只看得见这些表）。
 * 敏感列（`password` / `phone`）在这里再过滤一次——它们虽然能被授权，但不该进提示词。
 *
 * <p>缓存：表结构在一个进程生命周期内不会变（Flyway 迁移是在启动时跑的），所以读一次就够。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaCatalog implements ColumnLookup {

    private final AiQueryExecutor executor;

    private volatile Entry cached;

    /**
     * 白名单表的结构描述，每行一张表：{@code ticket（工单主表）: id 工单ID, ticket_no 工单号, ...}
     *
     * <p>形如 {@code 列名 注释}——注释就是 {@code docs/02} 与建表脚本里那些中文说明，
     * 模型靠它们把"报修量""哪个楼"对上具体字段。
     */
    public String describe() {
        return load().description();
    }

    @Override
    public boolean has(String table, String column) {
        return load().columns().getOrDefault(table, Set.of()).contains(column);
    }

    private Entry load() {
        Entry entry = cached;
        if (entry == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = read();
                }
                entry = cached;
            }
        }
        return entry;
    }

    private Entry read() {
        AiQueryResult result = executor.execute(buildQuery());

        // 按表分组，保持查询里的顺序（表名 → 列定义）
        Map<String, List<String>> partsByTable = new LinkedHashMap<>();
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();
        Map<String, String> commentsByTable = new LinkedHashMap<>();
        for (List<Object> row : result.rows()) {
            String table = text(row.get(0));
            String tableComment = text(row.get(1));
            String column = text(row.get(2));
            String columnComment = text(row.get(3));
            if (SchemaWhitelist.isSensitiveColumn(column)) {
                continue;
            }
            commentsByTable.putIfAbsent(table, tableComment);
            partsByTable.computeIfAbsent(table, key -> new ArrayList<>())
                    .add(columnComment.isBlank() ? column : column + " " + columnComment);
            columnsByTable.computeIfAbsent(table, key -> new java.util.HashSet<>()).add(column);
        }

        String description = partsByTable.entrySet().stream()
                .map(part -> {
                    String comment = commentsByTable.get(part.getKey());
                    String label = comment == null || comment.isBlank()
                            ? part.getKey()
                            : part.getKey() + "（" + comment + "）";
                    return label + ": " + String.join(", ", part.getValue());
                })
                .collect(Collectors.joining("\n"));
        log.info("AI 提示词的表结构已加载 表数={} 字符数={}", partsByTable.size(), description.length());
        return new Entry(description, columnsByTable);
    }

    /**
     * 表名清单直接写进 SQL 的 {@code IN}：它们来自 {@link SchemaWhitelist} 的代码常量
     * （只含字母与下划线），不存在注入面。
     */
    private String buildQuery() {
        String tableList = SchemaWhitelist.tables().stream()
                .map(table -> "'" + table + "'")
                .collect(Collectors.joining(", "));
        return """
                SELECT c.table_name, t.table_comment, c.column_name, c.column_comment
                FROM information_schema.columns c
                JOIN information_schema.tables t
                  ON t.table_schema = c.table_schema AND t.table_name = c.table_name
                WHERE c.table_schema = DATABASE()
                  AND c.table_name IN (%s)
                ORDER BY c.table_name, c.ordinal_position""".formatted(tableList);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private record Entry(String description, Map<String, Set<String>> columns) {
    }
}
