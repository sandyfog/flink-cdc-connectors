/*
 * Copyright 2022 Ververica Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.postgres;

import org.apache.flink.test.util.AbstractTestBase;

import io.debezium.config.Configuration;
import io.debezium.connector.postgresql.connection.PostgresConnection;
import io.debezium.jdbc.JdbcConfiguration;
import org.junit.BeforeClass;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertNotNull;

/**
 * Basic class for testing PostgreSQL source, this contains a PostgreSQL container which enables
 * binlog.
 */
public abstract class PostgresTestBase extends AbstractTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(PostgresTestBase.class);
    public static final Pattern COMMENT_PATTERN = Pattern.compile("^(.*)--.*$");
    public static final String DEFAULT_DB = "postgres";

    // use newer version of postgresql image to support pgoutput plugin
    // when testing postgres 13, only 13-alpine supports both amd64 and arm64
    protected static final DockerImageName PG_IMAGE =
            DockerImageName.parse("debezium/postgres:13").asCompatibleSubstituteFor("postgres");

    public static final PostgreSQLContainer<?> POSTGERS_CONTAINER =
            new PostgreSQLContainer<>(PG_IMAGE)
                    .withDatabaseName(DEFAULT_DB)
                    .withUsername("postgres")
                    .withPassword("postgres")
                    .withLogConsumer(new Slf4jLogConsumer(LOG))
                    .withCommand(
                            "postgres",
                            "-c",
                            // default
                            "fsync=off",
                            "-c",
                            // to ensure that the slot becomes inactive during the failover
                            "wal_sender_timeout=1000",
                            "-c",
                            "max_replication_slots=20");

    @BeforeClass
    public static void startContainers() {
        LOG.info("Starting containers...");
        Startables.deepStart(Stream.of(POSTGERS_CONTAINER)).join();
        LOG.info("Containers are started.");
    }

    protected Connection getJdbcConnection() throws SQLException {
        return DriverManager.getConnection(
                POSTGERS_CONTAINER.getJdbcUrl(),
                POSTGERS_CONTAINER.getUsername(),
                POSTGERS_CONTAINER.getPassword());
    }

    public static Connection getJdbcConnection(String databaseName) throws SQLException {
        return DriverManager.getConnection(
                POSTGERS_CONTAINER.withDatabaseName(databaseName).getJdbcUrl(),
                POSTGERS_CONTAINER.getUsername(),
                POSTGERS_CONTAINER.getPassword());
    }

    /**
     * Executes a JDBC statement using the default jdbc config without autocommitting the
     * connection.
     */
    protected void initializePostgresTable(String sqlFile) {
        final String ddlFile = String.format("ddl/%s.sql", sqlFile);
        final URL ddlTestFile = PostgresTestBase.class.getClassLoader().getResource(ddlFile);
        assertNotNull("Cannot locate " + ddlFile, ddlTestFile);
        try (Connection connection = getJdbcConnection();
                Statement statement = connection.createStatement()) {
            final List<String> statements =
                    Arrays.stream(
                                    Files.readAllLines(Paths.get(ddlTestFile.toURI())).stream()
                                            .map(String::trim)
                                            .filter(x -> !x.startsWith("--") && !x.isEmpty())
                                            .map(
                                                    x -> {
                                                        final Matcher m =
                                                                COMMENT_PATTERN.matcher(x);
                                                        return m.matches() ? m.group(1) : x;
                                                    })
                                            .collect(Collectors.joining("\n"))
                                            .split(";\n"))
                            .collect(Collectors.toList());
            for (String stmt : statements) {
                statement.execute(stmt);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected PostgresConnection createConnection(Map<String, String> properties) {
        Configuration config = Configuration.from(properties);
        return new PostgresConnection(JdbcConfiguration.adapt(config), "test-connection");
    }
}
