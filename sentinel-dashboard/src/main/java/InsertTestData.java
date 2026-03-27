import java.sql.*;

public class InsertTestData {
    public static void main(String[] args) throws Exception {
        String url = "jdbc:h2:C:/Users/wwf/logs/csp/sentinel-metrics/metrics;AUTO_SERVER=TRUE";
        String user = "sa";
        String password = "";

        Class.forName("org.h2.Driver");
        Connection conn = DriverManager.getConnection(url, user, password);

        long now = System.currentTimeMillis();

        String app = "sentinel-demo-spring-webmvc";
        String[] resources = {"/api/user", "/api/order", "/api/product", "/api/payment"};

        PreparedStatement ps = conn.prepareStatement(
            "MERGE INTO metric_minute (app, resource, minute_timestamp, pass_qps, success_qps, block_qps, exception_qps, rt, count) " +
            "KEY (app, resource, minute_timestamp) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        );

        int count = 0;

        // Insert data for last 2 days, one record per hour
        for (int day = 0; day <= 2; day++) {
            for (int hour = 0; hour < 24; hour++) {
                long timestamp = now - day * 24 * 60 * 60 * 1000 - hour * 60 * 60 * 1000;
                timestamp = (timestamp / 60000) * 60000;

                for (String resource : resources) {
                    ps.setString(1, app);
                    ps.setString(2, resource);
                    ps.setLong(3, timestamp);
                    ps.setLong(4, 50 + (int)(Math.random() * 100));
                    ps.setLong(5, 45 + (int)(Math.random() * 90));
                    ps.setLong(6, (int)(Math.random() * 10));
                    ps.setLong(7, (int)(Math.random() * 3));
                    ps.setDouble(8, 10 + Math.random() * 50);
                    ps.setInt(9, 50 + (int)(Math.random() * 100));
                    ps.executeUpdate();
                    count++;
                }
            }
        }

        System.out.println("Inserted " + count + " records");

        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM metric_minute");
        if (rs.next()) {
            System.out.println("Total records in metric_minute: " + rs.getInt(1));
        }

        rs = stmt.executeQuery("SELECT MIN(minute_timestamp), MAX(minute_timestamp) FROM metric_minute");
        if (rs.next()) {
            System.out.println("Time range: " + new java.util.Date(rs.getLong(1)) + " ~ " + new java.util.Date(rs.getLong(2)));
        }

        conn.close();
    }
}