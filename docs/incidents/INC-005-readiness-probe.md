# INC-005 — Readiness Probe Failure Blocks Deployment Rollout

## Incident Summary

A deliberately incorrect `readinessProbe` prevented new `booking-api` Pods from becoming Ready.

The Deployment rollout eventually failed with:

```text
deployment "booking-api" exceeded its progress deadline
```

However, the application remained available because Kubernetes preserved the previous healthy replicas.

---

## Environment

```text
Namespace: booking-dev
Deployment: booking-api
Replicas: 2
Strategy: RollingUpdate
Health endpoint: Spring Boot Actuator
```

---

## Fault Injection

The readiness endpoint was intentionally misconfigured.

Incorrect:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readines
    port: 8080
```

Correct:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
```

The invalid path returned an unsuccessful health response.

---

## Symptoms

The new Deployment revision created a new Pod.

Observed state:

```text
NAME                               READY   STATUS
booking-api-5d76fdd7dc-8v9kk       1/1     Running
booking-api-5d76fdd7dc-gbmjg       1/1     Running
booking-api-86c676689d-mbgk6       0/1     Running
```

Two older Pods were still Ready.

The new Pod was:

```text
Running
```

but:

```text
NotReady
```

---

## Rollout Failure

The Deployment rollout remained blocked.

Eventually:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

returned:

```text
error: deployment "booking-api" exceeded its progress deadline
```

---

## Investigation

Pod events were inspected:

```bash
kubectl describe pod booking-api-86c676689d-mbgk6 \
  -n booking-dev
```

The readiness check failed because the configured HTTP endpoint was incorrect.

Conceptually:

```text
Kubernetes
     |
     | GET /actuator/health/readines
     v
Spring Boot
     |
     v
invalid endpoint
     |
     v
readiness failure
```

---

## Deployment State

The Deployment had two healthy replicas from the previous ReplicaSet and one unhealthy replica from the new revision.

```text
Old ReplicaSet
    |
    +--> Pod #1 Ready
    |
    +--> Pod #2 Ready

New ReplicaSet
    |
    +--> Pod #3 NotReady
```

---

## Why the Old Pods Were Not Removed

The Deployment uses `RollingUpdate`.

Kubernetes only progresses the rollout when enough new replicas become available.

The new Pod failed readiness.

Therefore Kubernetes preserved the old replicas.

```text
new Pod NotReady
      |
      X
cannot safely replace old Pod
      |
      v
old Pod remains available
```

---

## Availability During Incident

The release failed, but service availability remained intact.

```text
Broken new revision
       |
       v
NotReady
       |
       X
No production traffic
```

Meanwhile:

```text
Old revision
    |
Ready Pods
    |
Service
    |
Application traffic
```

This demonstrates why readiness checks are a critical deployment protection mechanism.

---

## ProgressDeadlineExceeded

Because the rollout made no successful progress for the configured deployment deadline, Kubernetes reported:

```text
ProgressDeadlineExceeded
```

The Deployment could not reach its desired updated state.

This does not necessarily mean all running application instances are unavailable.

---

## ReplicaSet Investigation

ReplicaSets can be inspected using:

```bash
kubectl get rs -n booking-dev
```

This reveals which revision currently owns the healthy and failing Pods.

---

## Service Behaviour

The `booking-api` Service should route application traffic only toward Ready endpoints.

The endpoint state can be inspected with:

```bash
kubectl get endpointslices \
  -n booking-dev \
  -l kubernetes.io/service-name=booking-api
```

The new NotReady Pod should not be treated as a normal traffic destination.

---

## Root Cause

Incorrect readiness endpoint:

```text
/actuator/health/readines
```

instead of:

```text
/actuator/health/readiness
```

The application process itself was healthy enough to remain running, but Kubernetes correctly classified the new Pod as unable to receive traffic.

---

## Resolution

The probe endpoint was corrected:

```yaml
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
```

The Deployment was reapplied:

```bash
kubectl apply -f k8s/base/booking-api.yml
```

Then:

```bash
kubectl rollout status deployment/booking-api -n booking-dev
```

The new Pods successfully became Ready.

Kubernetes then completed the rolling update.

---

## Final Behaviour

```text
Correct readinessProbe
        |
        v
new Pod Ready
        |
        v
Service can send traffic
        |
        v
old replica removed
        |
        v
rollout completes
```

---

## Troubleshooting Model

When a rollout remains stuck:

```text
Deployment progressing?
      |
      v
New Pods created?
      |
      v
Running?
      |
      v
Ready?
      |
      +--> No
             |
             v
      kubectl describe pod
             |
             v
         probe failure?
```

Then inspect:

```text
Deployment conditions
ReplicaSets
Pod Events
EndpointSlices
```

before modifying unrelated resources.

---

## Key Learnings

- A Pod can be `Running` but not `Ready`.
- Readiness failure does not necessarily restart a container.
- NotReady Pods should not receive normal Service traffic.
- RollingUpdate can protect availability during a failed deployment.
- Kubernetes can keep the previous revision alive while rejecting the new one.
- `ProgressDeadlineExceeded` indicates rollout failure, not necessarily total application outage.
- Probe configuration directly impacts release safety.

---

## Resolution Status

```text
✅ Readiness failure reproduced
✅ New Pod remained NotReady
✅ Previous replicas stayed available
✅ Application availability preserved
✅ ProgressDeadlineExceeded observed
✅ Root cause identified
✅ Probe corrected
✅ Rollout recovered
```

**Status: RESOLVED**
