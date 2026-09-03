# PLAT-005 — Externalized Configuration with ConfigMap and Secret

## Objective

Externalize the runtime configuration of `booking-api` without rebuilding the Docker image.

The application must:

- Read non-sensitive configuration from a Kubernetes `ConfigMap`
- Read sensitive configuration from a Kubernetes `Secret`
- Keep using the same container image
- Avoid embedding environment-specific values in the Docker image
- Support different runtime configurations depending on the target environment

The image remains:

```text
kube-lab/booking-api:0.1.0
```

---

## Initial State

The Spring Boot application contained fallback configuration values:

```yaml
app:
  environment: ${APP_ENVIRONMENT:local}
  version: ${APP_VERSION:1.0.0}
```

Without Kubernetes configuration, the API returned:

```json
{
  "application": "booking-api",
  "environment": "local",
  "version": "1.0.0"
}
```

The goal was to override these values without rebuilding the image.

---

## Target Architecture

```text
                  booking-api Deployment
                           |
                +----------+----------+
                |                     |
                v                     v
             API Pod #1            API Pod #2
                |                     |
                +----------+----------+
                           |
                    Runtime Environment
                      /           \
                     /             \
                    v               v
              ConfigMap           Secret
                  |                 |
        APP_ENVIRONMENT=dev        |
        APP_VERSION=0.1.0          |
                                   |
                         BOOKING_INTERNAL_TOKEN
```

The application image remains immutable while its runtime configuration is provided by Kubernetes.

---

## ConfigMap

A ConfigMap named `booking-api-config` was created.

```yaml
apiVersion: v1
kind: ConfigMap

metadata:
  name: booking-api-config
  namespace: booking-dev

data:
  APP_ENVIRONMENT: dev
  APP_VERSION: 0.1.0
```

The ConfigMap stores non-sensitive configuration.

---

## Secret

A Kubernetes Secret named `booking-api-secret` was created.

```yaml
apiVersion: v1
kind: Secret

metadata:
  name: booking-api-secret
  namespace: booking-dev

type: Opaque

stringData:
  BOOKING_INTERNAL_TOKEN: change-me-in-real-environments
```

The value used in this lab is intentionally fictitious.

No real credential or sensitive information is stored in the public repository.

---

## Why `stringData` Was Used

Kubernetes Secrets support both:

```text
data
```

and:

```text
stringData
```

`stringData` allows plain text values to be written in the manifest.

Kubernetes converts them internally into the encoded `data` representation.

Important:

```text
Base64 != encryption
```

A Kubernetes Secret should therefore not be considered secure simply because its stored representation is Base64-encoded.

Production environments often introduce additional secret-management mechanisms such as:

```text
Vault
External Secrets Operator
Cloud secret stores
Encrypted GitOps secrets
```

---

## Deployment Integration

The existing `booking-api` Deployment was updated to inject both resources using `envFrom`.

```yaml
apiVersion: apps/v1
kind: Deployment

metadata:
  name: booking-api
  namespace: booking-dev

  labels:
    app: booking-api

spec:
  replicas: 2

  selector:
    matchLabels:
      app: booking-api

  template:
    metadata:
      labels:
        app: booking-api

    spec:
      containers:
        - name: booking-api
          image: kube-lab/booking-api:0.1.0

          ports:
            - containerPort: 8080

          envFrom:
            - configMapRef:
                name: booking-api-config

            - secretRef:
                name: booking-api-secret
```

---

## Configuration Model

The resulting configuration flow is:

```text
ConfigMap
   |
   +--> APP_ENVIRONMENT
   |
   +--> APP_VERSION
             \
              \
               v
             Pod environment
               ^
              /
             /
Secret
   |
   +--> BOOKING_INTERNAL_TOKEN
```

Spring Boot then resolves:

```text
APP_ENVIRONMENT
APP_VERSION
```

through environment variables.

---

## Validation

### Check ConfigMap

```bash
kubectl get configmap booking-api-config \
  -n booking-dev \
  -o yaml
```

Expected values:

```yaml
data:
  APP_ENVIRONMENT: dev
  APP_VERSION: 0.1.0
```

---

### Check Secret

```bash
kubectl get secret booking-api-secret \
  -n booking-dev
```

Expected resource:

```text
booking-api-secret
```

The secret value does not need to be printed during validation.

---

## Deployment Validation

The live Deployment was inspected using:

```bash
kubectl get deployment booking-api \
  -n booking-dev \
  -o jsonpath='{.spec.template.spec.containers[0].envFrom}'
```

Observed configuration:

```text
[
  {"configMapRef":{"name":"booking-api-config"}},
  {"secretRef":{"name":"booking-api-secret"}}
]
```

This confirmed that both resources were referenced by the Pod template.

---

## Pod Environment Validation

The environment variables were inspected directly inside a backend Pod.

```bash
kubectl exec \
  -n booking-dev \
  <booking-api-pod> \
  -- printenv APP_ENVIRONMENT
```

Expected:

```text
dev
```

Version:

```bash
kubectl exec \
  -n booking-dev \
  <booking-api-pod> \
  -- printenv APP_VERSION
```

Expected:

```text
0.1.0
```

Secret presence can be verified without printing the actual value:

