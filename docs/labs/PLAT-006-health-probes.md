# PLAT-006 — Kubernetes Health Probes

## Objective

Add Kubernetes health probes to `booking-api` so that the platform can distinguish between:

- application startup
- application readiness
- application liveness

The backend already exposes Spring Boot Actuator health endpoints.

---

## Target Health Model

```text
startupProbe
     |
     v
Has the application finished starting?
     |
     v
readinessProbe
     |
     v
Can the application receive traffic?
     |
     v
livenessProbe
     |
     v
Is the application still healthy enough to remain running?
```

---

## Spring Boot Health Endpoints

The backend exposes:

```text
/actuator/health/liveness
/actuator/health/readiness
```

These endpoints are used by Kubernetes to evaluate the application state.

---

## Probe Configuration

The `booking-api` Deployment was updated with:

```yaml
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080

  failureThreshold: 30
  periodSeconds: 2

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080

  initialDelaySeconds: 5
  periodSeconds: 5
  timeoutSeconds: 2
  failureThreshold: 3

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080

  initialDelaySeconds: 10
  periodSeconds: 10
  timeoutSeconds: 2
  failureThreshold: 3
```

---

## Probe Roles

### Startup Probe

The `startupProbe` protects slow-starting applications.

```text
Application starting
       |
startupProbe
       |
       +--> failure -> keep waiting
       |
       +--> success -> enable normal health checks
```

While the startup probe is active, Kubernetes does not use the liveness probe to restart the application.

---

### Readiness Probe

The `readinessProbe` determines whether a Pod should receive traffic.

```text
readinessProbe
      |
      +--> success
      |      |
      |      v
      |   Pod Ready
      |      |
      |      v
      |   Service traffic
      |
      +--> failure
             |
             v
         Pod NotReady
             |
             X
        Service traffic
```

A readiness failure does not necessarily restart the container.

---

### Liveness Probe

The `livenessProbe` determines whether Kubernetes should restart the container.

```text
livenessProbe
      |
      +--> success -> keep container running
      |
      +--> repeated failure
                    |
                    v
             container restart
```

---

## Deployment

The probes were added directly to the existing `booking-api` container.

Conceptually:

```text
Deployment
    |
    v
booking-api Pod
    |
    +--> startupProbe
    |
    +--> readinessProbe
    |
    +--> livenessProbe
```

---

## Rollout Validation

The updated Deployment was applied:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

The rollout was monitored:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

Pods were observed with:

```bash
kubectl get pods -n booking-dev -w
```

During startup, Pods can temporarily appear as:

```text
0/1 Running
```

and then transition to:

```text
1/1 Running
```

once the readiness probe succeeds.

---

## Probe Inspection

The Pod configuration can be inspected with:

```bash
kubectl describe pod <booking-api-pod> -n booking-dev
```

Relevant sections include:

```text
Startup
Readiness
Liveness
```

---

## Manual Health Validation

Readiness:

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl http://booking-api:8080/actuator/health/readiness
```

Expected:

```json
{ "status": "UP" }
```

Liveness:

```bash
kubectl run curl \
  --image=curlimages/curl \
  --rm -it \
  --restart=Never \
  -n booking-dev \
  -- curl http://booking-api:8080/actuator/health/liveness
```

Expected:

```json
{ "status": "UP" }
```

---

## Readiness Failure Exercise

To understand Kubernetes rollout behaviour, the readiness path was intentionally misconfigured.

Incorrect path:

```yaml
path: /actuator/health/readines
```

instead of:

```yaml
path: /actuator/health/readiness
```

The Deployment was reapplied.

---

## Observed Behaviour

The new Pod started successfully but did not become Ready.

Observed state:

```text
booking-api-5d76fdd7dc-8v9kk   1/1   Running
booking-api-5d76fdd7dc-gbmjg   1/1   Running

booking-api-86c676689d-mbgk6   0/1   Running
```

The two previous Pods remained available.

The new Pod remained:

```text
Running
```

but:

```text
NotReady
```

---

## RollingUpdate Protection

The Deployment uses the default `RollingUpdate` strategy.

Conceptually:

```text
Old ReplicaSet
  Pod #1 Ready
  Pod #2 Ready
       |
       | rollout starts
       v
New ReplicaSet
  Pod #3 NotReady
       |
       X
Kubernetes does not remove old Ready Pods
```

This protects the application from a broken release.

---

## Result During Failure

```text
New release
     |
readiness failure
     |
rollout blocked
     |
old Pods remain Ready
     |
application remains available
```

A failed release therefore did not automatically create downtime.

---

## Recovery

The correct readiness endpoint was restored:

```yaml
path: /actuator/health/readiness
```

The Deployment was applied again:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

The rollout was monitored:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

Once the new Pods became Ready, Kubernetes completed the rollout and removed obsolete replicas.

---

## Troubleshooting Commands

```bash
kubectl get pods -n booking-dev

kubectl get pods -n booking-dev -w

kubectl describe pod <pod> -n booking-dev

kubectl describe deployment booking-api -n booking-dev

kubectl get rs -n booking-dev

kubectl rollout status deployment/booking-api -n booking-dev

kubectl get endpointslices \
  -n booking-dev \
  -l kubernetes.io/service-name=booking-api
```

---

## Key Learnings

- `startupProbe` manages application startup.
- `readinessProbe` controls whether a Pod receives traffic.
- `livenessProbe` controls whether a container should be restarted.
- `Running` does not necessarily mean `Ready`.
- A failed readiness probe can remove a Pod from Service traffic without killing it.
- RollingUpdate can preserve healthy old replicas during a broken release.
- Health probes are part of deployment safety, not just monitoring.
- Incorrect probes can block a release even when the application process itself is running.

---

## Result

PLAT-006 completed successfully.

```text
✅ Startup probe configured
✅ Readiness probe configured
✅ Liveness probe configured
✅ Spring Boot Actuator endpoints validated
✅ Ready state observed
✅ Readiness failure reproduced
✅ RollingUpdate protection observed
✅ Previous healthy replicas preserved
✅ Broken release recovered
```

The backend now exposes meaningful application health information to Kubernetes.
