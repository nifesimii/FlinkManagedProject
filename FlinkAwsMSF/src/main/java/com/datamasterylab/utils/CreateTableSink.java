package com.datamasterylab.utils;

import com.datamasterylab.DataStreamJob;
import com.datamasterylab.entities.SensorData;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

public class CreateTableSink extends RichSinkFunction<SensorData> {
    private static final Logger LOGGER = LogManager.getLogger(CreateTableSink.class);

    public String jdbcUrl;
    private Connection connection;

    public CreateTableSink(String jdbcUrl) {
        Properties props = new Properties();

        try (InputStream input = DataStreamJob.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                LOGGER.error("Sorry, unable to find application.properties");
            }

            props.load(input);
        } catch (IOException ex) {
            LOGGER.error(ex.getMessage());
        }

        this.jdbcUrl = props.getProperty("jdbc.url");

    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        connection = DriverManager.getConnection(jdbcUrl);
        createTableIfNotExists();
    }

    private void createTableIfNotExists() throws SQLException {
        String createTableSQL = "CREATE TABLE IF NOT EXISTS sensor_data_msf (" +
                "id SERIAL PRIMARY KEY, " +
                "device_id VARCHAR, " +
                "sensor_type VARCHAR, " +
                "timestamp VARCHAR, " +
                "value FLOAT, " +
                "unit VARCHAR)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(createTableSQL);
        }

        String createAvgValuePerSensorTypeTableSQL = "CREATE TABLE IF NOT EXISTS avgValuePerSensorType (" +
                "sensor_type VARCHAR(10) PRIMARY KEY, " +
                "avg_value FLOAT)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(createAvgValuePerSensorTypeTableSQL);
        }

        String createCountPerSensorTypeTableSQL = "CREATE TABLE IF NOT EXISTS countPerSensorType (" +
                "sensor_type VARCHAR(10) PRIMARY KEY, " +
                "event_count INT)";
        try (Statement statement = connection.createStatement()) {
            statement.execute(createCountPerSensorTypeTableSQL);
        }

////        create unique constraint for createAvgValuePerSensorType
//        String createUniqueConstraintSQL = "ALTER TABLE avgValuePerSensorType ADD CONSTRAINT sensor_type_unique2 UNIQUE (sensor_type)";
//        try (Statement statement = connection.createStatement()) {
//            statement.execute(createUniqueConstraintSQL);
//        }
//
////        create unique constraint for countPerSensorType
//        String createUniqueConstraintSQL2 = "ALTER TABLE countPerSensorType ADD CONSTRAINT sensor_type_unique1 UNIQUE (sensor_type)";
//        try (Statement statement = connection.createStatement()) {
//            statement.execute(createUniqueConstraintSQL2);
//        }
    }

    @Override
    public void invoke(SensorData value, Context context) throws Exception {
        //        nothing to do here

    }

    @Override
    public void close() throws Exception {
        super.close();
        if(connection != null) {
            connection.close();
        }
    }
}
