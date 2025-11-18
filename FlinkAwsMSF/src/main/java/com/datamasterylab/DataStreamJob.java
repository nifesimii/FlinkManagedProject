/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.datamasterylab;

import com.datamasterylab.entities.SensorData;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.serialization.DeserializationSchema;
import com.datamasterylab.utils.CreateTableSink;  // ← ADD THIS LINE
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestartStrategyOptions;
import org.apache.flink.connector.jdbc.JdbcConnectionOptions;
import org.apache.flink.connector.jdbc.JdbcExecutionOptions;
import org.apache.flink.connector.jdbc.JdbcSink;
import org.apache.flink.connector.jdbc.JdbcStatementBuilder;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

import org.apache.flink.streaming.api.windowing.assigners.TumblingProcessingTimeWindows;
import org.apache.flink.streaming.connectors.kinesis.FlinkKinesisConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

import com.datamasterylab.serializers.SensorDataDeserializationSchema;

/**
 * Skeleton for a Flink DataStream Job.
 *
 * <p>For a tutorial how to write a Flink application, check the
 * tutorials and examples on the <a href="https://flink.apache.org">Flink Website</a>.
 *
 * <p>To package your application into a JAR file for execution, run
 * 'mvn clean package' on the command line.
 *
 * <p>If you change the name of the main class (with the public static void main(String[] args))
 * method, change the respective entry in the POM.xml file (simply search for 'mainClass').
 */
public class DataStreamJob {

	private static final Logger LOGGER = LogManager.getLogger(DataStreamJob.class);

	private static FlinkKinesisConsumer<SensorData> getKinesisSource(StreamExecutionEnvironment env,
																	 Properties appProperties, String streamName) {
		DeserializationSchema<SensorData> deserializationSchema = new SensorDataDeserializationSchema();

		return new FlinkKinesisConsumer<>(appProperties.getProperty(streamName),
				deserializationSchema,
				appProperties);
	}


	public static void main(String[] args) throws Exception {
		Configuration config = new Configuration();
		config.set(RestartStrategyOptions.RESTART_STRATEGY, "fixed-delay");
		config.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_ATTEMPTS, 5);
		config.set(RestartStrategyOptions.RESTART_STRATEGY_FIXED_DELAY_DELAY, Duration.ofSeconds(10));

		final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
		env.setRuntimeMode(RuntimeExecutionMode.STREAMING);

		Properties props = new Properties();
		try (InputStream input = DataStreamJob.class.getClassLoader().getResourceAsStream("application.properties")) {
			if (input == null) {
				LOGGER.error("Sorry, unable to find application.properties");
			}

			props.load(input);
		} catch (IOException ex) {
			LOGGER.error(ex.getMessage());
		}

		// Establish connection Postgres
		CreateTableSink createTableSink = new CreateTableSink(props.getProperty("jdbc.url"));

		FlinkKinesisConsumer<SensorData> tempSource = getKinesisSource(env, props, "KinesisTempStreamName");
		DataStream<SensorData> temperatureDataStream = env.addSource(tempSource).name("Kinesis Temperature Source");

		FlinkKinesisConsumer<SensorData> humiditySource = getKinesisSource(env, props, "KinesisHumidityStreamName");
		DataStream<SensorData> humidityDataStream = env.addSource(humiditySource).name("Kinesis Humidity Source");

		FlinkKinesisConsumer<SensorData> energySource = getKinesisSource(env, props, "KinesisEnergyStreamName");
		DataStream<SensorData> energyDataStream = env.addSource(energySource).name("Kinesis Energy Source");


		DataStream<SensorData> allSensorValues = temperatureDataStream
				.union(humidityDataStream)
				.union(energyDataStream);


		DataStream<SensorData> filteredStream = allSensorValues.filter(
				(FilterFunction<SensorData>) sensorData -> sensorData.getValue() > 0);

		// average value per sensor type
		DataStream<Tuple2<String, Float>> averageValueStream = filteredStream
				.keyBy(SensorData::getSensor_type)
				.window(TumblingProcessingTimeWindows.of(Duration.ofMinutes(1)))
				.aggregate(new AggregateFunction<SensorData, Tuple3<String, Float, Integer>, Tuple2<String, Float>>() {
					@Override
					public Tuple3<String, Float, Integer> createAccumulator(){
						return new Tuple3<>("", 0f, 0);
					}

					@Override
					public Tuple3<String, Float, Integer> add(SensorData sensorData, Tuple3<String, Float, Integer> acc) {
						return new Tuple3<>(sensorData.getSensor_type(), acc.f1 + sensorData.getValue(), acc.f2 + 1);
					}

					@Override
					public Tuple2<String, Float> getResult(Tuple3<String, Float, Integer> acc) {
						return new Tuple2<>(acc.f0, acc.f1/acc.f2);
					}

					@Override
					public Tuple3<String, Float, Integer> merge(Tuple3<String, Float, Integer> a, Tuple3<String, Float, Integer> b) {
						return new Tuple3<>(a.f0, a.f1+b.f1, a.f2+b.f2);
					}
				});


