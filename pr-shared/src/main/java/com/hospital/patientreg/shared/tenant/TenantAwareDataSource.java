package com.hospital.patientreg.shared.tenant;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Hands every borrowed PostgreSQL connection the current user's hospitals
 * ({@code app.hospital_ids}, {@code app.user_id}), which the row-level security policies read.
 * The values are set on every borrow, so a pooled connection never keeps another request's hospitals;
 * without a signed-in user the list is empty and the policies match no rows.
 */
public class TenantAwareDataSource extends DelegatingDataSource implements AutoCloseable {

    private static final String APPLY = "SELECT set_config('app.hospital_ids', ?, false), set_config('app.user_id', ?, false)";
    private static final String RESET = "SELECT set_config('app.hospital_ids', '', false), set_config('app.user_id', '', false)";

    public TenantAwareDataSource(DataSource target) {
        super(target);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return apply(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return apply(super.getConnection(username, password));
    }

    /** Closes the wrapped pool when the application stops. */
    @Override
    public void close() throws Exception {
        if (getTargetDataSource() instanceof AutoCloseable pool) {
            pool.close();
        }
    }

    private static Connection apply(Connection connection) throws SQLException {
        Tenant tenant = TenantContext.current().orElse(null);
        String hospitals = tenant == null ? ""
                : tenant.hospitalIds().stream().map(String::valueOf).collect(Collectors.joining(","));
        try (PreparedStatement ps = connection.prepareStatement(APPLY)) {
            ps.setString(1, hospitals);
            ps.setString(2, tenant == null ? "" : String.valueOf(tenant.userId()));
            ps.execute();
            return resettingOnClose(connection);
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    /**
     * Clears the settings again when the connection goes back to the pool, so a pooled connection never
     * carries a user's hospitals: code that ever borrowed from the pool directly would see nothing.
     */
    private static Connection resettingOnClose(Connection target) {
        return (Connection) Proxy.newProxyInstance(TenantAwareDataSource.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName()) && method.getParameterCount() == 0) {
                        try (PreparedStatement ps = target.prepareStatement(RESET)) {
                            if (!target.isClosed()) {
                                ps.execute();
                            }
                        } catch (SQLException ignored) {
                            // a broken connection is discarded by the pool anyway
                        } finally {
                            target.close();
                        }
                        return null;
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