```bash
kubectl exec \
  -n booking-dev \
  <booking-api-pod> \
  -- sh -c 'test -n "$BOOKING_INTERNAL_TOKEN" && echo "BOOKING_INTERNAL_TOKEN is set"'
```

Expected:

```text
BOOKING_INTERNAL_TOKEN is set
```

---

## Functional Validation

The application was tested through the existing Gateway API entry point.

```bash
curl http://localhost:8888/api/info
```

Expected response:

```json
{
  "application": "booking-api",
  "environment": "dev",
  "instance": "booking-api-...",
  "version": "0.1.0"
}
```

The application is now configured for the `dev` environment without any Docker image rebuild.

---

## Immutable Image Principle

Before externalized configuration:

```text
Docker image
   |
   +--> application
   +--> environment-specific configuration
```

After externalized configuration:

```text
Docker image
   |
   +--> application only

Kubernetes
   |
   +--> ConfigMap
   +--> Secret
```

The same image can therefore be reused across environments.

```text
kube-lab/booking-api:0.1.0
          |
     +----+----+
     |         |
     v         v
    DEV      PREPROD
```

Only the runtime configuration changes.

---

## Configuration Change Test

The ConfigMap value was changed from:

```yaml
APP_VERSION: 0.1.0
```

to another value.

The resource was then updated using:

```bash
kubectl apply -f k8s/base/booking-api-config.yml
```

However, the already-running Pods continued to expose the old environment values.

Example:

```bash
kubectl exec \
  -n booking-dev \
  <existing-pod> \
  -- printenv APP_VERSION
```

still returned the old value.

---

## Important Runtime Behaviour

ConfigMaps and Secrets injected through:

```yaml
env:
```

or:

```yaml
envFrom:
```

are evaluated when the Pod is created.

Conceptually:

```text
ConfigMap
    |
    v
Pod creation
    |
    v
Environment variables
    |
    v
Running process
```

Changing the source ConfigMap afterwards does not mutate the process environment of an already-running container.

---

## Applying the Updated Configuration

The Pods were restarted through the Deployment:

```bash
kubectl rollout restart deployment/booking-api -n booking-dev
```

Rollout status:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

A new ReplicaSet and new Pods were created.

```text
Deployment
    |
    +--> old ReplicaSet
    |       replicas: 0
    |
    +--> new ReplicaSet
            replicas: 2
```

The new Pods received the latest ConfigMap and Secret values.

---

## ReplicaSet Validation

```bash
kubectl get rs -n booking-dev
```

This allows the rollout history to be observed through the successive ReplicaSets created by the Deployment.

---

## Key Difference: Environment Variables vs Volumes

ConfigMap behaviour depends on how it is consumed.

### Environment Variable Injection

```text
ConfigMap
   |
envFrom
   |
Pod creation
   |
Environment variable
```

Changes require Pod recreation.

---

### Volume Mount

A ConfigMap can also be mounted as files:

```text
ConfigMap
   |
Volume
   |
Files inside Pod
```

Kubernetes can eventually update mounted files.

However, the application must still be capable of re-reading those files dynamically.

Updating files does not automatically guarantee that the running application reloads its configuration.

---

## Troubleshooting Commands

Inspect ConfigMap:

```bash
kubectl get configmap booking-api-config \
  -n booking-dev \
  -o yaml
```

Inspect Deployment references:

```bash
kubectl get deployment booking-api \
  -n booking-dev \
  -o jsonpath='{.spec.template.spec.containers[0].envFrom}'
```

Inspect Pod variables:

```bash
kubectl exec \
  -n booking-dev \
  <booking-api-pod> \
  -- env | grep -E 'APP_|BOOKING_'
```

Inspect ReplicaSets:

```bash
kubectl get rs -n booking-dev
```

Restart Deployment:

```bash
kubectl rollout restart deployment/booking-api -n booking-dev
```

Monitor rollout:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

---

## Key Learnings

- Docker images should remain immutable across environments.
- Environment-specific configuration belongs outside the application image.
- ConfigMaps are appropriate for non-sensitive runtime configuration.
- Secrets are intended for sensitive runtime values.
- Base64 encoding does not provide encryption.
- `envFrom` can inject all ConfigMap or Secret keys as environment variables.
- ConfigMap changes do not automatically update environment variables inside existing Pods.
- Pod recreation is required for environment-variable based configuration changes.
- `kubectl rollout restart` can be used to trigger a controlled Pod recreation.
- Configuration behaviour must be understood at both Kubernetes and application-runtime levels.

---

## Result

PLAT-005 completed successfully.

```text
✅ ConfigMap created
✅ Secret created
✅ Deployment references ConfigMap
✅ Deployment references Secret
✅ APP_ENVIRONMENT externalized
✅ APP_VERSION externalized
✅ Secret injected without printing its value
✅ Docker image unchanged
✅ Dev configuration successfully loaded
✅ ConfigMap update behaviour observed
✅ Controlled Deployment restart performed
✅ New Pods loaded updated configuration
```

The application now follows an immutable-image / externalized-configuration model suitable for multi-environment Kubernetes deployments.

---

## Next Step

PLAT-006 will introduce Kubernetes health management:

```text
startupProbe
readinessProbe
livenessProbe
```

The goal will be to teach Kubernetes when an application is:

```text
started
ready to receive traffic
healthy enough to remain running
```

and to intentionally reproduce a probe failure for troubleshooting practice.
