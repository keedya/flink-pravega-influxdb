package io.pravega.flinkprocessor.datatypes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Date;

@JsonIgnoreProperties(ignoreUnknown = true)
public class tag {
    public String tagName;
    public String deviceName;
    public String deviceID;
    public String registerId;
    public long value;
    public boolean success;
    public long timestamp;
    public String datatype;
    public String description;


    @Override
    public String toString() {
        return "MetricReport{" +
                "tagName='" + tagName + '\'' +
                ", deviceName='" + deviceName + '\'' +
                ", deviceID='" + deviceID + '\'' +
                ", registerId='" + registerId + '\'' +
                ", datatype='" + datatype + '\'' +
                ", description='" + description + '\'' +
                ", timestamp='" + timestamp + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}