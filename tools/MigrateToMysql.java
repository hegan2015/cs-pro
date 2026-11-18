import java.sql.*;
import java.util.*;

/**
 * H2(文件库) → MySQL 全量迁移：逐表 SELECT * → 批量 INSERT..ON DUPLICATE KEY UPDATE（幂等可重跑），
 * 同步回补 cs_sequence 号位（取迁移数据中数字 ID 最大值），最后逐表行数校验。
 * 原 H2 库以 READ_ONLY 打开，绝不写改。用法：java MigrateToMysql <h2File> <mysqlUrl> <user> <pass>
 */
public class MigrateToMysql {

    static final String[] TABLES = {
            "cs_tenant", "cs_subscription", "cs_activation_code", "cs_sub_order", "cs_free_usage",
            "cs_session", "cs_message", "cs_ticket", "cs_ticket_note", "cs_kb",
            "cs_rating", "cs_llm_call", "stats_daily", "idempotent_record"
    };

    /** ID 前缀 → 号表 name */
    static final Map<String, String> SEQ = new LinkedHashMap<>();
    static {
        SEQ.put("S", "session"); SEQ.put("M", "message"); SEQ.put("T", "ticket"); SEQ.put("N", "note");
        SEQ.put("K", "kb"); SEQ.put("L", "llm_call"); SEQ.put("TN", "tenant"); SEQ.put("SO", "sub_order");
    }

    public static void main(String[] args) throws Exception {
        String h2File = args.length > 0 ? args[0] : "./data/cs";
        String mysqlUrl = args.length > 1 ? args[0 + 1] : "jdbc:mysql://127.0.0.1:3306/cs_pro?useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
        String user = args.length > 2 ? args[2] : "cspro";
        String pass = args.length > 3 ? args[3] : "cspro-dev-2026";

        Map<String, Long> maxSeq = new HashMap<>();
        int total = 0;
        try (Connection h2 = DriverManager.getConnection(
                     "jdbc:h2:file:" + h2File + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;ACCESS_MODE_DATA=r", "sa", "");
             Connection my = DriverManager.getConnection(mysqlUrl, user, pass)) {
            my.setAutoCommit(false);
            for (String table : TABLES) {
                long moved = migrateTable(h2, my, table, maxSeq);
                total += moved;
                long h2Count = count(h2, "SELECT COUNT(*) FROM " + table);
                long myCount = count(my, "SELECT COUNT(*) FROM " + table);
                System.out.printf("%-22s h2=%-8d mysql=%-8d %s%n", table, h2Count, myCount,
                        h2Count == myCount ? "OK" : "!! MISMATCH");
            }
            for (Map.Entry<String, Long> e : maxSeq.entrySet()) {
                try (PreparedStatement p = my.prepareStatement(
                        "UPDATE cs_sequence SET next_val = GREATEST(next_val, ?) WHERE name = ?")) {
                    p.setLong(1, e.getValue());
                    p.setString(2, e.getKey());
                    p.executeUpdate();
                }
            }
            my.commit();
            System.out.println("迁移完成：共 " + total + " 行，号位已回补。H2 文件未做任何修改。");
        }
    }

    static long migrateTable(Connection h2, Connection my, String table, Map<String, Long> maxSeq) throws SQLException {
        List<String> cols = new ArrayList<>();
        Set<String> mysqlCols = new HashSet<>();
        try (ResultSet rs = my.getMetaData().getColumns(null, null, table, null)) {
            while (rs.next()) mysqlCols.add(rs.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
        }
        try (Statement s = h2.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM " + table)) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                String c = md.getColumnLabel(i).toLowerCase(Locale.ROOT);
                if (mysqlCols.contains(c)) cols.add(c);
            }
            if (cols.isEmpty()) return 0;
            String sql = "INSERT INTO " + table + "(" + String.join(",", cols) + ") VALUES("
                    + String.join(",", Collections.nCopies(cols.size(), "?")) + ") ON DUPLICATE KEY UPDATE "
                    + cols.stream().map(c -> c + "=VALUES(" + c + ")").reduce((a, b) -> a + "," + b).orElse("");
            try (PreparedStatement ins = my.prepareStatement(sql)) {
                long n = 0, batch = 0;
                while (rs.next()) {
                    for (int i = 0; i < cols.size(); i++) {
                        Object v = rs.getObject(i + 1);
                        if (v instanceof Clob clob) {
                            v = clob.getSubString(1, (int) Math.min(clob.length(), 10_000_000));
                        }
                        ins.setObject(i + 1, v);
                    }
                    ins.addBatch();
                    bumpSeq(maxSeq, table, rs);
                    if (++batch % 500 == 0) ins.executeBatch();
                }
                ins.executeBatch();
                my.commit();
                n = batch;
                System.out.println("  迁移 " + table + " " + n + " 行（" + cols.size() + " 列）");
                return n;
            }
        }
    }

    static void bumpSeq(Map<String, Long> maxSeq, String table, ResultSet rs) {
        if (!table.startsWith("cs_")) return;
        try {
            String id = rs.getString("id");
            if (id == null) return;
            for (Map.Entry<String, String> e : SEQ.entrySet()) {
                if (id.startsWith(e.getKey()) && id.length() > e.getKey().length()
                        && id.substring(e.getKey().length()).chars().allMatch(Character::isDigit)) {
                    maxSeq.merge(e.getValue(), Long.parseLong(id.substring(e.getKey().length())), Math::max);
                    return;
                }
            }
        } catch (SQLException ignore) {
        }
    }

    static long count(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }
}
