package org.ikigaidigital.timedeposit.adapter.out.persistence;

import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Records the SQL text of every statement prepared through it, so a test can check what actually reaches the driver.
 * A pass-through spy, not a double in the sense of ADR-0009: every call still goes to the real PostgreSQL, nothing is
 * replaced or stubbed. Its connections do not join a Spring transaction, so use it for reads only.
 */
final class RecordingDataSource extends DelegatingDataSource {

    private final List<String> statements = new CopyOnWriteArrayList<>();

    RecordingDataSource(DataSource target) {
        super(target);
    }

    List<String> statements() {
        return List.copyOf(statements);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return recording(super.getConnection());
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return recording(super.getConnection(username, password));
    }

    // Every prepareStatement and prepareCall overload takes the SQL as its first argument.
    private Connection recording(Connection target) {
        return (Connection) Proxy.newProxyInstance(RecordingDataSource.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().startsWith("prepare") && args != null && args[0] instanceof String sql) {
                        statements.add(sql);
                    }
                    try {
                        return method.invoke(target, args);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