		// average value per sensor type
		DataStream<Tuple2<String, Integer>> countValueStream = filteredStream
				.keyBy(SensorData::getSensor_type)
				.window(TumblingProcessingTimeWindows.of(Duration.ofMinutes(1)))
				.aggregate(new AggregateFunction<SensorData, Tuple2<String, Integer>, Tuple2<String, Integer>>() {
					@Override
					public Tuple2<String, Integer> createAccumulator(){
						return new Tuple2<>("", 0);
					}

					@Override
					public Tuple2<String, Integer> add(SensorData sensorData, Tuple2<String, Integer> acc) {
						return new Tuple2<>(sensorData.getSensor_type(), acc.f1 + 1);
					}

					@Override
					public Tuple2<String, Integer> getResult(Tuple2<String, Integer> acc) {
						return acc;
					}

					@Override
					public Tuple2<String,  Integer> merge(Tuple2<String, Integer> a, Tuple2<String, Integer> b) {
						return new Tuple2<>(a.f0, a.f1+b.f1);
					}
				});

		filteredStream.addSink(createTableSink).name("JDBC Sink for Create Table");

		// sink for all sensors data
		filteredStream.addSink(JdbcSink.sink(
				"INSERT INTO sensor_data_msf(device_id, sensor_type, timestamp, value, unit) VALUES(?,?,?,?,?)",
				(JdbcStatementBuilder<SensorData>) (ps, sensorData) -> {
					ps.setString(1, sensorData.getDevice_id());
					ps.setString(2, sensorData.getSensor_type());
					ps.setString(3, sensorData.getTimestamp());
					ps.setFloat(4, sensorData.getValue());
					ps.setString(5, sensorData.getUnit());
				},
				new JdbcExecutionOptions.Builder()
						.withBatchSize(1000)
						.withBatchIntervalMs(200)
						.withMaxRetries(5)
						.build(),
				new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
						.withUrl(props.getProperty("jdbc.url"))
						.withDriverName("org.postgresql.Driver")
						.build()
		)).name("JDBC Sink for All Sensor Data");


//		sink for avg value per sensor type
		averageValueStream.addSink(JdbcSink.sink(
				"INSERT INTO avgValuePerSensorType(sensor_type, avg_value) VALUES(?,?) ON CONFLICT(sensor_type) DO UPDATE SET avg_value = EXCLUDED.avg_value",
				(JdbcStatementBuilder<Tuple2<String, Float>>) (ps, tuple) -> {
					ps.setString(1, tuple.f0);
					ps.setFloat(2, tuple.f1);
				},
				new JdbcExecutionOptions.Builder()
						.withBatchSize(1000)
						.withBatchIntervalMs(200)
						.withMaxRetries(5)
						.build(),
				new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
						.withUrl(props.getProperty("jdbc.url"))
						.withDriverName("org.postgresql.Driver")
						.build()
		)).name("JDBC Sink for Avg Value Per Sensor Type");

		// sink for count per sensor type
		countValueStream.addSink(JdbcSink.sink(
				"INSERT INTO countPerSensorType(sensor_type, event_count) VALUES(?,?) ON CONFLICT(sensor_type) DO UPDATE SET event_count = EXCLUDED.event_count",
				(JdbcStatementBuilder<Tuple2<String, Integer>>) (ps, tuple) -> {
					ps.setString(1, tuple.f0);
					ps.setInt(2, tuple.f1);
				},
				new JdbcExecutionOptions.Builder()
						.withBatchSize(1000)
						.withBatchIntervalMs(200)
						.withMaxRetries(5)
						.build(),
				new JdbcConnectionOptions.JdbcConnectionOptionsBuilder()
						.withUrl(props.getProperty("jdbc.url"))
						.withDriverName("org.postgresql.Driver")
						.build()
		)).name("JDBC Sink for Count Per Sensor Type");





		// Execute program, beginning computation.
		env.execute("Flink Java API Skeleton");
	}

}
