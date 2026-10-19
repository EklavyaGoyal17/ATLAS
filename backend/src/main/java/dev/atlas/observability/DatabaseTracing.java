package dev.atlas.observability;

import java.lang.reflect.*;
import java.sql.*;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.stereotype.Component;

/** JDBC boundaries, including connection acquisition. SQL and bound values never leave JDBC. */
@Component
public class DatabaseTracing implements BeanPostProcessor {

    private final ObjectProvider<Telemetry> telemetry;

    public DatabaseTracing(ObjectProvider<Telemetry> telemetry) {
        this.telemetry = telemetry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof DataSource source)) return bean;
        return new DelegatingDataSource(source) {
            @Override
            public Connection getConnection() throws SQLException {
                return connection(() -> source.getConnection());
            }

            @Override
            public Connection getConnection(String u, String p)
                throws SQLException {
                return connection(() -> source.getConnection(u, p));
            }
        };
    }

    interface ConnectionFactory {
        Connection get() throws SQLException;
    }

    private Connection connection(ConnectionFactory factory)
        throws SQLException {
        var span = telemetry.getObject().start("db.connection");
        Connection c;
        try {
            c = factory.get();
        } catch (SQLException e) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw e;
        } finally {
            span.end();
        }
        return (Connection) Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] { Connection.class },
            (p, m, a) -> {
                Object value = invoke(c, m, a);
                if (value instanceof Statement statement) {
                    Class<?> type =
                        value instanceof CallableStatement
                            ? CallableStatement.class
                            : value instanceof PreparedStatement
                              ? PreparedStatement.class
                              : Statement.class;
                    return Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] { type },
                        (q, method, args) -> {
                            if (
                                !method.getName().startsWith("execute")
                            ) return invoke(statement, method, args);
                            var query = telemetry.getObject().start("db.query");
                            try (var scope = query.makeCurrent()) {
                                return invoke(statement, method, args);
                            } catch (Throwable ex) {
                                query.setStatus(
                                    io.opentelemetry.api.trace.StatusCode.ERROR
                                );
                                throw ex;
                            } finally {
                                query.end();
                            }
                        }
                    );
                }
                return value;
            }
        );
    }

    private Object invoke(Object target, Method method, Object[] args)
        throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
