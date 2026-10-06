# PLAT-007 — CPU, Memory Requests, Limits and QoS

## Objective

Configure CPU and memory requirements for `booking-api` so Kubernetes can:

- make better scheduling decisions
- reserve a minimum amount of resources
- limit excessive resource consumption
- classify the workload using Kubernetes QoS
- protect the node from uncontrolled memory usage

---

## Resource Configuration

The `booking-api` Deployment was updated with:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "128Mi"

  limits:
    cpu: "500m"
    memory: "256Mi"
```

---

## Resource Model

```text
requests
   |
   v
resources used by the scheduler
to decide where the Pod can run


limits
   |
   v
maximum resources the container
is allowed to consume
```

---

## CPU Units

Kubernetes expresses CPU resources using CPU cores.

```text
1000m = 1 CPU
500m  = 0.5 CPU
100m  = 0.1 CPU
```

For the backend:

```text
request = 100m
limit   = 500m
```

The scheduler therefore plans for at least:

```text
0.1 CPU
```

while the container may consume up to:

```text
0.5 CPU
```

---

## Memory Units

Memory is expressed using binary units such as:

```text
Mi = Mebibyte
Gi = Gibibyte
```

The backend configuration is:

```text
request = 128Mi
limit   = 256Mi
```

Conceptually:

```text
128Mi                         256Mi
  |-----------------------------|
 request                       limit
```

The workload receives a guaranteed scheduling request while still having room to burst.

---

## Scheduling Behaviour

The Kubernetes scheduler primarily evaluates `requests`.

Example:

```text
Node allocatable CPU: 300m
Pod CPU request:       100m

Result:
Pod can be scheduled
```

If the available resources are insufficient:

```text
Node available:   50m
Pod request:     100m

Result:
Pod may remain Pending
```

---

## CPU Limit Behaviour

CPU limits behave differently from memory limits.

If a container attempts to consume more CPU than its limit:

```text
application requests more CPU
          |
          v
CPU limit reached
          |
          v
CPU throttling
```

The process is generally slowed down rather than killed.

For the backend:

```text
CPU limit = 500m
```

A workload attempting to use more CPU can therefore experience:

```text
higher latency
slower request processing
CPU throttling
```

---

## Memory Limit Behaviour

Memory limits are stricter.

If a process consumes more memory than the configured limit:

```text
memory consumption
       |
       v
memory limit exceeded
       |
       v
process terminated
       |
       v
OOMKilled
```

For `booking-api`:

```text
memory limit = 256Mi
```

---

## Deployment Rollout

Adding `resources` modifies the Deployment Pod template.

Therefore Kubernetes creates a new ReplicaSet and new Pods.

```text
Deployment changed
      |
      v
new Pod template
      |
      v
new ReplicaSet
      |
      v
new Pods
```

This behaviour was observed after applying the new resource configuration.

---

## Validation

The Pod configuration was inspected using:

```bash
kubectl describe pod <booking-api-pod> -n booking-dev
```

Expected section:

```text
Requests:
  cpu:      100m
  memory:   128Mi

Limits:
  cpu:      500m
  memory:   256Mi
```

---

## QoS Classification

The backend Pod was classified as:

```text
QoS Class: Burstable
```

This is caused by requests and limits being defined but not equal.

```text
CPU
request = 100m
limit   = 500m

Memory
request = 128Mi
limit   = 256Mi
```

---

## Kubernetes QoS Classes

Kubernetes uses three main QoS classes.

### BestEffort

No requests or limits are configured.

```yaml
resources: {}
```

Conceptually:

```text
No guaranteed resources
No explicit limits
```

These workloads receive the weakest resource guarantees.

---

### Burstable

Requests and/or limits are configured, but the Pod does not satisfy the Guaranteed requirements.

Example:

```yaml
requests:
  cpu: "100m"
  memory: "128Mi"

limits:
  cpu: "500m"
  memory: "256Mi"
```

This allows the workload to use more resources than its initial request when capacity is available.

---

### Guaranteed

CPU and memory requests match their corresponding limits.

Example:

```yaml
resources:
  requests:
    cpu: "500m"
    memory: "256Mi"

  limits:
    cpu: "500m"
    memory: "256Mi"
```

Conceptually:

```text
request == limit
```

This provides the strongest QoS classification.

---

## Current Backend QoS

The Booking API uses:

```text
Burstable
```

This provides a pragmatic balance between:

```text
minimum reserved resources
        +
ability to burst
```

---

## How Resource Values Should Be Chosen

Production resource values should not be selected arbitrarily.

A typical process is:

```text
Initial estimate
      |
      v
Deploy workload
      |
      v
Observe metrics
      |
      v
CPU / memory usage
      |
      v
P50 / P95 / peaks
      |
      v
adjust requests
      |
      v
adjust limits
```

Metrics should drive resource tuning.

---

## Useful Commands

Inspect resources:

```bash
kubectl describe pod <pod> -n booking-dev
```

Inspect Deployment configuration:

```bash
kubectl get deployment booking-api \
  -n booking-dev \
  -o jsonpath='{.spec.template.spec.containers[0].resources}'
```

Inspect ReplicaSets:

```bash
kubectl get rs -n booking-dev
```

Inspect Pod QoS:

```bash
kubectl describe pod <pod> -n booking-dev
```

---

## Key Learnings

- `requests` represent the resources Kubernetes uses for scheduling.
- `limits` define resource ceilings.
- CPU limits generally cause throttling.
- Memory limits can terminate the container.
- CPU is expressed in cores or millicores.
- Memory commonly uses `Mi` and `Gi`.
- Resource configuration modifies the Pod template and therefore triggers a rollout.
- Kubernetes assigns QoS classes based on requests and limits.
- `Burstable` is a common and pragmatic QoS class for application workloads.
- Production sizing should be based on observed metrics.

---

## Result

PLAT-007 completed successfully.

```text
✅ CPU request configured
✅ CPU limit configured
✅ Memory request configured
✅ Memory limit configured
✅ New ReplicaSet created
✅ New Pods deployed
✅ Resource values visible in Pod description
✅ QoS Class = Burstable
✅ CPU throttling behaviour understood
✅ Memory limit behaviour understood
```

The Booking API now declares explicit resource requirements and boundaries suitable for Kubernetes scheduling and runtime resource management.
