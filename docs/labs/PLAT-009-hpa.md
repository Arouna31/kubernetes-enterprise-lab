# PLAT-009 — Horizontal Pod Autoscaling

## Objective

Configure automatic horizontal scaling for `booking-api` based on CPU utilization.

The workload must:

- run with at least 2 replicas
- scale up to a maximum of 5 replicas
- target an average CPU utilization of 60%
- scale back down when load decreases

---

## Prerequisites

The Booking API already defines CPU requests:

```yaml
resources:
  requests:
    cpu: "100m"
    memory: "128Mi"

  limits:
    cpu: "500m"
    memory: "256Mi"
```

CPU requests are important because the HPA CPU utilization percentage is calculated relative to the configured CPU request.

For example:

```text
CPU request = 100m

Actual usage = 60m

60m / 100m = 60%
```

---

## Metrics API Requirement

Before creating the HPA, the cluster metrics pipeline was tested:

```bash
kubectl top pods -n booking-dev
kubectl top nodes
```

Initially:

```text
error: Metrics API not available
```

The cluster therefore required Metrics Server.

---

## Metrics Server

Metrics Server exposes Kubernetes resource metrics through:

```text
metrics.k8s.io
```

The data flow is:

```text
Kubelet
   |
   v
Metrics Server
   |
   v
Metrics API
   |
   +--> kubectl top
   |
   +--> HorizontalPodAutoscaler
```

Metrics Server is intended for Kubernetes autoscaling metrics and is not a replacement for a complete monitoring platform such as Prometheus.

---

## Metrics Validation

After Metrics Server was configured, the following commands succeeded:

```bash
kubectl top nodes
kubectl top pods -n booking-dev
```

Example:

```text
NAME                         CPU(cores)   MEMORY(bytes)
booking-api-xxxx             8m           150Mi
booking-api-yyyy             10m          147Mi
```

This confirmed that the Metrics API was available.

---

## HPA Configuration

The following manifest was created:

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler

metadata:
  name: booking-api
  namespace: booking-dev

spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: booking-api

  minReplicas: 2
  maxReplicas: 5

  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 60
```

Applied with:

```bash
kubectl apply -f k8s/base/booking-api-hpa.yml
```

---

## HPA Validation

The HPA was inspected using:

```bash
kubectl get hpa -n booking-dev
```

Example:

```text
NAME          REFERENCE                TARGETS     MINPODS   MAXPODS   REPLICAS
booking-api   Deployment/booking-api   8%/60%      2         5         2
```

The HPA configuration was also inspected with:

```bash
kubectl describe hpa booking-api -n booking-dev
```

---

## CPU Utilization Target

The configured target is:

```text
60%
```

With:

```text
CPU request = 100m
```

the approximate relationship is:

```text
20m CPU usage
= 20%

60m CPU usage
= 60%

100m CPU usage
= 100%
```

The HPA therefore evaluates workload CPU usage relative to the configured request rather than relative to the physical CPU capacity of the machine.

---

## Load Test

A temporary workload was created to generate HTTP traffic against the Booking API.

```yaml
apiVersion: v1
kind: Pod

metadata:
  name: booking-api-load
  namespace: booking-dev

spec:
  restartPolicy: Never

  containers:
    - name: load-generator
      image: busybox:1.37

      command:
        - /bin/sh
        - -c
        - |
          while true; do
            for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
              wget -q -O- http://booking-api:8080/api/hotels > /dev/null &
            done

            wait
          done
```

Applied with:

```bash
kubectl apply -f k8s/scenarios/cpu-load.yml
```

---

## Observing Autoscaling

The HPA was monitored with:

```bash
kubectl get hpa -n booking-dev -w
```

Observed values included:

```text
cpu: 274%/60%   replicas: 4
cpu: 204%/60%   replicas: 5
cpu: 187%/60%   replicas: 5
cpu: 199%/60%   replicas: 5
```

This demonstrated that the workload was consuming significantly more CPU than the configured target.

---

## HPA Scaling Logic

The HPA approximately calculates:

```text
desiredReplicas =
currentReplicas × currentMetric / targetMetric
```

Example:

```text
current replicas = 2
current CPU = 110%
target CPU = 60%

