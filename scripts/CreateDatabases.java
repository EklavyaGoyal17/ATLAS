import java.sql.DriverManager;

/** Bootstrap only the two fixed local databases. Run with the PostgreSQL JDBC jar on the classpath. */
class CreateDatabases {

    public static void main(String[] args) throws Exception {
        try (
            var connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5433/postgres",
                "atlas",
                "atlas-local-only"
            )
        ) {
            for (String name : new String[] { "atlas", "atlas_test" }) {
                try (
                    var query = connection.prepareStatement(
                        "SELECT 1 FROM pg_database WHERE datname=?"
                    )
                ) {
                    query.setString(1, name);
                    try (var result = query.executeQuery()) {
                        if (!result.next()) {
                            try (var statement = connection.createStatement()) {
                                statement.execute("CREATE DATABASE " + name); // Fixed identifiers, never user input.
                            }
                        }
                    }
                }
            }
        }
        System.out.println("Local atlas and atlas_test databases are ready.");
    }
}
