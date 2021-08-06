#! /bin/bash
# Copyright (c) Dell Inc., or its subsidiaries. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
set -e
ROOT_DIR=$(readlink -f $(dirname $0)/..)
source ${ROOT_DIR}/scripts/env.sh
: ${NAMESPACE?"You must export NAMESPACE"}
: ${INPUT_STREAM?"You must export INPUT_STREAM"}

# Start a job
VALUES_FILE=${ROOT_DIR}/values/metrics.yaml


# Deploy influxdb grafana
kubectl apply -f ${ROOT_DIR}/scripts/MetricsKeycloak.yaml
sleep 4
kubectl apply -f ${ROOT_DIR}/scripts/Metrics.yaml

# Publish Application
${ROOT_DIR}/scripts/publish.sh

export RELEASE_NAME=$(basename "${INPUT_STREAM}" .yaml | tr '[:upper:]' '[:lower:]')
echo ${RELEASE_NAME}

#deploy flink cluster
helm upgrade --install --timeout 600s  --wait \
    flink-cluster \
    ${ROOT_DIR}/charts/flinkCluster \
    --namespace ${NAMESPACE} \
    -f "${VALUES_FILE}" \
    $@

#deploy applications
helm upgrade --install --timeout 600s  --wait \
    ${RELEASE_NAME} \
    ${ROOT_DIR}/charts/analytic \
    --namespace ${NAMESPACE} \
    -f "${VALUES_FILE}" \
    --set "appParameters.input-stream=${NAMESPACE}/${INPUT_STREAM}" \
    --set "mavenCoordinate.artifact=${APP_ARTIFACT_ID}" \
    --set "mavenCoordinate.group=${APP_GROUP_ID}" \
    --set "mavenCoordinate.version=${APP_VERSION}" \
    $@

# expose for litmus
kubectl expose svc milos --type=LoadBalancer --name=litmus | true

echo "use the following IP to access influxdb outside SDP"
kubectl get svc litmus

echo " Grafana URL"
kubectl get ing

