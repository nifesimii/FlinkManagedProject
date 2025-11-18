package com.datamasterylab.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SensorData {
    private String device_id;
    private String timestamp;
    private String sensor_type;
    private float value;
    private String unit;

    public SensorData(String device_id, float value) {
        this.device_id = device_id;
        this.value = value;
    }
}