2 × 110 / 60
≈ 3.67
```

Kubernetes may therefore request approximately:

```text
4 replicas
```

The HPA does not simply add one replica whenever the threshold is crossed.

---

## Maximum Replica Limit

The HPA was configured with:

```yaml
maxReplicas: 5
```

During the test, CPU remained far above the target even after reaching five replicas.

Example:

```text
cpu: 199%/60%
replicas: 5
```

The HPA could not scale further because:

```text
current replicas = maxReplicas
```

This demonstrates that `maxReplicas` acts as an explicit scaling boundary.

---

## Scale Down

After stopping the load test:

```bash
kubectl delete -f k8s/scenarios/cpu-load.yml
```

CPU utilization decreased.

The HPA progressively reduced the number of replicas back toward:

```text
minReplicas = 2
```

The reduction was not immediate.

This prevents aggressive oscillations between replica counts.

---

## Scale-Up vs Scale-Down

Conceptually:

```text
Traffic increases
      |
      v
CPU rises
      |
      v
HPA scales quickly
      |
      v
more Pods
```

When traffic decreases:

```text
Traffic decreases
      |
      v
CPU drops
      |
      v
stabilization
      |
      v
replicas reduced
```

This helps prevent scaling flapping.

---

## Understanding AGE

The HPA output contained:

```text
AGE
5d5h
```

This value represents the age of the HPA resource itself.

It does not represent:

```text
duration of the current load test
duration of the current scaling event
age of the current Pods
```

The creation timestamp can be inspected using:

```bash
kubectl get hpa booking-api \
  -n booking-dev \
  -o jsonpath='{.metadata.creationTimestamp}'
```

Updating the HPA with:

```bash
kubectl apply
```

does not recreate the resource, so its age continues increasing.

---

## Deployment Replicas and HPA

When the HPA manages a Deployment, it dynamically changes:

```text
Deployment.spec.replicas
```

This introduces a potential conflict if the Deployment manifest also explicitly defines:

```yaml
replicas: 2
```

Conceptually:

```text
Deployment YAML
replicas = 2

       VS

HPA
replicas = dynamic
```

In a mature GitOps setup, ownership of this field must be handled carefully to avoid controllers repeatedly modifying the same value.

---

## Useful Commands

```bash
kubectl top nodes

kubectl top pods -n booking-dev

kubectl get hpa -n booking-dev

kubectl get hpa -n booking-dev -w

kubectl describe hpa booking-api -n booking-dev

kubectl get pods -n booking-dev -w

kubectl get deployment booking-api -n booking-dev
```

---

## Key Learnings

- HPA requires resource metrics.
- Metrics Server provides the standard Kubernetes resource metrics pipeline.
- CPU utilization is calculated relative to CPU requests.
- Incorrect CPU requests can lead to poor autoscaling decisions.
- HPA can scale multiple replicas at once.
- `minReplicas` and `maxReplicas` constrain scaling.
- Scale-down is intentionally conservative.
- An HPA resource can be old while its metrics and replica count change continuously.
- The HPA controls `Deployment.spec.replicas`.
- Metrics Server is not a full observability solution.

---

## Result

```text
✅ Metrics API enabled
✅ kubectl top operational
✅ HPA created
✅ minReplicas = 2
✅ maxReplicas = 5
✅ CPU target = 60%
✅ Load generator deployed
✅ CPU utilization increased
✅ Scale-up observed
✅ Five replicas reached
✅ maxReplicas boundary observed
✅ Load removed
✅ Scale-down observed
✅ AGE behaviour understood
```

PLAT-009 completed successfully.
