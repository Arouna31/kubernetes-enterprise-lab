# INC-004 — ConfigMap Update Not Reflected in Running Pods

## Incident Summary

The `booking-api` configuration was externalized through a Kubernetes ConfigMap.

The ConfigMap was successfully updated, but the running Spring Boot Pods continued to expose the previous configuration values.

The Kubernetes resources appeared valid, yet the application did not reflect the expected runtime configuration.

---

## Environment

```text
Namespace: booking-dev
Application: booking-api
Replicas: 2
Configuration: ConfigMap + Secret
Injection method: envFrom
```

Relevant resources:

```text
booking-api Deployment
booking-api-config ConfigMap
booking-api-secret Secret
```

---

## Expected Behaviour

The ConfigMap contained:

```yaml
APP_ENVIRONMENT: dev
APP_VERSION: 0.1.0
```

The expected API response was:

```json
{
  "environment": "dev",
  "version": "0.1.0"
}
```

---

## Actual Behaviour

The API continued to return:

```json
{
  "application": "booking-api",
  "environment": "local",
  "instance": "booking-api-59499dd587-grbl9",
  "version": "1.0.0"
}
```

Direct inspection of the Pod confirmed the same values:

```bash
kubectl exec \
  -n booking-dev \
  booking-api-59499dd587-grbl9 \
  -- printenv APP_VERSION
```

Result:

```text
1.0.0
```

And:

```bash
kubectl exec \
  -n booking-dev \
  booking-api-59499dd587-grbl9 \
  -- printenv APP_ENVIRONMENT
```

Result:

```text
local
```

---

## Investigation

The Deployment configuration was checked.

```bash
kubectl get deployment booking-api \
  -n booking-dev \
  -o jsonpath='{.spec.template.spec.containers[0].envFrom}'
```

Result:

```text
[
  {"configMapRef":{"name":"booking-api-config"}},
  {"secretRef":{"name":"booking-api-secret"}}
]
```

The Deployment correctly referenced both resources.

---

## Deployment Diff Validation

The local manifest was compared with the live Deployment:

```bash
kubectl diff -f k8s/base/booking-api.yml
```

No differences were reported.

Applying the Deployment again returned:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

Result:

```text
deployment.apps/booking-api unchanged
```

The Deployment itself was therefore already up to date.

---

## ReplicaSet Investigation

ReplicaSets were inspected:

```bash
kubectl get rs -n booking-dev
```

Observed state:

```text
NAME                         DESIRED   CURRENT   READY   AGE
booking-api-59499dd587       2         2         2       8d
booking-api-d9d8bc57d        0         0         0       15d
booking-frontend-cbfd5c4f8   2         2         2       13d
```

The active backend ReplicaSet was several days old.

No new ReplicaSet had been created when only the ConfigMap contents changed.

---

## Root Cause

The ConfigMap was consumed through:

```yaml
envFrom:
  - configMapRef:
      name: booking-api-config
```

Environment variables are injected when a Pod is created.

The running process environment is not dynamically updated when the source ConfigMap changes.

Conceptually:

```text
ConfigMap v1
    |
    v
Pod created
    |
APP_VERSION=1.0.0
    |
Running process
```

Later:

```text
ConfigMap v2
APP_VERSION=0.1.0
    |
    X
    |
existing Pod
APP_VERSION=1.0.0
```

The Deployment template itself did not change.

Therefore Kubernetes had no reason to create a new ReplicaSet automatically.

---

## Why `kubectl apply` Did Not Restart the Pods

The Deployment only stores a reference to:

```text
booking-api-config
```

It does not embed the ConfigMap contents in its Pod template.

Before:

```yaml
configMapRef:
  name: booking-api-config
```

After:

```yaml
configMapRef:
  name: booking-api-config
```

From the Deployment's point of view:

```text
Pod template unchanged
```

Therefore:

```text
No new ReplicaSet
No new Pods
```

---

## Resolution

A controlled restart of the Deployment was triggered:

```bash
kubectl rollout restart deployment/booking-api -n booking-dev
```

The rollout was monitored:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

New Pods were created.

At startup, they loaded the current ConfigMap values.

---

## Validation

The new Pod environment was checked:

```bash
kubectl exec \
  -n booking-dev \
  <new-booking-api-pod> \
  -- printenv APP_ENVIRONMENT
```

Result:

```text
dev
```

Version:

```bash
kubectl exec \
  -n booking-dev \
  <new-booking-api-pod> \
  -- printenv APP_VERSION
```

Result:

```text
0.1.0
```

The API was tested again:

```bash
curl http://localhost:8888/api/info
```

The expected runtime configuration was now returned.

---

## Final Runtime Model

```text
ConfigMap updated
      |
      v
Deployment restart
      |
      v
new ReplicaSet
      |
      v
new Pods
      |
      v
latest environment variables
```

---

## Alternative Consumption Model

ConfigMaps can also be consumed as mounted volumes.

```text
ConfigMap
    |
    v
mounted files
```

In that model, Kubernetes may refresh the projected files.

However:

```text
File changed
   !=
Application automatically reloaded
```

The application must support dynamic configuration reload for that behaviour to be useful.

---

## Production Considerations

Manually running:

```bash
kubectl rollout restart
```

is acceptable for troubleshooting and controlled lab operations.

Production platforms often automate configuration-driven rollouts through mechanisms such as:

```text
Helm checksum annotations
GitOps pipelines
Reloader controllers
External configuration operators
```

The exact approach depends on the platform architecture.

---

## Troubleshooting Model

When a ConfigMap change is not reflected:

```text
ConfigMap updated?
      |
      v
How is it consumed?
      |
      +--> env / envFrom
      |       |
      |       v
      |   Pod recreated?
      |
      +--> mounted volume
              |
              v
        Files updated?
              |
              v
        Application reloads them?
```

This distinction should be verified before troubleshooting the application itself.

---

## Key Learnings

- ConfigMap content changes do not automatically alter existing process environment variables.
- `env` and `envFrom` values are injected at Pod creation time.
- Updating a ConfigMap does not modify the Deployment Pod template.
- An unchanged Pod template means no automatic new ReplicaSet.
- `kubectl diff` helps distinguish manifest drift from runtime lifecycle behaviour.
- ReplicaSet age is a useful troubleshooting signal.
- `kubectl rollout restart` can recreate Pods without changing the container image.
- Kubernetes configuration lifecycle and application configuration lifecycle are separate concerns.

---

## Resolution Status

```text
✅ ConfigMap verified
✅ Deployment reference verified
✅ Pod environment inspected
✅ Manifest drift excluded
✅ Root cause identified
✅ Deployment restarted
✅ New Pods created
✅ Updated environment loaded
✅ API behaviour validated
```

**Status: RESOLVED**
