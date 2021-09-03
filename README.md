# flink pravega -> influxDB

```aidl
wget http://10.247.52.38/influxsink/influxsink-0.0.2.tgz
tar xzvf influxsink-0.0.2.tgz
cd influxsink
export NAMESPACE=<project name>
export INPUT_STREAM=agg-stream4
./scripts/install_on_sdp.sh
```


# timeScaleDB

## install postgres
```aidl
apt-get install postgresql postgresql-contrib -y
sudo su
passwd postgres
su - postgres
psql -c "alter user postgres with password 'password'"
exit
```

## install TimescaleDB

```aidl
apt-get install gnupg2 software-properties-common curl git unzip -y
add-apt-repository ppa:timescale/timescaledb-ppa -y
apt-get install timescaledb-postgresql-12 -y
su - postgres
timescaledb-tune --quiet --yes
exit
```
## Create a new Database
```aidl
su - postgres
psql
CREATE DATABASE testdb;
```

- change the database to testdb and connect it to the TimescaleDB with the following command
```aidl
\c testdb
CREATE EXTENSION IF NOT EXISTS timescaledb CASCADE;
```

## create Table

```aidl
CREATE TABLE idrac(
time        TIMESTAMP WITH TIME ZONE NOT NULL,
remote_addr TEXT,
value       NUMERIC,
Metric_id   TEXT,
rack_label  TEXT,
context_id  TEXT,
id          TEXT
);
```

- Transform the table into hypertable
```aidl
SELECT create_hypertable('idrac', 'time');
```