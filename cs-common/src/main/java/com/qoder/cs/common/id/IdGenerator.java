package com.qoder.cs.common.id;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;

/**
 * 跨方言取号器：H2 走原生 SEQUENCE（NEXT VALUE FOR），MySQL 走号表
 * cs_sequence + LAST_INSERT_ID(expr) 原子取号（同一连接内 UPDATE→SELECT，多实例安全）。
 * seq 名白名单校验，杜绝拼接注入。
 */
public class IdGenerator {

    private static final Set<String> ALLOWED = Set.of(
            "seq_session", "seq_message", "seq_ticket", "seq_note", "seq_kb",
            "seq_llm_call", "seq_tenant", "seq_sub_order", "seq_invoice");

    private final DataSource ds;
    private final boolean mysql;

    public IdGenerator(DataSource ds, boolean mysql) {
        this.ds = ds;
        this.mysql = mysql;
    }

    public long next(String seq) {
        if (!ALLOWED.contains(seq)) {
            throw new IllegalArgumentException("unknown sequence: " + seq);
        }
        try (Connection c = ds.getConnection()) {
            if (mysql) {
                try (Statement s = c.createStatement()) {
                    int updated = s.executeUpdate("UPDATE cs_sequence SET next_val = LAST_INSERT_ID(next_val + 1)"
                            + " WHERE name = '" + seq.substring("seq_".length()) + "'");
                    if (updated != 1) {
                        throw new IllegalStateException("sequence row missing: " + seq);
                    }
                    try (ResultSet r = s.executeQuery("SELECT LAST_INSERT_ID()")) {
                        if (r.next()) {
                            return r.getLong(1);
                        }
                    }
                }
                throw new IllegalStateException("LAST_INSERT_ID unavailable for " + seq);
            }
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT NEXT VALUE FOR " + seq)) {
                if (r.next()) {
                    return r.getLong(1);
                }
            }
            throw new IllegalStateException("sequence missing: " + seq);
        } catch (SQLException e) {
            throw new IllegalStateException("取号失败 " + seq, e);
        }
    }
}
