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
: ${DOCKER_REGISTRY?"You must export DOCKER_REGISTRY"}
: ${TSDB_FQN?"You must export TSDB_FQN"}
# Start a job
VALUES_FILE=${ROOT_DIR}/values/metrics.yaml


#deploy new influxdb with tls
# Load the image
sudo -E ${SDP_INSTALL_EXECUTABLE} --accept-eula push --registry ${DOCKER_REGISTRY} --input ${ROOT_DIR}/docker_images/influx.tar.gz

helm upgrade --install tsdb --timeout 600s  --wait \
     --set image.repository=${DOCKER_REGISTRY}/influxdb \
     --set image.tag=1.8.0-alpine  \
     --set setDefaultUser.image=${DOCKER_REGISTRY}/curl:latest \
     --set ingress.hostname=${TSDB_FQN} \
     ${ROOT_DIR}/charts/influxdb \
     $@

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

echo " InfluxDB URL"
kubectl get ing

