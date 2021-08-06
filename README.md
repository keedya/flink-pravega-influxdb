# flink pravega -> influxDB

```aidl
wget http://10.247.52.38/influxsink/influxsink-0.0.2.tgz
tar xzvf influxsink-0.0.2.tgz
cd influxsink
export NAMESPACE=<project name>
export INPUT_STREAM=agg-stream4
./scripts/install_on_sdp.sh
```